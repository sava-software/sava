package software.sava.rpc.json.http.ws;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/// Captures what the builder configures without opening a connection. With a
/// `connectResult`, `buildAsync` completes with it and records the URI, so
/// `connect()` can be driven end to end; without one it fails, matching the
/// builder tests that never connect.
///
/// Every `buildAsync` call captures its listener — the engine hands each attempt its own
/// epoch-carrying listener, and adoption routing is only testable end to end by invoking the
/// listener the BUILDER received rather than calling the engine's own callbacks directly. With
/// `invokeOnOpen`, `buildAsync` delivers `onOpen(connectResult)` itself before completing, the
/// way the JDK does; without it, a test invokes `listeners.get(i).onOpen(...)` deliberately —
/// including late, after a newer attempt, which is the stale-adoption case.
final class RecordingWebSocketBuilder implements WebSocket.Builder {

  final java.util.List<WebSocket.Listener> listeners = new java.util.ArrayList<>();
  boolean invokeOnOpen;

  private final AtomicReference<Duration> connectTimeout;
  final AtomicReference<URI> builtUri = new AtomicReference<>();
  /// How many handshakes were initiated — the single-flight and closed-instance guards are
  /// assertions about this count, not about the last URI.
  int builds;
  private final WebSocket connectResult;

  RecordingWebSocketBuilder(final AtomicReference<Duration> connectTimeout) {
    this(connectTimeout, null);
  }

  RecordingWebSocketBuilder(final AtomicReference<Duration> connectTimeout, final WebSocket connectResult) {
    this.connectTimeout = connectTimeout;
    this.connectResult = connectResult;
  }

  @Override
  public WebSocket.Builder header(final String name, final String value) {
    return this;
  }

  @Override
  public WebSocket.Builder connectTimeout(final Duration timeout) {
    connectTimeout.set(timeout);
    return this;
  }

  @Override
  public WebSocket.Builder subprotocols(final String mostPreferred, final String... lesserPreferred) {
    return this;
  }

  @Override
  public CompletableFuture<WebSocket> buildAsync(final URI uri, final WebSocket.Listener listener) {
    ++builds;
    builtUri.set(uri);
    listeners.add(listener);
    if (connectResult == null) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException("no connection in tests"));
    }
    if (invokeOnOpen) {
      listener.onOpen(connectResult);
    }
    return CompletableFuture.completedFuture(connectResult);
  }
}
