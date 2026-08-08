package software.sava.rpc.json.http.response;

import org.junit.jupiter.api.Test;
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
}
