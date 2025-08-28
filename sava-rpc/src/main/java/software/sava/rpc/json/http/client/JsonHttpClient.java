package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

import static java.lang.System.Logger.Level.DEBUG;
import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static java.net.http.HttpResponse.BodyHandlers.ofInputStream;
import static software.sava.rpc.json.http.client.JsonRpcHttpClient.readBody;

public abstract class JsonHttpClient {

  private static final System.Logger logger = System.getLogger(JsonHttpClient.class.getName());

  protected final URI endpoint;
  protected final HttpClient httpClient;
  protected final Duration requestTimeout;
  protected final UnaryOperator<HttpRequest.Builder> extendRequest;
  @Deprecated
  protected final Predicate<HttpResponse<byte[]>> applyResponse;
  protected final BiPredicate<HttpResponse<?>, byte[]> testResponse;

  protected JsonHttpClient(final URI endpoint,
                           final HttpClient httpClient,
                           final Duration requestTimeout,
                           final UnaryOperator<HttpRequest.Builder> extendRequest,
                           @Deprecated final Predicate<HttpResponse<byte[]>> applyResponse,
                           final BiPredicate<HttpResponse<?>, byte[]> testResponse) {
    this.endpoint = endpoint;
    this.httpClient = httpClient;
    this.requestTimeout = requestTimeout;
    this.extendRequest = extendRequest == null ? UnaryOperator.identity() : extendRequest;
    this.applyResponse = applyResponse;
    this.testResponse = testResponse;
  }

  protected JsonHttpClient(final URI endpoint,
                           final HttpClient httpClient,
                           final Duration requestTimeout) {
    this(endpoint, httpClient, requestTimeout, null, null, null);
  }

  @Deprecated
  protected static <R> Function<HttpResponse<byte[]>, R> applyResponse(final Function<JsonIterator, R> parser) {
    return new JsonBytesResponseController<>(parser);
  }

  @Deprecated
  protected static <R> Function<HttpResponse<byte[]>, R> applyResponse(final BiFunction<byte[], JsonIterator, R> parser) {
    return new KeepJsonResponseController<>(parser);
  }

  protected static <R> Function<HttpResponse<?>, R> applyGenericResponse(final Function<JsonIterator, R> parser) {
    return new GenericJsonResponseParser<>(parser);
  }

  protected static <R> Function<HttpResponse<?>, R> applyGenericResponse(final BiFunction<byte[], JsonIterator, R> parser) {
    return new GenericJsonBytesResponseParser<>(parser);
  }

  private static HttpRequest.Builder newJsonRequest(final URI endpoint, final Duration requestTimeout) {
    return HttpRequest
        .newBuilder(endpoint)
        .header("Content-Type", "application/json")
        .timeout(requestTimeout);
  }

  public final URI endpoint() {
    return this.endpoint;
  }

  public final HttpClient httpClient() {
    return this.httpClient;
  }

  @Deprecated
  protected <R> Function<HttpResponse<byte[]>, R> wrapParser(final Function<HttpResponse<byte[]>, R> parser) {
    return applyResponse == null ? parser : response ->
        applyResponse.test(response) ? parser.apply(response) : null;
  }

  // GET methods

  protected final HttpRequest.Builder newRequest(final URI endpoint, final Duration requestTimeout) {
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
    logger.log(DEBUG, body);
    return newRequest(endpoint, requestTimeout, "POST", ofString(body)).build();
  }

  protected <R> Function<HttpResponse<?>, R> wrapResponseParser(final Function<HttpResponse<?>, R> parser) {
    if (testResponse == null) {
      if (applyResponse == null) {
        return parser;
      } else {
        return response -> {
          final byte[] body = readBody(response);
          final var disguisedResponse = new DisguisedHttpResponse(response, body);
          return applyResponse.test(disguisedResponse)
              ? parser.apply(new ReadHttpResponse<>(response, body))
              : null;
        };
      }
    } else {
      return response -> {
        final byte[] body = readBody(response);
        return testResponse.test(response, body)
            ? parser.apply(new ReadHttpResponse<>(response, body))
            : null;
      };
    }
  }

  protected final <R> CompletableFuture<R> sendPostRequest(final URI endpoint,
                                                           final Function<HttpResponse<?>, R> parser,
                                                           final Duration requestTimeout,
                                                           final String body) {
    return httpClient
        .sendAsync(newPostRequest(endpoint, requestTimeout, body), ofInputStream())
        .thenApply(wrapResponseParser(parser));
  }

  protected final <R> CompletableFuture<R> sendPostRequest(final Function<HttpResponse<?>, R> parser,
                                                           final Duration requestTimeout,
                                                           final String body) {
    return sendPostRequest(endpoint, parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendPostRequest(final Function<HttpResponse<?>, R> parser,
                                                           final String body) {
    return sendPostRequest(parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendPostRequest(final URI endpoint,
                                                           final Function<HttpResponse<?>, R> parser,
                                                           final String body) {
    return sendPostRequest(endpoint, parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendGetRequest(final Function<HttpResponse<?>, R> parser,
                                                          final String path) {
    return httpClient
        .sendAsync(newRequest(path).build(), ofInputStream())
        .thenApply(wrapResponseParser(parser));
  }

  protected final <R> CompletableFuture<R> sendGetRequest(final URI endpoint,
                                                          final Function<HttpResponse<?>, R> parser) {
    return httpClient
        .sendAsync(newRequest(endpoint).build(), ofInputStream())
        .thenApply(wrapResponseParser(parser));
  }

  protected final <R> CompletableFuture<R> sendPostRequestNoWrap(final URI endpoint,
                                                                 final Function<HttpResponse<?>, R> parser,
                                                                 final Duration requestTimeout,
                                                                 final String body) {
    return httpClient
        .sendAsync(newPostRequest(endpoint, requestTimeout, body), ofInputStream())
        .thenApply(parser);
  }

  protected final <R> CompletableFuture<R> sendPostRequestNoWrap(final Function<HttpResponse<?>, R> parser,
                                                                 final Duration requestTimeout,
                                                                 final String body) {
    return sendPostRequestNoWrap(endpoint, parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendPostRequestNoWrap(final Function<HttpResponse<?>, R> parser,
                                                                 final String body) {
    return sendPostRequestNoWrap(parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendPostRequestNoWrap(final URI endpoint,
                                                                 final Function<HttpResponse<?>, R> parser,
                                                                 final String body) {
    return sendPostRequestNoWrap(endpoint, parser, requestTimeout, body);
  }

  protected final <R> CompletableFuture<R> sendGetRequestNoWrap(final Function<HttpResponse<?>, R> parser,
                                                                final String path) {
    return httpClient
        .sendAsync(newRequest(path).build(), ofInputStream())
        .thenApply(parser);
  }

  protected final <R> CompletableFuture<R> sendGetRequestNoWrap(final URI endpoint,
                                                                final Function<HttpResponse<?>, R> parser) {
    return httpClient
        .sendAsync(newRequest(endpoint).build(), ofInputStream())
        .thenApply(parser);
  }

  protected final <H, R> CompletableFuture<R> sendPostRequestNoWrap(final URI endpoint,
                                                                    final HttpResponse.BodyHandler<H> bodyHandler,
                                                                    final Function<HttpResponse<H>, R> parser,
                                                                    final Duration requestTimeout,
                                                                    final String body) {
    return httpClient
        .sendAsync(newPostRequest(endpoint, requestTimeout, body), bodyHandler)
        .thenApply(parser);
  }

  protected final <H, R> CompletableFuture<R> sendPostRequestNoWrap(final HttpResponse.BodyHandler<H> bodyHandler,
                                                                    final Function<HttpResponse<H>, R> parser,
                                                                    final Duration requestTimeout,
                                                                    final String body) {
    return sendPostRequestNoWrap(endpoint, bodyHandler, parser, requestTimeout, body);
  }

  protected final <H, R> CompletableFuture<R> sendPostRequestNoWrap(final HttpResponse.BodyHandler<H> bodyHandler,
                                                                    final Function<HttpResponse<H>, R> parser,
                                                                    final String body) {
    return sendPostRequestNoWrap(bodyHandler, parser, requestTimeout, body);
  }

  protected final <H, R> CompletableFuture<R> sendPostRequestNoWrap(final URI endpoint,
                                                                    final HttpResponse.BodyHandler<H> bodyHandler,
                                                                    final Function<HttpResponse<H>, R> parser,
                                                                    final String body) {
    return sendPostRequestNoWrap(endpoint, bodyHandler, parser, requestTimeout, body);
  }

  protected final <H, R> CompletableFuture<R> sendGetRequestNoWrap(final HttpResponse.BodyHandler<H> bodyHandler,
                                                                   final Function<HttpResponse<H>, R> parser,
                                                                   final String path) {
    return httpClient
        .sendAsync(newRequest(path).build(), bodyHandler)
        .thenApply(parser);
  }

  protected final <H, R> CompletableFuture<R> sendGetRequestNoWrap(final URI endpoint,
                                                                   final HttpResponse.BodyHandler<H> bodyHandler,
                                                                   final Function<HttpResponse<H>, R> parser) {
    return httpClient
        .sendAsync(newRequest(endpoint).build(), bodyHandler)
        .thenApply(parser);
  }
}
