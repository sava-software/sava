package software.sava.core.accounts.vanity;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/// The consumer side of a search: when [VanityAddressGenerator#poll] and
/// [VanityAddressGenerator#take] may wait for a worker, and when they must not.
///
/// The generator is built directly over a queue that records every call to its blocking
/// methods and answers them without blocking. Whether a call *would* have waited is then an
/// assertion on that record rather than a measurement of elapsed time, so these tests use no
/// clock and cannot be killed by a timeout.
final class ConcurrentVanityAddressGeneratorTests {

  /// Answers the blocking calls immediately from what is queued, and records each one with
  /// the timeout it was given.
  private static final class RecordingQueue extends ArrayBlockingQueue<Result> {

    private int blockingPolls;
    private long lastTimeout = -1;
    private TimeUnit lastUnit;
    private int blockingTakes;

    RecordingQueue() {
      super(4);
    }

    @Override
    public Result poll(final long timeout, final TimeUnit unit) {
      ++blockingPolls;
      lastTimeout = timeout;
      lastUnit = unit;
      return super.poll();
    }

    @Override
    public Result take() {
      ++blockingTakes;
      return super.poll();
    }
  }

  private static Result result(final int fill) {
    final var keyPair = new byte[64];
    Arrays.fill(keyPair, (byte) fill);
    return new Result(PublicKey.readPubKey(keyPair, 32), keyPair, 17L);
  }

  private static ConcurrentVanityAddressGenerator generator(final long findKeys,
                                                            final RecordingQueue results,
                                                            final int found) {
    return new ConcurrentVanityAddressGenerator(findKeys, results, new AtomicInteger(found), new AtomicLong(0));
  }

  /// Once the search has all the keys it was asked for, no worker will queue another, so an
  /// empty queue is final: `poll` must answer `null` at once rather than wait out its timeout.
  /// The found count sits exactly at `findKeys`, the first count that means "done".
  @Test
  void aFinishedSearchWithNothingQueuedAnswersNullWithoutWaiting() throws InterruptedException {
    final var results = new RecordingQueue();
    final var generator = generator(2, results, 2);

    assertNull(generator.poll(1, TimeUnit.HOURS));
    assertEquals(0, results.blockingPolls, "a finished, drained search must not wait for a result");
  }

  /// A finished search still hands back whatever its workers queued before it finished.
  @Test
  void aFinishedSearchHandsBackWhatIsQueued() throws InterruptedException {
    final var results = new RecordingQueue();
    final var queued = result(3);
    results.add(queued);
    final var generator = generator(2, results, 2);

    assertSame(queued, generator.poll(5, TimeUnit.SECONDS));
    assertNull(generator.poll(5, TimeUnit.SECONDS));
    assertEquals(1, results.blockingPolls, "only the call that found a result may reach the queue's poll");
  }

  /// While fewer than `findKeys` keys have been found a worker may still queue one, so `poll`
  /// waits on the queue with the caller's timeout.
  @Test
  void anUnfinishedSearchWaitsWithTheCallersTimeout() throws InterruptedException {
    final var results = new RecordingQueue();
    final var generator = generator(2, results, 1);

    assertNull(generator.poll(7, TimeUnit.MILLISECONDS));
    assertEquals(1, results.blockingPolls, "an unfinished search must wait for the next result");
    assertEquals(7, results.lastTimeout);
    assertEquals(TimeUnit.MILLISECONDS, results.lastUnit);

    final var queued = result(4);
    results.add(queued);
    assertSame(queued, generator.poll(7, TimeUnit.MILLISECONDS));
  }

  /// `take` hands back the next queued result and, per its javadoc, answers `null` instead of
  /// waiting when nothing is queued.
  @Test
  void takeReturnsTheQueuedResultAndNullWithoutWaitingWhenEmpty() throws InterruptedException {
    final var results = new RecordingQueue();
    final var generator = generator(2, results, 0);

    assertNull(generator.take());
    assertEquals(0, results.blockingTakes, "take must not wait on an empty queue");

    final var queued = result(5);
    results.add(queued);
    assertSame(queued, generator.take());
    assertEquals(1, results.blockingTakes);
    assertNull(generator.take());
  }

  /// The counters read through to the shared atomics the workers update.
  @Test
  void countersReadTheSharedAtomics() {
    final var found = new AtomicInteger(7);
    final var searched = new AtomicLong(1_234_567_890_123L);
    final var generator = new ConcurrentVanityAddressGenerator(9, new RecordingQueue(), found, searched);

    assertEquals(7, generator.numFound());
    assertEquals(1_234_567_890_123L, generator.numSearched());

    found.set(8);
    searched.set(42);
    assertEquals(8, generator.numFound());
    assertEquals(42, generator.numSearched());
  }

  /// `breakOut` raises the shared found count past any `findKeys`, which is the signal every
  /// worker polls to stop, and from then on an empty queue no longer makes `poll` wait.
  @Test
  void breakOutMarksTheSearchFinished() throws InterruptedException {
    final var results = new RecordingQueue();
    final var found = new AtomicInteger(1);
    final var generator = new ConcurrentVanityAddressGenerator(3, results, found, new AtomicLong(0));

    generator.breakOut();

    assertEquals(Integer.MAX_VALUE, found.get());
    assertEquals(Integer.MAX_VALUE, generator.numFound());
    assertNull(generator.poll(1, TimeUnit.HOURS));
    assertEquals(0, results.blockingPolls);
  }
}
