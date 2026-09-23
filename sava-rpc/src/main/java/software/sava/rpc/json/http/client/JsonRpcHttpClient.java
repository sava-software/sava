package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.*;

public abstract class JsonRpcHttpClient extends JsonHttpClient {

  protected final AtomicLong id;

  protected JsonRpcHttpClient(final URI endpoint,
                              final HttpClient httpClient,
                              final Duration requestTimeout,
                              final UnaryOperator<HttpRequest.Builder> extendRequest,
                              final BiPredicate<HttpResponse<?>, byte[]> testResponse) {
    this(endpoint, httpClient, requestTimeout, extendRequest, testResponse, null);
  }

  /// `deadlineScheduler` null selects the common pool: see [JsonHttpClient#deadlineScheduler].
  protected JsonRpcHttpClient(final URI endpoint,
                              final HttpClient httpClient,
                              final Duration requestTimeout,
                              final UnaryOperator<HttpRequest.Builder> extendRequest,
                              final BiPredicate<HttpResponse<?>, byte[]> testResponse,
                              final ScheduledExecutorService deadlineScheduler) {
    super(endpoint, httpClient, requestTimeout, extendRequest, testResponse, deadlineScheduler);
    this.id = new AtomicLong(System.currentTimeMillis());
  }

  protected JsonRpcHttpClient(final URI endpoint,
                              final HttpClient httpClient,
                              final Duration requestTimeout) {
    this(endpoint, httpClient, requestTimeout, null, null);
  }

  protected static <R> Function<HttpResponse<?>, R> applyGenericResponseResult(final Function<JsonIterator, R> parser) {
    return new JsonRpcResultResponseParser<>(parser);
  }

  protected static <R> Function<HttpResponse<?>, R> applyGenericResponseResult(final JsonRpcResponseParser<R> parser) {
    return new FullContextJsonRpcResponseParser<>(parser);
  }

}
