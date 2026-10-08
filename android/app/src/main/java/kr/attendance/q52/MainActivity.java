package kr.attendance.q52;

import android.app.*;
import android.content.*;
import android.graphics.Color;
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
  private LinearLayout layout;
  private TextView result, state;
  private String selection = null, registration = null;
  private long selectedAt = 0, lastTagAt = 0, lockedUntil = 0;
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
                    + "건 · "
                    + (SyncService.running ? SyncService.status : "연결 중지됨"));
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

  private void home() {
    layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(28, 28, 28, 28);
    layout.setBackgroundColor(Color.rgb(244, 247, 252));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(layout);
    setContentView(scroll);
    TextView title = text("출퇴근", 30);
    title.setTextColor(Color.rgb(25, 57, 95));
    text("출근 또는 퇴근을 선택한 뒤\n등록된 NFC 스티커를 대주세요.", 20);
    button("출근", () -> select("출근"));
    button("퇴근", () -> select("퇴근"));
    result = text("태그 대기", 24);
    state = text("", 14);
    button(
        "PC로 지금 전송",
        () -> {
          startForegroundService(new Intent(this, SyncService.class).setAction("SEND"));
          message("전송을 요청했습니다. 아래 상태를 확인하세요.");
        });
    button("관리자", this::login);
    if (nfc == null) message("이 기기는 NFC를 지원하지 않습니다.");
    else if (!nfc.isEnabled()) message("설정에서 NFC를 켜주세요.");
  }

  private TextView text(String s, int size) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setPadding(0, 16, 0, 16);
    layout.addView(t);
    return t;
  }

  private void button(String s, Runnable r) {
    Button b = new Button(this);
    b.setText(s);
    b.setTextSize(20);
    b.setMinHeight(76);
    layout.addView(b);
    b.setOnClickListener(v -> r.run());
  }

  private void select(String s) {
    selection = s;
    selectedAt = System.currentTimeMillis();
    message(s + " 선택됨 · 30초 이내 태그하세요.");
  }

  private void message(String s) {
    result.setText(s);
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
          long now = System.currentTimeMillis();
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
    if (System.currentTimeMillis() < lockedUntil) {
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
                  lockedUntil = System.currentTimeMillis() + 30000;
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
