package kr.attendance.q52;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.nfc.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {
  private Store db;
  private NfcAdapter nfc;
  private LinearLayout layout, menuPanel;
  private Button arrivalButton, departureButton;
  private View menuToggle;
  private static final int BACKGROUND = Color.rgb(32, 32, 32);
  private TextView result, state;
  private String selection = null, registration = null;
  private long selectedAt = 0, lastTagAt = -1500, lockedUntil = 0;
  private int failures = 0;
  private boolean adminOpen = false;
  private final Handler handler = new Handler();
  private final Runnable refresh =
      new Runnable() {
        public void run() {
          if (state != null)
            state.setText(
                "미전송 "
                    + db.pendingCount()
                    + "건\nPC 연결 상태 · "
                    + (SyncService.running ? SyncService.status : "연결 중지됨"));
          if (selection != null && SystemClock.elapsedRealtime() - selectedAt >= 30000) {
            selection = null;
            message("선택 시간이 지났습니다. 다시 선택하세요.");
          }
          handler.postDelayed(this, 2000);
        }
      };

  public void onCreate(Bundle b) {
    super.onCreate(b);
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
            != android.content.pm.PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 1);
    db = new Store(this);
    nfc = NfcAdapter.getDefaultAdapter(this);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    home();
    handler.post(refresh);
    if (!SyncService.prefs(this).getString("url", "").isEmpty())
      startForegroundService(new Intent(this, SyncService.class));
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private GradientDrawable outline(int fill, int width) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(fill);
    drawable.setCornerRadius(dp(3));
    drawable.setStroke(dp(width), Color.WHITE);
    return drawable;
  }

  private TextView label(String value, int size) {
    TextView text = new TextView(this);
    text.setText(value);
    text.setTextColor(Color.WHITE);
    text.setTextSize(size);
    return text;
  }

  private void home() {
    getWindow().setStatusBarColor(BACKGROUND);
    getWindow().setNavigationBarColor(BACKGROUND);
    getWindow().getDecorView().setSystemUiVisibility(0);
    FrameLayout screen = new FrameLayout(this);
    screen.setBackgroundColor(BACKGROUND);
    layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setBackground(outline(BACKGROUND, 4));
    layout.setPadding(dp(4), dp(4), dp(4), dp(4));
    FrameLayout.LayoutParams border = new FrameLayout.LayoutParams(-1, -1);
    border.setMargins(dp(20), dp(16), dp(20), dp(16));
    screen.addView(layout, border);
    setContentView(screen);

    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(14), 0, dp(8), 0);
    TextView title = label("출퇴근", 23);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    header.addView(title, new LinearLayout.LayoutParams(0, dp(58), 1));
    title.setGravity(Gravity.CENTER_VERTICAL);
    menuToggle =
        new View(this) {
          private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

          @Override
          protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(dp(3));
            paint.setStrokeCap(Paint.Cap.ROUND);
            float center = getHeight() / 2f;
            for (int offset = -1; offset <= 1; offset++)
              canvas.drawLine(
                  dp(12),
                  center + dp(8) * offset,
                  getWidth() - dp(12),
                  center + dp(8) * offset,
                  paint);
          }
        };
    menuToggle.setContentDescription("메뉴 열기");
    menuToggle.setBackground(
        new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), null, outline(Color.WHITE, 0)));
    menuToggle.setOnClickListener(v -> toggleMenu());
    menuToggle.setFocusable(true);
    header.addView(menuToggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
    layout.addView(header);
    View divider = new View(this);
    divider.setBackgroundColor(Color.WHITE);
    layout.addView(divider, new LinearLayout.LayoutParams(-1, dp(4)));

    FrameLayout body = new FrameLayout(this);
    layout.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(false);
    body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout actions = new LinearLayout(this);
    actions.setOrientation(LinearLayout.VERTICAL);
    actions.setPadding(dp(18), dp(16), dp(18), dp(8));
    actions.setMinimumHeight(dp(400));
    scroll.addView(actions, new ScrollView.LayoutParams(-1, -1));
    arrivalButton = attendanceButton("출근");
    departureButton = attendanceButton("퇴근");
    LinearLayout.LayoutParams arrival = new LinearLayout.LayoutParams(-1, 0, 1);
    arrival.bottomMargin = dp(18);
    actions.addView(arrivalButton, arrival);
    actions.addView(departureButton, new LinearLayout.LayoutParams(-1, 0, 1));
    result = label("출근 또는 퇴근을 선택하세요.", 18);
    result.setGravity(Gravity.CENTER);
    result.setPadding(0, dp(12), 0, dp(4));
    result.setMinHeight(dp(84));
    result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    actions.addView(result, new LinearLayout.LayoutParams(-1, -2));

    menuPanel = new LinearLayout(this);
    menuPanel.setOrientation(LinearLayout.VERTICAL);
    menuPanel.setPadding(dp(14), dp(12), dp(14), dp(14));
    menuPanel.setBackground(outline(BACKGROUND, 2));
    menuPanel.setElevation(dp(12));
    menuPanel.setVisibility(View.GONE);
    menuPanel.setClickable(true);
    state = label("", 16);
    state.setPadding(dp(4), dp(4), dp(4), dp(12));
    menuPanel.addView(state);
    menuButton(
        "PC로 지금 전송",
        () -> {
          startForegroundService(new Intent(this, SyncService.class).setAction("SEND"));
          message("PC로 전송을 요청했습니다.");
        });
    menuButton(
        "관리자",
        () -> {
          closeMenu();
          login();
        });
    FrameLayout.LayoutParams panel = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
    panel.setMargins(dp(10), dp(8), dp(10), 0);
    body.addView(menuPanel, panel);
    if (nfc == null) message("이 기기는 NFC를 지원하지 않습니다.");
    else if (!nfc.isEnabled()) message("설정에서 NFC를 켜주세요.");
  }

  private Button attendanceButton(String title) {
    Button button = new Button(this);
    button.setText(title);
    button.setTextColor(Color.WHITE);
    button.setTextSize(52);
    button.setAllCaps(false);
    button.setGravity(Gravity.CENTER);
    button.setPadding(dp(8), dp(8), dp(8), dp(8));
    button.setMinimumHeight(dp(120));
    button.setAutoSizeTextTypeUniformWithConfiguration(
        32, 52, 2, android.util.TypedValue.COMPLEX_UNIT_SP);
    button.setBackground(outline(BACKGROUND, 4));
    button.setOnClickListener(v -> select(title));
    return button;
  }

  private void menuButton(String text, Runnable action) {
    Button button = new Button(this);
    button.setText(text);
    button.setTextColor(Color.WHITE);
    button.setTextSize(18);
    button.setBackground(outline(BACKGROUND, 1));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
    params.topMargin = dp(8);
    menuPanel.addView(button, params);
    button.setOnClickListener(v -> action.run());
  }

  private void toggleMenu() {
    if (menuPanel.getVisibility() == View.VISIBLE) {
      closeMenu();
      return;
    }
    selection = null;
    message("출근 또는 퇴근을 선택하세요.");
    menuPanel.setVisibility(View.VISIBLE);
    menuToggle.setContentDescription("메뉴 닫기");
  }

  private void closeMenu() {
    menuPanel.setVisibility(View.GONE);
    menuToggle.setContentDescription("메뉴 열기");
  }

  @Override
  public void onBackPressed() {
    if (menuPanel.getVisibility() == View.VISIBLE) closeMenu();
    else super.onBackPressed();
  }

  private void select(String s) {
    closeMenu();
    selection = s;
    selectedAt = SystemClock.elapsedRealtime();
    message(s + " 선택됨\n30초 이내 태그하세요.");
  }

  private void message(String s) {
    result.setText(s);
    if (arrivalButton != null) {
      arrivalButton.setSelected("출근".equals(selection));
      arrivalButton.setBackground(
          outline(arrivalButton.isSelected() ? Color.rgb(60, 60, 60) : BACKGROUND, 4));
      departureButton.setSelected("퇴근".equals(selection));
      departureButton.setBackground(
          outline(departureButton.isSelected() ? Color.rgb(60, 60, 60) : BACKGROUND, 4));
    }
  }

  protected void onResume() {
    super.onResume();
    if (!SyncService.running && !SyncService.prefs(this).getString("url", "").isEmpty())
      startForegroundService(new Intent(this, SyncService.class));
    if (nfc != null)
      nfc.enableReaderMode(
          this,
          this,
          NfcAdapter.FLAG_READER_NFC_A
              | NfcAdapter.FLAG_READER_NFC_B
              | NfcAdapter.FLAG_READER_NFC_F
              | NfcAdapter.FLAG_READER_NFC_V
              | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
          null);
  }

  protected void onPause() {
    super.onPause();
    if (nfc != null) nfc.disableReaderMode(this);
    selection = null;
    registration = null;
  }

  public void onTagDiscovered(Tag tag) {
    byte[] bytes = tag.getId();
    StringBuilder uid = new StringBuilder();
    for (byte b : bytes) uid.append(String.format(Locale.ROOT, "%02X", b));
    runOnUiThread(
        () -> {
          if (uid.length() == 0) {
            message("고정 식별값이 없는 태그입니다.");
            return;
          }
          long now = SystemClock.elapsedRealtime();
          if (now - lastTagAt < 1500) return;
          lastTagAt = now;
          try {
            if (registration != null) {
              String name = registration;
              registration = null;
              message(db.register(uid.toString(), name));
              return;
            }
            if (adminOpen) return;
            if (selection == null || now - selectedAt > 30000) {
              selection = null;
              message("먼저 출근 또는 퇴근을 선택하세요.");
              return;
            }
            String kind = selection;
            selection = null;
            message(db.record(uid.toString(), kind));
          } catch (Exception e) {
            message("저장 실패. 다시 시도하거나 관리자에게 문의하세요.");
          }
        });
  }

  private EditText field(LinearLayout box, String hint, String value, boolean secret) {
    EditText e = new EditText(this);
    e.setHint(hint);
    e.setText(value);
    e.setSingleLine(true);
    if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    box.addView(e);
    return e;
  }

  private LinearLayout form() {
    LinearLayout b = new LinearLayout(this);
    b.setPadding(24, 8, 24, 8);
    b.setOrientation(LinearLayout.VERTICAL);
    return b;
  }

  private String hash(String s) {
    try {
      byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void login() {
    selection = null;
    if (SystemClock.elapsedRealtime() < lockedUntil) {
      message("PIN 오류가 반복되어 30초 후 다시 시도하세요.");
      return;
    }
    boolean first = SyncService.prefs(this).getString("pin", "").isEmpty();
    LinearLayout f = form();
    EditText p = field(f, "관리자 PIN (6자리 이상)", "", true);
    EditText confirm = first ? field(f, "PIN 다시 입력", "", true) : null;
    new AlertDialog.Builder(this)
        .setTitle(first ? "최초 관리자 PIN 설정" : "관리자 로그인")
        .setView(f)
        .setNegativeButton("취소", null)
        .setPositiveButton(
            "확인",
            (d, w) -> {
              String pin = p.getText().toString();
              if (first) {
                if (!pin.matches("[0-9]{6,}") || !pin.equals(confirm.getText().toString())) {
                  message("6자리 이상 숫자 PIN을 동일하게 두 번 입력하세요.");
                  return;
                }
                String salt = UUID.randomUUID().toString();
                SyncService.prefs(this)
                    .edit()
                    .putString("salt", salt)
                    .putString("pin", hash(salt + pin))
                    .apply();
              } else if (!hash(SyncService.prefs(this).getString("salt", "") + pin)
                  .equals(SyncService.prefs(this).getString("pin", ""))) {
                failures++;
                if (failures >= 5) {
                  lockedUntil = SystemClock.elapsedRealtime() + 30000;
                  failures = 0;
                }
                message("PIN이 일치하지 않습니다.");
                return;
              }
              failures = 0;
              adminMenu();
            })
        .show();
  }

  private void adminMenu() {
    adminOpen = true;
    String[] items = {
      "직원 / 스티커 등록",
      "등록된 태그 해제",
      "PC 연결 설정",
      "최근 기록 / 시간 정정",
      "전체 기록 재전송 준비",
      "PC 연결 중지",
      "누락 기록 수동 입력"
    };
    AlertDialog dlg =
        new AlertDialog.Builder(this)
            .setTitle("관리자")
            .setItems(
                items,
                (d, w) -> {
                  switch (w) {
                    case 0:
                      register();
                      break;
                    case 1:
                      tags();
                      break;
                    case 2:
                      connection();
                      break;
                    case 3:
                      records();
                      break;
                    case 4:
                      db.getWritableDatabase().execSQL("UPDATE records SET dirty=1");
                      message("전체 기록을 미전송으로 표시했습니다. 지금 전송을 눌러주세요.");
                      break;
                    case 5:
                      stopService(new Intent(this, SyncService.class));
                      break;
                    case 6:
                      manual();
                      break;
                  }
                })
            .setNegativeButton("닫기", null)
            .create();
    dlg.setOnDismissListener(d -> adminOpen = false);
    dlg.show();
  }

  private void register() {
    LinearLayout f = form();
    EditText name = field(f, "직원 이름 (동명이인은 구분 표기)", "", false);
    new AlertDialog.Builder(this)
        .setTitle("직원 스티커 등록")
        .setMessage("같은 이름은 같은 직원으로 연결됩니다. 이름 입력 후 60초 안에 스티커를 찍으세요.")
        .setView(f)
        .setNegativeButton("취소", null)
        .setPositiveButton(
            "태그 읽기",
            (d, w) -> {
              registration = name.getText().toString().trim();
              message("등록할 스티커를 대주세요. (60초)");
              handler.postDelayed(
                  () -> {
                    if (registration != null) {
                      registration = null;
                      message("등록 시간이 끝났습니다.");
                    }
                  },
                  60000);
            })
        .show();
  }

  private void tags() {
    List<String[]> rows = db.tags();
    String[] names = new String[rows.size()];
    for (int i = 0; i < names.length; i++) names[i] = rows.get(i)[1] + " · " + rows.get(i)[0];
    new AlertDialog.Builder(this)
        .setTitle("분실·교체 태그 해제")
        .setItems(
            names,
            (d, w) ->
                new AlertDialog.Builder(this)
                    .setMessage(names[w] + " 태그를 해제할까요? 근태 기록은 유지됩니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton(
                        "해제",
                        (a, b) -> {
                          db.removeTag(rows.get(w)[0]);
                          message("태그 해제 완료");
                        })
                    .show())
        .show();
  }

  private void connection() {
    LinearLayout f = form();
    String oldUrl = SyncService.prefs(this).getString("url", "");
    EditText url = field(f, "http://100.x.x.x:8765", oldUrl, false);
    EditText code = field(f, "PC에 표시된 숫자 6자리", "", false);
    code.setInputType(InputType.TYPE_CLASS_NUMBER);
    code.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(6)});
    new AlertDialog.Builder(this)
        .setTitle("PC 연결")
        .setMessage("PC 수신기의 ‘새 연결 코드’를 누르고 숫자 6자리를 입력하세요. 이미 연결된 PC라면 코드를 비워두어도 됩니다.")
        .setView(f)
        .setNegativeButton("취소", null)
        .setPositiveButton(
            "연결",
            (d, w) -> {
              String u = url.getText().toString().trim(), c = code.getText().toString().trim();
              if (!SyncService.validUrl(u)) {
                message("PC의 Tailscale 주소를 확인하세요.");
                return;
              }
              if (c.isEmpty()
                  && u.equals(oldUrl)
                  && !SyncService.prefs(this).getString("token", "").isEmpty()) {
                startForegroundService(new Intent(this, SyncService.class).setAction("SEND"));
                return;
              }
              if (!c.matches("[0-9]{6}")) {
                message("PC에 표시된 숫자 6자리를 입력하세요.");
                return;
              }
              message("PC에 연결 중…");
              new Thread(
                      () -> {
                        try {
                          String token = SyncService.pair(u, c);
                          runOnUiThread(
                              () -> {
                                SyncService.prefs(this)
                                    .edit()
                                    .putString("url", u)
                                    .putString("token", token)
                                    .apply();
                                if (!isFinishing() && !isDestroyed() && hasWindowFocus()) {
                                  startForegroundService(
                                      new Intent(this, SyncService.class).setAction("SEND"));
                                  message("PC 연결 완료. 다음부터 자동으로 연결됩니다.");
                                }
                              });
                        } catch (Exception e) {
                          runOnUiThread(
                              () -> {
                                if (!isDestroyed())
                                  message("연결 실패. PC 주소·Tailscale 연결을 확인하고 새 코드를 발급해 다시 입력하세요.");
                              });
                        }
                      })
                  .start();
            })
        .show();
  }

  private void manual() {
    LinearLayout f = form();
    EditText name = field(f, "등록된 직원 이름", "", false);
    EditText time = field(f, "2026-10-08T09:00:00", "", false);
    Spinner kind = new Spinner(this);
    kind.setAdapter(
        new ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_dropdown_item, new String[] {"출근", "퇴근"}));
    f.addView(kind);
    EditText reason = field(f, "입력 사유", "", false);
    new AlertDialog.Builder(this)
        .setTitle("누락 기록 입력")
        .setView(f)
        .setNegativeButton("취소", null)
        .setPositiveButton(
            "저장",
            (d, w) -> {
              try {
                message(
                    db.addManual(
                        name.getText().toString(),
                        time.getText().toString(),
                        kind.getSelectedItem().toString(),
                        reason.getText().toString()));
              } catch (Exception e) {
                message("저장 실패. 입력값을 확인하세요.");
              }
            })
        .show();
  }

  private void records() {
    List<String[]> rows = db.recent();
    String[] names = new String[rows.size()];
    for (int i = 0; i < names.length; i++)
      names[i] = rows.get(i)[1] + " " + rows.get(i)[3] + " " + rows.get(i)[2];
    new AlertDialog.Builder(this)
        .setTitle("최근 100건 · 선택하면 시간 정정")
        .setItems(
            names,
            (d, w) -> {
              String[] r = rows.get(w);
              LinearLayout f = form();
              EditText time =
                  field(
                      f,
                      "2026-10-08T09:00:00",
                      java.time.OffsetDateTime.parse(r[2]).toLocalDateTime().toString(),
                      false);
              EditText reason = field(f, "정정 사유", "", false);
              new AlertDialog.Builder(this)
                  .setTitle(r[1] + " " + r[3])
                  .setView(f)
                  .setNegativeButton("취소", null)
                  .setPositiveButton(
                      "정정",
                      (a, b) ->
                          message(
                              db.correct(
                                  r[0], time.getText().toString(), reason.getText().toString())))
                  .show();
            })
        .show();
  }

  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    db.close();
    super.onDestroy();
  }
}
