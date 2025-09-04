package software.sava.rpc.json.http.client;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

record ReadHttpResponse<T>(HttpResponse<T> response, byte[] readBody) implements HttpResponse<T> {

  @Override
  public int statusCode() {
    return response.statusCode();
  }

  @Override
  public HttpRequest request() {
    return response.request();
  }

  @Override
  public Optional<HttpResponse<T>> previousResponse() {
    return response.previousResponse();
  }

  @Override
  public HttpHeaders headers() {
    return response.headers();
  }

  @Override
  public T body() {
    return response.body();
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
