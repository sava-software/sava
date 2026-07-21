package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;

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
        clock,
        new RecordingExecutor(),
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

  /// Flushing an un-subscription counts as the cycle's write — it suppresses the
  /// ping the cycle would otherwise send — and the flush must not repeat: the
  /// queued frame is written exactly once.
  @Test
  void flushedUnsubscriptionSuppressesThePingAndIsNotResent() {
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
      assertEquals(0, socket.pings, "the flushed un-subscription is this cycle's write");
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
}
