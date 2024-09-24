package software.sava.core.encoding;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.PrimitiveIterator;

public final class Jex {

  static final byte[] LOWER_BYTES = new byte[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
      'a', 'b', 'c', 'd', 'e', 'f'};
  static final byte[] UPPER_BYTES = new byte[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
      'A', 'B', 'C', 'D', 'E', 'F'};
  private static final int INVALID = -1;
  private static final char[] LOWER = new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
      'a', 'b', 'c', 'd', 'e', 'f'};
  private static final char[] UPPER = new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
      'A', 'B', 'C', 'D', 'E', 'F'};
  private static final int MAX_CHAR = 'f';
  private static final int[] DIGITS = new int[MAX_CHAR + 1];

  static {
    Arrays.fill(DIGITS, INVALID);
    for (final char c : LOWER) {
      DIGITS[c] = Character.digit(c, 16);
    }
    for (int i = 10; i < UPPER.length; ++i) {
      final char c = UPPER[i];
      DIGITS[c] = Character.digit(c, 16);
    }
  }

  private static RuntimeException createIllegalLengthException(final int len) {
    return new IllegalArgumentException(String.format("Invalid hex encoding length of %d.", len));
  }

  private static RuntimeException createIllegalCharException(final byte chr, final int pos) {
    return createIllegalCharException((char) chr, pos);
  }

  private static RuntimeException createIllegalCharException(final char chr, final int pos) {
    return new IllegalArgumentException(String
        .format("Invalid character '%c' for hex encoding at position %d.", chr, pos));
  }

  public static String encode(final byte[] data) {
    return new String(encode(data, LOWER));
  }

  public static String encodeUpper(final byte[] data) {
    return new String(encode(data, UPPER));
  }

  public static char[] encodeChars(final byte[] data) {
    return encode(data, LOWER);
  }

  public static char[] encodeUpperChars(final byte[] data) {
    return encode(data, UPPER);
  }

  private static char[] encode(final byte[] data, final char[] alpha) {
    final char[] hex = new char[data.length << 1];
    encode(data, hex, 0, alpha);
    return hex;
  }

  private static void encode(final byte[] data,
                             final char[] out,
                             int outOffset,
                             final char[] alpha) {
    final int len = data.length;
    for (int i = 0, d; i < len; ) {
      d = data[i++] & 0xff;
      out[outOffset++] = alpha[d >>> 4];
      out[outOffset++] = alpha[d & 0xf];
    }
  }

  public static byte[] encodeBytes(final byte[] data) {
    return encodeBytes(data, LOWER_BYTES);
  }

  public static byte[] encodeUpperBytes(final byte[] data) {
    return encodeBytes(data, UPPER_BYTES);
  }

  static byte[] encodeBytes(final byte[] data, final byte[] alpha) {
    final byte[] hex = new byte[data.length << 1];
    encodeBytes(data, hex, 0, alpha);
    return hex;
  }

  public static void encodeBytes(final byte[] data,
                                 final byte[] out,
                                 final int outOffset) {
    encodeBytes(data, out, outOffset, LOWER_BYTES);
  }

  public static void encodeUpperBytes(final byte[] data,
                                      final byte[] out,
                                      final int outOffset) {
    encodeBytes(data, out, outOffset, UPPER_BYTES);
  }

  static void encodeBytes(final byte[] data,
                          final byte[] out,
                          int outOffset,
                          final byte[] alpha) {
    final int len = data.length;
    for (int i = 0, d; i < len; ) {
      d = data[i++] & 0xff;
      out[outOffset++] = alpha[d >>> 4];
      out[outOffset++] = alpha[d & 0xf];
    }
  }

  public static String encode(final ByteBuffer data) {
    return new String(encode(data, LOWER));
  }

  public static String encodeUpper(final ByteBuffer data) {
    return new String(encode(data, UPPER));
  }

  public static char[] encodeChars(final ByteBuffer data) {
    return encode(data, LOWER);
  }

  public static char[] encodeUpperChars(final ByteBuffer data) {
    return encode(data, UPPER);
  }

  private static char[] encode(final ByteBuffer data, final char[] alpha) {
    final char[] hex = new char[data.limit() << 1];
    encode(data, hex, 0, alpha);
    return hex;
  }

  public static void encodeChars(final ByteBuffer data, final char[] out, final int outOffset) {
    encode(data, out, outOffset, LOWER);
  }

  public static void encodeUpperChars(final ByteBuffer data, final char[] out, final int outOffset) {
    encode(data, out, outOffset, UPPER);
  }

  private static void encode(final ByteBuffer data, final char[] out, int outOffset, final char[] alpha) {
    final int max = outOffset + (data.limit() << 1);
    for (int d; outOffset < max; ) {
      d = data.get() & 0xff;
      out[outOffset++] = alpha[d >>> 4];
      out[outOffset++] = alpha[d & 0xf];
    }
  }

  public static byte[] encodeBytes(final ByteBuffer data) {
    return encodeBytes(data, LOWER_BYTES);
  }

  public static byte[] encodeUpperBytes(final ByteBuffer data) {
    return encodeBytes(data, UPPER_BYTES);
  }

  static byte[] encodeBytes(final ByteBuffer data, final byte[] alpha) {
    final byte[] hex = new byte[data.limit() << 1];
    encodeBytes(data, hex, 0, alpha);
    return hex;
  }

  public static void encodeBytes(final ByteBuffer data, final byte[] out, final int outOffset) {
    encodeBytes(data, out, outOffset, LOWER_BYTES);
  }

  public static void encodeUpperBytes(final ByteBuffer data, final byte[] out, final int outOffset) {
    encodeBytes(data, out, outOffset, UPPER_BYTES);
  }

  static void encodeBytes(final ByteBuffer data, final byte[] out, int outOffset, final byte[] alpha) {
    final int max = outOffset + (data.limit() << 1);
    for (int d; outOffset < max; ) {
      d = data.get() & 0xff;
      out[outOffset++] = alpha[d >>> 4];
      out[outOffset++] = alpha[d & 0xf];
    }
  }

  public static String encode(final byte[] data, final int offset, final int len) {
    return new String(encode(data, offset, len, LOWER));
  }

  public static String encodeUpper(final byte[] data, final int offset, final int len) {
    return new String(encode(data, offset, len, UPPER));
  }

  public static char[] encodeChars(final byte[] data, final int offset, final int len) {
    return encode(data, offset, len, LOWER);
  }

  public static char[] encodeUpperChars(final byte[] data, final int offset, final int len) {
    return encode(data, offset, len, UPPER);
  }

  private static char[] encode(final byte[] data, final int offset, final int len, final char[] alpha) {
    final char[] hex = new char[len << 1];
    encode(data, offset, len, hex, 0, alpha);
    return hex;
  }

  public static void encodeChars(final byte[] data, int offset, final int len, final char[] out, int outOffset) {
    encode(data, offset, len, out, outOffset, LOWER);
  }

  public static void encodeUpperChars(final byte[] data, final int offset, final int len, final char[] out, int outOffset) {
    encode(data, offset, len, out, outOffset, UPPER);
  }

  private static void encode(final byte[] data,
                             int offset,
                             final int len,
                             final char[] out,
                             int outOffset,
                             final char[] alpha) {
    final int max = offset + len;
    for (int d; offset < max; ++offset) {
      d = data[offset] & 0xff;
      out[outOffset++] = alpha[d >>> 4];
      out[outOffset++] = alpha[d & 0xf];
    }
  }

  public static String encode(final ByteBuffer data, final int len) {
    return new String(encode(data, len, LOWER));
  }

  public static String encodeUpper(final ByteBuffer data, final int len) {
    return new String(encode(data, len, UPPER));
  }

  public static char[] encodeChars(final ByteBuffer data, final int len) {
    return encode(data, len, LOWER);
  }

  public static char[] encodeUpperChars(final ByteBuffer data, final int len) {
    return encode(data, len, UPPER);
  }

  private static char[] encode(final ByteBuffer data, final int len, final char[] alpha) {
    final char[] hex = new char[len << 1];
    encode(data, len, hex, 0, alpha);
    return hex;
  }

  public static void encodeChars(final ByteBuffer data, final int len, final char[] out, final int outOffset) {
    encode(data, len, out, outOffset, LOWER);
  }

  public static void encodeUpperChars(final ByteBuffer data, final int len, final char[] out, final int outOffset) {
    encode(data, len, out, outOffset, UPPER);
  }

  private static void encode(final ByteBuffer data,
                             final int len,
                             final char[] out,
                             int outOffset,
                             final char[] alpha) {
    final int max = outOffset + (len << 1);
    for (int d; outOffset < max; ) {
      d = data.get() & 0xff;
      out[outOffset++] = alpha[d >>> 4];
      out[outOffset++] = alpha[d & 0xf];
    }
  }

  public static byte[] encodeBytes(final ByteBuffer data, final int len) {
    return encodeBytes(data, len, LOWER_BYTES);
  }

  public static byte[] encodeUpperBytes(final ByteBuffer data, final int len) {
    return encodeBytes(data, len, UPPER_BYTES);
  }

  private static byte[] encodeBytes(final ByteBuffer data, final int len, final byte[] alpha) {
    final byte[] hex = new byte[len << 1];
    encodeBytes(data, len, hex, 0, alpha);
    return hex;
  }

  public static void encodeBytes(final ByteBuffer data, final int len, final byte[] out, final int outOffset) {
    encodeBytes(data, len, out, outOffset, LOWER_BYTES);
  }

  public static void encodeUpperBytes(final ByteBuffer data, final int len, final byte[] out, final int outOffset) {
    encodeBytes(data, len, out, outOffset, UPPER_BYTES);
  }

  private static void encodeBytes(final ByteBuffer data,
                                  final int len,
                                  final byte[] out,
                                  int outOffset,
                                  final byte[] alpha) {
    final int max = outOffset + (len << 1);
    for (int d; outOffset < max; ) {
      d = data.get() & 0xff;
      out[outOffset++] = alpha[d >>> 4];
      out[outOffset++] = alpha[d & 0xf];
    }
  }

  public static byte[] encodeBytes(final byte[] data, final int offset, final int len) {
    return encodeBytes(data, offset, len, LOWER_BYTES);
  }

  public static byte[] encodeUpperBytes(final byte[] data, final int offset, final int len) {
    return encodeBytes(data, offset, len, UPPER_BYTES);
  }

  static byte[] encodeBytes(final byte[] data, final int offset, final int len, final byte[] alpha) {
    final byte[] hex = new byte[len << 1];
    encodeBytes(data, offset, len, hex, 0, alpha);
    return hex;
  }

  public static void encodeBytes(final byte[] data,
                                 final int offset,
                                 final int len,
                                 final byte[] out,
                                 final int outOffset) {
    encodeBytes(data, offset, len, out, outOffset, LOWER_BYTES);
  }

  public static void encodeUpperBytes(final byte[] data,
                                      final int offset,
                                      final int len,
                                      final byte[] out,
                                      final int outOffset) {
    encodeBytes(data, offset, len, out, outOffset, UPPER_BYTES);
  }

  static void encodeBytes(final byte[] data,
                          int offset,
                          final int len,
                          final byte[] out,
                          int outOffset,
                          final byte[] alpha) {
    final int max = offset + len;
    for (int d; offset < max; ++offset) {
      d = data[offset] & 0xff;
      out[outOffset++] = alpha[d >>> 4];
      out[outOffset++] = alpha[d & 0xf];
    }
  }

  public static String encodeReverse(final byte[] data, final int offset, final int len) {
    return new String(encodeReverse(data, offset, len, LOWER));
  }

  public static String encodeUpperReverse(final byte[] data, final int offset, final int len) {
    return new String(encodeReverse(data, offset, len, UPPER));
  }

  public static char[] encodeReverseChars(final byte[] data, final int offset, final int len) {
    return encodeReverse(data, offset, len, LOWER);
  }

  public static char[] encodeUpperReverseChars(final byte[] data, final int offset, final int len) {
    return encodeReverse(data, offset, len, UPPER);
  }

  private static char[] encodeReverse(final byte[] data,
                                      final int offset,
                                      final int len,
                                      final char[] alpha) {
    final char[] hex = new char[len << 1];
    encodeReverse(data, offset, len, hex, 0, alpha);
    return hex;
  }

  public static void encodeReverseChars(final byte[] data,
                                        final int offset,
                                        final int len,
                                        final char[] out,
                                        final int outOffset) {
    encodeReverse(data, offset, len, out, outOffset, LOWER);
  }

  public static void encodeUpperReverseChars(final byte[] data,
                                             final int offset,
                                             final int len,
                                             final char[] out,
                                             final int outOffset) {
    encodeReverse(data, offset, len, out, outOffset, UPPER);
  }

  private static void encodeReverse(final byte[] data,
                                    int offset,
                                    final int len,
                                    final char[] out,
                                    int outOffset,
                                    final char[] alpha) {
    final int min = offset - len;
    for (int d; offset > min; --offset) {
      d = data[offset] & 0xff;
      out[outOffset++] = alpha[d >>> 4];
      out[outOffset++] = alpha[d & 0xf];
    }
  }

  public static String encodeReverse(final ByteBuffer data, int offset, final int len) {
    return new String(encodeReverse(data, offset, len, LOWER));
  }

  public static String encodeUpperReverse(final ByteBuffer data, int offset, final int len) {
    return new String(encodeReverse(data, offset, len, UPPER));
  }

  public static char[] encodeReverseChars(final ByteBuffer data, int offset, final int len) {
    return encodeReverse(data, offset, len, LOWER);
  }

  public static char[] encodeUpperReverseChars(final ByteBuffer data, int offset, final int len) {
    return encodeReverse(data, offset, len, UPPER);
  }

  private static char[] encodeReverse(final ByteBuffer data, int offset, final int len, final char[] alpha) {
    final char[] hex = new char[len << 1];
    encodeReverse(data, offset, len, hex, 0, alpha);
    return hex;
  }

  public static void encodeReverseChars(final ByteBuffer data,
                                        final int offset,
                                        final int len,
                                        final char[] out,
                                        final int outOffset) {
    encodeReverse(data, offset, len, out, outOffset, LOWER);
  }

  public static void encodeUpperReverseChars(final ByteBuffer data,
                                             final int offset,
                                             final int len,
                                             final char[] out,
                                             final int outOffset) {
    encodeReverse(data, offset, len, out, outOffset, UPPER);
  }

  private static void encodeReverse(final ByteBuffer data,
                                    int offset,
                                    final int len,
                                    final char[] out,
                                    int outOffset,
                                    final char[] alpha) {
    final int min = offset - len;
    for (int d; offset > min; --offset) {
      d = data.get(offset) & 0xff;
      out[outOffset++] = alpha[d >>> 4];
      out[outOffset++] = alpha[d & 0xf];
    }
  }

  public static byte[] encodeReverseBytes(final byte[] data, final int offset, final int len) {
    return encodeReverseBytes(data, offset, len, LOWER_BYTES);
  }

  public static byte[] encodeUpperReverseBytes(final byte[] data, final int offset, final int len) {
    return encodeReverseBytes(data, offset, len, UPPER_BYTES);
  }

  static byte[] encodeReverseBytes(final byte[] data,
                                   int offset,
                                   final int len,
                                   final byte[] alpha) {
    final byte[] hex = new byte[len << 1];
    encodeReverseBytes(data, offset, len, hex, 0, alpha);
    return hex;
  }

  public static void encodeReverseBytes(final byte[] data,
                                        final int offset,
                                        final int len,
                                        final byte[] out,
                                        final int outOffset) {
    encodeReverseBytes(data, offset, len, out, outOffset, LOWER_BYTES);
  }

  public static void encodeUpperReverseBytes(final byte[] data,
                                             final int offset,
                                             final int len,
                                             final byte[] out,
                                             final int outOffset) {
    encodeReverseBytes(data, offset, len, out, outOffset, UPPER_BYTES);
  }

  static void encodeReverseBytes(final byte[] data,
                                 int offset,
                                 final int len,
                                 final byte[] out,
                                 int outOffset,
                                 final byte[] alpha) {
    final int min = offset - len;
    for (int d; offset > min; --offset) {
      d = data[offset] & 0xff;
      out[outOffset++] = alpha[d >>> 4];
      out[outOffset++] = alpha[d & 0xf];
    }
  }

  public static byte[] encodeReverseBytes(final ByteBuffer data, final int offset, final int len) {
    return encodeReverseBytes(data, offset, len, LOWER_BYTES);
  }

  public static byte[] encodeUpperReverseBytes(final ByteBuffer data, final int offset, final int len) {
    return encodeReverseBytes(data, offset, len, UPPER_BYTES);
  }

  static byte[] encodeReverseBytes(final ByteBuffer data,
                                   final int offset,
                                   final int len,
                                   final byte[] alpha) {
    final byte[] hex = new byte[len << 1];
    encodeReverseBytes(data, offset, len, hex, 0, alpha);
    return hex;
  }

  public static void encodeReverseBytes(final ByteBuffer data,
                                        final int offset,
                                        final int len,
                                        final byte[] out,
                                        int outOffset) {
    encodeReverseBytes(data, offset, len, out, outOffset, LOWER_BYTES);
  }

  public static void encodeUpperReverseBytes(final ByteBuffer data,
                                             final int offset,
                                             final int len,
                                             final byte[] out,
                                             final int outOffset) {
    encodeReverseBytes(data, offset, len, out, outOffset, UPPER_BYTES);
  }

  static void encodeReverseBytes(final ByteBuffer data,
                                 int offset,
                                 final int len,
                                 final byte[] out,
                                 int outOffset,
                                 final byte[] alpha) {
    final int min = offset - len;
    for (int d; offset > min; --offset) {
      d = data.get(offset) & 0xff;
      out[outOffset++] = alpha[d >>> 4];
      out[outOffset++] = alpha[d & 0xf];
    }
  }

  public static boolean isValid(final CharSequence hex) {
    if (hex == null) {
      return false;
    }
    final int len = hex.length();
    if ((len & 1) != 0) {
      return false;
    }
    if (len == 0) {
      return true;
    }
    int index = 0;
    do {
      char chr = hex.charAt(index++);
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        return false;
      }
      chr = hex.charAt(index++);
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        return false;
      }
    } while (index < len);
    return true;
  }

  public static boolean isLengthValid(final CharSequence hex) {
    return hex != null && (hex.length() & 1) == 0;
  }

  public static byte[] decode(final CharSequence chars) {
    final byte[] data = new byte[chars.length() >> 1];
    if (data.length == 0) {
      return data;
    }
    for (int i = 0, c = 0; ; ++c) {
      data[i++] = (byte) (DIGITS[chars.charAt(c)] << 4 | DIGITS[chars.charAt(++c)]);
      if (i == data.length) {
        return data;
      }
    }
  }

  public static byte[] decode(final char[] chars) {
    final byte[] data = new byte[chars.length >> 1];
    if (data.length == 0) {
      return data;
    }
    for (int i = 0, c = 0; ; ++c) {
      data[i++] = (byte) (DIGITS[chars[c]] << 4 | DIGITS[chars[++c]]);
      if (i == data.length) {
        return data;
      }
    }
  }

  public static byte[] decode(final byte[] chars) {
    final byte[] data = new byte[chars.length >> 1];
    if (data.length == 0) {
      return data;
    }
    for (int i = 0, c = 0; ; ++c) {
      data[i++] = (byte) (DIGITS[chars[c]] << 4 | DIGITS[chars[++c]]);
      if (i == data.length) {
        return data;
      }
    }
  }

  public static byte[] decode(final ByteBuffer chars) {
    final byte[] data = new byte[chars.limit() >> 1];
    if (data.length == 0) {
      return data;
    }
    int index = 0;
    do {
      data[index++] = (byte) (DIGITS[chars.get()] << 4 | DIGITS[chars.get()]);
    } while (index < data.length);
    return data;
  }

  public static byte[] decodeChecked(final CharSequence chars) {
    final int len = chars.length();
    if (len == 0) {
      return new byte[0];
    }
    if ((len & 1) != 0) {
      throw createIllegalLengthException(len);
    }
    final byte[] data = new byte[len >> 1];
    for (int i = 0, c = 0; ; ++c) {
      char chr = chars.charAt(c);
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException(chr, c);
      }
      int bite = DIGITS[chr] << 4;
      chr = chars.charAt(++c);
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException(chr, c);
      }
      data[i++] = (byte) (bite | DIGITS[chr]);
      if (i == data.length) {
        return data;
      }
    }
  }

  static byte[] decodeCheckedToCharArray(final String hex) {
    return decodeChecked(hex.toCharArray());
  }

  public static byte[] decodeChecked(final char[] chars) {
    if (chars.length == 0) {
      return new byte[0];
    }
    if ((chars.length & 1) != 0) {
      throw createIllegalLengthException(chars.length);
    }
    final byte[] data = new byte[chars.length >> 1];
    for (int i = 0, c = 0; ; ++c) {
      char chr = chars[c];
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException(chr, c);
      }
      int bite = DIGITS[chr] << 4;
      chr = chars[++c];
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException(chr, c);
      }
      data[i++] = (byte) (bite | DIGITS[chr]);
      if (i == data.length) {
        return data;
      }
    }
  }

  public static byte[] decodeChecked(final byte[] chars) {
    if (chars.length == 0) {
      return new byte[0];
    }
    if ((chars.length & 1) != 0) {
      throw createIllegalLengthException(chars.length);
    }
    final byte[] data = new byte[chars.length >> 1];
    for (int i = 0, c = 0; ; ++c) {
      byte chr = chars[c];
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException(chr, c);
      }
      int bite = DIGITS[chr] << 4;
      chr = chars[++c];
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException(chr, c);
      }
      data[i++] = (byte) (bite | DIGITS[chr]);
      if (i == data.length) {
        return data;
      }
    }
  }

  public static byte[] decodeChecked(final ByteBuffer chars) {
    final int len = chars.limit();
    if (len == 0) {
      return new byte[0];
    }
    if ((len & 1) != 0) {
      throw createIllegalLengthException(len);
    }
    final byte[] data = new byte[len >> 1];
    for (int i = 0; ; ) {
      byte chr = chars.get();
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException(chr, i * 2);
      }
      int bite = DIGITS[chr] << 4;
      chr = chars.get();
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException(chr, (i * 2) + 1);
      }
      data[i++] = (byte) (bite | DIGITS[chr]);
      if (i == data.length) {
        return data;
      }
    }
  }

  public static void decode(final CharSequence chars, final byte[] out, int offset) {
    for (int c = 0, len = chars.length(); c < len; ) {
      out[offset++] = (byte) (DIGITS[chars.charAt(c++)] << 4 | DIGITS[chars.charAt(c++)]);
    }
  }

  static void decodeToCharArray(final String hex, final byte[] out, int offset) {
    decode(hex.toCharArray(), out, offset);
  }

  public static void decode(final char[] chars, final byte[] out, int offset) {
    decode(chars, 0, chars.length, out, offset);
  }

  public static void decode(final char[] chars,
                            int offset,
                            final int len,
                            final byte[] out,
                            int outOffset) {
    while (offset < len) {
      out[outOffset++] = (byte) (DIGITS[chars[offset++]] << 4 | DIGITS[chars[offset++]]);
    }
  }

  public static void decodeChecked(final CharSequence chars, final byte[] out, int offset) {
    final int len = chars.length();
    if (len == 0) {
      return;
    }
    if ((len & 1) != 0) {
      throw createIllegalLengthException(len);
    }
    for (int c = 0; ; ++offset) {
      char chr = chars.charAt(c);
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException(chr, c);
      }
      int bite = DIGITS[chr] << 4;
      chr = chars.charAt(++c);
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException(chr, c);
      }
      out[offset] = (byte) (bite | DIGITS[chr]);
      if (++c == len) {
        return;
      }
    }
  }

  static void decodeCheckedToCharArray(final String hex, final byte[] out, int offset) {
    decodeChecked(hex.toCharArray(), out, offset);
  }

  public static void decodeChecked(final char[] chars, final byte[] out, int offset) {
    decodeChecked(chars, 0, chars.length, out, offset);
  }

  public static void decodeChecked(final char[] chars,
                                   int offset,
                                   final int len,
                                   final byte[] out,
                                   int outOffset) {
    if (len == 0) {
      return;
    }
    if ((len & 1) != 0) {
      throw createIllegalLengthException(len);
    }
    for (; ; ++outOffset) {
      char chr = chars[offset];
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException(chr, offset);
      }
      int bite = DIGITS[chr] << 4;
      chr = chars[++offset];
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException(chr, offset);
      }
      out[outOffset] = (byte) (bite | DIGITS[chr]);
      if (++offset == len) {
        return;
      }
    }
  }

  public static void decodeChecked(final byte[] chars, final byte[] out, int offset) {
    decodeChecked(chars, 0, chars.length, out, offset);
  }

  public static void decodeChecked(final byte[] chars,
                                   int offset,
                                   final int len,
                                   final byte[] out,
                                   int outOffset) {
    if (len == 0) {
      return;
    }
    if ((len & 1) != 0) {
      throw createIllegalLengthException(len);
    }
    for (; ; ++outOffset) {
      byte chr = chars[offset];
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException(chr, offset);
      }
      int bite = DIGITS[chr] << 4;
      chr = chars[++offset];
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException(chr, offset);
      }
      out[outOffset] = (byte) (bite | DIGITS[chr]);
      if (++offset == len) {
        return;
      }
    }
  }

  public static void decodeChecked(final ByteBuffer buffer, final byte[] out, int offset) {
    decodeChecked(buffer, 0, buffer.limit(), out, offset);
  }

  public static void decodeChecked(final ByteBuffer buffer,
                                   int offset,
                                   final int len,
                                   final byte[] out,
                                   int outOffset) {
    if (len == 0) {
      return;
    }
    if ((len & 1) != 0) {
      throw createIllegalLengthException(len);
    }
    for (; ; ++outOffset) {
      byte chr = buffer.get();
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException(chr, offset);
      }
      int bite = DIGITS[chr] << 4;
      chr = buffer.get();
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException(chr, ++offset);
      }
      out[outOffset] = (byte) (bite | DIGITS[chr]);
      offset += 2;
      if (offset == len) {
        return;
      }
    }
  }

  public static byte[] decodePrimIter(final CharSequence hex) {
    final byte[] data = new byte[hex.length() >> 1];
    if (data.length == 0) {
      return data;
    }
    final PrimitiveIterator.OfInt chars = hex.chars().iterator();
    int index = 0;
    do {
      data[index++] = (byte) (DIGITS[chars.nextInt()] << 4 | DIGITS[chars.nextInt()]);
    } while (index < data.length);
    return data;
  }

  public static byte[] decodePrimIterChecked(final CharSequence hex) {
    final int len = hex.length();
    if (len == 0) {
      return new byte[0];
    }
    if ((len & 1) != 0) {
      throw createIllegalLengthException(len);
    }
    final byte[] data = new byte[len >> 1];
    final PrimitiveIterator.OfInt chars = hex.chars().iterator();
    for (int index = 0; index < data.length; ) {
      int chr = chars.nextInt();
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException((char) chr, index * 2);
      }
      int bite = DIGITS[chr] << 4;
      chr = chars.nextInt();
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException((char) chr, (index * 2) + 1);
      }
      data[index++] = (byte) (bite | DIGITS[chr]);
    }
    return data;
  }

  public static void decodePrimIter(final CharSequence hex, final byte[] out, int offset) {
    final PrimitiveIterator.OfInt chars = hex.chars().iterator();
    final int max = offset + (hex.length() >> 1);
    while (offset < max) {
      out[offset++] = (byte) (DIGITS[chars.nextInt()] << 4 | DIGITS[chars.nextInt()]);
    }
  }

  public static void decodePrimIterChecked(final CharSequence hex, final byte[] out, int offset) {
    final int len = hex.length();
    if (len == 0) {
      return;
    }
    if ((len & 1) != 0) {
      throw createIllegalLengthException(len);
    }
    final PrimitiveIterator.OfInt chars = hex.chars().iterator();
    for (int index = 0; chars.hasNext(); index += 2) {
      int chr = chars.nextInt();
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException((char) chr, index);
      }
      int bite = DIGITS[chr] << 4;
      chr = chars.nextInt();
      if (chr > MAX_CHAR || DIGITS[chr] == INVALID) {
        throw createIllegalCharException((char) chr, index + 1);
      }
      out[offset++] = (byte) (bite | DIGITS[chr]);
    }
  }

  private Jex() {
  }
}
