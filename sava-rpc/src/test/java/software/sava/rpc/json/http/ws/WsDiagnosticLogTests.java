package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
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
        ws.onClose(new RecordingWebSocket(), 1011, "upstream exploded");
      }
    });
    assertTrue(messages(withReason).contains("upstream exploded"),
        () -> "close reason not logged: " + messages(withReason));

    final var blankReason = capture(Level.ALL, () -> {
      try (final var ws = createWebsocket()) {
        ws.onClose(new RecordingWebSocket(), 1011, "  ");
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
        assertDoesNotThrow(() -> ws.onClose(new RecordingWebSocket(), 1011, null));
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

      // the ping is sent by the pending-subscription pass once the window elapses
      final var records = capture(Level.ALL, () -> {
        clock.advanceMillis(TIMINGS.pingDelay() + 1);
        ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
      });
      assertEquals(1, socket.pings, "no ping was attempted");

      final var logged = messages(records);
      assertTrue(logged.contains("Failed to ping"), () -> "ping failure not logged: " + logged);
      assertTrue(logged.contains(ENDPOINT.getHost()), () -> "ping failure lost its endpoint: " + logged);
    }
  }
}
