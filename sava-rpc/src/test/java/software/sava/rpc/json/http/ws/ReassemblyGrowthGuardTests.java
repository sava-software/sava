package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.ws.SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH;

/// The growth guard on the reassembly buffer, the ws `# equal-capacity copy` family
/// (`config/pitest/README.md`): `ensureCapacity` reallocates only when a fragment carries
/// the message past the buffer it already has.
///
/// The guard is what keeps a fragment that adds nothing from costing a copy. Once a message
/// has filled a buffer grown to `maxMessageLength`, the cap check still admits empty
/// continuation fragments, and the JDK delivers every zero-length frame as an empty
/// `onText` part, so an inclusive guard would copy the whole buffer once per two-byte
/// frame. That same-sized copy is invisible to the `reassemblyCapacity()` seam. Below the
/// cap the same inclusive guard grows a buffer the message still fits, which the seam does
/// show.
@ExtendWith(QuietWsLogging.class)
final class ReassemblyGrowthGuardTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final String PREFIX =
      "{\"jsonrpc\":\"2.0\",\"method\":\"eventNotification\",\"params\":{\"result\":\"";
  private static final String SUFFIX = "\",\"subscription\":99}}";

  /// An adopted connection with a confirmed generic subscription (server id 99) whose
  /// parser reads the notification's string result.
  private record Fixture(SolanaJsonRpcWebsocket ws,
                         RecordingWebSocket socket,
                         List<String> received,
                         List<Throwable> errors) implements AutoCloseable {

    static Fixture open(final int maxMessageLength) {
      final var errors = new ArrayList<Throwable>();
      final var ws = new SolanaJsonRpcWebsocket(
          ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null, TIMINGS,
          maxMessageLength, new TestClock(), new RecordingExecutor(), null,
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
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("{\"jsonrpc\":\"2.0\",\"result\":99,\"id\":2}".toCharArray()), true);
      return new Fixture(ws, socket, received, errors);
    }

    void deliver(final char[] chars, final int from, final int to, final boolean last) {
      ws.onText(socket, CharBuffer.wrap(chars, from, to - from), last);
    }

    void deliverEmpty(final boolean last) {
      ws.onText(socket, CharBuffer.wrap(new char[0]), last);
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
    try (final var fixture = Fixture.open(DEFAULT_MAX_MESSAGE_LENGTH)) {
      return fixture.ws().reassemblyCapacity();
    }
  }

  /// Below the cap: a fragment that fills the buffer exactly, then an empty one, leave the
  /// buffer as it was; only the fragment that carries the message past it grows it.
  @Test
  void aFragmentThatExactlyFillsTheBufferDoesNotGrowIt() {
    try (final var fixture = Fixture.open(DEFAULT_MAX_MESSAGE_LENGTH)) {
      final var ws = fixture.ws();
      final int initial = ws.reassemblyCapacity();
      final var message = message(initial + 64);
      final char[] chars = message.toCharArray();

      fixture.deliver(chars, 0, initial, false);
      assertEquals(initial, ws.reassemblyCapacity(), "a fragment the buffer holds exactly grew it");
      fixture.deliverEmpty(false);
      assertEquals(initial, ws.reassemblyCapacity(), "an empty fragment on a full buffer grew it");

      fixture.deliver(chars, initial, chars.length, true);
      assertTrue(ws.reassemblyCapacity() > initial, "the fragment past the buffer must grow it");
      assertEquals(List.of(payloadOf(message)), fixture.received());
      assertTrue(fixture.errors().isEmpty(), fixture.errors()::toString);
    }
  }

  /// At the cap: a buffer that already has `maxMessageLength` chars and is full still
  /// admits empty continuation fragments, and the message they end arrives intact. This is
  /// the shape in which an inclusive guard re-copies the buffer per fragment; the copy
  /// keeps its size, so the seam reads the cap either way, and this test pins only the
  /// reachable shape and its payload.
  @Test
  void emptyFragmentsOnABufferFullAtTheCapStillCompleteTheMessage() {
    final int cap = initialCapacity();
    try (final var fixture = Fixture.open(cap)) {
      final var ws = fixture.ws();
      assertEquals(cap, ws.reassemblyCapacity(), "the buffer starts at the cap");
      final var message = message(cap);
      final char[] chars = message.toCharArray();

      fixture.deliver(chars, 0, cap / 2, false);
      fixture.deliver(chars, cap / 2, cap, false);
      for (int i = 0; i < 3; ++i) {
        fixture.deliverEmpty(false);
      }
      fixture.deliverEmpty(true);

      assertEquals(List.of(payloadOf(message)), fixture.received());
      assertEquals(cap, ws.reassemblyCapacity());
      assertTrue(fixture.errors().isEmpty(), fixture.errors()::toString);
      assertFalse(fixture.socket().aborted, "empty fragments at the cap are not an overflow");
    }
  }
}
