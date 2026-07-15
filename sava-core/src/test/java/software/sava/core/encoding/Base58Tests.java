package software.sava.core.encoding;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

final class Base58Tests {

  private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
  private static final BigInteger FIFTY_EIGHT = BigInteger.valueOf(58);

  private static String referenceEncode(final byte[] input) {
    int zeros = 0;
    while (zeros < input.length && input[zeros] == 0) {
      ++zeros;
    }
    final var encoded = new StringBuilder();
    for (var num = new BigInteger(1, input); num.signum() > 0; ) {
      final var divRem = num.divideAndRemainder(FIFTY_EIGHT);
      encoded.append(ALPHABET.charAt(divRem[1].intValue()));
      num = divRem[0];
    }
    return encoded.append("1".repeat(zeros)).reverse().toString();
  }

  private static byte[] referenceDecode(final String encoded) {
    int zeros = 0;
    while (zeros < encoded.length() && encoded.charAt(zeros) == '1') {
      ++zeros;
    }
    var num = BigInteger.ZERO;
    for (int i = zeros; i < encoded.length(); ++i) {
      num = num.multiply(FIFTY_EIGHT).add(BigInteger.valueOf(ALPHABET.indexOf(encoded.charAt(i))));
    }
    if (num.signum() == 0) {
      return new byte[zeros];
    }
    final byte[] magnitude = num.toByteArray();
    final int from = magnitude[0] == 0 ? 1 : 0;
    final byte[] out = new byte[zeros + magnitude.length - from];
    System.arraycopy(magnitude, from, out, zeros, magnitude.length - from);
    return out;
  }

  @Test
  void testReferenceCrossValidation() {
    final long seed = new SecureRandom().nextLong();
    final var random = new Random(seed);
    for (int len = 0; len <= 1_232; len = len < 256 ? len + 1 : len + 61) {
      for (int leadingZeros = 0, max = Math.min(len, 2); leadingZeros <= max; ++leadingZeros) {
        final byte[] bytes = new byte[len];
        random.nextBytes(bytes);
        Arrays.fill(bytes, 0, leadingZeros, (byte) 0);
        final var msg = "seed=" + seed + " len=" + len + " leadingZeros=" + leadingZeros;
        final var expected = referenceEncode(bytes);
        assertEquals(expected, Base58.encode(bytes), msg);
        assertArrayEquals(bytes, Base58.decode(expected), msg);
        final byte[] out = new byte[len];
        Base58.decode(expected, out);
        assertArrayEquals(bytes, out, msg);
      }
    }
  }

  @Test
  void testBitcoinCoreVectors() {
    // https://github.com/bitcoin/bitcoin/blob/master/src/test/data/base58_encode_decode.json
    final String[][] vectors = {
        {"", ""},
        {"61", "2g"},
        {"626262", "a3gV"},
        {"636363", "aPEr"},
        {"73696d706c792061206c6f6e6720737472696e67", "2cFupjhnEsSn59qHXstmK2ffpLv2"},
        {"00eb15231dfceb60925886b67d065299925915aeb172c06647", "1NS17iag9jJgTHD1VXjvLCEnZuQ3rJDE9L"},
        {"516b6fcd0f", "ABnLTmg"},
        {"bf4f89001e670274dd", "3SEo3LWLoPntC"},
        {"572e4794", "3EFU7m"},
        {"ecac89cad93923c02321", "EJDM8drfXA6uyA"},
        {"10c8511e", "Rt5zm"},
        {"00000000000000000000", "1111111111"}
    };
    final var hex = HexFormat.of();
    for (final var vector : vectors) {
      final byte[] bytes = hex.parseHex(vector[0]);
      assertEquals(vector[1], Base58.encode(bytes), vector[0]);
      assertArrayEquals(bytes, Base58.decode(vector[1]), vector[1]);
    }
  }

  @Test
  void testKnownSolanaAccounts() {
    final String[][] vectors = {
        {"0000000000000000000000000000000000000000000000000000000000000000", "11111111111111111111111111111111"},
        {"06ddf6e1d765a193d9cbe146ceeb79ac1cb485ed5f5b37913a8cf5857eff00a9", "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"},
        {"8c97258f4e2489f1bb3d1029148e0d830b5a1399daff1084048e7bd8dbe9f859", "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"},
        {"069b8857feab8184fb687f634618c035dac439dc1aeb3b5598a0f00000000001", "So11111111111111111111111111111111111111112"},
        {"0306466fe5211732ffecadba72c39be7bc8ce5bbc5f7126b2c439b3a40000000", "ComputeBudget111111111111111111111111111111"},
        {"06a7d517192c5c51218cc94c3d4af17f58daee089ba1fd44e3dbd98a00000000", "SysvarRent111111111111111111111111111111111"}
    };
    final var hex = HexFormat.of();
    for (final var vector : vectors) {
      final byte[] bytes = hex.parseHex(vector[0]);
      final var encoded = vector[1];
      assertEquals(encoded, Base58.encode(bytes), encoded);
      assertArrayEquals(bytes, Base58.decode(encoded), encoded);
      final var publicKey = PublicKey.fromBase58Encoded(encoded);
      assertArrayEquals(bytes, publicKey.toByteArray(), encoded);
      assertEquals(encoded, publicKey.toBase58(), encoded);
    }
  }

  @Test
  void testSliceEncode() {
    final long seed = new SecureRandom().nextLong();
    final var random = new Random(seed);
    final byte[] buffer = new byte[256];
    random.nextBytes(buffer);
    Arrays.fill(buffer, 40, 48, (byte) 0); // zero run so some slices lead with zero bytes
    final byte[] untouched = buffer.clone();

    for (int offset = 0; offset <= 64; offset += 3) {
      for (int to = offset; to <= buffer.length; to += 13) {
        final var expected = Base58.encode(Arrays.copyOfRange(buffer, offset, to));
        assertEquals(expected, Base58.encode(buffer, offset, to), "seed=" + seed + " offset=" + offset + " to=" + to);
      }
    }
    assertArrayEquals(untouched, buffer, "encode must not mutate its input");

    final char[] output = new char[buffer.length << 1];
    final int outputStart = Base58.encode(buffer, output);
    assertEquals(Base58.encode(buffer), new String(output, outputStart, output.length - outputStart));
    assertArrayEquals(untouched, buffer);
  }

  @Test
  void testMutableEncode() {
    final long seed = new SecureRandom().nextLong();
    final var random = new Random(seed);
    final char[] output = new char[64];
    final char[] encoded = new char[64];
    for (int leadingZeros = 0; leadingZeros <= 3; ++leadingZeros) {
      for (int iteration = 0; iteration < 64; ++iteration) {
        final byte[] key = new byte[PublicKey.PUBLIC_KEY_LENGTH];
        random.nextBytes(key);
        Arrays.fill(key, 0, leadingZeros, (byte) 0);
        final var msg = "seed=" + seed + " leadingZeros=" + leadingZeros;
        final var expected = Base58.encode(key);

        final int outputStart = Base58.mutableEncode(key.clone(), output);
        assertEquals(expected, new String(output, outputStart, output.length - outputStart), msg);

        // larger maxLen values consume leading input bytes so inputStart advances past leadingZeroes,
        // and maxLen 40 can consume the entire number; `encoded` is deliberately reused dirty
        for (final int maxLen : new int[]{1, 2, 3, 4, 5, 6, 7, 8, 16, 24, 32, 40}) {
          final byte[] mutable = key.clone();
          final char[] shortEncoded = new char[maxLen << 1];
          final long offsets = Base58.beginMutableEncode(mutable, maxLen, shortEncoded);
          final int shortStart = (int) offsets;
          final int shortLen = shortEncoded.length - shortStart;
          final int encodedStart = encoded.length - shortLen;
          final int keyStart = Base58.continueMutableEncode(
              mutable,
              (int) (offsets >>> 48),
              (int) (offsets >>> 32) & 0xFFFF,
              encodedStart,
              encoded
          );
          assertEquals(
              expected,
              new String(encoded, keyStart, encodedStart - keyStart) + new String(shortEncoded, shortStart, shortLen),
              msg + " maxLen=" + maxLen
          );
        }
      }
    }

    final int allZeroStart = Base58.mutableEncode(new byte[PublicKey.PUBLIC_KEY_LENGTH], output);
    assertEquals("1".repeat(PublicKey.PUBLIC_KEY_LENGTH), new String(output, allZeroStart, output.length - allZeroStart));

    final char[] shortEncoded = new char[8];
    final long offsets = Base58.beginMutableEncode(new byte[PublicKey.PUBLIC_KEY_LENGTH], 4, shortEncoded);
    assertEquals(shortEncoded.length, (int) offsets);
    final int keyStart = Base58.continueMutableEncode(
        new byte[PublicKey.PUBLIC_KEY_LENGTH],
        (int) (offsets >>> 48),
        (int) (offsets >>> 32) & 0xFFFF,
        encoded.length,
        encoded
    );
    assertEquals("1".repeat(PublicKey.PUBLIC_KEY_LENGTH), new String(encoded, keyStart, encoded.length - keyStart));

    // number fully consumed within maxLen: continue must only write the leading '1's,
    // regardless of stale content in the reused output buffer
    for (final char stale : new char[]{'\0', '1', 'z'}) {
      final byte[] tinyKey = new byte[PublicKey.PUBLIC_KEY_LENGTH];
      tinyKey[31] = 1;
      Arrays.fill(encoded, stale);
      final char[] shortTiny = new char[8];
      final long tinyOffsets = Base58.beginMutableEncode(tinyKey, 4, shortTiny);
      final int tinyShortStart = (int) tinyOffsets;
      final int tinyShortLen = shortTiny.length - tinyShortStart;
      final int tinyEncodedStart = encoded.length - tinyShortLen;
      final int tinyKeyStart = Base58.continueMutableEncode(
          tinyKey,
          (int) (tinyOffsets >>> 48),
          (int) (tinyOffsets >>> 32) & 0xFFFF,
          tinyEncodedStart,
          encoded
      );
      assertEquals(
          "1".repeat(31) + "2",
          new String(encoded, tinyKeyStart, tinyEncodedStart - tinyKeyStart) + new String(shortTiny, tinyShortStart, tinyShortLen),
          "stale=" + stale
      );
    }
  }

  @Test
  void testPublicKeyLengthBoundaries() {
    final byte[] maxKey = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(maxKey, (byte) 0xFF);
    final var maxEncoded = "JEKNVnkbo3jma5nREBBJCDoXFVeKkD56V3xKrvRmWxFG";  // 2^256 - 1
    final var overflowed = "JEKNVnkbo3jma5nREBBJCDoXFVeKkD56V3xKrvRmWxFH";  // 2^256: 33 bytes, only the last character differs
    final var underflowed = "4uQeVj5tqViQh7yWWGStvkEG1Zmhx6uasJtWCJziofL";  // 2^248 - 1: 31 bytes, same length as many valid keys

    assertEquals(maxEncoded, Base58.encode(maxKey));
    assertArrayEquals(maxKey, Base58.decode(maxEncoded));
    assertEquals(maxEncoded, PublicKey.fromBase58Encoded(maxEncoded).toBase58());

    assertEquals(33, Base58.decode(overflowed).length);
    assertEquals(31, Base58.decode(underflowed).length);
    // same string length as a valid 32-byte key: length alone cannot validate a destination
    assertEquals(underflowed.length(), "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA".length());

    for (final var invalid : new String[]{overflowed, underflowed}) {
      final byte[] out = new byte[PublicKey.PUBLIC_KEY_LENGTH];
      final byte[] ascii = invalid.getBytes(StandardCharsets.US_ASCII);
      assertThrows(IllegalArgumentException.class, () -> Base58.decode(invalid, out), invalid);
      assertThrows(IllegalArgumentException.class, () -> Base58.decode(invalid.toCharArray(), 0, invalid.length(), out), invalid);
      assertThrows(IllegalArgumentException.class, () -> Base58.decode(ascii, 0, ascii.length, out), invalid);
      assertThrows(IllegalArgumentException.class, () -> PublicKey.fromBase58Encoded(invalid), invalid);
      assertThrows(IllegalArgumentException.class, () -> PublicKey.fromBase58Encoded(invalid.toCharArray()), invalid);
      assertThrows(IllegalArgumentException.class, () -> PublicKey.fromBase58Encoded(ascii, 0, ascii.length), invalid);
    }

    final byte[] highBit = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    highBit[0] = (byte) 0x80; // 2^255: the top bit must not be interpreted as a sign
    final var highBitEncoded = "9cfBkPsoQ2NPHYPi7b69bcQG8FKfNc33k2UfRxiPFyd9";
    assertEquals(highBitEncoded, Base58.encode(highBit));
    assertArrayEquals(highBit, Base58.decode(highBitEncoded));
  }

  @Test
  void testMaxValueDigitSweep() {
    // 'z' repeated n times decodes to 58^n - 1, the largest value per digit count.
    // An under-allocated limb array fails here with an ArrayIndexOutOfBoundsException.
    final var digits = new StringBuilder(512);
    for (int len = 1; len <= 512; ++len) {
      digits.append('z');
      final var encoded = digits.toString();
      assertEquals(encoded, Base58.encode(Base58.decode(encoded)), encoded);
    }
    for (final int len : new int[]{600, 1_024, 1_688, 2_048, 4_096}) {
      final var encoded = "z".repeat(len);
      assertArrayEquals(referenceDecode(encoded), Base58.decode(encoded), "len=" + len);
    }
  }

  @Test
  void testCanonicalRoundTrip() {
    final long seed = new SecureRandom().nextLong();
    final var random = new Random(seed);
    for (int iteration = 0; iteration < 512; ++iteration) {
      final char[] chars = new char[1 + random.nextInt(200)];
      for (int i = 0; i < chars.length; ++i) {
        chars[i] = ALPHABET.charAt(random.nextInt(58));
      }
      for (int ones = random.nextInt(4), i = 0; i < ones && i < chars.length; ++i) {
        chars[i] = '1';
      }
      final var encoded = new String(chars);
      // base58 is canonical: every valid string is the unique encoding of its decoded value
      assertEquals(encoded, Base58.encode(Base58.decode(encoded)), () -> "seed=" + seed + " input=" + encoded);
    }
  }

  @Test
  void testRandom() {
    final long seed = new SecureRandom().nextLong();
    final var random = new Random(seed);
    final byte[] bytes = new byte[4_096];
    random.nextBytes(bytes);
    final var encoded = Base58.encode(bytes);
    assertArrayEquals(bytes, Base58.decode(encoded), "seed=" + seed);
  }

  @Test
  void testRoundTripLengths() {
    final long seed = new SecureRandom().nextLong();
    final var random = new Random(seed);
    for (int len = 0; len <= 128; ++len) {
      for (int leadingZeros = 0; leadingZeros <= Math.min(len, 4); ++leadingZeros) {
        final byte[] bytes = new byte[len];
        random.nextBytes(bytes);
        Arrays.fill(bytes, 0, leadingZeros, (byte) 0);
        if (leadingZeros < len && bytes[leadingZeros] == 0) {
          bytes[leadingZeros] = 1;
        }
        final var msg = "seed=" + seed + " len=" + len + " leadingZeros=" + leadingZeros;

        final var encoded = Base58.encode(bytes);
        assertArrayEquals(bytes, Base58.decode(encoded), msg);

        final char[] chars = encoded.toCharArray();
        assertArrayEquals(bytes, Base58.decode(chars), msg);

        final char[] shifted = new char[chars.length + 7];
        System.arraycopy(chars, 0, shifted, 3, chars.length);
        assertArrayEquals(bytes, Base58.decode(shifted, 3, chars.length), msg);

        final byte[] out = new byte[len];
        Base58.decode(encoded, out);
        assertArrayEquals(bytes, out, msg);

        final byte[] out2 = new byte[len];
        Base58.decode(shifted, 3, chars.length, out2);
        assertArrayEquals(bytes, out2, msg);

        final byte[] ascii = encoded.getBytes(StandardCharsets.US_ASCII);
        final byte[] shiftedAscii = new byte[ascii.length + 7];
        System.arraycopy(ascii, 0, shiftedAscii, 3, ascii.length);
        assertArrayEquals(bytes, Base58.decode(ascii, 0, ascii.length), msg);
        assertArrayEquals(bytes, Base58.decode(shiftedAscii, 3, ascii.length), msg);

        final byte[] out3 = new byte[len];
        Base58.decode(shiftedAscii, 3, ascii.length, out3);
        assertArrayEquals(bytes, out3, msg);
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
  void testDecodeIntoDirtyBuffer() {
    // every variant must fully overwrite `out`, including the leading zero bytes
    final byte[] expected = {0, 0, 5, 6};
    final var encoded = Base58.encode(expected);
    final byte[] ascii = encoded.getBytes(StandardCharsets.US_ASCII);
    final byte[] out = new byte[expected.length];

    Arrays.fill(out, (byte) 0xFF);
    Base58.decode(encoded, out);
    assertArrayEquals(expected, out);

    Arrays.fill(out, (byte) 0xFF);
    Base58.decode(encoded.toCharArray(), 0, encoded.length(), out);
    assertArrayEquals(expected, out);

    Arrays.fill(out, (byte) 0xFF);
    Base58.decode(ascii, 0, ascii.length, out);
    assertArrayEquals(expected, out);

    final var ones = "1111";
    Arrays.fill(out, (byte) 0xFF);
    Base58.decode(ones, out);
    assertArrayEquals(new byte[4], out);

    Arrays.fill(out, (byte) 0xFF);
    Base58.decode(ones.toCharArray(), 0, 4, out);
    assertArrayEquals(new byte[4], out);

    Arrays.fill(out, (byte) 0xFF);
    Base58.decode(ones.getBytes(StandardCharsets.US_ASCII), 0, 4, out);
    assertArrayEquals(new byte[4], out);

    assertThrows(IllegalArgumentException.class, () -> Base58.decode(ones.toCharArray(), 0, 4, new byte[3]));
    assertThrows(IllegalArgumentException.class, () -> Base58.decode(ones.getBytes(StandardCharsets.US_ASCII), 0, 4, new byte[3]));
    assertThrows(IllegalArgumentException.class, () -> Base58.decode(ones.toCharArray(), 0, 4, new byte[5]));
    assertThrows(IllegalArgumentException.class, () -> Base58.decode(ones.getBytes(StandardCharsets.US_ASCII), 0, 4, new byte[5]));
  }

  @Test
  void testIllegalCharacters() {
    for (final char c : new char[]{'0', 'O', 'I', 'l', '!', ' ', '{', '\u007F', '\u03C0', '\uD83D'}) {
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

