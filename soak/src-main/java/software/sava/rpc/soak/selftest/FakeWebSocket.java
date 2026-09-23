package software.sava.rpc.soak.selftest;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/// The raw transport under the harness wrapper: a copy of the shape of sava's package-private
/// `RecordingWebSocket` (which this module cannot import), with the additions the self-test
/// spec lists. It records what the engine writes so the listener path can be driven with no
/// network, and its knobs make failures happen at chosen moments: `failPing`/`throwPing` drive
/// the ping-failure pair, `deferPings`/`deferTexts` hold futures open so deadline claims are
/// ordered by the test rather than a scheduler, `requestAction` re-enters lifecycle code from
/// inside `adopt` (the #52 window), `abortAction` observes the abort synchronously.
///
/// Lists are copy-on-write because the threaded negative control reads them from the test
/// thread while a retry thread builds and writes.
final class FakeWebSocket implements WebSocket {

  final int index;
  final List<String> sentText = new CopyOnWriteArrayList<>();
  final List<String> closeReasons = new CopyOnWriteArrayList<>();
  final AtomicInteger pings = new AtomicInteger();
  final AtomicInteger abortCount = new AtomicInteger();
  volatile long requested;
  volatile boolean aborted;
  volatile boolean outputClosed;
  volatile Throwable failText;
  volatile Throwable failPing;
  volatile RuntimeException throwText;
  volatile RuntimeException throwPing;
  volatile Runnable abortAction;
  volatile Runnable sendCloseAction;
  volatile Runnable requestAction;
  volatile boolean deferPings;
  final List<CompletableFuture<WebSocket>> deferredPings = new CopyOnWriteArrayList<>();
  volatile boolean deferTexts;
  final List<CompletableFuture<WebSocket>> deferredTexts = new CopyOnWriteArrayList<>();

  FakeWebSocket(final int index) {
    this.index = index;
  }

  @Override
  public CompletableFuture<WebSocket> sendText(final CharSequence data, final boolean last) {
    sentText.add(data.toString());
    if (throwText != null) {
      throw throwText;
    }
    if (deferTexts) {
      final var deferred = new CompletableFuture<WebSocket>();
      deferredTexts.add(deferred);
      return deferred;
    }
    return failText == null
        ? CompletableFuture.completedFuture(this)
        : CompletableFuture.failedFuture(failText);
  }

  @Override
  public CompletableFuture<WebSocket> sendBinary(final ByteBuffer data, final boolean last) {
    return CompletableFuture.completedFuture(this);
  }

  @Override
  public CompletableFuture<WebSocket> sendPing(final ByteBuffer message) {
    pings.incrementAndGet();
    if (throwPing != null) {
      throw throwPing;
    }
    if (deferPings) {
      final var deferred = new CompletableFuture<WebSocket>();
      deferredPings.add(deferred);
      return deferred;
    }
    return failPing == null
        ? CompletableFuture.completedFuture(this)
        : CompletableFuture.failedFuture(failPing);
  }

  @Override
  public CompletableFuture<WebSocket> sendPong(final ByteBuffer message) {
    return CompletableFuture.completedFuture(this);
  }

  @Override
  public CompletableFuture<WebSocket> sendClose(final int statusCode, final String reason) {
    closeReasons.add(statusCode + ":" + reason);
    final var action = sendCloseAction;
    if (action != null) {
      action.run();
    }
    return CompletableFuture.completedFuture(this);
  }

  @Override
  public void request(final long n) {
    requested += n;
    final var action = requestAction;
    if (action != null) {
      action.run();
    }
  }

  @Override
  public String getSubprotocol() {
    return "";
  }

  @Override
  public boolean isOutputClosed() {
    return outputClosed;
  }

  @Override
  public boolean isInputClosed() {
    return false;
  }

  @Override
  public void abort() {
    abortCount.incrementAndGet();
    aborted = true;
    // The JDK reports isOutputClosed() == true after abort(); a close() after an abort must not
    // record a polite close frame on an aborted socket.
    outputClosed = true;
    final var action = abortAction;
    if (action != null) {
      action.run();
    }
  }
}
