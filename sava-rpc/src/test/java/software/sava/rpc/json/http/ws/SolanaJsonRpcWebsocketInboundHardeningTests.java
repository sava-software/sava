package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.JsonRpcException;
import software.sava.rpc.json.http.response.ProcessedSlot;
import software.sava.rpc.json.http.response.TxResult;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Defensive JSON-RPC correlation regressions. Every frame here is untrusted peer input; the
/// oracle is the registration and wire state exposed by the public listener/subscribe API, not
/// the parser's current branch structure.
@ExtendWith(QuietWsLogging.class)
final class SolanaJsonRpcWebsocketInboundHardeningTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000L, 60_000L, 60_000L);
  private static final PublicKey ACCOUNT =
      PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final String SIGNATURE =
      "2EBVM6cB8vAAD93Ktr6Vd8p67XPbQzCJX47MpReuiCXJAtcjaxpvWpcg9Ege1Nr5Tk3a2GFrByT7WPBjdsTycY9b";
  private static final long CALLBACK_BOUND_MILLIS = 1_000L;

  private static SolanaJsonRpcWebsocket websocket() {
    return websocket(null);
  }

  private static SolanaJsonRpcWebsocket websocket(final List<Throwable> transportErrors) {
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
        transportErrors == null ? null : (_, error) -> transportErrors.add(error),
        null,
        null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws,
                           final RecordingWebSocket socket,
                           final String json) {
    ws.onText(socket, json, true);
  }

  private static long sent(final RecordingWebSocket socket, final String method) {
    return socket.sentText.stream().filter(frame -> frame.contains("\"method\":\"" + method + '"')).count();
  }

  /// Signals after onText has resolved the callback's connection but before it reaches any
  /// correlation lock. Holding that lock lets a test install the successor in the gap without
  /// sleeps, polling, or a production hook.
  private record TakeoverFrame(String json,
                               CountDownLatch resolved,
                               AtomicBoolean checkpointed) implements CharSequence {

    @Override
    public int length() {
      checkpointed.set(true);
      resolved.countDown();
      return json.length();
    }

    @Override
    public char charAt(final int index) {
      return json.charAt(index);
    }

    @Override
    public CharSequence subSequence(final int start, final int end) {
      return json.subSequence(start, end);
    }
  }

  private static void feedAcrossTakeover(final SolanaJsonRpcWebsocket ws,
                                         final RecordingWebSocket displaced,
                                         final RecordingWebSocket successor,
                                         final String json) throws InterruptedException {
    final var resolved = new CountDownLatch(1);
    final var checkpointed = new AtomicBoolean();
    final var retainedHolds = new AtomicInteger(-1);
    final var callbackFailure = new AtomicReference<Throwable>();
    final var callback = Thread.ofPlatform().unstarted(() -> {
      try {
        ws.onText(displaced, new TakeoverFrame(json, resolved, checkpointed), true);
      } catch (final Throwable t) {
        callbackFailure.set(t);
      } finally {
        // Keep a mutated/failed callback from stranding the lifecycle lock on this exiting
        // thread and turning the remainder of the suite into a watchdog result.
        resolved.countDown();
        retainedHolds.set(ws.lock.getHoldCount());
        while (ws.lock.isHeldByCurrentThread()) {
          ws.lock.unlock();
        }
      }
    });

    ws.lock.lock();
    try {
      callback.start();
      assertTrue(resolved.await(CALLBACK_BOUND_MILLIS, TimeUnit.MILLISECONDS),
          "callback did not reach the post-connection-resolution checkpoint");
      assertTrue(checkpointed.get(), "callback exited before resolving the displaced connection");
      ws.onOpen(successor);
    } finally {
      ws.lock.unlock();
      joinBounded(callback, "takeover callback");
    }
    assertEquals(0, retainedHolds.get(), "callback retained the lifecycle lock");
    assertNull(callbackFailure.get(), () -> "callback escaped: " + callbackFailure.get());
  }

  private static void feedAndAssertCallbackLockReleased(final SolanaJsonRpcWebsocket ws,
                                                        final RecordingWebSocket socket,
                                                        final String json,
                                                        final String path) throws InterruptedException {
    assertLifecycleLockReleased(ws, path + " setup");
    final var retainedHolds = new AtomicInteger(-1);
    final var callbackFailure = new AtomicReference<Throwable>();
    final var callback = Thread.ofPlatform().unstarted(() -> {
      try {
        feed(ws, socket, json);
      } catch (final Throwable t) {
        callbackFailure.set(t);
      } finally {
        retainedHolds.set(ws.lock.getHoldCount());
        while (ws.lock.isHeldByCurrentThread()) {
          ws.lock.unlock();
        }
      }
    });
    callback.start();
    joinBounded(callback, path);
    // Assert the lock contract before the parent thread re-enters the websocket in any way.
    assertEquals(0, retainedHolds.get(), path + " retained the lifecycle lock");
    assertNull(callbackFailure.get(), () -> path + " callback escaped: " + callbackFailure.get());
  }

  private static void joinBounded(final Thread callback, final String path) throws InterruptedException {
    callback.join(CALLBACK_BOUND_MILLIS);
    if (callback.isAlive()) {
      callback.interrupt();
      callback.join(CALLBACK_BOUND_MILLIS);
    }
    assertFalse(callback.isAlive(), path + " did not complete within the deterministic bound");
  }

  private static void assertLifecycleLockReleased(final SolanaJsonRpcWebsocket ws,
                                                  final String path) {
    final int retainedHolds = ws.lock.getHoldCount();
    // A removed finally-unlock mutant leaves this test thread holding a re-entrant lock. Clean
    // it up before failing so try-with-resources can still close the subject.
    while (ws.lock.isHeldByCurrentThread()) {
      ws.lock.unlock();
    }
    assertEquals(0, retainedHolds, path + " retained the lifecycle lock");
  }

  /// WebSocket.Listener promises CharSequence, not CharBuffer. Fragment reassembly must preserve
  /// JSON tokens split at arbitrary callback boundaries and dispatch exactly once.
  @Test
  void stringFragmentsSplitAcrossJsonTokensDispatchExactlyOnce() {
    try (final var ws = websocket()) {
      final var slots = new ArrayList<ProcessedSlot>();
      assertTrue(ws.slotSubscribe(slots::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      ws.onText(socket, "{\"jsonrpc\":\"2.0\",\"res", false);
      ws.onText(socket, "ult\":17,\"id\":2}", true);
      ws.onText(socket, "{\"jsonrpc\":\"2.0\",\"method\":\"slotNotifi", false);
      ws.onText(socket, "cation\",\"par", false);
      ws.onText(socket,
          "ams\":{\"result\":{\"parent\":15,\"root\":16,\"slot\":17},\"subscription\":17}}", true);

      assertEquals(1, slots.size());
      assertEquals(17L, slots.getFirst().slot());
    }
  }

  /// Frame validation excludes JSON-splicing controls and accepts the first printable codepoint.
  /// U+001F/U+0020 are the independent boundary oracle for the `c < 0x20` contract.
  @Test
  void genericMethodValidationPinsTheControlCharacterBoundary() {
    try (final var ws = websocket()) {
      assertTrue(ws.subscribe("watch Subscribe", "watch Unsubscribe", "watch Notification",
          "space", "", JsonIterator::readLong, null, _ -> {
          }), "space is printable and cannot splice the JSON string");
      assertThrows(IllegalArgumentException.class,
          () -> ws.subscribe("watch\u001fSubscribe", "watchUnsubscribe", "watchNotification",
              "control", "", JsonIterator::readLong, null, _ -> {
              }));
    }
  }

  /// A numeric result for no pending request and a null result for a pending request are neither
  /// subscription grants nor unsubscribe acknowledgements. They must not consume the pending
  /// registration or manufacture a parser exception.
  @Test
  void responseResultTypeControlsCorrelation() {
    try (final var ws = websocket()) {
      final var errors = new ArrayList<RuntimeException>();
      final var received = new ArrayList<Long>();
      ws.exceptionSubscribe(errors::add);
      assertTrue(ws.subscribe("watchSubscribe", "watchUnsubscribe", "watchNotification",
          "watch", "", JsonIterator::readLong, null, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":91,\"id\":999}");
      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":null,\"id\":2}");
      assertTrue(errors.isEmpty(), () -> "non-grant responses were dispatched as failures: " + errors);

      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":91,\"id\":2}");
      feed(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"method\":\"watchNotification\",\"params\":{\"result\":7,\"subscription\":91}}");
      assertEquals(List.of(7L), received, "the null result must leave the real grant correlatable");
    }
  }

  /// A signature notification with a null value is not terminal evidence. It is ignored, and a
  /// later well-typed notification for the same id still reaches the live consumer.
  @Test
  void nullSignatureResultDoesNotRetireOrDispatchTheSubscription() {
    try (final var ws = websocket()) {
      final var errors = new ArrayList<RuntimeException>();
      final var received = new ArrayList<TxResult>();
      ws.exceptionSubscribe(errors::add);
      assertTrue(ws.signatureSubscribe(SIGNATURE, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":44,\"id\":2}");

      feed(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"method\":\"signatureNotification\",\"params\":{\"result\":{\"context\":{\"slot\":1},\"value\":null},\"subscription\":44}}");
      feed(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"method\":\"signatureNotification\",\"params\":{\"result\":{\"context\":{\"slot\":2},\"value\":\"receivedSignature\"},\"subscription\":44}}");

      assertTrue(errors.isEmpty(), () -> "a null signature result became a parser failure: " + errors);
      assertEquals(1, received.size());
      assertEquals("receivedSignature", received.getFirst().value());
    }
  }

  /// The notification method and subscription id are a pair. A root frame carrying another
  /// root generation's id is cancelled and cannot be delivered to the current singleton.
  @Test
  void staleRootSubscriptionIdCannotReachTheCurrentConsumer() {
    try (final var ws = websocket()) {
      final var roots = new ArrayList<Long>();
      assertTrue(ws.rootSubscribe(roots::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":10,\"id\":2}");

      feed(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"method\":\"rootNotification\",\"params\":{\"result\":99,\"subscription\":11}}");

      assertTrue(roots.isEmpty());
      assertEquals(1L, sent(socket, "rootUnsubscribe"));
      assertTrue(socket.sentText.getLast().contains("[11]"), socket.sentText.toString());
    }
  }

  /// An automatically-minted cancellation has no request fingerprint. If the id is subsequently
  /// granted to a live subscription, a transient error for that old cancellation is obsolete;
  /// it remains the server's JsonRpcException, not a null-dereference in correlation code.
  @Test
  void nullFingerprintCancellationErrorIsCorrelatedAgainstItsLiveSuccessor() throws InterruptedException {
    final var transportErrors = new ArrayList<Throwable>();
    try (final var ws = websocket(transportErrors)) {
      final var errors = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      feed(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"method\":\"accountNotification\",\"params\":{\"result\":{\"context\":{\"slot\":1},\"value\":{\"data\":[\"\",\"base64\"],\"executable\":false,\"lamports\":1,\"owner\":\"11111111111111111111111111111111\",\"rentEpoch\":0,\"space\":0}},\"subscription\":700}}");
      assertEquals(1L, sent(socket, "accountUnsubscribe")); // cancellation id 2

      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      ws.checkCycle(0L); // subscribe id 3
      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":700,\"id\":3}");
      feed(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"},\"id\":2}");

      assertEquals(1, errors.size());
      assertEquals(-32_603L, assertInstanceOf(JsonRpcException.class, errors.getFirst()).code());
      assertTrue(transportErrors.isEmpty());
    }
  }

  /// A cancellation fingerprint excludes the request id, so reconnect/retry generations of the
  /// same request are equivalent. A transient rejection of the predecessor's cancellation must
  /// not classify that live equivalent successor as an ambiguous id collision.
  @Test
  void equalFingerprintCancellationErrorLeavesItsLiveSuccessorConnected() throws InterruptedException {
    final var transportErrors = new ArrayList<Throwable>();
    try (final var ws = websocket(transportErrors)) {
      final var errors = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);
      assertTrue(ws.signatureSubscribe(SIGNATURE, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":700,\"id\":2}");

      assertTrue(ws.signatureUnsubscribe(SIGNATURE));
      ws.checkCycle(0L); // cancellation id 3
      assertTrue(ws.signatureSubscribe(SIGNATURE, _ -> {
      }));
      ws.checkCycle(0L); // byte-identical successor id 4
      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":700,\"id\":4}");
      feed(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"},\"id\":3}");

      assertFalse(socket.aborted);
      assertTrue(transportErrors.isEmpty());
      assertEquals(1, errors.size(), "the correlated server error still reaches the exception consumer");
      assertEquals(-32_603L, assertInstanceOf(JsonRpcException.class, errors.getFirst()).code());
    }
  }

  /// The stale-id wording heuristic applies only when the response id names no request this
  /// connection owns. Correlation, not the server's prose, decides whether an error is delivered.
  @Test
  void invalidSubscriptionIdWordingIsDeliveredOnlyForACorrelatedRequest() {
    try (final var ws = websocket()) {
      final var errors = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      final var error = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Invalid subscription id: stale\"},\"id\":";
      feed(ws, socket, error + "2}");
      assertEquals(1, errors.size(), "a pending request id makes the error correlated");

      feed(ws, socket, error + "999}");
      assertEquals(1, errors.size(), "the same wording from an unknown id is the stale-error case");
    }
  }

  /// A malformed peer may omit the error message, but the parsed JSON-RPC code is still useful
  /// consumer evidence. The stale-id wording check must dispatch that original exception rather
  /// than dereference its absent message and replace it with an outer-catch null-pointer failure.
  @Test
  void uncorrelatedErrorWithoutAMessageDispatchesTheOriginalJsonRpcException() {
    try (final var ws = websocket()) {
      final var errors = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32603},"id":999}""");

      assertEquals(1, errors.size());
      final var error = assertInstanceOf(JsonRpcException.class, errors.getFirst());
      assertEquals(-32_603L, error.code());
      assertNull(error.getMessage());
    }
  }

  /// A request-defect response already inside the displaced parser must not remove the durable
  /// registration which adoption has just replayed onto the successor connection.
  @Test
  void staleErrorInsideTheParserCannotReleaseTheSuccessorsRegistration() throws InterruptedException {
    try (final var ws = websocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      final var displaced = new RecordingWebSocket();
      ws.onOpen(displaced);

      final var successor = new RecordingWebSocket();
      feedAcrossTakeover(ws, displaced, successor,
          "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"Invalid params\"},\"id\":2}");

      assertFalse(ws.accountSubscribe(ACCOUNT, _ -> {
      }), "the successor still owns the durable account key");
    }
  }

  /// A confirmation from a displaced socket may finish parsing after takeover, but its server id
  /// belongs to that old connection. The public handle replayed on the successor stays unconfirmed.
  @Test
  void staleConfirmationInsideTheParserCannotConfirmTheSuccessor() throws InterruptedException {
    try (final var ws = websocket()) {
      final var handle = new AtomicReference<Subscription<?>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, ACCOUNT, handle::set, _ -> {
      }));
      final var displaced = new RecordingWebSocket();
      ws.onOpen(displaced);
      assertNotNull(handle.get());

      final var successor = new RecordingWebSocket();
      feedAcrossTakeover(ws, displaced, successor,
          "{\"jsonrpc\":\"2.0\",\"result\":900,\"id\":2}");

      assertNull(handle.get().subId(), "only the successor socket may confirm its replayed handle");
    }
  }

  /// Terminal signature delivery from a displaced socket may finish, but it cannot release the
  /// durable signature key which takeover has already replayed for the successor connection.
  @Test
  void staleTerminalSignatureInsideTheParserCannotReleaseTheSuccessor() throws InterruptedException {
    try (final var ws = websocket()) {
      assertTrue(ws.signatureSubscribe(SIGNATURE, _ -> {
      }));
      final var displaced = new RecordingWebSocket();
      ws.onOpen(displaced);
      feed(ws, displaced, "{\"jsonrpc\":\"2.0\",\"result\":44,\"id\":2}");

      final var successor = new RecordingWebSocket();
      feedAcrossTakeover(ws, displaced, successor,
          "{\"jsonrpc\":\"2.0\",\"method\":\"signatureNotification\",\"params\":{\"result\":{\"context\":{\"slot\":2},\"value\":{\"err\":null}},\"subscription\":44}}");

      assertFalse(ws.signatureSubscribe(SIGNATURE, _ -> {
      }), "the successor still owns the durable signature key");
    }
  }

  /// A settled cancellation releases retirement only when no later cancellation for that id is
  /// queued. This checks both acknowledgement values and observes the distinction on the wire.
  @Test
  void acknowledgementKeepsRetirementWhileALaterCancellationIsQueued() throws InterruptedException {
    assertQueuedCancellationKeepsRetirement(true);
    assertQueuedCancellationKeepsRetirement(false);
  }

  private static void assertQueuedCancellationKeepsRetirement(final boolean result) throws InterruptedException {
    try (final var ws = websocket()) {
      assertTrue(ws.signatureSubscribe(SIGNATURE, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":700,\"id\":2}");

      assertTrue(ws.signatureUnsubscribe(SIGNATURE));
      ws.checkCycle(0L); // first cancellation, id 3
      assertTrue(ws.signatureSubscribe(SIGNATURE, _ -> {
      }));
      ws.checkCycle(0L); // successor subscribe, id 4
      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":700,\"id\":4}");
      assertTrue(ws.signatureUnsubscribe(SIGNATURE));
      ws.checkCycle(0L); // second cancellation remains queued behind id 3

      feedAndAssertCallbackLockReleased(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"result\":" + result + ",\"id\":3}",
          result + " queued acknowledgement");
      assertEquals(1L, sent(socket, "signatureUnsubscribe"));
      feed(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"method\":\"signatureNotification\",\"params\":{\"result\":{\"context\":{\"slot\":2},\"value\":{\"err\":null}},\"subscription\":700}}");

      assertEquals(1L, sent(socket, "signatureUnsubscribe"),
          "a retired id must not consume or bypass the queued later cancellation");
      assertLifecycleLockReleased(ws, result + " acknowledgement");
    }
  }

  /// With no queued successor, either boolean acknowledgement settles the retirement. A later
  /// frame for the now-unknown id therefore mints a fresh defensive cancellation.
  @Test
  void acknowledgementReleasesRetirementWhenTheCancellationIsSettled() throws InterruptedException {
    assertSettledAcknowledgementReleasesRetirement(true);
    assertSettledAcknowledgementReleasesRetirement(false);
  }

  private static void assertSettledAcknowledgementReleasesRetirement(final boolean result)
      throws InterruptedException {
    try (final var ws = websocket()) {
      assertTrue(ws.signatureSubscribe(SIGNATURE, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":700,\"id\":2}");
      assertTrue(ws.signatureUnsubscribe(SIGNATURE));
      ws.checkCycle(0L); // cancellation id 3
      feedAndAssertCallbackLockReleased(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"result\":" + result + ",\"id\":3}",
          result + " settled acknowledgement");

      feed(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"method\":\"signatureNotification\",\"params\":{\"result\":{\"context\":{\"slot\":2},\"value\":{\"err\":null}},\"subscription\":700}}");
      assertEquals(2L, sent(socket, "signatureUnsubscribe"),
          "the settled id is unknown again and must draw fresh compensation");
      assertLifecycleLockReleased(ws, result + " acknowledgement");
    }
  }

  /// A true acknowledgement records kill evidence only for pending attempts which preceded it
  /// on the wire. A postdating request remains the sole ordinal entry.
  @Test
  void acknowledgementDoesNotMarkPostdatingPendingRequestAsKilled() throws InterruptedException {
    try (final var ws = websocket()) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"method\":\"accountNotification\",\"params\":{\"result\":{\"context\":{\"slot\":1},\"value\":{\"data\":[\"\",\"base64\"],\"executable\":false,\"lamports\":1,\"owner\":\"11111111111111111111111111111111\",\"rentEpoch\":0,\"space\":0}},\"subscription\":800}}");
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      ws.checkCycle(0L); // request 3 follows cancellation 2

      feedAndAssertCallbackLockReleased(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"result\":true,\"id\":2}",
          "postdating-request acknowledgement");

      assertEquals(1, ws.retainedOrdinalEntries(),
          "only the postdating pending subscribe retains an attempt ordinal");
    }
  }

  /// Kill evidence for an id is obsolete when that id is granted to an attempt transmitted
  /// after the cancellation. Removing it is observable bookkeeping: live/pending registrations,
  /// not an already-adjudicated id, are the only remaining ordinal owners.
  @Test
  void aPostdatingGrantConsumesEarlierKillEvidence() throws InterruptedException {
    try (final var ws = websocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // account A request 2 predates the cancellation
      feed(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"method\":\"accountNotification\",\"params\":{\"result\":{\"context\":{\"slot\":1},\"value\":{\"data\":[\"\",\"base64\"],\"executable\":false,\"lamports\":1,\"owner\":\"11111111111111111111111111111111\",\"rentEpoch\":0,\"space\":0}},\"subscription\":800}}");
      feedAndAssertCallbackLockReleased(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"result\":true,\"id\":3}",
          "predating-attempt acknowledgement"); // records kill 800

      final var second = PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
      assertTrue(ws.accountSubscribe(second, _ -> {
      }));
      ws.checkCycle(0L); // account B request 4 postdates the cancellation
      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":800,\"id\":4}");

      assertEquals(2, ws.retainedOrdinalEntries(),
          "the two registrations retain ordinals; the postdated kill evidence is consumed");
    }
  }

  /// When a cancellation acknowledgement proves a mapped grant was killed, the public handle
  /// becomes unconfirmed before replay. The caller must not observe the cancelled server id.
  @Test
  void casualtyAcknowledgementClearsThePublicSubscriptionId() throws InterruptedException {
    try (final var ws = websocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // predecessor id 2
      assertTrue(ws.accountUnsubscribe(ACCOUNT));

      final var successor = new AtomicReference<Subscription<?>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, ACCOUNT, successor::set, _ -> {
      }));
      ws.checkCycle(0L); // successor id 3 precedes the compensation
      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":700,\"id\":2}"); // cancellation id 4
      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":700,\"id\":3}");
      assertNotNull(successor.get());
      assertNotNull(successor.get().subId());

      feedAndAssertCallbackLockReleased(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"result\":true,\"id\":4}",
          "casualty acknowledgement");

      assertNull(successor.get().subId(), "a grant killed on the wire is unconfirmed until replayed");
      assertLifecycleLockReleased(ws, "casualty acknowledgement");
    }
  }

  /// Coalescing is safe only for byte-identical requests. A cancelled identical loser resolving
  /// to the live owner's id is consumed locally; it must not abort or cancel that owner.
  @Test
  void tombstonedEquivalentGrantLeavesItsLiveOwnerUntouched() {
    final var transportErrors = new ArrayList<Throwable>();
    try (final var ws = websocket(transportErrors)) {
      final var ownerValues = new ArrayList<Long>();
      assertTrue(ws.subscribe("fooSubscribe", "fooUnsubscribe", "fooNotification",
          "owner", "\"same\"", JsonIterator::readLong, null, ownerValues::add));
      assertTrue(ws.subscribe("fooSubscribe", "fooUnsubscribe", "fooNotification",
          "loser", "\"same\"", JsonIterator::readLong, null, _ -> {
          }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":55,\"id\":2}");
      assertTrue(ws.unsubscribe("fooNotification", "loser"));
      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":55,\"id\":3}");

      assertFalse(socket.aborted);
      assertTrue(transportErrors.isEmpty());
      assertEquals(0L, sent(socket, "fooUnsubscribe"));
      feed(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"method\":\"fooNotification\",\"params\":{\"result\":9,\"subscription\":55}}");
      assertEquals(List.of(9L), ownerValues);
    }
  }

  /// Request-defect retirement and terminal signature cleanup both leave the lifecycle lock and
  /// caller-failure dispatch in a usable state.
  @Test
  void inboundTerminalPathsReleaseTheLockAndReportSignatureConsumerFailure() {
    try (final var ws = websocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"Invalid params\"},\"id\":2}");
      assertLifecycleLockReleased(ws, "request-defect retirement");
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }), "the terminal rejection released the durable key");
    }

    try (final var ws = websocket()) {
      final var failures = new ArrayList<RuntimeException>();
      final var boom = new IllegalStateException("signature consumer failed");
      ws.exceptionSubscribe(failures::add);
      assertTrue(ws.signatureSubscribe(SIGNATURE, _ -> {
        throw boom;
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":44,\"id\":2}");
      feed(ws, socket,
          "{\"jsonrpc\":\"2.0\",\"method\":\"signatureNotification\",\"params\":{\"result\":{\"context\":{\"slot\":2},\"value\":{\"err\":null}},\"subscription\":44}}");

      assertEquals(List.of(boom), failures);
      assertEquals(0, ws.retainedRegistrations());
      assertLifecycleLockReleased(ws, "terminal signature notification");
    }
  }
}
