package software.sava.core.encoding;

import java.lang.invoke.VarHandle;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import static java.lang.invoke.MethodHandles.byteArrayViewVarHandle;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

public final class ByteUtil {

  private static final VarHandle SHORT_LE = byteArrayViewVarHandle(short[].class, LITTLE_ENDIAN).withInvokeExactBehavior();
  private static final VarHandle INT_LE = byteArrayViewVarHandle(int[].class, LITTLE_ENDIAN).withInvokeExactBehavior();
  private static final VarHandle LONG_LE = byteArrayViewVarHandle(long[].class, LITTLE_ENDIAN).withInvokeExactBehavior();
  private static final VarHandle FLOAT_LE = byteArrayViewVarHandle(float[].class, LITTLE_ENDIAN).withInvokeExactBehavior();
  private static final VarHandle DOUBLE_LE = byteArrayViewVarHandle(double[].class, LITTLE_ENDIAN).withInvokeExactBehavior();

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

  /// Reads one byte as an unsigned value in `[0, 255]`, despite the name. For a signed byte,
  /// read `b[off]` directly.
  public static int getInt8LE(final byte[] b, final int off) {
    return b[off] & 0xFF;
  }

  public static short getInt16LE(final byte[] b, final int off) {
    return (short) SHORT_LE.get(b, off);
  }

  /// Reads a `u16` as an `int` in `[0, 65535]`; [#getInt16LE] reads the top half of that range
  /// as negative.
  public static int getUInt16LE(final byte[] b, final int off) {
    return Short.toUnsignedInt(getInt16LE(b, off));
  }

  public static int getInt32LE(final byte[] b, final int off) {
    return (int) INT_LE.get(b, off);
  }

  /// Reads a `u32` as a `long` in `[0, 2^32 - 1]`.
  public static long getUInt32LE(final byte[] b, final int off) {
    return Integer.toUnsignedLong(getInt32LE(b, off));
  }

  public static long getInt64LE(final byte[] b, final int off) {
    return (long) LONG_LE.get(b, off);
  }

  /// Reads a `u64` as its exact, never negative value. Allocates on every call: on a hot path,
  /// read [#getInt64LE] and widen with [#toUnsignedBigInteger] only where needed.
  public static BigInteger getUInt64LE(final byte[] b, final int off) {
    return toUnsignedBigInteger(getInt64LE(b, off));
  }

  /// Validates the field before `data` is touched, so a bad width or short buffer gets one
  /// diagnosis and a writer never leaves a half-written value.
  ///
  /// @throws IllegalArgumentException  if `byteSize` is not positive
  /// @throws IndexOutOfBoundsException if `[offset, offset + byteSize)` falls outside `data`
  private static void checkField(final byte[] data, final int offset, final int byteSize) {
    if (byteSize <= 0) {
      throw new IllegalArgumentException("byteSize must be positive: " + byteSize);
    }
    Objects.checkFromIndexSize(offset, byteSize, data.length);
  }

  /// Writes `val` little-endian into the `byteSize`-byte field at `offset`, returning
  /// `byteSize`.
  ///
  /// Serves signed and unsigned fields: accepts `-2^(8 * byteSize - 1)` through
  /// `2^(8 * byteSize) - 1` and sign-extends a negative value. Use [#putUIntLE] to reject
  /// negative values.
  ///
  /// @throws IllegalArgumentException  if `val` does not fit in `byteSize` bytes, or `byteSize`
  ///                                   is not positive
  /// @throws IndexOutOfBoundsException if `[offset, offset + byteSize)` falls outside `data`
  public static int putIntLE(final byte[] data,
                             final int offset,
                             final BigInteger val,
                             final int byteSize) {
    checkField(data, offset, byteSize);
    final byte[] be = val.toByteArray();
    int msb = 0;
    if (be.length > byteSize) {
      // toByteArray() prepends a zero byte for magnitudes with the top bit
      // set; it carries no value and must not spill past the field.
      if (be.length == byteSize + 1 && be[0] == 0) {
        msb = 1;
      } else {
        throw new IllegalArgumentException(String.format(
            "%s does not fit in %d bytes.", val, byteSize
        ));
      }
    }
    int j = offset;
    for (int i = be.length - 1; i >= msb; --i, ++j) {
      data[j] = be[i];
    }
    final int to = offset + byteSize;
    if (j < to) {
      final byte fill = (byte) (val.signum() < 0 ? -1 : 0);
      do {
        data[j] = fill;
      } while (++j < to);
    }
    return byteSize;
  }

  /// Writes a non-negative `val` little-endian into the `byteSize`-byte field at `offset`,
  /// returning `byteSize`. Unlike [#putIntLE], a negative `val` is rejected, not sign-extended.
  ///
  /// @throws IllegalArgumentException  if `val` is negative, does not fit in `byteSize` bytes,
  ///                                   or `byteSize` is not positive
  /// @throws IndexOutOfBoundsException if `[offset, offset + byteSize)` falls outside `data`
  public static int putUIntLE(final byte[] data,
                              final int offset,
                              final BigInteger val,
                              final int byteSize) {
    if (val.signum() < 0) {
      throw new IllegalArgumentException(String.format(
          "%s is negative and cannot be written to an unsigned %d byte field.", val, byteSize
      ));
    }
    return putIntLE(data, offset, val, byteSize);
  }

  /// Reads the `byteSize`-byte field at `offset` as a little-endian two's-complement signed
  /// integer. Use [#getUIntLE] for an unsigned field.
  ///
  /// @throws IllegalArgumentException  if `byteSize` is not positive
  /// @throws IndexOutOfBoundsException if `[offset, offset + byteSize)` falls outside `data`
  public static BigInteger getIntLE(final byte[] data, final int offset, final int byteSize) {
    checkField(data, offset, byteSize);
    final byte[] be = new byte[byteSize];
    for (int i = 0, o = offset + (byteSize - 1); i < be.length; ++i, --o) {
      be[i] = data[o];
    }
    return new BigInteger(be);
  }

  /// Reads the `byteSize`-byte field at `offset` as a little-endian unsigned integer, so the
  /// result is never negative.
  ///
  /// @throws IllegalArgumentException  if `byteSize` is not positive
  /// @throws IndexOutOfBoundsException if `[offset, offset + byteSize)` falls outside `data`
  public static BigInteger getUIntLE(final byte[] data, final int offset, final int byteSize) {
    checkField(data, offset, byteSize);
    final byte[] be = new byte[byteSize];
    for (int i = 0, o = offset + (byteSize - 1); i < be.length; ++i, --o) {
      be[i] = data[o];
    }
    return new BigInteger(1, be);
  }

  public static int putInt128LE(final byte[] data, final int offset, final BigInteger val) {
    return putIntLE(data, offset, val, 16);
  }

  /// Returns the unsigned value of the 64 bits in `val`, as when `val` holds a `u64`. Correct for
  /// every `long`; for a non-negative `val`, [BigInteger#valueOf(long)] is equal and cheaper, so
  /// hot paths can branch on `val < 0`.
  public static BigInteger toUnsignedBigInteger(final long val) {
    return new BigInteger(1, new byte[]{
        (byte) (val >>> 56), (byte) (val >>> 48), (byte) (val >>> 40), (byte) (val >>> 32),
        (byte) (val >>> 24), (byte) (val >>> 16), (byte) (val >>> 8), (byte) val
    });
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
    for (int i = offset + len - 1, j = 0; j < len; --i, ++j) {
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
