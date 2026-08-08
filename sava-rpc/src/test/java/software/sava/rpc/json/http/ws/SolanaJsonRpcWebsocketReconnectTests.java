package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
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
/// Every websocket here is built with a [RecordingExecutor], which captures the check
/// loop's task without running it — no thread exists, and every send is driven
/// synchronously by the test through `onOpen`, an inbound frame, or `checkCycle`.
/// Subscribing happens *before* the `onOpen` being asserted on: `queueSubscription`
/// only queues and signals, and `onOpen` is what flushes the queue. The resend
/// throttle (`Timings.subscriptionResendDelay`, which follows `reConnectDelay` here)
/// skips anything re-sent inside its window, so a test that wants a resend steps the
/// clock over it.
final class SolanaJsonRpcWebsocketReconnectTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  /// Large delays keep the background subscription/ping thread out of the way.
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);

  private static final PublicKey ACCOUNT_A =
      PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final PublicKey ACCOUNT_B =
      PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");

  private static SolanaJsonRpcWebsocket websocket(final Timings timings) {
    return websocket(timings, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(), null, null);
  }

  private static SolanaJsonRpcWebsocket websocket(final Timings timings,
                                                  final Consumer<SolanaRpcWebsocket> onOpen,
                                                  final BiConsumer<SolanaRpcWebsocket, Throwable> onError) {
    return websocket(timings, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(), onOpen, onError);
  }

  private static SolanaJsonRpcWebsocket websocket(final Timings timings,
                                                  final int maxMessageLength,
                                                  final NanoClock clock,
                                                  final Consumer<SolanaRpcWebsocket> onOpen,
                                                  final BiConsumer<SolanaRpcWebsocket, Throwable> onError) {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        timings,
        maxMessageLength,
        clock,
        new RecordingExecutor(),
        null,
        onOpen,
        (_, _, _) -> {
        },
        onError,
        null,
        null
    );
  }

  /// Presence rather than an exact count, so a test stays valid if a later write
  /// cycle legally repeats a frame; sends here are deterministic (see the class doc).
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
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.slotSubscribe(_ -> {
      }));

      final var socket = new RecordingWebSocket();
      // Failed on first attempt: a successfully sent request stays gated until the server
      // answers, so only a failed send exercises the same-connection retry this test paces.
      socket.failText = new java.io.IOException("the frame never left");
      ws.onOpen(socket);
      assertSent(socket, "slotSubscribe", "");
      socket.failText = null;
      final int sentOnOpen = socket.sentText.size();

      // still inside the window: the check must skip it
      ws.onPing(socket, ByteBuffer.allocate(0));
      assertEquals(sentOnOpen, socket.sentText.size(), "resend inside the window: " + socket.sentText);

      clock.advanceMillis(TIMINGS.reConnectDelay() + 1);
      ws.onPing(socket, ByteBuffer.allocate(0));
      assertEquals(sentOnOpen + 1, socket.sentText.size(),
          "the failed subscription send should retry after the window: " + socket.sentText);
      assertSent(socket, "slotSubscribe", "");
      // The ping is a question for the peer, and this end re-sending a subscription is not an
      // answer to it. Suppressing the ping on a cycle that wrote — which is what this used to
      // assert — made the ping unreachable on precisely the connections it exists to find:
      // nothing is ever confirmed on a half open socket, so its pending subscriptions re-send
      // every reConnectDelay forever and the ping was never due.
      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings, "a silent peer must be pinged even while a re-send is pending");
    }
  }

  /// A connection can be busy inbound and silent outbound indefinitely — a high traffic
  /// subscription with nothing left to subscribe. The peer is plainly there, so the liveness
  /// ping never falls due, but an intermediary ageing the connection on what it receives from
  /// *us* would drop one we are happily reading. The keepalive covers that, and deliberately
  /// runs slower than the liveness ping because nothing is actually wrong.
  @Test
  void aConnectionSilentOutboundIsKeptAliveEvenWhileThePeerTalks() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      // Step just inside the liveness window each time, with a message arriving before each
      // check, so the peer is never silent long enough for liveness to fire.
      final long step = TIMINGS.pingDelay() - 1;

      clock.advanceMillis(step);
      ws.onText(socket, java.nio.CharBuffer.wrap("{\"jsonrpc\":\"2.0\",\"result\":1,\"id\":2}"), true);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(0, socket.pings, "the peer is talking, so no liveness ping is due");

      clock.advanceMillis(step);
      ws.onText(socket, java.nio.CharBuffer.wrap("{\"jsonrpc\":\"2.0\",\"result\":2,\"id\":3}"), true);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      // Nearly two liveness windows of our own silence and still nothing: the keepalive is
      // deliberately slower, because a talking peer means nothing is actually wrong.
      assertEquals(0, socket.pings, "the keepalive must not run at the liveness rate");

      clock.advanceMillis(step);
      ws.onText(socket, java.nio.CharBuffer.wrap("{\"jsonrpc\":\"2.0\",\"result\":3,\"id\":4}"), true);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings,
          "a connection silent outbound must still poke the peer, however chatty the peer is");
    }
  }

  /// The keep-alive is a network-path property, not a detection deadline, so it is settable
  /// rather than derived. Given explicitly it is honoured as given; left alone it tracks the
  /// ping delay, so tuning only the detection deadline still moves it proportionately.
  @Test
  void theKeepAliveDelayIsSettableAndOtherwiseTracksThePingDelay() {
    assertEquals(
        30_000L * Timings.DEFAULT_KEEP_ALIVE_FACTOR,
        new Timings(3_000L, 30_000L, 2_000L).keepAliveDelay(),
        "an unset keep-alive must follow the ping delay it was not told about"
    );
    assertEquals(
        7_500L,
        new Timings(3_000L, 30_000L, 2_000L, 7_500L).keepAliveDelay(),
        "an explicit keep-alive must be honoured, including one shorter than the ping delay"
    );

    // and it is what the gate actually reads
    final var timings = new Timings(60_000L, 60_000L, 60_000L, 1_000L);
    final var clock = new TestClock();
    try (final var ws = websocket(timings, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      // well inside the liveness window, but past a keep-alive set far shorter than it
      clock.advanceMillis(1_001L);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings, "the configured keep-alive is what the gate reads");
    }
  }

  /// One outstanding ping at a time: the JDK permits a single pending control-frame send, so a
  /// second ping over a pending one failed every cycle with a misleading "pending" error while
  /// its rollback re-armed the gate — an error storm reporting the wrong cause. The guard
  /// suppresses the ask until the pending ping settles; a failure then rolls the clocks back so
  /// the retry is immediate rather than waiting out a fresh window.
  ///
  /// This replaces the overlapping-pings interleave test: the interleave it pinned is now
  /// impossible by construction, which is the point.
  @Test
  void aPendingPingSuppressesFurtherPingsUntilItSettles() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      final var socket = new RecordingWebSocket();
      socket.deferPings = true;
      ws.onOpen(socket);

      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings, "the first ask");

      // a full further window elapses, but the first ask has not settled
      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings, "one outstanding ping at a time");

      // settling it as a failure rolls the clocks back, so the retry is immediate
      socket.deferredPings.getFirst().completeExceptionally(new IllegalStateException("late failure"));
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(2, socket.pings, "a settled failure re-opens the gate without a fresh window");
    }
  }

  /// Both boundaries of the ping gate. Every other ping test steps `pingDelay + 1`, so none of
  /// them can tell `>` from `>=` on either clause — the silence window or the rate limit.
  @Test
  void thePingGateIsExclusiveOnBothItsBoundaries() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      // exactly pingDelay of silence is not yet longer than pingDelay
      clock.advanceMillis(TIMINGS.pingDelay());
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(0, socket.pings, "the silence window is exclusive at its boundary");

      clock.advanceMillis(1L);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings, "one millisecond past it, the peer is asked");

      // and the rate limit is exclusive at its own boundary: exactly pingDelay after the ask,
      // with the peer still silent, is not yet time to ask again
      clock.advanceMillis(TIMINGS.pingDelay());
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings, "the rate limit is exclusive at its boundary");

      clock.advanceMillis(1L);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(2, socket.pings, "one millisecond past it, the peer is asked again");
    }
  }

  /// The defect this pins, at the cadence it actually occurs at. A subscription which is never
  /// confirmed re-sends every reConnectDelay, and each re-send used to refresh the write stamp
  /// the ping gate read, so the gate never opened however long the peer stayed silent.
  @Test
  void aPermanentlyPendingSubscriptionDoesNotSuppressPinging() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.slotSubscribe(_ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      // Never confirm it, and run the check at its real cadence for well past the ping delay.
      final long checkDelay = TIMINGS.subscriptionAndPingCheckDelay();
      for (long elapsed = 0; elapsed < TIMINGS.pingDelay() * 4L; elapsed += checkDelay) {
        clock.advanceMillis(checkDelay);
        assertDoesNotThrow(() -> ws.checkCycle(0L));
      }

      assertTrue(socket.pings > 0, "a silent peer was never pinged while a subscription stayed pending");
      // and the successfully sent request was never duplicated: it stays gated until the
      // server answers, however many windows elapse
      assertEquals(1, socket.sentText.size(), "a sent-but-unanswered request must not be re-sent");
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
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      // no subscriptions: every check is a pure ping decision
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(0, socket.pings, "the handshake is the peer's first word; no immediate ping");

      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(0, socket.pings, "still inside the window");

      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings, "a quiet connection should be pinged after pingDelay");

      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings, "the ask was made; the window restarts from it");

      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(2, socket.pings, "pinging resumes once the window elapses again");

      // Anything from the peer answers the question the ping asks, so it restarts the window —
      // an inbound frame cannot be used to drive a check into pinging.
      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      ws.onPing(socket, ByteBuffer.allocate(0));
      assertEquals(2, socket.pings, "a frame from the peer is the answer; no ping is due");
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(2, socket.pings, "and the window it restarted has not elapsed");
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
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    ws.onError(socket, new IllegalStateException("boom"));
    assertTrue(ws.closed(), "an unhandled error should close the websocket");
  }

  @Test
  void onErrorWithAHandlerDelegatesAndLeavesTheConnectionOpen() {
    final var seen = new AtomicReference<Throwable>();
    try (final var ws = websocket(TIMINGS, null, (_, error) -> seen.set(error))) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      final var boom = new IllegalStateException("boom");
      ws.onError(socket, boom);

      assertSame(boom, seen.get());
      assertFalse(ws.closed(), "the handler owns the decision to close");
    }
  }

  /// close() drops every subscription, so a later connection has nothing to resend.
  /// The clock steps past the resend throttle first — inside the window nothing
  /// would be re-sent even from an uncleared map.
  @Test
  void closeClearsSubscriptionsSoNothingIsResent() {
    final var clock = new TestClock();
    final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null);
    ws.accountSubscribe(ACCOUNT_A, _ -> {
    });
    ws.slotSubscribe(_ -> {
    });
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    assertSent(socket, "accountSubscribe", ACCOUNT_A.toBase58());

    // Non-zero first: a probe only ever read at zero cannot tell addition from subtraction,
    // so the count is pinned while there is something to count — one account registration, the
    // slot singleton, and both pending entries.
    assertEquals(4, ws.retainedRegistrations());

    ws.close();
    assertTrue(ws.closed());
    assertFalse(socket.closeReasons.isEmpty(), "a close frame should be sent");

    // Asserted directly rather than through a reopen: onOpen now refuses to run on a closed
    // instance, so "nothing was re-sent afterwards" would hold whether or not close() cleared
    // anything — the assertion had gone vacuous, and ten teardown mutants outlived it.
    assertEquals(0, ws.retainedRegistrations(), "close() must forget every registration");

    clock.advanceMillis(TIMINGS.reConnectDelay() + 1);
    final var afterClose = new RecordingWebSocket();
    ws.onOpen(afterClose);
    assertTrue(afterClose.aborted, "a handshake completing after close is aborted");
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

  /// Agave cancels a signature subscription server-side once a processed notification has been
  /// sent; the local registry has to follow. Left registered, the completed signature was
  /// re-subscribed on every reconnect — replaying a terminal notification into a consumer that
  /// already acted on it, once per reconnect, forever.
  @Test
  void aTerminalSignatureNotificationRetiresTheSubscription() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      final var sig = "2nBhEBYYvfaAe16UMNqRHre4YNSskvuYgx3M6E4JP1oDYvZEJHvoPzyUidNgNX5r9sTyN1J9UxtbCXy2rqYcuyuv";
      assertTrue(ws.signatureSubscribe(sig, true, _ -> {
      }));
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      assertSent(first, "signatureSubscribe", sig);
      ws.onText(first, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":24006,"id":2}"""), true);
      ws.onText(first, CharBuffer.wrap("""
          {"jsonrpc":"2.0","method":"signatureNotification","params":{"result":{"context":{"slot":2},"value":{"err":null}},"subscription":24006}}"""), true);

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      final var second = new RecordingWebSocket();
      ws.onOpen(second);
      assertNotSent(second, "signatureSubscribe");

      // and the slot is genuinely free, not merely skipped
      assertTrue(ws.signatureSubscribe(sig, true, _ -> {
      }), "a completed signature's key must be subscribable again");
    }
  }

  /// A rejection is the request's terminal state. Without one, the entry re-sent every resend
  /// window for the life of the connection, and its channel slot stayed occupied — so the key
  /// could never be re-subscribed with corrected parameters.
  @Test
  void aRejectedSubscriptionStopsResendingAndFreesItsKey() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      final var errors = new java.util.ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertSent(socket, "accountSubscribe", ACCOUNT_A.toBase58());

      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params: unsupported encoding"},"id":2}"""), true);
      assertEquals(1, errors.size(), "the rejection still reaches the exception subscribers");
      final var rejection = assertInstanceOf(
          software.sava.rpc.json.http.response.JsonRpcException.class, errors.getFirst());
      assertTrue(rejection.retryAfterSeconds().isEmpty(),
          "the request id must not masquerade as a retry-after");

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      final long frames = socket.sentText.stream().filter(m -> m.contains("accountSubscribe")).count();
      assertEquals(1, frames, "a rejected subscription must not be re-sent");

      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }), "the rejected key must be free for a corrected subscribe");
    }
  }

  /// A subId names a subscription on the connection that issued it. Re-queuing for a new
  /// connection clears it, so an unsubscribe inside the resubscribe window does not put the old
  /// connection's subId on the wire — a frame the server reads as cancelling someone else's
  /// subscription.
  @Test
  void anUnsubscribeDuringResubscribeCarriesNoStaleSubId() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      ws.onText(first, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":700,"id":2}"""), true);

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      final var second = new RecordingWebSocket();
      ws.onOpen(second);
      assertSent(second, "accountSubscribe", ACCOUNT_A.toBase58());

      // unconfirmed on this connection: the unsubscribe has no subId to send, and must not
      // borrow the previous connection's
      assertTrue(ws.accountUnsubscribe(ACCOUNT_A));
      ws.onPong(second, ByteBuffer.wrap(new byte[0]));
      assertNotSent(second, "accountUnsubscribe");
    }
  }

  /// The superseded-socket guard on the control-frame and error callbacks. onText and onClose
  /// are pinned elsewhere; these three complete the set, each with its own observable: a stale
  /// ping or pong must not vouch for the peer, and a stale error must not close the live
  /// connection — this fixture installs no onError handler, so an unguarded error would close.
  @Test
  void aSupersededSocketsControlFramesAndErrorsAreIgnored() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      clock.advanceMillis(1_000L);
      final var second = new RecordingWebSocket();
      ws.onOpen(second);
      assertTrue(first.aborted, "the displaced socket is aborted at takeover");

      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      ws.onPing(first, ByteBuffer.wrap(new byte[0]));
      ws.onPong(first, ByteBuffer.wrap(new byte[0]));
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, second.pings,
          "a superseded socket's control frames must not vouch for the peer on the live one");

      ws.onError(first, new IllegalStateException("the abandoned socket finally noticed"));
      assertFalse(ws.closed(), "a superseded socket's error must not close the live connection");
    }
  }

  /// The terminal-rejection path per registry shape: the slot/root singletons and the generic
  /// method-keyed map release differently than the commitment-keyed channels, and each freed
  /// slot is proven by the re-subscribe returning true.
  @Test
  void aRejectionFreesEveryRegistryShape() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.slotSubscribe(_ -> {
      }));
      assertTrue(ws.rootSubscribe(_ -> {
      }));
      assertTrue(ws.subscribe("voteSubscribe", "voteUnsubscribe", "voteNotification",
          "vote", "", JsonIterator::readString, null, _ -> {
          }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      // msgIds 2, 3, 4 in subscription order — one request-defect code each, so every clause
      // of the classification is pinned: a clause silently dropping -32600 or -32601 would
      // leave that subscription registered and fail its re-subscribe below
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","error":{"code":-32600,"message":"Invalid request"},"id":2}"""), true);
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","error":{"code":-32601,"message":"Method not found"},"id":3}"""), true);
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params"},"id":4}"""), true);

      assertTrue(ws.slotSubscribe(_ -> {
      }), "a rejected slot subscription must free the singleton");
      assertTrue(ws.rootSubscribe(_ -> {
      }), "a rejected root subscription must free the singleton");
      assertTrue(ws.subscribe("voteSubscribe", "voteUnsubscribe", "voteNotification",
          "vote", "", JsonIterator::readString, null, _ -> {
          }), "a rejected generic subscription must free its method key");
    }
  }

  /// The remaining commitment-keyed shapes of the rejection path: logs, signature and program
  /// each release through their own switch arm.
  @Test
  void aRejectionFreesTheRemainingChannelShapes() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      final var sig = "2EBVM6cB8vAAD93Ktr6Vd8p67XPbQzCJX47MpReuiCXJAtcjaxpvWpcg9Ege1Nr5Tk3a2GFrByT7WPBjdsTycY9b";
      assertTrue(ws.logsSubscribe(ACCOUNT_A, _ -> {
      }));
      assertTrue(ws.signatureSubscribe(sig, _ -> {
      }));
      assertTrue(ws.programSubscribe(ACCOUNT_B, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      for (int id = 2; id <= 4; ++id) {
        ws.onText(socket, CharBuffer.wrap("""
            {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params"},"id":""" + id + "}"), true);
      }

      assertTrue(ws.logsSubscribe(ACCOUNT_A, _ -> {
      }), "a rejected logs subscription must free its key");
      assertTrue(ws.signatureSubscribe(sig, _ -> {
      }), "a rejected signature subscription must free its key");
      assertTrue(ws.programSubscribe(ACCOUNT_B, _ -> {
      }), "a rejected program subscription must free its key");
    }
  }

  /// The singleton and generic registries clear their subIds on re-queue too — the
  /// commitment-keyed half of this contract is pinned by
  /// [#anUnsubscribeDuringResubscribeCarriesNoStaleSubId].
  @Test
  void singletonAndGenericSubIdsAreClearedOnReconnect() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.slotSubscribe(_ -> {
      }));
      assertTrue(ws.rootSubscribe(_ -> {
      }));
      assertTrue(ws.subscribe("voteSubscribe", "voteUnsubscribe", "voteNotification",
          "vote", "", JsonIterator::readString, null, _ -> {
          }));
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      // msgIds 2, 3, 4 confirm as subIds 10, 11, 12
      for (int id = 2; id <= 4; ++id) {
        ws.onText(first, CharBuffer.wrap("""
            {"jsonrpc":"2.0","result":""" + (8 + id) + ",\"id\":" + id + "}"), true);
      }

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      final var second = new RecordingWebSocket();
      ws.onOpen(second);

      assertTrue(ws.slotUnsubscribe());
      assertTrue(ws.rootUnsubscribe());
      assertTrue(ws.unsubscribe("voteNotification", "vote"));
      ws.onPong(second, ByteBuffer.wrap(new byte[0]));
      assertNotSent(second, "slotUnsubscribe");
      assertNotSent(second, "rootUnsubscribe");
      assertNotSent(second, "voteUnsubscribe");
    }
  }

  /// A queued un-subscription names a subId on the connection that issued it, so it must not
  /// survive a reconnect: flushed onto the new connection it would cancel whatever subscription
  /// happens to hold that number there.
  @Test
  void aQueuedUnsubscriptionDoesNotSurviveReconnect() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.logsSubscribe(ACCOUNT_A, _ -> {
      }));
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      ws.onText(first, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":555,"id":2}"""), true);
      assertTrue(ws.logsUnsubscribe(ACCOUNT_A));
      // deliberately not flushed: the connection is about to be replaced

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      final var second = new RecordingWebSocket();
      ws.onOpen(second);
      ws.onPong(second, ByteBuffer.wrap(new byte[0]));
      assertNotSent(second, "logsUnsubscribe");
    }
  }

  /// Not every rejection is terminal. A request defect — invalid params, unknown method — can
  /// only collect the same answer again, so it retires the request. A refusal that describes
  /// the server's condition must not: Agave answers -32603 "Subscription refused" when its
  /// node-wide subscription limit is full, and before classification that transient refusal
  /// permanently retired the subscription with no way back short of re-subscribing by hand.
  @Test
  void aTransientRefusalKeepsTheSubscriptionPending() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      final var errors = new java.util.ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","error":{"code":-32603,"message":"Subscription refused. Node subscription limit reached"},"id":2}"""), true);
      assertEquals(1, errors.size(), "the refusal still reaches the exception subscribers");

      assertFalse(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }), "the subscription is still registered, so a duplicate is still a duplicate");

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      final long frames = socket.sentText.stream().filter(m -> m.contains("accountSubscribe")).count();
      assertEquals(2, frames, "a transient refusal is retried at the resend cadence");
    }
  }

  /// JSON-RPC 2.0 mandates "id":null when the server could not read the request at all — the
  /// parse-error class. Reading that as a number would throw, and the throw would abandon the
  /// whole error branch: no dispatch of the server's actual error, and for a correlatable
  /// rejection no release either. The frame below is the live answer, verbatim, to a
  /// quote-spliced signatureSubscribe (api.mainnet-beta.solana.com, 2026-08-09) — the exchange
  /// that keeps the frame-splice guard while the correlated -32602 retired the decode check.
  @Test
  void aNullIdErrorStillReachesTheExceptionSubscribers() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      final var errors = new java.util.ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      assertDoesNotThrow(() -> ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","error":{"code":-32700,"message":"Parse error"},"id":null}"""), true));

      assertEquals(1, errors.size(), "the server's error, not a number-parse failure, is dispatched");
      final var ex = assertInstanceOf(software.sava.rpc.json.http.response.JsonRpcException.class, errors.getFirst());
      assertEquals(-32_700L, ex.code());
      assertTrue(ex.retryAfterSeconds().isEmpty(),
          "nothing on this path knows a retry-after, and the request id must not masquerade as one");

      assertFalse(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }), "an uncorrelatable error must not release anything");
    }
  }

  /// The JDK permits one outstanding text send per connection; the rest fail with "Send
  /// pending". A reconnect's bulk re-subscribe is exactly a burst of text sends, so each waits
  /// for its predecessor to settle — and a failed predecessor must not dam the frames behind it.
  @Test
  void outboundTextSendsAreChainedOneAtATime() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      assertTrue(ws.accountSubscribe(ACCOUNT_B, _ -> {
      }));
      assertTrue(ws.slotSubscribe(_ -> {
      }));
      final var socket = new RecordingWebSocket();
      socket.deferTexts = true;
      ws.onOpen(socket);

      assertEquals(1, socket.sentText.size(), "one frame on the wire until its send settles");
      socket.deferredTexts.getFirst().complete(socket);
      assertEquals(2, socket.sentText.size(), "settling the first releases the second");
      socket.deferredTexts.get(1).completeExceptionally(new IOException("send failed"));
      assertEquals(3, socket.sentText.size(), "a failed predecessor must not dam the chain");
    }
  }

  /// A failed send re-arms the retry — a successful one stays gated until the server answers —
  /// and the retry is paced by the resend window: re-arming it immediately would hot-loop a
  /// growing chain of doomed frames on a broken socket, once per cycle and inbound frame.
  @Test
  void aFailedSendRetriesAfterTheResendWindow() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      socket.failText = new IOException("the frame never left");
      ws.onOpen(socket);
      assertEquals(1, socket.sentText.size());

      socket.failText = null;
      clock.advanceMillis(1L); // inside the window: the failure does not retry hot
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.sentText.size(),
          "a failed attempt retries at the resend cadence, not per cycle");

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(2, socket.sentText.size(), "the window elapsing re-arms the retry");

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(2, socket.sentText.size(),
          "the successful retry stays gated until the server answers");
    }
  }

  /// An un-subscription whose send fails is still owed: the entry returns to the queue and the
  /// next flush retries it, exactly once more.
  @Test
  void aFailedFlushRequeuesTheUnsubscription() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":700,"id":2}"""), true);
      assertTrue(ws.accountUnsubscribe(ACCOUNT_A));

      socket.failText = new IOException("the frame never left");
      ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
      final long attempted = socket.sentText.stream().filter(m -> m.contains("accountUnsubscribe")).count();
      assertEquals(1, attempted, "the flush attempted the frame");

      socket.failText = null;
      ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
      ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
      final long delivered = socket.sentText.stream().filter(m -> m.contains("accountUnsubscribe")).count();
      assertEquals(2, delivered, "retried exactly once more, then drained");
    }
  }

  /// Single-flight: while an attempt is unsettled, every connect() returns it. Stacked attempts
  /// meant the older handshake completing last displaced the newer live connection, and the
  /// JDK's WebSocket.Builder is not specified safe for concurrent buildAsync calls.
  @Test
  void connectIsSingleFlightWhileAnAttemptIsUnsettled() {
    final var clock = new TestClock();
    final var scheduler = new RecordingScheduler();
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new java.util.concurrent.atomic.AtomicReference<>(), socket);
    try (final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(java.time.Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock,
        new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        }, null, null, null)) {
      ws.onOpen(socket);
      // inside the window, so the attempt defers — and stays unsettled until its task runs
      final var first = ws.connect();
      assertNotNull(first);
      assertEquals(1, scheduler.deferred.size());

      // every caller gets a defensive copy of the ONE attempt: distinct futures, one handshake
      final var second = ws.connect();
      assertNotNull(second);
      assertNotSame(first, second, "callers receive copies; the attempt itself is never exposed");
      assertEquals(1, scheduler.deferred.size(), "no second handshake is scheduled");

      scheduler.deferred.getFirst().task().run();
      assertSame(socket, first.toCompletableFuture().join(), "both views settle with the one attempt");
      assertSame(socket, second.toCompletableFuture().join());
      assertEquals(1, webSocketBuilder.builds);
    }
  }

  /// A deferred attempt that outlives the instance must not initiate a handshake: the loop is
  /// gone, and nothing would ever run the connection it built.
  @Test
  void aDeferredConnectAfterCloseDoesNotBuild() {
    final var clock = new TestClock();
    final var scheduler = new RecordingScheduler();
    final var socket = new RecordingWebSocket();
    final var builtUri = new java.util.concurrent.atomic.AtomicReference<java.net.URI>();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new java.util.concurrent.atomic.AtomicReference<>(), socket);
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(java.time.Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock,
        new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        }, null, null, null);
    ws.onOpen(socket);
    final var deferred = ws.connect();
    assertNotNull(deferred);
    assertEquals(1, scheduler.deferred.size());

    ws.close();
    final int builtBefore = webSocketBuilder.builds;
    scheduler.deferred.getFirst().task().run();
    assertEquals(builtBefore, webSocketBuilder.builds, "a closed instance must not build a handshake");
    assertTrue(deferred.toCompletableFuture().isCompletedExceptionally(),
        "the caller holding the deferred attempt learns it was abandoned");
  }

  /// The resend gate's second clause: a send pending past the resend delay is queued behind a
  /// slow chain, not lost. Re-queuing it drained as a duplicate subscribe with the same message
  /// id — the server answered with a second subscription whose confirmation was then ignored,
  /// leaving an orphan feeding unknown-id traffic.
  @Test
  void aQueuedSendIsNotResentWhileItIsStillInFlight() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      socket.deferTexts = true;
      ws.onOpen(socket);
      assertEquals(1, socket.sentText.size());

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.sentText.size(), "in flight is not lost: no duplicate request");

      // once it settles as a failure, the retry is owed and immediate
      socket.deferredTexts.getFirst().completeExceptionally(new java.io.IOException("never left"));
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(2, socket.sentText.size(), "a settled failure re-arms the resend");
    }
  }

  /// A failed un-subscription is re-queued only onto the connection that owes it: the subId
  /// names a subscription on the connection that issued it, and re-queuing it after a takeover
  /// would send the dead connection's number down the replacement.
  @Test
  void aFailedFlushOnADisplacedSocketDoesNotRequeue() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.logsSubscribe(ACCOUNT_A, _ -> {
      }));
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      ws.onText(first, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":555,"id":2}"""), true);
      assertTrue(ws.logsUnsubscribe(ACCOUNT_A));

      first.deferTexts = true;
      ws.onPong(first, ByteBuffer.wrap(new byte[0])); // flush queues the frame, held open

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      final var second = new RecordingWebSocket();
      ws.onOpen(second);

      // the old connection's send now fails; its subId must not be re-queued for the new one
      first.deferredTexts.getFirst().completeExceptionally(new java.io.IOException("aborted"));
      ws.onPong(second, ByteBuffer.wrap(new byte[0]));
      assertNotSent(second, "logsUnsubscribe");
    }
  }

  /// Channel correlation: a malformed signatureNotification naming another channel's subId must
  /// not remove that channel's registration — before the check it terminally deleted the
  /// mapping, silencing a healthy subscription forever on the strength of one bad frame.
  @Test
  void aCrossChannelNotificationCannotRemoveAnotherChannelsSubscription() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      final var received = new java.util.ArrayList<software.sava.rpc.json.http.response.AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(ACCOUNT_A, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":700,"id":2}"""), true);

      // a signatureNotification carrying the ACCOUNT subscription's id
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","method":"signatureNotification","params":{"result":{"context":{"slot":2},"value":{"err":null}},"subscription":700}}"""), true);

      // the account subscription must still be registered and receiving
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":3},"value":{"lamports":1,"data":["","base64"],"owner":"11111111111111111111111111111111","executable":false,"rentEpoch":0,"space":0}},"subscription":700}}"""), true);
      assertEquals(1, received.size(), "the cross-channel frame must not have removed the mapping");
    }
  }

  /// A slot notification carrying a stale subscription id — a predecessor's, after an
  /// unsubscribe/resubscribe — must not reach the successor consumer.
  @Test
  void aStaleSlotNotificationDoesNotReachTheSuccessorConsumer() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      final var slots = new java.util.ArrayList<software.sava.rpc.json.http.response.ProcessedSlot>();
      assertTrue(ws.slotSubscribe(slots::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":10,"id":2}"""), true);

      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":1,"root":1,"slot":2},"subscription":99}}"""), true);
      assertTrue(slots.isEmpty(), "a stale id must not reach the current consumer");
      assertTrue(socket.sentText.stream().anyMatch(m -> m.contains("slotUnsubscribe") && m.contains("[99]")),
          "the stale server-side subscription is cancelled instead: " + socket.sentText);

      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":1,"root":1,"slot":3},"subscription":10}}"""), true);
      assertEquals(1, slots.size(), "the matching id still dispatches");
    }
  }

  /// The cancellation tombstone: an unsubscribe before confirmation cannot recall the request —
  /// queued or on the wire, it will be answered — so the confirmation must convert into an
  /// immediate server unsubscribe. Discarding it as unknown orphaned a server subscription with
  /// nothing left to cancel it, invisibly for channels that rarely notify.
  @Test
  void unsubscribeBeforeConfirmationConvertsTheConfirmationIntoAnUnsubscribe() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.logsSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertSent(socket, "logsSubscribe", ACCOUNT_A.toBase58());

      assertTrue(ws.logsUnsubscribe(ACCOUNT_A), "the local record is removable before confirmation");

      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":808,"id":2}"""), true);
      assertTrue(socket.sentText.stream().anyMatch(m -> m.contains("logsUnsubscribe") && m.contains("[808]")),
          "the confirmation of a cancelled request must cancel the server side: " + socket.sentText);
      assertEquals(0, ws.retainedRegistrations(), "nothing may linger locally");
    }
  }

  /// Pre-confirmation correlation: after unsubscribe/resubscribe, the successor has no
  /// confirmed id to compare against — the retired-id set is what keeps the predecessor's late
  /// notifications away from the successor's consumer in that window.
  @Test
  void aRetiredIdIsDroppedWhileTheSuccessorIsUnconfirmed() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.slotSubscribe(_ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":10,"id":2}"""), true);
      assertTrue(ws.slotUnsubscribe());

      final var successorSlots = new java.util.ArrayList<software.sava.rpc.json.http.response.ProcessedSlot>();
      assertTrue(ws.slotSubscribe(successorSlots::add));
      // successor is still unconfirmed: a late notification for the retired id arrives
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":1,"root":1,"slot":2},"subscription":10}}"""), true);
      assertTrue(successorSlots.isEmpty(),
          "the predecessor's stream must not reach the successor's consumer");
    }
  }

  /// The NEVER sentinel branches explicitly at both gates, so maximal delays mean "no retry
  /// between attempts", never "no first attempt".
  @Test
  void maximalDelaysDoNotSuppressTheFirstAttempt() {
    final var clock = new TestClock();
    final var scheduler = new RecordingScheduler();
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new java.util.concurrent.atomic.AtomicReference<>(), socket);
    final var maximal = new Timings(Long.MAX_VALUE, 15_000L, 2_000L, 30_000L, Long.MAX_VALUE);
    try (final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(java.time.Duration.ofMillis(1_000)),
        maximal, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock,
        new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        }, null, null, null)) {
      assertNotNull(ws.connect());
      assertEquals(1, webSocketBuilder.builds, "the FIRST attempt is immediate under a maximal throttle");

      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      ws.onOpen(socket);
      assertEquals(1, socket.sentText.size(), "the INITIAL send is immediate under a maximal resend delay");
    }
  }

  /// End to end through the attempt listener: connect() hands the JDK builder an
  /// epoch-carrying listener, and adoption must flow through it — every other adoption test
  /// drives the engine's callbacks directly and so bypasses the routing this pins.
  @Test
  void connectAdoptsThroughItsAttemptListener() {
    final var clock = new TestClock();
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new java.util.concurrent.atomic.AtomicReference<>(), socket);
    webSocketBuilder.invokeOnOpen = true;
    try (final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(java.time.Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock,
        new RecordingExecutor(), new RecordingScheduler(), null, (_, _, _) -> {
        }, null, null, null)) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));

      assertNotNull(ws.connect());
      assertEquals(1, webSocketBuilder.builds);
      assertEquals(1, webSocketBuilder.listeners.size(), "the attempt carries its own listener");

      // adoption happened through the attempt listener: the socket is current and serving
      assertSent(socket, "accountSubscribe", ACCOUNT_A.toBase58());
      final long delivered;
      clock.advanceMillis(1_000L);
      delivered = clock.currentTimeMillis();
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":700,"id":2}"""), true);
      assertEquals(delivered, ws.lastMessageReceivedTimestamp(), "the adopted socket stamps as current");
    }
  }

  /// A stale attempt's late onOpen must not displace the connection that outraced it: the
  /// attempt's epoch, not its completion order, authorizes adoption.
  @Test
  void aStaleAttemptsLateAdoptionIsRejected() {
    final var clock = new TestClock();
    final var socketA = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new java.util.concurrent.atomic.AtomicReference<>(), socketA);
    try (final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(java.time.Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock,
        new RecordingExecutor(), new RecordingScheduler(), null, (_, _, _) -> {
        }, null, null, null)) {
      // attempt 1: build completes but its onOpen is withheld, like a slow handshake thread
      assertNotNull(ws.connect());
      assertEquals(1, webSocketBuilder.listeners.size());

      // attempt 2 is admitted — attempt 1's future settled — and its adoption wins
      clock.advanceMillis(TIMINGS.reConnectDelay() + 1);
      assertNotNull(ws.connect());
      assertEquals(2, webSocketBuilder.listeners.size());
      final var socketB = new RecordingWebSocket();
      webSocketBuilder.listeners.get(1).onOpen(socketB);

      // attempt 1's handshake finally lands, stale
      webSocketBuilder.listeners.get(0).onOpen(socketA);
      assertTrue(socketA.aborted, "a stale attempt's socket is aborted, not adopted");

      clock.advanceMillis(1_000L);
      final long delivered = clock.currentTimeMillis();
      ws.onText(socketB, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":700,"id":2}"""), true);
      assertEquals(delivered, ws.lastMessageReceivedTimestamp(), "the winner is still current");
      ws.onText(socketA, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":701,"id":3}"""), true);
      assertEquals(delivered, ws.lastMessageReceivedTimestamp(), "the stale socket cannot stamp");
    }
  }

  /// A subscription made while no connection exists — between connect() and its adoption — is
  /// sent once a connection is adopted. The registry, not the connection, owns intent.
  @Test
  void aSubscriptionMadeDuringTheGapIsSentOnAdoption() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      // the gap: connect() aborts and displaces; nothing is current
      assertNotNull(ws.connect());
      assertTrue(first.aborted);

      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }), "intent is registrable while no connection exists");

      clock.advanceMillis(TIMINGS.reConnectDelay() + 1);
      final var second = new RecordingWebSocket();
      ws.onOpen(second);
      assertSent(second, "accountSubscribe", ACCOUNT_A.toBase58());
    }
  }

  /// The mirror: unsubscribing during the gap removes the intent, and nothing about the dead
  /// connection's confirmed subId survives to the replacement — its number died with it.
  @Test
  void anUnsubscribeDuringTheGapLeavesNothingToReplay() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.logsSubscribe(ACCOUNT_A, _ -> {
      }));
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      ws.onText(first, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":555,"id":2}"""), true);

      assertNotNull(ws.connect());
      assertTrue(ws.logsUnsubscribe(ACCOUNT_A), "the registration is removable during the gap");

      clock.advanceMillis(TIMINGS.reConnectDelay() + 1);
      final var second = new RecordingWebSocket();
      ws.onOpen(second);
      ws.onPong(second, ByteBuffer.wrap(new byte[0]));
      assertNotSent(second, "logsSubscribe");
      assertNotSent(second, "logsUnsubscribe");
    }
  }

  /// Reconnect is what clears the send-once gate: a successfully sent but unanswered subscribe
  /// is never re-sent on its own connection, and MUST be re-sent on the replacement — the old
  /// request died with the old connection, whatever the server did with it.
  @Test
  void reconnectResendsASentButUnansweredSubscription() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      assertEquals(1, first.sentText.size());

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, first.sentText.size(), "sent and unanswered: gated on this connection");

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      final var second = new RecordingWebSocket();
      ws.onOpen(second);
      assertSent(second, "accountSubscribe", ACCOUNT_A.toBase58());
    }
  }

  /// One reconnect, every registry shape: each channel's registration must survive the takeover
  /// and re-send on the replacement connection.
  @Test
  void everyRegistryShapeReplaysOnReconnect() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      final var sig = "2EBVM6cB8vAAD93Ktr6Vd8p67XPbQzCJX47MpReuiCXJAtcjaxpvWpcg9Ege1Nr5Tk3a2GFrByT7WPBjdsTycY9b";
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      assertTrue(ws.logsSubscribe(ACCOUNT_B, _ -> {
      }));
      assertTrue(ws.programSubscribe(ACCOUNT_B, _ -> {
      }));
      assertTrue(ws.signatureSubscribe(sig, _ -> {
      }));
      assertTrue(ws.slotSubscribe(_ -> {
      }));
      assertTrue(ws.rootSubscribe(_ -> {
      }));
      assertTrue(ws.subscribe("voteSubscribe", "voteUnsubscribe", "voteNotification",
          "vote", "", JsonIterator::readString, null, _ -> {
          }));
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      assertEquals(7, first.sentText.size());

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      final var second = new RecordingWebSocket();
      ws.onOpen(second);
      for (final var method : new String[]{"accountSubscribe", "logsSubscribe", "programSubscribe",
          "signatureSubscribe", "slotSubscribe", "rootSubscribe", "voteSubscribe"}) {
        assertSent(second, method, "");
      }
      assertEquals(7, second.sentText.size(), "each shape replays exactly once: " + second.sentText);
    }
  }

  /// A caller's cancel abandons only the caller's view. When connect() exposed the internal
  /// future, cancel() satisfied the single-flight isDone() check and admitted a second
  /// handshake against the JDK's not-thread-safe builder while the first still ran — the
  /// attempt is the instance's to own, and only close() or completion ends it.
  @Test
  void cancellingTheExposedFutureDoesNotAbandonTheAttempt() {
    final var clock = new TestClock();
    final var scheduler = new RecordingScheduler();
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new java.util.concurrent.atomic.AtomicReference<>(), socket);
    try (final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(java.time.Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock,
        new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        }, null, null, null)) {
      ws.onOpen(socket);
      final var first = ws.connect(); // inside the window: deferred
      assertNotNull(first);
      assertEquals(1, scheduler.deferred.size());

      first.toCompletableFuture().cancel(true);
      final var second = ws.connect();
      assertNotNull(second);
      assertFalse(second.toCompletableFuture().isDone(),
          "a fresh view of the still-running attempt, not the cancelled one");
      assertEquals(1, scheduler.deferred.size(), "the cancel admitted no second handshake");

      scheduler.deferred.getFirst().task().run();
      assertEquals(1, webSocketBuilder.builds, "the one attempt builds once");
      assertSame(socket, second.toCompletableFuture().join());
      assertTrue(first.toCompletableFuture().isCancelled(), "the abandoned view stays abandoned");
    }
  }

  /// The send-once gate is the connection's own: a displaced connection's late send failure
  /// releases the gate in its own dead state, never the successor's. When the gate was a shared
  /// instance set — cleared and repopulated with the SAME msgIds at adoption — a stale
  /// completion's removal reopened the successor's gate, and after the resend window the
  /// successor queued exactly the duplicate subscribe the gate exists to prevent.
  @Test
  void aDisplacedConnectionsLateFailureCannotReopenTheSuccessorsSendGate() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var first = new RecordingWebSocket();
      first.deferTexts = true;
      ws.onOpen(first);
      assertEquals(1, first.sentText.size(), "held open on the first connection");

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      final var second = new RecordingWebSocket();
      ws.onOpen(second);
      assertEquals(1, second.sentText.size(), "replayed on the successor, gated awaiting its answer");

      // the displaced connection's send finally fails — same msgId as the successor's replay
      first.deferredTexts.getFirst().completeExceptionally(new java.io.IOException("aborted late"));

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, second.sentText.size(),
          "the stale failure released its own dead gate, not the successor's: " + second.sentText);
    }
  }

  /// The full invalid-signature story, with the server's answers verbatim. Probed against
  /// api.mainnet-beta.solana.com (2026-08-09):
  ///
  ///   >> signatureSubscribe "sig"
  ///   << {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid Request: Invalid signature provided"},"id":1}
  ///
  /// This is the measured evidence that removed client-side base58/length validation: the
  /// rejection arrives WITH the request id, so the client can do everything a local check
  /// could — retire the request, free its key, report the cause — with the server as the
  /// single authority on what a valid signature is. This test replays that exchange end to
  /// end; if the terminal-rejection path ever stops correlating it, the local validation
  /// question reopens.
  @Test
  void anInvalidSignatureIsRetiredByTheServersOwnRejection() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      final var errors = new java.util.ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);

      // frame-safe but semantically invalid: accepted client side, the server's to judge
      assertTrue(ws.signatureSubscribe("sig", _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertSent(socket, "signatureSubscribe", "sig");

      // the server's verbatim rejection, id-corrected to this exchange's request id
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid Request: Invalid signature provided"},"id":2}"""), true);

      final var rejection = assertInstanceOf(
          software.sava.rpc.json.http.response.JsonRpcException.class, errors.getFirst());
      assertEquals(-32_602L, rejection.code());
      assertEquals("Invalid Request: Invalid signature provided", rejection.getMessage());

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.sentText.stream().filter(m -> m.contains("signatureSubscribe")).count(),
          "the rejected request must not re-send: " + socket.sentText);

      assertTrue(ws.signatureSubscribe("sig", _ -> {
      }), "the key is free for a corrected subscribe");
      assertEquals(0, errors.size() - 1, "one rejection, one report");
    }
  }

  /// F3: the tombstone's granted id is retired the moment it is known — a successor for the
  /// same key may already be unconfirmed, and the cancelled subscription's stream must not
  /// reach it in that window.
  @Test
  void aCancelledSubscriptionsLateGrantCannotFeedItsSuccessor() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.slotSubscribe(_ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertTrue(ws.slotUnsubscribe(), "cancelled before confirmation: tombstoned");

      final var successorSlots = new java.util.ArrayList<software.sava.rpc.json.http.response.ProcessedSlot>();
      assertTrue(ws.slotSubscribe(successorSlots::add));

      // the predecessor's grant finally arrives: converted to an unsubscribe, id retired
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":10,"id":2}"""), true);
      assertTrue(socket.sentText.stream().anyMatch(m -> m.contains("slotUnsubscribe") && m.contains("[10]")),
          "the tombstone converts the grant into a cancellation: " + socket.sentText);

      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":1,"root":1,"slot":2},"subscription":10}}"""), true);
      assertTrue(successorSlots.isEmpty(), "the cancelled subscription's stream is not the successor's");
    }
  }

  /// F3: the compensating un-subscription is durable — one failed send used to orphan the
  /// server subscription permanently, because nothing re-queued it.
  @Test
  void aFailedCompensatingUnsubscriptionIsRequeued() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      assertTrue(ws.logsSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertTrue(ws.logsUnsubscribe(ACCOUNT_A), "tombstoned before confirmation");

      socket.failText = new java.io.IOException("compensation frame never left");
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":808,"id":2}"""), true);
      final long attempted = socket.sentText.stream().filter(m -> m.contains("logsUnsubscribe")).count();
      assertEquals(1, attempted, "the compensation was attempted");

      socket.failText = null;
      ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
      final long delivered = socket.sentText.stream().filter(m -> m.contains("logsUnsubscribe")).count();
      assertEquals(2, delivered, "the failed compensation is owed and re-sent: " + socket.sentText);
    }
  }

  /// F4, defensively: a server may grant the SAME id to an identical re-subscription
  /// (solana-labs#18943 — unverified against current Agave, guarded regardless). The queued
  /// un-subscription for that id would cancel the subscription just granted, so the
  /// confirmation cancels the cancellation, and the id comes out of retirement.
  @Test
  void aReusedSubscriptionIdCancelsItsQueuedCancellation() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      final var received = new java.util.ArrayList<software.sava.rpc.json.http.response.AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(ACCOUNT_A, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":700,"id":2}"""), true);
      assertTrue(ws.accountUnsubscribe(ACCOUNT_A));

      // re-subscribe before the queued un-subscription flushes; the server grants the SAME id
      assertTrue(ws.accountSubscribe(ACCOUNT_A, received::add));
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":700,"id":3}"""), true);

      // the queued cancellation must be gone: a flush sends nothing that would kill id 700
      ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
      assertTrue(socket.sentText.stream().noneMatch(m -> m.contains("accountUnsubscribe") && m.contains("[700]")),
          "the queued cancellation would cancel the successor: " + socket.sentText);

      // and the id is live again, out of retirement
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":3},"value":{"lamports":1,"data":["","base64"],"owner":"11111111111111111111111111111111","executable":false,"rentEpoch":0,"space":0}},"subscription":700}}"""), true);
      assertEquals(1, received.size(), "the reused id feeds the successor consumer");
    }
  }

  /// F7: send-once needs an answer deadline. A request the server never answers — on a
  /// connection other traffic keeps healthy — would otherwise stay gated forever, its
  /// subscription silently nonexistent. Past four resend windows the CONNECTION is replaced:
  /// aborted, with the error seam told why.
  @Test
  void anUnansweredRequestEscalatesByReplacingTheConnection() {
    final var clock = new TestClock();
    final var errors = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null,
        (_, error) -> errors.set(error))) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(1, socket.sentText.size(), "sent once, never answered");

      // peer stays chatty, so no liveness gate ever fires — the deadline is the only watcher
      for (int i = 0; i < 3; ++i) {
        clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
        ws.onText(socket, CharBuffer.wrap("""
            {"jsonrpc":"2.0","result":555,"id":99}"""), true);
        assertDoesNotThrow(() -> ws.checkCycle(0L));
        assertNull(errors.get(), "inside the deadline nothing escalates");
      }

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertNotNull(errors.get(), "past the deadline the error seam is told");
      assertTrue(socket.aborted, "and the connection is what gets replaced");
      assertEquals(1, socket.sentText.size(), "never a duplicate request");
    }
  }

  /// F8: un-subscription outcomes are correlated by request id now. True and false both settle
  /// the request quietly, and a correlated rejection is a settled double-cancel, not consumer
  /// news — the message-text heuristic survives only for uncorrelated stale-id errors.
  @Test
  void unsubscriptionAcknowledgementsAreCorrelatedAndQuiet() {
    final var clock = new TestClock();
    try (final var ws = websocket(TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, null, null)) {
      final var errors = new java.util.ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);
      assertTrue(ws.logsSubscribe(ACCOUNT_A, _ -> {
      }));
      assertTrue(ws.accountSubscribe(ACCOUNT_B, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":555,"id":2}"""), true);
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":556,"id":3}"""), true);

      assertTrue(ws.logsUnsubscribe(ACCOUNT_A));
      assertTrue(ws.accountUnsubscribe(ACCOUNT_B));
      ws.onPong(socket, ByteBuffer.wrap(new byte[0])); // flush mints ids 4 and 5, in subId order

      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":true,"id":4}"""), true);
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":false,"id":5}"""), true);
      assertTrue(errors.isEmpty(), "acknowledgements, true or false, are not consumer news");

      // a correlated rejection of an un-subscription is equally settled — no heuristic needed
      assertTrue(ws.logsSubscribe(ACCOUNT_A, _ -> {
      }));
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":557,"id":6}"""), true);
      assertTrue(ws.logsUnsubscribe(ACCOUNT_A));
      ws.onPong(socket, ByteBuffer.wrap(new byte[0])); // mints id 7
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"some novel wording"},"id":7}"""), true);
      assertTrue(errors.isEmpty(), "a correlated un-subscription rejection is a settled double-cancel");
    }
  }
}
