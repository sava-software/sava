package software.sava.core.encoding;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

final class ByteUtilTests {

  private static final BigInteger TWO_POW_128 = BigInteger.TWO.pow(128);
  private static final BigInteger TWO_POW_256 = BigInteger.TWO.pow(256);

  /// Signed round trip at every offset, plus the unsigned view of the same
  /// bytes, plus containment: bytes outside the field must never be written.
  private void testInt128(final BigInteger expected) {
    final byte[] write = new byte[32];
    for (int i = 0; i < 16; ++i) {
      Arrays.fill(write, (byte) 0x5A);
      assertEquals(16, ByteUtil.putInt128LE(write, i, expected));
      assertEquals(expected, ByteUtil.getInt128LE(write, i));
      assertEquals(expected.mod(TWO_POW_128), ByteUtil.getUInt128LE(write, i));
      assertContained(write, i, 16);
    }
  }

  private void testInt256(final BigInteger expected) {
    final byte[] write = new byte[64];
    for (int i = 0; i < 32; ++i) {
      Arrays.fill(write, (byte) 0x5A);
      assertEquals(32, ByteUtil.putInt256LE(write, i, expected));
      assertEquals(expected, ByteUtil.getInt256LE(write, i));
      assertEquals(expected.mod(TWO_POW_256), ByteUtil.getUInt256LE(write, i));
      assertContained(write, i, 32);
    }
  }

  private static void assertContained(final byte[] data, final int offset, final int byteSize) {
    for (int i = 0; i < offset; ++i) {
      assertEquals((byte) 0x5A, data[i], "wrote before the field at index " + i);
    }
    for (int i = offset + byteSize; i < data.length; ++i) {
      assertEquals((byte) 0x5A, data[i], "wrote past the field at index " + i);
    }
  }

  @Test
  void test128BitIntegers() {
    // 116, 142, 244, 171, 253, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255
    final byte[] i128LE = new byte[]{116, -114, -12, -85, -3, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1};
    final var i128 = ByteUtil.getInt128LE(i128LE, 0);
    final var expected = new BigInteger("-9999970700");
    assertEquals(expected, i128);
    testInt128(expected);

    final byte[] test = new byte[16];
    ByteUtil.putInt128LE(test, 0, i128);
    assertArrayEquals(i128LE, test);

    testInt128(BigInteger.ZERO);
    testInt128(BigInteger.ONE);
    testInt128(BigInteger.ONE.negate());
    testInt128(new BigInteger("165959464850144709097569536226796601860"));
    testInt128(new BigInteger("67935603135873865182680218184035306913"));
    testInt128(new BigInteger("-162272704100837194170455521702674872040"));
    testInt128(new BigInteger("-155155494242896723051467122773477245"));
    testInt128(new BigInteger("-25912721450736272609715131753556298938"));
  }

  @Test
  void test128BitIntegerBounds() {
    // Signed extremes.
    testInt128(BigInteger.TWO.pow(127).subtract(BigInteger.ONE));
    testInt128(BigInteger.TWO.pow(127).negate());

    // Unsigned values with the top bit set: toByteArray() prepends a sign
    // byte, which previously spilled one byte past the field, and the
    // unsigned reads previously returned negative values.
    final byte[] write = new byte[32];
    for (final var val : new BigInteger[]{
        BigInteger.TWO.pow(127),
        TWO_POW_128.subtract(BigInteger.ONE),
        TWO_POW_128.subtract(new BigInteger("9999970700"))
    }) {
      for (int i = 0; i < 16; ++i) {
        Arrays.fill(write, (byte) 0x5A);
        ByteUtil.putInt128LE(write, i, val);
        assertEquals(val, ByteUtil.getUInt128LE(write, i));
        assertContained(write, i, 16);
      }
    }
  }

  @Test
  void testIntLEDoesNotFit() {
    final byte[] write = new byte[64];
    assertThrows(IllegalArgumentException.class, () -> ByteUtil.putInt128LE(write, 0, TWO_POW_128));
    assertThrows(IllegalArgumentException.class, () -> ByteUtil.putInt128LE(write, 0, TWO_POW_128.add(BigInteger.ONE)));
    assertThrows(IllegalArgumentException.class,
        () -> ByteUtil.putInt128LE(write, 0, BigInteger.TWO.pow(127).negate().subtract(BigInteger.ONE)));
    assertThrows(IllegalArgumentException.class, () -> ByteUtil.putInt256LE(write, 0, TWO_POW_256));
    assertThrows(IllegalArgumentException.class,
        () -> ByteUtil.putInt256LE(write, 0, BigInteger.TWO.pow(255).negate().subtract(BigInteger.ONE)));

    // more than one byte over the field: toByteArray() yields byteSize + 2 or more,
    // including a leading zero sign byte, which must not be misread as the exact-fit case
    assertThrows(IllegalArgumentException.class, () -> ByteUtil.putInt128LE(write, 0, BigInteger.TWO.pow(135)));
    assertThrows(IllegalArgumentException.class, () -> ByteUtil.putInt128LE(write, 0, BigInteger.TWO.pow(200)));
    assertThrows(IllegalArgumentException.class, () -> ByteUtil.putInt256LE(write, 0, BigInteger.TWO.pow(263)));
  }

  @Test
  void test256BitIntegers() {
    testInt256(new BigInteger("240952751974454416887375303278538328657633745834006340121122396849307110663"));
    testInt256(BigInteger.ZERO);
    testInt256(BigInteger.ONE.negate());
    testInt256(BigInteger.TWO.pow(255).subtract(BigInteger.ONE));
    testInt256(BigInteger.TWO.pow(255).negate());

    final byte[] write = new byte[32];
    final var uMax = TWO_POW_256.subtract(BigInteger.ONE);
    ByteUtil.putInt256LE(write, 0, uMax);
    assertEquals(uMax, ByteUtil.getUInt256LE(write, 0));
  }

  @Test
  void testFixedWidthRoundTrips() {
    final byte[] data = new byte[16];
    for (int offset = 0; offset < 8; ++offset) {
      for (final short val : new short[]{0, 1, -1, 0x12_34, Short.MIN_VALUE, Short.MAX_VALUE}) {
        ByteUtil.putInt16LE(data, offset, val);
        assertEquals(val, ByteUtil.getInt16LE(data, offset));
        // scramble between overloads so the int delegate is observed writing
        ByteUtil.putInt16LE(data, offset, (short) ~val);
        ByteUtil.putInt16LE(data, offset, (int) val);
        assertEquals(val, ByteUtil.getInt16LE(data, offset));
      }
      for (final int val : new int[]{0, 1, -1, 0x12_34_56_78, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
        ByteUtil.putInt32LE(data, offset, val);
        assertEquals(val, ByteUtil.getInt32LE(data, offset));
      }
      for (final long val : new long[]{0, 1, -1, 0x12_34_56_78_9A_BC_DE_F0L, Long.MIN_VALUE, Long.MAX_VALUE}) {
        ByteUtil.putInt64LE(data, offset, val);
        assertEquals(val, ByteUtil.getInt64LE(data, offset));
      }
    }

    ByteUtil.putInt16LE(data, 0, (short) 0x0201);
    assertEquals(0x01, ByteUtil.getInt8LE(data, 0));
    assertEquals(0x02, ByteUtil.getInt8LE(data, 1));
    data[2] = (byte) 0xFF;
    assertEquals(0xFF, ByteUtil.getInt8LE(data, 2));
  }

  @Test
  void testFloatRoundTrips() {
    final byte[] data = new byte[16];
    for (int offset = 0; offset < 8; ++offset) {
      for (final float val : new float[]{
          0.0f, -0.0f, 1.5f, -1.5f,
          Float.MIN_VALUE, Float.MAX_VALUE,
          Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NaN
      }) {
        ByteUtil.putFloat32LE(data, offset, val);
        assertEquals(Float.floatToIntBits(val), Float.floatToIntBits(ByteUtil.getFloat32LE(data, offset)));
        // scramble between overloads so the double delegate is observed writing
        ByteUtil.putInt32LE(data, offset, ~Float.floatToIntBits(val));
        ByteUtil.putFloat32LE(data, offset, (double) val);
        assertEquals(Float.floatToIntBits(val), Float.floatToIntBits(ByteUtil.getFloat32LE(data, offset)));
      }
      for (final double val : new double[]{
          0.0, -0.0, 1.5, -1.5,
          Double.MIN_VALUE, Double.MAX_VALUE,
          Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN
      }) {
        ByteUtil.putFloat64LE(data, offset, val);
        assertEquals(Double.doubleToLongBits(val), Double.doubleToLongBits(ByteUtil.getFloat64LE(data, offset)));
      }
    }
  }

  @Test
  void testIndexOf() {
    final byte[] data = {1, 2, 3, 4, 2, 3, 5};
    final byte[] sub = {2, 3};

    assertEquals(1, ByteUtil.indexOf(data, sub));
    assertEquals(1, ByteUtil.indexOf(data, 0, sub));
    assertEquals(1, ByteUtil.indexOf(data, 1, sub));
    assertEquals(4, ByteUtil.indexOf(data, 2, sub));
    assertEquals(4, ByteUtil.indexOf(data, 4, sub));
    assertEquals(-1, ByteUtil.indexOf(data, 5, sub));
    assertEquals(-1, ByteUtil.indexOf(data, new byte[]{3, 2}));
    assertEquals(-1, ByteUtil.indexOf(data, new byte[]{2, 3, 9}));

    // match at the very end
    assertEquals(5, ByteUtil.indexOf(data, new byte[]{3, 5}));
    // the end bound is exclusive of matches extending past it
    assertEquals(-1, ByteUtil.indexOf(data, 0, 6, new byte[]{3, 5}, 0, 2));
    assertEquals(5, ByteUtil.indexOf(data, 0, 7, new byte[]{3, 5}, 0, 2));

    // sub-range of the needle
    assertEquals(1, ByteUtil.indexOf(data, 0, data.length, new byte[]{9, 2, 3, 4, 9}, 1, 4));
    assertEquals(1, ByteUtil.indexOf(data, 0, new byte[]{9, 2, 3}, 1));

    // an empty needle matches at the start index
    assertEquals(0, ByteUtil.indexOf(data, new byte[0]));
    assertEquals(3, ByteUtil.indexOf(data, 3, new byte[0]));
  }

  @Test
  void testFixedLength() {
    assertArrayEquals(new byte[]{1, 2, 0, 0}, ByteUtil.fixedLength(new byte[]{1, 2}, 4));
    final byte[] exact = {1, 2, 3};
    assertSame(exact, ByteUtil.fixedLength(exact, 3));
    assertThrows(IllegalArgumentException.class, () -> ByteUtil.fixedLength(new byte[5], 4));

    assertArrayEquals(new byte[]{'a', 'b', 0, 0}, ByteUtil.fixedLength("ab", 4));
    assertArrayEquals(new byte[]{'a', 'b'}, ByteUtil.fixedLength("ab", 2, java.nio.charset.StandardCharsets.US_ASCII));
    assertThrows(IllegalArgumentException.class, () -> ByteUtil.fixedLength("abcde", 4));
  }

  @Test
  void testReverse() {
    final byte[] bytes = new byte[]{0, 1, 2, 3, 4};

    assertArrayEquals(new byte[]{4, 3, 2, 1, 0}, ByteUtil.reverse(bytes));
    assertArrayEquals(new byte[]{2, 1, 0}, ByteUtil.reverse(bytes, 3));
    // Regression: offsets greater than zero previously wrote out of bounds.
    assertArrayEquals(new byte[]{4, 3, 2}, ByteUtil.reverse(bytes, 2, 3));
    assertArrayEquals(new byte[]{3, 2, 1}, ByteUtil.reverse(bytes, 1, 3));
    assertArrayEquals(new byte[]{1}, ByteUtil.reverse(bytes, 1, 1));
    assertArrayEquals(new byte[0], ByteUtil.reverse(bytes, 5, 0));

    assertArrayEquals(bytes, ByteUtil.reverse(ByteUtil.reverse(bytes)));
  }

  /// The u64 widening used wherever a wire `long` is really unsigned. Pinned
  /// against the decimal round trip it replaced, which is the obvious-but-slow
  /// way to say the same thing.
  @Test
  void toUnsignedBigIntegerMatchesTheDecimalRoundTrip() {
    final long[] edges = {
        0L, 1L, -1L, -2L, 255L, 256L, -256L,
        0xFFL, 0xFF00L, 0xFF000000L, 0xFF00000000L, 0xFF00000000000000L,
        0x0102030405060708L, 0x8000000000000000L, 0x7FFFFFFFFFFFFFFFL,
        (long) Integer.MAX_VALUE, (long) Integer.MAX_VALUE + 1, 0xFFFFFFFFL,
        Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE, Long.MAX_VALUE - 1
    };
    for (final long val : edges) {
      assertEquals(new BigInteger(Long.toUnsignedString(val)), ByteUtil.toUnsignedBigInteger(val),
          "widening " + val);
    }

    final var random = new java.util.Random(20260720L);
    for (int i = 0; i < 20_000; ++i) {
      final long val = random.nextLong();
      assertEquals(new BigInteger(Long.toUnsignedString(val)), ByteUtil.toUnsignedBigInteger(val),
          "widening " + val);
    }
  }

  /// One bit at a time across all 64 positions. The random sweep above only
  /// catches a wrong shift distance or a transposed byte with high probability;
  /// this catches it outright, and says which position is wrong.
  @Test
  void toUnsignedBigIntegerPlacesEveryBitAtItsOwnPosition() {
    for (int i = 0; i < Long.SIZE; ++i) {
      assertEquals(BigInteger.ONE.shiftLeft(i), ByteUtil.toUnsignedBigInteger(1L << i),
          "bit " + i);
    }
    // Every bit below i set: catches a shift that is right for isolated bits but
    // drops or duplicates a byte when neighbours are populated.
    for (int i = 1; i <= Long.SIZE; ++i) {
      final long val = i == Long.SIZE ? -1L : (1L << i) - 1;
      assertEquals(BigInteger.ONE.shiftLeft(i).subtract(BigInteger.ONE),
          ByteUtil.toUnsignedBigInteger(val), "low " + i + " bits");
    }
  }

  /// The property every call site leans on. `DecimalInteger`, `DecimalIntegerAmount`
  /// and `Lamports` all branch `val < 0 ? toUnsignedBigInteger(val) : valueOf(val)`,
  /// treating the two as interchangeable where they overlap — the branch is an
  /// allocation choice, not a change of meaning. If they ever diverged, those
  /// callers would return different values either side of zero for no stated reason.
  @Test
  void toUnsignedBigIntegerAgreesWithValueOfWhereCallersBranch() {
    for (final long val : new long[]{0L, 1L, 2L, 255L, 256L, 0xFFFFFFFFL, Long.MAX_VALUE}) {
      assertEquals(BigInteger.valueOf(val), ByteUtil.toUnsignedBigInteger(val), "non-negative " + val);
    }
    final var random = new java.util.Random(31L);
    for (int i = 0; i < 10_000; ++i) {
      final long val = random.nextLong() >>> 1; // non-negative half of the range
      assertEquals(BigInteger.valueOf(val), ByteUtil.toUnsignedBigInteger(val), "non-negative " + val);
    }
    // And the negative half is where they must differ, by exactly 2^64.
    assertEquals(BigInteger.valueOf(-1L).add(BigInteger.TWO.pow(64)),
        ByteUtil.toUnsignedBigInteger(-1L));
  }

  /// Widening must not lose or invent bits: truncating the result back to a
  /// `long` has to reproduce the input exactly, and the value has to land inside
  /// the u64 range for every input.
  @Test
  void toUnsignedBigIntegerPreservesAllSixtyFourBits() {
    final var twoPow64 = BigInteger.TWO.pow(64);
    final var random = new java.util.Random(64L);
    for (int i = 0; i < 20_000; ++i) {
      final long val = i == 0 ? Long.MIN_VALUE : i == 1 ? -1L : random.nextLong();
      final var widened = ByteUtil.toUnsignedBigInteger(val);
      assertEquals(val, widened.longValue(), "bits lost widening " + val);
      assertTrue(widened.signum() >= 0 && widened.compareTo(twoPow64) < 0, "out of u64 range: " + val);
      assertTrue(widened.bitLength() <= Long.SIZE, "too wide: " + val);
    }
  }

  /// Widening has to induce the same order as `Long.compareUnsigned` — that is
  /// what makes the result usable for comparing wire amounts. The top half of the
  /// signed range must sort above the bottom half, not below it.
  @Test
  void toUnsignedBigIntegerOrdersLikeCompareUnsigned() {
    assertTrue(ByteUtil.toUnsignedBigInteger(Long.MIN_VALUE)
        .compareTo(ByteUtil.toUnsignedBigInteger(Long.MAX_VALUE)) > 0);

    final var random = new java.util.Random(128L);
    for (int i = 0; i < 10_000; ++i) {
      final long a = random.nextLong();
      final long b = random.nextLong();
      assertEquals(Integer.signum(Long.compareUnsigned(a, b)),
          ByteUtil.toUnsignedBigInteger(a).compareTo(ByteUtil.toUnsignedBigInteger(b)),
          a + " vs " + b);
    }
  }

  /// Cross-check against this class's own little-endian writer, which reaches the
  /// same 64 bits through a `VarHandle` rather than through the shift arithmetic
  /// under test — an oracle that shares no code with the implementation.
  @Test
  void toUnsignedBigIntegerAgreesWithTheVarHandleWriter() {
    final byte[] le = new byte[Long.BYTES];
    final var random = new java.util.Random(256L);
    for (int i = 0; i < 5_000; ++i) {
      final long val = i == 0 ? Long.MIN_VALUE : i == 1 ? -1L : random.nextLong();
      ByteUtil.putInt64LE(le, 0, val);
      assertEquals(new BigInteger(1, ByteUtil.reverse(le)), ByteUtil.toUnsignedBigInteger(val),
          "widening " + val);
    }
  }

  /// The whole point: the top half of the range widens to a large positive value
  /// rather than staying negative.
  @Test
  void toUnsignedBigIntegerNeverReturnsANegative() {
    assertEquals(new BigInteger("18446744073709551615"), ByteUtil.toUnsignedBigInteger(-1L));
    assertEquals(new BigInteger("9223372036854775808"), ByteUtil.toUnsignedBigInteger(Long.MIN_VALUE));

    final var random = new java.util.Random(7L);
    for (int i = 0; i < 5_000; ++i) {
      assertTrue(ByteUtil.toUnsignedBigInteger(random.nextLong()).signum() >= 0);
    }
    assertEquals(0, ByteUtil.toUnsignedBigInteger(0L).signum());
  }
}
