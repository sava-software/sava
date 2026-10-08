package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.JsonRpcException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Defensive tests for how this library classifies JSON-RPC error responses on the
/// websocket. A response correlates by its id with a request this connection sent, and a
/// correlated error is consumer news whatever its message says; the same "Invalid
/// subscription id" wording under an id the connection never sent, or no longer tracks, is
/// the stale un-subscription case and is dropped. A cancellation and a cancelled subscribe
/// each keep their correlation in two structures, so each test drives one of them and
/// compares the correlated route with the uncorrelated one.
@ExtendWith(QuietWsLogging.class)
final class CorrelatedRejectionWordingTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000L, 60_000L, 60_000L);
  private static final PublicKey ACCOUNT =
      PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");

  private static SolanaJsonRpcWebsocket websocket() {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(),
        new RecordingExecutor(),
        null,
        _ -> {
        },
        (_, _, _) -> {
        },
        null,
        null,
        null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws,
                           final RecordingWebSocket socket,
                           final String json) {
    ws.onText(socket, json, true);
  }

  private static String staleWordedError(final long code, final long id) {
    return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":" + code
        + ",\"message\":\"Invalid subscription id: transient\"},\"id\":" + id + '}';
  }

  /// A notification for an id nothing owns mints a cancellation (request 2). Its rejection
  /// under a code that keeps the error reportable (a server condition, or a method the
  /// server does not recognize) is delivered; the same frame under an id never sent is not.
  @Test
  void aRejectedCancellationIsDeliveredWhateverItsWording() {
    for (final long code : List.of((long) JsonRpcException.INTERNAL_ERROR, (long) JsonRpcException.METHOD_NOT_FOUND)) {
      try (final var ws = websocket()) {
        final var errors = new ArrayList<RuntimeException>();
        ws.exceptionSubscribe(errors::add);
        final var socket = new RecordingWebSocket();
        ws.onOpen(socket);
        feed(ws, socket, """
            {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":1},"value":{"data":["","base64"],"executable":false,"lamports":1,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":0}},"subscription":700}}""");
        assertEquals(1L, socket.sentText.stream()
            .filter(frame -> frame.contains("\"id\":2,\"method\":\"accountUnsubscribe\"")).count());

        feed(ws, socket, staleWordedError(code, 2));
        assertEquals(1, errors.size(), () -> code + ": the cancellation's own rejection is consumer news");
        assertEquals(code, assertInstanceOf(JsonRpcException.class, errors.getFirst()).code());

        feed(ws, socket, staleWordedError(code, 999));
        assertEquals(1, errors.size(), () -> code + ": the same wording from an id never sent is dropped");
      }
    }
  }

  /// A subscribe cancelled while on the wire leaves a tombstone for its request id (2). The
  /// server's rejection of that request is still the answer to a request this connection
  /// sent, so it is delivered, and it consumes the tombstone: the same frame repeated is no
  /// longer correlated and is dropped.
  @Test
  void aRejectedCancelledSubscribeIsDeliveredWhateverItsWording() {
    try (final var ws = websocket()) {
      final var errors = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // request 2 on the wire
      assertTrue(ws.accountUnsubscribe(ACCOUNT));
      assertEquals(1, ws.retainedCancellationTombstones());

      final long internalError = JsonRpcException.INTERNAL_ERROR;
      feed(ws, socket, staleWordedError(internalError, 2));
      assertEquals(1, errors.size(), "the cancelled request's rejection is consumer news");
      assertEquals(internalError, assertInstanceOf(JsonRpcException.class, errors.getFirst()).code());
      assertEquals(0, ws.retainedCancellationTombstones(), "the answer consumes the tombstone");

      feed(ws, socket, staleWordedError(internalError, 2));
      assertEquals(1, errors.size(), "once answered, the id no longer correlates");
    }
  }
}
