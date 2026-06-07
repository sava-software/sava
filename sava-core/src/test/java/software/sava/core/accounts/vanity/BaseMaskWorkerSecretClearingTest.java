package software.sava.core.accounts.vanity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
        new SecureRandom(),
        null,
        null,
        null,
        false,
        null,
        1,
        found,
        searched,
        results,
        1
    );

    worker.run();

    assertEquals(1, found.get());
    assertEquals(1, results.size());

    // The per-worker scratch buffers holding secret material must be zeroed.
    assertArrayEquals(new byte[32], secretField(worker, "privateKey"));
    assertArrayEquals(new byte[64], secretField(worker, "mutableKeyPair"));
  }
}
