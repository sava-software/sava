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

  /// When a message was last received, in epoch milliseconds, or 0 when none has been since
  /// this connection opened.
  ///
  /// This is the only evidence available that a connection is still carrying traffic. Nothing
  /// else here reports it: [#closed()] says only that [#close()] was called, so a half open
  /// socket, or one whose subscriptions were dropped server side, reports itself open
  /// indefinitely.
  ///
  /// Only messages count. A ping or a pong proves the transport is alive but says nothing about
  /// whether subscriptions are still being served, and conflating the two would hide exactly the
  /// failure this exists to expose. A message which fails to parse still counts: it is evidence
  /// the connection delivered something, which is the question being asked.
  ///
  /// Silence is not by itself a failure — a quiet subscription is quiet — so treat this as a
  /// lower bound on liveness and weigh it against how much traffic the subscriptions should be
  /// producing. The 0 returned before the first message reads as no evidence rather than as
  /// evidence of death, so a caller gating a fallback on it falls back rather than trusting a
  /// connection nothing has vouched for.
  long lastMessageReceivedTimestamp();

  boolean closed();

  /// @return A CompletableFuture which completes once the underlying WebSocket is connected.
  /// `null` will be returned if this has been [closed][#close()].
  ///
  /// See [java.net.http.WebSocket.Builder#buildAsync(URI,WebSocket.Listener)] for potential exceptions.
  ///
  /// This may be used to re-connect the underlying WebSocket if this has not been [closed][#close()].
  /// [Timings#reConnectDelay()] (milliseconds, like every delay here) will delay the connection
  /// attempt if a previous attempt has already been made. Attempts are single-flight: while one
  /// is unsettled, every caller receives a future that settles with that attempt — a private
  /// copy, so cancelling it abandons only that caller's view — rather than starting another
  /// handshake. The socket being replaced is aborted; its late callbacks are ignored.
  CompletableFuture<?> connect();

  /// Registers a consumer for engine-reported failures: correlated request rejections, terminal
  /// registration collisions, and consumer bugs contained by the dispatch paths. Each subscriber
  /// is contained — one subscriber throwing does not starve the rest. A no-op once
  /// [closed][#close()]: close releases every consumer reference, and a late registration must
  /// not re-pin its caller to a dead instance.
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

  /// Every `signatureSubscribe` overload arrives here, so this is where the signature is
  /// checked for frame safety: it is the one caller supplied string that reaches the wire
  /// inside a frame, and a quote, backslash or control character would splice into that frame
  /// — the server then answers a parse error with a null id, leaving the request permanently
  /// uncorrelatable. Semantic validity is deliberately the server's call: a well formed frame
  /// carrying an invalid signature is rejected with the request id attached, which this client
  /// correlates, retires and reports (measured against api.mainnet-beta.solana.com).
  ///
  /// @throws IllegalArgumentException if `b58TxSig` is null, empty, or contains a character
  ///                                 that cannot travel inside a JSON string. Rejection is a
  ///                                 throw and not a `false` return because `false` already
  ///                                 means this signature and commitment are subscribed.
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
  /// @param subscribeMethod    the subscription request method.
  /// @param unSubscribeMethod  the corresponding un-subscription request method. Fixed by the
  ///                           first registration under a notification method: later
  ///                           registrations must agree, so unknown-id recovery never has to
  ///                           pick among divergent methods. The binding lives only as long as
  ///                           some registration under the method remains — releasing the last
  ///                           key releases the binding, and a re-registration may then bind a
  ///                           different method while an older cancellation is still
  ///                           outstanding; its wrong-method rejection is reported, not
  ///                           retried.
  /// @param notificationMethod the method of the corresponding notification messages.
  /// @param key                unique key within this notification method, used for
  ///                           de-duplication and to unsubscribe.
  /// @param paramsJson         placed RAW within the request params array — validity and
  ///                           escaping are the caller's responsibility.
  /// @param parser             applied positioned at the notification params result value.
  /// @throws IllegalArgumentException if any method name could splice the frame, names a
  ///                                  built-in channel's notification, subscribe, or
  ///                                  unsubscribe method, or disagrees with the un-subscription
  ///                                  method already bound to this notification method.
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

  /// Once closed, this WebSocket is no longer usable: subscriptions return false, `connect()`
  /// returns null, and inbound frames are ignored. A close frame is sent politely, and the
  /// transport is aborted a few seconds later if the peer never replies — the release of the
  /// socket is bounded, not dependent on the peer's cooperation.
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

    /// Cap on a single (possibly fragmented) text message, in chars, defaulting to
    /// 2^26 — 67,108,864 chars, a 128 MiB buffer, which a 10 MiB account (the
    /// network's account data cap) base64-encodes well inside. A message the cap
    /// excludes aborts the connection and surfaces through `onError` — without
    /// one, the fragment reassembly buffer grows until OOM against a server that
    /// never sends a final frame.
    ///
    /// @throws IllegalArgumentException if maxMessageLength is not positive.
    Builder maxMessageLength(final int maxMessageLength);

    int maxMessageLength();

    /// How long the whole handshake — DNS, TCP, TLS and the HTTP upgrade — may take. Separate
    /// from [#reConnectDelay(long)]: a handshake budget and a retry cadence have no reason to be
    /// the same number.
    Builder connectTimeout(final long connectTimeout);

    Builder reConnectDelay(final long reConnectDelay);

    Builder pingDelay(final long pingDelay);

    Builder subscriptionAndPingCheckDelay(final long subscriptionAndPingCheckDelay);

    /// How long this end may be silent before it pokes the peer, guarding against an
    /// intermediary that ages a connection on what it receives from us. Defaults to a multiple
    /// of [#pingDelay()], so tuning only the ping delay moves this proportionately.
    Builder keepAliveDelay(final long keepAliveDelay);

    /// How long a FAILED subscription send waits before it is retried, in milliseconds — and,
    /// times four, the deadline after which a sent-but-never-answered request replaces the
    /// connection. Defaults to [#reConnectDelay()] floored at [#subscriptionAndPingCheckDelay()].
    ///
    /// A successfully sent request is never re-sent on its own connection: JSON-RPC ids
    /// correlate responses, they do not deduplicate calls, so a duplicate of a merely slow
    /// subscribe would create a second, orphaned server subscription. Only a send that failed
    /// outright retries, at this cadence; a request the server never answers at all escalates
    /// through the error seam by aborting the connection, so reconnect policy — not a duplicate
    /// frame — resolves it. This delay also paces replay after a reconnect — a re-queued
    /// subscription keeps its last attempt stamp — and the retry of an un-subscription the
    /// server refused transiently, so an immediately-refusing peer is not retried at wire
    /// speed.
    ///
    /// @throws IllegalArgumentException if subscriptionResendDelay is not positive.
    Builder subscriptionResendDelay(final long subscriptionResendDelay);

    Builder commitment(final Commitment commitment);

    Builder solanaAccounts(final SolanaAccounts solanaAccounts);

    URI wsUri();

    WebSocket.Builder webSocketBuilder();

    long connectTimeout();

    long reConnectDelay();

    long pingDelay();

    long subscriptionAndPingCheckDelay();

    long keepAliveDelay();

    long subscriptionResendDelay();

    SolanaAccounts solanaAccounts();

    Commitment commitment();

    Consumer<SolanaRpcWebsocket> onOpen();

    Builder onOpen(final Consumer<SolanaRpcWebsocket> onOpen);

    OnClose onClose();

    /// The default behavior is to [#close()] this WebSocket.
    ///
    /// This behavior can be changed to instead attempt to [re-connect][#connect()] the underlying WebSocket and re-use this instance.
    Builder onClose(final OnClose onClose);

    BiConsumer<SolanaRpcWebsocket, Throwable> onError();

    /// The default behavior is to [#close()] this WebSocket.
    ///
    /// For a socket error this behavior can be changed to instead attempt to
    /// [re-connect][#connect()] the underlying WebSocket and re-use this instance. One failure
    /// is different: if the internal check loop itself dies, this handler is invoked and the
    /// instance is then closed regardless — the loop's thread is gone, so reconnecting requires
    /// a new instance.
    ///
    /// Handlers may be invoked while internal locks are held and on whichever thread settles
    /// the underlying operation; keep them brief and non-blocking.
    Builder onError(final BiConsumer<SolanaRpcWebsocket, Throwable> onError);

    BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError();

    Builder onSendTextError(final BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError);

    BiConsumer<SolanaRpcWebsocket, Throwable> onPingError();

    Builder onPingError(final BiConsumer<SolanaRpcWebsocket, Throwable> onPingError);
  }
}
