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

/// An error response names the request it rejects by its envelope id, which
/// `JsonRpcException.envelopeRequestId` reads as a non-negative long or nothing ("the only
/// ids this client mints"); the websocket stands in -1 for nothing. Request ids are minted
/// by pre-incrementing a counter that starts at 1, so the first is 2. The ws
/// `# positive request-id domain` family claims that the ids below 2 the reader can still
/// produce, 0 and 1, and the -1 sentinel name no request, so entering or skipping the
/// correlation block for them changes nothing. Each test rejects those ids, and ids the
/// reader cannot carry, around one live request, and asserts that the request is untouched
/// until a rejection names its own id.
@ExtendWith(QuietWsLogging.class)
final class ResponseIdDomainTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000L, Long.MAX_VALUE, 60_000L, Long.MAX_VALUE, 60_000L);
  private static final PublicKey ACCOUNT = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
  /// Zero and one, the -1 sentinel's sources (null, negative, fractional, string and
  /// out-of-range ids), none of them minted.
  private static final List<String> UNMINTED_IDS = List.of(
      "0", "1", "null", "-7", "2.5", "\"2\"", "99999999999999999999"
  );

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
        (_, _, _) -> {
        },
        null,
        null,
        null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  private static String rejection(final long code, final String message, final String id) {
    return """
        {"jsonrpc":"2.0","error":{"code":%d,"message":"%s"},"id":%s}""".formatted(code, message, id);
  }

  private static long framesContaining(final RecordingWebSocket socket, final String token) {
    return socket.sentText.stream().filter(frame -> frame.contains(token)).count();
  }

  /// A request-defect code releases the registration it names. Rejections naming no minted
  /// id release nothing and are reported as uncorrelated; the request's own id releases it.
  @Test
  void requestDefectRejectionsOfUnmintedIdsReleaseNothing() {
    try (final var ws = createWebsocket(new TestClock())) {
      final var dispatched = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(dispatched::add);
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(1, socket.sentText.size(), socket.sentText::toString);
      assertTrue(socket.sentText.getFirst().contains("\"id\":2,"), "the first minted id is 2");
      final int retained = ws.retainedRegistrations();

      for (final var id : UNMINTED_IDS) {
        feed(ws, socket, rejection(-32602, "Invalid params", id));
        assertAll(
            () -> assertEquals(retained, ws.retainedRegistrations(), "id " + id + " released a registration"),
            () -> assertFalse(ws.accountSubscribe(ACCOUNT, _ -> {
            }), "id " + id + " freed the registry slot")
        );
      }
      assertEquals(UNMINTED_IDS.size(), dispatched.size(), "each uncorrelated rejection is reported");

      feed(ws, socket, rejection(-32602, "Invalid params", "2"));
      assertEquals(0, ws.retainedRegistrations(), "the request's own id releases it");
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }), "the released slot is free again");
    }
  }

  /// A server-condition code releases the send gate of the request it names, so the request
  /// is re-sent one resend window after its attempt. Rejections naming no minted id leave
  /// the gate closed past that window; the request's own id opens it.
  @Test
  void serverConditionRejectionsOfUnmintedIdsKeepTheSendGated() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = createWebsocket(clock)) {
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // request 2, unanswered
      for (final var id : UNMINTED_IDS) {
        feed(ws, socket, rejection(-32603, "Internal error", id));
      }
      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1L);
      ws.checkCycle(0L);
      assertEquals(1, framesContaining(socket, "\"id\":2,"), "the unanswered request stays gated");

      feed(ws, socket, rejection(-32603, "Internal error", "2"));
      ws.checkCycle(0L);
      assertEquals(2, framesContaining(socket, "\"id\":2,"), "its own rejection re-arms the send");
    }
  }
}
