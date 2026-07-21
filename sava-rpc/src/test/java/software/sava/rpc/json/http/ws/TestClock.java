package software.sava.rpc.json.http.ws;

/// Manually advanced [NanoClock]. Non-zero origin, so a "timestamp mutated to 0"
/// mutant is distinguishable from the fixture. Implements only the abstract
/// members: `currentTimeMillis()` goes through the interface default and advances
/// coherently with [#nanoTime()].
final class TestClock implements NanoClock {

  private long nanos = 1_234_567_890_123_456L;

  void advanceMillis(final long millis) {
    nanos += millis * 1_000_000L;
  }

  @Override
  public long nanoTime() {
    return nanos;
  }

  @Override
  public void sleep(final long millis) {
    advanceMillis(millis);
  }
}
