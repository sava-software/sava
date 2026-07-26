package software.sava.core.encoding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

final class ByteUtilTests {

  private static final BigInteger TWO_POW_128 = BigInteger.TWO.pow(128);
  private static final BigInteger TWO_POW_192 = BigInteger.TWO.pow(192);
  private static final BigInteger TWO_POW_256 = BigInteger.TWO.pow(256);

  /// The widths the arbitrary-width methods are exercised at: the named ones,
  /// the `u192` they were opened up for, and odd sizes either side of a Java
  /// primitive so nothing can depend on the field being a whole number of words.
  private static final int[] WIDTHS = {1, 2, 3, 5, 8, 9, 16, 24, 32, 33};

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

  /// Signed and unsigned round trips at every offset for an arbitrary width, the
  /// generalisation of [#testInt128] and [#testInt256] to the widths this class
  /// does not name — a `u192` pod decimal being the one that asked for them.
  private static void testWidth(final BigInteger expected, final int byteSize) {
    final byte[] write = new byte[byteSize * 2];
    final var modulus = BigInteger.TWO.pow(byteSize * Byte.SIZE);
    for (int i = 0; i <= byteSize; ++i) {
      Arrays.fill(write, (byte) 0x5A);
      assertEquals(byteSize, ByteUtil.putIntLE(write, i, expected, byteSize));
      assertEquals(expected, ByteUtil.getIntLE(write, i, byteSize), "signed width " + byteSize);
      assertEquals(expected.mod(modulus), ByteUtil.getUIntLE(write, i, byteSize), "unsigned width " + byteSize);
      assertContained(write, i, byteSize);
    }
  }

  @Test
  void testArbitraryWidthIntegers() {
    for (final int byteSize : WIDTHS) {
      final int bits = byteSize * Byte.SIZE;
      testWidth(BigInteger.ZERO, byteSize);
      testWidth(BigInteger.ONE, byteSize);
      testWidth(BigInteger.ONE.negate(), byteSize);
      testWidth(BigInteger.TWO.pow(bits - 1).subtract(BigInteger.ONE), byteSize);
      testWidth(BigInteger.TWO.pow(bits - 1).negate(), byteSize);
    }
  }

  /// The whole reason the unsigned reader is a separate method: a field whose top
  /// bit is set is a large positive number, and reading it through the signed one
  /// returns a value short by exactly the modulus.
  @Test
  void testArbitraryWidthUnsignedExtremes() {
    for (final int byteSize : WIDTHS) {
      final var modulus = BigInteger.TWO.pow(byteSize * Byte.SIZE);
      final var uMax = modulus.subtract(BigInteger.ONE);
      final var topBit = BigInteger.TWO.pow(byteSize * Byte.SIZE - 1);
      final byte[] write = new byte[byteSize * 2];
      for (int i = 0; i <= byteSize; ++i) {
        Arrays.fill(write, (byte) 0x5A);
        assertEquals(byteSize, ByteUtil.putUIntLE(write, i, uMax, byteSize));
        assertEquals(uMax, ByteUtil.getUIntLE(write, i, byteSize));
        assertEquals(BigInteger.ONE.negate(), ByteUtil.getIntLE(write, i, byteSize));
        assertContained(write, i, byteSize);

        assertEquals(byteSize, ByteUtil.putUIntLE(write, i, topBit, byteSize));
        assertEquals(topBit, ByteUtil.getUIntLE(write, i, byteSize));
        assertEquals(topBit.subtract(modulus), ByteUtil.getIntLE(write, i, byteSize));
        assertContained(write, i, byteSize);
      }
      assertThrows(IllegalArgumentException.class,
          () -> ByteUtil.putIntLE(new byte[byteSize], 0, modulus, byteSize));
    }
  }

  /// The arbitrary-width reads against an oracle that shares no code with them:
  /// each byte weighted by its own power of 256, and the signed view derived from
  /// the top byte rather than from a second traversal. A transposed byte, an
  /// off-by-one offset or a dropped high byte all fail here.
  @Test
  void arbitraryWidthReadsAgreeWithAPositionalOracle() {
    final var random = new Random(192L);
    final byte[] data = new byte[64];
    for (int t = 0; t < 2_000; ++t) {
      random.nextBytes(data);
      final int byteSize = 1 + random.nextInt(33);
      final int offset = random.nextInt(data.length - byteSize + 1);

      var unsigned = BigInteger.ZERO;
      for (int i = 0; i < byteSize; ++i) {
        unsigned = unsigned.add(BigInteger.valueOf(data[offset + i] & 0xFF).shiftLeft(i * Byte.SIZE));
      }
      assertEquals(unsigned, ByteUtil.getUIntLE(data, offset, byteSize),
          "unsigned " + byteSize + " bytes at " + offset);
      assertTrue(ByteUtil.getUIntLE(data, offset, byteSize).signum() >= 0);

      final var signed = data[offset + byteSize - 1] < 0
          ? unsigned.subtract(BigInteger.TWO.pow(byteSize * Byte.SIZE))
          : unsigned;
      assertEquals(signed, ByteUtil.getIntLE(data, offset, byteSize),
          "signed " + byteSize + " bytes at " + offset);
    }
  }

  /// The named widths and the arbitrary-width ones are the same field read two
  /// ways; if they ever disagreed, one of the two families would be wrong about
  /// the layout of every account that uses it.
  @Test
  void namedWidthsAgreeWithTheArbitraryWidthReaders() {
    final var random = new Random(128L);
    final byte[] data = new byte[64];
    for (int t = 0; t < 500; ++t) {
      random.nextBytes(data);
      for (int offset = 0; offset <= 16; ++offset) {
        assertEquals(ByteUtil.getIntLE(data, offset, 16), ByteUtil.getInt128LE(data, offset));
        assertEquals(ByteUtil.getUIntLE(data, offset, 16), ByteUtil.getUInt128LE(data, offset));
        assertEquals(ByteUtil.getIntLE(data, offset, 32), ByteUtil.getInt256LE(data, offset));
        assertEquals(ByteUtil.getUIntLE(data, offset, 32), ByteUtil.getUInt256LE(data, offset));
      }
    }
  }

  private static void assertRejectsWidth(final Executable executable) {
    final var thrown = assertThrows(IllegalArgumentException.class, executable);
    // NumberFormatException is an IllegalArgumentException: without the width
    // check, an empty field reaches the BigInteger constructor and throws one,
    // which a type-only assertion cannot tell apart from the rejection.
    assertEquals(IllegalArgumentException.class, thrown.getClass(), "wrong exception: " + thrown);
    assertTrue(thrown.getMessage().startsWith("byteSize must be positive"), thrown.getMessage());
  }

  /// A width that addresses no bytes is a caller mistake, not a value: unchecked,
  /// `byteSize == 0` reads as zero through the unsigned reader, throws a
  /// `NumberFormatException` through the signed one and writes nothing at all
  /// through the writers — three answers to one question.
  @Test
  void testNonPositiveWidthIsRejected() {
    final byte[] data = new byte[8];
    for (final int byteSize : new int[]{0, -1, Integer.MIN_VALUE}) {
      assertRejectsWidth(() -> ByteUtil.getIntLE(data, 0, byteSize));
      assertRejectsWidth(() -> ByteUtil.getUIntLE(data, 0, byteSize));
      assertRejectsWidth(() -> ByteUtil.putIntLE(data, 0, BigInteger.ZERO, byteSize));
      assertRejectsWidth(() -> ByteUtil.putUIntLE(data, 0, BigInteger.ZERO, byteSize));
    }
  }

  /// The field is bounds-checked before the first byte moves, so a write that
  /// cannot fit leaves the buffer as it found it rather than half-updated.
  @Test
  void testFieldIsBoundsCheckedBeforeAnythingIsWritten() {
    final byte[] data = new byte[16];
    Arrays.fill(data, (byte) 0x5A);

    assertThrows(IndexOutOfBoundsException.class, () -> ByteUtil.putIntLE(data, 1, BigInteger.ONE, 16));
    assertThrows(IndexOutOfBoundsException.class, () -> ByteUtil.putUIntLE(data, 9, BigInteger.ONE, 8));
    assertThrows(IndexOutOfBoundsException.class, () -> ByteUtil.putInt256LE(data, 0, BigInteger.ONE));
    assertThrows(IndexOutOfBoundsException.class, () -> ByteUtil.putIntLE(data, -1, BigInteger.ONE, 4));
    for (final byte b : data) {
      assertEquals((byte) 0x5A, b, "a rejected write reached the buffer");
    }

    assertThrows(IndexOutOfBoundsException.class, () -> ByteUtil.getIntLE(data, 9, 8));
    assertThrows(IndexOutOfBoundsException.class, () -> ByteUtil.getUIntLE(data, 9, 8));
    assertThrows(IndexOutOfBoundsException.class, () -> ByteUtil.getUIntLE(data, -1, 4));

    // a field ending exactly at the last byte is in bounds, at either end
    assertEquals(16, ByteUtil.putIntLE(data, 0, BigInteger.ONE, 16));
    assertEquals(BigInteger.ONE, ByteUtil.getUIntLE(data, 0, 16));
    assertEquals(1, ByteUtil.putIntLE(data, 15, BigInteger.ONE, 1));
    assertEquals(BigInteger.ONE, ByteUtil.getUIntLE(data, 15, 1));
  }

  /// `putUIntLE` differs from `putIntLE` by one rejected operand, and that is the
  /// point: sign extension is correct for an `i128` field and is silently
  /// `2^n - 1` for an unsigned one.
  @Test
  void testUnsignedWriterRejectsNegatives() {
    final byte[] write = new byte[24];
    Arrays.fill(write, (byte) 0x5A);
    final var thrown = assertThrows(IllegalArgumentException.class,
        () -> ByteUtil.putUIntLE(write, 0, BigInteger.ONE.negate(), 24));
    assertTrue(thrown.getMessage().contains("negative"), thrown.getMessage());
    for (final byte b : write) {
      assertEquals((byte) 0x5A, b, "a rejected write reached the buffer");
    }

    // zero is not negative, and the full unsigned range is accepted
    assertEquals(24, ByteUtil.putUIntLE(write, 0, BigInteger.ZERO, 24));
    assertEquals(BigInteger.ZERO, ByteUtil.getUIntLE(write, 0, 24));
    final var uMax = TWO_POW_192.subtract(BigInteger.ONE);
    assertEquals(24, ByteUtil.putUIntLE(write, 0, uMax, 24));
    assertEquals(uMax, ByteUtil.getUIntLE(write, 0, 24));
    assertThrows(IllegalArgumentException.class, () -> ByteUtil.putUIntLE(write, 0, TWO_POW_192, 24));

    // what the signed writer does with the operand the unsigned one refuses
    assertEquals(24, ByteUtil.putIntLE(write, 0, BigInteger.ONE.negate(), 24));
    assertEquals(uMax, ByteUtil.getUIntLE(write, 0, 24));
  }

  /// The narrow unsigned readers against the arbitrary-width one: the same bytes
  /// read as a `u16`, `u32` or `u64` must be the number the generic reader sees,
  /// and must never come back negative the way the signed readers do.
  @Test
  void testUnsignedNarrowReaders() {
    final byte[] data = new byte[24];
    final var random = new Random(64L);
    for (int t = 0; t < 1_000; ++t) {
      random.nextBytes(data);
      for (int offset = 0; offset <= 16; ++offset) {
        assertEquals(ByteUtil.getUIntLE(data, offset, Short.BYTES).intValueExact(),
            ByteUtil.getUInt16LE(data, offset));
        assertEquals(ByteUtil.getUIntLE(data, offset, Integer.BYTES).longValueExact(),
            ByteUtil.getUInt32LE(data, offset));
        assertEquals(ByteUtil.getUIntLE(data, offset, Long.BYTES),
            ByteUtil.getUInt64LE(data, offset));
      }
    }

    // the values the signed readers get wrong
    ByteUtil.putInt16LE(data, 0, (short) 0xFFFF);
    assertEquals(-1, ByteUtil.getInt16LE(data, 0));
    assertEquals(65_535, ByteUtil.getUInt16LE(data, 0));
    ByteUtil.putInt16LE(data, 2, Short.MIN_VALUE);
    assertEquals(32_768, ByteUtil.getUInt16LE(data, 2));

    ByteUtil.putInt32LE(data, 4, -1);
    assertEquals(4_294_967_295L, ByteUtil.getUInt32LE(data, 4));
    ByteUtil.putInt32LE(data, 8, Integer.MIN_VALUE);
    assertEquals(2_147_483_648L, ByteUtil.getUInt32LE(data, 8));

    ByteUtil.putInt64LE(data, 12, -1L);
    assertEquals(new BigInteger("18446744073709551615"), ByteUtil.getUInt64LE(data, 12));
    ByteUtil.putInt64LE(data, 12, Long.MIN_VALUE);
    assertEquals(new BigInteger("9223372036854775808"), ByteUtil.getUInt64LE(data, 12));
    ByteUtil.putInt64LE(data, 12, 1L);
    assertEquals(BigInteger.ONE, ByteUtil.getUInt64LE(data, 12));
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
