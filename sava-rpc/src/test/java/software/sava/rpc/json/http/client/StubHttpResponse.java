package software.sava.rpc.json.http.client;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// A response built from literals, so the parsers can be driven with hostile and
/// malformed input without a server in the way.
///
/// Every accessor answers with a distinguishable, non-default value: a stub that
/// returns null, an empty `Optional` or a zero is indistinguishable from the
/// return-value mutant of whatever delegates to it, which silently withdraws that
/// mutant from the ratchet (`AGENTS.md`: stubs return distinguishable values).
record StubHttpResponse<T>(T body, HttpHeaders headers, int statusCode) implements HttpResponse<T> {

  private static final URI REQUEST_URI = URI.create("https://rpc.example.invalid");
  private static final HttpRequest REQUEST = HttpRequest.newBuilder(REQUEST_URI).build();

  /// The redirect this response pretends to have followed — a distinct instance, so
  /// delegation can be asserted by identity.
  private static final HttpResponse<?> PREVIOUS = new StubHttpResponse<>(
      null, HttpHeaders.of(Map.of(), (_, _) -> true), 302
  );
  /// A real, never-connected session: `createSSLEngine` hands out the initial
  /// `SSL_NULL_WITH_NULL_NULL` session without any handshake or network.
  private static final SSLSession SSL_SESSION;

  static {
    try {
      SSL_SESSION = SSLContext.getDefault().createSSLEngine().getSession();
    } catch (final NoSuchAlgorithmException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /// `headerPairs` are flat name/value pairs; repeats accumulate under one name.
  static <T> StubHttpResponse<T> of(final int statusCode, final T body, final String... headerPairs) {
    final var map = new LinkedHashMap<String, List<String>>();
    for (int i = 0; i < headerPairs.length; i += 2) {
      map.computeIfAbsent(headerPairs[i], _ -> new ArrayList<>()).add(headerPairs[i + 1]);
    }
    return new StubHttpResponse<>(body, HttpHeaders.of(map, (_, _) -> true), statusCode);
  }

  static <T> StubHttpResponse<T> of(final T body, final String... headerPairs) {
    return of(200, body, headerPairs);
  }

  @Override
  public HttpRequest request() {
    return REQUEST;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Optional<HttpResponse<T>> previousResponse() {
    return Optional.of((HttpResponse<T>) PREVIOUS);
  }

  @Override
  public Optional<SSLSession> sslSession() {
    return Optional.of(SSL_SESSION);
  }

  @Override
  public URI uri() {
    return REQUEST_URI;
  }

  @Override
  public HttpClient.Version version() {
    return HttpClient.Version.HTTP_2;
  }
}
