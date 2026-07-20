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
/// Obviously not secure, and deliberately package private to the test sources.
final class FixedSeedSecureRandom extends SecureRandom {

  /// Arbitrary fixed seeds. Several rather than one so the suite still samples
  /// more than a single key path, without giving up reproducibility.
  static final long[] SEEDS = {1L, 7L, 42L, 1337L, 8675309L};

  private final Random random;

  FixedSeedSecureRandom(final long seed) {
    this.random = new Random(seed);
  }

  @Override
  public void nextBytes(final byte[] bytes) {
    random.nextBytes(bytes);
  }
}
