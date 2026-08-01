package software.sava.rpc.json.http.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/// The GET and no-wrap transport routes. `RpcRequestTests` drives every JSON-RPC
/// call through `sendPostRequest`, so these protected members of
/// [JsonHttpClient] — offered to downstream clients — were never entered by the
/// harness (the 2026-07-20 `client` triage records them as its largest
/// `NO_COVERAGE` family, with a local server as the named escape).
///
/// The server echoes the method and path it saw as the response body, so a
/// parser asserting the payload is also asserting, end to end, which HTTP
/// request the route built. What separates the route families is pinned
/// directly:
///
/// - **wrapped** routes hand the parser a [ReadHttpResponse] whenever
///   `testResponse` is configured, and suppress it entirely when the predicate
///   rejects the body;
/// - **no-wrap** routes ignore `testResponse` and hand the parser the raw
///   response from the JDK client;
/// - every route sends the `Content-Type: application/json` header and applies
///   `extendRequest`.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class JsonHttpClientTransportTests {

  static {
    System.setProperty("com.sun.net.httpserver.HttpServerProvider", "sun.net.httpserver.DefaultHttpServerProvider");
  }

  private static final ExecutorService HTTP_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().executor(HTTP_EXECUTOR).build();
  private static final Duration TIMEOUT = Duration.ofSeconds(8);
  private static final String EXTEND_HEADER = "x-extend-request";

  /// What the server observed, alongside the echo it answered with.
  private record Recorded(String method, String path, String contentType, String extendHeader, String body) {
  }

  private final Deque<Recorded> recorded = new ConcurrentLinkedDeque<>();
  private final HttpServer httpServer;
  private final URI endpoint;
  /// `testResponse` configured (accepts any non-empty body) and an
  /// `extendRequest` stamping [#EXTEND_HEADER]: the wrapped routes must hand
  /// their parser a [ReadHttpResponse].
  private final TransportClient wrapping;
  /// No `testResponse` and no `extendRequest`: the defaulted construction path.
  private final TransportClient plain;

  /// Minimal concrete subclass; the routes under test are inherited, and the
  /// test calls them directly via same-package access.
  private static final class TransportClient extends JsonRpcHttpClient {

    TransportClient(final URI endpoint,
                    final Duration requestTimeout,
                    final UnaryOperator<HttpRequest.Builder> extendRequest,
                    final BiPredicate<HttpResponse<?>, byte[]> testResponse) {
      super(endpoint, HTTP_CLIENT, requestTimeout, extendRequest, testResponse);
    }
  }

  JsonHttpClientTransportTests() {
    try {
      this.httpServer = HttpServer.create(new InetSocketAddress(0), 0);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    httpServer.setExecutor(HTTP_EXECUTOR);
    httpServer.createContext("/", this::echo);
    httpServer.start();
    final var serverAddress = httpServer.getAddress();
    this.endpoint = URI.create(String.format("http://[%s]:%d", serverAddress.getHostString(), serverAddress.getPort()));
    this.wrapping = new TransportClient(
        endpoint, TIMEOUT,
        request -> request.header(EXTEND_HEADER, "extended"),
        (_, body) -> body.length > 0
    );
    this.plain = new TransportClient(endpoint, TIMEOUT, null, null);
  }

  private void echo(final HttpExchange exchange) throws IOException {
    final var requestBody = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
    final var requestHeaders = exchange.getRequestHeaders();
    recorded.add(new Recorded(
        exchange.getRequestMethod(),
        exchange.getRequestURI().getPath(),
        requestHeaders.getFirst("Content-Type"),
        requestHeaders.getFirst(EXTEND_HEADER),
        requestBody
    ));
    final var response = String
        .format("{\"method\":\"%s\",\"path\":\"%s\"}", exchange.getRequestMethod(), exchange.getRequestURI().getPath())
        .getBytes(UTF_8);
    exchange.sendResponseHeaders(200, response.length);
    try (final var os = exchange.getResponseBody()) {
      os.write(response);
    }
  }

  @AfterAll
  void shutdown() {
    httpServer.stop(0);
  }

  private Recorded lastRecorded() {
    final var last = recorded.pollLast();
    assertNotNull(last, "the server saw no request");
    return last;
  }

  /// The echoed body a route must produce, asserting method and path in one move.
  private static String echoOf(final String method, final String path) {
    return String.format("{\"method\":\"%s\",\"path\":\"%s\"}", method, path);
  }

  /// For the wrapped routes of [#wrapping]: the parser must receive the
  /// pre-read view, not the raw JDK response.
  private static final Function<HttpResponse<?>, String> WRAPPED_PARSER = response -> {
    assertInstanceOf(ReadHttpResponse.class, response, "wrapped routes hand the parser a ReadHttpResponse");
    return new String(JsonHttpClient.readBody(response), UTF_8);
  };

  /// For the no-wrap routes: `testResponse` and its re-wrapping must both be
  /// bypassed, so the raw response arrives, body unread.
  private static final Function<HttpResponse<?>, String> RAW_PARSER = response -> {
    assertFalse(response instanceof ReadHttpResponse<?>, "no-wrap routes must not pre-read the response");
    return new String(JsonHttpClient.readBody(response), UTF_8);
  };

  // GET, wrapped

  @Test
  void getByPathResolvesAgainstTheEndpointAndWraps() {
    assertEquals(echoOf("GET", "/health"), wrapping.sendGetRequest(WRAPPED_PARSER, "/health").join());
    final var request = lastRecorded();
    assertEquals("GET", request.method());
    assertEquals("application/json", request.contentType());
    assertEquals("extended", request.extendHeader());
    assertEquals("", request.body());
  }

  @Test
  void getByExplicitUriIgnoresTheDefaultEndpoint() {
    final var uri = endpoint.resolve("/explicit-get");
    assertEquals(echoOf("GET", "/explicit-get"), wrapping.sendGetRequest(uri, WRAPPED_PARSER).join());
    assertEquals("extended", lastRecorded().extendHeader());
  }

  /// The rejecting predicate is what `testResponse` exists for; only the wrapped
  /// routes may honour it.
  @Test
  void wrappedGetHonoursARejectingTestResponse() {
    final var rejecting = new TransportClient(endpoint, TIMEOUT, null, (_, _) -> false);
    assertNull(rejecting.sendGetRequest(WRAPPED_PARSER, "/rejected").join());
    assertEquals("/rejected", lastRecorded().path(), "the request must still be sent");
    assertEquals(echoOf("GET", "/accepted"), rejecting.sendGetRequestNoWrap(RAW_PARSER, "/accepted").join());
    lastRecorded();
  }

  // GET, no-wrap

  @Test
  void getNoWrapByPathBypassesTestResponse() {
    assertEquals(echoOf("GET", "/raw"), wrapping.sendGetRequestNoWrap(RAW_PARSER, "/raw").join());
    final var request = lastRecorded();
    assertEquals("application/json", request.contentType());
    assertEquals("extended", request.extendHeader());
  }

  @Test
  void getNoWrapByExplicitUri() {
    final var uri = endpoint.resolve("/raw-uri");
    assertEquals(echoOf("GET", "/raw-uri"), wrapping.sendGetRequestNoWrap(uri, RAW_PARSER).join());
    lastRecorded();
  }

  @Test
  void getNoWrapWithABodyHandlerByPath() {
    final Function<HttpResponse<String>, String> parser = HttpResponse::body;
    assertEquals(
        echoOf("GET", "/handled"),
        plain.sendGetRequestNoWrap(HttpResponse.BodyHandlers.ofString(), parser, "/handled").join()
    );
    assertEquals("GET", lastRecorded().method());
  }

  @Test
  void getNoWrapWithABodyHandlerByExplicitUri() {
    final Function<HttpResponse<String>, String> parser = HttpResponse::body;
    final var uri = endpoint.resolve("/handled-uri");
    assertEquals(
        echoOf("GET", "/handled-uri"),
        plain.sendGetRequestNoWrap(uri, HttpResponse.BodyHandlers.ofString(), parser).join()
    );
    lastRecorded();
  }

  // POST

  @Test
  void postByExplicitUriWraps() {
    final var uri = endpoint.resolve("/post-uri");
    assertEquals(echoOf("POST", "/post-uri"), wrapping.sendPostRequest(uri, WRAPPED_PARSER, "{\"post\":1}").join());
    final var request = lastRecorded();
    assertEquals("POST", request.method());
    assertEquals("application/json", request.contentType());
    assertEquals("{\"post\":1}", request.body());
  }

  @Test
  void postNoWrapSendsTheBodyToTheEndpoint() {
    assertEquals(echoOf("POST", "/"), wrapping.sendPostRequestNoWrap(RAW_PARSER, "{\"noWrap\":1}").join());
    assertEquals("{\"noWrap\":1}", lastRecorded().body());
    assertEquals(echoOf("POST", "/"), wrapping.sendPostRequestNoWrap(RAW_PARSER, TIMEOUT, "{\"noWrap\":2}").join());
    assertEquals("{\"noWrap\":2}", lastRecorded().body());
  }

  @Test
  void postNoWrapByExplicitUri() {
    final var uri = endpoint.resolve("/raw-post");
    assertEquals(echoOf("POST", "/raw-post"), wrapping.sendPostRequestNoWrap(uri, RAW_PARSER, "{\"noWrap\":3}").join());
    assertEquals("{\"noWrap\":3}", lastRecorded().body());
    assertEquals(
        echoOf("POST", "/raw-post"),
        wrapping.sendPostRequestNoWrap(uri, RAW_PARSER, TIMEOUT, "{\"noWrap\":4}").join()
    );
    assertEquals("{\"noWrap\":4}", lastRecorded().body());
  }

  @Test
  void postNoWrapWithABodyHandler() {
    final Function<HttpResponse<String>, String> parser = HttpResponse::body;
    final var asString = HttpResponse.BodyHandlers.ofString();
    assertEquals(echoOf("POST", "/"), plain.sendPostRequestNoWrap(asString, parser, "{\"handled\":1}").join());
    assertEquals("{\"handled\":1}", lastRecorded().body());
    assertEquals(echoOf("POST", "/"), plain.sendPostRequestNoWrap(asString, parser, TIMEOUT, "{\"handled\":2}").join());
    assertEquals("{\"handled\":2}", lastRecorded().body());
  }

  @Test
  void postNoWrapWithABodyHandlerByExplicitUri() {
    final Function<HttpResponse<String>, String> parser = HttpResponse::body;
    final var asString = HttpResponse.BodyHandlers.ofString();
    final var uri = endpoint.resolve("/handled-post");
    assertEquals(
        echoOf("POST", "/handled-post"),
        plain.sendPostRequestNoWrap(uri, asString, parser, "{\"handled\":3}").join()
    );
    assertEquals("{\"handled\":3}", lastRecorded().body());
    assertEquals(
        echoOf("POST", "/handled-post"),
        plain.sendPostRequestNoWrap(uri, asString, parser, TIMEOUT, "{\"handled\":4}").join()
    );
    assertEquals("{\"handled\":4}", lastRecorded().body());
  }

  // Request builders — the built request is inspectable directly, no server round
  // trip in the way.

  @Test
  void timeoutOnlyRequestBuilderTargetsTheEndpointWithThatTimeout() {
    final var timeout = Duration.ofMillis(123);
    final var request = plain
        .newRequest(timeout, "PUT", HttpRequest.BodyPublishers.ofString("x"))
        .build();
    assertEquals(endpoint, request.uri());
    assertEquals("PUT", request.method());
    assertEquals(timeout, request.timeout().orElseThrow());
    assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
  }
}
