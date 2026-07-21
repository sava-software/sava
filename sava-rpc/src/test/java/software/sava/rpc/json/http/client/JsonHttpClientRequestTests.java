package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.net.URI;
import java.net.UnknownServiceException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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

  @Test
  void accessorsReportTheConfiguredValues() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var testClient = client(httpClient);
      assertEquals(ENDPOINT, testClient.endpoint());
      assertSame(httpClient, testClient.httpClient());
      assertEquals(DEFAULT_TIMEOUT, testClient.defaultRequestTimeout());
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
    assertEquals(wrapped.request(), response.request());
    assertEquals(wrapped.previousResponse(), response.previousResponse());
    assertEquals(wrapped.sslSession(), response.sslSession());
    // body() is the wrapped body; readBody() is the decoded one
    assertSame(wrapped.body(), response.body());
    assertSame(alreadyRead, response.readBody());
  }
}
