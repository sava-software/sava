package software.sava.rpc.json.http.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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

    /// `deadlineScheduler` is where every route that reads the body arms its exchange
    /// deadline.
    TransportClient(final URI endpoint,
                    final HttpClient httpClient,
                    final Duration requestTimeout,
                    final UnaryOperator<HttpRequest.Builder> extendRequest,
                    final BiPredicate<HttpResponse<?>, byte[]> testResponse,
                    final ScheduledExecutorService deadlineScheduler) {
      super(endpoint, httpClient, requestTimeout, extendRequest, testResponse, deadlineScheduler);
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
  /// stalls mid-stream, which JDK 25's request timeout (headers only) never ends.
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

  // the exchange deadline: armed on a recording scheduler and fired by the test, never
  // waited out, so nothing below depends on how long anything takes

  /// One timer as a route armed it. Nothing runs it until the test calls [#fire], which
  /// is the deadline arriving.
  private record Deadline(Runnable task, long delayNanos) {

    void fire() {
      task.run();
    }
  }

  /// A client's deadline scheduler that records every timer a route arms and runs none;
  /// the release a settled response asks of its timer is accepted and changes nothing.
  /// Any other scheduler or timer method throws, so a route that reached for one fails
  /// loudly instead of going unrecorded.
  private static final class DeadlineRecorder {

    final List<Deadline> armed = new CopyOnWriteArrayList<>();
    final ScheduledExecutorService scheduler = (ScheduledExecutorService) Proxy.newProxyInstance(
        ScheduledExecutorService.class.getClassLoader(),
        new Class<?>[]{ScheduledExecutorService.class},
        (_, method, args) -> {
          if (!method.getName().equals("schedule") || !(args[0] instanceof Runnable task)) {
            throw new UnsupportedOperationException(method.getName());
          }
          armed.add(new Deadline(task, ((TimeUnit) args[2]).toNanos((long) args[1])));
          return Proxy.newProxyInstance(
              ScheduledFuture.class.getClassLoader(),
              new Class<?>[]{ScheduledFuture.class},
              (_, timerMethod, _) -> {
                if (!timerMethod.getName().equals("cancel")) {
                  throw new UnsupportedOperationException(timerMethod.getName());
                }
                return Boolean.TRUE;
              }
          );
        }
    );

    /// The one timer armed so far.
    Deadline only() {
      assertEquals(1, armed.size(), "one route call arms exactly one deadline");
      return armed.getFirst();
    }
  }

  /// The shared JDK client with each exchange left observable: the request a route
  /// built, the arrival of its response headers at the handler the route passed, and the
  /// JDK's response future, handed to the route unchanged so that cancelling it still
  /// aborts the exchange.
  ///
  /// That future is the one thing settled when a fired deadline's `cancel(true)`
  /// returns. The JDK often completes a cancelled response from its own pool while the
  /// cancellation is still running, so the route's future may still be completing then:
  /// a test checks the JDK's future at once and only then waits for the route's.
  private static final class ObservedHttpClient extends HttpClient {

    /// `headers` completes with the status when the response headers arrive, or fails
    /// with the exchange if that ends first, so waiting for it is bounded by the request
    /// timeout alone.
    record Exchange(HttpRequest request, CompletableFuture<Integer> headers, CompletableFuture<?> response) {
    }

    final List<Exchange> exchanges = new CopyOnWriteArrayList<>();

    /// The one exchange sent so far.
    Exchange only() {
      assertEquals(1, exchanges.size(), "one route call sends exactly one request");
      return exchanges.getFirst();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                            final HttpResponse.BodyHandler<T> bodyHandler) {
      final var headers = new CompletableFuture<Integer>();
      final var response = HTTP_CLIENT.sendAsync(request, responseInfo -> {
        headers.complete(responseInfo.statusCode());
        return bodyHandler.apply(responseInfo);
      });
      response.whenComplete((_, failure) -> {
        if (failure != null) {
          headers.completeExceptionally(failure);
        }
      });
      exchanges.add(new Exchange(request, headers, response));
      return response;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request,
                                                            final HttpResponse.BodyHandler<T> bodyHandler,
                                                            final HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException("no route sends with a push promise handler");
    }

    @Override
    public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> bodyHandler) {
      throw new UnsupportedOperationException("every route sends asynchronously");
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return HTTP_CLIENT.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return HTTP_CLIENT.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
      return HTTP_CLIENT.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return HTTP_CLIENT.proxy();
    }

    @Override
    public SSLContext sslContext() {
      return HTTP_CLIENT.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
      return HTTP_CLIENT.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return HTTP_CLIENT.authenticator();
    }

    @Override
    public Version version() {
      return HTTP_CLIENT.version();
    }

    @Override
    public Optional<Executor> executor() {
      return HTTP_CLIENT.executor();
    }
  }

  /// A stall as this client sees it: the headers have arrived and the body has not, so
  /// the route is pending under its one deadline, armed for `deadline`. Firing it must
  /// end the route with the JDK's `CancellationException`, an ordinary failed call to a
  /// retrying caller. The route is waited on only once the JDK's response future is
  /// done, so the wait cannot hang, and its cause is checked rather than `isCancelled`:
  /// when the JDK's own completion wins, the cancellation arrives wrapped.
  private static void assertTheDeadlineEndsTheStall(final ObservedHttpClient.Exchange exchange,
                                                    final Deadline armed,
                                                    final Duration deadline,
                                                    final CompletableFuture<?> route,
                                                    final String name) {
    assertEquals(deadline.toNanos(), armed.delayNanos(), name + ": the deadline");
    assertEquals(200, exchange.headers().join(), name + ": the headers reach this client");
    assertFalse(route.isDone(), name + ": a body that stalls after the headers leaves the route pending");

    armed.fire();

    assertTrue(exchange.response().isDone(), name + ": the fired deadline cancels the exchange's response");
    final var failure = assertThrows(ExecutionException.class, route::get, name);
    assertInstanceOf(CancellationException.class, failure.getCause(), name + ": the route fails with the cancellation");
  }

  /// A body that stalls after the headers must not leave the request pending (or a
  /// thread parked reading it) for good: the route arms one deadline at twice the request
  /// timeout, and when it fires, the future the route handed back fails instead of
  /// hanging. Checked on the wrapped GET and POST routes, the two the JSON-RPC clients use.
  ///
  /// The test fires the deadline itself, long before the request timeout could expire, so
  /// the JDK's own timer never takes part. It stops at the headers on JDK 25; on JDK 26 it
  /// covers the body too, and which of the two ends a real stall first there is the
  /// runtime's.
  @Test
  void aBodyThatStallsAfterTheHeadersIsCancelledAtTwiceTheRequestTimeout() {
    for (final var route : new String[]{"GET", "POST"}) {
      final var http = new ObservedHttpClient();
      final var deadlines = new DeadlineRecorder();
      final var client = new TransportClient(
          endpoint, http, TIMEOUT, null, (_, body) -> body.length > 0, deadlines.scheduler
      );

      final var response = route.equals("GET")
          ? client.sendGetRequest(WRAPPED_PARSER, "/stall")
          : client.sendPostRequest(endpoint.resolve("/stall"), WRAPPED_PARSER, "{}");
      assertTheDeadlineEndsTheStall(http.only(), deadlines.only(), Duration.ofSeconds(16), response, route);
    }
  }

  /// The no-wrap routes carry the same deadline (they read the body too), while a
  /// body-handler route leaves the body to its handler: with a streaming handler the
  /// response completes at the headers and this client arms no deadline for it, so
  /// nothing exists that could fail the completed future later, however long the body
  /// stalls. That is all this pins: whether the stalled stream itself survives is the
  /// runtime's -- JDK 26's request timeout ends it, JDK 25's does not -- and is not
  /// asserted here.
  @Test
  void theNoWrapRoutesShareTheDeadlineAndTheBodyHandlerRoutesDoNot() {
    final var http = new ObservedHttpClient();
    final var deadlines = new DeadlineRecorder();
    final var client = new TransportClient(endpoint, http, TIMEOUT, null, null, deadlines.scheduler);

    final var noWrap = client.sendGetRequestNoWrap(RAW_PARSER, "/stall");
    assertTheDeadlineEndsTheStall(http.only(), deadlines.only(), Duration.ofSeconds(16), noWrap, "no-wrap GET");

    final var handled = client.sendGetRequestNoWrap(HttpResponse.BodyHandlers.ofInputStream(), HttpResponse::statusCode, "/stall");
    assertEquals(1, deadlines.armed.size(), "a body-handler route arms no deadline");
    assertEquals(200, handled.join(),
        "a caller-supplied handler completes at the headers; the body and its timing are the handler's");
  }

  /// A response that completes in time is untouched by the deadline: the route arms its
  /// one timer, and a timer that fires after the response arrived -- one a scheduler
  /// kept -- finds a finished exchange and changes nothing. Releasing the timer on
  /// arrival is pinned by `JsonHttpClientDeadlineTests`, where the response completes on
  /// the test's own thread; here the release runs on the JDK's pool after the route's
  /// future completes, where only an open-ended wait could observe it.
  @Test
  void aTimelyResponseIsUnaffectedByTheDeadline() {
    final var http = new ObservedHttpClient();
    final var deadlines = new DeadlineRecorder();
    final var client = new TransportClient(endpoint, http, TIMEOUT, null, null, deadlines.scheduler);

    final var response = client.sendGetRequest(RAW_PARSER, "/timely");
    final var deadline = deadlines.only();
    assertEquals(Duration.ofSeconds(16).toNanos(), deadline.delayNanos());
    assertEquals(echoOf("GET", "/timely"), response.join());
    assertEquals("/timely", lastRecorded().path());

    deadline.fire();

    assertEquals(echoOf("GET", "/timely"), response.join());
    assertFalse(response.isCancelled());
    assertFalse(http.only().response().isCancelled(), "a late deadline cannot cancel a finished exchange");
  }

  /// `extendRequest` may replace the request timeout; the exchange deadline follows the
  /// timeout on the built request, not the client default. A five-second default
  /// overridden down to three seconds arms a six-second deadline, where one derived from
  /// the default would be ten, and firing that deadline ends the stalled exchange.
  @Test
  void theDeadlineRespectsATimeoutOverriddenByExtendRequest() {
    final var overridden = Duration.ofSeconds(3);
    final var http = new ObservedHttpClient();
    final var deadlines = new DeadlineRecorder();
    final var client = new TransportClient(
        endpoint, http, Duration.ofSeconds(5), request -> request.timeout(overridden), null, deadlines.scheduler
    );

    final var response = client.sendGetRequest(RAW_PARSER, "/stall");
    final var exchange = http.only();
    assertEquals(overridden, exchange.request().timeout().orElseThrow(), "the built request carries the override");
    assertTheDeadlineEndsTheStall(exchange, deadlines.only(), Duration.ofSeconds(6), response, "overridden GET");
  }

  /// Executor rejection remains synchronous on the wrapped and no-wrap routes. The
  /// recording-scheduler tests separately assert that rejection schedules no deadline.
  @Test
  void aRejectedSubmissionThrowsSynchronously() {
    try (final var rejecting = HttpClient.newBuilder()
        .executor(_ -> {
          throw new RejectedExecutionException("no threads");
        })
        .build()) {
      final var client = new TransportClient(endpoint, rejecting, TIMEOUT, null, null);

      assertThrows(RejectedExecutionException.class, () -> client.sendGetRequest(RAW_PARSER, "/timely"));
      assertThrows(RejectedExecutionException.class, () -> client.sendPostRequestNoWrap(RAW_PARSER, "{}"));
    }
  }

  /// The deadline is a whole-exchange bound of twice the request timeout: one for the headers
  /// (all the JDK 25 timer covers) and the same again for the body.
  @Test
  void theResponseDeadlineIsTwiceTheRequestTimeout() {
    assertEquals(Duration.ofSeconds(16).toNanos(), JsonHttpClient.responseDeadlineNanos(Duration.ofSeconds(8)));
    assertEquals(Duration.ofMillis(500).toNanos(), JsonHttpClient.responseDeadlineNanos(Duration.ofMillis(250)));
  }
}
