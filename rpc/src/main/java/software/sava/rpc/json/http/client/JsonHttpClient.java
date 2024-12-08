package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static java.net.http.HttpResponse.BodyHandlers.ofByteArray;

public abstract class JsonHttpClient {

  protected final URI endpoint;
  protected final HttpClient httpClient;
  protected final Duration requestTimeout;
  protected final UnaryOperator<HttpRequest.Builder> extendRequest;
  protected final Predicate<HttpResponse<byte[]>> applyResponse;

  protected JsonHttpClient(final URI endpoint,
                           final HttpClient httpClient,
                           final Duration requestTimeout,
                           final UnaryOperator<HttpRequest.Builder> extendRequest,
                           final Predicate<HttpResponse<byte[]>> applyResponse) {
    this.endpoint = endpoint;
    this.httpClient = httpClient;
    this.requestTimeout = requestTimeout;
    this.extendRequest = extendRequest == null ? UnaryOperator.identity() : extendRequest;
    this.applyResponse = applyResponse;
  }

  protected JsonHttpClient(final URI endpoint,
                           final HttpClient httpClient,
                           final Duration requestTimeout) {
    this(endpoint, httpClient, requestTimeout, null, null);
  }

  public final URI endpoint() {
    return this.endpoint;
  }

  public final HttpClient httpClient() {
    return this.httpClient;
  }

  protected static <R> Function<HttpResponse<byte[]>, R> applyResponse(final Function<JsonIterator, R> adapter) {
    return new JsonResponseController<>(adapter);
  }

  protected static <R> Function<HttpResponse<byte[]>, R> applyResponse(final BiFunction<byte[], JsonIterator, R> adapter) {
    return new KeepJsonResponseController<>(adapter);
  }

  protected <R> Function<HttpResponse<byte[]>, R> wrapParser(final Function<HttpResponse<byte[]>, R> parser) {
    return applyResponse == null ? parser : response -> applyResponse.test(response) ? parser.apply(response) : null;
  }

  private static HttpRequest.Builder newJsonRequest(final URI endpoint, final Duration requestTimeout) {
    return HttpRequest
        .newBuilder(endpoint)
        .header("Content-Type", "application/json")
        .timeout(requestTimeout);
  }

  // GET methods

  private HttpRequest.Builder newRequest(final URI endpoint, final Duration requestTimeout) {
    return extendRequest.apply(newJsonRequest(endpoint, requestTimeout));
  }

  protected final HttpRequest.Builder newRequest(final String path, final Duration requestTimeout) {
    return newRequest(endpoint.resolve(path), requestTimeout);
  }

  protected final HttpRequest.Builder newRequest(final String path) {
    return newRequest(path, requestTimeout);
  }

  protected final HttpRequest.Builder newRequest(final URI endpoint) {
    return newRequest(endpoint, requestTimeout);
  }

  protected final HttpRequest.Builder newRequest(final Duration requestTimeout) {
    return newRequest(endpoint, requestTimeout);
  }

  protected final HttpRequest.Builder newRequest() {
    return newRequest(endpoint);
  }

  // POST/PUT methods

  private HttpRequest.Builder newRequest(final URI endpoint,
                                         final Duration requestTimeout,
                                         final String method,
                                         final HttpRequest.BodyPublisher bodyPublisher) {
    return extendRequest.apply(newJsonRequest(endpoint, requestTimeout).method(method, bodyPublisher));
  }

  protected final HttpRequest.Builder newRequest(final URI endpoint,
                                                 final String method,
                                                 final HttpRequest.BodyPublisher bodyPublisher) {
    return newRequest(endpoint, requestTimeout, method, bodyPublisher);
  }

  protected final HttpRequest.Builder newRequest(final Duration requestTimeout,
                                                 final String method,
                                                 final HttpRequest.BodyPublisher bodyPublisher) {
    return newRequest(endpoint, requestTimeout, method, bodyPublisher);
  }

  protected final HttpRequest.Builder newRequest(final String method,
                                                 final HttpRequest.BodyPublisher bodyPublisher) {
    return newRequest(endpoint, method, bodyPublisher);
  }

  protected final HttpRequest.Builder newRequest(final String path,
                                                 final Duration requestTimeout,
                                                 final String method,
                                                 final HttpRequest.BodyPublisher bodyPublisher) {
    return newRequest(endpoint.resolve(path), requestTimeout, method, bodyPublisher);
  }

  protected final HttpRequest.Builder newRequest(final String path,
                                                 final String method,
                                                 final HttpRequest.BodyPublisher bodyPublisher) {
    return newRequest(path, requestTimeout, method, bodyPublisher);
  }

  protected final HttpRequest newPostRequest(final URI endpoint, final Duration requestTimeout, final String body) {
    return newRequest(endpoint, requestTimeout, "POST", ofString(body)).build();
  }

  protected final <R> CompletableFuture<R> sendPostRequest(final URI endpoint,
                                                           final Function<HttpResponse<byte[]>, R> parser,
                                                           final Duration requestTimeout,
                                                           final String body) {
//    System.out.println(body);
    return httpClient
        .sendAsync(newPostRequest(endpoint, requestTimeout, body), ofByteArray())
        .thenApply(wrapParser(parser));
  }

  protected final <R> CompletableFuture<R> sendPostRequest(final Function<HttpResponse<byte[]>, R> parser,
                                                           final Duration requestTimeout,
                                                           final String body) {
    return sendPostRequest(endpoint, parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendPostRequest(final Function<HttpResponse<byte[]>, R> parser,
                                                           final String body) {
    // System.out.println(body);
    return sendPostRequest(parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendPostRequest(final URI endpoint,
                                                           final Function<HttpResponse<byte[]>, R> parser,
                                                           final String body) {
    return sendPostRequest(endpoint, parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendGetRequest(final Function<HttpResponse<byte[]>, R> parser,
                                                          final String path) {
    return httpClient
        .sendAsync(newRequest(path).build(), ofByteArray())
        .thenApply(wrapParser(parser));
  }

  protected final <R> CompletableFuture<R> sendGetRequest(final URI endpoint,
                                                          final Function<HttpResponse<byte[]>, R> parser) {
    return httpClient
        .sendAsync(newRequest(endpoint).build(), ofByteArray())
        .thenApply(wrapParser(parser));
  }

  protected final <R> CompletableFuture<R> sendPostRequestNoWrap(final URI endpoint,
                                                                 final Function<HttpResponse<byte[]>, R> parser,
                                                                 final Duration requestTimeout,
                                                                 final String body) {
//    System.out.println(body);
    return httpClient
        .sendAsync(newPostRequest(endpoint, requestTimeout, body), ofByteArray())
        .thenApply(parser);
  }

  protected final <R> CompletableFuture<R> sendPostRequestNoWrap(final Function<HttpResponse<byte[]>, R> parser,
                                                                 final Duration requestTimeout,
                                                                 final String body) {
    return sendPostRequestNoWrap(endpoint, parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendPostRequestNoWrap(final Function<HttpResponse<byte[]>, R> parser,
                                                                 final String body) {
    // System.out.println(body);
    return sendPostRequestNoWrap(parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendPostRequestNoWrap(final URI endpoint,
                                                                 final Function<HttpResponse<byte[]>, R> parser,
                                                                 final String body) {
    return sendPostRequestNoWrap(endpoint, parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendGetRequestNoWrap(final Function<HttpResponse<byte[]>, R> parser,
                                                                final String path) {
    return httpClient
        .sendAsync(newRequest(path).build(), ofByteArray())
        .thenApply(parser);
  }

  protected final <R> CompletableFuture<R> sendGetRequestNoWrap(final URI endpoint,
                                                                final Function<HttpResponse<byte[]>, R> parser) {
    return httpClient
        .sendAsync(newRequest(endpoint).build(), ofByteArray())
        .thenApply(parser);
  }
}
