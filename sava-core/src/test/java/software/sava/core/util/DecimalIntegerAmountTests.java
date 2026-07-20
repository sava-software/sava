package software.sava.core.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

final class DecimalIntegerAmountTests {

  private static final BigInteger UNSIGNED_LONG_MAX = new BigInteger("18446744073709551615");

  /// Supplies the raw long and inherits amount().
  private record RawAmount(long asLong, int decimals) implements DecimalIntegerAmount {
  }

  /// Supplies the BigInteger and inherits asLong().
  private record WideAmount(BigInteger amount, int decimals) implements DecimalIntegerAmount {
  }

  /// Implements neither side of the pair.
  private record NoAmount(int decimals) implements DecimalIntegerAmount {
  }

  private static void assertDecimal(final String expected, final int expectedScale, final BigDecimal actual) {
    assertEquals(new BigDecimal(expected), actual);
    assertEquals(expectedScale, actual.scale(), "unexpected scale for " + actual);
  }

  @Test
  void amountWidensPositiveLongs() {
    assertEquals(BigInteger.ZERO, new RawAmount(0L, 9).amount());
    assertEquals(BigInteger.ONE, new RawAmount(1L, 9).amount());
    assertEquals(BigInteger.valueOf(Long.MAX_VALUE), new RawAmount(Long.MAX_VALUE, 9).amount());
  }

  /// The raw long is a u64 read off chain, so the whole top half of the range
  /// arrives negative and must widen unsigned rather than sign extend.
  @Test
  void amountWidensNegativeLongsAsUnsigned() {
    assertEquals(UNSIGNED_LONG_MAX, new RawAmount(-1L, 9).amount());
    assertEquals(new BigInteger("9223372036854775808"), new RawAmount(Long.MIN_VALUE, 9).amount());
    // never negative, whatever the bit pattern
    assertEquals(1, new RawAmount(-1L, 9).amount().signum());
  }

  @Test
  void asLongNarrowsBackToTheSameBits() {
    for (final long raw : new long[]{0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 1_234_567_891L}) {
      assertEquals(raw, new WideAmount(new RawAmount(raw, 9).amount(), 9).asLong(), "round trip of " + raw);
    }
  }

  /// asLong() truncates to the low 64 bits with no range check. Anything wider
  /// than a u64 is silently rewritten, so callers holding real BigInteger
  /// amounts must not reach for it.
  @Test
  void asLongTruncatesValuesWiderThanU64() {
    assertEquals(-1L, new WideAmount(UNSIGNED_LONG_MAX, 9).asLong());
    assertEquals(5L, new WideAmount(UNSIGNED_LONG_MAX.add(BigInteger.valueOf(6L)), 9).asLong());
    assertEquals(0L, new WideAmount(BigInteger.TWO.pow(64), 9).asLong());
  }

  @Test
  void toDecimalUsesAmountAndDecimals() {
    assertDecimal("1", 0, new RawAmount(1_000_000_000L, 9).toDecimal());
    assertDecimal("1", 0, new RawAmount(1_000_000L, 6).toDecimal());
    assertDecimal("1E-9", 9, new RawAmount(1L, 9).toDecimal());
    // stripTrailingZeros runs past the point, so a zero-decimal amount reports a
    // negative scale rather than the literal digits
    assertDecimal("1E+6", -6, new RawAmount(1_000_000L, 0).toDecimal());
  }

  /// toDecimal() goes through amount(), so a u64 past the signed range converts
  /// at full width instead of turning negative.
  @Test
  void toDecimalReadsRawLongsUnsigned() {
    assertDecimal("18446744073.709551615", 9, new RawAmount(-1L, 9).toDecimal());
    assertDecimal("18446744073.709551615", 9, new WideAmount(UNSIGNED_LONG_MAX, 9).toDecimal());
  }

  @Test
  void wideAmountsConvertPastTheLongRange() {
    // exceeds u64: only the BigInteger path can carry it, and toDecimal() must not narrow
    final var wide = UNSIGNED_LONG_MAX.add(BigInteger.ONE);
    assertDecimal("18446744073.709551616", 9, new WideAmount(wide, 9).toDecimal());
  }

  /// amount() and asLong() are defined in terms of each other: an implementation
  /// must override exactly one. Overriding neither is a compile-clean infinite
  /// recursion, so this pins the failure mode rather than leaving it a surprise.
  @Test
  void implementingNeitherRecursesForever() {
    final var broken = new NoAmount(9);
    assertThrows(StackOverflowError.class, broken::amount);
    assertThrows(StackOverflowError.class, broken::asLong);
  }
}
