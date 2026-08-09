package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/// Unsubscribe flows per channel: the queued un-subscription frame, its flush on
/// the next write cycle, double-unsubscribe, unsubscribing before confirmation
/// (nothing to send), and lookups that must miss — wrong commitment, wrong
/// channel, wrong key. The account channel's flow is pinned in
/// [SolanaJsonRpcWebsocketTests]; these cover the rest.
final class SolanaJsonRpcWebsocketUnsubscribeTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final PublicKey KEY =
      PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");

  private static SolanaJsonRpcWebsocket websocket() {
    return websocket(new TestClock());
  }

  private static SolanaJsonRpcWebsocket websocket(final TestClock clock) {
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
        (_, _, _) -> {
        },
        null, null, null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  /// A pong is a write cycle, so it flushes queued un-subscriptions.
  private static void flush(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket) {
    ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
  }

  @Test
  void logsUnsubscribe() {
    try (final var ws = websocket()) {
      assertTrue(ws.logsSubscribe(KEY, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":555,"id":2}""");

      assertTrue(ws.logsUnsubscribe(KEY));
      assertFalse(ws.logsUnsubscribe(KEY));

      flush(ws, socket);
      assertEquals("""
          {"jsonrpc":"2.0","id":3,"method":"logsUnsubscribe","params":[555]}""", socket.sentText.getLast());
    }
  }

  @Test
  void signatureUnsubscribe() {
    try (final var ws = websocket()) {
      final var sig = "2EBVM6cB8vAAD93Ktr6Vd8p67XPbQzCJX47MpReuiCXJAtcjaxpvWpcg9Ege1Nr5Tk3a2GFrByT7WPBjdsTycY9b";
      assertTrue(ws.signatureSubscribe(sig, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":777,"id":2}""");

      assertTrue(ws.signatureUnsubscribe(sig));
      assertFalse(ws.signatureUnsubscribe(sig));

      flush(ws, socket);
      assertEquals("""
          {"jsonrpc":"2.0","id":3,"method":"signatureUnsubscribe","params":[777]}""", socket.sentText.getLast());
    }
  }

  @Test
  void programUnsubscribe() {
    try (final var ws = websocket()) {
      final var program = PublicKey.fromBase58Encoded("GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc");
      assertTrue(ws.programSubscribe(program, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":888,"id":2}""");

      assertTrue(ws.programUnsubscribe(program));
      assertFalse(ws.programUnsubscribe(program));

      flush(ws, socket);
      assertEquals("""
          {"jsonrpc":"2.0","id":3,"method":"programUnsubscribe","params":[888]}""", socket.sentText.getLast());
    }
  }

  @Test
  void slotAndRootUnsubscribe() {
    try (final var ws = websocket()) {
      assertTrue(ws.slotSubscribe(_ -> {
      }));
      assertTrue(ws.rootSubscribe(_ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":10,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":11,"id":3}""");

      assertTrue(ws.slotUnsubscribe());
      assertFalse(ws.slotUnsubscribe());
      assertTrue(ws.rootUnsubscribe());
      assertFalse(ws.rootUnsubscribe());

      flush(ws, socket);
      final int frames = socket.sentText.size();
      assertEquals("""
          {"jsonrpc":"2.0","id":4,"method":"slotUnsubscribe","params":[10]}""", socket.sentText.get(frames - 2));
      assertEquals("""
          {"jsonrpc":"2.0","id":5,"method":"rootUnsubscribe","params":[11]}""", socket.sentText.get(frames - 1));
    }
  }

  /// Unsubscribing before the server confirmed leaves nothing to unsubscribe from:
  /// the pending request is dropped and no frame is ever written.
  @Test
  void unsubscribeBeforeConfirmationSendsNothing() {
    try (final var ws = websocket()) {
      assertTrue(ws.slotSubscribe(_ -> {
      }));
      assertTrue(ws.slotUnsubscribe());

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      flush(ws, socket);
      assertTrue(socket.sentText.isEmpty(), "nothing was confirmed, so nothing should be written: " + socket.sentText);
    }
  }

  /// A queued un-subscription is written exactly once, however many flush cycles run.
  ///
  /// This test used to also assert that the flush suppressed the cycle's ping, back when a
  /// pending un-subscription gated pinging. That gate is gone, and the assertion had become
  /// unfalsifiable rather than merely obsolete: the flush is driven through `onPong`, an
  /// inbound handler which has no path that sends a ping, so `socket.pings` was structurally
  /// zero no matter what the production code did. Asserting it read as coverage of the
  /// suppression contract while proving nothing about it.
  @Test
  void aFlushedUnsubscriptionIsNotResent() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      assertTrue(ws.logsSubscribe(KEY, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":555,"id":2}""");
      assertTrue(ws.logsUnsubscribe(KEY));

      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      flush(ws, socket);
      flush(ws, socket);

      final long unsubFrames = socket.sentText.stream().filter(m -> m.contains("logsUnsubscribe")).count();
      assertEquals(1, unsubFrames, "the queued frame must be written exactly once: " + socket.sentText);
    }
  }

  /// A generic unsubscribe that misses its map falls back to scanning the active
  /// subscriptions — which must skip non-generic subscriptions and generic ones
  /// whose key does not match.
  @Test
  void genericUnsubscribeScansActiveSubscriptionsAndMisses() {
    try (final var ws = websocket()) {
      assertTrue(ws.accountSubscribe(KEY, _ -> {
      }));
      assertTrue(ws.subscribe("voteSubscribe", "voteUnsubscribe", "voteNotification",
          "vote", "", ji -> ji.skipUntil("slots").openArray().readLong(), null, _ -> {
          }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":999,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":100,"id":3}""");

      assertFalse(ws.unsubscribe("voteNotification", "missing-key"), "wrong key");
      assertFalse(ws.unsubscribe("otherNotification", "vote"), "wrong notification method");
      assertTrue(ws.unsubscribe("voteNotification", "vote"), "the exact match still unsubscribes");
    }
  }

  /// The dangling-subscription scan must miss on every mismatched dimension —
  /// commitment, channel, and key — and only then report nothing to remove.
  @Test
  void unsubscribeMissesOnCommitmentChannelAndKey() {
    try (final var ws = websocket()) {
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(KEY, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":999,"id":2}""");

      final var otherKey = PublicKey.fromBase58Encoded("BDn3HiXMTym7ZQofWFxDb7ZGQX6GomQzJYKfytTAqd5g");
      assertFalse(ws.accountUnsubscribe(Commitment.PROCESSED, KEY), "wrong commitment");
      assertFalse(ws.logsUnsubscribe(KEY), "wrong channel");
      assertFalse(ws.accountUnsubscribe(otherKey), "wrong key");

      // the subscription survived every miss
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":1},"value":{"data":["","base64"],"executable":false,"lamports":1,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":0}},"subscription":999}}""");
      assertEquals(1, received.size());

      assertTrue(ws.accountUnsubscribe(KEY), "the exact match still unsubscribes");
    }
  }

  /// Flushing a queued un-subscription is this end putting a frame on the wire, so it feeds the
  /// keep-alive clock: 119s of shared silence, a flush, then 2s — past the 120s keep-alive as
  /// measured from the open, but two seconds from the flush. An unstamped flush would ping here.
  @Test
  void aFlushedUnsubscriptionCountsAsAnOutboundFrame() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      assertTrue(ws.logsSubscribe(KEY, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":555,"id":2}""");
      assertTrue(ws.logsUnsubscribe(KEY));

      clock.advanceMillis(119_000L);
      flush(ws, socket);
      final long unsubFrames = socket.sentText.stream().filter(m -> m.contains("logsUnsubscribe")).count();
      assertEquals(1, unsubFrames, "the flush must actually have written the frame: " + socket.sentText);

      clock.advanceMillis(2_000L);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(0, socket.pings, "the flushed un-subscription was this end's last outbound frame");
    }
  }

  /// Queued un-subscriptions flush in subId order — a specified property, not hash luck. The
  /// ids are chosen so a hash-ordered map would invert them (17 lands in a lower bin than 2),
  /// which is exactly what this pins: deterministic wire order survives a well-meaning swap to
  /// ConcurrentHashMap only if a test can tell the difference.
  @Test
  void queuedUnsubscriptionsFlushInSubIdOrder() {
    try (final var ws = websocket()) {
      final var program = PublicKey.fromBase58Encoded("GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc");
      assertTrue(ws.logsSubscribe(KEY, _ -> {
      }));
      assertTrue(ws.programSubscribe(program, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":17,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":2,"id":3}""");

      assertTrue(ws.logsUnsubscribe(KEY));
      assertTrue(ws.programUnsubscribe(program));

      final int before = socket.sentText.size();
      flush(ws, socket);
      assertEquals(before + 2, socket.sentText.size());
      assertTrue(socket.sentText.get(before).contains("[2]"),
          "lowest subId flushes first: " + socket.sentText);
      assertTrue(socket.sentText.get(before + 1).contains("[17]"),
          "highest subId flushes last: " + socket.sentText);
    }
  }

  /// A server-condition rejection says the cancellation is still owed, but it does not make
  /// the retry cadence disappear. `subscriptionResendDelay` is the engine's retry window; an
  /// immediate -32603/retry exchange must not turn one overloaded peer into a wire-speed loop.
  @Test
  void aTransientUnsubscribeRejectionObservesTheRetryCadence() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      assertTrue(ws.accountSubscribe(KEY, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}""");
      assertTrue(ws.accountUnsubscribe(KEY));
      ws.checkCycle(0L); // cancellation id 3
      assertEquals(1, socket.sentText.stream().filter(m -> m.contains("accountUnsubscribe")).count());

      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32603,"message":"Internal error"},"id":3}""");
      ws.checkCycle(0L);
      final long immediateRetries = socket.sentText.stream().filter(m -> m.contains("accountUnsubscribe")).count();
      assertTrue(socket.aborted || immediateRetries == 1,
          "a transient rejection must be paced or replace the connection, not retry at an "
              + "unchanged pacing time: " + socket.sentText);

      if (!socket.aborted) {
        clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
        ws.checkCycle(0L);
        assertEquals(2, socket.sentText.stream().filter(m -> m.contains("accountUnsubscribe")).count(),
            "the still-owed cancellation retries once its window has elapsed");
      }
    }
  }

  /// The unanswered deadline measures peer silence after transmission. An un-subscription can
  /// sit behind another outstanding text send; time spent in that local chain is not time the
  /// peer has had to answer it, so successful transmission must restart its deadline just as a
  /// subscription send does.
  @Test
  void anUnsubscribeAnswerDeadlineStartsAtTransmission() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      assertTrue(ws.accountSubscribe(KEY, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}""");

      socket.deferTexts = true;
      assertTrue(ws.rootSubscribe(_ -> {
      }));
      ws.checkCycle(0L); // rootSubscribe is now the pending head send
      assertEquals(1, socket.deferredTexts.size());
      assertTrue(ws.accountUnsubscribe(KEY));
      ws.checkCycle(0L); // accountUnsubscribe is admitted behind that head

      clock.advanceMillis(239_000L); // just inside the four-window deadline
      ws.checkCycle(0L);
      assertFalse(socket.aborted);
      socket.deferredTexts.get(0).complete(socket); // root sent; dispatches the queued unsubscribe
      assertEquals(2, socket.deferredTexts.size());
      socket.deferredTexts.get(1).complete(socket); // unsubscribe actually transmitted now

      clock.advanceMillis(2_000L); // past admission deadline, only 2s after transmission
      ws.checkCycle(0L);
      assertFalse(socket.aborted,
          "the peer must receive its full answer window after the unsubscribe reaches the wire");
    }
  }

  /// Once an admitted subscribe send fails, no confirmation can exist. A cancellation
  /// tombstone for that request has no event left that could consume it, so retaining it for
  /// the connection's lifetime is a leak rather than compensation state.
  @Test
  void aFailedCancelledSubscribeDoesNotRetainAnUnanswerableTombstone() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      assertTrue(ws.accountSubscribe(KEY, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      socket.deferTexts = true;
      ws.onOpen(socket);
      assertEquals(1, socket.deferredTexts.size(), "the subscribe is admitted and dispatched");

      assertTrue(ws.accountUnsubscribe(KEY));
      assertEquals(1, ws.retainedCancellationTombstones());
      socket.deferredTexts.getFirst().completeExceptionally(new IOException("request never left"));

      assertEquals(0, ws.retainedCancellationTombstones(),
          "a failed send can neither grant a subscription nor answer its tombstone");
      assertEquals(0, ws.retainedOrdinalEntries(),
          "the cancelled registration's attempt ordinal dies when its send fails");
    }
  }
}
