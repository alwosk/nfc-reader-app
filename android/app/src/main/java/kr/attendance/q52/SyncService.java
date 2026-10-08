package kr.attendance.q52;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

public class SyncService extends Service {
  private ScheduledExecutorService executor;
  private long lastAttempt = 0;
  static volatile String status = "전송 대기";
  static volatile boolean running = false;

  static android.content.SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("settings", 0);
  }

  static boolean validUrl(String value) {
    try {
      URL u = new URL(value);
      String[] a = u.getHost().split("\\.");
      return u.getProtocol().equals("http")
          && a.length == 4
          && Integer.parseInt(a[0]) == 100
          && Integer.parseInt(a[1]) >= 64
          && Integer.parseInt(a[1]) <= 127
          && Integer.parseInt(a[2]) >= 0
          && Integer.parseInt(a[2]) <= 255
          && Integer.parseInt(a[3]) >= 0
          && Integer.parseInt(a[3]) <= 255
          && u.getUserInfo() == null
          && u.getQuery() == null
          && u.getRef() == null
          && (u.getPath().isEmpty() || u.getPath().equals("/"))
          && u.getPort() > 0;
    } catch (Exception e) {
      return false;
    }
  }

  public void onCreate() {
    super.onCreate();
    running = true;
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel("sync", "PC 연결", NotificationManager.IMPORTANCE_LOW));
    PendingIntent pi =
        PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    startForeground(
        1,
        new Notification.Builder(this, "sync")
            .setContentTitle("출퇴근 연결 중")
            .setContentText("PC 요청 확인 및 미전송 기록 보관")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(pi)
            .build());
    executor = Executors.newSingleThreadScheduledExecutor();
    executor.scheduleWithFixedDelay(() -> tick(false), 2, 10, TimeUnit.SECONDS);
  }

  public int onStartCommand(Intent i, int flags, int id) {
    if (i != null && "SEND".equals(i.getAction())) executor.execute(() -> tick(true));
    return START_STICKY;
  }

  static String pair(String base, String code) throws Exception {
    if (!validUrl(base) || !code.matches("[0-9]{6}")) throw new IOException("연결 정보 확인");
    HttpURLConnection c =
        (HttpURLConnection) new URL(base.replaceAll("/$", "") + "/pair").openConnection();
    c.setConnectTimeout(5000);
    c.setReadTimeout(10000);
    c.setInstanceFollowRedirects(false);
    c.setRequestMethod("POST");
    c.setDoOutput(true);
    c.setRequestProperty("Content-Type", "application/json");
    try {
      byte[] bytes = new JSONObject().put("code", code).toString().getBytes(StandardCharsets.UTF_8);
      c.setFixedLengthStreamingMode(bytes.length);
      try (OutputStream out = c.getOutputStream()) {
        out.write(bytes);
      }
      if (c.getResponseCode() != 200) throw new IOException("연결 코드 확인");
      try (InputStream in = c.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] b = new byte[1024];
        int n;
        while ((n = in.read(b)) != -1) {
          out.write(b, 0, n);
          if (out.size() > 4096) throw new IOException("응답 크기 초과");
        }
        String token = new JSONObject(out.toString("UTF-8")).getString("token");
        if (token.length() < 32) throw new IOException("연결 응답 확인");
        return token;
      }
    } finally {
      c.disconnect();
    }
  }

  private JSONObject request(String path, JSONObject body) throws Exception {
    String base = prefs(this).getString("url", "");
    String token = prefs(this).getString("token", "");
    if (!validUrl(base) || token.length() < 32) throw new IOException("관리자 연결 설정을 확인하세요");
    HttpURLConnection c =
        (HttpURLConnection) new URL(base.replaceAll("/$", "") + path).openConnection();
    c.setConnectTimeout(5000);
    c.setReadTimeout(10000);
    c.setInstanceFollowRedirects(false);
    c.setRequestProperty("Authorization", "Bearer " + token);
    try {
      if (body != null) {
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) {
          out.write(bytes);
        }
      }
      if (c.getResponseCode() != 200) throw new IOException("PC 응답 " + c.getResponseCode());
      try (InputStream in = c.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) != -1) {
          out.write(b, 0, n);
          if (out.size() > 1000000) throw new IOException("응답 크기 초과");
        }
        return new JSONObject(out.toString("UTF-8"));
      }
    } finally {
      c.disconnect();
    }
  }

  private void tick(boolean manual) {
    try (Store db = new Store(this)) {
      JSONObject ctrl = request("/control", null);
      long seq = ctrl.getLong("requested");
      boolean requested = seq > ctrl.getLong("completed");
      if (!manual && !requested && System.currentTimeMillis() - lastAttempt < 3600000) return;
      lastAttempt = System.currentTimeMillis();
      status = "전송 중…";
      int count = 0;
      for (int b = 0; b < 1000; b++) {
        JSONArray rows = db.pending();
        if (rows.length() == 0) break;
        JSONObject result = request("/records", new JSONObject().put("records", rows));
        if (result.getInt("accepted") != rows.length()) throw new IOException("PC 저장 확인 실패");
        db.acknowledge(rows);
        count += rows.length();
      }
      if (db.pendingCount() != 0) throw new IOException("남은 기록은 다음 시도에 전송합니다");
      request("/complete", new JSONObject().put("sequence", seq));
      status = "전송 완료 (" + count + "건) · " + java.time.LocalTime.now(Store.SEOUL).withNano(0);
    } catch (Exception e) {
      status = "PC 연결 대기 · 기록은 폰에 보관 (" + e.getMessage() + ")";
    }
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    status = "운영체제 연결 제한: 앱을 다시 열어 연결하세요.";
    stopSelf();
  }

  public IBinder onBind(Intent i) {
    return null;
  }

  public void onDestroy() {
    running = false;
    if (executor != null) executor.shutdownNow();
    super.onDestroy();
  }
}
