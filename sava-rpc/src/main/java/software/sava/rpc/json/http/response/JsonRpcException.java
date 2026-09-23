package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.Objects;
import java.util.OptionalLong;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public final class JsonRpcException extends RuntimeException {

  // https://www.jsonrpc.org/specification#error_object
  public static final int INVALID_REQUEST = -32_600;
  public static final int METHOD_NOT_FOUND = -32_601;
  public static final int INVALID_PARAMS = -32_602;
  public static final int INTERNAL_ERROR = -32_603;
  public static final int PARSE_ERROR = -32_700;

  private final OptionalLong retryAfterSeconds;
  private final OptionalLong requestId;
  private final long code;
  private final RpcCustomError customError;

  private JsonRpcException(final long code,
                           final String message,
                           final OptionalLong retryAfterSeconds,
                           final OptionalLong requestId,
                           final RpcCustomError customError) {
    super(message);
    this.code = code;
    this.retryAfterSeconds = Objects.requireNonNullElse(retryAfterSeconds, OptionalLong.empty());
    this.requestId = Objects.requireNonNullElse(requestId, OptionalLong.empty());
    this.customError = customError;
  }

  /// Parses the `error` object `ji` is positioned at, with no request id: the envelope's `id`
  /// is outside that object, so a caller that has it passes it to
  /// [#parseException(JsonIterator, OptionalLong, OptionalLong)].
  public static JsonRpcException parseException(final JsonIterator ji, final OptionalLong retryAfterSeconds) {
    return parseException(ji, retryAfterSeconds, OptionalLong.empty());
  }

  /// Parses the `error` object `ji` is positioned at. `requestId` is the response envelope's
  /// `id` when the caller read it and it was a number — the request this error answers — and
  /// empty otherwise; `retryAfterSeconds` is the HTTP `retry-after` hint, empty over a
  /// websocket. The cursor is left at the end of the error object.
  public static JsonRpcException parseException(final JsonIterator ji,
                                                final OptionalLong retryAfterSeconds,
                                                final OptionalLong requestId) {
    final var parser = new Parser();
    ji.testObject(parser);
    if (parser.dataMark != null) {
      // Member order is free, and `data`'s interpretation depends on `code`: when data arrived
      // first it was parsed against code 0 and valid structured details were misclassified.
      // The mark defers it until the whole error object has been read — and the cursor is
      // restored afterwards, so a caller parsing past the error object continues where the
      // object ended rather than where the deferred data did.
      final int endMark = ji.mark();
      parser.customError = RpcCustomError.parseError(parser.code, ji.reset(parser.dataMark));
      ji.reset(endMark);
    }
    return parser.create(retryAfterSeconds, requestId);
  }

  /// The envelope's `id` — the request being answered — when it is a non-negative integer
  /// literal a long can hold, which are the only ids this client mints. Member order is free,
  /// so it is scanned for from `start`, the offset of the envelope object in `ji`'s buffer; a
  /// nested `id` is never reached, because the scan skips each member's value whole. Best
  /// effort by design: a truncated envelope, an id no long can express, a fraction or exponent
  /// form, a negative id, a string and `"id":null` (a request the server could not read) all
  /// read as empty — and none of them may cost the caller the error object, which the caller
  /// parses next as the JsonRpcException it always was. One reader for both transports, so
  /// the HTTP client and the websocket agree on every shape: over the websocket an id that
  /// used to throw out of the frame handler now reads as uncorrelated, and the rejection it
  /// came with is still classified and dispatched. The cursor is left wherever the scan
  /// stopped; callers reset before reading on.
  public static OptionalLong envelopeRequestId(final JsonIterator ji, final int start) {
    try {
      if (ji.reset(start).skipUntil("id") == null || ji.whatIsNext() != ValueType.NUMBER) {
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

  public long code() {
    return code;
  }

  public OptionalLong retryAfterSeconds() {
    return retryAfterSeconds;
  }

  /// The `id` of the response envelope that carried this error, when it was a non-negative
  /// integer a long can hold — the only ids this client mints — so it names the request being
  /// answered. Over the websocket that is the `msgId` of the subscribe or unsubscribe the
  /// server rejected: a request-defect code (-32600, -32601, -32602) retires that registration,
  /// while any other code leaves it pending for the resend pacing. Empty for an `"id":null`
  /// answer — a request the server could not read at all — for a string, negative, fractional
  /// or out-of-range id, and for an error parsed without its envelope.
  public OptionalLong requestId() {
    return requestId;
  }

  public RpcCustomError customError() {
    return customError;
  }

  private static final class Parser implements FieldBufferPredicate {

    private long code;
    private String message;
    private RpcCustomError customError;
    private Integer dataMark;

    private Parser() {
    }

    private JsonRpcException create(final OptionalLong retryAfterSeconds, final OptionalLong requestId) {
      return new JsonRpcException(
          code,
          message,
          retryAfterSeconds,
          requestId,
          customError == null ? RpcCustomError.parseError(code) : customError
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("code", buf, offset, len)) {
        code = ji.readLong();
      } else if (fieldEquals("message", buf, offset, len)) {
        message = ji.readString();
      } else if (fieldEquals("data", buf, offset, len)) {
        dataMark = ji.mark();
        ji.skip();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
