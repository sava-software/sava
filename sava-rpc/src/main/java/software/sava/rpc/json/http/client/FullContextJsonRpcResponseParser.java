package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;

final class FullContextJsonRpcResponseParser<R> extends BaseGenericJsonRpcResponseParser<R> {

  private final JsonRpcResponseParser<R> parser;

  FullContextJsonRpcResponseParser(final JsonRpcResponseParser<R> parser) {
    this.parser = parser;
  }

  @Override
  public R parseResponse(final HttpResponse<?> httpResponse, final byte[] body, final JsonIterator ji) {
    return parser.parseResponse(httpResponse, body, ji);
  }
}
