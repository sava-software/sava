package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// A Ping still in flight when a terminal notice retires its transport is failed by the
/// retirement itself: `retireConnection` detaches the connection, then aborts the socket,
/// and an abort fails whatever the socket still had pending. The failure is teardown noise
/// on an already superseded connection, so `recordFailedPing` must not record it.
///
/// The record would not stay unread. Every terminal-notice path calls
/// `takePingFailure(retired)` right after the abort, and a recorded failure takes over the
/// notice: the error policy and `onPingError` receive the abort's exception in place of
/// the transport's own error, or in place of the close policy. The fixture's `abortAction`
/// fails the pending Ping synchronously inside `abort()`, the earliest point a real
/// transport's completion can land in that window.
@ExtendWith(QuietWsLogging.class)
final class TeardownPingFailureTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);

  private static SolanaJsonRpcWebsocket websocket(final TestClock clock, final List<String> events) {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        clock,
        new RecordingExecutor(),
        null,
        null,
        (_, code, reason) -> events.add("close:" + code + ':' + reason),
        (_, error) -> events.add("error:" + error.getMessage()),
        null,
        (_, error) -> events.add("ping:" + error.getMessage())
    );
  }

  /// Opens a transport and leaves one Ping outstanding on it, whose future the retirement's
  /// abort fails.
  private static RecordingWebSocket openWithAPendingPing(final SolanaJsonRpcWebsocket ws,
                                                         final TestClock clock) throws InterruptedException {
    final var socket = new RecordingWebSocket();
    socket.deferPings = true;
    ws.onOpen(socket);
    clock.advanceMillis(TIMINGS.pingDelay() + 1);
    ws.checkCycle(0L);
    assertEquals(1, socket.deferredPings.size(), "one Ping is outstanding");
    socket.abortAction = () -> socket.deferredPings.getFirst()
        .completeExceptionally(new IOException("ping cut by abort"));
    return socket;
  }

  @Test
  void aPingFailedByTheErrorRetirementsAbortLeavesTheTransportErrorReported() throws InterruptedException {
    final var events = new ArrayList<String>();
    final var clock = new TestClock();
    try (final var ws = websocket(clock, events)) {
      final var socket = openWithAPendingPing(ws, clock);

      ws.onError(socket, new IOException("transport reset"));

      assertTrue(socket.aborted);
      assertTrue(socket.deferredPings.getFirst().isCompletedExceptionally(), "the abort failed the Ping");
      assertEquals(List.of("error:transport reset"), events,
          "the transport's own error owns the notice; the abort's Ping failure is noise");
      ws.checkCycle(0L);
      assertEquals(List.of("error:transport reset"), events, "nothing is reported later either");
    }
  }

  @Test
  void aPingFailedByTheCloseRetirementsAbortLeavesTheClosePolicyRunning() throws InterruptedException {
    final var events = new ArrayList<String>();
    final var clock = new TestClock();
    try (final var ws = websocket(clock, events)) {
      final var socket = openWithAPendingPing(ws, clock);

      ws.onClose(socket, 1001, "going away");

      assertTrue(socket.aborted);
      assertTrue(socket.deferredPings.getFirst().isCompletedExceptionally(), "the abort failed the Ping");
      assertEquals(List.of("close:1001:going away"), events,
          "the peer's close owns the notice; the abort's Ping failure is noise");
      ws.checkCycle(0L);
      assertEquals(List.of("close:1001:going away"), events, "nothing is reported later either");
    }
  }
}
