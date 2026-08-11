package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;

import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.*;

/// The diagnostics that are the *only* record of what happened, asserted through
/// `System.Logger`'s JUL backend rather than assumed — the check-loop funnel's
/// technique, applied to the rest of the class.
///
/// These were accepted as "logging only" while the harness had no way to read a
/// log, which made the removals unobservable by construction rather than by
/// argument. They are not: a ping/pong payload, a close reason, and a send/ping
/// failure with no user handler installed exist nowhere else — drop the call and
/// the event leaves no trace at all. The lazy `() -> new String(...)` suppliers
/// only run when DEBUG is enabled, so a suite that never enables it reads them as
/// uncovered; enabling it is what makes them ordinary kills.
@ExtendWith(QuietWsLogging.class)
final class WsDiagnosticLogTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);

  private static SolanaJsonRpcWebsocket createWebsocket() {
    return createWebsocket(new TestClock());
  }

  /// `onSendTextError` / `onPingError` left null on purpose: the specific
  /// observation branch is the one whose output is the log line.
  private static SolanaJsonRpcWebsocket createWebsocket(final TestClock clock) {
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
        null,
        (_, _) -> {
        },
        null, null
    );
  }

  /// Captures what the class logs while [action] runs. [level] is set on the JUL
  /// logger because `System.Logger.DEBUG` maps to `Level.FINE`: without it the
  /// backend discards the record before the handler sees it, and the lazy message
  /// supplier is never invoked at all.
  private static List<LogRecord> capture(final Level level, final Runnable action) {
    final var records = new ArrayList<LogRecord>();
    final var handler = new Handler() {
      @Override
      public void publish(final LogRecord record) {
        records.add(record);
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    handler.setLevel(Level.ALL);
    final var julLogger = Logger.getLogger(SolanaJsonRpcWebsocket.class.getName());
    final var previousLevel = julLogger.getLevel();
    final boolean parentHandlers = julLogger.getUseParentHandlers();
    julLogger.setUseParentHandlers(false);
    julLogger.setLevel(level);
    julLogger.addHandler(handler);
    try {
      action.run();
    } finally {
      julLogger.removeHandler(handler);
      julLogger.setLevel(previousLevel);
      julLogger.setUseParentHandlers(parentHandlers);
    }
    return records;
  }

  /// Pattern *and* parameters: the close lines are MessageFormat templates whose
  /// reason arrives as a parameter, so asserting on `getMessage()` alone would
  /// pass no matter which branch built the record.
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

  /// The payload each frame carried is recorded only by these two lines, and only
  /// through a lazy supplier that DEBUG gates. Diagnostics read a duplicate: logging
  /// must not consume the caller's buffer while it records the remaining payload.
  @Test
  void pingAndPongLogTheirPayload() {
    try (final var ws = createWebsocket()) {
      final var socket = new RecordingWebSocket();
      final var pingPayload = "ping-payload-4711";
      final var pongPayload = "pong-payload-8123";
      final var pingBuffer = ByteBuffer.wrap(("skip:" + pingPayload).getBytes(ISO_8859_1));
      final var pongBuffer = ByteBuffer.wrap(("skip:" + pongPayload).getBytes(ISO_8859_1));
      pingBuffer.position(5);
      pongBuffer.position(5);

      // the socket must be current: control frames from a never-installed socket are rejected
      ws.onOpen(socket);
      final var records = capture(Level.ALL, () -> {
        ws.onPing(socket, pingBuffer);
        ws.onPong(socket, pongBuffer);
      });

      final var logged = messages(records);
      assertAll(
          () -> assertTrue(logged.contains(pingPayload), () -> "ping payload not logged: " + logged),
          () -> assertTrue(logged.contains(pongPayload), () -> "pong payload not logged: " + logged),
          () -> assertEquals(5, pingBuffer.position(),
              "lazy Ping diagnostics must not consume the caller's buffer"),
          () -> assertEquals(5, pongBuffer.position(),
              "lazy Pong diagnostics must not consume the caller's buffer")
      );
    }
  }

  /// Both branches close, so the reason-blank ternary is observable only in what
  /// the record says. A blank reason must not produce the with-reason message.
  @Test
  void closeLogsTheReasonAndDistinguishesABlankOne() {
    final var withReason = capture(Level.ALL, () -> {
      try (final var ws = createWebsocket()) {
        final var socket = new RecordingWebSocket();
        ws.onOpen(socket);
        ws.onClose(socket, 1011, "upstream exploded");
      }
    });
    assertTrue(messages(withReason).contains("upstream exploded"),
        () -> "close reason not logged: " + messages(withReason));

    final var blankReason = capture(Level.ALL, () -> {
      try (final var ws = createWebsocket()) {
        final var socket = new RecordingWebSocket();
        ws.onOpen(socket);
        ws.onClose(socket, 1011, "  ");
      }
    });
    // asserting only that the two differ would be satisfied by logging *nothing*
    // on the blank branch, so pin that it still reports the close and its code
    assertFalse(blankReason.isEmpty(), "a blank reason logged nothing at all");
    assertTrue(messages(blankReason).contains("1011"), () -> "blank close lost its code: " + messages(blankReason));
    assertFalse(messages(blankReason).contains("upstream exploded"), "blank close leaked a stale reason");
    final var blankCloseRecord = blankReason.stream()
        .filter(record -> record.getMessage().contains("closed with code"))
        .findFirst()
        .orElseThrow();
    assertFalse(blankCloseRecord.getMessage().contains("because"),
        "a blank reason must use the no-reason diagnostic template");
    assertEquals(2, blankCloseRecord.getParameters().length,
        "the no-reason template carries only endpoint and status code");
    assertNotEquals(messages(withReason), messages(blankReason),
        "the reason-blank ternary picked the same message for both branches");

    // a null reason takes the same branch as a blank one; forcing the `reason ==
    // null` operand false would dereference it, so the branch is not a free pass
    final var nullReason = capture(Level.ALL, () -> {
      try (final var ws = createWebsocket()) {
        final var socket = new RecordingWebSocket();
        ws.onOpen(socket);
        assertDoesNotThrow(() -> ws.onClose(socket, 1011, null));
      }
    });
    assertFalse(nullReason.isEmpty(), "a null reason logged nothing at all");
    assertTrue(messages(nullReason).contains("1011"), () -> "null close lost its code: " + messages(nullReason));
  }

  /// With no `onSendTextError` installed the failed write is reported nowhere else:
  /// the future is consumed inside `whenComplete` and nothing rethrows.
  @Test
  void sendTextFailureWithNoHandlerIsLogged() {
    try (final var ws = createWebsocket()) {
      final var socket = new RecordingWebSocket();
      socket.failText = new IllegalStateException("write rejected");
      // onOpen only writes if there is something queued to flush
      ws.slotSubscribe(_ -> {
      });

      final var records = capture(Level.ALL, () -> ws.onOpen(socket));
      assertFalse(socket.sentText.isEmpty(), "no text was attempted");

      final var logged = messages(records);
      assertTrue(logged.contains("Failed to sendText"), () -> "send failure not logged: " + logged);
      assertTrue(logged.contains(ENDPOINT.getHost()), () -> "send failure lost its endpoint: " + logged);
    }
  }

  /// Opening the connection is traced end to end: the endpoint it connected to,
  /// the frame it wrote, and the confirmation that the write completed. Each is a
  /// separate call and each is the only record of its step — a successful send in
  /// particular leaves nothing else behind, since the future is consumed inside
  /// `whenComplete`. `request(Long.MAX_VALUE)` is asserted on the socket rather
  /// than in the log: it is what starts delivery, so dropping it is a real defect.
  @Test
  void openingTheConnectionIsTracedAndRequestsDelivery() {
    try (final var ws = createWebsocket()) {
      final var socket = new RecordingWebSocket();
      ws.slotSubscribe(_ -> {
      });

      final var records = capture(Level.ALL, () -> ws.onOpen(socket));

      final var logged = messages(records);
      assertTrue(logged.contains(ENDPOINT.getHost()), () -> "connect not logged: " + logged);
      assertTrue(logged.contains("Writing text"), () -> "outgoing frame not traced: " + logged);
      assertTrue(logged.contains("Sent text"), () -> "completed write not traced: " + logged);
      assertEquals(Long.MAX_VALUE, socket.requested, "onOpen must request message delivery");
    }
  }

  /// A successful outbound Ping has no callback or returned future exposed to the caller. Its
  /// DEBUG record is therefore the only durable observation of which wire timestamp was sent to
  /// which endpoint; assert the JUL template and parameters rather than formatted text.
  @Test
  void aSuccessfulOutboundPingIsTracedExactly() {
    final var clock = new TestClock();
    try (final var ws = createWebsocket(clock)) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      final long elapsedMillis = TIMINGS.pingDelay() + 1L;
      clock.advanceMillis(elapsedMillis);

      final var records = capture(Level.FINE,
          () -> assertDoesNotThrow(() -> ws.checkCycle(0L)));

      assertEquals(1, socket.pings, "the due liveness probe must reach the transport");
      final var record = assertDiagnostic(
          records,
          Level.FINE,
          "{0} to {1}.\n",
          Long.toString(elapsedMillis + 1L),
          ENDPOINT.getHost()
      );
      assertNull(record.getThrown(), "a successful Ping diagnostic carries no failure");
    }
  }

  /// Same standing for the Ping-specific observation: ordinary error policy also receives the
  /// transport failure, while the absent onPingError handler leaves this diagnostic to the log.
  @Test
  void pingFailureWithNoHandlerIsLogged() {
    final var clock = new TestClock();
    try (final var ws = createWebsocket(clock)) {
      final var socket = new RecordingWebSocket();
      socket.failPing = new IllegalStateException("ping rejected");
      ws.onOpen(socket);

      // Driven through the check seam rather than an inbound frame: any frame from the peer is
      // evidence it is there, which restarts the ping window, so it cannot also be used to make
      // a ping fall due.
      final var records = capture(Level.ALL, () -> {
        clock.advanceMillis(TIMINGS.pingDelay() + 1);
        assertDoesNotThrow(() -> ws.checkCycle(0L));
      });
      assertEquals(1, socket.pings, "no ping was attempted");

      final var logged = messages(records);
      assertTrue(logged.contains("Failed to ping"), () -> "ping failure not logged: " + logged);
      assertTrue(logged.contains(ENDPOINT.getHost()), () -> "ping failure lost its endpoint: " + logged);
    }
  }

  /// A failed Ping completed after takeover is expected teardown noise, so it must not reach the
  /// successor's error policy. The DEBUG trace is the remaining evidence that the completion was
  /// observed and deliberately discarded rather than silently lost.
  @Test
  void supersededSocketPingFailureIsTraced() {
    final var clock = new TestClock();
    try (final var ws = createWebsocket(clock)) {
      final var first = new RecordingWebSocket();
      first.deferPings = true;
      ws.onOpen(first);
      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, first.deferredPings.size(), "the predecessor owns one pending Ping");

      final var successor = new RecordingWebSocket();
      ws.onOpen(successor);
      assertTrue(first.aborted, "takeover retires the predecessor transport");

      final var records = capture(Level.ALL, () -> assertTrue(
          first.deferredPings.getFirst().completeExceptionally(
              new IllegalStateException("abort failed the predecessor Ping")
          )
      ));

      final var logged = messages(records);
      assertTrue(logged.contains("Dropped ping on a superseded socket."),
          () -> "the ignored stale Ping failure left no diagnostic: " + logged);
      assertFalse(successor.aborted, "the stale completion must not disturb its successor");
    }
  }

  @Test
  void aDefaultTransportErrorIsLoggedBeforeTheWrapperCloses() {
    final var failure = new IllegalStateException("transport exploded");
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null, TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), null, null, null, null, null, null
    );
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);

    final var records = capture(Level.ALL, () -> ws.onError(socket, failure));

    assertTrue(ws.closed());
    assertTrue(records.stream().anyMatch(record ->
            record.getThrown() == failure
                && record.getMessage().contains(ENDPOINT.getHost())),
        () -> "the terminal transport failure left no diagnostic: " + messages(records));
  }

  @Test
  void aThrowingPingObserverIsContainedAndDiagnosed() {
    final var clock = new TestClock();
    final var observerFailure = new IllegalStateException("ping observer failed");
    try (final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null, TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock,
        new RecordingExecutor(), null, null, null, (_, _) -> {
        }, null, (_, _) -> {
          throw observerFailure;
        }
    )) {
      final var socket = new RecordingWebSocket();
      socket.failPing = new IllegalStateException("ping failed");
      ws.onOpen(socket);

      final var records = capture(Level.ALL, () -> {
        clock.advanceMillis(TIMINGS.pingDelay() + 1);
        assertDoesNotThrow(() -> ws.checkCycle(0L));
      });

      assertTrue(records.stream().anyMatch(record -> record.getThrown() == observerFailure),
          () -> "the throwing Ping observer was not diagnosed: " + messages(records));
      assertFalse(ws.closed(), "the custom ordinary error policy keeps the wrapper reusable");
    }
  }

  private static final software.sava.core.accounts.PublicKey KEY =
      software.sava.core.accounts.PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");

  private static final String UNKNOWN_ACCOUNT_NOTIFICATION = """
      {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":3},"value":{"lamports":1,"data":["","base64"],"owner":"11111111111111111111111111111111","executable":false,"rentEpoch":0,"space":0}},"subscription":555}}""";

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, java.nio.CharBuffer.wrap(json), true);
  }

  private static void genericSubscribe(final SolanaJsonRpcWebsocket ws,
                                       final String key,
                                       final String params) {
    assertTrue(ws.subscribe("fooSubscribe", "fooUnsubscribe", "fooNotification", key, params,
        systems.comodal.jsoniter.JsonIterator::readLong, null, _ -> {
        }));
  }

  /// These six records distinguish protocol outcomes that otherwise converge on the same
  /// callback or lifecycle action: failed cancellation versus cancelled request versus two live
  /// requests, and equivalent coalescing versus a fatal non-equivalent id collision. The exact
  /// template, severity, and parameters are the diagnostic contract; state assertions belong to
  /// the correlation tests that set up these already-proven paths.
  @Test
  void correlationDiagnosticsClassifySixDistinctWireOutcomes() {
    try (final var ws = createWebsocket()) {
      genericSubscribe(ws, "predecessor", "\"p\"");
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":55,"id":2}""");
      assertTrue(ws.unsubscribe("fooNotification", "predecessor"));
      ws.onPong(socket, ByteBuffer.wrap(new byte[0])); // cancellation id 3
      genericSubscribe(ws, "successor", "\"q\"");
      ws.onPong(socket, ByteBuffer.wrap(new byte[0])); // successor id 4
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":55,"id":4}""");

      final var records = capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32603,"message":"Internal error"},"id":3}"""));
      assertDiagnostic(records, Level.SEVERE,
          "Un-subscription 3 for id 55 from " + ENDPOINT.getHost()
              + " failed while a non-equivalent successor successor owns the id; replacing the connection.");
    }

    try (final var ws = createWebsocket()) {
      genericSubscribe(ws, "owner", "\"p\"");
      genericSubscribe(ws, "loser", "\"p\"");
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertTrue(ws.unsubscribe("fooNotification", "loser"));
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":55,"id":2}""");

      final var records = capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","result":55,"id":3}"""));
      assertDiagnostic(records, Level.FINE,
          "Cancelled request {0} was coalesced onto live subscription {1}; nothing to cancel.",
          3L, BigInteger.valueOf(55));
    }

    try (final var ws = createWebsocket()) {
      genericSubscribe(ws, "owner", "\"p\"");
      genericSubscribe(ws, "loser", "\"q\"");
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":55,"id":2}""");
      assertTrue(ws.unsubscribe("fooNotification", "loser"));

      final var records = capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","result":55,"id":3}"""));
      assertDiagnostic(records, Level.SEVERE,
          "Subscription id 55 from " + ENDPOINT.getHost()
              + " was assigned to a cancelled request that is not equivalent to its live owner owner; "
              + "replacing the connection.");
    }

    try (final var ws = createWebsocket()) {
      genericSubscribe(ws, "first", "\"p\"");
      genericSubscribe(ws, "second", "\"p\"");
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertTrue(ws.unsubscribe("fooNotification", "first"));
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":55,"id":2}"""); // compensation id 4
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":4}""");

      final var records = capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","result":55,"id":3}"""));
      assertDiagnostic(records, Level.WARNING,
          "Grant {0} for request {1} was already cancelled; re-subscribing {2}.",
          BigInteger.valueOf(55), 3L, "second");
    }

    try (final var ws = createWebsocket()) {
      genericSubscribe(ws, "first", "\"p\"");
      genericSubscribe(ws, "second", "\"p\"");
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":55,"id":2}""");

      final var records = capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","result":55,"id":3}"""));
      assertDiagnostic(records, Level.WARNING,
          "Subscription id 55 from " + ENDPOINT.getHost()
              + " is already owned by first; releasing second — the server coalesced identical params "
              + "onto one subscription.");
    }

    try (final var ws = createWebsocket()) {
      genericSubscribe(ws, "first", "\"p\"");
      genericSubscribe(ws, "second", "\"q\"");
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":55,"id":2}""");

      final var records = capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","result":55,"id":3}"""));
      assertDiagnostic(records, Level.SEVERE,
          "Subscription id 55 from " + ENDPOINT.getHost()
              + " was assigned to non-equivalent requests first and second; replacing the connection.");
    }
  }

  /// Singleton drops and a parser failure are all intentionally non-throwing listener outcomes,
  /// so assert their exact WARNING classifications rather than inferring them from unchanged
  /// subscription state.
  @Test
  void rootAndMalformedNotificationDiagnosticsAreExact() {
    try (final var ws = createWebsocket()) {
      assertTrue(ws.accountSubscribe(KEY, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}""");

      final var records = capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","method":"rootNotification","params":{"result":2,"subscription":700}}"""));
      assertDiagnostic(records, Level.WARNING,
          "Dropping root notification whose subscription {0} belongs to {1}.",
          BigInteger.valueOf(700), Channel.account);
    }

    try (final var ws = createWebsocket()) {
      assertTrue(ws.rootSubscribe(_ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      final var early = capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","method":"rootNotification","params":{"result":2,"subscription":9}}"""));
      assertDiagnostic(early, Level.WARNING,
          "Dropping root notification {0} received before the subscription was confirmed.",
          BigInteger.valueOf(9));

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":9,"id":2}""");
      final var malformed = capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","method":"rootNotification","params":{"result":{},"subscription":9}}"""));
      final var parseFailure = assertDiagnostic(malformed, Level.WARNING, "Unexpected json rpc error.");
      assertNotNull(parseFailure.getThrown(), "the malformed-frame diagnostic must retain its parse failure");
    }
  }

  /// How an un-subscription rejection was CLASSIFIED exists nowhere but its log line: settled
  /// quietly, reported as an unrecognized method, or re-queued as still owed. Each branch
  /// takes the same visible action otherwise — the gates release either way — so the record is
  /// the only evidence the engine chose the right one.
  @Test
  void unsubscribeRejectionClassificationsAreRecorded() {
    try (final var ws = createWebsocket()) {
      assertTrue(ws.accountSubscribe(KEY, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // subscribe id 2
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":555,"id":2}""");
      assertTrue(ws.accountUnsubscribe(KEY));
      ws.onPong(socket, ByteBuffer.wrap(new byte[0])); // cancellation id 3

      final var unrecognized = messages(capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32601,"message":"Method not found"},"id":3}""")));
      assertTrue(unrecognized.contains("does not recognize"),
          () -> "the unrecognized-method classification is unrecorded: " + unrecognized);
    }
    try (final var ws = createWebsocket()) {
      assertTrue(ws.accountSubscribe(KEY, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":555,"id":2}""");
      assertTrue(ws.accountUnsubscribe(KEY));
      ws.onPong(socket, ByteBuffer.wrap(new byte[0]));

      final var settled = messages(capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid subscription id."},"id":3}""")));
      assertTrue(settled.contains("treating as settled"),
          () -> "the already-absent settlement is unrecorded: " + settled);
    }
  }

  /// A quiet Helius `result:false` deliberately removes and replays nothing, so the DEBUG line
  /// is the entire trace that the acknowledgement arrived and was understood as already-gone.
  @Test
  void aFalseAcknowledgementRecordsThatTheIdWasAlreadyGone() {
    final var sig = "2EBVM6cB8vAAD93Ktr6Vd8p67XPbQzCJX47MpReuiCXJAtcjaxpvWpcg9Ege1Nr5Tk3a2GFrByT7WPBjdsTycY9b";
    try (final var ws = createWebsocket()) {
      assertTrue(ws.signatureSubscribe(sig, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":777,"id":2}""");
      assertTrue(ws.signatureUnsubscribe(sig));
      ws.onPong(socket, ByteBuffer.wrap(new byte[0])); // cancellation id 3

      final var logged = messages(capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","result":false,"id":3}""")));
      assertTrue(logged.contains("already gone server side"),
          () -> "the false acknowledgement left no record: " + logged);
    }
  }

  /// A subscribe the server rejects as a request defect is retired and its registry slot freed.
  /// The consumer sees the exception either way; only the WARNING says the slot was released
  /// and for which channel and key.
  @Test
  void aRequestDefectRejectionRecordsTheReleasedRegistration() {
    try (final var ws = createWebsocket()) {
      assertTrue(ws.accountSubscribe(KEY, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // subscribe id 2, unconfirmed

      final var logged = messages(capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params"},"id":2}""")));
      assertTrue(logged.contains("rejected by") && logged.contains(KEY.toBase58()),
          () -> "the released registration is unrecorded: " + logged);
    }
  }

  /// The wire-order adjudications - a cancellation made obsolete by a successor that owns its
  /// id, and a live subscription the server cancelled and the engine replayed - change engine
  /// state silently. Their records are what a reader has to reconstruct which rule fired.
  @Test
  void wireOrderAdjudicationsAreRecorded() {
    try (final var ws = createWebsocket()) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertTrue(ws.accountSubscribe(KEY, _ -> {
      }));
      ws.onPong(socket, ByteBuffer.wrap(new byte[0])); // subscribe id 2, wire 1
      feed(ws, socket, UNKNOWN_ACCOUNT_NOTIFICATION); // auto-cancellation id 3, wire 2
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":555,"id":2}"""); // the grant installs at 555, ordinal 1

      final var replay = messages(capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":3}""")));
      assertTrue(replay.contains("cancelled live subscription") && replay.contains("re-subscribing"),
          () -> "the casualty replay is unrecorded: " + replay);
    }
    try (final var ws = createWebsocket()) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertTrue(ws.accountSubscribe(KEY, _ -> {
      }));
      ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
      feed(ws, socket, UNKNOWN_ACCOUNT_NOTIFICATION); // auto-cancellation id 3, fingerprint null
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":555,"id":2}"""); // a live owner now holds 555

      final var obsolete = messages(capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32603,"message":"Subscription refused"},"id":3}""")));
      assertTrue(obsolete.contains("is obsolete: a successor owns the id"),
          () -> "the obsolete cancellation is unrecorded: " + obsolete);
    }
  }

  /// A numeric answer to an un-subscription is a server defect the engine settles rather than
  /// wedges. Nothing is installed from it, so the WARNING is its only trace.
  @Test
  void aNumericAnswerToACancellationIsRecorded() {
    try (final var ws = createWebsocket()) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, UNKNOWN_ACCOUNT_NOTIFICATION); // mints cancellation id 2

      final var logged = messages(capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","result":42,"id":2}""")));
      assertTrue(logged.contains("numeric result"),
          () -> "the defective numeric acknowledgement is unrecorded: " + logged);
    }
  }

  /// A notification whose subscription id belongs to another channel is dropped, and dropping is
  /// silent by nature: the WARNING is the only difference between a hostile frame refused and a
  /// frame that never arrived.
  @Test
  void crossChannelNotificationDropsAreRecorded() {
    try (final var ws = createWebsocket()) {
      assertTrue(ws.accountSubscribe(KEY, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}"""); // account owns 700

      final var slot = messages(capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":1,"root":1,"slot":2},"subscription":700}}""")));
      assertTrue(slot.contains("belongs to"),
          () -> "the cross-channel slot drop is unrecorded: " + slot);

      final var signature = messages(capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","method":"signatureNotification","params":{"result":{"context":{"slot":3},"value":{"err":null}},"subscription":700}}""")));
      assertTrue(signature.contains("belongs to"),
          () -> "the cross-channel signature drop is unrecorded: " + signature);
    }
  }

  /// Both keyed publish overloads and the generic path each refuse a notification whose id
  /// belongs to someone else, and each refusal is silent apart from its record. Dropping is
  /// indistinguishable from a frame that never arrived unless the log says otherwise.
  @Test
  void everyDispatchPathRecordsItsCrossChannelDrop() {
    try (final var ws = createWebsocket()) {
      assertTrue(ws.accountSubscribe(KEY, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":23784,"id":2}"""); // the account channel owns 23784

      final var logs = messages(capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","method":"logsNotification","params":{"result":{"context":{"slot":1},"value":{"signature":"sig","err":null,"logs":["l"]}},"subscription":23784}}""")));
      assertTrue(logs.contains("belongs to") && logs.contains("account"),
          () -> "the logs-over-account drop is unrecorded: " + logs);
    }
    try (final var ws = createWebsocket()) {
      assertTrue(ws.logsSubscribe(KEY, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":24040,"id":2}"""); // the logs channel owns 24040

      final var account = messages(capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":1},"value":{"data":["","base64"],"executable":false,"lamports":1,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":0}},"subscription":24040}}""")));
      assertTrue(account.contains("belongs to") && account.contains("logs"),
          () -> "the account-over-logs drop is unrecorded: " + account);
    }
    try (final var ws = createWebsocket()) {
      assertTrue(ws.subscribe("fooSubscribe", "fooUnsubscribe", "fooNotification", "k", "\"p\"",
          systems.comodal.jsoniter.JsonIterator::readLong, null, _ -> {
          }));
      assertTrue(ws.subscribe("barSubscribe", "barUnsubscribe", "barNotification", "k", "\"p\"",
          systems.comodal.jsoniter.JsonIterator::readLong, null, _ -> {
          }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":40,"id":2}"""); // fooNotification owns 40

      final var generic = messages(capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","method":"barNotification","params":{"result":7,"subscription":40}}""")));
      assertTrue(generic.contains("does not match"),
          () -> "the generic cross-method drop is unrecorded: " + generic);
    }
  }

  /// An unconfirmed singleton correlates with nothing, so a notification arriving before its
  /// confirmation is dropped — and dropping is silent. The record is the only thing separating
  /// "refused an early frame" from "the frame never came", and the only evidence the engine
  /// did NOT answer it with a cancellation that would kill the grant still in flight.
  @Test
  void aPreConfirmationSingletonDropIsRecorded() {
    try (final var ws = createWebsocket()) {
      final var slots = new ArrayList<software.sava.rpc.json.http.response.ProcessedSlot>();
      assertTrue(ws.slotSubscribe(slots::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // deliberately unconfirmed

      final var logged = messages(capture(Level.ALL, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":1,"root":1,"slot":2},"subscription":9}}""")));

      assertTrue(slots.isEmpty(), "an unconfirmed singleton correlates with nothing");
      assertTrue(logged.contains("before the subscription was confirmed"),
          () -> "the pre-confirmation drop is unrecorded: " + logged);
      assertTrue(socket.sentText.stream().noneMatch(m -> m.contains("slotUnsubscribe")),
          () -> "the grant in flight must not be cancelled: " + socket.sentText);
    }
  }

  /// Escalation replaces the connection and hands the consumer an exception; the WARNING is
  /// the engine's own record that it did so, and the only one a log-only deployment keeps.
  @Test
  void anEscalationRecordsWhichRequestWentUnanswered() {
    final var clock = new TestClock();
    try (final var ws = createWebsocket(clock)) {
      assertTrue(ws.accountSubscribe(KEY, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      clock.advanceMillis(TIMINGS.subscriptionResendDelay()
          * SolanaJsonRpcWebsocket.UNANSWERED_ESCALATION_FACTOR + 1);

      final var logged = messages(capture(Level.ALL, () -> assertDoesNotThrow(() -> ws.checkCycle(0L))));

      assertTrue(logged.contains("has gone unanswered"),
          () -> "the escalation left no record: " + logged);
    }
  }
}
