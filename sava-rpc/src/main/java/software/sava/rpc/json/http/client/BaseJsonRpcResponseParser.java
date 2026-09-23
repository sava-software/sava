package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.response.JsonRpcException;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.net.http.HttpResponse;
import java.util.OptionalLong;

import static java.lang.System.Logger.Level.ERROR;

abstract class BaseJsonRpcResponseParser<R> extends BaseJsonResponseController<R> {

  private static final System.Logger logger = System.getLogger(BaseJsonRpcResponseParser.class.getName());

  private static RuntimeException parseRpcException(final byte[] body,
                                                    final JsonIterator ji,
                                                    final OptionalLong retryAfter,
                                                    final OptionalLong requestId) {
    try {
      return JsonRpcException.parseException(ji, retryAfter, requestId);
    } catch (final RuntimeException ex) {
      logger.log(ERROR, "Failed to parse JSON-RPC exception: " + new String(body), ex);
      throw ex;
    }
  }

  /// The envelope's `id` — the request being answered — when it is a non-negative integer
  /// literal a long can hold, which are the only ids this client mints. Member order is free,
  /// so it is scanned for from the top of the object; a nested `id` is never reached, because
  /// the scan skips each member's value whole. Best effort by design: a truncated envelope, an
  /// id no long can express, a fraction or exponent form, a negative id, a string and
  /// `"id":null` (a request the server could not read) all annotate nothing — and none of them
  /// may cost the caller the error object, which still reaches it as the JsonRpcException it
  /// always was. Measured in review: a 20-digit id and a truncated body under a 503 both used
  /// to parse to their JsonRpcException and would have thrown a raw JsonException instead.
  static OptionalLong envelopeRequestId(final JsonIterator ji) {
    try {
      if (ji.reset(0).skipUntil("id") == null || ji.whatIsNext() != ValueType.NUMBER) {
        return OptionalLong.empty();
      }
      final var literal = ji.readNumberAsString();
      for (int i = 0; i < literal.length(); ++i) {
        final char c = literal.charAt(i);
        if (c < '0' || c > '9') {
          return OptionalLong.empty();
        }
      }
      return OptionalLong.of(Long.parseLong(literal));
    } catch (final RuntimeException unreadable) {
      return OptionalLong.empty();
    }
  }

  @Override
  protected final JsonIterator checkResponse(final HttpResponse<?> httpResponse, final byte[] body) {
    final var ji = JsonIterator.parse(body);
    final int responseCode = httpResponse.statusCode();
    final boolean isJsonObject = ji.whatIsNext() == ValueType.OBJECT;
    if (responseCode < 200 || responseCode >= 300 || !isJsonObject || ji.skipUntil("result") == null) {
      if (!isJsonObject) {
        throw throwUncheckedIOException(httpResponse, new String(body));
      } else if (ji.reset(0).skipUntil("error") == null) {
        throw throwUncheckedIOException(httpResponse, new String(body));
      } else {
        final var retryAfter = httpResponse.headers().firstValueAsLong("retry-after");
        final var requestId = envelopeRequestId(ji);
        ji.reset(0).skipUntil("error");
        throw parseRpcException(body, ji, retryAfter, requestId);
      }
    } else {
      return ji;
    }
  }
}
