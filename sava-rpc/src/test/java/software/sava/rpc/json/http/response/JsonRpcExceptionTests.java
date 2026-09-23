package software.sava.rpc.json.http.response;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

/// The exception carries what its callers know about the envelope: the retry-after hint from
/// the HTTP client and the response id from whichever path read it. Neither is parsed here —
/// the error object has no envelope — so each is exactly what was passed and absent otherwise.
final class JsonRpcExceptionTests {

  private static final String ERROR = "{\"code\":-32602,\"message\":\"Invalid params\"}";

  @Test
  void theRequestIdIsWhatTheCallerPassed() {
    final var withId = JsonRpcException.parseException(JsonIterator.parse(ERROR), OptionalLong.empty(), OptionalLong.of(42));
    assertEquals(OptionalLong.of(42), withId.requestId());
    assertEquals(-32602, withId.code());
    assertEquals("Invalid params", withId.getMessage());
    assertTrue(withId.retryAfterSeconds().isEmpty());
  }

  @Test
  void theTwoArgumentFormCarriesNoRequestId() {
    final var without = JsonRpcException.parseException(JsonIterator.parse(ERROR), OptionalLong.of(30));
    assertTrue(without.requestId().isEmpty());
    assertEquals(OptionalLong.of(30), without.retryAfterSeconds());
  }

  /// The one reader both transports use: it carries a non-negative integer literal a long can
  /// hold, from wherever the envelope starts in the buffer, and reads everything else as
  /// absent without throwing — including a body cut off before the closing brace.
  @Test
  void theEnvelopeReaderCarriesOnlyWhatSavaMints() {
    assertEquals(OptionalLong.of(9034), JsonRpcException.envelopeRequestId(
        JsonIterator.parse("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32602,\"message\":\"x\"},\"id\":9034}"), 0));
    assertEquals(OptionalLong.of(7), JsonRpcException.envelopeRequestId(
        JsonIterator.parse("{\"id\":7,\"error\":{\"code\":-32602,\"message\":\"x\"}}"), 0), "leading id");
    assertEquals(OptionalLong.of(0), JsonRpcException.envelopeRequestId(
        JsonIterator.parse("{\"error\":{\"code\":1,\"message\":\"x\"},\"id\":0}"), 0), "zero is a legal id");
    for (final var absent : new String[]{
        "{\"error\":{\"code\":1,\"message\":\"x\"},\"id\":null}",
        "{\"error\":{\"code\":1,\"message\":\"x\"},\"id\":\"abc\"}",
        "{\"error\":{\"code\":1,\"message\":\"x\"},\"id\":-5}",
        "{\"error\":{\"code\":1,\"message\":\"x\"},\"id\":1.5}",
        "{\"error\":{\"code\":1,\"message\":\"x\"},\"id\":1e3}",
        "{\"error\":{\"code\":1,\"message\":\"x\"},\"id\":18446744073709551615}",
        "{\"error\":{\"code\":1,\"message\":\"x\",\"data\":{\"id\":99}}}",
        "{\"error\":{\"code\":1,\"message\":\"x\"}}",
        "{\"error\":{\"code\":1,\"message\":\"x\"}",
        "{\"error\":{\"code\":1,\"message\":\"x\"},\"id\":"}) {
      assertTrue(JsonRpcException.envelopeRequestId(JsonIterator.parse(absent), 0).isEmpty(), absent);
    }
  }

  /// The websocket hands the reader a frame at an offset inside a larger buffer.
  @Test
  void theEnvelopeReaderStartsWhereItIsTold() {
    final var buffer = "garbage{\"error\":{\"code\":1,\"message\":\"x\"},\"id\":12}";
    assertEquals(OptionalLong.of(12), JsonRpcException.envelopeRequestId(JsonIterator.parse(buffer), 7));
  }

  @Test
  void aNullRequestIdReadsAsEmptyRatherThanNull() {
    final var exception = JsonRpcException.parseException(JsonIterator.parse(ERROR), null, null);
    assertNotNull(exception.requestId());
    assertTrue(exception.requestId().isEmpty());
    assertNotNull(exception.retryAfterSeconds());
    assertTrue(exception.retryAfterSeconds().isEmpty());
  }
}
