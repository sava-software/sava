package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.io.UncheckedIOException;
import java.net.UnknownServiceException;
import java.net.http.HttpResponse;
import java.util.function.Function;

import static java.lang.System.Logger.Level.ERROR;

public record JsonResponseController<R>(Function<JsonIterator, R> parser) implements Function<HttpResponse<byte[]>, R> {

  public static final System.Logger log = System.getLogger(JsonResponseController.class.getName());

  static void logBody(final HttpResponse<byte[]> httpResponse, final RuntimeException ex) {
    logBody(httpResponse, new String(httpResponse.body()), ex);
  }

  static void logBody(final HttpResponse<?> httpResponse, final String body, final RuntimeException ex) {
    log.log(ERROR,
        String.format("Failed to parse [httpCode:%d], [body=%s]", httpResponse.statusCode(), body),
        ex
    );
  }

  static RuntimeException throwUncheckedIOException(final HttpResponse<?> httpResponse, final String body) {
    throw new UncheckedIOException(new UnknownServiceException(String.format(
        "HTTP request failed with [httpCode:%d], [body=%s]", httpResponse.statusCode(), body)));
  }

  public static void checkResponseCode(final HttpResponse<byte[]> httpResponse) {
    final int responseCode = httpResponse.statusCode();
    if (responseCode < 200 || responseCode >= 300) {
      throw throwUncheckedIOException(httpResponse, new String(httpResponse.body()));
    }
  }

  @Override
  public R apply(final HttpResponse<byte[]> httpResponse) {
    checkResponseCode(httpResponse);
    try {
      final var ji = JsonIterator.parse(httpResponse.body());
      return parser.apply(ji);
    } catch (final RuntimeException ex) {
      logBody(httpResponse, ex);
      throw ex;
    }
  }
}
