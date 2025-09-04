package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.Function;

final class GenericJsonResponseParser<R> extends BaseJsonResponseController<R> implements Function<HttpResponse<?>, R> {

  private final Function<JsonIterator, R> parser;

  GenericJsonResponseParser(final Function<JsonIterator, R> parser) {
    this.parser = parser;
  }

  @Override
  public R apply(final HttpResponse<?> httpResponse) {
    return super.applyResponse(httpResponse);
  }

  @Override
  protected R parseResponse(final HttpResponse<?> httpResponse, final byte[] body, final JsonIterator ji) {
    return parser.apply(ji);
  }
}
