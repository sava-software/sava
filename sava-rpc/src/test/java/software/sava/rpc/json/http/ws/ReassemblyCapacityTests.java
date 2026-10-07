package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.ws.SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH;

/// The reassembly buffer behind fragmented websocket text, from both sides of the ws
/// `# capacity math` family (`config/pitest/README.md`): `ensureCapacity` grows the
/// buffer to `clamp(2 × capacity + 2, required, maxMessageLength)`.
///
/// Output: a message delivers its exact characters however it was fragmented and however
/// the buffer grew. The oracle is the payload the test generated, compared with what the
/// subscription's parser read back, over the lengths the growth arithmetic keys on: the
/// initial capacity and one past it, the band around twice that where hints a few chars
/// apart clamp differently, a cap between two such hints, and several doublings out.
///
/// Growth: reassembly reallocates geometrically, never once per fragment, and a cap within
/// reach of the next growth is reached in one allocation, as the design note on
/// `ensureCapacity` promises. Read through the `reassemblyCapacity()` seam, against a
/// bound (a step of at least half the capacity) that the doubling policy meets with room.
@ExtendWith(QuietWsLogging.class)
final class ReassemblyCapacityTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final String PREFIX =
      "{\"jsonrpc\":\"2.0\",\"method\":\"eventNotification\",\"params\":{\"result\":\"";
  private static final String SUFFIX = "\",\"subscription\":99}}";

  private static SolanaJsonRpcWebsocket createWebsocket(final int maxMessageLength,
                                                        final List<Throwable> errors) {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        TIMINGS,
        maxMessageLength,
        new TestClock(),
        new RecordingExecutor(),
        null,
        _ -> {
        },
        (_, _, _) -> {
        },
        (_, error) -> errors.add(error),
        null, null
    );
  }

  /// An adopted connection with a confirmed generic subscription (server id 99) whose
  /// parser reads the notification's string result.
  private record Fixture(SolanaJsonRpcWebsocket ws,
                         RecordingWebSocket socket,
                         List<String> received,
                         List<Throwable> errors) implements AutoCloseable {

    static Fixture open(final int maxMessageLength) {
      final var errors = new ArrayList<Throwable>();
      final var ws = createWebsocket(maxMessageLength, errors);
      final var received = new ArrayList<String>();
      assertTrue(ws.subscribe(
          "eventSubscribe", "eventUnsubscribe", "eventNotification",
          "event", "", JsonIterator::readString, null, received::add
      ));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("{\"jsonrpc\":\"2.0\",\"result\":99,\"id\":2}".toCharArray()), true);
      return new Fixture(ws, socket, received, errors);
    }

    @Override
    public void close() {
      ws.close();
    }
  }

  private static String payload(final int length) {
    final char[] chars = new char[length];
    for (int i = 0; i < length; ++i) {
      chars[i] = (char) ('a' + i % 26);
    }
    return new String(chars);
  }

  /// A notification exactly `length` chars long.
  private static String message(final int length) {
    return PREFIX + payload(length - PREFIX.length() - SUFFIX.length()) + SUFFIX;
  }

  private static String payloadOf(final String message) {
    return message.substring(PREFIX.length(), message.length() - SUFFIX.length());
  }

  /// Delivers `message` as fragments ending at each cut, then a final one. Array-backed
  /// fragments take the `arraycopy` route, string-backed ones `CharBuffer.get`.
  private static void deliver(final Fixture fixture,
                              final String message,
                              final boolean arrayBacked,
                              final int... cuts) {
    final char[] chars = message.toCharArray();
    int from = 0;
    for (int i = 0; i <= cuts.length; ++i) {
      final int to = i < cuts.length ? cuts[i] : chars.length;
      final CharSequence fragment = arrayBacked
          ? CharBuffer.wrap(chars, from, to - from)
          : CharBuffer.wrap(message.substring(from, to));
      fixture.ws().onText(fixture.socket(), fragment, i == cuts.length);
      from = to;
    }
  }

  private static int[] everyStep(final int length, final int step) {
    final var cuts = new int[(length - 1) / step];
    for (int i = 0; i < cuts.length; ++i) {
      cuts[i] = (i + 1) * step;
    }
    return cuts;
  }

  private static int initialCapacity() {
    try (final var fixture = Fixture.open(DEFAULT_MAX_MESSAGE_LENGTH)) {
      return fixture.ws().reassemblyCapacity();
    }
  }

  @Test
  void theSeamReadsNoBufferWithoutAConnection() {
    try (final var ws = createWebsocket(DEFAULT_MAX_MESSAGE_LENGTH, new ArrayList<>())) {
      assertEquals(0, ws.reassemblyCapacity(), "before adoption");
      ws.onOpen(new RecordingWebSocket());
      assertTrue(ws.reassemblyCapacity() > 0, "an adopted connection owns a buffer");
      ws.close();
      assertEquals(0, ws.reassemblyCapacity(), "after close");
    }
  }

  @Test
  void fragmentedMessagesDeliverTheirExactPayloadAcrossTheCapacityShapes() {
    final int initial = initialCapacity();
    final int[] lengths = {
        initial - 1, initial, initial + 1,
        2 * initial - 3, 2 * initial - 2, 2 * initial - 1, 2 * initial,
        2 * initial + 1, 2 * initial + 2, 2 * initial + 3,
        9 * initial + 5
    };
    for (final int length : lengths) {
      final var message = message(length);
      final var shapes = List.of(
          new int[0], // whole: string-backed reassembles, array-backed parses in place
          new int[]{length / 2},
          new int[]{initial}, // fills the initial buffer exactly
          new int[]{initial + 1}, // the first growth on a non-final fragment
          new int[]{initial + 1, 2 * initial - 1}, // a second growth inside the band
          everyStep(length, 1_000)
      );
      for (final int[] cuts : shapes) {
        if (cuts.length > 0 && cuts[cuts.length - 1] >= length) {
          continue;
        }
        for (final boolean arrayBacked : new boolean[]{true, false}) {
          try (final var fixture = Fixture.open(DEFAULT_MAX_MESSAGE_LENGTH)) {
            deliver(fixture, message, arrayBacked, cuts);
            assertEquals(List.of(payloadOf(message)), fixture.received(),
                () -> length + " chars cut at " + Arrays.toString(cuts) + ", array " + arrayBacked);
            assertTrue(fixture.errors().isEmpty(), fixture.errors()::toString);
          }
        }
      }
    }
  }

  /// A cap at twice the initial capacity sits between the doubled hint (just above it) and
  /// a hint just below it, so the clamp decides where the buffer lands; the message,
  /// exactly at the cap, must arrive intact either way.
  @Test
  void aMessageAtACapBetweenTwoHintsDeliversItsExactPayload() {
    final int initial = initialCapacity();
    final int cap = 2 * initial;
    final var message = message(cap);
    for (final int[] cuts : List.of(
        new int[]{initial + 1}, new int[]{initial + 1, cap - 2}, new int[]{initial + 1, cap - 1})) {
      for (final boolean arrayBacked : new boolean[]{true, false}) {
        try (final var fixture = Fixture.open(cap)) {
          deliver(fixture, message, arrayBacked, cuts);
          assertEquals(List.of(payloadOf(message)), fixture.received(),
              () -> "cut at " + Arrays.toString(cuts) + ", array " + arrayBacked);
          assertTrue(fixture.errors().isEmpty(), fixture.errors()::toString);
          assertFalse(fixture.socket().aborted);
        }
      }
    }
  }

  /// The fragment that crosses the initial capacity grows the buffer by a geometric step,
  /// and the fragments that follow within that headroom reuse it: one copy for the
  /// crossing, not an exact fit that every later fragment copies again.
  @Test
  void crossingTheCapacityGrowsGeometricallyNotToAnExactFit() {
    try (final var fixture = Fixture.open(DEFAULT_MAX_MESSAGE_LENGTH)) {
      final var ws = fixture.ws();
      final int initial = ws.reassemblyCapacity();
      final int headroom = initial + initial / 2;
      final var message = message(headroom + 2);
      final char[] chars = message.toCharArray();

      ws.onText(fixture.socket(), CharBuffer.wrap(chars, 0, initial + 1), false);
      final int grown = ws.reassemblyCapacity();
      assertTrue(grown >= headroom,
          () -> "an exact fit, not a geometric step: " + initial + " -> " + grown);
      for (int offset = initial + 1; offset < headroom; ++offset) {
        ws.onText(fixture.socket(), CharBuffer.wrap(chars, offset, 1), false);
        assertEquals(grown, ws.reassemblyCapacity(), "a fragment inside the headroom re-copied");
      }
      ws.onText(fixture.socket(), CharBuffer.wrap(chars, headroom, 2), true);
      assertEquals(List.of(payloadOf(message)), fixture.received());
    }
  }

  /// A cap within reach of the next growth is reached in one allocation, and fragments
  /// below it never copy the buffer again: "one terminal allocation, not an exact-fit
  /// re-copy per fragment".
  @Test
  void aCapWithinReachOfTheNextGrowthIsReachedInOneAllocation() {
    final int initial = initialCapacity();
    final int cap = initial + 100;
    try (final var fixture = Fixture.open(cap)) {
      final var ws = fixture.ws();
      final var message = message(cap);
      final char[] chars = message.toCharArray();

      ws.onText(fixture.socket(), CharBuffer.wrap(chars, 0, initial + 1), false);
      assertEquals(cap, ws.reassemblyCapacity(), "the crossing growth lands on the cap");
      for (int offset = initial + 1; offset < cap - 1; ++offset) {
        ws.onText(fixture.socket(), CharBuffer.wrap(chars, offset, 1), false);
        assertEquals(cap, ws.reassemblyCapacity(), "a fragment below the cap re-copied");
      }
      ws.onText(fixture.socket(), CharBuffer.wrap(chars, cap - 1, 1), true);
      assertEquals(List.of(payloadOf(message)), fixture.received());
      assertTrue(fixture.errors().isEmpty(), fixture.errors()::toString);
      assertFalse(fixture.socket().aborted);
    }
  }
}
