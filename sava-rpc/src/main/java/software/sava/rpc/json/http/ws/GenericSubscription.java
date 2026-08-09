package software.sava.rpc.json.http.ws;

import systems.comodal.jsoniter.JsonIterator;

import java.util.function.Consumer;
import java.util.function.Function;

/// A caller-defined subscription, registered by notification method rather than a [Channel].
///
/// Identity note: [RootSubscription#equals] is final and sees only (commitment, channel, key),
/// so two generic handles under DIFFERENT notification methods but the same key compare equal.
/// That is inert inside the engine — [SolanaJsonRpcWebsocket]'s generic registry keys by
/// notification method and then key, and every conditional removal compares by reference — but
/// a caller keying their own map by Subscription should key by (notificationMethod, key).
final class GenericSubscription<T> extends RootSubscription<T> {

  private final String unSubscribeMethod;
  private final String notificationMethod;
  private final Function<JsonIterator, T> parser;

  GenericSubscription(final String unSubscribeMethod,
                      final String notificationMethod,
                      final Function<JsonIterator, T> parser,
                      final String key,
                      final long msgId,
                      final String msg,
                      final Consumer<Subscription<T>> onSub,
                      final Consumer<T> consumer) {
    super(null, null, key, msgId, msg, onSub, consumer);
    this.unSubscribeMethod = unSubscribeMethod;
    this.notificationMethod = notificationMethod;
    this.parser = parser;
  }

  @Override
  public String unSubscribeMethod() {
    return unSubscribeMethod;
  }

  String notificationMethod() {
    return notificationMethod;
  }

  void parseAndAccept(final JsonIterator ji) {
    accept(parser.apply(ji));
  }
}
