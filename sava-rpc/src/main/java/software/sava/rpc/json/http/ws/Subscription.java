package software.sava.rpc.json.http.ws;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;

import java.util.function.Consumer;

public interface Subscription<T> extends Consumer<T>, Runnable {

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

  Commitment commitment();

  String key();

  PublicKey publicKey();

  long msgId();

  String msg();

  long lastAttempt();

  void setLastAttempt(long lastAttempt);

  long subId();

  void setSubId(long subId);
}
