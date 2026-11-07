package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.client.TestLogs;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.JsonRpcException;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.*;

/// The fallback from batch messages to single requests. A server that cannot read a batch message
/// at all answers it with one parse error carrying no id, which the engine attributes to the
/// oldest batch it still awaits an answer for, provided no request transmitted before that batch
/// is unanswered: an in-order server answers that one first. The attributed rejection concludes
/// every element's attempt and re-sends each request alone at once, and batching stops for the
/// instance. Nothing else releases a batch: a null-id error of any other code, one inside a batch
/// response, a correlated error, an error on a displaced connection, and an error while an older
/// request is unanswered all leave every gate where it is. Nothing else stops batching for good:
/// a connection lost with a batch of its own still wholly unanswered, by a peer close, a transport
/// error, the unanswered-request deadline or the caller's own `connect()`, makes the one
/// connection adopted next send one request per message, and the connection after that batches
/// again; a declared budget of single subscribe messages the replay would exceed makes that
/// connection batch instead. Every new diagnostic is pinned by its exact template and parameters.
@ExtendWith(QuietWsLogging.class)
final class BatchRejectionFallbackTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final String HOST = ENDPOINT.getHost();
  /// Large windows keep resends and pings away unless a test steps the clock over them.
  private static final Timings TIMINGS = new Timings(60_000L, 60_000L, 60_000L);
  private static final long WINDOW = TIMINGS.subscriptionResendDelay();
  /// Past the unanswered-request deadline, which is a multiple of the resend window.
  private static final long PAST_THE_DEADLINE = WINDOW * SolanaJsonRpcWebsocket.UNANSWERED_ESCALATION_FACTOR + 1L;
  /// Agave's inbound message limit: room for every pass here to share one array.
  private static final int BATCH_BYTES = 4_096;
  private static final PublicKey KEY_A = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
  private static final PublicKey KEY_B = PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final PublicKey KEY_C = PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final PublicKey KEY_D = PublicKey.fromBase58Encoded("H4vnBqifaSACnKa7acsxstsY1iV1bvJNxsCY7enrd1hq");
  private static final PublicKey KEY_E = PublicKey.fromBase58Encoded("83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri");
  private static final String REJECTED_WHOLE =
      "{0} rejected a batch of {1} requests as a whole; re-sending them one per message, and batching no more.";
  private static final String SINGLE_REQUESTS_AFTER_A_LOSS =
      "Connection to {0} sends one request per message: its predecessor was lost with a batch unanswered.";
  private static final String BATCHES_DESPITE_THE_LOSS =
      "Connection to {0} batches although its predecessor was lost with a batch unanswered: a replay of {1} subscriptions one per message would exceed the {2} the endpoint allows.";
  /// Agave's answer, verbatim, to a message it could not read at all.
  private static final String PARSE_ERROR_NULL_ID =
      "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Parse error\"},\"id\":null}";
  /// An answer to a request this engine never sent: peer traffic that correlates with nothing.
  private static final String UNCORRELATED_ANSWER = "{\"jsonrpc\":\"2.0\",\"result\":555,\"id\":99}";
  private static final Pattern REQUEST_ID = Pattern.compile("\"id\":(\\d+)");
  private static final Pattern ESCALATED_REQUEST =
      Pattern.compile("^Request (\\d+) to " + Pattern.quote(HOST) + " has gone unanswered for ");

  private static SolanaJsonRpcWebsocket websocket(final TestClock clock) {
    return websocket(clock, null);
  }

  /// An engine with a declared budget of single subscribe messages per connection.
  private static SolanaJsonRpcWebsocket budgetedWebsocket(final TestClock clock, final int singleSubscribeBudget) {
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
        null,
        BATCH_BYTES,
        singleSubscribeBudget
    );
  }

  /// An engine whose `connect()` can be driven: `builder` supplies the socket, and a scheduler is
  /// present for the connect throttle.
  private static SolanaJsonRpcWebsocket connectableWebsocket(final TestClock clock, final RecordingWebSocketBuilder builder) {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        builder,
        TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        clock,
        new RecordingExecutor(),
        new RecordingScheduler(),
        null,
        (_, _, _) -> {
        },
        null,
        null,
        null,
        BATCH_BYTES
    );
  }

  /// `onError` is the retirement seam an escalation and a transport error reach; null logs and
  /// closes the instance instead. The close handler keeps the instance open, so a dropped
  /// connection can be replaced by the next `onOpen`.
  private static SolanaJsonRpcWebsocket websocket(final TestClock clock,
                                                  final BiConsumer<SolanaRpcWebsocket, Throwable> onError) {
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
        onError,
        null,
        null,
        BATCH_BYTES
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  /// A pong runs the maintenance pass, which admits the ready requests.
  private static void pong(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket) {
    ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
  }

  /// One maintenance pass on the test thread, never parking.
  private static void pass(final SolanaJsonRpcWebsocket ws) {
    try {
      ws.checkCycle(0L);
    } catch (final InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError("the pass was interrupted", ex);
    }
  }

  /// Runs one cycle with no time limit while an interrupt is pending, and answers whether it ran
  /// its pass without parking, which on an open websocket means a wake was remembered (the
  /// registration scope idiom). Without one the cycle reaches the await, which consumes the
  /// interrupt and throws at once, so neither answer blocks. The `finally` clear is load-bearing:
  /// a cycle that skips the await leaves the interrupt pending.
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

  private static String grant(final long requestId, final long subId) {
    return "{\"jsonrpc\":\"2.0\",\"result\":" + subId + ",\"id\":" + requestId + '}';
  }

  /// Agave's answer when its node-wide subscription limit is full: a server condition, so the
  /// request stays pending and is retried on the resend cadence.
  private static String refusal(final long requestId) {
    return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Subscription refused\"},\"id\":"
        + requestId + '}';
  }

  private static String acknowledgement(final long requestId) {
    return "{\"jsonrpc\":\"2.0\",\"result\":true,\"id\":" + requestId + '}';
  }

  private static String nullIdError(final int code, final String message) {
    return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + code + ",\"message\":\"" + message + "\"},\"id\":null}";
  }

  private static String array(final List<String> elements) {
    return elements.stream().collect(joining(",", "[", "]"));
  }

  private static void registerAccounts(final SolanaRpcWebsocket ws, final PublicKey... keys) {
    for (final var key : keys) {
      assertTrue(ws.accountSubscribe(key, _ -> {
      }), key.toBase58());
    }
  }

  /// Every request id a frame names, in frame order: one for a plain request, each element's for
  /// an array.
  private static List<Long> requestIdsIn(final String frame) {
    final var ids = new ArrayList<Long>();
    final var matcher = REQUEST_ID.matcher(frame);
    while (matcher.find()) {
      ids.add(Long.parseLong(matcher.group(1)));
    }
    return ids;
  }

  private static List<String> framesFrom(final RecordingWebSocket socket, final int from) {
    return List.copyOf(socket.sentText.subList(from, socket.sentText.size()));
  }

  private static long framesContaining(final RecordingWebSocket socket, final String token) {
    return socket.sentText.stream().filter(frame -> frame.contains(token)).count();
  }

  /// One batch message naming exactly `requestIds`, in that order.
  private static void assertArrayOf(final String frame, final Long... requestIds) {
    assertTrue(frame.startsWith("[") && frame.endsWith("]"), () -> "not a batch message: " + frame);
    assertEquals(List.of(requestIds), requestIdsIn(frame), frame);
  }

  /// One plain message per request, in request-id order, none of them an array.
  private static void assertSingles(final List<String> frames, final Long... requestIds) {
    for (final var frame : frames) {
      assertFalse(frame.startsWith("["), () -> "a batch message where a plain request was expected: " + frame);
    }
    assertEquals(List.of(requestIds), frames.stream().map(frame -> requestIdsIn(frame).getFirst()).toList(),
        frames::toString);
  }

  /// Adopts `replacement` and returns the engine's log records of the adoption, where a suspension
  /// of batching is reported, since that is where it takes effect.
  private static List<LogRecord> adopt(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket replacement) {
    return TestLogs.capture(SolanaJsonRpcWebsocket.class, () -> ws.onOpen(replacement));
  }

  /// Replaces the connection and runs the first pass past the resend window: a re-queued
  /// registration keeps its last-attempt stamp, so the replacement sends nothing inside it.
  /// Returns the adoption's log records, as [#adopt] does.
  private static List<LogRecord> replayAfterTheWindow(final SolanaJsonRpcWebsocket ws,
                                                      final TestClock clock,
                                                      final RecordingWebSocket replacement) {
    final var records = adopt(ws, replacement);
    assertEquals(List.of(), replacement.sentText, "inside the resend window the replacement sends nothing");
    clock.advanceMillis(WINDOW + 1L);
    pass(ws);
    return records;
  }

  /// The request id the one escalation named, read from its message, since the deadline scan
  /// names whichever overdue entry it visits first.
  private static long escalatedRequest(final List<Throwable> errors) {
    assertEquals(1, errors.size(), errors::toString);
    final var message = errors.getFirst().getMessage();
    final var matcher = ESCALATED_REQUEST.matcher(message);
    assertTrue(matcher.find(), message);
    return Long.parseLong(matcher.group(1));
  }

  private static String messages(final List<LogRecord> records) {
    final var joined = new StringBuilder();
    for (final var record : records) {
      joined.append(record.getMessage());
      final var params = record.getParameters();
      if (params != null) {
        for (final var param : params) {
          joined.append(" | ").append(param);
        }
      }
      joined.append('\n');
    }
    return joined.toString();
  }

  /// Exactly one record at `level` with this exact template, and exactly these parameters: the
  /// `WsDiagnosticLogTests` idiom, since a template alone passes whichever branch built it.
  private static LogRecord assertDiagnostic(final List<LogRecord> records,
                                            final Level level,
                                            final String template,
                                            final Object... parameters) {
    final var matches = records.stream()
        .filter(record -> level.equals(record.getLevel()) && template.equals(record.getMessage()))
        .toList();
    assertEquals(1, matches.size(), () -> "expected one " + level + " record for '" + template
        + "', got:\n" + messages(records));
    final var record = matches.getFirst();
    final var actualParameters = record.getParameters();
    assertArrayEquals(parameters, actualParameters == null ? new Object[0] : actualParameters,
        "diagnostic parameters classify the specific protocol outcome");
    return record;
  }

  private static void assertNoDiagnostic(final List<LogRecord> records, final String template) {
    assertTrue(records.stream().noneMatch(record -> template.equals(record.getMessage())),
        () -> "expected no record for '" + template + "', got:\n" + messages(records));
  }

  /// A message whose first `length()` call runs `takeover`. `onText` asks for the length only
  /// after resolving the connection its callback belongs to, so the takeover lands between that
  /// resolution and the parse (the `BatchResponseDispatchTests` idiom).
  private static final class TakeoverOnRead implements CharSequence {

    private final String value;
    private Runnable takeover;

    private TakeoverOnRead(final String value, final Runnable takeover) {
      this.value = value;
      this.takeover = takeover;
    }

    boolean tookOver() {
      return takeover == null;
    }

    @Override
    public int length() {
      final var pending = takeover;
      if (pending != null) {
        takeover = null;
        pending.run();
      }
      return value.length();
    }

    @Override
    public char charAt(final int index) {
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(final int start, final int end) {
      return value.subSequence(start, end);
    }

    @Override
    public String toString() {
      return value;
    }
  }

  private interface InterruptibleTransition {

    void run() throws InterruptedException;
  }

  /// A removed unlock on a reentrant path does not block its own caller; it leaves that caller
  /// owning one extra hold, which only becomes a hang when another thread enters later. Capture
  /// that same-thread fact first, drain every leaked hold in `finally`, then fail synchronously.
  private static void assertCallingThreadReleasedLifecycleLock(final SolanaJsonRpcWebsocket ws,
                                                               final InterruptibleTransition transition)
      throws InterruptedException {
    assertEquals(0, ws.lock.getHoldCount(), "the transition must start without a borrowed hold");
    int retainedHoldCount;
    try {
      transition.run();
    } finally {
      retainedHoldCount = ws.lock.getHoldCount();
      while (ws.lock.isHeldByCurrentThread()) {
        ws.lock.unlock();
      }
    }
    assertEquals(0, retainedHoldCount,
        "the transition returned while its calling/completion thread still owned the lifecycle lock");
  }

  /// Parks a test-owned stand-in for the maintenance loop on the production condition, then
  /// performs one transition which promises to wake that loop (the registry hardening idiom).
  /// Holding the lifecycle lock while checking `hasWaiters` makes both sides deterministic:
  /// acquiring it proves awaitNanos has enqueued and released, and a successful signal transfers
  /// the waiter off the condition queue before the triggering transition unlocks. A transferred
  /// waiter needs only the lifecycle lock, which the calling thread has released by then, so its
  /// thread is joined with no deadline; the bounded joins, and the interrupt between them, clean
  /// up after a signal that never came, so a broken transition fails instead of hanging.
  private static void assertSignalsAlreadyParkedCycle(final SolanaJsonRpcWebsocket ws,
                                                      final Runnable transition)
      throws InterruptedException {
    final var beforeAwait = new CompletableFuture<Void>();
    final var cycleCompleted = new CompletableFuture<Void>();
    final var cycleFailure = new AtomicReference<Throwable>();
    final var cycle = new Thread(() -> {
      ws.lock.lock();
      try {
        beforeAwait.complete(null);
        //noinspection ResultOfMethodCallIgnored
        ws.newSubscription.awaitNanos(Long.MAX_VALUE);
      } catch (final Throwable ex) {
        cycleFailure.set(ex);
      } finally {
        while (ws.lock.isHeldByCurrentThread()) {
          ws.lock.unlock();
        }
        cycleCompleted.complete(null);
      }
    }, "parked-websocket-maintenance-test");
    cycle.start();

    boolean enteredAwait = false;
    boolean signalTransferred = false;
    try {
      CompletableFuture.anyOf(beforeAwait, cycleCompleted).join();
      enteredAwait = beforeAwait.isDone();
      if (enteredAwait) {
        ws.lock.lock();
        try {
          assertTrue(ws.lock.hasWaiters(ws.newSubscription),
              "the checkpoint must own an already-parked maintenance cycle");
        } finally {
          while (ws.lock.isHeldByCurrentThread()) {
            ws.lock.unlock();
          }
        }

        assertCallingThreadReleasedLifecycleLock(ws, transition::run);

        ws.lock.lock();
        try {
          signalTransferred = !ws.lock.hasWaiters(ws.newSubscription);
        } finally {
          while (ws.lock.isHeldByCurrentThread()) {
            ws.lock.unlock();
          }
        }
      }
    } finally {
      if (signalTransferred) {
        // Off the condition queue, the waiter needs only the lock this thread has released, so
        // the join ends however late the cycle thread is scheduled.
        cycle.join();
      } else {
        // Independent cleanup for a missing transition signal, bounded so that a broken
        // transition fails the assertions below instead of hanging here.
        ws.close();
        cycle.join(1_000L);
        if (cycle.isAlive()) {
          cycle.interrupt();
          cycle.join(1_000L);
        }
      }
    }

    assertTrue(enteredAwait);
    assertTrue(signalTransferred,
        "the lifecycle transition must transfer an already-parked maintenance cycle");
    assertFalse(cycle.isAlive());
    assertNull(cycleFailure.get());
  }

  /// A batch of three answered with Agave's parse error: the rejection is reported with the batch's
  /// size, the server's error still reaches the exception subscribers, every element's attempt is
  /// concluded (gate, ordinal) and the elements are ready again at once, so the next pass, with no
  /// clock step, sends each alone in request-id order, byte-identical to its element. Batching is
  /// over for the instance, and once the singles are granted nothing is left in flight to
  /// escalate or to send again.
  @Test
  void aNullIdErrorAnsweringABatchReSendsItsRequestsAlone() {
    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, (_, ex) -> errors.add(ex))) {
      final var dispatched = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(dispatched::add);
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(1, socket.sentText.size(), socket.sentText::toString);
      final var batch = socket.sentText.getFirst();
      assertArrayOf(batch, 2L, 3L, 4L);
      assertEquals(1, ws.retainedOutstandingBatches(), "the batch is ledgered");
      assertFalse(ws.batchingRefused());

      final var records = TestLogs.capture(SolanaJsonRpcWebsocket.class, () -> feed(ws, socket, PARSE_ERROR_NULL_ID));

      assertDiagnostic(records, Level.WARNING, REJECTED_WHOLE, HOST, 3);
      assertEquals(1, dispatched.size(), "the server's error still reaches the exception subscribers");
      final var ex = assertInstanceOf(JsonRpcException.class, dispatched.getFirst());
      assertEquals(JsonRpcException.PARSE_ERROR, ex.code());
      assertTrue(ex.requestId().isEmpty(), "an id:null answer names no request");
      assertAll("the rejection concludes every attempt in the batch",
          () -> assertEquals(0, ws.retainedOutstandingBatches(), "the rejected batch leaves the ledger"),
          () -> assertEquals(0, ws.retainedOrdinalEntries(), "every element's ordinal dies with its attempt"),
          () -> assertEquals(3, ws.retainedReadySubscriptions(), "every element is ready again"),
          () -> assertTrue(ws.readySetMatchesPendingWork(), "and none is still in flight"),
          () -> assertTrue(ws.batchingRefused(), "batching is over for the instance"),
          () -> assertEquals(1, socket.sentText.size(), "nothing is re-sent before a pass runs")
      );

      pass(ws); // no clock step: a released request is due at once
      final var singles = framesFrom(socket, 1);
      assertSingles(singles, 2L, 3L, 4L);
      assertEquals(batch, array(singles), "each single is byte-identical to its element");
      assertTrue(ws.readySetMatchesPendingWork());

      for (long id = 2; id <= 4; ++id) {
        feed(ws, socket, grant(id, 700 + id));
      }
      clock.advanceMillis(PAST_THE_DEADLINE);
      pass(ws);
      assertTrue(errors.isEmpty(), () -> "the granted singles leave nothing in flight to escalate: " + errors);
      assertEquals(4, socket.sentText.size(), "and nothing is sent twice");
    }
  }

  /// A request cancelled while its batch was out carries a tombstone, which the rejection releases
  /// without re-sending the request: the server never ran the attempt, so no grant can ever
  /// consume the tombstone, and the registration is gone. Its batch-mates are re-sent alone.
  @Test
  void aCancelledElementOfARejectedBatchIsReleasedWithoutARetry() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertArrayOf(socket.sentText.getFirst(), 2L, 3L, 4L);
      assertTrue(ws.accountUnsubscribe(KEY_B));
      assertEquals(1, ws.retainedCancellationTombstones(), "request 3 was cancelled while its message was out");

      feed(ws, socket, PARSE_ERROR_NULL_ID);

      assertEquals(0, ws.retainedCancellationTombstones(), "no grant can consume the tombstone now");
      pass(ws);
      assertSingles(framesFrom(socket, 1), 2L, 4L);
      assertEquals(1, framesContaining(socket, "\"id\":3,"), "the cancelled request is never re-sent");
      assertTrue(ws.readySetMatchesPendingWork());
    }
  }

  /// After a rejection the instance batches no more: later registrations, on the same connection
  /// and on a replacement's replay, go out one per message, whatever the configured cap.
  @Test
  void aRejectedBatchStopsBatchingForTheInstance() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, PARSE_ERROR_NULL_ID);
      pass(ws);
      assertSingles(framesFrom(socket, 1), 2L, 3L, 4L);

      registerAccounts(ws, KEY_D, KEY_E); // requests 5, 6
      pong(ws, socket);
      assertSingles(framesFrom(socket, 4), 5L, 6L);

      final var replacement = new RecordingWebSocket();
      replayAfterTheWindow(ws, clock, replacement);
      assertSingles(replacement.sentText, 2L, 3L, 4L, 5L, 6L);
      assertTrue(ws.batchingRefused());
    }
  }

  /// With batching on but only single messages out, a null-id parse error is what it always was:
  /// dispatched, attributable to no request, releasing nothing.
  @Test
  void aNullIdErrorWithNoBatchOutstandingReleasesNothing() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var dispatched = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(dispatched::add);
      registerAccounts(ws, KEY_A); // request 2
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      registerAccounts(ws, KEY_B); // request 3
      pong(ws, socket);
      assertSingles(socket.sentText, 2L, 3L);
      assertEquals(0, ws.retainedOutstandingBatches(), "a lone request is never ledgered");

      feed(ws, socket, PARSE_ERROR_NULL_ID);

      assertEquals(1, dispatched.size());
      assertFalse(ws.batchingRefused(), "nothing was attributed, so nothing was refused");
      pass(ws);
      assertEquals(2, socket.sentText.size(), "both requests stay gated: an uncorrelatable error releases nothing");
      assertFalse(ws.accountSubscribe(KEY_A, _ -> {
      }), "an uncorrelatable error must not release the registration");
    }
  }

  /// A link left with one request goes out as its plain object and is not ledgered: no
  /// whole-batch rejection can answer a message that is not a batch, so the parse error leaves it
  /// gated and batching on. The composer here has one due request, so the message was a plain
  /// request from the start.
  @Test
  void aNullIdErrorAnsweringALoneRequestReleasesNothing() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A); // request 2
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertSingles(socket.sentText, 2L);
      assertEquals(0, ws.retainedOutstandingBatches());

      feed(ws, socket, PARSE_ERROR_NULL_ID);

      pass(ws);
      assertEquals(1, socket.sentText.size(), "the request stays gated");
      assertFalse(ws.batchingRefused());
      assertEquals(0, ws.retainedReadySubscriptions(), "not re-armed");
    }
  }

  /// A cancellation flushed ahead of the batch is still unanswered when the parse error arrives:
  /// an in-order server answers the cancellation first, so the error cannot be the batch's, and
  /// nothing is released. Once the cancellation is acknowledged the next parse error is the
  /// batch's.
  @Test
  void anOlderUnansweredRequestBlocksAttribution() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A); // request 2
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, grant(2, 700));
      assertTrue(ws.accountUnsubscribe(KEY_A)); // the cancellation of 700 is queued
      registerAccounts(ws, KEY_B, KEY_C); // requests 3, 4
      pong(ws, socket); // flushes the cancellation as request 5, then the batch
      assertEquals(3, socket.sentText.size(), socket.sentText::toString);
      assertTrue(socket.sentText.get(1).contains("accountUnsubscribe"), socket.sentText.get(1));
      assertArrayOf(socket.sentText.get(2), 3L, 4L);

      feed(ws, socket, PARSE_ERROR_NULL_ID);

      pass(ws);
      assertAll("the cancellation transmitted before the batch is unanswered, so the error is not the batch's",
          () -> assertEquals(3, socket.sentText.size(), socket.sentText::toString),
          () -> assertEquals(1, ws.retainedOutstandingBatches()),
          () -> assertFalse(ws.batchingRefused())
      );

      feed(ws, socket, acknowledgement(5));
      feed(ws, socket, PARSE_ERROR_NULL_ID);

      pass(ws);
      assertSingles(framesFrom(socket, 3), 3L, 4L);
      assertTrue(ws.batchingRefused());
    }
  }

  /// A batch the server answered is never released: its elements left the in-flight gate with
  /// their grants, so a later parse error finds no batch to attribute and batching stays on.
  @Test
  void anAnsweredBatchIsNeverReleased() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, array(List.of(grant(2, 700), grant(3, 701), grant(4, 702))));
      assertEquals(1, ws.retainedOutstandingBatches(), "answered, and pruned only when next consulted");

      feed(ws, socket, PARSE_ERROR_NULL_ID);

      pass(ws);
      assertAll(
          () -> assertEquals(1, socket.sentText.size(), "nothing is re-sent"),
          () -> assertEquals(0, ws.retainedOutstandingBatches(), "the answered batch was pruned, not released"),
          () -> assertFalse(ws.batchingRefused()),
          () -> assertFalse(ws.accountSubscribe(KEY_A, _ -> {
          }), "the grants stand")
      );
    }
  }

  /// Every element refused transiently and retried in a new batch: the retry's fresh ordinals make
  /// the old batch answered, so it is pruned when the new one is pushed, and the parse error
  /// releases the new batch whole, a request the old one never held included.
  @Test
  void aRetriedElementLeavesItsOldBatchAnswered() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B); // requests 2, 3
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertArrayOf(socket.sentText.getFirst(), 2L, 3L);
      feed(ws, socket, array(List.of(refusal(2), refusal(3))));
      assertEquals(1, ws.retainedOutstandingBatches(), "the refused batch is answered but not yet pruned");
      registerAccounts(ws, KEY_C); // request 4
      clock.advanceMillis(WINDOW + 1L);
      pong(ws, socket);
      assertArrayOf(socket.sentText.get(1), 2L, 3L, 4L);
      assertEquals(1, ws.retainedOutstandingBatches(), "pushing the retry pruned the answered batch");

      feed(ws, socket, PARSE_ERROR_NULL_ID);

      assertEquals(0, ws.retainedOutstandingBatches(), "the retry's batch is the one released");
      pass(ws);
      assertSingles(framesFrom(socket, 2), 2L, 3L, 4L);
    }
  }

  /// A ledger entry records the ordinals its own link minted, not the ones its requests hold by
  /// the time the link runs. A peer that answers requests before their link ran concludes those
  /// attempts while the link still waits in the chain; the requests are admitted again on their
  /// cadence and chained again behind it, and when the chain drains both links go out. The first
  /// link's attempts were concluded before it ran, so its entry reads answered from the moment it
  /// is ledgered and leaves at the next push: the ledger holds the live attempt's entry alone,
  /// never two entries that both read unanswered for the same requests, and that one entry is
  /// what a parse error releases.
  @Test
  void aLinkChainedAgainBehindItselfIsLedgeredUnderItsOwnOrdinals() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var socket = new RecordingWebSocket();
      socket.deferTexts = true;
      ws.onOpen(socket);
      assertTrue(ws.signatureSubscribe("ahead", _ -> {
      })); // request 2: its send stays open, so everything chained after it waits
      pong(ws, socket);
      registerAccounts(ws, KEY_A, KEY_B); // requests 3, 4
      pong(ws, socket);
      assertEquals(1, socket.sentText.size(), "the batch waits in the chain behind request 2");

      feed(ws, socket, refusal(3));
      feed(ws, socket, refusal(4));
      clock.advanceMillis(WINDOW + 1L);
      pong(ws, socket); // both are due again, and are chained again behind their first link
      assertEquals(1, socket.sentText.size(), "still behind request 2");
      assertEquals(0, ws.retainedOutstandingBatches(), "nothing is ledgered before its link runs");

      for (int sent = 0; sent < 3; ++sent) {
        socket.deferredTexts.get(sent).complete(socket);
      }

      assertEquals(3, socket.sentText.size(), socket.sentText::toString);
      assertArrayOf(socket.sentText.get(1), 3L, 4L);
      assertArrayOf(socket.sentText.get(2), 3L, 4L);
      assertEquals(1, ws.retainedOutstandingBatches(),
          "the first link's entry read answered, and left when the second was pushed");

      socket.deferTexts = false;
      feed(ws, socket, grant(2, 700));
      feed(ws, socket, PARSE_ERROR_NULL_ID);

      assertEquals(0, ws.retainedOutstandingBatches(), "the one live entry was released");
      assertTrue(ws.batchingRefused());
      pass(ws);
      assertSingles(framesFrom(socket, 3), 3L, 4L);
    }
  }

  /// A request cancelled while its link waits in the chain is left out of the message, and the
  /// requests behind it keep the ordinals the link minted for them, past the gap it leaves. The
  /// entry records each survivor under its own, so the batch reads unanswered while it is, and a
  /// parse error releases it; an entry that numbered its survivors from the link's first ordinal
  /// would read answered from the start and be passed over.
  @Test
  void aPartlyRecalledBatchIsLedgeredUnderEachSurvivorsOwnOrdinal() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var socket = new RecordingWebSocket();
      socket.deferTexts = true;
      ws.onOpen(socket);
      assertTrue(ws.signatureSubscribe("ahead", _ -> {
      })); // request 2: its send stays open, so the batch chained after it waits
      pong(ws, socket);
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 3, 4, 5
      pong(ws, socket);
      assertTrue(ws.accountUnsubscribe(KEY_A), "request 3 is recalled while its link waits");

      socket.deferredTexts.get(0).complete(socket);

      assertEquals(2, socket.sentText.size(), socket.sentText::toString);
      assertArrayOf(socket.sentText.get(1), 4L, 5L);
      assertEquals(1, ws.retainedOutstandingBatches());

      socket.deferTexts = false;
      socket.deferredTexts.get(1).complete(socket);
      feed(ws, socket, grant(2, 700));
      feed(ws, socket, PARSE_ERROR_NULL_ID);

      assertEquals(0, ws.retainedOutstandingBatches(), "the batch read unanswered, so the rejection was its own");
      assertTrue(ws.batchingRefused());
      pass(ws);
      assertSingles(framesFrom(socket, 2), 4L, 5L);
    }
  }

  /// The residue the ledger documents, pinned so it does not move unnoticed. A peer that refuses
  /// one request of a batch before the batch was sent concludes that attempt; the request is
  /// retried alone, and when the chain drains the batch goes out with both requests and the lone
  /// retry behind it. The batch's entry reads answered from the start, one of its attempts having
  /// been concluded early, and a lone request is never ledgered, so nothing stands unanswered for
  /// either message: a parse error then releases nothing and batching stays on. The request only
  /// the batch carried stays gated, to wait for its own answer or the unanswered deadline.
  @Test
  void aBatchWithAnAttemptConcludedBeforeItWasSentReadsAnswered() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var socket = new RecordingWebSocket();
      socket.deferTexts = true;
      ws.onOpen(socket);
      assertTrue(ws.signatureSubscribe("ahead", _ -> {
      })); // request 2: its send stays open, so everything chained after it waits
      pong(ws, socket);
      registerAccounts(ws, KEY_A, KEY_B); // requests 3, 4
      pong(ws, socket);

      feed(ws, socket, refusal(4)); // before the batch was sent
      clock.advanceMillis(WINDOW + 1L);
      pong(ws, socket); // request 4 is due again, and is chained alone behind the batch
      feed(ws, socket, grant(2, 700));
      for (int sent = 0; sent < 3; ++sent) {
        socket.deferredTexts.get(sent).complete(socket);
      }

      assertEquals(3, socket.sentText.size(), socket.sentText::toString);
      assertArrayOf(socket.sentText.get(1), 3L, 4L);
      assertSingles(framesFrom(socket, 2), 4L);
      assertEquals(1, ws.retainedOutstandingBatches(), "the batch is ledgered, and reads answered");

      socket.deferTexts = false;
      feed(ws, socket, PARSE_ERROR_NULL_ID);

      assertAll(
          () -> assertEquals(0, ws.retainedOutstandingBatches(), "pruned as answered, not released"),
          () -> assertFalse(ws.batchingRefused(), "no batch was rejected that the ledger can name"),
          () -> assertEquals(0, ws.retainedReadySubscriptions(), "requests 3 and 4 stay in flight")
      );
      pass(ws);
      assertEquals(3, socket.sentText.size(), "and nothing is sent again");
    }
  }

  /// With two batches outstanding the parse error answers the older one; the newer stays gated.
  @Test
  void theOldestOfTwoOutstandingBatchesIsTheOneReleased() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B); // requests 2, 3
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      registerAccounts(ws, KEY_C, KEY_D); // requests 4, 5
      pong(ws, socket);
      assertArrayOf(socket.sentText.get(0), 2L, 3L);
      assertArrayOf(socket.sentText.get(1), 4L, 5L);
      assertEquals(2, ws.retainedOutstandingBatches());

      feed(ws, socket, PARSE_ERROR_NULL_ID);

      assertEquals(1, ws.retainedOutstandingBatches());
      pass(ws);
      assertSingles(framesFrom(socket, 2), 2L, 3L);
      assertEquals(1, framesContaining(socket, "\"id\":4,"), "the newer batch stays gated");
      assertEquals(1, framesContaining(socket, "\"id\":5,"), "the newer batch stays gated");
    }
  }

  /// A null-id parse error inside a batch response answers one element of a message the server did
  /// read: it is not a whole-batch rejection, so the other outstanding batch stays gated and
  /// batching stays on. The error element is read last, once the array has answered its own batch
  /// whole, so when it is read the oldest unanswered batch is the other one, with nothing older in
  /// flight: only the distinction between a whole message and an element keeps that batch gated.
  @Test
  void anUncorrelatableElementInsideABatchResponseReleasesNoOtherBatch() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var dispatched = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(dispatched::add);
      registerAccounts(ws, KEY_A, KEY_B); // requests 2, 3
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      registerAccounts(ws, KEY_C, KEY_D); // requests 4, 5
      pong(ws, socket);
      assertEquals(2, ws.retainedOutstandingBatches());

      feed(ws, socket, array(List.of(grant(2, 700), grant(3, 701), PARSE_ERROR_NULL_ID)));

      assertEquals(2, ws.retainedOutstandingBatches(),
          "the answered batch is pruned only when the ledger is next consulted, and nothing consulted it");
      assertEquals(1, dispatched.size(), "the element is dispatched like any uncorrelatable error");
      pass(ws);
      assertAll(
          () -> assertEquals(2, socket.sentText.size(), "nothing is re-sent"),
          () -> assertFalse(ws.batchingRefused()),
          () -> assertEquals(0, ws.retainedReadySubscriptions(), "the other batch's elements stay in flight"),
          () -> assertFalse(ws.accountSubscribe(KEY_A, _ -> {
          }), "the grants beside it stand")
      );
    }
  }

  /// A parse error that names a request is that request's: handled as a correlated error, on the
  /// resend cadence since its code blames neither the request nor its params, and no batch is
  /// released on its account. Batching stays on.
  @Test
  void aCorrelatedErrorIsNoWholeBatchRejection() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      feed(ws, socket, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Parse error\"},\"id\":3}");

      pass(ws);
      assertEquals(1, socket.sentText.size(), "inside the window the concluded request is not yet due");
      assertFalse(ws.batchingRefused());
      clock.advanceMillis(WINDOW + 1L);
      pass(ws);
      assertSingles(framesFrom(socket, 1), 3L);
      assertEquals(1, framesContaining(socket, "\"id\":2,"), "its batch-mates stay gated");
      assertEquals(1, framesContaining(socket, "\"id\":4,"), "its batch-mates stay gated");
    }
  }

  /// An id-less error of any other code is not a server's answer to a message it could not read:
  /// a proxy's out-of-band error, say, while a batch is the oldest unanswered message. Releasing
  /// the batch on it would re-send attempts the upstream still grants, so nothing is released,
  /// batching stays on, and the error is dispatched as it always was.
  @Test
  void anUnsolicitedNullIdErrorWithAnotherCodeReleasesNothing() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var dispatched = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(dispatched::add);
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      feed(ws, socket, nullIdError(-32_000, "too many requests"));

      assertEquals(1, dispatched.size());
      assertEquals(-32_000L, assertInstanceOf(JsonRpcException.class, dispatched.getFirst()).code());
      pass(ws);
      assertAll(
          () -> assertEquals(1, socket.sentText.size(), "nothing is re-sent"),
          () -> assertEquals(1, ws.retainedOutstandingBatches(), "the batch is still awaiting its answer"),
          () -> assertFalse(ws.batchingRefused()),
          () -> assertEquals(0, ws.retainedReadySubscriptions(), "every element stays in flight")
      );
    }
  }

  /// A generic request carries the caller's raw params and never shares a message, so malformed
  /// params poison only their own frame. Transmitted ahead of a batch, its parse error finds an
  /// older unanswered request, the malformed single itself, and releases nothing: the single stays
  /// gated, as a malformed single always has, while its batch-mates are confirmed by their array.
  @Test
  void aMalformedGenericRequestTravelsAloneAndItsParseErrorReleasesNoBatch() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var dispatched = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(dispatched::add);
      assertTrue(ws.subscribe("fooSubscribe", "fooUnsubscribe", "fooNotification", "k", "{not json",
          JsonIterator::readLong, null, _ -> {
          })); // request 2
      registerAccounts(ws, KEY_A, KEY_B); // requests 3, 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(2, socket.sentText.size(), socket.sentText::toString);
      assertSingles(socket.sentText.subList(0, 1), 2L);
      assertArrayOf(socket.sentText.get(1), 3L, 4L);

      feed(ws, socket, PARSE_ERROR_NULL_ID);

      assertEquals(1, dispatched.size());
      clock.advanceMillis(WINDOW + 1L);
      pass(ws);
      assertAll(
          () -> assertEquals(2, socket.sentText.size(), "the malformed single stays gated and the batch too"),
          () -> assertFalse(ws.batchingRefused())
      );
      feed(ws, socket, array(List.of(grant(3, 700), grant(4, 701))));
      assertFalse(ws.accountSubscribe(KEY_A, _ -> {
      }), "the batch-mates are confirmed");
    }
  }

  /// A takeover lands while a displaced connection's callback reads a parse error it already
  /// resolved to that connection: the displaced connection's ledger must not decide anything for
  /// the successor, which goes on batching.
  @Test
  void aDisplacedConnectionsRejectionLeavesTheSuccessorBatching() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4
      final var displaced = new RecordingWebSocket();
      ws.onOpen(displaced);
      assertArrayOf(displaced.sentText.getFirst(), 2L, 3L, 4L);
      final var successor = new RecordingWebSocket();
      final var stale = new TakeoverOnRead(PARSE_ERROR_NULL_ID, () -> ws.onOpen(successor));

      ws.onText(displaced, stale, true);

      assertTrue(stale.tookOver(), "the takeover landed after the callback resolved its connection");
      assertEquals(0, ws.lock.getHoldCount(), "the stale callback released the lock");
      assertFalse(ws.batchingRefused(), "a displaced connection's rejection refuses nothing");
      clock.advanceMillis(WINDOW + 1L);
      pass(ws);
      assertEquals(1, successor.sentText.size(), successor.sentText::toString);
      assertArrayOf(successor.sentText.getFirst(), 2L, 3L, 4L);
    }
  }

  /// A caller's `connect()` over an open connection ends it without a retirement, and it still
  /// decides from the ledger: with a batch of the dropped connection wholly unanswered, the
  /// connection the call opens sends one request per message. A caller's own silence watch that
  /// reconnects before the engine's deadline would otherwise replay batched, against a server that
  /// ignores arrays, for ever. The recording builder delivers `onOpen` inside `connect()`, the way
  /// the JDK does, so the adoption and its WARNING happen inside the call.
  @Test
  void aCallersConnectOverAnOpenConnectionWithABatchUnansweredSuspendsBatching() {
    final var clock = new TestClock();
    final var replacement = new RecordingWebSocket();
    final var builder = new RecordingWebSocketBuilder(new AtomicReference<>(), replacement);
    builder.invokeOnOpen = true;
    try (final var ws = connectableWebsocket(clock, builder)) {
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      assertArrayOf(first.sentText.getFirst(), 2L, 3L, 4L);

      // Past the reconnect throttle the adoption armed, so the call builds at once; the batch's
      // stamps are past the resend window by then too, so the replacement replays on adoption.
      clock.advanceMillis(TIMINGS.reConnectDelay() + 1L);

      final var records = TestLogs.capture(SolanaJsonRpcWebsocket.class, () -> assertNotNull(ws.connect()));

      assertTrue(first.aborted, "the caller's connect dropped the open connection");
      assertDiagnostic(records, Level.WARNING, SINGLE_REQUESTS_AFTER_A_LOSS, HOST);
      assertSingles(replacement.sentText, 2L, 3L, 4L);
    }
  }

  /// A batch none of whose elements was answered by the deadline is still wholly unanswered on the
  /// ledger of the connection the escalation retires, so the replacement sends one request per
  /// message, at once, since the stamps are already past the window: a server that ignores
  /// arrays gets the replay in the form it answers instead of being escalated again on the same
  /// silence. The deadline itself latches nothing, and the connection after the replacement
  /// batches again. The suspension is reported when the replacement opens, not at the loss.
  @Test
  void aBatchLeftUnansweredPastTheDeadlineMakesTheReplacementSendSingles() {
    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, (_, ex) -> errors.add(ex))) {
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertArrayOf(socket.sentText.getFirst(), 2L, 3L, 4L);
      clock.advanceMillis(PAST_THE_DEADLINE);

      final var atTheLoss = TestLogs.capture(SolanaJsonRpcWebsocket.class, () -> pass(ws));

      escalatedRequest(errors);
      assertNoDiagnostic(atTheLoss, SINGLE_REQUESTS_AFTER_A_LOSS);
      assertTrue(socket.aborted, "the connection is what gets replaced");
      assertFalse(ws.batchingRefused(), "the deadline latches nothing");
      final var replacement = new RecordingWebSocket();
      final var records = adopt(ws, replacement);
      assertDiagnostic(records, Level.WARNING, SINGLE_REQUESTS_AFTER_A_LOSS, HOST);
      assertSingles(replacement.sentText, 2L, 3L, 4L);

      ws.onClose(replacement, WebSocket.NORMAL_CLOSURE, "peer close"); // single requests are never ledgered
      final var third = new RecordingWebSocket();
      final var later = replayAfterTheWindow(ws, clock, third);
      assertNoDiagnostic(later, SINGLE_REQUESTS_AFTER_A_LOSS);
      assertEquals(1, third.sentText.size(), third.sentText::toString);
      assertArrayOf(third.sentText.getFirst(), 2L, 3L, 4L);
    }
  }

  /// A batch one of whose elements was answered proves the server reads arrays: the unanswered
  /// rest escalate as any request does, the answered batch leaves the retired ledger before it is
  /// read, and the replacement goes on batching.
  @Test
  void aPartlyAnsweredBatchLeftUnansweredLeavesBatchingOn() {
    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, (_, ex) -> errors.add(ex))) {
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, grant(2, 700));
      clock.advanceMillis(PAST_THE_DEADLINE);
      pass(ws);

      final long escalated = escalatedRequest(errors);
      assertTrue(escalated == 3L || escalated == 4L, () -> "an unanswered element escalated: " + escalated);
      assertFalse(ws.batchingRefused());
      final var replacement = new RecordingWebSocket();
      final var records = adopt(ws, replacement);
      assertNoDiagnostic(records, SINGLE_REQUESTS_AFTER_A_LOSS);
      assertEquals(1, replacement.sentText.size(), replacement.sentText::toString);
      assertArrayOf(replacement.sentText.getFirst(), 2L, 3L, 4L);
    }
  }

  /// A plain request left unanswered says nothing about arrays: the replacement goes on batching.
  @Test
  void aSingleLeftUnansweredLeavesBatchingOn() {
    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, (_, ex) -> errors.add(ex))) {
      registerAccounts(ws, KEY_A); // request 2
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertSingles(socket.sentText, 2L);
      clock.advanceMillis(PAST_THE_DEADLINE);
      pass(ws);

      assertEquals(2L, escalatedRequest(errors));
      assertFalse(ws.batchingRefused());
      registerAccounts(ws, KEY_B); // request 3, registered while no connection is up
      final var replacement = new RecordingWebSocket();
      final var records = adopt(ws, replacement);
      assertNoDiagnostic(records, SINGLE_REQUESTS_AFTER_A_LOSS);
      assertEquals(1, replacement.sentText.size(), replacement.sentText::toString);
      assertArrayOf(replacement.sentText.getFirst(), 2L, 3L);
    }
  }

  /// The suspension reads the retired ledger, not the request that escalated: a plain request
  /// transmitted before a batch is the only one past the deadline and escalates as any request
  /// does, but the connection it retires leaves the batch wholly unanswered, so the replacement
  /// sends every request alone, the plain one included. The batch went out two windows after the
  /// plain request, so the deadline scan finds exactly one overdue entry whatever order it visits
  /// them in.
  @Test
  void anOverduePlainRequestBesideAnUnansweredBatchSuspendsBatchingToo() {
    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, (_, ex) -> errors.add(ex))) {
      registerAccounts(ws, KEY_A); // request 2
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertSingles(socket.sentText, 2L);
      assertEquals(0, ws.retainedOutstandingBatches(), "a lone request is never ledgered");
      clock.advanceMillis(2L * WINDOW);
      registerAccounts(ws, KEY_B, KEY_C); // requests 3, 4
      pong(ws, socket);
      assertEquals(2, socket.sentText.size(), socket.sentText::toString);
      assertArrayOf(socket.sentText.get(1), 3L, 4L);
      assertEquals(1, ws.retainedOutstandingBatches(), "the batch is ledgered and unanswered");
      clock.advanceMillis(2L * WINDOW + 2L); // request 2 is past the deadline, the batch is not
      pass(ws);

      assertEquals(2L, escalatedRequest(errors), "the plain request is the one overdue");
      assertFalse(ws.batchingRefused());
      assertTrue(socket.aborted, "the connection is what gets replaced");
      final var replacement = new RecordingWebSocket();
      final var records = adopt(ws, replacement);
      assertDiagnostic(records, Level.WARNING, SINGLE_REQUESTS_AFTER_A_LOSS, HOST);
      assertSingles(replacement.sentText, 2L, 3L, 4L);
    }
  }

  /// A connection lost with a batch wholly unanswered is what a server that resets or closes on
  /// arrays leaves, so the connection adopted next sends one request per message: the replay such
  /// a server would reset again goes out in the form it answers. The suspension is reported once,
  /// when that connection opens and not at the loss, and latches nothing; the connection after it
  /// batches again, since single requests are never ledgered and a loss with nothing on the ledger
  /// decides nothing. What else the peer sent while the batch was out is not weighed: a connection
  /// that received something is lost like any other.
  @Test
  void aConnectionLostWithABatchUnansweredMakesTheNextConnectionSendSingles() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      assertArrayOf(first.sentText.getFirst(), 2L, 3L, 4L);
      feed(ws, first, UNCORRELATED_ANSWER);

      final var atTheLoss = TestLogs.capture(SolanaJsonRpcWebsocket.class,
          () -> ws.onClose(first, WebSocket.NORMAL_CLOSURE, "peer close"));

      assertNoDiagnostic(atTheLoss, SINGLE_REQUESTS_AFTER_A_LOSS);
      assertFalse(ws.batchingRefused(), "a loss latches nothing");
      final var second = new RecordingWebSocket();
      final var records = replayAfterTheWindow(ws, clock, second);
      assertDiagnostic(records, Level.WARNING, SINGLE_REQUESTS_AFTER_A_LOSS, HOST);
      assertSingles(second.sentText, 2L, 3L, 4L);

      ws.onClose(second, WebSocket.NORMAL_CLOSURE, "peer close");
      final var third = new RecordingWebSocket();
      final var later = replayAfterTheWindow(ws, clock, third);
      assertNoDiagnostic(later, SINGLE_REQUESTS_AFTER_A_LOSS);
      assertEquals(1, third.sentText.size(), third.sentText::toString);
      assertArrayOf(third.sentText.getFirst(), 2L, 3L, 4L);
    }
  }

  /// The suspension is the connection's for its life, not its replay's: once the replay is
  /// granted, a burst registered on the same connection still goes out one request per message,
  /// and it is the connection after it that batches again. The cost of a loss is one connection's
  /// batching, the Builder's words, and a reading that restored batching after the replay would
  /// fail here.
  @Test
  void aSuspendedConnectionSendsSinglesForItsLife() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B); // requests 2, 3
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      assertArrayOf(first.sentText.getFirst(), 2L, 3L);
      ws.onClose(first, WebSocket.NORMAL_CLOSURE, "peer close");
      final var second = new RecordingWebSocket();
      replayAfterTheWindow(ws, clock, second);
      assertSingles(second.sentText, 2L, 3L);
      feed(ws, second, grant(2, 700));
      feed(ws, second, grant(3, 701));

      registerAccounts(ws, KEY_C, KEY_D); // requests 4, 5
      pong(ws, second);

      assertSingles(framesFrom(second, 2), 4L, 5L);
      ws.onClose(second, WebSocket.NORMAL_CLOSURE, "peer close");
      final var third = new RecordingWebSocket();
      replayAfterTheWindow(ws, clock, third);
      assertEquals(1, third.sentText.size(), third.sentText::toString);
      assertArrayOf(third.sentText.getFirst(), 2L, 3L, 4L, 5L);
    }
  }

  /// A transport error is the same retirement path as a peer close: lost with its batch
  /// unanswered, the connection makes the next one send singles, and the error still reaches the
  /// error policy.
  @Test
  void aTransportErrorWithABatchUnansweredMakesTheNextConnectionSendSingles() {
    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, (_, ex) -> errors.add(ex))) {
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      assertArrayOf(first.sentText.getFirst(), 2L, 3L, 4L);
      ws.onError(first, new IOException("connection reset"));

      assertEquals(1, errors.size(), errors::toString);
      assertFalse(ws.batchingRefused());
      final var second = new RecordingWebSocket();
      final var records = replayAfterTheWindow(ws, clock, second);
      assertDiagnostic(records, Level.WARNING, SINGLE_REQUESTS_AFTER_A_LOSS, HOST);
      assertSingles(second.sentText, 2L, 3L, 4L);
    }
  }

  /// Once batching is refused there is nothing left to decide: a connection lost with a batch
  /// unanswered after the server rejected an older one as a whole suspends nothing and reports
  /// nothing, and the replacement sends singles because the instance is refused, for good. The
  /// release re-armed the rejected requests at once, so the replacement sends them when it opens
  /// and the rest after the window.
  @Test
  void aRefusedInstanceSuspendsNothingMore() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B); // requests 2, 3
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      registerAccounts(ws, KEY_C, KEY_D); // requests 4, 5
      pong(ws, first);
      assertArrayOf(first.sentText.get(0), 2L, 3L);
      assertArrayOf(first.sentText.get(1), 4L, 5L);
      feed(ws, first, PARSE_ERROR_NULL_ID); // releases the older batch and refuses batching
      assertTrue(ws.batchingRefused());
      assertEquals(1, ws.retainedOutstandingBatches(), "the newer batch is still ledgered, unanswered");
      ws.onClose(first, WebSocket.NORMAL_CLOSURE, "peer close");

      final var second = new RecordingWebSocket();
      final var records = adopt(ws, second);

      assertNoDiagnostic(records, SINGLE_REQUESTS_AFTER_A_LOSS);
      assertSingles(second.sentText, 2L, 3L);
      clock.advanceMillis(WINDOW + 1L);
      pass(ws);
      assertSingles(second.sentText, 2L, 3L, 4L, 5L);
    }
  }

  /// A declared budget of single subscribe messages overrides the suspension when the replay would
  /// not fit it: the single-request replay would be cut off before it completed, so the connection
  /// batches, says so with the replay size and the budget, and nothing is suspended. The flag is
  /// consumed on this branch too: once that connection's batch is answered and the connection
  /// lost with nothing on its ledger, the connection after it is adopted with neither WARNING and
  /// batches, as any connection after an ordinary loss does. A stale flag would log again.
  @Test
  void aReplayLargerThanTheSingleSubscribeBudgetBatchesDespiteTheLoss() {
    final var clock = new TestClock();
    try (final var ws = budgetedWebsocket(clock, 2)) {
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4: a replay of three
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      assertArrayOf(first.sentText.getFirst(), 2L, 3L, 4L);
      ws.onClose(first, WebSocket.NORMAL_CLOSURE, "peer close");

      final var second = new RecordingWebSocket();
      final var records = replayAfterTheWindow(ws, clock, second);

      assertDiagnostic(records, Level.WARNING, BATCHES_DESPITE_THE_LOSS, HOST, 3, 2);
      assertNoDiagnostic(records, SINGLE_REQUESTS_AFTER_A_LOSS);
      assertEquals(1, second.sentText.size(), second.sentText::toString);
      assertArrayOf(second.sentText.getFirst(), 2L, 3L, 4L);
      feed(ws, second, array(List.of(grant(2, 700), grant(3, 701), grant(4, 702)))); // the batch is answered
      ws.onClose(second, WebSocket.NORMAL_CLOSURE, "peer close"); // nothing unanswered on the ledger

      final var third = new RecordingWebSocket();
      final var later = replayAfterTheWindow(ws, clock, third);

      assertNoDiagnostic(later, BATCHES_DESPITE_THE_LOSS);
      assertNoDiagnostic(later, SINGLE_REQUESTS_AFTER_A_LOSS);
      assertEquals(1, third.sentText.size(), third.sentText::toString);
      assertArrayOf(third.sentText.getFirst(), 2L, 3L, 4L);
    }
  }

  /// The replay counts registrations, not keys: one account under two commitments is two, two
  /// generic registrations under one notification method are two, and the root subscription counts
  /// on its own without a slot subscription beside it. Six against a budget of five, so counting any
  /// of them as one, or the root as the slot, would make the replay fit and the connection send
  /// singles.
  @Test
  void theReplayCountsRegistrationsNotKeys() {
    final var clock = new TestClock();
    try (final var ws = budgetedWebsocket(clock, 5)) {
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, _ -> {
      }));
      assertTrue(ws.accountSubscribe(Commitment.PROCESSED, KEY_A, _ -> {
      }));
      assertTrue(ws.subscribe("voteSubscribe", "voteUnsubscribe", "voteNotification", "vote-a", "", JsonIterator::skip, _ -> {
      }));
      assertTrue(ws.subscribe("voteSubscribe", "voteUnsubscribe", "voteNotification", "vote-b", "", JsonIterator::skip, _ -> {
      }));
      assertTrue(ws.rootSubscribe(null, _ -> {
      }));
      assertTrue(ws.logsSubscribe(KEY_B, _ -> {
      }));
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      assertTrue(first.sentText.stream().anyMatch(frame -> frame.startsWith("[")), "the typed requests share one array");
      ws.onClose(first, WebSocket.NORMAL_CLOSURE, "peer close");

      final var second = new RecordingWebSocket();
      final var records = replayAfterTheWindow(ws, clock, second);

      assertDiagnostic(records, Level.WARNING, BATCHES_DESPITE_THE_LOSS, HOST, 6, 5);
      assertTrue(second.sentText.stream().anyMatch(frame -> frame.startsWith("[")), "the replay batches");
    }
  }

  /// A replay that fits the budget exactly is sent one per message as it would be without one: the
  /// budget is what the endpoint accepts, so a replay of that many is not cut off.
  @Test
  void aReplayThatFitsTheSingleSubscribeBudgetSendsSingles() {
    final var clock = new TestClock();
    try (final var ws = budgetedWebsocket(clock, 3)) {
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4: a replay of three
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      assertArrayOf(first.sentText.getFirst(), 2L, 3L, 4L);
      ws.onClose(first, WebSocket.NORMAL_CLOSURE, "peer close");

      final var second = new RecordingWebSocket();
      final var records = replayAfterTheWindow(ws, clock, second);

      assertDiagnostic(records, Level.WARNING, SINGLE_REQUESTS_AFTER_A_LOSS, HOST);
      assertNoDiagnostic(records, BATCHES_DESPITE_THE_LOSS);
      assertSingles(second.sentText, 2L, 3L, 4L);
    }
  }

  /// The replay counts every registration an adoption replays, one of each kind here, since each
  /// goes out as one message when sent alone: an account, a logs, a signature, a program and a
  /// keyed program subscription, the slot and root subscriptions, and a generic one, eight in all
  /// against a budget of seven. Leaving any one kind out of the count would make the replay fit
  /// and the connection send singles. The generic request travels alone in either mode, so the
  /// batching connection shows one array and one plain message, the single-request one no array.
  @Test
  void theReplayCountsEveryKindOfRegistration() {
    final var clock = new TestClock();
    try (final var ws = budgetedWebsocket(clock, 7)) {
      registerAccounts(ws, KEY_A); // request 2
      assertTrue(ws.logsSubscribe(KEY_B, _ -> {
      }));
      assertTrue(ws.signatureSubscribe("5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW", _ -> {
      }));
      assertTrue(ws.programSubscribe(KEY_C, _ -> {
      }));
      assertTrue(ws.keyedProgramSubscribe(Commitment.CONFIRMED, "keyed", KEY_D, List.of(), null, _ -> {
      }));
      assertTrue(ws.slotSubscribe(null, _ -> {
      }));
      assertTrue(ws.rootSubscribe(null, _ -> {
      }));
      assertTrue(ws.subscribe("voteSubscribe", "voteUnsubscribe", "voteNotification", "vote", "", JsonIterator::skip, _ -> {
      }));
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      assertEquals(2, first.sentText.size(), first.sentText::toString);
      assertTrue(first.sentText.stream().anyMatch(frame -> frame.startsWith("[")), "the typed requests share one array");
      ws.onClose(first, WebSocket.NORMAL_CLOSURE, "peer close");

      final var second = new RecordingWebSocket();
      final var records = replayAfterTheWindow(ws, clock, second);

      assertDiagnostic(records, Level.WARNING, BATCHES_DESPITE_THE_LOSS, HOST, 8, 7);
      assertEquals(2, second.sentText.size(), second.sentText::toString);
      assertTrue(second.sentText.stream().anyMatch(frame -> frame.startsWith("[")), "the replay batches");
    }
  }

  /// A connection whose batch the server answered, in part or whole, proves the server reads
  /// arrays: the answered batch leaves the retired ledger before the ledger is read, so the loss
  /// decides nothing and the replacement goes on batching. One grant is enough, since a batch any
  /// of whose requests was answered reads answered, and the entry stays ledgered until that
  /// retirement prunes it: nothing else runs a prune between the grant and the loss.
  @Test
  void aConnectionLostAfterItsBatchWasAnsweredInPartLeavesTheNextBatching() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      assertArrayOf(first.sentText.getFirst(), 2L, 3L, 4L);
      feed(ws, first, grant(2, 700));
      assertEquals(1, ws.retainedOutstandingBatches(), "the answered batch stays ledgered until a prune runs");
      ws.onClose(first, WebSocket.NORMAL_CLOSURE, "peer close");

      final var second = new RecordingWebSocket();
      final var records = replayAfterTheWindow(ws, clock, second);

      assertNoDiagnostic(records, SINGLE_REQUESTS_AFTER_A_LOSS);
      assertFalse(ws.batchingRefused());
      assertEquals(1, second.sentText.size(), second.sentText::toString);
      assertArrayOf(second.sentText.getFirst(), 2L, 3L, 4L);
    }
  }

  /// A batch whose send failed before the loss was concluded locally: the failure re-armed its
  /// requests, which answers the entry, so a connection lost with nothing else on its ledger
  /// decides nothing and the replacement goes on batching. A send still pending at the loss is
  /// the next test's case.
  @Test
  void aConnectionLostWithABatchWhoseSendFailedLeavesTheNextBatching() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4
      final var first = new RecordingWebSocket();
      first.failText = new IOException("send refused");
      ws.onOpen(first);
      first.failText = null;
      assertArrayOf(first.sentText.getFirst(), 2L, 3L, 4L);
      assertEquals(1, ws.retainedOutstandingBatches(), "the failed batch is ledgered");
      assertEquals(3, ws.retainedReadySubscriptions(), "the failed send re-armed its elements, which answers its batch");
      ws.onClose(first, WebSocket.NORMAL_CLOSURE, "peer close");

      final var second = new RecordingWebSocket();
      final var records = replayAfterTheWindow(ws, clock, second);

      assertNoDiagnostic(records, SINGLE_REQUESTS_AFTER_A_LOSS);
      assertFalse(ws.batchingRefused());
      assertEquals(1, second.sentText.size(), second.sentText::toString);
      assertArrayOf(second.sentText.getFirst(), 2L, 3L, 4L);
    }
  }

  /// A batch whose send future has not settled when the connection is lost counts: the ledger
  /// cannot tell a frame still in the socket's buffer from one the peer received, so the next
  /// connection sends singles, and the failure that settles the future afterwards, on the
  /// retired connection, revokes nothing.
  @Test
  void aBatchWhoseSendIsStillPendingAtTheLossSuspendsBatching() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4
      final var first = new RecordingWebSocket();
      first.deferTexts = true;
      ws.onOpen(first);
      assertArrayOf(first.sentText.getFirst(), 2L, 3L, 4L);
      assertEquals(1, first.deferredTexts.size(), "the batch's send future is still open");
      ws.onClose(first, WebSocket.NORMAL_CLOSURE, "peer close");
      first.deferredTexts.getFirst().completeExceptionally(new IOException("failed after the close"));

      final var second = new RecordingWebSocket();
      final var records = replayAfterTheWindow(ws, clock, second);

      assertDiagnostic(records, Level.WARNING, SINGLE_REQUESTS_AFTER_A_LOSS, HOST);
      assertSingles(second.sentText, 2L, 3L, 4L);
    }
  }

  /// A connection lost with two batches unanswered is one loss: the suspension is decided and
  /// reported once for the connection, whatever its ledger holds, and reaches one connection.
  @Test
  void aConnectionLostWithTwoBatchesUnansweredIsOneLoss() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B); // requests 2, 3
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      registerAccounts(ws, KEY_C, KEY_D); // requests 4, 5
      pong(ws, first);
      assertArrayOf(first.sentText.get(0), 2L, 3L);
      assertArrayOf(first.sentText.get(1), 4L, 5L);
      assertEquals(2, ws.retainedOutstandingBatches(), "both batches unanswered");
      ws.onClose(first, WebSocket.NORMAL_CLOSURE, "peer close");

      final var second = new RecordingWebSocket();
      final var records = replayAfterTheWindow(ws, clock, second);

      assertDiagnostic(records, Level.WARNING, SINGLE_REQUESTS_AFTER_A_LOSS, HOST);
      assertSingles(second.sentText, 2L, 3L, 4L, 5L);
      ws.onClose(second, WebSocket.NORMAL_CLOSURE, "peer close");
      final var third = new RecordingWebSocket();
      replayAfterTheWindow(ws, clock, third);
      assertEquals(1, third.sentText.size(), third.sentText::toString);
      assertArrayOf(third.sentText.getFirst(), 2L, 3L, 4L, 5L);
    }
  }

  /// The suspension is spent by the connection it was decided for, not by time or by a pass: a
  /// registration made and a pass run while no connection is up leave it standing, and the
  /// connection adopted next is the one that sends singles.
  @Test
  void theSuspensionWaitsForTheNextConnectionToBeAdopted() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B); // requests 2, 3
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      assertArrayOf(first.sentText.getFirst(), 2L, 3L);
      ws.onClose(first, WebSocket.NORMAL_CLOSURE, "peer close");
      registerAccounts(ws, KEY_C); // request 4, registered while no connection is up
      clock.advanceMillis(WINDOW + 1L);
      final var meanwhile = TestLogs.capture(SolanaJsonRpcWebsocket.class, () -> pass(ws));
      assertNoDiagnostic(meanwhile, SINGLE_REQUESTS_AFTER_A_LOSS); // nothing to send to; nothing consumed

      final var second = new RecordingWebSocket();
      final var records = adopt(ws, second);

      assertDiagnostic(records, Level.WARNING, SINGLE_REQUESTS_AFTER_A_LOSS, HOST);
      assertSingles(second.sentText, 2L, 3L, 4L);
    }
  }

  /// Answered batches leave the ledger when the next batch is pushed, so a connection that batches
  /// for its life holds at most the last batch and the one being pushed.
  @Test
  void theLedgerStaysBoundedWhileBatchesAreAnswered() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      long nextRequestId = 2;
      for (int round = 0; round < 50; ++round) {
        final long first = nextRequestId;
        final long second = first + 1;
        nextRequestId += 2;
        assertTrue(ws.signatureSubscribe("sig" + first, _ -> {
        }));
        assertTrue(ws.signatureSubscribe("sig" + second, _ -> {
        }));
        pong(ws, socket);
        assertArrayOf(socket.sentText.getLast(), first, second);
        feed(ws, socket, array(List.of(grant(first, 1_000 + first), grant(second, 1_000 + second))));
        assertTrue(ws.retainedOutstandingBatches() <= 1,
            () -> "the ledger holds at most the batch just pushed: " + ws.retainedOutstandingBatches());
      }
      assertEquals(1, ws.retainedOutstandingBatches(), "the last batch, answered, waits for the next push");
      assertFalse(ws.batchingRefused());
    }
  }

  /// A batch the server never answers stops the head prune, so every later batch would stay
  /// ledgered behind it, answered or not. A push therefore drops answered entries from anywhere
  /// once the ledger holds more entries than there are requests in flight, which it can only
  /// while some of them are answered. The ledger grows to one entry more than the requests in
  /// flight at a push, the batch being pushed, then falls back to the unanswered batches and that
  /// one, and does so again: the counts are pinned push by push, since a sweep one push early or
  /// late is the boundary a bound like this gets wrong. The unanswered batches keep their order
  /// through the sweeps, so a parse error afterwards still releases the older one and leaves the
  /// newer gated.
  @Test
  void theLedgerStaysBoundedBehindBatchesNeverAnswered() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B); // requests 2, 3: never answered
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      registerAccounts(ws, KEY_C, KEY_D); // requests 4, 5: never answered
      pong(ws, socket);
      assertArrayOf(socket.sentText.get(0), 2L, 3L);
      assertArrayOf(socket.sentText.get(1), 4L, 5L);
      assertEquals(2, ws.retainedOutstandingBatches());

      // Each round pushes one batch while six requests are in flight, the four never answered
      // and its own two, and then has it answered.
      final var retainedAfterEachPush = new ArrayList<Integer>();
      long nextRequestId = 6;
      for (int round = 0; round < 12; ++round) {
        final long first = nextRequestId;
        final long second = first + 1;
        nextRequestId += 2;
        assertTrue(ws.signatureSubscribe("sig" + first, _ -> {
        }));
        assertTrue(ws.signatureSubscribe("sig" + second, _ -> {
        }));
        pong(ws, socket);
        assertArrayOf(socket.sentText.getLast(), first, second);
        retainedAfterEachPush.add(ws.retainedOutstandingBatches());
        feed(ws, socket, array(List.of(grant(first, 1_000 + first), grant(second, 1_000 + second))));
      }
      assertEquals(List.of(3, 4, 5, 6, 7, 3, 4, 5, 6, 7, 3, 4), retainedAfterEachPush,
          "seven entries is one more than the six requests in flight; the push after it sweeps the"
              + " answered ones and leaves the two unanswered batches and its own");
      assertFalse(ws.batchingRefused());

      final int sentBeforeTheRejection = socket.sentText.size();
      feed(ws, socket, PARSE_ERROR_NULL_ID);
      pass(ws);

      assertSingles(framesFrom(socket, sentBeforeTheRejection), 2L, 3L);
      assertEquals(1, framesContaining(socket, "\"id\":4,"), "the newer unanswered batch stays gated");
      assertEquals(1, framesContaining(socket, "\"id\":5,"), "the newer unanswered batch stays gated");
    }
  }

  /// Without a connection there is no ledger: before the first one, and once `close()` has
  /// dropped it, the seam reports nothing retained rather than dereference a connection that does
  /// not exist. Non-zero in between, on the live connection, so each zero is the absence of a
  /// connection and not of batches.
  @Test
  void theLedgerSeamAnswersWithoutAConnection() {
    try (final var ws = websocket(new TestClock())) {
      registerAccounts(ws, KEY_A, KEY_B);
      assertEquals(0, ws.retainedOutstandingBatches(), "registered before any connection");

      ws.onOpen(new RecordingWebSocket());
      assertEquals(1, ws.retainedOutstandingBatches(), "the adoption's batch is ledgered");

      ws.close();

      assertTrue(ws.closed());
      assertEquals(0, ws.retainedOutstandingBatches(), "closed: no connection, so no ledger");
    }
  }

  /// A release re-arms requests for the next pass; a cycle already parked on the condition must be
  /// woken for it, or a maximal check delay would leave the released requests unsent.
  @Test
  void aReleasedBatchWakesAnAlreadyParkedCycle() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertArrayOf(socket.sentText.getFirst(), 2L, 3L, 4L);

      assertSignalsAlreadyParkedCycle(ws, () -> feed(ws, socket, PARSE_ERROR_NULL_ID));

      assertTrue(ws.batchingRefused());
      assertEquals(3, ws.retainedReadySubscriptions(), "the released requests await the woken pass");
    }
  }

  /// A release remembers its wake as well as signalling it: a cycle that has not parked yet when
  /// the rejection arrives runs its next pass at once for the released requests, instead of
  /// parking a whole check delay on them.
  @Test
  void aReleaseIsRememberedByACycleNotYetParked() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      registerAccounts(ws, KEY_A, KEY_B, KEY_C); // requests 2, 3, 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      pass(ws);
      assertFalse(cycleRanWithoutParking(ws), "nothing is remembered before the rejection");

      feed(ws, socket, PARSE_ERROR_NULL_ID);

      assertEquals(1, socket.sentText.size(), "the release re-arms the requests; only a pass sends them");
      assertTrue(cycleRanWithoutParking(ws),
          "the release must remember its wake for a cycle that has not parked yet");
      assertSingles(framesFrom(socket, 1), 2L, 3L, 4L);
    }
  }

}
