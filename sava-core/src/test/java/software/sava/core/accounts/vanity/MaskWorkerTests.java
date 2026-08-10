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

  /// Every satisfiable search below runs on a fixed seed, so its attempt count is an exact
  /// number rather than a draw: the worst is 529 (both ends constrained, seed 42) and the
  /// two-character tail takes 510. This cap is ~19x that, so it never fires on working code,
  /// while still bounding a worker whose match path a mutant has broken — under
  /// [Long#MAX_VALUE] such a worker spins until the mutation-testing watchdog kills the whole
  /// run, which reports a timeout instead of a failed assertion and tells us nothing.
  private static final long MAX_SEARCHES = 10_000L;

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
          beginsWithWorker(seed, beginsWith, results, new AtomicInteger(0), new AtomicLong(0), CHECK_FOUND, MAX_SEARCHES),
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
        beginsWithWorker(1L, beginsWith, firstResults, new AtomicInteger(0), firstSearched, 1, MAX_SEARCHES),
        firstResults);

    final var secondResults = newResults();
    final var secondSearched = new AtomicLong(0);
    final var second = runToResult(
        beginsWithWorker(1L, beginsWith, secondResults, new AtomicInteger(0), secondSearched, 1, MAX_SEARCHES),
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
          beginsWith, endsWith, 1, new AtomicInteger(0), new AtomicLong(0), results, CHECK_FOUND, MAX_SEARCHES
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
          null, endsWith, 1, new AtomicInteger(0), new AtomicLong(0), results, CHECK_FOUND, MAX_SEARCHES
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
        null, endsWith, 1, new AtomicInteger(0), new AtomicLong(0), results, CHECK_FOUND, MAX_SEARCHES
    ).run();
    final var result = results.poll();
    assertNotNull(result, "worker returned without queueing a result");
    final var address = result.publicKey().toBase58();
    assertEquals("zz", address.substring(address.length() - 2).toLowerCase(),
        "address did not end with a match: " + address);
  }

  /// A budget tight enough that exhausting it is itself the assertion.
  ///
  /// The other satisfiable searches here cap at [#MAX_SEARCHES], which is deliberately far
  /// above what they need: it stops a broken worker from spinning, but it is too loose to say
  /// anything about how hard the worker had to look. This one picks a cap barely above the
  /// real cost and asserts the search ended *early*, so it fails both ways — a worker that
  /// stops finding trips `assertNotNull`, and one that finds only by brute-forcing its way
  /// through the budget trips the `searched` bound.
  ///
  /// Both targets are one case-insensitive character, which the fixed seeds hit within a
  /// couple of attempts, so the cap below is ~30x the real cost and the test runs in
  /// milliseconds.
  @Test
  @Timeout(30)
  void aBrokenSearchExhaustsAFiniteBudgetInsteadOfFinding() {
    final long budget = 64L;

    final var endsWith = Subsequence.create("z", false, false, false);
    final var tailSearched = new AtomicLong(0);
    final var tailResults = newResults();
    new MaskWorker(
        null, null, new FixedSeedSecureRandom(FixedSeedSecureRandom.SEEDS[0]), null, null, null, false,
        null, endsWith, 1, new AtomicInteger(0), tailSearched, tailResults, CHECK_FOUND, budget
    ).run();
    final var tail = tailResults.poll();
    assertNotNull(tail, "a reachable tail was not found inside " + budget + " attempts");
    final var tailAddress = tail.publicKey().toBase58();
    assertTrue(tailAddress.endsWith("z") || tailAddress.endsWith("Z"), tailAddress);
    assertTrue(tailSearched.get() < budget,
        "the search exhausted its budget rather than finding: " + tailSearched.get());

    final var beginsWith = Subsequence.create("a", false, false, false);
    final var headSearched = new AtomicLong(0);
    final var headResults = newResults();
    beginsWithWorker(FixedSeedSecureRandom.SEEDS[0], beginsWith, headResults,
        new AtomicInteger(0), headSearched, CHECK_FOUND, budget).run();
    final var head = headResults.poll();
    assertNotNull(head, "a reachable prefix was not found inside " + budget + " attempts");
    final var headAddress = head.publicKey().toBase58();
    assertTrue(headAddress.startsWith("a") || headAddress.startsWith("A"), headAddress);
    assertTrue(headSearched.get() < budget,
        "the search exhausted its budget rather than finding: " + headSearched.get());
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
        null, 1, new AtomicInteger(0), new AtomicLong(0), results, CHECK_FOUND, MAX_SEARCHES);

    assertDoesNotThrow(worker::run);
    assertNotNull(results.poll());
  }
}
