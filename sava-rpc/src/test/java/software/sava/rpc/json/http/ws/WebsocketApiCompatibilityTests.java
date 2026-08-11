package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.WebSocket;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/// Compatibility pins for implementations and callers compiled against the websocket API before
/// the liveness timing additions. These fakes deliberately implement only the old abstract
/// surface: the test must stop compiling if an additive method loses its compatibility default.
final class WebsocketApiCompatibilityTests {

  @Test
  void aLegacyWebsocketHasTheConservativeLastMessageObservation() throws ReflectiveOperationException {
    final var method = SolanaRpcWebsocket.class.getMethod("lastMessageReceivedTimestamp");
    assertTrue(method.isDefault(), "an added abstract method breaks existing websocket implementations");

    final var legacy = (SolanaRpcWebsocket) Proxy.newProxyInstance(
        SolanaRpcWebsocket.class.getClassLoader(),
        new Class<?>[]{SolanaRpcWebsocket.class},
        (proxy, invoked, args) -> InvocationHandler.invokeDefault(
            proxy, invoked, args == null ? new Object[0] : args));
    assertEquals(0L, legacy.lastMessageReceivedTimestamp(),
        "an implementation with no observation must not claim evidence of traffic");
  }

  @Test
  void aLegacyBuilderDerivesReadableValuesForTheNewTimingAccessors() {
    final SolanaRpcWebsocket.Builder legacy = new LegacyBuilder();

    assertEquals(41L, legacy.connectTimeout(),
        "legacy builders used their reconnect delay as the handshake timeout");
    assertEquals(86L, legacy.keepAliveDelay(),
        "an unset keep-alive follows twice the legacy ping delay");
    assertEquals(47L, legacy.subscriptionResendDelay(),
        "the derived resend delay is floored at the legacy check cadence");
  }

  @Test
  void aLegacyBuilderRejectsIndependentTimingConfigurationClearly() {
    final SolanaRpcWebsocket.Builder legacy = new LegacyBuilder();

    assertUnsupported("connectTimeout", () -> legacy.connectTimeout(101L));
    assertUnsupported("keepAliveDelay", () -> legacy.keepAliveDelay(103L));
    assertUnsupported("subscriptionResendDelay", () -> legacy.subscriptionResendDelay(107L));

    assertEquals(41L, legacy.reConnectDelay(), "an unsupported setter must not alter an old knob");
    assertEquals(43L, legacy.pingDelay(), "an unsupported setter must not alter an old knob");
    assertEquals(47L, legacy.subscriptionAndPingCheckDelay(),
        "an unsupported setter must not alter an old knob");
  }

  @Test
  void aLegacySubscriptionDerivesItsBuiltInNotificationMethod() {
    final Subscription<String> account = new LegacySubscription(Channel.account);
    final Subscription<String> channelLess = new LegacySubscription(null);

    assertEquals("accountNotification", account.notificationMethod());
    assertNull(channelLess.notificationMethod(),
        "a legacy channel-less subscription has no notification method to derive");
  }

  @Test
  void theLegacyTimingsConstructorKeepsItsBinaryDescriptor() throws ReflectiveOperationException {
    final var constructor = Timings.class.getConstructor(long.class, long.class, long.class);
    final var timings = constructor.newInstance(-53L, 0L, -61L);

    assertEquals(-53L, timings.reConnectDelay());
    assertEquals(0L, timings.pingDelay());
    assertEquals(-61L, timings.subscriptionAndPingCheckDelay());
    assertEquals(0L, timings.keepAliveDelay());
    assertEquals(1L, timings.subscriptionResendDelay());
  }

  private static void assertUnsupported(final String setting, final Runnable operation) {
    final var failure = assertThrows(UnsupportedOperationException.class, operation::run);
    assertTrue(String.valueOf(failure.getMessage()).contains(setting), failure.getMessage());
  }

  /// Implements exactly the Builder methods present before independent handshake, keep-alive,
  /// and subscription-retry timings were added.
  private static final class LegacyBuilder implements SolanaRpcWebsocket.Builder {

    private URI uri;
    private WebSocket.Builder webSocketBuilder;
    private int maxMessageLength = 37;
    private long reConnectDelay = 41;
    private long pingDelay = 43;
    private long checkDelay = 47;
    private Commitment commitment = Commitment.FINALIZED;
    private SolanaAccounts solanaAccounts = SolanaAccounts.MAIN_NET;
    private Consumer<SolanaRpcWebsocket> onOpen;
    private SolanaRpcWebsocket.OnClose onClose;
    private BiConsumer<SolanaRpcWebsocket, Throwable> onError;
    private BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError;
    private BiConsumer<SolanaRpcWebsocket, Throwable> onPingError;

    @Override
    public SolanaRpcWebsocket create() {
      return null;
    }

    @Override
    public LegacyBuilder uri(final URI uri) {
      this.uri = uri;
      return this;
    }

    @Override
    public LegacyBuilder webSocketBuilder(final WebSocket.Builder webSocketBuilder) {
      this.webSocketBuilder = webSocketBuilder;
      return this;
    }

    @Override
    public LegacyBuilder maxMessageLength(final int maxMessageLength) {
      this.maxMessageLength = maxMessageLength;
      return this;
    }

    @Override
    public int maxMessageLength() {
      return maxMessageLength;
    }

    @Override
    public LegacyBuilder reConnectDelay(final long reConnectDelay) {
      this.reConnectDelay = reConnectDelay;
      return this;
    }

    @Override
    public LegacyBuilder pingDelay(final long pingDelay) {
      this.pingDelay = pingDelay;
      return this;
    }

    @Override
    public LegacyBuilder subscriptionAndPingCheckDelay(final long subscriptionAndPingCheckDelay) {
      this.checkDelay = subscriptionAndPingCheckDelay;
      return this;
    }

    @Override
    public LegacyBuilder commitment(final Commitment commitment) {
      this.commitment = commitment;
      return this;
    }

    @Override
    public LegacyBuilder solanaAccounts(final SolanaAccounts solanaAccounts) {
      this.solanaAccounts = solanaAccounts;
      return this;
    }

    @Override
    public URI wsUri() {
      return uri;
    }

    @Override
    public WebSocket.Builder webSocketBuilder() {
      return webSocketBuilder;
    }

    @Override
    public long reConnectDelay() {
      return reConnectDelay;
    }

    @Override
    public long pingDelay() {
      return pingDelay;
    }

    @Override
    public long subscriptionAndPingCheckDelay() {
      return checkDelay;
    }

    @Override
    public SolanaAccounts solanaAccounts() {
      return solanaAccounts;
    }

    @Override
    public Commitment commitment() {
      return commitment;
    }

    @Override
    public Consumer<SolanaRpcWebsocket> onOpen() {
      return onOpen;
    }

    @Override
    public LegacyBuilder onOpen(final Consumer<SolanaRpcWebsocket> onOpen) {
      this.onOpen = onOpen;
      return this;
    }

    @Override
    public SolanaRpcWebsocket.OnClose onClose() {
      return onClose;
    }

    @Override
    public LegacyBuilder onClose(final SolanaRpcWebsocket.OnClose onClose) {
      this.onClose = onClose;
      return this;
    }

    @Override
    public BiConsumer<SolanaRpcWebsocket, Throwable> onError() {
      return onError;
    }

    @Override
    public LegacyBuilder onError(final BiConsumer<SolanaRpcWebsocket, Throwable> onError) {
      this.onError = onError;
      return this;
    }

    @Override
    public BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError() {
      return onSendTextError;
    }

    @Override
    public LegacyBuilder onSendTextError(final BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError) {
      this.onSendTextError = onSendTextError;
      return this;
    }

    @Override
    public BiConsumer<SolanaRpcWebsocket, Throwable> onPingError() {
      return onPingError;
    }

    @Override
    public LegacyBuilder onPingError(final BiConsumer<SolanaRpcWebsocket, Throwable> onPingError) {
      this.onPingError = onPingError;
      return this;
    }
  }

  /// Implements exactly the Subscription methods present before notification-method identity was
  /// exposed on the public handle.
  private static final class LegacySubscription implements Subscription<String> {

    private final Channel channel;
    private long lastAttempt = 67;
    private BigInteger subId = BigInteger.valueOf(71);

    private LegacySubscription(final Channel channel) {
      this.channel = channel;
    }

    @Override
    public void accept(final String value) {
    }

    @Override
    public void run() {
    }

    @Override
    public Channel channel() {
      return channel;
    }

    @Override
    public Commitment commitment() {
      return Commitment.FINALIZED;
    }

    @Override
    public String key() {
      return "legacy-key";
    }

    @Override
    public PublicKey publicKey() {
      return null;
    }

    @Override
    public long msgId() {
      return 73;
    }

    @Override
    public String msg() {
      return "legacy-message";
    }

    @Override
    public long lastAttempt() {
      return lastAttempt;
    }

    @Override
    public void setLastAttempt(final long lastAttempt) {
      this.lastAttempt = lastAttempt;
    }

    @Override
    public BigInteger subId() {
      return subId;
    }

    @Override
    public void setSubId(final BigInteger subId) {
      this.subId = subId;
    }
  }
}
