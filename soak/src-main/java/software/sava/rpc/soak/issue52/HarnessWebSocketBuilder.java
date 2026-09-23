package software.sava.rpc.soak.issue52;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static software.sava.rpc.soak.issue52.DeliveryFrames.FrameKind;

/// The `WebSocket.Builder` sava's prototype is given. sava calls `connectTimeout(...)` on it
/// once at `create()` and `buildAsync(uri, listener)` once per attempt; everything sava sets is
/// replayed onto a FRESH delegate per `buildAsync`, because headers accumulate on a shared JDK
/// builder and the attempt header below must carry this attempt's ordinal and no other.
///
/// Ordinal = the 1-based count of `buildAsync` calls on this tracker. The `X-Soak-Attempt`
/// header carries it to the controlled peer (never in live mode: a public provider gets no
/// harness headers), and the returned `HarnessBuildFuture(ordinal)` is the object sava cancels.
final class HarnessWebSocketBuilder implements WebSocket.Builder {

  private static final int MAX_HEADERS = 16;

  private final AttemptTracker tracker;
  private final Supplier<WebSocket.Builder> delegateFactory;
  private Duration connectTimeout;
  private final List<String[]> headers = new ArrayList<>();
  private String mostPreferredSubprotocol;
  private String[] lesserSubprotocols;

  HarnessWebSocketBuilder(final AttemptTracker tracker, final Supplier<WebSocket.Builder> delegateFactory) {
    this.tracker = tracker;
    this.delegateFactory = delegateFactory;
  }

  @Override
  public WebSocket.Builder header(final String name, final String value) {
    if (headers.size() < MAX_HEADERS) {
      headers.add(new String[]{name, value});
    } else {
      tracker.stats.headerOverflow.increment();
    }
    return this;
  }

  @Override
  public WebSocket.Builder connectTimeout(final Duration timeout) {
    this.connectTimeout = timeout;
    return this;
  }

  @Override
  public WebSocket.Builder subprotocols(final String mostPreferred, final String... lesserPreferred) {
    this.mostPreferredSubprotocol = mostPreferred;
    this.lesserSubprotocols = lesserPreferred;
    return this;
  }

  @Override
  public CompletableFuture<WebSocket> buildAsync(final URI uri, final WebSocket.Listener listener) {
    final long now = System.nanoTime();
    final var attempt = tracker.newAttempt(uri, listener, now);
    final var frames = DeliveryFrames.of();
    final var frame = frames.push(tracker, FrameKind.BUILD_ASYNC, attempt.ordinal, now);
    try {
      var delegate = delegateFactory.get();
      if (connectTimeout != null) {
        delegate = delegate.connectTimeout(connectTimeout);
      }
      for (final var header : headers) {
        delegate = delegate.header(header[0], header[1]);
      }
      if (mostPreferredSubprotocol != null) {
        delegate = delegate.subprotocols(mostPreferredSubprotocol, lesserSubprotocols);
      }
      if (!tracker.liveMode()) {
        delegate = delegate.header("X-Soak-Attempt", Long.toString(attempt.ordinal));
      }
      final var harnessListener = new HarnessListener(tracker, attempt, listener);
      final var future = new HarnessBuildFuture(tracker, attempt);
      attempt.install(harnessListener, future);
      // A wrapping delegate (the self-test fake) may deliver onOpen synchronously from inside
      // this call; the listener and future are installed on the attempt before it can.
      final var jdkFuture = delegate.buildAsync(uri, harnessListener);
      future.attach(jdkFuture);
      return future;
    } finally {
      frames.pop(frame);
      tracker.buildAsyncReturned(attempt);
    }
  }
}
