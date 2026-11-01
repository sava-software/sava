package software.sava.rpc.json.http.response;

import java.io.UncheckedIOException;
import java.net.UnknownServiceException;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.OptionalLong;

/// An HTTP response a sava-rpc client could not use: on either response controller a status
/// outside 2xx, once its body has been read, and on the JSON-RPC one a response, whatever its
/// status, whose body does not start a JSON object (a gateway's error page, a captive portal,
/// plain text, nothing at all) or whose object holds neither `result` nor `error`. The status,
/// the body text and the `retry-after` hint, when the server gave one in seconds, are fields,
/// so a caller tells a 401 from a 429 from a 502 without reading the message. A JSON-RPC error
/// object the server did answer with is a [JsonRpcException] instead, which carries the status
/// it arrived under in [JsonRpcException#httpStatus()]; under a 2xx an object's `result` member
/// answers even when an `error` member sits beside it, and under any other status the `error`
/// member answers even beside a `result`. A body the JSON-RPC gate's parser cannot read is
/// typed here too: an empty, blank or missing body, before the parser is opened; and, with the
/// parser's failure attached as suppressed, an object the scan cannot get through and, once
/// the gate has taken the error path, an `error` member that is neither null nor a readable
/// error object, that is a value that is not an object, as some gateways answer a 401 or a
/// 429, or an object whose `code` is not an integer a long holds or whose `message` is neither
/// a string nor null. A readable error object whose `data` does not parse, a shape that does not
/// fit its code or a value a decoder refuses, is still the [JsonRpcException] the server
/// answered with, its custom error the one the code alone names and the data's failure attached
/// to it as suppressed. An `"error": null` or an empty error object reads as a
/// [JsonRpcException] with code 0. Two failures escape untyped and without a status, because
/// they happen outside the gate: a body the client cannot read, one labelled gzip that is not
/// gzip among them, is an [UncheckedIOException] over the reading failure, thrown before any
/// status is consulted; and a 2xx whose `result` the call's own parser cannot read is that
/// parser's exception.
///
/// Built as this failure was built before it had a type of its own: an `UncheckedIOException`
/// over an `UnknownServiceException` carrying the message, so a catch of `UncheckedIOException`
/// still catches it, `getCause()` is still the `UnknownServiceException`, and `getMessage()` is
/// still that cause's `toString()`; only the class name in this exception's own `toString()` is
/// new. Serializable, as that pair was.
public final class HttpResponseException extends UncheckedIOException {

  private static final long NO_RETRY_AFTER = -1;

  private final int statusCode;
  private final String body;
  // A primitive rather than an OptionalLong field, which is not serializable.
  private final long retryAfterSeconds;

  private HttpResponseException(final String message,
                                final int statusCode,
                                final String body,
                                final OptionalLong retryAfterSeconds) {
    super(new UnknownServiceException(message));
    this.statusCode = statusCode;
    this.body = body;
    this.retryAfterSeconds = retryAfterSeconds.orElse(NO_RETRY_AFTER);
  }

  /// The failure of `httpResponse`, whose body text is `body`; a null body reads as empty.
  public static HttpResponseException of(final HttpResponse<?> httpResponse, final String body) {
    return of(httpResponse.statusCode(), body, httpResponse.headers());
  }

  /// The failure of a response that arrived with `statusCode` and the body text `body`, its
  /// `retry-after` hint read from `headers` as [#retryAfterSeconds(HttpHeaders)] reads it; a
  /// null body reads as empty and null headers as no hint. [#of(HttpResponse, String)] is this
  /// over the response's own status and headers; this form is for a caller that holds the
  /// parts rather than the response, such as a test that wants this type without faking an
  /// `HttpResponse`.
  public static HttpResponseException of(final int statusCode, final String body, final HttpHeaders headers) {
    final var bodyText = Objects.requireNonNullElse(body, "");
    return new HttpResponseException(
        String.format("HTTP request failed with [httpCode:%d], [body=%s]", statusCode, bodyText),
        statusCode,
        bodyText,
        headers == null ? OptionalLong.empty() : retryAfterSeconds(headers)
    );
  }

  /// The first `retry-after` header as a delay in seconds, when the server sent one in that
  /// form: a decimal integer, as `Long.parseLong` reads it (the header's values arrive trimmed).
  /// The header also allows an HTTP-date, which reads as no hint rather than as a number format
  /// failure thrown in place of the response's own, and so does a negative value.
  public static OptionalLong retryAfterSeconds(final HttpHeaders headers) {
    final var value = headers.firstValue("retry-after");
    if (value.isEmpty()) {
      return OptionalLong.empty();
    }
    try {
      final long seconds = Long.parseLong(value.get());
      return seconds < 0 ? OptionalLong.empty() : OptionalLong.of(seconds);
    } catch (final NumberFormatException e) {
      return OptionalLong.empty();
    }
  }

  /// The response's HTTP status code.
  public int statusCode() {
    return statusCode;
  }

  /// The response body as text, empty when there was none.
  public String body() {
    return body;
  }

  /// The `retry-after` hint in seconds, when the server sent one in that form.
  public OptionalLong retryAfterSeconds() {
    return retryAfterSeconds == NO_RETRY_AFTER ? OptionalLong.empty() : OptionalLong.of(retryAfterSeconds);
  }
}
