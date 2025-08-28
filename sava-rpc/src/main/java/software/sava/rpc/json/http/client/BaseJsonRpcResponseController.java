package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.Function;

import static software.sava.rpc.json.http.client.JsonResponseController.logBody;

abstract class BaseJsonRpcResponseController<B, R> implements Function<HttpResponse<B>, R> {

  protected abstract R parseResponse(final HttpResponse<B> httpResponse, final byte[] body, final JsonIterator ji);

  @Override
  public final R apply(final HttpResponse<B> httpResponse) {
    final byte[] body = JsonRpcHttpClient.readBody(httpResponse);
    if (body == null) {
      return null;
    }
    final var ji = JsonRpcHttpClient.checkResponse(httpResponse, body);
    try {
      return parseResponse(httpResponse, body, ji);
    } catch (final RuntimeException ex) {
      logBody(httpResponse, new String(body), ex);
      throw ex;
    }
  }
}
