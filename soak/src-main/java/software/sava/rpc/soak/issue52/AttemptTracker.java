package software.sava.rpc.soak.issue52;

import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.rpc.soak.issue52.RetirementRecord.CallbackRoute;
import software.sava.rpc.soak.issue52.RetirementRecord.CauseClass;
import software.sava.rpc.soak.issue52.RetirementRecord.FutureRoute;
import software.sava.rpc.soak.issue52.RetirementRecord.NextAttemptOutcome;
import software.sava.rpc.soak.issue52.RetirementRecord.OpenOrder;
import software.sava.rpc.soak.issue52.RetirementRecord.PingRoute;
import software.sava.rpc.soak.issue52.RetirementRecord.ThreadKind;
import software.sava.rpc.soak.issue52.RetirementRecord.Verdict;
import software.sava.services.core.remote.call.Backoff;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static software.sava.rpc.soak.issue52.DeliveryFrames.FrameKind;

/// One per engine: the issue #52 capture layer around one `SolanaRpcWebsocket` and, when a
/// manager drives it, around that manager's `Backoff` and log lines (DESIGN.md §10,
/// attribution.md). It hands records outward through the two sinks and never imports the
/// harness's core packages, which are written concurrently.
///
/// Three seams, all public collaborator points sava or ravina already offer:
/// - [#wrap(HttpClient)] is the `WebSocket.Builder` the prototype is given; it mints the
///   attempt ordinal, wraps the listener and socket, and returns the future sava cancels.
/// - [#backoff(Backoff)] is the manager's reconnect policy; each call is one accepted claim.
/// - [#instrument(SolanaRpcWebsocket.Builder)] installs the prototype handlers that consume
///   the retirement sub-frame the engine's abort pushed on this same thread, composed AFTER
///   any existing handler (the manager composes its own BEFORE these, via `andThen`).
///
/// A row is emitted once, at delivery, with the next-attempt columns as known at that moment
/// (a successor built inside the gap is already known; otherwise PENDING). Per-attempt
/// outcomes keep resolving afterwards in [#attempt(long)], which the report joins on.
public final class AttemptTracker {

  public static final long NO_INJECTION = -1;
  private static final AtomicLong RETIREMENT_IDS = new AtomicLong();
  private static final AtomicLong CLAIM_IDS = new AtomicLong();
  private static final StackWalker WALKER = StackWalker.getInstance();
  private static final int MAX_ATTEMPTS_RETAINED = 256;
  private static final int MAX_ABORT_LOG = 64;
  private static final int MAX_ENGINE_FRAMES = 12;
  private static final String ATTEMPT_LISTENER_CLASS = DeliveryFrames.ENGINE_CLASS + "$AttemptListener";

  public static AttemptTracker forEngine(final String engine,
                                         final Consumer<RetirementRecord> retirementSink,
                                         final Consumer<ClaimRecord> claimSink,
                                         final boolean forced,
                                         final boolean liveMode) {
    return new AttemptTracker(engine, retirementSink, claimSink, forced, liveMode);
  }

  private final String engine;
  private final Consumer<RetirementRecord> retirementSink;
  private final Consumer<ClaimRecord> claimSink;
  private final boolean forced;
  private final boolean liveMode;
  private volatile String forcedReason;
  private volatile boolean deferBuildCompletion;
  private volatile Injection injection;
  private volatile boolean blockAttemptFailed;
  private volatile Predicate<Thread> checkLoopThread = t -> t.getName().startsWith("pool-");
  private volatile RecordingBackoff backoff;
  private volatile Consumer<SolanaRpcWebsocket> afterRetirement;
  private final AtomicLong ordinals = new AtomicLong();
  private final ConcurrentHashMap<Long, Attempt> attempts = new ConcurrentHashMap<>();
  private final Object buildMonitor = new Object();
  private volatile long lastErrorCount;
  private volatile long lastClaimNanos = -1;
  private final AbortEntry[] abortLog = new AbortEntry[MAX_ABORT_LOG];
  private final AtomicLong abortLogCount = new AtomicLong();
  final Stats stats = new Stats();

  private AttemptTracker(final String engine,
                         final Consumer<RetirementRecord> retirementSink,
                         final Consumer<ClaimRecord> claimSink,
                         final boolean forced,
                         final boolean liveMode) {
    this.engine = engine;
    this.retirementSink = retirementSink;
    this.claimSink = claimSink;
    this.forced = forced;
    this.liveMode = liveMode;
    this.forcedReason = forced ? "FORCED" : "NONE";
    // Forced runs need the #52 window: hold the build future until onOpen has returned.
    this.deferBuildCompletion = forced;
  }

  // -------------------------------------------------------------------------------- seams

  public WebSocket.Builder wrap(final HttpClient httpClient) {
    return wrap(httpClient::newWebSocketBuilder);
  }

  /// Self-test seam: the fake builder supplies itself, so the harness wraps it exactly as it
  /// wraps `httpClient.newWebSocketBuilder()`.
  public WebSocket.Builder wrap(final Supplier<WebSocket.Builder> delegateFactory) {
    return new HarnessWebSocketBuilder(this, delegateFactory);
  }

  public Backoff backoff(final Backoff delegate) {
    final var recording = new RecordingBackoff(this, delegate);
    this.backoff = recording;
    return recording;
  }

  public SolanaRpcWebsocket.Builder instrument(final SolanaRpcWebsocket.Builder prototype) {
    return instrument(prototype, null);
  }

  /// `afterRetirement` is the BARE engine's reconnect policy: it runs after the row's
  /// attribution has been fixed, so a synchronous `connect()` inside it cannot contaminate the
  /// record it follows (a nested delivery then attributes to its own, inner frame).
  public SolanaRpcWebsocket.Builder instrument(final SolanaRpcWebsocket.Builder prototype,
                                               final Consumer<SolanaRpcWebsocket> afterRetirement) {
    this.afterRetirement = afterRetirement;
    final Consumer<SolanaRpcWebsocket> onOpen = this::onOpen;
    final var existingOnOpen = prototype.onOpen();
    var builder = prototype.onOpen(existingOnOpen == null ? onOpen : existingOnOpen.andThen(onOpen));
    final SolanaRpcWebsocket.OnClose onClose = this::onClose;
    final var existingOnClose = builder.onClose();
    builder = builder.onClose(existingOnClose == null ? onClose : existingOnClose.andThen(onClose));
    final BiConsumer<SolanaRpcWebsocket, Throwable> onError = this::onError;
    final var existingOnError = builder.onError();
    builder = builder.onError(existingOnError == null ? onError : existingOnError.andThen(onError));
    final BiConsumer<SolanaRpcWebsocket, Throwable> onPingError = this::onPingError;
    final var existingOnPingError = builder.onPingError();
    builder = builder.onPingError(existingOnPingError == null ? onPingError : existingOnPingError.andThen(onPingError));
    return builder;
  }

  // ------------------------------------------------------------------------ configuration

  public String engine() {
    return engine;
  }

  public boolean forced() {
    return forced;
  }

  public boolean liveMode() {
    return liveMode;
  }

  public void forcedReason(final String reason) {
    this.forcedReason = reason;
  }

  /// Forced-mode hook: `HarnessSocket.request(long)` injects a retirement through the harness
  /// listener, inside `adopt` and before the manager's `markOpen`, for every attempt this arming
  /// selects.
  ///
  /// The arming is a *series* rather than a single ordinal because re-arming from the workload's
  /// retirement callback is a race the injector loses: under a constant-zero reconnect the
  /// successor attempt is built and adopted before that callback returns, so an arm computed
  /// from the ordinal current at callback time lands behind the attempt it meant to select and
  /// nothing matches it afterwards. A series is decided once, on the arming thread, and every
  /// later attempt is measured against it, so no reconnect can outrun it.
  ///
  /// Selected are the ordinals at or after the one following the ordinal current here, stepping
  /// by `period`, until `maxInjections` have fired. Reaching the ceiling is counted
  /// ([Stats#injectionCapReached]): an injector that stopped at its budget and one that was never
  /// armed otherwise leave the same empty evidence behind.
  public void injectRetirementEvery(final int period, final long maxInjections) {
    if (period < 1) {
      throw new IllegalArgumentException("injection period must be positive: " + period);
    }
    arm(new Injection(ordinals.get() + 1, period, maxInjections));
  }

  /// One-shot arming at exactly `ordinal`; [#NO_INJECTION] disarms.
  public void injectRetirementAt(final long ordinal) {
    // A step no reachable ordinal can complete selects the first ordinal and nothing else.
    arm(ordinal == NO_INJECTION ? null : new Injection(ordinal, Long.MAX_VALUE, 1));
  }

  /// Every injected retirement is forced evidence — its row carries `forced` and the
  /// `forcedReason`, and `RetirementDetector` refuses the revisit trigger on a forced row. An
  /// arming on an unforced engine would emit rows that claim to be natural, so it is refused
  /// here rather than discovered in the sheet.
  private void arm(final Injection armed) {
    if (armed != null && !forced) {
      throw new IllegalStateException("injected retirements are forced evidence; engine " + engine + " is not forced");
    }
    this.injection = armed;
  }

  /// The injection site's question, asked once per adopted socket: is this attempt's ordinal in
  /// the armed series, and is there budget left for it. Public so the self-test can assert the
  /// arming arithmetic without a transport.
  public boolean claimInjection(final long ordinal) {
    final var armed = injection;
    return armed != null && armed.claim(ordinal, stats);
  }

  /// One arming: an arithmetic series of attempt ordinals and a budget, both fixed at the moment
  /// the arm is taken, so an ordinal advancing concurrently cannot move the target.
  private static final class Injection {

    private final long first;
    private final long period;
    private final long max;
    private final AtomicLong fired = new AtomicLong();
    private final AtomicBoolean capCounted = new AtomicBoolean();

    Injection(final long first, final long period, final long max) {
      this.first = first;
      this.period = period;
      this.max = max;
    }

    boolean claim(final long ordinal, final Stats stats) {
      if (ordinal < first || (ordinal - first) % period != 0) {
        return false;
      }
      for (long done = fired.get(); done < max; done = fired.get()) {
        if (fired.compareAndSet(done, done + 1)) {
          return true;
        }
      }
      if (capCounted.compareAndSet(false, true)) {
        stats.injectionCapReached.increment();
      }
      return false;
    }
  }

  public void blockAttemptFailedUntilSuccessor(final boolean block) {
    this.blockAttemptFailed = block;
  }

  boolean blockAttemptFailedUntilSuccessor() {
    return blockAttemptFailed;
  }

  public void deferBuildCompletionUntilOnOpen(final boolean defer) {
    this.deferBuildCompletion = defer;
  }

  boolean deferBuildCompletionUntilOnOpen() {
    return deferBuildCompletion;
  }

  /// The engine's own loop thread is `pool-N-thread-1`; a workload that injects a named
  /// executor through the package-private seam registers its own predicate here.
  public void checkLoopThread(final Predicate<Thread> predicate) {
    this.checkLoopThread = predicate;
  }

  public BackoffClass backoffClass() {
    final var b = backoff;
    return b == null ? null : b.backoffClass;
  }

  public long currentOrdinal() {
    return ordinals.get();
  }

  public long lastErrorCount() {
    return lastErrorCount;
  }

  public Stats stats() {
    return stats;
  }

  // ---------------------------------------------------------------------------- attempts

  Attempt newAttempt(final URI uri, final WebSocket.Listener savaListener, final long nowNanos) {
    final long ordinal = ordinals.incrementAndGet();
    final var attempt = new Attempt(this, ordinal, uri, savaListener, Thread.currentThread(), nowNanos);
    attempts.put(ordinal, attempt);
    final long evictBelow = ordinal - MAX_ATTEMPTS_RETAINED;
    if (evictBelow > 0) {
      attempts.remove(evictBelow);
    }
    stats.attempts.increment();
    synchronized (buildMonitor) {
      buildMonitor.notifyAll();
    }
    return attempt;
  }

  void buildAsyncReturned(final Attempt attempt) {
    attempt.buildReturnedNanos = System.nanoTime();
    synchronized (buildMonitor) {
      buildMonitor.notifyAll();
    }
  }

  /// Forced mode: holds the caller until a `buildAsync` with an ordinal above `ordinal` has
  /// been observed, or the bound expires. Never called on the hot path.
  boolean awaitBuildAfter(final long ordinal, final long timeoutNanos) {
    final long deadline = System.nanoTime() + timeoutNanos;
    synchronized (buildMonitor) {
      while (ordinals.get() <= ordinal) {
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          return false;
        }
        try {
          buildMonitor.wait(Math.max(1L, remaining / 1_000_000L));
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    }
    return true;
  }

  public AttemptView attempt(final long ordinal) {
    final var a = attempts.get(ordinal);
    return a == null ? null : a.view();
  }

  public NextAttemptOutcome attemptOutcome(final long ordinal) {
    final var a = attempts.get(ordinal);
    return a == null ? null : a.outcome();
  }

  /// Both scans here read [#attempts], which [#newAttempt] fills only AFTER bumping [#ordinals]
  /// and constructing the `Attempt` (a reflective generation read, a URI redaction, a JFR event
  /// begin). A successor can therefore own an ordinal — and be reported by a claim, which reads
  /// that counter — while its map entry has not landed, or has not become visible to this
  /// thread; no timestamp window can then find it. So neither scan may decide whether a
  /// successor existed: `RetirementDetector.successorObserved` puts the claim's own ordinal
  /// ahead of them, and `nextAttemptOrdinal` stays a diagnostic reporting only what the map held.
  private Attempt firstAttemptAfter(final long origin, final long fromNanos) {
    Attempt best = null;
    for (final var a : attempts.values()) {
      if (a.ordinal > origin && a.buildNanos >= fromNanos && (best == null || a.ordinal < best.ordinal)) {
        best = a;
      }
    }
    return best;
  }

  private boolean successorBuiltBetween(final long origin, final long fromNanos, final long toNanos) {
    for (final var a : attempts.values()) {
      if (a.ordinal > origin && a.buildNanos >= fromNanos && a.buildNanos <= toNanos) {
        return true;
      }
    }
    return false;
  }

  // ------------------------------------------------------------------------------ aborts

  void recordAbort(final HarnessSocket socket,
                   final long seq,
                   final long nowNanos,
                   final String caller,
                   final boolean duplicate) {
    stats.aborts.increment();
    final boolean retirement = HarnessSocket.isRetirementCaller(caller);
    final String classifier = HarnessSocket.shortClassifier(caller);
    abortLog[(int) (abortLogCount.getAndIncrement() % MAX_ABORT_LOG)] = new AbortEntry(
        seq, socket.ordinal(), classifier, retirement, duplicate, threadName(Thread.currentThread()), nowNanos
    );
    if (duplicate) {
      stats.abortsDuplicate.increment();
      return;
    }
    if (retirement) {
      stats.abortsRetirement.increment();
      final var frames = DeliveryFrames.of();
      if (frames.retirementAbort(this, socket, seq, nowNanos, classifier) == null) {
        emitSubFrameOverflow(frames, socket, seq, nowNanos, classifier);
      }
      return;
    }
    stats.abortsOther.increment();
    if ((classifier.equals("connect") || classifier.equals("adopt")) && socket.attempt().adopted()) {
      // A live connection replaced or displaced without any callback: the attempt opened and
      // was never acknowledged by the manager (or was, and is simply being replaced).
      socket.attempt().replaced = true;
    }
  }

  /// The last [#MAX_ABORT_LOG] aborts, oldest first — the self-test's view of no-callback
  /// abort classification.
  public List<AbortEntry> recentAborts() {
    final long count = abortLogCount.get();
    final long from = Math.max(0, count - MAX_ABORT_LOG);
    final var out = new ArrayList<AbortEntry>((int) (count - from));
    for (long i = from; i < count; ++i) {
      final var e = abortLog[(int) (i % MAX_ABORT_LOG)];
      if (e != null) {
        out.add(e);
      }
    }
    return out;
  }

  public record AbortEntry(long seq,
                           long ordinal,
                           String classifier,
                           boolean retirement,
                           boolean duplicate,
                           String thread,
                           long nanoTime) {
  }

  // ------------------------------------------------------------------------ build cancel

  DeliveryFrames.SubFrame buildCancelObserved(final Attempt attempt,
                                              final DeliveryFrames frames,
                                              final boolean wasDone,
                                              final long nowNanos,
                                              final String caller) {
    stats.buildCancels.increment();
    final String classifier = HarnessSocket.shortClassifier(caller);
    attempt.cancelled(nowNanos, classifier, wasDone);
    final var frame = frames.current();
    if (frame != null) {
      final var sub = frame.findRetirement(attempt.ordinal);
      if (sub != null) {
        sub.buildCancelObserved = true;
        sub.wasDoneAtCancel = wasDone;
        sub.cancelEnterNanos = nowNanos;
        return sub;
      }
    }
    if (wasDone) {
      return null;
    }
    if (classifier.equals("buildReservedAttempt")) {
      attempt.cancelledBeforeInstall = true;
      emitCancelledBeforeInstall(attempt, frames, nowNanos);
    } else if (classifier.equals(DeliveryFrames.RETIREMENT_CLASSIFIER)) {
      // A retirement cancel whose abort was not in this frame: the retired socket and the
      // in-flight build belong to different attempts, which the engine's generation fence is
      // supposed to make impossible.
      stats.cancelsWithoutAbort.increment();
    }
    return null;
  }

  // ------------------------------------------------------------------------------ claims

  void claim(final long errorCount, final long delayMillis, final long nowNanos) {
    final var frames = DeliveryFrames.of();
    final var frame = frames.current();
    final boolean inside = frames.insideBuildCancel();
    final DeliveryFrames.SubFrame sub = frame == null ? null : frame.innermostRetirement();
    final ClaimRecord.Route route;
    if (inside) {
      route = ClaimRecord.Route.FUTURE;
    } else if (sub != null) {
      route = ClaimRecord.Route.CALLBACK;
    } else if (frame != null && frame.kind == FrameKind.BUILD_COMPLETION) {
      route = ClaimRecord.Route.FUTURE;
    } else {
      route = ClaimRecord.Route.UNROUTED;
    }
    final long origin;
    final ClaimRecord.OriginQuality quality;
    if (sub != null) {
      origin = sub.ordinal;
      quality = frame.lazy ? ClaimRecord.OriginQuality.LAZY : ClaimRecord.OriginQuality.FRAME;
    } else if (frame != null && !frame.lazy && frame.ordinal >= 0) {
      origin = frame.ordinal;
      quality = ClaimRecord.OriginQuality.FRAME;
    } else {
      origin = -1;
      quality = ClaimRecord.OriginQuality.UNKNOWN;
    }
    final var record = new ClaimRecord(
        CLAIM_IDS.incrementAndGet(), engine, errorCount, delayMillis, route, origin, quality,
        currentOrdinal(), inside, threadName(Thread.currentThread()), nowNanos
    );
    if (sub != null) {
      sub.addClaim(record);
    }
    stats.claims.increment();
    if (route == ClaimRecord.Route.UNROUTED) {
      stats.claimsUnrouted.increment();
    }
    lastErrorCount = errorCount;
    lastClaimNanos = nowNanos;
    claimSink.accept(record);
    final var event = new Issue52Events.ManagerClaim();
    if (event.isEnabled()) {
      event.claimSeq = record.claimSeq();
      event.engine = engine;
      event.errorCount = errorCount;
      event.delayMillis = delayMillis;
      event.route = route.name();
      event.originOrdinal = origin;
      event.originQuality = quality.name();
      event.currentOrdinal = record.currentOrdinal();
      event.insideBuildCancel = inside;
      event.claimThread = Thread.currentThread();
      event.harnessNanos = nowNanos;
      event.commit();
    }
  }

  /// The manager's one transition to OPEN, tagged by the frame it was logged in: inside a
  /// listener `onOpen` frame it came through the callback, inside a build-completion frame
  /// through the future. Either way the attempt is acknowledged and `errorCount` is 0 again.
  void connectedObserved(final DeliveryFrames.Frame frame, final long nowNanos) {
    lastErrorCount = 0;
    if (frame.ordinal < 0) {
      stats.connectedUntagged.increment();
      return;
    }
    final var attempt = attempts.get(frame.ordinal);
    if (attempt == null) {
      stats.connectedUntagged.increment();
      return;
    }
    attempt.acknowledged = true;
    attempt.acknowledgedNanos = nowNanos;
    attempt.acknowledgedViaFuture = frame.kind == FrameKind.BUILD_COMPLETION;
    // Rejoin: the retry's connect() returned a copy of an attempt that was already in flight,
    // so the acknowledged attempt was built BEFORE the claim that scheduled that retry.
    attempt.rejoined = lastClaimNanos >= 0 && attempt.buildNanos < lastClaimNanos;
    stats.acknowledged.increment();
  }

  // ---------------------------------------------------------------------------- handlers

  private void onOpen(final SolanaRpcWebsocket ws) {
    final var frame = DeliveryFrames.of().currentExplicit();
    if (frame != null && frame.kind == FrameKind.ON_OPEN) {
      final var attempt = attempts.get(frame.ordinal);
      if (attempt != null) {
        attempt.openReported = true;
      }
    }
    stats.opensReported.increment();
  }

  private void onClose(final SolanaRpcWebsocket ws, final int statusCode, final String reason) {
    deliver(DeliveryFrames.CONSUMED_ON_CLOSE, ws, statusCode, reason, null);
  }

  private void onError(final SolanaRpcWebsocket ws, final Throwable error) {
    deliver(DeliveryFrames.CONSUMED_ON_ERROR, ws, -1, null, error);
  }

  private void onPingError(final SolanaRpcWebsocket ws, final Throwable error) {
    final var frames = DeliveryFrames.of();
    final var frame = frames.current();
    DeliveryFrames.SubFrame sub = null;
    if (frame != null) {
      if (frame.lazy) {
        synchronized (frame) {
          sub = frame.consume(DeliveryFrames.CONSUMED_ON_PING_ERROR, System.nanoTime());
        }
      } else {
        sub = frame.consume(DeliveryFrames.CONSUMED_ON_PING_ERROR, System.nanoTime());
      }
    }
    if (sub == null || sub.pending == null) {
      stats.pingErrorWithoutPair.increment();
      return;
    }
    final var b = sub.pending;
    sub.pending = null;
    b.pingRoute = PingRoute.PRESENT;
    b.causeDetail = b.causeDetail + " | onPingError=" + (error == null ? "null" : error.getClass().getSimpleName());
    emit(b.build());
  }

  /// The single consumption point for `onClose`/`onError` (attribution.md rule): consume the
  /// innermost unconsumed retirement sub-frame of the current frame, or classify the notice as
  /// instance-scoped when there is none.
  private void deliver(final int kind,
                       final SolanaRpcWebsocket ws,
                       final int statusCode,
                       final String reason,
                       final Throwable error) {
    final long now = System.nanoTime();
    final var frames = DeliveryFrames.of();
    final var frame = frames.current();
    final String stack = engineStack();
    final boolean pairExpected = kind == DeliveryFrames.CONSUMED_ON_ERROR && stack.contains("|deliverRetiredPingFailure|");
    final boolean loopDeath = stack.contains("|run|") && !stack.contains("|deliverRetiredError|");
    DeliveryFrames.SubFrame sub = null;
    if (frame != null) {
      if (frame.lazy) {
        synchronized (frame) {
          sub = frame.consume(kind, now);
        }
      } else {
        sub = frame.consume(kind, now);
      }
    }
    final var b = new RetirementRecord.Builder();
    b.retirementId = RETIREMENT_IDS.incrementAndGet();
    b.engine = engine;
    b.backoffClass = backoffClassName();
    b.forced = forced;
    b.forcedReason = forcedReason;
    b.deliveryThread = threadName(Thread.currentThread());
    b.frameKind = frame == null ? RetirementRecord.FrameKind.NONE : frame.recordKind();
    b.threadKind = threadKind(frames, frame);
    b.errorCountBefore = lastErrorCount;
    b.errorCountAfter = lastErrorCount;
    if (sub == null) {
      // No fresh retirement abort in the frame: instance-scoped (the check loop died), or
      // engine drift the stack does not corroborate.
      b.causeClass = loopDeath ? CauseClass.INSTANCE_DEATH : CauseClass.UNKNOWN;
      final var detail = new StringBuilder(160);
      detail.append(describe(kind, statusCode, reason, error)).append(" | no-fresh-abort; stack=").append(stack);
      b.causeDetail = detail.toString();
      b.retiredAtNanos = now;
      b.callbackRoute = kind == DeliveryFrames.CONSUMED_ON_CLOSE ? CallbackRoute.ON_CLOSE_FENCED : CallbackRoute.ON_ERROR_FENCED;
      b.verdict = RetirementDetector.verdictFor(b.causeClass, b.callbackRoute, false, false, false, -1, -1);
      if (loopDeath) {
        stats.instanceDeaths.increment();
      } else {
        stats.unknownDeliveries.increment();
      }
      emit(b.build());
      runPolicy(ws);
      return;
    }
    final var attempt = sub.socket.attempt();
    b.originOrdinal = sub.ordinal;
    b.originGeneration = attempt.generation;
    b.adoptedAtNanos = attempt.onOpenEntryNanos;
    b.retiredAtNanos = sub.abortNanos;
    b.connectionAgeMs = attempt.onOpenEntryNanos < 0 ? -1 : (sub.abortNanos - attempt.onOpenEntryNanos) / 1_000_000L;
    b.openOrder = attempt.openOrder;
    final long listenerOrdinal = frame.kind.listener() ? frame.ordinal : -1;
    b.retiredNewerThanListener = listenerOrdinal >= 0 && sub.ordinal != listenerOrdinal;
    b.abortSeq = sub.seq;
    b.abortBeforeCallback = true;
    b.abortClassifier = sub.classifier;
    b.abortToHandlerUs = (now - sub.abortNanos) / 1_000L;
    b.buildCancelObserved = sub.buildCancelObserved;
    b.buildWasDoneAtCancel = sub.wasDoneAtCancel;
    final var futureClaim = sub.futureClaim();
    final var callbackClaim = sub.callbackClaim();
    if (!sub.buildCancelObserved || sub.wasDoneAtCancel) {
      b.futureRoute = FutureRoute.NONE_ALREADY_SETTLED;
    } else if (futureClaim != null) {
      b.futureRoute = FutureRoute.ACCEPTED;
    } else {
      // The route fired (the bridge settled inside the cancel) and nothing claimed: either the
      // manager's copy had been consumed by markOpen (acknowledged) or the claim was fenced.
      b.futureRoute = attempt.acknowledged ? FutureRoute.FIRED_IGNORED : FutureRoute.FENCED;
    }
    if (kind == DeliveryFrames.CONSUMED_ON_CLOSE) {
      b.callbackRoute = callbackClaim != null ? CallbackRoute.ON_CLOSE_ACCEPTED : CallbackRoute.ON_CLOSE_FENCED;
    } else {
      b.callbackRoute = callbackClaim != null ? CallbackRoute.ON_ERROR_ACCEPTED : CallbackRoute.ON_ERROR_FENCED;
    }
    b.pingRoute = pairExpected ? PingRoute.EXPECTED_MISSING : PingRoute.ABSENT;
    b.claimCount = sub.claimCount + sub.claimOverflow;
    final var firstClaim = sub.claimCount > 0 ? sub.claims[0] : null;
    final var lastClaim = sub.lastClaim();
    if (firstClaim != null) {
      b.errorCountBefore = firstClaim.errorCount() - 1;
      b.errorCountAfter = lastClaim.errorCount();
    }
    b.retryDelayMs = futureClaim != null ? futureClaim.delayMillis() : callbackClaim != null ? callbackClaim.delayMillis() : -1;
    b.loggedRetryMs = sub.loggedRetryMs;
    boolean successorInsideGap = false;
    if (futureClaim != null) {
      final long gapEnd = callbackClaim != null ? callbackClaim.nanoTime() : now;
      b.gapFutureToCallbackUs = Math.max(0, (gapEnd - futureClaim.nanoTime()) / 1_000L);
      b.retryMarginUs = futureClaim.delayMillis() * 1_000L - b.gapFutureToCallbackUs;
      b.harnessTimeInGapUs = sub.harnessNanosInGap / 1_000L;
      successorInsideGap = successorBuiltBetween(sub.ordinal, futureClaim.nanoTime(), now);
      b.successorBuiltInsideGap = successorInsideGap;
    }
    b.currentOrdinalAtCallbackClaim = callbackClaim != null ? callbackClaim.currentOrdinal() : -1;
    // Over the fields of THIS row, so re-reading it from the TSV reproduces the stamped verdict.
    final boolean successorObserved = RetirementDetector.successorObserved(
        successorInsideGap, b.currentOrdinalAtCallbackClaim, b.originOrdinal
    );
    b.causeClass = causeClass(kind, error, pairExpected);
    final var detail = new StringBuilder(200);
    detail.append(describe(kind, statusCode, reason, error));
    sub.appendEngineLines(detail);
    b.causeDetail = detail.toString();
    b.verdict = RetirementDetector.verdictFor(
        b.causeClass, b.callbackRoute, futureClaim != null, callbackClaim != null,
        successorObserved, b.retryDelayMs, b.gapFutureToCallbackUs
    );
    final var next = firstAttemptAfter(sub.ordinal, sub.abortNanos);
    if (next != null) {
      b.nextAttemptOrdinal = next.ordinal;
      b.nextAttemptOutcome = next.outcome();
      if (lastClaim != null && b.retryDelayMs >= 0) {
        b.wakeLatencyUs = (next.buildNanos - lastClaim.nanoTime()) / 1_000L - b.retryDelayMs * 1_000L;
      }
    }
    if (pairExpected) {
      // The engine promises onPingError in a finally arm after onError; the row waits for it
      // so the pair reads one record.
      sub.pending = b;
    } else {
      emit(b.build());
    }
    runPolicy(ws);
  }

  private void runPolicy(final SolanaRpcWebsocket ws) {
    final var policy = afterRetirement;
    if (policy != null) {
      try {
        policy.accept(ws);
      } catch (final RuntimeException e) {
        stats.policyThrew.increment();
      }
    }
  }

  private ThreadKind threadKind(final DeliveryFrames frames, final DeliveryFrames.Frame frame) {
    if (frame == null || frame.lazy) {
      return checkLoopThread.test(Thread.currentThread()) ? ThreadKind.CHECK_LOOP : ThreadKind.OTHER;
    }
    if (frames.hasEnclosing(FrameKind.ON_OPEN)) {
      return ThreadKind.ADOPTING;
    }
    if (frame.kind.listener()) {
      return ThreadKind.JDK_LISTENER;
    }
    if (frame.kind == FrameKind.BUILD_COMPLETION) {
      return ThreadKind.BUILD_COMPLETION;
    }
    return ThreadKind.OTHER;
  }

  /// Cause is parsed, not typed (attribution.md caveats): the engine reports escalations as
  /// `IllegalStateException`s whose message prefixes are the only classification available
  /// without an engine change.
  static CauseClass causeClass(final int kind, final Throwable error, final boolean pairExpected) {
    if (kind == DeliveryFrames.CONSUMED_ON_CLOSE) {
      return CauseClass.JDK_CLOSE;
    }
    if (pairExpected) {
      return CauseClass.PING_SEND_FAILURE;
    }
    final String message = error == null ? null : error.getMessage();
    if (message != null) {
      if (message.startsWith("soak: injected")) {
        return CauseClass.HARNESS_INJECTED;
      }
      if (error instanceof IllegalStateException) {
        if (message.startsWith("Request ")) {
          return CauseClass.UNANSWERED_REQUEST;
        }
        if (message.startsWith("Ping send to ")) {
          return CauseClass.PING_SEND_TIMEOUT;
        }
        if (message.startsWith("Ping to ")) {
          return CauseClass.PING_RESPONSE_TIMEOUT;
        }
        if (message.contains(" exceeds maxMessageLength ")) {
          return CauseClass.MAX_MESSAGE_OVERFLOW;
        }
        if (message.startsWith("Subscription id ")) {
          return CauseClass.ID_COLLISION;
        }
        if (message.startsWith("Un-subscription ")) {
          return CauseClass.UNSUB_NONEQUIVALENT;
        }
      }
    }
    return CauseClass.JDK_ERROR;
  }

  private static String describe(final int kind, final int statusCode, final String reason, final Throwable error) {
    if (kind == DeliveryFrames.CONSUMED_ON_CLOSE) {
      return "status=" + statusCode + " reason=" + reason;
    }
    if (error == null) {
      return "null";
    }
    final String message = error.getMessage();
    final String cls = error.getClass().getSimpleName();
    if (message == null) {
      return cls;
    }
    return cls + ": " + (message.length() <= 120 ? message : message.substring(0, 120));
  }

  /// Engine method names on the delivering stack, innermost first, `|`-delimited with
  /// sentinels so exact-name tests read `|name|`. Walked on the retirement path only.
  private static String engineStack() {
    final var sb = new StringBuilder(96).append('|');
    WALKER.forEach(f -> {
      if (f.getClassName().startsWith(DeliveryFrames.ENGINE_CLASS) && sb.length() < 512) {
        sb.append(f.getMethodName()).append('|');
      }
    });
    return sb.toString();
  }

  // ---------------------------------------------------------------------------- emission

  void emitUnobserved(final DeliveryFrames.SubFrame sub, final DeliveryFrames.Frame frame, final long nowNanos, final String why) {
    final var attempt = sub.socket.attempt();
    final var b = new RetirementRecord.Builder();
    b.retirementId = RETIREMENT_IDS.incrementAndGet();
    b.engine = engine;
    b.backoffClass = backoffClassName();
    b.forced = forced;
    b.forcedReason = forcedReason;
    b.originOrdinal = sub.ordinal;
    b.originGeneration = attempt.generation;
    b.adoptedAtNanos = attempt.onOpenEntryNanos;
    b.retiredAtNanos = sub.abortNanos;
    b.connectionAgeMs = attempt.onOpenEntryNanos < 0 ? -1 : (sub.abortNanos - attempt.onOpenEntryNanos) / 1_000_000L;
    b.openOrder = attempt.openOrder;
    b.deliveryThread = threadName(frame.owner.thread);
    b.frameKind = frame.recordKind();
    b.threadKind = frame.lazy
        ? (checkLoopThread.test(frame.owner.thread) ? ThreadKind.CHECK_LOOP : ThreadKind.OTHER)
        : frame.kind.listener() ? ThreadKind.JDK_LISTENER : ThreadKind.OTHER;
    b.abortSeq = sub.seq;
    b.abortBeforeCallback = false;
    b.abortClassifier = sub.classifier;
    b.buildCancelObserved = sub.buildCancelObserved;
    b.buildWasDoneAtCancel = sub.wasDoneAtCancel;
    final var futureClaim = sub.futureClaim();
    b.futureRoute = !sub.buildCancelObserved || sub.wasDoneAtCancel
        ? FutureRoute.NONE_ALREADY_SETTLED
        : futureClaim != null ? FutureRoute.ACCEPTED : FutureRoute.FENCED;
    b.callbackRoute = CallbackRoute.UNOBSERVED;
    b.claimCount = sub.claimCount + sub.claimOverflow;
    if (sub.claimCount > 0) {
      b.errorCountBefore = sub.claims[0].errorCount() - 1;
      b.errorCountAfter = sub.lastClaim().errorCount();
    }
    b.retryDelayMs = futureClaim != null ? futureClaim.delayMillis() : -1;
    b.loggedRetryMs = sub.loggedRetryMs;
    b.causeClass = CauseClass.UNKNOWN;
    final var detail = new StringBuilder(120).append("unobserved: ").append(why);
    sub.appendEngineLines(detail);
    b.causeDetail = detail.toString();
    b.verdict = Verdict.UNOBSERVED_DELIVERY;
    stats.unobservedDeliveries.increment();
    emit(b.build());
  }

  /// The frame that should hold this retirement's sub-frame is full, so no handler can ever
  /// consume it: the retirement happened and the harness cannot attribute it. Emit the row
  /// anyway, because `stats.subFrameOverflow` on its own is an in-memory counter no artifact
  /// carries, and a retirement lost in silence reads as a clean run. `UNOBSERVED_DELIVERY` is
  /// the verdict the report and the runner's `i52_unobserved_delivery` gate already read as
  /// "the capture, not the subject, is what failed here".
  private void emitSubFrameOverflow(final DeliveryFrames frames,
                                    final HarnessSocket socket,
                                    final long seq,
                                    final long nowNanos,
                                    final String classifier) {
    final var attempt = socket.attempt();
    final var frame = frames.currentExplicit();
    final var b = new RetirementRecord.Builder();
    b.retirementId = RETIREMENT_IDS.incrementAndGet();
    b.engine = engine;
    b.backoffClass = backoffClassName();
    b.forced = forced;
    b.forcedReason = forcedReason;
    b.originOrdinal = socket.ordinal();
    b.originGeneration = attempt.generation;
    b.adoptedAtNanos = attempt.onOpenEntryNanos;
    b.retiredAtNanos = nowNanos;
    b.connectionAgeMs = attempt.onOpenEntryNanos < 0 ? -1 : (nowNanos - attempt.onOpenEntryNanos) / 1_000_000L;
    b.openOrder = attempt.openOrder;
    b.deliveryThread = threadName(Thread.currentThread());
    b.frameKind = frame == null ? RetirementRecord.FrameKind.NONE : frame.recordKind();
    b.threadKind = threadKind(frames, frame);
    b.abortSeq = seq;
    b.abortClassifier = classifier;
    b.causeClass = CauseClass.UNKNOWN;
    b.causeDetail = "unobserved: sub-frame overflow, more than "
        + DeliveryFrames.MAX_SUBS + " retirement aborts in one delivery frame";
    b.verdict = Verdict.UNOBSERVED_DELIVERY;
    stats.unobservedDeliveries.increment();
    emit(b.build());
  }

  void emitPendingWithoutPing(final DeliveryFrames.SubFrame sub, final String why) {
    final var b = sub.pending;
    sub.pending = null;
    b.pingRoute = PingRoute.EXPECTED_MISSING;
    b.causeDetail = b.causeDetail + " | onPingError-missing: " + why;
    stats.pingPairMissing.increment();
    emit(b.build());
  }

  private void emitCancelledBeforeInstall(final Attempt attempt, final DeliveryFrames frames, final long nowNanos) {
    final var frame = frames.current();
    final var b = new RetirementRecord.Builder();
    b.retirementId = RETIREMENT_IDS.incrementAndGet();
    b.engine = engine;
    b.backoffClass = backoffClassName();
    b.forced = forced;
    b.forcedReason = forcedReason;
    b.originOrdinal = attempt.ordinal;
    b.originGeneration = attempt.generation;
    b.retiredAtNanos = nowNanos;
    b.openOrder = attempt.openOrder;
    b.causeClass = CauseClass.CANCELLED_BEFORE_INSTALL;
    b.causeDetail = "build cancelled by buildReservedAttempt before install";
    b.deliveryThread = threadName(Thread.currentThread());
    b.frameKind = frame == null ? RetirementRecord.FrameKind.NONE : frame.recordKind();
    b.threadKind = threadKind(frames, frame);
    b.abortClassifier = "none";
    b.buildCancelObserved = true;
    b.buildWasDoneAtCancel = false;
    b.futureRoute = FutureRoute.FENCED;
    b.callbackRoute = CallbackRoute.UNOBSERVED;
    b.errorCountBefore = lastErrorCount;
    b.errorCountAfter = lastErrorCount;
    b.verdict = Verdict.ATTRIBUTED_SINGLE_CLAIM;
    b.nextAttemptOutcome = NextAttemptOutcome.PENDING;
    stats.cancelledBeforeInstall.increment();
    emit(b.build());
  }

  private void emit(final RetirementRecord record) {
    stats.retirementsEmitted.increment();
    if (record.verdict() == Verdict.DOUBLE_CLAIM_MISATTRIBUTED) {
      stats.misattributed.increment();
    } else if (record.verdict() == Verdict.DOUBLE_CLAIM_UNEXPLAINED) {
      stats.doubleClaimsUnexplained.increment();
    }
    retirementSink.accept(record);
    final var event = new Issue52Events.Retirement();
    if (event.isEnabled()) {
      event.retirementId = record.retirementId();
      event.engine = record.engine();
      event.backoffClass = record.backoffClass();
      event.forced = record.forced();
      event.forcedReason = record.forcedReason();
      event.originOrdinal = record.originOrdinal();
      event.originGeneration = record.originGeneration();
      event.adoptedAtNanos = record.adoptedAtNanos();
      event.retiredAtNanos = record.retiredAtNanos();
      event.connectionAgeMs = record.connectionAgeMs();
      event.openOrder = record.openOrder().name();
      event.causeClass = record.causeClass().name();
      event.causeDetail = record.causeDetail();
      event.deliveryThread = Thread.currentThread();
      event.threadKind = record.threadKind().name();
      event.frameKind = record.frameKind().name();
      event.retiredNewerThanListener = record.retiredNewerThanListener();
      event.abortSeq = record.abortSeq();
      event.abortBeforeCallback = record.abortBeforeCallback();
      event.abortClassifier = record.abortClassifier();
      event.abortToHandlerUs = record.abortToHandlerUs();
      event.buildCancelObserved = record.buildCancelObserved();
      event.buildWasDoneAtCancel = record.buildWasDoneAtCancel();
      event.futureRoute = record.futureRoute().name();
      event.callbackRoute = record.callbackRoute().name();
      event.pingRoute = record.pingRoute().name();
      event.claimCount = record.claimCount();
      event.errorCountBefore = record.errorCountBefore();
      event.errorCountAfter = record.errorCountAfter();
      event.retryDelayMs = record.retryDelayMs();
      event.loggedRetryMs = record.loggedRetryMs();
      event.gapFutureToCallbackUs = record.gapFutureToCallbackUs();
      event.retryMarginUs = record.retryMarginUs();
      event.harnessTimeInGapUs = record.harnessTimeInGapUs();
      event.currentOrdinalAtCallbackClaim = record.currentOrdinalAtCallbackClaim();
      event.successorBuiltInsideGap = record.successorBuiltInsideGap();
      event.verdict = record.verdict().name();
      event.nextAttemptOrdinal = record.nextAttemptOrdinal();
      event.nextAttemptOutcome = record.nextAttemptOutcome().name();
      event.wakeLatencyUs = record.wakeLatencyUs();
      event.subscriptionRecoveryMs = record.subscriptionRecoveryMs();
      event.commit();
    }
    if (record.verdict() == Verdict.DOUBLE_CLAIM_MISATTRIBUTED) {
      final var mis = new Issue52Events.Misattribution();
      if (mis.isEnabled()) {
        mis.retirementId = record.retirementId();
        mis.engine = record.engine();
        mis.originOrdinal = record.originOrdinal();
        mis.successorOrdinal = record.currentOrdinalAtCallbackClaim();
        mis.gapUs = record.gapFutureToCallbackUs();
        mis.retryDelayMs = record.retryDelayMs();
        mis.errorCountInflation = 1;
        mis.backoffClass = record.backoffClass();
        mis.forced = record.forced();
        mis.commit();
      }
    }
  }

  private String backoffClassName() {
    final var b = backoff;
    return b == null ? "NONE" : b.backoffClass.name();
  }

  static String threadName(final Thread thread) {
    final String name = thread.getName();
    return name.isEmpty() ? "vt#" + thread.threadId() : name;
  }

  // ------------------------------------------------------------------------- lifecycle

  /// Ages out lazy frames on every thread; a workload calls it from its gauge tick.
  public void sweep() {
    DeliveryFrames.sweepAll(System.nanoTime());
  }

  /// End of run: finalises every lazy frame regardless of age, so an unconsumed retirement is
  /// a row and never a silent drop.
  public void flush() {
    DeliveryFrames.sweepAll(System.nanoTime() + DeliveryFrames.LAZY_AGE_NANOS + 1);
  }

  public void close() {
    flush();
  }

  // --------------------------------------------------------------------------- attempts

  /// Everything known about one `buildAsync`, resolved as the attempt's life unfolds.
  static final class Attempt {

    final AttemptTracker tracker;
    final long ordinal;
    final URI uri;
    final String endpoint;
    final Thread buildThread;
    final long buildNanos;
    final long generation;
    volatile long buildReturnedNanos = -1;
    HarnessListener listener;
    HarnessBuildFuture future;
    private HarnessSocket socket;
    volatile long onOpenEntryNanos = -1;
    volatile Thread onOpenThread;
    volatile long onOpenReturnNanos = -1;
    volatile long futureSettledNanos = -1;
    volatile Thread completionThread;
    /// 0 pending, 1 succeeded, 2 failed, 3 cancelled before it settled
    volatile int futureOutcome;
    volatile String failure = "";
    volatile OpenOrder openOrder = OpenOrder.UNKNOWN;
    /// sava installed the Connection for this socket (observed at `request(long)`).
    volatile boolean installed;
    /// the prototype onOpen ran for this attempt (adoption completed without retirement)
    volatile boolean openReported;
    volatile boolean acknowledged;
    volatile boolean acknowledgedViaFuture;
    volatile long acknowledgedNanos = -1;
    volatile boolean rejoined;
    volatile boolean replaced;
    volatile boolean cancelledBeforeInstall;
    volatile String cancelledBy = "";
    private final Issue52Events.ConnectAttempt event = new Issue52Events.ConnectAttempt();
    private final AtomicBoolean committed = new AtomicBoolean();

    Attempt(final AttemptTracker tracker,
            final long ordinal,
            final URI uri,
            final WebSocket.Listener savaListener,
            final Thread buildThread,
            final long buildNanos) {
      this.tracker = tracker;
      this.ordinal = ordinal;
      this.uri = uri;
      this.endpoint = redact(uri);
      this.buildThread = buildThread;
      this.buildNanos = buildNanos;
      this.generation = Generation.of(savaListener);
      event.begin();
    }

    void install(final HarnessListener listener, final HarnessBuildFuture future) {
      this.listener = listener;
      this.future = future;
    }

    /// ONE wrapper per raw socket by identity: sava adopts the `onOpen` argument and compares
    /// identities in `connectionFor`/`close()`/`buildReservedAttempt`.
    synchronized HarnessSocket wrapperFor(final WebSocket raw) {
      if (socket == null) {
        socket = new HarnessSocket(tracker, this, raw);
        return socket;
      }
      if (socket.raw == raw) {
        return socket;
      }
      // A second distinct raw socket for one attempt cannot happen with the JDK builder; a
      // broken wrapping delegate could do it, and the mismatch is counted rather than hidden.
      tracker.stats.wrapperMismatch.increment();
      socket = new HarnessSocket(tracker, this, raw);
      return socket;
    }

    HarnessSocket socket() {
      synchronized (this) {
        return socket;
      }
    }

    boolean adopted() {
      return installed;
    }

    void onOpenEntered(final long nowNanos, final Thread thread) {
      onOpenEntryNanos = nowNanos;
      onOpenThread = thread;
      openOrder = future != null && future.isDone() ? OpenOrder.FUTURE_FIRST : OpenOrder.ONOPEN_FIRST;
    }

    void onOpenReturned(final long nowNanos) {
      onOpenReturnNanos = nowNanos;
      commit("OPEN", "");
      if (future != null) {
        future.onOpenReturned();
      }
    }

    /// A cancelled attempt stays cancelled: the JDK future may still deliver a socket (or a
    /// failure) after the harness future was cancelled, and that late result is the orphan
    /// guard's business, not a change of outcome.
    void futureSucceeded(final long nowNanos, final Thread thread) {
      if (futureOutcome == 3) {
        return;
      }
      futureSettledNanos = nowNanos;
      completionThread = thread;
      futureOutcome = 1;
    }

    void futureFailed(final long nowNanos, final Thread thread, final Throwable ex) {
      if (futureOutcome == 3) {
        return;
      }
      futureSettledNanos = nowNanos;
      completionThread = thread;
      futureOutcome = 2;
      failure = ex.getClass().getName();
      commit("FAILED", failure);
    }

    void cancelled(final long nowNanos, final String by, final boolean wasDone) {
      if (wasDone) {
        return;
      }
      futureSettledNanos = nowNanos;
      completionThread = Thread.currentThread();
      futureOutcome = 3;
      cancelledBy = by;
      if (!installed) {
        commit("CANCELLED", "cancel:" + by);
      }
    }

    private void commit(final String outcome, final String failure) {
      if (committed.compareAndSet(false, true) && event.isEnabled()) {
        event.end();
        event.ordinal = ordinal;
        event.engine = tracker.engine;
        event.endpoint = endpoint;
        event.outcome = outcome;
        event.failure = failure;
        event.openOrder = openOrder.name();
        event.buildThread = buildThread;
        event.completionThread = completionThread;
        event.commit();
      }
    }

    NextAttemptOutcome outcome() {
      if (cancelledBeforeInstall) {
        return NextAttemptOutcome.CANCELLED_BEFORE_INSTALL;
      }
      if (futureOutcome == 2) {
        return NextAttemptOutcome.FAILED;
      }
      if (futureOutcome == 3 && !installed) {
        return NextAttemptOutcome.FAILED;
      }
      if (acknowledged) {
        return rejoined ? NextAttemptOutcome.REJOINED_ACKNOWLEDGED : NextAttemptOutcome.OPENED_ACKNOWLEDGED;
      }
      if (installed && replaced) {
        return NextAttemptOutcome.OPENED_UNACKNOWLEDGED_REPLACED;
      }
      return NextAttemptOutcome.PENDING;
    }

    AttemptView view() {
      return new AttemptView(
          ordinal, endpoint, threadName(buildThread), buildNanos, generation,
          onOpenThread == null ? "" : threadName(onOpenThread), onOpenEntryNanos, openOrder,
          futureOutcome, failure, cancelledBy, installed, openReported, acknowledged, acknowledgedViaFuture,
          rejoined, replaced, cancelledBeforeInstall, outcome()
      );
    }

    /// A live provider URL may carry an API key in its path or query; only the authority
    /// survives.
    private static String redact(final URI uri) {
      if (uri == null) {
        return "";
      }
      final var sb = new StringBuilder(48);
      if (uri.getScheme() != null) {
        sb.append(uri.getScheme()).append("://");
      }
      if (uri.getHost() != null) {
        sb.append(uri.getHost());
      }
      if (uri.getPort() >= 0) {
        sb.append(':').append(uri.getPort());
      }
      return sb.toString();
    }
  }

  /// Immutable snapshot of one attempt, for the self-test and the report join.
  public record AttemptView(long ordinal,
                            String endpoint,
                            String buildThread,
                            long buildNanos,
                            long generation,
                            String onOpenThread,
                            long onOpenEntryNanos,
                            OpenOrder openOrder,
                            int futureOutcome,
                            String failure,
                            String cancelledBy,
                            boolean installed,
                            boolean openReported,
                            boolean acknowledged,
                            boolean acknowledgedViaFuture,
                            boolean rejoined,
                            boolean replaced,
                            boolean cancelledBeforeInstall,
                            NextAttemptOutcome outcome) {
  }

  /// Optional oracle: sava's `AttemptListener.generation` (`connectGeneration` at the attempt)
  /// through reflection, reachable only under `--add-opens` on the module path (or on the
  /// classpath, where the unnamed module opens everything). Disabled for good on the first
  /// refusal so a modular launch without the flag costs one failed lookup, not one per attempt.
  private static final class Generation {

    private static volatile boolean available = true;
    private static volatile Field field;

    static long of(final WebSocket.Listener listener) {
      if (!available || listener == null || !listener.getClass().getName().equals(ATTEMPT_LISTENER_CLASS)) {
        return -1;
      }
      try {
        var f = field;
        if (f == null) {
          f = listener.getClass().getDeclaredField("generation");
          f.setAccessible(true);
          field = f;
        }
        return f.getLong(listener);
      } catch (final ReflectiveOperationException | RuntimeException e) {
        available = false;
        return -1;
      }
    }
  }

  /// Harness self-accounting: every bounded structure's overflow is counted here rather than
  /// dropped silently, so a truncated capture can be told apart from a clean one.
  ///
  /// [#snapshot()] is the export seam, and these numbers reach a run's artifacts only where a
  /// workload stage copies it into that run's `Counters` (one tracker per engine, so the
  /// consumer must add, never set). `frameDepthOverflow`, `subFrameOverflow`,
  /// `headerOverflow`, `wrapperMismatch` and `frameRegistryOverflow` are the ones that say
  /// "the attribution machinery stopped being able to attribute" and have no other
  /// representation in any artifact, so an uncopied snapshot leaves a truncated capture
  /// invisible to the report and to the verdict. The one overflow that would otherwise lose a
  /// whole retirement rather than a diagnostic — a sub-frame that does not fit — is also
  /// emitted as an `UNOBSERVED_DELIVERY` row, which does reach `retirements.tsv`.
  public static final class Stats {

    public final LongAdder attempts = new LongAdder();
    public final LongAdder aborts = new LongAdder();
    public final LongAdder abortsRetirement = new LongAdder();
    public final LongAdder abortsOther = new LongAdder();
    public final LongAdder abortsDuplicate = new LongAdder();
    public final LongAdder buildCancels = new LongAdder();
    public final LongAdder cancelsWithoutAbort = new LongAdder();
    public final LongAdder claims = new LongAdder();
    public final LongAdder claimsUnrouted = new LongAdder();
    public final LongAdder acknowledged = new LongAdder();
    public final LongAdder connectedUntagged = new LongAdder();
    public final LongAdder opensReported = new LongAdder();
    public final LongAdder retirementsEmitted = new LongAdder();
    public final LongAdder misattributed = new LongAdder();
    public final LongAdder doubleClaimsUnexplained = new LongAdder();
    public final LongAdder instanceDeaths = new LongAdder();
    public final LongAdder unknownDeliveries = new LongAdder();
    public final LongAdder unobservedDeliveries = new LongAdder();
    public final LongAdder cancelledBeforeInstall = new LongAdder();
    public final LongAdder pingErrorWithoutPair = new LongAdder();
    public final LongAdder pingPairMissing = new LongAdder();
    public final LongAdder policyThrew = new LongAdder();
    public final LongAdder orphanSocketsAborted = new LongAdder();
    public final LongAdder injectedRetirements = new LongAdder();
    public final LongAdder injectionCapReached = new LongAdder();
    public final LongAdder wrapperMismatch = new LongAdder();
    public final LongAdder frameDepthOverflow = new LongAdder();
    public final LongAdder subFrameOverflow = new LongAdder();
    public final LongAdder headerOverflow = new LongAdder();

    /// Every counter by name in declaration order, plus the process-wide frame registry's
    /// two. `frameRegistryThreads` is a level rather than a count, so a consumer merging
    /// several trackers must not sum it.
    public Map<String, Long> snapshot() {
      final var m = new LinkedHashMap<String, Long>(32);
      m.put("attempts", attempts.sum());
      m.put("aborts", aborts.sum());
      m.put("abortsRetirement", abortsRetirement.sum());
      m.put("abortsOther", abortsOther.sum());
      m.put("abortsDuplicate", abortsDuplicate.sum());
      m.put("buildCancels", buildCancels.sum());
      m.put("cancelsWithoutAbort", cancelsWithoutAbort.sum());
      m.put("claims", claims.sum());
      m.put("claimsUnrouted", claimsUnrouted.sum());
      m.put("acknowledged", acknowledged.sum());
      m.put("connectedUntagged", connectedUntagged.sum());
      m.put("opensReported", opensReported.sum());
      m.put("retirementsEmitted", retirementsEmitted.sum());
      m.put("misattributed", misattributed.sum());
      m.put("doubleClaimsUnexplained", doubleClaimsUnexplained.sum());
      m.put("instanceDeaths", instanceDeaths.sum());
      m.put("unknownDeliveries", unknownDeliveries.sum());
      m.put("unobservedDeliveries", unobservedDeliveries.sum());
      m.put("cancelledBeforeInstall", cancelledBeforeInstall.sum());
      m.put("pingErrorWithoutPair", pingErrorWithoutPair.sum());
      m.put("pingPairMissing", pingPairMissing.sum());
      m.put("policyThrew", policyThrew.sum());
      m.put("orphanSocketsAborted", orphanSocketsAborted.sum());
      m.put("injectedRetirements", injectedRetirements.sum());
      m.put("injectionCapReached", injectionCapReached.sum());
      m.put("wrapperMismatch", wrapperMismatch.sum());
      m.put("frameDepthOverflow", frameDepthOverflow.sum());
      m.put("subFrameOverflow", subFrameOverflow.sum());
      m.put("headerOverflow", headerOverflow.sum());
      m.put("frameRegistryOverflow", DeliveryFrames.REGISTRY_OVERFLOW.sum());
      m.put("frameRegistryThreads", (long) DeliveryFrames.registeredThreads());
      return m;
    }
  }
}
