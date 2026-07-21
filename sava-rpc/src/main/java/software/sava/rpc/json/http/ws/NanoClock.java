package software.sava.rpc.json.http.ws;

/// Clock seam for time-dependent code, so tests advance time instead of waiting.
/// [SolanaRpcWebsocket.Builder#clock(NanoClock)] injects it; production code uses
/// [#SYSTEM]. Mirrors ravina's `software.sava.services.core.NanoClock`.
public interface NanoClock {

  NanoClock SYSTEM = new NanoClock() {
    @Override
    public long nanoTime() {
      return System.nanoTime();
    }

    @Override
    public long currentTimeMillis() {
      return System.currentTimeMillis();
    }

    @Override
    public void sleep(final long millis) throws InterruptedException {
      Thread.sleep(millis);
    }
  };

  long nanoTime();

  /// Millisecond reading for wall-clock age comparisons, as opposed to the
  /// monotonic [#nanoTime()] used for pacing.
  ///
  /// [#SYSTEM] overrides this with `System.currentTimeMillis()`, so values
  /// produced in production are epoch millis. The default derives from
  /// [#nanoTime()] instead, so a test clock that implements only `nanoTime()`
  /// still advances both readings coherently — give such a clock a non-zero
  /// origin. Consumers must treat these values as comparable to each other,
  /// not as an epoch, unless the clock is [#SYSTEM].
  default long currentTimeMillis() {
    return nanoTime() / 1_000_000L;
  }

  void sleep(final long millis) throws InterruptedException;
}
