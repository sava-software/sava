package software.sava.rpc.json.http.client;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/// A response built from literals, so the parsers can be driven with hostile and
/// malformed input without a server in the way.
record StubHttpResponse<T>(T body, HttpHeaders headers, int statusCode) implements HttpResponse<T> {

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
    return null;
  }

  @Override
  public Optional<HttpResponse<T>> previousResponse() {
    return Optional.empty();
  }

  @Override
  public Optional<SSLSession> sslSession() {
    return Optional.empty();
  }

  @Override
  public URI uri() {
    return URI.create("https://rpc.example.invalid");
  }

  @Override
  public HttpClient.Version version() {
    return HttpClient.Version.HTTP_2;
  }
}
