package software.sava.core.encoding;

import java.util.Arrays;

public final class Base58 {

  private static final char[] ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
  private static final char ENCODED_ZERO = ALPHABET[0];
  private static final int[] INDEXES = new int[123];

  static {
    Arrays.fill(INDEXES, -1);
    for (int i = 0; i < ALPHABET.length; i++) {
      INDEXES[ALPHABET[i]] = i;
    }
  }

  public static boolean isBase58(final char c) {
    return Arrays.binarySearch(ALPHABET, c) >= 0;
  }

  public static boolean isBase58(final String str) {
    for (final char c : str.toCharArray()) {
      if (!isBase58(c)) {
        return false;
      }
    }
    return true;
  }

  public static int nonBase58(final String str) {
    final char[] chars = str.toCharArray();
    for (int i = 0; i < chars.length; ++i) {
      final char c = chars[i];
      if (Arrays.binarySearch(ALPHABET, c) < 0) {
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

  public static byte[] decode(final char[] input) {
    return decode(input, 0, input.length);
  }

  public static byte[] decode(final char[] input, final int from, final int len) {
    if (len == 0) {
      return new byte[0];
    }

    final byte[] input58 = new byte[len];
    for (int i = from, i58 = 0; i58 < len; ++i, ++i58) {
      final int c = Character.codePointAt(input, i);
      try {
        final int digit = INDEXES[c];
        if (digit >= 0) {
          input58[i58] = (byte) digit;
          continue;
        }
      } catch (final ArrayIndexOutOfBoundsException ex) {
        // throw below
      }
      throw new IllegalArgumentException("Illegal character " + c + " at position " + i);
    }

    int zeros = 0;
    while (input58[zeros] == 0) {
      if (++zeros == len) {
        return input58;
      }
    }

    final byte[] decoded = new byte[len];
    int outputStart = len;
    for (int inputStart = zeros; ; ) {
      decoded[--outputStart] = divMod(input58, inputStart, 58, 256);
      if (input58[inputStart] == 0) {
        if (++inputStart == len) {
          break;
        }
      }
    }

    while (outputStart < len && decoded[outputStart] == 0) {
      ++outputStart;
    }

    final int start = outputStart - zeros;
    final byte[] zeroPadded = new byte[len - start];
    System.arraycopy(decoded, start, zeroPadded, 0, zeroPadded.length);
    return zeroPadded;
  }

  public static byte[] decode(final String input) {
    final int len = input.length();
    if (len == 0) {
      return new byte[0];
    }

    final byte[] input58 = new byte[len];
    final var codePoints = input.codePoints().iterator();
    for (int i = 0; i < len; ++i) {
      final int c = codePoints.next();
      try {
        final int digit = INDEXES[c];
        if (digit >= 0) {
          input58[i] = (byte) digit;
          continue;
        }
      } catch (final ArrayIndexOutOfBoundsException ex) {
        // throw below
      }
      throw new IllegalArgumentException("Illegal character " + c + " at position " + i);
    }

    int zeros = 0;
    while (input58[zeros] == 0) {
      if (++zeros == len) {
        return input58;
      }
    }

    final byte[] decoded = new byte[len];
    int outputStart = len;
    for (int inputStart = zeros; ; ) {
      decoded[--outputStart] = divMod(input58, inputStart, 58, 256);
      if (input58[inputStart] == 0) {
        if (++inputStart == len) {
          break;
        }
      }
    }

    while (outputStart < len && decoded[outputStart] == 0) {
      ++outputStart;
    }

    final int start = outputStart - zeros;
    final byte[] zeroPadded = new byte[len - start];
    System.arraycopy(decoded, start, zeroPadded, 0, zeroPadded.length);
    return zeroPadded;
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

  public static int continueMutableEncode(final byte[] input,
                                          int leadingZeroes,
                                          int inputStart,
                                          int outputStart,
                                          final char[] output) {
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
