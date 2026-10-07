package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.UnknownServiceException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.Function;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

/// Request construction and the plain (non JSON-RPC) response gate. The overload
/// tree exists so callers can vary endpoint, path, timeout and method
/// independently; every one of them has to end up with the JSON content type, the
/// caller's `extendRequest` applied, and paths resolved against the client
/// endpoint rather than replacing it.
final class JsonHttpClientRequestTests {

  private static final URI ENDPOINT = URI.create("https://rpc.example.invalid/v1/");
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /// Minimal concrete client — `newRequest` is protected, so reaching it needs a
  /// subclass rather than a stub.
  private static final class TestClient extends JsonHttpClient {

    TestClient(final HttpClient httpClient, final java.util.function.UnaryOperator<HttpRequest.Builder> extend) {
      super(ENDPOINT, httpClient, DEFAULT_TIMEOUT, extend, null);
    }

    HttpRequest.Builder request() {
      return newRequest();
    }

    HttpRequest.Builder request(final Duration timeout) {
      return newRequest(timeout);
    }

    HttpRequest.Builder request(final URI uri) {
      return newRequest(uri);
    }

    HttpRequest.Builder request(final String path) {
      return newRequest(path);
    }

    HttpRequest.Builder request(final String path, final Duration timeout) {
      return newRequest(path, timeout);
    }

    HttpRequest.Builder post(final String method, final String body) {
      return newRequest(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }

    HttpRequest.Builder post(final String path, final String method, final String body) {
      return newRequest(path, method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }

    HttpRequest postRequest(final URI endpoint, final Duration timeout, final String body) {
      return newPostRequest(endpoint, timeout, body);
    }
  }

  /// Reads a request's body back through its publisher, the way the JDK client sends it.
  private static byte[] bodyBytes(final HttpRequest request) {
    final var collected = new CompletableFuture<byte[]>();
    request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
      private final ByteArrayOutputStream out = new ByteArrayOutputStream();

      @Override
      public void onSubscribe(final Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(final ByteBuffer item) {
        final byte[] chunk = new byte[item.remaining()];
        item.get(chunk);
        out.writeBytes(chunk);
      }

      @Override
      public void onError(final Throwable throwable) {
        collected.completeExceptionally(throwable);
      }

      @Override
      public void onComplete() {
        collected.complete(out.toByteArray());
      }
    });
    // BodyPublishers.ofString publishes synchronously on request(), so the future is complete
    return collected.join();
  }

  private static TestClient client(final HttpClient httpClient) {
    return new TestClient(httpClient, null);
  }

  @Test
  void defaultRequestUsesTheEndpointContentTypeAndTimeout() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var request = client(httpClient).request().build();
      assertEquals(ENDPOINT, request.uri());
      assertEquals("application/json", request.headers().firstValue("Content-Type").orElse(null));
      assertEquals(DEFAULT_TIMEOUT, request.timeout().orElse(null));
    }
  }

  @Test
  void perRequestTimeoutOverridesTheDefault() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var request = client(httpClient).request(Duration.ofSeconds(3)).build();
      assertEquals(Duration.ofSeconds(3), request.timeout().orElse(null));
      assertEquals(ENDPOINT, request.uri(), "a timeout override must not change the endpoint");
    }
  }

  @Test
  void explicitUriReplacesTheEndpoint() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var other = URI.create("https://other.example.invalid/rpc");
      final var request = client(httpClient).request(other).build();
      assertEquals(other, request.uri());
      assertEquals(DEFAULT_TIMEOUT, request.timeout().orElse(null));
    }
  }

  /// Paths resolve against the endpoint, so a relative path extends it and a
  /// rooted path replaces the endpoint's path.
  @Test
  void pathsResolveAgainstTheEndpoint() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var testClient = client(httpClient);
      assertEquals(URI.create("https://rpc.example.invalid/v1/health"),
          testClient.request("health").build().uri());
      assertEquals(URI.create("https://rpc.example.invalid/absolute"),
          testClient.request("/absolute").build().uri());
      assertEquals(URI.create("https://rpc.example.invalid/v1/"),
          testClient.request("").build().uri());
    }
  }

  @Test
  void pathAndTimeoutCombine() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var request = client(httpClient).request("health", Duration.ofSeconds(2)).build();
      assertEquals(URI.create("https://rpc.example.invalid/v1/health"), request.uri());
      assertEquals(Duration.ofSeconds(2), request.timeout().orElse(null));
    }
  }

  @Test
  void methodAndBodyAreSetOnPostStyleRequests() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var request = client(httpClient).post("POST", "{\"a\":1}").build();
      assertEquals("POST", request.method());
      assertEquals(ENDPOINT, request.uri());
      assertTrue(request.bodyPublisher().isPresent());
      assertEquals("application/json", request.headers().firstValue("Content-Type").orElse(null));
    }
  }

  @Test
  void pathIsResolvedForPostStyleRequestsToo() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var request = client(httpClient).post("submit", "PUT", "{}").build();
      assertEquals(URI.create("https://rpc.example.invalid/v1/submit"), request.uri());
      assertEquals("PUT", request.method());
    }
  }

  /// A null extendRequest must behave as identity rather than throwing — the
  /// constructor substitutes one.
  @Test
  void nullExtendRequestIsTreatedAsIdentity() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      assertDoesNotThrow(() -> client(httpClient).request().build());
    }
  }

  @Test
  void extendRequestAppliesToEveryOverload() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var testClient = new TestClient(httpClient, request -> request.header("X-Tag", "seen"));
      for (final var builder : new HttpRequest.Builder[]{
          testClient.request(),
          testClient.request(Duration.ofSeconds(1)),
          testClient.request(URI.create("https://other.example.invalid")),
          testClient.request("health"),
          testClient.request("health", Duration.ofSeconds(1)),
          testClient.post("POST", "{}"),
          testClient.post("submit", "POST", "{}")}) {
        assertEquals("seen", builder.build().headers().firstValue("X-Tag").orElse(null));
      }
    }
  }

  /// `newPostRequest` writes the body to a DEBUG log line before building the request.
  /// Unlike the parse-failure tails `TestLogs` was written for, that line is not the only
  /// record of anything: the body is the caller's own request, carried verbatim by the
  /// request this returns. The caller's `extendRequest` is handed a builder already holding
  /// it, the caller's `HttpClient` receives the built request, and the server reads it off
  /// the wire (`RpcRequestTests` asserts it per method). So the request must be the same
  /// whether the DEBUG line runs or is suppressed, and must carry the body byte for byte.
  @Test
  void postRequestCarriesItsBodyWhetherOrNotTheDebugLineRuns() {
    final var target = URI.create("https://other.example.invalid/rpc");
    final var timeout = Duration.ofSeconds(17);
    // non-ASCII, so a publisher that lost the charset would not round-trip
    final var body = """
        {"jsonrpc":"2.0","id":7,"method":"getAccountInfo","params":["Ünïcødé"]}""";
    final byte[] expected = body.getBytes(StandardCharsets.UTF_8);
    final var extenderSaw = new ArrayList<byte[]>();
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var testClient = new TestClient(httpClient, builder -> {
        extenderSaw.add(bodyBytes(builder.copy().build()));
        return builder.header("X-Tag", "seen");
      });
      final var requests = new ArrayList<HttpRequest>();
      // ALL runs the DEBUG line through the JUL backend; OFF suppresses it
      TestLogs.capture(JsonHttpClient.class, Level.ALL,
          () -> requests.add(testClient.postRequest(target, timeout, body)));
      TestLogs.capture(JsonHttpClient.class, Level.OFF,
          () -> requests.add(testClient.postRequest(target, timeout, body)));

      assertEquals(2, requests.size());
      for (final var request : requests) {
        assertEquals("POST", request.method());
        assertEquals(target, request.uri());
        assertEquals(timeout, request.timeout().orElseThrow());
        assertEquals(List.of("application/json"), request.headers().allValues("Content-Type"));
        assertEquals(List.of("seen"), request.headers().allValues("X-Tag"));
        assertEquals(2, request.headers().map().size(), () -> request.headers().map().toString());
        assertEquals(expected.length, request.bodyPublisher().orElseThrow().contentLength());
        assertArrayEquals(expected, bodyBytes(request));
      }
      assertEquals(2, extenderSaw.size());
      for (final var seen : extenderSaw) {
        assertArrayEquals(expected, seen, "extendRequest is handed the body before the build");
      }
    }
  }

  @Test
  void accessorsReportTheConfiguredValues() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var testClient = client(httpClient);
      assertEquals(ENDPOINT, testClient.endpoint());
      assertSame(httpClient, testClient.httpClient());
      assertEquals(DEFAULT_TIMEOUT, testClient.defaultRequestTimeout());
    }
  }

  @Test
  void nullTestResponsePassesTheOriginalResponseToTheParser() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var response = StubHttpResponse.of(new byte[]{1, 2, 3});
      final var parsed = new HttpResponse<?>[1];
      final Function<HttpResponse<?>, HttpResponse<?>> parser = candidate -> {
        parsed[0] = candidate;
        return candidate;
      };

      final var wrapped = client(httpClient).wrapResponseParser(parser);
      assertSame(response, wrapped.apply(response));
      assertSame(response, parsed[0],
          "without a predicate, the original response must reach the parser");
    }
  }

  /// The plain controller — used by the non JSON-RPC paths — gates only on the
  /// status code, with no envelope to inspect.
  @Test
  void plainControllerRejectsNonSuccessStatuses() {
    final var parser = JsonHttpClient.applyGenericResponse(ji -> ji.readString());
    for (final int status : new int[]{199, 300, 400, 404, 500}) {
      final var ex = assertThrows(UncheckedIOException.class,
          () -> parser.apply(StubHttpResponse.of(status, "\"ok\"".getBytes(StandardCharsets.UTF_8))),
          "status " + status);
      assertInstanceOf(UnknownServiceException.class, ex.getCause());
    }
  }

  @Test
  void plainControllerParsesSuccessBodies() {
    final var parser = JsonHttpClient.applyGenericResponse(ji -> ji.readString());
    for (final int status : new int[]{200, 204, 299}) {
      assertEquals("ok", parser.apply(
          StubHttpResponse.of(status, "\"ok\"".getBytes(StandardCharsets.UTF_8))), "status " + status);
    }
  }

  /// A null body is not an error on the plain path — it parses to null, so a
  /// caller sees "no content" rather than an exception.
  @Test
  void plainControllerReturnsNullForAnEmptyBody() {
    final var parser = JsonHttpClient.applyGenericResponse(ji -> ji.readString());
    assertNull(parser.apply(StubHttpResponse.of(200, (byte[]) null)));
  }

  /// The failure message carries the body so a caller can see what arrived.
  @Test
  void plainControllerFailureIncludesStatusAndBody() {
    final var parser = JsonHttpClient.applyGenericResponse(ji -> ji.readString());
    final var ex = assertThrows(UncheckedIOException.class, () -> parser.apply(
        StubHttpResponse.of(503, "upstream down".getBytes(StandardCharsets.UTF_8))));
    assertTrue(ex.getMessage().contains("httpCode:503"), ex.getMessage());
    assertTrue(ex.getMessage().contains("upstream down"), ex.getMessage());
  }

  /// ReadHttpResponse wraps a response with an already-read body; everything other
  /// than the body must delegate to the wrapped response.
  @Test
  void readHttpResponseDelegatesEverythingButTheBody() {
    final var wrapped = StubHttpResponse.of(418, "original".getBytes(StandardCharsets.UTF_8), "X-H", "v");
    final byte[] alreadyRead = "read".getBytes(StandardCharsets.UTF_8);
    final var response = new ReadHttpResponse<>(wrapped, alreadyRead);

    assertEquals(418, response.statusCode());
    assertSame(wrapped.headers(), response.headers());
    assertEquals("v", response.headers().firstValue("X-H").orElse(null));
    assertEquals(wrapped.uri(), response.uri());
    assertEquals(wrapped.version(), response.version());
    // by identity, and against a stub whose values are non-default: asserting
    // equality against a null request or an empty Optional would pass just as well
    // for a mutant that returns null / Optional.empty() from the delegation itself
    assertSame(wrapped.request(), response.request());
    assertSame(wrapped.previousResponse().orElseThrow(), response.previousResponse().orElseThrow());
    assertSame(wrapped.sslSession().orElseThrow(), response.sslSession().orElseThrow());
    // body() is the wrapped body; readBody() is the decoded one
    assertSame(wrapped.body(), response.body());
    assertSame(alreadyRead, response.readBody());
  }
}
