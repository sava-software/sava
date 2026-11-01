package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.response.HttpResponseException;
import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static java.lang.System.Logger.Level.ERROR;

abstract class BaseJsonResponseController<R> {

  private static final System.Logger logger = System.getLogger(BaseJsonResponseController.class.getName());

  /// The typed failure of a response that is not a usable answer, carrying its status, body and
  /// retry-after hint; thrown by the caller, so that the compiler sees the throw.
  protected static HttpResponseException responseFailure(final HttpResponse<?> httpResponse, final String body) {
    return HttpResponseException.of(httpResponse, body);
  }

  protected JsonIterator checkResponse(final HttpResponse<?> httpResponse, final byte[] body) {
    final int responseCode = httpResponse.statusCode();
    if (responseCode < 200 || responseCode >= 300) {
      throw responseFailure(httpResponse, body == null ? "" : new String(body, StandardCharsets.UTF_8));
    } else if (body == null) {
      return null;
    } else {
      return JsonIterator.parse(body);
    }
  }

  protected abstract R parseResponse(final HttpResponse<?> httpResponse, final byte[] body, final JsonIterator ji);

  public final R applyResponse(final HttpResponse<?> httpResponse) {
    final byte[] body = JsonHttpClient.readBody(httpResponse);
    final var ji = checkResponse(httpResponse, body);
    if (ji == null) {
      return null;
    }
    try {
      return parseResponse(httpResponse, body, ji);
    } catch (final RuntimeException ex) {
      logger.log(ERROR,
          String.format("Failed to parse [httpCode:%d], [body=%s]", httpResponse.statusCode(), body == null ? "" : new String(body, StandardCharsets.UTF_8)),
          ex
      );
      throw ex;
    }
  }
}
