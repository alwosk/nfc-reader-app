package kr.attendance.q52;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, qualifiers = "w400dp-h760dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MainActivityTest {
  ActivityController<MainActivity> controller;
  MainActivity activity;

  @Before
  public void setup() {
    SyncService.prefs(RuntimeEnvironment.getApplication()).edit().clear().commit();
    Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
        .setSystemFeature(android.content.pm.PackageManager.FEATURE_NFC, true);
    org.robolectric.shadows.ShadowNfcAdapter.setNfcHardwareExists(true);
    Shadows.shadowOf(android.nfc.NfcAdapter.getDefaultAdapter(RuntimeEnvironment.getApplication()))
        .setEnabled(true);
    controller = Robolectric.buildActivity(MainActivity.class).setup().visible();
    activity = controller.get();
    layout();
  }

  @After
  public void finish() {
    controller.pause().stop().destroy();
  }

  View root() {
    return activity.findViewById(android.R.id.content);
  }

  void layout() {
    root()
        .measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(760, View.MeasureSpec.EXACTLY));
    root().layout(0, 0, 400, 760);
  }

  View find(View node, String text) {
    if (node instanceof TextView && text.equals(((TextView) node).getText().toString()))
      return node;
    if (text.equals(node.getContentDescription())) return node;
    if (node instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) node).getChildCount(); i++) {
        View found = find(((ViewGroup) node).getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  void screenshot(String name) throws Exception {
    layout();
    Bitmap bitmap = Bitmap.createBitmap(400, 760, Bitmap.Config.ARGB_8888);
    root().draw(new Canvas(bitmap));
    File folder = new File("build/reports/ui");
    folder.mkdirs();
    try (FileOutputStream out = new FileOutputStream(new File(folder, name + ".png"))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
  }

  @Test
  public void menuStartsHiddenAndTogglesActions() throws Exception {
    assertNotNull(find(root(), "출근"));
    assertNotNull(find(root(), "퇴근"));
    assertFalse(find(root(), "PC로 지금 전송").isShown());
    assertFalse(find(root(), "관리자").isShown());
    screenshot("home");
    find(root(), "메뉴 열기").performClick();
    layout();
    assertTrue(find(root(), "PC로 지금 전송").isShown());
    assertTrue(find(root(), "관리자").isShown());
    screenshot("menu");
    activity.onBackPressed();
    assertFalse(find(root(), "관리자").isShown());
  }

  @Test
  public void selectionMessageAppearsBelowButtonsAndExpires() throws Exception {
    find(root(), "출근").performClick();
    layout();
    View message = find(root(), "출근 선택됨\n30초 이내 태그하세요.");
    assertNotNull(message);
    assertTrue(message.getTop() >= find(root(), "퇴근").getBottom());
    screenshot("selected");
    Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(32));
    assertNotNull(find(root(), "선택 시간이 지났습니다. 다시 선택하세요."));
  }

  @Test
  public void sameButtonCancelsAndOtherButtonSwitchesSelection() {
    View arrival = find(root(), "출근");
    View departure = find(root(), "퇴근");
    arrival.performClick();
    assertTrue(arrival.isSelected());
    arrival.performClick();
    assertFalse(arrival.isSelected());
    assertNotNull(find(root(), "선택이 해제됐습니다.\n출근 또는 퇴근을 선택하세요."));
    departure.performClick();
    assertTrue(departure.isSelected());
    departure.performClick();
    assertFalse(departure.isSelected());
    arrival.performClick();
    departure.performClick();
    assertFalse(arrival.isSelected());
    assertTrue(departure.isSelected());
    Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(32));
    assertFalse(departure.isSelected());
  }
}
