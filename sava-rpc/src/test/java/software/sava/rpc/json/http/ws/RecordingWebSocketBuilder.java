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
final class RecordingWebSocketBuilder implements WebSocket.Builder {

  private final AtomicReference<Duration> connectTimeout;
  final AtomicReference<URI> builtUri = new AtomicReference<>();
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
    builtUri.set(uri);
    return connectResult == null
        ? CompletableFuture.failedFuture(new UnsupportedOperationException("no connection in tests"))
        : CompletableFuture.completedFuture(connectResult);
  }
}
