package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.response.IxError;
import software.sava.rpc.json.http.response.JsonRpcException;
import software.sava.rpc.json.http.response.RpcCustomError;
import software.sava.rpc.json.http.response.TransactionError;
import systems.comodal.jsoniter.JsonIterator;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

final class ParseCustomRpcErrorTests {

  @Test
  void testPreFlightFailure() {
    final var json = """
        {
          "jsonrpc":"2.0",
          "error":{
            "code":-32002,
            "message":"Transaction simulation failed: Error processing Instruction 0: custom program error: 0x0",
            "data":{
              "accounts":null,
              "err":{
                "InstructionError":[
                  0,
                  {
                    "Custom":0
                  }
                ]
              },
              "innerInstructions":null,
              "logs":[
                "Program GovaE4iu227srtG2s3tZzB4RmWBzw8sTwrCLZz7kN7rY invoke [1]",
                "Program log: Instruction: NewVote",
                "Program 11111111111111111111111111111111 invoke [2]",
                "Allocate: account Address { address: asdf, base: None } already in use",
                "Program 11111111111111111111111111111111 failed: custom program error: 0x0",
                "Program GovaE4iu227srtG2s3tZzB4RmWBzw8sTwrCLZz7kN7rY consumed 10428 of 400000 compute units",
                "Program GovaE4iu227srtG2s3tZzB4RmWBzw8sTwrCLZz7kN7rY failed: custom program error: 0x0"
              ],
              "replacementBlockhash":null,
              "returnData":null,
              "unitsConsumed":10428
            }
          },
          "id":1733932258981
        }""";

    final var ji = JsonIterator.parse(json);
    ji.skipUntil("error");

    final var exception = JsonRpcException.parseException(ji, OptionalLong.empty());

    assertEquals(-32002, exception.code());
    assertEquals("Transaction simulation failed: Error processing Instruction 0: custom program error: 0x0", exception.getMessage());
    assertTrue(exception.retryAfterSeconds().isEmpty());

    final var customError = exception.customError();
    if (customError instanceof RpcCustomError.SendTransactionPreflightFailure(final var simulation)) {
      assertNull(simulation.context());
      assertNull(simulation.replacementBlockHash());

      assertTrue(simulation.accounts().isEmpty());

      final var unitsConsumed = simulation.unitsConsumed();
      assertTrue(unitsConsumed.isPresent());
      assertEquals(10428, unitsConsumed.getAsInt());

      final var logs = simulation.logs();
      assertEquals(7, logs.size());
      assertEquals("Program GovaE4iu227srtG2s3tZzB4RmWBzw8sTwrCLZz7kN7rY invoke [1]", logs.getFirst());
      assertEquals("Allocate: account Address { address: asdf, base: None } already in use", logs.get(3));
      assertEquals("Program GovaE4iu227srtG2s3tZzB4RmWBzw8sTwrCLZz7kN7rY failed: custom program error: 0x0", logs.getLast());

      final var error = simulation.error();
      assertNotNull(error);

      if (error instanceof TransactionError.InstructionError(int index, var ixError)) {
        assertEquals(0, index);
        if (ixError instanceof IxError.Custom(long code)) {
          assertEquals(0, code);
        } else {
          fail(error.getClass().getSimpleName());
        }
      } else {
        fail(error.getClass().getSimpleName());
      }
    } else {
      fail(customError.getClass().getSimpleName());
    }
  }

  private static JsonRpcException parseException(final String json) {
    final var ji = JsonIterator.parse(json);
    ji.skipUntil("error");
    return JsonRpcException.parseException(ji, OptionalLong.empty());
  }

  @Test
  void testEpochRewardsPeriodActive() {
    var exception = parseException("""
        {"jsonrpc":"2.0","error":{"code":-32017,"message":"Epoch rewards period still active at slot 789","data":{"currentBlockHeight":123,"rewardsCompleteBlockHeight":456,"slot":789}},"id":1}"""
    );
    assertEquals(-32017, exception.code());
    if (exception.customError() instanceof RpcCustomError.EpochRewardsPeriodActive(
        final var slot, final long currentBlockHeight, final long rewardsCompleteBlockHeight
    )) {
      assertEquals(OptionalLong.of(789), slot);
      assertEquals(123, currentBlockHeight);
      assertEquals(456, rewardsCompleteBlockHeight);
    } else {
      fail(exception.customError().getClass().getSimpleName());
    }

    // Nodes which pre-date the Agave 3.0 data schema omit the slot.
    exception = parseException("""
        {"jsonrpc":"2.0","error":{"code":-32017,"message":"Epoch rewards period still active at slot 789","data":{"currentBlockHeight":123,"rewardsCompleteBlockHeight":456}},"id":1}"""
    );
    if (exception.customError() instanceof RpcCustomError.EpochRewardsPeriodActive(
        final var slot, final long currentBlockHeight, final long rewardsCompleteBlockHeight
    )) {
      assertTrue(slot.isEmpty());
      assertEquals(123, currentBlockHeight);
      assertEquals(456, rewardsCompleteBlockHeight);
    } else {
      fail(exception.customError().getClass().getSimpleName());
    }
  }

  @Test
  void testSlotNotEpochBoundary() {
    final var exception = parseException("""
        {"jsonrpc":"2.0","error":{"code":-32018,"message":"Rewards cannot be found because slot 150 is not the epoch boundary. This may be due to gap in the queried node's local ledger or long-term storage","data":{"slot":150}},"id":1}"""
    );
    assertEquals(-32018, exception.code());
    if (exception.customError() instanceof RpcCustomError.SlotNotEpochBoundary(final long slot)) {
      assertEquals(150, slot);
    } else {
      fail(exception.customError().getClass().getSimpleName());
    }
  }

  @Test
  void testDataFreeErrorCodes() {
    var exception = parseException("""
        {"jsonrpc":"2.0","error":{"code":-32019,"message":"Failed to query long-term storage; please try again"},"id":1}"""
    );
    assertEquals(-32019, exception.code());
    assertInstanceOf(RpcCustomError.LongTermStorageUnreachable.class, exception.customError());

    exception = parseException("""
        {"jsonrpc":"2.0","error":{"code":-32020,"message":"Transaction 5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW not found"},"id":1}"""
    );
    assertEquals(-32020, exception.code());
    assertInstanceOf(RpcCustomError.FilterTransactionNotFound.class, exception.customError());

    exception = parseException("""
        {"jsonrpc":"2.0","error":{"code":-32021,"message":"No slot history"},"id":1}"""
    );
    assertEquals(-32021, exception.code());
    assertInstanceOf(RpcCustomError.NoSlotHistory.class, exception.customError());
  }
}
