package software.sava.rpc.json.http.ws;

/// Time source for the websocket engine; [#SYSTEM] reads the system clocks.
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

  /// Millisecond reading for wall-clock age comparisons; the monotonic [#nanoTime()] is for
  /// pacing. Epoch millis only from [#SYSTEM]: the default derives from [#nanoTime()], so its
  /// values are comparable to each other but are not an epoch.
  default long currentTimeMillis() {
    return nanoTime() / 1_000_000L;
  }

  void sleep(final long millis) throws InterruptedException;
}
