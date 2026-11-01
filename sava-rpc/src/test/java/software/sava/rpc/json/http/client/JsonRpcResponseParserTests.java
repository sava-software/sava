package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.response.JsonRpcException;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;
import software.sava.rpc.json.http.response.Context;
import software.sava.rpc.json.http.response.HttpResponseException;

import java.io.UncheckedIOException;
import java.net.UnknownServiceException;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;

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

  @Test
  void genericResultParserFactoryReturnsAWorkingController() {
    final var controller = JsonRpcHttpClient.applyGenericResponseResult(JsonIterator::readString);

    assertNotNull(controller);
    assertEquals("factory-result", controller.apply(StubHttpResponse.of(
        200, json("{\"jsonrpc\":\"2.0\",\"result\":\"factory-result\",\"id\":1}"))));
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
    assertEquals(OptionalLong.of(1), ex.requestId(), "the envelope's id is the request being answered");
  }

  /// The envelope's id is scanned for from the top, so it is carried whether it leads or
  /// trails the error object; only a numeric one is carried, and "id":null — the answer to a
  /// request the server could not read — leaves it empty rather than inventing a request.
  @Test
  void theRequestIdFollowsTheEnvelopeWhateverTheMemberOrder() {
    final var leading = assertThrows(JsonRpcException.class, () -> parseResult(
        200, "{\"id\":7,\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}"));
    assertEquals(OptionalLong.of(7), leading.requestId());

    final var unreadable = assertThrows(JsonRpcException.class, () -> parseResult(
        200, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32700,\"message\":\"Parse error\"},\"id\":null}"));
    assertTrue(unreadable.requestId().isEmpty());

    final var stringId = assertThrows(JsonRpcException.class, () -> parseResult(
        200, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"Invalid params\"},\"id\":\"abc\"}"));
    assertTrue(stringId.requestId().isEmpty(), "only a numeric id is carried");

    final var zero = assertThrows(JsonRpcException.class, () -> parseResult(
        200, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"Invalid params\"},\"id\":0}"));
    assertEquals(OptionalLong.of(0), zero.requestId(), "zero is a legal id, not an absent one");

    final var noId = assertThrows(JsonRpcException.class, () -> parseResult(
        200, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}"));
    assertEquals(-32601, noId.code());
    assertTrue(noId.requestId().isEmpty(), "an envelope with no id names no request");

    final var nestedOnly = assertThrows(JsonRpcException.class, () -> parseResult(
        200, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"x\",\"data\":{\"id\":99}}}"));
    assertTrue(nestedOnly.requestId().isEmpty(), "an id nested inside the error is not the envelope's");
  }

  /// Reading the id is best effort and must never cost the caller the error object: an id the
  /// scan cannot carry — one no long can hold, a fraction, a negative (sava mints none), or a
  /// body truncated after the error object — still yields the JsonRpcException the `error`
  /// member describes, with an empty id, exactly what the same bodies yielded before the id
  /// was read at all. Digits 0 and 9 both appear in the carried id, so the literal check is
  /// pinned at both ends of its range.
  @Test
  void anIdTheScanCannotCarryStillYieldsTheProtocolError() {
    final var tooLarge = assertThrows(JsonRpcException.class, () -> parseResult(
        200, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32005,\"message\":\"Node is behind\"},\"id\":18446744073709551615}"));
    assertEquals(-32005, tooLarge.code());
    assertTrue(tooLarge.requestId().isEmpty(), "an id no long can hold annotates nothing");

    final var fraction = assertThrows(JsonRpcException.class, () -> parseResult(
        200, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"x\"},\"id\":1.5}"));
    assertTrue(fraction.requestId().isEmpty(), "a fractional id is not read as 1");

    final var exponent = assertThrows(JsonRpcException.class, () -> parseResult(
        200, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"x\"},\"id\":1e3}"));
    assertTrue(exponent.requestId().isEmpty(), "an exponent form is not one sava mints");

    final var negative = assertThrows(JsonRpcException.class, () -> parseResult(
        200, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"x\"},\"id\":-5}"));
    assertTrue(negative.requestId().isEmpty(), "a negative id answers nothing sava sent");

    final var carried = assertThrows(JsonRpcException.class, () -> parseResult(
        200, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"x\"},\"id\":9034}"));
    assertEquals(OptionalLong.of(9034), carried.requestId());

    final var truncated = assertThrows(JsonRpcException.class, () -> parseResult(
        503, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,\"message\":\"boom\"}"));
    assertEquals(-32000, truncated.code(), "a body cut off after the error object still delivers the error");
    assertTrue(truncated.requestId().isEmpty());
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
    assertEquals(0, ex.getSuppressed().length, "classified by the gate, not failed in the parser");
    assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1}", assertInstanceOf(HttpResponseException.class, ex).body());
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
      assertEquals(0, ex.getSuppressed().length, "classified by the gate, not failed in the parser: " + body);
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

  /// The mirror of [#errorEnvelopeUnderANonSuccessStatusStillParses]: a `result`
  /// envelope does **not** win over a failing status. A proxy answering 5xx with a
  /// cached or synthesized success body is not a node answering, so the status
  /// vetoes the body and the call fails as a transport error rather than handing
  /// the parser a result nobody vouched for.
  @Test
  void resultEnvelopeUnderANonSuccessStatusIsRejected() {
    // 199 is constructible here even though the JDK client never surfaces one:
    // the gate's contract is over any HttpResponse, not only those the default
    // transport can produce
    for (final int status : new int[]{199, 300, 400, 500, 503}) {
      final var ex = assertThrows(UncheckedIOException.class,
          () -> parseResult(status, "{\"result\":\"ok\",\"id\":1}"), "status " + status);
      assertInstanceOf(UnknownServiceException.class, ex.getCause(), "status " + status);
      assertTrue(ex.getMessage().contains("httpCode:" + status), ex.getMessage());
      assertEquals(0, ex.getSuppressed().length, "classified by the gate, not failed in the parser: " + status);
      assertEquals("{\"result\":\"ok\",\"id\":1}", assertInstanceOf(HttpResponseException.class, ex).body());
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

  /// A missing `value` is distinct from `value: null`: the response parser is
  /// invoked at the exhausted result-object cursor so a value parser can choose
  /// its own absent-value default without resetting to the internal sentinel.
  @Test
  void valueParserReceivesTheExhaustedCursorWhenValueIsAbsent() {
    final BiFunction<JsonIterator, Context, String> parser = (ji, context) -> {
      assertEquals(2468, context.slot());
      assertTrue(ji.mark() >= 0, "the absent-value path must not reset to the -1 sentinel");
      assertEquals(ValueType.INVALID, ji.whatIsNext(), "the result object should already be exhausted");
      return "absent";
    };
    final var controller = new JsonRpcValueResponseParser<>(parser);

    assertEquals("absent", controller.apply(StubHttpResponse.of(200, json("""
        {"jsonrpc":"2.0","result":{"context":{"slot":2468}},"id":1}"""))));
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

  /// The `HttpResponse<byte[]>`-typed sibling of [JsonRpcValueResponseParser]:
  /// same context/value unwrapping, same gate.
  @Test
  void bytesValueParserSplitsContextFromValue() {
    final BiFunction<JsonIterator, Context, String> parser =
        (ji, context) -> context.slot() + ":" + ji.readString();
    final var controller = new JsonRpcBytesValueParseController<>(parser);
    final var result = controller.apply(StubHttpResponse.of(200, json("""
        {"jsonrpc":"2.0","result":{"context":{"apiVersion":"2.0.5","slot":4321},"value":"payload"},"id":1}""")));
    assertEquals("4321:payload", result);
  }

  @Test
  void bytesValueParserPropagatesErrorEnvelopes() {
    final BiFunction<JsonIterator, Context, String> parser = (ji, _) -> ji.readString();
    final var controller = new JsonRpcBytesValueParseController<>(parser);
    final var ex = assertThrows(JsonRpcException.class, () -> controller.apply(
        StubHttpResponse.of(200, json("""
            {"error":{"code":-32004,"message":"not available"},"id":1}"""))));
    assertEquals(-32004, ex.code());
  }

  /// The full-context variant hands the parser everything the gate saw — the
  /// response, the raw body, and the iterator already positioned at `result`.
  @Test
  void fullContextParserReceivesTheResponseBodyAndIterator() {
    final byte[] body = json("""
        {"jsonrpc":"2.0","result":"ok","id":1}""");
    final var stub = StubHttpResponse.of(200, body, "retry-after", "17");
    final var controller = JsonRpcHttpClient.applyGenericResponseResult(
        (httpResponse, parsedBody, ji) -> {
          assertSame(stub, httpResponse, "the original response must be handed through");
          assertSame(body, parsedBody, "the raw body must be handed through");
          return httpResponse.headers().firstValue("retry-after").orElseThrow() + ":" + ji.readString();
        });
    assertEquals("17:ok", controller.apply(stub));
  }

  /// An error envelope whose `error` value is not an object is not a parsed protocol error and
  /// not a usable answer: it is typed with its status and body, the parser's own failure
  /// attached as suppressed, and the body is still logged, so that a provider's shape can be
  /// traced whether or not the caller keeps the exception. The log is at DEBUG, which the JUL
  /// backend records as `FINE`: the exception carries everything the line does, and an ERROR
  /// line would repeat what the caller logs from it.
  @Test
  void malformedErrorEnvelopeIsTypedWithItsStatusAndLogged() {
    final var thrown = new HttpResponseException[1];
    final var records = TestLogs.capture(BaseJsonRpcResponseParser.class, () -> {
      final var ex = assertThrows(HttpResponseException.class,
          () -> parseResult(429, """
              {"jsonrpc":"2.0","error":"boom","id":1}"""));
      assertEquals(429, ex.statusCode());
      assertTrue(ex.body().contains("boom"), ex.body());
      assertEquals(1, ex.getSuppressed().length, "the parser's own failure is attached");
      assertInstanceOf(systems.comodal.jsoniter.JsonException.class, ex.getSuppressed()[0]);
      thrown[0] = ex;
    });
    assertTrue(records.stream().anyMatch(record ->
            record.getThrown() == thrown[0].getSuppressed()[0]
                && record.getMessage().contains("boom")
                && Level.FINE.equals(record.getLevel())),
        "the malformed envelope must be logged at DEBUG with its body: "
            + records.stream().map(record -> record.getLevel() + " " + record.getMessage()).toList());
  }

  /// Every body the gate cannot read as a JSON-RPC answer is typed with its status, so that a
  /// rate limiter's or a gateway's answer, whatever its shape, reaches the caller as a 429 or a
  /// 503 and not as the JSON parser's complaint with no status: an empty, blank or missing body;
  /// an object the scan cannot get through; an error member that is not a readable error
  /// object, whether a string, an object whose code is not an integer a long holds, whose
  /// message is neither a string nor null, or whose data does not fit its code; and an error
  /// object whose data a decoder refuses, which throws its own kind rather than the parser's.
  /// Every shape is checked, so a failure names each one still unreadable.
  @Test
  void everyUnreadableBodyIsTypedWithItsStatus() {
    assertAll(
        () -> {
          final var missing = assertThrows(HttpResponseException.class,
              () -> new JsonRpcResultResponseParser<>(JsonIterator::readString).apply(StubHttpResponse.of(502, (byte[]) null)));
          assertEquals(502, missing.statusCode());
          assertEquals("", missing.body());
          assertEquals(0, missing.getSuppressed().length, "nothing was parsed");
        },
        () -> {
          final var empty = assertThrows(HttpResponseException.class, () -> parseResult(429, "", "retry-after", "7"));
          assertEquals(429, empty.statusCode());
          assertEquals("", empty.body());
          assertEquals(OptionalLong.of(7), empty.retryAfterSeconds());
          assertEquals(0, empty.getSuppressed().length, "nothing was parsed");
        },
        () -> {
          final var blank = assertThrows(HttpResponseException.class, () -> parseResult(503, " \n\t "));
          assertEquals(503, blank.statusCode());
          assertEquals(" \n\t ", blank.body(), "the body text is kept as it came");
        },
        () -> {
          final var truncated = assertThrows(HttpResponseException.class, () -> parseResult(503, "{\"message\":\"upstream"));
          assertEquals(503, truncated.statusCode());
          assertEquals(1, truncated.getSuppressed().length, "the parser's failure is attached");
        },
        () -> {
          final var stringError = assertThrows(HttpResponseException.class, () -> parseResult(429, "{\"error\":\"Too Many Requests\"}"));
          assertEquals(429, stringError.statusCode());
          assertEquals("{\"error\":\"Too Many Requests\"}", stringError.body());
        },
        () -> assertEquals(429, assertThrows(HttpResponseException.class,
            () -> parseResult(429, "{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"slow\"}}")).statusCode()),
        () -> assertEquals(401, assertThrows(HttpResponseException.class,
            () -> parseResult(401, "{\"error\":{\"code\":401,\"message\":5}}")).statusCode()),
        () -> assertEquals(200, assertThrows(HttpResponseException.class,
            () -> parseResult(200, "{\"error\":{\"code\":-32002,\"message\":\"x\",\"data\":\"unexpected\"},\"id\":1}")).statusCode()),
        () -> {
          // -32002 carries a simulation whose returnData is decoded as it is parsed; base64 that
          // is not throws the decoder's IllegalArgumentException, not the parser's exception.
          final var badReturnData = assertThrows(HttpResponseException.class, () -> parseResult(200, """
              {"jsonrpc":"2.0","error":{"code":-32002,"message":"Transaction simulation failed","data":{"err":"x","logs":[],"unitsConsumed":1,"returnData":{"programId":"11111111111111111111111111111111","data":["!","base64"]}}},"id":1}"""));
          assertEquals(200, badReturnData.statusCode());
          assertEquals(1, badReturnData.getSuppressed().length);
          assertInstanceOf(IllegalArgumentException.class, badReturnData.getSuppressed()[0], "the decoder's own failure is the one attached");
        }
    );
  }

  /// Under a 2xx, a result member answers whatever sits beside it: an error member beside a
  /// result is never read, and an error object with a null message is still a readable error.
  @Test
  void aResultAnswersBesideAnErrorAndANullMessageIsReadable() {
    assertEquals("ok", parseResult(200, "{\"jsonrpc\":\"2.0\",\"result\":\"ok\",\"error\":\"boom\",\"id\":1}"));
    assertEquals("ok", parseResult(200, "{\"jsonrpc\":\"2.0\",\"error\":\"boom\",\"result\":\"ok\",\"id\":1}"), "in either member order");
    final var nullMessage = assertThrows(JsonRpcException.class,
        () -> parseResult(429, "{\"error\":{\"code\":429,\"message\":null},\"id\":1}"));
    assertEquals(429, nullMessage.code());
    assertEquals(OptionalInt.of(429), nullMessage.httpStatus());
  }

  /// A response that is not a usable answer is typed: its status, its body and the server's
  /// retry-after hint in seconds are fields, so a caller tells a 429 from a 401 from a 502
  /// without reading the message. It is still the UncheckedIOException over an
  /// UnknownServiceException it always was, for every catch written against that.
  @Test
  void statusFailuresAreTypedWithStatusBodyAndRetryAfter() {
    final var ex = assertThrows(HttpResponseException.class,
        () -> parseResult(429, "slow down", "retry-after", "7"));
    assertEquals(429, ex.statusCode());
    assertEquals("slow down", ex.body());
    assertEquals(OptionalLong.of(7), ex.retryAfterSeconds());
    assertInstanceOf(UnknownServiceException.class, ex.getCause());
    assertTrue(ex.getMessage().contains("httpCode:429"), ex.getMessage());
    assertTrue(ex.getMessage().contains("slow down"), ex.getMessage());
    assertEquals(ex.getCause().toString(), ex.getMessage(), "the message is the cause's toString, as before");
    assertEquals("java.net.UnknownServiceException: HTTP request failed with [httpCode:429], [body=slow down]", ex.getMessage());
    assertEquals(0, ex.getSuppressed().length, "classified by the gate, not failed in the parser");
  }

  /// A body of nothing but JSON whitespace, each kind alone and all together, is typed before
  /// the parser is opened: the body text is kept as it came and no parser failure is attached,
  /// which a check that let one kind through would betray.
  @Test
  void aBlankBodyOfEachJsonWhitespaceIsTypedBeforeTheParser() {
    assertAll(List.of(" ", "\n", "\r", "\t", " \n\r\t ").stream().map(blank -> () -> {
      final var ex = assertThrows(HttpResponseException.class, () -> parseResult(429, blank, "retry-after", "3"));
      assertEquals(429, ex.statusCode());
      assertEquals(blank, ex.body(), "the body text is kept as it came");
      assertEquals(OptionalLong.of(3), ex.retryAfterSeconds());
      assertEquals(0, ex.getSuppressed().length, "typed before the parser is opened: " + List.of(blank));
    }));
  }

  /// The typed failure is still serializable, as the pair it replaces was; an absent or negative
  /// retry-after is no hint, and zero is one.
  @Test
  void theTypedFailureSerializes() throws Exception {
    final var ex = assertThrows(HttpResponseException.class,
        () -> parseResult(503, "down", "retry-after", "5"));
    final var bytes = new java.io.ByteArrayOutputStream();
    try (final var out = new java.io.ObjectOutputStream(bytes)) {
      out.writeObject(ex);
    }
    try (final var in = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
      final var copy = (HttpResponseException) in.readObject();
      assertEquals(503, copy.statusCode());
      assertEquals("down", copy.body());
      assertEquals(OptionalLong.of(5), copy.retryAfterSeconds());
      assertEquals(ex.getMessage(), copy.getMessage());
    }
    final var noHint = assertThrows(HttpResponseException.class, () -> parseResult(503, "down"));
    assertTrue(noHint.retryAfterSeconds().isEmpty());
    final var negative = assertThrows(HttpResponseException.class, () -> parseResult(503, "down", "retry-after", "-3"));
    assertTrue(negative.retryAfterSeconds().isEmpty(), "a negative delay is no hint");
    final var zero = assertThrows(HttpResponseException.class, () -> parseResult(503, "down", "retry-after", "0"));
    assertEquals(OptionalLong.of(0), zero.retryAfterSeconds(), "zero is a hint to retry at once, not no hint");
  }

  /// The factory reads a null body as empty, as the generic controller's missing body is.
  @Test
  void aNullBodyReadsAsEmpty() {
    final var ex = HttpResponseException.of(StubHttpResponse.of(500, (byte[]) null), null);
    assertEquals("", ex.body());
    assertEquals(500, ex.statusCode());
    assertTrue(ex.getMessage().endsWith("[body=]"), ex.getMessage());
  }

  /// A caller that holds a status, a body and headers rather than an `HttpResponse` builds the
  /// typed failure from the parts, and a 429's `retry-after` hint is read from those headers.
  @Test
  void thePartsFactoryReadsTheRetryAfterHintFromItsHeaders() {
    final var headers = HttpHeaders.of(Map.of("retry-after", List.of("7")), (_, _) -> true);
    final var ex = HttpResponseException.of(429, "slow down", headers);
    assertEquals(429, ex.statusCode());
    assertEquals("slow down", ex.body());
    assertEquals(OptionalLong.of(7), ex.retryAfterSeconds());
    assertEquals("java.net.UnknownServiceException: HTTP request failed with [httpCode:429], [body=slow down]", ex.getMessage());
    assertInstanceOf(UnknownServiceException.class, ex.getCause());
  }

  /// Null headers read as no hint, not as a failure of the factory.
  @Test
  void thePartsFactoryReadsNullHeadersAsNoHint() {
    final var ex = HttpResponseException.of(503, "down", null);
    assertEquals(503, ex.statusCode());
    assertEquals("down", ex.body());
    assertTrue(ex.retryAfterSeconds().isEmpty());
  }

  /// The response factory is the parts factory over the response's own status and headers, so
  /// the two build equal fields and an equal message from the same inputs: a hint in seconds, a
  /// date-valued hint that reads as none, and a body that is text, empty or null.
  @Test
  void bothFactoriesBuildTheSameFailure() {
    final var responses = List.of(
        StubHttpResponse.of(429, (byte[]) null, "retry-after", "7"),
        StubHttpResponse.of(503, (byte[]) null, "retry-after", "Wed, 21 Oct 2026 07:28:00 GMT"));
    for (final var response : responses) {
      for (final var body : Arrays.asList("slow down", "", null)) {
        final var fromResponse = HttpResponseException.of(response, body);
        final var fromParts = HttpResponseException.of(response.statusCode(), body, response.headers());
        final var input = response.statusCode() + " " + response.headers().map() + " " + body;
        assertEquals(fromResponse.statusCode(), fromParts.statusCode(), input);
        assertEquals(fromResponse.body(), fromParts.body(), input);
        assertEquals(fromResponse.retryAfterSeconds(), fromParts.retryAfterSeconds(), input);
        assertEquals(fromResponse.getMessage(), fromParts.getMessage(), input);
      }
    }
  }

  /// A success status whose body is not a JSON object fails with that status, so the caller
  /// sees the 200 a captive portal or a gateway page arrived under.
  @Test
  void aNonObjectBodyUnderASuccessStatusIsTypedWithThatStatus() {
    final var ex = assertThrows(HttpResponseException.class, () -> parseResult(200, "<html>portal</html>"));
    assertEquals(200, ex.statusCode());
    assertEquals("<html>portal</html>", ex.body());
    assertTrue(ex.retryAfterSeconds().isEmpty());
    assertEquals(0, ex.getSuppressed().length, "classified by the gate, not failed in the parser");
  }

  /// A JSON-RPC error object answered under a non-2xx status keeps that status: a provider's
  /// 401 or 429 that still speaks JSON-RPC is told from a 200 by the exception, not by a log.
  @Test
  void rpcErrorsCarryTheHttpStatusTheyArrivedUnder() {
    final var unauthorized = assertThrows(JsonRpcException.class, () -> parseResult(
        401, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"bad key\"},\"id\":1}"));
    assertEquals(OptionalInt.of(401), unauthorized.httpStatus());
    assertEquals(-32001, unauthorized.code());

    final var ok = assertThrows(JsonRpcException.class, () -> parseResult(
        200, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"Invalid params\"},\"id\":1}"));
    assertEquals(OptionalInt.of(200), ok.httpStatus());

    final var limited = assertThrows(JsonRpcException.class, () -> parseResult(
        429, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":429,\"message\":\"Too many requests\"},\"id\":1}",
        "retry-after", "3"));
    assertEquals(OptionalInt.of(429), limited.httpStatus());
    assertEquals(OptionalLong.of(3), limited.retryAfterSeconds());
  }

  /// An error parsed without its response, as the websocket parses one, has no status.
  @Test
  void anErrorParsedWithoutAResponseHasNoHttpStatus() {
    final var ji = JsonIterator.parse("{\"code\":-32602,\"message\":\"Invalid params\"}");
    final var ex = JsonRpcException.parseException(ji, OptionalLong.empty());
    assertTrue(ex.httpStatus().isEmpty());
    assertEquals(-32602, ex.code());
  }

  /// The retry-after header may be an HTTP-date rather than seconds. On the typed status
  /// failure that form reads as no hint; the status and the body still arrive.
  @Test
  void aRetryAfterDateOnAStatusFailureReadsAsNoHint() {
    final var status = assertThrows(HttpResponseException.class,
        () -> parseResult(503, "down", "retry-after", "Wed, 21 Oct 2026 07:28:00 GMT"));
    assertTrue(status.retryAfterSeconds().isEmpty());
    assertEquals(503, status.statusCode());
    assertEquals("down", status.body());
  }

  /// A JSON-RPC error answered with a date-valued retry-after must arrive as the error the server
  /// sent, with no hint, not as a number format failure out of the gate.
  @Test
  void aRetryAfterDateOnAnRpcErrorDoesNotMaskTheServersAnswer() {
    final var rpc = assertThrows(JsonRpcException.class, () -> parseResult(
        429, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":429,\"message\":\"Too many requests\"},\"id\":1}",
        "retry-after", "Wed, 21 Oct 2026 07:28:00 GMT"));
    assertTrue(rpc.retryAfterSeconds().isEmpty());
    assertEquals(OptionalInt.of(429), rpc.httpStatus());
    assertEquals(429, rpc.code());

    final var negative = assertThrows(JsonRpcException.class, () -> parseResult(
        429, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":429,\"message\":\"Too many requests\"},\"id\":1}",
        "retry-after", "-5"));
    assertTrue(negative.retryAfterSeconds().isEmpty(), "a negative delay, which the gate used to carry, is no hint");
    final var zero = assertThrows(JsonRpcException.class, () -> parseResult(
        429, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":429,\"message\":\"Too many requests\"},\"id\":1}",
        "retry-after", "0"));
    assertEquals(OptionalLong.of(0), zero.retryAfterSeconds());
  }

}
