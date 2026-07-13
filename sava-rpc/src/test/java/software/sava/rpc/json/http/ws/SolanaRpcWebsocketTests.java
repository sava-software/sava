package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class SolanaRpcWebsocketTests {

  private static SolanaRpcWebsocket createWebsocket(final HttpClient httpClient) {
    return SolanaRpcWebsocket.build()
        .uri("wss://localhost")
        .webSocketBuilder(httpClient)
        .create();
  }

  /// Subscriptions are queued before the websocket is connected, deduplicated, and may be
  /// unsubscribed from while pending.
  @Test
  void rootSubscription() {
    try (final var httpClient = HttpClient.newHttpClient();
         final var websocket = createWebsocket(httpClient)) {
      final var slot = new AtomicLong(-1);

      assertTrue(websocket.rootSubscribe(slot::set));
      assertFalse(websocket.rootSubscribe(slot::set));

      assertTrue(websocket.rootUnsubscribe());
      assertFalse(websocket.rootUnsubscribe());

      assertTrue(websocket.rootSubscribe(slot::set));
      assertFalse(websocket.closed());
    }
  }

  /// Methods which are not directly supported, e.g. those added by an RPC provider, may be
  /// subscribed to with a parser for their notifications.
  @Test
  void genericSubscription() {
    try (final var httpClient = HttpClient.newHttpClient();
         final var websocket = createWebsocket(httpClient)) {
      final var notification = new AtomicReference<String>();

      assertTrue(websocket.subscribe(
          "customSubscribe",
          "customUnsubscribe",
          "customNotification",
          "key",
          "\"params\"",
          JsonIterator::readString,
          notification::set
      ));

      // The key de-duplicates subscriptions of the same notification method.
      assertFalse(websocket.subscribe(
          "customSubscribe",
          "customUnsubscribe",
          "customNotification",
          "key",
          "\"params\"",
          JsonIterator::readString,
          notification::set
      ));
      assertTrue(websocket.subscribe(
          "customSubscribe",
          "customUnsubscribe",
          "customNotification",
          "otherKey",
          "\"params\"",
          JsonIterator::readString,
          notification::set
      ));

      assertTrue(websocket.unsubscribe("customNotification", "key"));
      assertFalse(websocket.unsubscribe("customNotification", "key"));
      assertFalse(websocket.unsubscribe("unknownNotification", "otherKey"));
      assertTrue(websocket.unsubscribe("customNotification", "otherKey"));
    }
  }

  @Test
  void accountSubscriptionsAreKeyedByCommitment() {
    try (final var httpClient = HttpClient.newHttpClient();
         final var websocket = createWebsocket(httpClient)) {
      final var account = PublicKey.fromBase58Encoded("BDn3HiXMTym7ZQofWFxDb7ZGQX6GomQzJYKfytTAqd5g");

      assertTrue(websocket.accountSubscribe(account, _ -> {
          }
      ));
      assertFalse(websocket.accountSubscribe(account, _ -> {
          }
      ));
      assertTrue(websocket.accountUnsubscribe(account));
      assertFalse(websocket.accountUnsubscribe(account));
    }
  }

  @Test
  void closedWebsocketIsNotUsable() {
    final SolanaRpcWebsocket websocket;
    try (final var httpClient = HttpClient.newHttpClient()) {
      websocket = createWebsocket(httpClient);
      assertFalse(websocket.closed());
      websocket.close();
    }
    assertTrue(websocket.closed());
    assertNull(websocket.connect());
  }
}
