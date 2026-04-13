package software.sava.helius.rpc.json;

import software.sava.helius.rpc.json.request.TransactionsForAddressRequest;
import software.sava.helius.rpc.json.response.PagedResponse;
import software.sava.helius.rpc.json.response.TxFull;
import software.sava.helius.rpc.json.response.TxSig;
import software.sava.rpc.json.http.client.SolanaRpcClient;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface HeliusRpc {

  static HeliusRpcBuilder build() {
    return new HeliusRpcBuilder();
  }

  SolanaRpcClient rpcClient();

  CompletableFuture<PagedResponse<List<TxSig>>> getSignaturesForAddress(final TransactionsForAddressRequest request);

  CompletableFuture<PagedResponse<List<TxFull>>> getTransactionsForAddress(final TransactionsForAddressRequest request);
}
