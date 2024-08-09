package software.sava.rpc.json.http.ws;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.RpcEncoding;
import software.sava.rpc.json.http.SolanaNetwork;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.request.TransactionDetails;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.response.ProcessedSlot;
import software.sava.rpc.json.http.response.TxLogs;
import software.sava.rpc.json.http.response.TxResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface SolanaRpcWebsocket extends AutoCloseable {

  static Builder build() {
    return new SolanaRpcWebsocketBuilder();
  }

  void connect();

  void exceptionSubscribe(final Consumer<RuntimeException> consumer);


  boolean accountSubscribe(final PublicKey key,
                           final Consumer<AccountInfo<byte[]>> consumer);

  boolean accountSubscribe(final Commitment commitment,
                           final PublicKey key,
                           final Consumer<AccountInfo<byte[]>> consumer);

  boolean accountUnsubscribe(final PublicKey key);

  boolean accountUnsubscribe(final Commitment commitment, final PublicKey key);

  boolean logsSubscribe(final PublicKey key, final Consumer<TxLogs> consumer);

  boolean logsSubscribe(final Commitment commitment, final PublicKey key, final Consumer<TxLogs> consumer);

  boolean logsUnsubscribe(final PublicKey key);

  boolean logsUnsubscribe(final Commitment commitment, final PublicKey key);

  boolean signatureSubscribe(final String b58TxSig, final Consumer<TxResult> consumer);

  boolean signatureSubscribe(final String b58TxSig,
                             final boolean enableReceivedNotification,
                             final Consumer<TxResult> consumer);

  default boolean signatureSubscribe(final Commitment commitment,
                                     final String b58TxSig,
                                     final Consumer<TxResult> consumer) {
    return signatureSubscribe(commitment, commitment == Commitment.PROCESSED, b58TxSig, consumer);
  }

  boolean signatureSubscribe(final Commitment commitment,
                             final boolean enableReceivedNotification,
                             final String b58TxSig,
                             final Consumer<TxResult> consumer);

  boolean signatureUnsubscribe(final String b58TxSig);

  boolean signatureUnsubscribe(final Commitment commitment, final String b58TxSig);

  boolean subscribeToTokenAccount(final PublicKey tokenMint,
                                  final PublicKey ownerAddress,
                                  final Consumer<Map<PublicKey, AccountInfo<byte[]>>> consumer);

  boolean subscribeToTokenAccount(final Commitment commitment,
                                  final PublicKey tokenMint,
                                  final PublicKey ownerAddress,
                                  final Consumer<Map<PublicKey, AccountInfo<byte[]>>> consumer);

  boolean subscribeToTokenAccounts(final PublicKey ownerAddress,
                                   final Consumer<Map<PublicKey, AccountInfo<byte[]>>> consumer);

  boolean subscribeToTokenAccounts(final Commitment commitment,
                                   final PublicKey ownerAddress,
                                   final Consumer<Map<PublicKey, AccountInfo<byte[]>>> consumer);

  boolean programSubscribe(final PublicKey program, final Consumer<Map<PublicKey, AccountInfo<byte[]>>> consumer);

  boolean programSubscribe(final PublicKey program,
                           final List<Filter> filters,
                           final Consumer<Map<PublicKey, AccountInfo<byte[]>>> consumer);

  boolean programSubscribe(final Commitment commitment,
                           final PublicKey program,
                           final List<Filter> filters,
                           final Consumer<Map<PublicKey, AccountInfo<byte[]>>> consumer);

  boolean programUnsubscribe(final PublicKey program);

  boolean programUnsubscribe(final Commitment commitment, final PublicKey program);

  boolean transactionSubscribe(final Commitment commitment,
                               final boolean vote,
                               final boolean failed,
                               final String signature,
                               final Collection<PublicKey> accountInclude,
                               final Collection<PublicKey> accountExclude,
                               final Collection<PublicKey> accountRequired,
                               final RpcEncoding encoding,
                               final TransactionDetails transactionDetails,
                               final boolean showRewards,
                               final Consumer<TxResult> consumer);

  default boolean transactionSubscribe(final Commitment commitment,
                                       final String signature,
                                       final Collection<PublicKey> accountInclude,
                                       final Collection<PublicKey> accountExclude,
                                       final Collection<PublicKey> accountRequired,
                                       final RpcEncoding rpcEncoding,
                                       final Consumer<TxResult> consumer) {
    return transactionSubscribe(
        commitment,
        false,
        false,
        signature,
        accountInclude,
        accountExclude,
        accountRequired,
        rpcEncoding,
        TransactionDetails.full,
        false,
        consumer
    );
  }

  boolean slotSubscribe(final Consumer<ProcessedSlot> consumer);

  boolean slotUnsubscribe();

  @Override
  void close();

  interface Builder {

    SolanaRpcWebsocket create();

    default void uri(final String endpoint) {
      uri(URI.create(endpoint));
    }

    default void uri(final SolanaNetwork network) {
      uri(network.getWebSocketEndpoint());
    }

    void uri(final URI uri);

    default void webSocketBuilder(final HttpClient httpClient) {
      webSocketBuilder(httpClient.newWebSocketBuilder());
    }

    void webSocketBuilder(final WebSocket.Builder webSocketBuilder);

    void reConnect(final long reConnect);

    void writeOrPingDelay(final long writeOrPingDelay);

    void subscriptionAndPingCheckDelay(final long subscriptionAndPingCheckDelay);

    void commitment(final Commitment commitment);

    void solanaAccounts(SolanaAccounts solanaAccounts);

    URI wsUri();

    WebSocket.Builder webSocketBuilder();

    long reConnect();

    long writeOrPingDelay();

    long subscriptionAndPingCheckDelay();

    SolanaAccounts solanaAccounts();

    Commitment commitment();
  }
}
