package software.sava.core.crypto;

import org.junit.jupiter.api.Test;
import software.sava.core.encoding.Jex;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/// Known-answer vectors from RFC 4231, section 4. The vectors are what pins the
/// key and data arguments to their roles: cases 2 and 3 use a key and a message
/// that differ in both length and content, so passing them the other way round
/// cannot produce the expected digest.
@SuppressWarnings("removal")
final class HmacTests {

  private static byte[] repeat(final int b, final int len) {
    final byte[] key = new byte[len];
    Arrays.fill(key, (byte) b);
    return key;
  }

  private static byte[] ascii(final String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }

  /// RFC 4231 case 1.
  @Test
  void rfc4231Case1() {
    assertArrayEquals(
        Jex.decode("87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cde"
            + "daa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854"),
        Hmac.hmacSHA512(repeat(0x0b, 20), ascii("Hi There")));
  }

  /// RFC 4231 case 2: a 4-byte key over a 28-byte message.
  @Test
  void rfc4231Case2() {
    assertArrayEquals(
        Jex.decode("164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea250554"
            + "9758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737"),
        Hmac.hmacSHA512(ascii("Jefe"), ascii("what do ya want for nothing?")));
  }

  /// RFC 4231 case 3.
  @Test
  void rfc4231Case3() {
    assertArrayEquals(
        Jex.decode("fa73b0089d56a284efb0f0756c890be9b1b5dbdd8ee81a3655f83e33b2279d39"
            + "bf3e848279a722c806b485a47e67c807b946a337bee8942674278859e13292fb"),
        Hmac.hmacSHA512(repeat(0xaa, 20), repeat(0xdd, 50)));
  }

  /// RFC 4231 case 6: a 131-byte key, longer than the 128-byte block, so the JCE
  /// must hash it down rather than pad it.
  @Test
  void rfc4231Case6OverLongKey() {
    assertArrayEquals(
        Jex.decode("80b24263c7c1a3ebb71493c1dd7be8b49b46d1f41b4aeec1121b013783f8f352"
            + "6b56d037e05f2598bd0fd2215d6a1e5295e64f73f63f0aec8b915a985d786598"),
        Hmac.hmacSHA512(repeat(0xaa, 131),
            ascii("Test Using Larger Than Block-Size Key - Hash Key First")));
  }

  /// The arguments are not interchangeable. This is the regression guard: the
  /// method used to key the Mac with its second argument, so a future reordering
  /// would silently reintroduce that.
  @Test
  void keyAndDataAreNotInterchangeable() {
    final var key = ascii("Jefe");
    final var data = ascii("what do ya want for nothing?");
    assertFalse(Arrays.equals(Hmac.hmacSHA512(key, data), Hmac.hmacSHA512(data, key)));
  }

  /// The no-argument factory is public API and the escape hatch the deprecated
  /// two-argument method points callers at, so it has to hand back a usable,
  /// independent [javax.crypto.Mac] from the pinned provider.
  @Test
  void factoryReturnsAnIndependentMacFromThePinnedProvider() throws Exception {
    final var mac = Hmac.hmacSHA512();
    assertEquals("HmacSHA512", mac.getAlgorithm());
    assertSame(SunCrypto.SUN_JCE_PROVIDER, mac.getProvider());
    assertEquals(64, mac.getMacLength());

    assertNotSame(Hmac.hmacSHA512(), Hmac.hmacSHA512(), "each call needs its own Mac");

    // driving it by hand must reproduce the convenience method
    mac.init(new javax.crypto.spec.SecretKeySpec(ascii("Jefe"), "HmacSHA512"));
    assertArrayEquals(
        Hmac.hmacSHA512(ascii("Jefe"), ascii("what do ya want for nothing?")),
        mac.doFinal(ascii("what do ya want for nothing?")));
  }

  /// A fresh Mac carries no state from a previous one — the reason the factory
  /// hands out a new instance rather than sharing one.
  @Test
  void separateMacsDoNotShareState() throws Exception {
    final var poisoned = Hmac.hmacSHA512();
    poisoned.init(new javax.crypto.spec.SecretKeySpec(ascii("Jefe"), "HmacSHA512"));
    poisoned.update(ascii("partial input"));

    final var clean = Hmac.hmacSHA512();
    clean.init(new javax.crypto.spec.SecretKeySpec(ascii("Jefe"), "HmacSHA512"));
    assertArrayEquals(
        Hmac.hmacSHA512(ascii("Jefe"), ascii("what do ya want for nothing?")),
        clean.doFinal(ascii("what do ya want for nothing?")));
  }
}
