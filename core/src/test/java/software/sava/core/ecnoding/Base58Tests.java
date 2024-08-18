package software.sava.core.ecnoding;

import org.junit.jupiter.api.Test;
import software.sava.core.encoding.Base58;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class Base58Tests {

  @Test
  void testRandom() throws NoSuchAlgorithmException {
    final byte[] bytes = SecureRandom.getInstanceStrong().generateSeed(4_096);
    final var encoded = Base58.encode(bytes);
    assertArrayEquals(bytes, Base58.decode(encoded));
  }

  @Test
  void testExternal() {
    var expected = "FfkQe7KDkc4nPipvveW7BEtyj4SpqJ1v63UpeFCWYGS2";
    byte[] decoded = Base58.decode(expected);
    assertEquals(expected, Base58.encode(decoded));

    expected = "2pD1X4ERc255sNPUKaJUMuLeMhRQYfejgauQCALMJPznLY5ptAtbNKgK1WrA7QNw9Nq4ssfvEnBFLCpZXzD1TCCj";
    decoded = Base58.decode(expected);
    assertEquals(expected, Base58.encode(decoded));

    expected = "11111111111111111111111111111111";
    decoded = Base58.decode(expected);
    assertArrayEquals(new byte[32], decoded);
    assertEquals(expected, Base58.encode(decoded));
  }
}

