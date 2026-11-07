package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.*;

/// Outbound subscribe batching. Built with a positive cap, the engine sends the subscribes one
/// maintenance pass admits as JSON-RPC batch messages, in request-id order, each element exactly
/// the frame the same request is sent as alone; un-subscriptions stay single frames, flushed ahead
/// of every array. What a request's lifecycle does to a single frame it does to each element: a
/// cancellation before transmission drops the element, a failed send re-arms each, a sent message
/// restarts each element's answer deadline and runs each `onSub`, and a cancellation is
/// adjudicated against an element by the position of the element's message on the wire. At cap 0,
/// or any cap below it, nothing is batched and each request is chained at its own admission, as
/// before batching existed; an `onSub` that cancels the next due request is what tells the two
/// orders apart. What fits under the cap is `SubscribeBatchCapTests`' subject, and inbound arrays
/// are `BatchResponseDispatchTests`'.
@ExtendWith(QuietWsLogging.class)
final class SubscribeBatchingTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final String HOST = ENDPOINT.getHost();
  /// Large windows keep resends and pings away unless a test steps the clock over them.
  private static final Timings TIMINGS = new Timings(60_000L, 60_000L, 60_000L);
  /// Agave's inbound message limit: room for every pass here to share one array.
  private static final int BATCH_BYTES = 4_096;
  /// The smallest positive cap. No two requests fit it, so each goes alone, but through the
  /// composer, which chains a link once the next due request is admitted.
  private static final int ALONE_CAP = 1;
  private static final PublicKey KEY_A = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
  private static final PublicKey KEY_B = PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final PublicKey KEY_C = PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final PublicKey KEY_D = PublicKey.fromBase58Encoded("H4vnBqifaSACnKa7acsxstsY1iV1bvJNxsCY7enrd1hq");
  private static final PublicKey KEY_H = PublicKey.fromBase58Encoded("83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri");
  private static final PublicKey KEY_X = PublicKey.fromBase58Encoded("GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc");
  private static final PublicKey PROGRAM = PublicKey.fromBase58Encoded("11111111111111111111111111111111");
  private static final String SIGNATURE =
      "5h6xBEauJ3PK6SWCZ1PGjBvj8vDdWG3KpwATGy1ARAXFSDwt8GFXM7W5Ncn16wmqokgpiKRLuS83KUxyZyv2sUYv";

  private static SolanaJsonRpcWebsocket websocket(final TestClock clock, final int subscribeBatchBytes) {
    return websocket(clock, subscribeBatchBytes, TIMINGS, null, null);
  }

  /// `onError` and `onSendTextError` are the retirement and send-failure seams a test observes;
  /// null logs instead.
  private static SolanaJsonRpcWebsocket websocket(final TestClock clock,
                                                  final int subscribeBatchBytes,
                                                  final Timings timings,
                                                  final BiConsumer<SolanaRpcWebsocket, Throwable> onError,
                                                  final BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError) {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        timings,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        clock,
        new RecordingExecutor(),
        null,
        null,
        (_, _, _) -> {
        },
        onError,
        onSendTextError,
        null,
        subscribeBatchBytes
    );
  }

  /// The frames the adoption pass sends for `registrations` made before the connection opens.
  private static List<String> sentByAdoption(final int subscribeBatchBytes,
                                             final Consumer<SolanaRpcWebsocket> registrations) {
    try (final var ws = websocket(new TestClock(), subscribeBatchBytes)) {
      registrations.accept(ws);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertTrue(ws.readySetMatchesPendingWork(), "every admitted request is in flight");
      return List.copyOf(socket.sentText);
    }
  }

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  /// A pong runs the maintenance pass, which admits the ready requests.
  private static void pong(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket) {
    ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
  }

  private static String grant(final long requestId, final long subId) {
    return "{\"jsonrpc\":\"2.0\",\"result\":" + subId + ",\"id\":" + requestId + '}';
  }

  private static String acknowledgement(final long requestId) {
    return "{\"jsonrpc\":\"2.0\",\"result\":true,\"id\":" + requestId + '}';
  }

  private static String accountNotification(final long subId) {
    return """
        {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":3},\
        "value":{"lamports":1,"data":["","base64"],"owner":"11111111111111111111111111111111",\
        "executable":false,"rentEpoch":0,"space":0}},"subscription":""" + subId + "}}";
  }

  private static long framesContaining(final RecordingWebSocket socket, final String token) {
    return socket.sentText.stream().filter(frame -> frame.contains(token)).count();
  }

  /// Position on the wire of the only frame containing `token`.
  private static int wirePosition(final RecordingWebSocket socket, final String token) {
    final var positions = IntStream.range(0, socket.sentText.size())
        .filter(i -> socket.sentText.get(i).contains(token))
        .toArray();
    assertEquals(1, positions.length, () -> token + " is not on the wire exactly once: " + socket.sentText);
    return positions[0];
  }

  /// The JSON-RPC batch array of `frames`, in order.
  private static String array(final List<String> frames) {
    return frames.stream().collect(joining(",", "[", "]"));
  }

  /// The JSON-RPC batch array of the requests' frames, in order.
  private static String arrayOf(final Collection<? extends Subscription<?>> requests) {
    return array(requests.stream().map(Subscription::msg).toList());
  }

  private static String arrayOf(final Subscription<?>... requests) {
    return arrayOf(List.of(requests));
  }

  /// A subscribe whose `onSub` files its handle under its request id.
  private static boolean subscribe(final SolanaRpcWebsocket ws,
                                   final PublicKey key,
                                   final Map<Long, Subscription<?>> handles) {
    return ws.accountSubscribe(Commitment.CONFIRMED, key, sub -> handles.put(sub.msgId(), sub), _ -> {
    });
  }

  /// The ready set's size, and its invariant against the pending and in-flight maps.
  private static void assertReady(final SolanaJsonRpcWebsocket ws, final int expected, final String why) {
    assertEquals(expected, ws.retainedReadySubscriptions(), why);
    assertTrue(ws.readySetMatchesPendingWork(),
        () -> "every ready request is pending and every pending request is ready or in flight: " + why);
  }

  /// Whether a cycle ran its pass without parking: true when a wake was remembered for it. With an
  /// interrupt already pending, a cycle that reaches its await throws there at once.
  private static boolean cycleRanWithoutParking(final SolanaJsonRpcWebsocket ws) {
    Thread.currentThread().interrupt();
    try {
      ws.checkCycle(Long.MAX_VALUE);
      return true;
    } catch (final InterruptedException _) {
      return false;
    } finally {
      Thread.interrupted();
    }
  }

  private static void registerThreeAccounts(final SolanaRpcWebsocket ws) {
    for (final var key : List.of(KEY_A, KEY_B, KEY_C)) {
      assertTrue(ws.accountSubscribe(key, _ -> {
      }));
    }
  }

  /// Batching is opt-in. The constructor without a cap sends one request per message, none of
  /// them an array; a cap of 0 through the other constructor builds the same engine, frame for
  /// frame; and a builder with nothing set, which passes its default cap of 0 through that
  /// constructor, sends no array either.
  @Test
  void batchingIsOffByDefault() {
    final var unbatched = new ArrayList<String>();
    try (final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null, TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(), new RecordingExecutor(), null, null,
        (_, _, _) -> {
        }, null, null, null)) {
      registerThreeAccounts(ws);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      unbatched.addAll(socket.sentText);
    }
    assertEquals(3, unbatched.size(), unbatched::toString);
    for (final var frame : unbatched) {
      assertFalse(frame.startsWith("["), frame);
    }
    assertEquals(unbatched, sentByAdoption(0, SubscribeBatchingTests::registerThreeAccounts), "a cap of 0 is batching off");

    try (final var ws = (SolanaJsonRpcWebsocket) new SolanaRpcWebsocketBuilder()
        .uri(ENDPOINT)
        .webSocketBuilder(new RecordingWebSocketBuilder(new AtomicReference<>()))
        .clock(new TestClock())
        .executorService(new RecordingExecutor())
        .create()) {
      registerThreeAccounts(ws);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(3, socket.sentText.size(), socket.sentText::toString);
      for (final var frame : socket.sentText) {
        assertFalse(frame.startsWith("["), () -> "the builder builds batching off: " + frame);
      }
    }
  }

  private static void registerOneOfEachTypedChannel(final SolanaRpcWebsocket ws) {
    assertTrue(ws.accountSubscribe(KEY_A, _ -> {
    }));
    assertTrue(ws.programSubscribe(PROGRAM, _ -> {
    }));
    assertTrue(ws.logsSubscribe(KEY_B, _ -> {
    }));
    assertTrue(ws.signatureSubscribe(SIGNATURE, _ -> {
    }));
    assertTrue(ws.slotSubscribe(_ -> {
    }));
    assertTrue(ws.rootSubscribe(_ -> {
    }));
  }

  /// The requests one pass admits share one array in ascending request-id order, each element
  /// byte for byte the frame the same request is sent as with batching off. The oracle is that
  /// run: the same registrations on a fresh engine without a cap, which mints the same request
  /// ids. One request of each typed channel, so every channel's rendering is carried unchanged.
  @Test
  void dueSubscribesShareOneArrayInIdOrder() {
    final var alone = sentByAdoption(0, SubscribeBatchingTests::registerOneOfEachTypedChannel);
    assertEquals(6, alone.size(), alone::toString);
    for (int i = 0; i < alone.size(); ++i) {
      final var id = "\"id\":" + (i + 2) + ',';
      assertTrue(alone.get(i).contains(id), () -> "batching off sends in request-id order: " + alone);
    }
    assertEquals(List.of(array(alone)), sentByAdoption(BATCH_BYTES, SubscribeBatchingTests::registerOneOfEachTypedChannel));
  }

  /// A pass that admits one request sends it as its own object, byte for byte what batching off
  /// sends: a server is never asked to read an array for one request.
  @Test
  void aBatchOfOneIsSentAsItsPlainObject() {
    final Consumer<SolanaRpcWebsocket> one = ws -> assertTrue(ws.accountSubscribe(KEY_A, _ -> {
    }));
    final var alone = sentByAdoption(0, one);
    assertEquals(1, alone.size(), alone::toString);
    assertEquals(alone, sentByAdoption(BATCH_BYTES, one));
    assertFalse(alone.getFirst().startsWith("["), alone::toString);
  }

  /// Un-subscriptions are never batched and still lead: the pass flushes the cancellation first,
  /// as its own frame, then sends every subscribe it admitted as one array, the resubscription of
  /// the cancelled key included. Intent order on the wire is what keeps an id-reusing server from
  /// granting the resubscription the old id and then cancelling it.
  @Test
  void cancellationsTravelAloneAheadOfTheBatch() throws InterruptedException {
    try (final var ws = websocket(new TestClock(), BATCH_BYTES)) {
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      })); // request 2
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, grant(2, 700));
      final var handles = new TreeMap<Long, Subscription<?>>();
      assertTrue(subscribe(ws, KEY_B, handles)); // request 3
      assertTrue(ws.accountUnsubscribe(KEY_A));
      assertTrue(subscribe(ws, KEY_A, handles)); // request 4, the resubscription
      assertTrue(subscribe(ws, KEY_C, handles)); // request 5
      final int before = socket.sentText.size();

      ws.checkCycle(0L);

      assertEquals(List.of(
              "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"accountUnsubscribe\",\"params\":[700]}",
              arrayOf(handles.values())
          ), socket.sentText.subList(before, socket.sentText.size()),
          "the cancellation, minted at the flush, goes alone ahead of the array of every subscribe admitted");
      assertEquals(List.of(3L, 4L, 5L), List.copyOf(handles.keySet()));
    }
  }

  /// The wire-order adjudications of `WireOrderAdjudicationTests`, each with the attempt
  /// adjacent to the cancellation as an element of a batch: the last element of the array just
  /// before the cancellation frame, or the first of the array just after it. A server runs a
  /// message's calls before it reads the next message, so an element's wire position is its
  /// message's, and each outcome is the one a single frame in that position gets. Where the
  /// batch-mate beside the adjacent element could decide the outcome too, it is answered before
  /// the acknowledgement, so the adjacent element decides it alone. Both placements of every
  /// shape assert `retainedOrdinalEntries()` exactly, the batch-mate's ordinal included.
  @Test
  void batchElementsAdjudicateByTheirMessagesPosition() throws InterruptedException {
    casualtyReplayAtTheEdgeOfABatch();
    killRecordAtTheEdgeOfABatch();
    deadGrantCheckAtTheEdgeOfABatch();
    killSweepAtTheEdgeOfABatch();
  }

  /// The casualty replay: a live grant of the cancelled id whose element is the last of the array
  /// before the cancellation is replayed; one whose element is the first of the array after it is
  /// kept.
  private static void casualtyReplayAtTheEdgeOfABatch() throws InterruptedException {
    try (final var ws = websocket(new TestClock(), BATCH_BYTES)) {
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      })); // request 2
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertTrue(ws.accountUnsubscribe(KEY_A)); // unanswered: tombstoned
      final var handles = new TreeMap<Long, Subscription<?>>();
      assertTrue(subscribe(ws, KEY_X, handles)); // request 3, the batch-mate
      assertTrue(subscribe(ws, KEY_A, handles)); // request 4, the successor
      ws.checkCycle(0L);
      assertEquals(arrayOf(handles.get(3L), handles.get(4L)), socket.sentText.getLast(),
          "the successor is the last element of its array");
      feed(ws, socket, grant(2, 700)); // the tombstone converts it: cancellation 5 of 700
      feed(ws, socket, grant(4, 700)); // the successor reuses id 700
      feed(ws, socket, grant(3, 701));
      assertEquals(wirePosition(socket, "\"id\":4,") + 1, wirePosition(socket, "[700]"),
          "the successor's message is adjacent before the cancellation");

      feed(ws, socket, acknowledgement(5));
      assertNull(handles.get(4L).subId(), "a grant whose element preceded the cancellation died with it");
      ws.checkCycle(0L);
      assertEquals(2, framesContaining(socket, "\"id\":4,"), "the cancelled grant is re-sent");
      assertEquals(BigInteger.valueOf(701), handles.get(3L).subId(), "the batch-mate keeps its own grant");
      assertEquals(2, ws.retainedOrdinalEntries(),
          "the batch-mate's grant and the successor's retry, which replaced its ordinal; request 2's died with its tombstone");
    }

    try (final var ws = websocket(new TestClock(), BATCH_BYTES)) {
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      })); // request 2
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, grant(2, 700));
      assertTrue(ws.accountUnsubscribe(KEY_A)); // the cancellation of 700 is queued
      final var handles = new TreeMap<Long, Subscription<?>>();
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, sub -> handles.put(sub.msgId(), sub),
          received::add)); // request 3, the successor
      assertTrue(subscribe(ws, KEY_X, handles)); // request 4, the batch-mate
      ws.checkCycle(0L); // the flush first: cancellation 5, then the array
      assertEquals(arrayOf(handles.get(3L), handles.get(4L)), socket.sentText.getLast(),
          "the successor is the first element of its array");
      assertEquals(wirePosition(socket, "[700]") + 1, wirePosition(socket, "\"id\":3,"),
          "the successor's message is adjacent after the cancellation");
      feed(ws, socket, grant(3, 700));
      feed(ws, socket, grant(4, 701));

      feed(ws, socket, acknowledgement(5));
      assertEquals(BigInteger.valueOf(700), handles.get(3L).subId(),
          "a grant whose element followed the cancellation postdates it");
      ws.checkCycle(0L);
      assertEquals(1, framesContaining(socket, "\"id\":3,"), "the live grant is not re-sent");
      feed(ws, socket, accountNotification(700));
      assertEquals(1, received.size(), "the kept grant still delivers");
      assertEquals(2, ws.retainedOrdinalEntries(),
          "the kept grant's and the batch-mate's; request 2's went with its mapping when it was unsubscribed");
    }
  }

  /// The kill record: a pending element last in the array before the cancellation makes the
  /// acknowledgement record a kill of the cancelled id; a pending element first in the array after
  /// it records nothing.
  private static void killRecordAtTheEdgeOfABatch() throws InterruptedException {
    try (final var ws = websocket(new TestClock(), BATCH_BYTES)) {
      final var handles = new TreeMap<Long, Subscription<?>>();
      assertTrue(subscribe(ws, KEY_X, handles)); // request 2, the batch-mate
      assertTrue(subscribe(ws, KEY_A, handles)); // request 3
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of(arrayOf(handles.values())), socket.sentText, "request 3 is the last element");
      feed(ws, socket, grant(2, 701)); // the batch-mate is answered; request 3 stays pending
      feed(ws, socket, accountNotification(800)); // unknown id: cancellation 4
      assertEquals(wirePosition(socket, "\"id\":3,") + 1, wirePosition(socket, "[800]"));

      feed(ws, socket, acknowledgement(4));
      assertEquals(3, ws.retainedOrdinalEntries(),
          "the batch-mate's mapped grant, the pending element's ordinal and the recorded kill");
    }

    try (final var ws = websocket(new TestClock(), BATCH_BYTES)) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, accountNotification(800)); // unknown id: cancellation 2
      final var handles = new TreeMap<Long, Subscription<?>>();
      assertTrue(subscribe(ws, KEY_A, handles)); // request 3
      assertTrue(subscribe(ws, KEY_X, handles)); // request 4, the batch-mate
      ws.checkCycle(0L);
      assertEquals(arrayOf(handles.values()), socket.sentText.getLast(), "request 3 is the first element");
      assertEquals(wirePosition(socket, "[800]") + 1, wirePosition(socket, "\"id\":3,"));

      feed(ws, socket, acknowledgement(2));
      assertEquals(2, ws.retainedOrdinalEntries(), "only the two postdating elements' ordinals; no kill");
    }
  }

  /// The dead-grant check: a grant of a killed id to the last element of the array before the
  /// kill's cancellation is replayed; one to the first element of the array after it is installed
  /// and consumes the kill.
  private static void deadGrantCheckAtTheEdgeOfABatch() throws InterruptedException {
    try (final var ws = websocket(new TestClock(), BATCH_BYTES)) {
      final var handles = new TreeMap<Long, Subscription<?>>();
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(subscribe(ws, KEY_X, handles)); // request 2, the batch-mate
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, sub -> handles.put(sub.msgId(), sub),
          received::add)); // request 3
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of(arrayOf(handles.values())), socket.sentText, "request 3 is the last element");
      feed(ws, socket, accountNotification(800)); // unknown id: cancellation 4
      feed(ws, socket, acknowledgement(4)); // a kill of 800 is recorded
      assertEquals(wirePosition(socket, "\"id\":3,") + 1, wirePosition(socket, "[800]"));

      feed(ws, socket, grant(3, 800));
      assertNull(handles.get(3L).subId(), "a grant below the kill arrived already cancelled");
      ws.checkCycle(0L);
      assertEquals(2, framesContaining(socket, "\"id\":3,"), "the dead grant is re-sent");
      assertEquals(3, ws.retainedOrdinalEntries(),
          "the batch-mate's, request 3's retry, and the kill: the unanswered batch-mate still predates it");
      feed(ws, socket, accountNotification(800));
      assertTrue(received.isEmpty(), "the dead grant's id delivers nothing");
    }

    try (final var ws = websocket(new TestClock(), BATCH_BYTES)) {
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      })); // request 2
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, accountNotification(800)); // unknown id: cancellation 3
      feed(ws, socket, acknowledgement(3)); // a kill of 800 is recorded
      final var handles = new TreeMap<Long, Subscription<?>>();
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_B, sub -> handles.put(sub.msgId(), sub),
          received::add)); // request 4
      assertTrue(subscribe(ws, KEY_X, handles)); // request 5, the batch-mate
      ws.checkCycle(0L);
      assertEquals(arrayOf(handles.values()), socket.sentText.getLast(), "request 4 is the first element");
      assertEquals(wirePosition(socket, "[800]") + 1, wirePosition(socket, "\"id\":4,"));

      feed(ws, socket, grant(4, 800));
      assertEquals(BigInteger.valueOf(800), handles.get(4L).subId(), "a grant above the kill is fresh");
      assertEquals(3, ws.retainedOrdinalEntries(), "three attempt ordinals; the kill is consumed");
      feed(ws, socket, accountNotification(800));
      assertEquals(1, received.size(), "the fresh grant delivers");
    }
  }

  /// The kill sweep: a kill stays while the last element of the array before its cancellation
  /// pends, since that element can still be granted the killed id, and is swept once only elements
  /// of the array after the cancellation pend.
  private static void killSweepAtTheEdgeOfABatch() throws InterruptedException {
    try (final var ws = websocket(new TestClock(), BATCH_BYTES)) {
      final var handles = new TreeMap<Long, Subscription<?>>();
      assertTrue(subscribe(ws, KEY_X, handles)); // request 2, the batch-mate
      assertTrue(subscribe(ws, KEY_A, handles)); // request 3
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of(arrayOf(handles.values())), socket.sentText, "request 3 is the last element");
      feed(ws, socket, accountNotification(800)); // unknown id: cancellation 4
      feed(ws, socket, acknowledgement(4)); // both elements predate it: a kill
      assertEquals(wirePosition(socket, "\"id\":3,") + 1, wirePosition(socket, "[800]"));
      feed(ws, socket, grant(2, 701)); // the batch-mate resolves to another id; request 3 pends

      ws.checkCycle(0L);
      assertEquals(3, ws.retainedOrdinalEntries(),
          "the predating element keeps the kill: its ordinal, the batch-mate's mapped grant and the kill");
    }

    try (final var ws = websocket(new TestClock(), BATCH_BYTES)) {
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      })); // request 2
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, accountNotification(800)); // unknown id: cancellation 3
      final var handles = new TreeMap<Long, Subscription<?>>();
      assertTrue(subscribe(ws, KEY_B, handles)); // request 4
      assertTrue(subscribe(ws, KEY_X, handles)); // request 5, the batch-mate
      ws.checkCycle(0L);
      assertEquals(arrayOf(handles.values()), socket.sentText.getLast(), "request 4 is the first element");
      assertEquals(wirePosition(socket, "[800]") + 1, wirePosition(socket, "\"id\":4,"));
      feed(ws, socket, acknowledgement(3)); // request 2 predates it: a kill
      feed(ws, socket, grant(2, 801)); // request 2 resolves to another id
      assertEquals(4, ws.retainedOrdinalEntries(), "three attempt ordinals and the kill");

      ws.checkCycle(0L);
      assertEquals(3, ws.retainedOrdinalEntries(), "only postdating elements pend: the kill is swept");
    }
  }

  /// Opens a transport whose first request, H (request 2), is held on the wire, then registers
  /// one request per key, from request 3 on, and runs a pass, which chains them as one link
  /// behind H. Each request files its handle as its `onSub` runs, once its message is sent.
  private static RecordingWebSocket queueBehindAHeldFrame(final SolanaJsonRpcWebsocket ws,
                                                          final Map<Long, Subscription<?>> handles,
                                                          final PublicKey... keys) {
    assertTrue(subscribe(ws, KEY_H, handles));
    final var socket = new RecordingWebSocket();
    socket.deferTexts = true;
    ws.onOpen(socket);
    for (final var key : keys) {
      assertTrue(subscribe(ws, key, handles));
    }
    pong(ws, socket);
    assertEquals(1, socket.sentText.size(), "the link waits behind H");
    assertReady(ws, 0, "every request was admitted");
    return socket;
  }

  /// A request cancelled while its batch waits in the outbound chain is left out of the message:
  /// the link's stage consumes its tombstone and releases its ordinal, and the others go out as
  /// one array without it. Its `onSub` never runs.
  @Test
  void aCancelledElementIsDroppedAtTransmission() {
    final var reported = new ArrayList<Throwable>();
    try (final var ws = websocket(new TestClock(), BATCH_BYTES, TIMINGS, (_, ex) -> reported.add(ex),
        (_, ex) -> reported.add(ex))) {
      final var handles = new TreeMap<Long, Subscription<?>>();
      final var socket = queueBehindAHeldFrame(ws, handles, KEY_A, KEY_B, KEY_C); // requests 3, 4, 5
      assertEquals(4, ws.retainedOrdinalEntries(), "H and the three queued requests hold ordinals");
      assertTrue(ws.accountUnsubscribe(KEY_B));
      assertEquals(1, ws.retainedCancellationTombstones(), "request 4 was admitted, so its cancellation tombstones it");

      socket.deferredTexts.getFirst().complete(socket); // H settles: the link's stage runs
      assertEquals(2, socket.sentText.size(), socket.sentText::toString);
      socket.deferredTexts.getLast().complete(socket);

      assertEquals(List.of(2L, 3L, 5L), List.copyOf(handles.keySet()), "request 4 was never sent, so its onSub never ran");
      assertEquals(arrayOf(handles.get(3L), handles.get(5L)), socket.sentText.getLast(), "the message carries 3 and 5 only");
      assertEquals(0, ws.retainedCancellationTombstones(), "the recall consumed the tombstone");
      assertEquals(3, ws.retainedOrdinalEntries(), "and released request 4's ordinal");
      assertReady(ws, 0, "nothing is left to send");
      assertEquals(List.of(), reported, "no seam reports the recall");
    }
  }

  /// A link whose every request was cancelled while it waited sends nothing, never an empty
  /// array, which a server leaves unanswered. The chain continues behind it: a later request goes
  /// out as usual, and no seam reports anything.
  @Test
  void aBatchWhoseEveryElementWasCancelledSendsNothing() {
    final var reported = new ArrayList<Throwable>();
    try (final var ws = websocket(new TestClock(), BATCH_BYTES, TIMINGS, (_, ex) -> reported.add(ex),
        (_, ex) -> reported.add(ex))) {
      final var exceptions = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(exceptions::add);
      final var handles = new TreeMap<Long, Subscription<?>>();
      final var socket = queueBehindAHeldFrame(ws, handles, KEY_A, KEY_B); // requests 3 and 4
      assertTrue(ws.accountUnsubscribe(KEY_A));
      assertTrue(ws.accountUnsubscribe(KEY_B));
      assertEquals(2, ws.retainedCancellationTombstones());

      socket.deferredTexts.getFirst().complete(socket); // H settles: the link's stage recalls both
      assertEquals(1, socket.sentText.size(), () -> "the emptied link sends nothing: " + socket.sentText);
      assertEquals(0, ws.retainedCancellationTombstones(), "the recall consumed both tombstones");
      assertEquals(1, ws.retainedOrdinalEntries(), "only H holds an ordinal");

      assertTrue(subscribe(ws, KEY_C, handles)); // request 5
      pong(ws, socket);
      assertEquals(2, socket.sentText.size(), socket.sentText::toString);
      socket.deferredTexts.getLast().complete(socket);
      assertEquals(handles.get(5L).msg(), socket.sentText.getLast(), "the chain continues behind the emptied link");
      assertFalse(socket.sentText.contains("[]"), socket.sentText::toString);
      assertEquals(List.of(2L, 5L), List.copyOf(handles.keySet()));
      assertEquals(List.of(), reported, "no seam reports the recall");
      assertEquals(List.of(), exceptions);
    }
  }

  /// A batch left with one request after the others were cancelled sends that request as its own
  /// object, exactly the frame batching off would send.
  @Test
  void aBatchLeftWithOneSurvivorIsSentAsItsPlainObject() {
    try (final var ws = websocket(new TestClock(), BATCH_BYTES)) {
      final var handles = new TreeMap<Long, Subscription<?>>();
      final var socket = queueBehindAHeldFrame(ws, handles, KEY_A, KEY_B); // requests 3 and 4
      assertTrue(ws.accountUnsubscribe(KEY_A));

      socket.deferredTexts.getFirst().complete(socket); // H settles: request 3 is recalled
      assertEquals(2, socket.sentText.size(), socket.sentText::toString);
      socket.deferredTexts.getLast().complete(socket);

      assertEquals(List.of(2L, 4L), List.copyOf(handles.keySet()));
      assertEquals(handles.get(4L).msg(), socket.sentText.getLast(), "the survivor goes out as its plain object");
      assertEquals(0, ws.retainedCancellationTombstones());
    }
  }

  /// A failed send of a batch message fails every request in it, and each is released as a single
  /// failed frame is: its in-flight entry, ordinal and tombstone go, its `onSub` does not run, and
  /// a request still pending is ready again on its kept stamp, so it is retried once the resend
  /// window is exceeded and not before. One message failed, so the failure seam hears once. The
  /// failure shapes: a failed future, a send that throws as a wrapping socket's may, and a failure
  /// that settles later, after one request of the message was cancelled.
  @Test
  void aFailedBatchSendRearmsEveryElement() throws InterruptedException {
    assertEveryElementRearmed("a failed future", socket -> socket.failText = new IOException("the transport refused the frame"), false);
    assertEveryElementRearmed("a thrown send", socket -> socket.throwText = new IllegalStateException("a wrapping socket threw"), false);
    assertEveryElementRearmed("a later failure", socket -> socket.deferTexts = true, true);
  }

  private static void assertEveryElementRearmed(final String shape,
                                                final Consumer<RecordingWebSocket> failure,
                                                final boolean failsLater) throws InterruptedException {
    final var clock = new TestClock();
    final var sendFailures = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, BATCH_BYTES, TIMINGS, null, (_, ex) -> sendFailures.add(ex))) {
      final var handles = new TreeMap<Long, Subscription<?>>();
      for (final var key : List.of(KEY_A, KEY_B, KEY_C)) {
        assertTrue(subscribe(ws, key, handles)); // requests 2, 3, 4
      }
      final var socket = new RecordingWebSocket();
      failure.accept(socket);
      ws.onOpen(socket);
      assertEquals(1, socket.sentText.size(), () -> shape + ": one message: " + socket.sentText);
      final var batch = socket.sentText.getFirst();
      if (failsLater) {
        assertTrue(ws.accountUnsubscribe(KEY_B));
        assertEquals(1, ws.retainedCancellationTombstones(), shape + ": request 3 was cancelled while its message was out");
        socket.deferredTexts.getFirst().completeExceptionally(new IOException("the transport failed the frame"));
      }

      assertAll(shape,
          () -> assertEquals(1, sendFailures.size(), () -> "one message failed: " + sendFailures),
          () -> assertEquals(0, ws.retainedOrdinalEntries(), "every element's ordinal is released"),
          () -> assertEquals(0, ws.retainedCancellationTombstones(), "and the cancelled element's tombstone with it"),
          () -> assertEquals(List.of(), List.copyOf(handles.keySet()), "no onSub runs for a failed send")
      );
      assertReady(ws, failsLater ? 2 : 3, shape + ": every element still pending is ready again, none in flight");
      socket.failText = null;
      socket.throwText = null;
      socket.deferTexts = false;

      clock.advanceMillis(TIMINGS.subscriptionResendDelay());
      ws.checkCycle(0L);
      assertEquals(1, socket.sentText.size(), shape + ": an age equal to the resend window has not exceeded it");

      clock.advanceMillis(1L);
      ws.checkCycle(0L);
      assertEquals(2, socket.sentText.size(), () -> shape + ": one millisecond past the window: " + socket.sentText);
      final var retried = failsLater ? arrayOf(handles.get(2L), handles.get(4L)) : batch;
      assertEquals(retried, socket.sentText.getLast(), shape + ": the elements still pending are retried together");
      assertEquals(failsLater ? List.of(2L, 4L) : List.of(2L, 3L, 4L), List.copyOf(handles.keySet()),
          shape + ": and run their onSub once the retry is sent");
      assertEquals(1, sendFailures.size(), shape);
    }
  }

  /// A batch message waiting in the chain is judged by its admission stamp, and once it is sent
  /// every element's deadline restarts from the transmission, the moment each `onSub` runs, once
  /// per element in array order. The request deadline then judges the server's silence, not the
  /// chain's: past the admission deadline but inside the transmission one nothing escalates, and
  /// past the transmission deadline the unanswered batch does.
  @Test
  void aSentBatchRestartsEveryDeadlineAndRunsEachOnSub() throws InterruptedException {
    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    final var onSubs = new ArrayList<Long>();
    try (final var ws = websocket(clock, BATCH_BYTES, TIMINGS, (_, ex) -> errors.add(ex), null)) {
      for (final var key : List.of(KEY_A, KEY_B, KEY_C)) {
        assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, key, sub -> onSubs.add(sub.msgId()), _ -> {
        })); // requests 2, 3, 4
      }
      final var socket = new RecordingWebSocket();
      socket.deferTexts = true;
      ws.onOpen(socket); // admits the array; the chain holds it un-transmitted
      assertEquals(1, socket.sentText.size(), socket.sentText::toString);
      assertEquals(List.of(), onSubs, "no onSub runs before its message is sent");

      clock.advanceMillis(239_000L); // just inside the admission deadline of four resend windows
      ws.checkCycle(0L);
      assertTrue(errors.isEmpty(), errors::toString);
      socket.deferredTexts.getFirst().complete(socket); // transmission: every element's clock restarts
      assertEquals(List.of(2L, 3L, 4L), onSubs, "each element's onSub ran once, in array order");
      pong(ws, socket); // the transport answers its ping; the JSON-RPC answers are still owed

      clock.advanceMillis(120_000L); // past the admission deadline, inside the transmission one
      ws.checkCycle(0L);
      assertTrue(errors.isEmpty(), () -> "every element's deadline judges the server's silence: " + errors);
      assertFalse(socket.aborted);
      pong(ws, socket);

      clock.advanceMillis(200_000L); // past the transmission deadline
      ws.checkCycle(0L);
      assertEquals(1, errors.size(), () -> "a batch the server never answers escalates: " + errors);
      assertTrue(socket.aborted);
      assertEquals(List.of(2L, 3L, 4L), onSubs, "no onSub ran twice");
    }
  }

  /// Each element is its own request to the deadline: with the rest of its batch answered, the one
  /// element the server never answers escalates exactly as a single unanswered request does, by
  /// replacing the connection and naming the request, which is never sent twice.
  @Test
  void anUnansweredBatchElementEscalatesLikeASingle() throws InterruptedException {
    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, BATCH_BYTES, TIMINGS, (_, ex) -> errors.add(ex), null)) {
      registerThreeAccounts(ws); // requests 2, 3, 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(1, socket.sentText.size(), socket.sentText::toString);
      feed(ws, socket, "[" + grant(2, 700) + "," + grant(4, 702) + "]"); // request 3 goes unanswered

      // The peer stays chatty, so no liveness gate fires: the deadline is the only watcher.
      for (int i = 0; i < SolanaJsonRpcWebsocket.UNANSWERED_ESCALATION_FACTOR - 1; ++i) {
        clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L);
        feed(ws, socket, """
            {"jsonrpc":"2.0","result":555,"id":99}""");
        ws.checkCycle(0L);
        assertTrue(errors.isEmpty(), () -> "inside the deadline nothing escalates: " + errors);
      }

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L);
      ws.checkCycle(0L);
      assertEquals(1, errors.size(), errors::toString);
      final var message = errors.getFirst().getMessage();
      assertTrue(message.startsWith("Request 3 to " + HOST + " has gone unanswered for "), message);
      assertTrue(socket.aborted, "the connection is what gets replaced");
      assertEquals(1, framesContaining(socket, "\"id\":3,"), "never a duplicate request");
    }
  }

  /// A reconnect replays the registrations in batches, within the cap, but only once the resend
  /// window is exceeded: each re-queued registration keeps its last-attempt stamp, the documented
  /// `subscriptionResendDelay` contract, so the new connection sends nothing inside the window.
  /// The cap here holds all but the last request, so both connections send the same messages.
  @Test
  void aReconnectReplaysInBatchesAfterTheResendWindow() throws InterruptedException {
    final var alone = sentByAdoption(0, SubscribeBatchingTests::registerThreeAccounts);
    final var twoShared = array(alone.subList(0, 2));
    final int cap = twoShared.getBytes(UTF_8).length;
    final var expected = List.of(twoShared, alone.get(2));
    final var clock = new TestClock();
    try (final var ws = websocket(clock, cap)) {
      registerThreeAccounts(ws);
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      assertEquals(expected, first.sentText, "the first connection sends two messages within the cap");

      final var second = new RecordingWebSocket();
      ws.onOpen(second);
      assertEquals(List.of(), second.sentText, "inside the resend window the replay sends nothing");

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L);
      ws.checkCycle(0L);
      assertEquals(expected, second.sentText, "past the window the replay goes out in the same capped messages");
      for (final var frame : second.sentText) {
        assertTrue(frame.getBytes(UTF_8).length <= cap, frame);
      }
    }
  }

  /// A registration scope's one remembered wake runs one pass, which ships everything the scope
  /// queued as one array, in request-id order. The engine here runs no loop thread, so no pass
  /// can run between the calls, scope or no scope; `RegistrationScopeTests` shows that the calls
  /// inside a scope wake nothing.
  @Test
  void aScopeShipsItsRegistrationsAsOneBatch() throws InterruptedException {
    try (final var ws = websocket(new TestClock(), BATCH_BYTES)) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.checkCycle(0L);
      assertFalse(cycleRanWithoutParking(ws), "nothing is remembered before the scope");

      final var handles = new TreeMap<Long, Subscription<?>>();
      ws.registerAll(scope -> {
        for (final var key : List.of(KEY_A, KEY_B, KEY_C, KEY_D)) {
          assertTrue(subscribe(scope, key, handles));
        }
      });
      assertEquals(List.of(), socket.sentText, "the scope queues; only a pass sends");

      assertTrue(cycleRanWithoutParking(ws), "the scope's one wake is remembered by the next cycle");
      assertEquals(List.of(2L, 3L, 4L, 5L), List.copyOf(handles.keySet()), "that one pass sent every registration");
      assertEquals(List.of(arrayOf(handles.values())), socket.sentText, "as one array, in request-id order");
    }
  }

  /// What A's `onSub` saw when it cancelled C from inside the pass that sent A, and what the pass
  /// left behind.
  private record OnSubCancellation(int readyBeforeTheCancel,
                                   int tombstonesAfterTheCancel,
                                   List<String> sent,
                                   String messageOfC,
                                   int tombstones,
                                   int ordinalEntries) {
  }

  /// Registers A (request 2), whose `onSub` cancels C, then C (request 3), and opens the
  /// connection; the adoption pass sends A, and the fake's synchronous send runs A's `onSub`
  /// inside the pass.
  private static OnSubCancellation cancelTheNextRequestFromOnSub(final int subscribeBatchBytes) {
    try (final var ws = websocket(new TestClock(), subscribeBatchBytes)) {
      final var inside = new ArrayList<Integer>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, _ -> {
        inside.add(ws.retainedReadySubscriptions());
        assertTrue(ws.accountUnsubscribe(Commitment.CONFIRMED, KEY_C), "C is registered when A's onSub runs");
        inside.add(ws.retainedCancellationTombstones());
      }, _ -> {
      }));
      final var sentC = new ArrayList<String>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_C, sub -> sentC.add(sub.msg()), _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(2, inside.size(), () -> "A's onSub ran once: " + inside);
      assertTrue(sentC.size() <= 1, sentC::toString);
      assertTrue(ws.readySetMatchesPendingWork());
      return new OnSubCancellation(inside.get(0), inside.get(1), List.copyOf(socket.sentText),
          sentC.isEmpty() ? null : sentC.getFirst(), ws.retainedCancellationTombstones(), ws.retainedOrdinalEntries());
    }
  }

  /// Cap 0 chains each request at its own admission, so when A's synchronous send runs A's
  /// `onSub`, the next due request, C, has not been admitted: it is still ready, and cancelling
  /// it leaves no tombstone, since a cancellation tombstones exactly the requests in flight. The
  /// composer chains A's link only once C's admission shows C cannot join it, so at a cap no two
  /// requests fit the same cancellation finds C admitted, tombstones it, and C's own link's stage
  /// recalls it: the wire and the seams end up as at cap 0, and only the callback can tell. At a
  /// cap both fit, A and C share one message, sent before either `onSub` runs, so the
  /// cancellation finds C on the wire, and its tombstone stays to turn C's grant into a
  /// cancellation.
  @Test
  void aRequestCancelledFromOnSubAtCapZeroLeavesNoTombstone() {
    final var atCapZero = cancelTheNextRequestFromOnSub(0);
    assertEquals(1, atCapZero.sent().size(), atCapZero::toString);
    final var frameA = atCapZero.sent().getFirst();
    assertAll("cap 0",
        () -> assertEquals(1, atCapZero.readyBeforeTheCancel(), "C was still ready: not yet admitted"),
        () -> assertEquals(0, atCapZero.tombstonesAfterTheCancel(), "so its cancellation left no tombstone"),
        () -> assertNull(atCapZero.messageOfC(), "C was never sent"),
        () -> assertEquals(0, atCapZero.tombstones()),
        () -> assertEquals(1, atCapZero.ordinalEntries(), "only A's attempt holds an ordinal")
    );

    final var apart = cancelTheNextRequestFromOnSub(ALONE_CAP);
    assertAll("a cap no two requests fit",
        () -> assertEquals(0, apart.readyBeforeTheCancel(), "C was admitted before A's link was chained"),
        () -> assertEquals(1, apart.tombstonesAfterTheCancel(), "so its cancellation tombstoned it"),
        () -> assertEquals(List.of(frameA), apart.sent(), "and its own link's stage recalled it: A alone was sent"),
        () -> assertNull(apart.messageOfC(), "C was never sent"),
        () -> assertEquals(0, apart.tombstones(), "the recall consumed the tombstone"),
        () -> assertEquals(1, apart.ordinalEntries(), "and released C's ordinal")
    );

    final var shared = cancelTheNextRequestFromOnSub(BATCH_BYTES);
    assertAll("a cap both requests fit",
        () -> assertEquals(0, shared.readyBeforeTheCancel()),
        () -> assertEquals(1, shared.tombstonesAfterTheCancel(), "C was already on the wire beside A"),
        () -> assertNotNull(shared.messageOfC(), "C was sent, so its onSub ran"),
        () -> assertEquals(List.of(array(List.of(frameA, shared.messageOfC()))), shared.sent()),
        () -> assertEquals(1, shared.tombstones(), "the tombstone waits for C's grant"),
        () -> assertEquals(2, shared.ordinalEntries(), "both transmitted attempts hold ordinals")
    );
  }

  /// A cap below 0 is batching off in the maintenance pass, exactly as 0 is: each due request is
  /// chained at its own admission, so A's `onSub` still finds C ready, and its cancellation leaves
  /// no tombstone. The composer would send every request alone at such a cap, the same frames
  /// cap 0 sends, so only the callback's view tells the two chaining points apart. The oracle is
  /// the cap-0 engine driven the same way, compared reading for reading; the router's half of the
  /// rule is pinned in `BatchResponseDispatchTests`.
  @Test
  void aNegativeCapIsBatchingOffLikeZero() {
    final var atCapZero = cancelTheNextRequestFromOnSub(0);
    for (final int negative : new int[]{-1, Integer.MIN_VALUE}) {
      assertEquals(atCapZero, cancelTheNextRequestFromOnSub(negative), () -> "cap " + negative);
    }
  }

  /// Wire ordinals are minted when a link is chained, never when its stage runs. A (request 2) is
  /// held on the wire, and B and C (requests 3 and 4) are chained behind it as one array; then a
  /// notification for an unknown id draws a cancellation of 800 on the direct path, chained
  /// behind the array while it still waits. The wire is A, the array, the cancellation, so both
  /// elements precede the cancellation, and its acknowledgement records a kill of 800 against
  /// them. B's grant of 800 then arrives already dead and is replayed, while C's grant of another
  /// id installs. Had the elements taken their ordinals when the array's stage ran, after the
  /// cancellation was minted, B's grant would have counted as postdating the kill and been
  /// installed under an id the server had cancelled: a subscription that receives nothing.
  @Test
  void aCancellationChainedBehindAQueuedBatchPostdatesEveryElement() throws InterruptedException {
    try (final var ws = websocket(new TestClock(), BATCH_BYTES)) {
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      })); // request 2
      final var socket = new RecordingWebSocket();
      socket.deferTexts = true;
      ws.onOpen(socket); // A's send is held open
      final var handles = new TreeMap<Long, Subscription<?>>();
      final var receivedC = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(subscribe(ws, KEY_B, handles)); // request 3
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_C, sub -> handles.put(sub.msgId(), sub),
          receivedC::add)); // request 4
      pong(ws, socket); // chains requests 3 and 4 as one link behind A
      assertEquals(3, ws.retainedOrdinalEntries(), "each queued request holds its ordinal already");
      feed(ws, socket, accountNotification(800)); // unknown id: cancellation 5, minted now
      assertEquals(1, socket.sentText.size(), "the array and the cancellation wait behind A");

      socket.deferredTexts.get(0).complete(socket); // A settles: the array goes out
      socket.deferredTexts.get(1).complete(socket); // the array settles: the cancellation goes out
      socket.deferredTexts.get(2).complete(socket);
      assertEquals(List.of(
          socket.sentText.getFirst(),
          arrayOf(handles.get(3L), handles.get(4L)),
          "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"accountUnsubscribe\",\"params\":[800]}"
      ), socket.sentText, "A, the array, then the cancellation");

      feed(ws, socket, acknowledgement(5));
      assertEquals(4, ws.retainedOrdinalEntries(),
          "the three attempts sent before the cancellation are unanswered, so the acknowledgement records a kill");

      feed(ws, socket, "[" + grant(3, 800) + "," + grant(4, 801) + "]");
      assertNull(handles.get(3L).subId(), "B's grant of 800 preceded the cancellation of 800: it arrived already dead");
      assertEquals(BigInteger.valueOf(801), handles.get(4L).subId(), "C's grant of another id installs");
      assertEquals(4, ws.retainedOrdinalEntries(),
          "A's, C's, B's until its retry replaces it, and the kill, which a grant below it leaves");

      ws.checkCycle(0L);
      assertEquals(2, framesContaining(socket, "\"id\":3,"), "B is replayed");
      assertEquals(4, ws.retainedOrdinalEntries(), "B's retry holds a fresh ordinal, and A still predates the kill");
      feed(ws, socket, accountNotification(801));
      assertEquals(1, receivedC.size(), "C's grant delivers");
    }
  }

  /// The NEVER stamp is due under any resend delay: a maximal delay means no retry between
  /// attempts, never no first attempt, so the first batch goes out at once.
  @Test
  void aMaximalResendDelayStillSendsTheFirstBatch() {
    final var maximal = new Timings(60_000L, 60_000L, 60_000L, 120_000L, Long.MAX_VALUE);
    try (final var ws = websocket(new TestClock(), BATCH_BYTES, maximal, null, null)) {
      final var handles = new TreeMap<Long, Subscription<?>>();
      assertTrue(subscribe(ws, KEY_A, handles));
      assertTrue(subscribe(ws, KEY_B, handles));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of(2L, 3L), List.copyOf(handles.keySet()), "the initial send is immediate under a maximal resend delay");
      assertEquals(List.of(arrayOf(handles.values())), socket.sentText);
    }
  }

  /// The composer admits through the ready map's own removal, as the cap-0 loop does. At a cap no
  /// two requests fit, A's link is chained once B is admitted, and A's synchronous send runs A's
  /// `onSub`, which cancels C. The pass's iterator already holds C, but C was never admitted: the
  /// removal finds it gone, so C gets no frame, no `onSub`, no ordinal and no tombstone. The
  /// batching counterpart of `ReadySetTests.aRequestCancelledByAnEarlierOnSubInTheSamePassIsNeverSent`.
  @Test
  void aRequestCancelledByAnEarlierOnSubIsNeverSentWhileBatching() {
    try (final var ws = websocket(new TestClock(), ALONE_CAP)) {
      final var handles = new TreeMap<Long, Subscription<?>>();
      final var cancellations = new ArrayList<Boolean>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, sub -> {
        handles.put(sub.msgId(), sub);
        cancellations.add(ws.accountUnsubscribe(Commitment.CONFIRMED, KEY_C));
      }, _ -> {
      })); // A, request 2
      assertTrue(subscribe(ws, KEY_B, handles)); // B, request 3
      final var onSubC = new AtomicInteger();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_C, _ -> onSubC.incrementAndGet(), _ -> {
      })); // C, request 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      assertAll(
          () -> assertEquals(List.of(true), cancellations, "A's onSub cancelled C inside the pass"),
          () -> assertEquals(List.of(handles.get(2L).msg(), handles.get(3L).msg()), socket.sentText,
              "the pass sends A and B, each alone"),
          () -> assertEquals(0, framesContaining(socket, "\"id\":4,"), socket.sentText::toString),
          () -> assertEquals(0, onSubC.get(), "C was never sent, so its onSub never ran"),
          () -> assertEquals(0, ws.retainedCancellationTombstones(), "C was never admitted, so it owes no tombstone"),
          () -> assertEquals(2, ws.retainedOrdinalEntries(), "only A and B hold ordinals")
      );
      assertReady(ws, 0, "A and B are on the wire and C is gone");
    }
  }
}
