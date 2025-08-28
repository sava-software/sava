package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.Function;

import static software.sava.rpc.json.http.client.JsonResponseController.logBody;

abstract class BaseJsonResponseParser<R> implements Function<HttpResponse<?>, R> {

  protected abstract R parseResponse(final HttpResponse<?> httpResponse, final byte[] body, final JsonIterator ji);

  protected abstract JsonIterator checkResponse(final HttpResponse<?> httpResponse, final byte[] body);

  @Override
  public R apply(final HttpResponse<?> httpResponse) {
    final byte[] body = JsonRpcHttpClient.readBody(httpResponse);
    if (body == null) {
      return null;
    }
    final var ji = checkResponse(httpResponse, body);
    try {
      return parseResponse(httpResponse, body, ji);
    } catch (final RuntimeException ex) {
      logBody(httpResponse, new String(body), ex);
      throw ex;
    }
  }
}
