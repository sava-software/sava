package software.sava.rpc.json.http.client;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

record DisguisedHttpResponse(HttpResponse<?> response, byte[] body) implements HttpResponse<byte[]> {

  @Override
  public int statusCode() {
    return response.statusCode();
  }

  @Override
  public HttpRequest request() {
    return response.request();
  }

  @Override
  public Optional<HttpResponse<byte[]>> previousResponse() {
    throw new IllegalStateException("Cannot get previous response");
  }

  @Override
  public HttpHeaders headers() {
    return response.headers();
  }

  @Override
  public Optional<SSLSession> sslSession() {
    return response.sslSession();
  }

  @Override
  public URI uri() {
    return response.uri();
  }

  @Override
  public HttpClient.Version version() {
    return response.version();
  }
}
