package software.sava.core.borsh;

import org.junit.jupiter.api.Test;
import software.sava.core.encoding.ByteUtil;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static software.sava.core.borsh.Borsh.readMultiDimensionBigIntegerVector;

final class BorshTests {

  @Test
  void vectorOfArrays() {
    final var vectorSize = 8;
    final int arrayLength = 64;
    final byte[] data = new byte[Integer.BYTES + (vectorSize * arrayLength)];
    ByteUtil.putInt32LE(data, 0, vectorSize);

    final var random = new Random();
    final int u128Length = 128 / Byte.SIZE;
    final byte[] randomBytes = new byte[u128Length];

    final int numIntegers = arrayLength / u128Length;

    final BigInteger[][] expected = new BigInteger[vectorSize][numIntegers];
    for (int i = 0, from = Integer.BYTES, to = from + u128Length; i < vectorSize; ++i) {
      final BigInteger[] expectedArray = expected[i];
      for (int j = 0; j < numIntegers; ++j) {
        random.nextBytes(randomBytes);
        final var val = new BigInteger(randomBytes);
        expectedArray[j] = val;
        ByteUtil.putInt128LE(data, from, val);
        from = to;
        to += u128Length;
      }
      expected[i] = expectedArray;
    }

    final BigInteger[][] result = readMultiDimensionBigIntegerVector(numIntegers, data, 0);
    assertEquals(expected.length, result.length);
    for (int i = 0; i < expected.length; ++i) {
      assertArrayEquals(expected[i], result[i]);
    }
  }

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
}
