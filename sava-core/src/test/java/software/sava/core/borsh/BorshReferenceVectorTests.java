package software.sava.core.borsh;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.AccountState;
import software.sava.core.encoding.ByteUtil;

import java.math.BigInteger;
import java.util.Arrays;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/// One-dimension vector, fixed array, checked, and Optional coverage for the reference
/// Borsh families — String, PublicKey, 128/256-bit integers, Borsh-typed, and enums;
/// BorshTests covers the matrix families.
final class BorshReferenceVectorTests {

  private static byte[] dirty(final int length) {
    final byte[] data = new byte[length];
    Arrays.fill(data, (byte) 0xAA);
    return data;
  }

  private static PublicKey key(final int fill) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(bytes, (byte) fill);
    return PublicKey.createPubKey(bytes);
  }

  record TestStruct(long a, int b) implements Borsh {

    static final int BYTES = Long.BYTES + Integer.BYTES;

    static TestStruct read(final byte[] data, final int offset) {
      return new TestStruct(ByteUtil.getInt64LE(data, offset), ByteUtil.getInt32LE(data, offset + Long.BYTES));
    }

    @Override
    public int l() {
      return BYTES;
    }

    @Override
    public int write(final byte[] data, final int offset) {
      ByteUtil.putInt64LE(data, offset, a);
      ByteUtil.putInt32LE(data, offset + Long.BYTES, b);
      return BYTES;
    }
  }

  @Test
  void stringFamily() {
    assertEquals("", Borsh.readString(new byte[Integer.BYTES], 0));
    final byte[] single = dirty(Integer.BYTES + 4);
    assertEquals(Integer.BYTES + 4, Borsh.write("meow", single, 0));
    assertEquals("meow", Borsh.readString(single, 0));
    assertEquals("meow", Borsh.string(single, 0));

    final var strings = new String[]{"", "hello", "Café ☕"};
    int expectedLen = Integer.BYTES;
    for (final var s : strings) {
      expectedLen += Integer.BYTES + s.getBytes(UTF_8).length;
    }
    assertEquals(expectedLen, Borsh.lenVector(strings));

    final byte[] data = dirty(expectedLen);
    assertEquals(expectedLen, Borsh.writeVector(strings, data, 0));
    assertEquals(strings.length, ByteUtil.getInt32LE(data, 0));
    assertArrayEquals(strings, Borsh.readStringVector(data, 0));

    final var result = new String[strings.length];
    assertEquals(expectedLen - Integer.BYTES, Borsh.readArray(result, data, Integer.BYTES));
    assertArrayEquals(strings, result);

    final byte[] arrayData = dirty(expectedLen - Integer.BYTES);
    assertEquals(arrayData.length, Borsh.writeArray(strings, arrayData, 0));
    assertEquals(arrayData.length, Borsh.writeArrayChecked(strings, strings.length, arrayData, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(strings, 2, arrayData, 0));
    assertArrayEquals(Arrays.copyOfRange(data, Integer.BYTES, data.length), arrayData);

    assertEquals(1, Borsh.lenOptional((String) null));
    assertEquals(Integer.BYTES + 5 + 1, Borsh.lenOptional("hello"));

    assertNull(Borsh.getBytes((String) null));
    assertNull(Borsh.getBytes("  "));
    assertArrayEquals("hello".getBytes(UTF_8), Borsh.getBytes("hello"));
    final byte[][] all = Borsh.getBytes(new String[]{"a", null, "bc"});
    assertEquals(3, all.length);
    assertArrayEquals("a".getBytes(UTF_8), all[0]);
    assertNull(all[1]);
    assertArrayEquals("bc".getBytes(UTF_8), all[2]);
  }

  @Test
  void stringMatrixFamily() {
    final var matrix = new String[][]{{"a", "bb"}, {"ccc", ""}};
    int rows = 0;
    for (final var row : matrix) {
      rows += Borsh.lenVector(row);
    }
    assertEquals(Integer.BYTES + rows, Borsh.lenVector(matrix));

    // vector of vectors: outer count, then each row is its own vector
    final byte[] vector = dirty(Borsh.lenVector(matrix));
    assertEquals(vector.length, Borsh.writeVector(matrix, vector, 0));
    assertEquals(matrix.length, ByteUtil.getInt32LE(vector, 0));
    final var reread = Borsh.readMultiDimensionStringVector(vector, 0);
    assertEquals(matrix.length, reread.length);
    for (int i = 0; i < matrix.length; ++i) {
      assertArrayEquals(matrix[i], reread[i]);
    }

    // vector of fixed arrays: outer count, then rows without their own prefixes
    int arrayLen = 0;
    for (final var row : matrix) {
      for (final var s : row) {
        arrayLen += Integer.BYTES + s.getBytes(UTF_8).length;
      }
    }
    final byte[] vectorArray = dirty(Integer.BYTES + arrayLen);
    assertEquals(vectorArray.length, Borsh.writeVectorArray(matrix, vectorArray, 0));
    final var rereadArray = Borsh.readMultiDimensionStringVectorArray(2, vectorArray, 0);
    assertEquals(matrix.length, rereadArray.length);
    for (int i = 0; i < matrix.length; ++i) {
      assertArrayEquals(matrix[i], rereadArray[i]);
    }
    final byte[] checked = dirty(vectorArray.length);
    assertEquals(checked.length, Borsh.writeVectorArrayChecked(matrix, 2, checked, 0));
    assertArrayEquals(vectorArray, checked);
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeVectorArrayChecked(matrix, 3, checked, 0));

    final byte[] array = dirty(2 + arrayLen);
    assertEquals(arrayLen, Borsh.writeArray(matrix, array, 2));
    final var arrayResult = new String[2][2];
    assertEquals(arrayLen, Borsh.readArray(arrayResult, array, 2));
    for (int i = 0; i < matrix.length; ++i) {
      assertArrayEquals(matrix[i], arrayResult[i]);
    }
    assertEquals(arrayLen, Borsh.writeArrayChecked(matrix, 2, array, 2));
    assertEquals(arrayLen, Borsh.writeArrayChecked(matrix, 2, 2, array, 2));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(matrix, 1, array, 2));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(matrix, 3, 2, array, 2));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(matrix, 2, 1, array, 2));
  }

  @Test
  void publicKeyFamily() {
    final var vec = new PublicKey[]{key(1), key(2), key(3)};
    final int payload = vec.length * PublicKey.PUBLIC_KEY_LENGTH;
    assertEquals(Integer.BYTES + payload, Borsh.lenVector(vec));
    assertEquals(payload, Borsh.lenArray(vec));

    final byte[] data = dirty(Integer.BYTES + payload);
    assertEquals(Integer.BYTES + payload, Borsh.writeVector(vec, data, 0));
    assertEquals(vec.length, ByteUtil.getInt32LE(data, 0));
    assertArrayEquals(vec, Borsh.readPublicKeyVector(data, 0));
    final var result = new PublicKey[vec.length];
    assertEquals(payload, Borsh.readArray(result, data, Integer.BYTES));
    assertArrayEquals(vec, result);

    final byte[] arrayData = dirty(payload);
    assertEquals(payload, Borsh.writeArray(vec, arrayData, 0));
    assertEquals(payload, Borsh.writeArrayChecked(vec, vec.length, arrayData, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(vec, 2, arrayData, 0));

    final byte[] optional = dirty(1 + PublicKey.PUBLIC_KEY_LENGTH);
    assertEquals(1, Borsh.writeOptional((PublicKey) null, optional, 0));
    assertEquals(0, optional[0]);
    assertEquals(33, Borsh.writeOptional(key(4), optional, 0));
    assertEquals(1, optional[0]);
    assertEquals(key(4), PublicKey.readPubKey(optional, 1));
    assertEquals(1, Borsh.lenOptional((PublicKey) null));
    assertEquals(33, Borsh.lenOptional(key(4)));
  }

  @Test
  void bigInteger128Family() {
    final var vec = new BigInteger[]{
        BigInteger.valueOf(-1),
        BigInteger.ZERO,
        BigInteger.ONE.shiftLeft(100),
        BigInteger.valueOf(Long.MAX_VALUE)
    };
    final int payload = vec.length * 16;
    assertEquals(Integer.BYTES + payload, Borsh.len128Vector(vec));
    assertEquals(payload, Borsh.len128Array(vec));

    final byte[] data = dirty(Integer.BYTES + payload);
    assertEquals(Integer.BYTES + payload, Borsh.write128Vector(vec, data, 0));
    assertEquals(vec.length, ByteUtil.getInt32LE(data, 0));
    assertArrayEquals(vec, Borsh.read128Vector(data, 0));
    final var result = new BigInteger[vec.length];
    assertEquals(payload, Borsh.read128Array(result, data, Integer.BYTES));
    assertArrayEquals(vec, result);

    final byte[] arrayData = dirty(payload);
    assertEquals(payload, Borsh.write128Array(vec, arrayData, 0));
    assertEquals(payload, Borsh.write128ArrayChecked(vec, vec.length, arrayData, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.write128ArrayChecked(vec, 3, arrayData, 0));

    final byte[] optional = dirty(17);
    assertEquals(1, Borsh.write128Optional(null, optional, 0));
    assertEquals(0, optional[0]);
    assertEquals(17, Borsh.write128Optional(BigInteger.TEN, optional, 0));
    assertEquals(BigInteger.TEN, ByteUtil.getInt128LE(optional, 1));
    assertEquals(1, Borsh.len128Optional(null));
    assertEquals(17, Borsh.len128Optional(BigInteger.TEN));
    assertEquals(16, Borsh.write128(BigInteger.TWO, optional, 1));
  }

  @Test
  void bigInteger256Family() {
    final var vec = new BigInteger[]{
        BigInteger.valueOf(-2),
        BigInteger.ONE.shiftLeft(200),
        BigInteger.valueOf(7)
    };
    final int payload = vec.length * 32;
    assertEquals(Integer.BYTES + payload, Borsh.len256Vector(vec));
    assertEquals(payload, Borsh.len256Array(vec));

    final byte[] data = dirty(Integer.BYTES + payload);
    assertEquals(Integer.BYTES + payload, Borsh.write256Vector(vec, data, 0));
    assertEquals(vec.length, ByteUtil.getInt32LE(data, 0));
    assertArrayEquals(vec, Borsh.read256Vector(data, 0));
    final var result = new BigInteger[vec.length];
    assertEquals(payload, Borsh.read256Array(result, data, Integer.BYTES));
    assertArrayEquals(vec, result);

    final byte[] arrayData = dirty(payload);
    assertEquals(payload, Borsh.write256Array(vec, arrayData, 0));
    assertEquals(payload, Borsh.write256ArrayChecked(vec, vec.length, arrayData, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.write256ArrayChecked(vec, 1, arrayData, 0));

    final byte[] optional = dirty(33);
    assertEquals(1, Borsh.write256Optional(null, optional, 0));
    assertEquals(0, optional[0]);
    assertEquals(33, Borsh.write256Optional(BigInteger.TEN, optional, 0));
    assertEquals(BigInteger.TEN, ByteUtil.getInt256LE(optional, 1));
    assertEquals(1, Borsh.len256Optional(null));
    assertEquals(33, Borsh.len256Optional(BigInteger.TEN));
    assertEquals(32, Borsh.write256(BigInteger.TWO, optional, 1));
  }

  @Test
  void borshTypedFamily() {
    final var vec = new TestStruct[]{
        new TestStruct(Long.MIN_VALUE, -1),
        new TestStruct(0L, 0),
        new TestStruct(Long.MAX_VALUE, Integer.MAX_VALUE)
    };
    final int payload = vec.length * TestStruct.BYTES;
    assertEquals(TestStruct.BYTES, Borsh.len(vec[0]));
    assertEquals(Integer.BYTES + payload, Borsh.lenVector(vec));
    assertEquals(payload, Borsh.lenArray(vec));

    final byte[] data = dirty(Integer.BYTES + payload);
    assertEquals(Integer.BYTES + payload, Borsh.writeVector(vec, data, 0));
    assertEquals(vec.length, ByteUtil.getInt32LE(data, 0));
    assertArrayEquals(vec, Borsh.readVector(TestStruct.class, TestStruct::read, data, 0));
    final var result = new TestStruct[vec.length];
    assertEquals(payload, Borsh.readArray(result, TestStruct::read, data, Integer.BYTES));
    assertArrayEquals(vec, result);

    final byte[] arrayData = dirty(payload);
    assertEquals(payload, Borsh.writeArray(vec, arrayData, 0));
    assertEquals(payload, Borsh.writeArrayChecked(vec, vec.length, arrayData, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(vec, 2, arrayData, 0));
    assertEquals(TestStruct.BYTES, Borsh.write(vec[0], arrayData, 0));

    final byte[] optional = dirty(1 + TestStruct.BYTES);
    assertEquals(1, Borsh.writeOptional((Borsh) null, optional, 0));
    assertEquals(0, optional[0]);
    assertEquals(1 + TestStruct.BYTES, Borsh.writeOptional(vec[2], optional, 0));
    assertEquals(vec[2], TestStruct.read(optional, 1));
    assertEquals(1, Borsh.lenOptional((Borsh) null));
    assertEquals(1 + TestStruct.BYTES, Borsh.lenOptional(vec[2]));

    // matrix shapes
    final var matrix = new TestStruct[][]{{vec[0], vec[1]}, {vec[2], vec[0]}};
    final int matrixPayload = 4 * TestStruct.BYTES;
    assertEquals(matrixPayload, Borsh.lenArray(matrix));
    assertEquals(Integer.BYTES + matrixPayload + (2 * Integer.BYTES), Borsh.lenVector(matrix));
    assertEquals(Integer.BYTES + matrixPayload, Borsh.lenVectorArray(matrix));

    final byte[] vector = dirty(Borsh.lenVector(matrix));
    assertEquals(vector.length, Borsh.writeVector(matrix, vector, 0));
    final var rereadVector = Borsh.readMultiDimensionVector(TestStruct.class, TestStruct::read, vector, 0);
    assertEquals(matrix.length, rereadVector.length);
    for (int i = 0; i < matrix.length; ++i) {
      assertArrayEquals(matrix[i], rereadVector[i]);
    }

    final byte[] vectorArray = dirty(Borsh.lenVectorArray(matrix));
    assertEquals(vectorArray.length, Borsh.writeVectorArray(matrix, vectorArray, 0));
    final var rereadVectorArray = Borsh.readMultiDimensionVectorArray(TestStruct.class, TestStruct::read, 2, vectorArray, 0);
    assertEquals(matrix.length, rereadVectorArray.length);
    for (int i = 0; i < matrix.length; ++i) {
      assertArrayEquals(matrix[i], rereadVectorArray[i]);
    }
    final byte[] vectorArrayChecked = dirty(vectorArray.length);
    assertEquals(vectorArray.length, Borsh.writeVectorArrayChecked(matrix, 2, vectorArrayChecked, 0));
    assertArrayEquals(vectorArray, vectorArrayChecked);
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeVectorArrayChecked(matrix, 1, vectorArrayChecked, 0));

    final byte[] matrixArray = dirty(2 + matrixPayload);
    assertEquals(matrixPayload, Borsh.writeArray(matrix, matrixArray, 2));
    final var matrixResult = new TestStruct[2][2];
    assertEquals(matrixPayload, Borsh.readArray(matrixResult, TestStruct::read, matrixArray, 2));
    for (int i = 0; i < matrix.length; ++i) {
      assertArrayEquals(matrix[i], matrixResult[i]);
    }
    assertEquals(matrixPayload, Borsh.writeArrayChecked(matrix, 2, matrixArray, 2));
    assertEquals(matrixPayload, Borsh.writeArrayChecked(matrix, 2, 2, matrixArray, 2));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(matrix, 3, matrixArray, 2));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(matrix, 1, 2, matrixArray, 2));
  }

  @Test
  void enumFamily() {
    final byte[] data = {(byte) AccountState.Frozen.ordinal()};
    assertEquals(AccountState.Frozen, Borsh.read(AccountState.values(), data, 0));
    data[0] = (byte) 200;
    assertThrows(ArrayIndexOutOfBoundsException.class, () -> Borsh.read(AccountState.values(), data, 0));

    record BorshEnumImpl(int ordinal) implements Borsh.Enum {
    }
    final var impl = new BorshEnumImpl(3);
    assertEquals(1, impl.l());
    final byte[] out = dirty(2);
    assertEquals(1, impl.write(out, 1));
    assertEquals(3, out[1]);
  }

  @Test
  void bigIntegerMatrixChecked() {
    final var matrix = new BigInteger[][]{
        {BigInteger.ONE, BigInteger.TWO, BigInteger.TEN},
        {BigInteger.valueOf(-1), BigInteger.ZERO, BigInteger.valueOf(7)}
    };

    final int payload128 = 6 * 16;
    final byte[] array128 = dirty(2 + payload128);
    assertEquals(payload128, Borsh.write128Array(matrix, array128, 2));
    final byte[] checked128 = dirty(array128.length);
    assertEquals(payload128, Borsh.write128ArrayChecked(matrix, 3, checked128, 2));
    assertArrayEquals(array128, checked128);
    assertEquals(payload128, Borsh.write128ArrayChecked(matrix, 2, 3, checked128, 2));
    assertArrayEquals(array128, checked128);
    assertThrows(IllegalArgumentException.class, () -> Borsh.write128ArrayChecked(matrix, 2, checked128, 2));
    assertThrows(IllegalArgumentException.class, () -> Borsh.write128ArrayChecked(matrix, 1, 3, checked128, 2));
    assertThrows(IllegalArgumentException.class, () -> Borsh.write128ArrayChecked(matrix, 2, 2, checked128, 2));
    final var read128 = new BigInteger[2][3];
    assertEquals(payload128, Borsh.read128Array(read128, array128, 2));
    assertTrue(Arrays.deepEquals(matrix, read128));

    final byte[] vectorArray128 = dirty(3 + Integer.BYTES + payload128);
    assertEquals(Integer.BYTES + payload128, Borsh.write128VectorArray(matrix, vectorArray128, 3));
    final byte[] checkedVectorArray128 = dirty(vectorArray128.length);
    assertEquals(Integer.BYTES + payload128, Borsh.write128VectorArrayChecked(matrix, 3, checkedVectorArray128, 3));
    assertArrayEquals(vectorArray128, checkedVectorArray128);
    assertThrows(IllegalArgumentException.class, () -> Borsh.write128VectorArrayChecked(matrix, 2, checkedVectorArray128, 3));
    final byte[] vector128 = dirty(3 + Borsh.len128Vector(matrix));
    assertEquals(Borsh.len128Vector(matrix), Borsh.write128Vector(matrix, vector128, 3));

    final int payload256 = 6 * 32;
    final byte[] array256 = dirty(2 + payload256);
    assertEquals(payload256, Borsh.write256Array(matrix, array256, 2));
    final byte[] checked256 = dirty(array256.length);
    assertEquals(payload256, Borsh.write256ArrayChecked(matrix, 3, checked256, 2));
    assertArrayEquals(array256, checked256);
    assertEquals(payload256, Borsh.write256ArrayChecked(matrix, 2, 3, checked256, 2));
    assertThrows(IllegalArgumentException.class, () -> Borsh.write256ArrayChecked(matrix, 2, checked256, 2));
    assertThrows(IllegalArgumentException.class, () -> Borsh.write256ArrayChecked(matrix, 1, 3, checked256, 2));
    assertThrows(IllegalArgumentException.class, () -> Borsh.write256ArrayChecked(matrix, 2, 2, checked256, 2));
    final var read256 = new BigInteger[2][3];
    assertEquals(payload256, Borsh.read256Array(read256, array256, 2));
    assertTrue(Arrays.deepEquals(matrix, read256));

    final byte[] vectorArray256 = dirty(3 + Integer.BYTES + payload256);
    assertEquals(Integer.BYTES + payload256, Borsh.write256VectorArray(matrix, vectorArray256, 3));
    final byte[] checkedVectorArray256 = dirty(vectorArray256.length);
    assertEquals(Integer.BYTES + payload256, Borsh.write256VectorArrayChecked(matrix, 3, checkedVectorArray256, 3));
    assertArrayEquals(vectorArray256, checkedVectorArray256);
    assertThrows(IllegalArgumentException.class, () -> Borsh.write256VectorArrayChecked(matrix, 2, checkedVectorArray256, 3));
    final byte[] vector256 = dirty(3 + Borsh.len256Vector(matrix));
    assertEquals(Borsh.len256Vector(matrix), Borsh.write256Vector(matrix, vector256, 3));
  }

  @Test
  void publicKeyMatrixChecked() {
    final var matrix = new PublicKey[][]{{key(1), key(2), key(3)}, {key(4), key(5), key(6)}};
    final int payload = 6 * PublicKey.PUBLIC_KEY_LENGTH;

    final byte[] array = dirty(2 + payload);
    assertEquals(payload, Borsh.writeArray(matrix, array, 2));
    final byte[] checked = dirty(array.length);
    assertEquals(payload, Borsh.writeArrayChecked(matrix, 3, checked, 2));
    assertArrayEquals(array, checked);
    assertEquals(payload, Borsh.writeArrayChecked(matrix, 2, 3, checked, 2));
    assertArrayEquals(array, checked);
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(matrix, 2, checked, 2));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(matrix, 1, 3, checked, 2));
    assertThrows(IllegalArgumentException.class, () -> Borsh.writeArrayChecked(matrix, 2, 2, checked, 2));

    final byte[] vectorArray = dirty(3 + Integer.BYTES + payload);
    assertEquals(Integer.BYTES + payload, Borsh.writeVectorArray(matrix, vectorArray, 3));
    assertEquals(2, ByteUtil.getInt32LE(vectorArray, 3));
    final byte[] vector = dirty(3 + Borsh.lenVector(matrix));
    assertEquals(Borsh.lenVector(matrix), Borsh.writeVector(matrix, vector, 3));
  }

  @Test
  void matrixWritersReturnConsumedLengthAtNonZeroOffsets() {
    final var strings = new String[][]{{"a", "bb"}, {"ccc", ""}};
    final byte[] stringVector = dirty(3 + Borsh.lenVector(strings));
    assertEquals(Borsh.lenVector(strings), Borsh.writeVector(strings, stringVector, 3));
    final byte[] stringArray = dirty(2 + Borsh.lenVector(strings) - Integer.BYTES * 3);
    assertEquals(stringArray.length - 2, Borsh.writeArray(strings, stringArray, 2));
    final byte[] stringVectorArray = dirty(3 + Integer.BYTES + stringArray.length - 2);
    assertEquals(stringVectorArray.length - 3, Borsh.writeVectorArray(strings, stringVectorArray, 3));

    final var structs = new TestStruct[][]{
        {new TestStruct(1L, 2), new TestStruct(3L, 4)},
        {new TestStruct(5L, 6), new TestStruct(7L, 8)}
    };
    final byte[] structVector = dirty(3 + Borsh.lenVector(structs));
    assertEquals(Borsh.lenVector(structs), Borsh.writeVector(structs, structVector, 3));
    final byte[] structArray = dirty(2 + Borsh.lenArray(structs));
    assertEquals(Borsh.lenArray(structs), Borsh.writeArray(structs, structArray, 2));
    final byte[] structVectorArray = dirty(3 + Borsh.lenVectorArray(structs));
    assertEquals(Borsh.lenVectorArray(structs), Borsh.writeVectorArray(structs, structVectorArray, 3));
  }

  @Test
  void fixedRowVectorBoundsAreExact() {
    // room for exactly two rows of three elements; a claim of three rows must be rejected
    // by the length validation itself
    final byte[] keyData = new byte[Integer.BYTES + 6 * PublicKey.PUBLIC_KEY_LENGTH];
    ByteUtil.putInt32LE(keyData, 0, 3);
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionPublicKeyVectorArray(3, keyData, 0));
    ByteUtil.putInt32LE(keyData, 0, 2);
    assertEquals(2, Borsh.readMultiDimensionPublicKeyVectorArray(3, keyData, 0).length);

    final byte[] data128 = new byte[Integer.BYTES + 6 * 16];
    ByteUtil.putInt32LE(data128, 0, 3);
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimension128VectorArray(3, data128, 0));

    final byte[] data256 = new byte[Integer.BYTES + 6 * 32];
    ByteUtil.putInt32LE(data256, 0, 3);
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimension256VectorArray(3, data256, 0));

    // string rows need at least a u32 prefix per element
    final byte[] stringData = new byte[Integer.BYTES + 6 * Integer.BYTES];
    ByteUtil.putInt32LE(stringData, 0, 3);
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionStringVectorArray(3, stringData, 0));

    // Borsh-typed rows are bounded by at least one byte per element
    final byte[] structData = new byte[Integer.BYTES + 4];
    ByteUtil.putInt32LE(structData, 0, 3);
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionVectorArray(TestStruct.class, TestStruct::read, 3, structData, 0));
  }

  @Test
  void corruptReferenceVectorLengthsAreRejectedBeforeAllocation() {
    final byte[] data = new byte[Integer.BYTES + 8];
    ByteUtil.putInt32LE(data, 0, Integer.MAX_VALUE);
    assertThrows(IllegalArgumentException.class, () -> Borsh.readStringVector(data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionStringVector(data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionStringVectorArray(2, data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readPublicKeyVector(data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionPublicKeyVector(data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionPublicKeyVectorArray(2, data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.read128Vector(data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.read256Vector(data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimension128Vector(data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimension256VectorArray(2, data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readVector(TestStruct.class, TestStruct::read, data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionVector(TestStruct.class, TestStruct::read, data, 0));
    assertThrows(IllegalArgumentException.class, () -> Borsh.readMultiDimensionVectorArray(TestStruct.class, TestStruct::read, 2, data, 0));
  }
}
