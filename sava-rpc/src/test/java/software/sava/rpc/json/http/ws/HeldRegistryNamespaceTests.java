package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// A registration whose key already holds a commitment map, or whose notification method
/// already holds a generic namespace, joins that held map. Two routes reach it: the held
/// map is used as found, or the outer registry is written again with it.
/// `queueSubscription`'s `byCommitment == null` guard forced true re-puts the map just read
/// (`# same-map re-put`), and the generic `subscribe` forced past its `registered != null`
/// arm asks `computeIfAbsent` for the namespace `get` just returned
/// (`# compute-if-absent convergence`). By the `Map.put` and `Map.computeIfAbsent`
/// contracts both are no-ops while every registry mutator holds the lifecycle lock. This
/// class asserts what both routes must keep, across the shapes the guard keys on (absent,
/// held, emptied by its last removal, created again): the later registration joins the held
/// namespace, the earlier one stays addressable, and the namespace leaves the outer
/// registry only with its last registration. `retainedRegistrations()` counts outer keys
/// while no connection exists, so before `onOpen` it reads the namespace count directly.
@ExtendWith(QuietWsLogging.class)
final class HeldRegistryNamespaceTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final PublicKey KEY = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");

  private static SolanaJsonRpcWebsocket createWebsocket() {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(),
        new RecordingExecutor(),
        null,
        _ -> {
        },
        (_, _, _) -> {
        },
        null, null, null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  private static void assertFrame(final RecordingWebSocket socket,
                                  final int index,
                                  final long id,
                                  final String commitment) {
    final var frame = socket.sentText.get(index);
    assertTrue(frame.contains("\"id\":" + id + ',') && frame.contains("\"commitment\":\"" + commitment + '"'),
        socket.sentText::toString);
  }

  /// The String-keyed helper, reached through `logsSubscribe`.
  @Test
  void aSecondCommitmentJoinsTheHeldMapOfAStringKey() {
    try (final var ws = createWebsocket()) {
      final var delivered = new ArrayList<String>();
      assertTrue(ws.logsSubscribe(Commitment.CONFIRMED, KEY, _ -> delivered.add("confirmed")));
      assertEquals(1, ws.retainedRegistrations());
      assertTrue(ws.logsSubscribe(Commitment.FINALIZED, KEY, _ -> delivered.add("finalized")));
      assertEquals(1, ws.retainedRegistrations(), "the second commitment joins the key's held map");
      assertTrue(ws.logsUnsubscribe(Commitment.CONFIRMED, KEY));
      assertEquals(1, ws.retainedRegistrations(), "the key stays held by its other commitment");
      assertFalse(ws.logsSubscribe(Commitment.FINALIZED, KEY, _ -> delivered.add("duplicate")),
          "the remaining commitment is still registered in the held map");
      assertTrue(ws.logsUnsubscribe(Commitment.FINALIZED, KEY));
      assertEquals(0, ws.retainedRegistrations(), "the key leaves with its last commitment");

      assertTrue(ws.logsSubscribe(Commitment.FINALIZED, KEY, _ -> delivered.add("recreated")));
      assertTrue(ws.logsSubscribe(Commitment.CONFIRMED, KEY, _ -> delivered.add("joined")));
      assertEquals(1, ws.retainedRegistrations(), "the new map is held again by both commitments");

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(2, socket.sentText.size(), socket.sentText::toString);
      assertFrame(socket, 0, 4, "finalized");
      assertFrame(socket, 1, 5, "confirmed");

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":4}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":701,"id":5}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"logsNotification","params":{"result":{"context":{"slot":1},"value":{"signature":"sig","err":null,"logs":["l"]}},"subscription":700}}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"logsNotification","params":{"result":{"context":{"slot":2},"value":{"signature":"sig","err":null,"logs":["l"]}},"subscription":701}}""");
      assertEquals(List.of("recreated", "joined"), delivered);
    }
  }

  /// The PublicKey-keyed helper, reached through `accountSubscribe`.
  @Test
  void aSecondCommitmentJoinsTheHeldMapOfAPublicKey() {
    try (final var ws = createWebsocket()) {
      final var delivered = new ArrayList<String>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY, _ -> delivered.add("confirmed")));
      assertTrue(ws.accountSubscribe(Commitment.FINALIZED, KEY, _ -> delivered.add("finalized")));
      assertEquals(1, ws.retainedRegistrations(), "the second commitment joins the key's held map");
      assertTrue(ws.accountUnsubscribe(Commitment.FINALIZED, KEY));
      assertEquals(1, ws.retainedRegistrations(), "the key stays held by its other commitment");
      assertFalse(ws.accountSubscribe(Commitment.CONFIRMED, KEY, _ -> delivered.add("duplicate")));
      assertTrue(ws.accountUnsubscribe(Commitment.CONFIRMED, KEY));
      assertEquals(0, ws.retainedRegistrations(), "the key leaves with its last commitment");

      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY, _ -> delivered.add("recreated")));
      assertTrue(ws.accountSubscribe(Commitment.FINALIZED, KEY, _ -> delivered.add("joined")));
      assertEquals(1, ws.retainedRegistrations(), "the new map is held again by both commitments");

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(2, socket.sentText.size(), socket.sentText::toString);
      assertFrame(socket, 0, 4, "confirmed");
      assertFrame(socket, 1, 5, "finalized");

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":4}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":701,"id":5}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":1},"value":{"data":["","base64"],"executable":false,"lamports":1,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":0}},"subscription":701}}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":2},"value":{"data":["","base64"],"executable":false,"lamports":1,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":0}},"subscription":700}}""");
      assertEquals(List.of("joined", "recreated"), delivered);
    }
  }

  /// The generic namespace: a second key under a held notification method joins it and is
  /// bound to the cancellation method the first key fixed.
  @Test
  void aSecondKeyJoinsTheHeldNamespaceOfAGenericMethod() {
    try (final var ws = createWebsocket()) {
      final var delivered = new ArrayList<String>();
      assertTrue(subscribe(ws, "a", "watchUnsubscribe", delivered));
      assertEquals(1, ws.retainedRegistrations());
      assertTrue(subscribe(ws, "b", "watchUnsubscribe", delivered));
      assertEquals(1, ws.retainedRegistrations(), "the second key joins the method's held namespace");
      assertThrows(IllegalArgumentException.class, () -> subscribe(ws, "c", "otherUnsubscribe", delivered),
          "the held namespace binds the first key's cancellation method");
      assertTrue(ws.unsubscribe("watchNotification", "a"));
      assertEquals(1, ws.retainedRegistrations(), "the namespace stays for its other key");
      assertFalse(subscribe(ws, "b", "watchUnsubscribe", delivered), "the remaining key is still held");
      assertTrue(ws.unsubscribe("watchNotification", "b"));
      assertEquals(0, ws.retainedRegistrations(), "the namespace leaves with its last key");

      assertTrue(subscribe(ws, "c", "otherUnsubscribe", delivered),
          "a namespace created again binds its own first cancellation method");
      assertTrue(subscribe(ws, "d", "otherUnsubscribe", delivered));
      assertEquals(1, ws.retainedRegistrations(), "the new namespace is held again by both keys");

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(2, socket.sentText.size(), socket.sentText::toString);
      assertTrue(socket.sentText.get(0).contains("\"id\":4,"), socket.sentText::toString);
      assertTrue(socket.sentText.get(1).contains("\"id\":5,"), socket.sentText::toString);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":4}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":701,"id":5}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"watchNotification","params":{"result":42,"subscription":701}}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"watchNotification","params":{"result":43,"subscription":700}}""");
      assertEquals(List.of("d:42", "c:43"), delivered);
    }
  }

  private static boolean subscribe(final SolanaJsonRpcWebsocket ws,
                                   final String key,
                                   final String unSubscribeMethod,
                                   final List<String> delivered) {
    return ws.subscribe(
        "watchSubscribe", unSubscribeMethod, "watchNotification",
        key, '"' + key + '"', JsonIterator::readLong, null, value -> delivered.add(key + ':' + value)
    );
  }
}
