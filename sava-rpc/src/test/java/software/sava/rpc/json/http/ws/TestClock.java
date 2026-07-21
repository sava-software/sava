package software.sava.rpc.json.http.ws;

import java.util.concurrent.atomic.AtomicLong;

/// Manually advanced [NanoClock]. Non-zero origin, so a "timestamp mutated to 0"
/// mutant is distinguishable from the fixture. Implements only the abstract
/// members: `currentTimeMillis()` goes through the interface default and advances
/// coherently with [#nanoTime()]. Atomic rather than volatile: the test thread is
/// the usual writer, but [#sleep(long)] makes any thread under test a potential
/// second one, and `+=` on a volatile would lose one of the two advances.
final class TestClock implements NanoClock {

  private final AtomicLong nanos = new AtomicLong(1_234_567_890_123_456L);

  void advanceMillis(final long millis) {
    nanos.addAndGet(millis * 1_000_000L);
  }

  @Override
  public long nanoTime() {
    return nanos.get();
  }

  @Override
  public void sleep(final long millis) {
    advanceMillis(millis);
  }
}
