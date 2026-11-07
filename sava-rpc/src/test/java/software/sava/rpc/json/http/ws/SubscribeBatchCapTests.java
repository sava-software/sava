package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.*;

/// The subscribe batch cap: which due requests may share one batch message. The cap counts the
/// UTF-8 bytes of the whole message, its brackets and commas included, and a request joins a
/// message only if the whole message stays within it, the limit itself included; Agave counts its
/// inbound limit the same way and resets the connection, unanswered, on a message above it. A
/// request that fits beside nothing travels alone and unchanged: one bigger than the cap, one
/// whose text the JDK refuses to encode, and every generic request, whose frame carries the
/// caller's raw params.
///
/// The oracle for a composed message is the batching-off run of the same registrations. A fresh
/// engine mints the same request ids in the same order, so that run's frames are exactly the
/// elements a batch must carry, and a message's size is its own `getBytes(UTF_8).length`.
@ExtendWith(QuietWsLogging.class)
final class SubscribeBatchCapTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  /// Large windows keep resends and pings away unless a test steps the clock over them.
  private static final Timings TIMINGS = new Timings(60_000L, 60_000L, 60_000L);
  /// Agave's limit on one inbound websocket message, inclusive, in UTF-8 bytes: the largest cap
  /// that is safe against it.
  private static final int AGAVE_MAX_INBOUND_MESSAGE_BYTES = 4_096;
  private static final PublicKey KEY_A = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
  private static final PublicKey KEY_B = PublicKey.fromBase58Encoded("GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc");
  private static final PublicKey KEY_C = PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final PublicKey KEY_D = PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final PublicKey PROGRAM = PublicKey.fromBase58Encoded("11111111111111111111111111111111");
  /// Generic params that close their own request's params and object, then open a second
  /// request: a cancellation of subscription 100 under request id 99999. Alone, the frame is one
  /// message with trailing characters, which a JSON-RPC server refuses whole (Agave answers -32700
  /// with a null id) and runs nothing of; inside an array, the same text is one more valid call.
  private static final String SPLICING_PARAMS = """
      "x"]},{"jsonrpc":"2.0","id":99999,"method":"accountUnsubscribe","params":[100""";

  private static SolanaJsonRpcWebsocket websocket(final TestClock clock,
                                                  final int subscribeBatchBytes,
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
        null,
        subscribeBatchBytes
    );
  }

  /// The frames the adoption pass sends for `registrations` made before the connection opens.
  private static List<String> sentByAdoption(final int subscribeBatchBytes,
                                             final Consumer<SolanaRpcWebsocket> registrations) {
    try (final var ws = websocket(new TestClock(), subscribeBatchBytes, null)) {
      registrations.accept(ws);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertTrue(ws.readySetMatchesPendingWork(), "every admitted request is in flight");
      return List.copyOf(socket.sentText);
    }
  }

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  private static String grant(final long requestId, final long subId) {
    return "{\"jsonrpc\":\"2.0\",\"result\":" + subId + ",\"id\":" + requestId + '}';
  }

  private static int utf8Bytes(final String text) {
    return text.getBytes(UTF_8).length;
  }

  /// The JSON-RPC batch array of `frames`, in order.
  private static String array(final List<String> frames) {
    return frames.stream().collect(joining(",", "[", "]"));
  }

  /// The message a link of `frames` is sent as: a lone request as its own frame, several as their
  /// array.
  private static String message(final List<String> frames) {
    return frames.size() == 1 ? frames.getFirst() : array(frames);
  }

  private static void assertWithinCap(final List<String> frames, final int cap) {
    for (final var frame : frames) {
      assertTrue(utf8Bytes(frame) <= cap, () -> utf8Bytes(frame) + " bytes is over a cap of " + cap + ": " + frame);
    }
  }

  private static Consumer<SolanaRpcWebsocket> accounts(final List<PublicKey> keys) {
    return ws -> {
      for (final var key : keys) {
        assertTrue(ws.accountSubscribe(key, _ -> {
        }));
      }
    };
  }

  /// Memcmp filters, each the largest sava builds, until they alone exceed Agave's limit.
  private static List<Filter> filtersOverTheLimit() {
    final var filters = new ArrayList<Filter>();
    int filterBytes = 0;
    for (int i = 1; filterBytes <= AGAVE_MAX_INBOUND_MESSAGE_BYTES; ++i) {
      final byte[] bytes = new byte[Filter.MAX_MEM_COMP_LENGTH];
      Arrays.fill(bytes, (byte) i);
      final var filter = Filter.createMemCompFilter(i * Filter.MAX_MEM_COMP_LENGTH, bytes);
      filters.add(filter);
      filterBytes += utf8Bytes(filter.toJson());
    }
    return filters;
  }

  /// A cap of exactly the array's bytes sends the array: the cap is met, not exceeded. One byte
  /// less and the last request no longer fits, so all but the last share a message and the last
  /// goes alone, as its plain object.
  @Test
  void aBatchFillsExactlyToTheCapAndSplitsOneByteOver() {
    final var registrations = accounts(List.of(KEY_A, KEY_B, KEY_C));
    final var alone = sentByAdoption(0, registrations);
    assertEquals(3, alone.size(), alone::toString);
    final var whole = array(alone);
    final int exact = utf8Bytes(whole);

    assertEquals(List.of(whole), sentByAdoption(exact, registrations),
        "a cap of exactly the array's bytes holds the array");

    final var oneByteShort = sentByAdoption(exact - 1, registrations);
    assertEquals(List.of(array(alone.subList(0, 2)), alone.get(2)), oneByteShort,
        "one byte short of the array, the last request goes alone");
    assertWithinCap(oneByteShort, exact - 1);
  }

  /// The framing is part of the message. An array of n requests carries `[`, `]` and n - 1 commas
  /// besides its frames, n + 1 bytes in all, and the cap counts them: at the frames' bytes plus
  /// n + 1 the n requests share one message, and one byte less the last splits off. At the frames'
  /// bytes alone, which a composer counting only frames would fill, they split too. Each n from two
  /// to the fixture's key count, so the separator is paid once per element.
  @Test
  void theArrayFramingCountsTowardTheCap() {
    final var keys = List.of(KEY_A, KEY_B, KEY_C, KEY_D);
    for (int n = 2; n <= keys.size(); ++n) {
      final int count = n;
      final var registrations = accounts(keys.subList(0, n));
      final var alone = sentByAdoption(0, registrations);
      assertEquals(n, alone.size(), alone::toString);
      final int frameBytes = alone.stream().mapToInt(SubscribeBatchCapTests::utf8Bytes).sum();
      final var lastSplitOff = List.of(message(alone.subList(0, n - 1)), alone.getLast());

      assertEquals(List.of(array(alone)), sentByAdoption(frameBytes + n + 1, registrations),
          () -> count + " requests share a message of their frames' bytes plus " + (count + 1));
      final var oneShort = sentByAdoption(frameBytes + n, registrations);
      assertEquals(lastSplitOff, oneShort, () -> count + " requests, one framing byte short");
      assertWithinCap(oneShort, frameBytes + n);
      assertEquals(lastSplitOff, sentByAdoption(frameBytes, registrations),
          () -> count + " requests, at their frames' bytes alone");
    }
  }

  /// The cap counts UTF-8 bytes, not chars. A signature is the one caller text a typed frame
  /// carries, and the engine refuses only the characters that would splice the frame, so a
  /// signature request can hold text wider in bytes than in chars: here U+20AC, three bytes for
  /// one char, and U+1F600, four bytes for two. A server rejects such signatures by id; here they
  /// are only sizes. Two requests whose array fits the cap by `length()` but not in bytes are
  /// split, and at the array's byte length they share it.
  @Test
  void theCapCountsUtf8BytesNotChars() {
    final Consumer<SolanaRpcWebsocket> registrations = ws -> {
      assertTrue(ws.signatureSubscribe("€".repeat(12), _ -> {
      }));
      assertTrue(ws.signatureSubscribe("😀".repeat(6), _ -> {
      }));
    };
    final var alone = sentByAdoption(0, registrations);
    assertEquals(2, alone.size(), alone::toString);
    final var whole = array(alone);
    assertTrue(whole.length() < utf8Bytes(whole), "the fixture is wider in bytes than in chars");

    assertEquals(List.of(whole), sentByAdoption(utf8Bytes(whole), registrations),
        "at the array's byte length the two share it");
    final var byChars = sentByAdoption(whole.length(), registrations);
    assertEquals(alone, byChars, "an array that fits by length() but not in bytes is split");
    assertWithinCap(byChars, whole.length());
  }

  /// A request bigger than the cap has room beside nothing, so it goes out alone and unchanged
  /// wherever the pass meets it. Between two pairs, each pair shares its own message; first in
  /// the pass, the requests after it share theirs. Agave resets the connection on any message over
  /// its limit, so this request is connection-fatal there with batching on or off
  /// (`SubscribePipelinePinTests.anOversizeRequestIsSentUnchanged`); batching neither adds to that
  /// nor hides it.
  @Test
  void anOversizeRequestTravelsAloneAndNothingJoinsIt() {
    final var filters = filtersOverTheLimit();
    final Consumer<SolanaRpcWebsocket> between = ws -> {
      accounts(List.of(KEY_A, KEY_B)).accept(ws);
      assertTrue(ws.programSubscribe(Commitment.CONFIRMED, PROGRAM, filters, _ -> {
      }, _ -> {
      }));
      accounts(List.of(KEY_C, KEY_D)).accept(ws);
    };
    final var alone = sentByAdoption(0, between);
    assertEquals(5, alone.size(), alone::toString);
    final var oversize = alone.get(2);
    assertTrue(utf8Bytes(oversize) > AGAVE_MAX_INBOUND_MESSAGE_BYTES,
        () -> "the request must exceed the cap for this test to mean anything: " + utf8Bytes(oversize) + " bytes");

    final var batched = sentByAdoption(AGAVE_MAX_INBOUND_MESSAGE_BYTES, between);
    assertEquals(List.of(array(alone.subList(0, 2)), oversize, array(alone.subList(3, 5))), batched,
        "the pairs on either side share their own messages; the oversize request joins neither");
    assertWithinCap(List.of(batched.getFirst(), batched.getLast()), AGAVE_MAX_INBOUND_MESSAGE_BYTES);

    final Consumer<SolanaRpcWebsocket> first = ws -> {
      assertTrue(ws.programSubscribe(Commitment.CONFIRMED, PROGRAM, filters, _ -> {
      }, _ -> {
      }));
      accounts(List.of(KEY_A, KEY_B)).accept(ws);
    };
    final var aloneFirst = sentByAdoption(0, first);
    assertEquals(List.of(aloneFirst.getFirst(), array(aloneFirst.subList(1, 3))),
        sentByAdoption(AGAVE_MAX_INBOUND_MESSAGE_BYTES, first),
        "opening the pass, the oversize request still goes alone and the requests after it share");
  }

  /// Registers account requests on either side of a signature request whose text is `signature`,
  /// from request 2 on, collecting each request's handle as its `onSub` runs.
  private static void accountsAroundASignature(final SolanaRpcWebsocket ws,
                                               final String signature,
                                               final Map<Long, Subscription<?>> handles) {
    for (final var key : List.of(KEY_A, KEY_B)) {
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, key, sub -> handles.put(sub.msgId(), sub), _ -> {
      }));
    }
    assertTrue(ws.signatureSubscribe(signature, sub -> handles.put(sub.msgId(), sub), _ -> {
    }));
    for (final var key : List.of(KEY_C, KEY_D)) {
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, key, sub -> handles.put(sub.msgId(), sub), _ -> {
      }));
    }
  }

  /// A request whose text the JDK refuses to encode fails its own send and nobody else's: sized as
  /// unshareable, it travels alone, so the requests on either side share their messages, are sent,
  /// and confirm. A signature holding a lone surrogate is such a request, since a signature is
  /// checked only for frame-splicing characters. In a shared message the JDK would refuse the whole
  /// array, and every request in it would fail with it, once per resend window. The socket refuses
  /// what the JDK refuses (`RecordingWebSocket.strictUtf8`), judged by its own encoder.
  @Test
  void anUnencodableRequestNeverPoisonsItsNeighbours() throws InterruptedException {
    final var unencodable = "lone\uD83Dsurrogate";
    final var alone = sentByAdoption(0, ws -> accountsAroundASignature(ws, unencodable, new TreeMap<>()));
    assertEquals(5, alone.size(), alone::toString);

    final var clock = new TestClock();
    final var sendFailures = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, AGAVE_MAX_INBOUND_MESSAGE_BYTES, (_, ex) -> sendFailures.add(ex))) {
      final var handles = new TreeMap<Long, Subscription<?>>();
      accountsAroundASignature(ws, unencodable, handles);
      final var socket = new RecordingWebSocket();
      socket.strictUtf8 = true;
      ws.onOpen(socket);

      assertEquals(List.of(array(alone.subList(0, 2)), alone.get(2), array(alone.subList(3, 5))), socket.sentText,
          "the unencodable request goes alone between the two shared messages");
      assertEquals(1, sendFailures.size(), () -> "only its own message failed: " + sendFailures);
      final var failure = assertInstanceOf(IOException.class, sendFailures.getFirst());
      assertEquals("Malformed text message", failure.getMessage());
      assertEquals(List.of(2L, 3L, 5L, 6L), List.copyOf(handles.keySet()),
          "every request but the refused one was sent and ran its onSub");

      feed(ws, socket, "[" + grant(2, 700) + "," + grant(3, 701) + "]");
      feed(ws, socket, "[" + grant(5, 702) + "," + grant(6, 703) + "]");
      assertAll(
          () -> assertEquals(BigInteger.valueOf(700), handles.get(2L).subId()),
          () -> assertEquals(BigInteger.valueOf(701), handles.get(3L).subId()),
          () -> assertEquals(BigInteger.valueOf(702), handles.get(5L).subId()),
          () -> assertEquals(BigInteger.valueOf(703), handles.get(6L).subId())
      );

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L);
      ws.checkCycle(0L);
      assertEquals(alone.get(2), socket.sentText.getLast(), "past the window it is retried, alone");
      assertEquals(4, socket.sentText.size(), socket.sentText::toString);
      assertEquals(2, sendFailures.size(), "and refused again, alone");
    }
  }

  /// Registers account requests on either side of a generic request with `paramsJson`, from
  /// request 2 on, collecting each request's handle as its `onSub` runs.
  private static void accountsAroundAGeneric(final SolanaRpcWebsocket ws,
                                             final String paramsJson,
                                             final Map<Long, Subscription<?>> handles) {
    for (final var key : List.of(KEY_A, KEY_B)) {
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, key, sub -> handles.put(sub.msgId(), sub), _ -> {
      }));
    }
    assertTrue(ws.subscribe("transactionSubscribe", "transactionUnsubscribe", "transactionNotification",
        "spliced", paramsJson, JsonIterator::readLong, sub -> handles.put(sub.msgId(), sub), _ -> {
        }));
    for (final var key : List.of(KEY_C, KEY_D)) {
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, key, sub -> handles.put(sub.msgId(), sub), _ -> {
      }));
    }
  }

  /// A generic request never shares a message, because its frame carries the caller's
  /// `paramsJson` raw. These params splice a cancellation of subscription 100 into the frame:
  /// alone, the frame is one message the server refuses whole, while inside an array the
  /// cancellation would be a valid call it runs against whatever owns id 100. With batching on,
  /// the generic request goes out alone and exactly as rendered, no array carries the spliced
  /// call, and its typed neighbours share their messages and confirm. The rule is the request's
  /// type, not its text: a well-formed generic request opening the pass goes alone too.
  @Test
  void aGenericRequestNeverSharesAnArray() {
    try (final var ws = websocket(new TestClock(), AGAVE_MAX_INBOUND_MESSAGE_BYTES, null)) {
      final var handles = new TreeMap<Long, Subscription<?>>();
      accountsAroundAGeneric(ws, SPLICING_PARAMS, handles);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      assertEquals(List.of(2L, 3L, 4L, 5L, 6L), List.copyOf(handles.keySet()), "every request was sent");
      final var generic = handles.get(4L).msg();
      assertTrue(generic.contains("\"id\":99999"), "the generic frame carries the spliced call");
      assertEquals(List.of(
          array(List.of(handles.get(2L).msg(), handles.get(3L).msg())),
          generic,
          array(List.of(handles.get(5L).msg(), handles.get(6L).msg()))
      ), socket.sentText, "the generic request goes alone, as rendered, between its neighbours' arrays");
      assertEquals(1, socket.sentText.stream().filter(frame -> frame.contains("99999")).count(),
          "no array carries the spliced call");

      feed(ws, socket, "[" + grant(2, 700) + "," + grant(3, 701) + "]");
      feed(ws, socket, "[" + grant(5, 702) + "," + grant(6, 703) + "]");
      assertAll(
          () -> assertEquals(BigInteger.valueOf(700), handles.get(2L).subId()),
          () -> assertEquals(BigInteger.valueOf(701), handles.get(3L).subId()),
          () -> assertEquals(BigInteger.valueOf(702), handles.get(5L).subId()),
          () -> assertEquals(BigInteger.valueOf(703), handles.get(6L).subId())
      );
    }

    try (final var ws = websocket(new TestClock(), AGAVE_MAX_INBOUND_MESSAGE_BYTES, null)) {
      final var handles = new TreeMap<Long, Subscription<?>>();
      assertTrue(ws.subscribe("blockSubscribe", "blockUnsubscribe", "blockNotification", "all", "\"all\"",
          JsonIterator::readLong, sub -> handles.put(sub.msgId(), sub), _ -> {
          })); // request 2
      for (final var key : List.of(KEY_A, KEY_B)) {
        assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, key, sub -> handles.put(sub.msgId(), sub), _ -> {
        }));
      }
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      assertEquals(List.of(
          handles.get(2L).msg(),
          array(List.of(handles.get(3L).msg(), handles.get(4L).msg()))
      ), socket.sentText, "a well-formed generic request goes alone too, and the requests after it share");
    }
  }
}
