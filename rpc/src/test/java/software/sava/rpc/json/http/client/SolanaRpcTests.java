package software.sava.rpc.json.http.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.NodeHealth;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static software.sava.rpc.json.http.client.HttpClientTests.createServer;
import static software.sava.rpc.json.http.client.HttpClientTests.writeResponse;

final class SolanaRpcTests {

  private static URI HTTP_SERVER_ENDPOINT;
  private static HttpServer HTTP_SERVER;
  private static HttpClient HTTP_CLIENT;

  @BeforeAll
  static void setupHttpServer() {
    final var httpServerRecord = createServer();

    httpServerRecord.httpServer().createContext("/", request -> {
          assertEquals("POST", request.getRequestMethod());
          final var requestBody = request.getRequestBody().readAllBytes();

          try (final var ji = JsonIterator.parse(requestBody)) {
            final long id = ji.skipUntil("id").readLong();
            if (id > Integer.MAX_VALUE || id < 0) {
              writeResponse(400, request, "Test id must be a positive int, not: " + id);
              return;
            }

            final var requestString = new String(requestBody);
            final var responseMsg = switch ((int) id) {
              case 1 -> {
                if (requestString.equals("""
                    {"jsonrpc":"2.0","id":1,"method":"getHealth"}""")) {
                  yield """
                      {"jsonrpc":"2.0","error":{"code":-32005,"message":"Node is unhealthy","data":{"numSlotsBehind":null}},"id":1698251465713}""";
                } else {
                  yield null;
                }
              }
              case 2 -> {
                if (requestString.equals("""
                    {"jsonrpc":"2.0","id":2,"method":"getAccountInfo","params":["7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r",{"encoding":"base64","commitment":"confirmed","minContextSlot":1000,"dataSlice":{"length":32,"offset":88}}]}""")) {
                  yield """
                      {"jsonrpc":"2.0","id":1742866771486,"result":{"context":{"slot":328984397,"apiVersion":"2.1.9"},"value":{"lamports":7182720,"data":["CR5z0XpVJtRI5Ymupa/nwizWHFtmqGpCerJiMJUU5Vw=","base64"],"owner":"LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo","executable":false,"rentEpoch":18446744073709551615,"space":904}}}""";
                } else if (requestString.equals("""
                    {"jsonrpc":"2.0","id":2,"method":"getAccountInfo","params":["7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r",{"encoding":"base64","commitment":"confirmed"}]}""")) {
                  yield """
                      {"jsonrpc":"2.0","id":1742867256946,"result":{"context":{"slot":328985609,"apiVersion":"2.1.9"},"value":{"lamports":7182720,"data":["IQsxYrVlsQ0QJx4AWAKIE8DUAQDgkwQAeFX+/4iqAQD0AQAAAAAAALoEAAC6BAAAIEIAAAAAAACZCuJnAAAAAAAAAAAAAAAA/QQAACBCAAAEAAABECcAAAkec9F6VSbUSOWJrqWv58Is1hxbZqhqQnqyYjCVFOVcxvp6877brTo9ZfNqq8l0MbG75MLS9uDkfKYCA0UvXWFoEybzQegBQQUKNAmPyVJWyzXz5ImKF+3P+3n3XueHs4Ivcaabwzb5Xwz716AtZNyfeliY7G4hkFwJfcvp+vHCiFs8AAAAAADRw+TPAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIN67mXD/3ERICDqyDFckHxEM9KyOJy5hDgOnlZKbrLsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPD//w8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAhqamcAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAC4v0fKdeC+80liZjCG34Ji0oU0a0dkfbAIYIU61nxeLgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==","base64"],"owner":"LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo","executable":false,"rentEpoch":18446744073709551615,"space":904}}}""";
                } else {
                  yield null;
                }
              }
              case 3 -> {
                if (requestString.equals("""
                    {"jsonrpc":"2.0","id":3,"method":"getMultipleAccounts","params":[["7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r","5rCf1DM8LjKTw4YqhnoLcngyZYeNnQqztScTogYHAS6"],{"encoding":"base64","commitment":"confirmed","minContextSlot":1000,"dataSlice":{"length":32,"offset":88}}]}""")) {
                  yield """
                      {"jsonrpc":"2.0","id":1742865650193,"result":{"context":{"slot":328981577,"apiVersion":"2.1.9"},"value":[{"lamports":7182720,"data":["CR5z0XpVJtRI5Ymupa/nwizWHFtmqGpCerJiMJUU5Vw=","base64"],"owner":"LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo","executable":false,"rentEpoch":18446744073709551615,"space":904},{"lamports":161492326,"data":["BpuIV/6rgYT7aH9jRhjANdrEOdwa6ztVmKDwAAAAAAE=","base64"],"owner":"LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo","executable":false,"rentEpoch":18446744073709551615,"space":904}]}}""";
                } else if (requestString.equals("""
                    {"jsonrpc":"2.0","id":3,"method":"getMultipleAccounts","params":[["7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r","5rCf1DM8LjKTw4YqhnoLcngyZYeNnQqztScTogYHAS6"],{"encoding":"base64","commitment":"confirmed"}]}""")) {
                  yield """
                      {"jsonrpc":"2.0","id":1742866270066,"result":{"context":{"slot":328983133,"apiVersion":"2.1.9"},"value":[{"lamports":7182720,"data":["IQsxYrVlsQ0QJx4AWAKIE8DUAQDgkwQAeFX+/4iqAQD0AQAAAAAAAEXkAAAFSAAAH0IAAAAAAAA1B+JnAAAAAAAAAAAAAAAA/QQAABtCAAAEAAABECcAAAkec9F6VSbUSOWJrqWv58Is1hxbZqhqQnqyYjCVFOVcxvp6877brTo9ZfNqq8l0MbG75MLS9uDkfKYCA0UvXWFoEybzQegBQQUKNAmPyVJWyzXz5ImKF+3P+3n3XueHs4Ivcaabwzb5Xwz716AtZNyfeliY7G4hkFwJfcvp+vHCj1k8AAAAAACOSdnPAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIN67mXD/3ERICDqyDFckHxEM9KyOJy5hDgOnlZKbrLsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPD//w8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAhqamcAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAC4v0fKdeC+80liZjCG34Ji0oU0a0dkfbAIYIU61nxeLgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==","base64"],"owner":"LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo","executable":false,"rentEpoch":18446744073709551615,"space":904},{"lamports":161492326,"data":["IQsxYrVlsQ0QJx4AWAKIE8DUAQDgkwQAeFX+/4iqAQD0AQAAAAAAAOREAQB0MwAA0+z//wAAAABEB+JnAAAAAAAAAAAAAAAA/wQAAMzs//8EAAAAAAAAAAabiFf+q4GE+2h/Y0YYwDXaxDncGus7VZig8AAAAAABxvp6877brTo9ZfNqq8l0MbG75MLS9uDkfKYCA0UvXWHJSJlnLnmUpTrMngO1OaOUlAPGmZRz7OrcxR6p6JFrJq9frZbeoPqr6Upv3p3ixa4nPIa3aZLTj4H3hSgAdOmvsDacZUgAAACFBNQ6DAAAAFHd+n01qBISSiluOrh5Pt8AxOeitb1p/qhb1PVqqJaHAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPZ93Bg5F5w1GjB12qcQbOR1fpZKy3cDiSwnLzzQjuM8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgP///////wEAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA5qamcAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==","base64"],"owner":"LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo","executable":false,"rentEpoch":18446744073709551615,"space":904}]}}""";
                } else {
                  yield null;
                }
              }
              default -> "Unexpected json rpc id: " + id;
            };

            if (responseMsg == null) {
              writeResponse(400, request, requestString);
            } else {
              writeResponse(request, responseMsg);
            }
          }
        }
    );

    HTTP_SERVER_ENDPOINT = httpServerRecord.endpoint();
    HTTP_SERVER = httpServerRecord.httpServer();
    HTTP_CLIENT = HttpClientTests.createClient();
  }

  @AfterAll
  static void shutdown() {
    HTTP_SERVER.stop(0);
  }

  private static SolanaJsonRpcClient createClient(final Predicate<HttpResponse<byte[]>> applyResponse) {
    return (SolanaJsonRpcClient) SolanaRpcClient.createClient(HTTP_SERVER_ENDPOINT, HTTP_CLIENT, applyResponse);
  }

  private static SolanaJsonRpcClient createClient() {
    return createClient(null);
  }

  @Test
  void testAccountInfo() {
    final int testId = 1;
    final var rpcClient = createClient();

    rpcClient.id.set(testId);
    var accountInfo = rpcClient.getAccountInfo(
        BigInteger.valueOf(1000),
        32, 88,
        PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r")
    ).join();

    assertEquals("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r", accountInfo.pubKey().toBase58());
    byte[] data = accountInfo.data();
    assertEquals(32, data.length);
    assertEquals("cbbtcf3aa214zXHbiAZQwf4122FBYbraNdFqgw4iMij", PublicKey.readPubKey(data).toString());

    rpcClient.id.set(testId);
    accountInfo = rpcClient.getAccountInfo(
        PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r")
    ).join();

    assertEquals("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r", accountInfo.pubKey().toBase58());
    data = accountInfo.data();
    assertEquals(904, data.length);
    assertEquals("cbbtcf3aa214zXHbiAZQwf4122FBYbraNdFqgw4iMij", PublicKey.readPubKey(data, 88).toString());
  }

  @Test
  void testMultipleAccounts() {
    final int testId = 2;
    final var rpcClient = createClient();

    rpcClient.id.set(testId);
    var accounts = rpcClient.getMultipleAccounts(
        Commitment.CONFIRMED,
        BigInteger.valueOf(1000),
        32, 88,
        List.of(
            PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r"),
            PublicKey.fromBase58Encoded("5rCf1DM8LjKTw4YqhnoLcngyZYeNnQqztScTogYHAS6")
        )
    ).join();

    assertEquals(2, accounts.size());

    var accountInfo = accounts.getFirst();
    assertEquals("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r", accountInfo.pubKey().toBase58());
    byte[] data = accountInfo.data();
    assertEquals(32, data.length);
    assertEquals("cbbtcf3aa214zXHbiAZQwf4122FBYbraNdFqgw4iMij", PublicKey.readPubKey(data).toString());

    accountInfo = accounts.getLast();
    assertEquals("5rCf1DM8LjKTw4YqhnoLcngyZYeNnQqztScTogYHAS6", accountInfo.pubKey().toBase58());
    data = accountInfo.data();
    assertEquals(32, data.length);
    assertEquals("So11111111111111111111111111111111111111112", PublicKey.readPubKey(data).toBase58());


    rpcClient.id.set(testId);
    accounts = rpcClient.getMultipleAccounts(
        List.of(
            PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r"),
            PublicKey.fromBase58Encoded("5rCf1DM8LjKTw4YqhnoLcngyZYeNnQqztScTogYHAS6")
        )
    ).join();

    assertEquals(2, accounts.size());

    accountInfo = accounts.getFirst();
    assertEquals("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r", accountInfo.pubKey().toBase58());
    data = accountInfo.data();
    assertEquals(904, data.length);
    assertEquals("cbbtcf3aa214zXHbiAZQwf4122FBYbraNdFqgw4iMij", PublicKey.readPubKey(data, 88).toString());

    accountInfo = accounts.getLast();
    assertEquals("5rCf1DM8LjKTw4YqhnoLcngyZYeNnQqztScTogYHAS6", accountInfo.pubKey().toBase58());
    data = accountInfo.data();
    assertEquals(904, data.length);
    assertEquals("So11111111111111111111111111111111111111112", PublicKey.readPubKey(data, 88).toBase58());
  }

  private void validateNodeHealth(final NodeHealth nodeHealth) {
    assertEquals(-32005, nodeHealth.code());
    assertEquals(0, nodeHealth.numSlotsBehind());
    assertEquals("Node is unhealthy", nodeHealth.message());
  }

  @Test
  void testNodeHealth() {
    final var client = createClient();
    client.id.set(0);
    final var nodeHealth = client.getHealth().join();
    validateNodeHealth(nodeHealth);
  }

  @Test
  void testPeekResponse() {
    var rpcClient = createClient(response -> {
          assertEquals(200, response.statusCode());
          return false;
        }
    );
    rpcClient.id.set(0);
    var nodeHealth = rpcClient.getHealth().join();
    assertNull(nodeHealth);

    rpcClient = createClient(_ -> true);
    rpcClient.id.set(0);
    nodeHealth = rpcClient.getHealth().join();
    validateNodeHealth(nodeHealth);
  }
}
