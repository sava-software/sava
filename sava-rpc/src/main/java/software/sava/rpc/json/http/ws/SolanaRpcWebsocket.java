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
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

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

  /// When the current connection last received a complete message, in epoch milliseconds, or 0
  /// when it has received none or there is no connection.
  ///
  /// Only messages count, including ones that fail to parse; Pings and Pongs prove the transport
  /// alive, not that subscriptions are being served. [#closed()] says only that [#close()] was
  /// called. Silence is not failure by itself, so weigh this against the traffic the
  /// subscriptions should produce, and treat 0 as no evidence rather than as liveness. The
  /// default returns 0.
  default long lastMessageReceivedTimestamp() {
    return 0;
  }

  boolean closed();

  /// Connects, or reconnects, the underlying WebSocket.
  ///
  /// A new attempt waits out whatever remains of [Timings#reConnectDelay()] since the previous
  /// one. Attempts are single-flight: while one is unsettled, every caller gets a private copy of
  /// its future, so cancelling one abandons only that caller's view. The socket being replaced is
  /// aborted and its late callbacks are ignored.
  ///
  /// The returned future and the [Builder#onClose(OnClose)] and [Builder#onError(BiConsumer)]
  /// callbacks are overlapping, uncorrelated signals: in the implementation from [#build()],
  /// retiring a transport whose attempt is still unsettled completes that attempt's future
  /// exceptionally before the callback runs, and the two may report different details
  /// (cancellation versus the transport error or close status). Callbacks carry no attempt
  /// identity, so they cannot be attributed to a particular future; delaying reconnect narrows
  /// the overlap but cannot attribute it.
  ///
  /// @return a future completing once connected, or `null` if this is [closed][#close()]; see
  ///         [java.net.http.WebSocket.Builder#buildAsync(URI,WebSocket.Listener)] for its
  ///         failures
  CompletableFuture<?> connect();

  /// Registers a consumer for engine-reported failures: request rejections, terminal
  /// registration collisions, unexpected errors processing inbound messages, and exceptions
  /// thrown by subscription consumers, notification parsers and `onSub` callbacks. A throwing
  /// consumer does not stop the others. A no-op once [closed][#close()], since close releases
  /// every consumer.
  void exceptionSubscribe(final Consumer<RuntimeException> consumer);

  boolean accountSubscribe(final PublicKey key,
                           final Consumer<AccountInfo<byte[]>> consumer);

  default boolean accountSubscribe(final PublicKey key,
                                   final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                                   final Consumer<AccountInfo<byte[]>> consumer) {
    return accountSubscribe(defaultCommitment(), key, onSub, consumer);
  }

  default boolean accountSubscribe(final Commitment commitment,
                                   final PublicKey key,
                                   final Consumer<AccountInfo<byte[]>> consumer) {
    return accountSubscribe(commitment, key, null, consumer);
  }

  boolean accountSubscribe(final Commitment commitment,
                           final PublicKey key,
                           final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                           final Consumer<AccountInfo<byte[]>> consumer);

  boolean accountUnsubscribe(final PublicKey key);

  boolean accountUnsubscribe(final Commitment commitment, final PublicKey key);

  boolean logsSubscribe(final PublicKey key, final Consumer<TxLogs> consumer);

  default boolean logsSubscribe(final PublicKey key,
                                final Consumer<Subscription<TxLogs>> onSub,
                                final Consumer<TxLogs> consumer) {
    return logsSubscribe(defaultCommitment(), key, onSub, consumer);
  }

  default boolean logsSubscribe(final Commitment commitment, final PublicKey key, final Consumer<TxLogs> consumer) {
    return logsSubscribe(commitment, key, null, consumer);
  }

  boolean logsSubscribe(final Commitment commitment,
                        final PublicKey key,
                        final Consumer<Subscription<TxLogs>> onSub,
                        final Consumer<TxLogs> consumer);

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

  default boolean signatureSubscribe(final Commitment commitment,
                                     final boolean enableReceivedNotification,
                                     final String b58TxSig,
                                     final Consumer<TxResult> consumer) {
    return signatureSubscribe(commitment, enableReceivedNotification, b58TxSig, null, consumer);
  }

  default boolean signatureSubscribe(final String b58TxSig,
                                     final Consumer<Subscription<TxResult>> onSub,
                                     final Consumer<TxResult> consumer) {
    return signatureSubscribe(defaultCommitment(), b58TxSig, onSub, consumer);
  }

  default boolean signatureSubscribe(final String b58TxSig,
                                     final boolean enableReceivedNotification,
                                     final Consumer<Subscription<TxResult>> onSub,
                                     final Consumer<TxResult> consumer) {
    return signatureSubscribe(defaultCommitment(), enableReceivedNotification, b58TxSig, onSub, consumer);
  }

  default boolean signatureSubscribe(final Commitment commitment,
                                     final String b58TxSig,
                                     final Consumer<Subscription<TxResult>> onSub,
                                     final Consumer<TxResult> consumer) {
    return signatureSubscribe(commitment, commitment == Commitment.PROCESSED, b58TxSig, onSub, consumer);
  }

  /// Only frame safety is checked locally. Whether the signature is valid is the server's call;
  /// its rejection is correlated, releases the registration, and is reported through
  /// [#exceptionSubscribe(Consumer)]. Overloads without `enableReceivedNotification` enable it
  /// only at [Commitment#PROCESSED].
  ///
  /// @throws IllegalArgumentException if `b58TxSig` is null, empty, or contains a quote,
  ///                                 backslash or character below U+0020. A throw, not `false`,
  ///                                 which means this signature and commitment are already
  ///                                 subscribed.
  boolean signatureSubscribe(final Commitment commitment,
                             final boolean enableReceivedNotification,
                             final String b58TxSig,
                             final Consumer<Subscription<TxResult>> onSub,
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

  default boolean programSubscribe(final Commitment commitment,
                                   final PublicKey program,
                                   final List<Filter> filters,
                                   final Consumer<AccountInfo<byte[]>> consumer) {
    return programSubscribe(
        commitment,
        program,
        filters,
        null
        , consumer
    );
  }

  default boolean programSubscribe(final PublicKey program,
                                   final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                                   final Consumer<AccountInfo<byte[]>> consumer) {
    return programSubscribe(
        program,
        null,
        onSub,
        consumer
    );
  }

  default boolean programSubscribe(final PublicKey program,
                                   final List<Filter> filters,
                                   final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                                   final Consumer<AccountInfo<byte[]>> consumer) {
    return programSubscribe(
        defaultCommitment(),
        program,
        filters,
        onSub,
        consumer
    );
  }

  boolean programSubscribe(final Commitment commitment,
                           final PublicKey program,
                           final List<Filter> filters,
                           final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                           final Consumer<AccountInfo<byte[]>> consumer);

  boolean programUnsubscribe(final PublicKey program);

  boolean programUnsubscribe(final Commitment commitment, final PublicKey program);

  /// Registers a program subscription under a caller-chosen key, so that, unlike
  /// [#programSubscribe(PublicKey,List,Consumer)], distinct keys may subscribe to the same
  /// program and commitment with different filters. Keys are not scoped to a program: the
  /// `(key, commitment)` pair is unique across keyed program subscriptions, and passing it to
  /// [#keyedProgramUnsubscribe(Commitment,String)] removes exactly this registration.
  ///
  /// @param subscriptionKey unique among keyed program subscriptions at this commitment
  /// @throws IllegalArgumentException if `subscriptionKey` is null or empty
  /// @throws UnsupportedOperationException if this implementation does not provide keyed
  ///                                       program subscriptions
  default boolean keyedProgramSubscribe(final String subscriptionKey,
                                         final PublicKey program,
                                         final List<Filter> filters,
                                         final Consumer<AccountInfo<byte[]>> consumer) {
    return keyedProgramSubscribe(defaultCommitment(), subscriptionKey, program, filters, null, consumer);
  }

  default boolean keyedProgramSubscribe(final Commitment commitment,
                                         final String subscriptionKey,
                                         final PublicKey program,
                                         final List<Filter> filters,
                                         final Consumer<AccountInfo<byte[]>> consumer) {
    return keyedProgramSubscribe(commitment, subscriptionKey, program, filters, null, consumer);
  }

  default boolean keyedProgramSubscribe(final String subscriptionKey,
                                         final PublicKey program,
                                         final List<Filter> filters,
                                         final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                                         final Consumer<AccountInfo<byte[]>> consumer) {
    return keyedProgramSubscribe(defaultCommitment(), subscriptionKey, program, filters, onSub, consumer);
  }

  default boolean keyedProgramSubscribe(final Commitment commitment,
                                         final String subscriptionKey,
                                         final PublicKey program,
                                         final List<Filter> filters,
                                         final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                                         final Consumer<AccountInfo<byte[]>> consumer) {
    throw new UnsupportedOperationException("Keyed program subscriptions are not supported by this implementation.");
  }

  /// Removes the keyed program registration at the default commitment.
  ///
  /// @throws IllegalArgumentException if `subscriptionKey` is null or empty
  /// @throws UnsupportedOperationException if this implementation does not provide keyed
  ///                                       program subscriptions
  default boolean keyedProgramUnsubscribe(final String subscriptionKey) {
    return keyedProgramUnsubscribe(defaultCommitment(), subscriptionKey);
  }

  default boolean keyedProgramUnsubscribe(final Commitment commitment, final String subscriptionKey) {
    throw new UnsupportedOperationException("Keyed program subscriptions are not supported by this implementation.");
  }

  default boolean slotSubscribe(final Consumer<ProcessedSlot> consumer) {
    return slotSubscribe(null, consumer);
  }

  boolean slotSubscribe(final Consumer<Subscription<ProcessedSlot>> onSub, final Consumer<ProcessedSlot> consumer);

  boolean slotUnsubscribe();

  default boolean rootSubscribe(final Consumer<Long> consumer) {
    return rootSubscribe(null, consumer);
  }

  boolean rootSubscribe(final Consumer<Subscription<Long>> onSub, final Consumer<Long> consumer);

  boolean rootUnsubscribe();

  /// Subscribe to a websocket method which is not directly supported by this interface, e.g.
  /// Helius' transactionSubscribe. Subscriptions are replayed if the connection is re-connected.
  ///
  /// @param unSubscribeMethod  bound by the first registration under `notificationMethod`;
  ///                           later registrations must agree until its last key is released.
  ///                           After a rebind, an older cancellation's wrong-method rejection
  ///                           is reported, not retried.
  /// @param notificationMethod the method of the corresponding notification messages.
  /// @param key                unique within `notificationMethod`; used for de-duplication and
  ///                           to [unsubscribe][#unsubscribe(String,String)].
  /// @param paramsJson         placed RAW within the request params array — validity and
  ///                           escaping are the caller's responsibility.
  /// @param parser             applied positioned at the notification params result value.
  /// @throws IllegalArgumentException if any method name is null, empty, or contains a quote,
  ///                                  backslash or character below U+0020, names a built-in
  ///                                  channel's notification, subscribe, or unsubscribe method,
  ///                                  or disagrees with the un-subscription method already bound
  ///                                  to this notification method.
  default <T> boolean subscribe(final String subscribeMethod,
                                final String unSubscribeMethod,
                                final String notificationMethod,
                                final String key,
                                final String paramsJson,
                                final Function<JsonIterator, T> parser,
                                final Consumer<T> consumer) {
    return subscribe(subscribeMethod, unSubscribeMethod, notificationMethod, key, paramsJson, parser, null, consumer);
  }

  <T> boolean subscribe(final String subscribeMethod,
                        final String unSubscribeMethod,
                        final String notificationMethod,
                        final String key,
                        final String paramsJson,
                        final Function<JsonIterator, T> parser,
                        final Consumer<Subscription<T>> onSub,
                        final Consumer<T> consumer);

  /// Unsubscribe from a subscription created via [#subscribe].
  boolean unsubscribe(final String notificationMethod, final String key);

  /// Once closed, this WebSocket is no longer usable: subscriptions return false, [#connect()]
  /// returns null, and inbound frames are ignored. A close frame is sent, and the transport is
  /// aborted a few seconds later if the peer never replies.
  @Override
  void close();

  /// Every delay and timeout set here is in milliseconds.
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

    /// Cap on a single (possibly fragmented) text message, in chars, defaulting to 2^26: well
    /// above a base64-encoded 10 MiB account, the network's account data cap. A longer message
    /// aborts the connection and is reported through [#onError(BiConsumer)]; the cap bounds the
    /// reassembly buffer against a server that never sends a final frame.
    ///
    /// @throws IllegalArgumentException if maxMessageLength is not positive.
    Builder maxMessageLength(final int maxMessageLength);

    int maxMessageLength();

    /// How long the whole handshake (DNS, TCP, TLS and the HTTP upgrade) may take, in
    /// milliseconds; independent of [#reConnectDelay(long)].
    ///
    /// @throws IllegalArgumentException if connectTimeout is not positive.
    /// @throws UnsupportedOperationException if this implementation cannot configure an
    ///                                       independent handshake timeout
    default Builder connectTimeout(final long connectTimeout) {
      throw unsupportedTiming("connectTimeout");
    }

    Builder reConnectDelay(final long reConnectDelay);

    /// How long the peer may be silent before a Ping is sent, in milliseconds. The same window
    /// then bounds the Ping's send, and, from successful send completion, the wait for any peer
    /// frame; a frame racing a still-pending send answers the probe, but the send must still
    /// settle in time. A failed probe aborts the transport and is reported through
    /// [#onError(BiConsumer)].
    Builder pingDelay(final long pingDelay);

    Builder subscriptionAndPingCheckDelay(final long subscriptionAndPingCheckDelay);

    /// How long this end may be silent before a Ping is sent, in milliseconds, even while the
    /// peer is talking; it guards against something in the path that ages the connection on what
    /// it receives from us. Ordinary proxies and load balancers reset on traffic in either
    /// direction, so set this when something enforces client liveness. Defaults to a multiple of
    /// [#pingDelay()] with no cap, so raising the ping delay raises this too.
    ///
    /// @throws IllegalArgumentException if keepAliveDelay is not positive.
    /// @throws UnsupportedOperationException if this implementation cannot configure an
    ///                                       independent keep-alive delay
    default Builder keepAliveDelay(final long keepAliveDelay) {
      throw unsupportedTiming("keepAliveDelay");
    }

    /// How long a failed subscription send waits before it is retried, in milliseconds.
    /// Defaults to [#reConnectDelay()] floored at [#subscriptionAndPingCheckDelay()] and at 1.
    ///
    /// A successfully sent request is not re-sent while it awaits an answer, since a duplicate
    /// would create an orphaned server subscription; one left unanswered for four of these
    /// windows aborts the connection and is reported through [#onError(BiConsumer)], leaving
    /// recovery to the reconnect policy. This delay also paces the retry of a subscription or
    /// un-subscription the server refused transiently, and replay after a reconnect (a
    /// re-queued subscription keeps its last attempt stamp, so a large value delays replay by up
    /// to one window).
    ///
    /// @throws IllegalArgumentException if subscriptionResendDelay is not positive.
    /// @throws UnsupportedOperationException if this implementation cannot configure an
    ///                                       independent subscription re-send delay
    default Builder subscriptionResendDelay(final long subscriptionResendDelay) {
      throw unsupportedTiming("subscriptionResendDelay");
    }

    Builder commitment(final Commitment commitment);

    Builder solanaAccounts(final SolanaAccounts solanaAccounts);

    URI wsUri();

    WebSocket.Builder webSocketBuilder();

    /// Defaults to [#reConnectDelay()], the handshake timeout of a builder without an independent
    /// setting.
    default long connectTimeout() {
      return reConnectDelay();
    }

    long reConnectDelay();

    long pingDelay();

    long subscriptionAndPingCheckDelay();

    /// Defaults to the built-in builder's derived value, a multiple of [#pingDelay()].
    default long keepAliveDelay() {
      return Timings.keepAliveFor(pingDelay());
    }

    /// Defaults to the built-in builder's derived value; see [#subscriptionResendDelay(long)].
    default long subscriptionResendDelay() {
      return Timings.resendDelayFor(reConnectDelay(), subscriptionAndPingCheckDelay());
    }

    private static UnsupportedOperationException unsupportedTiming(final String timing) {
      return new UnsupportedOperationException(
          timing + " is not configurable by this SolanaRpcWebsocket.Builder implementation.");
    }

    SolanaAccounts solanaAccounts();

    Commitment commitment();

    Consumer<SolanaRpcWebsocket> onOpen();

    Builder onOpen(final Consumer<SolanaRpcWebsocket> onOpen);

    OnClose onClose();

    /// Called when the peer closes the current transport, after that transport is retired.
    /// Without a handler the instance [closes][SolanaRpcWebsocket#close()]; a handler may instead
    /// [reconnect][SolanaRpcWebsocket#connect()] and reuse this instance, replaying its durable
    /// registrations. A close following a failed Ping send is reported through
    /// [#onError(BiConsumer)] instead. Runs without the lifecycle lock. It may duplicate an
    /// exceptional completion of the attempt's future, and carries no attempt identity; see
    /// [SolanaRpcWebsocket#connect()].
    Builder onClose(final OnClose onClose);

    BiConsumer<SolanaRpcWebsocket, Throwable> onError();

    /// Called for an error on the current transport, after that transport is retired. Without a
    /// handler the error is logged and the instance [closes][SolanaRpcWebsocket#close()]; a
    /// handler may instead [reconnect][SolanaRpcWebsocket#connect()] and reuse this instance.
    /// If the internal check loop dies, this is called and the instance then closes regardless:
    /// reconnecting requires a new instance.
    ///
    /// Runs without the lifecycle lock, on whichever thread reports the failure; keep it brief
    /// and non-blocking. It may duplicate an exceptional completion of the attempt's future, and
    /// carries no attempt identity; see [SolanaRpcWebsocket#connect()].
    Builder onError(final BiConsumer<SolanaRpcWebsocket, Throwable> onError);

    BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError();

    Builder onSendTextError(final BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError);

    BiConsumer<SolanaRpcWebsocket, Throwable> onPingError();

    /// Observes an outbound Ping whose send throws or completes exceptionally, after the
    /// transport is aborted and the [#onError(BiConsumer)] handling has run. A send that never
    /// completes, or a probe that gets no peer frame, reaches only [#onError(BiConsumer)].
    Builder onPingError(final BiConsumer<SolanaRpcWebsocket, Throwable> onPingError);
  }
}
