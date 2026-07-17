package software.sava.core.borsh;

import org.junit.jupiter.api.Test;
import software.sava.core.encoding.ByteUtil;

import java.time.Instant;
import java.util.Arrays;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

/// One-dimension vector, fixed array, checked, scalar, and Optional coverage for the
/// primitive Borsh families; BorshTests covers the matrix families. Buffers are dirtied
/// so dropped writes and overwrites past the promised length are both observable.
final class BorshPrimitiveVectorTests {

  private static byte[] dirty(final int length) {
    final byte[] data = new byte[length];
    Arrays.fill(data, (byte) 0xAA);
    return data;
  }

  private static void assertUntouched(final byte[] data, final int from, final int to) {
    for (int i = from; i < to; ++i) {
      assertEquals((byte) 0xAA, data[i], "byte " + i + " was written");
    }
  }

  @Test
  void intFamily() {
    final int[] vec = {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE};
    final int offset = 3;
    final int payload = vec.length * Integer.BYTES;
    assertEquals(Integer.BYTES + payload, Borsh.lenVector(vec));
    assertEquals(payload, Borsh.lenArray(vec));

    final byte[] data = dirty(offset + Integer.BYTES + payload + 2);
    assertEquals(Integer.BYTES + payload, Borsh.writeVector(vec, data, offset));
    assertEquals(vec.length, ByteUtil.getInt32LE(data, offset));
    for (int i = 0; i < vec.length; ++i) {
      assertEquals(vec[i], ByteUtil.getInt32LE(data, offset + Integer.BYTES + i * Integer.BYTES));
    }
    assertUntouched(data, 0, offset);
    assertUntouched(data, offset + Integer.BYTES + payload, data.length);

    assertArrayEquals(vec, Borsh.readintVector(data, offset));
    final int[] result = new int[vec.length];
    assertEquals(payload, Borsh.readArray(result, data, offset + Integer.BYTES));
    assertArrayEquals(vec, result);

    final byte[] arrayData = dirty(payload);
    assertEquals(payload, Borsh.writeArray(vec, arrayData, 0));
    assertArrayEquals(Arrays.copyOfRange(data, offset + Integer.BYTES, offset + Integer.BYTES + payload), arrayData);
    assertEquals(payload, Borsh.writeArrayChecked(vec, vec.length, arrayData, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(vec, vec.length + 1, arrayData, 0));

    final byte[] optional = dirty(1 + Integer.BYTES);
    assertEquals(1, Borsh.writeOptional(OptionalInt.empty(), optional, 0));
    assertEquals(0, optional[0]);
    assertEquals(1, Borsh.writeOptional((OptionalInt) null, optional, 0));
    assertEquals(5, Borsh.writeOptional(OptionalInt.of(42), optional, 0));
    assertEquals(1, optional[0]);
    assertEquals(42, ByteUtil.getInt32LE(optional, 1));
    assertEquals(1, Borsh.lenOptional(OptionalInt.empty()));
    assertEquals(5, Borsh.lenOptional(OptionalInt.of(42)));
  }

  @Test
  void byteFamily() {
    final byte[] vec = {Byte.MIN_VALUE, -1, 0, 1, Byte.MAX_VALUE};
    assertEquals(Integer.BYTES + vec.length, Borsh.lenVector(vec));
    assertEquals(vec.length, Borsh.lenArray(vec));

    final byte[] data = dirty(Integer.BYTES + vec.length);
    Borsh.writeVector(vec, data, 0);
    assertArrayEquals(vec, Borsh.readbyteVector(data, 0));
    final byte[] result = new byte[vec.length];
    assertEquals(vec.length, Borsh.readArray(result, data, Integer.BYTES));
    assertArrayEquals(vec, result);

    assertEquals(vec.length, Borsh.writeArrayChecked(vec, vec.length, dirty(vec.length), 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(vec, 1, dirty(vec.length), 0));

    // null and empty are both written as absent
    final byte[] optional = dirty(1 + Integer.BYTES + vec.length);
    assertEquals(1, Borsh.writeOptionalVector(null, optional, 0));
    assertEquals(0, optional[0]);
    assertEquals(1, Borsh.writeOptionalVector(new byte[0], optional, 0));
    assertEquals(0, optional[0]);
    assertEquals(1 + Integer.BYTES + vec.length, Borsh.writeOptionalVector(vec, optional, 0));
    assertEquals(1, optional[0]);
    assertEquals(vec.length, ByteUtil.getInt32LE(optional, 1));
    assertEquals(1, Borsh.lenOptionalVector(null));
    assertEquals(1, Borsh.lenOptionalVector(new byte[0]));
    assertEquals(1 + Integer.BYTES + vec.length, Borsh.lenOptionalVector(vec));

    assertEquals(1, Borsh.writeOptionalArray(null, optional, 0));
    assertEquals(0, optional[0]);
    assertEquals(1 + vec.length, Borsh.writeOptionalArray(vec, optional, 0));
    assertEquals(1, optional[0]);
    assertArrayEquals(vec, Arrays.copyOfRange(optional, 1, 1 + vec.length));

    assertEquals(1, Borsh.writeOptionalbyte(OptionalInt.empty(), optional, 0));
    assertEquals(2, Borsh.writeOptionalbyte(OptionalInt.of(7), optional, 0));
    assertEquals(7, optional[1]);
    assertEquals(1, Borsh.lenOptionalbyte(OptionalInt.empty()));
    assertEquals(2, Borsh.lenOptionalbyte(OptionalInt.of(7)));

    assertEquals(1, Borsh.writeOptional((Byte) null, optional, 0));
    assertEquals(2, Borsh.writeOptional(Byte.valueOf((byte) 9), optional, 0));
    assertEquals(9, optional[1]);
    assertEquals(1, Borsh.lenOptional((Byte) null));
    assertEquals(2, Borsh.lenOptional(Byte.valueOf((byte) 9)));
  }

  @Test
  void booleanFamily() {
    final boolean[] vec = {true, false, true, true, false};
    assertEquals(Integer.BYTES + vec.length, Borsh.lenVector(vec));
    assertEquals(vec.length, Borsh.lenArray(vec));

    final byte[] data = dirty(Integer.BYTES + vec.length);
    assertEquals(Integer.BYTES + vec.length, Borsh.writeVector(vec, data, 0));
    for (int i = 0; i < vec.length; ++i) {
      assertEquals(vec[i] ? 1 : 0, data[Integer.BYTES + i]);
    }
    assertArrayEquals(vec, Borsh.readbooleanVector(data, 0));
    final boolean[] result = new boolean[vec.length];
    assertEquals(vec.length, Borsh.readArray(result, data, Integer.BYTES));
    assertArrayEquals(vec, result);

    assertEquals(vec.length, Borsh.writeArrayChecked(vec, vec.length, dirty(vec.length), 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(vec, 0, dirty(vec.length), 0));

    final byte[] scalar = dirty(2);
    assertEquals(1, Borsh.write(true, scalar, 0));
    assertEquals(1, scalar[0]);
    assertEquals(1, Borsh.write(false, scalar, 0));
    assertEquals(0, scalar[0]);

    assertEquals(1, Borsh.writeOptional((Boolean) null, scalar, 0));
    assertEquals(0, scalar[0]);
    assertEquals(2, Borsh.writeOptional(Boolean.TRUE, scalar, 0));
    assertEquals(1, scalar[0]);
    assertEquals(1, scalar[1]);
    assertEquals(2, Borsh.writeOptional(Boolean.FALSE, scalar, 0));
    assertEquals(0, scalar[1]);
    assertEquals(1, Borsh.lenOptional((Boolean) null));
    assertEquals(2, Borsh.lenOptional(Boolean.TRUE));
  }

  @Test
  void shortFamily() {
    final short[] vec = {Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE};
    final int payload = vec.length * Short.BYTES;
    assertEquals(Integer.BYTES + payload, Borsh.lenVector(vec));
    assertEquals(payload, Borsh.lenArray(vec));

    final byte[] data = dirty(Integer.BYTES + payload);
    assertEquals(Integer.BYTES + payload, Borsh.writeVector(vec, data, 0));
    for (int i = 0; i < vec.length; ++i) {
      assertEquals(vec[i], ByteUtil.getInt16LE(data, Integer.BYTES + i * Short.BYTES));
    }
    assertArrayEquals(vec, Borsh.readshortVector(data, 0));
    final short[] result = new short[vec.length];
    assertEquals(payload, Borsh.readArray(result, data, Integer.BYTES));
    assertArrayEquals(vec, result);

    assertEquals(payload, Borsh.writeArrayChecked(vec, vec.length, dirty(payload), 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(vec, 2, dirty(payload), 0));

    final byte[] optional = dirty(1 + Short.BYTES);
    assertEquals(1, Borsh.writeOptionalshort(OptionalInt.empty(), optional, 0));
    assertEquals(0, optional[0]);
    assertEquals(3, Borsh.writeOptionalshort(OptionalInt.of(-2), optional, 0));
    assertEquals(-2, ByteUtil.getInt16LE(optional, 1));
    assertEquals(1, Borsh.lenOptionalshort(OptionalInt.empty()));
    assertEquals(3, Borsh.lenOptionalshort(OptionalInt.of(-2)));

    assertEquals(1, Borsh.writeOptional((Short) null, optional, 0));
    assertEquals(3, Borsh.writeOptional(Short.valueOf((short) 11), optional, 0));
    assertEquals(11, ByteUtil.getInt16LE(optional, 1));
    assertEquals(1, Borsh.lenOptional((Short) null));
    assertEquals(3, Borsh.lenOptional(Short.valueOf((short) 11)));
  }

  @Test
  void longFamily() {
    final long[] vec = {Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE};
    final int payload = vec.length * Long.BYTES;
    assertEquals(Integer.BYTES + payload, Borsh.lenVector(vec));
    assertEquals(payload, Borsh.lenArray(vec));

    final byte[] data = dirty(Integer.BYTES + payload);
    assertEquals(Integer.BYTES + payload, Borsh.writeVector(vec, data, 0));
    for (int i = 0; i < vec.length; ++i) {
      assertEquals(vec[i], ByteUtil.getInt64LE(data, Integer.BYTES + i * Long.BYTES));
    }
    assertArrayEquals(vec, Borsh.readlongVector(data, 0));
    final long[] result = new long[vec.length];
    assertEquals(payload, Borsh.readArray(result, data, Integer.BYTES));
    assertArrayEquals(vec, result);

    assertEquals(payload, Borsh.writeArrayChecked(vec, vec.length, dirty(payload), 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(vec, 4, dirty(payload), 0));

    final byte[] optional = dirty(1 + Long.BYTES);
    assertEquals(1, Borsh.writeOptional(OptionalLong.empty(), optional, 0));
    assertEquals(0, optional[0]);
    assertEquals(1, Borsh.writeOptional((OptionalLong) null, optional, 0));
    assertEquals(9, Borsh.writeOptional(OptionalLong.of(Long.MAX_VALUE), optional, 0));
    assertEquals(Long.MAX_VALUE, ByteUtil.getInt64LE(optional, 1));
    assertEquals(1, Borsh.lenOptional(OptionalLong.empty()));
    assertEquals(9, Borsh.lenOptional(OptionalLong.of(1L)));

    final var instant = Instant.ofEpochMilli(1_752_000_000_123L);
    assertEquals(1, Borsh.writeOptional((Instant) null, optional, 0));
    assertEquals(0, optional[0]);
    assertEquals(9, Borsh.writeOptional(instant, optional, 0));
    assertEquals(instant.toEpochMilli(), ByteUtil.getInt64LE(optional, 1));
  }

  @Test
  void floatFamily() {
    final float[] vec = {Float.MIN_VALUE, -1.5f, 0f, 2.25f, Float.MAX_VALUE};
    final int payload = vec.length * Float.BYTES;
    assertEquals(Integer.BYTES + payload, Borsh.lenVector(vec));
    assertEquals(payload, Borsh.lenArray(vec));

    final byte[] data = dirty(Integer.BYTES + payload);
    assertEquals(Integer.BYTES + payload, Borsh.writeVector(vec, data, 0));
    for (int i = 0; i < vec.length; ++i) {
      assertEquals(Float.floatToIntBits(vec[i]), ByteUtil.getInt32LE(data, Integer.BYTES + i * Float.BYTES));
    }
    assertArrayEquals(vec, Borsh.readfloatVector(data, 0));
    final float[] result = new float[vec.length];
    assertEquals(payload, Borsh.readArray(result, data, Integer.BYTES));
    assertArrayEquals(vec, result);

    assertEquals(payload, Borsh.writeArrayChecked(vec, vec.length, dirty(payload), 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(vec, 6, dirty(payload), 0));

    final byte[] optional = dirty(1 + Float.BYTES);
    assertEquals(1, Borsh.writeOptionalfloat(OptionalDouble.empty(), optional, 0));
    assertEquals(0, optional[0]);
    assertEquals(5, Borsh.writeOptionalfloat(OptionalDouble.of(2.25), optional, 0));
    assertEquals(2.25f, ByteUtil.getFloat32LE(optional, 1));
    assertEquals(1, Borsh.lenOptionalfloat(OptionalDouble.empty()));
    assertEquals(5, Borsh.lenOptionalfloat(OptionalDouble.of(2.25)));
  }

  @Test
  void doubleFamily() {
    final double[] vec = {Double.MIN_VALUE, -1.5, 0d, 3.75, Double.MAX_VALUE};
    final int payload = vec.length * Double.BYTES;
    assertEquals(Integer.BYTES + payload, Borsh.lenVector(vec));
    assertEquals(payload, Borsh.lenArray(vec));

    final byte[] data = dirty(Integer.BYTES + payload);
    assertEquals(Integer.BYTES + payload, Borsh.writeVector(vec, data, 0));
    for (int i = 0; i < vec.length; ++i) {
      assertEquals(Double.doubleToLongBits(vec[i]), ByteUtil.getInt64LE(data, Integer.BYTES + i * Double.BYTES));
    }
    assertArrayEquals(vec, Borsh.readdoubleVector(data, 0));
    final double[] result = new double[vec.length];
    assertEquals(payload, Borsh.readArray(result, data, Integer.BYTES));
    assertArrayEquals(vec, result);

    assertEquals(payload, Borsh.writeArrayChecked(vec, vec.length, dirty(payload), 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(vec, 3, dirty(payload), 0));

    final byte[] optional = dirty(1 + Double.BYTES);
    assertEquals(1, Borsh.writeOptional(OptionalDouble.empty(), optional, 0));
    assertEquals(0, optional[0]);
    assertEquals(1, Borsh.writeOptional((OptionalDouble) null, optional, 0));
    assertEquals(9, Borsh.writeOptional(OptionalDouble.of(3.75), optional, 0));
    assertEquals(3.75, ByteUtil.getFloat64LE(optional, 1));
    assertEquals(1, Borsh.lenOptional(OptionalDouble.empty()));
    assertEquals(9, Borsh.lenOptional(OptionalDouble.of(3.75)));
  }

  @Test
  void matrixCheckedWritersEnforceBothDimensions() {
    final int[][] matrix = {{1, 2, 3}, {4, 5, 6}};
    final int payload = 6 * Integer.BYTES;
    final byte[] expected = dirty(payload);
    Borsh.writeArray(matrix, expected, 0);

    final byte[] data = dirty(payload);
    assertEquals(payload, Borsh.writeArrayChecked(matrix, 3, data, 0));
    assertArrayEquals(expected, data);
    assertEquals(payload, Borsh.writeArrayChecked(matrix, 2, 3, data, 0));
    assertArrayEquals(expected, data);
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(matrix, 4, data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(matrix, 3, 3, data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(matrix, 2, 2, data, 0));

    final byte[] vectorArray = dirty(Integer.BYTES + payload);
    final byte[] vectorArrayChecked = dirty(Integer.BYTES + payload);
    assertEquals(Integer.BYTES + payload, Borsh.writeVectorArray(matrix, vectorArray, 0));
    assertEquals(matrix.length, ByteUtil.getInt32LE(vectorArray, 0));
    assertEquals(Integer.BYTES + payload, Borsh.writeVectorArrayChecked(matrix, 3, vectorArrayChecked, 0));
    assertArrayEquals(vectorArray, vectorArrayChecked);
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeVectorArrayChecked(matrix, 2, vectorArrayChecked, 0));
  }

  @Test
  void booleanMatrixFamily() {
    final boolean[][] matrix = {{true, false, true}, {false, true, false}};
    final int payload = 6;
    assertEquals(payload, Borsh.lenArray(matrix));
    assertEquals(Integer.BYTES + payload + (2 * Integer.BYTES), Borsh.lenVector(matrix));
    assertEquals(Integer.BYTES + payload, Borsh.lenVectorArray(matrix));

    final byte[] array = dirty(2 + payload);
    assertEquals(payload, Borsh.writeArray(matrix, array, 2));
    for (int i = 0; i < payload; ++i) {
      assertEquals(matrix[i / 3][i % 3] ? 1 : 0, array[2 + i]);
    }
    final boolean[][] arrayResult = new boolean[2][3];
    assertEquals(payload, Borsh.readArray(arrayResult, array, 2));
    assertTrue(Arrays.deepEquals(matrix, arrayResult));

    final byte[] vector = dirty(3 + Borsh.lenVector(matrix));
    assertEquals(Borsh.lenVector(matrix), Borsh.writeVector(matrix, vector, 3));
    assertEquals(matrix.length, ByteUtil.getInt32LE(vector, 3));
    final byte[] vectorAtZero = Arrays.copyOfRange(vector, 3, vector.length);
    assertTrue(Arrays.deepEquals(matrix, Borsh.readMultiDimensionbooleanVector(vectorAtZero, 0)));

    final byte[] vectorArray = dirty(3 + Integer.BYTES + payload);
    assertEquals(Integer.BYTES + payload, Borsh.writeVectorArray(matrix, vectorArray, 3));
    final byte[] vectorArrayAtZero = Arrays.copyOfRange(vectorArray, 3, vectorArray.length);
    assertTrue(Arrays.deepEquals(matrix, Borsh.readMultiDimensionbooleanVectorArray(3, vectorArrayAtZero, 0)));
    final byte[] checked = dirty(vectorArray.length);
    assertEquals(Integer.BYTES + payload, Borsh.writeVectorArrayChecked(matrix, 3, checked, 3));
    assertArrayEquals(vectorArray, checked);
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeVectorArrayChecked(matrix, 2, checked, 3));

    assertEquals(payload, Borsh.writeArrayChecked(matrix, 3, array, 2));
    assertEquals(payload, Borsh.writeArrayChecked(matrix, 2, 3, array, 2));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(matrix, 2, array, 2));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(matrix, 1, 3, array, 2));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(matrix, 2, 2, array, 2));
  }

  @Test
  void byteMatrixChecked() {
    final byte[][] matrix = {{1, 2, 3}, {4, 5, 6}};
    assertMatrixChecked(
        matrix, 6,
        Borsh::writeArray, Borsh::writeVectorArray, Borsh::writeVector,
        (m, fl, data, offset) -> Borsh.writeArrayChecked(m, fl, data, offset),
        (m, first, second, data, offset) -> Borsh.writeArrayChecked(m, first, second, data, offset),
        (m, fl, data, offset) -> Borsh.writeVectorArrayChecked(m, fl, data, offset),
        Borsh.lenVector(matrix)
    );
  }

  @Test
  void shortMatrixChecked() {
    final short[][] matrix = {{1, -2, 3}, {4, 5, -6}};
    assertMatrixChecked(
        matrix, 6 * Short.BYTES,
        Borsh::writeArray, Borsh::writeVectorArray, Borsh::writeVector,
        (m, fl, data, offset) -> Borsh.writeArrayChecked(m, fl, data, offset),
        (m, first, second, data, offset) -> Borsh.writeArrayChecked(m, first, second, data, offset),
        (m, fl, data, offset) -> Borsh.writeVectorArrayChecked(m, fl, data, offset),
        Borsh.lenVector(matrix)
    );
  }

  @Test
  void intMatrixChecked() {
    final int[][] matrix = {{7, -8, 9}, {10, -11, 12}};
    assertMatrixChecked(
        matrix, 6 * Integer.BYTES,
        Borsh::writeArray, Borsh::writeVectorArray, Borsh::writeVector,
        (m, fl, data, offset) -> Borsh.writeArrayChecked(m, fl, data, offset),
        (m, first, second, data, offset) -> Borsh.writeArrayChecked(m, first, second, data, offset),
        (m, fl, data, offset) -> Borsh.writeVectorArrayChecked(m, fl, data, offset),
        Borsh.lenVector(matrix)
    );
  }

  @Test
  void longMatrixChecked() {
    final long[][] matrix = {{1L, -2L, 3L}, {4L, 5L, -6L}};
    assertMatrixChecked(
        matrix, 6 * Long.BYTES,
        Borsh::writeArray, Borsh::writeVectorArray, Borsh::writeVector,
        (m, fl, data, offset) -> Borsh.writeArrayChecked(m, fl, data, offset),
        (m, first, second, data, offset) -> Borsh.writeArrayChecked(m, first, second, data, offset),
        (m, fl, data, offset) -> Borsh.writeVectorArrayChecked(m, fl, data, offset),
        Borsh.lenVector(matrix)
    );
  }

  @Test
  void floatMatrixChecked() {
    final float[][] matrix = {{1.5f, -2f, 3f}, {4f, 5.25f, -6f}};
    assertMatrixChecked(
        matrix, 6 * Float.BYTES,
        Borsh::writeArray, Borsh::writeVectorArray, Borsh::writeVector,
        (m, fl, data, offset) -> Borsh.writeArrayChecked(m, fl, data, offset),
        (m, first, second, data, offset) -> Borsh.writeArrayChecked(m, first, second, data, offset),
        (m, fl, data, offset) -> Borsh.writeVectorArrayChecked(m, fl, data, offset),
        Borsh.lenVector(matrix)
    );
  }

  @Test
  void doubleMatrixChecked() {
    final double[][] matrix = {{1.5, -2d, 3d}, {4d, 5.25, -6d}};
    assertMatrixChecked(
        matrix, 6 * Double.BYTES,
        Borsh::writeArray, Borsh::writeVectorArray, Borsh::writeVector,
        (m, fl, data, offset) -> Borsh.writeArrayChecked(m, fl, data, offset),
        (m, first, second, data, offset) -> Borsh.writeArrayChecked(m, first, second, data, offset),
        (m, fl, data, offset) -> Borsh.writeVectorArrayChecked(m, fl, data, offset),
        Borsh.lenVector(matrix)
    );
  }

  @FunctionalInterface
  private interface MatrixWriter<M> {

    int write(final M matrix, final byte[] data, final int offset);
  }

  @FunctionalInterface
  private interface CheckedWriter<M> {

    int write(final M matrix, final int fixedLength, final byte[] data, final int offset);
  }

  @FunctionalInterface
  private interface DimensionCheckedWriter<M> {

    int write(final M matrix, final int first, final int second, final byte[] data, final int offset);
  }

  /// Shared 2x3 matrix assertions: the checked writers must be byte-identical to the
  /// unchecked ones, throw on any dimension mismatch, and every writer runs at a non-zero
  /// offset so offset arithmetic is observable.
  private static <M> void assertMatrixChecked(final M matrix,
                                              final int payload,
                                              final MatrixWriter<M> writeArray,
                                              final MatrixWriter<M> writeVectorArray,
                                              final MatrixWriter<M> writeVector,
                                              final CheckedWriter<M> writeArrayChecked,
                                              final DimensionCheckedWriter<M> writeDimensionChecked,
                                              final CheckedWriter<M> writeVectorArrayChecked,
                                              final int vectorLen) {
    final byte[] array = dirty(2 + payload);
    assertEquals(payload, writeArray.write(matrix, array, 2));
    final byte[] checkedArray = dirty(array.length);
    assertEquals(payload, writeArrayChecked.write(matrix, 3, checkedArray, 2));
    assertArrayEquals(array, checkedArray);
    assertEquals(payload, writeDimensionChecked.write(matrix, 2, 3, checkedArray, 2));
    assertArrayEquals(array, checkedArray);
    assertThrows(IllegalArgumentException.class, () -> writeArrayChecked.write(matrix, 2, checkedArray, 2));
    assertThrows(IllegalArgumentException.class, () -> writeDimensionChecked.write(matrix, 1, 3, checkedArray, 2));
    assertThrows(IllegalArgumentException.class, () -> writeDimensionChecked.write(matrix, 2, 2, checkedArray, 2));

    final byte[] vectorArray = dirty(3 + Integer.BYTES + payload);
    assertEquals(Integer.BYTES + payload, writeVectorArray.write(matrix, vectorArray, 3));
    assertEquals(2, ByteUtil.getInt32LE(vectorArray, 3));
    final byte[] checkedVectorArray = dirty(vectorArray.length);
    assertEquals(Integer.BYTES + payload, writeVectorArrayChecked.write(matrix, 3, checkedVectorArray, 3));
    assertArrayEquals(vectorArray, checkedVectorArray);
    assertThrows(IllegalArgumentException.class, () -> writeVectorArrayChecked.write(matrix, 2, checkedVectorArray, 3));

    final byte[] vector = dirty(3 + vectorLen);
    assertEquals(vectorLen, writeVector.write(matrix, vector, 3));
    assertEquals(2, ByteUtil.getInt32LE(vector, 3));
  }

  @Test
  void fixedRowVectorBoundsAreExact() {
    // room for exactly two rows of three elements; a claim of three rows must be rejected
    // by the length validation, not by falling over further into the read
    final byte[] byteData = new byte[Integer.BYTES + 6];
    ByteUtil.putInt32LE(byteData, 0, 3);
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionbyteVectorArray(3, byteData, 0));
    ByteUtil.putInt32LE(byteData, 0, 2);
    assertEquals(2, Borsh.readMultiDimensionbyteVectorArray(3, byteData, 0).length);

    final byte[] booleanData = new byte[Integer.BYTES + 6];
    ByteUtil.putInt32LE(booleanData, 0, 3);
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionbooleanVectorArray(3, booleanData, 0));

    final byte[] shortData = new byte[Integer.BYTES + 6 * Short.BYTES];
    ByteUtil.putInt32LE(shortData, 0, 3);
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionshortVectorArray(3, shortData, 0));

    final byte[] intData = new byte[Integer.BYTES + 6 * Integer.BYTES];
    ByteUtil.putInt32LE(intData, 0, 3);
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionintVectorArray(3, intData, 0));

    final byte[] longData = new byte[Integer.BYTES + 6 * Long.BYTES];
    ByteUtil.putInt32LE(longData, 0, 3);
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionlongVectorArray(3, longData, 0));

    final byte[] floatData = new byte[Integer.BYTES + 6 * Float.BYTES];
    ByteUtil.putInt32LE(floatData, 0, 3);
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionfloatVectorArray(3, floatData, 0));

    final byte[] doubleData = new byte[Integer.BYTES + 6 * Double.BYTES];
    ByteUtil.putInt32LE(doubleData, 0, 3);
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensiondoubleVectorArray(3, doubleData, 0));
  }

  @Test
  void optionalNullsAreAbsent() {
    final byte[] data = dirty(4);
    assertEquals(1, Borsh.writeOptionalbyte(null, data, 0));
    assertEquals(0, data[0]);
    assertEquals(1, Borsh.writeOptionalshort(null, data, 0));
    assertEquals(1, Borsh.writeOptionalfloat(null, data, 0));
    assertEquals(1, Borsh.lenOptionalbyte(null));
    assertEquals(1, Borsh.lenOptionalshort(null));
    assertEquals(1, Borsh.lenOptionalfloat(null));
    assertEquals(1, Borsh.lenOptional((OptionalInt) null));
    assertEquals(1, Borsh.lenOptional((OptionalLong) null));
    assertEquals(1, Borsh.lenOptional((OptionalDouble) null));

    // an empty byte array is also written as absent
    assertEquals(1, Borsh.writeOptionalArray(new byte[0], data, 0));
    assertEquals(0, data[0]);
  }

  @Test
  void corruptVectorLengthsAreRejectedBeforeAllocation() {
    // a length prefix claiming more elements than the remaining bytes could hold must be
    // rejected instead of sizing an allocation from attacker-controlled account data
    final byte[] data = new byte[Integer.BYTES + 8];
    ByteUtil.putInt32LE(data, 0, Integer.MAX_VALUE);
    assertThrows(IllegalArgumentException.class, () -> Borsh.readbyteVector(data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readbooleanVector(data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readshortVector(data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readintVector(data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readlongVector(data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readfloatVector(data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readdoubleVector(data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionintVector(data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionintVectorArray(3, data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionbyteVectorArray(0, data, 0));

    // one element over the exact bound is rejected, the exact bound itself parses
    ByteUtil.putInt32LE(data, 0, 3);
    assertThrows(IllegalArgumentException.class, () -> Borsh.readintVector(data, 0));
    ByteUtil.putInt32LE(data, 0, 2);
    assertEquals(2, Borsh.readintVector(data, 0).length);
    ByteUtil.putInt32LE(data, 0, 8);
    assertEquals(8, Borsh.readbyteVector(data, 0).length);

    // negative lengths keep throwing NegativeArraySizeException
    ByteUtil.putInt32LE(data, 0, -1);
    assertThrows(NegativeArraySizeException.class, () -> Borsh.readbyteVector(data, 0));
    assertThrows(NegativeArraySizeException.class, () -> Borsh.readlongVector(data, 0));
    assertThrows(NegativeArraySizeException.class, () -> Borsh.readMultiDimensionintVector(data, 0));
  }
}
