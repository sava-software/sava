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
                             final int vectorSize,
                             final int numElementsPerSubArray,
                             final int dataTypeByteLength,
                             final byte[] vectorArray,
                             final Function<byte[], T> factory,
                             final Serializer<T> serializer) {
    final var random = new Random();
    final var expectedMatrix = (T[][]) Array.newInstance(clas, vectorSize, numElementsPerSubArray);
    for (int i = 0, from = Integer.BYTES, to = from + dataTypeByteLength; i < vectorSize; ++i) {
      final var expectedArray = expectedMatrix[i];
      for (int j = 0; j < numElementsPerSubArray; ) {
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
    final var vectorSize = 8;
    final int numElementsPerSubArray = 4;
    final int dataTypeByteLength = Float.BYTES;
    final int arrayLength = numElementsPerSubArray * dataTypeByteLength;
    final int arrayByteLength = vectorSize * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, vectorSize);

    final var random = new Random();
    final var expectedMatrix = new float[vectorSize][numElementsPerSubArray];
    for (int i = 0, from = Integer.BYTES, to = from + dataTypeByteLength; i < vectorSize; ++i) {
      final var expectedArray = expectedMatrix[i];
      for (int j = 0; j < numElementsPerSubArray; ) {
        final var val = random.nextFloat();
        ByteUtil.putFloat32LE(vectorArray, from, val);
        expectedArray[j++] = val;
        from = to;
        to += dataTypeByteLength;
      }
      expectedMatrix[i] = expectedArray;
    }

    // verify read/write arrays
    final var arrayResult = new float[vectorSize][numElementsPerSubArray];
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
    var result = readMultiDimensionfloatVectorArray(numElementsPerSubArray, vectorArray, 0);
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
    final int vectorLength = Integer.BYTES + (vectorSize * Integer.BYTES) + (vectorSize * arrayLength);
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
    final var vectorSize = 8;
    final int numElementsPerSubArray = 4;
    final int dataTypeByteLength = Double.BYTES;
    final int arrayLength = numElementsPerSubArray * dataTypeByteLength;
    final int arrayByteLength = vectorSize * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, vectorSize);

    final var random = new Random();
    final var expectedMatrix = new double[vectorSize][numElementsPerSubArray];
    for (int i = 0, from = Integer.BYTES, to = from + dataTypeByteLength; i < vectorSize; ++i) {
      final var expectedArray = expectedMatrix[i];
      for (int j = 0; j < numElementsPerSubArray; ) {
        final var val = random.nextDouble();
        ByteUtil.putFloat64LE(vectorArray, from, val);
        expectedArray[j++] = val;
        from = to;
        to += dataTypeByteLength;
      }
      expectedMatrix[i] = expectedArray;
    }

    // verify read/write arrays
    final var arrayResult = new double[vectorSize][numElementsPerSubArray];
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
    var result = readMultiDimensiondoubleVectorArray(numElementsPerSubArray, vectorArray, 0);
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
    final int vectorLength = Integer.BYTES + (vectorSize * Integer.BYTES) + (vectorSize * arrayLength);
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
    final var vectorSize = 8;
    final int numElementsPerSubArray = 4;
    final int dataTypeByteLength = Byte.BYTES;
    final int arrayLength = numElementsPerSubArray * dataTypeByteLength;
    final int arrayByteLength = vectorSize * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, vectorSize);

    final var random = new Random();
    final var expectedMatrix = new byte[vectorSize][numElementsPerSubArray];
    for (int i = 0, from = Integer.BYTES, to = from + dataTypeByteLength; i < vectorSize; ++i) {
      final var expectedArray = expectedMatrix[i];
      for (int j = 0; j < numElementsPerSubArray; ) {
        final var val = (byte) (random.nextBoolean() ? 1 : 0);
        vectorArray[from] = val;
        expectedArray[j++] = val;
        from = to;
        to += dataTypeByteLength;
      }
      expectedMatrix[i] = expectedArray;
    }

    // verify read/write arrays
    final var arrayResult = new byte[vectorSize][numElementsPerSubArray];
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
    var result = readMultiDimensionbyteVectorArray(numElementsPerSubArray, vectorArray, 0);
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
    final int vectorLength = Integer.BYTES + (vectorSize * Integer.BYTES) + (vectorSize * arrayLength);
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
    final var vectorSize = 8;
    final int numElementsPerSubArray = 4;
    final int dataTypeByteLength = Byte.BYTES;
    final int arrayLength = numElementsPerSubArray * dataTypeByteLength;
    final int arrayByteLength = vectorSize * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, vectorSize);

    final var random = new Random();
    final var expectedMatrix = new byte[vectorSize][numElementsPerSubArray];
    for (int i = 0, from = Integer.BYTES, to = from + dataTypeByteLength; i < vectorSize; ++i) {
      final var expectedArray = expectedMatrix[i];
      for (int j = 0; j < numElementsPerSubArray; ) {
        final var val = (byte) random.nextInt();
        vectorArray[from] = val;
        expectedArray[j++] = val;
        from = to;
        to += dataTypeByteLength;
      }
      expectedMatrix[i] = expectedArray;
    }

    // verify read/write arrays
    final var arrayResult = new byte[vectorSize][numElementsPerSubArray];
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
    var result = readMultiDimensionbyteVectorArray(numElementsPerSubArray, vectorArray, 0);
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
    final int vectorLength = Integer.BYTES + (vectorSize * Integer.BYTES) + (vectorSize * arrayLength);
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
    final var vectorSize = 8;
    final int numElementsPerSubArray = 4;
    final int dataTypeByteLength = Short.BYTES;
    final int arrayLength = numElementsPerSubArray * dataTypeByteLength;
    final int arrayByteLength = vectorSize * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, vectorSize);

    final var random = new Random();
    final var expectedMatrix = new short[vectorSize][numElementsPerSubArray];
    for (int i = 0, from = Integer.BYTES, to = from + dataTypeByteLength; i < vectorSize; ++i) {
      final var expectedArray = expectedMatrix[i];
      for (int j = 0; j < numElementsPerSubArray; ) {
        final var val = (short) random.nextInt();
        ByteUtil.putInt16LE(vectorArray, from, val);
        expectedArray[j++] = val;
        from = to;
        to += dataTypeByteLength;
      }
      expectedMatrix[i] = expectedArray;
    }

    // verify read/write arrays
    final var arrayResult = new short[vectorSize][numElementsPerSubArray];
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
    var result = readMultiDimensionshortVectorArray(numElementsPerSubArray, vectorArray, 0);
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
    final int vectorLength = Integer.BYTES + (vectorSize * Integer.BYTES) + (vectorSize * arrayLength);
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
    final var vectorSize = 8;
    final int numElementsPerSubArray = 4;
    final int dataTypeByteLength = Integer.BYTES;
    final int arrayLength = numElementsPerSubArray * dataTypeByteLength;
    final int arrayByteLength = vectorSize * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, vectorSize);

    final var random = new Random();
    final var expectedMatrix = new int[vectorSize][numElementsPerSubArray];
    for (int i = 0, from = Integer.BYTES, to = from + dataTypeByteLength; i < vectorSize; ++i) {
      final var expectedArray = expectedMatrix[i];
      for (int j = 0; j < numElementsPerSubArray; ) {
        final var val = random.nextInt();
        ByteUtil.putInt32LE(vectorArray, from, val);
        expectedArray[j++] = val;
        from = to;
        to += dataTypeByteLength;
      }
      expectedMatrix[i] = expectedArray;
    }

    // verify read/write arrays
    final var arrayResult = new int[vectorSize][numElementsPerSubArray];
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
    var result = readMultiDimensionintVectorArray(numElementsPerSubArray, vectorArray, 0);
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
    final int vectorLength = Integer.BYTES + (vectorSize * Integer.BYTES) + (vectorSize * arrayLength);
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
    final var vectorSize = 8;
    final int numElementsPerSubArray = 4;
    final int dataTypeByteLength = Long.BYTES;
    final int arrayLength = numElementsPerSubArray * dataTypeByteLength;
    final int arrayByteLength = vectorSize * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, vectorSize);

    final var random = new Random();
    final var expectedMatrix = new long[vectorSize][numElementsPerSubArray];
    for (int i = 0, from = Integer.BYTES, to = from + dataTypeByteLength; i < vectorSize; ++i) {
      final var expectedArray = expectedMatrix[i];
      for (int j = 0; j < numElementsPerSubArray; ) {
        final var val = random.nextLong();
        ByteUtil.putInt64LE(vectorArray, from, val);
        expectedArray[j++] = val;
        from = to;
        to += dataTypeByteLength;
      }
      expectedMatrix[i] = expectedArray;
    }

    // verify read/write arrays
    final var arrayResult = new long[vectorSize][numElementsPerSubArray];
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
    var result = readMultiDimensionlongVectorArray(numElementsPerSubArray, vectorArray, 0);
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
    final int vectorLength = Integer.BYTES + (vectorSize * Integer.BYTES) + (vectorSize * arrayLength);
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
  void multiDimensionalBigIntegers() {
    final var vectorSize = 8;
    final int numElementsPerSubArray = 4;
    final int dataTypeByteLength = 128 / Byte.SIZE;
    final int arrayLength = numElementsPerSubArray * dataTypeByteLength;
    final int arrayByteLength = vectorSize * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, vectorSize);

    final var expectedMatrix = multiDimensional(
        BigInteger.class,
        vectorSize,
        numElementsPerSubArray,
        dataTypeByteLength,
        vectorArray,
        BigInteger::new,
        (val, _vectorArray, from) -> ByteUtil.putInt128LE(_vectorArray, from, val)
    );

    // verify read/write arrays
    final var arrayResult = new BigInteger[vectorSize][numElementsPerSubArray];
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
    var result = readMultiDimensionBigIntegerVectorArray(numElementsPerSubArray, vectorArray, 0);
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
    final int vectorLength = Integer.BYTES + (vectorSize * Integer.BYTES) + (vectorSize * arrayLength);
    data = new byte[Borsh.lenVector(result)];
    assertEquals(vectorLength, data.length);
    len = Borsh.writeVector(expectedMatrix, data, 0);
    assertEquals(vectorLength, len);

    result = Borsh.readMultiDimensionBigIntegerVector(data, 0);
    for (int i = 0; i < expectedMatrix.length; ++i) {
      assertArrayEquals(expectedMatrix[i], result[i]);
    }
  }

  @Test
  void multiDimensionalPublicKeys() {
    final var vectorSize = 8;
    ;
    final int numElementsPerSubArray = 4;
    final int dataTypeByteLength = PublicKey.PUBLIC_KEY_LENGTH;
    final int arrayLength = numElementsPerSubArray * dataTypeByteLength;
    final int arrayByteLength = vectorSize * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, vectorSize);

    final var expectedMatrix = multiDimensional(
        PublicKey.class,
        vectorSize,
        numElementsPerSubArray,
        dataTypeByteLength,
        vectorArray,
        PublicKey::createPubKey,
        PublicKey::write
    );

    // verify read/write arrays
    final var arrayResult = new PublicKey[vectorSize][numElementsPerSubArray];
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
    var result = readMultiDimensionPublicKeyVectorArray(numElementsPerSubArray, vectorArray, 0);
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
    final int vectorLength = Integer.BYTES + (vectorSize * Integer.BYTES) + (vectorSize * arrayLength);
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
    final var vectorSize = 8;
    final int dataTypeByteLength = Clock.BYTES;
    final int numElementsPerSubArray = 4;
    final int arrayLength = numElementsPerSubArray * dataTypeByteLength;
    final int arrayByteLength = vectorSize * arrayLength;
    final byte[] vectorArray = new byte[Integer.BYTES + arrayByteLength];
    ByteUtil.putInt32LE(vectorArray, 0, vectorSize);

    final var expectedMatrix = multiDimensional(
        Clock.class,
        vectorSize,
        numElementsPerSubArray,
        dataTypeByteLength,
        vectorArray,
        Clock::read,
        Clock::write
    );

    // verify read/write arrays
    final var arrayResult = new Clock[vectorSize][numElementsPerSubArray];
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
    var result = readMultiDimensionVectorArray(Clock.class, borshFactory, numElementsPerSubArray, vectorArray, 0);
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
    final int vectorLength = Integer.BYTES + (vectorSize * Integer.BYTES) + (vectorSize * arrayLength);
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
