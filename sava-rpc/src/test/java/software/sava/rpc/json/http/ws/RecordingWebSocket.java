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
/// synchronously from `sendText`, driving the check loop's unhandled-exception
/// funnel. The attempt is still recorded in every case.
final class RecordingWebSocket implements WebSocket {

  final List<String> sentText = new ArrayList<>();
  final List<String> closeReasons = new ArrayList<>();
  int pings;
  boolean aborted;
  boolean outputClosed;
  Throwable failText;
  Throwable failPing;
  RuntimeException throwText;

  @Override
  public CompletableFuture<WebSocket> sendText(final CharSequence data, final boolean last) {
    sentText.add(data.toString());
    if (throwText != null) {
      throw throwText;
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
    return CompletableFuture.completedFuture(this);
  }

  @Override
  public void request(final long n) {
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
  }
}
