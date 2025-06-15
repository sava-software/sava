package software.sava.core.ecnoding;

import org.junit.jupiter.api.Test;
import software.sava.core.encoding.ByteUtil;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class ByteUtilTests {

  private void testInt128(final BigInteger expected) {
    byte[] write = new byte[32];

    for (int i = 0; i < 16; ++i) {
      ByteUtil.putInt128LE(write, i, expected);
      var read = ByteUtil.getInt128LE(write, i);
      assertEquals(expected, read);

      final var negate = expected.negate();
      ByteUtil.putInt128LE(write, i, negate);
      read = ByteUtil.getUInt128LE(write, i);
      assertEquals(negate, read);
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

    testInt128(new BigInteger("165959464850144709097569536226796601860"));
    testInt128(new BigInteger("67935603135873865182680218184035306913"));
    testInt128(new BigInteger("-162272704100837194170455521702674872040"));
    testInt128(new BigInteger("-155155494242896723051467122773477245"));
    testInt128(new BigInteger("-25912721450736272609715131753556298938"));
  }

  private void testInt256(final BigInteger expected) {
    byte[] write = new byte[64];

    for (int i = 0; i < 32; ++i) {
      ByteUtil.putInt256LE(write, i, expected);
      var read = ByteUtil.getInt256LE(write, i);
      assertEquals(expected, read);

      final var negate = expected.negate();
      ByteUtil.putInt256LE(write, i, negate);
      read = ByteUtil.getInt256LE(write, i);
      assertEquals(negate, read);
    }
  }

  @Test
  void test256BitIntegers() {
    testInt256(new BigInteger("240952751974454416887375303278538328657633745834006340121122396849307110663"));
  }
}
