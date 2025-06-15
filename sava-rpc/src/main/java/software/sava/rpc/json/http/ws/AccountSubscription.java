package software.sava.rpc.json.http.ws;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;

import java.util.function.Consumer;

final class AccountSubscription<T> extends RootSubscription<T> {

  private final PublicKey publicKey;

  AccountSubscription(final Commitment commitment,
                      final Channel channel,
                      final PublicKey publicKey,
                      final long msgId,
                      final String msg,
                      final Consumer<Subscription<T>> onSub,
                      final Consumer<T> consumer) {
    super(commitment, channel, publicKey.toBase58(), msgId, msg, onSub, consumer);
    this.publicKey = publicKey;
  }

  @Override
  public PublicKey publicKey() {
    return publicKey;
  }
}
