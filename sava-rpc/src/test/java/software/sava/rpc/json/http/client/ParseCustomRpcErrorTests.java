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
}
