package software.sava.core.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LamportDecimalTests {

  private static final long ONE_SOL = 1_000_000_000L;

  private record Lamports() implements LamportDecimal {
  }

  private static void assertDecimal(final String expected, final int expectedScale, final BigDecimal actual) {
    assertEquals(new BigDecimal(expected), actual);
    assertEquals(expectedScale, actual.scale(), "unexpected scale for " + actual);
  }

  /// Nine is the on-chain lamports-per-SOL exponent; it is not a tunable.
  @Test
  void lamportDigitsIsNine() {
    assertEquals(9, LamportDecimal.LAMPORT_DIGITS);
    assertEquals(9, new Lamports().decimals());
  }

  @Test
  void convertsLamportsToSol() {
    assertDecimal("1", 0, LamportDecimal.toBigDecimal(ONE_SOL));
    assertDecimal("0", 0, LamportDecimal.toBigDecimal(0L));
    // one lamport is the smallest unit SOL can express
    assertDecimal("1E-9", 9, LamportDecimal.toBigDecimal(1L));
    assertDecimal("2.5", 1, LamportDecimal.toBigDecimal(2_500_000_000L));
    // rent-exempt minimum for a 0-byte account, a value that shows up everywhere
    assertDecimal("0.00089088", 8, LamportDecimal.toBigDecimal(890_880L));
  }

  @Test
  void convertsSolToLamports() {
    assertDecimal("1000000000", 0, LamportDecimal.fromBigDecimal(BigDecimal.ONE));
    assertDecimal("2500000000", 0, LamportDecimal.fromBigDecimal(new BigDecimal("2.5")));
    assertDecimal("1", 0, LamportDecimal.fromBigDecimal(new BigDecimal("1E-9")));
  }

  @Test
  void appliesNineDigitsAndNotSomeOtherScale() {
    // an off-by-one in LAMPORT_DIGITS moves the point by a factor of ten
    assertEquals(0, LamportDecimal.toBigDecimal(ONE_SOL).compareTo(BigDecimal.ONE));
    assertEquals(0, LamportDecimal.toBigDecimal(ONE_SOL * 10).compareTo(BigDecimal.TEN));
    assertEquals(0, LamportDecimal.fromBigDecimal(BigDecimal.ONE).compareTo(BigDecimal.valueOf(ONE_SOL)));
  }

  @Test
  void roundTripsThroughSol() {
    for (final long lamports : new long[]{0L, 1L, 890_880L, ONE_SOL, Long.MAX_VALUE}) {
      final var sol = LamportDecimal.toBigDecimal(lamports);
      assertEquals(0, LamportDecimal.fromBigDecimal(sol).compareTo(BigDecimal.valueOf(lamports)), "round trip of " + lamports);
    }
  }

  /// Lamport balances are u64 on chain, so the top half of the range arrives as
  /// a negative long.
  @Test
  void readsNegativeLongsAsUnsigned() {
    assertDecimal("18446744073.709551615", 9, LamportDecimal.toBigDecimal(-1L));
    assertDecimal("9223372036.854775808", 9, LamportDecimal.toBigDecimal(Long.MIN_VALUE));
  }

  @Test
  void bigIntegerAndBigDecimalOverloadsAgree() {
    assertDecimal("1", 0, LamportDecimal.toBigDecimal(BigInteger.valueOf(ONE_SOL)));
    assertDecimal("1", 0, LamportDecimal.toBigDecimal(BigDecimal.valueOf(ONE_SOL)));
    // beyond the signed range the BigInteger overload is the only exact route
    assertDecimal("18446744073.709551615", 9, LamportDecimal.toBigDecimal(new BigInteger("18446744073709551615")));
  }
}
