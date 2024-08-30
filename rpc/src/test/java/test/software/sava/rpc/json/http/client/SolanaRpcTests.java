package test.software.sava.rpc.json.http.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.response.NodeHealth;
import systems.comodal.jsoniter.JsonIterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    final var httpClient = HttpClientTests.createClient();
    RPC_CLIENT = SolanaRpcClient.createClient(httpServerRecord.endpoint(), httpClient);
  }

  @AfterAll
  static void shutdown() {
    RPC_CLIENT.httpClient().close();
    HTTP_SERVER.stop(0);
  }

  private void validateNodeHealth(final NodeHealth nodeHealth) {
    assertEquals(-32005, nodeHealth.code());
    assertEquals(0, nodeHealth.numSlotsBehind());
    assertEquals("Node is unhealthy", nodeHealth.message());
  }

  @Test
  void testNodeHealth() {
    final var nodeHealth = RPC_CLIENT.getHealth().join();
    validateNodeHealth(nodeHealth);
  }

  @Test
  void testPeekResponse() {
    var rpcClient = SolanaRpcClient.createClient(RPC_CLIENT.endpoint(), RPC_CLIENT.httpClient(),
        response -> {
          assertEquals(200, response.statusCode());
          return false;
        });
    var nodeHealth = rpcClient.getHealth().join();
    assertNull(nodeHealth);

    rpcClient = SolanaRpcClient.createClient(RPC_CLIENT.endpoint(), RPC_CLIENT.httpClient(), _ -> true);
    nodeHealth = rpcClient.getHealth().join();
    validateNodeHealth(nodeHealth);
  }
}
