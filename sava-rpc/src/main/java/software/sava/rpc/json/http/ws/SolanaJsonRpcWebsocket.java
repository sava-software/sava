package software.sava.rpc.json.http.ws;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.*;
import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.lang.System.Logger.Level.*;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.joining;
import static software.sava.rpc.json.http.response.AccountInfo.BYTES_IDENTITY;

final class SolanaJsonRpcWebsocket implements WebSocket.Listener, SolanaRpcWebsocket, Runnable {

  private static final System.Logger log = System.getLogger(SolanaJsonRpcWebsocket.class.getName());

  /// Multiplies [Timings#subscriptionResendDelay()] into the unanswered-request deadline of
  /// [#escalateUnanswered(Connection, long)].
  static final int UNANSWERED_ESCALATION_FACTOR = 4;

  /// How long [#close()] waits for the peer's close reply before aborting the socket. JDK
  /// `sendClose` closes only the output; the input, and with it the transport, this listener and
  /// the reassembly buffer, stays retained until the peer answers, which a silent peer never
  /// does. Sized like [SolanaRpcWebsocketBuilder#DEFAULT_CONNECT_TIMEOUT].
  static final long CLOSE_GRACE_MILLIS = 8_000;

  private final URI endpoint;
  private final SolanaAccounts solanaAccounts;
  private final Commitment defaultCommitment;
  private final Timings timings;
  private final int maxMessageLength;
  private final NanoClock clock;
  /// Construction-time origin for [#pacingMillis()]; `nanoTime` is meaningful only as a
  /// difference.
  private final long pacingOrigin;
  private final WebSocket.Builder webSocketBuilder;
  private final ExecutorService executorService;
  private final Consumer<SolanaRpcWebsocket> onOpen;
  private final OnClose onClose;
  private final BiConsumer<SolanaRpcWebsocket, Throwable> onError;
  private final BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError;
  private final BiConsumer<SolanaRpcWebsocket, Throwable> onPingError;
  private final AtomicLong msgId;
  private final Map<String, Map<Commitment, Subscription<AccountInfo<byte[]>>>> accountSubs;
  private final Map<String, Map<Commitment, Subscription<TxLogs>>> txLogSubs;
  private final Map<String, Map<Commitment, Subscription<TxResult>>> signatureSubs;
  private final Map<String, Map<Commitment, Subscription<AccountInfo<byte[]>>>> programSubs;
  /// Explicitly keyed program registrations, a namespace separate from [#programSubs] so a
  /// caller key equal to a program's base58 address does not alias the legacy registration.
  private final Map<String, Map<Commitment, Subscription<AccountInfo<byte[]>>>> keyedProgramSubs;
  private final Set<Consumer<RuntimeException>> exceptionSubs;
  /// Volatile, not atomic, like [#rootSub]: every write holds [#lock], and dispatch reads it on
  /// listener threads without the lock.
  private volatile Subscription<ProcessedSlot> slotSub;
  private volatile Subscription<Long> rootSub;
  private final Map<String, Map<String, Subscription<?>>> genericSubs;

  /// Visible for tests.
  final ReentrantLock lock;
  final Condition newSubscription;
  /// The adopted connection; null before adoption, from [#connect()] until the next adoption,
  /// after retirement, and after [#close()]. Volatile: written under [#lock], read without it at
  /// every callback entry.
  private volatile Connection connection;
  /// When a connection was last attempted or adopted, in [#pacingMillis()] time: the base of the
  /// [Timings#reConnectDelay()] throttle in [#connect()]. Not advanced by traffic, so a
  /// long-lived connection's reconnect is not deferred as if just retried. Plain, not atomic:
  /// every read and write holds [#lock].
  private long lastConnectAttempt;
  private final boolean internalExecutor;
  private final ScheduledExecutorService scheduler;
  /// Runs [#close()]'s abort watchdog after [#CLOSE_GRACE_MILLIS]: on [#scheduler] when one was
  /// injected, else on the JDK's delayed executor.
  private final Executor closeWatchdogExecutor;
  /// The in-flight connection attempt's bridge future. Guarded by [#lock]. While it is
  /// unsettled, [#connect()] returns a copy rather than starting a second handshake: racing
  /// attempts let the older one displace the newer connection, and `WebSocket.Builder` is not
  /// specified safe for concurrent `buildAsync` calls.
  private CompletableFuture<WebSocket> inFlightConnect;
  /// The future `buildAsync` returned for the in-flight attempt, which owns the upgrade and its
  /// socket (unlike the [#inFlightConnect] bridge). Guarded by [#lock]. [#close()] cancels it
  /// too; a socket it yields after the attempt lost authorization is aborted by
  /// [#ownBuild(CompletableFuture, long)].
  private CompletableFuture<WebSocket> inFlightBuild;
  /// Handle of a deferred attempt's wake: a placeholder, replaced by an injected [#scheduler]'s
  /// own handle once `schedule()` returns. Guarded by [#lock]; [#close()] cancels it so a long
  /// deferral does not retain a closed client.
  private Future<?> scheduledConnect;
  /// Guarded by [#lock]. `buildAsync` runs off the lock but the builder is not thread-safe, so
  /// an attempt requested while another `buildAsync` call is running parks its bridge in
  /// [#pendingBuilderStart] and starts when that call returns.
  private boolean builderInUse;
  private CompletableFuture<WebSocket> pendingBuilderStart;
  /// The connection attempt authorized to install its socket. Guarded by [#lock]. Single-flight
  /// is not enough: an attempt's future can settle or be cancelled before its onOpen arrives,
  /// and without the generation that late onOpen would displace a newer connection.
  private long connectGeneration;
  /// Memory for [#newSubscription] signals: set under [#lock] with every signal, consumed by the
  /// check cycle. A Condition forgets a signal sent while the loop is mid-cycle, which would
  /// park it a full check delay, or after [#close()] with a huge delay, forever.
  private boolean checkSignalled;


  SolanaJsonRpcWebsocket(final URI endpoint,
                         final SolanaAccounts solanaAccounts,
                         final Commitment defaultCommitment,
                         final WebSocket.Builder webSocketBuilder,
                         final Timings timings,
                         final int maxMessageLength,
                         final NanoClock clock,
                         final ExecutorService executorService,
                         final ScheduledExecutorService scheduler,
                         final Consumer<SolanaRpcWebsocket> onOpen,
                         final OnClose onClose,
                         final BiConsumer<SolanaRpcWebsocket, Throwable> onError,
                         final BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError,
                         final BiConsumer<SolanaRpcWebsocket, Throwable> onPingError) {
    this.endpoint = endpoint;
    this.solanaAccounts = solanaAccounts;
    this.defaultCommitment = defaultCommitment;
    this.timings = timings;
    this.maxMessageLength = maxMessageLength;
    this.clock = clock;
    this.pacingOrigin = clock.nanoTime();
    this.webSocketBuilder = webSocketBuilder;
    this.onOpen = onOpen;
    this.onClose = onClose;
    this.onError = onError;
    this.onSendTextError = onSendTextError;
    this.onPingError = onPingError;
    this.msgId = new AtomicLong(1);
    // NEVER, not 0: pacing time starts near zero, so a zero stamp would read as "just now"
    // and defer the very first connect by the whole window.
    this.lastConnectAttempt = Subscription.NEVER;
    this.accountSubs = new ConcurrentHashMap<>();
    this.txLogSubs = new ConcurrentHashMap<>();
    this.signatureSubs = new ConcurrentHashMap<>();
    this.programSubs = new ConcurrentHashMap<>();
    this.keyedProgramSubs = new ConcurrentHashMap<>();
    this.genericSubs = new ConcurrentHashMap<>();
    // Copy-on-write: registered from user threads, iterated on listener threads during an
    // error dispatch. The one plain collection here was the one shared across exactly that
    // boundary, so registering a handler could corrupt the set mid-dispatch.
    this.exceptionSubs = new CopyOnWriteArraySet<>();
    this.lock = new ReentrantLock();
    this.newSubscription = lock.newCondition();
    // null: deferred connects use CompletableFuture.delayedExecutor — the shared
    // JDK delayer, no thread of ours. The check-loop executor below cannot host
    // them: its single thread is occupied by the loop for the websocket's
    // lifetime. Injected, deferred connects are scheduled on it instead; the
    // caller owns its lifecycle.
    this.scheduler = scheduler;
    this.closeWatchdogExecutor = scheduler == null
        ? CompletableFuture.delayedExecutor(CLOSE_GRACE_MILLIS, MILLISECONDS)
        : command -> scheduler.schedule(command, CLOSE_GRACE_MILLIS, MILLISECONDS);
    // null: same dedicated executor as always, owned by this websocket and shut
    // down by close(); injected: the caller's to shut down, and close() only asks
    // the check loop to return its thread.
    if (executorService == null) {
      this.executorService = Executors.newFixedThreadPool(1);
      this.internalExecutor = true;
    } else {
      this.executorService = executorService;
      this.internalExecutor = false;
    }
    this.executorService.execute(this);
  }

  @Override
  public URI endpoint() {
    return endpoint;
  }

  @Override
  public SolanaAccounts solanaAccounts() {
    return solanaAccounts;
  }

  @Override
  public Commitment defaultCommitment() {
    return defaultCommitment;
  }

  @Override
  public Timings timings() {
    return timings;
  }

  @Override
  public long lastMessageReceivedTimestamp() {
    // No connection, no evidence: between connect() and adoption, and after close(), there is
    // nothing whose traffic this could honestly describe — a failed reconnect must not report
    // the dead connection's history as if something were carrying it.
    final var conn = this.connection;
    return conn == null ? 0 : conn.lastMessageReceived;
  }

  @Override
  public boolean closed() {
    return this.msgId.get() < 0;
  }

  /// Test seam: registrations still held, summed across every registry.
  int retainedRegistrations() {
    final var conn = this.connection;
    int retained = (conn == null ? 0
        : conn.pendingSubscriptions.size()
        + conn.pendingUnSubscriptions.size()
        + conn.subscriptionsBySubId.size())
        + this.accountSubs.size()
        + this.txLogSubs.size()
        + this.signatureSubs.size()
        + this.programSubs.size()
        + this.keyedProgramSubs.size()
        + this.genericSubs.size();
    if (this.slotSub != null) {
      ++retained;
    }
    if (this.rootSub != null) {
      ++retained;
    }
    return retained;
  }

  /// Test seam: exception subscribers still held. They have no public removal, so [#close()] is
  /// their only release and must win against a racing registration.
  int retainedExceptionSubscribers() {
    return this.exceptionSubs.size();
  }

  /// Test seam: cancellation tombstones held by the current connection.
  int retainedCancellationTombstones() {
    final var conn = this.connection;
    return conn == null ? 0 : conn.cancelledRequests.size();
  }

  /// Test seam: attempt ordinals plus recorded kills held by the current connection. Entries
  /// must die with their registrations and adjudications, not only with the connection.
  int retainedOrdinalEntries() {
    final var conn = this.connection;
    return conn == null ? 0 : conn.attemptSeqs.size() + conn.killedSubIds.size();
  }

  /// Test seam: whether the executor is shut down. [#close()] shuts down only an executor this
  /// instance created, never an injected one.
  boolean executorServiceShutdown() {
    return this.executorService.isShutdown();
  }

  /// The notification's own subscription id, member order free.
  private static BigInteger readSubscriptionId(final JsonIterator ji) {
    ji.skipUntil("subscription");
    return ji.readBigInteger();
  }

  /// Whether `subId` is stale for the singleton `sub`, meaning auto-unsubscribe it: retired on
  /// this connection ([Connection#retiredSubIds]), or not the singleton's confirmed id (after
  /// unsubscribe/resubscribe the predecessor's id still names the old server subscription).
  /// Returns false for an unconfirmed singleton's non-retired id; callers must drop those frames
  /// rather than deliver them, since an early frame from a reordering peer deserves neither.
  private boolean staleSingletonId(final Connection conn, final Subscription<?> sub, final BigInteger subId) {
    if (conn.retiredSubIds.contains(subId)) {
      return true;
    }
    final var confirmed = sub.subId();
    return confirmed != null && !confirmed.equals(subId);
  }

  /// Positions the cursor at the top-level `params` member, even when it precedes `method`: the
  /// method scan has already consumed everything before `method`, so a forward-only scan would
  /// miss it and silently drop the notification.
  private static void skipToParams(final JsonIterator ji, final int offset) {
    if (ji.skipUntil("params") == null) {
      ji.reset(offset).skipUntil("params");
    }
  }

  /// One connection's socket, parse state, and every registry that dies with it.
  ///
  /// This is the concurrency model. The JDK serializes listener callbacks per socket, not per
  /// listener, so callbacks from two socket generations can overlap. A callback resolves its
  /// Connection once, by socket identity, and so reaches only its own state: a stale callback
  /// mutates a dead connection nothing reads. Only the durable registries (the channel maps of
  /// what the caller wants subscribed) stay on the instance, and commits into them re-check
  /// `conn == this.connection` under the lock.
  ///
  /// Field idioms: lock-guarded plain fields where every mutator locks ([#outboundTail],
  /// [#inFlightSends], [#cancelledRequests]), volatile for stamps this connection's listener
  /// thread writes unlocked, atomics where completion threads stamp or CAS off-lock, concurrent
  /// maps where dispatch reads race locked mutation, and skip-lists where sorted iteration is
  /// the wire order.
  private static final class Connection {

    final WebSocket socket;

    // reassembly and parse state; only this connection's listener thread touches it
    char[] buffer = new char[4_096];
    int offset;
    final JsonIterator ji = JsonIterator.parse(new byte[0]);

    // requests and registrations that die with this connection. pendingUnSubscriptions holds
    // queued cancellations (method + earliest re-send time); the frame is minted at send time
    // so its request id can be registered for acknowledgement correlation. inFlightSends maps
    // msgId -> pacing time the send was queued, restarted at transmission, which is what the
    // unanswered-request deadline measures.
    final Map<Long, Subscription<?>> pendingSubscriptions = new ConcurrentSkipListMap<>();
    final Map<BigInteger, QueuedUnsub> pendingUnSubscriptions = new ConcurrentSkipListMap<>();
    final Map<BigInteger, Subscription<?>> subscriptionsBySubId = new ConcurrentSkipListMap<>();
    final Map<Long, Long> inFlightSends = new HashMap<>();
    final Map<Long, CancelledRequest> cancelledRequests = new HashMap<>();
    /// Next wire ordinal, assigned under the lock at chain admission, which is transmission
    /// order since the chain is FIFO. Request ids cannot serve: a retry reuses its id at a new
    /// wire position.
    long nextWireSeq;
    /// msgId to the wire ordinal of its latest transmission attempt, which every same-id
    /// adjudication compares. Kept while a transmitted attempt can still grant or its grant stays
    /// mapped; removed when an attempt concludes ungranted, and a retry installs a fresh ordinal.
    final Map<Long, Long> attemptSeqs = new HashMap<>();
    final Map<Long, UnsubRequest> pendingUnsubAcks = new HashMap<>();
    /// id to the wire ordinal of a cancellation the server acknowledged true while requests
    /// transmitted ahead of it were still unanswered. A later grant of the id below that ordinal
    /// arrives already dead and is replayed (every coalesced grant below it, not only the
    /// first); one at or above it is fresh and removes the entry. Lock-guarded; a sweep drops
    /// entries once nothing pending could still resolve to them.
    final Map<BigInteger, Long> killedSubIds = new HashMap<>();
    /// Gate allowing one wire cancellation per subscription id at a time, so repeated
    /// unknown-id notifications cannot drive a frame and acknowledgement entry each. Accepted
    /// risk: DISTINCT unknown ids still cost one of each per id, bounded only in time by the
    /// unanswered-request deadline, which a maximal resend delay disables.
    final Set<BigInteger> inFlightUnsubs = ConcurrentHashMap.newKeySet();
    /// Ids whose late notifications are dropped, each released with the cancellation, kill, or
    /// rejection that implied its retirement. Accepted residue: a replayed casualty cancelled
    /// before its re-send leaves its predecessor's id here until reconnect, one id per
    /// occurrence; retention, not correlation corruption.
    final Set<BigInteger> retiredSubIds = ConcurrentHashMap.newKeySet();
    /// Leaves ACTIVE at most once, and ESCALATED is terminal, so maintenance claims at most one
    /// escalation per connection.
    /// Volatile: set under the instance lock, but read by send and ping completion threads
    /// deciding whether a failure is teardown noise.
    volatile ConnectionLifecycle lifecycle = ConnectionLifecycle.ACTIVE;

    // The outbound chain and pacing clocks are connection-scoped, so a displaced connection's
    // late completions mutate only their own dead state.
    CompletableFuture<WebSocket> outboundTail = CompletableFuture.completedFuture(null);
    /// The one Ping whose send or answer is outstanding. Atomic because peer contact and send
    /// completion arrive off the lifecycle lock while maintenance decides under it whether a
    /// deadline won.
    final AtomicReference<PingProbe> pingProbe = new AtomicReference<>();
    /// A failed Ping's cause, published under the lifecycle lock with the `PING_FAILED`
    /// transition; callbacks receive it only after the lock is released.
    volatile Throwable pingFailure;
    final AtomicLong lastOutboundFrame = new AtomicLong(0);
    volatile long lastPeerContact;
    volatile long lastMessageReceived;

    Connection(final WebSocket socket) {
      this.socket = socket;
    }
  }

  private enum ConnectionLifecycle {
    ACTIVE,
    PING_FAILED,
    ESCALATED
  }

  /// An outstanding Ping with two deadlines: its send future must settle, then peer contact must
  /// follow, since JDK completion means only that the frame was sent. [#answered] keeps contact
  /// that races send completion; the send keeps its own deadline.
  private static final class PingProbe {

    private static final long PENDING = Subscription.NEVER;
    private static final long TIMED_OUT = Subscription.NEVER + 1;

    final long admittedAt;
    final AtomicLong sentAt = new AtomicLong(PENDING);
    volatile boolean answered;

    private PingProbe(final long admittedAt) {
      this.admittedAt = admittedAt;
    }
  }

  /// Opaque token for an overdue Ping between observation and claim. Visible for tests.
  static final class PingDeadlineTransition {

    private final Connection conn;
    private final PingProbe probe;
    private final boolean sendPending;
    private final long startedAt;
    private final long now;

    private PingDeadlineTransition(final Connection conn,
                                   final PingProbe probe,
                                   final boolean sendPending,
                                   final long startedAt,
                                   final long now) {
      this.conn = conn;
      this.probe = probe;
      this.sendPending = sendPending;
      this.startedAt = startedAt;
      this.now = now;
    }
  }

  /// An un-subscription on the wire, awaiting its boolean acknowledgement. `wireSeq` is its
  /// transmission ordinal: on a `true` acknowledgement it decides whether a same-id grant was
  /// cancelled by this request (grant attempt below it) or postdates it. Neither response
  /// arrival order (JSON-RPC leaves it free) nor request ids (stable across retries) can.
  /// `fingerprint` is the cancelled request's, compared against a live same-id owner when this
  /// cancellation is transiently rejected; null for an id this client never owned.
  private record UnsubRequest(BigInteger subId, String unSubscribeMethod, long wireSeq, String fingerprint) {
  }

  /// Tombstone of a request cancelled before its confirmation. Its fingerprint (the request
  /// after its id) decides the late grant: landing on a live owner is benign coalescing only if
  /// the two requests are equivalent, and connection-fatal otherwise.
  private record CancelledRequest(String unSubscribeMethod, String fingerprint) {
  }

  /// A cancellation awaiting the flush. `notBefore` is the earliest pacing time to send it: zero
  /// for a first send or a failed send, one resend window after a server-condition rejection so
  /// a refusing peer is not retried at wire speed. `fingerprint` is the cancelled request's, or
  /// null when unknown.
  private record QueuedUnsub(String unSubscribeMethod, long notBefore, String fingerprint) {
  }

  private record PendingBuildStart(long generation, CompletableFuture<WebSocket> connected) {
  }

  /// Resolves the connection a callback belongs to — null when its socket is not the current
  /// one, which is the entire late-callback defense: no Connection, no state to corrupt.
  private Connection connectionFor(final WebSocket webSocket) {
    final var conn = this.connection;
    return conn != null && conn.socket == webSocket ? conn : null;
  }

  /// Retires the current connection if `webSocket` is its socket, before any lifecycle policy
  /// runs: clears it and its in-flight attempt, advances the generation, aborts the socket, and
  /// returns it; null when `webSocket` is not current. The wrapper and durable registries stay
  /// live so a handler may [#connect()] again, and callbacks the abort causes are ignored.
  private Connection retireConnection(final WebSocket webSocket) {
    final Connection retired;
    final CompletableFuture<WebSocket> inFlight;
    final CompletableFuture<WebSocket> build;
    lock.lock();
    try {
      retired = connectionFor(webSocket);
      if (retired == null) {
        return null;
      }
      this.connection = null;
      // onOpen is allowed to arrive before buildAsync's future settles. That future still
      // carries this attempt's listener and remains the single-flight authority, so merely
      // detaching the adopted socket would make a reconnect from the lifecycle handler join
      // the dead attempt forever. Retire the whole generation before policy: its late result
      // is stale, the next connect owns a fresh listener, and no pending task retains this
      // wrapper after the transport it was building has died.
      inFlight = this.inFlightConnect;
      build = this.inFlightBuild;
      this.inFlightConnect = null;
      this.inFlightBuild = null;
      this.pendingBuilderStart = null;
      // A Connection can exist only after any deferred task has already run. Clearing releases
      // its completed token; close() remains the path which cancels a genuinely pending task.
      this.scheduledConnect = null;
      // Move monotonically forward and leave a generation gap before policy can re-enter
      // connect(), which increments once more. A backward two-step move can collide with a
      // listener two attempts old after that reconnect, re-authorizing a transport which was
      // already superseded.
      this.connectGeneration += 2;
    } finally {
      lock.unlock();
    }
    // Use the claimed Connection as the authority from here on. Besides documenting that the
    // input identity was resolved under the lock, this makes a corrupted/stale claim fail
    // immediately instead of partially retiring the real successor and hanging its waiters.
    retired.socket.abort();
    // Release builder ownership before invoking user policy. Cancelling the builder future
    // may settle the bridge and run caller completion actions synchronously, hence all local
    // state above is committed first and none of these operations runs under the lock.
    if (build != null) {
      build.cancel(true);
    }
    if (inFlight != null) {
      inFlight.cancel(true);
    }
    return retired;
  }

  /// Monotonic milliseconds for every pacing decision (reconnect throttle, resend and unanswered
  /// deadlines, ping and keep-alive), so a wall-clock step cannot disable them; only
  /// [#lastMessageReceivedTimestamp()], epoch millis by contract, uses the wall clock. At least
  /// 1, so fields initialized to 0 or [Subscription#NEVER] read as before this instance existed.
  private long pacingMillis() {
    return ((clock.nanoTime() - pacingOrigin) / 1_000_000L) + 1L;
  }

  /// Starts attempt `connected` if it is still [#inFlightConnect], else fails it. While another
  /// `buildAsync` call is running the attempt parks in [#pendingBuilderStart]; otherwise it
  /// stamps [#lastConnectAttempt] and builds off the lifecycle lock, since a builder may deliver
  /// listener callbacks synchronously and user handlers must not inherit the hold. A parked
  /// successor is started when the build call returns.
  ///
  /// @throws NullPointerException if `connected` is null
  private void startBuild(final long generation,
                          final CompletableFuture<WebSocket> connected) {
    // Every real attempt owns a bridge. Fail before acquiring builder ownership if an internal
    // handoff ever violates that invariant; entering the try/finally with a null bridge would
    // let its release edge manufacture the same invalid successor indefinitely.
    Objects.requireNonNull(connected, "websocket build bridge");
    final boolean rejected;
    lock.lock();
    try {
      // The bridge is the attempt's exact ownership token. Every transition which closes or
      // advances the generation clears/replaces it under this lock, so separate closed and
      // generation predicates merely restate the same state transition in weaker forms.
      rejected = this.inFlightConnect != connected;
      if (!rejected) {
        // A scheduled task which reached this point owns its wake. Clear only after the attempt
        // identity has been validated, so a stale task cannot erase a successor's handle.
        this.scheduledConnect = null;
        if (builderInUse) {
          pendingBuilderStart = connected;
          return;
        }
        builderInUse = true;
        this.lastConnectAttempt = pacingMillis();
      }
    } finally {
      lock.unlock();
    }
    if (rejected) {
      connected.completeExceptionally(
          new IllegalStateException("websocket closed or reconnected before its build started"));
      return;
    }

    try {
      buildReservedAttempt(generation, connected);
    } finally {
      final var pending = releaseBuilderAndTakePending();
      if (pending != null) {
        startBuild(pending.generation(), pending.connected());
      }
    }
  }

  /// Calls `buildAsync` under the builder reservation and settles `connected` from it. If the
  /// attempt lost authorization meanwhile (close or a newer attempt), the build is cancelled,
  /// any socket it already produced is aborted, and `connected` fails. Separate so
  /// [#startBuild(long, CompletableFuture)] has a single release point.
  private void buildReservedAttempt(final long generation,
                                    final CompletableFuture<WebSocket> connected) {
    final CompletableFuture<WebSocket> built;
    try {
      built = Objects.requireNonNull(this.webSocketBuilder.buildAsync(
              this.endpoint, new AttemptListener(generation)
          ), "websocket builder returned null"
      );
    } catch (final RuntimeException ex) {
      connected.completeExceptionally(ex);
      return;
    }
    final var ownedBuild = ownBuild(built, generation);
    final boolean installed;
    lock.lock();
    try {
      installed = this.inFlightConnect == connected;
      if (installed) {
        this.inFlightBuild = ownedBuild;
      }
    } finally {
      lock.unlock();
    }
    if (!installed) {
      ownedBuild.cancel(true);
      // A successful future may have completed before the install decision. Cancellation
      // cannot reclaim that socket, and ownBuild's first callback ran while the generation was
      // still current, so re-check ownership here and abort the now-ownerless result.
      // Establish terminality first: after isDone() is true the outcome cannot change, so the
      // following exceptional-state check and getNow are one stable observation. Without that
      // guard, an uncancellable future completing exceptionally between those two calls made
      // getNow throw out of connect() instead of letting the already-retired attempt settle.
      if (ownedBuild.isDone() && !ownedBuild.isCompletedExceptionally()) {
        final var unadopted = ownedBuild.getNow(null);
        // Losing installation proves this generation or its bridge is no longer authorized.
        // Its listener therefore cannot adopt this result as current: a late onOpen is fenced by
        // the same generation. There is no same-socket current arm to preserve here.
        if (unadopted != null) {
          unadopted.abort();
        }
      }
      connected.completeExceptionally(
          new IllegalStateException("websocket closed or reconnected while its build started"));
      return;
    }
    ownedBuild.whenComplete((webSocket, ex) -> {
      if (ex == null) {
        connected.complete(webSocket);
      } else {
        connected.completeExceptionally(ex);
      }
    });
  }

  /// Releases the builder reservation and takes the parked successor attempt, if any, tagged
  /// with the current generation; the caller starts it off-lock.
  private PendingBuildStart releaseBuilderAndTakePending() {
    lock.lock();
    try {
      builderInUse = false;
      final var successor = pendingBuilderStart;
      pendingBuilderStart = null;
      return successor == null ? null : new PendingBuildStart(this.connectGeneration, successor);
    } finally {
      lock.unlock();
    }
  }

  /// Schedules the deferred [#startBuild(long, CompletableFuture)] outside the lifecycle lock,
  /// on [#scheduler] or else the JDK delayed executor. The `placeholder` already in
  /// [#scheduledConnect] lets [#close()] cancel the wake while an injected scheduler is still
  /// inside `schedule()`; a handle returned to an attempt that is no longer current is
  /// cancelled. A task that started before `schedule()` returned has consumed the placeholder,
  /// and its handle is left alone: cancelling it would interrupt a valid build.
  private void scheduleBuild(final long delay,
                             final long generation,
                             final CompletableFuture<WebSocket> connected,
                             final CompletableFuture<Void> placeholder) {
    final Runnable build = () -> startBuild(generation, connected);
    if (scheduler == null) {
      placeholder.thenRun(build);
      try {
        CompletableFuture.delayedExecutor(delay, MILLISECONDS)
            .execute(() -> placeholder.complete(null));
      } catch (final RuntimeException ex) {
        placeholder.completeExceptionally(ex);
        connected.completeExceptionally(ex);
      }
      return;
    }

    final Future<?> scheduled;
    try {
      scheduled = Objects.requireNonNull(
          this.scheduler.schedule(build, delay, MILLISECONDS),
          "websocket scheduler returned null"
      );
    } catch (final RuntimeException ex) {
      lock.lock();
      // The only operation which can displace this placeholder before schedule() returns is
      // terminal close; clearing null twice is harmless, and no successor can be admitted on a
      // closed wrapper. Keeping an identity arm here adds no ownership distinction.
      this.scheduledConnect = null;
      lock.unlock();
      placeholder.completeExceptionally(ex);
      connected.completeExceptionally(ex);
      return;
    }
    final boolean attemptCurrent;
    lock.lock();
    try {
      attemptCurrent = this.inFlightConnect == connected;
      if (attemptCurrent && this.scheduledConnect == placeholder) {
        this.scheduledConnect = scheduled;
      }
    } finally {
      lock.unlock();
    }
    if (!attemptCurrent) {
      scheduled.cancel(true);
    }
  }

  /// Returns `built` with a hook that aborts its socket if the attempt is by then closed or
  /// superseded, rather than leave it waiting on an onOpen that may never come; otherwise
  /// adoption owns the socket.
  private CompletableFuture<WebSocket> ownBuild(final CompletableFuture<WebSocket> built, final long generation) {
    built.whenComplete((webSocket, _) -> {
      if (webSocket != null) {
        final boolean stale;
        lock.lock();
        try {
          stale = closed() || generation != this.connectGeneration;
        } finally {
          lock.unlock();
        }
        if (stale) {
          webSocket.abort();
        }
      }
    });
    return built;
  }

  /// Routes one attempt's callbacks, carrying the generation that authorizes adoption. Every
  /// callback other than onOpen routes by socket identity and needs no token.
  private final class AttemptListener implements WebSocket.Listener {

    private final long generation;

    private AttemptListener(final long generation) {
      this.generation = generation;
    }

    @Override
    public void onOpen(final WebSocket webSocket) {
      SolanaJsonRpcWebsocket.this.adopt(webSocket, generation);
    }

    @Override
    public CompletionStage<?> onText(final WebSocket webSocket, final CharSequence message, final boolean last) {
      // The engine consumes callbacks synchronously and deliberately returns null (the JDK's
      // already-complete signal). Spell that contract here instead of forwarding an
      // always-null operand: the observable obligation is the delegation itself.
      SolanaJsonRpcWebsocket.this.onText(webSocket, message, last);
      return null;
    }

    @Override
    public CompletionStage<?> onBinary(final WebSocket webSocket, final ByteBuffer data, final boolean last) {
      // Delegated so production reaches the engine's rejection rather than the JDK default,
      // which silently discards the frame and requests another — a protocol violation
      // disappearing without trace.
      SolanaJsonRpcWebsocket.this.onBinary(webSocket, data, last);
      return null;
    }

    @Override
    public CompletionStage<?> onPing(final WebSocket webSocket, final ByteBuffer message) {
      SolanaJsonRpcWebsocket.this.onPing(webSocket, message);
      return null;
    }

    @Override
    public CompletionStage<?> onPong(final WebSocket webSocket, final ByteBuffer message) {
      SolanaJsonRpcWebsocket.this.onPong(webSocket, message);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(final WebSocket webSocket, final int statusCode, final String reason) {
      SolanaJsonRpcWebsocket.this.onClose(webSocket, statusCode, reason);
      return null;
    }

    @Override
    public void onError(final WebSocket webSocket, final Throwable error) {
      SolanaJsonRpcWebsocket.this.onError(webSocket, error);
    }
  }

  @Override
  public CompletableFuture<?> connect() {
    final CompletableFuture<WebSocket> connected;
    final CompletableFuture<Void> scheduledPlaceholder;
    final long generation;
    final long delay;
    lock.lock();
    try {
      if (closed()) {
        return null;
      }
      final var inFlight = this.inFlightConnect;
      if (inFlight != null && !inFlight.isDone()) {
        // A defensive copy per caller: the internal future is the single-flight authority, and
        // handing it out let a caller's cancel() satisfy isDone() and admit a second handshake
        // against the JDK's not-thread-safe builder while the first still ran. A copy settles
        // with the attempt; cancelling it abandons only the caller's view.
        return inFlight.copy();
      }
      // Dropping the reference is not enough: `this` stays the JDK listener of the socket being
      // replaced, and its demand outlives the field, so an un-aborted socket keeps delivering
      // into state that now describes a different connection. abort() rather than sendClose():
      // sendClose() closes only the output and leaves the listener registered until the peer
      // replies, which is the window this is closing — and a peer that stopped answering never
      // ends it.
      final var replaced = this.connection;
      this.connection = null;
      if (replaced != null) {
        replaced.socket.abort();
      }
      // The predecessor ATTEMPT is released with the connection it never became: a handshake
      // still pending under the old generation is cancelled rather than left retaining its
      // listener, and a socket it built that nothing ever adopted is aborted rather than left
      // waiting on an onOpen that may never arrive.
      final var priorBuild = this.inFlightBuild;
      if (priorBuild != null) {
        this.inFlightBuild = null;
        if (!priorBuild.isDone()) {
          priorBuild.cancel(true);
        } else if (!priorBuild.isCompletedExceptionally()) {
          final var unadopted = priorBuild.join();
          if (replaced == null || replaced.socket != unadopted) {
            unadopted.abort();
          }
        }
      }
      // Decide-and-stamp atomically: read-compute-set let two callers inside the same window
      // both see a stale stamp, both pass, and both launch handshakes.
      final long now = pacingMillis();
      final long lastAttempt = this.lastConnectAttempt;
      // NEVER branches explicitly: the sentinel is 2^40 ms behind the origin, which is "before
      // every sane window" but not "before Long.MAX_VALUE" — without the branch, a maximal
      // reConnectDelay deferred the FIRST attempt, though no previous attempt exists.
      final long millisSinceLastAttempt = lastAttempt == Subscription.NEVER
          ? Long.MAX_VALUE
          : now - lastAttempt;
      if (millisSinceLastAttempt < timings.reConnectDelay()) {
        delay = this.timings.reConnectDelay() - millisSinceLastAttempt;
      } else {
        delay = 0;
      }
      generation = ++this.connectGeneration;
      connected = new CompletableFuture<>();
      this.inFlightConnect = connected;
      // Each branch installs its own attempt as the single-flight authority BEFORE any code
      // that could re-enter connect() — the builder itself, or completion actions a joined
      // caller attached to an earlier copy. A trailing assignment here used to overwrite the
      // NEWER authority such a re-entry installed, after which a third caller stacked another
      // handshake.
      if (delay > 0) {
        // The placeholder is the lock-owned handle before any scheduler code runs. It lets
        // close() release this wrapper while an injected scheduler is still returning its real
        // Future, and gives that late handle an identity to lose against.
        scheduledPlaceholder = new CompletableFuture<>();
        this.scheduledConnect = scheduledPlaceholder;
      } else {
        scheduledPlaceholder = null;
      }
    } finally {
      lock.unlock();
    }
    if (scheduledPlaceholder == null) {
      startBuild(generation, connected);
    } else {
      scheduleBuild(delay, generation, connected, scheduledPlaceholder);
    }
    return connected.copy();
  }


  @Override
  public void run() {
    try {
      final long sleepNanos = MILLISECONDS.toNanos(timings.subscriptionAndPingCheckDelay());
      runLoop(sleepNanos);
    } catch (final InterruptedException e) {
      // exit
    } catch (final RuntimeException ex) {
      log.log(ERROR, "Unhandled Solana Websocket exception.", ex);
      // The loop dying is the one terminal transition a supervised instance makes on its own,
      // and it must reach the consumer's error seam: their reconnect policy lives there, and a
      // close() with no notification bypasses it silently.
      if (this.onError != null) {
        try {
          this.onError.accept(this, ex);
        } catch (final RuntimeException handlerEx) {
          log.log(ERROR, "onError handler threw while handling check loop failure.", handlerEx);
        }
      }
    } finally {
      close();
    }
  }

  /// The check loop, until closed or interrupted: the only unbounded control flow, kept apart
  /// from [#run()]'s finite error reporting and teardown.
  private void runLoop(final long sleepNanos) throws InterruptedException {
    while (true) {
      if (closed()) {
        return;
      }
      // Observe interruption at the loop boundary as well as in Condition.awaitNanos(). A
      // remembered signal deliberately skips that await; without this guard, a continuously
      // signalled loop could ignore an interrupt indefinitely. Keep the two stop conditions
      // sequential: forcing either branch in one direction then has the same finite/liveness
      // character as its sibling instead of mixing causes under one mutation key.
      if (Thread.interrupted()) {
        return;
      }
      checkCycle(sleepNanos);
    }
  }

  /// One wait-and-check cycle of [#runLoop(long)]. Visible for tests; `awaitNanos <= 0` never
  /// parks.
  void checkCycle(final long awaitNanos) throws InterruptedException {
    final var delivery = prepareCheckCycleDelivery(awaitNanos);
    if (delivery != null) {
      delivery.run();
    }
  }

  /// Waits, then runs one maintenance pass under [#lock], returning the escalation delivery to
  /// run after unlocking, or null when there is none. Visible for tests, which interleave a
  /// takeover or terminal callback before the delivery.
  Runnable prepareCheckCycleDelivery(final long awaitNanos) throws InterruptedException {
    final Connection conn;
    final Throwable escalation;
    lock.lock();
    try {
      // Wake on a new subscription, on close(), or every check delay. The signalled flag is the
      // condition's memory: a signal landing while this loop was mid-cycle used to be lost, and
      // the next await parked the full delay — after close(), with a large check delay, forever.
      // closed() is re-checked under the lock for the same reason: close() signals under it, so
      // the check-then-park race is closed rather than narrowed. The await's return value —
      // remaining time, which distinguishes timeout from signal and re-arms a partial wait —
      // is deliberately ignored: every wake cause, spurious included, converges on the same
      // pass below, whose delays are enforced by clock comparisons rather than by how long
      // this await slept, and the signal/timeout split lives in the flag, not the return.
      if (!checkSignalled && !closed()) {
        //noinspection ResultOfMethodCallIgnored
        newSubscription.awaitNanos(awaitNanos);
      }
      checkSignalled = false;
      conn = this.connection;
      if (conn != null) {
        escalation = handlePendingSubscriptions(conn);
      } else {
        escalation = null;
      }
    } finally {
      lock.unlock();
    }
    return escalation == null ? null : () -> deliverEscalation(conn, escalation);
  }

  @Override
  public void exceptionSubscribe(final Consumer<RuntimeException> consumer) {
    // Locked and closed-guarded like every registration: close() clears this set to release
    // consumer references, and an unguarded add racing that clear pinned the caller's object
    // graph to a dead instance forever.
    lock.lock();
    try {
      if (!closed()) {
        this.exceptionSubs.add(consumer);
      }
    } finally {
      lock.unlock();
    }
  }

  private void queuePendingSubsOnOpen(final Connection conn,
                                      final Map<String, ? extends Map<Commitment, ? extends Subscription<?>>> subs) {
    for (final var subscriptions : subs.values()) {
      for (final var sub : subscriptions.values()) {
        // The previous connection's subId is dead with the connection. Left in place, an
        // unsubscribe issued before this connection confirms would carry it onto the new
        // connection — a frame the server reads as cancelling somebody else's subscription.
        sub.setSubId(null);
        conn.pendingSubscriptions.put(sub.msgId(), sub);
      }
    }
  }

  @Override
  public void onOpen(final WebSocket webSocket) {
    lock.lock();
    final long generation;
    try {
      generation = this.connectGeneration;
    } finally {
      lock.unlock();
    }
    adopt(webSocket, generation);
  }

  /// Installs a connection for `webSocket` if `generation` is still authorized; otherwise aborts
  /// the socket.
  private void adopt(final WebSocket webSocket, final long generation) {
    if (closed()) {
      // close() landed between connect() and this handshake completing. Nothing may be rebuilt
      // on a closed instance, and the socket that just opened is nobody's: kill it rather than
      // leak a connection whose listener will ignore it.
      webSocket.abort();
      return;
    }
    final Connection conn;
    final Throwable escalation;
    lock.lock();
    try {
      if (closed() || generation != this.connectGeneration) {
        // Re-checked under the lock: close() tears down under it, and a newer connect() bumps
        // the generation under it — a stale attempt's late socket must not displace the connection
        // that outraced it, and a closed instance must not be rebuilt.
        webSocket.abort();
        return;
      }
      // Displace under the lock, then build the successor whole. A fresh Connection IS the old
      // clears: chains, gates, tombstones, retired ids, parse state and liveness stamps all
      // begin empty by construction, and the displaced connection keeps its own — its late
      // completions and callbacks mutate state nothing reads.
      final var displaced = this.connection;
      if (displaced != null && displaced.socket != webSocket) {
        displaced.socket.abort();
      }
      conn = new Connection(webSocket);
      queuePendingSubsOnOpen(conn, this.accountSubs);
      queuePendingSubsOnOpen(conn, this.txLogSubs);
      queuePendingSubsOnOpen(conn, this.signatureSubs);
      queuePendingSubsOnOpen(conn, this.programSubs);
      queuePendingSubsOnOpen(conn, this.keyedProgramSubs);
      final var slotSub = this.slotSub;
      if (slotSub != null) {
        slotSub.setSubId(null);
        conn.pendingSubscriptions.put(slotSub.msgId(), slotSub);
      }
      final var rootSub = this.rootSub;
      if (rootSub != null) {
        rootSub.setSubId(null);
        conn.pendingSubscriptions.put(rootSub.msgId(), rootSub);
      }
      for (final var subscriptions : this.genericSubs.values()) {
        for (final var sub : subscriptions.values()) {
          sub.setSubId(null);
          conn.pendingSubscriptions.put(sub.msgId(), sub);
        }
      }
      final long opened = pacingMillis();
      // The upgrade is this connection's first outbound frame and its first evidence the peer
      // is there; the stamps are this connection's pacing epoch. lastMessageReceived needs no
      // reset — a new Connection has never received anything, by construction. The successful
      // attempt also re-arms the reconnect throttle.
      conn.lastOutboundFrame.set(opened);
      conn.lastPeerContact = opened;
      this.lastConnectAttempt = opened;
      this.connection = conn;
      // Flush the durable registrations through the same maintenance path as every later pass.
      // Legacy Timings constructors deliberately retain arbitrary long values, so this first
      // pass can already find a due Ping or unanswered send; its terminal result must survive
      // adoption rather than leave an ESCALATED connection installed with no delivery owner.
      escalation = handlePendingSubscriptions(conn);
    } finally {
      lock.unlock();
    }
    // Terminal delivery is user/collaborator work and stays off the lifecycle lock. A successful
    // claim retires this connection; a terminal callback or takeover which got there first makes
    // the identity check below suppress demand and the open notice just the same.
    if (escalation != null) {
      deliverEscalation(conn, escalation);
    }
    // Demand is collaborator code too: a wrapping socket may synchronously deliver a terminal
    // callback or even adopt a successor from request(). Invoking it under this re-entrant lock
    // lent the predecessor's outer hold to that successor's otherwise off-lock onOpen handler.
    // Check identity on both sides: a connection retracted before demand gets no request, and
    // one retracted by demand gets no open callback. Demand still precedes every reported open.
    if (this.connection != conn) {
      return;
    }
    webSocket.request(Long.MAX_VALUE);
    if (this.connection != conn) {
      return;
    }
    if (this.onOpen != null) {
      this.onOpen.accept(this);
    } else {
      log.log(INFO, "WebSocket connected to {0}.", endpoint.getHost());
    }
  }

  private static String createSubscriptionMsg(final long msgId,
                                              final Channel channel,
                                              final String params) {
    return String.format("""
        {"jsonrpc":"2.0","id":%d,"method":"%s","params":[%s]}""", msgId, channel.subscribe(), params
    );
  }

  private <T> boolean queueSubscription(final Commitment commitment,
                                        final Channel channel,
                                        final String key,
                                        final String params,
                                        final Map<String, Map<Commitment, Subscription<T>>> subs,
                                        final Consumer<Subscription<T>> onSub,
                                        final Consumer<T> consumer) {
    // Locked end to end: registration must be atomic against the listener's confirmation and
    // close()'s teardown. Unlocked, an insert could land after teardown and return true, and
    // the registry state could interleave with a confirmation mid-flight.
    lock.lock();
    try {
      if (closed()) {
        // "Once closed, this WebSocket is no longer usable": returning true here would be an
        // affirmative lie — the maps would fill, but the check loop has exited and nothing sends.
        return false;
      }
      final var byCommitment = subs.get(key);
      if (byCommitment != null && byCommitment.containsKey(commitment)) {
        // The lifecycle lock makes this the one duplicate decision; no contender may mint an
        // id until ownership is decided here.
        return false;
      }
      final long msgId = this.msgId.incrementAndGet();
      final var msg = createSubscriptionMsg(msgId, channel, params);
      final var sub = Subscription.createSubscription(commitment, channel, key, msgId, msg, onSub, consumer);
      final var registrations = byCommitment == null ? new ConcurrentHashMap<Commitment, Subscription<T>>(4) : byCommitment;
      if (byCommitment == null) {
        subs.put(key, registrations);
      }
      registrations.put(commitment, sub);
      // Registered durably above; queued for send only if a connection exists — during the
      // gap, adoption re-derives the pending set from the registries, so intent is never lost.
      final var conn = this.connection;
      if (conn != null) {
        conn.pendingSubscriptions.put(msgId, sub);
      }
      checkSignalled = true;
      newSubscription.signal();
      return true;
    } finally {
      lock.unlock();
    }
  }

  private <T> boolean queueSubscription(final Commitment commitment,
                                        final Channel channel,
                                        final PublicKey publicKey,
                                        final String params,
                                        final Map<String, Map<Commitment, Subscription<T>>> subs,
                                        final Consumer<Subscription<T>> onSub,
                                        final Consumer<T> consumer) {
    return queueSubscription(
        commitment,
        channel,
        publicKey.toBase58(),
        publicKey,
        params,
        subs,
        onSub,
        consumer
    );
  }

  private <T> boolean queueSubscription(final Commitment commitment,
                                        final Channel channel,
                                        final String key,
                                        final PublicKey publicKey,
                                        final String params,
                                        final Map<String, Map<Commitment, Subscription<T>>> subs,
                                        final Consumer<Subscription<T>> onSub,
                                        final Consumer<T> consumer) {
    // Locked end to end: registration must be atomic against the listener's confirmation and
    // close()'s teardown. Unlocked, an insert could land after teardown and return true, and
    // the registry state could interleave with a confirmation mid-flight.
    lock.lock();
    try {
      if (closed()) {
        // "Once closed, this WebSocket is no longer usable": returning true here would be an
        // affirmative lie — the maps would fill, but the check loop has exited and nothing sends.
        return false;
      }
      final var byCommitment = subs.get(key);
      if (byCommitment != null && byCommitment.containsKey(commitment)) {
        // See the String-keyed overload: this decision is inside the lifecycle lock so two
        // callers cannot both consume ids.
        return false;
      }
      final long msgId = this.msgId.incrementAndGet();
      final var msg = createSubscriptionMsg(msgId, channel, params);
      final var sub = new AccountSubscription<>(
          commitment, channel, key, publicKey, msgId, msg, onSub, consumer
      );
      final var registrations = byCommitment == null ? new ConcurrentHashMap<Commitment, Subscription<T>>(4) : byCommitment;
      if (byCommitment == null) {
        subs.put(key, registrations);
      }
      registrations.put(commitment, sub);
      // Registered durably above; queued for send only if a connection exists — during the
      // gap, adoption re-derives the pending set from the registries, so intent is never lost.
      final var conn = this.connection;
      if (conn != null) {
        conn.pendingSubscriptions.put(msgId, sub);
      }
      checkSignalled = true;
      newSubscription.signal();
      return true;
    } finally {
      lock.unlock();
    }
  }

  /// Reports a subscription consumer's exception as a consumer failure, not a protocol one:
  /// logged and forwarded to the exception subscribers, while the caller carries on processing
  /// the message.
  private void consumerThrew(final String context, final RuntimeException ex) {
    log.log(WARNING, "Subscription consumer threw handling " + context + '.', ex);
    dispatchException(ex);
  }

  /// Dispatches to every exception subscriber, logging each one's `RuntimeException` so it can
  /// neither starve the others, be re-dispatched as a second exception, nor escape into the JDK,
  /// which treats a listener throw as connection-fatal.
  private void dispatchException(final RuntimeException ex) {
    for (final var sub : this.exceptionSubs) {
      try {
        sub.accept(ex);
      } catch (final RuntimeException subEx) {
        log.log(WARNING, "Exception subscriber threw.", subEx);
      }
    }
  }

  /// Whether an error code blames the request itself, so re-sending the identical frame can only
  /// collect the identical answer; other codes describe the server's condition and may pass on
  /// a later attempt.
  private static boolean isRequestDefect(final long code) {
    return code == JsonRpcException.INVALID_REQUEST
        || code == JsonRpcException.METHOD_NOT_FOUND
        || code == JsonRpcException.INVALID_PARAMS;
  }

  /// The request from `"method"` on: what the server was asked, without the correlation id.
  private static String requestFingerprint(final String msg) {
    return msg.substring(msg.indexOf("\"method\""));
  }

  /// Whether two subscriptions asked the server the same thing, which an id-reusing server may
  /// coalesce onto one id; one id for two different requests is a server defect.
  private static boolean equivalentRequests(final Subscription<?> a, final Subscription<?> b) {
    return requestFingerprint(a.msg()).equals(requestFingerprint(b.msg()));
  }

  /// Frees the registry slot `sub` occupies so its key can be subscribed again. Removes by
  /// identity, since [RootSubscription#equals(Object)] ignores the consumer and request, so an
  /// equal successor may already hold the slot. Prunes emptied outer entries, since signature
  /// keys are unbounded.
  private void releaseChannelSlot(final Subscription<?> sub) {
    final var channel = sub.channel();
    if (channel == null) {
      // Generic subscriptions carry no Channel; they are registered by notification method.
      if (sub instanceof GenericSubscription<?> generic) {
        this.genericSubs.computeIfPresent(generic.notificationMethod(), (_, subs) -> {
              if (subs.get(sub.key()) == sub) {
                subs.remove(sub.key());
              }
              return subs.isEmpty() ? null : subs;
            }
        );
      }
      return;
    }
    switch (channel) {
      case account -> releaseCommitmentSlot(this.accountSubs, sub);
      case logs -> releaseCommitmentSlot(this.txLogSubs, sub);
      case signature -> releaseCommitmentSlot(this.signatureSubs, sub);
      case program -> {
        // The legacy and explicitly keyed registries are intentionally disjoint namespaces.
        // Identity-sensitive removal makes checking both safe even when a caller key happens
        // to equal a legacy program address.
        releaseCommitmentSlot(this.programSubs, sub);
        releaseCommitmentSlot(this.keyedProgramSubs, sub);
      }
      case slot -> {
        if (this.slotSub == sub) {
          this.slotSub = null;
        }
      }
      case root -> {
        if (this.rootSub == sub) {
          this.rootSub = null;
        }
      }
    }
  }

  private static <M extends Map<Commitment, ? extends Subscription<?>>> void releaseCommitmentSlot(final Map<String, M> subs,
                                                                                                   final Subscription<?> sub) {
    subs.computeIfPresent(sub.key(), (_, byCommitment) -> {
          if (byCommitment.get(sub.commitment()) == sub) {
            byCommitment.remove(sub.commitment());
          }
          return byCommitment.isEmpty() ? null : byCommitment;
        }
    );
  }

  private void queueUnsubscribe(final Subscription<?> sub) {
    final var conn = this.connection;
    if (conn == null) {
      // No connection: nothing was sent, nothing can be confirmed, and a dead connection's
      // subId died with it — removing the registration above was the whole job.
      return;
    }
    conn.pendingSubscriptions.remove(sub.msgId());
    final var subId = sub.subId();
    if (subId != null) {
      // Value-conditional: the wire cancellation belongs to whichever registration OWNS the
      // id's mapping. If this one does not — an id-reusing server coalesced identical params
      // onto one id — sending it would kill the owner's server subscription, so the removal
      // above stays the whole job.
      if (conn.subscriptionsBySubId.remove(subId, sub)) {
        conn.attemptSeqs.remove(sub.msgId());
        // Retired, not merely removed: a late notification for this id must be dropped even
        // while a successor subscription is still unconfirmed.
        conn.retiredSubIds.add(subId);
        conn.pendingUnSubscriptions.put(subId, new QueuedUnsub(sub.unSubscribeMethod(), 0L, requestFingerprint(sub.msg())));
        // The un-subscription is transmitted by the next write cycle; on a quiet connection
        // that used to mean waiting out the whole check delay.
        checkSignalled = true;
        newSubscription.signal();
      }
    } else if (conn.inFlightSends.containsKey(sub.msgId())) {
      // Admitted to a send: on the wire, or queued in the chain where dispatch will recall it.
      // The tombstone converts its eventual confirmation into an immediate server unsubscribe;
      // discarding the confirmation as unknown left a server subscription nothing could cancel.
      // A request never admitted needs no tombstone — no frame, no answer, so one could never
      // be consumed and used to sit in the map for the connection's life. The fingerprint
      // rides along so the late grant can be adjudicated against whatever owns its id by then.
      conn.cancelledRequests.put(sub.msgId(),
          new CancelledRequest(sub.unSubscribeMethod(), requestFingerprint(sub.msg()))
      );
    } else {
      // Never admitted, or answered transiently and cancelled before the retry: no later
      // answer or retry exists, so removing the registration is the terminal transition for
      // its attempt ordinal too.
      conn.attemptSeqs.remove(sub.msgId());
    }
  }

  private boolean queueUnsubscribe(final String key,
                                   final Commitment commitment,
                                   final Map<String, ? extends Map<Commitment, ? extends Subscription<?>>> subs) {
    // Locked end to end, because the confirmation handler mutates the same state under this
    // lock. Unlocked, an unsubscribe could observe subId == null mid-confirmation, return true
    // having queued no server unsubscribe, and the confirmation would then install a live
    // mapping with no remaining local record — notifications forever, with no route to cancel.
    lock.lock();
    try {
      final var commitmentSubs = subs.get(key);
      if (commitmentSubs == null) {
        return false;
      } else {
        final var sub = commitmentSubs.remove(commitment);
        if (sub == null) {
          return false;
        } else {
          subs.compute(key, (_, v) -> v == null || v.isEmpty() ? null : v);
          this.queueUnsubscribe(sub);
          return true;
        }
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean accountSubscribe(final PublicKey key, final Consumer<AccountInfo<byte[]>> consumer) {
    return accountSubscribe(this.defaultCommitment, key, consumer);
  }

  @Override
  public boolean accountSubscribe(final Commitment commitment,
                                  final PublicKey key,
                                  final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                                  final Consumer<AccountInfo<byte[]>> consumer) {
    final var sub = this.accountSubs.get(key.toBase58());
    if (sub == null || !sub.containsKey(commitment)) {
      final var params = String.format("""
          "%s",{"encoding":"base64","commitment":"%s"}""", key, commitment.getValue()
      );
      return queueSubscription(commitment, Channel.account, key, params, this.accountSubs, onSub, consumer);
    } else {
      return false;
    }
  }

  @Override
  public boolean accountUnsubscribe(final PublicKey key) {
    return accountUnsubscribe(this.defaultCommitment, key);
  }

  @Override
  public boolean accountUnsubscribe(final Commitment commitment, final PublicKey key) {
    return queueUnsubscribe(key.toBase58(), commitment, this.accountSubs);
  }

  @Override
  public boolean logsSubscribe(final PublicKey key, final Consumer<TxLogs> consumer) {
    return logsSubscribe(this.defaultCommitment, key, consumer);
  }

  @Override
  public boolean logsSubscribe(final Commitment commitment,
                               final PublicKey key,
                               final Consumer<Subscription<TxLogs>> onSub,
                               final Consumer<TxLogs> consumer) {
    final var sub = this.txLogSubs.get(key.toBase58());
    if (sub == null || !sub.containsKey(commitment)) {
      final var params = String.format("""
          {"mentions":["%s"]},{"commitment":"%s"}""", key, commitment.getValue()
      );
      return queueSubscription(commitment, Channel.logs, key.toBase58(), params, this.txLogSubs, onSub, consumer);
    } else {
      return false;
    }
  }

  @Override
  public boolean logsUnsubscribe(final PublicKey key) {
    return logsUnsubscribe(this.defaultCommitment, key);
  }

  @Override
  public boolean logsUnsubscribe(final Commitment commitment, final PublicKey key) {
    return queueUnsubscribe(key.toBase58(), commitment, this.txLogSubs);
  }

  @Override
  public boolean signatureSubscribe(final String b58TxSig, final Consumer<TxResult> consumer) {
    return signatureSubscribe(this.defaultCommitment, b58TxSig, consumer);
  }

  @Override
  public boolean signatureSubscribe(final String b58TxSig,
                                    final boolean enableReceivedNotification,
                                    final Consumer<TxResult> consumer) {
    return signatureSubscribe(this.defaultCommitment, enableReceivedNotification, b58TxSig, consumer);
  }

  @Override
  public boolean signatureSubscribe(final Commitment commitment,
                                    final boolean enableReceivedNotification,
                                    final String b58TxSig,
                                    final Consumer<Subscription<TxResult>> onSub,
                                    final Consumer<TxResult> consumer) {
    // Probed against api.mainnet-beta.solana.com (2026-08-09): a syntactically valid frame
    // carrying a semantically invalid signature returns -32602 WITH the request id — which the
    // rejection path correlates, releases and reports — so client-side base58/length validation
    // duplicates work the server does authoritatively. A frame-SPLICING character is different:
    // a quote in the signature broke the frame itself, and the server answered -32700 with
    // "id":null — uncorrelatable, leaving the request gated forever. So only splicing is
    // rejected here; semantic validity is the server's call, whose rejection is terminal.
    validateJsonToken(b58TxSig, "b58TxSig");
    final var sub = this.signatureSubs.get(b58TxSig);
    if (sub == null || !sub.containsKey(commitment)) {
      final var params = String.format("""
          "%s",{"commitment":"%s","enableReceivedNotification":%b}""", b58TxSig, commitment.getValue(), enableReceivedNotification
      );
      return queueSubscription(commitment, Channel.signature, b58TxSig, params, this.signatureSubs, onSub, consumer);
    } else {
      return false;
    }
  }

  @Override
  public boolean signatureUnsubscribe(final String b58TxSig) {
    return signatureUnsubscribe(this.defaultCommitment, b58TxSig);
  }


  @Override
  public boolean signatureUnsubscribe(final Commitment commitment, final String b58TxSig) {
    return queueUnsubscribe(b58TxSig, commitment, this.signatureSubs);
  }

  @Override
  public boolean subscribeToTokenAccount(final PublicKey tokenMint,
                                         final PublicKey ownerAddress,
                                         final Consumer<AccountInfo<byte[]>> consumer) {
    return subscribeToTokenAccount(this.defaultCommitment, tokenMint, ownerAddress, consumer);
  }

  @Override
  public boolean subscribeToTokenAccount(final Commitment commitment,
                                         final PublicKey tokenMint,
                                         final PublicKey ownerAddress,
                                         final Consumer<AccountInfo<byte[]>> consumer) {
    return programSubscribe(
        commitment,
        solanaAccounts.tokenProgram(),
        List.of(
            TokenAccount.TOKEN_ACCOUNT_SIZE_FILTER,
            TokenAccount.createMintFilter(tokenMint),
            TokenAccount.createOwnerFilter(ownerAddress)
        ),
        consumer
    );
  }

  @Override
  public boolean subscribeToTokenAccounts(final PublicKey ownerAddress, final Consumer<AccountInfo<byte[]>> consumer) {
    return subscribeToTokenAccounts(this.defaultCommitment, ownerAddress, consumer);
  }

  @Override
  public boolean subscribeToTokenAccounts(final Commitment commitment,
                                          final PublicKey ownerAddress,
                                          final Consumer<AccountInfo<byte[]>> consumer) {
    return programSubscribe(
        commitment,
        solanaAccounts.tokenProgram(),
        List.of(
            TokenAccount.TOKEN_ACCOUNT_SIZE_FILTER,
            TokenAccount.createOwnerFilter(ownerAddress)
        ),
        consumer
    );
  }

  @Override
  public boolean programSubscribe(final PublicKey program, final Consumer<AccountInfo<byte[]>> consumer) {
    return programSubscribe(this.defaultCommitment, program, null, consumer);
  }

  @Override
  public boolean programSubscribe(final PublicKey program,
                                  final List<Filter> filters,
                                  final Consumer<AccountInfo<byte[]>> consumer) {
    return programSubscribe(this.defaultCommitment, program, filters, consumer);
  }

  @Override
  public boolean programSubscribe(final Commitment commitment,
                                  final PublicKey program,
                                  final List<Filter> filters,
                                  final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                                  final Consumer<AccountInfo<byte[]>> consumer) {
    final var sub = this.programSubs.get(program.toBase58());
    if (sub == null || !sub.containsKey(commitment)) {
      final var filtersJson = filters == null || filters.isEmpty() ? "" : filters.stream()
          .map(Filter::toJson)
          .collect(joining(",", ",\"filters\":[", "]"));

      final var params = String.format("""
              "%s",{"commitment":"%s","encoding":"base64"%s}""",
          program, commitment.getValue(), filtersJson
      );
      return queueSubscription(commitment, Channel.program, program, params, this.programSubs, onSub, consumer);
    } else {
      return false;
    }
  }

  @Override
  public boolean programUnsubscribe(final PublicKey program) {
    return programUnsubscribe(this.defaultCommitment, program);
  }

  @Override
  public boolean programUnsubscribe(final Commitment commitment, final PublicKey program) {
    return queueUnsubscribe(program.toBase58(), commitment, this.programSubs);
  }

  @Override
  public boolean keyedProgramSubscribe(final Commitment commitment,
                                       final String subscriptionKey,
                                       final PublicKey program,
                                       final List<Filter> filters,
                                       final Consumer<Subscription<AccountInfo<byte[]>>> onSub,
                                       final Consumer<AccountInfo<byte[]>> consumer) {
    validateSubscriptionKey(subscriptionKey);
    final var programAddress = program.toBase58();
    final var filtersJson = filters == null || filters.isEmpty() ? "" : filters.stream()
        .map(Filter::toJson)
        .collect(joining(",", ",\"filters\":[", "]"));

    final var params = String.format("""
            "%s",{"commitment":"%s","encoding":"base64"%s}""",
        programAddress, commitment.getValue(), filtersJson
    );
    return queueSubscription(
        commitment,
        Channel.program,
        subscriptionKey,
        program,
        params,
        this.keyedProgramSubs,
        onSub,
        consumer
    );
  }

  @Override
  public boolean keyedProgramUnsubscribe(final Commitment commitment, final String subscriptionKey) {
    validateSubscriptionKey(subscriptionKey);
    return queueUnsubscribe(subscriptionKey, commitment, this.keyedProgramSubs);
  }

  private static void validateSubscriptionKey(final String subscriptionKey) {
    if (subscriptionKey == null || subscriptionKey.isEmpty()) {
      throw new IllegalArgumentException("subscriptionKey is required");
    }
  }

  @Override
  public boolean slotSubscribe(final Consumer<Subscription<ProcessedSlot>> onSub,
                               final Consumer<ProcessedSlot> consumer) {
    lock.lock();
    try {
      if (closed() || this.slotSub != null) {
        // Occupancy first: an occupied singleton owes no message id and no allocation.
        return false;
      }
      final long msgId = this.msgId.incrementAndGet();
      final var msg = String.format("""
          {"jsonrpc":"2.0","id":%d,"method":"%s"}""", msgId, Channel.slot.subscribe()
      );
      final var slotSub = Subscription.createSubscription(null, Channel.slot, Channel.slot.name(), msgId, msg, onSub, consumer);
      this.slotSub = slotSub;
      final var conn = this.connection;
      if (conn != null) {
        conn.pendingSubscriptions.put(msgId, slotSub);
      }
      checkSignalled = true;
      newSubscription.signal();
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean slotUnsubscribe() {
    lock.lock();
    try {
      final var slotSub = this.slotSub;
      this.slotSub = null;
      if (slotSub == null) {
        return false;
      } else {
        this.queueUnsubscribe(slotSub);
        return true;
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean rootSubscribe(final Consumer<Subscription<Long>> onSub, final Consumer<Long> consumer) {
    lock.lock();
    try {
      if (closed() || this.rootSub != null) {
        return false;
      }
      final long msgId = this.msgId.incrementAndGet();
      final var msg = String.format("""
          {"jsonrpc":"2.0","id":%d,"method":"%s"}""", msgId, Channel.root.subscribe()
      );
      final var rootSub = Subscription.createSubscription(null, Channel.root, Channel.root.name(), msgId, msg, onSub, consumer);
      this.rootSub = rootSub;
      final var conn = this.connection;
      if (conn != null) {
        conn.pendingSubscriptions.put(msgId, rootSub);
      }
      checkSignalled = true;
      newSubscription.signal();
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean rootUnsubscribe() {
    lock.lock();
    try {
      final var rootSub = this.rootSub;
      this.rootSub = null;
      if (rootSub == null) {
        return false;
      } else {
        this.queueUnsubscribe(rootSub);
        return true;
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public <T> boolean subscribe(final String subscribeMethod,
                               final String unSubscribeMethod,
                               final String notificationMethod,
                               final String key,
                               final String paramsJson,
                               final Function<JsonIterator, T> parser,
                               final Consumer<Subscription<T>> onSub,
                               final Consumer<T> consumer) {
    // The frame is built by interpolation, so the method names must not be able to splice into
    // it; paramsJson is documented-raw by design and stays the caller's responsibility.
    validateJsonToken(subscribeMethod, "subscribeMethod");
    validateJsonToken(unSubscribeMethod, "unSubscribeMethod");
    validateJsonToken(notificationMethod, "notificationMethod");
    for (final var channel : Channel.values()) {
      if ((channel.name() + "Notification").equals(notificationMethod)) {
        // Built-in routing always wins, so a generic registration under a built-in name would
        // subscribe, confirm, and then never receive anything — an accepted state that cannot
        // be honored.
        throw new IllegalArgumentException(
            notificationMethod + " is routed by the built-in " + channel + " channel; use its typed subscribe");
      }
      // The same doctrine both directions: a generic subscribe under a built-in REQUEST method
      // confirms into an id the built-in router then refuses to deliver — and on an id-reusing
      // server it can be granted a healthy typed subscription's id, displacing it.
      if (channel.subscribe().equals(subscribeMethod)) {
        throw new IllegalArgumentException(
            subscribeMethod + " is the built-in " + channel + " channel's subscribe method; use its typed subscribe");
      }
      if (channel.unSubscribe().equals(unSubscribeMethod)) {
        throw new IllegalArgumentException(
            unSubscribeMethod + " is the built-in " + channel + " channel's unsubscribe method; use its typed subscribe");
      }
    }
    lock.lock();
    try {
      if (closed()) {
        return false;
      }
      final var registered = this.genericSubs.get(notificationMethod);
      if (registered != null && !registered.isEmpty()) {
        if (registered.containsKey(key)) {
          return false;
        }
        // One cancellation method per notification method, fixed by the first registration:
        // recovery for an unknown id has one registry to consult, and must not have to pick
        // among divergent methods — the pick would be arbitrary, and the wrong one draws
        // -32601 while the stray keeps streaming.
        final var boundMethod = registered.values().iterator().next().unSubscribeMethod();
        if (!boundMethod.equals(unSubscribeMethod)) {
          throw new IllegalArgumentException(
              notificationMethod + " subscriptions unsubscribe via " + boundMethod + ", not " + unSubscribeMethod);
        }
      }
      final var subs = registered != null
          ? registered
          : this.genericSubs.computeIfAbsent(notificationMethod, _ -> new ConcurrentHashMap<>());
      final long msgId = this.msgId.incrementAndGet();
      final var msg = String.format("""
          {"jsonrpc":"2.0","id":%d,"method":"%s","params":[%s]}""", msgId, subscribeMethod, paramsJson
      );
      final var sub = new GenericSubscription<>(unSubscribeMethod, notificationMethod, parser, key, msgId, msg, onSub, consumer);
      // Every mutator of this registry holds the lifecycle lock. The duplicate was rejected
      // above, so putIfAbsent's loser arm was unreachable and only obscured that ownership.
      subs.put(key, sub);
      final var conn = this.connection;
      if (conn != null) {
        conn.pendingSubscriptions.put(msgId, sub);
      }
      checkSignalled = true;
      newSubscription.signal();
      return true;
    } finally {
      lock.unlock();
    }
  }

  private static void validateJsonToken(final String value, final String name) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(name + " is required");
    }
    for (int i = 0; i < value.length(); ++i) {
      final char c = value.charAt(i);
      if (c == '"' || c == '\\' || c < 0x20) {
        throw new IllegalArgumentException("Invalid character in " + name + ": " + value);
      }
    }
  }

  @Override
  public boolean unsubscribe(final String notificationMethod, final String key) {
    lock.lock();
    try {
      final var subs = this.genericSubs.get(notificationMethod);
      if (subs != null) {
        final var sub = subs.remove(key);
        if (sub != null) {
          // Under the lock every mutator of this map is serialized, so the empty check cannot
          // race a re-registration; without the prune each retired method name kept an empty
          // map resident for the instance's life.
          if (subs.isEmpty()) {
            this.genericSubs.remove(notificationMethod, subs);
          }
          this.queueUnsubscribe(sub);
          return true;
        }
      }
      return false;
    } finally {
      lock.unlock();
    }
  }


  private static final FieldMatcher METHODS = FieldMatcher.of(
      "accountNotification",
      "signatureNotification",
      "programNotification",
      "logsNotification",
      "slotNotification",
      "rootNotification"
  );

  private static final CharBufferFunction<Channel> METHOD_PARSER = (buf, offset, len) -> switch (METHODS.match(buf, offset, len)) {
    case 0 -> Channel.account;
    case 1 -> Channel.signature;
    case 2 -> Channel.program;
    case 3 -> Channel.logs;
    case 4 -> Channel.slot;
    case 5 -> Channel.root;
    default -> null;
  };

  private static String createUnSubMsg(final long msgId, final String unSubscribeMethod, final BigInteger subId) {
    return String.format("""
            {"jsonrpc":"2.0","id":%d,"method":"%s","params":[%d]}""",
        msgId, unSubscribeMethod, subId
    );
  }

  private CompletableFuture<WebSocket> sendText(final Connection conn, final String msg) {
    CompletableFuture<WebSocket> future;
    try {
      future = conn.socket.sendText(msg, true);
    } catch (final RuntimeException ex) {
      // The JDK fails the future rather than throwing, but a wrapping socket may throw
      // synchronously — routed into the same seam so onSendTextError fires and the chain's
      // failure bookkeeping runs; a thrown send used to be contained by the chain yet
      // reported nowhere.
      future = CompletableFuture.failedFuture(ex);
    }
    log.log(DEBUG, "Writing text {0}", msg);
    future.whenComplete((_, ex) -> {
      if (ex != null) {
        if (conn != this.connection || conn.lifecycle != ConnectionLifecycle.ACTIVE) {
          // Every queued send on a displaced or escalation-aborted socket fails as its chain
          // drains against the abort; reporting each one would storm the error handler with
          // expected teardown noise — the escalation itself already reached the error seam.
          log.log(DEBUG, "Dropped text on a superseded socket: {0}", msg);
        } else if (onSendTextError == null) {
          log.log(WARNING, String.format("Failed to sendText '%s' to %s.", msg, this.endpoint.getHost()), ex);
        } else {
          onSendTextError.accept(this, ex);
        }
      } else {
        // Stamped on completion rather than on submission, so a text frame that never left is
        // not a write. The clock is the connection's own, so a displaced connection's late
        // success stamps only its dead self.
        conn.lastOutboundFrame.set(pacingMillis());
        log.log(DEBUG, "Sent text {0}", msg);
      }
    });
    return future;
  }

  /// Chains a text frame behind the connection's previous send, because the JDK allows one
  /// outstanding text send and fails others with `IllegalStateException`. A failed predecessor
  /// does not block the chain. Callers hold [#lock], which guards the tail. The send runs on
  /// the thread that settles the predecessor, or synchronously on the caller, under the lock,
  /// when the predecessor has already settled.
  private CompletableFuture<WebSocket> queueText(final Connection conn, final String msg) {
    final var next = conn.outboundTail
        .exceptionally(_ -> null)
        .thenCompose(_ -> sendText(conn, msg));
    conn.outboundTail = next;
    return next;
  }

  private void sendUnSubscription(final Connection conn,
                                  final String unSubscribeMethod,
                                  final BigInteger subId,
                                  final String fingerprint) {
    lock.lock();
    try {
      final var queued = conn.pendingUnSubscriptions.get(subId);
      if (conn.inFlightUnsubs.contains(subId)) {
        // Gate eligibility is decided BEFORE the queued intent is consumed, on the direct
        // path exactly as in the flush: the earlier cancellation owning the gate preceded —
        // and therefore cannot cancel — whatever this obligation targets. The obligation is
        // RECORDED, never spent: returning without queueing lost the only frame that could
        // cancel a postdating successor. The ack that frees the gate signals the loop.
        if (queued == null) {
          conn.pendingUnSubscriptions.put(subId, new QueuedUnsub(unSubscribeMethod, 0L, fingerprint));
        }
        return;
      }
      if (queued != null && queued.notBefore() > pacingMillis()) {
        // A paced retry stays paced: the orphan's continued notifications are not authority
        // to erase the window a rejecting peer earned.
        return;
      }
      conn.pendingUnSubscriptions.remove(subId);
      sendUnSubscriptionLockHeld(conn,
          queued == null ? unSubscribeMethod : queued.unSubscribeMethod(),
          subId,
          queued == null ? fingerprint : queued.fingerprint()
      );
    } finally {
      lock.unlock();
    }
  }

  /// Sends an un-subscription for `subId` unless one is already in flight, registering it for
  /// acknowledgement correlation and in [Connection#inFlightSends] so the unanswered-request
  /// deadline covers it. A failed send is re-queued, since the frame may be the only
  /// cancellation of an orphaned server subscription. Callers hold [#lock].
  private void sendUnSubscriptionLockHeld(final Connection conn,
                                          final String unSubscribeMethod,
                                          final BigInteger subId,
                                          final String fingerprint) {
    if (!conn.inFlightUnsubs.add(subId)) {
      // One cancellation for this id is already on the wire; its acknowledgement, rejection,
      // or send failure re-arms the gate. A duplicate here was a frame per replayed
      // notification.
      return;
    }
    final long msgId = this.msgId.incrementAndGet();
    // This cancellation's wire ordinal: what the acknowledgement handler compares against a
    // grant's attempt ordinal when a shared id must be adjudicated.
    conn.pendingUnsubAcks.put(msgId, new UnsubRequest(subId, unSubscribeMethod, ++conn.nextWireSeq, fingerprint));
    conn.inFlightSends.put(msgId, pacingMillis());
    queueText(conn, createUnSubMsg(msgId, unSubscribeMethod, subId)).whenComplete((_, ex) -> {
      lock.lock();
      try {
        if (ex != null) {
          conn.pendingUnsubAcks.remove(msgId);
          conn.inFlightSends.remove(msgId);
          conn.inFlightUnsubs.remove(subId);
          conn.pendingUnSubscriptions.putIfAbsent(subId, new QueuedUnsub(unSubscribeMethod, 0L, fingerprint));
          // The gate released with an obligation still (or newly) queued: the loop is woken,
          // or a maximal check delay would park the retry forever.
          checkSignalled = true;
          newSubscription.signal();
        } else {
          // The same restart the subscribe path performs: the deadline judges the peer's
          // silence after transmission, and time spent behind a slow chain is not the peer's.
          // replace, not put — a served answer must not be resurrected.
          conn.inFlightSends.replace(msgId, pacingMillis());
        }
      } finally {
        lock.unlock();
      }
    });
  }

  private <T> void publish(final Connection conn,
                           final Channel channel,
                           final JsonIterator ji,
                           final int paramsMark,
                           final T item) {
    ji.skipRestOfObject();
    if (ji.skipUntil("subscription") == null) {
      ji.reset(paramsMark).skipUntil("subscription");
    }
    final var subId = ji.readBigInteger();
    final var registered = conn.subscriptionsBySubId.get(subId);
    if (registered == null) {
      sendUnSubscription(conn, channel.unSubscribe(), subId, null);
    } else if (registered.channel() != channel) {
      // The declared method and the subId's registration disagree — a malformed or hostile
      // frame. Dispatching would hand one channel's consumer another channel's payload, and
      // unsubscribing would cancel a healthy subscription that never appeared in this frame.
      log.log(WARNING, "Dropping {0} notification whose subscription {1} belongs to {2}.",
          channel, subId, registered.channel()
      );
    } else {
      @SuppressWarnings("unchecked") final var sub = (Subscription<T>) registered;
      try {
        sub.accept(item);
      } catch (final RuntimeException ex) {
        consumerThrew(channel + " notification", ex);
      }
    }
  }

  private <T> void publish(final Connection conn,
                           final Channel channel,
                           final JsonIterator ji,
                           final int paramsMark,
                           final Function<Subscription<T>, T> factory) {
    final int mark = ji.mark();
    ji.skipRestOfObject();
    if (ji.skipUntil("subscription") == null) {
      ji.reset(paramsMark).skipUntil("subscription");
    }
    final var subId = ji.readBigInteger();
    final var registered = conn.subscriptionsBySubId.get(subId);
    if (registered == null) {
      sendUnSubscription(conn, channel.unSubscribe(), subId, null);
    } else if (registered.channel() != channel) {
      log.log(WARNING, "Dropping {0} notification whose subscription {1} belongs to {2}.",
          channel, subId, registered.channel()
      );
    } else {
      @SuppressWarnings("unchecked") final var sub = (Subscription<T>) registered;
      ji.reset(mark);
      // The factory parses — its exceptions are protocol-class and belong to the outer catch —
      // so only the consumer's accept is contained here.
      final var item = factory.apply(sub);
      try {
        sub.accept(item);
      } catch (final RuntimeException ex) {
        consumerThrew(channel + " notification", ex);
      }
    }
  }

  private void publishGeneric(final Connection conn,
                              final String notificationMethod,
                              final String unSubscribeMethod,
                              final JsonIterator ji,
                              final int paramsMark) {
    final int resultMark = ji.mark();
    ji.skip();
    if (ji.skipUntil("subscription") == null) {
      ji.reset(paramsMark).skipUntil("subscription");
    }
    final var subId = ji.readBigInteger();
    if (conn.subscriptionsBySubId.get(subId) instanceof GenericSubscription<?> generic
        && generic.notificationMethod().equals(notificationMethod)) {
      ji.reset(resultMark);
      try {
        generic.parseAndAccept(ji);
      } catch (final RuntimeException ex) {
        // The generic path fuses the caller's parser with the caller's consumer, so both are
        // caller code and both are contained — the label says so rather than guessing which.
        consumerThrew("generic " + notificationMethod + " parser/consumer", ex);
      }
    } else if (conn.subscriptionsBySubId.get(subId) != null) {
      // Registered, but under a different method or channel: malformed or hostile, and
      // unsubscribing the id would cancel the healthy subscription it actually names.
      log.log(WARNING, "Dropping {0} notification whose subscription {1} does not match.",
          notificationMethod, subId
      );
    } else {
      // This is the exact method bound by subscribe(), captured under the lifecycle lock before
      // parsing. Deriving a method from notificationMethod was never part of the generic API and
      // produced invalid frames for unrelated method names when the final registration raced out.
      sendUnSubscription(conn, unSubscribeMethod, subId, null);
    }
  }

  @SuppressWarnings("unused")
  private void onWholeMessage(final char[] msg,
                              final int offset,
                              final int tail,
                              final JsonIterator ji,
                              final Connection conn) {
    // System.out.format("<- %s%n", new String(msg, offset, tail - offset));
    try {
      if (ji.skipUntil("method") == null) {
        if (ji.reset(offset).skipUntil("error") != null) {
          // The response id names the request being rejected. Read by the reader the HTTP client
          // uses (JsonRpcException.envelopeRequestId), so both transports agree: member order is
          // free, so it is scanned for from the top; a server that could not read the request at
          // all answers with "id":null, which must not abandon this branch — that is the error
          // class most likely to carry it — and an id the reader cannot carry (one no long can
          // hold, a fraction, a negative) is simply uncorrelated, where reading it with readLong
          // used to throw out of this handler and leave the rejection unclassified. -1 is the
          // local absent sentinel; the correlation below matches only ids sava minted.
          final var envelopeId = JsonRpcException.envelopeRequestId(ji, offset);
          final long requestId = envelopeId.orElse(-1L);
          boolean dispatchException = true;
          boolean correlated = false;
          RuntimeException fatal = null;
          ji.reset(offset).skipUntil("error");
          // The first OptionalLong is retry-after seconds — the HTTP client fills it from the
          // retry-after header — and this path has no such hint. The second is the response id,
          // so a consumer handed a subscribe rejection can tell which registration it released;
          // an "id":null answer leaves it empty.
          final var exception = JsonRpcException.parseException(ji, OptionalLong.empty(), envelopeId);
          // A rejection the server blames on the request itself is that request's terminal
          // state: re-sending the same frame can only collect the same answer, so the entry is
          // retired and its registry slot freed for a corrected subscribe. Any other error —
          // Agave answers -32603 with "Subscription refused" when its node-wide subscription
          // limit is full — is the server's condition, not the request's, so the entry stays
          // pending and the resend pacing retries it.
          if (requestId >= 0) {
            // Same discipline as the confirmation branch: the re-queued subscription carries
            // the SAME msgId, so a displaced socket's in-flight rejection would otherwise
            // delete the entry onOpen just re-armed.
            lock.lock();
            try {
              if (conn == this.connection) {
                final var rejectedUnsub = conn.pendingUnsubAcks.remove(requestId);
                if (rejectedUnsub != null) {
                  conn.inFlightUnsubs.remove(rejectedUnsub.subId());
                  if (conn.pendingUnSubscriptions.containsKey(rejectedUnsub.subId())) {
                    // EVERY gate release wakes a cancellation queued behind it: the enqueue's
                    // own signal was consumed by the gate-blocked pass, and a maximal check
                    // delay would otherwise park it forever.
                    checkSignalled = true;
                    newSubscription.signal();
                  }
                  // A rejected UN-subscription: which rejection decides what is owed. A code
                  // that blames the request is terminal — the measured already-absent case,
                  // Agave's -32602 "Invalid subscription id.", settles quietly, but -32601
                  // means the minted method named no cancellation the server recognizes, so
                  // the orphan it compensated is uncancellable by it: settled, and the
                  // consumer is told. A server-condition code (-32603 and kin) means the
                  // cancellation did NOT run and is still owed — re-queued for the next
                  // flush, and reported.
                  if (isRequestDefect(exception.code())) {
                    if (exception.code() == JsonRpcException.METHOD_NOT_FOUND) {
                      log.log(WARNING, "Un-subscription {0} for {1} used method {2}, which {3} does not recognize.",
                          requestId, rejectedUnsub.subId(), rejectedUnsub.unSubscribeMethod(), endpoint.getHost()
                      );
                    } else {
                      // Settled: the id was already gone server side, and ordered frames mean
                      // nothing more can arrive for it — retirement is released with the
                      // rejection rather than growing for the connection's life.
                      conn.retiredSubIds.remove(rejectedUnsub.subId());
                      log.log(DEBUG, "Un-subscription {0} for {1} rejected by {2}; treating as settled.",
                          requestId, rejectedUnsub.subId(), endpoint.getHost()
                      );
                      dispatchException = false;
                    }
                  } else if (conn.subscriptionsBySubId.get(rejectedUnsub.subId()) instanceof Subscription<?> liveOwner) {
                    if (rejectedUnsub.fingerprint() == null
                        || rejectedUnsub.fingerprint().equals(requestFingerprint(liveOwner.msg()))) {
                      // Obsolete, not owed: a successor's EQUIVALENT grant now owns this id,
                      // and on a no-reference-counting server that live mapping owns the ONE
                      // shared server subscription — retrying the old cancellation would kill
                      // it. Response order cannot change that ownership.
                      log.log(DEBUG, "Un-subscription {0} for {1} is obsolete: a successor owns the id.",
                          requestId, rejectedUnsub.subId()
                      );
                    } else {
                      // The failed cancellation targeted a DIFFERENT request than the live
                      // owner: the predecessor's stream may still exist server-side, so one
                      // id now names two streams and notifications cannot be attributed —
                      // the same connection-fatal ambiguity as a non-equivalent collision.
                      conn.lifecycle = ConnectionLifecycle.ESCALATED;
                      fatal = new IllegalStateException(
                          "Un-subscription " + requestId + " for id " + rejectedUnsub.subId() + " from "
                              + endpoint.getHost() + " failed while a non-equivalent successor "
                              + liveOwner.key() + " owns the id; replacing the connection.");
                      log.log(ERROR, fatal.getMessage());
                    }
                  } else {
                    // Still owed, but on the retry cadence: an immediately-refusing peer
                    // must not be retried at wire speed, so the re-queue carries the same
                    // window a failed subscribe waits out — saturated, so the maximal
                    // "disabled" window does not wrap into an immediate retry.
                    final long window = timings.subscriptionResendDelay();
                    final long now = pacingMillis();
                    final long notBefore = window > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + window;
                    conn.pendingUnSubscriptions.putIfAbsent(rejectedUnsub.subId(),
                        new QueuedUnsub(rejectedUnsub.unSubscribeMethod(), notBefore, rejectedUnsub.fingerprint())
                    );
                  }
                }
                // ANY correlated error is a response, and a response releases the send gate:
                // for a transient refusal that is precisely what re-arms the retry the
                // classification below preserves. The tombstone releases on any answer too —
                // a cancelled request is not pending, so nothing will re-send it, and a
                // tombstone no confirmation can ever consume is a leak, not compensation.
                // "Correlated" here means the id matched a request THIS connection is
                // tracking: only those bypass the stale-id wording heuristic below — a
                // numeric id alone proves nothing, since the uncorrelatable stale case
                // carries one too.
                // A correlated error concludes the transmitted attempt whatever its
                // classification: the attempt produced no grant, so its wire ordinal is dead
                // evidence — kept, a stale ordinal below a kill pinned that kill (and its
                // retirement) until reconnect. A retry is a NEW wire position with a fresh
                // ordinal; retry pacing lives in lastAttempt and the send gate, never here.
                conn.attemptSeqs.remove(requestId);
                correlated = conn.inFlightSends.remove(requestId) != null | rejectedUnsub != null;
                correlated |= conn.cancelledRequests.remove(requestId) != null;
                if (isRequestDefect(exception.code())) {
                  final var rejected = conn.pendingSubscriptions.remove(requestId);
                  if (rejected != null) {
                    releaseChannelSlot(rejected);
                    log.log(WARNING, "Subscription request {0} rejected by {1}: released {2} {3}.",
                        requestId, endpoint.getHost(), rejected.channel(), rejected.key()
                    );
                  }
                }
              }
            } finally {
              lock.unlock();
            }
          }
          final var message = exception.getMessage();
          // The startsWith heuristic survives only as the fallback for UNCORRELATED stale-id
          // errors — a correlated error's disposition was decided by classification above,
          // and its wording must not overrule that: a subscribe rejection whose message
          // happens to say "Invalid subscription id" is still consumer news.
          if (fatal != null) {
            // The connection replacement supersedes the plain error dispatch.
            onError(conn.socket, fatal);
          } else if (dispatchException && (correlated || message == null || !message.startsWith("Invalid subscription id"))) {
            dispatchException(exception);
          }
        } else {
          final var sub = SubConfirmation.parse(ji.reset(offset));
          if (sub.boolResult() != null) {
            // An un-subscription acknowledgement. True retires the request; false means the id
            // was already gone server side — either way the request is settled, and neither is
            // an error worth a consumer's attention. These used to be skipped wholesale. The
            // false case is not hypothetical: a stale unsubscribe draws -32602 "Invalid
            // subscription id." from Agave but this quiet false from Helius (both measured
            // 2026-08-09).
            lock.lock();
            try {
              if (conn == this.connection) {
                final var acked = conn.pendingUnsubAcks.remove(sub.msgId());
                if (acked != null) {
                  conn.inFlightSends.remove(sub.msgId());
                  conn.inFlightUnsubs.remove(acked.subId());
                  final boolean stillQueued = conn.pendingUnSubscriptions.containsKey(acked.subId());
                  if (stillQueued) {
                    // A later cancellation for this id waited out this one's single-flight
                    // gate; the gate is free now, so the loop is woken to transmit it.
                    checkSignalled = true;
                    newSubscription.signal();
                  }
                  if (sub.boolResult()) {
                    // TRUE is the contract's only removal evidence, and WIRE order — not
                    // response arrival order, which JSON-RPC leaves free, and not request
                    // ids, which are stable across retries — decides what it removed. A
                    // mapped grant whose latest ATTEMPT preceded this cancellation on the
                    // wire was the thing the server cancelled: it is re-queued, not mourned —
                    // the durable registration still expresses intent, so it re-sends and
                    // re-confirms under a fresh grant. A grant whose attempt followed the
                    // cancellation postdates the kill and is left alone.
                    final var casualty = conn.subscriptionsBySubId.get(acked.subId());
                    if (casualty != null) {
                      final long casualtySeq = conn.attemptSeqs.getOrDefault(casualty.msgId(), Long.MAX_VALUE);
                      if (casualtySeq < acked.wireSeq()) {
                        conn.subscriptionsBySubId.remove(acked.subId());
                        casualty.setSubId(null);
                        casualty.setLastAttempt(Subscription.NEVER);
                        conn.retiredSubIds.add(acked.subId());
                        conn.pendingSubscriptions.put(casualty.msgId(), casualty);
                        conn.inFlightSends.remove(casualty.msgId());
                        checkSignalled = true;
                        newSubscription.signal();
                        log.log(WARNING, "Un-subscription {0} cancelled live subscription {1}; re-subscribing {2}.",
                            sub.msgId(), acked.subId(), casualty.key()
                        );
                      }
                    } else if (conn.pendingSubscriptions.keySet().stream()
                        .anyMatch(m -> conn.attemptSeqs.getOrDefault(m, Long.MAX_VALUE) < acked.wireSeq())) {
                      // An attempt transmitted ahead of this cancellation is still
                      // unanswered: any grant it draws for this id arrives already dead, so
                      // the kill ordinal is recorded for the confirmation branch — response
                      // order must not change the outcome, and one kill covers EVERY
                      // coalesced grant below it.
                      conn.killedSubIds.merge(acked.subId(), acked.wireSeq(), Math::max);
                    } else if (!stillQueued) {
                      // Nothing outstanding can resolve to this id, and frames are ordered,
                      // so no late notification for it can follow its own removal:
                      // retirement has done its job and is released rather than growing for
                      // the connection's life.
                      conn.retiredSubIds.remove(acked.subId());
                    }
                  } else {
                    // FALSE: the server removed NOTHING — the id was already gone (Agave
                    // answers -32602 here; Helius answers this quiet false, both measured
                    // 2026-08-09). It is no evidence that any live mapping was cancelled, so
                    // nothing is removed or replayed on its account.
                    if (!stillQueued) {
                      conn.retiredSubIds.remove(acked.subId());
                    }
                    log.log(DEBUG, "Un-subscription {0} for {1} was already gone server side.",
                        sub.msgId(), acked.subId()
                    );
                  }
                }
              }
            } finally {
              lock.unlock();
            }
          } else if (sub.subId() != null) {
            // Under the lock, re-checked against the current socket. onOpen displaces at entry
            // and rebuilds under this lock, but a confirmation already past the entry guard when
            // the takeover began is still in flight — and the re-queued subscription carries the
            // SAME msgId, so without the re-check the old connection's subId would land on the
            // new connection's subscription and every later frame for it would be unsubscribed
            // as unknown.
            RuntimeException collision = null;
            RuntimeException fatal = null;
            lock.lock();
            try {
              if (conn == this.connection) {
                conn.inFlightSends.remove(sub.msgId());
                final var strayAck = conn.pendingUnsubAcks.remove(sub.msgId());
                if (strayAck != null) {
                  // A NUMERIC answer to an un-subscription request is a server defect, but it
                  // is an answer: the gates release, so the id's cancellation is not wedged
                  // for the connection's life. Nothing is installed from it.
                  conn.inFlightUnsubs.remove(strayAck.subId());
                  if (conn.pendingUnSubscriptions.containsKey(strayAck.subId())) {
                    // Every gate release wakes queued work, defective answers included.
                    checkSignalled = true;
                    newSubscription.signal();
                  }
                  log.log(WARNING, "Un-subscription {0} for {1} was answered with a numeric result; settling.",
                      sub.msgId(), strayAck.subId()
                  );
                } else {
                  final var cancelled = conn.cancelledRequests.remove(sub.msgId());
                  if (cancelled != null) {
                    final var owner = conn.subscriptionsBySubId.get(sub.subId());
                    if (owner != null) {
                      if (cancelled.fingerprint().equals(requestFingerprint(owner.msg()))) {
                        // The cancelled request's grant resolved to an id a LIVE registration
                        // already owns, and the two requests were EQUIVALENT: the server
                        // coalesced them, so there is no server-side subscription of the
                        // loser's to cancel — a wire cancellation here would kill the
                        // owner's. The tombstone is spent on nothing, deliberately — and the
                        // ordinal dies with it.
                        conn.attemptSeqs.remove(sub.msgId());
                        log.log(DEBUG, "Cancelled request {0} was coalesced onto live subscription {1}; nothing to cancel.",
                            sub.msgId(), sub.subId()
                        );
                      } else {
                        // One id for two DIFFERENT requests: cancelling the loser does not
                        // make the ambiguity disappear — frames under this id can no longer
                        // be attributed — so this takes the same connection-fatal path as a
                        // collision between two live registrations.
                        conn.lifecycle = ConnectionLifecycle.ESCALATED;
                        fatal = new IllegalStateException(
                            "Subscription id " + sub.subId() + " from " + endpoint.getHost()
                                + " was assigned to a cancelled request that is not equivalent to its live owner "
                                + owner.key() + "; replacing the connection.");
                        log.log(ERROR, fatal.getMessage());
                      }
                    } else {
                      // Unsubscribed before this confirmation arrived: the local record is
                      // already gone, so the just-created server subscription has nothing
                      // left to cancel it — except this, the moment its id is first known.
                      // The id is retired too: a successor for the same key may already be
                      // unconfirmed, and the cancelled subscription's notifications must not
                      // reach it in that window.
                      conn.attemptSeqs.remove(sub.msgId());
                      conn.retiredSubIds.add(sub.subId());
                      sendUnSubscription(conn, cancelled.unSubscribeMethod(), sub.subId(), cancelled.fingerprint());
                    }
                  } else {
                    final var pendingSub = conn.pendingSubscriptions.remove(sub.msgId());
                    if (pendingSub != null) {
                      final var kill = conn.killedSubIds.get(sub.subId());
                      final long attemptSeq = conn.attemptSeqs.getOrDefault(sub.msgId(), Long.MAX_VALUE);
                      if (kill != null && attemptSeq < kill) {
                        // This grant is already dead: its attempt was ahead of a cancellation
                        // the server has since acknowledged true for this same id. Installing
                        // it would map a subscription the server no longer holds — the exact
                        // outcome the ack-time casualty replay prevents when the responses
                        // arrive the other way around. The kill evidence is NOT consumed: an
                        // id-reusing server may have coalesced several grants below the same
                        // cancellation, and each must be replayed. A retry re-sent after the
                        // cancellation carries a higher attempt ordinal and installs below.
                        conn.retiredSubIds.add(sub.subId());
                        pendingSub.setLastAttempt(Subscription.NEVER);
                        conn.pendingSubscriptions.put(sub.msgId(), pendingSub);
                        checkSignalled = true;
                        newSubscription.signal();
                        log.log(WARNING, "Grant {0} for request {1} was already cancelled; re-subscribing {2}.",
                            sub.subId(), sub.msgId(), pendingSub.key()
                        );
                      } else {
                        if (kill != null) {
                          // A grant whose attempt postdates the kill is fresh: the evidence
                          // is obsolete for this id and released.
                          conn.killedSubIds.remove(sub.subId());
                        }
                        final var previous = conn.subscriptionsBySubId.putIfAbsent(sub.subId(), pendingSub);
                        if (previous == null || previous == pendingSub) {
                          pendingSub.setSubId(sub.subId());
                          // Same-id reuse, measured live (api.mainnet-beta.solana.com,
                          // 2026-08-09): an identical subscribe returned the SAME id for both
                          // requests, and ONE unsubscribe cancelled it — no reference
                          // counting. A queued un-subscription for this id therefore kills
                          // the subscription we were just granted, so the cancellation is
                          // cancelled; and a retired id that comes back is retired no longer.
                          // Helius, measured the same day, granted the identical duplicate a
                          // DISTINCT id with an independent lifetime — reuse is
                          // server-dependent, and against a non-reusing server these two
                          // removes simply find nothing.
                          conn.pendingUnSubscriptions.remove(sub.subId());
                          conn.retiredSubIds.remove(sub.subId());
                        } else if (equivalentRequests(previous, pendingSub)) {
                          // The server coalesced two EQUIVALENT registrations onto one id —
                          // Agave reuses the token for byte-identical params, which the
                          // generic API can produce under two keys. Displacing starved the
                          // first owner silently, and unsubscribing either then killed the
                          // shared server subscription for both. The owner keeps the mapping;
                          // the loser is released and reported, and NO wire cancellation is
                          // sent — the server-side subscription is the owner's.
                          conn.attemptSeqs.remove(sub.msgId());
                          releaseChannelSlot(pendingSub);
                          collision = new IllegalStateException(
                              "Subscription id " + sub.subId() + " from " + endpoint.getHost()
                                  + " is already owned by " + previous.key() + "; releasing " + pendingSub.key()
                                  + " — the server coalesced identical params onto one subscription.");
                          log.log(WARNING, collision.getMessage());
                        } else {
                          // One id for two DIFFERENT requests: the id no longer names a
                          // stream either registration can trust, and no local bookkeeping
                          // can decide whose frames these are. The connection is the unit
                          // that can be made honest again — aborted, through the same seam
                          // every other irrecoverable transport state uses.
                          conn.attemptSeqs.remove(sub.msgId());
                          conn.lifecycle = ConnectionLifecycle.ESCALATED;
                          fatal = new IllegalStateException(
                              "Subscription id " + sub.subId() + " from " + endpoint.getHost()
                                  + " was assigned to non-equivalent requests " + previous.key() + " and "
                                  + pendingSub.key() + "; replacing the connection.");
                          log.log(ERROR, fatal.getMessage());
                        }
                      }
                    }
                  }
                }
              }
            } finally {
              lock.unlock();
            }
            if (fatal != null) {
              onError(conn.socket, fatal);
            } else if (collision != null) {
              // Off the lock, like every consumer-facing dispatch.
              dispatchException(collision);
            }
          }
        }
      } else {
        final int methodMark = ji.mark();
        final var channel = ji.applyChars(METHOD_PARSER);
        if (channel == null) {
          final var notificationMethod = ji.reset(methodMark).readString();
          final String unSubscribeMethod;
          lock.lock();
          try {
            final var registered = this.genericSubs.get(notificationMethod);
            unSubscribeMethod = registered == null || registered.isEmpty()
                ? null
                : registered.values().iterator().next().unSubscribeMethod();
          } finally {
            lock.unlock();
          }
          if (unSubscribeMethod != null) {
            skipToParams(ji, offset);
            final int paramsMark = ji.mark();
            ji.skipUntil("result");
            publishGeneric(conn, notificationMethod, unSubscribeMethod, ji, paramsMark);
          }
        } else {
          skipToParams(ji, offset);
          if (channel == Channel.slot) {
            final var slotSub = this.slotSub;
            final int slotParamsMark = ji.mark();
            final var subId = readSubscriptionId(ji);
            final var registeredForSlotId = conn.subscriptionsBySubId.get(subId);
            if (registeredForSlotId != null && registeredForSlotId.channel() != Channel.slot) {
              // The same cross-channel guard the keyed channels apply: this id belongs to a
              // LIVE registration of another channel, so a slotNotification naming it is
              // malformed or hostile — and auto-unsubscribing it would cancel that healthy
              // registration on a permissive server.
              log.log(WARNING, "Dropping slot notification whose subscription {0} belongs to {1}.",
                  subId, registeredForSlotId.channel()
              );
            } else if (slotSub == null || staleSingletonId(conn, slotSub, subId)) {
              // No singleton, or a notification for a predecessor's id after an
              // unsubscribe/resubscribe: either way it must not reach the current consumer.
              sendUnSubscription(conn, channel.unSubscribe(), subId, null);
            } else if (slotSub.subId() == null) {
              // Unconfirmed: ordered frames put the confirmation before its first
              // notification, so nothing legitimate exists in this window — and answering a
              // reordering peer's early frame with an unsubscribe could cancel the very
              // grant in flight. Dropped, delivered to nobody.
              log.log(WARNING, "Dropping slot notification {0} received before the subscription was confirmed.", subId);
            } else {
              ji.reset(slotParamsMark).skipUntil("result");
              final var slot = ProcessedSlot.parse(ji);
              try {
                slotSub.accept(slot);
              } catch (final RuntimeException ex) {
                consumerThrew("slot notification", ex);
              }
            }
          } else if (channel == Channel.root) {
            final var rootSub = this.rootSub;
            final int rootParamsMark = ji.mark();
            final var subId = readSubscriptionId(ji);
            final var registeredForRootId = conn.subscriptionsBySubId.get(subId);
            if (registeredForRootId != null && registeredForRootId.channel() != Channel.root) {
              // Same cross-channel guard as the slot path above.
              log.log(WARNING, "Dropping root notification whose subscription {0} belongs to {1}.",
                  subId, registeredForRootId.channel()
              );
            } else if (rootSub == null || staleSingletonId(conn, rootSub, subId)) {
              sendUnSubscription(conn, channel.unSubscribe(), subId, null);
            } else if (rootSub.subId() == null) {
              // Same pre-confirmation drop as the slot path above.
              log.log(WARNING, "Dropping root notification {0} received before the subscription was confirmed.", subId);
            } else {
              ji.reset(rootParamsMark).skipUntil("result");
              final long root = ji.readLong();
              try {
                rootSub.accept(root);
              } catch (final RuntimeException ex) {
                consumerThrew("root notification", ex);
              }
            }
          } else {
            final int paramsMark = ji.mark();
            ji.skipUntil("result");

            final int resultMark = ji.mark();
            ji.skipUntil("context");
            final var context = Context.parse(ji);
            if (ji.skipUntil("value") == null) {
              ji.reset(resultMark).skipUntil("value");
            }
            switch (channel) {
              case account ->
                  publish(conn, channel, ji, paramsMark, sub -> AccountInfo.parse(sub.publicKey(), ji, context, BYTES_IDENTITY));
              case logs -> publish(conn, channel, ji, paramsMark, TxLogs.parse(ji, context));
              case program ->
                  publish(conn, channel, ji, paramsMark, AccountInfo.parseAccount(ji, context, BYTES_IDENTITY));
              case signature -> {
                final var result = TxResult.parseResult(ji, context);
                if (result != null) {
                  ji.skipRestOfObject();
                  if (ji.skipUntil("subscription") == null) {
                    ji.reset(paramsMark).skipUntil("subscription");
                  }
                  final var subId = ji.readBigInteger();
                  final var registered = conn.subscriptionsBySubId.get(subId);
                  if (registered == null) {
                    if (!conn.retiredSubIds.contains(subId)) {
                      // Unknown, and not merely late for a retirement already being cancelled:
                      // auto-cancel like every other channel, rather than letting an orphaned
                      // server subscription stream forever.
                      sendUnSubscription(conn, Channel.signature.unSubscribe(), subId, null);
                    }
                  } else if (registered.channel() != Channel.signature) {
                    // A signatureNotification naming another channel's subId is malformed or
                    // hostile; acting on it would terminally remove that channel's mapping.
                    log.log(WARNING, "Dropping signature notification whose subscription {0} belongs to {1}.",
                        subId, registered.channel()
                    );
                  } else {
                    @SuppressWarnings("unchecked") final var sub = (Subscription<TxResult>) registered;
                    // Detached before delivery: the server has already cancelled its side, so
                    // the terminal state must not depend on the consumer returning normally —
                    // a throwing consumer previously left the completed signature registered,
                    // replaying it every reconnect and blocking resubscription of its key.
                    if (!"receivedSignature".equals(result.value())) {
                      lock.lock();
                      try {
                        conn.subscriptionsBySubId.remove(subId);
                        conn.attemptSeqs.remove(sub.msgId());
                        // The durable commit follows the doctrine every sibling branch does:
                        // a displaced socket's terminal frame retires only its own dead
                        // mapping. A successor has re-armed this same object, and releasing
                        // it here deleted the successor's intent.
                        if (conn == this.connection) {
                          releaseCommitmentSlot(this.signatureSubs, sub);
                        }
                      } finally {
                        lock.unlock();
                      }
                    }
                    try {
                      sub.accept(result);
                    } catch (final RuntimeException ex) {
                      consumerThrew("signature notification", ex);
                    }
                  }
                }
              }
              default -> { // ignored.
              }
            }
          }
        }
      }
    } catch (final RuntimeException ex) {
      log.log(WARNING, "Unexpected json rpc error.", ex);
      dispatchException(ex);
    }
  }

  private void ensureCapacity(final Connection conn, final int minCapacity) {
    if (minCapacity > conn.buffer.length) {
      // the onText gate guarantees minCapacity <= maxMessageLength, so the clamp
      // never under-allocates, and unclamped doubling could reach nearly twice the
      // declared budget; widened so the shift cannot wrap — near-MAX_VALUE caps land
      // on maxMessageLength, one terminal allocation, not an exact-fit re-copy per fragment
      final long newCapacity = ((long) conn.buffer.length << 1) + 2;
      conn.buffer = Arrays.copyOf(conn.buffer, Math.clamp(newCapacity, minCapacity, this.maxMessageLength));
    }
  }

  @Override
  public CompletionStage<?> onText(final WebSocket webSocket, final CharSequence message, final boolean last) {
    // One resolution answers every guard: no current connection (closed, or the gap between
    // connect() and adoption), or a socket that is not the current one, resolves to null — and
    // a stale callback that already resolved its own connection can only mutate that dead
    // connection's state, which nothing reads.
    final var conn = connectionFor(webSocket);
    if (conn == null) {
      return null;
    }
    // Every fragment is peer contact, but only a terminal frame is a message: a peer
    // trickling fragments of one JSON document forever is provably alive while never having
    // delivered anything — advancing the public message evidence on fragments would report
    // exactly that peer as healthy. A complete message stamps before anything can reject it: a
    // frame the cap rejects, or one that fails to parse, still counts, since liveness is a
    // question about the connection rather than the content. Two clocks deliberately: the
    // consumer-facing stamp is epoch millis by contract, the pacing stamp is monotonic.
    conn.lastPeerContact = pacingMillis();
    answerPingProbe(conn);
    if (last) {
      conn.lastMessageReceived = clock.currentTimeMillis();
    }
    // The JDK happens to deliver CharBuffers today, but the listener contract promises only a
    // CharSequence, and webSocketBuilder(...) is public API — a wrapping builder may pass a String.
    final var buf = message instanceof CharBuffer charBuffer ? charBuffer : CharBuffer.wrap(message);
    final int len = message.length();
    // A message the cap excludes is a protocol violation, not a parse failure: the
    // reassembly buffer would otherwise grow until OOM against a server that never
    // stops fragmenting. Enforced on the whole prospective message regardless of
    // framing, overflow-safely (offset never exceeds the cap, so the subtraction
    // cannot wrap). Connection-fatal, so it takes the same seam a transport error
    // does: retire the connection (which aborts its transport), and let onError decide
    // between the default log-and-close and the caller's reconnect policy.
    if (len > this.maxMessageLength - conn.offset) {
      final long total = (long) conn.offset + len;
      onError(webSocket, new IllegalStateException(
              total + " char message from " + endpoint.getHost()
                  + " exceeds maxMessageLength " + this.maxMessageLength
          )
      );
      return null;
    }
    if (last) {
      if (conn.offset > 0) {
        final int to = conn.offset + len;
        ensureCapacity(conn, to);
        if (buf.hasArray()) {
          System.arraycopy(buf.array(), buf.position() + buf.arrayOffset(), conn.buffer, conn.offset, len);
        } else {
          buf.get(conn.buffer, conn.offset, len);
        }
        onWholeMessage(conn.buffer, 0, to, conn.ji.reset(conn.buffer, 0, to), conn);
        conn.offset = 0;
      } else {
        if (buf.hasArray()) {
          final int offset = buf.position() + buf.arrayOffset();
          final int to = offset + len;
          final char[] bufArray = buf.array();
          onWholeMessage(bufArray, offset, to, conn.ji.reset(bufArray, offset, to), conn);
        } else {
          ensureCapacity(conn, len);
          buf.get(conn.buffer, 0, len);
          onWholeMessage(conn.buffer, 0, len, conn.ji.reset(conn.buffer, 0, len), conn);
        }
      }
    } else {
      ensureCapacity(conn, conn.offset + len);
      if (buf.hasArray()) {
        System.arraycopy(buf.array(), buf.position() + buf.arrayOffset(), conn.buffer, conn.offset, len);
      } else {
        buf.get(conn.buffer, conn.offset, len);
      }
      conn.offset += len;
    }
    return null;
  }

  @Override
  public CompletionStage<?> onBinary(final WebSocket webSocket, final ByteBuffer data, final boolean last) {
    // The same identity fence as every other entry: a binary frame is a protocol violation
    // only on the LIVE JSON transport. A displaced or closed socket's late callback throwing
    // here would escape into the JDK for a connection already retired.
    if (connectionFor(webSocket) == null) {
      return null;
    }
    throw new UnsupportedOperationException();
  }

  private void lockAndHandlePendingSubscriptions() {
    final Throwable escalation;
    final Connection conn;
    lock.lock();
    try {
      // Re-resolve after taking the lifecycle lock. A peer callback can resolve transport A,
      // pause, and resume after transport B has taken over; maintenance belongs to B rather
      // than to a captured, already-aborted Connection.
      conn = this.connection;
      if (conn != null) {
        escalation = handlePendingSubscriptions(conn);
      } else {
        escalation = null;
      }
    } finally {
      lock.unlock();
    }
    if (escalation != null) {
      deliverEscalation(conn, escalation);
    }
  }

  /// Delivers a maintenance escalation after the lifecycle lock is released: retires `conn` if
  /// it is still current (else does nothing), then reports its recorded Ping failure through
  /// [#deliverRetiredPingFailure(Throwable)] if there is one, else `escalation` through the
  /// error policy.
  private void deliverEscalation(final Connection conn, final Throwable escalation) {
    // Claim the transport once for the whole notice. A concurrent connection may already have
    // displaced it. A close/error callback which wins retirement also takes any already-recorded
    // Ping failure, so the specific observation cannot disappear in the gap between signaling
    // this maintenance pass and its delivery.
    final var retired = retireConnection(conn.socket);
    if (retired == null) {
      return;
    }
    final var pingFailure = takePingFailure(retired);
    if (pingFailure != null) {
      deliverRetiredPingFailure(pingFailure);
    } else {
      deliverRetiredError(escalation);
    }
  }

  private static Throwable takePingFailure(final Connection conn) {
    final var pingFailure = conn.pingFailure;
    conn.pingFailure = null;
    return pingFailure;
  }

  /// Reports a failed Ping on a retired transport: the error policy first, then [#onPingError]
  /// (or a log) in `finally`, so a reconnect, close, or throw from the policy cannot suppress it.
  private void deliverRetiredPingFailure(final Throwable pingFailure) {
    try {
      deliverRetiredError(pingFailure);
    } finally {
      if (this.onPingError == null) {
        log.log(WARNING, "Failed to ping " + this.endpoint.getHost() + '.', pingFailure);
      } else {
        try {
          this.onPingError.accept(this, pingFailure);
        } catch (final RuntimeException handlerEx) {
          log.log(ERROR, "onPingError handler threw while handling a terminal ping failure.", handlerEx);
        }
      }
    }
  }

  /// One maintenance pass, run under [#lock]. Returns the escalation for the caller to deliver
  /// after unlocking, since user error handlers run off the lock; null when there is none.
  private Throwable handlePendingSubscriptions(final Connection conn) {
    return switch (conn.lifecycle) {
      case ACTIVE -> handleActivePendingSubscriptions(conn);
      case PING_FAILED -> claimFailedPing(conn);
      case ESCALATED -> null;
    };
  }

  private Throwable handleActivePendingSubscriptions(final Connection conn) {
    final long now = pacingMillis();
    if (!conn.killedSubIds.isEmpty()) {
      // The sweep is CAUSAL, per entry: a kill is evidence only against requests transmitted
      // before it, so once no pending attempt predates it the entry dies — a request
      // transmitted AFTER the kill must not keep it alive merely by pending, or subscription
      // churn turns temporal bookkeeping into connection-lifetime retention. A swept kill
      // also releases the retirement it implied, unless a queued or in-flight cancellation
      // still owns that id.
      conn.killedSubIds.entrySet().removeIf(kill -> {
        final long killSeq = kill.getValue();
        for (final var pending : conn.pendingSubscriptions.keySet()) {
          if (conn.attemptSeqs.getOrDefault(pending, Long.MAX_VALUE) < killSeq) {
            return false;
          }
        }
        final var subId = kill.getKey();
        if (!conn.pendingUnSubscriptions.containsKey(subId) && !conn.inFlightUnsubs.contains(subId)) {
          conn.retiredSubIds.remove(subId);
        }
        return true;
      });
    }
    // Cancellations flush FIRST, so the wire order matches intent order. The registries force
    // unsubscribe-before-resubscribe locally, but this pass used to send the subscribes first
    // — and an id-reusing server (Agave, measured 2026-08-09) then granted the successor the
    // predecessor's id and processed the reordered un-subscription against it: the frame meant
    // for the predecessor killed the successor, permanently and silently. With intent order on
    // the wire the server cancels the predecessor before the subscribe arrives, and grants the
    // successor an id with no cancellation in flight.
    flushPendingUnSubscriptions(conn, now);
    for (final var sub : conn.pendingSubscriptions.values()) {
      // The NEVER branch mirrors the connect throttle's: a maximal resend delay must not
      // suppress the INITIAL send, only the retries.
      final long lastAttempt = sub.lastAttempt();
      final boolean due = lastAttempt == Subscription.NEVER
          || now - lastAttempt > this.timings.subscriptionResendDelay();
      if (due && conn.inFlightSends.putIfAbsent(sub.msgId(), now) == null) {
        // The in-flight set is the second gate: a send pending past the resend delay is queued
        // behind a slow chain, not lost, and re-queuing it would drain as a duplicate subscribe
        // the server answers with a second, orphaned subscription.
        sub.setLastAttempt(now);
        sendSubscription(conn, sub);
      }
    }
    Throwable escalation = escalateUnanswered(conn, now);
    if (escalation == null) {
      escalation = escalateUnansweredPing(conn, now);
    }
    if (escalation == null) {
      // Unconditional otherwise: re-sends and flushes are this end writing, no evidence the
      // peer is there, so they must not suppress the ping that asks. Gating the ping on our
      // own writes made it unreachable on exactly the connections it exists to find.
      sendPing(conn);
      // A synchronously failed future records the failure while this pass still owns the lock.
      // Consume it now so callback delivery happens immediately after the caller unlocks.
      escalation = switch (conn.lifecycle) {
        case PING_FAILED -> claimFailedPing(conn);
        case ACTIVE, ESCALATED -> null;
      };
    }
    return escalation;
  }

  /// Chains a subscribe like [#queueText(Connection, String)], but recallable: a cancellation
  /// made while the frame waits in the chain consumes its tombstone here and nothing is sent.
  /// On transmission the [Connection#inFlightSends] stamp restarts, so the unanswered deadline
  /// measures the server's silence; until then the admission stamp guards a chain that never
  /// drains.
  private void sendSubscription(final Connection conn, final Subscription<?> sub) {
    // This attempt's wire ordinal: admission order is transmission order (the chain is FIFO),
    // and a RETRY is a new position on the wire under the same request id, so the latest
    // attempt's ordinal — never the id — is what same-id adjudication compares.
    conn.attemptSeqs.put(sub.msgId(), ++conn.nextWireSeq);
    conn.outboundTail = conn.outboundTail
        .exceptionally(_ -> null)
        .thenCompose(_ -> {
          lock.lock();
          try {
            if (conn.cancelledRequests.remove(sub.msgId()) != null) {
              conn.inFlightSends.remove(sub.msgId());
              conn.attemptSeqs.remove(sub.msgId());
              return CompletableFuture.completedFuture(null);
            }
          } finally {
            lock.unlock();
          }
          return sendText(conn, sub.msg()).whenComplete((_, ex) -> {
            if (ex != null) {
              // Only a FAILED send re-arms the retry: the frame never left, so re-sending is
              // safe. A successful send stays gated until the server answers — its response
              // is what removes the gate — because a duplicate of a merely slow request
              // creates a second, orphaned server subscription. The LAST-ATTEMPT pacing
              // stamp is kept, so a failing socket retries once per resend window rather
              // than hot-looping a growing chain of doomed frames on every cycle and inbound
              // frame; the wire ordinal is NOT — the frame never transmitted, so the ordinal
              // is dead evidence a stale entry would let pin obsolete kills, and the retry
              // takes a fresh one. A cancellation tombstone releases with the gate: no
              // confirmation can ever exist to consume it.
              lock.lock();
              try {
                conn.inFlightSends.remove(sub.msgId());
                conn.attemptSeqs.remove(sub.msgId());
                conn.cancelledRequests.remove(sub.msgId());
              } finally {
                lock.unlock();
              }
            } else {
              lock.lock();
              try {
                // replace, not put: the server may already have answered — completion
                // callbacks are not ordered against inbound frames — and the answer's removal
                // must not be resurrected into a stamp nothing will ever clear.
                conn.inFlightSends.replace(sub.msgId(), pacingMillis());
              } finally {
                lock.unlock();
              }
              try {
                sub.run();
              } catch (final RuntimeException onSubEx) {
                consumerThrew("onSub callback", onSubEx);
              }
            }
          });
        });
  }

  /// The unanswered-request deadline. Once any in-flight send has waited more than
  /// [#UNANSWERED_ESCALATION_FACTOR] resend windows, marks the connection ESCALATED (at most
  /// once) and returns the exception for the caller to deliver after unlocking, which retires
  /// the connection; null otherwise. An unanswered transmitted request is never re-sent on its
  /// own connection, so a server that never answers would leave that subscription missing while
  /// other traffic kept the connection looking healthy; replacing the connection hands it to
  /// the consumer's reconnect policy. Ages run from transmission for frames that reached the
  /// wire and from admission for frames the chain never delivered.
  private RuntimeException escalateUnanswered(final Connection conn, final long now) {
    if (conn.lifecycle == ConnectionLifecycle.ESCALATED || conn.inFlightSends.isEmpty()) {
      return null;
    }
    final long window = this.timings.subscriptionResendDelay();
    final long deadline = window > Long.MAX_VALUE / UNANSWERED_ESCALATION_FACTOR
        ? Long.MAX_VALUE
        : window * UNANSWERED_ESCALATION_FACTOR;
    for (final var entry : conn.inFlightSends.entrySet()) {
      if (now - entry.getValue() > deadline) {
        conn.lifecycle = ConnectionLifecycle.ESCALATED;
        final var unanswered = new IllegalStateException(
            "Request " + entry.getKey() + " to " + endpoint.getHost() + " has gone unanswered for "
                + (now - entry.getValue()) + "ms; replacing the connection rather than duplicating the request.");
        log.log(WARNING, unanswered.getMessage());
        return unanswered;
      }
    }
    return null;
  }

  /// Moves a PING_FAILED connection to ESCALATED and returns its recorded failure, which is
  /// non-null in that state, so the failure is delivered once.
  private static Throwable claimFailedPing(final Connection conn) {
    conn.lifecycle = ConnectionLifecycle.ESCALATED;
    return conn.pingFailure;
  }

  /// The Ping deadline: [Timings#pingDelay()] for the send future to settle, then another for
  /// peer contact. Past either, returns the escalation for the error seam; null otherwise.
  /// Contact during a pending send is remembered but does not excuse a send that never
  /// completes.
  private RuntimeException escalateUnansweredPing(final Connection conn, final long now) {
    final var transition = preparePingDeadlineTransition(conn, now);
    return transition == null ? null : claimPingDeadlineTransition(transition);
  }

  private PingDeadlineTransition preparePingDeadlineTransition(final Connection conn,
                                                               final long now) {
    final var probe = conn.pingProbe.get();
    if (probe == null) {
      return null;
    }

    final long sentAt = probe.sentAt.get();
    final boolean sendPending = sentAt == PingProbe.PENDING;
    final long startedAt = sendPending ? probe.admittedAt : sentAt;
    if (now - startedAt <= this.timings.pingDelay()) {
      return null;
    }
    return new PingDeadlineTransition(conn, probe, sendPending, startedAt, now);
  }

  /// Claims an observed Ping deadline and returns its escalation, or null when send completion
  /// (which starts the response window) or peer contact won since the observation. Visible for
  /// tests.
  RuntimeException claimPingDeadlineTransition(final PingDeadlineTransition transition) {
    final var probe = transition.probe;
    if (transition.sendPending
        && !probe.sentAt.compareAndSet(PingProbe.PENDING, PingProbe.TIMED_OUT)) {
      // Send completion won and starts a fresh response window. Re-evaluate on the next pass.
      return null;
    }
    if (!transition.conn.pingProbe.compareAndSet(probe, null)) {
      return null;
    }
    transition.conn.lifecycle = ConnectionLifecycle.ESCALATED;
    final long elapsed = transition.now - transition.startedAt;
    return new IllegalStateException(transition.sendPending
        ? "Ping send to " + endpoint.getHost() + " has not completed in " + elapsed
        + "ms; replacing the connection."
        : "Ping to " + endpoint.getHost() + " has gone unanswered for " + elapsed
        + "ms; replacing the connection.");
  }

  /// Test seam: snapshots the current connection's overdue Ping, or null if none is overdue, for
  /// [#claimPingDeadlineTransition(PingDeadlineTransition)]. Production holds [#lock] from
  /// observation through claim, so tests may interpose only what runs without it: send
  /// completion and peer contact.
  ///
  /// @throws NullPointerException if no connection is adopted
  PingDeadlineTransition preparePingDeadlineTransition() {
    lock.lock();
    try {
      return preparePingDeadlineTransition(this.connection, pacingMillis());
    } finally {
      lock.unlock();
    }
  }

  /// Test seam: records peer contact against the current Ping of the transition's connection,
  /// without the maintenance pass a real callback runs afterwards.
  void answerPingDeadlineTransition(final PingDeadlineTransition transition) {
    answerPingProbe(transition.conn);
  }

  private void sendPing(final Connection conn) {
    final long now = pacingMillis();
    // A ping serves two ends, and either alone is a reason to send one.
    //
    // Liveness: the peer has gone quiet and we want to know whether it is still there. Our own
    // writes are no answer to that, which is why re-sending a subscription must not suppress it.
    //
    // Keepalive: we have gone quiet. A connection can be busy inbound and silent outbound
    // indefinitely — a high traffic subscription with nothing left to subscribe — and an
    // intermediary or server that ages a connection on what it receives from us may drop it
    // while we are happily reading. That costs a reconnect and a full re-subscribe, against one
    // frame per window to avoid.
    //
    // The two run at different rates. A peer that has gone quiet is the urgent case and is asked
    // at the ping delay. Our own silence is not urgent at all — the peer is plainly there, we
    // are simply not saying anything — so the keepalive runs slower, sized to sit well inside
    // the ~60s idle timeout common to proxies and load balancers rather than to match a delay
    // chosen for detection. See Timings#keepAliveDelay.
    //
    // The outstanding probe is the liveness rate limit. A successful answer advances
    // lastPeerContact, a pending/send-success probe remains installed, and a failed send is
    // terminal. The keep-alive paces itself because sending a Ping advances lastOutboundFrame.
    if ((now - conn.lastPeerContact > this.timings.pingDelay())
        || now - conn.lastOutboundFrame.get() > this.timings.keepAliveDelay()) {
      if (conn.pingProbe.get() != null) {
        // The probe spans both the JDK send and the peer answer. Piling another over either
        // phase would turn one liveness question into a stream of unanswered control frames.
        return;
      }
      conn.lastOutboundFrame.set(now);
      final var pingMsg = String.valueOf(now);
      final var probe = new PingProbe(now);
      conn.pingProbe.set(probe);
      final CompletableFuture<WebSocket> pingFuture;
      try {
        pingFuture = conn.socket.sendPing(ByteBuffer.wrap(pingMsg.getBytes(ISO_8859_1)));
      } catch (final RuntimeException ex) {
        recordFailedPing(conn, probe, ex);
        return;
      }
      pingFuture.whenComplete(((_, throwable) -> {
        if (throwable != null) {
          recordFailedPing(conn, probe, throwable);
        } else {
          // Arm only after the JDK says the frame was sent. A Pong or any other peer frame may
          // race that completion; it clears this exact probe first, and the identity check then
          // prevents the late completion from resurrecting an already answered question.
          final long sent = pacingMillis();
          if (probe.sentAt.compareAndSet(PingProbe.PENDING, sent) && probe.answered) {
            conn.pingProbe.compareAndSet(probe, null);
          }
          log.log(DEBUG, "{0} to {1}.\n", pingMsg, endpoint.getHost());
        }
      }));
    }
  }

  /// Records a Ping send failure, thrown or from the future, in one locked transition: clears the
  /// probe and, on the current ACTIVE connection, publishes the failure as PING_FAILED and wakes
  /// the loop, so no pass can send a second Ping or prepare a duplicate delivery in between.
  /// Callbacks are delivered later, off the lock.
  private void recordFailedPing(final Connection conn,
                                final PingProbe probe,
                                final Throwable failure) {
    final boolean superseded;
    lock.lock();
    try {
      conn.pingProbe.compareAndSet(probe, null);
      superseded = conn != this.connection;
      if (!superseded
          && conn.lifecycle == ConnectionLifecycle.ACTIVE
          && conn.pingFailure == null) {
        conn.pingFailure = failure;
        conn.lifecycle = ConnectionLifecycle.PING_FAILED;
        checkSignalled = true;
        newSubscription.signal();
      }
    } finally {
      lock.unlock();
    }
    // Logging can invoke an arbitrary backend. Keep it outside the lifecycle lock just like the
    // user-facing failure callbacks, while retaining the diagnostic the superseded transport's
    // deliberately ignored completion used to provide.
    if (superseded) {
      log.log(DEBUG, "Dropped ping on a superseded socket.");
    }
  }

  /// Records peer contact against the outstanding Ping. After send completion this clears the
  /// probe; while the send is pending it only sets `answered`, which the completion callback
  /// honours, so a send that never settles keeps its own deadline.
  private static void answerPingProbe(final Connection conn) {
    final var probe = conn.pingProbe.get();
    if (probe == null) {
      return;
    }
    probe.answered = true;
    final long sentAt = probe.sentAt.get();
    if (sentAt != PingProbe.PENDING && sentAt != PingProbe.TIMED_OUT) {
      conn.pingProbe.compareAndSet(probe, null);
    }
  }

  /// Sends every queued un-subscription that is due and whose id has no cancellation in flight;
  /// the rest stay queued.
  private void flushPendingUnSubscriptions(final Connection conn, final long now) {
    final var iterator = conn.pendingUnSubscriptions.entrySet().iterator();
    while (iterator.hasNext()) {
      final var entry = iterator.next();
      if (entry.getValue().notBefore() > now) {
        // A rejected cancellation waits out its retry window here rather than being re-sent
        // by every pass — an immediately-refusing peer must not drive a wire-speed loop.
        continue;
      }
      if (conn.inFlightUnsubs.contains(entry.getKey())) {
        // Gate eligibility is decided BEFORE the queued intent is consumed: an earlier
        // cancellation for this id is still unanswered, and it preceded whatever this entry
        // was queued to cancel — discharging this obligation on ITS answer orphaned the
        // successor server-side. The entry stays queued; the ack that frees the gate signals
        // the loop.
        continue;
      }
      // Removed optimistically so a second flush inside the same window cannot double-send;
      // the sender re-queues on failure, since the frame is still owed.
      iterator.remove();
      sendUnSubscriptionLockHeld(conn, entry.getValue().unSubscribeMethod(), entry.getKey(), entry.getValue().fingerprint());
    }
  }

  @Override
  public CompletionStage<?> onPing(final WebSocket webSocket, final ByteBuffer message) {
    final var conn = connectionFor(webSocket);
    if (conn == null) {
      return null;
    }
    // A pong will be sent by the underlying WebSocket implementation — which is outbound
    // traffic, so it feeds the keep-alive clock too: without that, the very next check could
    // send a redundant keep-alive ping on a connection that answered a ping a moment ago.
    // The server pinging us is evidence it is there, exactly as a pong is, and for the same
    // reason it is not a message.
    final long now = pacingMillis();
    conn.lastPeerContact = now;
    answerPingProbe(conn);
    conn.lastOutboundFrame.set(now);
    // Not message.array(): a direct or read-only buffer throws, and a sliced one logs bytes
    // outside position..limit. Decoding a duplicate reads exactly the payload, touching nothing.
    log.log(DEBUG, () -> ISO_8859_1.decode(message.duplicate()).toString());
    lockAndHandlePendingSubscriptions();
    return null;
  }

  @Override
  public CompletionStage<?> onPong(final WebSocket webSocket, final ByteBuffer message) {
    final var conn = connectionFor(webSocket);
    if (conn == null) {
      return null;
    }
    // A pong is evidence the peer is there, which is all the ping was asking. It deliberately
    // does not count as a message: whether subscriptions are still served is a separate
    // question, and answering it with a pong would hide a connection whose subscriptions the
    // server has dropped.
    conn.lastPeerContact = pacingMillis();
    answerPingProbe(conn);
    lockAndHandlePendingSubscriptions();
    // Not message.array(): a direct or read-only buffer throws, and a sliced one logs bytes
    // outside position..limit. Decoding a duplicate reads exactly the payload, touching nothing.
    log.log(DEBUG, () -> ISO_8859_1.decode(message.duplicate()).toString());
    return null;
  }

  @Override
  public CompletionStage<?> onClose(final WebSocket webSocket, final int statusCode, final String reason) {
    // Retire by socket identity before policy: a custom handler which waits before reconnecting
    // must not leave the maintenance loop flushing or pinging a transport the peer has closed.
    // The wrapper and desired-subscription registries survive for that reconnect; the default
    // policy still closes the whole instance.
    final var retired = retireConnection(webSocket);
    if (retired != null) {
      // A failed Ping may have signalled the maintenance loop immediately before the peer's
      // terminal notice arrived. Retirement arbitrates those two delivery threads: the earlier
      // recorded send failure owns the one recovery policy and both callbacks it promises.
      final var pingFailure = takePingFailure(retired);
      if (pingFailure != null) {
        deliverRetiredPingFailure(pingFailure);
        return null;
      }
      if (onClose == null) {
        if (reason == null || reason.isBlank()) {
          log.log(WARNING, "WebSocket connection to {0} closed with code {1,number,integer}.",
              endpoint.getHost(), statusCode
          );
        } else {
          log.log(WARNING, "WebSocket connection to {0} closed with code {1,number,integer} because ''{2}''.",
              endpoint.getHost(), statusCode, reason
          );
        }
        this.close();
      } else {
        // The USER's handler runs off the lock: decided under it, delivered after it, so a
        // handler that blocks — or calls back into subscribe from another thread it waits on —
        // cannot deadlock the instance. What it does with the notice is its policy.
        onClose.accept(this, statusCode, reason);
      }
    }
    return null;
  }

  @Override
  public void onError(final WebSocket webSocket, final Throwable error) {
    // Same discipline as onClose: detach and release this transport first, then invoke policy
    // off-lock. Late callbacks from it are stale, while a custom reconnect preserves registries.
    final var retired = retireConnection(webSocket);
    if (retired != null) {
      final var pingFailure = takePingFailure(retired);
      if (pingFailure == null) {
        deliverRetiredError(error);
      } else {
        deliverRetiredPingFailure(pingFailure);
      }
    }
  }

  /// Applies the error policy (`onError`, or log and [#close()]) to a transport the caller has
  /// already retired.
  private void deliverRetiredError(final Throwable error) {
    if (onError == null) {
      log.log(ERROR, "Error on connection to " + endpoint.getHost(), error);
      this.close();
    } else {
      onError.accept(this, error);
    }
  }

  @Override
  public void close() {
    // The flag first — from here every entry guard rejects — then the teardown under the lock,
    // so it cannot interleave with a locked registry mutation that read closed() as false a
    // moment ago: the mutation completes, then this wipes, and nothing lands in a cleared map.
    // One accepted transient: a build which reserved its generation before this flag changed may
    // still enter or be inside public builder code. Its post-build identity check cancels the
    // returned future and aborts any ownerless socket; close never waits on collaborator code.
    // Do not turn the previous value into an early-return gate: a concurrent repeat close still
    // waits for the first caller's lock-held local teardown to commit, as it did with set(). The
    // return-valued transition only keeps that liveness-critical write out of close's otherwise
    // finite VoidMethodCall family.
    this.msgId.getAndSet(Long.MIN_VALUE);
    final Connection conn;
    final CompletableFuture<WebSocket> inFlight;
    final CompletableFuture<WebSocket> build;
    final Future<?> scheduled;
    lock.lock();
    try {
      inFlight = this.inFlightConnect;
      build = this.inFlightBuild;
      scheduled = this.scheduledConnect;
      // Nulled, not merely captured: a closed instance retains no completed transports or
      // scheduling handles.
      this.inFlightConnect = null;
      this.inFlightBuild = null;
      this.scheduledConnect = null;
      this.pendingBuilderStart = null;
      conn = this.connection;
      this.connection = null;
      this.accountSubs.clear();
      this.txLogSubs.clear();
      this.signatureSubs.clear();
      this.programSubs.clear();
      this.keyedProgramSubs.clear();
      this.slotSub = null;
      this.rootSub = null;
      this.genericSubs.clear();
      // Cleared so a closed instance retains no consumer references: handlers registered here
      // are the one subscriber set that survived close, pinning caller object graphs.
      this.exceptionSubs.clear();
      // wake the check loop so it observes closed() and returns its thread — an
      // injected executor is never shut down here, so this is all it gets
      checkSignalled = true;
      newSubscription.signal();
    } finally {
      lock.unlock();
    }
    // A build that completed with a socket nobody adopted — onOpen delayed, or never delivered
    // by a wrapping builder — is otherwise unreleased: cancellation is a no-op on a completed
    // future and the politeness below only knows the adopted connection. A build completing
    // AFTER the cancels below is aborted by its attempt's ownership hook, which observes
    // closed().
    final var settled = build != null && build.isDone() ? build : inFlight;
    if (settled != null && settled.state() == Future.State.SUCCESS) {
      final var unadopted = settled.resultNow();
      if (conn == null || conn.socket != unadopted) {
        unadopted.abort();
      }
    }
    // The local teardown is COMMITTED before any transport politeness is attempted: a
    // synchronous transport throw or a rejected watchdog schedule — the caller's injected
    // scheduler may already be shut down — used to skip the registry clears, the loop signal,
    // and the executor shutdown entirely. Politeness failing degrades to an immediate abort.
    if (conn != null) {
      final var webSocket = conn.socket;
      try {
        // The polite frame is gated on the OUTPUT being open; the abort watchdog is not gated
        // on it at all. Output and input close independently, and it is the input that retains
        // the transport, this listener, and the reassembly buffer — an output-closed socket
        // whose peer never finishes the handshake was forgotten here still fully retained.
        if (!webSocket.isOutputClosed()) {
          webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "close");
        }
        // abort() is idempotent and harmless after a completed close handshake. Construction
        // normalizes the JDK delayer and injected scheduler, so this one submission is pinned by
        // the deterministic scheduler seam without waiting on the default executor.
        closeWatchdogExecutor.execute(webSocket::abort);
      } catch (final RuntimeException ex) {
        log.log(WARNING, "Polite close failed; aborting the socket.", ex);
        webSocket.abort();
      }
    }
    // Caller notification comes LAST — local teardown committed, transport release armed —
    // so a completion action on a defensive copy observes a fully closed instance and can
    // block without stranding the upgrade or the adopted socket behind its own code.
    // Cancelling the BUILD future may feed the bridge synchronously, so a deferred wake and the
    // owned executor must already be released when that happens. A pending handshake that never
    // settles would otherwise retain its listener — and this instance — indefinitely, which is
    // why the builder-owned future is cancelled and not merely the bridge callers join.
    // A deferred attempt has no builder future yet, so its scheduled wake is the transport owner.
    // Release it before cancelling the bridge: bridge cancellation invokes caller completion
    // actions synchronously, and a blocking action must not strand an arbitrarily delayed wake or
    // postpone shutdown of the executor this instance owns.
    if (scheduled != null) {
      scheduled.cancel(false);
    }
    if (this.internalExecutor) {
      this.executorService.shutdown();
    }
    if (build != null) {
      build.cancel(true);
    }
    if (inFlight != null) {
      inFlight.cancel(true);
    }
  }
}
