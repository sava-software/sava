package software.sava.core.encoding;

import java.lang.invoke.VarHandle;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static java.lang.invoke.MethodHandles.byteArrayViewVarHandle;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

public final class ByteUtil {

  private static final VarHandle SHORT_LE = byteArrayViewVarHandle(short[].class, LITTLE_ENDIAN);
  private static final VarHandle INT_LE = byteArrayViewVarHandle(int[].class, LITTLE_ENDIAN);
  private static final VarHandle LONG_LE = byteArrayViewVarHandle(long[].class, LITTLE_ENDIAN);
  private static final VarHandle FLOAT_LE = byteArrayViewVarHandle(float[].class, LITTLE_ENDIAN);
  private static final VarHandle DOUBLE_LE = byteArrayViewVarHandle(double[].class, LITTLE_ENDIAN);

  public static void putInt16LE(final byte[] b, final int off, final int val) {
    putInt16LE(b, off, (short) val);
  }

  public static void putInt16LE(final byte[] b, final int off, final short val) {
    SHORT_LE.set(b, off, val);
  }

  public static void putInt32LE(final byte[] b, final int off, final int val) {
    INT_LE.set(b, off, val);
  }

  public static void putInt64LE(final byte[] b, final int off, final long val) {
    LONG_LE.set(b, off, val);
  }

  public static void putFloat32LE(final byte[] b, final int off, final double val) {
    putFloat32LE(b, off, (float) val);
  }

  public static void putFloat32LE(final byte[] b, final int off, final float val) {
    FLOAT_LE.set(b, off, val);
  }

  public static void putFloat64LE(final byte[] b, final int off, final double val) {
    DOUBLE_LE.set(b, off, val);
  }

  public static float getFloat32LE(final byte[] b, final int off) {
    return (float) FLOAT_LE.get(b, off);
  }

  public static double getFloat64LE(final byte[] b, final int off) {
    return (double) DOUBLE_LE.get(b, off);
  }

  public static int getInt8LE(final byte[] b, final int off) {
    return b[off] & 0xFF;
  }

  public static short getInt16LE(final byte[] b, final int off) {
    return (short) SHORT_LE.get(b, off);
  }

  public static int getInt32LE(final byte[] b, final int off) {
    return (int) INT_LE.get(b, off);
  }

  public static long getInt64LE(final byte[] b, final int off) {
    return (long) LONG_LE.get(b, off);
  }

  public static int putIntLE(final byte[] data,
                             final int offset,
                             final BigInteger val,
                             final int byteSize) {
    int j = offset;
    final byte[] be = val.toByteArray();
    for (int i = be.length - 1; i >= 0; --i, ++j) {
      data[j] = be[i];
    }
    if (be.length < byteSize) {
      final int to = offset + byteSize;
      final byte zero = (byte) (val.signum() < 0 ? -1 : 0);
      for (; j < to; ++j) {
        data[j] = zero;
      }
    }
    return byteSize;
  }

  private static BigInteger getIntLE(final byte[] data, final int offset, final int byteSize) {
    final byte[] be = new byte[byteSize];
    for (int i = 0, o = offset + (byteSize - 1); i < be.length; ++i, --o) {
      be[i] = data[o];
    }
    return new BigInteger(be);
  }

  private static BigInteger getUIntLE(final byte[] data, final int offset, final int byteSize) {
    return getIntLE(data, offset, byteSize);
  }

  public static int putInt128LE(final byte[] data, final int offset, final BigInteger val) {
    return putIntLE(data, offset, val, 16);
  }

  public static BigInteger getUInt128LE(final byte[] data, final int offset) {
    return getUIntLE(data, offset, 16);
  }

  public static BigInteger getInt128LE(final byte[] data, final int offset) {
    return getIntLE(data, offset, 16);
  }

  public static int putInt256LE(final byte[] data, final int offset, final BigInteger val) {
    return putIntLE(data, offset, val, 32);
  }

  public static BigInteger getUInt256LE(final byte[] data, final int offset) {
    return getUIntLE(data, offset, 32);
  }

  public static BigInteger getInt256LE(final byte[] data, final int offset) {
    return getIntLE(data, offset, 32);
  }

  public static int indexOf(final byte[] data, final int start, final int end,
                            final byte[] sub, final int subStart, final int subEnd) {
    final int len = subEnd - subStart;
    for (int from = start, to = from + len; to <= end; ++from, ++to) {
      if (Arrays.equals(sub, subStart, subEnd, data, from, to)) {
        return from;
      }
    }
    return -1;
  }

  public static int indexOf(final byte[] data, final int start,
                            final byte[] sub, final int subStart) {
    return indexOf(data, start, data.length, sub, subStart, sub.length);
  }

  public static int indexOf(final byte[] data, final int start, final byte[] sub) {
    return indexOf(data, start, data.length, sub, 0, sub.length);
  }

  public static int indexOf(final byte[] data, final byte[] sub) {
    return indexOf(data, 0, data.length, sub, 0, sub.length);
  }

  public static byte[] reverse(final byte[] bytes, final int offset, final int len) {
    final byte[] reversed = new byte[len];
    for (int i = offset, j = (offset + len) - 1; j >= offset; ++i, --j) {
      reversed[j] = bytes[i];
    }
    return reversed;
  }

  public static byte[] reverse(final byte[] bytes, final int len) {
    return reverse(bytes, 0, len);
  }

  public static byte[] reverse(final byte[] bytes) {
    return reverse(bytes, bytes.length);
  }

  public static byte[] fixedLength(final byte[] bytes, final int length) {
    if (bytes.length < length) {
      final byte[] fixedBytes = new byte[length];
      System.arraycopy(bytes, 0, fixedBytes, 0, bytes.length);
      return fixedBytes;
    } else if (bytes.length == length) {
      return bytes;
    } else {
      throw new IllegalArgumentException(String.format("Must be <= %s bytes", length));
    }
  }

  public static byte[] fixedLength(final String val, final int length, final Charset charset) {
    return fixedLength(val.getBytes(charset), length);
  }

  public static byte[] fixedLength(final String val, final int length) {
    return fixedLength(val, length, StandardCharsets.UTF_8);
  }

  private ByteUtil() {
  }
}
