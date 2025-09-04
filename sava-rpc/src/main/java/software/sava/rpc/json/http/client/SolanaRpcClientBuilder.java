package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.SolanaNetwork;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

  SolanaRpcClientBuilder() {
  }

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
        null,
        testResponse,
        defaultCommitment
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

  public SolanaRpcClientBuilder extendRequest(final UnaryOperator<HttpRequest.Builder> extendRequest) {
    this.extendRequest = extendRequest;
    return this;
  }

  public SolanaRpcClientBuilder compressResponses() {
    return extendRequest(r -> r.header("Accept-Encoding", "gzip"));
  }

  public SolanaRpcClientBuilder testResponse(final BiPredicate<HttpResponse<?>, byte[]> testResponse) {
    this.testResponse = testResponse;
    return this;
  }

  public SolanaRpcClientBuilder defaultCommitment(final Commitment defaultCommitment) {
    this.defaultCommitment = defaultCommitment;
    return this;
  }
}
