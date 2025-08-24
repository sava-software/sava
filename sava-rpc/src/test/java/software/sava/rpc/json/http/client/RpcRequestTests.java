package software.sava.rpc.json.http.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.lang.System.Logger.Level.ERROR;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class RpcRequestTests implements HttpHandler {

  static {
    System.setProperty("com.sun.net.httpserver.HttpServerProvider", "sun.net.httpserver.DefaultHttpServerProvider");
  }

  private static final System.Logger logger = System.getLogger(RpcRequestTests.class.getName());
  private static final ExecutorService HTTP_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().executor(HTTP_EXECUTOR).build();

  private static final int DEFAULT_INVALID_REQUEST_CODE = 418;
  private static final byte[] NO_RESPONSE = "{}".getBytes();

  // Skip the JSON RPC message id as it changes per request.
  private static final int VERSION_PREFIX_LENGTH = """
      {"jsonrpc":"2.0","id":""".length() + 2;

  private static String stripJsonRpcPrefix(final String request) {
    final int offset = request.indexOf("""
        "method":""", VERSION_PREFIX_LENGTH
    );
    return offset < 0 ? request : request.substring(offset);
  }

  private final Queue<TestRequest> expectedRequestQueue;
  private final HttpServer httpServer;
  protected final SolanaRpcClient rpcClient;

  protected RpcRequestTests() {
    try {
      this.httpServer = HttpServer.create(new InetSocketAddress(0), 0);
      httpServer.setExecutor(HTTP_EXECUTOR);
      httpServer.start();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    this.expectedRequestQueue = new ArrayDeque<>();

    final var serverAddress = httpServer.getAddress();
    final var endpoint = URI.create(String.format("http://[%s]:%d", serverAddress.getHostString(), serverAddress.getPort()));
    rpcClient = SolanaRpcClient.createClient(
        endpoint,
        HTTP_CLIENT,
        response -> !Arrays.equals(response.body(), NO_RESPONSE)
    );

    httpServer.createContext("/", this);
  }

  private void writeResponse(final int responseCode,
                             final HttpExchange httpExchange,
                             final byte[] responseBytes) {
    try {
      httpExchange.sendResponseHeaders(responseCode, responseBytes.length);
      try (final var os = httpExchange.getResponseBody()) {
        os.write(responseBytes);
      }
    } catch (final IOException ioEx) {
      throw new UncheckedIOException(ioEx);
    }
  }

  private void writeResponse(final HttpExchange httpExchange, final byte[] responseBytes) {
    writeResponse(200, httpExchange, responseBytes);
  }

  private void writeResponse(final int responseCode, final HttpExchange httpExchange, final String response) {
    writeResponse(responseCode, httpExchange, response.getBytes(UTF_8));
  }

  private void writeResponse(final HttpExchange httpExchange, final String response) {
    writeResponse(200, httpExchange, response);
  }

  @Override
  public final void handle(final HttpExchange exchange) throws IOException {
    assertEquals("POST", exchange.getRequestMethod());
    final var requestBody = new String(exchange.getRequestBody().readAllBytes());
    final var strippedRequestBody = stripJsonRpcPrefix(requestBody);
    final var testRequest = expectedRequestQueue.poll();
    if (testRequest == null) {
      writeResponse(400, exchange, "Expected request queue is empty.");
    } else {
      final var expectedRequest = testRequest.expectedRequest();
      if (expectedRequest == null) {
        writeResponse(400, exchange, "Registered expected request must not be null.");
      } else {
        final var strippedExpectedRequest = stripJsonRpcPrefix(expectedRequest);
        if (strippedExpectedRequest.equals(strippedRequestBody)) {
          final var response = testRequest.response();
          if (response != null) {
            writeResponse(exchange, response);
          } else {
            writeResponse(exchange, NO_RESPONSE);
          }
        } else {
          final var msg = String.format("""
                  Expected request body does not match the actual. Note: The JSON RPC "id" does not matter.
                   - expected: %s
                   - actual:   %s
                  """,
              expectedRequest, requestBody
          );
          logger.log(ERROR, msg);
          writeResponse(testRequest.invalidRequestCode(), exchange, msg);
        }
      }
    }
  }

  private record TestRequest(int invalidRequestCode, String expectedRequest, String response) {

  }

  protected final void registerRequest(final int invalidRequestCode,
                                       final String expectedRequest,
                                       final String response,
                                       final int numInstances) {
    final var testRequest = new TestRequest(
        invalidRequestCode,
        Objects.requireNonNull(expectedRequest),
        response
    );
    for (int i = 0; i < numInstances; i++) {
      expectedRequestQueue.add(testRequest);
    }
  }

  protected final void registerRequest(final int invalidRequestCode,
                                       final String expectedRequest,
                                       final String response) {
    registerRequest(invalidRequestCode, expectedRequest, response, 1);
  }

  protected final void registerRequest(final String expectedRequest,
                                       final String response,
                                       final int numInstances) {
    registerRequest(DEFAULT_INVALID_REQUEST_CODE, expectedRequest, response, numInstances);
  }

  protected final void registerRequest(final int invalidRequestCode,
                                       final String expectedRequest,
                                       final int numInstances) {
    registerRequest(invalidRequestCode, expectedRequest, null, numInstances);
  }

  protected final void registerRequest(final String expectedRequest, final int numInstances) {
    registerRequest(DEFAULT_INVALID_REQUEST_CODE, expectedRequest, null, numInstances);
  }

  protected final void registerRequest(final String expectedRequest, final String response) {
    registerRequest(DEFAULT_INVALID_REQUEST_CODE, expectedRequest, response);
  }

  protected final void registerRequest(final int invalidRequestCode, final String expectedRequest) {
    registerRequest(invalidRequestCode, expectedRequest, null);
  }

  protected final void registerRequest(final String expectedRequest) {
    registerRequest(DEFAULT_INVALID_REQUEST_CODE, expectedRequest, null);
  }

  @AfterEach
  final void verifyEmptyRequestQueue() {
    final int remainingRequests = expectedRequestQueue.size();
    if (remainingRequests > 0) {
      final var msg = expectedRequestQueue.stream()
          .map(TestRequest::expectedRequest)
          .collect(java.util.stream.Collectors.joining(
              "\n * ",
              String.format("Expected zero remaining requests. %d remaining:\n * ", remainingRequests),
              ""
          ));
      Assertions.fail(msg);
    }
  }

  @AfterAll
  protected void shutdown() {
    httpServer.stop(0);
  }
}
