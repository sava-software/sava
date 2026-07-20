package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/// Subscribe and unsubscribe acknowledgements come straight off the websocket, so
/// this parser reads whatever the node sends. A subscribe reply carries the
/// subscription id as a number, an unsubscribe reply carries a boolean in the
/// same `result` field, and either may be replaced by an `error` object.
final class SubConfirmationTests {

  private static SubConfirmation parse(final String json) {
    return SubConfirmation.parse(JsonIterator.parse(json.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void subscribeAcknowledgementCarriesTheSubscriptionId() {
    final var confirmation = parse("{\"jsonrpc\":\"2.0\",\"result\":23784,\"id\":1}");
    assertEquals(BigInteger.valueOf(23784), confirmation.subId());
    assertEquals(1, confirmation.msgId());
    assertNull(confirmation.jsonRpcException());
  }

  /// An unsubscribe reply reuses `result` for a boolean. It must not be read as a
  /// subscription id, and it must not throw.
  @Test
  void unsubscribeAcknowledgementLeavesTheSubscriptionIdNull() {
    for (final String result : new String[]{"true", "false"}) {
      final var confirmation = parse("{\"jsonrpc\":\"2.0\",\"result\":" + result + ",\"id\":7}");
      assertNull(confirmation.subId(), "result:" + result);
      assertEquals(7, confirmation.msgId());
      assertNull(confirmation.jsonRpcException());
    }
  }

  @Test
  void errorRepliesAreCaptured() {
    final var confirmation = parse("""
        {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid Request: Only 1 address supported"},"id":4}""");
    assertNotNull(confirmation.jsonRpcException());
    assertEquals(-32602, confirmation.jsonRpcException().code());
    assertTrue(confirmation.jsonRpcException().getMessage().contains("Only 1 address supported"),
        confirmation.jsonRpcException().getMessage());
    assertEquals(4, confirmation.msgId());
    assertNull(confirmation.subId());
  }

  /// Subscription ids are u64 on the wire, which is why they are parsed as
  /// BigInteger rather than long.
  @Test
  void subscriptionIdsBeyondLongAreParsedWhole() {
    final var confirmation = parse("{\"result\":18446744073709551615,\"id\":2}");
    assertEquals(new BigInteger("18446744073709551615"), confirmation.subId());
    assertEquals(2, confirmation.msgId());
  }

  @Test
  void fieldOrderDoesNotMatter() {
    final var idFirst = parse("{\"id\":9,\"result\":11,\"jsonrpc\":\"2.0\"}");
    final var resultFirst = parse("{\"jsonrpc\":\"2.0\",\"result\":11,\"id\":9}");
    assertEquals(idFirst.subId(), resultFirst.subId());
    assertEquals(idFirst.msgId(), resultFirst.msgId());
  }

  /// A node adding fields must not break the parse — every unknown key is skipped.
  @Test
  void unknownFieldsAreSkipped() {
    final var confirmation = parse("""
        {"jsonrpc":"2.0","unexpected":{"nested":[1,2,3]},"result":55,"extra":"ignored","id":3,"trailing":null}""");
    assertEquals(BigInteger.valueOf(55), confirmation.subId());
    assertEquals(3, confirmation.msgId());
    assertNull(confirmation.jsonRpcException());
  }

  @Test
  void missingFieldsFallBackToDefaults() {
    final var empty = parse("{}");
    assertNull(empty.subId());
    assertEquals(0, empty.msgId());
    assertNull(empty.jsonRpcException());

    final var onlyId = parse("{\"id\":12}");
    assertNull(onlyId.subId());
    assertEquals(12, onlyId.msgId());
  }

  /// Both a result and an error is malformed, but it must still parse rather than
  /// throw — the caller decides what an ambiguous reply means.
  @Test
  void resultAndErrorTogetherAreBothRetained() {
    final var confirmation = parse("""
        {"result":5,"error":{"code":-1,"message":"nope"},"id":6}""");
    assertEquals(BigInteger.valueOf(5), confirmation.subId());
    assertNotNull(confirmation.jsonRpcException());
    assertEquals(6, confirmation.msgId());
  }
}
