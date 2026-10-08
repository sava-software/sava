package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.JsonException;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.ws.SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH;

/// One text message, every backing it can arrive in: the ws `# zero-offset route
/// convergence` and `# equivalent buffer copy` families (`config/pitest/README.md`).
///
/// `onText` picks a copy route from what backs a frame. A whole array-backed frame is
/// parsed in place; any other whole frame is copied into the reassembly buffer first; a
/// fragment is appended by `System.arraycopy` from an array, or by `CharBuffer.get` when
/// there is none. The mutants in those families move frames between these routes. The
/// property is that the route never shows in what a subscription receives or in which
/// failures reach the exception subscribers.
///
/// The oracle is the payload the test generated, compared with what the subscription's
/// parser read back, for every shape a listener's `CharSequence` can take: heap buffers at
/// offset zero, at a non-zero position and as a slice; read-only and byte-view buffers that
/// expose no array; a wrapped `String`; and plain `String` and `StringBuilder` values. Each
/// is delivered whole and cut into fragments, over the lengths around the initial buffer
/// capacity.
@ExtendWith(QuietWsLogging.class)
final class TextFrameBackingParityTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final String PREFIX =
      "{\"jsonrpc\":\"2.0\",\"method\":\"eventNotification\",\"params\":{\"result\":\"";
  private static final String SUFFIX = "\",\"subscription\":99}}";
  /// Chars around a frame inside a larger array; never part of any message.
  private static final int PAD_BEFORE = 7;
  private static final int PAD_AFTER = 5;

  /// How a listener may be handed `text`. Covers both sides of `instanceof CharBuffer` and
  /// of `hasArray()`, and an array offset of zero and non-zero in position and in
  /// `arrayOffset`.
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

  /// An adopted connection with a confirmed generic subscription (server id 99) whose
  /// parser reads the notification's string result, and an exception subscriber.
  private record Fixture(SolanaJsonRpcWebsocket ws,
                         RecordingWebSocket socket,
                         List<String> received,
                         List<RuntimeException> exceptions,
                         List<Throwable> errors) implements AutoCloseable {

    static Fixture open() {
      final var errors = new ArrayList<Throwable>();
      final var ws = new SolanaJsonRpcWebsocket(
          ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null, TIMINGS,
          DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(), new RecordingExecutor(), null,
          _ -> {
          },
          (_, _, _) -> {
          },
          (_, error) -> errors.add(error),
          null, null
      );
      final var received = new ArrayList<String>();
      assertTrue(ws.subscribe(
          "eventSubscribe", "eventUnsubscribe", "eventNotification",
          "event", "", JsonIterator::readString, null, received::add
      ));
      final var exceptions = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(exceptions::add);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("{\"jsonrpc\":\"2.0\",\"result\":99,\"id\":2}".toCharArray()), true);
      return new Fixture(ws, socket, received, exceptions, errors);
    }

    void assertQuiet(final String shape) {
      assertTrue(exceptions.isEmpty(), () -> shape + ": " + exceptions);
      assertTrue(errors.isEmpty(), () -> shape + ": " + errors);
      assertFalse(socket.aborted, shape);
    }

    @Override
    public void close() {
      ws.close();
    }
  }

  /// A notification exactly `length` chars long.
  private static String message(final int length) {
    final char[] payload = new char[length - PREFIX.length() - SUFFIX.length()];
    for (int i = 0; i < payload.length; ++i) {
      payload[i] = (char) ('a' + i % 26);
    }
    return PREFIX + new String(payload) + SUFFIX;
  }

  private static String payloadOf(final String message) {
    return message.substring(PREFIX.length(), message.length() - SUFFIX.length());
  }

  private static int initialCapacity() {
    try (final var fixture = Fixture.open()) {
      return fixture.ws().reassemblyCapacity();
    }
  }

  @Test
  void aWholeFrameDeliversTheSamePayloadWhateverBacksIt() {
    final int initial = initialCapacity();
    for (final int length : new int[]{200, initial - 1, initial, initial + 1, 3 * initial + 7}) {
      final var message = message(length);
      for (final var backing : Backing.values()) {
        try (final var fixture = Fixture.open()) {
          fixture.ws().onText(fixture.socket(), backing.of(message), true);
          final var shape = length + " chars, whole, " + backing;
          assertEquals(List.of(payloadOf(message)), fixture.received(), shape);
          fixture.assertQuiet(shape);
        }
      }
    }
  }

  /// Every fragment position (first, middle, final) meets every backing: fragment `i` of
  /// rotation `r` takes backing `(i + r) % n`. The cuts land on both sides of the initial
  /// capacity, so appends run with and without growth.
  @Test
  void aFragmentedFrameDeliversTheSamePayloadWhateverBacksEachFragment() {
    final int initial = initialCapacity();
    final int length = 2 * initial + 3;
    final var message = message(length);
    final int[] cuts = {1, initial - 1, initial, initial + 1, 2 * initial};
    final var backings = Backing.values();
    for (int rotation = 0; rotation < backings.length; ++rotation) {
      try (final var fixture = Fixture.open()) {
        int from = 0;
        for (int i = 0; i <= cuts.length; ++i) {
          final int to = i < cuts.length ? cuts[i] : length;
          final var backing = backings[(i + rotation) % backings.length];
          fixture.ws().onText(fixture.socket(), backing.of(message.substring(from, to)), to == length);
          from = to;
        }
        final var shape = "rotation " + rotation;
        assertEquals(List.of(payloadOf(message)), fixture.received(), shape);
        fixture.assertQuiet(shape);
      }
    }
  }

  /// A frame that fails to parse reaches the exception subscribers once on every route, as
  /// the same failure. Its position is not compared: `JsonException.offset()` is a position
  /// in whichever buffer was parsed, the caller's array in place or the reassembly buffer.
  @Test
  void aMalformedWholeFrameReachesTheExceptionSubscribersWhateverBacksIt() {
    final var malformed = PREFIX + "abc";
    String expectedOp = null;
    for (final var backing : Backing.values()) {
      try (final var fixture = Fixture.open()) {
        fixture.ws().onText(fixture.socket(), backing.of(malformed), true);
        final var shape = "malformed, " + backing;
        assertTrue(fixture.received().isEmpty(), shape);
        assertEquals(1, fixture.exceptions().size(), () -> shape + ": " + fixture.exceptions());
        final var failure = assertInstanceOf(JsonException.class, fixture.exceptions().getFirst(), shape);
        assertNotNull(failure.op(), shape);
        if (expectedOp == null) {
          expectedOp = failure.op();
        } else {
          assertEquals(expectedOp, failure.op(), shape);
        }
        assertTrue(fixture.errors().isEmpty(), () -> shape + ": " + fixture.errors());
        assertFalse(fixture.socket().aborted, shape);
      }
    }
  }
}
