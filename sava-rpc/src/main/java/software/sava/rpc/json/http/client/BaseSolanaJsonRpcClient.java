package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.Context;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.*;

public abstract class BaseSolanaJsonRpcClient extends JsonRpcHttpClient {

  protected final Commitment defaultCommitment;

  protected BaseSolanaJsonRpcClient(final URI endpoint,
                                    final HttpClient httpClient,
                                    final Duration requestTimeout,
                                    final UnaryOperator<HttpRequest.Builder> extendRequest,
                                    final Predicate<HttpResponse<byte[]>> applyResponse,
                                    final BiPredicate<HttpResponse<?>, byte[]> testResponse,
                                    final Commitment defaultCommitment) {
    super(endpoint, httpClient, requestTimeout, extendRequest, applyResponse, testResponse);
    this.defaultCommitment = defaultCommitment;
  }

  protected static <R> Function<HttpResponse<?>, R> applyGenericResponseValue(final BiFunction<JsonIterator, Context, R> parser) {
    return new JsonRpcValueResponseParser<>(parser);
  }

  public final Commitment defaultCommitment() {
    return defaultCommitment;
  }
}
