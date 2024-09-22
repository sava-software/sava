package software.sava.core.ecnoding;

import org.junit.jupiter.api.Test;
import software.sava.core.encoding.ByteUtil;

import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ByteUtilTests {

  private void testInt128(final BigInteger expected) {
    byte[] write = new byte[16];
    ByteUtil.putInt128LE(write, 0, expected);
    var read = ByteUtil.getInt128LE(write, 0);
    assertEquals(expected, read);

    if (expected.signum() < 0) {
      final var abs = expected.negate();
      Arrays.fill(write, (byte) 0);
      ByteUtil.putInt128LE(write, 0, abs);
      read = ByteUtil.getUInt128LE(write, 0);
      assertEquals(abs, read);
    }
  }

  @Test
  void test128BitIntegers() {
    var expected = new BigInteger("-155155494242896723051467122773477245");
    testInt128(expected);

    expected = new BigInteger("-25912721450736272609715131753556298938");
    testInt128(expected);
  }
}
