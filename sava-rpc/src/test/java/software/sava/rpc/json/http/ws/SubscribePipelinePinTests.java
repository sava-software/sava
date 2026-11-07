package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.client.TestLogs;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import systems.comodal.jsoniter.JsonException;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.*;

/// Characterization pins for the websocket subscribe pipeline: what the engine does today with
/// an inbound JSON-RPC batch array, which it does not read while batching is off, a request over
/// Agave's inbound message limit, a request the JDK's UTF-8 encoder refuses, a send that fails,
/// and a request cancelled by an earlier request's `onSub` within one maintenance pass. Each test
/// pins current behaviour, so a change that moves one of these outcomes shows up here as a
/// behaviour change rather than a side effect.
@ExtendWith(QuietWsLogging.class)
final class SubscribePipelinePinTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  /// Large windows keep resends and pings away unless a test steps the clock over them.
  private static final Timings TIMINGS = new Timings(60_000L, 60_000L, 60_000L);
  /// Agave's limit on one inbound websocket message, inclusive, in UTF-8 bytes: hard-coded as
  /// `set_max_message_size(4_096)` in `rpc/src/rpc_pubsub_service.rs`. A message one byte
  /// longer draws a TCP reset with no close frame and no answer (measured against the
  /// solana-cli 3.1.10 validator).
  private static final int AGAVE_MAX_INBOUND_MESSAGE_BYTES = 4_096;
  private static final PublicKey KEY_A = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
  private static final PublicKey KEY_X = PublicKey.fromBase58Encoded("GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc");
  private static final PublicKey KEY_C = PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final PublicKey PROGRAM = PublicKey.fromBase58Encoded("11111111111111111111111111111111");

  private static SolanaJsonRpcWebsocket websocket(final TestClock clock) {
    return websocket(clock, null);
  }

  /// `onSendTextError` is the one seam a test here observes; null logs a failed send instead.
  private static SolanaJsonRpcWebsocket websocket(final TestClock clock,
                                                  final BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError) {
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
        onSendTextError,
        null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  private static long framesContaining(final RecordingWebSocket socket, final String token) {
    return socket.sentText.stream().filter(frame -> frame.contains(token)).count();
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
    assertEquals(1, matches.size(), () -> "expected one " + level + " record for '" + template + "', got: "
        + records.stream().map(record -> record.getLevel() + " " + record.getMessage()).toList());
    final var record = matches.getFirst();
    final var actualParameters = record.getParameters();
    assertArrayEquals(parameters, actualParameters == null ? new Object[0] : actualParameters,
        "diagnostic parameters classify the specific outcome");
    return record;
  }

  /// With batching off, which is what the constructor without a batch cap builds, an inbound
  /// top-level array is not a message the engine reads: its envelope scan meets `[` where it
  /// expects an object, and the handler's catch contains the parser's `JsonException` as one
  /// "Unexpected json rpc error." diagnostic and one dispatch to the exception subscribers.
  /// Nothing inside the array is correlated, so both requests stay gated: no subscription id is
  /// installed, both attempt ordinals stay open, and neither request is re-sent once the resend
  /// window passes. The failure stays with that message: single answers that follow still
  /// confirm. Agave answers a batch request with exactly such an array, which
  /// `BatchResponseDispatchTests` reads with batching on.
  @Test
  void anInboundArrayIsOneContainedParseFailure() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var dispatched = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(dispatched::add);
      final var subA = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      final var subX = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, subA::set, _ -> {
      }));
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_X, subX::set, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // requests 2 and 3 on the wire, unanswered
      assertEquals(2, socket.sentText.size(), socket.sentText::toString);

      final var records = TestLogs.capture(SolanaJsonRpcWebsocket.class, () -> feed(ws, socket, """
          [{"jsonrpc":"2.0","result":700,"id":2},{"jsonrpc":"2.0","result":701,"id":3}]"""));

      final var diagnostic = assertDiagnostic(records, Level.WARNING, "Unexpected json rpc error.");
      assertEquals(1, dispatched.size(), dispatched::toString);
      final var failure = assertInstanceOf(JsonException.class, dispatched.getFirst(),
          "the parser's own refusal of the array is what reaches the exception subscribers");
      assertSame(failure, diagnostic.getThrown(), "the diagnostic and the dispatch report one failure");
      assertAll(
          () -> assertNull(subA.get().subId(), "no element of the array is installed as a grant"),
          () -> assertNull(subX.get().subId(), "no element of the array is installed as a grant"),
          () -> assertEquals(2, ws.retainedOrdinalEntries(), "both transmitted attempts are still open")
      );

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L);
      ws.checkCycle(0L);
      assertEquals(2, socket.sentText.size(),
          () -> "both requests are still gated as awaiting an answer, so neither is re-sent: " + socket.sentText);

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":701,"id":3}""");
      assertAll(
          () -> assertEquals(BigInteger.valueOf(700), subA.get().subId(), "the next single answer still confirms"),
          () -> assertEquals(BigInteger.valueOf(701), subX.get().subId(), "the next single answer still confirms"),
          () -> assertEquals(1, dispatched.size(), dispatched::toString)
      );
    }
  }

  /// A `programSubscribe` whose rendered frame is larger than Agave accepts is sent whole and
  /// exactly as rendered: the engine neither splits, trims nor refuses it, so on Agave the
  /// request costs the connection. Every abstract `programSubscribe` and `keyedProgramSubscribe`
  /// method that takes a filter list documents it.
  @Test
  void anOversizeRequestIsSentUnchanged() {
    try (final var ws = websocket(new TestClock())) {
      // Filters until they alone exceed the limit, each one the largest memcmp sava builds.
      final var filters = new ArrayList<Filter>();
      int filterBytes = 0;
      for (int i = 1; filterBytes <= AGAVE_MAX_INBOUND_MESSAGE_BYTES; ++i) {
        final byte[] bytes = new byte[Filter.MAX_MEM_COMP_LENGTH];
        Arrays.fill(bytes, (byte) i);
        final var filter = Filter.createMemCompFilter(i * Filter.MAX_MEM_COMP_LENGTH, bytes);
        filters.add(filter);
        filterBytes += filter.toJson().getBytes(UTF_8).length;
      }
      final var sub = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      assertTrue(ws.programSubscribe(Commitment.CONFIRMED, null, PROGRAM, filters, sub::set, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      final var rendered = sub.get().msg();
      assertTrue(rendered.getBytes(UTF_8).length > AGAVE_MAX_INBOUND_MESSAGE_BYTES,
          () -> "the request must exceed Agave's limit for this pin to mean anything: "
              + rendered.getBytes(UTF_8).length + " bytes");
      assertEquals(List.of(rendered), socket.sentText, "sent whole, as one message, exactly as rendered");
      final var filtersJson = filters.stream().map(Filter::toJson).collect(joining(",", "\"filters\":[", "]"));
      assertTrue(rendered.contains(filtersJson), "the rendering carries every filter, in order");
    }
  }

  /// A generic request whose raw `paramsJson` holds a lone surrogate cannot be encoded as UTF-8,
  /// so the JDK transport fails its send, which `RecordingWebSocket.strictUtf8` reproduces. The
  /// failure is that request's alone: `onSendTextError` hears it once, the ordinary request
  /// chained behind it is sent and confirmed, and the broken one keeps its last-attempt stamp,
  /// so it is retried only once the resend window is exceeded, not at its boundary, and then
  /// fails alone again.
  @Test
  void anUnencodableGenericRequestFailsAloneOncePerWindow() throws InterruptedException {
    final var clock = new TestClock();
    final var sendFailures = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, (_, ex) -> sendFailures.add(ex))) {
      assertTrue(ws.subscribe("fooSubscribe", "fooUnsubscribe", "fooNotification", "lone-surrogate",
          "\"\uD83D\"", JsonIterator::readLong, null, _ -> {
          })); // request 2
      final var account = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, account::set, _ -> {
      })); // request 3
      final var socket = new RecordingWebSocket();
      socket.strictUtf8 = true;
      ws.onOpen(socket);

      assertAll(
          () -> assertEquals(1, framesContaining(socket, "\"id\":2,"), socket.sentText::toString),
          () -> assertEquals(1, framesContaining(socket, "\"id\":3,"),
              "the failed send ahead of it does not hold back the ordinary request"),
          () -> assertEquals(1, sendFailures.size(), sendFailures::toString)
      );
      final var failure = assertInstanceOf(IOException.class, sendFailures.getFirst());
      assertEquals("Malformed text message", failure.getMessage());

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":3}""");
      assertEquals(BigInteger.valueOf(700), account.get().subId(), "the ordinary request is confirmed");

      clock.advanceMillis(TIMINGS.subscriptionResendDelay());
      ws.checkCycle(0L);
      assertAll(
          () -> assertEquals(1, framesContaining(socket, "\"id\":2,"),
              "an age equal to the resend window has not exceeded it"),
          () -> assertEquals(1, sendFailures.size(), sendFailures::toString)
      );

      clock.advanceMillis(1L);
      ws.checkCycle(0L);
      assertAll(
          () -> assertEquals(2, framesContaining(socket, "\"id\":2,"), "retried once the window is exceeded"),
          () -> assertEquals(2, sendFailures.size(), "and refused again, alone"),
          () -> assertEquals(1, framesContaining(socket, "\"id\":3,"), "the confirmed request is not re-sent"),
          () -> assertEquals(BigInteger.valueOf(700), account.get().subId(), "nor disturbed")
      );
    }
  }

  /// `onSub` runs after each successful send of its request, retries included (the published
  /// `Subscription` contract), so a send that fails runs none: the frame never left. The
  /// request's send fails on the adopting connection and its `onSub` stays silent; once the
  /// resend window is exceeded its retry reaches the wire, and that send runs `onSub` exactly
  /// once, with the registration.
  @Test
  void aFailedSendRunsNoOnSubAndTheRetrySentRunsItOnce() throws InterruptedException {
    final var clock = new TestClock();
    final var sendFailures = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, (_, ex) -> sendFailures.add(ex))) {
      final var onSub = new ArrayList<Subscription<AccountInfo<byte[]>>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, onSub::add, _ -> {
      })); // request 2
      final var socket = new RecordingWebSocket();
      socket.failText = new IOException("the frame never left");
      ws.onOpen(socket);

      assertAll(
          () -> assertEquals(1, framesContaining(socket, "\"id\":2,"), socket.sentText::toString),
          () -> assertEquals(1, sendFailures.size(), "the send was attempted and failed: " + sendFailures),
          () -> assertEquals(List.of(), onSub, "a failed send runs no onSub")
      );

      socket.failText = null;
      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L);
      ws.checkCycle(0L);

      assertAll(
          () -> assertEquals(2, framesContaining(socket, "\"id\":2,"), "retried once the window is exceeded"),
          () -> assertEquals(1, sendFailures.size(), "and the retry was sent: " + sendFailures),
          () -> assertEquals(1, onSub.size(), "the retry's send ran onSub once"),
          () -> assertEquals(2L, onSub.getFirst().msgId(), "with the registration of request 2")
      );
    }
  }

  /// A send that completes synchronously, as the fake's do and the JDK's can, runs its `onSub`
  /// inside the maintenance pass, so `onSub` can cancel a request the same pass has not reached
  /// yet. A (request 2) is due again after a transient refusal, X (request 3) is transmitted and
  /// still awaiting its answer, and C (request 4) is registered after both; A's re-send runs its
  /// `onSub`, which cancels C. The pass sends A's frame and nothing else: C gets no frame, no
  /// `onSub` call, no attempt ordinal and no tombstone. X, awaiting between A and C, is what
  /// separates this shape from the adjacent one, where C directly follows A
  /// (`ReadySetTests.aRequestCancelledByAnEarlierOnSubInTheSamePassIsNeverSent`); both end the
  /// same way, with C never sent.
  @Test
  void aRequestCancelledByAnEarlierOnSubBeyondAnAwaitingRequestIsNeverSent() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var cancellations = new ArrayList<Boolean>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A,
          _ -> cancellations.add(ws.accountUnsubscribe(Commitment.CONFIRMED, KEY_C)), _ -> {
          })); // A, request 2
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_X, null, _ -> {
      })); // X, request 3
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // sends A and X; A's onSub finds no C to cancel yet
      assertEquals(2, socket.sentText.size(), socket.sentText::toString);
      final var frameA = socket.sentText.getFirst();
      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32603,"message":"Subscription refused"},"id":2}""");
      // A is due again; X has waited one window, far from its unanswered deadline.
      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L);
      final var onSubC = new AtomicInteger();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_C, _ -> onSubC.incrementAndGet(), _ -> {
      })); // C, request 4, never attempted
      final int sentBefore = socket.sentText.size();

      ws.checkCycle(0L);

      assertAll(
          () -> assertEquals(List.of(false, true), cancellations,
              "A's onSub ran on each send, and its second run cancelled C inside the pass"),
          () -> assertEquals(List.of(frameA), socket.sentText.subList(sentBefore, socket.sentText.size()),
              "the pass sends A's re-send and nothing else"),
          () -> assertEquals(0, framesContaining(socket, "\"id\":4,"), socket.sentText::toString),
          () -> assertEquals(0, framesContaining(socket, KEY_C.toBase58()), socket.sentText::toString),
          () -> assertEquals(0, onSubC.get(), "C was never sent, so its onSub never ran"),
          () -> assertEquals(0, ws.retainedCancellationTombstones(), "C was never admitted, so it owes no tombstone"),
          () -> assertEquals(2, ws.retainedOrdinalEntries(), "only A's re-send and the awaiting X hold ordinals")
      );
    }
  }
}
