package software.sava.core.util;

import java.math.BigDecimal;
import java.math.BigInteger;

public interface DecimalInteger {

  int decimals();

  default BigDecimal toDecimal(final long amount) {
    return toDecimal(amount, decimals());
  }

  default BigDecimal toDecimal(final BigInteger amount) {
    return toDecimal(amount, decimals());
  }

  default BigDecimal toDecimal(final BigDecimal amount) {
    return toDecimal(amount, decimals());
  }

  default BigDecimal fromDecimal(final BigDecimal amount) {
    return fromDecimal(amount, decimals());
  }

  static BigDecimal toDecimal(final long val, final int decimals) {
    return toDecimal(
        val < 0 ? new BigDecimal(Long.toUnsignedString(val)) : BigDecimal.valueOf(val),
        decimals);
  }

  static BigDecimal toDecimal(final BigInteger val, final int decimals) {
    return toDecimal(new BigDecimal(val), decimals);
  }

  static BigDecimal toDecimal(final BigDecimal val, final int decimals) {
    return val.movePointLeft(decimals).stripTrailingZeros();
  }

  static BigDecimal fromDecimal(final BigDecimal val, final int decimals) {
    return val.movePointRight(decimals);
  }
}
