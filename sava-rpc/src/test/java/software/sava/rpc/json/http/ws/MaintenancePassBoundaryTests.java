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

import static org.junit.jupiter.api.Assertions.*;

/// Boundaries inside one maintenance pass where the two sides of a mutated comparison agree
/// on every input a caller can produce (the ws "Empty scans, deadlines, and wire order"
/// section of `config/pitest/README.md`):
///
/// - `# empty-scan fast path`: with nothing in flight and no recorded kill, the pass skips
///   two loops whose bodies are the only place work happens. Entering them iterates an
///   empty `HashMap` entry set, which yields no element (`Collection.removeIf` traverses
///   `iterator()` and `HashMap.EntrySet` does not override it).
/// - `# saturated deadline fringe`: the unanswered deadline saturates to `Long.MAX_VALUE`
///   for resend windows above `Long.MAX_VALUE / UNANSWERED_ESCALATION_FACTOR`; at that
///   window exactly, the two sides compute `Long.MAX_VALUE - 3` and `Long.MAX_VALUE`. Ages
///   are differences of `pacingMillis()` readings, a long nanosecond difference over a
///   million, so no age exceeds about 1.9e13 ms and neither deadline is reached.
/// - `# saturated-add equality`: a rejected cancellation's retry time saturates for windows
///   above `Long.MAX_VALUE - now`; at that window exactly, `now + window` is
///   `Long.MAX_VALUE` too.
///
/// Pings are disabled (`Long.MAX_VALUE` ping and keep-alive delays) so that the largest
/// clock step a [TestClock] admits tests only the deadline under study.
@ExtendWith(QuietWsLogging.class)
final class MaintenancePassBoundaryTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final PublicKey ACCOUNT = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");

  private static Timings timings(final long subscriptionResendDelay) {
    return new Timings(60_000L, Long.MAX_VALUE, 60_000L, Long.MAX_VALUE, subscriptionResendDelay);
  }

  private static SolanaJsonRpcWebsocket websocket(final Timings timings,
                                                  final TestClock clock,
                                                  final List<Throwable> errors) {
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
        (_, ex) -> errors.add(ex),
        null,
        null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  /// The largest step `clock` can still take without its nanosecond reading overflowing.
  private static long largestStepMillis(final TestClock clock) {
    return (Long.MAX_VALUE - clock.nanoTime()) / 1_000_000L;
  }

  private static long framesContaining(final RecordingWebSocket socket, final String token) {
    return socket.sentText.stream().filter(frame -> frame.contains(token)).count();
  }

  /// Nothing in flight and no kill recorded, at ages from zero to past every deadline: the
  /// pass finds no work, escalates nothing, and leaves the connection serving.
  @Test
  void aPassWithNothingInFlightAndNoKillFindsNoWorkAtAnyAge() throws InterruptedException {
    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    final var timings = timings(60_000L);
    try (final var ws = websocket(timings, clock, errors)) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      final long deadline = timings.subscriptionResendDelay() * SolanaJsonRpcWebsocket.UNANSWERED_ESCALATION_FACTOR;
      for (final long step : new long[]{0L, deadline, 1L, largestStepMillis(clock) - deadline - 1L}) {
        clock.advanceMillis(step);
        ws.checkCycle(0L);
        assertAll(
            () -> assertTrue(errors.isEmpty(), () -> "an empty pass escalated: " + errors),
            () -> assertFalse(socket.aborted, "an empty pass retired the connection"),
            () -> assertTrue(socket.sentText.isEmpty(), socket.sentText::toString),
            () -> assertEquals(0, socket.pings),
            () -> assertEquals(0, ws.retainedOrdinalEntries())
        );
      }

      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      ws.checkCycle(0L);
      assertEquals(1, socket.sentText.size(), "the connection still serves after the empty passes");
      assertTrue(errors.isEmpty(), () -> "a fresh send escalated: " + errors);
    }
  }

  /// One in-flight request aged by the largest step a [TestClock] admits, about 9.2e12 ms,
  /// against resend windows one below, at, and one above the saturation boundary: none
  /// escalates. A window whose deadline sits below that age escalates at the same age, so
  /// the age is real and is measured.
  @Test
  void aWindowAtTheSaturationBoundaryNeverEscalatesAtTheLargestAge() throws InterruptedException {
    final long boundary = Long.MAX_VALUE / SolanaJsonRpcWebsocket.UNANSWERED_ESCALATION_FACTOR;
    for (final long window : new long[]{boundary - 1L, boundary, boundary + 1L}) {
      final var clock = new TestClock();
      final var errors = new ArrayList<Throwable>();
      try (final var ws = websocket(timings(window), clock, errors)) {
        assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
        }));
        final var socket = new RecordingWebSocket();
        ws.onOpen(socket); // transmitted, unanswered
        clock.advanceMillis(largestStepMillis(clock));
        ws.checkCycle(0L);
        assertAll(
            () -> assertTrue(errors.isEmpty(), () -> "window " + window + " escalated: " + errors),
            () -> assertFalse(socket.aborted, "window " + window + " retired the connection"),
            () -> assertEquals(1, socket.sentText.size(), socket.sentText::toString)
        );
      }
    }

    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    final long age = largestStepMillis(clock);
    try (final var ws = websocket(timings(age / 8), clock, errors)) {
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      clock.advanceMillis(age);
      ws.checkCycle(0L);
      assertEquals(1, errors.size(), "a deadline below the age escalates");
      assertTrue(errors.getFirst().getMessage().contains("unanswered for " + age + "ms"),
          errors.getFirst()::getMessage);
      assertTrue(socket.aborted);
    }
  }

  /// A cancellation rejected for a server condition is retried one resend window later. The
  /// clock is not advanced before the rejection, so the pacing time is 1 and a window of
  /// `Long.MAX_VALUE - 1` lands exactly on the saturation boundary; its neighbours land one
  /// either side. Every one of them parks the retry past the largest clock step, while a
  /// reachable window re-sends it on time.
  @Test
  void aRetryAtTheSaturatedAddBoundaryStaysParked() throws InterruptedException {
    for (final long window : new long[]{Long.MAX_VALUE - 2L, Long.MAX_VALUE - 1L, Long.MAX_VALUE}) {
      final var clock = new TestClock();
      final var rejected = rejectedCancellation(window, clock);
      try (final var ws = rejected.ws()) {
        clock.advanceMillis(largestStepMillis(clock));
        ws.checkCycle(0L);
        assertEquals(1, framesContaining(rejected.socket(), "[700]"),
            () -> "window " + window + " re-sent the cancellation: " + rejected.socket().sentText);
      }
    }

    final long window = 60_000L;
    final var clock = new TestClock();
    final var reachable = rejectedCancellation(window, clock);
    try (final var ws = reachable.ws()) {
      clock.advanceMillis(window - 1L);
      ws.checkCycle(0L);
      assertEquals(1, framesContaining(reachable.socket(), "[700]"), "not yet due");
      clock.advanceMillis(1L);
      ws.checkCycle(0L);
      assertEquals(2, framesContaining(reachable.socket(), "[700]"),
          () -> "the retry is due one window after the rejection: " + reachable.socket().sentText);
    }
  }

  private record Rejected(SolanaJsonRpcWebsocket ws, RecordingWebSocket socket) {
  }

  /// A confirmed account subscription, cancelled, whose cancellation the server rejects
  /// with -32603, all at pacing time 1.
  private static Rejected rejectedCancellation(final long window, final TestClock clock)
      throws InterruptedException {
    final var errors = new ArrayList<Throwable>();
    final var ws = websocket(timings(window), clock, errors);
    try {
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}""");
      assertTrue(ws.accountUnsubscribe(ACCOUNT));
      ws.checkCycle(0L); // cancellation id 3
      assertEquals(1, framesContaining(socket, "[700]"), socket.sentText::toString);
      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32603,"message":"Internal error"},"id":3}""");
      ws.checkCycle(0L);
      assertEquals(1, framesContaining(socket, "[700]"), "a rejected cancellation waits out its window");
      assertTrue(errors.isEmpty(), () -> "the rejection escalated: " + errors);
      return new Rejected(ws, socket);
    } catch (final RuntimeException | Error | InterruptedException ex) {
      ws.close();
      throw ex;
    }
  }
}
