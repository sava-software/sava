package software.sava.rpc.soak.selftest;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/// The delegate under the harness builder: a copy of the shape of sava's package-private
/// `RecordingWebSocketBuilder` with one addition the self-test spec needs — `buildAsync` can
/// return a DEFERRED future the test completes later, so the order of "future settles" and
/// "onOpen arrives" is the test's choice (JDK 25 settles the future first; the forced #52
/// window needs onOpen first). With `invokeOnOpen` it delivers `onOpen(socket)` synchronously
/// from inside `buildAsync` the way a wrapping builder may, which is what nests an adoption
/// inside a user handler's `connect()`.
///
/// Every `buildAsync` captures the listener it was given — the harness listener wrapping
/// sava's attempt listener — because adoption routing is only testable end to end by invoking
/// the listener the BUILDER received. One `FakeWebSocket` is minted per build and handed to
/// `configureNext` before it can be used, so a socket created inside a nested reconnect can be
/// given its `failPing` before the engine's first maintenance pass touches it.
final class FakeWebSocketBuilder implements WebSocket.Builder {

  final List<WebSocket.Listener> listeners = new CopyOnWriteArrayList<>();
  final List<FakeWebSocket> sockets = new CopyOnWriteArrayList<>();
  final List<CompletableFuture<WebSocket>> futures = new CopyOnWriteArrayList<>();
  final List<URI> uris = new CopyOnWriteArrayList<>();
  final List<String> attemptHeaders = new CopyOnWriteArrayList<>();
  final List<Duration> connectTimeouts = new CopyOnWriteArrayList<>();
  final AtomicInteger builds = new AtomicInteger();
  volatile boolean deferFutures = true;
  volatile boolean uncancellableFutures;
  volatile boolean invokeOnOpen;
  volatile Runnable beforeReturn;
  volatile RuntimeException failBuild;
  volatile Consumer<FakeWebSocket> configureNext;

  /// A future whose `cancel` is refused: the real JDK future cannot be un-completed, and this
  /// is how a socket can still arrive after the harness future was cancelled.
  static final class UncancellableFuture extends CompletableFuture<WebSocket> {

    @Override
    public boolean cancel(final boolean mayInterruptIfRunning) {
      return false;
    }
  }

  @Override
  public WebSocket.Builder header(final String name, final String value) {
    if (name.equals("X-Soak-Attempt")) {
      attemptHeaders.add(value);
    }
    return this;
  }

  @Override
  public WebSocket.Builder connectTimeout(final Duration timeout) {
    connectTimeouts.add(timeout);
    return this;
  }

  @Override
  public WebSocket.Builder subprotocols(final String mostPreferred, final String... lesserPreferred) {
    return this;
  }

  @Override
  public CompletableFuture<WebSocket> buildAsync(final URI uri, final WebSocket.Listener listener) {
    final int index = builds.getAndIncrement();
    uris.add(uri);
    listeners.add(listener);
    final var socket = new FakeWebSocket(index);
    final var configure = configureNext;
    if (configure != null) {
      configure.accept(socket);
    }
    sockets.add(socket);
    final var fail = failBuild;
    if (fail != null) {
      throw fail;
    }
    final var before = beforeReturn;
    if (before != null) {
      before.run();
    }
    if (invokeOnOpen) {
      listener.onOpen(socket);
    }
    final CompletableFuture<WebSocket> future;
    if (deferFutures) {
      future = uncancellableFutures ? new UncancellableFuture() : new CompletableFuture<>();
    } else {
      future = CompletableFuture.completedFuture(socket);
    }
    futures.add(future);
    return future;
  }

  FakeWebSocket socket(final int index) {
    return sockets.get(index);
  }

  WebSocket.Listener listener(final int index) {
    return listeners.get(index);
  }

  /// Settles build `index` the way the JDK's handshake thread would, with its socket.
  boolean completeBuild(final int index) {
    return futures.get(index).complete(sockets.get(index));
  }

  boolean failBuild(final int index, final Throwable failure) {
    return futures.get(index).completeExceptionally(failure);
  }
}
