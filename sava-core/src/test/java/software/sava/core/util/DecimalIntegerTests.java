package software.sava.core.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

final class DecimalIntegerTests {

  private static final BigInteger UNSIGNED_LONG_MAX = new BigInteger("18446744073709551615");

  private record Decimals(int decimals) implements DecimalInteger {
  }

  /// The scale of the result is load-bearing: `stripTrailingZeros` is what
  /// turns 1.000000000 into 1, and dropping the call leaves a value that is
  /// still `compareTo`-equal but not `equals`-equal. Assert on scale directly
  /// so the difference cannot pass silently.
  private static void assertDecimal(final String expected, final int expectedScale, final BigDecimal actual) {
    assertEquals(new BigDecimal(expected), actual);
    assertEquals(expectedScale, actual.scale(), "unexpected scale for " + actual);
  }

  @Test
  void toDecimalShiftsLeft() {
    // a whole unit collapses to 1, not 1.000000000
    assertDecimal("1", 0, DecimalInteger.toDecimal(BigDecimal.valueOf(1_000_000_000L), 9));
    // the smallest representable fraction keeps all nine digits
    assertDecimal("1E-9", 9, DecimalInteger.toDecimal(BigDecimal.valueOf(1L), 9));
    assertDecimal("1.5", 1, DecimalInteger.toDecimal(BigDecimal.valueOf(1_500_000_000L), 9));
    // shifting the wrong way would give 1.23E+11 rather than 1.23
    assertDecimal("1.23", 2, DecimalInteger.toDecimal(BigDecimal.valueOf(1_230_000_000L), 9));
  }

  @Test
  void fromDecimalShiftsRight() {
    assertDecimal("1000000000", 0, DecimalInteger.fromDecimal(BigDecimal.ONE, 9));
    assertDecimal("1500000000", 0, DecimalInteger.fromDecimal(new BigDecimal("1.5"), 9));
    assertDecimal("500000000", 0, DecimalInteger.fromDecimal(new BigDecimal("0.5"), 9));
  }

  /// movePointRight clamps the resulting scale at zero, so trailing fraction
  /// digits are absorbed rather than preserved — but only until the input has
  /// more of them than the shift can consume.
  @Test
  void fromDecimalClampsScaleAtZero() {
    assertDecimal("1000000000", 0, DecimalInteger.fromDecimal(new BigDecimal("1.0"), 9));
    assertDecimal("1000000000.000", 3, DecimalInteger.fromDecimal(new BigDecimal("1.000000000000"), 9));
  }

  /// Pins the direction of both shifts against each other. A swap of
  /// movePointLeft and movePointRight survives any test that only round trips.
  @Test
  void shiftDirectionsAreOpposite() {
    final var oneUnit = new BigDecimal("1");
    assertTrue(DecimalInteger.toDecimal(oneUnit, 6).compareTo(oneUnit) < 0, "toDecimal must shrink");
    assertTrue(DecimalInteger.fromDecimal(oneUnit, 6).compareTo(oneUnit) > 0, "fromDecimal must grow");
  }

  @Test
  void roundTripThroughDecimalAndBack() {
    for (final long raw : new long[]{0L, 1L, 999_999_999L, 1_000_000_000L, 1_234_567_891L, Long.MAX_VALUE}) {
      final var decimal = DecimalInteger.toDecimal(raw, 9);
      assertEquals(0, DecimalInteger.fromDecimal(decimal, 9).compareTo(BigDecimal.valueOf(raw)), "round trip of " + raw);
    }
  }

  /// A negative long is a raw u64 that overflowed the signed range, so the
  /// long overload reads it unsigned.
  @Test
  void longOverloadTreatsNegativeAsUnsigned() {
    assertDecimal("18446744073.709551615", 9, DecimalInteger.toDecimal(-1L, 9));
    assertDecimal("9223372036.854775808", 9, DecimalInteger.toDecimal(Long.MIN_VALUE, 9));
    // the boundary either side of the sign flip must stay contiguous
    assertDecimal("9223372036.854775807", 9, DecimalInteger.toDecimal(Long.MAX_VALUE, 9));
  }

  @Test
  void longOverloadKeepsPositivesExact() {
    assertDecimal("1", 0, DecimalInteger.toDecimal(1_000_000_000L, 9));
    assertDecimal("0", 0, DecimalInteger.toDecimal(0L, 9));
  }

  /// The BigInteger overload has no unsigned reinterpretation to do — the value
  /// already carries its own sign, so a negative stays negative. This is the
  /// deliberate asymmetry with the long overload above.
  @Test
  void bigIntegerOverloadPreservesSign() {
    assertDecimal("-1E-9", 9, DecimalInteger.toDecimal(BigInteger.valueOf(-1L), 9));
    assertDecimal("1E-9", 9, DecimalInteger.toDecimal(BigInteger.ONE, 9));
    assertDecimal("18446744073.709551615", 9, DecimalInteger.toDecimal(UNSIGNED_LONG_MAX, 9));
  }

  @Test
  void zeroDecimalsIsIdentity() {
    assertDecimal("123", 0, DecimalInteger.toDecimal(BigDecimal.valueOf(123L), 0));
    assertDecimal("123", 0, DecimalInteger.fromDecimal(BigDecimal.valueOf(123L), 0));
    assertDecimal("123", 0, DecimalInteger.toDecimal(123L, 0));
  }

  /// stripTrailingZeros strips them on both sides of the point, so a whole
  /// number ending in zeros comes back with a negative scale: numerically equal,
  /// but 1E+6 rather than 1000000 once it reaches a toString.
  @Test
  void trailingZerosStripPastThePointIntoNegativeScale() {
    assertDecimal("1E+6", -6, DecimalInteger.toDecimal(1_000_000L, 0));
    assertDecimal("1E+3", -3, DecimalInteger.toDecimal(1_000_000_000_000L, 9));
    assertEquals(0, DecimalInteger.toDecimal(1_000_000L, 0).compareTo(BigDecimal.valueOf(1_000_000L)));
  }

  /// Nothing rejects a negative scale, and the shift simply runs the other way.
  /// Pinned so the behaviour is a decision rather than an accident.
  @Test
  void negativeDecimalsShiftTheOtherWay() {
    assertDecimal("1E+3", -3, DecimalInteger.toDecimal(BigDecimal.ONE, -3));
    assertDecimal("0.001", 3, DecimalInteger.fromDecimal(BigDecimal.ONE, -3));
  }

  @Test
  void defaultMethodsUseDeclaredDecimals() {
    final var six = new Decimals(6);
    assertEquals(6, six.decimals());
    assertDecimal("1", 0, six.toDecimal(1_000_000L));
    assertDecimal("1", 0, six.toDecimal(BigInteger.valueOf(1_000_000L)));
    assertDecimal("1", 0, six.toDecimal(BigDecimal.valueOf(1_000_000L)));
    assertDecimal("1000000", 0, six.fromDecimal(BigDecimal.ONE));
  }

  /// Two different scales must not agree, or the default methods could ignore
  /// decimals() entirely and still pass.
  @Test
  void defaultMethodsHonourDifferentScales() {
    assertDecimal("0.001", 3, new Decimals(9).toDecimal(1_000_000L));
    assertDecimal("1", 0, new Decimals(6).toDecimal(1_000_000L));
    assertDecimal("1E+6", -6, new Decimals(0).toDecimal(1_000_000L));
  }

  @Test
  void defaultMethodsInheritUnsignedLongHandling() {
    assertDecimal("18446744073709.551615", 6, new Decimals(6).toDecimal(-1L));
  }
}
