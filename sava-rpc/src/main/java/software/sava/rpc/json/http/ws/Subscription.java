package software.sava.rpc.json.http.ws;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;

import java.math.BigInteger;
import java.util.function.Consumer;

/// A live registration inside the websocket engine. The `onSub` callback receives it after each
/// successful send of its request, including retries and reconnect replays; the server's
/// confirmation may or may not have arrived, so [#subId()] may still be null. Treat it as
/// read-only: [#setSubId(BigInteger)] and [#setLastAttempt(long)] are engine bookkeeping, and
/// calling them corrupts pacing and correlation. The engine retains registrations; holding this
/// handle is not required.
public interface Subscription<T> extends Consumer<T>, Runnable {

  /// A [#lastAttempt()] stamp meaning "never attempted". Stamps are monotonic pacing
  /// milliseconds starting near zero, so 0 means "just now"; this is about 34 years earlier, so
  /// `now - NEVER` cannot overflow but exceeds only windows shorter than that. Compare against
  /// it explicitly when a window may be larger.
  long NEVER = -(1L << 40);

  static <T> Subscription<T> createAccountSubscription(final Commitment commitment,
                                                       final Channel channel,
                                                       final PublicKey publicKey,
                                                       final long msgId,
                                                       final String msg,
                                                       final Consumer<Subscription<T>> onSub,
                                                       final Consumer<T> consumer) {
    return new AccountSubscription<>(commitment, channel, publicKey, msgId, msg, onSub, consumer);
  }
  
  static <T> Subscription<T> createSubscription(final Commitment commitment,
                                                final Channel channel,
                                                final String key,
                                                final long msgId,
                                                final String msg,
                                                final Consumer<Subscription<T>> onSub,
                                                final Consumer<T> consumer) {
    return new RootSubscription<>(commitment, channel, key, msgId, msg, onSub, consumer);
  }

  void accept(final T t);

  Channel channel();

  /// The notification method serving this registration: the channel's derived name, or the
  /// method a generic registration was created under. Part of identity, since a generic key is
  /// unique only within its notification method. The default derives it from [#channel()] and
  /// returns null when the channel is null.
  default String notificationMethod() {
    final var channel = channel();
    return channel == null ? null : channel.name() + "Notification";
  }

  /// The default derives it from [#channel()].
  ///
  /// @throws NullPointerException if the default runs with a null [#channel()]
  default String unSubscribeMethod() {
    return channel().unSubscribe();
  }

  Commitment commitment();

  String key();

  PublicKey publicKey();

  long msgId();

  String msg();

  long lastAttempt();

  void setLastAttempt(long lastAttempt);

  BigInteger subId();

  void setSubId(final BigInteger subId);
}
