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
import systems.comodal.jsoniter.ValueType;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
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

  /// Multiplies [Timings#subscriptionResendDelay()] into the unanswered-request deadline. A
  /// successfully sent subscribe is never re-sent on its own connection — JSON-RPC ids
  /// correlate, they do not deduplicate — so a server that simply never answers would leave
  /// that subscription silently nonexistent forever while other traffic kept the connection
  /// looking healthy. Past the deadline the connection is the thing replaced: aborted, with
  /// the error seam told why, so the consumer's reconnect policy — not a duplicate request —
  /// resolves it.
  static final int UNANSWERED_ESCALATION_FACTOR = 4;

  /// How long a polite close may wait for the peer's reply before the socket is aborted. JDK
  /// sendClose closes only the output; input — and with it the transport, this listener, and
  /// the reassembly buffer — stays retained until the peer answers, and a silent peer never
  /// does. Handshake-scale, mirroring the default connect timeout.
  static final long CLOSE_GRACE_MILLIS = 8_000;

  private final URI endpoint;
  private final SolanaAccounts solanaAccounts;
  private final Commitment defaultCommitment;
  private final Timings timings;
  private final int maxMessageLength;
  private final NanoClock clock;
  /// Origin for [#pacingMillis()]: nanoTime's absolute value is meaningless by specification —
  /// only differences count — so readings are normalized against construction time.
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
  private final Set<Consumer<RuntimeException>> exceptionSubs;
  /// Volatile, not atomic, for the same split as [#webSocket]: every write holds [#lock] —
  /// subscribe, unsubscribe, the rejection release, onOpen's re-queue, close's teardown — so
  /// CAS added nothing but ceremony, while the dispatch path reads on listener threads without
  /// the lock and needs the visibility.
  private volatile Subscription<ProcessedSlot> slotSub;
  private volatile Subscription<Long> rootSub;
  private final Map<String, Map<String, Subscription<?>>> genericSubs;

  private final ReentrantLock lock;
  private final Condition newSubscription;
  /// When a connection was last *attempted*, which is what [SolanaRpcWebsocket#connect()]'s
  /// throttle is specified against: "will delay the connection attempt if a previous attempt has
  /// already been made". Deliberately not advanced by traffic — a ping on a healthy connection is
  /// not an attempt, and letting it count meant a connection that lived for minutes still had its
  /// reconnect deferred as though it had just been retried.
  ///
  /// The current connection, or null between [#connect()] and the next adoption and after
  /// [#close()]. Volatile: written under [#lock], resolved at every callback entry without it.
  private volatile Connection connection;
  /// Guarded by [#lock] — plain on purpose, like [#outboundTail]: every read and write sits
  /// inside the lock since the throttle decision became decide-and-stamp atomic, and an atomic
  /// here would advertise lock-free access that does not exist.
  private long lastConnectAttempt;
  private final boolean internalExecutor;
  private final ScheduledExecutorService scheduler;
  /// The one permitted in-flight connection attempt. Guarded by [#lock]: while it is unsettled,
  /// connect() returns it rather than stacking a second handshake — two attempts racing meant
  /// the older one completing last displaced the newer live connection, and the JDK's
  /// WebSocket.Builder is not specified safe for concurrent buildAsync calls.
  private CompletableFuture<WebSocket> inFlightConnect;
  /// The scheduled handle of a deferred attempt, when the scheduler provides one. Guarded by
  /// [#lock]; close() cancels it so a long deferral does not retain a dead client to expiry.
  private Future<?> scheduledConnect;
  /// Which connection attempt is authorized to install its socket. Guarded by [#lock].
  /// Single-flight bounds concurrency while an attempt is UNSETTLED, but future completion and
  /// listener adoption are not one atomic event: a wrapping builder or a canceled future can
  /// let a new attempt start before a previous attempt's late onOpen arrives, and without the
  /// generation that stale onOpen would displace the newer connection.
  private long connectGeneration;
  /// Set under [#lock] by anything that signals [#newSubscription]; consumed by the check
  /// cycle. A Condition has no memory: a signal landing while the loop is mid-cycle was simply
  /// lost, and the next await parked the full delay — or, after close() with a very large check
  /// delay, forever, leaking the non-daemon loop thread.
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

  /// Test seam: the registrations this instance is still holding, summed across every registry.
  /// Exists so the close() teardown is assertable directly — after close, onOpen refuses to run,
  /// so no behavioural path can distinguish a cleared map from a retained one.
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
        + this.genericSubs.size();
    if (this.slotSub != null) {
      ++retained;
    }
    if (this.rootSub != null) {
      ++retained;
    }
    return retained;
  }

  /// The notification's own subscription id, member order free.
  private static BigInteger readSubscriptionId(final JsonIterator ji, final int paramsMark) {
    if (ji.skipUntil("subscription") == null) {
      ji.reset(paramsMark).skipUntil("subscription");
    }
    return ji.readBigInteger();
  }

  /// A singleton with a confirmed id must only be fed notifications carrying that id: after an
  /// unsubscribe/resubscribe, the predecessor's id still names the OLD server-side subscription,
  /// and its late notifications must not reach the successor consumer. An unconfirmed singleton
  /// (subId still null) accepts unknown ids — there is nothing to compare yet — which is why the
  /// caller checks [#retiredSubIds] first: a known-retired id is dropped regardless.
  private boolean staleSingletonId(final Connection conn, final Subscription<?> sub, final BigInteger subId) {
    if (conn.retiredSubIds.contains(subId)) {
      return true;
    }
    final var confirmed = sub.subId();
    return confirmed != null && !confirmed.equals(subId);
  }

  /// Positions the cursor at the top-level `params` member, wherever the server put it.
  ///
  /// JSON-RPC member order carries no meaning, and the intra-params parses already tolerate it
  /// with mark/reset fallbacks — but the method scan runs first and consumes everything before
  /// `method`, so a server emitting `params` first left the plain forward scan at end of input
  /// and the whole notification was dropped. A healthy subscription then starved silently while
  /// the liveness stamp, fed by the very frames being dropped, reported the connection fine.
  private static void skipToParams(final JsonIterator ji, final int offset) {
    if (ji.skipUntil("params") == null) {
      ji.reset(offset).skipUntil("params");
    }
  }

  /// One connection, whole: its socket, its parse state, and every registry that dies with it.
  ///
  /// This is the concurrency model. The JDK serializes listener callbacks per socket, not per
  /// listener, so callbacks from two socket generations can genuinely overlap — and any
  /// connection-scoped state kept on the instance had to be defended at every touch point,
  /// which four review rounds proved is a losing game. Here a callback resolves its Connection
  /// once, by socket identity, and then cannot reach a successor's state at all: a stale
  /// callback mutates its own dead connection, which nothing reads. Only the durable registries
  /// — the channel maps that express what the caller wants subscribed — stay on the instance,
  /// and commits into them re-check `conn == this.connection` under the lock.
  ///
  /// Field idioms inside a Connection follow the same access rules as the instance's:
  /// lock-guarded plain fields for state every mutator locks (`outboundTail`, `inFlightSends`,
  /// `cancelledRequests`, `pingInFlight`), volatile for the stamps this connection's own
  /// listener thread writes unlocked, atomics where completion threads stamp or CAS off-lock,
  /// concurrent maps where dispatch reads race locked mutation, and skip-lists where sorted
  /// iteration is the specified wire order.
  private static final class Connection {

    final WebSocket socket;

    // reassembly and parse state; only this connection's listener thread touches it
    char[] buffer = new char[4_096];
    int offset;
    final JsonIterator ji = JsonIterator.parse(new byte[0]);

    // requests and registrations that die with this connection. pendingUnSubscriptions maps
    // subId -> unsubscribe METHOD; the frame is minted at send time so its request id can be
    // registered for acknowledgement correlation. inFlightSends maps msgId -> pacing time the
    // send was queued, which is what the unanswered-request deadline measures.
    final Map<Long, Subscription<?>> pendingSubscriptions = new ConcurrentSkipListMap<>();
    final Map<BigInteger, String> pendingUnSubscriptions = new ConcurrentSkipListMap<>();
    final Map<BigInteger, Subscription<?>> subscriptionsBySubId = new ConcurrentSkipListMap<>();
    final Map<Long, Long> inFlightSends = new HashMap<>();
    final Map<Long, String> cancelledRequests = new HashMap<>();
    final Map<Long, UnsubRequest> pendingUnsubAcks = new HashMap<>();
    final Set<BigInteger> retiredSubIds = ConcurrentHashMap.newKeySet();
    /// The unanswered-request escalation fires at most once per connection.
    boolean escalated;

    // the outbound chain and pacing clocks, scoped so a displaced connection's late
    // completions stamp and roll back only their own
    CompletableFuture<WebSocket> outboundTail = CompletableFuture.completedFuture(null);
    CompletableFuture<WebSocket> pingInFlight;
    final AtomicLong lastOutboundFrame = new AtomicLong(0);
    final AtomicLong lastPing = new AtomicLong(0);
    volatile long lastPeerContact;
    volatile long lastMessageReceived;

    Connection(final WebSocket socket) {
      this.socket = socket;
    }
  }

  /// An un-subscription on the wire, awaiting the server's boolean acknowledgement.
  private record UnsubRequest(BigInteger subId, String unSubscribeMethod) {
  }

  /// Resolves the connection a callback belongs to — null when its socket is not the current
  /// one, which is the entire late-callback defense: no Connection, no state to corrupt.
  private Connection connectionFor(final WebSocket webSocket) {
    final var conn = this.connection;
    return conn != null && conn.socket == webSocket ? conn : null;
  }

  /// Monotonic milliseconds for every pacing decision — the reconnect throttle, the resend
  /// deadline, the liveness and keep-alive gates. [NanoClock]'s own javadoc reserves the
  /// monotonic reading for pacing, yet every gate read the wall clock: an NTP step backwards
  /// silently disabled ping detection, keep-alive and resend for the length of the step,
  /// defeating exactly the half-open detection this class exists to provide. The wall clock
  /// keeps one job here — [#lastMessageReceivedTimestamp()], which is epoch millis by contract.
  ///
  /// Positive from the first call, so the fields initialized to 0 or to [Subscription#NEVER]
  /// read as "before this instance existed" under plain subtraction.
  private long pacingMillis() {
    return ((clock.nanoTime() - pacingOrigin) / 1_000_000L) + 1L;
  }

  /// Whether a callback belongs to a socket this instance has already replaced.
  ///
  /// One listener serves every connection, but the state it writes — the liveness stamps, the
  /// pending-subscription re-send — describes whichever connection is current. A displaced
  /// socket acting on that state is not a stale no-op: it refills [#lastMessageReceived] after
  /// the new connection cleared it, suppresses the live socket's liveness ping, and re-sends
  /// the live connection's subscriptions down a socket nobody is reading.
  ///

  /// The deferred half of an attempt: the closed() check and the buildAsync are one locked
  /// step, so a close() cannot slip between them and let a dead instance initiate a handshake.
  private CompletableFuture<WebSocket> deferredBuild(final long generation, final AttemptListener attemptListener) {
    lock.lock();
    try {
      if (closed() || generation != this.connectGeneration) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("websocket closed or reconnected while a connect was deferred"));
      }
      this.lastConnectAttempt = pacingMillis();
      return this.webSocketBuilder.buildAsync(this.endpoint, attemptListener);
    } finally {
      lock.unlock();
    }
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
      return SolanaJsonRpcWebsocket.this.onText(webSocket, message, last);
    }

    @Override
    public CompletionStage<?> onBinary(final WebSocket webSocket, final ByteBuffer data, final boolean last) {
      // Delegated so production reaches the engine's rejection rather than the JDK default,
      // which silently discards the frame and requests another — a protocol violation
      // disappearing without trace.
      return SolanaJsonRpcWebsocket.this.onBinary(webSocket, data, last);
    }

    @Override
    public CompletionStage<?> onPing(final WebSocket webSocket, final ByteBuffer message) {
      return SolanaJsonRpcWebsocket.this.onPing(webSocket, message);
    }

    @Override
    public CompletionStage<?> onPong(final WebSocket webSocket, final ByteBuffer message) {
      return SolanaJsonRpcWebsocket.this.onPong(webSocket, message);
    }

    @Override
    public CompletionStage<?> onClose(final WebSocket webSocket, final int statusCode, final String reason) {
      return SolanaJsonRpcWebsocket.this.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(final WebSocket webSocket, final Throwable error) {
      SolanaJsonRpcWebsocket.this.onError(webSocket, error);
    }
  }

  /// Single-flight: while an attempt is unsettled every caller receives it, so callers inside
  /// one window cannot stack handshakes — two attempts racing meant the older one completing
  /// last displaced the newer live connection, and the JDK's WebSocket.Builder is not specified
  /// safe for concurrent use. Returns null once closed.
  @Override
  public CompletableFuture<?> connect() {
    if (closed()) {
      return null;
    }
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
      // Decide-and-stamp atomically: read-compute-set let two callers inside the same window
      // both see a stale stamp, both pass, and both launch handshakes.
      final long delay;
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
        this.lastConnectAttempt = now;
      }
      final long generation = ++this.connectGeneration;
      final var attemptListener = new AttemptListener(generation);
      final CompletableFuture<WebSocket> attempt;
      if (delay > 0) {
        if (scheduler == null) {
          final var delayedExecutor = CompletableFuture.delayedExecutor(delay, MILLISECONDS);
          attempt = CompletableFuture.supplyAsync(() -> deferredBuild(generation, attemptListener).join(), delayedExecutor);
        } else {
          final var connected = new CompletableFuture<WebSocket>();
          this.scheduledConnect = this.scheduler.schedule(() -> {
                try {
                  deferredBuild(generation, attemptListener).whenComplete((webSocket, ex) -> {
                    if (ex == null) {
                      connected.complete(webSocket);
                    } else {
                      connected.completeExceptionally(ex);
                    }
                  });
                } catch (final RuntimeException ex) {
                  connected.completeExceptionally(ex);
                }
              }, delay, MILLISECONDS
          );
          attempt = connected;
        }
      } else {
        attempt = this.webSocketBuilder.buildAsync(this.endpoint, attemptListener);
      }
      this.inFlightConnect = attempt;
      return attempt.copy();
    } finally {
      lock.unlock();
    }
  }


  @Override
  public void run() {
    try {
      final long sleepNanos = MILLISECONDS.toNanos(timings.subscriptionAndPingCheckDelay());
      while (!closed()) {
        checkCycle(sleepNanos);
      }
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

  /// One wait-and-check cycle of the loop above, package-private so same-package
  /// tests can drive the loop interior deterministically — an `awaitNanos <= 0`
  /// never parks. Extracted because the interior was otherwise reachable only by
  /// threads racing the test scheduler (see the ws triage README's check-loop
  /// entry for the flip-insurance history this replaced).
  void checkCycle(final long awaitNanos) throws InterruptedException {
    lock.lock();
    try {
      // Wake on a new subscription, on close(), or every check delay. The signalled flag is the
      // condition's memory: a signal landing while this loop was mid-cycle used to be lost, and
      // the next await parked the full delay — after close(), with a large check delay, forever.
      // closed() is re-checked under the lock for the same reason: close() signals under it, so
      // the check-then-park race is closed rather than narrowed.
      if (!checkSignalled && !closed()) {
        newSubscription.awaitNanos(awaitNanos);
      }
      checkSignalled = false;
      final var conn = this.connection;
      if (conn != null) {
        handlePendingSubscriptions(conn);
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void exceptionSubscribe(final Consumer<RuntimeException> consumer) {
    this.exceptionSubs.add(consumer);
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

  /// Installs a connection, if its attempt is still the authorized one.
  private void adopt(final WebSocket webSocket, final long generation) {
    if (closed()) {
      // close() landed between connect() and this handshake completing. Nothing may be rebuilt
      // on a closed instance, and the socket that just opened is nobody's: kill it rather than
      // leak a connection whose listener will ignore it.
      webSocket.abort();
      return;
    }
    final Connection conn;
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
      handlePendingSubscriptions(conn);
      // Still inside the lock: conn is current by construction — nothing can displace it while
      // we hold what displacement requires — so this is the moment to open the inbound tap.
      webSocket.request(Long.MAX_VALUE);
    } finally {
      lock.unlock();
    }
    // The handler runs off the lock. A concurrent close() or takeover may have retracted the
    // open between unlock and here; one volatile read keeps a retracted open from being
    // reported, without re-acquiring anything.
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
      final long msgId = this.msgId.incrementAndGet();
      final var msg = createSubscriptionMsg(msgId, channel, params);
      final var sub = Subscription.createSubscription(commitment, channel, key, msgId, msg, onSub, consumer);
      final var duplicate = subs.computeIfAbsent(sub.key(), _ -> new ConcurrentHashMap<>(4)).putIfAbsent(commitment, sub);
      if (duplicate == null) {
        // Registered durably above; queued for send only if a connection exists — during the
        // gap, adoption re-derives the pending set from the registries, so intent is never lost.
        final var conn = this.connection;
        if (conn != null) {
          conn.pendingSubscriptions.put(msgId, sub);
        }
        checkSignalled = true;
        newSubscription.signal();
        return true;
      } else {
        return false;
      }
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
      final long msgId = this.msgId.incrementAndGet();
      final var msg = createSubscriptionMsg(msgId, channel, params);
      final var sub = Subscription.createAccountSubscription(commitment, channel, publicKey, msgId, msg, onSub, consumer);
      final var duplicate = subs.computeIfAbsent(sub.key(), _ -> new ConcurrentHashMap<>(4)).putIfAbsent(commitment, sub);
      if (duplicate == null) {
        // Registered durably above; queued for send only if a connection exists — during the
        // gap, adoption re-derives the pending set from the registries, so intent is never lost.
        final var conn = this.connection;
        if (conn != null) {
          conn.pendingSubscriptions.put(msgId, sub);
        }
        checkSignalled = true;
        newSubscription.signal();
        return true;
      } else {
        return false;
      }
    } finally {
      lock.unlock();
    }
  }

  /// A consumer threw. Its exception must not read as a protocol failure — the frame parsed;
  /// the caller's code broke — must not abort the rest of this message's processing, and must
  /// still reach the exception subscribers, which is where consumers watch for their own bugs.
  private void consumerThrew(final String context, final RuntimeException ex) {
    log.log(WARNING, "Subscription consumer threw handling " + context + '.', ex);
    for (final var sub : this.exceptionSubs) {
      sub.accept(ex);
    }
  }

  /// Whether an error code blames the request itself, so re-sending the identical frame can
  /// only collect the identical answer. Everything else — resource refusals, internal errors —
  /// describes the server's condition and may pass on a later attempt.
  private static boolean isRequestDefect(final long code) {
    return code == JsonRpcException.INVALID_REQUEST
        || code == JsonRpcException.METHOD_NOT_FOUND
        || code == JsonRpcException.INVALID_PARAMS;
  }

  /// Frees the registry slot a subscription occupies, so its key can be subscribed again.
  ///
  /// Identity, not equality: [RootSubscription#equals] deliberately compares only commitment,
  /// channel and key, so a value-sensitive remove would delete an equal *successor* that has
  /// already retaken the slot. Each removal is a per-key compute, which also prunes the outer
  /// entry once its commitment map empties — the signature channel's keys are unbounded, one
  /// per transaction, so an unpruned outer key per completed signature grows forever.
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
      case program -> releaseCommitmentSlot(this.programSubs, sub);
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
    final var pending = conn.pendingSubscriptions.remove(sub.msgId());
    final var subId = sub.subId();
    if (subId != null) {
      conn.subscriptionsBySubId.remove(subId);
      // Retired, not merely removed: a late notification for this id must be dropped even
      // while a successor subscription is still unconfirmed.
      conn.retiredSubIds.add(subId);
      conn.pendingUnSubscriptions.put(subId, sub.unSubscribeMethod());
      // The un-subscription is transmitted by the next write cycle; on a quiet connection that
      // used to mean waiting out the whole check delay.
      checkSignalled = true;
      newSubscription.signal();
    } else if (pending != null || conn.inFlightSends.containsKey(sub.msgId())) {
      // Unconfirmed: the request is queued or already on the wire, and neither is recallable.
      // The tombstone converts its eventual confirmation into an immediate server unsubscribe;
      // discarding the confirmation as unknown left a server subscription nothing could cancel.
      conn.cancelledRequests.put(sub.msgId(), sub.unSubscribeMethod());
    }
  }

  private boolean removeDanglingSub(final String key,
                                    final Channel channel,
                                    final Commitment commitment) {
    final var conn = this.connection;
    if (conn == null) {
      return false;
    }
    final var iterator = conn.subscriptionsBySubId.entrySet().iterator();
    while (iterator.hasNext()) {
      final var activeSub = iterator.next().getValue();
      if (activeSub.channel() == channel && activeSub.commitment() == commitment && activeSub.key().equals(key)) {
        iterator.remove();
        this.queueUnsubscribe(activeSub);
        return true;
      }
    }
    return false;
  }

  private boolean queueUnsubscribe(final String key,
                                   final Channel channel,
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
        return removeDanglingSub(key, channel, commitment);
      } else {
        final var sub = commitmentSubs.remove(commitment);
        if (sub == null) {
          return removeDanglingSub(key, channel, commitment);
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
    return queueUnsubscribe(key.toBase58(), Channel.account, commitment, this.accountSubs);
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
    return queueUnsubscribe(key.toBase58(), Channel.logs, commitment, this.txLogSubs);
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
    return queueUnsubscribe(b58TxSig, Channel.signature, commitment, this.signatureSubs);
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
    return queueUnsubscribe(program.toBase58(), Channel.program, commitment, this.programSubs);
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
    }
    lock.lock();
    try {
      if (closed()) {
        return false;
      }
      final var subs = this.genericSubs.computeIfAbsent(notificationMethod, _ -> new ConcurrentHashMap<>());
      if (subs.containsKey(key)) {
        return false;
      }
      final long msgId = this.msgId.incrementAndGet();
      final var msg = String.format("""
          {"jsonrpc":"2.0","id":%d,"method":"%s","params":[%s]}""", msgId, subscribeMethod, paramsJson
      );
      final var sub = new GenericSubscription<>(unSubscribeMethod, notificationMethod, parser, key, msgId, msg, onSub, consumer);
      if (subs.putIfAbsent(key, sub) == null) {
        final var conn = this.connection;
        if (conn != null) {
          conn.pendingSubscriptions.put(msgId, sub);
        }
        checkSignalled = true;
        newSubscription.signal();
        return true;
      } else {
        return false;
      }
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
      // Fallback: a generic subscription whose method-map entry is already gone can still be
      // active by subId — e.g. re-registered under a raced map prune — and must remain
      // unsubscribable.
      final var conn = this.connection;
      final var iterator = conn == null
          ? java.util.Collections.<Map.Entry<BigInteger, Subscription<?>>>emptyIterator()
          : conn.subscriptionsBySubId.entrySet().iterator();
      while (iterator.hasNext()) {
        if (iterator.next().getValue() instanceof GenericSubscription<?> activeSub
            && activeSub.notificationMethod().equals(notificationMethod)
            && activeSub.key().equals(key)) {
          iterator.remove();
          this.queueUnsubscribe(activeSub);
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
    final var future = conn.socket.sendText(msg, true);
    log.log(DEBUG, "Writing text {0}", msg);
    future.whenComplete((_, ex) -> {
      if (ex != null) {
        if (conn != this.connection) {
          // Every queued send on a displaced socket fails as its chain drains against the
          // abort; reporting each one would storm the error handler with expected noise.
          log.log(DEBUG, "Dropped text on a superseded socket: {0}", msg);
        } else if (onSendTextError == null) {
          log.log(WARNING, String.format("Failed to sendText '%s' to %s.", msg, this.endpoint.getHost()), ex);
        } else {
          onSendTextError.accept(this, ex);
        }
      } else {
        // Stamped on completion rather than on submission, so a frame that never left is not a
        // write — the same rule the ping's rollback enforces on its clocks. The clock is the
        // connection's own, so a displaced connection's late success stamps only its dead self.
        conn.lastOutboundFrame.set(pacingMillis());
        log.log(DEBUG, "Sent text {0}", msg);
      }
    });
    return future;
  }

  /// Serializes outbound text frames: the JDK permits one outstanding text send per connection
  /// and fails the rest with `IllegalStateException("Send pending")`, so a reconnect's bulk
  /// re-subscribe fired as unchained sends lost every frame after the first — precisely when
  /// the connection was already degraded. Each send waits for its predecessor to settle, and a
  /// failed predecessor does not dam the chain.
  ///
  /// Callers hold [#lock], which is what guards the tail; the send itself runs on whatever
  /// thread settles the predecessor.
  private CompletableFuture<WebSocket> queueText(final Connection conn, final String msg) {
    final var next = conn.outboundTail
        .exceptionally(_ -> null)
        .thenCompose(_ -> sendText(conn, msg));
    conn.outboundTail = next;
    return next;
  }

  private void sendUnSubscription(final Connection conn,
                                  final String unSubscribeMethod,
                                  final BigInteger subId) {
    lock.lock();
    try {
      final var queuedMethod = conn.pendingUnSubscriptions.remove(subId);
      sendUnSubscriptionLockHeld(conn, queuedMethod == null ? unSubscribeMethod : queuedMethod, subId);
    } finally {
      lock.unlock();
    }
  }

  /// Mints the frame, registers its request id for acknowledgement correlation, and — because
  /// this frame is often the COMPENSATION for a subscription nothing else can cancel — re-queues
  /// the method on send failure rather than ignoring the future: one failed compensating frame
  /// used to orphan the server subscription permanently.
  private void sendUnSubscriptionLockHeld(final Connection conn,
                                          final String unSubscribeMethod,
                                          final BigInteger subId) {
    final long msgId = this.msgId.incrementAndGet();
    conn.pendingUnsubAcks.put(msgId, new UnsubRequest(subId, unSubscribeMethod));
    queueText(conn, createUnSubMsg(msgId, unSubscribeMethod, subId)).exceptionally(_ -> {
      lock.lock();
      try {
        conn.pendingUnsubAcks.remove(msgId);
        conn.pendingUnSubscriptions.putIfAbsent(subId, unSubscribeMethod);
      } finally {
        lock.unlock();
      }
      return null;
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
      sendUnSubscription(conn, channel.unSubscribe(), subId);
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
      sendUnSubscription(conn, channel.unSubscribe(), subId);
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
      // Prefer the method the registration itself supplied; the shared-prefix convention is
      // only the fallback for an id whose method was never registered here at all.
      final var subs = this.genericSubs.get(notificationMethod);
      final var registeredMethod = subs == null ? null
          : subs.values().stream().findFirst()
          .map(Subscription::unSubscribeMethod).orElse(null);
      sendUnSubscription(conn, registeredMethod != null
          ? registeredMethod
          : notificationMethod.replace("Notification", "Unsubscribe"), subId
      );
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
          // The response id names the request being rejected. Member order is free in JSON-RPC,
          // so scan for it from the top rather than assuming it trails the error — and a server
          // that could not read the request at all answers with "id":null, which must not
          // abandon this branch: that is the error class most likely to carry it.
          long requestId = -1;
          if (ji.reset(offset).skipUntil("id") != null && ji.whatIsNext() == ValueType.NUMBER) {
            requestId = ji.readLong();
          }
          boolean dispatchException = true;
          ji.reset(offset).skipUntil("error");
          // The OptionalLong parameter is retry-after seconds — the HTTP client fills it from
          // the retry-after header — and this path has no such hint. The request id stays local:
          // JsonRpcException has no field for it, and it must not masquerade as a backoff.
          final var exception = JsonRpcException.parseException(ji, OptionalLong.empty());
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
                // A rejected UN-subscription is settled by the rejection itself — the usual
                // cause is a double-cancel racing the server's own cleanup, which the old
                // message.startsWith("Invalid subscription id") heuristic guessed at; the
                // request id states it.
                final var rejectedUnsub = conn.pendingUnsubAcks.remove(requestId);
                if (rejectedUnsub != null) {
                  log.log(DEBUG, "Un-subscription {0} for {1} rejected by {2}; treating as settled.",
                      requestId, rejectedUnsub.subId(), endpoint.getHost());
                  dispatchException = false;
                }
                // ANY correlated error is a response, and a response releases the send gate:
                // for a transient refusal that is precisely what re-arms the retry the
                // classification below preserves.
                conn.inFlightSends.remove(requestId);
                if (isRequestDefect(exception.code())) {
                  conn.cancelledRequests.remove(requestId);
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
          // errors; a correlated un-subscription rejection is recognized by its request id.
          if (dispatchException && (message == null || !message.startsWith("Invalid subscription id"))) {
            for (final var sub : this.exceptionSubs) {
              sub.accept(exception);
            }
          }
        } else {
          final var sub = SubConfirmation.parse(ji.reset(offset));
          if (sub.boolResult() != null) {
            // An un-subscription acknowledgement. True retires the request; false means the id
            // was already gone server side — either way the request is settled, and neither is
            // an error worth a consumer's attention. These used to be skipped wholesale.
            lock.lock();
            try {
              if (conn == this.connection) {
                final var acked = conn.pendingUnsubAcks.remove(sub.msgId());
                if (acked != null && !sub.boolResult()) {
                  log.log(DEBUG, "Un-subscription {0} for {1} was already gone server side.",
                      sub.msgId(), acked.subId());
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
            lock.lock();
            try {
              if (conn == this.connection) {
                conn.inFlightSends.remove(sub.msgId());
                final var cancelledUnSubMethod = conn.cancelledRequests.remove(sub.msgId());
                if (cancelledUnSubMethod != null) {
                  // Unsubscribed before this confirmation arrived: the local record is already
                  // gone, so the just-created server subscription has nothing left to cancel it
                  // — except this, the moment its id is first known. The id is retired too: a
                  // successor for the same key may already be unconfirmed, and the cancelled
                  // subscription's notifications must not reach it in that window.
                  conn.retiredSubIds.add(sub.subId());
                  sendUnSubscription(conn, cancelledUnSubMethod, sub.subId());
                } else {
                  final var pendingSub = conn.pendingSubscriptions.remove(sub.msgId());
                  if (pendingSub != null) {
                    pendingSub.setSubId(sub.subId());
                    // Same-id reuse, defensively: a server may return the SAME id for an
                    // identical subscription (Agave coalesces duplicates — solana-labs#18943,
                    // unverified against current Agave, guarded regardless). A queued
                    // un-subscription for this id would cancel the subscription we were just
                    // granted, so the cancellation is cancelled; and a retired id that comes
                    // back is retired no longer.
                    conn.pendingUnSubscriptions.remove(sub.subId());
                    conn.retiredSubIds.remove(sub.subId());
                    final var previous = conn.subscriptionsBySubId.put(sub.subId(), pendingSub);
                    if (previous != null && previous != pendingSub) {
                      log.log(WARNING, "Subscription id {0} was reassigned by {1}; displacing {2} {3}.",
                          sub.subId(), endpoint.getHost(), previous.channel(), previous.key());
                    }
                  }
                }
              }
            } finally {
              lock.unlock();
            }
          } else if (sub.jsonRpcException() != null) {
            if (sub.jsonRpcException().code() != -32602) {  // May happen due to stale/duplicate un-subscription requests.
              log.log(WARNING, "Unexpected json rpc error.", sub.jsonRpcException());
            }
          }
        }
      } else {
        final int methodMark = ji.mark();
        final var channel = ji.applyChars(METHOD_PARSER);
        if (channel == null) {
          final var notificationMethod = ji.reset(methodMark).readString();
          if (this.genericSubs.containsKey(notificationMethod)) {
            skipToParams(ji, offset);
            final int paramsMark = ji.mark();
            ji.skipUntil("result");
            publishGeneric(conn, notificationMethod, ji, paramsMark);
          }
        } else {
          skipToParams(ji, offset);
          if (channel == Channel.slot) {
            final var slotSub = this.slotSub;
            final int slotParamsMark = ji.mark();
            final var subId = readSubscriptionId(ji, slotParamsMark);
            if (slotSub == null || staleSingletonId(conn, slotSub, subId)) {
              // No singleton, or a notification for a predecessor's id after an
              // unsubscribe/resubscribe: either way it must not reach the current consumer.
              sendUnSubscription(conn, channel.unSubscribe(), subId);
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
            final var subId = readSubscriptionId(ji, rootParamsMark);
            if (rootSub == null || staleSingletonId(conn, rootSub, subId)) {
              sendUnSubscription(conn, channel.unSubscribe(), subId);
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
                      sendUnSubscription(conn, Channel.signature.unSubscribe(), subId);
                    }
                  } else if (registered.channel() != Channel.signature) {
                    // A signatureNotification naming another channel's subId is malformed or
                    // hostile; acting on it would terminally remove that channel's mapping.
                    log.log(WARNING, "Dropping signature notification whose subscription {0} belongs to {1}.",
                        subId, registered.channel()
                    );
                  } else if (registered != null) {
                    @SuppressWarnings("unchecked") final var sub = (Subscription<TxResult>) registered;
                    // Detached before delivery: the server has already cancelled its side, so
                    // the terminal state must not depend on the consumer returning normally —
                    // a throwing consumer previously left the completed signature registered,
                    // replaying it every reconnect and blocking resubscription of its key.
                    if (!"receivedSignature".equals(result.value())) {
                      lock.lock();
                      try {
                        conn.subscriptionsBySubId.remove(subId);
                        releaseCommitmentSlot(this.signatureSubs, sub);
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
      for (final var sub : this.exceptionSubs) {
        sub.accept(ex);
      }
    }
  }

  private void ensureCapacity(final Connection conn, final int minCapacity) {
    if (minCapacity - conn.buffer.length > 0) {
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
    // does: drop the partial message, abort the connection, and let onError decide
    // between the default log-and-close and the caller's reconnect policy.
    if (len > this.maxMessageLength - conn.offset) {
      final long total = (long) conn.offset + len;
      conn.offset = 0;
      webSocket.abort();
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
    throw new UnsupportedOperationException();
  }

  private void lockAndHandlePendingSubscriptions(final Connection conn) {
    lock.lock();
    try {
      handlePendingSubscriptions(conn);
    } finally {
      lock.unlock();
    }
  }

  private void handlePendingSubscriptions(final Connection conn) {
    if (conn != this.connection) {
      // The caller resolved its connection before pausing; by the time it runs, a takeover may
      // have happened, and the current connection's sends are the check cycle's to make.
      return;
    }
    final long now = pacingMillis();
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
        queueText(conn, sub.msg()).whenComplete((_, ex) -> {
          if (ex != null) {
            // Only a FAILED send re-arms the retry: the frame never left, so re-sending is
            // safe. A successful send stays gated until the server answers — its response is
            // what removes the gate — because a duplicate of a merely slow request creates a
            // second, orphaned server subscription. The attempt stamp is kept, so a failing
            // socket retries once per resend window rather than hot-looping a growing chain
            // of doomed frames on every cycle and inbound frame.
            lock.lock();
            try {
              conn.inFlightSends.remove(sub.msgId());
            } finally {
              lock.unlock();
            }
          } else {
            try {
              sub.run();
            } catch (final RuntimeException onSubEx) {
              consumerThrew("onSub callback", onSubEx);
            }
          }
        });
      }
    }
    // Both unconditional. Retrying a subscription, or flushing an un-subscription, is this end
    // writing; it is no evidence the peer is still there, so it must not suppress the ping that
    // asks. Gating the ping on our own writes made it unreachable on exactly the connections it
    // exists to find: nothing is ever confirmed on a half open socket, so its pending
    // subscriptions resend every reConnectDelay forever and the ping was never due.
    escalateUnanswered(conn, now);
    flushPendingUnSubscriptions(conn);
    sendPing(conn);
  }

  /// The unanswered-request deadline. Send-once means a successfully transmitted subscribe is
  /// never duplicated on its own connection, so a server that simply never answers one would
  /// leave that subscription silently nonexistent forever — while other traffic kept the
  /// connection looking healthy to every liveness gate. Past
  /// [#UNANSWERED_ESCALATION_FACTOR] resend windows, the CONNECTION is what gets replaced:
  /// aborted, with the error seam told why, so the consumer's reconnect policy resolves it the
  /// way it resolves any other dead transport. Escalation fires at most once per connection.
  private void escalateUnanswered(final Connection conn, final long now) {
    if (conn.escalated || conn.inFlightSends.isEmpty()) {
      return;
    }
    final long window = this.timings.subscriptionResendDelay();
    final long deadline = window > Long.MAX_VALUE / UNANSWERED_ESCALATION_FACTOR
        ? Long.MAX_VALUE
        : window * UNANSWERED_ESCALATION_FACTOR;
    for (final var entry : conn.inFlightSends.entrySet()) {
      if (now - entry.getValue() > deadline) {
        conn.escalated = true;
        final var unanswered = new IllegalStateException(
            "Request " + entry.getKey() + " to " + endpoint.getHost() + " has gone unanswered for "
                + (now - entry.getValue()) + "ms; replacing the connection rather than duplicating the request.");
        log.log(WARNING, unanswered.getMessage());
        conn.socket.abort();
        onError(conn.socket, unanswered);
        return;
      }
    }
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
    // Only the liveness reason needs a rate limit. A silent peer never advances lastPeerContact,
    // so without one it would be asked on every check cycle; lastPing is what paces that. The
    // keep-alive paces itself, because sending the ping is a write and so moves the very stamp
    // it is measured against — which is also why a keep-alive shorter than the ping delay is
    // honoured rather than silently clamped to it.
    if ((now - conn.lastPeerContact > this.timings.pingDelay()
        && now - conn.lastPing.get() > this.timings.pingDelay())
        || now - conn.lastOutboundFrame.get() > this.timings.keepAliveDelay()) {
      if (conn.pingInFlight != null && !conn.pingInFlight.isDone()) {
        // The JDK permits one outstanding control-frame send; pinging over a pending ping fails
        // every cycle with a misleading "pending" error while the rollback re-arms the gate.
        return;
      }
      final long previousPing = conn.lastPing.getAndSet(now);
      final long previousWrite = conn.lastOutboundFrame.getAndSet(now);
      final var pingMsg = String.valueOf(now);
      final var pingFuture = conn.socket.sendPing(ByteBuffer.wrap(pingMsg.getBytes(ISO_8859_1)));
      conn.pingInFlight = pingFuture;
      pingFuture.whenComplete(((_, throwable) -> {
        if (throwable != null) {
          // Roll both back: a ping that never left is neither a write nor an ask, so the next
          // check retries instead of waiting out the delay. That is what lets a run of failures
          // accumulate quickly enough to be worth acting on.
          conn.lastOutboundFrame.compareAndSet(now, previousWrite);
          conn.lastPing.compareAndSet(now, previousPing);
          if (this.onPingError == null) {
            log.log(WARNING, String.format("Failed to ping %d to %s.", now, this.endpoint.getHost()), throwable);
          } else {
            this.onPingError.accept(this, throwable);
          }
        } else {
          log.log(DEBUG, "{0} to {1}.\n", pingMsg, endpoint.getHost());
        }
      }));
    }
  }

  /// Flushes every queued un-subscription.
  ///
  /// Returns nothing: the count only ever existed to tell the caller whether to skip the ping,
  /// and the ping no longer depends on what this end wrote.
  private void flushPendingUnSubscriptions(final Connection conn) {
    final var iterator = conn.pendingUnSubscriptions.entrySet().iterator();
    while (iterator.hasNext()) {
      final var entry = iterator.next();
      // Removed optimistically so a second flush inside the same window cannot double-send;
      // the sender re-queues on failure, since the frame is still owed.
      iterator.remove();
      sendUnSubscriptionLockHeld(conn, entry.getValue(), entry.getKey());
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
    conn.lastOutboundFrame.set(now);
    // Not message.array(): a direct or read-only buffer throws, and a sliced one logs bytes
    // outside position..limit. Decoding a duplicate reads exactly the payload, touching nothing.
    log.log(DEBUG, () -> ISO_8859_1.decode(message.duplicate()).toString());
    lockAndHandlePendingSubscriptions(conn);
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
    lockAndHandlePendingSubscriptions(conn);
    // Not message.array(): a direct or read-only buffer throws, and a sliced one logs bytes
    // outside position..limit. Decoding a duplicate reads exactly the payload, touching nothing.
    log.log(DEBUG, () -> ISO_8859_1.decode(message.duplicate()).toString());
    return null;
  }

  @Override
  public CompletionStage<?> onClose(final WebSocket webSocket, final int statusCode, final String reason) {
    // Decided AND acted on under the lock. An entry-only check is a TOCTOU: callbacks from
    // different sockets sharing this listener may run concurrently, so an old socket's close
    // could pass the check, pause, let onOpen install its successor, then resume and tear the
    // successor down. onOpen displaces under this lock, so holding it across the action is
    // what makes the check a guarantee. closed() joins it: after an explicit close(), the
    // peer's reciprocal close frame is bookkeeping, not news.
    final boolean act;
    lock.lock();
    try {
      act = connectionFor(webSocket) != null;
      if (act && onClose == null) {
        // The default teardown stays under the lock: it is internal, and releasing first would
        // reopen the takeover race for the automatic close.
        return onCloseLocked(statusCode, reason);
      }
    } finally {
      lock.unlock();
    }
    if (act) {
      // The USER's handler runs off the lock: decided under it, delivered after it, so a
      // handler that blocks — or calls back into subscribe from another thread it waits on —
      // cannot deadlock the instance. What it does with the notice is its policy.
      onClose.accept(this, statusCode, reason);
    }
    return null;
  }

  private CompletionStage<?> onCloseLocked(final int statusCode, final String reason) {
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
      onClose.accept(this, statusCode, reason);
    }
    return null;
  }

  @Override
  public void onError(final WebSocket webSocket, final Throwable error) {
    // Same discipline as onClose: the handler is told the instance failed, not which socket
    // did, so the decision and the action share the lock.
    final boolean act;
    lock.lock();
    try {
      act = connectionFor(webSocket) != null;
      if (act && onError == null) {
        log.log(ERROR, "Error on connection to " + endpoint.getHost(), error);
        this.close();
        return;
      }
    } finally {
      lock.unlock();
    }
    if (act) {
      onError.accept(this, error);
    }
  }

  @Override
  public void close() {
    // The flag first — from here every entry guard rejects — then the teardown under the lock,
    // so it cannot interleave with a locked registry mutation that read closed() as false a
    // moment ago: the mutation completes, then this wipes, and nothing lands in a cleared map.
    this.msgId.set(Long.MIN_VALUE);
    lock.lock();
    try {
      // The attempt and its schedule are close()'s to reap: a deferred attempt used to retain
      // the closed client until its delay expired, and its waiters until the closed-check
      // failed it. Cancelling cannot stop a buildAsync already running — adoption's
      // closed-check aborts that socket — but it fails waiters promptly and unschedules what
      // has not started.
      if (this.inFlightConnect != null) {
        this.inFlightConnect.cancel(true);
      }
      if (this.scheduledConnect != null) {
        this.scheduledConnect.cancel(false);
      }
      final var conn = this.connection;
      final var webSocket = conn == null ? null : conn.socket;
      if (webSocket != null) {
        // The polite frame is gated on the OUTPUT being open; the abort watchdog is not gated
        // on it at all. Output and input close independently, and it is the input that retains
        // the transport, this listener, and the reassembly buffer — an output-closed socket
        // whose peer never finishes the handshake was forgotten here still fully retained.
        if (!webSocket.isOutputClosed()) {
          webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "close");
        }
        // abort() is idempotent and harmless after a completed close handshake.
        if (scheduler == null) {
          CompletableFuture.delayedExecutor(CLOSE_GRACE_MILLIS, MILLISECONDS).execute(webSocket::abort);
        } else {
          scheduler.schedule(webSocket::abort, CLOSE_GRACE_MILLIS, MILLISECONDS);
        }
      }
      this.connection = null;
      this.accountSubs.clear();
      this.txLogSubs.clear();
      this.signatureSubs.clear();
      this.programSubs.clear();
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
    if (this.internalExecutor) {
      this.executorService.shutdown();
    }
  }
}
