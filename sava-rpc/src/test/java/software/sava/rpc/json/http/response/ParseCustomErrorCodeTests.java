package software.sava.rpc.json.http.response;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonException;
import systems.comodal.jsoniter.JsonIterator;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

// The long-code overloads guard against codes outside int range truncating
// onto known codes. code ± (1L << 32) aliases a real code under (int)
// truncation, so each guard is exercised with an aliasing code on both sides.
final class ParseCustomErrorCodeTests {

  @Test
  void longCodesRouteThroughIntSwitch() {
    assertSame(RpcCustomError.BlockCleanedUp.INSTANCE, RpcCustomError.parseError(-32001L));
    assertSame(RpcCustomError.SlotSkipped.INSTANCE, RpcCustomError.parseError(-32007L));
  }

  @Test
  void outOfRangeCodesDoNotTruncateOntoKnownCodes() {
    assertSame(RpcCustomError.Unknown.INSTANCE, RpcCustomError.parseError(-32001L + (1L << 32)));
    assertSame(RpcCustomError.Unknown.INSTANCE, RpcCustomError.parseError(-32001L - (1L << 32)));
    assertSame(RpcCustomError.Unknown.INSTANCE, RpcCustomError.parseError((long) Integer.MIN_VALUE));
    assertSame(RpcCustomError.Unknown.INSTANCE, RpcCustomError.parseError((long) Integer.MAX_VALUE));
  }

  @Test
  void longCodesWithDataRouteThroughIntSwitch() {
    final var ji = JsonIterator.parse("{\"numSlotsBehind\":42}");
    final var error = RpcCustomError.parseError(-32005L, ji);
    assertEquals(new RpcCustomError.NodeUnhealthy(OptionalLong.of(42)), error);
  }

  /// Agave's `NodeUnhealthyErrorData` holds an `Option<Slot>` and serializes `None` as null,
  /// which getHealth and sendTransaction's preflight health check answer whenever the node's
  /// health is unknown: that is a NodeUnhealthy with no slot count, not unreadable data.
  @Test
  void aNullNumSlotsBehindReadsAsAbsent() {
    final var ji = JsonIterator.parse("{\"numSlotsBehind\":null}");
    assertEquals(new RpcCustomError.NodeUnhealthy(OptionalLong.empty()), RpcCustomError.parseError(-32005L, ji));
  }

  @Test
  void outOfRangeCodesWithDataSkipTheirPayload() {
    final var ji = JsonIterator.parse("[{\"numSlotsBehind\":42},{\"numSlotsBehind\":42},7]");
    assertTrue(ji.readArray());
    assertSame(RpcCustomError.Unknown.INSTANCE, RpcCustomError.parseError(-32005L + (1L << 32), ji));
    assertTrue(ji.readArray());
    assertSame(RpcCustomError.Unknown.INSTANCE, RpcCustomError.parseError(-32005L - (1L << 32), ji));
    assertTrue(ji.readArray());
    assertEquals(7, ji.readInt());
    assertFalse(ji.readArray());
  }

  /// JSON-RPC member order carries no meaning, and `data`'s interpretation depends on `code`:
  /// when data arrived first it was parsed against code 0, so valid structured details were
  /// misclassified as Unknown. Both orders must classify identically.
  @Test
  void errorMemberOrderDoesNotChangeTheClassification() {
    final var codeFirst = JsonRpcException.parseException(JsonIterator.parse(
        """
            {"code":-32005,"message":"unhealthy","data":{"numSlotsBehind":42}}"""), OptionalLong.empty());
    final var dataFirst = JsonRpcException.parseException(JsonIterator.parse(
        """
            {"data":{"numSlotsBehind":42},"code":-32005,"message":"unhealthy"}"""), OptionalLong.empty());

    assertEquals(-32005L, dataFirst.code());
    assertEquals(codeFirst.customError(), dataFirst.customError());
    assertEquals(new RpcCustomError.NodeUnhealthy(OptionalLong.of(42)), dataFirst.customError());
  }

  @Test
  void deferredErrorDataParsingRestoresTheOuterCursor() {
    final var ji = JsonIterator.parse(
        """
            [{"data":{"numSlotsBehind":42},"code":-32005,"message":"unhealthy"},73]""");

    assertTrue(ji.readArray());
    final var exception = JsonRpcException.parseException(ji, OptionalLong.empty());
    assertEquals(new RpcCustomError.NodeUnhealthy(OptionalLong.of(42)), exception.customError());
    assertTrue(ji.readArray());
    assertEquals(73, ji.readInt());
    assertFalse(ji.readArray());
  }

  /// Data the code's parser cannot read costs the caller the structured details, not the
  /// error: the code and the message stand, the custom error is the one the code alone names,
  /// the data's failure is attached as suppressed, and the cursor is left where the error
  /// object ended, as it is after readable data, so a caller parsing past the error object,
  /// the websocket among them, continues from there.
  @Test
  void unreadableErrorDataKeepsTheErrorAndRestoresTheOuterCursor() {
    final var ji = JsonIterator.parse(
        """
            [{"data":"unexpected","code":-32005,"message":"unhealthy"},73]""");

    assertTrue(ji.readArray());
    final var exception = JsonRpcException.parseException(ji, OptionalLong.empty());
    assertEquals(-32005L, exception.code());
    assertEquals("unhealthy", exception.getMessage());
    assertSame(RpcCustomError.Unknown.INSTANCE, exception.customError(), "the custom error the code alone names");
    assertEquals(1, exception.getSuppressed().length);
    assertInstanceOf(JsonException.class, exception.getSuppressed()[0]);
    assertTrue(ji.readArray());
    assertEquals(73, ji.readInt());
    assertFalse(ji.readArray());
  }

  /// Duplicate object members are malformed and their ordering is not a protocol contract,
  /// but an untrusted response must not expose a code whose structured classification was
  /// produced from a different code. The code retained by the field scan classifies `data`.
  @Test
  void duplicateCodesCannotProduceAnInternallyInconsistentError() {
    final var exception = JsonRpcException.parseException(JsonIterator.parse(
        """
            {"code":-32005,"data":{"numSlotsBehind":1,"contextSlot":2},"code":-32016}"""),
        OptionalLong.empty());

    assertEquals(-32016, exception.code());
    assertEquals(new RpcCustomError.MinContextSlotNotReached(2), exception.customError());
  }
}
