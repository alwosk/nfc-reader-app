package kr.attendance.q52;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.time.*;
import java.util.*;
import org.json.*;

final class Store extends SQLiteOpenHelper {
  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  Store(Context c) {
    super(c, "attendance.db", null, 1);
  }

  public void onCreate(SQLiteDatabase d) {
    d.execSQL("CREATE TABLE employees(id TEXT PRIMARY KEY,name TEXT NOT NULL UNIQUE)");
    d.execSQL("CREATE TABLE tags(uid TEXT PRIMARY KEY,employee TEXT NOT NULL)");
    d.execSQL(
        "CREATE TABLE records(id TEXT PRIMARY KEY,employee TEXT NOT NULL,name TEXT NOT NULL,time"
            + " TEXT NOT NULL,day TEXT NOT NULL,kind TEXT NOT NULL,revision INTEGER NOT NULL,dirty"
            + " INTEGER NOT NULL,reason TEXT NOT NULL,UNIQUE(employee,day,kind))");
    d.execSQL(
        "CREATE TABLE audit(id INTEGER PRIMARY KEY,record TEXT,old_time TEXT,new_time TEXT,reason"
            + " TEXT)");
  }

  public void onUpgrade(SQLiteDatabase d, int a, int b) {
    throw new IllegalStateException("지원되지 않는 DB 업그레이드");
  }

  synchronized String register(String uid, String name) {
    name = name.trim();
    if (name.isEmpty() || name.length() > 40) return "이름은 1~40자로 입력하세요.";
    SQLiteDatabase d = getWritableDatabase();
    try (Cursor c = d.rawQuery("SELECT employee FROM tags WHERE uid=?", new String[] {uid})) {
      if (c.moveToFirst()) return "이미 등록된 태그입니다. 분실·교체 시 기존 태그를 해제하세요.";
    }
    String id = null;
    try (Cursor c = d.rawQuery("SELECT id FROM employees WHERE name=?", new String[] {name})) {
      if (c.moveToFirst()) id = c.getString(0);
    }
    d.beginTransaction();
    try {
      if (id == null) {
        id = UUID.randomUUID().toString();
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("name", name);
        d.insertOrThrow("employees", null, v);
      }
      ContentValues t = new ContentValues();
      t.put("uid", uid);
      t.put("employee", id);
      d.insertOrThrow("tags", null, t);
      d.setTransactionSuccessful();
      return name + " 등록 완료";
    } finally {
      d.endTransaction();
    }
  }

  synchronized String record(String uid, String kind) {
    SQLiteDatabase d = getWritableDatabase();
    String id, name;
    try (Cursor c =
        d.rawQuery(
            "SELECT e.id,e.name FROM tags t JOIN employees e ON t.employee=e.id WHERE t.uid=?",
            new String[] {uid})) {
      if (!c.moveToFirst()) return "등록되지 않은 태그입니다. 관리자에게 문의하세요.";
      id = c.getString(0);
      name = c.getString(1);
    }
    ZonedDateTime now = ZonedDateTime.now(SEOUL);
    String day = now.toLocalDate().toString();
    try (Cursor c =
        d.rawQuery(
            "SELECT kind,time FROM records WHERE employee=? AND day=?", new String[] {id, day})) {
      boolean entered = false, exited = false;
      while (c.moveToNext()) {
        entered |= c.getString(0).equals("출근");
        exited |= c.getString(0).equals("퇴근");
        if (kind.equals("퇴근")
            && c.getString(0).equals("출근")
            && !now.toInstant().isAfter(OffsetDateTime.parse(c.getString(1)).toInstant()))
          return "출근보다 이른 시각입니다. Q52 시계를 확인하세요.";
      }
      if ((kind.equals("출근") && entered) || (kind.equals("퇴근") && exited))
        return name + ": 오늘 " + kind + " 기록이 이미 있습니다.";
      if (kind.equals("퇴근") && !entered) return "오늘 출근 기록이 없습니다. 관리자에게 확인하세요.";
    }
    ContentValues v = new ContentValues();
    v.put("id", UUID.randomUUID().toString());
    v.put("employee", id);
    v.put("name", name);
    v.put("time", now.withNano(0).toOffsetDateTime().toString());
    v.put("day", day);
    v.put("kind", kind);
    v.put("revision", 1);
    v.put("dirty", 1);
    v.put("reason", "");
    d.insertOrThrow("records", null, v);
    return name + " · " + kind + " 완료\n" + now.toLocalTime().withNano(0) + " (폰에 저장됨)";
  }

  synchronized JSONArray pending() throws JSONException {
    JSONArray out = new JSONArray();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,employee,name,time,kind,revision,reason FROM records WHERE dirty=1 ORDER"
                    + " BY time LIMIT 200",
                null)) {
      while (c.moveToNext()) {
        JSONObject r = new JSONObject();
        r.put("id", c.getString(0));
        r.put("employee_id", c.getString(1));
        r.put("name", c.getString(2));
        r.put("time", c.getString(3));
        r.put("kind", c.getString(4));
        r.put("revision", c.getInt(5));
        r.put("reason", c.getString(6));
        out.put(r);
      }
    }
    return out;
  }

  synchronized void acknowledge(JSONArray sent) throws JSONException {
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      for (int i = 0; i < sent.length(); i++) {
        JSONObject r = sent.getJSONObject(i);
        d.execSQL(
            "UPDATE records SET dirty=0 WHERE id=? AND revision=?",
            new Object[] {r.getString("id"), r.getInt("revision")});
      }
      d.setTransactionSuccessful();
    } finally {
      d.endTransaction();
    }
  }

  synchronized int pendingCount() {
    try (Cursor c =
        getReadableDatabase().rawQuery("SELECT COUNT(*) FROM records WHERE dirty=1", null)) {
      c.moveToFirst();
      return c.getInt(0);
    }
  }

  synchronized List<String[]> tags() {
    List<String[]> rows = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT t.uid,e.name FROM tags t JOIN employees e ON e.id=t.employee ORDER BY"
                    + " e.name",
                null)) {
      while (c.moveToNext()) rows.add(new String[] {c.getString(0), c.getString(1)});
    }
    return rows;
  }

  synchronized void removeTag(String uid) {
    getWritableDatabase().delete("tags", "uid=?", new String[] {uid});
  }

  synchronized List<String[]> recent() {
    List<String[]> rows = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT id,name,time,kind FROM records ORDER BY time DESC LIMIT 100", null)) {
      while (c.moveToNext())
        rows.add(new String[] {c.getString(0), c.getString(1), c.getString(2), c.getString(3)});
    }
    return rows;
  }

  synchronized String addManual(String name, String time, String kind, String reason) {
    if (reason.trim().isEmpty() || reason.length() > 1000) return "입력 사유는 1~1000자로 입력하세요.";
    LocalDateTime local;
    try {
      local = LocalDateTime.parse(time);
    } catch (Exception e) {
      return "시간 형식: 2026-10-08T09:00:00";
    }
    if (local.getYear() < 2020 || local.getYear() > 2100) return "날짜를 확인하세요.";
    SQLiteDatabase d = getWritableDatabase();
    String employee;
    try (Cursor c =
        d.rawQuery("SELECT id FROM employees WHERE name=?", new String[] {name.trim()})) {
      if (!c.moveToFirst()) return "등록된 직원 이름을 정확히 입력하세요.";
      employee = c.getString(0);
    }
    String day = local.toLocalDate().toString();
    String value = local.atZone(SEOUL).withNano(0).toOffsetDateTime().toString();
    try (Cursor c =
        d.rawQuery(
            "SELECT kind,time FROM records WHERE employee=? AND day=?",
            new String[] {employee, day})) {
      while (c.moveToNext()) {
        if (c.getString(0).equals(kind)) return "이미 같은 날짜의 기록이 있습니다. 시간 정정을 사용하세요.";
        int cmp = OffsetDateTime.parse(value).compareTo(OffsetDateTime.parse(c.getString(1)));
        if ((kind.equals("출근") && cmp >= 0) || (kind.equals("퇴근") && cmp <= 0))
          return "출근은 퇴근보다 빨라야 합니다.";
      }
    }
    ContentValues v = new ContentValues();
    v.put("id", UUID.randomUUID().toString());
    v.put("employee", employee);
    v.put("name", name.trim());
    v.put("time", value);
    v.put("day", day);
    v.put("kind", kind);
    v.put("revision", 1);
    v.put("dirty", 1);
    v.put("reason", "관리자 입력: " + reason);
    d.insertOrThrow("records", null, v);
    return "누락 기록 입력 완료";
  }

  synchronized String correct(String id, String time, String reason) {
    if (reason.trim().isEmpty() || reason.length() > 1000) return "정정 사유는 1~1000자로 입력하세요.";
    String newTime;
    try {
      newTime = LocalDateTime.parse(time).atZone(SEOUL).withNano(0).toOffsetDateTime().toString();
    } catch (Exception e) {
      return "시간 형식: 2026-10-08T09:00:00";
    }
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      try (Cursor c =
          d.rawQuery("SELECT employee,time,day,kind FROM records WHERE id=?", new String[] {id})) {
        if (!c.moveToFirst()) return "기록을 찾을 수 없습니다.";
        if (!time.substring(0, 10).equals(c.getString(2))) return "이 버전은 같은 날짜 내 시간 정정만 지원합니다.";
        String kind = c.getString(3);
        try (Cursor other =
            d.rawQuery(
                "SELECT time FROM records WHERE employee=? AND day=? AND kind<>?",
                new String[] {c.getString(0), c.getString(2), kind})) {
          if (other.moveToFirst()) {
            int compare =
                OffsetDateTime.parse(newTime).compareTo(OffsetDateTime.parse(other.getString(0)));
            if ((kind.equals("출근") && compare >= 0) || (kind.equals("퇴근") && compare <= 0))
              return "출근은 퇴근보다 빨라야 합니다.";
          }
        }
        d.execSQL(
            "INSERT INTO audit(record,old_time,new_time,reason) VALUES(?,?,?,?)",
            new Object[] {id, c.getString(1), newTime, reason});
        d.execSQL(
            "UPDATE records SET time=?,reason=?,revision=revision+1,dirty=1 WHERE id=?",
            new Object[] {newTime, reason, id});
      }
      d.setTransactionSuccessful();
      return "정정 완료. 다음 전송 때 PC에 반영됩니다.";
    } finally {
      d.endTransaction();
    }
  }
}
