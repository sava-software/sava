package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.io.UncheckedIOException;
import java.net.UnknownServiceException;
import java.net.http.HttpResponse;

import static java.lang.System.Logger.Level.ERROR;

abstract class BaseJsonResponseController<R> {

  private static final System.Logger logger = System.getLogger(BaseJsonResponseController.class.getName());

  protected static RuntimeException throwUncheckedIOException(final HttpResponse<?> httpResponse, final String body) {
    throw new UncheckedIOException(new UnknownServiceException(String.format(
        "HTTP request failed with [httpCode:%d], [body=%s]", httpResponse.statusCode(), body)));
  }

  protected JsonIterator checkResponse(final HttpResponse<?> httpResponse, final byte[] body) {
    final int responseCode = httpResponse.statusCode();
    if (responseCode < 200 || responseCode >= 300) {
      throw throwUncheckedIOException(httpResponse, body == null ? "" : new String(body));
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
          String.format("Failed to parse [httpCode:%d], [body=%s]", httpResponse.statusCode(), body == null ? "" : new String(body)),
          ex
      );
      throw ex;
    }
  }
}
