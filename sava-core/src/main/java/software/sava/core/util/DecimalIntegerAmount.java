package software.sava.core.util;

import software.sava.core.encoding.ByteUtil;

import java.math.BigDecimal;
import java.math.BigInteger;

public interface DecimalIntegerAmount extends DecimalInteger {

  default BigInteger amount() {
    final long amount = asLong();
    return amount < 0
        ? ByteUtil.toUnsignedBigInteger(amount)
        : BigInteger.valueOf(amount);
  }

  default long asLong() {
    return amount().longValue();
  }

  default BigDecimal toDecimal() {
    return toDecimal(amount());
  }
}
