package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.response.Context;
import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.BiFunction;

final class JsonRpcValueResponseParser<R> extends BaseGenericJsonRpcResponseParser<R> {

  private final BiFunction<JsonIterator, Context, R> parser;

  JsonRpcValueResponseParser(final BiFunction<JsonIterator, Context, R> parser) {
    this.parser = parser;
  }

  @Override
  protected R parseResponse(final HttpResponse<?> httpResponse, final byte[] body, final JsonIterator ji) {
    // The Photon compression indexer serves value before context, the Solana RPC context first.
    final int resultMark = ji.mark();
    final Context context;
    if (ji.skipUntil("context") == null) {
      context = null;
      ji.reset(resultMark);
    } else {
      context = Context.parse(ji);
    }
    if (ji.skipUntil("value") == null) {
      ji.reset(resultMark).skipUntil("value");
    }
    return parser.apply(ji, context);
  }
}
