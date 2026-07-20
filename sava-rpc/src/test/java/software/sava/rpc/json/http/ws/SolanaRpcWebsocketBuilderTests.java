package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.SolanaNetwork;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// The builder is the whole public entry point to the websocket client, and its
/// defaults are the timings a caller inherits without saying anything.
final class SolanaRpcWebsocketBuilderTests {

  private static SolanaRpcWebsocket.Builder builder() {
    return SolanaRpcWebsocket.build();
  }

  @Test
  void defaultsAreTheDocumentedTimings() {
    final var builder = builder();
    assertEquals(3_000L, builder.reConnectDelay());
    assertEquals(15_000L, builder.pingDelay());
    assertEquals(2_000L, builder.subscriptionAndPingCheckDelay());
    assertEquals(Commitment.CONFIRMED, builder.commitment());
    assertSame(SolanaAccounts.MAIN_NET, builder.solanaAccounts());
  }

  @Test
  void unsetValuesStayNull() {
    final var builder = builder();
    assertNull(builder.wsUri());
    assertNull(builder.webSocketBuilder());
    assertNull(builder.onOpen());
    assertNull(builder.onClose());
    assertNull(builder.onError());
    assertNull(builder.onSendTextError());
    assertNull(builder.onPingError());
  }

  @Test
  void settersRoundTripAndChain() {
    final var uri = URI.create("wss://example.invalid");
    final var webSocketBuilder = HttpClient.newHttpClient().newWebSocketBuilder();
    final var builder = builder()
        .uri(uri)
        .webSocketBuilder(webSocketBuilder)
        .reConnectDelay(1_111L)
        .pingDelay(2_222L)
        .subscriptionAndPingCheckDelay(3_333L)
        .commitment(Commitment.FINALIZED)
        .solanaAccounts(SolanaAccounts.MAIN_NET);

    assertEquals(uri, builder.wsUri());
    assertSame(webSocketBuilder, builder.webSocketBuilder());
    assertEquals(1_111L, builder.reConnectDelay());
    assertEquals(2_222L, builder.pingDelay());
    assertEquals(3_333L, builder.subscriptionAndPingCheckDelay());
    assertEquals(Commitment.FINALIZED, builder.commitment());
  }

  @Test
  void callbacksRoundTrip() {
    final var opened = new AtomicReference<SolanaRpcWebsocket>();
    final SolanaRpcWebsocket.OnClose onClose = (_, _, _) -> {
    };
    final var builder = builder()
        .onOpen(opened::set)
        .onClose(onClose)
        .onError((_, _) -> {
        })
        .onSendTextError((_, _) -> {
        })
        .onPingError((_, _) -> {
        });

    assertNotNull(builder.onOpen());
    assertSame(onClose, builder.onClose());
    assertNotNull(builder.onError());
    assertNotNull(builder.onSendTextError());
    assertNotNull(builder.onPingError());
  }

  /// create() derives the connect timeout from the reconnect delay, so a caller
  /// tuning reconnects does not also have to remember to tune the timeout.
  @Test
  void createAppliesReconnectDelayAsTheConnectTimeout() {
    final var recorded = new AtomicReference<Duration>();
    final var webSocketBuilder = new RecordingWebSocketBuilder(recorded);

    final var websocket = builder()
        .uri(SolanaNetwork.MAIN_NET.getWebSocketEndpoint())
        .webSocketBuilder(webSocketBuilder)
        .reConnectDelay(7_500L)
        .create();

    assertNotNull(websocket);
    assertEquals(Duration.ofMillis(7_500L), recorded.get());
    assertEquals(SolanaNetwork.MAIN_NET.getWebSocketEndpoint(), websocket.endpoint());
  }

  @Test
  void createCarriesTheConfiguredCommitmentAndAccounts() {
    final var websocket = builder()
        .uri(SolanaNetwork.DEV_NET.getWebSocketEndpoint())
        .webSocketBuilder(new RecordingWebSocketBuilder(new AtomicReference<>()))
        .commitment(Commitment.FINALIZED)
        .create();

    assertEquals(Commitment.FINALIZED, websocket.defaultCommitment());
    assertEquals(SolanaNetwork.DEV_NET.getWebSocketEndpoint(), websocket.endpoint());
  }

  /// A websocket builder is required — create() dereferences it to set the
  /// connect timeout, so leaving it unset fails immediately rather than at
  /// connect time.
  @Test
  void createWithoutAWebSocketBuilderFails() {
    final var builder = builder().uri(URI.create("wss://example.invalid"));
    assertThrows(NullPointerException.class, builder::create);
  }
}
