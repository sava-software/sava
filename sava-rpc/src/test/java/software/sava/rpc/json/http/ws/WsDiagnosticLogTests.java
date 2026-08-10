package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;

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

  /// `onSendTextError` / `onPingError` left null on purpose: the null-handler
  /// branch is the one whose only output is the log line.
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

  /// The payload each frame carried is recorded only by these two lines, and only
  /// through a lazy supplier that DEBUG gates. Asserting the payload text kills
  /// both the removal of the log call and the supplier's forced-empty return.
  @Test
  void pingAndPongLogTheirPayload() {
    try (final var ws = createWebsocket()) {
      final var socket = new RecordingWebSocket();
      final var pingPayload = "ping-payload-4711";
      final var pongPayload = "pong-payload-8123";

      // the socket must be current: control frames from a never-installed socket are rejected
      ws.onOpen(socket);
      final var records = capture(Level.ALL, () -> {
        ws.onPing(socket, ByteBuffer.wrap(pingPayload.getBytes(ISO_8859_1)));
        ws.onPong(socket, ByteBuffer.wrap(pongPayload.getBytes(ISO_8859_1)));
      });

      final var logged = messages(records);
      assertTrue(logged.contains(pingPayload), () -> "ping payload not logged: " + logged);
      assertTrue(logged.contains(pongPayload), () -> "pong payload not logged: " + logged);
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

  /// Same standing for the ping path: the failure only ever reaches a log line.
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

  private static final software.sava.core.accounts.PublicKey KEY =
      software.sava.core.accounts.PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");

  private static final String UNKNOWN_ACCOUNT_NOTIFICATION = """
      {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":3},"value":{"lamports":1,"data":["","base64"],"owner":"11111111111111111111111111111111","executable":false,"rentEpoch":0,"space":0}},"subscription":555}}""";

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, java.nio.CharBuffer.wrap(json), true);
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
