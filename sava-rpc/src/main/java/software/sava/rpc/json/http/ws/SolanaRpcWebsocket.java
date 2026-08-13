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
  /// This is application-message evidence, distinct from the transport watchdog: an unanswered
  /// Ping is reported through the error callback, but a Pong says nothing about whether the peer
  /// is still serving subscriptions. [#closed()] says only that [#close()] was called.
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
  ///
  /// Implementations compiled before this observation was added have no timestamp to expose.
  /// Returning no evidence keeps those implementations link-compatible and gives callers the
  /// conservative answer promised above rather than an [AbstractMethodError].
  default long lastMessageReceivedTimestamp() {
    return 0;
  }

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
  ///
  /// Callers must not assume exactly-once or correlated failure notification across the returned
  /// future and the lifecycle callbacks. In the implementation created by [#build()], retiring a
  /// current transport while its connection attempt remains unsettled settles that attempt
  /// exceptionally before invoking a configured [Builder#onClose(OnClose)] or
  /// [Builder#onError(BiConsumer)] callback. A future returned for that attempt may therefore
  /// expose a separate exceptional observation. The future and callback need not expose the same
  /// details: the future may report cancellation or invalidation while the callback reports the
  /// transport error or close status and reason.
  ///
  /// A returned future represents its connection attempt. Lifecycle callbacks receive this
  /// reusable wrapper and no attempt token, so this API cannot attribute a callback to a
  /// particular future. Recovery code that consumes both channels must treat them as potentially
  /// overlapping, uncorrelated signals rather than independently attributing each one to the
  /// current attempt. Delaying reconnect can narrow the overlap but cannot provide attribution.
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

  /// Registers a program subscription under an explicit caller identity. Unlike
  /// [#programSubscribe(PublicKey,List,Consumer)], distinct keys may subscribe to the same
  /// program and commitment with different filters. The `(key, commitment)` pair is unique
  /// across keyed program subscriptions — keys are not scoped to a program — and the same pair
  /// passed to `keyedProgramUnsubscribe` removes exactly this durable registration.
  ///
  /// This additive API is a capability: implementations predating it remain binary compatible
  /// and report the unsupported operation clearly. Existing `programSubscribe` identity and
  /// duplicate behavior are unchanged.
  ///
  /// @param subscriptionKey a non-null, non-empty identity unique among keyed program
  ///                        subscriptions at this commitment
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

  /// Removes the explicitly keyed program registration at the default commitment.
  ///
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
    ///
    /// This is an additive capability: a builder compiled before independent handshake timing
    /// cannot retain the value without also changing its reconnect cadence.
    ///
    /// @throws UnsupportedOperationException if this implementation cannot configure an
    ///                                       independent handshake timeout
    default Builder connectTimeout(final long connectTimeout) {
      throw unsupportedTiming("connectTimeout");
    }

    Builder reConnectDelay(final long reConnectDelay);

    /// How long the peer may be silent before a Ping is sent, how long that send may remain
    /// pending, and how long a successfully sent probe may remain unanswered before the transport
    /// is treated as unresponsive and reported through [#onError(BiConsumer)]. A peer frame which
    /// races a still-pending send already answers the probe, but the send operation must still
    /// settle within its own window. The peer-response window starts at successful send
    /// completion, not when the Ping was admitted for sending.
    Builder pingDelay(final long pingDelay);

    Builder subscriptionAndPingCheckDelay(final long subscriptionAndPingCheckDelay);

    /// How long this end may be silent before it pokes the peer, guarding against an
    /// intermediary that ages a connection on what it receives from us. Defaults to a multiple
    /// of [#pingDelay()], so tuning only the ping delay moves this proportionately.
    ///
    /// @throws UnsupportedOperationException if this implementation cannot configure an
    ///                                       independent keep-alive delay
    default Builder keepAliveDelay(final long keepAliveDelay) {
      throw unsupportedTiming("keepAliveDelay");
    }

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
    /// @throws UnsupportedOperationException if this implementation cannot configure an
    ///                                       independent subscription re-send delay
    default Builder subscriptionResendDelay(final long subscriptionResendDelay) {
      throw unsupportedTiming("subscriptionResendDelay");
    }

    Builder commitment(final Commitment commitment);

    Builder solanaAccounts(final SolanaAccounts solanaAccounts);

    URI wsUri();

    WebSocket.Builder webSocketBuilder();

    /// A legacy builder used the reconnect delay as its handshake timeout. Deriving that value
    /// preserves its observable configuration while allowing implementations predating this
    /// accessor to remain link-compatible.
    default long connectTimeout() {
      return reConnectDelay();
    }

    long reConnectDelay();

    long pingDelay();

    long subscriptionAndPingCheckDelay();

    /// Legacy builders have no independent keep-alive setting, so they inherit the same derived
    /// default as the built-in builder.
    default long keepAliveDelay() {
      return Timings.keepAliveFor(pingDelay());
    }

    /// Legacy websocket engines paced retries through their reconnect and check delays. This is
    /// the value the built-in builder now derives when no independent setting is supplied.
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

    /// The current underlying transport is retired before this callback. The default behavior is
    /// to [#close()] this WebSocket and its subscription registries.
    ///
    /// This behavior can be changed to instead attempt to [re-connect][#connect()] the underlying
    /// WebSocket and re-use this instance; durable registrations are replayed on its successor.
    /// The handler runs without the websocket lifecycle lock.
    ///
    /// When this callback follows a transport retirement, do not assume it is the only observation
    /// of that retirement. In the implementation created by [SolanaRpcWebsocket#build()], if that
    /// transport's connection attempt is still unsettled, the attempt is settled exceptionally
    /// before this configured callback is invoked. A future returned for that attempt may expose a
    /// separate exceptional observation, and the two channels need not expose the same details. The
    /// [SolanaRpcWebsocket] passed here is the reusable wrapper and carries no identity for that
    /// attempt; see [SolanaRpcWebsocket#connect()].
    Builder onClose(final OnClose onClose);

    BiConsumer<SolanaRpcWebsocket, Throwable> onError();

    /// For an error attributed to the current underlying transport, that transport is retired
    /// before this callback. The default behavior is to [#close()] this WebSocket and its
    /// subscription registries.
    ///
    /// For a socket error this behavior can be changed to instead attempt to
    /// [re-connect][#connect()] the underlying WebSocket and re-use this instance. One failure
    /// is different: if the internal check loop itself dies, this handler is invoked and the
    /// instance is then closed regardless — the loop's thread is gone, so reconnecting requires
    /// a new instance.
    ///
    /// This handler runs without the websocket lifecycle lock, on whichever thread reports the
    /// failure; keep it brief and non-blocking.
    ///
    /// When this callback follows a transport retirement, do not assume it is the only observation
    /// of that retirement. In the implementation created by [SolanaRpcWebsocket#build()], if that
    /// transport's connection attempt is still unsettled, the attempt is settled exceptionally
    /// before this configured callback is invoked. A future returned for that attempt may expose a
    /// separate exceptional observation, and the two channels need not expose the same details. The
    /// [SolanaRpcWebsocket] passed here is the reusable wrapper and carries no identity for that
    /// attempt; see [SolanaRpcWebsocket#connect()].
    Builder onError(final BiConsumer<SolanaRpcWebsocket, Throwable> onError);

    BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError();

    Builder onSendTextError(final BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError);

    BiConsumer<SolanaRpcWebsocket, Throwable> onPingError();

    /// Observes an outbound Ping whose send operation throws or completes exceptionally. The
    /// failed transport is also aborted and reported through [#onError(BiConsumer)]; a send which
    /// never completes, or one which succeeds but receives no peer frame, uses only that ordinary
    /// error callback because no exceptional send completion was reported.
    Builder onPingError(final BiConsumer<SolanaRpcWebsocket, Throwable> onPingError);
  }
}
