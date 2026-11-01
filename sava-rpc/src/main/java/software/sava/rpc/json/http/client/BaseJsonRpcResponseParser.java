package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.response.HttpResponseException;
import software.sava.rpc.json.http.response.JsonRpcException;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static java.lang.System.Logger.Level.DEBUG;

abstract class BaseJsonRpcResponseParser<R> extends BaseJsonResponseController<R> {

  private static final System.Logger logger = System.getLogger(BaseJsonRpcResponseParser.class.getName());

  /// Whether `body` holds nothing but JSON whitespace, which the parser cannot start on.
  private static boolean isBlank(final byte[] body) {
    for (final byte b : body) {
      if (b != ' ' && b != '\n' && b != '\r' && b != '\t') {
        return false;
      }
    }
    return true;
  }

  /// The gate. A 2xx object with a `result` member passes, its iterator positioned there. An
  /// object's `error` member, reached under a non-2xx status whatever else the object holds and
  /// under a 2xx when there is no result, throws its [JsonRpcException] with the status it
  /// arrived under when it is a readable error object or null, a null or empty one reading as
  /// code 0. Everything else is not a usable JSON-RPC answer and throws the typed
  /// [HttpResponseException] with the status and the body: a null or blank body, before the
  /// parser is opened; a non-2xx without an error member, a body that does not start a JSON
  /// object and an object with neither member, from the classification; and every body the
  /// parser cannot read, an object the scan cannot get through or an `error` member that is
  /// neither null nor a readable error object, with the parser's failure attached as suppressed
  /// and the body logged at DEBUG. A readable error object whose `data` does not parse still
  /// throws its [JsonRpcException], which carries the data's failure as suppressed. Only the
  /// reads sit inside the try, so a failure of any kind out of the parser is typed and the
  /// deliberate throws are not caught.
  @Override
  protected final JsonIterator checkResponse(final HttpResponse<?> httpResponse, final byte[] body) {
    if (body == null || isBlank(body)) {
      throw responseFailure(httpResponse, body == null ? "" : new String(body, StandardCharsets.UTF_8));
    }
    final var ji = JsonIterator.parse(body);
    final int responseCode = httpResponse.statusCode();
    final JsonRpcException rpcException;
    try {
      final boolean isJsonObject = ji.whatIsNext() == ValueType.OBJECT;
      if (responseCode >= 200 && responseCode < 300 && isJsonObject && ji.skipUntil("result") != null) {
        return ji;
      }
      if (isJsonObject && ji.reset(0).skipUntil("error") != null) {
        // A decimal delay in seconds; the HTTP-date form the header also allows is no hint.
        final var retryAfter = HttpResponseException.retryAfterSeconds(httpResponse.headers());
        // Best effort and shared with the websocket (JsonRpcException.envelopeRequestId): an id
        // the reader cannot carry reads as absent and never costs the caller the error object.
        // Measured in review: a 20-digit id and a body truncated under a 503 both used to parse
        // to their JsonRpcException and would have thrown a raw JsonException instead.
        final var requestId = JsonRpcException.envelopeRequestId(ji, 0);
        ji.reset(0).skipUntil("error");
        rpcException = JsonRpcException.parseException(ji, retryAfter, requestId, OptionalInt.of(responseCode));
      } else {
        rpcException = null;
      }
    } catch (final RuntimeException unreadable) {
      // RuntimeException, not JsonException alone, so a failure of any kind out of the parser
      // is typed. A decoder's own kind (an IllegalArgumentException for base64 that is not, an
      // IllegalStateException for base64+zstd return data, which this parse reads with no
      // decompressor) no longer arrives here: it can only come out of an error object's data,
      // and parseException attaches that failure to the JsonRpcException instead.
      final var bodyText = new String(body, StandardCharsets.UTF_8);
      // DEBUG, not ERROR: the exception thrown below carries the status, the body and this
      // failure, so whether the answer is worth an error line is the caller's call. The line is
      // built only when DEBUG is on; the body text is built for the exception either way.
      logger.log(DEBUG, () -> "Not a readable JSON-RPC response: " + bodyText, unreadable);
      final var failure = responseFailure(httpResponse, bodyText);
      failure.addSuppressed(unreadable);
      throw failure;
    }
    if (rpcException != null) {
      throw rpcException;
    }
    throw responseFailure(httpResponse, new String(body, StandardCharsets.UTF_8));
  }
}
