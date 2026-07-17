package software.sava.core.borsh;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.serial.Serializable;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.time.Instant;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static java.nio.charset.StandardCharsets.UTF_8;

@SuppressWarnings("unchecked")
public interface Borsh extends Serializable {

  default byte[] writeOptional() {
    final byte[] data = new byte[1 + l()];
    data[0] = 1;
    write(data, 1);
    return data;
  }

  // Serialization Utility Methods

  // String

  @Deprecated(forRemoval = true)
  static String readString(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    return new String(data, offset + Integer.BYTES, len, UTF_8);
  }

  @Deprecated(forRemoval = true)
  static String string(final byte[] data, final int offset) {
    return readString(data, offset);
  }

  @Deprecated(forRemoval = true)
  static byte[] getBytes(final String str) {
    return str == null || str.isBlank() ? null : str.getBytes(UTF_8);
  }

  @Deprecated(forRemoval = true)
  static byte[][] getBytes(final String[] strings) {
    final int len = strings.length;
    final byte[][] bytes = new byte[len][];
    for (int i = 0; i < len; ++i) {
      bytes[i] = getBytes(strings[i]);
    }
    return bytes;
  }

  static int len(final String val) {
    final int len = val.getBytes(UTF_8).length;
    return Integer.BYTES + len;
  }

  @Deprecated(forRemoval = true)
  static int lenOptional(final String val) {
    return val == null ? 1 : 1 + len(val);
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final String[] array) {
    int len = Integer.BYTES;
    for (final var s : array) {
      len += len(s);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final String[][] array) {
    int len = Integer.BYTES;
    for (final var a : array) {
      len += lenVector(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int readArray(final String[] result, final byte[] data, final int offset) {
    int o = offset;
    String s;
    for (int i = 0, len; i < result.length; ++i) {
      len = ByteUtil.getInt32LE(data, o);
      o += Integer.BYTES;
      s = new String(data, o, len, UTF_8);
      result[i] = s;
      o += len;
    }
    return o - offset;
  }

  @Deprecated(forRemoval = true)
  static int readArray(final String[][] result, final byte[] data, final int offset) {
    int i = offset;
    for (final var out : result) {
      i += readArray(out, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static String[] readStringVector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final var result = new String[len];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static String[][] readMultiDimensionStringVector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final var result = new String[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = readStringVector(data, offset);
      result[i] = instance;
      offset += lenVector(instance);
    }
    return result;
  }

  @Deprecated(forRemoval = true)
  static String[][] readMultiDimensionStringVectorArray(final int fixedLength,
                                                        final byte[] data,
                                                        final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final var result = new String[len][fixedLength];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  static int write(final String str, final byte[] data, final int offset) {
    return writeVector(str.getBytes(UTF_8), data, offset);
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final String[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += write(a, data, i);
    }
    return i - offset;
  }

  private static IllegalArgumentException invalidArrayLength(final Object type,
                                                             final int expectedLength,
                                                             final int actualLength) {
    return new IllegalArgumentException(String.format(
        "%s.length must be %d, not %d.",
        type.getClass().getSimpleName(), expectedLength, actualLength
    ));
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final String[] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != fixedLength) {
      throw invalidArrayLength(array, fixedLength, array.length);
    }
    return writeArray(array, data, offset);
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final String[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final String[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArray(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final String[][] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, fixedLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final String[][] array,
                               final int firstDimensionLength,
                               final int secondDimensionLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != firstDimensionLength) {
      throw invalidArrayLength(array, firstDimensionLength, array.length);
    }
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, secondDimensionLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final String[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    int i = Integer.BYTES + offset;
    for (final var a : array) {
      i += writeVector(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArrayChecked(final String[][] array,
                                     final int fixedLength,
                                     final byte[] data,
                                     final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArrayChecked(array, fixedLength, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArray(final String[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  // byte

  @Deprecated(forRemoval = true)
  static int readArray(final byte[] result, final byte[] data, final int offset) {
    System.arraycopy(data, offset, result, 0, result.length);
    return result.length;
  }

  @Deprecated(forRemoval = true)
  static byte[] readbyteVector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final var result = new byte[len];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static int readArray(final byte[][] result, final byte[] data, final int offset) {
    int i = offset;
    for (final var out : result) {
      i += readArray(out, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static byte[][] readMultiDimensionbyteVector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final var result = new byte[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = readbyteVector(data, offset);
      result[i] = instance;
      offset += lenVector(instance);
    }
    return result;
  }

  @Deprecated(forRemoval = true)
  static byte[][] readMultiDimensionbyteVectorArray(final int fixedLength,
                                                    final byte[] data,
                                                    final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final byte[][] result = new byte[len][fixedLength];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static int writeOptionalbyte(final OptionalInt val, final byte[] data, final int offset) {
    if (val == null || val.isEmpty()) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      data[offset + 1] = (byte) val.getAsInt();
      return 2;
    }
  }

  @Deprecated(forRemoval = true)
  static int writeOptional(final Byte val, final byte[] data, final int offset) {
    if (val == null) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      data[offset + 1] = val;
      return 2;
    }
  }

  static int writeArray(final byte[] array, final byte[] data, final int offset) {
    System.arraycopy(array, 0, data, offset, array.length);
    return array.length;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final byte[] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != fixedLength) {
      throw invalidArrayLength(array, fixedLength, array.length);
    }
    return writeArray(array, data, offset);
  }

  static int writeVector(final byte[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeOptionalArray(final byte[] bytes, final byte[] data, final int offset) {
    if (bytes == null || bytes.length == 0) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      return 1 + writeArray(bytes, data, offset + 1);
    }
  }

  @Deprecated(forRemoval = true)
  static int writeOptionalVector(final byte[] bytes, final byte[] data, final int offset) {
    if (bytes == null || bytes.length == 0) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      return 1 + writeVector(bytes, data, offset + 1);
    }
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final byte[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArray(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final byte[][] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, fixedLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final byte[][] array,
                               final int firstDimensionLength,
                               final int secondDimensionLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != firstDimensionLength) {
      throw invalidArrayLength(array, firstDimensionLength, array.length);
    }
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, secondDimensionLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final byte[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    int i = Integer.BYTES + offset;
    for (final var a : array) {
      i += writeVector(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArrayChecked(final byte[][] array,
                                     final int fixedLength,
                                     final byte[] data,
                                     final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArrayChecked(array, fixedLength, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArray(final byte[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int lenOptional(final Byte val) {
    return val == null ? 1 : 2;
  }

  @Deprecated(forRemoval = true)
  static int lenOptionalbyte(final OptionalInt val) {
    return val == null || val.isEmpty() ? 1 : 2;
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final byte[] array) {
    return array.length;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final byte[] array) {
    return Integer.BYTES + array.length;
  }

  @Deprecated(forRemoval = true)
  static int lenOptionalVector(final byte[] array) {
    if (array == null || array.length == 0) {
      return 1;
    } else {
      return 1 + lenVector(array);
    }
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final byte[][] array) {
    int len = 0;
    for (final var a : array) {
      len += lenArray(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final byte[][] array) {
    int len = Integer.BYTES;
    for (final var a : array) {
      len += lenVector(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVectorArray(final byte[][] array) {
    return Integer.BYTES + lenArray(array);
  }

  // boolean

  @Deprecated(forRemoval = true)
  static int readArray(final boolean[] result, final byte[] data, final int offset) {
    int o = offset;
    for (int i = 0; i < result.length; ++i) {
      result[i] = data[o++] == 1;
    }
    return o - offset;
  }

  @Deprecated(forRemoval = true)
  static int readArray(final boolean[][] result, final byte[] data, final int offset) {
    int i = offset;
    for (final var out : result) {
      i += readArray(out, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static boolean[] readbooleanVector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final var result = new boolean[len];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static boolean[][] readMultiDimensionbooleanVector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final var result = new boolean[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = readbooleanVector(data, offset);
      result[i] = instance;
      offset += lenVector(instance);
    }
    return result;
  }

  @Deprecated(forRemoval = true)
  static boolean[][] readMultiDimensionbooleanVectorArray(final int fixedLength,
                                                          final byte[] data,
                                                          final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final boolean[][] result = new boolean[len][fixedLength];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static int write(final boolean val, final byte[] data, final int offset) {
    data[offset] = (byte) (val ? 1 : 0);
    return 1;
  }

  @Deprecated(forRemoval = true)
  static int writeOptional(final Boolean val, final byte[] data, final int offset) {
    if (val == null) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      write(val, data, offset + 1);
      return 2;
    }
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final boolean[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += write(a, data, i);
    }
    return array.length;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final boolean[] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != fixedLength) {
      throw invalidArrayLength(array, fixedLength, array.length);
    }
    return writeArray(array, data, offset);
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final boolean[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final boolean[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArray(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final boolean[][] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, fixedLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final boolean[][] array,
                               final int firstDimensionLength,
                               final int secondDimensionLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != firstDimensionLength) {
      throw invalidArrayLength(array, firstDimensionLength, array.length);
    }
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, secondDimensionLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final boolean[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    int i = Integer.BYTES + offset;
    for (final var a : array) {
      i += writeVector(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArrayChecked(final boolean[][] array,
                                     final int fixedLength,
                                     final byte[] data,
                                     final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArrayChecked(array, fixedLength, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArray(final boolean[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int lenOptional(final Boolean val) {
    return val == null ? 1 : 2;
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final boolean[] array) {
    return array.length;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final boolean[] array) {
    return Integer.BYTES + array.length;
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final boolean[][] array) {
    int len = 0;
    for (final var a : array) {
      len += lenArray(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final boolean[][] array) {
    int len = Integer.BYTES;
    for (final var a : array) {
      len += lenVector(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVectorArray(final boolean[][] array) {
    return Integer.BYTES + lenArray(array);
  }

  // short

  @Deprecated(forRemoval = true)
  static int readArray(final short[] result, final byte[] data, final int offset) {
    int o = offset;
    for (int i = 0; i < result.length; ++i) {
      result[i] = ByteUtil.getInt16LE(data, o);
      o += Short.BYTES;
    }
    return o - offset;
  }

  @Deprecated(forRemoval = true)
  static int readArray(final short[][] result, final byte[] data, final int offset) {
    int i = offset;
    for (final var out : result) {
      i += readArray(out, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static short[] readshortVector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final var result = new short[len];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static short[][] readMultiDimensionshortVector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final var result = new short[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = readshortVector(data, offset);
      result[i] = instance;
      offset += lenVector(instance);
    }
    return result;
  }

  @Deprecated(forRemoval = true)
  static short[][] readMultiDimensionshortVectorArray(final int fixedLength,
                                                      final byte[] data,
                                                      final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final short[][] result = new short[len][fixedLength];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static int writeOptionalshort(final OptionalInt val, final byte[] data, final int offset) {
    if (val == null || val.isEmpty()) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      ByteUtil.putInt16LE(data, offset + 1, (short) val.getAsInt());
      return 1 + Short.BYTES;
    }
  }

  @Deprecated(forRemoval = true)
  static int writeOptional(final Short val, final byte[] data, final int offset) {
    if (val == null) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      ByteUtil.putInt16LE(data, offset + 1, val);
      return 1 + Short.BYTES;
    }
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final short[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      ByteUtil.putInt16LE(data, i, a);
      i += Short.BYTES;
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final short[] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != fixedLength) {
      throw invalidArrayLength(array, fixedLength, array.length);
    }
    return writeArray(array, data, offset);
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final short[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final short[][] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, fixedLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final short[][] array,
                               final int firstDimensionLength,
                               final int secondDimensionLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != firstDimensionLength) {
      throw invalidArrayLength(array, firstDimensionLength, array.length);
    }
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, secondDimensionLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final short[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArray(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final short[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    int i = Integer.BYTES + offset;
    for (final var a : array) {
      i += writeVector(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArrayChecked(final short[][] array,
                                     final int fixedLength,
                                     final byte[] data,
                                     final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArrayChecked(array, fixedLength, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArray(final short[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int lenOptional(final Short val) {
    return val == null ? 1 : 1 + Short.BYTES;
  }

  @Deprecated(forRemoval = true)
  static int lenOptionalshort(final OptionalInt val) {
    return val == null || val.isEmpty() ? 1 : 1 + Short.BYTES;
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final short[] array) {
    return array.length * Short.BYTES;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final short[] array) {
    return Integer.BYTES + lenArray(array);
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final short[][] array) {
    int len = 0;
    for (final var a : array) {
      len += lenArray(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final short[][] array) {
    int len = Integer.BYTES;
    for (final var a : array) {
      len += lenVector(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVectorArray(final short[][] array) {
    return Integer.BYTES + lenArray(array);
  }

  // int

  @Deprecated(forRemoval = true)
  static int readArray(final int[] result, final byte[] data, final int offset) {
    int o = offset;
    for (int i = 0; i < result.length; ++i) {
      result[i] = ByteUtil.getInt32LE(data, o);
      o += Integer.BYTES;
    }
    return o - offset;
  }

  @Deprecated(forRemoval = true)
  static int readArray(final int[][] result, final byte[] data, final int offset) {
    int i = offset;
    for (final var out : result) {
      i += readArray(out, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int[] readintVector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final var result = new int[len];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static int[][] readMultiDimensionintVector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final var result = new int[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = readintVector(data, offset);
      result[i] = instance;
      offset += lenVector(instance);
    }
    return result;
  }

  @Deprecated(forRemoval = true)
  static int[][] readMultiDimensionintVectorArray(final int fixedLength,
                                                  final byte[] data,
                                                  final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final int[][] result = new int[len][fixedLength];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static int writeOptional(final OptionalInt val, final byte[] data, final int offset) {
    if (val == null || val.isEmpty()) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      ByteUtil.putInt32LE(data, offset + 1, val.getAsInt());
      return 1 + Integer.BYTES;
    }
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final int[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      ByteUtil.putInt32LE(data, i, a);
      i += Integer.BYTES;
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final int[] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != fixedLength) {
      throw invalidArrayLength(array, fixedLength, array.length);
    }
    return writeArray(array, data, offset);
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final int[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final int[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArray(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final int[][] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, fixedLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final int[][] array,
                               final int firstDimensionLength,
                               final int secondDimensionLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != firstDimensionLength) {
      throw invalidArrayLength(array, firstDimensionLength, array.length);
    }
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, secondDimensionLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final int[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    int i = Integer.BYTES + offset;
    for (final var a : array) {
      i += writeVector(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArrayChecked(final int[][] array,
                                     final int fixedLength,
                                     final byte[] data,
                                     final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArrayChecked(array, fixedLength, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArray(final int[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int lenOptional(final OptionalInt val) {
    return val == null || val.isEmpty() ? 1 : 1 + Integer.BYTES;
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final int[] array) {
    return array.length * Integer.BYTES;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final int[] array) {
    return Integer.BYTES + lenArray(array);
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final int[][] array) {
    int len = 0;
    for (final var a : array) {
      len += lenArray(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final int[][] array) {
    int len = Integer.BYTES;
    for (final var a : array) {
      len += lenVector(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVectorArray(final int[][] array) {
    return Integer.BYTES + lenArray(array);
  }

  // long

  @Deprecated(forRemoval = true)
  static int readArray(final long[] result, final byte[] data, final int offset) {
    int o = offset;
    for (int i = 0; i < result.length; ++i) {
      result[i] = ByteUtil.getInt64LE(data, o);
      o += Long.BYTES;
    }
    return o - offset;
  }

  @Deprecated(forRemoval = true)
  static int readArray(final long[][] result, final byte[] data, final int offset) {
    int i = offset;
    for (final var out : result) {
      i += readArray(out, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static long[] readlongVector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final var result = new long[len];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static long[][] readMultiDimensionlongVector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final var result = new long[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = readlongVector(data, offset);
      result[i] = instance;
      offset += lenVector(instance);
    }
    return result;
  }

  @Deprecated(forRemoval = true)
  static long[][] readMultiDimensionlongVectorArray(final int fixedLength,
                                                    final byte[] data,
                                                    final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final long[][] result = new long[len][fixedLength];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static int writeOptional(final OptionalLong val, final byte[] data, final int offset) {
    if (val == null || val.isEmpty()) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      ByteUtil.putInt64LE(data, offset + 1, val.getAsLong());
      return 1 + Long.BYTES;
    }
  }

  @Deprecated(forRemoval = true)
  static int writeOptional(final Instant val, final byte[] data, final int offset) {
    if (val == null) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      ByteUtil.putInt64LE(data, offset + 1, val.toEpochMilli());
      return 1 + Long.BYTES;
    }
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final long[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      ByteUtil.putInt64LE(data, i, a);
      i += Long.BYTES;
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final long[] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != fixedLength) {
      throw invalidArrayLength(array, fixedLength, array.length);
    }
    return writeArray(array, data, offset);
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final long[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final long[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArray(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final long[][] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, fixedLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final long[][] array,
                               final int firstDimensionLength,
                               final int secondDimensionLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != firstDimensionLength) {
      throw invalidArrayLength(array, firstDimensionLength, array.length);
    }
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, secondDimensionLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final long[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    int i = Integer.BYTES + offset;
    for (final var a : array) {
      i += writeVector(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArrayChecked(final long[][] array,
                                     final int fixedLength,
                                     final byte[] data,
                                     final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArrayChecked(array, fixedLength, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArray(final long[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int lenOptional(final OptionalLong val) {
    return val == null || val.isEmpty() ? 1 : 1 + Long.BYTES;
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final long[] array) {
    return array.length * Long.BYTES;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final long[] array) {
    return Integer.BYTES + lenArray(array);
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final long[][] array) {
    int len = 0;
    for (final var a : array) {
      len += lenArray(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final long[][] array) {
    int len = Integer.BYTES;
    for (final var a : array) {
      len += lenVector(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVectorArray(final long[][] array) {
    return Integer.BYTES + lenArray(array);
  }

  // float

  @Deprecated(forRemoval = true)
  static int readArray(final float[] result, final byte[] data, final int offset) {
    int o = offset;
    for (int i = 0; i < result.length; ++i) {
      result[i] = ByteUtil.getFloat32LE(data, o);
      o += Float.BYTES;
    }
    return o - offset;
  }

  @Deprecated(forRemoval = true)
  static int readArray(final float[][] result, final byte[] data, final int offset) {
    int i = offset;
    for (final var out : result) {
      i += readArray(out, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static float[] readfloatVector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final var result = new float[len];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static float[][] readMultiDimensionfloatVector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final var result = new float[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = readfloatVector(data, offset);
      result[i] = instance;
      offset += lenVector(instance);
    }
    return result;
  }

  @Deprecated(forRemoval = true)
  static float[][] readMultiDimensionfloatVectorArray(final int fixedLength,
                                                      final byte[] data,
                                                      final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final float[][] result = new float[len][fixedLength];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static int writeOptionalfloat(final OptionalDouble val, final byte[] data, final int offset) {
    if (val == null || val.isEmpty()) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      ByteUtil.putFloat32LE(data, offset + 1, (float) val.getAsDouble());
      return 1 + Float.BYTES;
    }
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final float[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      ByteUtil.putFloat32LE(data, i, a);
      i += Float.BYTES;
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final float[] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != fixedLength) {
      throw invalidArrayLength(array, fixedLength, array.length);
    }
    return writeArray(array, data, offset);
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final float[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final float[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArray(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final float[][] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, fixedLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final float[][] array,
                               final int firstDimensionLength,
                               final int secondDimensionLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != firstDimensionLength) {
      throw invalidArrayLength(array, firstDimensionLength, array.length);
    }
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, secondDimensionLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final float[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    int i = Integer.BYTES + offset;
    for (final var a : array) {
      i += writeVector(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArrayChecked(final float[][] array,
                                     final int fixedLength,
                                     final byte[] data,
                                     final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArrayChecked(array, fixedLength, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArray(final float[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int lenOptionalfloat(final OptionalDouble val) {
    return val == null || val.isEmpty() ? 1 : 1 + Float.BYTES;
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final float[] array) {
    return array.length * Float.BYTES;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final float[] array) {
    return Integer.BYTES + lenArray(array);
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final float[][] array) {
    int len = 0;
    for (final var a : array) {
      len += lenArray(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final float[][] array) {
    int len = Integer.BYTES;
    for (final var a : array) {
      len += lenVector(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVectorArray(final float[][] array) {
    return Integer.BYTES + lenArray(array);
  }

  // double

  @Deprecated(forRemoval = true)
  static int readArray(final double[] result, final byte[] data, int offset) {
    int o = offset;
    for (int i = 0; i < result.length; ++i) {
      result[i] = ByteUtil.getFloat64LE(data, o);
      o += Double.BYTES;
    }
    return o - offset;
  }

  @Deprecated(forRemoval = true)
  static int readArray(final double[][] result, final byte[] data, final int offset) {
    int i = offset;
    for (final var out : result) {
      i += readArray(out, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static double[] readdoubleVector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final var result = new double[len];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static double[][] readMultiDimensiondoubleVector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final var result = new double[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = readdoubleVector(data, offset);
      result[i] = instance;
      offset += lenVector(instance);
    }
    return result;
  }

  @Deprecated(forRemoval = true)
  static double[][] readMultiDimensiondoubleVectorArray(final int fixedLength,
                                                        final byte[] data,
                                                        final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final double[][] result = new double[len][fixedLength];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static int writeOptional(final OptionalDouble val, final byte[] data, final int offset) {
    if (val == null || val.isEmpty()) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      ByteUtil.putFloat64LE(data, offset + 1, val.getAsDouble());
      return 1 + Double.BYTES;
    }
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final double[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      ByteUtil.putFloat64LE(data, i, a);
      i += Double.BYTES;
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final double[] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != fixedLength) {
      throw invalidArrayLength(array, fixedLength, array.length);
    }
    return writeArray(array, data, offset);
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final double[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final double[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArray(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final double[][] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, fixedLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final double[][] array,
                               final int firstDimensionLength,
                               final int secondDimensionLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != firstDimensionLength) {
      throw invalidArrayLength(array, firstDimensionLength, array.length);
    }
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, secondDimensionLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final double[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    int i = Integer.BYTES + offset;
    for (final var a : array) {
      i += writeVector(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArrayChecked(final double[][] array,
                                     final int fixedLength,
                                     final byte[] data,
                                     final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArrayChecked(array, fixedLength, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArray(final double[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int lenOptional(final OptionalDouble val) {
    return val == null || val.isEmpty() ? 1 : 1 + Double.BYTES;
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final double[] array) {
    return array.length * Double.BYTES;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final double[] array) {
    return Integer.BYTES + lenArray(array);
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final double[][] array) {
    int len = 0;
    for (final var a : array) {
      len += lenArray(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final double[][] array) {
    int len = Integer.BYTES;
    for (final var a : array) {
      len += lenVector(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVectorArray(final double[][] array) {
    return Integer.BYTES + lenArray(array);
  }

  // 128-bit integers

  @Deprecated(forRemoval = true)
  static int read128Array(final BigInteger[][] result, final byte[] data, final int offset) {
    int i = offset;
    for (final var out : result) {
      i += read128Array(out, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int read128Array(final BigInteger[] result, final byte[] data, final int offset) {
    int o = offset;
    for (int i = 0; i < result.length; ++i) {
      result[i] = ByteUtil.getInt128LE(data, o);
      o += 16;
    }
    return o - offset;
  }

  @Deprecated(forRemoval = true)
  static BigInteger[] read128Vector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final var result = new BigInteger[len];
    read128Array(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static BigInteger[][] readMultiDimension128Vector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final var result = new BigInteger[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = read128Vector(data, offset);
      result[i] = instance;
      offset += len128Vector(instance);
    }
    return result;
  }

  @Deprecated(forRemoval = true)
  static BigInteger[][] readMultiDimension128VectorArray(final int fixedLength,
                                                         final byte[] data,
                                                         final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final BigInteger[][] result = new BigInteger[len][fixedLength];
    read128Array(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static int write128(final BigInteger val, final byte[] data, final int offset) {
    return ByteUtil.putInt128LE(data, offset, val);
  }

  @Deprecated(forRemoval = true)
  static int write128Optional(final BigInteger val, final byte[] data, final int offset) {
    if (val == null) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      return 1 + write128(val, data, offset + 1);
    }
  }

  @Deprecated(forRemoval = true)
  static int write128Array(final BigInteger[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += write128(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int write128ArrayChecked(final BigInteger[] array,
                                  final int fixedLength,
                                  final byte[] data,
                                  final int offset) {
    if (array.length != fixedLength) {
      throw invalidArrayLength(array, fixedLength, array.length);
    }
    return write128Array(array, data, offset);
  }

  @Deprecated(forRemoval = true)
  static int write128Vector(final BigInteger[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + write128Array(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int write128Array(final BigInteger[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += write128Array(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int write128ArrayChecked(final BigInteger[][] array,
                                  final int fixedLength,
                                  final byte[] data,
                                  final int offset) {
    int i = offset;
    for (final var a : array) {
      i += write128ArrayChecked(a, fixedLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int write128ArrayChecked(final BigInteger[][] array,
                                  final int firstDimensionLength,
                                  final int secondDimensionLength,
                                  final byte[] data,
                                  final int offset) {
    if (array.length != firstDimensionLength) {
      throw invalidArrayLength(array, firstDimensionLength, array.length);
    }
    int i = offset;
    for (final var a : array) {
      i += write128ArrayChecked(a, secondDimensionLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int write128Vector(final BigInteger[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    int i = Integer.BYTES + offset;
    for (final var a : array) {
      i += write128Vector(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int write128VectorArrayChecked(final BigInteger[][] array,
                                        final int fixedLength,
                                        final byte[] data,
                                        final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + write128ArrayChecked(array, fixedLength, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int write128VectorArray(final BigInteger[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + write128Array(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int len128Optional(final BigInteger val) {
    return val == null ? 1 : 17;
  }

  @Deprecated(forRemoval = true)
  static int len128Array(final BigInteger[] array) {
    return array.length * 16;
  }

  @Deprecated(forRemoval = true)
  static int len128Vector(final BigInteger[] array) {
    return Integer.BYTES + len128Array(array);
  }

  @Deprecated(forRemoval = true)
  static int len128Array(final BigInteger[][] array) {
    int len = 0;
    for (final var a : array) {
      len += len128Array(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int len128Vector(final BigInteger[][] array) {
    int len = Integer.BYTES;
    for (final var a : array) {
      len += len128Vector(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int len128VectorArray(final BigInteger[][] array) {
    return Integer.BYTES + len128Array(array);
  }

  // 256-bit integers

  @Deprecated(forRemoval = true)
  static int read256Array(final BigInteger[] result, final byte[] data, final int offset) {
    int o = offset;
    for (int i = 0; i < result.length; ++i) {
      result[i] = ByteUtil.getInt256LE(data, o);
      o += 32;
    }
    return o - offset;
  }

  @Deprecated(forRemoval = true)
  static int read256Array(final BigInteger[][] result, final byte[] data, final int offset) {
    int i = offset;
    for (final var out : result) {
      i += read256Array(out, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static BigInteger[] read256Vector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final var result = new BigInteger[len];
    read256Array(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static BigInteger[][] readMultiDimension256Vector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final var result = new BigInteger[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = read256Vector(data, offset);
      result[i] = instance;
      offset += len256Vector(instance);
    }
    return result;
  }

  @Deprecated(forRemoval = true)
  static BigInteger[][] readMultiDimension256VectorArray(final int fixedLength,
                                                         final byte[] data,
                                                         final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final BigInteger[][] result = new BigInteger[len][fixedLength];
    read256Array(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static int write256(final BigInteger val, final byte[] data, final int offset) {
    return ByteUtil.putInt256LE(data, offset, val);
  }

  @Deprecated(forRemoval = true)
  static int write256Optional(final BigInteger val, final byte[] data, final int offset) {
    if (val == null) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      return 1 + write256(val, data, offset + 1);
    }
  }

  @Deprecated(forRemoval = true)
  static int write256Array(final BigInteger[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += write256(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int write256ArrayChecked(final BigInteger[] array,
                                  final int fixedLength,
                                  final byte[] data,
                                  final int offset) {
    if (array.length != fixedLength) {
      throw invalidArrayLength(array, fixedLength, array.length);
    }
    return write256Array(array, data, offset);
  }

  @Deprecated(forRemoval = true)
  static int write256Vector(final BigInteger[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + write256Array(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int write256Array(final BigInteger[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += write256Array(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int write256ArrayChecked(final BigInteger[][] array,
                                  final int fixedLength,
                                  final byte[] data,
                                  final int offset) {
    int i = offset;
    for (final var a : array) {
      i += write256ArrayChecked(a, fixedLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int write256ArrayChecked(final BigInteger[][] array,
                                  final int firstDimensionLength,
                                  final int secondDimensionLength,
                                  final byte[] data,
                                  final int offset) {
    if (array.length != firstDimensionLength) {
      throw invalidArrayLength(array, firstDimensionLength, array.length);
    }
    int i = offset;
    for (final var a : array) {
      i += write256ArrayChecked(a, secondDimensionLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int write256Vector(final BigInteger[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    int i = Integer.BYTES + offset;
    for (final var a : array) {
      i += write256Vector(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int write256VectorArrayChecked(final BigInteger[][] array,
                                        final int fixedLength,
                                        final byte[] data,
                                        final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + write256ArrayChecked(array, fixedLength, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int write256VectorArray(final BigInteger[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + write256Array(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int len256Optional(final BigInteger val) {
    return val == null ? 1 : 33;
  }

  @Deprecated(forRemoval = true)
  static int len256Array(final BigInteger[] array) {
    return array.length * 32;
  }

  @Deprecated(forRemoval = true)
  static int len256Vector(final BigInteger[] array) {
    return Integer.BYTES + len256Array(array);
  }

  @Deprecated(forRemoval = true)
  static int len256Array(final BigInteger[][] array) {
    int len = 0;
    for (final var a : array) {
      len += len256Array(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int len256Vector(final BigInteger[][] array) {
    int len = Integer.BYTES;
    for (final var a : array) {
      len += len256Vector(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int len256VectorArray(final BigInteger[][] array) {
    return Integer.BYTES + len256Array(array);
  }

  // PublicKey

  @Deprecated(forRemoval = true)
  static int readArray(final PublicKey[] result, final byte[] data, final int offset) {
    int o = offset;
    for (int i = 0; i < result.length; ++i) {
      result[i] = PublicKey.readPubKey(data, o);
      o += PublicKey.PUBLIC_KEY_LENGTH;
    }
    return o - offset;
  }

  @Deprecated(forRemoval = true)
  static PublicKey[] readPublicKeyVector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final var result = new PublicKey[len];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static int readArray(final PublicKey[][] result, final byte[] data, final int offset) {
    int i = offset;
    for (final var out : result) {
      i += readArray(out, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static PublicKey[][] readMultiDimensionPublicKeyVector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final PublicKey[][] result = new PublicKey[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = readPublicKeyVector(data, offset);
      result[i] = instance;
      offset += lenVector(instance);
    }
    return result;
  }

  @Deprecated(forRemoval = true)
  static PublicKey[][] readMultiDimensionPublicKeyVectorArray(final int fixedLength,
                                                              final byte[] data,
                                                              final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final PublicKey[][] result = new PublicKey[len][fixedLength];
    readArray(result, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static int writeOptional(final PublicKey val, final byte[] data, final int offset) {
    if (val == null) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      val.write(data, offset + 1);
      return 1 + PublicKey.PUBLIC_KEY_LENGTH;
    }
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final PublicKey[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += a.write(data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final PublicKey[] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != fixedLength) {
      throw invalidArrayLength(array, fixedLength, array.length);
    }
    return writeArray(array, data, offset);
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final PublicKey[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final PublicKey[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArray(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final PublicKey[][] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, fixedLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final PublicKey[][] array,
                               final int firstDimensionLength,
                               final int secondDimensionLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != firstDimensionLength) {
      throw invalidArrayLength(array, firstDimensionLength, array.length);
    }
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, secondDimensionLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final PublicKey[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    int i = offset + Integer.BYTES;
    for (final var a : array) {
      i += writeVector(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArray(final PublicKey[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int lenOptional(final PublicKey val) {
    return val == null ? 1 : 1 + PublicKey.PUBLIC_KEY_LENGTH;
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final PublicKey[] array) {
    return array.length * PublicKey.PUBLIC_KEY_LENGTH;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final PublicKey[] array) {
    return Integer.BYTES + lenArray(array);
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final PublicKey[][] array) {
    int len = 0;
    for (final var a : array) {
      len += lenArray(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final PublicKey[][] array) {
    int len = Integer.BYTES;
    for (final var a : array) {
      len += lenVector(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVectorArray(final PublicKey[][] array) {
    return Integer.BYTES + lenArray(array);
  }

  // Borsh

  @Deprecated(forRemoval = true)
  interface Enum extends Borsh {

    int ordinal();

    default int l() {
      return 1;
    }

    default int write(final byte[] data, final int offset) {
      data[offset] = (byte) ordinal();
      return 1;
    }
  }

  @Deprecated(forRemoval = true)
  static <E extends java.lang.Enum<?>> E read(final E[] values, final byte[] data, final int offset) {
    return values[data[offset] & 0xFF];
  }

  @Deprecated(forRemoval = true)
  interface Factory<T> {

    T read(final byte[] data, final int offset);
  }

  @Deprecated(forRemoval = true)
  static <B extends Borsh> int readArray(final B[] result,
                                         final Factory<B> factory,
                                         final byte[] data,
                                         final int offset) {
    int o = offset;
    for (int i = 0; i < result.length; ++i) {
      final var instance = factory.read(data, o);
      result[i] = instance;
      o += instance.l();
    }
    return o - offset;
  }

  @Deprecated(forRemoval = true)
  static <B extends Borsh> int readArray(final B[][] result,
                                         final Factory<B> factory,
                                         final byte[] data,
                                         final int offset) {
    int i = offset;
    for (final var out : result) {
      i += readArray(out, factory, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static <B extends Borsh> B[] readVector(final Class<B> borshClass,
                                          final Factory<B> factory,
                                          final byte[] data,
                                          final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final var result = (B[]) Array.newInstance(borshClass, len);
    readArray(result, factory, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static <B extends Borsh> B[][] readMultiDimensionVector(final Class<B> borshClass,
                                                          final Factory<B> factory,
                                                          final byte[] data,
                                                          int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final B[][] result = (B[][]) Array.newInstance(borshClass, len, 0);
    for (int i = 0; i < result.length; ++i) {
      final var instance = readVector(borshClass, factory, data, offset);
      result[i] = instance;
      offset += lenVector(instance);
    }
    return result;
  }

  @Deprecated(forRemoval = true)
  static <B extends Borsh> B[][] readMultiDimensionVectorArray(final Class<B> borshClass,
                                                               final Factory<B> factory,
                                                               final int fixedLength,
                                                               final byte[] data,
                                                               final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    final B[][] result = (B[][]) Array.newInstance(borshClass, len, fixedLength);
    readArray(result, factory, data, offset + Integer.BYTES);
    return result;
  }

  @Deprecated(forRemoval = true)
  static int write(final Borsh val, final byte[] data, final int offset) {
    return val.write(data, offset);
  }

  @Deprecated(forRemoval = true)
  static int writeOptional(final Borsh val, final byte[] data, final int offset) {
    if (val == null) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      return 1 + write(val, data, offset + 1);
    }
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final Borsh[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += a.write(data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final Borsh[] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != fixedLength) {
      throw invalidArrayLength(array, fixedLength, array.length);
    }
    return writeArray(array, data, offset);
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final Borsh[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeArray(final Borsh[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArray(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final Borsh[][] array,
                               final int fixedLength,
                               final byte[] data,
                               final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, fixedLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeArrayChecked(final Borsh[][] array,
                               final int firstDimensionLength,
                               final int secondDimensionLength,
                               final byte[] data,
                               final int offset) {
    if (array.length != firstDimensionLength) {
      throw invalidArrayLength(array, firstDimensionLength, array.length);
    }
    int i = offset;
    for (final var a : array) {
      i += writeArrayChecked(a, secondDimensionLength, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVector(final Borsh[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    int i = offset + Integer.BYTES;
    for (final var a : array) {
      i += writeVector(a, data, i);
    }
    return i - offset;
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArray(final Borsh[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArray(array, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int writeVectorArrayChecked(final Borsh[][] array,
                                     final int fixedLength,
                                     final byte[] data,
                                     final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + writeArrayChecked(array, fixedLength, data, offset + Integer.BYTES);
  }

  @Deprecated(forRemoval = true)
  static int len(final Borsh val) {
    return val.l();
  }

  @Deprecated(forRemoval = true)
  static int lenOptional(final Borsh val) {
    return val == null ? 1 : 1 + val.l();
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final Borsh[] array) {
    int len = 0;
    for (final var a : array) {
      len += a.l();
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final Borsh[] array) {
    return Integer.BYTES + lenArray(array);
  }

  @Deprecated(forRemoval = true)
  static int lenVector(final Borsh[][] array) {
    int len = Integer.BYTES;
    for (final var a : array) {
      len += lenVector(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenArray(final Borsh[][] array) {
    int len = 0;
    for (final var a : array) {
      len += lenArray(a);
    }
    return len;
  }

  @Deprecated(forRemoval = true)
  static int lenVectorArray(final Borsh[][] array) {
    return Integer.BYTES + lenArray(array);
  }
}
