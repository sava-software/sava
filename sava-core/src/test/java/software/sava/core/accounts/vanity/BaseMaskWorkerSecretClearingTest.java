package software.sava.core.accounts.vanity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BaseMaskWorkerSecretClearingTest {

  private static byte[] secretField(final BaseMaskWorker worker, final String name) throws ReflectiveOperationException {
    final Field field = BaseMaskWorker.class.getDeclaredField(name);
    field.setAccessible(true);
    return (byte[]) field.get(worker);
  }

  @Test
  void secretsClearedAfterRun() throws ReflectiveOperationException {
    final var found = new AtomicInteger(0);
    final var searched = new AtomicLong(0);
    final var results = new ArrayBlockingQueue<Result>(1);

    // A null `beginsWith` matches the very first generated key, so the worker
    // finds its single key and terminates immediately.
    final var worker = new BeginsWithMaskWorker(
        null,
        null,
        new FixedSeedSecureRandom(FixedSeedSecureRandom.SEEDS[0]),
        null,
        null,
        null,
        false,
        null,
        1,
        found,
        searched,
        results,
        1,
        Long.MAX_VALUE
    );

    worker.run();

    assertEquals(1, found.get());
    assertEquals(1, results.size());

    // The per-worker scratch buffers holding secret material must be zeroed.
    assertArrayEquals(new byte[32], secretField(worker, "privateKey"));
    assertArrayEquals(new byte[64], secretField(worker, "mutableKeyPair"));
  }

  /// The tail worker has its own `run` and its own `finally`, so its buffers are checked on
  /// both ways a search ends: by finding a key and by reaching the attempt cap.
  @Test
  void maskWorkerSecretsClearedAfterFindingAndAfterExhaustingItsCap() throws ReflectiveOperationException {
    final var results = new ArrayBlockingQueue<Result>(1);
    final var finder = new MaskWorker(
        null, null, new FixedSeedSecureRandom(FixedSeedSecureRandom.SEEDS[0], 64), null, null, null, false,
        null, Subsequence.create("z", false, false, false),
        1, new AtomicInteger(0), new AtomicLong(0), results, 16, 64
    );
    finder.run();
    final var result = results.poll();
    assertNotNull(result, "a one-character tail is reached within the cap");
    // the seed buffer held this key's seed when the search ended
    assertFalse(Arrays.equals(new byte[32], Arrays.copyOf(result.keyPair(), 32)));
    assertArrayEquals(new byte[32], secretField(finder, "privateKey"));
    assertArrayEquals(new byte[64], secretField(finder, "mutableKeyPair"));

    final var unsatisfiable = Subsequence.create("savasava", true, false, false);
    final var entropy = new FixedSeedSecureRandom(FixedSeedSecureRandom.SEEDS[0], 32);
    final var exhausted = new MaskWorker(
        null, null, entropy, null, null, null, false,
        null, unsatisfiable, 1, new AtomicInteger(0), new AtomicLong(0), results, 16, 16
    );
    exhausted.run();
    assertTrue(results.isEmpty());
    assertEquals(16, entropy.draws(), "the search ended at its cap");
    assertArrayEquals(new byte[32], secretField(exhausted, "privateKey"));
    assertArrayEquals(new byte[64], secretField(exhausted, "mutableKeyPair"));
  }
}
