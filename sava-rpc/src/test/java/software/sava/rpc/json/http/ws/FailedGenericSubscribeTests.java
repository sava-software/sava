package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Defensive tests for what a generic `subscribe` that fails leaves in this library's
/// notification-method registry. `SolanaJsonRpcWebsocket.subscribe` refuses a null key with
/// `IllegalArgumentException`, as the interface documents, before it creates the
/// notification namespace or mints a request id: the registry put would reject the key only
/// after both exist, leaving an empty namespace resident for the instance's life. These tests
/// pin that the refusal leaves no namespace or registration behind, and that a notification
/// under the refused method is then ignored: no exception subscriber hears of it and no frame
/// answers it. The outcome under test is the state the failure leaves, not how it fails, so
/// the call is made through `subscribeWithNullKey`.
@ExtendWith(QuietWsLogging.class)
final class FailedGenericSubscribeTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000L, 60_000L, 60_000L);

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

  /// The first registration under `fooNotification`, with a null key. Whether the call
  /// throws or answers false, it must not have registered anything.
  private static boolean subscribeWithNullKey(final SolanaJsonRpcWebsocket ws) {
    try {
      return ws.subscribe("fooSubscribe", "fooUnsubscribe", "fooNotification", null, "\"p\"",
          JsonIterator::readLong, null, _ -> {
          });
    } catch (final RuntimeException rejected) {
      return false;
    }
  }

  /// A notification under a method with no live registration is ignored: it is not consumer
  /// news and it mints no cancellation, since no un-subscription method was ever bound. A
  /// subscribe refused under that method must not change that. The router reads an empty
  /// namespace as no registration too, so a leaked one would still pass here:
  /// `aFailedSubscribeRetainsNoNotificationNamespace` is the test that catches the leak.
  @Test
  void aNotificationUnderAFailedSubscribesNamespaceIsIgnored() {
    try (final var ws = websocket()) {
      final var errors = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertFalse(subscribeWithNullKey(ws), "a null key cannot become a registration");

      ws.onText(socket,
          "{\"jsonrpc\":\"2.0\",\"method\":\"fooNotification\",\"params\":{\"result\":9,\"subscription\":55}}",
          true);

      assertTrue(errors.isEmpty(), () -> "an unregistered method's frame is not consumer news: " + errors);
      assertTrue(socket.sentText.isEmpty(), () -> "nothing is owed for it: " + socket.sentText);
    }
  }

  /// Failure atomicity: a subscribe that registered nothing retains nothing. The prune in
  /// `unsubscribe` and in the terminal release exists because an empty namespace left
  /// resident is a per-method leak for the instance's life; `retainedRegistrations` counts it.
  @Test
  void aFailedSubscribeRetainsNoNotificationNamespace() {
    try (final var ws = websocket()) {
      assertFalse(subscribeWithNullKey(ws), "a null key cannot become a registration");

      assertEquals(0, ws.retainedRegistrations(),
          "a subscribe that registered nothing must not leave its notification namespace behind");
    }
  }
}
