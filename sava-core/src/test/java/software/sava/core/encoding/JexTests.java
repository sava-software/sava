package software.sava.core.encoding;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Random;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

final class JexTests {

  private static void assertEncodeVariants(final byte[] data, final String lower, final String upper) {
    final int hexLen = data.length << 1;

    assertEquals(lower, Jex.encode(data));
    assertEquals(upper, Jex.encodeUpper(data));
    assertArrayEquals(lower.toCharArray(), Jex.encodeChars(data));
    assertArrayEquals(upper.toCharArray(), Jex.encodeUpperChars(data));
    assertArrayEquals(lower.getBytes(US_ASCII), Jex.encodeBytes(data));
    assertArrayEquals(upper.getBytes(US_ASCII), Jex.encodeUpperBytes(data));

    final byte[] outBytes = new byte[hexLen + 3];
    Jex.encodeBytes(data, outBytes, 3);
    assertEquals(lower, new String(outBytes, 3, hexLen, US_ASCII));
    Jex.encodeUpperBytes(data, outBytes, 3);
    assertEquals(upper, new String(outBytes, 3, hexLen, US_ASCII));

    assertEquals(lower, Jex.encode(ByteBuffer.wrap(data)));
    assertEquals(upper, Jex.encodeUpper(ByteBuffer.wrap(data)));
    assertArrayEquals(lower.toCharArray(), Jex.encodeChars(ByteBuffer.wrap(data)));
    assertArrayEquals(upper.toCharArray(), Jex.encodeUpperChars(ByteBuffer.wrap(data)));
    assertArrayEquals(lower.getBytes(US_ASCII), Jex.encodeBytes(ByteBuffer.wrap(data)));
    assertArrayEquals(upper.getBytes(US_ASCII), Jex.encodeUpperBytes(ByteBuffer.wrap(data)));

    final char[] outChars = new char[hexLen + 2];
    Jex.encodeChars(ByteBuffer.wrap(data), outChars, 2);
    assertEquals(lower, new String(outChars, 2, hexLen));
    Jex.encodeUpperChars(ByteBuffer.wrap(data), outChars, 2);
    assertEquals(upper, new String(outChars, 2, hexLen));
    Jex.encodeBytes(ByteBuffer.wrap(data), outBytes, 3);
    assertEquals(lower, new String(outBytes, 3, hexLen, US_ASCII));
    Jex.encodeUpperBytes(ByteBuffer.wrap(data), outBytes, 3);
    assertEquals(upper, new String(outBytes, 3, hexLen, US_ASCII));

    final byte[] shifted = new byte[data.length + 7];
    System.arraycopy(data, 0, shifted, 5, data.length);
    assertEquals(lower, Jex.encode(shifted, 5, data.length));
    assertEquals(upper, Jex.encodeUpper(shifted, 5, data.length));
    assertArrayEquals(lower.toCharArray(), Jex.encodeChars(shifted, 5, data.length));
    assertArrayEquals(upper.toCharArray(), Jex.encodeUpperChars(shifted, 5, data.length));
    assertArrayEquals(lower.getBytes(US_ASCII), Jex.encodeBytes(shifted, 5, data.length));
    assertArrayEquals(upper.getBytes(US_ASCII), Jex.encodeUpperBytes(shifted, 5, data.length));

    Jex.encodeChars(shifted, 5, data.length, outChars, 2);
    assertEquals(lower, new String(outChars, 2, hexLen));
    Jex.encodeUpperChars(shifted, 5, data.length, outChars, 2);
    assertEquals(upper, new String(outChars, 2, hexLen));
    Jex.encodeBytes(shifted, 5, data.length, outBytes, 3);
    assertEquals(lower, new String(outBytes, 3, hexLen, US_ASCII));
    Jex.encodeUpperBytes(shifted, 5, data.length, outBytes, 3);
    assertEquals(upper, new String(outBytes, 3, hexLen, US_ASCII));

    // ByteBuffer with an explicit len, reading from the buffer's position
    assertEquals(lower, Jex.encode(ByteBuffer.wrap(shifted).position(5), data.length));
    assertEquals(upper, Jex.encodeUpper(ByteBuffer.wrap(shifted).position(5), data.length));
    assertArrayEquals(lower.toCharArray(), Jex.encodeChars(ByteBuffer.wrap(shifted).position(5), data.length));
    assertArrayEquals(upper.toCharArray(), Jex.encodeUpperChars(ByteBuffer.wrap(shifted).position(5), data.length));
    assertArrayEquals(lower.getBytes(US_ASCII), Jex.encodeBytes(ByteBuffer.wrap(shifted).position(5), data.length));
    assertArrayEquals(upper.getBytes(US_ASCII), Jex.encodeUpperBytes(ByteBuffer.wrap(shifted).position(5), data.length));
    Jex.encodeChars(ByteBuffer.wrap(shifted).position(5), data.length, outChars, 2);
    assertEquals(lower, new String(outChars, 2, hexLen));
    Jex.encodeUpperChars(ByteBuffer.wrap(shifted).position(5), data.length, outChars, 2);
    assertEquals(upper, new String(outChars, 2, hexLen));
    Jex.encodeBytes(ByteBuffer.wrap(shifted).position(5), data.length, outBytes, 3);
    assertEquals(lower, new String(outBytes, 3, hexLen, US_ASCII));
    Jex.encodeUpperBytes(ByteBuffer.wrap(shifted).position(5), data.length, outBytes, 3);
    assertEquals(upper, new String(outBytes, 3, hexLen, US_ASCII));
  }

  /// encodeReverse(data, offset, len) encodes data[offset] down through data[offset - len + 1]
  private static void assertReverseVariants(final byte[] data, final String lowerReversed, final String upperReversed) {
    final int hexLen = data.length << 1;
    final int last = data.length - 1;

    assertEquals(lowerReversed, Jex.encodeReverse(data, last, data.length));
    assertEquals(upperReversed, Jex.encodeUpperReverse(data, last, data.length));
    assertArrayEquals(lowerReversed.toCharArray(), Jex.encodeReverseChars(data, last, data.length));
    assertArrayEquals(upperReversed.toCharArray(), Jex.encodeUpperReverseChars(data, last, data.length));
    assertArrayEquals(lowerReversed.getBytes(US_ASCII), Jex.encodeReverseBytes(data, last, data.length));
    assertArrayEquals(upperReversed.getBytes(US_ASCII), Jex.encodeUpperReverseBytes(data, last, data.length));

    final char[] outChars = new char[hexLen + 1];
    Jex.encodeReverseChars(data, last, data.length, outChars, 1);
    assertEquals(lowerReversed, new String(outChars, 1, hexLen));
    Jex.encodeUpperReverseChars(data, last, data.length, outChars, 1);
    assertEquals(upperReversed, new String(outChars, 1, hexLen));

    final byte[] outBytes = new byte[hexLen + 1];
    Jex.encodeReverseBytes(data, last, data.length, outBytes, 1);
    assertEquals(lowerReversed, new String(outBytes, 1, hexLen, US_ASCII));
    Jex.encodeUpperReverseBytes(data, last, data.length, outBytes, 1);
    assertEquals(upperReversed, new String(outBytes, 1, hexLen, US_ASCII));

    assertEquals(lowerReversed, Jex.encodeReverse(ByteBuffer.wrap(data), last, data.length));
    assertEquals(upperReversed, Jex.encodeUpperReverse(ByteBuffer.wrap(data), last, data.length));
    assertArrayEquals(lowerReversed.toCharArray(), Jex.encodeReverseChars(ByteBuffer.wrap(data), last, data.length));
    assertArrayEquals(upperReversed.toCharArray(), Jex.encodeUpperReverseChars(ByteBuffer.wrap(data), last, data.length));
    assertArrayEquals(lowerReversed.getBytes(US_ASCII), Jex.encodeReverseBytes(ByteBuffer.wrap(data), last, data.length));
    assertArrayEquals(upperReversed.getBytes(US_ASCII), Jex.encodeUpperReverseBytes(ByteBuffer.wrap(data), last, data.length));

    Jex.encodeReverseChars(ByteBuffer.wrap(data), last, data.length, outChars, 1);
    assertEquals(lowerReversed, new String(outChars, 1, hexLen));
    Jex.encodeUpperReverseChars(ByteBuffer.wrap(data), last, data.length, outChars, 1);
    assertEquals(upperReversed, new String(outChars, 1, hexLen));
    Jex.encodeReverseBytes(ByteBuffer.wrap(data), last, data.length, outBytes, 1);
    assertEquals(lowerReversed, new String(outBytes, 1, hexLen, US_ASCII));
    Jex.encodeUpperReverseBytes(ByteBuffer.wrap(data), last, data.length, outBytes, 1);
    assertEquals(upperReversed, new String(outBytes, 1, hexLen, US_ASCII));
  }

  private static void assertDecodeVariants(final String hex, final byte[] expected) {
    final byte[] ascii = hex.getBytes(US_ASCII);

    assertArrayEquals(expected, Jex.decode(hex));
    assertArrayEquals(expected, Jex.decode(hex.toCharArray()));
    assertArrayEquals(expected, Jex.decode(ascii));
    assertArrayEquals(expected, Jex.decode(ByteBuffer.wrap(ascii)));
    assertArrayEquals(expected, Jex.decodeChecked(hex));
    assertArrayEquals(expected, Jex.decodeChecked(hex.toCharArray()));
    assertArrayEquals(expected, Jex.decodeChecked(ascii));
    assertArrayEquals(expected, Jex.decodeChecked(ByteBuffer.wrap(ascii)));
    assertArrayEquals(expected, Jex.decodePrimIter(hex));
    assertArrayEquals(expected, Jex.decodePrimIterChecked(hex));
    assertArrayEquals(expected, Jex.decodeCheckedToCharArray(hex));

    // decode-into variants must fully overwrite the field of a dirty buffer, and only the field
    final byte[] out = new byte[expected.length + 4];
    final Runnable[] decoders = {
        () -> Jex.decode(hex, out, 2),
        () -> Jex.decode(hex.toCharArray(), out, 2),
        () -> Jex.decode(hex.toCharArray(), 0, hex.length(), out, 2),
        () -> Jex.decodeChecked(hex, out, 2),
        () -> Jex.decodeChecked(hex.toCharArray(), out, 2),
        () -> Jex.decodeChecked(ascii, out, 2),
        () -> Jex.decodeChecked(ByteBuffer.wrap(ascii), out, 2),
        () -> Jex.decodePrimIter(hex, out, 2),
        () -> Jex.decodePrimIterChecked(hex, out, 2),
        () -> Jex.decodeToCharArray(hex, out, 2),
        () -> Jex.decodeCheckedToCharArray(hex, out, 2)
    };
    for (final var decoder : decoders) {
      Arrays.fill(out, (byte) 0x5A);
      decoder.run();
      assertArrayEquals(expected, Arrays.copyOfRange(out, 2, 2 + expected.length));
      assertEquals((byte) 0x5A, out[0]);
      assertEquals((byte) 0x5A, out[1]);
      assertEquals((byte) 0x5A, out[out.length - 2]);
      assertEquals((byte) 0x5A, out[out.length - 1]);
    }

    assertTrue(Jex.isValid(hex));
    assertTrue(Jex.isLengthValid(hex));
  }

  @Test
  void testReferenceCrossValidation() {
    final long seed = new SecureRandom().nextLong();
    final var random = new Random(seed);
    final var hexFormat = HexFormat.of();
    for (int len = 0; len <= 64; ++len) {
      final byte[] data = new byte[len];
      random.nextBytes(data);
      final var lower = hexFormat.formatHex(data);
      final var upper = lower.toUpperCase(Locale.ROOT);
      final var lowerReversed = hexFormat.formatHex(ByteUtil.reverse(data));
      try {
        assertEncodeVariants(data, lower, upper);
        assertReverseVariants(data, lowerReversed, lowerReversed.toUpperCase(Locale.ROOT));
        assertDecodeVariants(lower, data);
        assertDecodeVariants(upper, data);
      } catch (final AssertionError e) {
        throw new AssertionError("seed=" + seed + " len=" + len, e);
      }
    }
  }

  @Test
  void testAllByteValues() {
    final byte[] data = new byte[256];
    for (int i = 0; i < data.length; ++i) {
      data[i] = (byte) i;
    }
    final var lower = HexFormat.of().formatHex(data);
    final var upper = lower.toUpperCase(Locale.ROOT);
    final var lowerReversed = HexFormat.of().formatHex(ByteUtil.reverse(data));
    assertEncodeVariants(data, lower, upper);
    assertReverseVariants(data, lowerReversed, lowerReversed.toUpperCase(Locale.ROOT));
    assertDecodeVariants(lower, data);
    assertDecodeVariants(upper, data);
  }

  @Test
  void testMixedCase() {
    assertArrayEquals(new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC}, Jex.decode("aAbBcC"));
    assertArrayEquals(new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC}, Jex.decodeChecked("aAbBcC"));
    assertTrue(Jex.isValid("aAbBcC"));
  }

  @Test
  void testReverseSlice() {
    final byte[] data = {0x01, 0x02, 0x03};
    assertEquals("0302", Jex.encodeReverse(data, 2, 2));
    assertEquals("0201", Jex.encodeReverse(data, 1, 2));
    assertEquals("02", Jex.encodeReverse(data, 1, 1));
    assertEquals("", Jex.encodeReverse(data, 2, 0));
  }

  @Test
  void testOddLength() {
    assertFalse(Jex.isValid("abc"));
    assertFalse(Jex.isLengthValid("abc"));
    assertFalse(Jex.isValid(null));
    assertFalse(Jex.isLengthValid(null));
    assertTrue(Jex.isValid(""));
    assertTrue(Jex.isLengthValid(""));

    final byte[] out = new byte[4];
    assertThrows(IllegalArgumentException.class, () -> Jex.decodeChecked("abc"));
    assertThrows(IllegalArgumentException.class, () -> Jex.decodeChecked("abc".toCharArray()));
    assertThrows(IllegalArgumentException.class, () -> Jex.decodeChecked("abc".getBytes(US_ASCII)));
    assertThrows(IllegalArgumentException.class, () -> Jex.decodeChecked(ByteBuffer.wrap("abc".getBytes(US_ASCII))));
    assertThrows(IllegalArgumentException.class, () -> Jex.decodePrimIterChecked("abc"));
    assertThrows(IllegalArgumentException.class, () -> Jex.decodeChecked("abc", out, 0));
    assertThrows(IllegalArgumentException.class, () -> Jex.decodeChecked("abc".toCharArray(), out, 0));
    assertThrows(IllegalArgumentException.class, () -> Jex.decodeChecked("abc".getBytes(US_ASCII), out, 0));
    assertThrows(IllegalArgumentException.class, () -> Jex.decodeChecked(ByteBuffer.wrap("abc".getBytes(US_ASCII)), out, 0));
    assertThrows(IllegalArgumentException.class, () -> Jex.decodePrimIterChecked("abc", out, 0));

    // empty input is a no-op for every checked variant
    assertArrayEquals(new byte[0], Jex.decodeChecked(""));
    assertArrayEquals(new byte[0], Jex.decodeChecked(new char[0]));
    assertArrayEquals(new byte[0], Jex.decodeChecked(new byte[0]));
    assertArrayEquals(new byte[0], Jex.decodeChecked(ByteBuffer.wrap(new byte[0])));
    assertArrayEquals(new byte[0], Jex.decodePrimIterChecked(""));
  }

  @Test
  void testIllegalCharacters() {
    // both nibble positions, above and below the digit ranges, plus chars beyond 'f'
    for (final var bad : new String[]{"g0", "0g", "G0", "0G", "z0", "0z", "!0", "0!", "/0", "0/", ":0", "0:", "@0", "0@", "`0", "0`", "π0", "0π"}) {
      assertFalse(Jex.isValid(bad), bad);
      assertThrows(IllegalArgumentException.class, () -> Jex.decodeChecked(bad), bad);
      assertThrows(IllegalArgumentException.class, () -> Jex.decodeChecked(bad.toCharArray()), bad);
      assertThrows(IllegalArgumentException.class, () -> Jex.decodePrimIterChecked(bad), bad);
      final byte[] out = new byte[2];
      assertThrows(IllegalArgumentException.class, () -> Jex.decodeChecked(bad, out, 0), bad);
      assertThrows(IllegalArgumentException.class, () -> Jex.decodeChecked(bad.toCharArray(), out, 0), bad);
      assertThrows(IllegalArgumentException.class, () -> Jex.decodePrimIterChecked(bad, out, 0), bad);
      if (bad.indexOf('π') < 0) {
        final byte[] badAscii = bad.getBytes(US_ASCII);
        assertThrows(IllegalArgumentException.class, () -> Jex.decodeChecked(badAscii), bad);
        assertThrows(IllegalArgumentException.class, () -> Jex.decodeChecked(ByteBuffer.wrap(badAscii)), bad);
        assertThrows(IllegalArgumentException.class, () -> Jex.decodeChecked(badAscii, out, 0), bad);
        assertThrows(IllegalArgumentException.class, () -> Jex.decodeChecked(ByteBuffer.wrap(badAscii), out, 0), bad);
      }
    }

    // validation must not stop after the first pair
    assertFalse(Jex.isValid("aag0"));
    assertFalse(Jex.isValid("aa0g"));
    assertTrue(Jex.isValid("aaaa"));
  }

  private static void assertPosition(final int position, final org.junit.jupiter.api.function.Executable executable) {
    final var e = assertThrows(IllegalArgumentException.class, executable);
    assertTrue(e.getMessage().endsWith("position " + position + "."), e.getMessage());
  }

  @Test
  void testIllegalCharacterPositions() {
    // errors past the first pair pin the position arithmetic of each variant
    final var firstNibble = "aag0";  // position 2
    final var secondNibble = "aa0g"; // position 3
    final byte[] firstAscii = firstNibble.getBytes(US_ASCII);
    final byte[] secondAscii = secondNibble.getBytes(US_ASCII);
    final byte[] out = new byte[4];

    assertPosition(2, () -> Jex.decodeChecked(firstNibble));
    assertPosition(3, () -> Jex.decodeChecked(secondNibble));
    assertPosition(2, () -> Jex.decodeChecked(firstNibble.toCharArray()));
    assertPosition(3, () -> Jex.decodeChecked(secondNibble.toCharArray()));
    assertPosition(2, () -> Jex.decodeChecked(firstAscii));
    assertPosition(3, () -> Jex.decodeChecked(secondAscii));
    assertPosition(2, () -> Jex.decodeChecked(ByteBuffer.wrap(firstAscii)));
    assertPosition(3, () -> Jex.decodeChecked(ByteBuffer.wrap(secondAscii)));
    assertPosition(2, () -> Jex.decodePrimIterChecked(firstNibble));
    assertPosition(3, () -> Jex.decodePrimIterChecked(secondNibble));

    assertPosition(2, () -> Jex.decodeChecked(firstNibble, out, 0));
    assertPosition(3, () -> Jex.decodeChecked(secondNibble, out, 0));
    assertPosition(2, () -> Jex.decodeChecked(firstNibble.toCharArray(), out, 0));
    assertPosition(3, () -> Jex.decodeChecked(secondNibble.toCharArray(), out, 0));
    assertPosition(2, () -> Jex.decodeChecked(firstAscii, out, 0));
    assertPosition(3, () -> Jex.decodeChecked(secondAscii, out, 0));
    assertPosition(2, () -> Jex.decodeChecked(ByteBuffer.wrap(firstAscii), out, 0));
    assertPosition(3, () -> Jex.decodeChecked(ByteBuffer.wrap(secondAscii), out, 0));
    assertPosition(2, () -> Jex.decodePrimIterChecked(firstNibble, out, 0));
    assertPosition(3, () -> Jex.decodePrimIterChecked(secondNibble, out, 0));
  }
}
