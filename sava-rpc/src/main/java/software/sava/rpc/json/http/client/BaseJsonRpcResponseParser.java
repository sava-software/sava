package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.response.HttpResponseException;
import software.sava.rpc.json.http.response.JsonRpcException;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.net.http.HttpResponse;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static java.lang.System.Logger.Level.ERROR;

abstract class BaseJsonRpcResponseParser<R> extends BaseJsonResponseController<R> {

  private static final System.Logger logger = System.getLogger(BaseJsonRpcResponseParser.class.getName());

  private static RuntimeException parseRpcException(final byte[] body,
                                                    final JsonIterator ji,
                                                    final OptionalLong retryAfter,
                                                    final OptionalLong requestId,
                                                    final int httpStatus) {
    try {
      return JsonRpcException.parseException(ji, retryAfter, requestId, OptionalInt.of(httpStatus));
    } catch (final RuntimeException ex) {
      logger.log(ERROR, "Failed to parse JSON-RPC exception: " + new String(body), ex);
      throw ex;
    }
  }

  @Override
  protected final JsonIterator checkResponse(final HttpResponse<?> httpResponse, final byte[] body) {
    final var ji = JsonIterator.parse(body);
    final int responseCode = httpResponse.statusCode();
    final boolean isJsonObject = ji.whatIsNext() == ValueType.OBJECT;
    if (responseCode < 200 || responseCode >= 300 || !isJsonObject || ji.skipUntil("result") == null) {
      if (!isJsonObject) {
        throw responseFailure(httpResponse, new String(body));
      } else if (ji.reset(0).skipUntil("error") == null) {
        throw responseFailure(httpResponse, new String(body));
      } else {
        // A decimal delay in seconds as before; the HTTP-date form the header also allows, which
        // firstValueAsLong threw a NumberFormatException on in place of the server's error, is
        // no hint.
        final var retryAfter = HttpResponseException.retryAfterSeconds(httpResponse.headers());
        // Best effort and shared with the websocket (JsonRpcException.envelopeRequestId): an id
        // the reader cannot carry reads as absent and never costs the caller the error object.
        // Measured in review: a 20-digit id and a body truncated under a 503 both used to parse
        // to their JsonRpcException and would have thrown a raw JsonException instead.
        final var requestId = JsonRpcException.envelopeRequestId(ji, 0);
        ji.reset(0).skipUntil("error");
        throw parseRpcException(body, ji, retryAfter, requestId, responseCode);
      }
    } else {
      return ji;
    }
  }
}
