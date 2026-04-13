package software.sava.helius.rpc.json;

import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.client.SolanaRpcClientBuilder;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;

public final class HeliusRpcBuilder {

  private final SolanaRpcClientBuilder builder;
  private SolanaRpcClient rpcClient;

  HeliusRpcBuilder() {
    this.builder = SolanaRpcClient.build();
  }

  public HeliusRpc createClient() {
    if (rpcClient == null) {
      rpcClient = builder.createClient();
    }
    return new HeliusRpcImpl(
        Objects.requireNonNullElse(builder.endpoint(), rpcClient.endpoint()),
        Objects.requireNonNullElse(builder.httpClient(), rpcClient.httpClient()),
        Objects.requireNonNullElse(builder.requestTimeout(), rpcClient.defaultRequestTimeout()),
        builder.extendRequest(),
        builder.testResponse(),
        Objects.requireNonNullElse(builder.defaultCommitment(), rpcClient.defaultCommitment()),
        rpcClient
    );
  }

  public SolanaRpcClientBuilder builder() {
    return builder;
  }

  public SolanaRpcClient rpcClient() {
    return rpcClient;
  }

  public HeliusRpcBuilder rpcClient(final SolanaRpcClient rpcClient) {
    this.rpcClient = rpcClient;
    return this;
  }

  public HeliusRpcBuilder endpoint(final URI endpoint) {
    builder.endpoint(endpoint);
    return this;
  }

  public HeliusRpcBuilder httpClient(final HttpClient httpClient) {
    builder.httpClient(httpClient);
    return this;
  }

  public HeliusRpcBuilder requestTimeout(final Duration requestTimeout) {
    builder.requestTimeout(requestTimeout);
    return this;
  }

  public HeliusRpcBuilder extendRequest(final UnaryOperator<HttpRequest.Builder> extendRequest) {
    builder.extendRequest(extendRequest);
    return this;
  }

  public HeliusRpcBuilder compressResponses() {
    builder.compressResponses();
    return this;
  }

  public HeliusRpcBuilder testResponse(final BiPredicate<HttpResponse<?>, byte[]> testResponse) {
    builder.testResponse(testResponse);
    return this;
  }

  public HeliusRpcBuilder defaultCommitment(final Commitment defaultCommitment) {
    builder.defaultCommitment(defaultCommitment);
    return this;
  }
}
