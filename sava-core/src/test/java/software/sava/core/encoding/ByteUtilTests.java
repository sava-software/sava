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
      ByteUtil.putInt128LE(write, i, expected);
      assertEquals(expected, ByteUtil.getInt128LE(write, i));
      assertEquals(expected.mod(TWO_POW_128), ByteUtil.getUInt128LE(write, i));
      assertContained(write, i, 16);
    }
  }

  private void testInt256(final BigInteger expected) {
    final byte[] write = new byte[64];
    for (int i = 0; i < 32; ++i) {
      Arrays.fill(write, (byte) 0x5A);
      ByteUtil.putInt256LE(write, i, expected);
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
}
