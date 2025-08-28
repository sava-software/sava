package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.Function;

final class JsonBytesResponseController<B, R> extends JsonResponseController<B, R> {

  private final Function<JsonIterator, R> parser;

  JsonBytesResponseController(final Function<JsonIterator, R> parser) {
    this.parser = parser;
  }

  @Override
  protected R parseResponse(final HttpResponse<?> httpResponse, final byte[] body, final JsonIterator ji) {
    return parser.apply(ji);
  }
}
