package software.sava.rpc.json.http.ws;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;

import java.util.function.Consumer;

class RootSubscription<T> implements Subscription<T> {

  protected final Commitment commitment;
  protected final Channel channel;
  protected final String key;
  protected final long msgId;
  protected final String msg;
  protected final Consumer<T> consumer;
  protected volatile long lastAttempt;
  protected volatile long subId;

  RootSubscription(final Commitment commitment,
                   final Channel channel,
                   final String key,
                   final long msgId,
                   final String msg,
                   final Consumer<T> consumer) {
    this.commitment = commitment;
    this.channel = channel;
    this.key = key;
    this.msgId = msgId;
    this.msg = msg;
    this.subId = Long.MIN_VALUE;
    this.consumer = consumer;
  }

  @Override
  public final Channel channel() {
    return channel;
  }

  @Override
  public final Commitment commitment() {
    return commitment;
  }

  @Override
  public final String key() {
    return key;
  }

  @Override
  public PublicKey publicKey() {
    return null;
  }

  @Override
  public final long msgId() {
    return msgId;
  }

  @Override
  public final String msg() {
    return msg;
  }

  @Override
  public final long lastAttempt() {
    return lastAttempt;
  }

  @Override
  public final void setLastAttempt(final long lastAttempt) {
    this.lastAttempt = lastAttempt;
  }

  @Override
  public final long subId() {
    return subId;
  }

  @Override
  public final void setSubId(final long subId) {
    this.subId = subId;
  }

  @Override
  public final void accept(final T t) {
    this.consumer.accept(t);
  }

  @Override
  public final boolean equals(final Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    final var that = (Subscription<?>) o;
    return key.equals(that.key());
  }

  @Override
  public final int hashCode() {
    return key.hashCode();
  }

  @Override
  public final String toString() {
    return "Subscription[" +
        "key=" + key + ", " +
        "id=" + msgId + ", " +
        "msg=" + msg + ", " +
        "lastAttempt=" + lastAttempt + ", " +
        "subId=" + subId + ']';
  }
}
