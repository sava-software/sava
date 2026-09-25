package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.SolanaNetwork;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;

import static software.sava.rpc.json.http.client.SolanaJsonRpcClient.DEFAULT_REQUEST_TIMEOUT;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;

public final class SolanaRpcClientBuilder {

  private URI endpoint;
  private HttpClient httpClient;
  private Duration requestTimeout;
  private UnaryOperator<HttpRequest.Builder> extendRequest;
  private BiPredicate<HttpResponse<?>, byte[]> testResponse;
  private Commitment defaultCommitment;
  private ScheduledExecutorService deadlineScheduler;

  SolanaRpcClientBuilder() {
  }

  /// Unset values take defaults, notably the [SolanaNetwork#MAIN_NET] endpoint and
  /// [Commitment#CONFIRMED].
  public SolanaRpcClient createClient() {
    final var endpoint = this.endpoint == null ? SolanaNetwork.MAIN_NET.getEndpoint() : this.endpoint;
    final var httpClient = this.httpClient == null ? HttpClient.newHttpClient() : this.httpClient;
    final var requestTimeout = this.requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : this.requestTimeout;
    final var defaultCommitment = this.defaultCommitment == null ? CONFIRMED : this.defaultCommitment;

    return new SolanaJsonRpcClient(
        endpoint,
        httpClient,
        requestTimeout,
        extendRequest,
        testResponse,
        defaultCommitment,
        deadlineScheduler
    );
  }

  public SolanaRpcClientBuilder endpoint(final URI endpoint) {
    this.endpoint = endpoint;
    return this;
  }

  public SolanaRpcClientBuilder httpClient(final HttpClient httpClient) {
    this.httpClient = httpClient;
    return this;
  }

  public SolanaRpcClientBuilder requestTimeout(final Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
    return this;
  }

  /// Replaces any earlier extension, including the header added by [#compressResponses()].
  public SolanaRpcClientBuilder extendRequest(final UnaryOperator<HttpRequest.Builder> extendRequest) {
    this.extendRequest = extendRequest;
    return this;
  }

  /// Adds `Accept-Encoding: gzip` to each request, composing with any earlier
  /// [#extendRequest(UnaryOperator)].
  public SolanaRpcClientBuilder compressResponses() {
    final var extendRequest = this.extendRequest;
    return extendRequest(extendRequest == null
        ? r -> r.header("Accept-Encoding", "gzip")
        : r -> extendRequest.apply(r).header("Accept-Encoding", "gzip"));
  }

  /// Tests each response and its body before parsing; a rejected response completes the request's
  /// future with `null` instead of a parsed value.
  public SolanaRpcClientBuilder testResponse(final BiPredicate<HttpResponse<?>, byte[]> testResponse) {
    this.testResponse = testResponse;
    return this;
  }

  public SolanaRpcClientBuilder defaultCommitment(final Commitment defaultCommitment) {
    this.defaultCommitment = defaultCommitment;
    return this;
  }

  /// Where the client arms the whole-exchange deadline (twice the request timeout, the JDK 25
  /// backstop for a body that stalls after the headers). Unset, the client uses
  /// `ForkJoinPool.commonPool()`, which on JDK 25 is also where the JDK HTTP client completes
  /// its `sendAsync` futures, so a common pool saturated with blocking work delays the
  /// cancellation and the exchange runs past its deadline by that much. Supply a dedicated
  /// scheduler when the deadline must fire on time regardless. The client never shuts it down;
  /// the cancellation completes the response future on its thread, so non-async continuations
  /// on a timed-out exchange run there (give it more than one thread, or chain with the
  /// `*Async` forms); a `ScheduledThreadPoolExecutor` wants `setRemoveOnCancelPolicy(true)`;
  /// one that rejects the deadline by throwing (already shut down) fails the exchange rather
  /// than leaving it unbounded, and one that silently discards tasks leaves it unbounded.
  public SolanaRpcClientBuilder deadlineScheduler(final ScheduledExecutorService deadlineScheduler) {
    this.deadlineScheduler = deadlineScheduler;
    return this;
  }

  public URI endpoint() {
    return endpoint;
  }

  public HttpClient httpClient() {
    return httpClient;
  }

  public Duration requestTimeout() {
    return requestTimeout;
  }

  public UnaryOperator<HttpRequest.Builder> extendRequest() {
    return extendRequest;
  }

  public BiPredicate<HttpResponse<?>, byte[]> testResponse() {
    return testResponse;
  }

  public Commitment defaultCommitment() {
    return defaultCommitment;
  }

  /// Null until set: the built client then uses the common pool.
  public ScheduledExecutorService deadlineScheduler() {
    return deadlineScheduler;
  }
}
