package software.sava.core.borsh;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.time.Instant;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static java.nio.charset.StandardCharsets.UTF_8;

public interface Borsh {

  int l();

  int write(final byte[] data, final int offset);

  default int write(final byte[] data) {
    return write(data, 0);
  }

  default byte[] write() {
    final byte[] data = new byte[l()];
    write(data);
    return data;
  }

  default byte[] writeOptional() {
    final byte[] data = new byte[1 + l()];
    data[0] = 1;
    write(data, 1);
    return data;
  }

  default Borsh reusable() {
    return new Borshed(write());
  }

  // Serialization Utility Methods

  static String string(final byte[] data, final int offset) {
    return new String(data, offset + Integer.BYTES, ByteUtil.getInt32LE(data, offset), UTF_8);
  }

  static byte[] getBytes(final String str) {
    return str == null || str.isBlank() ? null : str.getBytes(UTF_8);
  }

  static byte[][] getBytes(final String[] strings) {
    final int len = strings.length;
    final byte[][] bytes = new byte[len][];
    for (int i = 0; i < len; ++i) {
      bytes[i] = strings[i].getBytes(UTF_8);
    }
    return bytes;
  }

  static int lenOptional(final Object val, final int len) {
    return val == null ? 1 : (1 + len);
  }

  static int len(final byte[] bytes) {
    return Integer.BYTES + bytes.length;
  }

  static int lenOptional(final byte[] bytes) {
    return bytes == null || bytes.length == 0 ? 1 : (1 + Integer.BYTES + bytes.length);
  }

  static int fixedLen(final byte[][] array) {
    int len = 0;
    for (final var a : array) {
      len += len(a);
    }
    return len;
  }

  static int len(final byte[][] array) {
    return Integer.BYTES + fixedLen(array);
  }

  static byte[] read(final byte[] data, final int offset) {
    final int length = ByteUtil.getInt32LE(data, offset);
    final byte[] bytes = new byte[length];
    System.arraycopy(data, offset + Integer.BYTES, data, 0, length);
    return bytes;
  }

  static int write(final byte[] bytes, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, bytes.length);
    System.arraycopy(bytes, 0, data, offset + Integer.BYTES, bytes.length);
    return Integer.BYTES + bytes.length;
  }

  static int fixedWrite(final byte[] bytes, final byte[] data, final int offset) {
    System.arraycopy(bytes, 0, data, offset, bytes.length);
    return bytes.length;
  }

  static int fixedWrite(final byte[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += fixedWrite(a, data, offset);
    }
    return i - offset;
  }

  static int write(final byte[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static int writeOptional(final byte[] bytes, final byte[] data, final int offset) {
    if (bytes == null || bytes.length == 0) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      return 1 + write(bytes, data, offset + 1);
    }
  }

  static int lenOptional(final Boolean val) {
    return val == null ? 1 : 2;
  }

  static int writeOptional(final Boolean val, final byte[] data, final int offset) {
    if (val == null) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      write(val, data, offset);
      return 2;
    }
  }

  static int write(final boolean val, final byte[] data, final int offset) {
    data[offset] = (byte) (val ? 1 : 0);
    return 1;
  }

  static int len(final boolean[] array) {
    return Integer.BYTES + array.length;
  }

  static int fixedLen(final boolean[] array) {
    return array.length;
  }

  static int fixedLen(final boolean[][] array) {
    int len = 0;
    for (final var a : array) {
      len += len(a);
    }
    return len;
  }

  static int len(final boolean[][] array) {
    return Integer.BYTES + fixedLen(array);
  }

  static int fixedWrite(final boolean[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += writeOptional(a, data, i);
    }
    return array.length;
  }

  static int write(final boolean[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static int fixedWrite(final boolean[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += fixedWrite(a, data, offset);
    }
    return i - offset;
  }

  static int write(final boolean[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static boolean[] readArray(final boolean[] result, final byte[] data, int offset) {
    for (int i = 0; i < result.length; ++i) {
      result[i] = data[offset] == 1;
      ++offset;
    }
    return result;
  }

  static boolean[] readbooleanVector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    return readArray(new boolean[len], data, offset + Integer.BYTES);
  }

  static boolean[][] readMultiDimensionbooleanVector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final var result = new boolean[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = readbooleanVector(data, offset);
      result[i] = instance;
      offset += len(instance);
    }
    return result;
  }

  static int lenOptional(final Short val) {
    return val == null ? 1 : 1 + Short.BYTES;
  }

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

  static int fixedLen(final short[] array) {
    return array.length * Short.BYTES;
  }

  static int fixedWrite(final short[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      ByteUtil.putInt16LE(data, i, a);
      i += Short.BYTES;
    }
    return i - offset;
  }

  static int len(final short[] array) {
    return Integer.BYTES + fixedLen(array);
  }

  static int fixedLen(final short[][] array) {
    int len = 0;
    for (final var a : array) {
      len += len(a);
    }
    return len;
  }

  static int len(final short[][] array) {
    return Integer.BYTES + fixedLen(array);
  }

  static int write(final short[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static int fixedWrite(final short[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += fixedWrite(a, data, offset);
    }
    return i - offset;
  }

  static int write(final short[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static short[] readArray(final short[] result, final byte[] data, int offset) {
    for (int i = 0; i < result.length; ++i) {
      result[i] = (short) ByteUtil.getInt16LE(data, offset);
      offset += Float.BYTES;
    }
    return result;
  }

  static short[] readshortVector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    return readArray(new short[len], data, offset + Integer.BYTES);
  }

  static short[][] readMultiDimensionshortVector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final var result = new short[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = readshortVector(data, offset);
      result[i] = instance;
      offset += len(instance);
    }
    return result;
  }

  static int lenOptionalByte(final OptionalInt val) {
    return val == null || val.isEmpty() ? 1 : 2;
  }

  static int writeOptionalByte(final OptionalInt val, final byte[] data, final int offset) {
    if (val == null || val.isEmpty()) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      data[offset + 1] = (byte) val.getAsInt();
      return 2;
    }
  }

  static int lenOptionalShort(final OptionalInt val) {
    return val == null || val.isEmpty() ? 1 : 1 + Short.BYTES;
  }

  static int writeOptionalShort(final OptionalInt val, final byte[] data, final int offset) {
    if (val == null || val.isEmpty()) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      ByteUtil.putInt16LE(data, offset + 1, (short) val.getAsInt());
      return 1 + Short.BYTES;
    }
  }

  static int lenOptional(final OptionalInt val) {
    return val == null || val.isEmpty() ? 1 : 1 + Integer.BYTES;
  }

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

  static int fixedLen(final int[] array) {
    return array.length * Integer.BYTES;
  }

  static int fixedWrite(final int[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      ByteUtil.putInt32LE(data, i, a);
      i += Integer.BYTES;
    }
    return i - offset;
  }

  static int len(final int[] array) {
    return Integer.BYTES + fixedLen(array);
  }

  static int fixedLen(final int[][] array) {
    int len = 0;
    for (final var a : array) {
      len += len(a);
    }
    return len;
  }

  static int len(final int[][] array) {
    return Integer.BYTES + fixedLen(array);
  }

  static int write(final int[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static int fixedWrite(final int[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += fixedWrite(a, data, offset);
    }
    return i - offset;
  }

  static int write(final int[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static int[] readArray(final int[] result, final byte[] data, int offset) {
    for (int i = 0; i < result.length; ++i) {
      result[i] = ByteUtil.getInt32LE(data, offset);
      offset += Integer.BYTES;
    }
    return result;
  }

  static int[] readintVector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    return readArray(new int[len], data, offset + Integer.BYTES);
  }

  static int[][] readMultiDimensionintVector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final var result = new int[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = readintVector(data, offset);
      result[i] = instance;
      offset += len(instance);
    }
    return result;
  }

  static int lenOptional(final OptionalLong val) {
    return val == null || val.isEmpty() ? 1 : 1 + Long.BYTES;
  }

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

  static int fixedLen(final long[] array) {
    return array.length * Long.BYTES;
  }

  static int fixedWrite(final long[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      ByteUtil.putInt64LE(data, i, a);
      i += Long.BYTES;
    }
    return i - offset;
  }

  static int len(final long[] array) {
    return Integer.BYTES + fixedLen(array);
  }

  static int fixedLen(final long[][] array) {
    int len = 0;
    for (final var a : array) {
      len += len(a);
    }
    return len;
  }

  static int len(final long[][] array) {
    return Integer.BYTES + fixedLen(array);
  }

  static int write(final long[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static int fixedWrite(final long[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += fixedWrite(a, data, offset);
    }
    return i - offset;
  }

  static int write(final long[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static long[] readArray(final long[] result, final byte[] data, int offset) {
    for (int i = 0; i < result.length; ++i) {
      result[i] = ByteUtil.getInt64LE(data, offset);
      offset += Long.BYTES;
    }
    return result;
  }

  static long[] readlongVector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    return readArray(new long[len], data, offset + Integer.BYTES);
  }

  static long[][] readMultiDimensionlongVector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final var result = new long[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = readlongVector(data, offset);
      result[i] = instance;
      offset += len(instance);
    }
    return result;
  }

  static int fixedLen(final float[] array) {
    return array.length * Float.BYTES;
  }

  static int fixedWrite(final float[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      ByteUtil.putFloat32LE(data, i, a);
      i += Float.BYTES;
    }
    return i - offset;
  }

  static int len(final float[] array) {
    return Integer.BYTES + fixedLen(array);
  }

  static int fixedLen(final float[][] array) {
    int len = 0;
    for (final var a : array) {
      len += len(a);
    }
    return len;
  }

  static int len(final float[][] array) {
    return Integer.BYTES + fixedLen(array);
  }

  static int write(final float[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static int fixedWrite(final float[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += fixedWrite(a, data, offset);
    }
    return i - offset;
  }

  static int write(final float[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static float[] readArray(final float[] result, final byte[] data, int offset) {
    for (int i = 0; i < result.length; ++i) {
      result[i] = ByteUtil.getFloat32LE(data, offset);
      offset += Float.BYTES;
    }
    return result;
  }

  static float[] readfloatVector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    return readArray(new float[len], data, offset + Integer.BYTES);
  }

  static float[][] readMultiDimensionfloatVector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final var result = new float[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = readfloatVector(data, offset);
      result[i] = instance;
      offset += len(instance);
    }
    return result;
  }

  static int fixedLen(final double[] array) {
    return array.length * Double.BYTES;
  }

  static int fixedWrite(final double[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      ByteUtil.putFloat64LE(data, i, a);
      i += Double.BYTES;
    }
    return i - offset;
  }

  static int len(final double[] array) {
    return Integer.BYTES + fixedLen(array);
  }

  static int write(final double[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static int lenOptionalFloat(final OptionalDouble val) {
    return val == null || val.isEmpty() ? 1 : 1 + Float.BYTES;
  }

  static int writeOptionalFloat(final OptionalDouble val, final byte[] data, final int offset) {
    if (val == null || val.isEmpty()) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      ByteUtil.putFloat32LE(data, offset + 1, (float) val.getAsDouble());
      return 1 + Float.BYTES;
    }
  }

  static int lenOptional(final OptionalDouble val) {
    return val == null || val.isEmpty() ? 1 : 1 + Double.BYTES;
  }

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

  static int fixedWrite(final double[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += fixedWrite(a, data, offset);
    }
    return i - offset;
  }

  static int write(final double[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static int fixedLen(final double[][] array) {
    int len = 0;
    for (final var a : array) {
      len += len(a);
    }
    return len;
  }

  static int len(final double[][] array) {
    return Integer.BYTES + fixedLen(array);
  }

  static double[] readArray(final double[] result, final byte[] data, int offset) {
    for (int i = 0; i < result.length; ++i) {
      result[i] = ByteUtil.getFloat64LE(data, offset);
      offset += Double.BYTES;
    }
    return result;
  }

  static double[] readDoubleVector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    return readArray(new double[len], data, offset + Integer.BYTES);
  }

  static double[][] readMultiDimensionDoubleVector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final var result = new double[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = readDoubleVector(data, offset);
      result[i] = instance;
      offset += len(instance);
    }
    return result;
  }

  static int write(final BigInteger val, final byte[] data, final int offset) {
    return ByteUtil.putInt128LE(data, offset, val);
  }

  static int fixedLen(final BigInteger[] array) {
    return array.length * 128;
  }

  static int fixedWrite(final BigInteger[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += write(a, data, offset);
    }
    return i - offset;
  }

  static int writeOptional(final BigInteger[] array, final byte[] data, final int offset) {
    if (array == null || array.length == 0) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      return 1 + write(array, data, offset + 1);
    }
  }

  static int len(final BigInteger[] array) {
    return Integer.BYTES + fixedLen(array);
  }

  static int fixedLen(final BigInteger[][] array) {
    int len = 0;
    for (final var a : array) {
      len += len(a);
    }
    return len;
  }

  static int len(final BigInteger[][] array) {
    return Integer.BYTES + fixedLen(array);
  }

  static BigInteger[] readArray(final BigInteger[] result, final byte[] data, int offset) {
    for (int i = 0; i < result.length; ++i) {
      result[i] = ByteUtil.getInt128LE(data, offset);
      offset += 16;
    }
    return result;
  }

  static BigInteger[] readVector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    return readArray(new BigInteger[len], data, offset + Integer.BYTES);
  }

  static BigInteger[][] readMultiDimensionVector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final var result = new BigInteger[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = readVector(data, offset);
      result[i] = instance;
      offset += len(instance);
    }
    return result;
  }

  static int write(final BigInteger[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static int fixedWrite(final BigInteger[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += fixedWrite(a, data, offset);
    }
    return i - offset;
  }

  static int write(final BigInteger[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static int lenOptional(final BigInteger val) {
    return val == null ? 1 : 1 + 16;
  }

  static int writeOptional(final BigInteger val, final byte[] data, final int offset) {
    if (val == null) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      return 1 + write(val, data, offset + 1);
    }
  }

  static int write(final String str, final byte[] data, final int offset) {
    return write(str.getBytes(UTF_8), data, offset);
  }

  static int fixedLen(final PublicKey[] array) {
    return array.length * PublicKey.PUBLIC_KEY_LENGTH;
  }

  static int fixedWrite(final PublicKey[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += a.write(data, i);
    }
    return i - offset;
  }

  static int len(final PublicKey[] array) {
    return Integer.BYTES + fixedLen(array);
  }

  static int fixedLen(final PublicKey[][] array) {
    int len = 0;
    for (final var a : array) {
      len += len(a);
    }
    return len;
  }

  static int len(final PublicKey[][] array) {
    return Integer.BYTES + fixedLen(array);
  }

  static PublicKey[] readArray(final PublicKey[] result, final byte[] data, int offset) {
    for (int i = 0; i < result.length; ++i) {
      result[i] = PublicKey.readPubKey(data, offset);
      offset += PublicKey.PUBLIC_KEY_LENGTH;
    }
    return result;
  }

  static PublicKey[] readPublicKeyVector(final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    return readArray(new PublicKey[len], data, offset + Integer.BYTES);
  }

  static PublicKey[][] readMultiDimensionPublicKeyVector(final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final PublicKey[][] result = new PublicKey[len][];
    for (int i = 0; i < result.length; ++i) {
      final var instance = readPublicKeyVector(data, offset);
      result[i] = instance;
      offset += len(instance);
    }
    return result;
  }

  static int write(final PublicKey[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static int fixedWrite(final PublicKey[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += fixedWrite(a, data, offset);
    }
    return i - offset;
  }

  static int write(final PublicKey[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static int lenOptional(final PublicKey val) {
    return val == null ? 1 : 1 + PublicKey.PUBLIC_KEY_LENGTH;
  }

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

  static int writeOptional(final PublicKey[] array, final byte[] data, final int offset) {
    if (array == null || array.length == 0) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      return 1 + write(array, data, offset + 1);
    }
  }

  static int fixedLen(final Borsh[] array) {
    int len = 0;
    for (final var a : array) {
      len += a.l();
    }
    return len;
  }

  static int len(final Borsh[] array) {
    return Integer.BYTES + fixedLen(array);
  }

  static int fixedLen(final Borsh[][] array) {
    int len = 0;
    for (final var a : array) {
      len += len(a);
    }
    return len;
  }

  static int len(final Borsh[][] array) {
    return Integer.BYTES + fixedLen(array);
  }

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

  static <E extends java.lang.Enum<?>> E read(final E[] values, final byte[] data, final int offset) {
    return values[data[offset] & 0xFF];
  }

  interface Factory<T> {

    T read(final byte[] data, final int offset);
  }

  static <B extends Borsh> B[] readArray(final B[] result, final Factory<B> factory, final byte[] data, int offset) {
    for (int i = 0; i < result.length; ++i) {
      final var instance = factory.read(data, offset);
      result[i] = instance;
      offset += instance.l();
    }
    return result;
  }

  static <B extends Borsh> B[] readVector(final Class<B> borshClass, final Factory<B> factory, final byte[] data, final int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    return readArray((B[]) Array.newInstance(borshClass, len), factory, data, offset + Integer.BYTES);
  }

  static <B extends Borsh> B[][] readMultiDimensionVector(final Class<B> borshClass, final Factory<B> factory, final byte[] data, int offset) {
    final int len = ByteUtil.getInt32LE(data, offset);
    offset += Integer.BYTES;
    final B[][] result = (B[][]) Array.newInstance(borshClass, len, 2);
    for (int i = 0; i < result.length; ++i) {
      final var instance = readVector(borshClass, factory, data, offset);
      result[i] = instance;
      offset += len(instance);
    }
    return result;
  }

  static int fixedWrite(final Borsh[] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += a.write(data, i);
    }
    return i - offset;
  }

  static int write(final Borsh[] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static int writeOptional(final Borsh[] array, final byte[] data, final int offset) {
    if (array == null || array.length == 0) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      return 1 + write(array, data, offset + 1);
    }
  }

  static int fixedWrite(final Borsh[][] array, final byte[] data, final int offset) {
    int i = offset;
    for (final var a : array) {
      i += fixedWrite(a, data, offset);
    }
    return i - offset;
  }

  static int write(final Borsh[][] array, final byte[] data, final int offset) {
    ByteUtil.putInt32LE(data, offset, array.length);
    return Integer.BYTES + fixedWrite(array, data, offset + Integer.BYTES);
  }

  static int write(final Borsh val, final byte[] data, final int offset) {
    return 1 + val.write(data, offset + 1);
  }

  static int len(final Borsh val) {
    return val.l();
  }

  static int lenOptional(final Borsh val) {
    return val == null ? 1 : 1 + val.l();
  }

  static int writeOptional(final Borsh val, final byte[] data, final int offset) {
    if (val == null) {
      data[offset] = (byte) 0;
      return 1;
    } else {
      data[offset] = (byte) 1;
      return write(val, data, offset);
    }
  }
}
