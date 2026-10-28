package software.sava.vanity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.*;

final class HostEncodingTests {

  @Test
  void utf8MatchesTheScriptsSpelling() {
    for (final var encoding : new String[]{"UTF-8", "utf-8", "UTF8", "utf8", "Utf-8"}) {
      assertTrue(HostEncoding.utf8(encoding), encoding);
      assertNull(HostEncoding.refusal(encoding), encoding);
    }
    for (final var encoding : new String[]{null, "", "UTF-16", "US-ASCII", "ANSI_X3.4-1968", "ISO-8859-1",
        "GBK", "Shift_JIS", "UTF-88", "xUTF-8", "UTF-8 "}) {
      assertFalse(HostEncoding.utf8(encoding), String.valueOf(encoding));
      assertNotNull(HostEncoding.refusal(encoding), String.valueOf(encoding));
    }
  }

  @Test
  void refusalIsTheFailureLineNamingThePropertyAndItsValue() {
    assertEquals("FAIL: Encrypted-key passwords require a UTF-8 locale (native.encoding is GBK); "
        + "select an installed UTF-8 locale using LANG or LC_ALL.", HostEncoding.refusal("GBK"));
    assertEquals("FAIL: Encrypted-key passwords require a UTF-8 locale (native.encoding is null); "
        + "select an installed UTF-8 locale using LANG or LC_ALL.", HostEncoding.refusal(null));
    assertEquals(2, HostEncoding.STATUS, "the scripts' status for the same condition");
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void theHostRefusalReadsTheNativeEncoding() {
    final var original = System.getProperty(HostEncoding.PROPERTY);
    try {
      System.setProperty(HostEncoding.PROPERTY, "ANSI_X3.4-1968");
      assertEquals(HostEncoding.refusal("ANSI_X3.4-1968"), HostEncoding.refusal());
      System.setProperty(HostEncoding.PROPERTY, "UTF-8");
      assertNull(HostEncoding.refusal());
    } finally {
      if (original == null) {
        System.clearProperty(HostEncoding.PROPERTY);
      } else {
        System.setProperty(HostEncoding.PROPERTY, original);
      }
    }
  }
}
