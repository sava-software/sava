package software.sava.rpc.json.http.ws;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.SolanaNetwork;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.response.ProcessedSlot;
import software.sava.rpc.json.http.response.TxLogs;
import software.sava.rpc.json.http.response.TxResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface SolanaRpcWebsocket extends AutoCloseable {

  @FunctionalInterface
  interface OnClose {

    void accept(final SolanaRpcWebsocket websocket,
                final int statusCode,
                final String reason);

    default OnClose andThen(final OnClose after) {
      Objects.requireNonNull(after);

      return (ws, c, r) -> {
        accept(ws, c, r);
        after.accept(ws, c, r);
      };
    }
  }

  static Builder build() {
    return new SolanaRpcWebsocketBuilder();
  }

  URI endpoint();

  SolanaAccounts solanaAccounts();

  Commitment defaultCommitment();

  Timings timings();

  boolean closed();

  /// @return A CompletableFuture which completes once the underlying WebSocket is connected.
  /// `null` will be returned if this has been [closed][#close()].
  ///
  /// See [java.net.http.WebSocket.Builder#buildAsync(URI,WebSocket.Listener)] for potential exceptions.
  ///
  /// This may be used to re-connect the underlying WebSocket if this has not been [closed][#close()].
  /// The connection attempt will be delayed by [Timings#reConnectDelay] if a previous attempt as already been made.
  CompletableFuture<?> connect();

  void exceptionSubscribe(final Consumer<RuntimeException> consumer);

  boolean accountSubscribe(final Commitment commitment,
                           final PublicKey key,
                           final Consumer<AccountInfo<byte[]>> consumer);

  boolean accountSubscribe(final PublicKey key,
                           final Consumer<AccountInfo<byte[]>> consumer);

  boolean accountUnsubscribe(final Commitment commitment, final PublicKey key);

  boolean accountUnsubscribe(final PublicKey key);

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
                                  final Consumer<AccountInfo<byte[]>> consumer);

  boolean subscribeToTokenAccount(final Commitment commitment,
                                  final PublicKey tokenMint,
                                  final PublicKey ownerAddress,
                                  final Consumer<AccountInfo<byte[]>> consumer);

  boolean subscribeToTokenAccounts(final PublicKey ownerAddress,
                                   final Consumer<AccountInfo<byte[]>> consumer);

  boolean subscribeToTokenAccounts(final Commitment commitment,
                                   final PublicKey ownerAddress,
                                   final Consumer<AccountInfo<byte[]>> consumer);

  boolean programSubscribe(final PublicKey program, final Consumer<AccountInfo<byte[]>> consumer);

  boolean programSubscribe(final PublicKey program,
                           final List<Filter> filters,
                           final Consumer<AccountInfo<byte[]>> consumer);

  boolean programSubscribe(final Commitment commitment,
                           final PublicKey program,
                           final List<Filter> filters,
                           final Consumer<AccountInfo<byte[]>> consumer);

  boolean programUnsubscribe(final PublicKey program);

  boolean programUnsubscribe(final Commitment commitment, final PublicKey program);

  boolean slotSubscribe(final Consumer<ProcessedSlot> consumer);

  boolean slotUnsubscribe();

  /// Once closed this WebSocket is no longer usable.
  @Override
  void close();

  interface Builder {

    SolanaRpcWebsocket create();

    default Builder uri(final String endpoint) {
      return uri(URI.create(endpoint));
    }

    default Builder uri(final SolanaNetwork network) {
      return uri(network.getWebSocketEndpoint());
    }

    Builder uri(final URI uri);

    default Builder webSocketBuilder(final HttpClient httpClient) {
      return webSocketBuilder(httpClient.newWebSocketBuilder());
    }

    Builder webSocketBuilder(final WebSocket.Builder webSocketBuilder);

    Builder reConnectDelay(final long reConnectDelay);

    @Deprecated
    default Builder reConnect(final long reConnect) {
      return reConnectDelay(reConnect);
    }

    Builder pingDelay(final long pingDelay);

    @Deprecated
    default Builder writeOrPingDelay(final long writeOrPingDelay) {
      return pingDelay(writeOrPingDelay);
    }

    Builder subscriptionAndPingCheckDelay(final long subscriptionAndPingCheckDelay);

    Builder commitment(final Commitment commitment);

    Builder solanaAccounts(final SolanaAccounts solanaAccounts);

    URI wsUri();

    WebSocket.Builder webSocketBuilder();

    long reConnect();

    long writeOrPingDelay();

    long subscriptionAndPingCheckDelay();

    SolanaAccounts solanaAccounts();

    Commitment commitment();

    Consumer<SolanaRpcWebsocket> onOpen();

    Builder onOpen(final Consumer<SolanaRpcWebsocket> onOpen);

    OnClose onClose();

    /// The default behavior is to [#close()] this WebSocket.
    ///
    /// This behaviour can be changed to instead attempt to [re-connect][#connect()] the underlying WebSocket and re-use this instance.
    Builder onClose(final OnClose onClose);

    BiConsumer<SolanaRpcWebsocket, Throwable> onError();

    /// The default behavior is to [#close()] this WebSocket.
    ///
    /// This behaviour can be changed to instead attempt to [re-connect][#connect()] the underlying WebSocket and re-use this instance.
    Builder onError(final BiConsumer<SolanaRpcWebsocket, Throwable> onError);
  }
}
