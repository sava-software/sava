package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TxLogs;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// `logsSubscribeAll`, agave's `all` logs filter. Its request carries the bare string `"all"`
/// where a `mentions` registration carries `{"mentions":["<key>"]}`; it is registered in the
/// logs registry under the key `all`, once per commitment; and notifications are routed by
/// subscription id, so an `all` and a `mentions` registration at the same commitment never see
/// each other's notifications. Every frame the client sends is asserted whole.
@ExtendWith(QuietWsLogging.class)
final class LogsSubscribeAllTests {

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

  private static String notification(final long subscription, final String signature) {
    return """
        {"jsonrpc":"2.0","method":"logsNotification","params":{"result":{"context":{"slot":517568769},"value":{"signature":"%s","err":null,"logs":["Program 11111111111111111111111111111111 invoke [1]","Program 11111111111111111111111111111111 success"]}},"subscription":%d}}""".formatted(signature, subscription);
  }

  private static List<String> signatures(final List<TxLogs> delivered) {
    return delivered.stream().map(TxLogs::signature).toList();
  }

  @Test
  void theConsumerOverloadSendsTheBareAllFilterAtTheDefaultCommitment() {
    try (final var ws = websocket()) {
      assertTrue(ws.logsSubscribeAll(_ -> {
      }));
      assertFalse(ws.logsSubscribeAll(_ -> {
      }), "one all registration per commitment");

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of("""
          {"jsonrpc":"2.0","id":2,"method":"logsSubscribe","params":["all",{"commitment":"confirmed"}]}"""
      ), socket.sentText);
    }
  }

  /// Each overload resolves its commitment the documented way, and each refuses the duplicate
  /// it would create, whichever overload created the original.
  @Test
  void overloadsRegisterOncePerCommitment() {
    try (final var ws = websocket()) {
      final var onSub = new AtomicReference<Subscription<TxLogs>>();
      assertTrue(ws.logsSubscribeAll(onSub::set, _ -> {
      }));
      assertFalse(ws.logsSubscribeAll(onSub::set, _ -> {
      }));
      assertFalse(ws.logsSubscribeAll(_ -> {
      }), "the default commitment's registration already exists");
      assertTrue(ws.logsSubscribeAll(Commitment.FINALIZED, _ -> {
      }));
      assertFalse(ws.logsSubscribeAll(Commitment.FINALIZED, _ -> {
      }));
      assertTrue(ws.logsSubscribeAll(Commitment.PROCESSED, null, _ -> {
      }));
      assertFalse(ws.logsSubscribeAll(Commitment.PROCESSED, onSub::set, _ -> {
      }));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of("""
              {"jsonrpc":"2.0","id":2,"method":"logsSubscribe","params":["all",{"commitment":"confirmed"}]}""", """
              {"jsonrpc":"2.0","id":3,"method":"logsSubscribe","params":["all",{"commitment":"finalized"}]}""", """
              {"jsonrpc":"2.0","id":4,"method":"logsSubscribe","params":["all",{"commitment":"processed"}]}"""
          ), socket.sentText
      );

      // the onSub callback fires once the frame has been written
      final var sub = onSub.get();
      assertNotNull(sub);
      assertEquals(2, sub.msgId());
      assertEquals("all", sub.key());
      assertNull(sub.publicKey());
      assertEquals(Channel.logs, sub.channel());
      assertEquals(Commitment.CONFIRMED, sub.commitment());
    }
  }

  /// The `all` registration and a `mentions` registration share the logs registry but not a
  /// key, so either may exist at a commitment the other holds, and each unsubscribe removes
  /// only its own. Each cancellation names its own subscription id.
  @Test
  void unsubscribeAllRemovesOnlyTheAllRegistration() {
    try (final var ws = websocket()) {
      assertTrue(ws.logsSubscribeAll(_ -> {
      }));
      assertTrue(ws.logsSubscribeAll(Commitment.FINALIZED, _ -> {
      }));
      assertTrue(ws.logsSubscribe(KEY, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":4242,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":4343,"id":3}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":4444,"id":4}""");

      assertTrue(ws.logsUnsubscribeAll());
      assertFalse(ws.logsUnsubscribeAll());
      flush(ws, socket);
      assertEquals("""
          {"jsonrpc":"2.0","id":5,"method":"logsUnsubscribe","params":[4242]}""", socket.sentText.getLast());

      assertTrue(ws.logsUnsubscribeAll(Commitment.FINALIZED));
      assertFalse(ws.logsUnsubscribeAll(Commitment.FINALIZED));
      assertFalse(ws.logsUnsubscribeAll(Commitment.PROCESSED), "never registered at processed");
      flush(ws, socket);
      assertEquals("""
          {"jsonrpc":"2.0","id":6,"method":"logsUnsubscribe","params":[4343]}""", socket.sentText.getLast());

      assertTrue(ws.logsUnsubscribe(KEY), "the mentions registration survived both");
      flush(ws, socket);
      assertEquals("""
          {"jsonrpc":"2.0","id":7,"method":"logsUnsubscribe","params":[4444]}""", socket.sentText.getLast());

      assertTrue(ws.logsSubscribeAll(_ -> {
      }), "the default commitment's slot is free again");
    }
  }

  /// A mentions unsubscribe misses while only the `all` registration is live, and leaves it
  /// delivering: a base58 public key never renders as `all`.
  @Test
  void aMentionsUnsubscribeDoesNotReachTheAllRegistration() {
    try (final var ws = websocket()) {
      final var all = new ArrayList<TxLogs>();
      assertTrue(ws.logsSubscribeAll(all::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":4242,"id":2}""");

      assertFalse(ws.logsUnsubscribe(KEY));
      flush(ws, socket);
      assertEquals(1, socket.sentText.size(), () -> "nothing was cancelled: " + socket.sentText);
      feed(ws, socket, notification(4242, "allSig"));
      assertEquals(List.of("allSig"), signatures(all));
    }
  }

  /// The server keeps sending a busy stream's notifications until it processes the
  /// cancellation; the devnet capture behind [LiveDevnetLogsAllShapeTests] saw a burst of them
  /// between the `logsUnsubscribe` leaving and its answer. None reaches the consumer, and
  /// while the cancellation is unanswered none puts another frame on the wire.
  @Test
  void notificationsAfterUnsubscribeAllReachNobody() {
    try (final var ws = websocket()) {
      final var all = new ArrayList<TxLogs>();
      assertTrue(ws.logsSubscribeAll(all::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":4242,"id":2}""");
      feed(ws, socket, notification(4242, "beforeSig"));

      assertTrue(ws.logsUnsubscribeAll());
      flush(ws, socket);
      final var sent = List.copyOf(socket.sentText);
      assertEquals("""
          {"jsonrpc":"2.0","id":3,"method":"logsUnsubscribe","params":[4242]}""", sent.getLast());

      for (int i = 0; i < 3; ++i) {
        feed(ws, socket, notification(4242, "lateSig" + i));
      }
      assertEquals(List.of("beforeSig"), signatures(all));
      assertEquals(sent, socket.sentText, "one cancellation per id while it is unanswered");
    }
  }

  /// With both registrations live at one commitment, a notification reaches the consumer of
  /// the subscription id it names, and only that one, in either direction, and removing the
  /// mentions registration leaves the `all` stream delivering.
  @Test
  void eachNotificationReachesOnlyItsOwnRegistration() {
    try (final var ws = websocket()) {
      final var all = new ArrayList<TxLogs>();
      final var mentions = new ArrayList<TxLogs>();
      assertTrue(ws.logsSubscribeAll(Commitment.CONFIRMED, all::add));
      assertTrue(ws.logsSubscribe(Commitment.CONFIRMED, KEY, mentions::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of("""
              {"jsonrpc":"2.0","id":2,"method":"logsSubscribe","params":["all",{"commitment":"confirmed"}]}""", """
              {"jsonrpc":"2.0","id":3,"method":"logsSubscribe","params":[{"mentions":["7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r"]},{"commitment":"confirmed"}]}"""
          ), socket.sentText
      );
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":701,"id":3}""");

      feed(ws, socket, notification(700, "allSig"));
      assertEquals(List.of("allSig"), signatures(all));
      assertTrue(mentions.isEmpty(), "the all subscription's notification reached the mentions consumer");

      feed(ws, socket, notification(701, "mentionsSig"));
      assertEquals(List.of("mentionsSig"), signatures(mentions));
      assertEquals(1, all.size(), "the mentions subscription's notification reached the all consumer");

      assertTrue(ws.logsUnsubscribe(Commitment.CONFIRMED, KEY));
      feed(ws, socket, notification(700, "allSig2"));
      assertEquals(List.of("allSig", "allSig2"), signatures(all));
    }
  }

  /// The generic subscribe still refuses the logs channel's method names, so the typed method
  /// is the only way to send the `all` filter. Neither refusal registers anything or spends a
  /// request id.
  @Test
  void theGenericSubscribeStillRefusesTheLogsChannel() {
    try (final var ws = websocket()) {
      final var routed = assertThrows(IllegalArgumentException.class, () -> ws.subscribe(
          "logsSubscribe", "logsUnsubscribe", "logsNotification", "all", "\"all\"",
          JsonIterator::readString, _ -> {
          }
      ));
      assertEquals("logsNotification is routed by the built-in logs channel; use its typed subscribe",
          routed.getMessage());

      final var requested = assertThrows(IllegalArgumentException.class, () -> ws.subscribe(
          "logsSubscribe", "logsUnsubscribe", "allLogsNotification", "all", "\"all\"",
          JsonIterator::readString, _ -> {
          }
      ));
      assertEquals("logsSubscribe is the built-in logs channel's subscribe method; use its typed subscribe",
          requested.getMessage());
      assertEquals(0, ws.retainedRegistrations());

      assertTrue(ws.logsSubscribeAll(_ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of("""
          {"jsonrpc":"2.0","id":2,"method":"logsSubscribe","params":["all",{"commitment":"confirmed"}]}"""
      ), socket.sentText);
    }
  }

  /// A replacement connection re-sends the `all` registration's request unchanged, request id
  /// included, beside the mentions registration it was held apart from; the replacement's new
  /// subscription id routes to the same consumer.
  @Test
  void theAllRegistrationReplaysOnAReplacementConnection() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var all = new ArrayList<TxLogs>();
      final var mentions = new ArrayList<TxLogs>();
      assertTrue(ws.logsSubscribeAll(all::add));
      assertTrue(ws.logsSubscribe(KEY, mentions::add));
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      feed(ws, first, """
          {"jsonrpc":"2.0","result":900,"id":2}""");
      feed(ws, first, """
          {"jsonrpc":"2.0","result":901,"id":3}""");

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1);
      final var second = new RecordingWebSocket();
      ws.onOpen(second);
      assertEquals(first.sentText, second.sentText, "each registration replays its original request");
      assertEquals("""
          {"jsonrpc":"2.0","id":2,"method":"logsSubscribe","params":["all",{"commitment":"confirmed"}]}""", second.sentText.getFirst());

      feed(ws, second, """
          {"jsonrpc":"2.0","result":1900,"id":2}""");
      feed(ws, second, notification(1900, "replayedSig"));
      assertEquals(List.of("replayedSig"), signatures(all));
      assertTrue(mentions.isEmpty());
    }
  }
}
