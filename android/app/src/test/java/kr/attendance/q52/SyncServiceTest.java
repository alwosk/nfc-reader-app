package kr.attendance.q52;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class SyncServiceTest {
  @Test public void acceptsLanAndExistingTailscaleAddresses() {
    for (String host : new String[] {"10.0.0.1", "10.255.255.254", "172.16.0.1",
        "172.31.255.254", "192.168.0.10", "100.64.0.1", "100.127.255.254"}) {
      assertTrue(host, SyncService.validUrl("http://" + host + ":8765"));
      assertTrue(host, SyncService.validUrl("http://" + host + ":8765/"));
    }
  }

  @Test public void rejectsUnsupportedHostsAndMalformedUrls() {
    for (String host : new String[] {"172.15.0.1", "172.32.0.1", "192.169.0.1",
        "100.63.0.1", "100.128.0.1", "8.8.8.8", "127.0.0.1", "0.0.0.0",
        "169.254.1.2", "[::1]", "localhost", "192.168.0.256", "192.168.01.2",
        "10.1.2.3.", "10.1.2", "10.1.2.-1", "10.1.2.+1"}) {
      assertFalse(host, SyncService.validUrl("http://" + host + ":8765"));
    }
    for (String url : new String[] {"https://192.168.0.10:8765",
        "http://user@192.168.0.10:8765", "http://192.168.0.10:8765/path",
        "http://192.168.0.10:8765?x=1", "http://192.168.0.10:8765#fragment",
        "http://192.168.0.10", "http://192.168.0.10:0", "http://192.168.0.10:65536", ""}) {
      assertFalse(url, SyncService.validUrl(url));
    }
  }
}
