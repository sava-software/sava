package software.sava.rpc.json.http.ws;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;

import java.math.BigInteger;
import java.util.function.Consumer;

/// A live registration inside the websocket engine. Consumers receive one via the `onSub`
/// callback, which fires after each successful request *send* — before confirmation, while
/// [#subId()] is still null, and again on every re-send and reconnect replay. Treat it as a
/// read-only handle: [#setSubId(BigInteger)] and [#setLastAttempt(long)] are the engine's own
/// bookkeeping seams and calling them corrupts pacing and correlation. The engine retains
/// registrations internally; holding this handle is not required to keep one alive.
public interface Subscription<T> extends Consumer<T>, Runnable {

  /// A [#lastAttempt()] stamp meaning "never": far enough in the past that every sane pacing
  /// window — anything under ~34 years — has elapsed, without being so far that `now - NEVER`
  /// could overflow. Pacing time is monotonic and starts near zero, so 0 cannot mean "never" —
  /// it means "just now". A delay beyond 2^40 ms behaves as "disabled", exactly as it always
  /// has: under the previous wall clock, `now` was ~1.7e12 and the comparison came out the
  /// same way.
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

  /// The notification method this registration is served by — a built-in channel's derived
  /// name, or the method a generic registration was created under. Part of identity: a generic
  /// key is unique only within its notification method, so two registrations sharing a key
  /// across methods are distinct, in a consumer's collections as much as in the engine's.
  String notificationMethod();

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
