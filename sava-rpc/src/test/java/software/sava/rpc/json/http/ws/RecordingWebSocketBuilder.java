package software.sava.rpc.json.http.ws;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/// Captures what the builder configures without opening a connection.
/// `buildAsync` is never reached by these tests — the websocket only connects
/// when it is started.
final class RecordingWebSocketBuilder implements WebSocket.Builder {

  private final AtomicReference<Duration> connectTimeout;

  RecordingWebSocketBuilder(final AtomicReference<Duration> connectTimeout) {
    this.connectTimeout = connectTimeout;
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
    return CompletableFuture.failedFuture(new UnsupportedOperationException("no connection in tests"));
  }
}
