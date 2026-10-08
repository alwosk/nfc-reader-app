package kr.attendance.q52;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class StoreTest {
  Store db;

  @Before
  public void before() {
    RuntimeEnvironment.getApplication().deleteDatabase("attendance.db");
    db = new Store(RuntimeEnvironment.getApplication());
    db.register("04112233", "김직원");
  }

  @After
  public void after() {
    db.close();
  }

  @Test
  public void duplicateTapAndMissingArrivalDoNotCreateRows() throws Exception {
    db.record("04112233", "퇴근");
    assertEquals(0, db.pendingCount());
    db.record("04112233", "출근");
    db.record("04112233", "출근");
    assertEquals(1, db.pendingCount());
    assertEquals("김직원", db.pending().getJSONObject(0).getString("name"));
  }

  @Test
  public void oldAcknowledgementCannotClearConcurrentCorrection() throws Exception {
    db.addManual("김직원", "2026-10-08T09:00:00", "출근", "누락 확인");
    JSONArray sent = db.pending();
    db.correct(sent.getJSONObject(0).getString("id"), "2026-10-08T08:55:00", "시각 확인");
    db.acknowledge(sent);
    assertEquals(1, db.pendingCount());
    assertEquals(2, db.pending().getJSONObject(0).getInt("revision"));
    db.acknowledge(db.pending());
    assertEquals(0, db.pendingCount());
    assertEquals(1, db.recent().size());
  }

  @Test
  public void recordsSurviveReopenAndRevokedTagCannotRecord() throws Exception {
    db.addManual("김직원", "2026-10-08T09:00:00", "출근", "누락 확인");
    db.removeTag("04112233");
    db.close();
    db = new Store(RuntimeEnvironment.getApplication());
    assertEquals(1, db.pendingCount());
    assertTrue(db.record("04112233", "출근").contains("등록되지 않은"));
    assertEquals(1, db.pendingCount());
  }
}
