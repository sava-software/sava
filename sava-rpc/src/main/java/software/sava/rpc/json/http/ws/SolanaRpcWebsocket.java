package software.sava.rpc.json.http.ws;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.rpc.DataSlice;
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

/// Subscribe calls answer `false` once the socket is closed, or when the same registration
/// already exists: for the typed subscriptions the same key at the same commitment (one key
/// may be registered once per commitment), for the generic ones the same key under the same
/// notification method; unsubscribe calls answer whether a registration was removed.
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

  /// Runs `registrations` on this websocket and wakes the internal check loop once, when it
  /// returns, rather than once per call. The subscribes and unsubscribes made inside it on the
  /// calling thread are ordinary calls: each decides, answers, throws and later calls back
  /// exactly as it does outside a scope, and each queues its request, so the pass after the
  /// scope can send them together, cancellations first as in every pass. Only this thread's
  /// calls on this websocket are deferred: other threads' registrations, those of threads the
  /// scope starts included, a `StructuredTaskScope`'s subtasks among them, and the websocket's
  /// own wakes (connection, acknowledgements, pings and close) are not, so a pass may still run,
  /// and send part of the scope's work, while the scope is open. Deferral follows the thread,
  /// not the task: work this thread runs on another's behalf inside the scope, such as a
  /// `ForkJoinTask` it forks and then joins, a task a caller-runs policy hands back to it, or its
  /// share of a parallel stream, is this thread's call and waits for the exit, so such work must
  /// not wait inside the scope for its own `onSub`. Nested calls wake once, at the outermost
  /// return. If `registrations` throws, the loop is woken for what it had queued and the
  /// exception then propagates; those registrations stay registered. `registerAll` takes no lock
  /// around `registrations`, which run with whatever locks the calling thread holds: a scope
  /// opened from an `onSub` callback runs under the lifecycle lock whenever that callback does,
  /// as it does when a pass sends its request and the send completes at once. There, every
  /// other thread's registration waits for that lock until the callback and the pass around it
  /// return, and no pass runs meanwhile, so a scope there must not wait for another thread's
  /// registration: joining a thread it started, a task scope's join included, would never
  /// return, and would hold the lock against `close()`.
  ///
  /// @param registrations subscribe and unsubscribe calls on the websocket it is given
  /// @throws NullPointerException if `registrations` is null
  void registerAll(final Consumer<? super SolanaRpcWebsocket> registrations);

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

  /// Returns the requested slice of account data. Agave before 4.4 ignores this option.
  /// A zero length requests empty data; the account metadata, including `space`, remains whole.
  /// The slice does not change registration identity: the account at the same
  /// commitment still occupies one registration.
  ///
  /// @param dataSlice the window of account data to return, [DataSlice#EMPTY] for metadata only;
  ///                  `null` returns whole accounts like the overloads without a slice
  default boolean accountSubscribe(final DataSlice dataSlice,
                                   final PublicKey key,
                                   final Consumer<AccountInfo<byte[]>> consumer) {
    return accountSubscribe(defaultCommitment(), dataSlice, key, null, consumer);
  }

  default boolean accountSubscribe(final DataSlice dataSlice,
                                   final PublicKey key,
                                   final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                                   final Consumer<AccountInfo<byte[]>> consumer) {
    return accountSubscribe(defaultCommitment(), dataSlice, key, onSub, consumer);
  }

  default boolean accountSubscribe(final Commitment commitment,
                                   final DataSlice dataSlice,
                                   final PublicKey key,
                                   final Consumer<AccountInfo<byte[]>> consumer) {
    return accountSubscribe(commitment, dataSlice, key, null, consumer);
  }

  boolean accountSubscribe(final Commitment commitment,
                           final DataSlice dataSlice,
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

  default boolean logsSubscribeAll(final Consumer<TxLogs> consumer) {
    return logsSubscribeAll(defaultCommitment(), null, consumer);
  }

  default boolean logsSubscribeAll(final Commitment commitment, final Consumer<TxLogs> consumer) {
    return logsSubscribeAll(commitment, null, consumer);
  }

  default boolean logsSubscribeAll(final Consumer<Subscription<TxLogs>> onSub, final Consumer<TxLogs> consumer) {
    return logsSubscribeAll(defaultCommitment(), onSub, consumer);
  }

  /// Subscribes to the logs of every transaction the node processes, through agave's `all`
  /// filter, which leaves out vote transactions. Each notification carries one transaction's
  /// signature, error and log messages as a [TxLogs]. The stream is node-wide and large on
  /// public mainnet: it is meant for local validators and indexers, while
  /// [#logsSubscribe(Commitment,PublicKey,Consumer,Consumer)] follows a single account or
  /// program.
  ///
  /// One registration per commitment, independent of the `mentions` registrations: both may be
  /// live at the same commitment, and each receives only its own subscription's notifications.
  /// The registration handed to `onSub` has the [Subscription#key()] `all` and a null
  /// [Subscription#publicKey()]. Agave's `allWithVotes` filter is not offered: agave 4.5
  /// deprecates it as an alias of `all`, since Alpenglow has no vote transactions.
  ///
  /// @return `false` once closed, or while the `all` registration at this commitment exists
  boolean logsSubscribeAll(final Commitment commitment,
                           final Consumer<Subscription<TxLogs>> onSub,
                           final Consumer<TxLogs> consumer);

  default boolean logsUnsubscribeAll() {
    return logsUnsubscribeAll(defaultCommitment());
  }

  /// Removes the `all` registration at `commitment`; `mentions` registrations are untouched.
  boolean logsUnsubscribeAll(final Commitment commitment);

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

  /// Follows SPL Token accounts only: it subscribes under the SPL Token program, so no Token-2022
  /// account is seen, and its 165-byte size filter would drop any Token-2022 account carrying an
  /// extension anyway; see
  /// [#subscribeToTokenAccount(Commitment,PublicKey,PublicKey,PublicKey,Consumer)] for a named
  /// token program.
  boolean subscribeToTokenAccount(final PublicKey tokenMint,
                                  final PublicKey ownerAddress,
                                  final Consumer<AccountInfo<byte[]>> consumer);

  /// Follows SPL Token accounts only; see
  /// [#subscribeToTokenAccount(Commitment,PublicKey,PublicKey,PublicKey,Consumer)].
  boolean subscribeToTokenAccount(final Commitment commitment,
                                  final PublicKey tokenMint,
                                  final PublicKey ownerAddress,
                                  final Consumer<AccountInfo<byte[]>> consumer);

  /// Follows the token accounts of `tokenMint` owned by `ownerAddress` under `tokenProgram` at the
  /// client's default commitment; see
  /// [#subscribeToTokenAccount(Commitment,PublicKey,PublicKey,PublicKey,Consumer)].
  default boolean subscribeToTokenAccount(final PublicKey tokenProgram,
                                          final PublicKey tokenMint,
                                          final PublicKey ownerAddress,
                                          final Consumer<AccountInfo<byte[]>> consumer) {
    return subscribeToTokenAccount(defaultCommitment(), tokenProgram, tokenMint, ownerAddress, consumer);
  }

  /// Follows the token accounts of `tokenMint` owned by `ownerAddress` under `tokenProgram`, the
  /// SPL Token or the Token-2022 program, selecting initialized accounts with agave's
  /// `tokenAccountState` filter rather than a 165-byte size, so Token-2022 accounts carrying
  /// extensions are included and uninitialized accounts are not. Like
  /// [#programSubscribe(Commitment,PublicKey,List,Consumer)] it is keyed by the program and the
  /// commitment: one registration per program at a commitment, so a second subscription under
  /// the same program returns `false`, while one under the other token program is its own.
  boolean subscribeToTokenAccount(final Commitment commitment,
                                  final PublicKey tokenProgram,
                                  final PublicKey tokenMint,
                                  final PublicKey ownerAddress,
                                  final Consumer<AccountInfo<byte[]>> consumer);

  /// Follows SPL Token accounts only: it subscribes under the SPL Token program, so no Token-2022
  /// account is seen, and its 165-byte size filter would drop any Token-2022 account carrying an
  /// extension anyway; see [#subscribeToTokenAccounts(Commitment,PublicKey,PublicKey,Consumer)]
  /// for a named token program.
  boolean subscribeToTokenAccounts(final PublicKey ownerAddress,
                                   final Consumer<AccountInfo<byte[]>> consumer);

  /// Follows SPL Token accounts only; see
  /// [#subscribeToTokenAccounts(Commitment,PublicKey,PublicKey,Consumer)].
  boolean subscribeToTokenAccounts(final Commitment commitment,
                                   final PublicKey ownerAddress,
                                   final Consumer<AccountInfo<byte[]>> consumer);

  /// Follows every token account owned by `ownerAddress` under `tokenProgram` at the client's
  /// default commitment; see [#subscribeToTokenAccounts(Commitment,PublicKey,PublicKey,Consumer)].
  default boolean subscribeToTokenAccounts(final PublicKey tokenProgram,
                                           final PublicKey ownerAddress,
                                           final Consumer<AccountInfo<byte[]>> consumer) {
    return subscribeToTokenAccounts(defaultCommitment(), tokenProgram, ownerAddress, consumer);
  }

  /// Follows every token account owned by `ownerAddress` under `tokenProgram`, selected with
  /// agave's `tokenAccountState` filter as
  /// [#subscribeToTokenAccount(Commitment,PublicKey,PublicKey,PublicKey,Consumer)] describes, and
  /// keyed by the program and the commitment the same way.
  boolean subscribeToTokenAccounts(final Commitment commitment,
                                   final PublicKey tokenProgram,
                                   final PublicKey ownerAddress,
                                   final Consumer<AccountInfo<byte[]>> consumer);

  boolean programSubscribe(final PublicKey program, final Consumer<AccountInfo<byte[]>> consumer);

  /// Agave rejects any inbound message over 4,096 UTF-8 bytes by resetting the connection
  /// without an answer, so a filter-heavy request above that size is connection-fatal there; it
  /// is sent unchanged.
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

  /// Agave rejects any inbound message over 4,096 UTF-8 bytes by resetting the connection
  /// without an answer, so a filter-heavy request above that size is connection-fatal there; it
  /// is sent unchanged.
  boolean programSubscribe(final Commitment commitment,
                           final PublicKey program,
                           final List<Filter> filters,
                           final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                           final Consumer<AccountInfo<byte[]>> consumer);

  /// Returns the requested slice of account data. Agave before 4.4 ignores this option.
  /// A zero length requests empty data; the account metadata, including `space`, remains whole.
  /// The slice does not change registration identity: the program at the same
  /// commitment still occupies one registration.
  ///
  /// @param dataSlice the window of account data to return, [DataSlice#EMPTY] for metadata only;
  ///                  `null` returns whole accounts like the overloads without a slice
  default boolean programSubscribe(final DataSlice dataSlice,
                                   final PublicKey program,
                                   final List<Filter> filters,
                                   final Consumer<AccountInfo<byte[]>> consumer) {
    return programSubscribe(defaultCommitment(), dataSlice, program, filters, null, consumer);
  }

  default boolean programSubscribe(final DataSlice dataSlice,
                                   final PublicKey program,
                                   final List<Filter> filters,
                                   final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                                   final Consumer<AccountInfo<byte[]>> consumer) {
    return programSubscribe(defaultCommitment(), dataSlice, program, filters, onSub, consumer);
  }

  default boolean programSubscribe(final Commitment commitment,
                                   final DataSlice dataSlice,
                                   final PublicKey program,
                                   final List<Filter> filters,
                                   final Consumer<AccountInfo<byte[]>> consumer) {
    return programSubscribe(commitment, dataSlice, program, filters, null, consumer);
  }

  /// Agave rejects any inbound message over 4,096 UTF-8 bytes by resetting the connection
  /// without an answer, so a filter-heavy request above that size is connection-fatal there; it
  /// is sent unchanged.
  boolean programSubscribe(final Commitment commitment,
                           final DataSlice dataSlice,
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

  /// Agave rejects any inbound message over 4,096 UTF-8 bytes by resetting the connection
  /// without an answer, so a filter-heavy request above that size is connection-fatal there; it
  /// is sent unchanged.
  boolean keyedProgramSubscribe(final Commitment commitment,
                                 final String subscriptionKey,
                                 final PublicKey program,
                                 final List<Filter> filters,
                                 final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                                 final Consumer<AccountInfo<byte[]>> consumer);

  /// Returns the requested slice of account data. Agave before 4.4 ignores this option.
  /// A zero length requests empty data; the account metadata, including `space`, remains whole.
  /// The slice does not change registration identity: the caller key at the same
  /// commitment still occupies one registration. Use distinct caller keys for distinct slices.
  ///
  /// @param dataSlice the window of account data to return, [DataSlice#EMPTY] for metadata only;
  ///                  `null` returns whole accounts like the overloads without a slice
  default boolean keyedProgramSubscribe(final DataSlice dataSlice,
                                        final String subscriptionKey,
                                        final PublicKey program,
                                        final List<Filter> filters,
                                        final Consumer<AccountInfo<byte[]>> consumer) {
    return keyedProgramSubscribe(defaultCommitment(), dataSlice, subscriptionKey, program, filters, null, consumer);
  }

  default boolean keyedProgramSubscribe(final DataSlice dataSlice,
                                        final String subscriptionKey,
                                        final PublicKey program,
                                        final List<Filter> filters,
                                        final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                                        final Consumer<AccountInfo<byte[]>> consumer) {
    return keyedProgramSubscribe(defaultCommitment(), dataSlice, subscriptionKey, program, filters, onSub, consumer);
  }

  default boolean keyedProgramSubscribe(final Commitment commitment,
                                        final DataSlice dataSlice,
                                        final String subscriptionKey,
                                        final PublicKey program,
                                        final List<Filter> filters,
                                        final Consumer<AccountInfo<byte[]>> consumer) {
    return keyedProgramSubscribe(commitment, dataSlice, subscriptionKey, program, filters, null, consumer);
  }

  /// Agave rejects any inbound message over 4,096 UTF-8 bytes by resetting the connection
  /// without an answer, so a filter-heavy request above that size is connection-fatal there; it
  /// is sent unchanged.
  boolean keyedProgramSubscribe(final Commitment commitment,
                                final DataSlice dataSlice,
                                final String subscriptionKey,
                                final PublicKey program,
                                final List<Filter> filters,
                                final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                                final Consumer<AccountInfo<byte[]>> consumer);

  /// Removes the keyed program registration at the default commitment.
  ///
  /// @throws IllegalArgumentException if `subscriptionKey` is null or empty
  default boolean keyedProgramUnsubscribe(final String subscriptionKey) {
    return keyedProgramUnsubscribe(defaultCommitment(), subscriptionKey);
  }

  boolean keyedProgramUnsubscribe(final Commitment commitment, final String subscriptionKey);

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
  ///                                  to this notification method; or if `key` is null.
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

    /// Sends the subscribe requests waiting to go out as JSON-RPC batch messages of at most
    /// `subscribeBatchBytes` UTF-8 bytes each, brackets and separators included, so a burst of
    /// registrations or a reconnect replay costs the server one message per batch instead of one
    /// per request; made inside [SolanaRpcWebsocket#registerAll(Consumer)], a burst wakes the
    /// sender once, so its requests can share batches. 0, the default, turns batching off. Unlike
    /// [#maxMessageLength(int)], which counts the chars of a message received, this counts the
    /// bytes of a message sent. The cap is at most 4,096, Agave's inbound message limit, which
    /// counts the whole message and is inclusive: Agave resets the connection, without an answer,
    /// on any message above it, batched or not.
    ///
    /// Only typed subscribes share a message. A [generic subscribe][SolanaRpcWebsocket#subscribe]
    /// always travels alone, since its raw params could add calls of their own to an array or make
    /// the whole array unreadable, and an un-subscription travels alone, ahead of the subscribes
    /// queued after it. A request too big to share a message within the cap goes alone and
    /// unchanged, as it would with batching off, and so does one whose text the JDK cannot encode,
    /// so that its failed send takes no other request with it. A lone request goes as its plain
    /// object, never as an array of one, including the last of a batch whose others were cancelled
    /// before it was sent; a batch left with none sends nothing. Each request's `onSub` runs once
    /// its message is sent.
    ///
    /// The server must answer a batch with an array of responses, matched to the requests by id,
    /// never by position, and answer messages in the order it receives them, as Agave does. A
    /// server that cannot read a batch at all answers it with one parse error (-32700) carrying no
    /// readable id. That error is taken to answer the oldest batch still unanswered, provided no
    /// request sent before that batch still awaits an answer; the batch's requests are then re-sent
    /// at once, one per message, and batching stops for the life of this instance. An id-less
    /// error of any other code releases nothing, and nothing else stops batching for good. A
    /// connection lost while a batch it sent is still wholly unanswered, whatever ended it (a
    /// peer close, a transport failure, the unanswered-request deadline
    /// [#subscriptionResendDelay(long)] describes, the Ping deadline, an answer this client
    /// treats as fatal, or the caller's own [SolanaRpcWebsocket#connect()] over the open
    /// connection), is the trace a server that resets or closes on arrays leaves, so the one
    /// connection opened next sends its requests one per message for its life, with a WARNING
    /// when it opens, and the connection after that batches again; a declared
    /// [#singleSubscribeBudget(int)] that connection's replay would exceed makes it batch instead,
    /// with a WARNING saying so. A loss looks the same whatever
    /// caused it, a network reset or a server restart included, which is why it costs one
    /// connection's batching and no more. Within this bound
    /// Agave answers every batch, so none loses the connection by its size.
    ///
    /// @throws IllegalArgumentException if subscribeBatchBytes is negative or above 4,096.
    Builder subscribeBatchBytes(final int subscribeBatchBytes);

    /// The cap set by [#subscribeBatchBytes(int)], in UTF-8 bytes; 0 while batching is off.
    int subscribeBatchBytes();

    /// How many subscribe requests sent one per message the endpoint accepts on one connection
    /// before it closes the connection, when the endpoint is known to count them; 0, the default,
    /// declares no such budget. It matters only with batching on ([#subscribeBatchBytes(int)]):
    /// when a lost connection's batch left wholly unanswered would make the next connection send
    /// its requests one per message, a replay of more registrations than this budget would be cut
    /// off before it completed, so that connection batches instead, with a WARNING naming the
    /// replay size and the budget, and the connection after it decides afresh. While the replay
    /// stays above the budget every connection lost with its batch unanswered batches again, so
    /// declaring a budget gives up the one-connection fallback for such replays: against an
    /// endpoint that resets or closes on arrays without answering, a replay larger than the budget
    /// is lost on every connection, where without a budget every second connection would carry
    /// it one request per message. A replay that fits the budget is sent one per message as it
    /// would be without one. The whole-batch rejection that stops batching for the instance's life
    /// is not affected: a server that cannot read an array leaves single requests as the only
    /// form, budget or not.
    ///
    /// The budget is a statement about the endpoint, not a limit this client enforces: a
    /// registration made on its own still goes out as one plain message and counts against the
    /// endpoint's budget, batching or not, and so does every generic subscription
    /// ([SolanaRpcWebsocket#subscribe]) in a batching replay, since a generic request never shares
    /// a message; and a connection sending one request per message whose later registrations cross
    /// the budget is closed by the endpoint once, after which the connection that replaces it
    /// batches. Measured 2026-11-07 on the public mainnet-beta
    /// websocket: 100 single subscribe messages per connection for its life, however slowly they
    /// are sent, while batch messages were not counted.
    ///
    /// @throws IllegalArgumentException if singleSubscribeBudget is negative.
    Builder singleSubscribeBudget(final int singleSubscribeBudget);

    /// The budget set by [#singleSubscribeBudget(int)]; 0 while none is declared.
    int singleSubscribeBudget();

    /// How long the whole handshake (DNS, TCP, TLS and the HTTP upgrade) may take, in
    /// milliseconds; independent of [#reConnectDelay(long)].
    ///
    /// @throws IllegalArgumentException if connectTimeout is not positive.
    Builder connectTimeout(final long connectTimeout);

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
    Builder keepAliveDelay(final long keepAliveDelay);

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
    Builder subscriptionResendDelay(final long subscriptionResendDelay);

    /// The commitment every subscribe and unsubscribe overload without a commitment uses. Defaults
    /// to [Commitment#FINALIZED]; see [Commitment].
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
