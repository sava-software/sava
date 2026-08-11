package software.sava.rpc.json.http.ws;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/// Captures what the client writes, so the listener path can be driven with no
/// network. `outputClosed` is settable because `close()` only sends a close frame
/// when the output is still open. `failText` / `failPing` make the returned
/// futures fail, driving the error-callback paths; `throwText` instead throws
/// synchronously from `sendText`, which the engine contains into the same failed
/// future the JDK would have returned. The attempt is still recorded in every case.
///
/// `deferPings` holds each ping's future open instead of settling it, so a test can choose when
/// a ping resolves relative to later cycles. That lets tests order send completion, peer contact,
/// deadline claims, and transport replacement without a scheduler race.
final class RecordingWebSocket implements WebSocket {

  final List<String> sentText = new ArrayList<>();
  final List<String> closeReasons = new ArrayList<>();
  int pings;
  long requested;
  boolean aborted;
  boolean outputClosed;
  Throwable failText;
  Throwable failPing;
  RuntimeException throwText;
  RuntimeException throwPing;
  /// Optional wrapping-socket behavior: report a terminal listener callback synchronously from
  /// abort(), exposing callers which invoke transport code while retaining their own lock.
  Runnable abortAction;
  /// Optional wrapping-socket behavior: observe the first polite teardown call synchronously.
  /// This is the boundary at which close() first hands control back to the transport.
  Runnable sendCloseAction;
  /// Optional wrapping-socket behavior: re-enter lifecycle code while demand is opened. The JDK
  /// implementation does not do this, but a public WebSocket wrapper may, and request() is a
  /// collaborator call made before adoption reports the connection to user code.
  Runnable requestAction;
  boolean deferPings;
  final List<CompletableFuture<WebSocket>> deferredPings = new ArrayList<>();
  /// Holds each text send's future open, so a test drives the one-outstanding-send chain
  /// deliberately: the next queued frame goes out only when the test settles its predecessor.
  boolean deferTexts;
  final List<CompletableFuture<WebSocket>> deferredTexts = new ArrayList<>();

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
    ++pings;
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
    if (sendCloseAction != null) {
      sendCloseAction.run();
    }
    return CompletableFuture.completedFuture(this);
  }

  /// Recorded rather than ignored: `onOpen`'s `request(Long.MAX_VALUE)` is what
  /// starts message delivery, and a stub that drops it on the floor makes its
  /// removal unobservable by accident of the fixture.
  @Override
  public void request(final long n) {
    requested += n;
    if (requestAction != null) {
      requestAction.run();
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
    aborted = true;
    // The JDK reports isOutputClosed() == true after abort(); without this, a close() after an
    // abort recorded a close frame on an aborted socket — a sequence the real JDK cannot produce.
    outputClosed = true;
    if (abortAction != null) {
      abortAction.run();
    }
  }
}
