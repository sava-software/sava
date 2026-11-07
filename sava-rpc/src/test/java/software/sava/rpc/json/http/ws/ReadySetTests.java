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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/// The ready set: the pending requests not yet admitted to a send, which is what a maintenance
/// pass visits. It is a subset index of the pending map, so every transition must leave each
/// pending request ready or in flight, never both and never neither; the tests here walk every
/// transition and ask the engine's own invariant seam after each one. A send that completes
/// synchronously, as the fake's do and the JDK's can, runs its `onSub` inside the pass, so
/// caller code can cancel or register requests while the pass is admitting; the last tests pin
/// what admission from the ready set does with that: a request cancelled earlier in the same
/// pass is never sent, whatever sits between it and the sender, and a registration made inside
/// the pass goes out behind the cancellations its callback queued unless a ready request with a
/// higher id remains for the pass to visit.
@ExtendWith(QuietWsLogging.class)
final class ReadySetTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  /// Large windows keep resends and pings away unless a test steps the clock over them.
  private static final Timings TIMINGS = new Timings(60_000L, 60_000L, 60_000L);
  private static final PublicKey KEY_A = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
  private static final PublicKey KEY_B = PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final PublicKey KEY_C = PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final PublicKey KEY_D = PublicKey.fromBase58Encoded("H4vnBqifaSACnKa7acsxstsY1iV1bvJNxsCY7enrd1hq");
  private static final PublicKey KEY_X = PublicKey.fromBase58Encoded("GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc");
  private static final PublicKey KEY_Y = PublicKey.fromBase58Encoded("83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri");
  private static final Pattern REQUEST_ID = Pattern.compile("\"id\":(\\d+),");

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
        null,
        null,
        null
    );
  }

  /// A key distinct per `index`, for registrations by the dozen.
  private static PublicKey key(final int index) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    bytes[PublicKey.PUBLIC_KEY_LENGTH - 1] = (byte) index;
    return PublicKey.createPubKey(bytes);
  }

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
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

  /// The request ids of the frames sent from index `from` on, in wire order.
  private static List<Long> requestIdsFrom(final RecordingWebSocket socket, final int from) {
    final var ids = new ArrayList<Long>();
    for (final var frame : socket.sentText.subList(from, socket.sentText.size())) {
      final var matcher = REQUEST_ID.matcher(frame);
      assertTrue(matcher.find(), () -> "every frame carries a request id: " + frame);
      ids.add(Long.parseLong(matcher.group(1)));
    }
    return ids;
  }

  /// The ready set's size after a transition, and its invariant against the pending and
  /// in-flight maps, which every transition must leave intact.
  private static void assertReady(final SolanaJsonRpcWebsocket ws, final int expected, final String why) {
    assertEquals(expected, ws.retainedReadySubscriptions(), why);
    assertTrue(ws.readySetMatchesPendingWork(),
        () -> "every ready request is pending and every pending request is ready or in flight: " + why);
  }

  /// Admission moves a request out of the ready set and nowhere else: the pending map, which the
  /// registration seam counts, is unchanged by a pass.
  @Test
  void aTransmittedSubscribeLeavesTheReadySet() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      }));
      assertTrue(ws.accountSubscribe(KEY_B, _ -> {
      }));
      assertTrue(ws.accountSubscribe(KEY_C, _ -> {
      }));
      assertReady(ws, 3, "registered on a live connection, no pass yet");
      final int registrations = ws.retainedRegistrations();

      ws.checkCycle(0L);

      assertEquals(List.of(2L, 3L, 4L), requestIdsFrom(socket, 0));
      assertReady(ws, 0, "every request was admitted");
      assertEquals(registrations, ws.retainedRegistrations(), "admission leaves the pending map alone");
    }
  }

  /// A request cancelled before any pass reached it leaves the ready set with its registration:
  /// nothing is sent, and since no frame can ever be answered it owes no tombstone and no
  /// attempt ordinal.
  @Test
  void aCancelledReadyRequestIsNeverSent() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      }));
      assertReady(ws, 1, "registered on a live connection, no pass yet");

      assertTrue(ws.accountUnsubscribe(KEY_A));

      assertReady(ws, 0, "cancelled before admission");
      ws.checkCycle(0L);
      assertAll(
          () -> assertEquals(List.of(), socket.sentText, "nothing to send"),
          () -> assertEquals(0, ws.retainedCancellationTombstones(), "never admitted, so no tombstone"),
          () -> assertEquals(0, ws.retainedOrdinalEntries(), "never admitted, so no ordinal"),
          () -> assertEquals(0, ws.retainedRegistrations())
      );
    }
  }

  /// A request admitted to the chain and cancelled while it waits there is in flight, not
  /// ready: its tombstone is what recalls the frame at dispatch, and the recall consumes it.
  @Test
  void aCancelledAwaitingRequestKeepsItsTombstone() {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      }));
      assertTrue(ws.accountSubscribe(KEY_B, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      socket.deferTexts = true;
      ws.onOpen(socket); // A on the wire, held open; B queued behind it
      assertEquals(1, socket.sentText.size(), socket.sentText::toString);
      assertReady(ws, 0, "both admitted: A on the wire, B in the chain");

      assertTrue(ws.accountUnsubscribe(KEY_B));

      assertReady(ws, 0, "cancelled while in flight");
      assertEquals(1, ws.retainedCancellationTombstones(), "B's tombstone recalls its frame");
      socket.deferredTexts.getFirst().complete(socket);
      assertAll(
          () -> assertEquals(1, socket.sentText.size(), () -> "B is recalled at dispatch: " + socket.sentText),
          () -> assertEquals(0, ws.retainedCancellationTombstones(), "the recall consumed the tombstone"),
          () -> assertEquals(1, ws.retainedOrdinalEntries(), "only A's transmitted attempt holds an ordinal")
      );
      assertReady(ws, 0, "after the recall");
    }
  }

  /// A rejection that blames the request is terminal: the request leaves both sets, and its
  /// key is free for a corrected registration, which is ready at once.
  @Test
  void aRequestDefectLeavesBothSetsAndFreesTheKey() {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertReady(ws, 0, "transmitted, awaiting its answer");

      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params: unsupported encoding"},"id":2}""");

      assertReady(ws, 0, "rejected: gone from both sets");
      assertEquals(0, ws.retainedRegistrations(), "the rejection released the registration");
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      }), "the rejected key is free for a corrected subscribe");
      assertReady(ws, 1, "the corrected registration is ready");
    }
  }

  /// A refusal that blames the server's condition re-arms the request on its kept stamp: ready
  /// again at once, due only once the resend window is exceeded, and admitted then.
  @Test
  void aTransientRefusalIsReadyAgainOnItsCadence() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertReady(ws, 0, "transmitted, awaiting its answer");

      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32603,"message":"Subscription refused"},"id":2}""");

      assertReady(ws, 1, "refused transiently: ready on its kept stamp");
      clock.advanceMillis(TIMINGS.subscriptionResendDelay());
      ws.checkCycle(0L);
      assertEquals(1, socket.sentText.size(), "an age equal to the resend window has not exceeded it");
      assertReady(ws, 1, "visited, not due, so not admitted");
      clock.advanceMillis(1L);
      ws.checkCycle(0L);
      assertEquals(2, socket.sentText.size(), "one millisecond past the window makes the retry due");
      assertReady(ws, 0, "re-admitted");
    }
  }

  /// A send that fails, whether its future fails or the transport throws, re-arms the request on
  /// its kept stamp: ready again at once, retried once the window is exceeded, and gated again
  /// once a retry reaches the wire. The windows here exceed the ping delay, so the peer answers
  /// the liveness ping between the retries: the connection outlives the passes, and the final
  /// gate is read on the connection that sent the retry, not on the absence of one.
  @Test
  void aFailedSendIsReadyAgainAfterTheWindow() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      socket.failText = new IOException("the frame never left");
      ws.onOpen(socket);
      assertEquals(1, socket.sentText.size());
      assertReady(ws, 1, "the failed send re-armed the request");

      socket.failText = null;
      clock.advanceMillis(1L);
      ws.checkCycle(0L);
      assertEquals(1, socket.sentText.size(), "inside the window the retry is not due");
      assertReady(ws, 1, "visited, not due, so not admitted");

      socket.throwText = new IllegalStateException("Output closed");
      clock.advanceMillis(TIMINGS.subscriptionResendDelay());
      ws.checkCycle(0L);
      assertEquals(2, socket.sentText.size(), "the window elapsing re-arms the retry");
      assertReady(ws, 1, "the transport's synchronous refusal is a failed send too");
      assertEquals(1, socket.pings, "the peer has been quiet for the ping delay: asked once");
      ws.onPong(socket, ByteBuffer.wrap(new byte[0])); // the peer is there; the pass it runs sends nothing

      socket.throwText = null;
      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L);
      ws.checkCycle(0L);
      assertEquals(3, socket.sentText.size(), "retried once more");
      assertFalse(socket.aborted, "on the connection that sent it: nothing replaced it");
      assertReady(ws, 0, "on the wire: gated until the server answers");
    }
  }

  /// A request cancelled while its frame is out, whose send then fails, stays cancelled: the
  /// failure releases its tombstone and its ordinal, and finds nothing pending to re-arm.
  @Test
  void aCancelledRequestWhoseSendFailsStaysCancelled() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      socket.deferTexts = true;
      ws.onOpen(socket);
      assertTrue(ws.accountUnsubscribe(KEY_A));
      assertEquals(1, ws.retainedCancellationTombstones(), "cancelled in flight: tombstoned");
      assertReady(ws, 0, "cancelled while in flight");

      socket.deferredTexts.getFirst().completeExceptionally(new IOException("the frame never left"));

      assertReady(ws, 0, "the cancellation wins over the failed send");
      assertAll(
          () -> assertEquals(0, ws.retainedCancellationTombstones(), "no answer can consume it"),
          () -> assertEquals(0, ws.retainedOrdinalEntries(), "the untransmitted attempt's ordinal is released"),
          () -> assertEquals(0, ws.retainedRegistrations())
      );
      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L);
      ws.checkCycle(0L);
      assertEquals(1, socket.sentText.size(), () -> "nothing to retry: " + socket.sentText);
    }
  }

  /// A transmitted request is never re-sent once answered: a grant that arrives after a
  /// transient refusal re-armed the request takes it out of the ready set, installs it, and the
  /// window elapsing sends nothing more.
  @Test
  void aGrantAfterATransientRefusalIsNotResent() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var handle = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, handle::set, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32603,"message":"Subscription refused"},"id":2}""");
      assertReady(ws, 1, "refused transiently");

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}""");

      assertReady(ws, 0, "granted: no longer pending");
      assertEquals(BigInteger.valueOf(700), handle.get().subId(), "installed");
      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L);
      ws.checkCycle(0L);
      assertEquals(1, socket.sentText.size(), () -> "a granted request is never re-sent: " + socket.sentText);
      feed(ws, socket, accountNotification(700));
      assertEquals(1, received.size(), "and its subscription delivers");
    }
  }

  /// A frame the transport reported as failed may still have reached the peer. Its grant takes
  /// the re-armed request out of the ready set and installs it, and the window elapsing sends
  /// nothing more. The failed attempt never ran `onSub`, so delivery under the granted id is
  /// the evidence that the grant was installed.
  @Test
  void aGrantAfterAFailedSendIsNotResent() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(KEY_A, received::add));
      final var socket = new RecordingWebSocket();
      socket.failText = new IOException("reported failed, delivered anyway");
      ws.onOpen(socket);
      assertReady(ws, 1, "the failed send re-armed the request");
      socket.failText = null;

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}""");

      assertReady(ws, 0, "granted: no longer pending");
      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L);
      ws.checkCycle(0L);
      assertEquals(1, socket.sentText.size(), () -> "a granted request is never re-sent: " + socket.sentText);
      feed(ws, socket, accountNotification(700));
      assertEquals(1, received.size(), "and its subscription delivers");
    }
  }

  /// A live grant cancelled by an acknowledged un-subscription whose attempt preceded it on the
  /// wire is replayed: ready, with a stamp that makes it due on the next pass.
  @Test
  void aCasualtyReplayIsReadyAndDueAtOnce() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // request 2
      assertTrue(ws.accountUnsubscribe(KEY_A)); // unanswered: tombstoned
      assertReady(ws, 0, "cancelled in flight");
      final var successor = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, successor::set, _ -> {
      }));
      assertReady(ws, 1, "the successor is registered");
      ws.checkCycle(0L); // successor request 3
      assertReady(ws, 0, "the successor is on the wire");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}"""); // tombstone: cancellation 4 of 700
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":3}"""); // the successor reuses id 700
      assertReady(ws, 0, "the successor is installed");
      assertEquals(BigInteger.valueOf(700), successor.get().subId());

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":4}""");

      assertNull(successor.get().subId(), "a grant whose attempt preceded the cancellation died with it");
      assertReady(ws, 1, "the casualty is replayed");
      ws.checkCycle(0L);
      assertEquals(2, framesContaining(socket, "\"id\":3,"), "the cancelled grant is re-sent on the next pass");
      assertReady(ws, 0, "re-admitted");
    }
  }

  /// A grant whose attempt sits below a recorded kill for its id arrives already cancelled and
  /// is replayed: ready, with a stamp that makes it due on the next pass.
  @Test
  void aKilledGrantReplayIsReadyAndDueAtOnce() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var handle = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, handle::set, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // request 2
      feed(ws, socket, accountNotification(800)); // unknown id: cancellation 3
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":3}"""); // kill of 800 recorded
      assertReady(ws, 0, "request 2 is awaiting its answer");

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":800,"id":2}""");

      assertNull(handle.get().subId(), "a grant below the kill arrived already cancelled");
      assertReady(ws, 1, "the dead grant is replayed");
      ws.checkCycle(0L);
      assertEquals(2, framesContaining(socket, "\"id\":2,"), "the dead grant is re-sent on the next pass");
      assertReady(ws, 0, "re-admitted");
    }
  }

  /// Adoption of a successor connection makes every durable registration ready with the stamp
  /// of its last attempt, so a replay inside the resend window waits the window out before
  /// anything is sent: the documented `subscriptionResendDelay` contract, pinned here, not
  /// changed.
  @Test
  void adoptionMakesEveryRegistrationReadyWithItsStamp() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      }));
      assertTrue(ws.accountSubscribe(KEY_B, _ -> {
      }));
      assertTrue(ws.accountSubscribe(KEY_C, _ -> {
      }));
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      assertEquals(3, first.sentText.size());
      assertReady(ws, 0, "every registration is on the first connection's wire");

      final var second = new RecordingWebSocket();
      ws.onOpen(second); // inside the window: stamps kept

      assertReady(ws, 3, "every registration is ready on the successor, with its stamp");
      assertEquals(List.of(), second.sentText, "nothing is sent inside the window");
      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L);
      ws.checkCycle(0L);
      assertEquals(List.of(2L, 3L, 4L), requestIdsFrom(second, 0), "the replay goes out once the window is exceeded");
      assertReady(ws, 0, "every registration was admitted");
    }
  }

  /// Admission order is ascending request id, the order the requests were registered in, both
  /// for a burst and for retries re-armed among requests still awaiting their answers; the
  /// burst is wide enough that the re-armed requests sit among many awaiting ones on the retry
  /// pass.
  @Test
  void readyAdmissionKeepsAscendingRequestIdOrder() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var socket = new RecordingWebSocket();
      socket.deferTexts = true;
      ws.onOpen(socket);
      final int registrations = 18; // requests 2 through 19
      for (int i = 1; i <= registrations; ++i) {
        assertTrue(ws.accountSubscribe(key(i), _ -> {
        }));
      }
      assertReady(ws, registrations, "registered, no pass yet");

      ws.checkCycle(0L);

      assertReady(ws, 0, "every request was admitted to the chain");
      // Each frame goes out when its predecessor settles; requests 2 and 11 fail on the wire.
      for (int i = 0; i < registrations; ++i) {
        assertEquals(i + 1, socket.sentText.size(), "one frame on the wire at a time");
        final var pending = socket.deferredTexts.get(i);
        if (i == 0 || i == 9) {
          pending.completeExceptionally(new IOException("the frame never left"));
        } else {
          pending.complete(socket);
        }
      }
      final var burst = LongStream.rangeClosed(2L, 19L).boxed().toList();
      assertEquals(burst, requestIdsFrom(socket, 0), "the burst went out in request-id order");
      assertReady(ws, 2, "the two failed sends are re-armed among the awaiting requests");

      socket.deferTexts = false;
      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L);
      ws.checkCycle(0L);

      assertEquals(List.of(2L, 11L), requestIdsFrom(socket, registrations), "the retries go out in request-id order");
      assertReady(ws, 0, "both retries were admitted");
    }
  }

  /// Admission order is the request ids' own order, and neither the order the requests became
  /// ready in nor a hash table's, which the test above cannot tell apart: its ids ascend in
  /// every one of those orders. Here request 17 is registered, and so ready, before request 3 is
  /// refused and ready again, and a hash table of sixteen buckets, which is what one stays at
  /// while the requests ready at any one moment stay this few, holds 17 in the bucket before
  /// 3's. Both are due on the same pass, which sends request 3 first.
  @Test
  void readyAdmissionOrderIsNeitherInsertionNorHashOrder() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      // Requests 2 through 16 in small waves, each admitted before the next is registered.
      for (int wave = 0; wave < 3; ++wave) {
        for (int i = 1; i <= 5; ++i) {
          assertTrue(ws.accountSubscribe(key(wave * 5 + i), _ -> {
          }));
        }
        ws.checkCycle(0L);
      }
      assertEquals(LongStream.rangeClosed(2L, 16L).boxed().toList(), requestIdsFrom(socket, 0));
      assertReady(ws, 0, "every request awaits its answer");

      assertTrue(ws.accountSubscribe(key(16), _ -> {
      })); // request 17: ready first
      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32603,"message":"Subscription refused"},"id":3}""");
      assertReady(ws, 2, "request 17 is new, and request 3 is ready again after it");

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L);
      ws.checkCycle(0L);

      assertEquals(List.of(3L, 17L), requestIdsFrom(socket, 15),
          "request 3 goes out first, although request 17 was ready before it and hashes before it");
      assertReady(ws, 0, "both were admitted");
    }
  }

  /// Ready entries die with their registrations and adjudications, not with the connection:
  /// after each terminal transition the ready set is empty and the bookkeeping seams agree.
  @Test
  void readyEntriesDieWithTheirRegistrations() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      // granted, then cancelled and acknowledged
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      }));
      ws.checkCycle(0L); // request 2
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}""");
      assertReady(ws, 0, "granted");
      assertTrue(ws.accountUnsubscribe(KEY_A));
      ws.checkCycle(0L); // cancellation 3
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":3}""");
      assertReady(ws, 0, "cancelled and acknowledged");

      // cancelled before any pass
      assertTrue(ws.accountSubscribe(KEY_B, _ -> {
      }));
      assertTrue(ws.accountUnsubscribe(KEY_B)); // request 4, never admitted
      assertReady(ws, 0, "cancelled before admission");

      // rejected as a request defect
      assertTrue(ws.accountSubscribe(KEY_C, _ -> {
      }));
      ws.checkCycle(0L); // request 5
      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params"},"id":5}""");
      assertReady(ws, 0, "rejected");

      // cancelled in flight, granted, and the grant cancelled on the wire
      assertTrue(ws.accountSubscribe(KEY_D, _ -> {
      }));
      ws.checkCycle(0L); // request 6
      assertTrue(ws.accountUnsubscribe(KEY_D)); // tombstoned
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":701,"id":6}"""); // tombstone: cancellation 7 of 701
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":7}""");
      assertReady(ws, 0, "the cancelled grant was cancelled on the wire");

      // refused transiently, then cancelled before the retry
      assertTrue(ws.accountSubscribe(KEY_X, _ -> {
      }));
      ws.checkCycle(0L); // request 8
      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32603,"message":"Subscription refused"},"id":8}""");
      assertReady(ws, 1, "refused transiently: ready for its retry");
      assertTrue(ws.accountUnsubscribe(KEY_X));
      assertReady(ws, 0, "cancelled before the retry");

      assertAll(
          () -> assertEquals(0, ws.retainedRegistrations(), "nothing registered"),
          () -> assertEquals(0, ws.retainedCancellationTombstones(), "nothing tombstoned"),
          () -> assertEquals(0, ws.retainedOrdinalEntries(), "no ordinal and no kill survives its registration")
      );
    }
  }

  /// Without a connection there is no ready set: before the first connection adopts the durable
  /// registrations, and once `close()` has dropped the connection, both seams report the empty,
  /// consistent state rather than dereference a connection that does not exist, like the
  /// tombstone and ordinal seams beside them. Non-zero first on both sides: a registration is
  /// held before the first connection, and a request is ready on the live one, so each zero is
  /// the absence of a connection and not of work.
  @Test
  void theReadySeamsAnswerWithoutAConnection() {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      }));
      assertEquals(1, ws.retainedRegistrations(), "held in the durable registry");
      assertReady(ws, 0, "registered before any connection: nothing is ready until one adopts it");

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // A adopted and sent
      assertTrue(ws.accountSubscribe(KEY_B, _ -> {
      }));
      assertReady(ws, 1, "B registered on the live connection, no pass yet");

      ws.close();

      assertTrue(ws.closed());
      assertReady(ws, 0, "closed: no connection, so no ready set");
      assertEquals(0, ws.retainedRegistrations(), "close() forgot every registration");
    }
  }

  /// The invariant seam has to be able to read false, or every assertion built on it, the fuzz
  /// harness's postcondition among them, shows nothing. No transition of a correct engine puts
  /// the registries out of step, so this test does it by hand, through the connection the engine
  /// keeps visible to its package, once for each way they can disagree: a pending request neither
  /// ready nor in flight, one both ready and in flight, and a ready entry that is not the pending
  /// registration of its id. Each is put right before the next and the seam reads true in
  /// between, so each false is that disagreement's own.
  @Test
  void theInvariantSeamReadsFalseOnEachDisagreement() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      ws.onOpen(new RecordingWebSocket());
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      }));
      ws.checkCycle(0L); // request 2 is in flight
      assertTrue(ws.accountSubscribe(KEY_B, _ -> {
      })); // request 3 is ready
      assertReady(ws, 1, "request 2 awaits its answer and request 3 its pass");
      final var conn = ws.connection;
      final var awaiting = conn.pendingSubscriptions.get(2L);
      final var ready = conn.readySubscriptions.remove(3L);
      assertNotNull(awaiting);
      assertNotNull(ready);

      assertFalse(ws.readySetMatchesPendingWork(), "request 3 is pending, yet neither ready nor in flight");
      conn.readySubscriptions.put(3L, ready);
      assertTrue(ws.readySetMatchesPendingWork(), "put right");

      conn.readySubscriptions.put(2L, awaiting);
      assertFalse(ws.readySetMatchesPendingWork(), "request 2 is both ready and in flight");
      conn.readySubscriptions.remove(2L);
      assertTrue(ws.readySetMatchesPendingWork(), "put right");

      conn.readySubscriptions.put(3L, awaiting);
      assertFalse(ws.readySetMatchesPendingWork(), "request 3's ready entry is not its pending registration");
      conn.readySubscriptions.put(3L, ready);
      assertReady(ws, 1, "put right: the engine's own state again");
    }
  }

  /// A (request 2) is due again after a transient refusal and C (request 3), registered before
  /// the pass, directly follows it with no awaiting request between them; A's re-send runs its
  /// `onSub` inside the pass, which cancels C. The pass sends A's frame and nothing else: C gets
  /// no frame, no `onSub` call, no attempt ordinal and no tombstone, and a grant naming request 3
  /// afterwards installs nothing and draws no un-subscription. The awaiting-between shape is
  /// pinned in `SubscribePipelinePinTests`; in this adjacent shape the engine used to transmit C
  /// after its cancellation, and the grant then named a server subscription nothing could cancel.
  @Test
  void aRequestCancelledByAnEarlierOnSubInTheSamePassIsNeverSent() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var cancellations = new ArrayList<Boolean>();
      final var consistentInsideThePass = new ArrayList<Boolean>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, _ -> {
        cancellations.add(ws.accountUnsubscribe(Commitment.CONFIRMED, KEY_C));
        consistentInsideThePass.add(ws.readySetMatchesPendingWork());
      }, _ -> {
      })); // A, request 2
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // sends A; its onSub finds no C to cancel yet
      assertEquals(1, socket.sentText.size(), socket.sentText::toString);
      final var frameA = socket.sentText.getFirst();
      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32603,"message":"Subscription refused"},"id":2}""");
      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L); // A is due again
      final var onSubC = new AtomicInteger();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_C, _ -> onSubC.incrementAndGet(), _ -> {
      })); // C, request 3, never attempted
      assertReady(ws, 2, "A re-armed and C registered, adjacent in the ready set");
      final int sentBefore = socket.sentText.size();

      ws.checkCycle(0L);

      assertAll(
          () -> assertEquals(List.of(false, true), cancellations,
              "A's onSub ran on each send, and its second run cancelled C inside the pass"),
          () -> assertEquals(List.of(true, true), consistentInsideThePass,
              "the ready set matched the pending work while the callback ran"),
          () -> assertEquals(List.of(frameA), socket.sentText.subList(sentBefore, socket.sentText.size()),
              "the pass sends A's re-send and nothing else"),
          () -> assertEquals(0, framesContaining(socket, "\"id\":3,"), socket.sentText::toString),
          () -> assertEquals(0, framesContaining(socket, KEY_C.toBase58()), socket.sentText::toString),
          () -> assertEquals(0, onSubC.get(), "C was never sent, so its onSub never ran"),
          () -> assertEquals(0, ws.retainedCancellationTombstones(), "C was never admitted, so it owes no tombstone"),
          () -> assertEquals(1, ws.retainedOrdinalEntries(), "only A's re-send holds an ordinal")
      );
      assertReady(ws, 0, "A is on the wire and C is gone");

      final int sentBeforeGrant = socket.sentText.size();
      final int registrationsBeforeGrant = ws.retainedRegistrations();
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":3}""");
      assertAll(
          () -> assertEquals(sentBeforeGrant, socket.sentText.size(),
              () -> "a grant for the never-sent request draws no un-subscription: " + socket.sentText),
          () -> assertEquals(registrationsBeforeGrant, ws.retainedRegistrations(), "and installs nothing"),
          () -> assertEquals(1, ws.retainedOrdinalEntries(), "request 3 still holds no ordinal")
      );
      assertReady(ws, 0, "after the stray grant");
    }
  }

  /// The ready set's order for a registration made from an `onSub` inside the pass, when a ready
  /// request with a higher id remains: the pass reaches it, so the registration goes out in the
  /// same pass, after that request. A (request 2) is due again after a transient refusal, B
  /// (request 3) is registered before the pass; A's re-send runs its `onSub`, which registers D
  /// (request 4). The pass sends A, B and D in that order.
  @Test
  void aRegistrationFromAnOnSubFollowsTheRemainingReadyRequestsInTheSamePass() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var runs = new AtomicInteger();
      final var onSubD = new AtomicInteger();
      final var registeredInsideThePass = new ArrayList<Boolean>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, _ -> {
        if (runs.incrementAndGet() == 2) {
          registeredInsideThePass.add(ws.accountSubscribe(Commitment.CONFIRMED, KEY_D, _ -> onSubD.incrementAndGet(), _ -> {
          })); // D, request 4
          registeredInsideThePass.add(ws.readySetMatchesPendingWork());
        }
      }, _ -> {
      })); // A, request 2
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32603,"message":"Subscription refused"},"id":2}""");
      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L); // A is due again
      assertTrue(ws.accountSubscribe(KEY_B, _ -> {
      })); // B, request 3
      assertReady(ws, 2, "A re-armed and B registered");
      final int sentBefore = socket.sentText.size();

      ws.checkCycle(0L);

      assertAll(
          () -> assertEquals(List.of(true, true), registeredInsideThePass,
              "A's second onSub registered D inside the pass, with the ready set consistent"),
          () -> assertEquals(List.of(2L, 3L, 4L), requestIdsFrom(socket, sentBefore),
              "the pass reaches D behind B, the ready request with a higher id that remained"),
          () -> assertEquals(1, onSubD.get(), "D's send completed inside the same pass")
      );
      assertReady(ws, 0, "every request was admitted");
    }
  }

  /// The ready set's order for a registration made from an `onSub` inside the pass, when no
  /// ready request with a higher id remains: it goes out on the immediately following pass,
  /// behind any cancellation the same callback queued, since cancellations flush first. A
  /// (request 2) is due again after a transient refusal, X (request 3) is confirmed as 700 and Y
  /// (request 4) is awaiting its answer; A's re-send runs its `onSub`, which un-subscribes X and
  /// subscribes it again as request 5. This pass sends A alone; the next sends the cancellation
  /// of 700 and then request 5. When the pass walked the pending map instead, the awaiting Y let
  /// it reach request 5 in the same pass and send it ahead of the cancellation of 700: the wire
  /// order an id-reusing server turns into a killed successor.
  @Test
  void aRegistrationFromAnOnSubWithNoReadyRequestLeftGoesOutBehindItsCancellationOnTheNextPass()
      throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var runs = new AtomicInteger();
      final var resubscribedInsideThePass = new ArrayList<Boolean>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, _ -> {
        if (runs.incrementAndGet() == 2) {
          resubscribedInsideThePass.add(ws.accountUnsubscribe(Commitment.CONFIRMED, KEY_X));
          resubscribedInsideThePass.add(ws.accountSubscribe(KEY_X, _ -> {
          })); // request 5
          resubscribedInsideThePass.add(ws.readySetMatchesPendingWork());
        }
      }, _ -> {
      })); // A, request 2
      assertTrue(ws.accountSubscribe(KEY_X, _ -> {
      })); // X, request 3
      assertTrue(ws.accountSubscribe(KEY_Y, _ -> {
      })); // Y, request 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of(2L, 3L, 4L), requestIdsFrom(socket, 0));
      final var frameA = socket.sentText.getFirst();
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":3}"""); // X confirmed
      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32603,"message":"Subscription refused"},"id":2}""");
      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L); // A is due again; Y stays awaiting
      assertReady(ws, 1, "A alone is ready");
      final int sentBefore = socket.sentText.size();

      ws.checkCycle(0L);

      assertEquals(List.of(true, true, true), resubscribedInsideThePass,
          "A's second onSub cancelled X and registered it again inside the pass, with the ready set consistent");
      assertEquals(List.of(frameA), socket.sentText.subList(sentBefore, socket.sentText.size()),
          "the pass sends A's re-send and nothing else: request 5 waits for the next pass");
      assertReady(ws, 1, "request 5 is ready for the next pass");
      final int sentBeforeNextPass = socket.sentText.size();

      ws.checkCycle(0L);

      final var nextPass = socket.sentText.subList(sentBeforeNextPass, socket.sentText.size());
      assertEquals(2, nextPass.size(), nextPass::toString);
      assertTrue(nextPass.getFirst().contains("\"method\":\"accountUnsubscribe\",\"params\":[700]"),
          () -> "the cancellation of 700 flushes first: " + nextPass);
      assertEquals(List.of(5L), requestIdsFrom(socket, sentBeforeNextPass + 1),
          () -> "then the re-subscription of X follows it: " + nextPass);
      assertTrue(nextPass.getLast().contains(KEY_X.toBase58()), nextPass::toString);
      assertEquals(1, framesContaining(socket, "\"id\":5,"), "request 5 was sent exactly once");
      assertReady(ws, 0, "request 5 is on the wire");
    }
  }
}
