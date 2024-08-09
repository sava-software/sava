package software.sava.core.util;

public interface LamportDecimal {

  int LAMPORT_DIGITS = 9;

  default int decimals() {
    return LAMPORT_DIGITS;
  }
}
