package software.sava.core.encoding;

import java.util.Arrays;

public final class Base58 {

  private static final char[] ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
  private static final char ENCODED_ZERO = ALPHABET[0];
  private static final int[] INDEXES = new int[123]; // z == 122

  static {
    Arrays.fill(INDEXES, -1);
    for (int i = 0; i < ALPHABET.length; i++) {
      INDEXES[ALPHABET[i]] = i;
    }
  }

  public static boolean isBase58(final char c) {
    return c < INDEXES.length && INDEXES[c] >= 0;
  }

  public static boolean isBase58(final String str) {
    for (int i = 0, len = str.length(); i < len; ++i) {
      if (!isBase58(str.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  public static int nonBase58(final String str) {
    for (int i = 0, len = str.length(); i < len; ++i) {
      if (!isBase58(str.charAt(i))) {
        return i;
      }
    }
    return -1;
  }

  public static String encode(final byte[] input) {
    return encode(input, 0, input.length);
  }

  public static String encode(final byte[] input, final int offset, final int to) {
    final char[] encoded = new char[input.length << 1]; // upper bound
    final int outputStart = encode(input, offset, to, encoded);
    return new String(encoded, outputStart, encoded.length - outputStart);
  }

  public static int encode(final byte[] input, final char[] output) {
    return encode(input, 0, input.length, output);
  }

  public static int encode(byte[] input, final int offset, final int to, final char[] output) {
    input = Arrays.copyOfRange(input, offset, to);

    int leadingZeroes = 0;
    while (leadingZeroes < input.length && input[leadingZeroes] == 0) {
      ++leadingZeroes;
    }

    int outputStart = output.length;
    for (int inputStart = leadingZeroes; inputStart < input.length; ) {
      output[--outputStart] = ALPHABET[divMod(input, inputStart, 256, 58)];
      if (input[inputStart] == 0) {
        ++inputStart;
      }
    }

    while (outputStart < output.length && output[outputStart] == ENCODED_ZERO) {
      ++outputStart;
    }
    for (; leadingZeroes > 0; --leadingZeroes) {
      output[--outputStart] = ENCODED_ZERO;
    }

    return outputStart;
  }

  private static final int[] POW_58 = {1, 58, 3_364, 195_112, 11_316_496, 656_356_768};

  private static int digit(final int c, final int position) {
    if (c < INDEXES.length) {
      final int digit = INDEXES[c];
      if (digit >= 0) {
        return digit;
      }
    }
    throw new IllegalArgumentException("Illegal character " + c + " at position " + position);
  }

  private static int limbsLength(final int numDigits) {
    // 5858/1000 > log2(58), +1 rounds the bit bound up, so the limb count never under-allocates.
    return (int) ((numDigits * 5_858L / 1_000 + 1 + 31) >> 5);
  }

  private static int mulAdd(final int[] limbs, final int used, final int mult, final int add) {
    long carry = add;
    for (int i = 0; i < used; ++i) {
      final long product = (limbs[i] & 0xFFFF_FFFFL) * mult + carry;
      limbs[i] = (int) product;
      carry = product >>> 32;
    }
    if (carry != 0) {
      limbs[used] = (int) carry;
      return used + 1;
    }
    return used;
  }

  private static int toLimbs(final char[] input, int i, final int to, final int[] limbs) {
    int used = 0;
    while (i < to) {
      int chunk = digit(input[i], i);
      int numDigits = 1;
      for (++i; numDigits < 5 && i < to; ++numDigits, ++i) {
        chunk = chunk * 58 + digit(input[i], i);
      }
      used = mulAdd(limbs, used, POW_58[numDigits], chunk);
    }
    return used;
  }

  private static int toLimbs(final String input, int i, final int to, final int[] limbs) {
    int used = 0;
    while (i < to) {
      int chunk = digit(input.charAt(i), i);
      int numDigits = 1;
      for (++i; numDigits < 5 && i < to; ++numDigits, ++i) {
        chunk = chunk * 58 + digit(input.charAt(i), i);
      }
      used = mulAdd(limbs, used, POW_58[numDigits], chunk);
    }
    return used;
  }

  private static int toLimbs(final byte[] input, int i, final int to, final int[] limbs) {
    int used = 0;
    while (i < to) {
      int chunk = digit(input[i] & 0xFF, i);
      int numDigits = 1;
      for (++i; numDigits < 5 && i < to; ++numDigits, ++i) {
        chunk = chunk * 58 + digit(input[i] & 0xFF, i);
      }
      used = mulAdd(limbs, used, POW_58[numDigits], chunk);
    }
    return used;
  }

  private static void writeLimbs(final int[] limbs, final int used, final int topBytes, final byte[] out, int o) {
    final int top = limbs[used - 1];
    for (int shift = (topBytes - 1) << 3; shift >= 0; shift -= 8) {
      out[o++] = (byte) (top >>> shift);
    }
    for (int i = used - 2; i >= 0; --i) {
      final int limb = limbs[i];
      out[o++] = (byte) (limb >>> 24);
      out[o++] = (byte) (limb >>> 16);
      out[o++] = (byte) (limb >>> 8);
      out[o++] = (byte) limb;
    }
  }

  private static byte[] toBytes(final int[] limbs, final int used, final int zeros) {
    final int topBytes = (39 - Integer.numberOfLeadingZeros(limbs[used - 1])) >> 3;
    final byte[] out = new byte[zeros + ((used - 1) << 2) + topBytes];
    writeLimbs(limbs, used, topBytes, out, zeros);
    return out;
  }

  private static void toBytes(final int[] limbs, final int used, final int zeros, final byte[] out) {
    final int topBytes = (39 - Integer.numberOfLeadingZeros(limbs[used - 1])) >> 3;
    final int len = zeros + ((used - 1) << 2) + topBytes;
    if (len != out.length) {
      throw new IllegalArgumentException("Decoded " + len + " bytes, expected " + out.length);
    }
    Arrays.fill(out, 0, zeros, (byte) 0);
    writeLimbs(limbs, used, topBytes, out, zeros);
  }

  public static byte[] decode(final char[] input) {
    return decode(input, 0, input.length);
  }

  public static byte[] decode(final char[] input, final int from, final int len) {
    if (len == 0) {
      return new byte[0];
    }
    final int to = from + len;
    int i = from;
    while (input[i] == ENCODED_ZERO) {
      if (++i == to) {
        return new byte[len];
      }
    }
    final int[] limbs = new int[limbsLength(to - i)];
    final int used = toLimbs(input, i, to, limbs);
    return toBytes(limbs, used, i - from);
  }

  /// Decodes directly into `out`, which must exactly fit the decoded value.
  ///
  /// @throws IllegalArgumentException if the input contains a non base58 character or the decoded length does not equal `out.length`.
  public static void decode(final char[] input, final int from, final int len, final byte[] out) {
    final int to = from + len;
    int i = from;
    while (i < to && input[i] == ENCODED_ZERO) {
      ++i;
    }
    if (i == to) {
      if (len != out.length) {
        throw new IllegalArgumentException("Decoded " + len + " bytes, expected " + out.length);
      }
      Arrays.fill(out, (byte) 0);
      return;
    }
    final int[] limbs = new int[limbsLength(to - i)];
    final int used = toLimbs(input, i, to, limbs);
    toBytes(limbs, used, i - from, out);
  }

  /// Decodes base58 ASCII text held in a byte array, e.g. a raw JSON or wire buffer.
  ///
  /// @throws IllegalArgumentException if the input contains a non base58 character.
  public static byte[] decode(final byte[] input, final int from, final int len) {
    if (len == 0) {
      return new byte[0];
    }
    final int to = from + len;
    int i = from;
    while (input[i] == ENCODED_ZERO) {
      if (++i == to) {
        return new byte[len];
      }
    }
    final int[] limbs = new int[limbsLength(to - i)];
    final int used = toLimbs(input, i, to, limbs);
    return toBytes(limbs, used, i - from);
  }

  /// Decodes base58 ASCII text directly into `out`, which must exactly fit the decoded value.
  ///
  /// @throws IllegalArgumentException if the input contains a non base58 character or the decoded length does not equal `out.length`.
  public static void decode(final byte[] input, final int from, final int len, final byte[] out) {
    final int to = from + len;
    int i = from;
    while (i < to && input[i] == ENCODED_ZERO) {
      ++i;
    }
    if (i == to) {
      if (len != out.length) {
        throw new IllegalArgumentException("Decoded " + len + " bytes, expected " + out.length);
      }
      Arrays.fill(out, (byte) 0);
      return;
    }
    final int[] limbs = new int[limbsLength(to - i)];
    final int used = toLimbs(input, i, to, limbs);
    toBytes(limbs, used, i - from, out);
  }

  public static byte[] decode(final String input) {
    final int len = input.length();
    if (len == 0) {
      return new byte[0];
    }
    int i = 0;
    while (input.charAt(i) == ENCODED_ZERO) {
      if (++i == len) {
        return new byte[len];
      }
    }
    final int[] limbs = new int[limbsLength(len - i)];
    final int used = toLimbs(input, i, len, limbs);
    return toBytes(limbs, used, i);
  }

  /// Decodes directly into `out`, which must exactly fit the decoded value.
  ///
  /// @throws IllegalArgumentException if the input contains a non base58 character or the decoded length does not equal `out.length`.
  public static void decode(final String input, final byte[] out) {
    final int len = input.length();
    int i = 0;
    while (i < len && input.charAt(i) == ENCODED_ZERO) {
      ++i;
    }
    if (i == len) {
      if (len != out.length) {
        throw new IllegalArgumentException("Decoded " + len + " bytes, expected " + out.length);
      }
      Arrays.fill(out, (byte) 0);
      return;
    }
    final int[] limbs = new int[limbsLength(len - i)];
    final int used = toLimbs(input, i, len, limbs);
    toBytes(limbs, used, i, out);
  }

  static byte divMod(final byte[] number, final int firstDigit, final int base, final int divisor) {
    int remainder = 0;
    for (int i = firstDigit, num; i < number.length; i++) {
      num = remainder * base + ((int) number[i] & 0xFF);
      number[i] = (byte) (num / divisor);
      remainder = num % divisor;
    }
    return (byte) remainder;
  }

  public static int mutableEncode(final byte[] input, final char[] output) {
    int leadingZeroes = 0;
    while (leadingZeroes < input.length && input[leadingZeroes] == 0) {
      ++leadingZeroes;
    }

    int outputStart = output.length;
    int inputStart = leadingZeroes;

    while (inputStart < input.length) {
      --outputStart;
      output[outputStart] = ALPHABET[divMod(input, inputStart, 256, 58)];
      if (input[inputStart] == 0) {
        ++inputStart;
      }
    }

    while (outputStart < output.length && output[outputStart] == ENCODED_ZERO) {
      ++outputStart;
    }

    while (leadingZeroes > 0) {
      --outputStart;
      output[outputStart] = ENCODED_ZERO;
      --leadingZeroes;
    }

    return outputStart;
  }

  /// Completes an encoding started by [#beginMutableEncode(byte[], int, char[])] into `output`,
  /// which may hold stale content from previous encodings: only characters written by this call
  /// are ever read back.
  public static int continueMutableEncode(final byte[] input,
                                          int leadingZeroes,
                                          int inputStart,
                                          int outputStart,
                                          final char[] output) {
    final int writtenFrom = outputStart;
    while (inputStart < input.length) {
      --outputStart;
      output[outputStart] = ALPHABET[divMod(input, inputStart, 256, 58)];
      if (input[inputStart] == 0) {
        ++inputStart;
      }
    }

    while (outputStart < writtenFrom && output[outputStart] == ENCODED_ZERO) {
      ++outputStart;
    }

    while (leadingZeroes > 0) {
      --outputStart;
      output[outputStart] = ENCODED_ZERO;
      --leadingZeroes;
    }

    return outputStart;
  }

  public static long beginMutableEncode(final byte[] input, final int maxLen, final char[] output) {
    int leadingZeroes = 0;
    while (leadingZeroes < input.length && input[leadingZeroes] == 0) {
      ++leadingZeroes;
    }

    int outputStart = output.length;
    int inputStart = leadingZeroes;
    for (int len = 0; inputStart < input.length && len < maxLen; ++len) {
      --outputStart;
      output[outputStart] = ALPHABET[divMod(input, inputStart, 256, 58)];
      if (input[inputStart] == 0) {
        ++inputStart;
      }
    }
    return outputStart | ((long) leadingZeroes << 48) | ((long) inputStart << 32);
  }

  private Base58() {
  }
}
