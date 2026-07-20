package software.sava.core.accounts.vanity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/// Drives the workers to a real match and re-derives the answer independently.
///
/// The workers decide whether a key matches from their own incremental base58
/// buffers — `beginMutableEncode` into a short tail buffer, then
/// `continueMutableEncode` resuming from offsets packed into a single long
/// (`>>> 48` and `>>> 32 & 0xFFFF`). The [Result] carries the key pair, and its
/// [software.sava.core.accounts.PublicKey] is read straight from those bytes, so
/// comparing against `publicKey().toBase58()` checks the incremental encode
/// against a full one. A mis-unpacked offset shows up as a match that does not
/// hold on the real address.
///
/// Every search runs off a [FixedSeedSecureRandom], so a failure here is
/// reproducible rather than a one-off draw.
final class MaskWorkerTests {

  private static final int CHECK_FOUND = 1024;
  private static final long UNBOUNDED = Long.MAX_VALUE;

  private static ArrayBlockingQueue<Result> newResults() {
    return new ArrayBlockingQueue<>(4);
  }

  private static BeginsWithMaskWorker beginsWithWorker(final long seed,
                                                       final Subsequence beginsWith,
                                                       final java.util.Queue<Result> results,
                                                       final AtomicInteger found,
                                                       final AtomicLong searched,
                                                       final int checkFound,
                                                       final long maxSearches) {
    return new BeginsWithMaskWorker(
        null, null, new FixedSeedSecureRandom(seed), null, null, null, false,
        beginsWith, 1, found, searched, results, checkFound, maxSearches);
  }

  private static Result runToResult(final BeginsWithMaskWorker worker, final ArrayBlockingQueue<Result> results) {
    worker.run();
    final var result = results.poll();
    assertNotNull(result, "worker returned without queueing a result");
    return result;
  }

  @Test
  @Timeout(120)
  void beginsWithWorkerFindsAMatchingAddress() {
    final var beginsWith = Subsequence.create("a", false, false, false);
    for (final long seed : FixedSeedSecureRandom.SEEDS) {
      final var results = newResults();
      final var result = runToResult(
          beginsWithWorker(seed, beginsWith, results, new AtomicInteger(0), new AtomicLong(0), CHECK_FOUND, UNBOUNDED),
          results);
      final var address = result.publicKey().toBase58();
      assertTrue(address.startsWith("a") || address.startsWith("A"),
          "seed " + seed + " did not begin with a match: " + address);
      assertEquals(64, result.keyPair().length);
    }
  }

  /// Same seed, same answer — this is what makes the suite mutation testable.
  @Test
  @Timeout(120)
  void searchesAreReproducible() {
    final var beginsWith = Subsequence.create("a", false, false, false);
    final var firstResults = newResults();
    final var firstSearched = new AtomicLong(0);
    final var first = runToResult(
        beginsWithWorker(1L, beginsWith, firstResults, new AtomicInteger(0), firstSearched, 1, UNBOUNDED),
        firstResults);

    final var secondResults = newResults();
    final var secondSearched = new AtomicLong(0);
    final var second = runToResult(
        beginsWithWorker(1L, beginsWith, secondResults, new AtomicInteger(0), secondSearched, 1, UNBOUNDED),
        secondResults);

    assertEquals(first.publicKey(), second.publicKey());
    assertArrayEquals(first.keyPair(), second.keyPair());
    assertEquals(firstSearched.get(), secondSearched.get());
  }

  /// The interesting case: both ends constrained, so the worker must resume the
  /// encode from the packed offsets rather than only reading the tail buffer.
  @Test
  @Timeout(180)
  void maskWorkerMatchesBothEndsOfTheRealAddress() {
    final var beginsWith = Subsequence.create("a", false, false, false);
    final var endsWith = Subsequence.create("z", false, false, false);
    for (final long seed : FixedSeedSecureRandom.SEEDS) {
      final var results = newResults();
      new MaskWorker(
          null, null, new FixedSeedSecureRandom(seed), null, null, null, false,
          beginsWith, endsWith, 1, new AtomicInteger(0), new AtomicLong(0), results, CHECK_FOUND, UNBOUNDED
      ).run();
      final var result = results.poll();
      assertNotNull(result, "seed " + seed + " returned without queueing a result");
      final var address = result.publicKey().toBase58();
      assertTrue(address.startsWith("a") || address.startsWith("A"),
          "seed " + seed + " did not begin with a match: " + address);
      assertTrue(address.endsWith("z") || address.endsWith("Z"),
          "seed " + seed + " did not end with a match: " + address);
    }
  }

  /// endsWith alone leaves `beginsWith` null, which queueResult treats as "accept
  /// any prefix" — the tail must still be a genuine match.
  @Test
  @Timeout(120)
  void maskWorkerWithoutBeginsWithStillMatchesTheTail() {
    final var endsWith = Subsequence.create("z", false, false, false);
    for (final long seed : FixedSeedSecureRandom.SEEDS) {
      final var results = newResults();
      new MaskWorker(
          null, null, new FixedSeedSecureRandom(seed), null, null, null, false,
          null, endsWith, 1, new AtomicInteger(0), new AtomicLong(0), results, CHECK_FOUND, UNBOUNDED
      ).run();
      final var result = results.poll();
      assertNotNull(result, "seed " + seed + " returned without queueing a result");
      final var address = result.publicKey().toBase58();
      assertTrue(address.endsWith("z") || address.endsWith("Z"),
          "seed " + seed + " did not end with a match: " + address);
    }
  }

  /// A multi-character tail exercises a wider short buffer and a longer resume.
  @Test
  @Timeout(180)
  void maskWorkerMatchesATwoCharacterTail() {
    final var endsWith = Subsequence.create("zz", false, false, false);
    final var results = newResults();
    new MaskWorker(
        null, null, new FixedSeedSecureRandom(FixedSeedSecureRandom.SEEDS[0]), null, null, null, false,
        null, endsWith, 1, new AtomicInteger(0), new AtomicLong(0), results, CHECK_FOUND, UNBOUNDED
    ).run();
    final var result = results.poll();
    assertNotNull(result, "worker returned without queueing a result");
    final var address = result.publicKey().toBase58();
    assertEquals("zz", address.substring(address.length() - 2).toLowerCase(),
        "address did not end with a match: " + address);
  }

  /// The escape hatch: an eight character target is ~58^8 addresses away, so this
  /// search never succeeds. Without a cap the worker spins forever.
  @Test
  @Timeout(60)
  void exhaustingMaxSearchesStopsAnUnsatisfiableSearch() {
    final var beginsWith = Subsequence.create("savasava", true, false, false);
    final var results = newResults();
    final var found = new AtomicInteger(0);

    beginsWithWorker(1L, beginsWith, results, found, new AtomicLong(0), 16, 500).run();

    assertEquals(0, found.get(), "an unsatisfiable search should not find anything");
    assertTrue(results.isEmpty());
  }

  /// The bound applies to the endsWith worker too, which has its own loop.
  @Test
  @Timeout(60)
  void maskWorkerAlsoHonoursMaxSearches() {
    final var endsWith = Subsequence.create("savasava", true, false, false);
    final var results = newResults();
    final var found = new AtomicInteger(0);

    new MaskWorker(
        null, null, new FixedSeedSecureRandom(1L), null, null, null, false,
        null, endsWith, 1, found, new AtomicLong(0), results, 16, 500
    ).run();

    assertEquals(0, found.get());
    assertTrue(results.isEmpty());
  }

  /// `searched` is what the CLI divides into elapsed time to report keys/sec, so
  /// it has to be the real count. Running an unsatisfiable search to a known cap
  /// makes the expected total exact.
  @Test
  @Timeout(60)
  void searchedCountsEveryGeneratedKeyExactlyOnce() {
    final var beginsWith = Subsequence.create("savasava", true, false, false);
    // a cap that is not a multiple of checkFound, so the tail is counted too
    for (final int checkFound : new int[]{1, 8, 16}) {
      final var searched = new AtomicLong(0);
      beginsWithWorker(1L, beginsWith, newResults(), new AtomicInteger(0), searched, checkFound, 500).run();
      assertEquals(500, searched.get(), "checkFound=" + checkFound);
    }
  }

  /// sigVerify runs the found key pair through both the internal and the JCE
  /// verifier; it must agree on a key the worker just produced.
  @Test
  @Timeout(60)
  void sigVerifyAcceptsAGeneratedKeyPair() {
    final var results = newResults();
    final var worker = new BeginsWithMaskWorker(
        null, null, new FixedSeedSecureRandom(1L), null, null, null, true,
        null, 1, new AtomicInteger(0), new AtomicLong(0), results, CHECK_FOUND, UNBOUNDED);

    assertDoesNotThrow(worker::run);
    assertNotNull(results.poll());
  }
}
