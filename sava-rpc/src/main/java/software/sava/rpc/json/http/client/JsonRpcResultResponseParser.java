package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.Function;

final class JsonRpcResultResponseParser<R> extends BaseGenericJsonRpcResponseParser<R> {

  private final Function<JsonIterator, R> parser;

  JsonRpcResultResponseParser(final Function<JsonIterator, R> parser) {
    this.parser = parser;
  }

  @Override
  protected R parseResponse(final HttpResponse<?> httpResponse, final byte[] body, final JsonIterator ji) {
    return parser.apply(ji);
  }
}
