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

  @Test
  void aNullRequestIdReadsAsEmptyRatherThanNull() {
    final var exception = JsonRpcException.parseException(JsonIterator.parse(ERROR), null, null);
    assertNotNull(exception.requestId());
    assertTrue(exception.requestId().isEmpty());
    assertNotNull(exception.retryAfterSeconds());
    assertTrue(exception.retryAfterSeconds().isEmpty());
  }

  /// The cursor contract is unchanged by the id: after the parse the caller continues at the
  /// end of the error object, whatever member order it had.
  @Test
  void theCursorIsLeftAtTheEndOfTheErrorObject() {
    final var ji = JsonIterator.parse("[{\"data\":{\"numSlotsBehind\":3},\"code\":-32005,\"message\":\"behind\"},7]");
    assertTrue(ji.readArray());
    final var exception = JsonRpcException.parseException(ji, OptionalLong.empty(), OptionalLong.of(9));
    assertEquals(OptionalLong.of(9), exception.requestId());
    assertEquals(new RpcCustomError.NodeUnhealthy(OptionalLong.of(3)), exception.customError());
    assertTrue(ji.readArray());
    assertEquals(7, ji.readInt());
    assertFalse(ji.readArray());
  }
}
