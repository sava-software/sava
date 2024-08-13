package software.sava.core.util;

import java.math.BigDecimal;
import java.math.BigInteger;

public interface LamportDecimal extends DecimalInteger {

  int LAMPORT_DIGITS = 9;

  default int decimals() {
    return LAMPORT_DIGITS;
  }

  static BigDecimal toBigDecimal(final long val) {
    return DecimalInteger.toDecimal(val, LAMPORT_DIGITS);
  }

  static BigDecimal toBigDecimal(final BigInteger val) {
    return DecimalInteger.toDecimal(val, LAMPORT_DIGITS);
  }

  static BigDecimal toBigDecimal(final BigDecimal val) {
    return DecimalInteger.toDecimal(val, LAMPORT_DIGITS);
  }

  static BigDecimal fromBigDecimal(final BigDecimal val) {
    return DecimalInteger.toDecimal(val, LAMPORT_DIGITS);
  }
}
