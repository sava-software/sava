package test.software.sava.rpc.json.http.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import systems.comodal.jsoniter.JsonIterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static test.software.sava.rpc.json.http.client.HttpClientTests.createServer;
import static test.software.sava.rpc.json.http.client.HttpClientTests.writeResponse;

final class SolanaRpcTests {

  private static HttpServer HTTP_SERVER;
  private static SolanaRpcClient RPC_CLIENT;

  @BeforeAll
  static void setupHttpServer() {
    final var httpServerRecord = createServer();

    httpServerRecord.httpServer().createContext("/", request -> {
      assertEquals("POST", request.getRequestMethod());
      try (final var ji = JsonIterator.parse(request.getRequestBody().readAllBytes())) {
        final var method = ji.skipUntil("method").readString();
        final var responseMsg = switch (method) {
          case "getHealth" -> """
              {"jsonrpc":"2.0","error":{"code":-32005,"message":"Node is unhealthy","data":{"numSlotsBehind":null}},"id":1698251465713}""";
          default -> "Unexpected method call: " + method;
        };
        writeResponse(request, responseMsg);
      }
    });

    HTTP_SERVER = httpServerRecord.httpServer();
    RPC_CLIENT = SolanaRpcClient.createHttpClient(httpServerRecord.endpoint());
  }

  @AfterAll
  static void shutdownServer() {
    HTTP_SERVER.stop(0);
  }

  @Test
  void testNodeHealth() {
    final var nodeHealth = RPC_CLIENT.getHealth().join();
    assertEquals(-32005, nodeHealth.code());
    assertEquals(0, nodeHealth.numSlotsBehind());
    assertEquals("Node is unhealthy", nodeHealth.message());
  }
}
