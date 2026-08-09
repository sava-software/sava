package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Subscriptions are held in maps keyed by identity, and the websocket re-sends
/// them on reconnect. Identity is `(commitment, channel, key, notificationMethod)`
/// and deliberately excludes the message id, so a resubscribe with a fresh id
/// still matches the original; the notification method is the key's namespace,
/// which for channel subscriptions is derived from the channel.
final class SubscriptionTests {

  private static final PublicKey KEY = PublicKey.createPubKey(new byte[32]);

  private static Subscription<String> subscription(final Commitment commitment,
                                                   final Channel channel,
                                                   final String key,
                                                   final long msgId) {
    return Subscription.createSubscription(commitment, channel, key, msgId, "{}", null, _ -> {
    });
  }

  @Test
  void channelDerivesItsRpcMethodNames() {
    assertEquals("accountSubscribe", Channel.account.subscribe());
    assertEquals("accountUnsubscribe", Channel.account.unSubscribe());
    assertEquals("logsSubscribe", Channel.logs.subscribe());
    assertEquals("programSubscribe", Channel.program.subscribe());
    assertEquals("rootSubscribe", Channel.root.subscribe());
    assertEquals("signatureSubscribe", Channel.signature.subscribe());
    assertEquals("slotSubscribe", Channel.slot.subscribe());
    // The literals above are the pins. A loop asserting name() + "Subscribe" recomputed the
    // production expression and compared it to itself, so it could never fail; a new channel is
    // instead caught by the count, which a new value moves.
    assertEquals(6, Channel.values().length);
  }

  @Test
  void identityIsCommitmentChannelAndKey() {
    final var a = subscription(Commitment.CONFIRMED, Channel.account, "abc", 1);
    final var b = subscription(Commitment.CONFIRMED, Channel.account, "abc", 999);

    assertEquals(a, b, "the message id must not affect identity");
    assertEquals(a.hashCode(), b.hashCode());

    assertNotEquals(a, subscription(Commitment.FINALIZED, Channel.account, "abc", 1));
    assertNotEquals(a, subscription(Commitment.CONFIRMED, Channel.logs, "abc", 1));
    assertNotEquals(a, subscription(Commitment.CONFIRMED, Channel.account, "xyz", 1));
    assertNotEquals(a, "not a subscription");
  }

  /// A resubscribe replaces the old entry rather than accumulating a duplicate.
  @Test
  void resubscribeWithANewMessageIdReplacesTheMapEntry() {
    final var subscriptions = new HashMap<Subscription<String>, String>();
    subscriptions.put(subscription(Commitment.CONFIRMED, Channel.account, "abc", 1), "first");
    subscriptions.put(subscription(Commitment.CONFIRMED, Channel.account, "abc", 2), "second");
    assertEquals(1, subscriptions.size());
    assertEquals("second", subscriptions.values().iterator().next());
  }

  @Test
  void nullCommitmentAndChannelParticipateInIdentity() {
    final var generic = subscription(null, null, "key", 1);
    assertEquals(generic, subscription(null, null, "key", 2));
    assertNotEquals(generic, subscription(Commitment.CONFIRMED, null, "key", 1));
    assertNotEquals(generic, subscription(null, Channel.slot, "key", 1));
    assertDoesNotThrow(generic::hashCode);
  }

  /// The public generic-subscribe API defines a key as unique only within its notification
  /// method, so these are two simultaneously valid registrations. Their public handles must
  /// preserve that namespace in identity or a caller's set/map silently merges them.
  @Test
  void genericIdentityIncludesTheNotificationMethodNamespace() {
    final var first = new GenericSubscription<>(
        "firstUnsubscribe", "firstNotification", JsonIterator::readString,
        "shared-key", 1, "{}", null, _ -> {
    });
    final var second = new GenericSubscription<>(
        "secondUnsubscribe", "secondNotification", JsonIterator::readString,
        "shared-key", 2, "{}", null, _ -> {
    });

    assertNotEquals(first, second);
  }

  @Test
  void unSubscribeMethodDefaultsToTheChannel() {
    assertEquals("accountUnsubscribe",
        subscription(Commitment.CONFIRMED, Channel.account, "abc", 1).unSubscribeMethod());
    assertEquals("slotUnsubscribe",
        subscription(Commitment.CONFIRMED, Channel.slot, "abc", 1).unSubscribeMethod());
  }

  @Test
  void accountSubscriptionExposesItsPublicKey() {
    final var accountSub = Subscription.createAccountSubscription(
        Commitment.CONFIRMED, Channel.account, KEY, 1, "{}", null, _ -> {
        });
    assertEquals(KEY, accountSub.publicKey());
    assertEquals(KEY.toBase58(), accountSub.key(), "the key is the base58 address");

    // the plain subscription has no public key
    assertNull(subscription(Commitment.CONFIRMED, Channel.slot, "abc", 1).publicKey());
  }

  @Test
  void subIdAndLastAttemptAreMutable() {
    final var sub = subscription(Commitment.CONFIRMED, Channel.account, "abc", 1);
    assertNull(sub.subId());
    // NEVER, not 0: pacing time is monotonic and starts near zero, so a zero default would read
    // as "attempted just now" and suppress the very first send for a whole resend window.
    assertEquals(Subscription.NEVER, sub.lastAttempt());

    sub.setSubId(BigInteger.valueOf(42));
    assertEquals(BigInteger.valueOf(42), sub.subId());
    sub.setLastAttempt(1_234L);
    assertEquals(1_234L, sub.lastAttempt());

    // clearing on unsubscribe
    sub.setSubId(null);
    assertNull(sub.subId());
  }

  /// Mutating the subscription id must not move it in a map — identity excludes it.
  @Test
  void mutatingSubIdDoesNotChangeIdentity() {
    final var sub = subscription(Commitment.CONFIRMED, Channel.account, "abc", 1);
    final int before = sub.hashCode();
    sub.setSubId(BigInteger.TEN);
    sub.setLastAttempt(99L);
    assertEquals(before, sub.hashCode());
    assertEquals(sub, subscription(Commitment.CONFIRMED, Channel.account, "abc", 1));
  }

  @Test
  void runInvokesTheOnSubCallbackAndToleratesNull() {
    final var seen = new AtomicReference<Subscription<String>>();
    final var withCallback = Subscription.createSubscription(
        Commitment.CONFIRMED, Channel.account, "abc", 1, "{}", seen::set, _ -> {
        });
    withCallback.run();
    assertSame(withCallback, seen.get());

    final var withoutCallback = subscription(Commitment.CONFIRMED, Channel.account, "abc", 1);
    assertDoesNotThrow(withoutCallback::run);
  }

  @Test
  void acceptForwardsToTheConsumer() {
    final var received = new AtomicReference<String>();
    final var sub = Subscription.createSubscription(
        Commitment.CONFIRMED, Channel.account, "abc", 1, "{}", null, received::set);
    sub.accept("notification");
    assertEquals("notification", received.get());
  }

  @Test
  void toStringReportsTheSubscriptionState() {
    final var sub = subscription(Commitment.CONFIRMED, Channel.account, "abc", 77);
    sub.setSubId(BigInteger.valueOf(5));
    final var text = sub.toString();
    assertTrue(text.contains("channel=account"), text);
    assertTrue(text.contains("key=abc"), text);
    assertTrue(text.contains("id=77"), text);
    assertTrue(text.contains("subId=5"), text);
  }

  /// GenericSubscription overrides the unsubscribe method rather than deriving it
  /// from a channel, since it has none.
  @Test
  void genericSubscriptionUsesItsOwnUnsubscribeMethodAndParser() {
    final var received = new AtomicReference<String>();
    final var generic = new GenericSubscription<>(
        "customUnsubscribe",
        "customNotification",
        ji -> ji.readString(),
        "key",
        1,
        "{}",
        null,
        received::set);

    assertEquals("customUnsubscribe", generic.unSubscribeMethod());
    assertEquals("customNotification", generic.notificationMethod());
    assertNull(generic.channel());
    assertNull(generic.commitment());

    generic.parseAndAccept(JsonIterator.parse("\"parsed\"".getBytes(StandardCharsets.UTF_8)));
    assertEquals("parsed", received.get());
  }
}
