package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.client.TestLogs;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.response.JsonRpcException;
import systems.comodal.jsoniter.JsonException;

import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.*;

/// Inbound JSON-RPC batch responses, which the engine reads element by element once it is built
/// with a positive subscribe batch cap. Each element is one response envelope and must be handled
/// exactly as that envelope arriving as its own message: correlated by its own id, its failure
/// contained to itself, its effect independent of where in the array it sits. The array's own
/// structure failing is contained once, a notification inside an array is refused, and an element
/// that retires the connection, or a takeover while the array is read, leaves the rest
/// undispatched. Built with a cap of 0 the engine reads an array as one unparseable message, the
/// contract `SubscribePipelinePinTests` pins.
///
/// Where a test compares an array with the same answers fed one message at a time, that run is
/// the oracle: the frames sent, the exceptions dispatched and every diagnostic must be identical.
@ExtendWith(QuietWsLogging.class)
final class BatchResponseDispatchTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final String HOST = ENDPOINT.getHost();
  /// Large windows keep resends and pings away unless a test steps the clock over them.
  private static final Timings TIMINGS = new Timings(60_000L, 60_000L, 60_000L);
  /// A positive cap, which is all the inbound path asks of it. It batches what these tests send
  /// as well, so the requests a pass admits share one array, the shape a batch response answers.
  private static final int BATCH_BYTES = 4_096;
  private static final PublicKey KEY_A = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
  private static final PublicKey KEY_B = PublicKey.fromBase58Encoded("GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc");
  private static final PublicKey KEY_C = PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final PublicKey KEY_D = PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final PublicKey KEY_E = PublicKey.fromBase58Encoded("83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri");
  private static final PublicKey KEY_F = PublicKey.fromBase58Encoded("11111111111111111111111111111111");
  /// Chars around a message in its backing array; never part of any message.
  private static final int PAD_BEFORE = 7;
  private static final int PAD_AFTER = 5;
  private static final String UNEXPECTED = "Unexpected json rpc error.";
  private static final String UNREADABLE_BATCH = "Unreadable batch response.";
  private static final String BATCHED_NOTIFICATION = "Dropping a notification inside a batch response from {0}.";
  private static final String REQUEST_REJECTED = "Subscription request {0} rejected by {1}: released {2} {3}.";

  /// How a listener may be handed a message: both sides of `instanceof CharBuffer` and of
  /// `hasArray()`, at an array position and offset of zero and of more than zero. `onText` parses
  /// an array-backed whole frame in place and copies every other frame into its reassembly buffer.
  private enum Backing {
    HEAP {
      @Override
      CharSequence of(final String text) {
        return CharBuffer.wrap(text.toCharArray());
      }
    },
    HEAP_AT_POSITION {
      @Override
      CharSequence of(final String text) {
        return CharBuffer.wrap(padded(text), PAD_BEFORE, text.length());
      }
    },
    HEAP_SLICE {
      @Override
      CharSequence of(final String text) {
        return CharBuffer.wrap(padded(text), PAD_BEFORE, text.length()).slice();
      }
    },
    READ_ONLY {
      @Override
      CharSequence of(final String text) {
        return CharBuffer.wrap(text.toCharArray()).asReadOnlyBuffer();
      }
    },
    BYTE_VIEW {
      @Override
      CharSequence of(final String text) {
        return ByteBuffer.allocate(2 * text.length()).asCharBuffer().put(text).flip();
      }
    },
    WRAPPED_STRING {
      @Override
      CharSequence of(final String text) {
        return CharBuffer.wrap(text);
      }
    },
    STRING {
      @Override
      CharSequence of(final String text) {
        return text;
      }
    },
    STRING_BUILDER {
      @Override
      CharSequence of(final String text) {
        return new StringBuilder(text);
      }
    };

    abstract CharSequence of(String text);

    private static char[] padded(final String text) {
      final char[] chars = new char[PAD_BEFORE + text.length() + PAD_AFTER];
      Arrays.fill(chars, '#');
      text.getChars(0, text.length(), chars, PAD_BEFORE);
      return chars;
    }
  }

  /// A message whose first `length()` call runs `takeover`. `onText` asks for the length only
  /// after resolving the connection its callback belongs to, so the takeover lands between that
  /// resolution and the parse: the window the takeover tests of the registry and inbound suites
  /// reach by parking a second thread, reached here on the test thread with nothing to wait for.
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

  /// What a run leaves observable, compared between one array and the same answers fed one
  /// message at a time: the frames sent, the exceptions dispatched, every diagnostic with its
  /// parameters, each request handle's grant, and the registry and ordinal seams.
  private record Observed(List<String> sent,
                          List<String> dispatched,
                          List<String> diagnostics,
                          List<String> grants,
                          int registrations,
                          int ordinalEntries,
                          boolean aborted) {

    static Observed of(final SolanaJsonRpcWebsocket ws,
                       final RecordingWebSocket socket,
                       final List<RuntimeException> dispatched,
                       final List<LogRecord> records,
                       final Map<Long, Subscription<?>> handles) {
      return new Observed(
          List.copyOf(socket.sentText),
          dispatched.stream().map(BatchResponseDispatchTests::describe).toList(),
          records.stream()
              .map(record -> record.getLevel() + " " + record.getMessage() + " "
                  + Arrays.toString(record.getParameters()))
              .toList(),
          handles.values().stream().map(sub -> sub.msgId() + "=" + sub.subId()).toList(),
          ws.retainedRegistrations(),
          ws.retainedOrdinalEntries(),
          socket.aborted
      );
    }
  }

  private static SolanaJsonRpcWebsocket websocket(final TestClock clock) {
    return websocket(clock, BATCH_BYTES, null);
  }

  /// `onError` is the retirement seam a test observes; null logs and closes the instance instead.
  private static SolanaJsonRpcWebsocket websocket(final TestClock clock,
                                                  final int subscribeBatchBytes,
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
        subscribeBatchBytes
    );
  }

  /// Delivers `json` whole as an array-backed frame at a non-zero position, which `onText` parses
  /// in place: each element's anchor is then an absolute position in a buffer that also holds
  /// chars outside the message, so an anchor taken from the wrong place cannot pass for the right
  /// one.
  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, Backing.HEAP_AT_POSITION.of(json), true);
  }

  /// Feeds `answers` as one array, or as one message each in the same order.
  private static void deliver(final SolanaJsonRpcWebsocket ws,
                              final RecordingWebSocket socket,
                              final List<String> answers,
                              final boolean asOneArray) {
    if (asOneArray) {
      feed(ws, socket, answers.stream().collect(joining(",", "[", "]")));
    } else {
      for (final var answer : answers) {
        feed(ws, socket, answer);
      }
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

  /// An account notification for `subId` carrying `lamports`, so a delivery names both.
  private static String accountNotification(final long subId, final long lamports) {
    return "{\"jsonrpc\":\"2.0\",\"method\":\"accountNotification\",\"params\":{\"result\":{\"context\":{\"slot\":3},"
        + "\"value\":{\"lamports\":" + lamports + ",\"data\":[\"\",\"base64\"],"
        + "\"owner\":\"11111111111111111111111111111111\",\"executable\":false,\"rentEpoch\":0,\"space\":0}},"
        + "\"subscription\":" + subId + "}}";
  }

  private static long framesContaining(final RecordingWebSocket socket, final String token) {
    return socket.sentText.stream().filter(frame -> frame.contains(token)).count();
  }

  private static String describe(final RuntimeException ex) {
    return ex instanceof JsonRpcException rpc
        ? "JsonRpcException " + rpc.code() + ' ' + rpc.requestId() + ' ' + rpc.getMessage()
        : ex.getClass().getSimpleName() + ' ' + ex.getMessage();
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

  private static List<LogRecord> matching(final List<LogRecord> records, final Level level, final String template) {
    return records.stream()
        .filter(record -> level.equals(record.getLevel()) && template.equals(record.getMessage()))
        .toList();
  }

  private static void assertNoWarnings(final List<LogRecord> records) {
    assertEquals(List.of(), records.stream()
        .filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
        .map(record -> record.getLevel() + " " + record.getMessage())
        .toList());
  }

  /// Each confirmation in an array installs its own grant, as it would alone: every request's
  /// notification then reaches that request's consumer, and only it. Nothing is reported, and no
  /// notification finds its grant missing and cancels it.
  @Test
  void anArrayOfConfirmationsInstallsEveryGrant() {
    try (final var ws = websocket(new TestClock())) {
      final var dispatched = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(dispatched::add);
      final var receivedA = new ArrayList<AccountInfo<byte[]>>();
      final var receivedB = new ArrayList<AccountInfo<byte[]>>();
      final var receivedC = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(KEY_A, receivedA::add)); // request 2
      assertTrue(ws.accountSubscribe(KEY_B, receivedB::add)); // request 3
      assertTrue(ws.accountSubscribe(KEY_C, receivedC::add)); // request 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(1, socket.sentText.size(), () -> "the three requests share one array: " + socket.sentText);

      final var records = TestLogs.capture(SolanaJsonRpcWebsocket.class, () -> {
        feed(ws, socket, """
            [{"jsonrpc":"2.0","result":700,"id":2},{"jsonrpc":"2.0","result":701,"id":3},{"jsonrpc":"2.0","result":702,"id":4}]""");
        feed(ws, socket, accountNotification(702, 12));
        feed(ws, socket, accountNotification(700, 10));
        feed(ws, socket, accountNotification(701, 11));
      });

      assertAll(
          () -> assertEquals(List.of(KEY_A), receivedA.stream().map(AccountInfo::pubKey).toList()),
          () -> assertEquals(List.of(10L), receivedA.stream().map(AccountInfo::lamports).toList(),
              "grant 700 is request 2's"),
          () -> assertEquals(List.of(KEY_B), receivedB.stream().map(AccountInfo::pubKey).toList()),
          () -> assertEquals(List.of(11L), receivedB.stream().map(AccountInfo::lamports).toList(),
              "grant 701 is request 3's"),
          () -> assertEquals(List.of(KEY_C), receivedC.stream().map(AccountInfo::pubKey).toList()),
          () -> assertEquals(List.of(12L), receivedC.stream().map(AccountInfo::lamports).toList(),
              "grant 702 is request 4's"),
          () -> assertEquals(1, socket.sentText.size(),
              () -> "no notification found its grant missing and cancelled it: " + socket.sentText),
          () -> assertEquals(List.of(), dispatched),
          () -> assertNoWarnings(records)
      );
    }
  }

  /// An error element releases its own request and nothing else, wherever it sits in the array and
  /// whatever its member order: the confirmation beside it is installed, the rejected request's
  /// key is free for a corrected subscribe, and the rejection reaches the exception subscribers
  /// naming request 3. Each element is read from its own start, the only anchor that finds both
  /// an `"id"` written before `"error"` and the element's own members rather than another's.
  @Test
  void anErrorElementReleasesOnlyItsOwnRequest() {
    for (final var array : List.of(
        """
            [{"jsonrpc":"2.0","result":700,"id":2},{"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params"},"id":3}]""",
        """
            [{"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params"},"id":3},{"jsonrpc":"2.0","result":700,"id":2}]""",
        """
            [{"id":2,"jsonrpc":"2.0","result":700},{"id":3,"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params"}}]"""
    )) {
      try (final var ws = websocket(new TestClock())) {
        final var dispatched = new ArrayList<RuntimeException>();
        ws.exceptionSubscribe(dispatched::add);
        final var subA = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
        assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, subA::set, _ -> {
        })); // request 2
        assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_B, null, _ -> {
        })); // request 3
        final var socket = new RecordingWebSocket();
        ws.onOpen(socket);

        final var records = TestLogs.capture(SolanaJsonRpcWebsocket.class, () -> feed(ws, socket, array));

        assertEquals(BigInteger.valueOf(700), subA.get().subId(), array);
        assertEquals(1, dispatched.size(), () -> array + ": " + dispatched);
        final var rejection = assertInstanceOf(JsonRpcException.class, dispatched.getFirst(), array);
        assertEquals(-32_602L, rejection.code(), array);
        assertEquals(OptionalLong.of(3), rejection.requestId(), array);
        assertDiagnostic(records, Level.WARNING, REQUEST_REJECTED, 3L, HOST, Channel.account, KEY_B.toBase58());
        assertEquals(0, matching(records, Level.WARNING, UNEXPECTED).size(), array);
        assertTrue(ws.accountSubscribe(KEY_B, _ -> {
        }), () -> array + ": request 3's key is free for a corrected subscribe");
        assertFalse(ws.accountSubscribe(KEY_A, _ -> {
        }), () -> array + ": request 2's key stays held by its grant");
      }
    }
  }

  /// One array holding one answer of each kind, in the order their requests went out: a `true`
  /// and a `false` un-subscription acknowledgement, a numeric answer to an un-subscription, a
  /// confirmation, a request-defect rejection (-32602) and a server-condition refusal (-32603).
  /// Agave answers a batch of errors and results this way, in request order; the answers to
  /// cancellations are here because elements correlate by id, so the router must classify every
  /// kind of answer whatever request it names. Each element is classified as the same answer
  /// arriving alone, so the oracle is that run: frames, dispatches and diagnostics must be
  /// identical, and in both runs each answer has its own effect. Each acknowledgement frees its
  /// id's cancellation gate, so a notification for the id draws a fresh cancellation at once where
  /// a held gate only queues one; the confirmation installs; the rejected request is released,
  /// never retried; the refused one stays pending and is retried once the resend window is
  /// exceeded.
  @Test
  void aMixedArrayClassifiesEachElementLikeItsOwnMessage() throws InterruptedException {
    final var alone = mixedAnswers(false);
    final var batched = mixedAnswers(true);
    assertEquals(alone, batched, "an element is classified exactly as the same answer alone");
  }

  private static Observed mixedAnswers(final boolean asOneArray) throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var dispatched = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(dispatched::add);
      final Map<Long, Subscription<?>> handles = new TreeMap<>();
      // Requests 2-4 are confirmed, then cancelled: their cancellations go out as requests 8-10.
      assertTrue(ws.accountSubscribe(KEY_D, _ -> {
      })); // request 2
      assertTrue(ws.accountSubscribe(KEY_E, _ -> {
      })); // request 3
      assertTrue(ws.accountSubscribe(KEY_F, _ -> {
      })); // request 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, grant(2, 800));
      feed(ws, socket, grant(3, 801));
      feed(ws, socket, grant(4, 802));
      assertTrue(ws.accountUnsubscribe(KEY_D));
      assertTrue(ws.accountUnsubscribe(KEY_E));
      assertTrue(ws.accountUnsubscribe(KEY_F));
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, sub -> handles.put(sub.msgId(), sub), _ -> {
      })); // request 5
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_B, sub -> handles.put(sub.msgId(), sub), _ -> {
      })); // request 6
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_C, sub -> handles.put(sub.msgId(), sub), _ -> {
      })); // request 7
      // Requests 2-4 went out as one array; the cancellations flush first, minted as requests
      // 8-10, each alone, then requests 5-7 share the pass's array.
      ws.checkCycle(0L);
      assertEquals(List.of(
              "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"accountUnsubscribe\",\"params\":[800]}",
              "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"accountUnsubscribe\",\"params\":[801]}",
              "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"accountUnsubscribe\",\"params\":[802]}"
          ), socket.sentText.subList(1, 4),
          () -> "the cancellations are requests 8-10, sent ahead of requests 5-7: " + socket.sentText);
      assertEquals(5, socket.sentText.size(), socket.sentText::toString);

      final var answers = List.of(
          """
              {"jsonrpc":"2.0","result":true,"id":8}""",
          """
              {"jsonrpc":"2.0","result":false,"id":9}""",
          """
              {"jsonrpc":"2.0","result":900,"id":10}""",
          grant(5, 700),
          """
              {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params"},"id":6}""",
          refusal(7)
      );
      final var records = TestLogs.capture(SolanaJsonRpcWebsocket.class, () -> {
        deliver(ws, socket, answers, asOneArray);
        feed(ws, socket, accountNotification(800, 1));
        feed(ws, socket, accountNotification(801, 1));
        feed(ws, socket, accountNotification(802, 1));
        try {
          clock.advanceMillis(TIMINGS.subscriptionResendDelay());
          ws.checkCycle(0L);
          assertEquals(1, framesContaining(socket, "\"id\":7,"),
              "an age equal to the resend window has not exceeded it");
          clock.advanceMillis(1L);
          ws.checkCycle(0L);
        } catch (final InterruptedException ex) {
          throw new AssertionError(ex);
        }
      });

      final var shape = asOneArray ? "one array" : "one message each";
      assertAll(shape,
          () -> assertEquals(2, framesContaining(socket, "\"params\":[800]"),
              "the true acknowledgement freed id 800's cancellation gate"),
          () -> assertEquals(2, framesContaining(socket, "\"params\":[801]"),
              "the false acknowledgement freed id 801's cancellation gate"),
          () -> assertEquals(2, framesContaining(socket, "\"params\":[802]"),
              "the numeric answer freed id 802's cancellation gate"),
          () -> assertDiagnostic(records, Level.FINE, "Un-subscription {0} for {1} was already gone server side.",
              9L, BigInteger.valueOf(801)),
          () -> assertDiagnostic(records, Level.WARNING,
              "Un-subscription {0} for {1} was answered with a numeric result; settling.",
              10L, BigInteger.valueOf(802)),
          () -> assertEquals(BigInteger.valueOf(700), handles.get(5L).subId(), "the confirmation installed"),
          () -> assertDiagnostic(records, Level.WARNING, REQUEST_REJECTED, 6L, HOST, Channel.account, KEY_B.toBase58()),
          () -> assertEquals(1, framesContaining(socket, "\"id\":6,"), "the rejected request is never retried"),
          () -> assertNull(handles.get(7L).subId(), "the refused request stays pending"),
          () -> assertEquals(2, framesContaining(socket, "\"id\":7,"),
              "the refused request is retried once the resend window is exceeded"),
          () -> assertEquals(List.of(
              "JsonRpcException -32602 OptionalLong[6] Invalid params",
              "JsonRpcException -32603 OptionalLong[7] Subscription refused"
          ), dispatched.stream().map(BatchResponseDispatchTests::describe).toList()),
          () -> assertEquals(0, matching(records, Level.WARNING, UNEXPECTED).size()),
          () -> assertFalse(socket.aborted)
      );
      final var observed = Observed.of(ws, socket, dispatched, records, handles);
      assertTrue(ws.accountSubscribe(KEY_B, _ -> {
      }), shape + ": the rejected request's key is free for a corrected subscribe");
      return observed;
    }
  }

  /// Elements adjudicate an id-reusing server's same-id grants exactly as separate messages do,
  /// in either response order. A request cancelled before its confirmation is tombstoned, its
  /// successor (request 3) goes out, and the tombstone's late grant mints the compensating
  /// cancellation (request 4) of id 700. The server grants the successor the same id and
  /// acknowledges the cancellation `true`: whichever answer comes first, the cancellation killed
  /// the successor's grant, which must be replayed. Two answers in one array leave everything the
  /// two messages do, and the outcome is the reconnect suite's for that order.
  @Test
  void elementsAdjudicateLikeSeparateMessages() {
    final var grantFirst = List.of(grant(3, 700), """
        {"jsonrpc":"2.0","result":true,"id":4}""");
    final var ackFirst = List.of(grantFirst.getLast(), grantFirst.getFirst());

    final var grantFirstBatched = compensationRace(grantFirst, true);
    assertEquals(compensationRace(grantFirst, false), grantFirstBatched,
        "a grant then an acknowledgement in one array adjudicate as two messages");
    assertEquals(2, grantFirstBatched.sent().stream()
            .filter(frame -> frame.contains("accountSubscribe") && frame.contains("\"id\":3,"))
            .count(),
        () -> "the revived successor re-sends its subscribe: " + grantFirstBatched.sent());

    final var ackFirstBatched = compensationRace(ackFirst, true);
    assertEquals(compensationRace(ackFirst, false), ackFirstBatched,
        "an acknowledgement then a grant in one array adjudicate as two messages");
    final long successorSends = ackFirstBatched.sent().stream()
        .filter(frame -> frame.contains("accountSubscribe") && frame.contains("\"id\":3,"))
        .count();
    assertTrue(ackFirstBatched.aborted() || successorSends == 2,
        () -> "the ambiguous true acknowledgement must replay or replace the connection, never leave the "
            + "same-id successor silently mapped: " + ackFirstBatched.sent());
  }

  private static Observed compensationRace(final List<String> answers, final boolean asOneArray) {
    try (final var ws = websocket(new TestClock())) {
      final var dispatched = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(dispatched::add);
      final Map<Long, Subscription<?>> handles = new TreeMap<>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, sub -> handles.put(sub.msgId(), sub), _ -> {
      })); // request 2
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      // Cancelled before its confirmation, so tombstoned; the successor is request 3.
      assertTrue(ws.accountUnsubscribe(KEY_A));
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, sub -> handles.put(sub.msgId(), sub), _ -> {
      }));
      ws.onPong(socket, ByteBuffer.wrap(new byte[0])); // transmits request 3
      // The tombstoned request's late grant mints the compensating cancellation, request 4.
      feed(ws, socket, grant(2, 700));
      assertEquals("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"accountUnsubscribe\",\"params\":[700]}",
          socket.sentText.getLast());

      final var records = TestLogs.capture(SolanaJsonRpcWebsocket.class, () -> {
        deliver(ws, socket, answers, asOneArray);
        ws.onPong(socket, ByteBuffer.wrap(new byte[0])); // the pass that re-sends a revived successor
      });
      return Observed.of(ws, socket, dispatched, records, handles);
    }
  }

  /// A malformed element is that element's failure alone: `5` is no envelope, and
  /// `{"result":"x","id":3}` carries a subscription id no number holds. Each is reported once as
  /// the same malformed message alone is, an "Unexpected json rpc error." whose exception is
  /// dispatched, request 3 stays as unanswered as that message leaves it, and the elements on
  /// either side still install.
  @Test
  void aMalformedElementIsContainedAndTheRestDispatch() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      final var dispatched = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(dispatched::add);
      final var subA = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      final var subB = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      final var subC = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, subA::set, _ -> {
      })); // request 2
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_B, subB::set, _ -> {
      })); // request 3
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_C, subC::set, _ -> {
      })); // request 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      final var records = TestLogs.capture(SolanaJsonRpcWebsocket.class, () -> feed(ws, socket, """
          [{"jsonrpc":"2.0","result":700,"id":2},5,{"jsonrpc":"2.0","result":"x","id":3},{"jsonrpc":"2.0","result":702,"id":4}]"""));

      final var reports = matching(records, Level.WARNING, UNEXPECTED);
      assertAll(
          () -> assertEquals(BigInteger.valueOf(700), subA.get().subId(), "the element before them installs"),
          () -> assertEquals(BigInteger.valueOf(702), subC.get().subId(), "the element after them installs"),
          () -> assertNull(subB.get().subId(), "the unreadable answer installs nothing"),
          () -> assertEquals(2, reports.size(), () -> "one report per malformed element: " + records),
          () -> assertEquals(0, matching(records, Level.WARNING, UNREADABLE_BATCH).size(),
              "the array itself stayed readable"),
          () -> assertEquals(2, dispatched.size(), dispatched::toString),
          () -> assertInstanceOf(JsonException.class, dispatched.getFirst(), "5 is not an envelope"),
          () -> assertInstanceOf(NumberFormatException.class, dispatched.getLast(), "\"x\" is not a subscription id"),
          () -> assertSame(dispatched.getFirst(), reports.getFirst().getThrown(), "each report is its dispatch"),
          () -> assertSame(dispatched.getLast(), reports.getLast().getThrown(), "each report is its dispatch")
      );

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L);
      ws.checkCycle(0L);
      assertEquals(1, framesContaining(socket, "\"id\":3,"),
          () -> "request 3 is still gated as awaiting an answer, so it is not re-sent: " + socket.sentText);
    }
  }

  /// An array whose structure breaks where a separator or its closing bracket belongs is contained
  /// as one failure: nothing escapes `onText`, the array is reported once as "Unreadable batch
  /// response." with the parser's exception, which reaches the exception subscribers, and the
  /// element read before the break keeps its effect. The requests after the break stay
  /// unanswered, and the next message still dispatches. The breaks: an array missing its closing
  /// bracket, and one missing the separator after its first element. A break where an element
  /// belongs, a stray separator for one, is not contained as one failure: it is read first as a
  /// malformed element, reported as such, and only then reported as an unreadable array.
  @Test
  void aBrokenArrayIsContainedWhole() {
    for (final var broken : List.of(
        """
            [{"jsonrpc":"2.0","result":700,"id":2}""",
        """
            [{"jsonrpc":"2.0","result":700,"id":2} {"jsonrpc":"2.0","result":701,"id":3}]"""
    )) {
      try (final var ws = websocket(new TestClock())) {
        final var dispatched = new ArrayList<RuntimeException>();
        ws.exceptionSubscribe(dispatched::add);
        final var subA = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
        final var subB = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
        assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, subA::set, _ -> {
        })); // request 2
        assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_B, subB::set, _ -> {
        })); // request 3
        final var socket = new RecordingWebSocket();
        ws.onOpen(socket);

        final var records = TestLogs.capture(SolanaJsonRpcWebsocket.class,
            () -> assertDoesNotThrow(() -> feed(ws, socket, broken)));

        final var diagnostic = assertDiagnostic(records, Level.WARNING, UNREADABLE_BATCH);
        assertEquals(1, dispatched.size(), () -> broken + ": " + dispatched);
        final var failure = assertInstanceOf(JsonException.class, dispatched.getFirst(), broken);
        assertSame(failure, diagnostic.getThrown(), "the diagnostic and the dispatch report one failure");
        assertEquals(0, matching(records, Level.WARNING, UNEXPECTED).size(),
            () -> broken + ": no element was malformed");
        assertEquals(BigInteger.valueOf(700), subA.get().subId(),
            () -> broken + ": the element before the break installed");
        assertNull(subB.get().subId(), () -> broken + ": nothing after the break is read");

        feed(ws, socket, grant(3, 701));
        assertEquals(BigInteger.valueOf(701), subB.get().subId(), () -> broken + ": the next message dispatches");
        assertEquals(1, dispatched.size(), () -> broken + ": " + dispatched);
      }
    }
  }

  /// An array of one answers exactly as its object alone: a confirmation installs and a rejection
  /// releases, with the same frames, dispatches, diagnostics and registry seams.
  @Test
  void aOneElementArrayAnswersLikeItsObject() {
    final var confirmation = grant(2, 700);
    final var confirmed = oneAnswer("[" + confirmation + "]");
    assertEquals(oneAnswer(confirmation), confirmed);
    assertEquals(List.of("2=700", "3=null"), confirmed.grants());

    final var rejection = """
        {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params"},"id":3}""";
    final var rejected = oneAnswer("[" + rejection + "]");
    assertEquals(oneAnswer(rejection), rejected);
    assertEquals(List.of("JsonRpcException -32602 OptionalLong[3] Invalid params"), rejected.dispatched());
    assertTrue(rejected.diagnostics().contains("WARNING " + REQUEST_REJECTED + " "
            + Arrays.toString(new Object[]{3L, HOST, Channel.account, KEY_B.toBase58()})),
        () -> "the rejection released request 3: " + rejected.diagnostics());
  }

  private static Observed oneAnswer(final String message) {
    try (final var ws = websocket(new TestClock())) {
      final var dispatched = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(dispatched::add);
      final Map<Long, Subscription<?>> handles = new TreeMap<>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, sub -> handles.put(sub.msgId(), sub), _ -> {
      })); // request 2
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_B, sub -> handles.put(sub.msgId(), sub), _ -> {
      })); // request 3
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      final var records = TestLogs.capture(SolanaJsonRpcWebsocket.class, () -> feed(ws, socket, message));
      return Observed.of(ws, socket, dispatched, records, handles);
    }
  }

  /// An empty array answers nothing; Agave never sends one, since it answers an empty batch
  /// request with nothing at all. Read as an array of no elements it changes nothing: no record,
  /// no dispatch, no frame, no registry or ordinal change, and the requests it did not answer are
  /// still answered by the messages that follow.
  @Test
  void anEmptyInboundArrayChangesNothing() {
    for (final var empty : List.of("[]", "[ ]", " [\n\t] ")) {
      try (final var ws = websocket(new TestClock())) {
        final var dispatched = new ArrayList<RuntimeException>();
        ws.exceptionSubscribe(dispatched::add);
        final var subA = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
        final var subB = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
        assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, subA::set, _ -> {
        })); // request 2
        assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_B, subB::set, _ -> {
        })); // request 3
        final var socket = new RecordingWebSocket();
        ws.onOpen(socket);
        final int sent = socket.sentText.size();
        final int registrations = ws.retainedRegistrations();
        final int ordinals = ws.retainedOrdinalEntries();
        final int ready = ws.retainedReadySubscriptions();

        final var records = TestLogs.capture(SolanaJsonRpcWebsocket.class, () -> feed(ws, socket, empty));

        final var shape = "'" + empty + "'";
        assertAll(shape,
            () -> assertEquals(List.of(), records.stream().map(LogRecord::getMessage).toList()),
            () -> assertEquals(List.of(), dispatched),
            () -> assertEquals(sent, socket.sentText.size()),
            () -> assertEquals(registrations, ws.retainedRegistrations()),
            () -> assertEquals(ordinals, ws.retainedOrdinalEntries()),
            () -> assertEquals(ready, ws.retainedReadySubscriptions()),
            () -> assertTrue(ws.readySetMatchesPendingWork()),
            () -> assertNull(subA.get().subId()),
            () -> assertNull(subB.get().subId())
        );
        feed(ws, socket, grant(2, 700));
        feed(ws, socket, grant(3, 701));
        assertEquals(BigInteger.valueOf(700), subA.get().subId(), shape);
        assertEquals(BigInteger.valueOf(701), subB.get().subId(), shape);
      }
    }
  }

  /// A batch response holds responses only, and Agave sends notifications as messages of their
  /// own, so one inside an array is dropped with a WARNING naming the host: its consumer receives
  /// nothing, a notification for an unknown subscription draws no cancellation, and the response
  /// beside it is still read. The same notifications alone are delivered and cancelled, so the
  /// drop belongs to the array, not to the notification.
  @Test
  void aNotificationInsideABatchResponseIsDropped() {
    try (final var ws = websocket(new TestClock())) {
      final var dispatched = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(dispatched::add);
      final var received = new ArrayList<AccountInfo<byte[]>>();
      final var subB = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      assertTrue(ws.accountSubscribe(KEY_A, received::add)); // request 2
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_B, subB::set, _ -> {
      })); // request 3
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, grant(2, 700));
      final int sent = socket.sentText.size();

      final var records = TestLogs.capture(SolanaJsonRpcWebsocket.class,
          () -> feed(ws, socket, "[" + accountNotification(700, 10) + "," + grant(3, 701) + "]"));

      assertDiagnostic(records, Level.WARNING, BATCHED_NOTIFICATION, HOST);
      assertEquals(List.of(), received, "the notification inside the array reaches no consumer");
      assertEquals(BigInteger.valueOf(701), subB.get().subId(), "the response beside it is still read");

      final var unknown = TestLogs.capture(SolanaJsonRpcWebsocket.class,
          () -> feed(ws, socket, "[" + accountNotification(999, 10) + "]"));
      assertDiagnostic(unknown, Level.WARNING, BATCHED_NOTIFICATION, HOST);
      assertEquals(sent, socket.sentText.size(), () -> "nothing inside the array is acted on: " + socket.sentText);

      feed(ws, socket, accountNotification(700, 10));
      assertEquals(List.of(10L), received.stream().map(AccountInfo::lamports).toList(),
          "the same notification alone is delivered");
      feed(ws, socket, accountNotification(999, 10));
      assertEquals(sent + 1, socket.sentText.size(), socket.sentText::toString);
      assertTrue(socket.sentText.getLast().contains("\"params\":[999]"),
          () -> "the same unknown notification alone is cancelled: " + socket.sentText.getLast());
      assertEquals(List.of(), dispatched);
    }
  }

  /// An element that retires the connection ends the array. A confirmation granting request 3 the
  /// id request 2 already holds, for a different account, is connection-fatal, since the id no
  /// longer names one stream; the -32603 refusal after it in the same array is then as stale as a
  /// frame on a displaced socket and is not dispatched. Read on the retired connection it would
  /// correlate nothing and still reach the exception subscribers. A recording `onError` keeps the
  /// instance, and its exception subscribers, open.
  @Test
  void anElementRetiringTheConnectionStopsTheRest() {
    final var errors = new ArrayList<Throwable>();
    try (final var ws = websocket(new TestClock(), BATCH_BYTES, (_, error) -> errors.add(error))) {
      final var dispatched = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(dispatched::add);
      assertTrue(ws.accountSubscribe(KEY_A, _ -> {
      })); // request 2
      assertTrue(ws.accountSubscribe(KEY_B, _ -> {
      })); // request 3
      assertTrue(ws.accountSubscribe(KEY_C, _ -> {
      })); // request 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, grant(2, 700));

      feed(ws, socket, "[" + grant(3, 700) + "," + refusal(4) + "]");

      assertAll(
          () -> assertEquals(1, errors.size(), errors::toString),
          () -> assertTrue(assertInstanceOf(IllegalStateException.class, errors.getFirst()).getMessage()
              .contains("was assigned to non-equivalent requests"), () -> errors.getFirst().getMessage()),
          () -> assertTrue(socket.aborted, "the collision replaced the connection"),
          () -> assertEquals(List.of(), dispatched, "the refusal after the retiring element is not dispatched")
      );
    }
  }

  /// A takeover can land while a displaced connection's callback reads an array it already resolved
  /// to that connection. None of the array's elements may then act: not the confirmation, though
  /// the successor's re-armed request carries the same id; not the -32602 rejection, which would
  /// release the successor's durable registration; and not the refusal, which on the dead
  /// connection would correlate nothing and reach the exception subscribers. The callback also
  /// returns with the lock released.
  @Test
  void aStaleArrayInsideTheParserCannotTouchTheSuccessor() {
    try (final var ws = websocket(new TestClock())) {
      final var dispatched = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(dispatched::add);
      final var subA = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, subA::set, _ -> {
      })); // request 2
      assertTrue(ws.accountSubscribe(KEY_B, _ -> {
      })); // request 3
      final var displaced = new RecordingWebSocket();
      ws.onOpen(displaced);
      final var successor = new RecordingWebSocket();
      final var stale = new TakeoverOnRead("[" + grant(2, 700) + ","
          + "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"Invalid params\"},\"id\":3},"
          + refusal(2) + "]", () -> ws.onOpen(successor));

      ws.onText(displaced, stale, true);

      assertAll(
          () -> assertTrue(stale.tookOver(), "the takeover landed after the callback resolved its connection"),
          () -> assertEquals(0, ws.lock.getHoldCount(), "the stale callback released the lock"),
          () -> assertNull(subA.get().subId(), "only the successor may confirm its re-armed request"),
          () -> assertFalse(ws.accountSubscribe(KEY_B, _ -> {
          }), "the successor still owns request 3's durable key"),
          () -> assertEquals(List.of(), dispatched, "nothing in the stale array is dispatched")
      );
    }
  }

  /// An array arrives in whatever backing and fragmentation a listener is handed, and each element
  /// is dispatched exactly once whichever route `onText` takes: parsed in place in the caller's
  /// array at any position, or copied into the reassembly buffer whole or fragment by fragment,
  /// with cuts inside elements, on a separator, and before the closing bracket. The refusal is the
  /// witness of "once": every dispatch of it reaches the exception subscribers.
  @Test
  void aFragmentedArrayDispatchesEachElementOnce() {
    final var array = "[" + grant(2, 700) + "," + refusal(3) + "," + grant(4, 702) + "]";
    final int[] cuts = {
        array.indexOf("\"id\":2"),
        array.indexOf("},{") + 2,
        array.indexOf("-32603"),
        array.length() - 1
    };
    for (final var backing : Backing.values()) {
      assertEachElementOnce(backing + ", whole", (ws, socket) -> ws.onText(socket, backing.of(array), true));
    }
    final var backings = Backing.values();
    for (int rotation = 0; rotation < backings.length; ++rotation) {
      final int first = rotation;
      assertEachElementOnce("fragments from rotation " + rotation, (ws, socket) -> {
        int from = 0;
        for (int i = 0; i <= cuts.length; ++i) {
          final int to = i < cuts.length ? cuts[i] : array.length();
          final var fragment = backings[(i + first) % backings.length].of(array.substring(from, to));
          ws.onText(socket, fragment, to == array.length());
          from = to;
        }
      });
    }
  }

  private static void assertEachElementOnce(final String shape,
                                            final BiConsumer<SolanaJsonRpcWebsocket, RecordingWebSocket> delivery) {
    try (final var ws = websocket(new TestClock())) {
      final var dispatched = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(dispatched::add);
      final var subA = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      final var subB = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      final var subC = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, subA::set, _ -> {
      })); // request 2
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_B, subB::set, _ -> {
      })); // request 3
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_C, subC::set, _ -> {
      })); // request 4
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      final var records = TestLogs.capture(SolanaJsonRpcWebsocket.class, () -> delivery.accept(ws, socket));

      assertAll(shape,
          () -> assertEquals(BigInteger.valueOf(700), subA.get().subId()),
          () -> assertNull(subB.get().subId(), "a refusal installs nothing"),
          () -> assertEquals(BigInteger.valueOf(702), subC.get().subId()),
          () -> assertEquals(List.of("JsonRpcException -32603 OptionalLong[3] Subscription refused"),
              dispatched.stream().map(BatchResponseDispatchTests::describe).toList()),
          () -> assertNoWarnings(records),
          () -> assertFalse(socket.aborted)
      );
    }
  }

  /// A blank message is not an array, so with batching on it takes the whole-message path and is
  /// reported exactly as with batching off: one "Unexpected json rpc error." carrying the parser's
  /// end-of-input exception, which is also dispatched, and no batch diagnostic. The oracle is the
  /// batching-off engine fed the same message.
  @Test
  void withBatchingOnABlankMessageIsReportedAsBefore() {
    for (final var blank : List.of("", " ", "\n\t ")) {
      assertEquals(blankReport(blank, 0), blankReport(blank, BATCH_BYTES), () -> "'" + blank + "'");
    }
  }

  private static String blankReport(final String blank, final int subscribeBatchBytes) {
    try (final var ws = websocket(new TestClock(), subscribeBatchBytes, null)) {
      final var dispatched = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(dispatched::add);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      final var records = TestLogs.capture(SolanaJsonRpcWebsocket.class, () -> feed(ws, socket, blank));

      final var diagnostic = assertDiagnostic(records, Level.WARNING, UNEXPECTED);
      assertEquals(0, matching(records, Level.WARNING, UNREADABLE_BATCH).size());
      assertEquals(1, dispatched.size(), dispatched::toString);
      final var failure = assertInstanceOf(JsonException.class, dispatched.getFirst());
      assertSame(failure, diagnostic.getThrown(), "the diagnostic and the dispatch report one failure");
      return failure.op() + ": " + failure.getMessage();
    }
  }

  /// The gate's boundary: built through the same constructor with a cap of 0, or a negative one,
  /// the engine reads an inbound array as it always has, one unparseable message ("Unexpected json
  /// rpc error.", one dispatched `JsonException`, nothing correlated), while the smallest positive
  /// cap reads the same array element by element and installs every grant. The negative cap is
  /// the router's half of the rule that only a positive cap batches;
  /// `SubscribeBatchingTests.aNegativeCapIsBatchingOffLikeZero` holds the outbound half.
  /// `SubscribePipelinePinTests` pins the 0 side through the constructor that takes no cap.
  @Test
  void withBatchingOffAnInboundArrayIsStillOneContainedParseFailure() {
    final var array = "[" + grant(2, 700) + "," + grant(3, 701) + "]";
    for (final int subscribeBatchBytes : new int[]{-1, 0, 1}) {
      try (final var ws = websocket(new TestClock(), subscribeBatchBytes, null)) {
        final var dispatched = new ArrayList<RuntimeException>();
        ws.exceptionSubscribe(dispatched::add);
        final var subA = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
        final var subB = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
        assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_A, subA::set, _ -> {
        })); // request 2
        assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY_B, subB::set, _ -> {
        })); // request 3
        final var socket = new RecordingWebSocket();
        ws.onOpen(socket);

        final var records = TestLogs.capture(SolanaJsonRpcWebsocket.class, () -> feed(ws, socket, array));

        final var cap = "cap " + subscribeBatchBytes;
        if (subscribeBatchBytes <= 0) {
          final var diagnostic = assertDiagnostic(records, Level.WARNING, UNEXPECTED);
          assertEquals(1, dispatched.size(), dispatched::toString);
          assertSame(assertInstanceOf(JsonException.class, dispatched.getFirst()), diagnostic.getThrown());
          assertNull(subA.get().subId(), cap);
          assertNull(subB.get().subId(), cap);
          assertEquals(2, ws.retainedOrdinalEntries(), "both transmitted attempts are still open");
        } else {
          assertNoWarnings(records);
          assertEquals(List.of(), dispatched);
          assertEquals(BigInteger.valueOf(700), subA.get().subId(), cap);
          assertEquals(BigInteger.valueOf(701), subB.get().subId(), cap);
        }
      }
    }
  }
}
