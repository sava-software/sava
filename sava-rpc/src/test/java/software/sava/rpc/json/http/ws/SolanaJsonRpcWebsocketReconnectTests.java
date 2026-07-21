package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/// Reconnect and resubscribe. `onOpen` is the whole recovery path: every live
/// subscription is re-queued and re-sent on the new connection, and the
/// subscription-id bookkeeping from the dead one is discarded. The existing
/// websocket tests drive notifications on a single connection; these drive the
/// connection lifecycle.
///
/// Determinism here is fiddly, because the class starts a background thread in its
/// constructor. Subscribing happens *before* the `onOpen` being asserted on:
/// `queueSubscription` only queues and signals, and of the two things that flush the
/// queue — that background thread and `onOpen` — only `onOpen` is synchronous with
/// the test. `reConnectDelay` doubles as a resend throttle
/// (`handlePendingSubscriptions` skips anything re-sent inside that window), so a
/// test that wants a resend has to step over it.
final class SolanaJsonRpcWebsocketReconnectTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  /// Large delays keep the background subscription/ping thread out of the way.
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);

  private static final PublicKey ACCOUNT_A =
      PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final PublicKey ACCOUNT_B =
      PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");

  private static SolanaJsonRpcWebsocket websocket(final Timings timings) {
    return websocket(timings, new TestClock(), null, null);
  }

  private static SolanaJsonRpcWebsocket websocket(final Timings timings,
                                                  final Consumer<SolanaRpcWebsocket> onOpen,
                                                  final BiConsumer<SolanaRpcWebsocket, Throwable> onError) {
    return websocket(timings, new TestClock(), onOpen, onError);
  }

  private static SolanaJsonRpcWebsocket websocket(final Timings timings,
                                                  final NanoClock clock,
                                                  final Consumer<SolanaRpcWebsocket> onOpen,
                                                  final BiConsumer<SolanaRpcWebsocket, Throwable> onError) {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        timings,
        clock,
        new RecordingExecutor(),
        onOpen,
        (_, _, _) -> {
        },
        onError,
        null,
        null
    );
  }

  /// Presence rather than an exact count: the background thread may also flush a
  /// pending subscription, so a duplicate send is legal and must not fail a test.
  private static void assertSent(final RecordingWebSocket socket, final String method, final String key) {
    assertTrue(
        socket.sentText.stream().anyMatch(m -> m.contains("\"method\":\"" + method + '"') && m.contains(key)),
        method + " for " + key + " not sent: " + socket.sentText);
  }

  private static void assertNotSent(final RecordingWebSocket socket, final String method) {
    assertTrue(socket.sentText.stream().noneMatch(m -> m.contains("\"method\":\"" + method + '"')),
        method + " should not have been sent: " + socket.sentText);
  }

  /// A subscription re-sent inside the reconnect window is skipped on that pass —
  /// the throttle exists so a flapping connection does not spam the node. The
  /// background thread retries it later.
  @Test
  void resendIsThrottledByReconnectDelay() {
    try (final var ws = websocket(TIMINGS)) {
      assertTrue(ws.slotSubscribe(_ -> {
      }));

      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      assertSent(first, "slotSubscribe", "");

      // immediately reconnecting is well inside the 60s window
      final var second = new RecordingWebSocket();
      ws.onOpen(second);
      assertNotSent(second, "slotSubscribe");
    }
  }

  /// The other side of the throttle: once the reconnect window has elapsed, an
  /// unconfirmed subscription is re-sent on the next check. An incoming ping drives
  /// that check synchronously; the clock steps over the window instead of waiting.
  @Test
  void unconfirmedSubscriptionIsResentOnceTheReconnectDelayElapses() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, clock, null, null)) {
      assertTrue(ws.slotSubscribe(_ -> {
      }));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertSent(socket, "slotSubscribe", "");
      final int sentOnOpen = socket.sentText.size();

      // still inside the window: the check must skip it
      ws.onPing(socket, ByteBuffer.allocate(0));
      assertEquals(sentOnOpen, socket.sentText.size(), "resend inside the window: " + socket.sentText);

      clock.advanceMillis(TIMINGS.reConnectDelay() + 1);
      ws.onPing(socket, ByteBuffer.allocate(0));
      assertEquals(sentOnOpen + 1, socket.sentText.size(),
          "the unconfirmed subscription should be re-sent after the window: " + socket.sentText);
      assertSent(socket, "slotSubscribe", "");
      // Deterministic now that the check loop runs on a RecordingExecutor and no
      // background thread exists: the re-send counts as the cycle's write.
      assertEquals(0, socket.pings, "a cycle that re-sent a subscription must not also ping");
    }
  }

  /// Ping pacing: a quiet connection is pinged only once `pingDelay` has elapsed
  /// since the last write, and a sent ping counts as that write — a second check
  /// inside the window must not ping again. The connection upgrade counts as the
  /// first write, so a brand-new connection is not pinged immediately (it used
  /// to be: `lastWrite`'s 0 origin read as infinitely stale).
  @Test
  void quietConnectionIsPingedOnlyAfterPingDelay() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, clock, null, null)) {
      // no subscriptions: every check is a pure ping decision
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(0, socket.pings, "opening the connection is the first write; no immediate ping");

      ws.onPing(socket, ByteBuffer.allocate(0));
      assertEquals(0, socket.pings, "still inside the window");

      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      ws.onPing(socket, ByteBuffer.allocate(0));
      assertEquals(1, socket.pings, "a quiet connection should be pinged after pingDelay");

      ws.onPing(socket, ByteBuffer.allocate(0));
      assertEquals(1, socket.pings, "the ping itself is a write; the window restarts from it");

      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      ws.onPing(socket, ByteBuffer.allocate(0));
      assertEquals(2, socket.pings, "pinging resumes once the window elapses again");
    }
  }

  /// Subscription ids belong to the connection that issued them. After a reconnect
  /// the mapping is gone, so a notification quoting a stale id is unknown — it must
  /// not reach a consumer, and the client unsubscribes it.
  @Test
  void staleSubscriptionIdsAreNotHonouredAfterReconnect() {
    try (final var ws = websocket(TIMINGS)) {
      final var notifications = new AtomicInteger();
      ws.accountSubscribe(ACCOUNT_A, _ -> notifications.incrementAndGet());

      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      // confirm it, so subId 4242 maps to the subscription on this connection
      final long msgId = subscribeMsgId(first);
      ws.onText(first, CharBuffer.wrap("{\"jsonrpc\":\"2.0\",\"result\":4242,\"id\":" + msgId + '}'), true);

      final var second = new RecordingWebSocket();
      ws.onOpen(second);

      ws.onText(second, CharBuffer.wrap("""
          {"jsonrpc":"2.0","method":"accountNotification","params":{"subscription":4242,\
          "result":{"context":{"slot":1},"value":{"data":["dGVzdA==","base64"],"executable":false,\
          "lamports":1,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":4}}}}"""), true);

      assertEquals(0, notifications.get(), "a stale subscription id must not dispatch");
      assertTrue(second.sentText.stream().anyMatch(m -> m.contains("accountUnsubscribe")),
          "an unknown subscription id should be unsubscribed: " + second.sentText);
    }
  }

  private static long subscribeMsgId(final RecordingWebSocket socket) {
    final var msg = socket.sentText.stream()
        .filter(m -> m.contains("\"method\":\"accountSubscribe\""))
        .findFirst()
        .orElseThrow();
    final int idStart = msg.indexOf("\"id\":") + 5;
    final int idEnd = msg.indexOf(',', idStart);
    return Long.parseLong(msg.substring(idStart, idEnd));
  }

  @Test
  void onOpenCallbackFiresOnEveryConnection() {
    final var opened = new AtomicReference<SolanaRpcWebsocket>();
    try (final var ws = websocket(TIMINGS, opened::set, null)) {
      ws.onOpen(new RecordingWebSocket());
      assertSame(ws, opened.get());

      // and again on reconnect, so a caller can re-prime state
      opened.set(null);
      ws.onOpen(new RecordingWebSocket());
      assertSame(ws, opened.get());
    }
  }

  @Test
  void onOpenWithoutACallbackStillFlushesSubscriptions() {
    try (final var ws = websocket(TIMINGS)) {
      assertTrue(ws.slotSubscribe(_ -> {
      }));
      final var socket = new RecordingWebSocket();
      assertDoesNotThrow(() -> ws.onOpen(socket));
      assertSent(socket, "slotSubscribe", "");
    }
  }

  /// Without an onError handler the client closes itself, which is what drives a
  /// reconnect. With one, the caller owns the decision.
  @Test
  void onErrorWithoutAHandlerClosesTheConnection() {
    final var ws = websocket(TIMINGS);
    ws.onOpen(new RecordingWebSocket());
    ws.onError(new RecordingWebSocket(), new IllegalStateException("boom"));
    assertTrue(ws.closed(), "an unhandled error should close the websocket");
  }

  @Test
  void onErrorWithAHandlerDelegatesAndLeavesTheConnectionOpen() {
    final var seen = new AtomicReference<Throwable>();
    try (final var ws = websocket(TIMINGS, null, (_, error) -> seen.set(error))) {
      ws.onOpen(new RecordingWebSocket());
      final var boom = new IllegalStateException("boom");
      ws.onError(new RecordingWebSocket(), boom);

      assertSame(boom, seen.get());
      assertFalse(ws.closed(), "the handler owns the decision to close");
    }
  }

  /// close() drops every subscription, so a later connection has nothing to resend.
  @Test
  void closeClearsSubscriptionsSoNothingIsResent() {
    final var ws = websocket(TIMINGS);
    ws.accountSubscribe(ACCOUNT_A, _ -> {
    });
    ws.slotSubscribe(_ -> {
    });
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    assertSent(socket, "accountSubscribe", ACCOUNT_A.toBase58());

    ws.close();
    assertTrue(ws.closed());
    assertFalse(socket.closeReasons.isEmpty(), "a close frame should be sent");

    final var afterClose = new RecordingWebSocket();
    ws.onOpen(afterClose);
    assertNotSent(afterClose, "accountSubscribe");
    assertNotSent(afterClose, "slotSubscribe");
  }

  /// An already-closed output must not be written to again.
  @Test
  void closeDoesNotWriteToAnAlreadyClosedOutput() {
    final var ws = websocket(TIMINGS);
    final var socket = new RecordingWebSocket();
    socket.outputClosed = true;
    ws.onOpen(socket);

    ws.close();
    assertTrue(socket.closeReasons.isEmpty(), "no close frame on an already closed output");
    assertTrue(ws.closed());
  }

  /// Nothing can be written before a connection exists, so a subscription made
  /// first has to survive until open.
  @Test
  void subscriptionsMadeBeforeConnectAreFlushedOnOpen() {
    try (final var ws = websocket(TIMINGS)) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertSent(socket, "accountSubscribe", ACCOUNT_A.toBase58());
    }
  }
}
