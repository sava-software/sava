package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.response.Context;
import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.BiFunction;

abstract class BaseJsonRpcValueParseController<B, R> extends BaseJsonRpcResponseController<B, R> {

  protected final BiFunction<JsonIterator, Context, R> parser;

  protected BaseJsonRpcValueParseController(final BiFunction<JsonIterator, Context, R> parser) {
    this.parser = parser;
  }

  @Override
  protected final R parseResponse(final HttpResponse<B> httpResponse, final byte[] body, final JsonIterator ji) {
    ji.skipUntil("context");
    final var context = Context.parse(ji);
    ji.skipUntil("value");
    return parser.apply(ji, context);
  }
}
