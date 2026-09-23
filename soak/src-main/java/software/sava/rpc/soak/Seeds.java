package software.sava.rpc.soak;

import software.sava.core.accounts.PublicKey;

import java.util.SplittableRandom;

/// Every random decision in a run derives from `SOAK_SEED` through one of these functions, so a
/// finding can be replayed from `run.env` alone.
///
/// The derivations are pure functions of `(seed, salt, index)` rather than splits of a shared
/// generator: a split depends on how many splits happened before it, which would make one
/// engine's stream depend on another engine's start-up order — exactly the kind of coupling
/// that turns "reproducible" into "reproducible on a quiet machine".
public final class Seeds {

  /// The key table is 256 entries because the subscription plan indexes keys with a byte and
  /// the peer derives program-account keys from the same table.
  public static final int KEY_TABLE_SIZE = 256;

  private static final long ENGINE_SALT = 0x9E3779B97F4A7C15L;
  private static final long WORKER_SALT = 0xC2B2AE3D27D4EB4FL;
  private static final long CONNECTION_SALT = 0x165667B19E3779F9L;
  private static final long KEY_TABLE_SALT = 0x27D4EB2F165667C5L;

  private Seeds() {
  }

  public static SplittableRandom forEngine(final long seed, final int engineIndex) {
    return new SplittableRandom(mix(seed, ENGINE_SALT, engineIndex));
  }

  public static SplittableRandom forWorker(final long seed, final int workerIndex) {
    return new SplittableRandom(mix(seed, WORKER_SALT, workerIndex));
  }

  public static SplittableRandom forConnection(final long seed, final long ordinal) {
    return new SplittableRandom(mix(seed, CONNECTION_SALT, ordinal));
  }

  /// The 256 keys both sides of a run agree on. Each key is generated from its own derived
  /// stream, so the table is identical whichever process builds it and whichever order it
  /// builds the entries in.
  public static PublicKey[] keyTable(final long seed) {
    final var keys = new PublicKey[KEY_TABLE_SIZE];
    for (int i = 0; i < KEY_TABLE_SIZE; ++i) {
      final var random = new SplittableRandom(mix(seed, KEY_TABLE_SALT, i));
      final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
      random.nextBytes(bytes);
      keys[i] = PublicKey.createPubKey(bytes);
    }
    return keys;
  }

  /// Stafford variant 13 of the SplitMix64 finaliser, the same mixer [SplittableRandom] uses
  /// for its own gamma, applied to `seed ^ salt` and the index so that adjacent indices do not
  /// produce correlated streams.
  public static long mix(final long seed, final long salt, final long index) {
    long z = seed ^ (salt * (index + 1));
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }
}
