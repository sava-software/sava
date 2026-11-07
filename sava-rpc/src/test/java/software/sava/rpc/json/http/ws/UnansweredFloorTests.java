package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/// The unanswered-deadline floor: a lower bound on every in-flight stamp, which lets a
/// maintenance pass skip the deadline scan while even the oldest request awaiting its answer
/// cannot be overdue. A skipped pass decides exactly what the scan would have, so the
/// escalation tests elsewhere pass unchanged, and the saving shows only through the
/// `unansweredScanVisits()` seam: the in-flight map's size summed over the scans the floor let
/// through, which is what a scan reads when nothing is overdue and an upper bound when it stops
/// at an overdue entry. The tests pin when the scan does not run (inside the floor, at the
/// deadline itself, after an answered generation raised the floor, after an emptied map was
/// refilled), that a scan let through by a stale floor still judges each request by its own
/// age, and that the floor never ends above a live stamp, neither after a scan nor after an
/// insertion older than the floor. A floor too high hides an overdue request, so those last
/// tests assert the escalation it would hide rather than a count.
///
/// A count is read while its connection is current: an escalation's delivery retires the
/// connection, after which the seam reads 0, so escalating passes are split at
/// `prepareCheckCycleDelivery`. Pings are disabled (`Long.MAX_VALUE` ping and keep-alive
/// delays), as in `MaintenancePassBoundaryTests`, so a step over the unanswered deadline tests
/// only that deadline.
@ExtendWith(QuietWsLogging.class)
final class UnansweredFloorTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000L, Long.MAX_VALUE, 60_000L, Long.MAX_VALUE, 60_000L);
  private static final long WINDOW = TIMINGS.subscriptionResendDelay();
  private static final long DEADLINE = WINDOW * SolanaJsonRpcWebsocket.UNANSWERED_ESCALATION_FACTOR;

  /// A clock over a [TestClock] that can step once between two readings of one pass: armed by
  /// [#stepAfterNextRead(long)], the next reading returns the current time and then advances
  /// it, so every later reading, the rest of that pass included, sees the step. A pass reads the
  /// clock first for its own `now` and again to stamp each cancellation it flushes, so this is
  /// how a test puts a millisecond boundary between those two readings, as a real clock may.
  private static final class SteppingClock implements NanoClock {

    /// The time source; this class decides only when it moves.
    private final TestClock delegate = new TestClock();
    /// The step the next reading takes once it has returned, in milliseconds; 0 when unarmed.
    private final AtomicLong stepAfterNextRead = new AtomicLong();

    /// Moves the clock at once, as [TestClock#advanceMillis(long)] does.
    void advanceMillis(final long millis) {
      delegate.advanceMillis(millis);
    }

    /// Arms a single step of `millis`, taken right after the next reading returns.
    void stepAfterNextRead(final long millis) {
      stepAfterNextRead.set(millis);
    }

    /// The current time; the first reading after [#stepAfterNextRead(long)] then takes the step.
    @Override
    public long nanoTime() {
      final long nanos = delegate.nanoTime();
      final long step = stepAfterNextRead.getAndSet(0L);
      if (step != 0L) {
        delegate.advanceMillis(step);
      }
      return nanos;
    }

    /// Advances as [TestClock#sleep(long)] does, never blocking.
    @Override
    public void sleep(final long millis) {
      delegate.sleep(millis);
    }
  }

  private static SolanaJsonRpcWebsocket websocket(final NanoClock clock, final List<Throwable> errors) {
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
        (_, ex) -> errors.add(ex),
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

  /// Registers account subscriptions on `count` keys from index `first` on.
  private static void subscribe(final SolanaJsonRpcWebsocket ws, final int first, final int count) {
    for (int index = first; index < first + count; ++index) {
      assertTrue(ws.accountSubscribe(key(index), _ -> {
      }));
    }
  }

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  /// The server's grant of subscription `subId` to request `requestId`.
  private static String grant(final long requestId, final long subId) {
    return "{\"jsonrpc\":\"2.0\",\"result\":" + subId + ",\"id\":" + requestId + "}";
  }

  private static String accountNotification(final long subId) {
    return """
        {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":3},\
        "value":{"lamports":1,"data":["","base64"],"owner":"11111111111111111111111111111111",\
        "executable":false,"rentEpoch":0,"space":0}},"subscription":""" + subId + "}}";
  }

  /// Runs one pass that must escalate and returns its connection's `unansweredScanVisits()`
  /// count, read before the escalation's delivery retires that connection.
  private static long escalatingPass(final SolanaJsonRpcWebsocket ws) throws InterruptedException {
    final var delivery = ws.prepareCheckCycleDelivery(0L);
    assertNotNull(delivery, "the pass escalates");
    final long visits = ws.unansweredScanVisits();
    delivery.run();
    return visits;
  }

  /// Requests transmitted and awaiting their answers, and passes that never step the clock: no
  /// pass scans, so the count stays at zero rather than at the size of a first scan. The
  /// request that filled the empty in-flight map set the floor to its own stamp, the pass's
  /// own reading, so even the adoption pass that sent them is answered by the floor. The clock
  /// moves a whole deadline before anything is stamped, so every stamp sits far from zero: a
  /// sum of the reading and the floor would cross the deadline where their difference cannot.
  @Test
  void passesInsideTheFloorVisitNothing() throws InterruptedException {
    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, errors)) {
      clock.advanceMillis(DEADLINE);
      final int requests = 64;
      subscribe(ws, 1, requests);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // requests 2 onward, transmitted and unanswered
      assertEquals(requests, socket.sentText.size(), socket.sentText::toString);
      assertEquals(0, ws.unansweredScanVisits(), "the adoption pass is answered by the floor");

      for (int pass = 0; pass < 10; ++pass) {
        ws.checkCycle(0L);
      }

      assertAll(
          () -> assertEquals(0, ws.unansweredScanVisits(), "no pass inside the floor scans"),
          () -> assertEquals(requests, socket.sentText.size(), "an unanswered request is never re-sent"),
          () -> assertTrue(errors.isEmpty(), errors::toString),
          () -> assertFalse(socket.aborted)
      );
    }
  }

  /// The floor answers every pass up to and including the deadline: an age equal to the
  /// deadline has not exceeded it, so the pass at that instant neither scans nor escalates. One
  /// millisecond later the floor no longer answers, and the scan it lets through visits the
  /// request and escalates. The count belongs to the connection and leaves with it.
  @Test
  void theFloorFastPathIsExclusiveAtTheDeadline() throws InterruptedException {
    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, errors)) {
      subscribe(ws, 1, 1);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // request 2, transmitted and unanswered
      assertEquals(0, ws.unansweredScanVisits(), "a fresh stamp is answered by the floor");

      clock.advanceMillis(DEADLINE);
      ws.checkCycle(0L);
      assertAll(
          () -> assertEquals(0, ws.unansweredScanVisits(), "the floor answers at the deadline itself"),
          () -> assertTrue(errors.isEmpty(), () -> "an age equal to the deadline escalated: " + errors),
          () -> assertFalse(socket.aborted)
      );

      clock.advanceMillis(1L);
      assertEquals(1, escalatingPass(ws), "one past the deadline the scan visits the request");
      assertAll(
          () -> assertEquals(1, errors.size(), errors::toString),
          () -> assertTrue(errors.getFirst().getMessage().contains("Request 2 "), errors.getFirst()::getMessage),
          () -> assertTrue(socket.aborted, "the escalation replaced the connection"),
          () -> assertEquals(0, ws.unansweredScanVisits(), "no connection, no count: it left with the retired one")
      );
    }
  }

  /// Two generations of requests a resend window apart, the older one answered. An answer never
  /// moves the floor, so it still holds the older generation's stamp, and the first pass past
  /// that stamp's deadline scans the younger generation, finds none of it overdue and raises the
  /// floor to its stamp. From there no pass scans until the younger generation's own deadline
  /// has passed, and that scan escalates.
  @Test
  void anAnsweredOldestGenerationRaisesTheFloor() throws InterruptedException {
    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, errors)) {
      final int generation = 32;
      subscribe(ws, 1, generation);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // the older generation, requests 2 onward, at t0
      final long younger = WINDOW;
      clock.advanceMillis(younger);
      subscribe(ws, 1 + generation, generation);
      ws.checkCycle(0L); // the younger generation at t0 + younger
      assertEquals(2 * generation, socket.sentText.size(), socket.sentText::toString);
      for (int answered = 0; answered < generation; ++answered) {
        feed(ws, socket, grant(2 + answered, 700 + answered));
      }
      assertEquals(0, ws.unansweredScanVisits(), "nothing could be overdue yet");

      clock.advanceMillis(DEADLINE + 1L - younger); // t0 + DEADLINE + 1
      ws.checkCycle(0L);
      assertAll(
          () -> assertEquals(generation, ws.unansweredScanVisits(),
              "the older generation's floor lets one scan through, of the younger generation"),
          () -> assertTrue(errors.isEmpty(), () -> "none of the younger generation is overdue: " + errors),
          () -> assertFalse(socket.aborted)
      );

      clock.advanceMillis(younger / 2); // between the two deadlines
      ws.checkCycle(0L);
      clock.advanceMillis(younger - 1L - younger / 2); // t0 + younger + DEADLINE: the younger deadline itself
      ws.checkCycle(0L);
      assertAll(
          () -> assertEquals(generation, ws.unansweredScanVisits(),
              "the raised floor answers every pass up to the younger generation's deadline"),
          () -> assertTrue(errors.isEmpty(), errors::toString)
      );

      clock.advanceMillis(1L);
      assertEquals(2L * generation, escalatingPass(ws),
          "past the younger deadline the scan runs and escalates, counted at the map's whole size");
      assertEquals(1, errors.size(), errors::toString);
      assertTrue(errors.getFirst().getMessage().contains("unanswered for " + (DEADLINE + 1L) + "ms"),
          errors.getFirst()::getMessage);
    }
  }

  /// A cancellation the listener sends on the direct path, for a notification naming a
  /// subscription nothing registered, into an in-flight map an answer has emptied: inserting it
  /// sets the floor to the cancellation's own stamp, so the floor the answered request left
  /// behind has no say. A pass past that old floor's deadline finds nothing in flight, the pass
  /// right after the cancellation is answered by the new floor, and the first scan is the one
  /// past the cancellation's own deadline, which escalates it.
  @Test
  void theFloorFollowsADirectPathCancellation() throws InterruptedException {
    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, errors)) {
      subscribe(ws, 1, 1);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // request 2 at t0
      assertEquals(0, ws.unansweredScanVisits(), "a fresh stamp is answered by the floor");
      feed(ws, socket, grant(2, 700)); // answered: nothing in flight, the floor left at t0

      clock.advanceMillis(DEADLINE + 1L); // past the deadline of the floor request 2 left
      ws.checkCycle(0L);
      assertEquals(0, ws.unansweredScanVisits(), "nothing in flight, nothing scanned");

      feed(ws, socket, accountNotification(999)); // unknown: cancellation 3, sent on the direct path
      assertEquals(2, socket.sentText.size(), socket.sentText::toString);
      assertTrue(socket.sentText.getLast().contains("\"id\":3,\"method\":\"accountUnsubscribe\",\"params\":[999]"),
          socket.sentText::toString);

      ws.checkCycle(0L);
      assertEquals(0, ws.unansweredScanVisits(), "the cancellation's stamp is the floor, not the answered request's");

      clock.advanceMillis(DEADLINE);
      ws.checkCycle(0L);
      assertAll(
          () -> assertEquals(0, ws.unansweredScanVisits(), "no scan up to the cancellation's own deadline"),
          () -> assertTrue(errors.isEmpty(), errors::toString)
      );

      clock.advanceMillis(1L);
      assertEquals(1, escalatingPass(ws), "the first scan is the one past the cancellation's deadline");
      assertEquals(1, errors.size(), errors::toString);
      assertTrue(errors.getFirst().getMessage().contains("Request 3 "), errors.getFirst()::getMessage);
    }
  }

  /// A scan let through by a stale floor judges each request by its own age, exclusively. The
  /// older of two requests is answered, and the floor it left lets the pass at the younger one's
  /// exact deadline into the scan, which spares that request, since an age equal to the deadline
  /// has not exceeded it, and raises the floor to its stamp; a millisecond later the scan
  /// escalates it. With an exact floor the fast path settles the deadline's equality before any
  /// scan runs (`theFloorFastPathIsExclusiveAtTheDeadline`), so only a stale floor brings the
  /// scan's own comparison to that edge.
  @Test
  void aScanFromAStaleFloorSparesARequestExactlyAtItsDeadline() throws InterruptedException {
    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, errors)) {
      subscribe(ws, 1, 1);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // request 2 at t0
      clock.advanceMillis(WINDOW);
      subscribe(ws, 2, 1);
      ws.checkCycle(0L); // request 3 at t0 + WINDOW
      assertEquals(2, socket.sentText.size(), socket.sentText::toString);
      feed(ws, socket, grant(2, 700)); // the older request answered; the floor stays at t0

      clock.advanceMillis(DEADLINE); // request 3's age is exactly the deadline
      ws.checkCycle(0L);
      assertAll(
          () -> assertEquals(1, ws.unansweredScanVisits(), "the stale floor lets the scan through"),
          () -> assertTrue(errors.isEmpty(), () -> "an age equal to the deadline escalated: " + errors),
          () -> assertFalse(socket.aborted)
      );

      clock.advanceMillis(1L);
      assertEquals(2, escalatingPass(ws), "a millisecond on, the scan escalates the request");
      assertEquals(1, errors.size(), errors::toString);
      assertTrue(errors.getFirst().getMessage().contains("Request 3 "), errors.getFirst()::getMessage);
      assertTrue(errors.getFirst().getMessage().contains("unanswered for " + (DEADLINE + 1L) + "ms"),
          errors.getFirst()::getMessage);
    }
  }

  /// A scan leaves the floor at the oldest stamp it read, never a younger one. Three requests go
  /// out a resend window apart and the oldest is answered, so the floor it left lets the pass
  /// one millisecond past its deadline into a scan of the two younger ones, neither overdue.
  /// The oracle is each request's own deadline, which owes nothing to the floor: the older
  /// survivor is spared at its exact deadline and escalates one millisecond later. A scan that
  /// kept the last or the newest stamp it read, or an insertion that let the newest request set
  /// the floor, leaves the floor above that survivor's stamp and hides it past its deadline,
  /// the one mistake a floor must never make. The count is read where the stale floor let the
  /// scan through but asserted last, so such a floor fails on the escalation it hides.
  @Test
  void aScanLeavesTheFloorAtTheOldestStampItSaw() throws InterruptedException {
    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, errors)) {
      subscribe(ws, 1, 1);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // request 2 at t0
      clock.advanceMillis(WINDOW);
      subscribe(ws, 2, 1);
      ws.checkCycle(0L); // request 3 at t0 + WINDOW
      clock.advanceMillis(WINDOW);
      subscribe(ws, 3, 1);
      ws.checkCycle(0L); // request 4 at t0 + 2 * WINDOW
      assertEquals(3, socket.sentText.size(), socket.sentText::toString);
      feed(ws, socket, grant(2, 700)); // the oldest answered; the floor stays at t0

      clock.advanceMillis(DEADLINE + 1L - 2 * WINDOW); // t0 + DEADLINE + 1
      ws.checkCycle(0L);
      final long staleFloorScan = ws.unansweredScanVisits();
      assertTrue(errors.isEmpty(), () -> "neither survivor is overdue yet: " + errors);

      clock.advanceMillis(WINDOW - 1L); // t0 + WINDOW + DEADLINE: request 3 exactly at its deadline
      ws.checkCycle(0L);
      assertTrue(errors.isEmpty(), () -> "an age equal to the deadline escalated: " + errors);

      clock.advanceMillis(1L);
      ws.checkCycle(0L);
      assertEquals(1, errors.size(), () -> "request 3 escalates one millisecond past its own deadline: " + errors);
      assertAll(
          () -> assertTrue(errors.getFirst().getMessage().contains("Request 3 "), errors.getFirst()::getMessage),
          () -> assertTrue(errors.getFirst().getMessage().contains("unanswered for " + (DEADLINE + 1L) + "ms"),
              errors.getFirst()::getMessage),
          () -> assertTrue(socket.aborted, "the escalation replaced the connection"),
          () -> assertEquals(2, staleFloorScan, "the floor the answered request left let a scan of both survivors through")
      );
    }
  }

  /// An insertion older than the floor lowers the floor to its stamp. A pass reads the clock for
  /// its own `now`, flushes the cancellations it owes, stamping each with a fresh reading, and
  /// only then admits subscribes under the `now` it started from; with a millisecond boundary
  /// between those readings, the cancellation that fills an empty in-flight map is stamped a
  /// millisecond after the subscribe admitted behind it. The subscribe's frame waits in the chain
  /// behind the cancellation's held send, so its stamp stays its admission reading. Oracle: the
  /// deadline, which for a frame the chain never delivered runs from admission, so the subscribe
  /// escalates one millisecond past it. An insertion that left the cancellation's stamp as the
  /// floor would hide the subscribe for that millisecond.
  @Test
  void anInsertionOlderThanTheFloorLowersIt() throws InterruptedException {
    final var clock = new SteppingClock();
    final var errors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, errors)) {
      subscribe(ws, 1, 1);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // request 2
      feed(ws, socket, grant(2, 700)); // answered: nothing in flight
      assertTrue(ws.accountUnsubscribe(key(1)), "subscription 700's cancellation waits for the next pass");
      subscribe(ws, 2, 1); // request 3, ready
      socket.deferTexts = true;

      clock.stepAfterNextRead(1L);
      ws.checkCycle(0L); // reads t, stamps cancellation 4 at t + 1, then admits request 3 at t behind it
      assertAll(
          () -> assertEquals(2, socket.sentText.size(), socket.sentText::toString),
          () -> assertTrue(socket.sentText.getLast().contains("\"id\":4,\"method\":\"accountUnsubscribe\",\"params\":[700]"),
              socket.sentText::toString),
          () -> assertTrue(errors.isEmpty(), errors::toString)
      );

      clock.advanceMillis(DEADLINE); // t + DEADLINE + 1
      ws.checkCycle(0L);
      assertEquals(1, errors.size(), () -> "request 3 escalates one millisecond past its own deadline: " + errors);
      assertAll(
          () -> assertTrue(errors.getFirst().getMessage().contains("Request 3 "), errors.getFirst()::getMessage),
          () -> assertTrue(errors.getFirst().getMessage().contains("unanswered for " + (DEADLINE + 1L) + "ms"),
              errors.getFirst()::getMessage),
          () -> assertTrue(socket.aborted, "the escalation replaced the connection")
      );
    }
  }
}
