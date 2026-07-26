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

  /// Reads a single byte as an unsigned value in `[0, 255]`, despite the name —
  /// the `u8` reader, not an `i8` one. There is no signed counterpart because
  /// `data[off]` already is one.
  public static int getInt8LE(final byte[] b, final int off) {
    return b[off] & 0xFF;
  }

  public static short getInt16LE(final byte[] b, final int off) {
    return (short) SHORT_LE.get(b, off);
  }

  /// A `u16` field widened to the narrowest Java type that can hold it, so the
  /// top half of the range reads as `[32768, 65535]` rather than negative.
  ///
  /// The widening that call sites otherwise open-code beside the read — as
  /// `Token2022` does twice for its extension type and length, and as a `u16`
  /// read straight through [#getInt16LE] silently gets wrong once a program
  /// admits a value above `Short.MAX_VALUE`.
  public static int getUInt16LE(final byte[] b, final int off) {
    return Short.toUnsignedInt(getInt16LE(b, off));
  }

  public static int getInt32LE(final byte[] b, final int off) {
    return (int) INT_LE.get(b, off);
  }

  /// A `u32` field widened to `long`, the counterpart to [#getUInt16LE].
  public static long getUInt32LE(final byte[] b, final int off) {
    return Integer.toUnsignedLong(getInt32LE(b, off));
  }

  public static long getInt64LE(final byte[] b, final int off) {
    return (long) LONG_LE.get(b, off);
  }

  /// A `u64` field as its exact value, for callers that cannot carry the raw
  /// bits — the widths above `u32` have no Java primitive to widen into.
  ///
  /// Callers on a hot path should prefer [#getInt64LE] and widen only where the
  /// value is actually needed as a number: most `u64` fields read off an account
  /// are below the sign bit, and this allocates for every one of them. See
  /// [#toUnsignedBigInteger], which owns the reinterpretation.
  public static BigInteger getUInt64LE(final byte[] b, final int off) {
    return toUnsignedBigInteger(getInt64LE(b, off));
  }

  /// Rejects a field these methods cannot address before any of them touches
  /// `data`, so a bad width or a short buffer is one diagnosis rather than
  /// whichever low-level failure the loop happens to hit — and, for the writers,
  /// so an out-of-range field cannot leave a half-written value behind.
  ///
  /// @throws IllegalArgumentException  if `byteSize` is not positive
  /// @throws IndexOutOfBoundsException if `[offset, offset + byteSize)` falls outside `data`
  private static void checkField(final byte[] data, final int offset, final int byteSize) {
    if (byteSize <= 0) {
      throw new IllegalArgumentException("byteSize must be positive: " + byteSize);
    }
    Objects.checkFromIndexSize(offset, byteSize, data.length);
  }

  /// Writes `val` little-endian into the `byteSize` byte field at `offset`,
  /// returning `byteSize`.
  ///
  /// Serves both signednesses: a negative value sign-extends into the fill, and
  /// the full unsigned range up to `2^(8 * byteSize) - 1` is accepted, since a
  /// magnitude that fills the field exactly is only over-long by the sign byte
  /// `BigInteger.toByteArray` prepends. Use [#putUIntLE] where the field is
  /// unsigned and a negative operand is a caller error rather than a bit
  /// pattern.
  ///
  /// @throws IllegalArgumentException  if `val` does not fit in `byteSize` bytes, or `byteSize` is not positive
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

  /// Writes an unsigned `val` little-endian into the `byteSize` byte field at
  /// `offset`, returning `byteSize`.
  ///
  /// [#putIntLE] with the one restriction that makes it an unsigned writer: a
  /// negative operand is rejected instead of sign-extending across the field.
  /// Sign extension is the right answer for an `i128` and silently the wrong one
  /// for a `u192` — `-1` written to an unsigned field is not an error the field
  /// can represent, it is `2^192 - 1`.
  ///
  /// @throws IllegalArgumentException  if `val` is negative, does not fit in `byteSize` bytes, or `byteSize` is not positive
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

  /// Reads the `byteSize` byte field at `offset` as a little-endian
  /// two's-complement signed integer — the read side of [#putIntLE].
  ///
  /// Named widths are covered by [#getInt128LE] and [#getInt256LE]; this is the
  /// escape hatch for the ones that are not, such as the `u192` fixed-point
  /// decimals some Rust programs carry, and the only reason it takes a width at
  /// all.
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

  /// Reads the `byteSize` byte field at `offset` as a little-endian unsigned
  /// integer: the high bit is a value bit, never a sign bit, so the result is
  /// never negative.
  ///
  /// The unsigned counterpart to [#getIntLE], and the one a Rust `uN` field
  /// wants — reading a `u192` whose top bit is set through the signed reader
  /// returns a value short by `2^192`.
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

  /// Widens a `long` holding a u64 read off the wire into its unsigned value.
  ///
  /// Reinterprets the 64 bits directly rather than formatting them to decimal and
  /// re-parsing: `new BigInteger(1, ...)` reads the bytes as a positive magnitude,
  /// which is exactly what "this is unsigned" means. The decimal round trip it
  /// replaces cost roughly five times the allocation and sixteen times the time.
  ///
  /// Correct for every `long`, but callers on a hot path should keep the
  /// `val < 0 ? … : BigInteger.valueOf(val)` guard — `valueOf` is cheaper still for
  /// non-negative values, which is the common case.
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
