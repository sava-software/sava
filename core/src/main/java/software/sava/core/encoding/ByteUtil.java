package software.sava.core.encoding;

import java.lang.invoke.VarHandle;
import java.math.BigInteger;
import java.util.Arrays;

import static java.lang.invoke.MethodHandles.byteArrayViewVarHandle;
import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

public final class ByteUtil {

  private static final VarHandle SHORT_LE = byteArrayViewVarHandle(short[].class, LITTLE_ENDIAN);
  private static final VarHandle INT_LE = byteArrayViewVarHandle(int[].class, LITTLE_ENDIAN);
  private static final VarHandle LONG_LE = byteArrayViewVarHandle(long[].class, LITTLE_ENDIAN);
  private static final VarHandle FLOAT_LE = byteArrayViewVarHandle(float[].class, LITTLE_ENDIAN);
  private static final VarHandle DOUBLE_LE = byteArrayViewVarHandle(double[].class, LITTLE_ENDIAN);

  private static final VarHandle SHORT_BE = byteArrayViewVarHandle(short[].class, BIG_ENDIAN);
  private static final VarHandle INT_BE = byteArrayViewVarHandle(int[].class, BIG_ENDIAN);
  private static final VarHandle LONG_BE = byteArrayViewVarHandle(long[].class, BIG_ENDIAN);
  private static final VarHandle FLOAT_BE = byteArrayViewVarHandle(float[].class, BIG_ENDIAN);
  private static final VarHandle DOUBLE_BE = byteArrayViewVarHandle(double[].class, BIG_ENDIAN);

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

  public static void putInt32BE(final byte[] b, final int off, final int val) {
    INT_BE.set(b, off, val);
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

  private static int putIntLE(final byte[] data, final int offset,
                              final BigInteger val,
                              final int byteSize) {
    final byte[] be = val.abs().toByteArray();
    for (int i = 0, o = offset + (be.length - 1); i < be.length; ++i, --o) {
      data[o] = be[i];
    }
    if (val.signum() < 0) {
      data[offset + (byteSize - 1)] |= (byte) 0b1000_0000;
    }
    return byteSize;
  }

  private static BigInteger getUIntLE(final byte[] data, final int offset, final int byteSize) {
    final byte[] be = new byte[byteSize];
    for (int i = 0, o = offset + (byteSize - 1); i < be.length; ++i, --o) {
      be[i] = data[o];
    }
    return new BigInteger(be);
  }

  private static BigInteger getIntLE(final byte[] data, final int offset, final int byteSize) {
    int o = offset + (byteSize - 1);
    final boolean signed = (data[o] & 0b1000_0000) == 0b1000_0000;
    final byte[] be = new byte[byteSize];
    byte b = (byte) (data[o--] & 0b0111_1111);
    boolean zero = b == 0;
    be[0] = b;
    for (int i = 1; i < be.length; ++i, --o) {
      b = data[o];
      be[i] = b;
      if (zero) {
        zero = b == 0;
      }
    }
    return new BigInteger(signed ? -1 : zero ? 0 : 1, be);
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

  private ByteUtil() {
  }
}
