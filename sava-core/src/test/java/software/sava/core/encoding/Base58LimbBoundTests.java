package software.sava.core.encoding;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/// `Base58.limbsLength` converts a base58 digit count into the number of 32-bit limbs the
/// decoder allocates to hold that value. Its contract is a bit bound: never under-allocate,
/// and do not overshoot the minimum by more than the rounding a limb boundary forces.
///
/// This is asserted directly rather than through `decode` because over-allocation is
/// invisible from the outside — the decoded bytes are identical, since `used` bounds what is
/// read back out. Only the amount of memory asked for changes. That is why the family sat
/// accepted as "allocation size only", and why the coordinate also sat in the audited
/// timeout set: inflate the estimate far enough and the run crawls under allocation and GC
/// until the watchdog stops it, reading `TIMED_OUT` under load and `SURVIVED` when idle.
///
/// The oracle is exact and independent of the implementation. The largest value `d` base58
/// digits can represent is `58^d - 1`, so the minimum number of limbs that can hold it is
/// `ceil(bitLength / 32)` — computed here with `BigInteger`, not with the shift-and-multiply
/// approximation under test. No timing, no allocation counter, no JIT or GC noise: the same
/// answer on any machine, in any load, every run.
final class Base58LimbBoundTests {

  private static final int LIMB_BITS = 32;
  private static final BigInteger BASE = BigInteger.valueOf(58);

  /// Minimum limbs that can represent any value of `digits` base58 digits.
  private static int minimumLimbs(final int digits) {
    if (digits == 0) {
      return 0;
    }
    final int bits = BASE.pow(digits).subtract(BigInteger.ONE).bitLength();
    return (bits + LIMB_BITS - 1) / LIMB_BITS;
  }

  @Test
  void neverUnderAllocatesForAnyDigitCount() {
    for (int digits = 1; digits <= 512; ++digits) {
      final int limbs = Base58.limbsLength(digits);
      final int minimum = minimumLimbs(digits);
      final int d = digits;
      assertTrue(
          limbs >= minimum,
          () -> "limbsLength(" + d + ") = " + limbs + " limbs cannot hold " + d
              + " base58 digits, which need " + minimum + " — the decoder would write out of bounds"
      );
    }
  }

  /// The other half of the bound. Without this, any mutant that inflates the estimate is
  /// still "correct" — it just costs orders of magnitude more memory for the same bytes.
  /// One limb of slack is what rounding a bit count up to a 32-bit boundary can cost.
  @Test
  void neverOverAllocatesByMoreThanOneLimb() {
    for (int digits = 1; digits <= 512; ++digits) {
      final int limbs = Base58.limbsLength(digits);
      final int minimum = minimumLimbs(digits);
      final int d = digits;
      assertTrue(
          limbs <= minimum + 1,
          () -> "limbsLength(" + d + ") = " + limbs + " limbs for " + d
              + " base58 digits, which need only " + minimum
              + " — the same bytes decode, for more memory"
      );
    }
  }

  /// Boundary cases the sweep above brackets but does not single out: the smallest input,
  /// and the digit counts either side of a limb boundary.
  @Test
  void holdsAtTheLimbBoundaries() {
    assertEquals(minimumLimbs(1), Base58.limbsLength(1), "one digit needs one limb");

    for (int digits = 1; digits <= 512; ++digits) {
      if (minimumLimbs(digits) != minimumLimbs(digits + 1)) {
        final int before = Base58.limbsLength(digits);
        final int after = Base58.limbsLength(digits + 1);
        assertTrue(after >= before, "the limb count must not shrink as digits grow");
        assertTrue(
            after <= before + 1,
            "crossing one limb boundary must cost at most one limb, was " + before + " -> " + after
        );
      }
    }
  }

  /// The bound is only meaningful if it tracks the input; a constant would satisfy both
  /// halves above for a single digit count but not across the range.
  @Test
  void growsWithTheDigitCount() {
    assertTrue(Base58.limbsLength(512) > Base58.limbsLength(1), "the estimate must scale with the input");
    // Zero digits yields one limb rather than none: the +31 rounding never rounds down, and
    // one spare limb is inside the stated bound. `decode` never asks — an all-zero input
    // returns before sizing anything — so this pins the degenerate case rather than a
    // requirement, and it is the bound, not the exact value, that is the contract.
    assertTrue(Base58.limbsLength(0) <= minimumLimbs(0) + 1, "the degenerate input must stay inside the bound");
  }
}
