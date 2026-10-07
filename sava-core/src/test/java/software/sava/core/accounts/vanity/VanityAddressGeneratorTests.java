package software.sava.core.accounts.vanity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.jupiter.api.Assertions.*;

/// Drives [VanityAddressGenerator#createGenerator] end to end on a fixed seed.
///
/// The executor runs each worker inline on the test thread the moment it is submitted, so the
/// whole search is over by the time `createGenerator` returns: no threads, no waiting, and any
/// exception a worker throws (a full queue, a broken attempt cap tripping the draw budget)
/// fails the test directly. Every search is bounded by a finite `maxSearches` and every
/// entropy source by a draw budget above it.
///
/// Results are checked by re-deriving them, never against golden addresses: the public key is
/// recomputed from the returned private half and the mask is checked on its full base58 form.
final class VanityAddressGeneratorTests {

  private static final int CHECK_FOUND = 4;

  /// Caps the satisfiable searches; far above what the fixed seeds need, so it only stops a
  /// worker whose match path is broken.
  private static final long MAX_SEARCHES = 10_000L;

  /// Records every submitted worker and, when asked to, runs it before returning.
  private static final class InlineExecutor extends AbstractExecutorService {

    private final boolean runWorkers;
    private final List<Runnable> submitted = new ArrayList<>();

    InlineExecutor(final boolean runWorkers) {
      this.runWorkers = runWorkers;
    }

    @Override
    public void execute(final Runnable command) {
      submitted.add(command);
      if (runWorkers) {
        command.run();
      }
    }

    @Override
    public void shutdown() {
    }

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) {
      return true;
    }
  }

  /// Hands each worker its own fixed seed, in order, with a draw budget.
  private static final class SeededFactory implements SecureRandomFactory {

    private final long maxDraws;
    private final List<FixedSeedSecureRandom> created = new ArrayList<>();

    SeededFactory(final long maxDraws) {
      this.maxDraws = maxDraws;
    }

    @Override
    public SecureRandom createSecureRandom() {
      final var secureRandom = new FixedSeedSecureRandom(FixedSeedSecureRandom.SEEDS[created.size()], maxDraws);
      created.add(secureRandom);
      return secureRandom;
    }

    long draws() {
      long draws = 0;
      for (final var secureRandom : created) {
        draws += secureRandom.draws();
      }
      return draws;
    }
  }

  private static VanityAddressGenerator create(final SeededFactory factory,
                                               final InlineExecutor executor,
                                               final int numThreads,
                                               final Subsequence beginsWith,
                                               final Subsequence endsWith,
                                               final long findKeys,
                                               final long maxSearches) {
    return VanityAddressGenerator.createGenerator(
        null, null, factory, null, null, null, false,
        executor, numThreads, beginsWith, endsWith, findKeys, CHECK_FOUND, maxSearches
    );
  }

  /// Polls every queued result off a generator whose search is over, then checks it is empty.
  private static List<Result> drain(final VanityAddressGenerator generator) throws InterruptedException {
    final var drained = new ArrayList<Result>();
    for (Result result; (result = generator.poll(0, NANOSECONDS)) != null; ) {
      drained.add(result);
    }
    assertNull(generator.take(), "a drained generator must have nothing left to take");
    return drained;
  }

  /// The result is a genuine key pair: its public half is the key derived from its private
  /// half, and [Result#publicKey] is that key. The search ran inside this test, so its
  /// duration is non-negative and far below a day.
  private static String assertGenuine(final Result result) {
    final var keyPair = result.keyPair();
    assertEquals(64, keyPair.length);
    final var derived = Signer.createFromPrivateKey(Arrays.copyOfRange(keyPair, 0, 32)).publicKey();
    assertEquals(derived, result.publicKey());
    assertEquals(PublicKey.readPubKey(keyPair, 32), result.publicKey());
    assertTrue(result.durationMillis() >= 0 && result.durationMillis() < Duration.ofDays(1).toMillis(),
        "implausible search duration: " + result.durationMillis());
    return result.publicKey().toBase58();
  }

  /// One worker, a reachable prefix and a finite cap: the search queues exactly `findKeys`
  /// distinct results, each beginning with the mask, and the generator's found count says so.
  ///
  /// `numSearched` is every key the workers generated, matches included: the draws the seeded
  /// sources made are the exact count. The CLI prints it as the low end of
  /// `[numSearched, numSearched + numThreads * checkFound)`, the width being the stretch a
  /// still-running worker has not flushed yet.
  @Test
  @Timeout(60)
  void aBoundedSearchQueuesFindKeysResultsThatMatchThePrefix() throws InterruptedException {
    final var factory = new SeededFactory(2 * MAX_SEARCHES);
    final var executor = new InlineExecutor(true);
    final var beginsWith = Subsequence.create("a", false, false, false);

    final var generator = create(factory, executor, 1, beginsWith, null, 3, MAX_SEARCHES);

    assertEquals(1, executor.submitted.size());
    final var worker = assertInstanceOf(BeginsWithMaskWorker.class, executor.submitted.getFirst());
    assertSame(factory.created.getFirst(), worker.secureRandom());
    assertEquals(3, generator.numFound());
    assertEquals(3, worker.found().get());

    final var results = drain(generator);
    assertEquals(3, results.size());
    final var addresses = new HashSet<String>();
    for (final var result : results) {
      final var address = assertGenuine(result);
      assertTrue(address.startsWith("a") || address.startsWith("A"), address);
      addresses.add(address);
    }
    assertEquals(3, addresses.size(), "the search queued the same key twice");

    assertEquals(factory.draws(), generator.numSearched(), "every generated key, the matches included");
  }

  /// An `endsWith` mask selects the tail worker, and every result ends with the mask;
  /// `numSearched` is every generated key, as for the prefix worker.
  @Test
  @Timeout(60)
  void anEndsWithSearchQueuesResultsThatMatchTheTail() throws InterruptedException {
    final var factory = new SeededFactory(2 * MAX_SEARCHES);
    final var executor = new InlineExecutor(true);
    final var endsWith = Subsequence.create("z", false, false, false);

    final var generator = create(factory, executor, 1, null, endsWith, 2, MAX_SEARCHES);

    assertInstanceOf(MaskWorker.class, executor.submitted.getFirst());
    assertEquals(2, generator.numFound());
    final var results = drain(generator);
    assertEquals(2, results.size());
    for (final var result : results) {
      final var address = assertGenuine(result);
      assertTrue(address.endsWith("z") || address.endsWith("Z"), address);
    }
    assertEquals(factory.draws(), generator.numSearched(), "every generated key, the matches included");
  }

  /// Every thread gets its own worker and its own entropy source, and a worker that reaches
  /// `maxSearches` stops queueing, so fewer than `findKeys` results arrive, as the overload's
  /// javadoc says. With no mask every key matches, which makes the count exact: each of the
  /// workers queues one result per attempt, all of which the queue must hold undrained.
  @Test
  @Timeout(60)
  void aCappedSearchStopsShortOfFindKeysOnEveryWorker() throws InterruptedException {
    final var factory = new SeededFactory(4);
    final var executor = new InlineExecutor(true);

    final var generator = create(factory, executor, 2, null, null, 5, 2);

    assertEquals(2, executor.submitted.size());
    assertEquals(2, factory.created.size());
    assertNotSame(factory.created.get(0), factory.created.get(1));
    for (int i = 0; i < 2; ++i) {
      final var worker = assertInstanceOf(BeginsWithMaskWorker.class, executor.submitted.get(i));
      assertSame(factory.created.get(i), worker.secureRandom());
      assertEquals(2, factory.created.get(i).draws());
    }
    assertEquals(4, generator.numFound());

    final var results = drain(generator);
    assertEquals(4, results.size());
    final var addresses = new HashSet<String>();
    for (final var result : results) {
      addresses.add(assertGenuine(result));
    }
    assertEquals(4, addresses.size(), "the workers queued the same key twice");
  }

  /// `findKeys` is an `int` count in a `long` parameter; one past [Integer#MAX_VALUE] is
  /// rejected before any worker is created or started.
  @Test
  void findKeysAboveIntegerMaxIsRejectedBeforeAnyWorkerStarts() {
    final var factory = new SeededFactory(0);
    final var executor = new InlineExecutor(true);

    assertThrows(IllegalArgumentException.class,
        () -> create(factory, executor, 1, null, null, Integer.MAX_VALUE + 1L, 1));
    assertTrue(factory.created.isEmpty());
    assertTrue(executor.submitted.isEmpty());
  }

  /// [Integer#MAX_VALUE] itself is a legal `findKeys`. The mask is unsatisfiable, so the worker
  /// runs to its cap, finds nothing, and counts every key it generated exactly once.
  @Test
  @Timeout(60)
  void findKeysOfIntegerMaxIsAccepted() throws InterruptedException {
    final var factory = new SeededFactory(32);
    final var executor = new InlineExecutor(true);
    final var beginsWith = Subsequence.create("savasava", true, false, false);

    final var generator = create(factory, executor, 1, beginsWith, null, Integer.MAX_VALUE, 16);

    assertNotNull(generator);
    assertEquals(0, generator.numFound());
    assertEquals(16, factory.draws());
    assertEquals(16, generator.numSearched());
    assertTrue(drain(generator).isEmpty());
  }

  /// The overload without `maxSearches` searches uncapped, so it still ends only by finding.
  @Test
  @Timeout(60)
  void theUncappedOverloadEndsOnceFindKeysAreFound() throws InterruptedException {
    final var factory = new SeededFactory(4);
    final var executor = new InlineExecutor(true);

    final var generator = VanityAddressGenerator.createGenerator(
        null, null, factory, null, null, null, false,
        executor, 1, null, null, 1, CHECK_FOUND
    );

    assertNotNull(generator);
    assertEquals(1, generator.numFound());
    assertEquals(1, factory.draws());
    final var results = drain(generator);
    assertEquals(1, results.size());
    assertGenuine(results.getFirst());
  }

  /// The overload without a factory draws from [SecureRandomFactory#DEFAULT], the platform's
  /// strong source, one instance per worker. The workers are recorded but not run, so nothing
  /// is drawn from it.
  @Test
  void theDefaultOverloadGivesEachWorkerAStrongSecureRandom() throws Exception {
    final var executor = new InlineExecutor(false);

    final var generator = VanityAddressGenerator.createGenerator(
        null, null, null, null, null, false,
        executor, 2, null, Subsequence.create("z", false, false, false), 1, CHECK_FOUND
    );

    assertNotNull(generator);
    assertEquals(0, generator.numFound());
    assertEquals(0, generator.numSearched());
    assertEquals(2, executor.submitted.size());
    final var strongAlgorithm = SecureRandom.getInstanceStrong().getAlgorithm();
    final var first = assertInstanceOf(MaskWorker.class, executor.submitted.get(0)).secureRandom();
    final var second = assertInstanceOf(MaskWorker.class, executor.submitted.get(1)).secureRandom();
    assertEquals(strongAlgorithm, first.getAlgorithm());
    assertEquals(strongAlgorithm, second.getAlgorithm());
    assertNotSame(first, second);
  }
}
