package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.Function;

import static software.sava.rpc.json.http.client.JsonResponseController.throwUncheckedIOException;

final class JsonRestRpcResponseParser<R> extends BaseJsonResponseParser<R> {

  private final Function<JsonIterator, R> parser;

  JsonRestRpcResponseParser(final Function<JsonIterator, R> parser) {
    this.parser = parser;
  }

  @Override
  protected R parseResponse(final HttpResponse<?> httpResponse, final byte[] body, final JsonIterator ji) {
    return parser.apply(ji);
  }

  @Override
  protected JsonIterator checkResponse(final HttpResponse<?> httpResponse, final byte[] body) {
    final int responseCode = httpResponse.statusCode();
    if (responseCode < 200 || responseCode >= 300) {
      throw throwUncheckedIOException(httpResponse, new String(body));
    }
    return JsonIterator.parse(body);
  }
}
