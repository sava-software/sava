package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.BiFunction;

final class JsonRpcBytesResponseController<R> extends BaseJsonRpcResponseController<byte[], R> {

  private final BiFunction<HttpResponse<byte[]>, JsonIterator, R> parser;

  JsonRpcBytesResponseController(final BiFunction<HttpResponse<byte[]>, JsonIterator, R> parser) {
    this.parser = parser;
  }

  @Override
  protected R parseResponse(final HttpResponse<?> httpResponse, final byte[] body, final JsonIterator ji) {
    return parser.apply(new DisguisedHttpResponse(httpResponse, body), ji);
  }
}
