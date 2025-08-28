package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.BiFunction;

abstract class BaseJsonRpcResponseControllerParser<B, R> extends BaseJsonRpcResponseController<B, R> {

  protected final BiFunction<HttpResponse<B>, JsonIterator, R> parser;

  public BaseJsonRpcResponseControllerParser(final BiFunction<HttpResponse<B>, JsonIterator, R> parser) {
    this.parser = parser;
  }

  @Override
  protected final R parseResponse(final HttpResponse<B> httpResponse, final byte[] body, final JsonIterator ji) {
    return parser.apply(httpResponse, ji);
  }
}
