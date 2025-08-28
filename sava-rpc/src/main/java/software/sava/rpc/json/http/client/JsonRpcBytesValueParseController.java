package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.response.Context;
import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.BiFunction;
import java.util.function.Function;

final class JsonRpcBytesValueParseController<R> extends BaseJsonRpcResponseParser<R> implements Function<HttpResponse<byte[]>, R> {

  private final BiFunction<JsonIterator, Context, R> parser;

  JsonRpcBytesValueParseController(final BiFunction<JsonIterator, Context, R> parser) {
    this.parser = parser;
  }

  @Override
  protected R parseResponse(final HttpResponse<?> httpResponse, final byte[] body, final JsonIterator ji) {
    ji.skipUntil("context");
    final var context = Context.parse(ji);
    ji.skipUntil("value");
    return parser.apply(ji, context);
  }

  @Override
  public R apply(final HttpResponse<byte[]> httpResponse) {
    return super.applyResponse(httpResponse);
  }
}
