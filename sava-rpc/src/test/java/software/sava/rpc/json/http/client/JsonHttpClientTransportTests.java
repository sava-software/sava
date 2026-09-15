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
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
  /// Held by every `/stall` handler after it has sent its headers and the first bytes of the
  /// body; released at shutdown so the server can stop.
  private final CountDownLatch releaseStalls = new CountDownLatch(1);
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
      this(endpoint, HTTP_CLIENT, requestTimeout, extendRequest, testResponse);
    }

    TransportClient(final URI endpoint,
                    final HttpClient httpClient,
                    final Duration requestTimeout,
                    final UnaryOperator<HttpRequest.Builder> extendRequest,
                    final BiPredicate<HttpResponse<?>, byte[]> testResponse) {
      super(endpoint, httpClient, requestTimeout, extendRequest, testResponse);
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
    httpServer.createContext("/stall", this::stall);
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

  /// What the server echoes back, and therefore what a route's parser must
  /// produce — asserting method and path in one move.
  private static String echoOf(final String method, final String path) {
    return """
        {"method":"%s","path":"%s"}""".formatted(method, path);
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
    final var response = echoOf(exchange.getRequestMethod(), exchange.getRequestURI().getPath()).getBytes(UTF_8);
    exchange.sendResponseHeaders(200, response.length);
    try (final var os = exchange.getResponseBody()) {
      os.write(response);
    }
  }

  /// Headers and the opening of a JSON body, then nothing: the shape of a node whose response
  /// stalls mid-stream, which the JDK request timeout (headers only) never ends.
  private void stall(final HttpExchange exchange) throws IOException {
    exchange.getRequestBody().readAllBytes();
    exchange.sendResponseHeaders(200, 0);
    final var os = exchange.getResponseBody();
    os.write("{\"result\":".getBytes(UTF_8));
    os.flush();
    try {
      releaseStalls.await();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      exchange.close();
    }
  }

  @AfterAll
  void shutdown() {
    releaseStalls.countDown();
    httpServer.stop(0);
  }

  private Recorded lastRecorded() {
    final var last = recorded.pollLast();
    assertNotNull(last, "the server saw no request");
    return last;
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
    final var body = """
        {"post":1}""";
    assertEquals(echoOf("POST", "/post-uri"), wrapping.sendPostRequest(uri, WRAPPED_PARSER, body).join());
    final var request = lastRecorded();
    assertEquals("POST", request.method());
    assertEquals("application/json", request.contentType());
    assertEquals(body, request.body());
  }

  @Test
  void postNoWrapSendsTheBodyToTheEndpoint() {
    final var body = """
        {"noWrap":1}""";
    assertEquals(echoOf("POST", "/"), wrapping.sendPostRequestNoWrap(RAW_PARSER, body).join());
    assertEquals(body, lastRecorded().body());
    final var timeoutBody = """
        {"noWrap":2}""";
    assertEquals(echoOf("POST", "/"), wrapping.sendPostRequestNoWrap(RAW_PARSER, TIMEOUT, timeoutBody).join());
    assertEquals(timeoutBody, lastRecorded().body());
  }

  @Test
  void postNoWrapByExplicitUri() {
    final var uri = endpoint.resolve("/raw-post");
    final var body = """
        {"noWrap":3}""";
    assertEquals(echoOf("POST", "/raw-post"), wrapping.sendPostRequestNoWrap(uri, RAW_PARSER, body).join());
    assertEquals(body, lastRecorded().body());
    final var timeoutBody = """
        {"noWrap":4}""";
    assertEquals(
        echoOf("POST", "/raw-post"),
        wrapping.sendPostRequestNoWrap(uri, RAW_PARSER, TIMEOUT, timeoutBody).join()
    );
    assertEquals(timeoutBody, lastRecorded().body());
  }

  @Test
  void postNoWrapWithABodyHandler() {
    final Function<HttpResponse<String>, String> parser = HttpResponse::body;
    final var asString = HttpResponse.BodyHandlers.ofString();
    final var body = """
        {"handled":1}""";
    assertEquals(echoOf("POST", "/"), plain.sendPostRequestNoWrap(asString, parser, body).join());
    assertEquals(body, lastRecorded().body());
    final var timeoutBody = """
        {"handled":2}""";
    assertEquals(echoOf("POST", "/"), plain.sendPostRequestNoWrap(asString, parser, TIMEOUT, timeoutBody).join());
    assertEquals(timeoutBody, lastRecorded().body());
  }

  @Test
  void postNoWrapWithABodyHandlerByExplicitUri() {
    final Function<HttpResponse<String>, String> parser = HttpResponse::body;
    final var asString = HttpResponse.BodyHandlers.ofString();
    final var uri = endpoint.resolve("/handled-post");
    final var body = """
        {"handled":3}""";
    assertEquals(
        echoOf("POST", "/handled-post"),
        plain.sendPostRequestNoWrap(uri, asString, parser, body).join()
    );
    assertEquals(body, lastRecorded().body());
    final var timeoutBody = """
        {"handled":4}""";
    assertEquals(
        echoOf("POST", "/handled-post"),
        plain.sendPostRequestNoWrap(uri, asString, parser, TIMEOUT, timeoutBody).join()
    );
    assertEquals(timeoutBody, lastRecorded().body());
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

  // the exchange deadline

  /// On JDK 25 the JDK request timeout stops at the headers, so a stalled body is ended by
  /// this client's scheduled cancellation at twice the request timeout and the failure's
  /// cause is the JDK's `CancellationException`. On JDK 26 the request timeout covers the
  /// body itself, so the JDK ends the stall at one timeout with an `HttpTimeoutException`
  /// and the cancellation never fires. Both are the contract; which one applies is the
  /// runtime's.
  private static void assertStallEnded(final CompletableFuture<?> response,
                                       final Duration requestTimeout,
                                       final long startedNanos,
                                       final String route) {
    final var failure = assertThrows(ExecutionException.class, () -> response.get(2, TimeUnit.SECONDS), route);
    final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    if (Runtime.version().feature() >= 26) {
      assertInstanceOf(HttpTimeoutException.class, failure.getCause(), route + ": JDK 26 ends a stalled body itself");
      assertTrue(elapsedMillis >= requestTimeout.toMillis(), () -> route + ": ended after " + elapsedMillis + "ms");
    } else {
      assertInstanceOf(CancellationException.class, failure.getCause(), route + ": the scheduled cancellation ends it on JDK 25");
      assertTrue(elapsedMillis >= requestTimeout.toMillis() * 2,
          () -> route + ": cancelled after " + elapsedMillis + "ms, before the body had its own budget");
    }
    assertTrue(response.isCompletedExceptionally(), route);
  }

  /// A body that stalls after the headers must not leave the request pending (or a thread
  /// parked reading it) for good: the future the route hands back fails -- an ordinary
  /// failed call to a retrying caller -- instead of hanging; see [#assertStallEnded] for
  /// which mechanism ends it on which JDK. Checked on the wrapped GET and POST routes, the
  /// two the JSON-RPC clients use.
  @Test
  void aBodyThatStallsAfterTheHeadersIsCancelledAtTwiceTheRequestTimeout() {
    final var requestTimeout = Duration.ofMillis(200);
    final var client = new TransportClient(endpoint, requestTimeout, null, (_, body) -> body.length > 0);

    for (final var route : new String[]{"GET", "POST"}) {
      final long started = System.nanoTime();
      final var response = route.equals("GET")
          ? client.sendGetRequest(WRAPPED_PARSER, "/stall")
          : client.sendPostRequest(endpoint.resolve("/stall"), WRAPPED_PARSER, "{}");
      assertStallEnded(response, requestTimeout, started, route);
    }
  }

  /// The no-wrap routes carry the same deadline (they read the body too), while a
  /// body-handler route leaves the body to its handler: with a streaming handler the
  /// response completes at the headers, the stalled body is the handler's to read, and the
  /// exchange deadline never touches it.
  @Test
  void theNoWrapRoutesShareTheDeadlineAndTheBodyHandlerRoutesDoNot() throws Exception {
    final var requestTimeout = Duration.ofMillis(200);
    final var client = new TransportClient(endpoint, requestTimeout, null, null);

    final long started = System.nanoTime();
    final var noWrap = client.sendGetRequestNoWrap(RAW_PARSER, "/stall");
    assertStallEnded(noWrap, requestTimeout, started, "no-wrap GET");

    final var handled = client.sendGetRequestNoWrap(HttpResponse.BodyHandlers.ofInputStream(), HttpResponse::statusCode, "/stall");
    assertEquals(200, handled.get(2, TimeUnit.SECONDS),
        "a caller-supplied handler completes at the headers; the body and its timing are the handler's");
    Thread.sleep(requestTimeout.toMillis() * 3); // past the exchange deadline: nothing cancels a handler route
    assertFalse(handled.isCompletedExceptionally());
  }

  /// A response that completes in time is untouched by the deadline: the timer is released
  /// when the response arrives, and waiting past the deadline changes nothing.
  @Test
  void aTimelyResponseIsUnaffectedByTheDeadline() throws InterruptedException {
    final var requestTimeout = Duration.ofMillis(200);
    final var client = new TransportClient(endpoint, requestTimeout, null, null);

    final var response = client.sendGetRequest(RAW_PARSER, "/timely");
    assertEquals(echoOf("GET", "/timely"), response.join());
    Thread.sleep(requestTimeout.toMillis() * 3);
    assertEquals(echoOf("GET", "/timely"), response.join());
    assertFalse(response.isCancelled());
  }

  /// `extendRequest` may replace the request timeout; the exchange deadline follows the
  /// timeout on the built request, not the client default. Pinned end to end in the fast
  /// direction: a five-second default overridden down to 200 ms ends within this test's
  /// two-second bound, where a deadline derived from the default would still be waiting.
  @Test
  void theDeadlineRespectsATimeoutOverriddenByExtendRequest() {
    final var overridden = Duration.ofMillis(200);
    final var client = new TransportClient(endpoint, Duration.ofSeconds(5), request -> request.timeout(overridden), null);

    final long started = System.nanoTime();
    final var response = client.sendGetRequest(RAW_PARSER, "/stall");
    assertStallEnded(response, overridden, started, "overridden GET");
  }

  /// A submission the client's executor rejects fails synchronously, before any deadline
  /// exists: the sentinel is scheduled only once `sendAsync` has returned the future that
  /// releases it, so a burst of rejected attempts leaves nothing on the JDK delayer. The
  /// retention itself is not observable without reflection; this pins the path the ordering
  /// protects, on the wrapped and the no-wrap routes.
  @Test
  void aRejectedSubmissionThrowsSynchronously() {
    final var rejecting = HttpClient.newBuilder()
        .executor(_ -> {
          throw new RejectedExecutionException("no threads");
        })
        .build();
    final var client = new TransportClient(endpoint, rejecting, TIMEOUT, null, null);

    assertThrows(RejectedExecutionException.class, () -> client.sendGetRequest(RAW_PARSER, "/timely"));
    assertThrows(RejectedExecutionException.class, () -> client.sendPostRequestNoWrap(RAW_PARSER, "{}"));
  }

  /// The deadline is a whole-exchange bound of twice the request timeout, not a second
  /// headers-only timer.
  @Test
  void theResponseDeadlineIsTwiceTheRequestTimeout() {
    assertEquals(Duration.ofSeconds(16).toNanos(), JsonHttpClient.responseDeadlineNanos(Duration.ofSeconds(8)));
    assertEquals(Duration.ofMillis(500).toNanos(), JsonHttpClient.responseDeadlineNanos(Duration.ofMillis(250)));
  }
}
