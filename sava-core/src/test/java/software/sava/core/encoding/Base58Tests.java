package software.sava.core.encoding;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

final class Base58Tests {

  @Test
  void testRandom() throws NoSuchAlgorithmException {
    final byte[] bytes = SecureRandom.getInstanceStrong().generateSeed(4_096);
    final var encoded = Base58.encode(bytes);
    assertArrayEquals(bytes, Base58.decode(encoded));
  }

  @Test
  void testRoundTripLengths() throws NoSuchAlgorithmException {
    final var random = SecureRandom.getInstanceStrong();
    for (int len = 0; len <= 128; ++len) {
      for (int leadingZeros = 0; leadingZeros <= Math.min(len, 4); ++leadingZeros) {
        final byte[] bytes = new byte[len];
        random.nextBytes(bytes);
        Arrays.fill(bytes, 0, leadingZeros, (byte) 0);
        if (leadingZeros < len && bytes[leadingZeros] == 0) {
          bytes[leadingZeros] = 1;
        }

        final var encoded = Base58.encode(bytes);
        assertArrayEquals(bytes, Base58.decode(encoded));

        final char[] chars = encoded.toCharArray();
        assertArrayEquals(bytes, Base58.decode(chars));

        final char[] shifted = new char[chars.length + 7];
        System.arraycopy(chars, 0, shifted, 3, chars.length);
        assertArrayEquals(bytes, Base58.decode(shifted, 3, chars.length));

        final byte[] out = new byte[len];
        Base58.decode(encoded, out);
        assertArrayEquals(bytes, out);

        final byte[] out2 = new byte[len];
        Base58.decode(shifted, 3, chars.length, out2);
        assertArrayEquals(bytes, out2);

        final byte[] ascii = encoded.getBytes(StandardCharsets.US_ASCII);
        final byte[] shiftedAscii = new byte[ascii.length + 7];
        System.arraycopy(ascii, 0, shiftedAscii, 3, ascii.length);
        assertArrayEquals(bytes, Base58.decode(ascii, 0, ascii.length));
        assertArrayEquals(bytes, Base58.decode(shiftedAscii, 3, ascii.length));

        final byte[] out3 = new byte[len];
        Base58.decode(shiftedAscii, 3, ascii.length, out3);
        assertArrayEquals(bytes, out3);
      }
    }
  }

  @Test
  void testFixedLengthMismatch() {
    final byte[] key = new byte[32];
    key[0] = 1;
    final var encoded = Base58.encode(key);
    assertThrows(IllegalArgumentException.class, () -> Base58.decode(encoded, new byte[31]));
    assertThrows(IllegalArgumentException.class, () -> Base58.decode(encoded, new byte[33]));
    assertThrows(IllegalArgumentException.class, () -> Base58.decode(encoded.toCharArray(), 0, encoded.length(), new byte[31]));
    assertThrows(IllegalArgumentException.class, () -> Base58.decode("11111111111111111111111111111111", new byte[31]));

    final byte[] zeros = new byte[32];
    Arrays.fill(zeros, (byte) 0xFF);
    Base58.decode("11111111111111111111111111111111", zeros);
    assertArrayEquals(new byte[32], zeros);
  }

  @Test
  void testIllegalCharacters() {
    for (final char c : new char[]{'0', 'O', 'I', 'l', '!', ' ', 'π', '\uD83D'}) {
      final var encoded = "3x" + c + "z";
      assertThrows(IllegalArgumentException.class, () -> Base58.decode(encoded));
      assertThrows(IllegalArgumentException.class, () -> Base58.decode(encoded.toCharArray()));
      assertThrows(IllegalArgumentException.class, () -> Base58.decode(encoded, new byte[3]));
      assertFalse(Base58.isBase58(c));
      assertFalse(Base58.isBase58(encoded));
      assertEquals(2, Base58.nonBase58(encoded));
    }
    // byte input: the sign bit and the 123-127 ASCII tail must both reject
    for (final byte b : new byte[]{'0', 'O', 'I', 'l', '{', '|', '}', '~', 127, (byte) 0x80, (byte) 0xFF}) {
      final byte[] ascii = {'3', 'x', b, 'z'};
      assertThrows(IllegalArgumentException.class, () -> Base58.decode(ascii, 0, ascii.length));
      assertThrows(IllegalArgumentException.class, () -> Base58.decode(ascii, 0, ascii.length, new byte[3]));
    }
    assertTrue(Base58.isBase58("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"));
    assertEquals(-1, Base58.nonBase58("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"));
  }

  @Test
  void testLeadingOnes() {
    assertArrayEquals(new byte[0], Base58.decode(""));
    assertArrayEquals(new byte[]{0}, Base58.decode("1"));
    assertArrayEquals(new byte[]{0, 0, 0}, Base58.decode("111"));
    assertArrayEquals(new byte[]{0, 57}, Base58.decode("1z"));
    assertArrayEquals(new byte[]{0, 0, 1, 0}, Base58.decode("115R"));
    assertEquals("115R", Base58.encode(new byte[]{0, 0, 1, 0}));
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

