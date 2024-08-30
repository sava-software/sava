package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static java.net.http.HttpResponse.BodyHandlers.ofByteArray;

public abstract class JsonHttpClient {

  protected final URI endpoint;
  protected final HttpClient httpClient;
  protected final Duration requestTimeout;
  protected final Predicate<HttpResponse<byte[]>> applyResponse;

  protected JsonHttpClient(final URI endpoint,
                           final HttpClient httpClient,
                           final Duration requestTimeout,
                           final Predicate<HttpResponse<byte[]>> applyResponse) {
    this.endpoint = endpoint;
    this.httpClient = httpClient;
    this.requestTimeout = requestTimeout;
    this.applyResponse = applyResponse;
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

  protected <R> Function<HttpResponse<byte[]>, R> wrapParser(final Function<HttpResponse<byte[]>, R> parser) {
    return applyResponse == null ? parser : response -> applyResponse.test(response) ? parser.apply(response) : null;
  }

  protected HttpRequest.Builder newRequest(final URI endpoint, final Duration requestTimeout) {
    return HttpRequest
        .newBuilder(endpoint)
        .header("Content-Type", "application/json")
        .timeout(requestTimeout);
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

  protected final HttpRequest.Builder newRequest(final String path, final Duration requestTimeout) {
    return newRequest(endpoint.resolve(path), requestTimeout);
  }

  protected final HttpRequest.Builder newRequest(final String path) {
    return newRequest(path, requestTimeout);
  }

  protected final HttpRequest.Builder newGetRequest(final String path, final Duration requestTimeout) {
    return newRequest(path, requestTimeout).GET();
  }

  protected final HttpRequest.Builder newGetRequest(final String path) {
    return newRequest(path).GET();
  }

  protected final HttpRequest.Builder newGetRequest(final URI endpoint) {
    return newRequest(endpoint).GET();
  }

  protected final HttpRequest newPostRequest(final String body) {
    return newRequest().POST(ofString(body)).build();
  }

  protected final HttpRequest newPostRequest(final Duration requestTimeout, final String body) {
    return newRequest(requestTimeout).POST(ofString(body)).build();
  }

  protected final HttpRequest newPostRequest(final URI endpoint, final String body) {
    return newRequest(endpoint).POST(ofString(body)).build();
  }

  protected final HttpRequest newPostRequest(final URI endpoint, final Duration requestTimeout, final String body) {
    return newRequest(endpoint, requestTimeout).POST(ofString(body)).build();
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
        .sendAsync(newGetRequest(path).build(), ofByteArray())
        .thenApply(wrapParser(parser));
  }

  protected final <R> CompletableFuture<R> sendGetRequest(final URI endpoint,
                                                          final Function<HttpResponse<byte[]>, R> parser) {
    return httpClient
        .sendAsync(newGetRequest(endpoint).build(), ofByteArray())
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
        .sendAsync(newGetRequest(path).build(), ofByteArray())
        .thenApply(parser);
  }

  protected final <R> CompletableFuture<R> sendGetRequestNoWrap(final URI endpoint,
                                                                final Function<HttpResponse<byte[]>, R> parser) {
    return httpClient
        .sendAsync(newGetRequest(endpoint).build(), ofByteArray())
        .thenApply(parser);
  }
}
