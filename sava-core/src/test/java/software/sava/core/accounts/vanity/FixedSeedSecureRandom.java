package software.sava.core.accounts.vanity;

import java.security.SecureRandom;
import java.util.Random;

/// Deterministic stand-in for the worker's entropy source.
///
/// The workers only ever draw key material through [SecureRandom#nextBytes], so
/// overriding that one method makes an entire vanity search reproducible: the
/// same seed always finds the same address after the same number of attempts.
/// That is what lets these tests be mutation tested and lets a failure be
/// re-run, rather than being a fresh search every time.
///
/// The tests still assert the *property* — that whatever was found genuinely
/// matches, re-derived from the returned key pair — never a hard coded address.
/// Fixing the seed is about reproducibility; it is not an invitation to pin
/// golden values that would couple these tests to ed25519 derivation internals.
///
/// Obviously not secure. Public only so tests in other packages (pbkdf, accounts) can share
/// it; it lives in the test sources and is excluded from the vanity mutation suite by name.
public final class FixedSeedSecureRandom extends SecureRandom {

  /// Arbitrary fixed seeds. Several rather than one so the suite still samples
  /// more than a single key path, without giving up reproducibility.
  static final long[] SEEDS = {1L, 7L, 42L, 1337L, 8675309L};

  private final Random random;
  private final long maxDraws;
  private long draws;

  public FixedSeedSecureRandom(final long seed) {
    this(seed, Long.MAX_VALUE);
  }

  /// A source that fails the search once it has drawn more than `maxDraws` times.
  ///
  /// Every key pair a worker generates is exactly one [#nextBytes] call, so a draw budget
  /// counts the worker's attempts from the outside. A test whose search can never succeed
  /// relies on the worker's own attempt cap to end it; a mutant that breaks that cap (the
  /// attempt counter, or the branch that acts on it) would otherwise spin until a timeout
  /// interrupts it. With a budget above the cap the test sets, the same mutant instead fails
  /// on a deterministic [AssertionError] naming the budget, while working code never reaches
  /// it.
  ///
  /// @param maxDraws draws allowed before the next one throws
  public FixedSeedSecureRandom(final long seed, final long maxDraws) {
    this.random = new Random(seed);
    this.maxDraws = maxDraws;
  }

  /// @return the number of [#nextBytes] calls made so far
  public long draws() {
    return draws;
  }

  @Override
  public void nextBytes(final byte[] bytes) {
    if (++draws > maxDraws) {
      throw new AssertionError(
          "the search drew past its budget of " + maxDraws + " key pairs; its attempt cap did not stop it"
      );
    }
    random.nextBytes(bytes);
  }
}
