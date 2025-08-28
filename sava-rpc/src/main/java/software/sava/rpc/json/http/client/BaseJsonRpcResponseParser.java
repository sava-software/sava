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
                                                    final OptionalLong retryAfter) {
    try {
      return JsonRpcException.parseException(ji, retryAfter);
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
        throw throwUncheckedIOException(httpResponse, new String(body));
      } else if (ji.reset(0).skipUntil("error") == null) {
        throw throwUncheckedIOException(httpResponse, new String(body));
      } else {
        final var retryAfter = httpResponse.headers().firstValueAsLong("retry-after");
        throw parseRpcException(body, ji, retryAfter);
      }
    } else {
      return ji;
    }
  }
}
