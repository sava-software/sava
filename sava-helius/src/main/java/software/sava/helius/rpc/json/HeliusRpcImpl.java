package software.sava.helius.rpc.json;

import software.sava.helius.rpc.json.request.TransactionsForAddressRequest;
import software.sava.helius.rpc.json.response.PagedResponse;
import software.sava.helius.rpc.json.response.TxFull;
import software.sava.helius.rpc.json.response.TxSig;
import software.sava.rpc.json.http.client.BaseSolanaJsonRpcClient;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;

final class HeliusRpcImpl extends BaseSolanaJsonRpcClient implements HeliusRpc {

  private static final Function<HttpResponse<?>, PagedResponse<List<TxSig>>> GET_SIGNATURES_FOR_ADDRESS =
      applyGenericResponseResult(TxSig::parseSignatures);

  private static final Function<HttpResponse<?>, PagedResponse<List<TxFull>>> GET_FULL_TRANSACTIONS_FOR_ADDRESS =
      applyGenericResponseResult(TxFull::parseFullDetails);

  private final SolanaRpcClient rpcClient;

  HeliusRpcImpl(final URI endpoint,
                final HttpClient httpClient,
                final Duration requestTimeout,
                final UnaryOperator<HttpRequest.Builder> extendRequest,
                final BiPredicate<HttpResponse<?>, byte[]> testResponse,
                final Commitment defaultCommitment,
                final SolanaRpcClient rpcClient) {
    super(endpoint, httpClient, requestTimeout, extendRequest, null, testResponse, defaultCommitment);
    this.rpcClient = rpcClient;
  }

  @Override
  public SolanaRpcClient rpcClient() {
    return rpcClient;
  }

  @Override
  public CompletableFuture<PagedResponse<List<TxSig>>> getSignaturesForAddress(final TransactionsForAddressRequest request) {
    return sendPostRequest(GET_SIGNATURES_FOR_ADDRESS, request.toJson(id.incrementAndGet(), "signatures"));
  }

  @Override
  public CompletableFuture<PagedResponse<List<TxFull>>> getTransactionsForAddress(final TransactionsForAddressRequest request) {
    return sendPostRequest(GET_FULL_TRANSACTIONS_FOR_ADDRESS, request.toJson(id.incrementAndGet(), "full"));
  }
}
