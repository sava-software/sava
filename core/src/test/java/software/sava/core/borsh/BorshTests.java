package software.sava.core.borsh;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.sysvar.Clock;
import software.sava.core.encoding.ByteUtil;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static software.sava.core.borsh.Borsh.*;

final class BorshTests {

  private static final int VECTOR_SIZE = 8;
  private static final int ELEMENTS_PER_ARRAY = 4;

  @Test
  void initMultiDimensionalVector() {
    final BigInteger[][] result = (BigInteger[][]) Array.newInstance(BigInteger.class, 8, 0);
    assertEquals(8, result.length);
    for (int i = 0; i < result.length; ++i) {
      final var a = result[i];
      assertEquals(0, a.length);
      result[i] = new BigInteger[8];
      assertEquals(8, result[i].length);
    }
  }

  @SuppressWarnings("unchecked")
  <T> T[][] multiDimensional(final Class<T> clas,
                             final int dataTypeByteLength,
                             final byte[] vectorArray,
                             final Function<byte[], T> factory,
                             final Serializer<T> serializer) {
    final var random = new Random();
    final var expectedMatrix = (T[][]) Array.newInstance(clas, VECTOR_SIZE, ELEMENTS_PER_ARRAY);
    for (int i = 0, from = Integer.BYTES, to = from + dataTypeByteLength; i < VECTOR_SIZE; ++i) {
      final var expectedArray = expectedMatrix[i];
      for (int j = 0; j < ELEMENTS_PER_ARRAY; ) {
        final byte[] randomBytes = new byte[dataTypeByteLength];
        random.nextBytes(randomBytes);
        final var val = factory.apply(randomBytes);
        serializer.serialize(val, vectorArray, from);
        expectedArray[j++] = val;
        from = to;
        to += dataTypeByteLength;
      }
      expectedMatrix[i] = expectedArray;
    }
    return expectedMatrix;
  }

  @Test
  void multiDimensionalFloats() {
    final int dataTypeByteLength = Float.BYTES;
    final int arrayLength = ELEMENTS_PER_ARRAY * dataTypeByteLength;
    final int arrayByteLength = VECTOR_SIZE * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, VECTOR_SIZE);

    final var random = new Random();
    final var expectedMatrix = new float[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    for (int i = 0, from = Integer.BYTES, to = from + dataTypeByteLength; i < VECTOR_SIZE; ++i) {
      final var expectedArray = expectedMatrix[i];
      for (int j = 0; j < ELEMENTS_PER_ARRAY; ) {
        final var val = random.nextFloat();
        ByteUtil.putFloat32LE(vectorArray, from, val);
        expectedArray[j++] = val;
        from = to;
        to += dataTypeByteLength;
      }
      expectedMatrix[i] = expectedArray;
    }

    // verify read/write arrays
    final var arrayResult = new float[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    int len = readArray(arrayResult, vectorArray, Integer.BYTES);
    assertEquals(arrayByteLength, len);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], arrayResult[i]);
    }

    byte[] data = new byte[Borsh.lenArray(arrayResult)];
    assertEquals(arrayByteLength, data.length);
    len = writeArray(arrayResult, data, 0);
    assertEquals(arrayByteLength, len);
    assertArrayEquals(Arrays.copyOfRange(vectorArray, 4, vectorArray.length), data);

    // verify vector array write/read
    var result = readMultiDimensionfloatVectorArray(ELEMENTS_PER_ARRAY, vectorArray, 0);
    assertEquals(expectedMatrix.length, result.length);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }

    data = new byte[Borsh.lenVectorArray(result)];
    assertEquals(vectorArray.length, data.length);
    len = Borsh.writeVectorArray(expectedMatrix, data, 0);
    assertEquals(vectorArray.length, len);
    assertArrayEquals(vectorArray, data);

    // verify vector write/read
    final int vectorLength = Integer.BYTES + (VECTOR_SIZE * Integer.BYTES) + (VECTOR_SIZE * arrayLength);
    data = new byte[Borsh.lenVector(result)];
    assertEquals(vectorLength, data.length);
    len = Borsh.writeVector(expectedMatrix, data, 0);
    assertEquals(vectorLength, len);

    result = Borsh.readMultiDimensionfloatVector(data, 0);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }
  }

  @Test
  void multiDimensionalDoubles() {
    final int dataTypeByteLength = Double.BYTES;
    final int arrayLength = ELEMENTS_PER_ARRAY * dataTypeByteLength;
    final int arrayByteLength = VECTOR_SIZE * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, VECTOR_SIZE);

    final var random = new Random();
    final var expectedMatrix = new double[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    for (int i = 0, from = Integer.BYTES, to = from + dataTypeByteLength; i < VECTOR_SIZE; ++i) {
      final var expectedArray = expectedMatrix[i];
      for (int j = 0; j < ELEMENTS_PER_ARRAY; ) {
        final var val = random.nextDouble();
        ByteUtil.putFloat64LE(vectorArray, from, val);
        expectedArray[j++] = val;
        from = to;
        to += dataTypeByteLength;
      }
      expectedMatrix[i] = expectedArray;
    }

    // verify read/write arrays
    final var arrayResult = new double[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    int len = readArray(arrayResult, vectorArray, Integer.BYTES);
    assertEquals(arrayByteLength, len);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], arrayResult[i]);
    }

    byte[] data = new byte[Borsh.lenArray(arrayResult)];
    assertEquals(arrayByteLength, data.length);
    len = writeArray(arrayResult, data, 0);
    assertEquals(arrayByteLength, len);
    assertArrayEquals(Arrays.copyOfRange(vectorArray, 4, vectorArray.length), data);

    // verify vector array write/read
    var result = readMultiDimensiondoubleVectorArray(ELEMENTS_PER_ARRAY, vectorArray, 0);
    assertEquals(expectedMatrix.length, result.length);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }

    data = new byte[Borsh.lenVectorArray(result)];
    assertEquals(vectorArray.length, data.length);
    len = Borsh.writeVectorArray(expectedMatrix, data, 0);
    assertEquals(vectorArray.length, len);
    assertArrayEquals(vectorArray, data);

    // verify vector write/read
    final int vectorLength = Integer.BYTES + (VECTOR_SIZE * Integer.BYTES) + (VECTOR_SIZE * arrayLength);
    data = new byte[Borsh.lenVector(result)];
    assertEquals(vectorLength, data.length);
    len = Borsh.writeVector(expectedMatrix, data, 0);
    assertEquals(vectorLength, len);

    result = Borsh.readMultiDimensiondoubleVector(data, 0);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }
  }

  @Test
  void multiDimensionalBooleans() {
    final int dataTypeByteLength = Byte.BYTES;
    final int arrayLength = ELEMENTS_PER_ARRAY * dataTypeByteLength;
    final int arrayByteLength = VECTOR_SIZE * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, VECTOR_SIZE);

    final var random = new Random();
    final var expectedMatrix = new byte[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    for (int i = 0, from = Integer.BYTES, to = from + dataTypeByteLength; i < VECTOR_SIZE; ++i) {
      final var expectedArray = expectedMatrix[i];
      for (int j = 0; j < ELEMENTS_PER_ARRAY; ) {
        final var val = (byte) (random.nextBoolean() ? 1 : 0);
        vectorArray[from] = val;
        expectedArray[j++] = val;
        from = to;
        to += dataTypeByteLength;
      }
      expectedMatrix[i] = expectedArray;
    }

    // verify read/write arrays
    final var arrayResult = new byte[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    int len = readArray(arrayResult, vectorArray, Integer.BYTES);
    assertEquals(arrayByteLength, len);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], arrayResult[i]);
    }

    byte[] data = new byte[Borsh.lenArray(arrayResult)];
    assertEquals(arrayByteLength, data.length);
    len = writeArray(arrayResult, data, 0);
    assertEquals(arrayByteLength, len);
    assertArrayEquals(Arrays.copyOfRange(vectorArray, 4, vectorArray.length), data);

    // verify vector array write/read
    var result = readMultiDimensionbyteVectorArray(ELEMENTS_PER_ARRAY, vectorArray, 0);
    assertEquals(expectedMatrix.length, result.length);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }

    data = new byte[Borsh.lenVectorArray(result)];
    assertEquals(vectorArray.length, data.length);
    len = Borsh.writeVectorArray(expectedMatrix, data, 0);
    assertEquals(vectorArray.length, len);
    assertArrayEquals(vectorArray, data);

    // verify vector write/read
    final int vectorLength = Integer.BYTES + (VECTOR_SIZE * Integer.BYTES) + (VECTOR_SIZE * arrayLength);
    data = new byte[Borsh.lenVector(result)];
    assertEquals(vectorLength, data.length);
    len = Borsh.writeVector(expectedMatrix, data, 0);
    assertEquals(vectorLength, len);

    result = Borsh.readMultiDimensionbyteVector(data, 0);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }
  }


  @Test
  void multiDimensionalBytes() {
    final int dataTypeByteLength = Byte.BYTES;
    final int arrayLength = ELEMENTS_PER_ARRAY * dataTypeByteLength;
    final int arrayByteLength = VECTOR_SIZE * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, VECTOR_SIZE);

    final var random = new Random();
    final var expectedMatrix = new byte[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    for (int i = 0, from = Integer.BYTES, to = from + dataTypeByteLength; i < VECTOR_SIZE; ++i) {
      final var expectedArray = expectedMatrix[i];
      for (int j = 0; j < ELEMENTS_PER_ARRAY; ) {
        final var val = (byte) random.nextInt();
        vectorArray[from] = val;
        expectedArray[j++] = val;
        from = to;
        to += dataTypeByteLength;
      }
      expectedMatrix[i] = expectedArray;
    }

    // verify read/write arrays
    final var arrayResult = new byte[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    int len = readArray(arrayResult, vectorArray, Integer.BYTES);
    assertEquals(arrayByteLength, len);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], arrayResult[i]);
    }

    byte[] data = new byte[Borsh.lenArray(arrayResult)];
    assertEquals(arrayByteLength, data.length);
    len = writeArray(arrayResult, data, 0);
    assertEquals(arrayByteLength, len);
    assertArrayEquals(Arrays.copyOfRange(vectorArray, 4, vectorArray.length), data);

    // verify vector array write/read
    var result = readMultiDimensionbyteVectorArray(ELEMENTS_PER_ARRAY, vectorArray, 0);
    assertEquals(expectedMatrix.length, result.length);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }

    data = new byte[Borsh.lenVectorArray(result)];
    assertEquals(vectorArray.length, data.length);
    len = Borsh.writeVectorArray(expectedMatrix, data, 0);
    assertEquals(vectorArray.length, len);
    assertArrayEquals(vectorArray, data);

    // verify vector write/read
    final int vectorLength = Integer.BYTES + (VECTOR_SIZE * Integer.BYTES) + (VECTOR_SIZE * arrayLength);
    data = new byte[Borsh.lenVector(result)];
    assertEquals(vectorLength, data.length);
    len = Borsh.writeVector(expectedMatrix, data, 0);
    assertEquals(vectorLength, len);

    result = Borsh.readMultiDimensionbyteVector(data, 0);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }
  }

  @Test
  void multiDimensionalShorts() {
    final int dataTypeByteLength = Short.BYTES;
    final int arrayLength = ELEMENTS_PER_ARRAY * dataTypeByteLength;
    final int arrayByteLength = VECTOR_SIZE * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, VECTOR_SIZE);

    final var random = new Random();
    final var expectedMatrix = new short[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    for (int i = 0, from = Integer.BYTES, to = from + dataTypeByteLength; i < VECTOR_SIZE; ++i) {
      final var expectedArray = expectedMatrix[i];
      for (int j = 0; j < ELEMENTS_PER_ARRAY; ) {
        final var val = (short) random.nextInt();
        ByteUtil.putInt16LE(vectorArray, from, val);
        expectedArray[j++] = val;
        from = to;
        to += dataTypeByteLength;
      }
      expectedMatrix[i] = expectedArray;
    }

    // verify read/write arrays
    final var arrayResult = new short[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    int len = readArray(arrayResult, vectorArray, Integer.BYTES);
    assertEquals(arrayByteLength, len);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], arrayResult[i]);
    }

    byte[] data = new byte[Borsh.lenArray(arrayResult)];
    assertEquals(arrayByteLength, data.length);
    len = writeArray(arrayResult, data, 0);
    assertEquals(arrayByteLength, len);
    assertArrayEquals(Arrays.copyOfRange(vectorArray, 4, vectorArray.length), data);

    // verify vector array write/read
    var result = readMultiDimensionshortVectorArray(ELEMENTS_PER_ARRAY, vectorArray, 0);
    assertEquals(expectedMatrix.length, result.length);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }

    data = new byte[Borsh.lenVectorArray(result)];
    assertEquals(vectorArray.length, data.length);
    len = Borsh.writeVectorArray(expectedMatrix, data, 0);
    assertEquals(vectorArray.length, len);
    assertArrayEquals(vectorArray, data);

    // verify vector write/read
    final int vectorLength = Integer.BYTES + (VECTOR_SIZE * Integer.BYTES) + (VECTOR_SIZE * arrayLength);
    data = new byte[Borsh.lenVector(result)];
    assertEquals(vectorLength, data.length);
    len = Borsh.writeVector(expectedMatrix, data, 0);
    assertEquals(vectorLength, len);

    result = Borsh.readMultiDimensionshortVector(data, 0);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }
  }

  @Test
  void multiDimensionalIntegers() {
    final int dataTypeByteLength = Integer.BYTES;
    final int arrayLength = ELEMENTS_PER_ARRAY * dataTypeByteLength;
    final int arrayByteLength = VECTOR_SIZE * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, VECTOR_SIZE);

    final var random = new Random();
    final var expectedMatrix = new int[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    for (int i = 0, from = Integer.BYTES, to = from + dataTypeByteLength; i < VECTOR_SIZE; ++i) {
      final var expectedArray = expectedMatrix[i];
      for (int j = 0; j < ELEMENTS_PER_ARRAY; ) {
        final var val = random.nextInt();
        ByteUtil.putInt32LE(vectorArray, from, val);
        expectedArray[j++] = val;
        from = to;
        to += dataTypeByteLength;
      }
      expectedMatrix[i] = expectedArray;
    }

    // verify read/write arrays
    final var arrayResult = new int[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    int len = readArray(arrayResult, vectorArray, Integer.BYTES);
    assertEquals(arrayByteLength, len);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], arrayResult[i]);
    }

    byte[] data = new byte[Borsh.lenArray(arrayResult)];
    assertEquals(arrayByteLength, data.length);
    len = writeArray(arrayResult, data, 0);
    assertEquals(arrayByteLength, len);
    assertArrayEquals(Arrays.copyOfRange(vectorArray, 4, vectorArray.length), data);

    // verify vector array write/read
    var result = readMultiDimensionintVectorArray(ELEMENTS_PER_ARRAY, vectorArray, 0);
    assertEquals(expectedMatrix.length, result.length);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }

    data = new byte[Borsh.lenVectorArray(result)];
    assertEquals(vectorArray.length, data.length);
    len = Borsh.writeVectorArray(expectedMatrix, data, 0);
    assertEquals(vectorArray.length, len);
    assertArrayEquals(vectorArray, data);

    // verify vector write/read
    final int vectorLength = Integer.BYTES + (VECTOR_SIZE * Integer.BYTES) + (VECTOR_SIZE * arrayLength);
    data = new byte[Borsh.lenVector(result)];
    assertEquals(vectorLength, data.length);
    len = Borsh.writeVector(expectedMatrix, data, 0);
    assertEquals(vectorLength, len);

    result = Borsh.readMultiDimensionintVector(data, 0);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }
  }

  @Test
  void multiDimensionalLongs() {
    final int dataTypeByteLength = Long.BYTES;
    final int arrayLength = ELEMENTS_PER_ARRAY * dataTypeByteLength;
    final int arrayByteLength = VECTOR_SIZE * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, VECTOR_SIZE);

    final var random = new Random();
    final var expectedMatrix = new long[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    for (int i = 0, from = Integer.BYTES, to = from + dataTypeByteLength; i < VECTOR_SIZE; ++i) {
      final var expectedArray = expectedMatrix[i];
      for (int j = 0; j < ELEMENTS_PER_ARRAY; ) {
        final var val = random.nextLong();
        ByteUtil.putInt64LE(vectorArray, from, val);
        expectedArray[j++] = val;
        from = to;
        to += dataTypeByteLength;
      }
      expectedMatrix[i] = expectedArray;
    }

    // verify read/write arrays
    final var arrayResult = new long[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    int len = readArray(arrayResult, vectorArray, Integer.BYTES);
    assertEquals(arrayByteLength, len);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], arrayResult[i]);
    }

    byte[] data = new byte[Borsh.lenArray(arrayResult)];
    assertEquals(arrayByteLength, data.length);
    len = writeArray(arrayResult, data, 0);
    assertEquals(arrayByteLength, len);
    assertArrayEquals(Arrays.copyOfRange(vectorArray, 4, vectorArray.length), data);

    // verify vector array write/read
    var result = readMultiDimensionlongVectorArray(ELEMENTS_PER_ARRAY, vectorArray, 0);
    assertEquals(expectedMatrix.length, result.length);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }

    data = new byte[Borsh.lenVectorArray(result)];
    assertEquals(vectorArray.length, data.length);
    len = Borsh.writeVectorArray(expectedMatrix, data, 0);
    assertEquals(vectorArray.length, len);
    assertArrayEquals(vectorArray, data);

    // verify vector write/read
    final int vectorLength = Integer.BYTES + (VECTOR_SIZE * Integer.BYTES) + (VECTOR_SIZE * arrayLength);
    data = new byte[Borsh.lenVector(result)];
    assertEquals(vectorLength, data.length);
    len = Borsh.writeVector(expectedMatrix, data, 0);
    assertEquals(vectorLength, len);

    result = Borsh.readMultiDimensionlongVector(data, 0);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }
  }

  @Test
  void multiDimensional128BitIntegers() {
    final int dataTypeByteLength = 128 / Byte.SIZE;
    final int arrayLength = ELEMENTS_PER_ARRAY * dataTypeByteLength;
    final int arrayByteLength = VECTOR_SIZE * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, VECTOR_SIZE);

    final var expectedMatrix = multiDimensional(
        BigInteger.class,
        dataTypeByteLength,
        vectorArray,
        BigInteger::new,
        (val, _vectorArray, from) -> ByteUtil.putInt128LE(_vectorArray, from, val)
    );

    // verify read/write arrays
    final var arrayResult = new BigInteger[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    int len = read128Array(arrayResult, vectorArray, Integer.BYTES);
    assertEquals(arrayByteLength, len);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], arrayResult[i]);
    }

    byte[] data = new byte[Borsh.len128Array(arrayResult)];
    assertEquals(arrayByteLength, data.length);
    len = write128Array(arrayResult, data, 0);
    assertEquals(arrayByteLength, len);
    assertArrayEquals(Arrays.copyOfRange(vectorArray, 4, vectorArray.length), data);

    // verify vector array write/read
    var result = readMultiDimension128VectorArray(ELEMENTS_PER_ARRAY, vectorArray, 0);
    assertEquals(expectedMatrix.length, result.length);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }

    data = new byte[Borsh.len128VectorArray(result)];
    assertEquals(vectorArray.length, data.length);
    len = Borsh.write128VectorArray(expectedMatrix, data, 0);
    assertEquals(vectorArray.length, len);
    assertArrayEquals(vectorArray, data);

    // verify vector write/read
    final int vectorLength = Integer.BYTES + (VECTOR_SIZE * Integer.BYTES) + (VECTOR_SIZE * arrayLength);
    data = new byte[Borsh.len128Vector(result)];
    assertEquals(vectorLength, data.length);
    len = Borsh.write128Vector(expectedMatrix, data, 0);
    assertEquals(vectorLength, len);

    result = Borsh.readMultiDimension128Vector(data, 0);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }
  }

  @Test
  void multiDimensional256BitIntegers() {
    final int dataTypeByteLength = 256 / Byte.SIZE;
    final int arrayLength = ELEMENTS_PER_ARRAY * dataTypeByteLength;
    final int arrayByteLength = VECTOR_SIZE * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, VECTOR_SIZE);

    final var expectedMatrix = multiDimensional(
        BigInteger.class,
        dataTypeByteLength,
        vectorArray,
        BigInteger::new,
        (val, _vectorArray, from) -> ByteUtil.putInt256LE(_vectorArray, from, val)
    );

    // verify read/write arrays
    final var arrayResult = new BigInteger[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    int len = read256Array(arrayResult, vectorArray, Integer.BYTES);
    assertEquals(arrayByteLength, len);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], arrayResult[i]);
    }

    byte[] data = new byte[Borsh.len256Array(arrayResult)];
    assertEquals(arrayByteLength, data.length);
    len = write256Array(arrayResult, data, 0);
    assertEquals(arrayByteLength, len);
    assertArrayEquals(Arrays.copyOfRange(vectorArray, 4, vectorArray.length), data);

    // verify vector array write/read
    var result = readMultiDimension256VectorArray(ELEMENTS_PER_ARRAY, vectorArray, 0);
    assertEquals(expectedMatrix.length, result.length);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }

    data = new byte[Borsh.len256VectorArray(result)];
    assertEquals(vectorArray.length, data.length);
    len = Borsh.write256VectorArray(expectedMatrix, data, 0);
    assertEquals(vectorArray.length, len);
    assertArrayEquals(vectorArray, data);

    // verify vector write/read
    final int vectorLength = Integer.BYTES + (VECTOR_SIZE * Integer.BYTES) + (VECTOR_SIZE * arrayLength);
    data = new byte[Borsh.len256Vector(result)];
    assertEquals(vectorLength, data.length);
    len = Borsh.write256Vector(expectedMatrix, data, 0);
    assertEquals(vectorLength, len);

    result = Borsh.readMultiDimension256Vector(data, 0);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }
  }

  @Test
  void multiDimensionalPublicKeys() {
    final int dataTypeByteLength = PublicKey.PUBLIC_KEY_LENGTH;
    final int arrayLength = ELEMENTS_PER_ARRAY * dataTypeByteLength;
    final int arrayByteLength = VECTOR_SIZE * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, VECTOR_SIZE);

    final var expectedMatrix = multiDimensional(
        PublicKey.class,
        dataTypeByteLength,
        vectorArray,
        PublicKey::createPubKey,
        PublicKey::write
    );

    // verify read/write arrays
    final var arrayResult = new PublicKey[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    int len = readArray(arrayResult, vectorArray, Integer.BYTES);
    assertEquals(arrayByteLength, len);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], arrayResult[i]);
    }

    byte[] data = new byte[Borsh.lenArray(arrayResult)];
    assertEquals(arrayByteLength, data.length);
    len = writeArray(arrayResult, data, 0);
    assertEquals(arrayByteLength, len);
    assertArrayEquals(Arrays.copyOfRange(vectorArray, 4, vectorArray.length), data);

    // verify vector array write/read
    var result = readMultiDimensionPublicKeyVectorArray(ELEMENTS_PER_ARRAY, vectorArray, 0);
    assertEquals(expectedMatrix.length, result.length);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }

    data = new byte[Borsh.lenVectorArray(result)];
    assertEquals(vectorArray.length, data.length);
    len = Borsh.writeVectorArray(expectedMatrix, data, 0);
    assertEquals(vectorArray.length, len);
    assertArrayEquals(vectorArray, data);

    // verify vector write/read
    final int vectorLength = Integer.BYTES + (VECTOR_SIZE * Integer.BYTES) + (VECTOR_SIZE * arrayLength);
    data = new byte[Borsh.lenVector(result)];
    assertEquals(vectorLength, data.length);
    len = Borsh.writeVector(expectedMatrix, data, 0);
    assertEquals(vectorLength, len);

    result = Borsh.readMultiDimensionPublicKeyVector(data, 0);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }
  }

  @FunctionalInterface
  private interface Serializer<T> {

    void serialize(final T val, final byte[] vectorArray, final int from);
  }

  @Test
  void multiDimensionalBorshType() {
    final int dataTypeByteLength = Clock.BYTES;
    final int arrayLength = ELEMENTS_PER_ARRAY * dataTypeByteLength;
    final int arrayByteLength = VECTOR_SIZE * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, VECTOR_SIZE);

    final var expectedMatrix = multiDimensional(
        Clock.class,
        dataTypeByteLength,
        vectorArray,
        Clock::read,
        Clock::write
    );

    // verify read/write arrays
    final var arrayResult = new Clock[VECTOR_SIZE][ELEMENTS_PER_ARRAY];
    final Factory<Clock> borshFactory = (bytes, offset) -> Clock.read(null, bytes, offset);
    int len = readArray(arrayResult, borshFactory, vectorArray, Integer.BYTES);
    assertEquals(arrayByteLength, len);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], arrayResult[i]);
    }

    byte[] data = new byte[Borsh.lenArray(arrayResult)];
    assertEquals(arrayByteLength, data.length);
    len = writeArray(arrayResult, data, 0);
    assertEquals(arrayByteLength, len);
    assertArrayEquals(Arrays.copyOfRange(vectorArray, 4, vectorArray.length), data);

    // verify vector array write/read
    var result = readMultiDimensionVectorArray(Clock.class, borshFactory, ELEMENTS_PER_ARRAY, vectorArray, 0);
    assertEquals(expectedMatrix.length, result.length);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }

    data = new byte[Borsh.lenVectorArray(result)];
    assertEquals(vectorArray.length, data.length);
    len = Borsh.writeVectorArray(expectedMatrix, data, 0);
    assertEquals(vectorArray.length, len);
    assertArrayEquals(vectorArray, data);

    // verify vector write/read
    final int vectorLength = Integer.BYTES + (VECTOR_SIZE * Integer.BYTES) + (VECTOR_SIZE * arrayLength);
    data = new byte[Borsh.lenVector(result)];
    assertEquals(vectorLength, data.length);
    len = Borsh.writeVector(expectedMatrix, data, 0);
    assertEquals(vectorLength, len);

    result = Borsh.readMultiDimensionVector(Clock.class, borshFactory, data, 0);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }
  }
}
