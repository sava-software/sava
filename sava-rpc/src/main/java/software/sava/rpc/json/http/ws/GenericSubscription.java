package software.sava.rpc.json.http.ws;

import systems.comodal.jsoniter.JsonIterator;

import java.util.function.Consumer;
import java.util.function.Function;

/// A caller-defined subscription, registered by notification method rather than a [Channel].
///
/// Identity includes [#notificationMethod()] — the namespace a generic key is unique within —
/// so two handles sharing a key across different notification methods are distinct, in a
/// consumer's collections as much as in the engine's registries. [RootSubscription#equals] is
/// final and compares through the interface accessor, which this class overrides.
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

  @Override
  public String notificationMethod() {
    return notificationMethod;
  }

  void parseAndAccept(final JsonIterator ji) {
    accept(parser.apply(ji));
  }
}
