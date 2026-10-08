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
/// notification-method registry. `SolanaJsonRpcWebsocket.subscribe` creates the namespace
/// map with `computeIfAbsent` before it stores the registration, and storing a null key
/// throws from `ConcurrentHashMap.put`, so the failed call leaves an empty namespace
/// behind. The interface documents no exception for a null key; the outcome under test is
/// the state the failure leaves, not how it fails, so the call is made through
/// `subscribeWithNullKey`.
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
  /// news and it mints no cancellation, since no un-subscription method was ever bound. The
  /// failed subscribe's empty namespace must not change that, whatever else it costs.
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
  /// resident is a per-method leak for the instance's life; `retainedRegistrations` counts
  /// it. Fails today: the empty `fooNotification` map stays until `close()`.
  @Test
  void aFailedSubscribeRetainsNoNotificationNamespace() {
    try (final var ws = websocket()) {
      assertFalse(subscribeWithNullKey(ws), "a null key cannot become a registration");

      assertEquals(0, ws.retainedRegistrations(),
          "a subscribe that registered nothing must not leave its notification namespace behind");
    }
  }
}
