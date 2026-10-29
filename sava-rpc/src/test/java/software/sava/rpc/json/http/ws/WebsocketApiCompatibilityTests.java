package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigInteger;

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
