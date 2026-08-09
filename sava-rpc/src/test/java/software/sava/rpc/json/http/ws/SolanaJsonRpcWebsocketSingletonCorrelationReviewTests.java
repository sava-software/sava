package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.nio.CharBuffer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Defensive protocol-correlation regressions: an untrusted notification must never make the
/// client cancel a live subscription owned by a different channel merely because it reuses that
/// channel's numeric subscription id.
final class SolanaJsonRpcWebsocketSingletonCorrelationReviewTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final PublicKey ACCOUNT =
      PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");

  @Test
  void slotNotificationCannotCancelAnotherChannelsSubscription() {
    assertSingletonFrameDoesNotCancelAccount("""
        {"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":1,"root":1,"slot":2},"subscription":700}}""",
        "slotUnsubscribe");
  }

  @Test
  void rootNotificationCannotCancelAnotherChannelsSubscription() {
    assertSingletonFrameDoesNotCancelAccount("""
        {"jsonrpc":"2.0","method":"rootNotification","params":{"result":2,"subscription":700}}""",
        "rootUnsubscribe");
  }

  private static void assertSingletonFrameDoesNotCancelAccount(final String notification,
                                                                final String wrongMethod) {
    try (final var ws = websocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":700,"id":2}"""), true);

      ws.onText(socket, CharBuffer.wrap(notification), true);

      assertFalse(socket.sentText.stream().anyMatch(frame -> frame.contains("\"method\":\"" + wrongMethod + "\"")
              && frame.contains("[700]")),
          "a malformed singleton notification must not cancel the account subscription: " + socket.sentText);
    }
  }

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
}
