package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.response.JsonRpcException;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;
import software.sava.rpc.json.http.response.Context;

import java.io.UncheckedIOException;
import java.net.UnknownServiceException;
import java.nio.charset.StandardCharsets;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/// The JSON-RPC envelope gate. Every RPC call funnels through
/// `BaseJsonRpcResponseParser.checkResponse`, which decides — from an untrusted
/// body and a status code — whether a response is a result, a protocol error, or
/// garbage. A wrong verdict either throws away a good response or hands a parser
/// something that is not a result.
final class JsonRpcResponseParserTests {

  private static byte[] json(final String body) {
    return body.getBytes(StandardCharsets.UTF_8);
  }

  private static String parseResult(final int statusCode, final String body, final String... headers) {
    final Function<JsonIterator, String> parser = JsonIterator::readString;
    final var controller = new JsonRpcResultResponseParser<>(parser);
    return controller.apply(StubHttpResponse.of(statusCode, json(body), headers));
  }

  private static String parseResult(final String body) {
    return parseResult(200, body);
  }

  @Test
  void resultIsHandedToTheParser() {
    assertEquals("ok", parseResult("{\"jsonrpc\":\"2.0\",\"result\":\"ok\",\"id\":1}"));
  }

  /// `result` is found by scanning, so it need not be the first field.
  @Test
  void resultIsFoundRegardlessOfFieldOrder() {
    assertEquals("ok", parseResult("{\"id\":1,\"jsonrpc\":\"2.0\",\"result\":\"ok\"}"));
    assertEquals("ok", parseResult("{\"result\":\"ok\",\"id\":1}"));
  }

  @Test
  void jsonRpcErrorsBecomeJsonRpcException() {
    final var ex = assertThrows(JsonRpcException.class, () -> parseResult(
        200, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"Invalid params\"},\"id\":1}"));
    assertEquals(-32602, ex.code());
    assertTrue(ex.getMessage().contains("Invalid params"), ex.getMessage());
  }

  /// A node may return an error envelope under a non-2xx status. The error object
  /// is the more specific signal, so it wins over the status code.
  @Test
  void errorEnvelopeUnderANonSuccessStatusStillParses() {
    for (final int status : new int[]{400, 429, 500, 503}) {
      final var ex = assertThrows(JsonRpcException.class, () -> parseResult(
          status, "{\"error\":{\"code\":-32005,\"message\":\"Node is behind\"},\"id\":1}"));
      assertEquals(-32005, ex.code(), "status " + status);
    }
  }

  /// Rate limiting: the retry-after header is lifted onto the exception so a
  /// caller can back off for the interval the node asked for.
  @Test
  void retryAfterHeaderIsCarriedOntoTheException() {
    final var ex = assertThrows(JsonRpcException.class, () -> parseResult(
        429,
        "{\"error\":{\"code\":-32429,\"message\":\"Too many requests\"},\"id\":1}",
        "retry-after", "30"));
    assertTrue(ex.retryAfterSeconds().isPresent(), "retry-after should be present");
    assertEquals(30, ex.retryAfterSeconds().getAsLong());
  }

  @Test
  void missingRetryAfterLeavesTheHintEmpty() {
    final var ex = assertThrows(JsonRpcException.class, () -> parseResult(
        429, "{\"error\":{\"code\":-32429,\"message\":\"Too many requests\"},\"id\":1}"));
    assertTrue(ex.retryAfterSeconds().isEmpty());
  }

  /// A JSON object carrying neither result nor error is not a JSON-RPC response.
  @Test
  void objectWithNeitherResultNorErrorIsRejected() {
    final var ex = assertThrows(UncheckedIOException.class,
        () -> parseResult("{\"jsonrpc\":\"2.0\",\"id\":1}"));
    assertInstanceOf(UnknownServiceException.class, ex.getCause());
    assertTrue(ex.getMessage().contains("httpCode:200"), ex.getMessage());
  }

  /// The realistic hostile case: a proxy or load balancer answering with an HTML
  /// error page instead of JSON. It must fail as a transport error rather than
  /// being fed to a result parser.
  @Test
  void nonObjectBodiesAreRejectedAsTransportErrors() {
    for (final String body : new String[]{
        "<html><body>502 Bad Gateway</body></html>",
        "[1,2,3]",
        "\"just a string\"",
        "42",
        "null",
        "not json at all"}) {
      final var ex = assertThrows(UncheckedIOException.class, () -> parseResult(502, body), body);
      assertInstanceOf(UnknownServiceException.class, ex.getCause(), body);
    }
  }

  /// The failure message embeds the body so the caller can see what arrived.
  @Test
  void rejectionMessageIncludesStatusAndBody() {
    final var ex = assertThrows(UncheckedIOException.class,
        () -> parseResult(503, "<html>upstream down</html>"));
    assertTrue(ex.getMessage().contains("httpCode:503"), ex.getMessage());
    assertTrue(ex.getMessage().contains("upstream down"), ex.getMessage());
  }

  /// A success status with a result is the only path that returns.
  @Test
  void successStatusesAllParse() {
    for (final int status : new int[]{200, 201, 299}) {
      assertEquals("ok", parseResult(status, "{\"result\":\"ok\"}"), "status " + status);
    }
  }

  /// A 2xx body that is a bare `result: null` still counts as a result — the node
  /// answered, the value is simply absent (getAccountInfo for a missing account).
  @Test
  void nullResultIsAResultNotAnError() {
    final Function<JsonIterator, String> parser = ji -> ji.whatIsNext() == ValueType.NULL
        ? "absent"
        : ji.readString();
    final var controller = new JsonRpcResultResponseParser<>(parser);
    assertEquals("absent", controller.apply(StubHttpResponse.of(200, json("{\"result\":null,\"id\":1}"))));
  }

  /// The value parser unwraps the `{"context":..,"value":..}` envelope the
  /// context-carrying methods return.
  @Test
  void valueParserSplitsContextFromValue() {
    final BiFunction<JsonIterator, Context, String> parser =
        (ji, context) -> context.slot() + ":" + ji.readString();
    final var controller = new JsonRpcValueResponseParser<>(parser);
    final var result = controller.apply(StubHttpResponse.of(200, json("""
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"2.0.5","slot":1234},"value":"payload"},"id":1}""")));
    assertEquals("1234:payload", result);
  }

  @Test
  void valueParserPropagatesErrorEnvelopes() {
    final BiFunction<JsonIterator, Context, String> parser =
        (ji, _) -> ji.readString();
    final var controller = new JsonRpcValueResponseParser<>(parser);
    final var ex = assertThrows(JsonRpcException.class, () -> controller.apply(
        StubHttpResponse.of(200, json("{\"error\":{\"code\":-32002,\"message\":\"blocked\"},\"id\":1}"))));
    assertEquals(-32002, ex.code());
  }
}
