package software.sava.rpc.soak.http;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.Base58;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.response.Block;
import software.sava.rpc.json.http.response.JsonRpcException;
import software.sava.rpc.json.http.response.LatestBlockHash;
import software.sava.rpc.json.http.response.TxStatus;
import software.sava.rpc.soak.Counters;
import software.sava.rpc.soak.GaugeSampler;
import software.sava.rpc.soak.Phase;
import software.sava.rpc.soak.Seeds;
import software.sava.rpc.soak.SoakContext;
import software.sava.rpc.soak.Workload;
import software.sava.rpc.soak.churn.ChurnWorkload;
import software.sava.rpc.soak.control.HarnessControls;
import software.sava.rpc.soak.events.SoakEvents;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.UnknownServiceException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.UnaryOperator;

/// The HTTP half of the soak: sustained concurrent JSON-RPC against the controlled peer, with
/// every response checked against a stamp derived from the request's own arguments.
///
/// The single highest-value assertion in this file is W3-A. Everything else — the rate, the
/// in-flight cap, the three client shapes, the cancellations — exists so that responses complete
/// *out of order, under load, while some of them fail*, which is the only condition under which
/// "this response answers the request I sent" can be disproved. A harness that issued one request
/// at a time would assert the same property and never test it.
///
/// Bounds are the other half of the design. The harness may never be the thing that runs out of
/// memory: each worker holds at most `SOAK_HTTP_INFLIGHT` futures behind a semaphore, the whole
/// workload is paced by one token bucket on a monotonic clock, and every operation carries a
/// watchdog that releases its permit and records `TIMEOUT` rather than letting a wedged exchange
/// quietly shrink the load to nothing. A run that starves is recorded as starved
/// (`harness.opsSkipped.*`) and becomes INVALID — which is a different verdict from FAIL on
/// purpose: nothing was disproved, the evidence is just absent.
public final class HttpWorkload implements Workload, GaugeSampler.GaugeSource {

  /// Stable, short, and used as a column value and a counter suffix.
  public static final String NAME = "http";

  static final String CLIENT_PLAIN = "plain";
  static final String CLIENT_GZIP = "gzip";
  static final String CLIENT_EXTENDED = "extended";

  /// The harness's own per-operation bound: twice the client's exchange deadline plus five
  /// seconds. Past it the operation is recorded `TIMEOUT` and its permit released — the future
  /// itself is left alone, because cancelling it here would destroy the evidence that the client
  /// never settled it.
  static final Duration OP_GRACE = Duration.ofSeconds(5);

  /// The slack the deadline properties allow on top of the client's own bound.
  static final Duration DEADLINE_SLACK = Duration.ofSeconds(2);

  /// `getProgramAccounts` carries `PROGRAM_ACCOUNTS_TIMEOUT` (120 s), so its harness bound is
  /// stated rather than derived: 125 s.
  static final Duration PGA_BOUND = Duration.ofSeconds(125);

  /// W4-C's probe client: a deliberately short request timeout, so that a common pool loaded by
  /// the OVERLAP fan-out is visibly racing the very timer that bounds the exchange.
  static final Duration W4C_REQUEST_TIMEOUT = Duration.ofMillis(500);
  static final int W4C_CALLS = 32;
  static final int OVERLAP_FANOUT = 8;

  /// How many of `getProgramAccounts`' accounts are key-checked per response. The peer derives
  /// every key, but recomputing five hundred base58 SHA-256 digests per response would make the
  /// harness the bottleneck; the first, the last and one interior index catch a response built
  /// for another program, which is what the check is for.
  static final int PGA_SAMPLES = 3;

  /// `SolanaJsonRpcClient.DEFAULT_REQUEST_TIMEOUT` and `PROGRAM_ACCOUNTS_TIMEOUT` are
  /// package-private in the subject, so the two bounds are restated here. They are the subject's
  /// documented defaults; if the subject ever moves one, the deadline properties go slack rather
  /// than tight, which is the safe direction for a harness that must not invent a stricter
  /// promise than the library makes.
  static final Duration SUBJECT_REQUEST_TIMEOUT = Duration.ofSeconds(8);
  static final Duration SUBJECT_PROGRAM_ACCOUNTS_TIMEOUT = Duration.ofSeconds(120);

  static final int MULTI_ACCOUNTS_PER_CALL = 8;
  static final int SIGNATURES_PER_CALL = 4;
  static final int WEDGE_PROBES = 3;
  static final Duration TWIN_PERIOD = Duration.ofSeconds(5);

  private static final long INFLIGHT_WAIT_MILLIS = 250L;
  private static final long POLL_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
  private static final int OUTCOME_SAMPLE = 1000;

  /// The cap on the in-flight registry. The set is already bounded in the ordinary case — the
  /// permits bound the paced draws and the phase overlays are fixed fan-outs — but an entry lives
  /// until its *future* completes, not until the harness stops waiting, so a client that never
  /// settled anything would grow it for the length of the run. A harness hunting a retention
  /// defect may not be the thing that retains, hence a cap and a counted overflow rather than
  /// trust in the subject.
  static final int MAX_TRACKED_INFLIGHT = 4096;

  /// The cap on open W3-E windows, and the counter that says one was dropped.
  static final int MAX_PENDING_CANCELLATIONS = 1024;
  static final long CANCEL_DELAY_MAX_MILLIS = 25L;
  static final String CANCEL_CHECKS_DROPPED = "harness.http.cancelChecksDropped";
  /// A no-wrap response the peer served with a `Content-Encoding`: by this route's contract the
  /// bytes are handed back as sent, so the envelope check does not apply to them.
  static final String NOWRAP_ENCODED = "rpc.nowrap.encodedBody";
  /// A W3-D probe operation that settled with an error inside its bound. Not a wedge - the
  /// client answered promptly - but recorded, because a burst of them says the connection pool
  /// handed out a socket the peer had already closed.
  static final String WEDGE_PROBE_ERRORED = "rpc.wedgeProbe.erroredFast";
  /// The header every request the harness issues *to ask a question about the client* carries.
  ///
  /// The peer's fault schedule steps over a request carrying it. That exemption is what makes a
  /// probe's answer mean what the property reads into it: a W3-D probe that the peer answered with
  /// a scheduled `HTTP_503`, or a W5-B1 survival probe the peer stalled on purpose, is
  /// indistinguishable at the call site from the wedge or the dead client those probes exist to
  /// find, so every such fault is a false finding waiting to be filed. Load-bearing requests — the
  /// paced mix, the twins, the OVERLAP fan-out — do *not* carry it: they are the ones that have to
  /// meet the faults.
  ///
  /// Public because the churn driver marks its own probes with the same header, and one spelling
  /// shared between the two callers cannot drift from the peer's exemption the way two could.
  public static final String PROBE_HEADER = "X-Soak-Probe";

  /// How many probe requests the run sent, counted once per request built rather than once per
  /// call site, so a probe client that is used somewhere new cannot go uncounted. The peer's
  /// applied-fault totals are read against it: the two together say whether the exemption held.
  public static final String PROBE_SENT = "rpc.probe.sent";

  /// The HTTP driver's own draw count, and the exact population `harness.opsSkipped.inflightCap`
  /// is drawn from: one increment per draw that reached the in-flight permit, whichever way the
  /// permit went. [Counters#HARNESS_OPS_ATTEMPTED] keeps its cross-driver meaning (every driver's
  /// launched operations) and is therefore the wrong denominator for a starvation percentage that
  /// only this driver's skips can enter — a ratio over it mixes websocket work into the divisor
  /// and can be read as headroom this driver never had.
  static final String HTTP_OPS_ATTEMPTED = "http.ops.attempted";

  /// A body that reached the parser and could not be read, counted apart from the socket deaths
  /// in `rpc.completed.transport`. The two share nothing but their shape: a peer that dropped the
  /// connection says nothing about decoding, while a run whose every well-formed body failed to
  /// inflate or parse is a regression in exactly the code W3-C watches. One counter for both made
  /// that regression unreadable in `counters.properties`, which is where it has to be visible.
  static final String RPC_COMPLETED_DECODE = "rpc.completed.decode";

  private final SoakContext ctx;
  private final Counters counters;
  private final URI endpoint;
  private final int workerCount;
  private final int inflightPerWorker;
  private final int ratePerSecond;
  private final double noWrapFraction;
  private final double cancelFraction;
  private final double largeFraction;
  private final Rpc[] mix;

  private final List<Subject> subjects;
  private final Subject plain;
  private final Subject gzip;
  private final SoakRawHttpClient raw;
  private final SolanaRpcClient deadlineClient;
  private final TokenBucket bucket;
  private final PublicKey[] keys;
  private final PublicKey programKey;
  private final List<PublicKey> liveAccounts;

  private final AtomicInteger inFlight;
  private final AtomicLong requestOrdinal;
  private final AtomicLong nonce;
  private final AtomicLong okOutcomes;
  private final AtomicBoolean running;
  private final AtomicBoolean overlapObserved;
  private final AtomicBoolean cancelObserved;
  private final AtomicBoolean errorObserved;
  private final List<Worker> workers;
  /// Every operation whose exchange may still be open, discoverable so that the gauge can ask how
  /// many of them are *late* and SHUTDOWN can cancel what is left. An entry is removed when its
  /// future completes rather than when [#settle] runs: the harness watchdog records a timeout and
  /// leaves the future alone on purpose, and an exchange nobody has released is exactly what both
  /// readers need to see.
  private final java.util.Set<Op> inFlightOps;
  private final java.util.Queue<CancellationCheck> pendingCancellations;
  private volatile ScheduledFuture<?> twinTask;

  private HttpWorkload(final SoakContext ctx,
                       final URI endpoint,
                       final int workerCount,
                       final int inflightPerWorker,
                       final int ratePerSecond,
                       final List<PublicKey> liveAccounts) {
    this.ctx = ctx;
    this.counters = ctx.counters();
    this.endpoint = endpoint;
    this.workerCount = workerCount;
    this.inflightPerWorker = inflightPerWorker;
    this.ratePerSecond = ratePerSecond;
    final var config = ctx.config();
    this.noWrapFraction = config.noWrapFraction();
    this.cancelFraction = config.cancelFraction();
    this.largeFraction = config.largeFraction();
    this.mix = Rpc.mix(ctx.live());
    this.keys = ctx.keyTable();
    this.programKey = keys[0];
    this.liveAccounts = liveAccounts;
    this.bucket = new TokenBucket(ratePerSecond);
    this.inFlight = new AtomicInteger();
    this.requestOrdinal = new AtomicLong();
    this.nonce = new AtomicLong();
    this.okOutcomes = new AtomicLong();
    this.running = new AtomicBoolean(true);
    this.overlapObserved = new AtomicBoolean();
    this.cancelObserved = new AtomicBoolean();
    this.errorObserved = new AtomicBoolean();
    this.workers = new ArrayList<>(workerCount);
    this.inFlightOps = java.util.concurrent.ConcurrentHashMap.newKeySet();
    this.pendingCancellations = new java.util.concurrent.ConcurrentLinkedQueue<>();

    final var httpClient = ctx.httpClient();
    // One plain client with no predicate at all: `wrapResponseParser` returns the parser
    // unwrapped when `testResponse` is null, which is a different code path through the subject
    // and would go untested if every client carried one.
    this.plain = new Subject(CLIENT_PLAIN, SolanaRpcClient.build()
        .endpoint(endpoint)
        .httpClient(httpClient)
        .defaultCommitment(Commitment.CONFIRMED)
        .createClient(), false);
    final var gzipSubject = new Subject(CLIENT_GZIP, null, true);
    gzipSubject.client = SolanaRpcClient.build()
        .endpoint(endpoint)
        .httpClient(httpClient)
        .defaultCommitment(Commitment.CONFIRMED)
        .compressResponses()
        .testResponse(gzipSubject::observe)
        .createClient();
    this.gzip = gzipSubject;
    final var extended = new Subject(CLIENT_EXTENDED, null, true);
    // The nonce header is for the local peer's log. Nothing of the harness reaches somebody
    // else's node (decided 2026-09-23), so a live run passes an identity operator through the
    // same seam: the subject still exercises extendRequest, and the provider sees a plain client.
    final UnaryOperator<HttpRequest.Builder> extendCall = ctx.live()
        ? request -> request
        : request -> request.header("X-Soak-Call", Long.toString(nonce.incrementAndGet()));
    extended.client = SolanaRpcClient.build()
        .endpoint(endpoint)
        .httpClient(httpClient)
        .defaultCommitment(Commitment.CONFIRMED)
        .extendRequest(extendCall)
        .testResponse(extended::observe)
        .createClient();
    this.subjects = List.of(plain, gzip, extended);
    for (final var subject : this.subjects) {
      subject.probeClient = probeClient(httpClient);
    }
    this.raw = new SoakRawHttpClient(endpoint, httpClient, SUBJECT_REQUEST_TIMEOUT);
    this.deadlineClient = SolanaRpcClient.build()
        .endpoint(endpoint)
        .httpClient(httpClient)
        .requestTimeout(W4C_REQUEST_TIMEOUT)
        .defaultCommitment(Commitment.CONFIRMED)
        .createClient();
  }

  /// A client whose every request carries [#PROBE_HEADER], so the peer's fault schedule steps over
  /// it.
  ///
  /// It is a *separate* `SolanaRpcClient` rather than a per-request flag on an existing one
  /// because `extendRequest` is a property of the client, not of the call, and reaching a
  /// per-call decision would mean assuming when the subject builds its request — an assumption
  /// about the library's internals that a harness asserting the library's contract must not make.
  /// What the separate instance costs is small and stated: it is built over the *same*
  /// caller-owned `HttpClient` and the same endpoint, so it shares the connection pool, the
  /// executor and the common-pool deadline timer — every piece of state a wedge could live in —
  /// and differs only in configuration, which `JsonHttpClient` holds immutably per instance.
  ///
  /// It carries no `testResponse`: a probe must not enter W3-F's numerator, and the ops issued on
  /// it are kept out of its denominator to match ([Op#wedgeProbe]).
  private SolanaRpcClient probeClient(final HttpClient httpClient) {
    return SolanaRpcClient.build()
        .endpoint(endpoint)
        .httpClient(httpClient)
        .defaultCommitment(Commitment.CONFIRMED)
        .extendRequest(this::markProbe)
        .createClient();
  }

  /// Counted here rather than at the call sites: `extendRequest` is applied once per request the
  /// client builds, so the count cannot drift from the number of requests that actually carried
  /// the header.
  private HttpRequest.Builder markProbe(final HttpRequest.Builder request) {
    counters.increment(PROBE_SENT);
    // The marker tells the local peer's fault schedule to step over a probe; a foreign node has
    // no schedule, and a live run sends it nothing of the harness's.
    return ctx.live() ? request : request.header(PROBE_HEADER, "1");
  }

  /// Builds the driver from the context alone. The endpoint is the controlled peer's HTTP port,
  /// or `SOAK_LIVE_HTTP_URL` in a live run — in which case every peer-established property is
  /// skipped with a stated reason rather than asserted against a node that never agreed to the
  /// contract.
  public static HttpWorkload create(final SoakContext ctx) {
    final var config = ctx.config();
    final URI endpoint = ctx.live()
        ? URI.create(config.liveHttpUrl())
        : URI.create("http://127.0.0.1:" + config.httpPort() + '/');
    final int workers = Math.max(1, ctx.live() ? config.liveConcurrency() : config.httpConcurrency());
    final int inflight = Math.max(1, HarnessControls.httpInflight(config));
    final int rate = Math.max(1, ctx.live() ? config.liveRps() : HarnessControls.httpRps(config));
    return new HttpWorkload(ctx, endpoint, workers, inflight, rate, liveAccounts(ctx));
  }

  private static List<PublicKey> liveAccounts(final SoakContext ctx) {
    if (!ctx.live()) {
      return List.of();
    }
    final var file = ctx.config().liveAccounts();
    final var accounts = new ArrayList<PublicKey>();
    try {
      for (final var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        final var trimmed = line.strip();
        if (!trimmed.isEmpty() && trimmed.charAt(0) != '#') {
          accounts.add(PublicKey.fromBase58Encoded(trimmed));
        }
      }
    } catch (final IOException | RuntimeException e) {
      throw new IllegalStateException("SOAK_LIVE_ACCOUNTS is required in a live run: " + file, e);
    }
    if (accounts.isEmpty()) {
      throw new IllegalStateException("SOAK_LIVE_ACCOUNTS holds no keys: " + file);
    }
    return List.copyOf(accounts);
  }

  // --- lifecycle --------------------------------------------------------------------------------

  /// Serves both [Workload#name()] and [GaugeSampler.GaugeSource#name()]: one driver, one name.
  @Override
  public String name() {
    return NAME;
  }

  @Override
  public void start(final SoakContext context) {
    declareSkips();
    ctx.registerGauge(this);
    for (int i = 0; i < workerCount; ++i) {
      final var worker = new Worker(i);
      workers.add(worker);
      worker.thread.start();
    }
    twinTask = ctx.timer().scheduleAtFixedRate(this::twinQuietly,
        TWIN_PERIOD.toMillis(), TWIN_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
  }

  /// A live run has no controlled peer, so the properties whose oracle *is* the peer are recorded
  /// as stated skips at the start rather than left to read as UNEXERCISED at the end. A skip
  /// without a reason is a FAIL, which is the rule that makes this worth doing up front.
  private void declareSkips() {
    final var properties = ctx.properties();
    if (ctx.live()) {
      final var reason = "live run: responses carry no peer stamp, so association cannot be checked";
      properties.notEvaluated("W3-A", reason);
      properties.notEvaluated("W3-C", reason);
      properties.notEvaluated("W3-E", reason);
      properties.notEvaluated("W4-C", "live run: the OVERLAP fan-out is not driven against a"
          + " third-party endpoint");
    }
    if (cancelFraction <= 0.0d) {
      properties.notEvaluated("W3-E", "SOAK_CANCEL_FRACTION is zero, so no cancellation was driven");
    }
  }

  @Override
  public void onPhase(final Phase phase) {
    switch (phase) {
      // A tenth of the rate, so the latency of an unloaded client is measured against the same
      // client under load rather than against another run.
      case HTTP_QUIET -> bucket.rate(Math.max(1, ratePerSecond / 10));
      case WARMUP, STEADY, HTTP_LOADED, FAULT_STORM -> bucket.rate(ratePerSecond);
      // A momentary trigger. The fan-out is submitted, never awaited: the phase thread owns the
      // whole schedule and a driver that blocks here delays every other driver's boundary.
      case OVERLAP -> ctx.timer().execute(this::overlapQuietly);
      case QUIESCE, DRAIN -> running.set(false);
      // Every property that reads an exchange has been decided by now, so an exchange still open
      // is only a hold on the JVM - and a peer stalling a body for minutes would outlive the run.
      case SHUTDOWN -> {
        running.set(false);
        cancelInFlight();
      }
      default -> {
        // STARTUP needs no rate change: the workers are started by `start` already paced.
      }
    }
  }

  @Override
  public void quiesce(final Duration bound) throws InterruptedException {
    running.set(false);
    final var twin = twinTask;
    if (twin != null) {
      twin.cancel(false);
    }
    final long deadline = System.nanoTime() + bound.toNanos();
    for (final var worker : workers) {
      worker.thread.interrupt();
    }
    for (final var worker : workers) {
      final long remaining = deadline - System.nanoTime();
      if (remaining > 0L) {
        worker.thread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
      }
    }
    while (inFlight.get() > 0 && deadline - System.nanoTime() > 0L) {
      LockSupport.parkNanos(POLL_PARK_NANOS);
    }
    flushCancellationChecks();
    evaluateTestResponse();
    declareUnexercised();
  }

  /// A property this run never reached is a *stated* skip, not an absence. The distinction is the
  /// whole point of the ledger: an unstated absence reads as UNEXERCISED and fails the run, which
  /// is what catches a driver that silently stopped driving.
  private void declareUnexercised() {
    final var properties = ctx.properties();
    if (!errorObserved.get() && properties.evaluated("W3-D") == 0L) {
      properties.notEvaluated("W3-D",
          "no error outcome was observed, so there was nothing for a wedge to follow");
    }
    if (!cancelObserved.get() && properties.evaluated("W3-E") == 0L) {
      properties.notEvaluated("W3-E", "no cancellation took effect: every future drawn at"
          + " SOAK_CANCEL_FRACTION had already completed when its cancellation was due");
    }
    if (!overlapObserved.get() && properties.evaluated("W4-C") == 0L) {
      properties.notEvaluated("W4-C", "the run ended before an OVERLAP overlay occurred");
    }
  }

  /// W3-F, evaluated once the in-flight set has drained: the predicate's invocation count must
  /// equal this side's count of completed default-route exchanges for that client.
  ///
  /// The tolerance is exactly the number of operations that never settled, counted on the subject
  /// being judged. An operation the client never completed cannot have reached the predicate, and
  /// asserting equality regardless would turn a wedge — which W3-D already reports — into a
  /// second, wrong finding about the predicate. The workload-wide in-flight count was the wrong
  /// number to read here: applied once per subject it widened each client's band by the whole
  /// run's outstanding set, including the other clients' and the no-wrap route's.
  private void evaluateTestResponse() {
    for (final var subject : subjects) {
      if (!subject.hasTestResponse) {
        continue;
      }
      final long invoked = subject.testResponseInvoked.sum();
      final long completed = subject.defaultRouteCompleted.sum();
      final long outstanding = Math.max(0L, subject.outstanding.sum());
      // A cancellation the harness drove (W3-E) settles the CALLER's future, not the exchange:
      // `cancel(true)` on a dependent stage leaves the source stage free to complete, and when
      // the response was already in flight the predicate still sees it once. Those exchanges are
      // "completed at the predicate and cancelled at the caller", so they widen the tolerance by
      // exactly their count, never by more — and only they do. The client's own exchange deadline
      // cancels the SOURCE future, which relays the cancellation to the parser stage instead of
      // running it, so a DEADLINE operation cannot have reached the predicate either; counting it
      // here was pure slack, and in a validate run five times the size of the justified term.
      final long harnessCancelled = subject.harnessCancelled.sum();
      if (invoked >= completed - outstanding && invoked <= completed + harnessCancelled + outstanding) {
        ctx.properties().pass("W3-F");
      } else {
        ctx.properties().fail("W3-F", subject.name + ": testResponse invoked " + invoked
            + " times for " + completed + " completed default-route exchanges, with " + outstanding
            + " still in flight, " + harnessCancelled + " cancelled by the harness and "
            + subject.deadlineSettled.sum() + " settled by the client's own exchange deadline");
      }
    }
  }

  @Override
  public void drain(final Duration bound) {
    if (ctx.live()) {
      ctx.properties().notEvaluated("W5-B1",
          "live run: the shared HttpClient is asserted only against the controlled peer");
      return;
    }
    // W5-B1: the shared client is the context's, and nothing this driver did may have closed it.
    // A brand-new SolanaRpcClient over it is the honest test, because it exercises the same
    // constructor path a caller would use after discarding the first one. It is a probe client:
    // the peer must not answer this question with a scheduled fault of its own.
    final var probe = probeClient(ctx.httpClient());
    final long millis = Math.max(1_000L, Math.min(bound.toMillis(), 10_000L));
    try {
      final Long slot = probe.getSlot().get(millis, TimeUnit.MILLISECONDS);
      if (slot == null) {
        ctx.properties().fail("W5-B1", "a fresh client on the shared HttpClient returned null");
      } else {
        ctx.properties().pass("W5-B1");
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      ctx.properties().fail("W5-B1", "interrupted while proving the shared HttpClient survived");
    } catch (final ExecutionException e) {
      final var cause = unwrap(e);
      if (ChurnWorkload.peerAnswered(cause)) {
        // The same rule the churn driver applies, and the same one helper: an error the peer
        // wrote back travelled through the shared HttpClient exactly as a success would have, so
        // it is evidence FOR the property. Reached only when the probe exemption did not hold -
        // and then it is still the answer W5-B1 asks for.
        ctx.properties().pass("W5-B1");
      } else {
        ctx.properties().fail("W5-B1", "a fresh client on the shared HttpClient failed after every"
            + " driver was drained, with no answer from the peer: " + describe(cause));
      }
    } catch (final TimeoutException e) {
      ctx.properties().fail("W5-B1", "a fresh client on the shared HttpClient did not settle within "
          + millis + " ms after every driver was drained; no status line came back");
    } catch (final RuntimeException e) {
      // A closed client answers here (IllegalStateException), as does a shut-down executor
      // (RejectedExecutionException): both are the direction the property exists to catch.
      ctx.properties().fail("W5-B1",
          "a fresh client on the shared HttpClient could not be used after every driver was"
              + " drained: " + describe(e));
    }
  }

  @Override
  public void close() {
    running.set(false);
    final var twin = twinTask;
    if (twin != null) {
      twin.cancel(false);
    }
    // Again, and not only from the phase boundary: a run that ended without reaching SHUTDOWN
    // still has to let go of whatever the peer is holding.
    cancelInFlight();
    // The HttpClient belongs to the context: W5-B1 asserts it survives this method, so closing it
    // here would be the harness disproving its own property.
  }

  // --- the worker loop --------------------------------------------------------------------------

  private final class Worker implements Runnable {

    private final int index;
    private final SplittableRandom random;
    private final Semaphore permits;
    private final Thread thread;

    private Worker(final int index) {
      this.index = index;
      this.random = Seeds.forWorker(ctx.seed(), index);
      this.permits = new Semaphore(inflightPerWorker);
      this.thread = new Thread(this, "soak-http-" + index);
      this.thread.setDaemon(true);
    }

    @Override
    public void run() {
      while (running.get()) {
        if (!bucket.acquire()) {
          // interrupted: quiesce is joining this thread
          break;
        }
        if (!running.get()) {
          break;
        }
        final boolean acquired;
        try {
          acquired = permits.tryAcquire(INFLIGHT_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
          // quiesce is joining this thread: the draw neither skipped nor issued, so it is not a
          // member of either population and is counted in neither.
          Thread.currentThread().interrupt();
          break;
        }
        // One draw, counted exactly once, whichever way the permit went. `opsSkipped.inflightCap`
        // is a strict subset of this population, so `skipped / attempted` is a fraction of draws
        // and can be compared with SOAK_SKIP_LIMIT_PCT as a percentage.
        counters.increment(HTTP_OPS_ATTEMPTED);
        if (!acquired) {
          counters.increment(Counters.HARNESS_OPS_SKIPPED_INFLIGHT);
          continue;
        }
        counters.increment(Counters.HARNESS_OPS_ATTEMPTED);
        boolean issued = false;
        try {
          issued = issue(this);
        } catch (final RuntimeException e) {
          System.err.println("soak: http worker " + index + " failed to issue: " + e);
        } finally {
          if (!issued) {
            permits.release();
          }
        }
      }
    }
  }

  /// Draws one operation and launches it. Returns false when nothing was launched, so the caller
  /// returns the permit rather than leaking it.
  private boolean issue(final Worker worker) {
    final var random = worker.random;
    final long ordinal = requestOrdinal.incrementAndGet();
    if (!ctx.live() && random.nextDouble() < noWrapFraction) {
      return launchNoWrap(worker, ordinal, random);
    }
    // SOAK_LARGE_FRACTION biases the draw toward the two methods whose responses are large. The
    // peer sizes `getBlock` and `getProgramAccounts` from its own configuration rather than from
    // a per-request marker, so "large" here means "ask for a large shape more often", not "ask
    // this one to be bigger" - see the gaps note in the handover.
    final var method = !ctx.live() && random.nextDouble() < largeFraction
        ? (random.nextBoolean() ? Rpc.GET_BLOCK : Rpc.GET_PROGRAM_ACCOUNTS)
        : mix[random.nextInt(mix.length)];
    final var subject = subjects.get(random.nextInt(subjects.size()));
    final boolean cancel = cancelFraction > 0.0d && random.nextDouble() < cancelFraction;
    return launch(worker, subject, method, ordinal, random, cancel);
  }

  private boolean launch(final Worker worker,
                         final Subject subject,
                         final Rpc method,
                         final long ordinal,
                         final SplittableRandom random,
                         final boolean cancel) {
    final Object expectation = expectation(method, random);
    final var event = new SoakEvents.RpcCall();
    event.begin();
    // The paced wrapped route is the only one that can carry a W3-D probe, so it is the only one
    // that claims from the countdown. The no-wrap route cannot (it is not a SolanaRpcClient at
    // all), and the twins and the OVERLAP fan-out must not: routing either through the probe
    // client would exempt the very requests W3-C and W4-C need the peer to fault.
    final boolean wedgeProbe = subject.claimWedgeProbe();
    final var op = new Op(subject, method, ordinal, expectation, event, "WRAPPED",
        worker.permits, method.bound(SUBJECT_REQUEST_TIMEOUT.toMillis()), wedgeProbe);
    final CompletableFuture<?> future;
    try {
      future = invoke(wedgeProbe ? subject.probeClient : subject.client, method, expectation);
    } catch (final RuntimeException e) {
      settle(op, null, e);
      return true;
    }
    arm(op, future);
    if (cancel) {
      scheduleCancellation(subject, op, future, random);
    }
    return true;
  }

  /// The caller-body-handler route. Driven to prove the path runs; explicitly excluded from W3-B,
  /// because `sendPostRequestNoWrap` carries only the JDK request timeout by contract.
  private boolean launchNoWrap(final Worker worker, final long ordinal, final SplittableRandom random) {
    final var key = keys[random.nextInt(keys.length)];
    final var event = new SoakEvents.RpcCall();
    event.begin();
    final var op = new Op(plain, Rpc.GET_ACCOUNT_INFO, ordinal, key, event, "NO_WRAP",
        worker.permits, Rpc.GET_ACCOUNT_INFO.bound(SUBJECT_REQUEST_TIMEOUT.toMillis()), false);
    op.noWrap = true;
    final CompletableFuture<HttpResponse<byte[]>> future;
    try {
      future = raw.getAccountInfoRaw(ordinal, key);
    } catch (final RuntimeException e) {
      settle(op, null, e);
      return true;
    }
    arm(op, future);
    return true;
  }

  /// A started duration event. `begin()` has to happen before the call is made, or the event
  /// times the settlement rather than the exchange.
  private static SoakEvents.RpcCall begun() {
    final var event = new SoakEvents.RpcCall();
    event.begin();
    return event;
  }

  private void arm(final Op op, final CompletableFuture<?> future) {
    op.future = future;
    op.watchdog = ctx.timer().schedule(() -> settle(op, null, new HarnessDeadline(op)),
        op.boundNanos, TimeUnit.NANOSECONDS);
    future.whenComplete((value, failure) -> {
      // The registry loses the operation here, not in `settle`: the watchdog settles an operation
      // the client is still holding and leaves its future running on purpose, and an exchange
      // nobody has released is exactly what the overdue gauge and the SHUTDOWN sweep exist to see.
      inFlightOps.remove(op);
      settle(op, value, failure);
    });
  }

  /// Admits an operation to the in-flight registry, or counts the refusal at the cap.
  private void track(final Op op) {
    if (inFlightOps.size() >= MAX_TRACKED_INFLIGHT) {
      counters.increment(Counters.HTTP_INFLIGHT_UNTRACKED);
      return;
    }
    inFlightOps.add(op);
  }

  /// Releases every exchange still open once the run has stopped asking questions.
  ///
  /// The per-operation watchdog records `TIMEOUT` and deliberately leaves the future alone, so
  /// without this a peer holding a body for minutes keeps its exchange — and the JVM — alive past
  /// the end of the run. Each cancellation is flagged as the harness's own before it is issued, so
  /// the settlement it may trigger is filed as harness-cancelled rather than as the client's
  /// exchange deadline; it is flagged as a teardown too, because a cancellation nobody's property
  /// is watching must not widen W3-F's tolerance.
  private void cancelInFlight() {
    for (final var op : inFlightOps) {
      final var future = op.future;
      if (future == null) {
        continue;
      }
      op.cancelDriven = true;
      op.shutdownCancel = true;
      if (future.cancel(true)) {
        counters.increment(Counters.RPC_SHUTDOWN_CANCELLED);
      }
    }
  }

  /// W3-E: a cancellation of the returned future, at a seeded delay inside the exchange window.
  ///
  /// The check that follows is the property: every *other* in-flight future on the same client
  /// must still satisfy W3-A, and none of them may wedge. The comparison is over the client's own
  /// mismatch and timeout counters across the window, which is the only reading that distinguishes
  /// "this cancellation disturbed its neighbours" from "the run had a mismatch somewhere".
  private void scheduleCancellation(final Subject subject,
                                    final Op op,
                                    final CompletableFuture<?> future,
                                    final SplittableRandom random) {
    // A few milliseconds, not a few seconds. Against loopback an exchange settles in tens of
    // milliseconds, and a cancellation scheduled past that lands on a future that is already
    // done - which cancels nothing and would make W3-E's denominator a count of intentions
    // rather than of cancellations.
    final long delayMillis = 1L + random.nextLong(CANCEL_DELAY_MAX_MILLIS);
    final long windowMillis = delayMillis + 2L * SUBJECT_REQUEST_TIMEOUT.toMillis()
        + 2L * DEADLINE_SLACK.toMillis();
    ctx.timer().schedule(() -> {
      // flagged before the cancel: `cancel` completes the future on this thread, and the settle
      // that runs from it has to see who cancelled
      op.cancelDriven = true;
      if (!future.cancel(true)) {
        return;
      }
      cancelObserved.set(true);
      final var check = new CancellationCheck(subject, windowMillis);
      if (pendingCancellations.size() >= MAX_PENDING_CANCELLATIONS) {
        // The queue is a harness structure and is bounded like every other one here. Dropping a
        // check is counted rather than silent: a run that dropped them is a run whose W3-E
        // denominator is smaller than its cancellations, and the report has to be able to say so.
        counters.increment(CANCEL_CHECKS_DROPPED);
      } else {
        pendingCancellations.add(check);
        ctx.timer().schedule(() -> settleCancellation(check), windowMillis, TimeUnit.MILLISECONDS);
      }
      // Drop whatever has already been decided, so the queue tracks outstanding windows rather
      // than the run's whole cancellation history.
      pendingCancellations.removeIf(CancellationCheck::decided);
    }, delayMillis, TimeUnit.MILLISECONDS);
  }

  /// One cancellation's W3-E window: the client's mismatch and timeout counts when the
  /// cancellation was armed, compared against the same counts when the window closes.
  private final class CancellationCheck {

    private final Subject subject;
    private final long windowMillis;
    private final long mismatchesBefore;
    private final long overdueBefore;
    private final AtomicBoolean decided = new AtomicBoolean();

    private CancellationCheck(final Subject subject, final long windowMillis) {
      this.subject = subject;
      this.windowMillis = windowMillis;
      this.mismatchesBefore = subject.mismatches.sum();
      this.overdueBefore = subject.overdueSettlements.sum();
    }

    private boolean decided() {
      return decided.get();
    }
  }

  private void settleCancellation(final CancellationCheck check) {
    if (!check.decided.compareAndSet(false, true)) {
      return;
    }
    final long mismatches = check.subject.mismatches.sum() - check.mismatchesBefore;
    final long overdue = check.subject.overdueSettlements.sum() - check.overdueBefore;
    if (mismatches == 0L && overdue == 0L) {
      ctx.properties().pass("W3-E");
    } else {
      ctx.properties().fail("W3-E", check.subject.name + ": a cancellation was followed by "
          + mismatches + " association mismatches and " + overdue
          + " exchanges on the same client left unsettled past their own bound within "
          + check.windowMillis + " ms");
    }
  }

  /// Closes every window still open at quiesce rather than waiting for its timer.
  ///
  /// Without this a short run cancels futures, never reaches the end of their windows, and then
  /// reports W3-E as UNEXERCISED - which reads as a FAIL about the subject when it is really a
  /// statement about the run's length. The evidence is already complete at quiesce: in-flight
  /// work has drained, so nothing further can change either count.
  private void flushCancellationChecks() {
    for (final var check : pendingCancellations) {
      settleCancellation(check);
    }
    pendingCancellations.clear();
  }

  // --- settlement and verification ----------------------------------------------------------------

  private void settle(final Op op, final Object value, final Throwable failure) {
    if (!op.settled.compareAndSet(false, true)) {
      return;
    }
    final var watchdog = op.watchdog;
    if (watchdog != null && !(failure instanceof HarnessDeadline)) {
      watchdog.cancel(false);
    }
    inFlight.decrementAndGet();
    if (op.future == null) {
      // A launch that threw never reached `arm`, so no completion will ever take it out.
      inFlightOps.remove(op);
    }
    if (!op.wedgeProbe) {
      op.subject.outstanding.decrement();
    }
    if (op.permits != null) {
      op.permits.release();
    }
    final long elapsedNanos = System.nanoTime() - op.startNanos;

    Outcome outcome;
    String detail = null;
    if (failure instanceof HarnessDeadline) {
      op.harnessTimeout = true;
      outcome = Outcome.TIMEOUT;
      detail = "the client never settled within the harness bound of "
          + TimeUnit.NANOSECONDS.toMillis(op.boundNanos) + " ms";
    } else if (failure != null) {
      final var cause = unwrap(failure);
      outcome = classify(cause);
      detail = describe(cause);
      if (outcome == Outcome.CANCELLED && !op.cancelDriven) {
        outcome = Outcome.DEADLINE;
        detail = "cancelled by the client's exchange deadline (2 x requestTimeout = "
            + TimeUnit.NANOSECONDS.toMillis(op.exchangeDeadlineNanos) + " ms): " + detail;
      }
      if (outcome == Outcome.TRANSPORT && op.noWrap) {
        // The raw route has no deadline timer, so a body the peer holds open surfaces here as a
        // transport failure rather than a cancellation. Recorded, never asserted.
        detail = "no-wrap route: " + detail;
      }
      if (outcome == Outcome.DECODE
          || (cause instanceof UncheckedIOException unchecked
          && !(unchecked.getCause() instanceof UnknownServiceException))) {
        recordCleanDecodingFailure(op, outcome, cause);
      }
    } else if (op.noWrap) {
      @SuppressWarnings("unchecked") final var response = (HttpResponse<byte[]>) value;
      op.httpStatus = response.statusCode();
      op.responseBytes = response.body() == null ? 0L : response.body().length;
      final var encoding = response.headers().firstValue("Content-Encoding").orElse(null);
      if (response.statusCode() / 100 != 2) {
        // the peer's HTTP_429 / HTTP_503 faults reach this route as a status, not an exception
        outcome = Outcome.HTTP_ERROR;
        detail = "no-wrap route: status " + response.statusCode();
      } else if (encoding != null) {
        counters.increment(NOWRAP_ENCODED);
        op.encoding = encoding;
        outcome = Outcome.OK;
        detail = null;
      } else {
        final String mismatch = verifyRaw(response.body(), (PublicKey) op.expectation);
        outcome = mismatch == null ? Outcome.OK : Outcome.MISMATCH;
        detail = mismatch;
      }
    } else {
      final String mismatch = verify(op.method, op.expectation, value);
      outcome = mismatch == null ? Outcome.OK : Outcome.MISMATCH;
      detail = mismatch;
    }

    record(op, outcome, elapsedNanos, detail);
  }

  private void record(final Op op, final Outcome outcome, final long elapsedNanos, final String detail) {
    final var subject = op.subject;
    counters.increment(outcome.counter);
    counters.record(Counters.rpcLatency(op.method.wireName), elapsedNanos);
    if (op.noWrap) {
      counters.increment(Counters.RPC_NOWRAP_COMPLETED);
    }
    if (outcome == Outcome.MISMATCH) {
      subject.mismatches.increment();
    } else if (outcome == Outcome.TIMEOUT && elapsedNanos > op.overdueAfterNanos()) {
      // W3-E asks whether a cancellation disturbed its neighbours, so only a neighbour that is
      // LATE is evidence. `getProgramAccounts` carries a two-minute request timeout and a harness
      // bound shorter than the exchange deadline it doubles to, so its watchdog settlement is a
      // call still legitimately running, not a wedged one - counting it made a pending large
      // response read as cancellation fallout.
      subject.overdueSettlements.increment();
    } else if (outcome == Outcome.CANCELLED) {
      // Neither this nor DEADLINE reaches testResponse, and W3-F's accounting needs both out of
      // its denominator - which [Outcome#defaultRouteCompletion] already does. Only the
      // harness-driven cancellation also widens W3-F's numerator tolerance, because only it
      // cancels a DEPENDENT stage whose source may already have run the predicate.
      if (op.cancelDriven && !op.shutdownCancel && !op.noWrap) {
        subject.harnessCancelled.increment();
      }
    } else if (outcome == Outcome.DEADLINE) {
      // The client's own deadline cancels the source future, so the parser stage - and with it
      // the predicate - never runs. Counted for the finding text, never as tolerance.
      subject.deadlineSettled.increment();
    }
    // A W3-D probe travels on the subject's probe client, which carries no `testResponse`, so it
    // is not a completion the predicate could have seen. Counting it here would make W3-F fail on
    // the harness's own routing.
    if (outcome.defaultRouteCompletion && !op.noWrap && !op.wedgeProbe) {
      subject.defaultRouteCompleted.increment();
    }

    evaluateAssociation(op, outcome, detail);
    evaluateDeadline(op, outcome, elapsedNanos);
    evaluateWedge(op, outcome, elapsedNanos, detail);
    commit(op, outcome, elapsedNanos, detail);
  }

  /// W3-A. A mismatch is the finding this whole driver exists to be able to see, so the example
  /// names the method, the argument and the stamp rather than the exception text.
  private void evaluateAssociation(final Op op, final Outcome outcome, final String detail) {
    if (ctx.live() || !op.method.verifiable) {
      return;
    }
    if (outcome == Outcome.OK) {
      ctx.properties().pass("W3-A");
    } else if (outcome == Outcome.MISMATCH) {
      ctx.properties().fail("W3-A", op.method.wireName + '(' + op.expectation + ") on "
          + op.subject.name + ": " + detail);
    }
  }

  /// W3-B for the default routes, and W4-C for the short-timeout probe client.
  ///
  /// The caller-body-handler route is skipped here by contract, not by omission: asserting a
  /// deadline there would be the harness imposing a promise the library declines to make.
  ///
  /// An overshoot fails at the bound itself. The earlier form passed at the bound and failed only
  /// past twice it, which left a band that recorded neither: a client whose exchange deadline
  /// fired consistently a few seconds late produced passes from the fast calls, nothing at all
  /// from the slow ones, and a green ledger. Worse, the doubled threshold was out of reach on
  /// every route the harness drives - its own watchdog settles a default-route operation at
  /// `2 x requestTimeout + 5 s`, so an elapsed time above twice the bound could not be observed
  /// - which made this a property that could pass or stay silent but never fail. The bound the
  /// ledger publishes (the client's exchange deadline plus [#DEADLINE_SLACK]) is now the bound it
  /// enforces, and `rpc.deadline.missed` keeps counting the same overshoots for the report.
  private void evaluateDeadline(final Op op, final Outcome outcome, final long elapsedNanos) {
    if (op.noWrap) {
      return;
    }
    final long bound = op.exchangeDeadlineNanos + DEADLINE_SLACK.toNanos();
    if (op.harnessTimeout && op.boundNanos < bound) {
      // The harness gave up before the client's own deadline was due - `getProgramAccounts`
      // carries a 120 s request timeout and a 125 s harness bound. Nothing was observed about the
      // deadline either way, so nothing is recorded: a pass here would be the harness certifying
      // a bound it never waited for.
      return;
    }
    if (elapsedNanos <= bound) {
      counters.increment(Counters.RPC_DEADLINE_HONOURED);
      ctx.properties().pass(op.deadlineProperty);
    } else if (op.w4c && op.w4cSentinelLateNanos != Long.MIN_VALUE
        && op.w4cSentinelLateNanos > DEADLINE_SLACK.toNanos()) {
      // The bare timer beside this probe was itself late past the slack: the common pool did
      // not run scheduled work in time, so nothing here is evidence about the client's timer.
      // Counted and named rather than passed or failed.
      counters.increment(Counters.RPC_DEADLINE_MISSED);
      counters.increment(Counters.HTTP_W4C_POOL_STARVED);
    } else {
      counters.increment(Counters.RPC_DEADLINE_MISSED);
      ctx.properties().fail(op.deadlineProperty, op.method.wireName + " on " + op.subject.name
          + " settled " + outcome + " after " + TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
          + " ms, past the " + TimeUnit.NANOSECONDS.toMillis(bound) + " ms bound ("
          + TimeUnit.NANOSECONDS.toMillis(op.exchangeDeadlineNanos) + " ms exchange deadline plus "
          + DEADLINE_SLACK.toMillis() + " ms of slack)"
          + (op.w4c ? "; sentinel timer on the common pool "
              + (op.w4cSentinelLateNanos == Long.MIN_VALUE ? "had not fired"
                  : "fired " + TimeUnit.NANOSECONDS.toMillis(Math.max(0L, op.w4cSentinelLateNanos)) + " ms late")
              + ", pool queued=" + ForkJoinPool.commonPool().getQueuedSubmissionCount()
              + " active=" + ForkJoinPool.commonPool().getActiveThreadCount() : ""));
    }
    if (op.w4c) {
      // How late the exchange deadline itself was, measured only when the client's own timer is
      // what settled it: a fast success says nothing about the timer's timeliness.
      if (outcome == Outcome.DEADLINE || outcome == Outcome.TIMEOUT) {
        final long late = elapsedNanos - op.exchangeDeadlineNanos;
        if (late > 0L) {
          counters.record(Counters.HIST_HTTP_DEADLINE_LATE, late);
        }
      }
    }
  }

  /// W3-D: the three operations that follow an error on a client must SETTLE inside the client's
  /// exchange deadline plus 2 s. Settle, not succeed: the property is that an error does not wedge the
  /// client, and an operation the client answers promptly - even with another error, because the
  /// peer's next scheduled fault landed on it, or because the JDK's pool handed out a keep-alive
  /// socket the peer had just dropped (`HTTP/1.1 header parser received no bytes`, settled in a
  /// millisecond) - is the opposite of wedged. Only a harness deadline or a settlement past the
  /// bound fails it. The probe counter is set by the error and consumed by the successors, so the
  /// property is evaluated on the client that failed rather than on the run.
  private void evaluateWedge(final Op op, final Outcome outcome, final long elapsedNanos, final String detail) {
    final var subject = op.subject;
    if (op.wedgeProbe) {
      // the client's own exchange deadline is 2 x requestTimeout (JsonHttpClient), so a stalled
      // probe settles there, not at requestTimeout: the bound follows the documented deadline
      final long bound = TimeUnit.NANOSECONDS.toMillis(op.exchangeDeadlineNanos) + DEADLINE_SLACK.toMillis();
      // The client's own `HttpTimeoutException` is an answer to its caller, not a wedge: a probe
      // that raises one 8 s into an 18 s bound settled, and excluding the whole TIMEOUT class
      // failed it for doing what the property asks. `op.harnessTimeout` is the only state in which
      // nothing was observed to settle at all - the harness stopped waiting - and it is set on
      // exactly that path.
      final boolean settled = !op.harnessTimeout;
      if (settled && TimeUnit.NANOSECONDS.toMillis(elapsedNanos) <= bound) {
        ctx.properties().pass("W3-D");
        if (outcome.error) {
          counters.increment(WEDGE_PROBE_ERRORED);
        }
      } else {
        ctx.properties().fail("W3-D", subject.name + ": the operation after an error settled "
            + outcome + " in " + TimeUnit.NANOSECONDS.toMillis(elapsedNanos) + " ms (bound "
            + bound + " ms): " + detail);
      }
    }
    if (outcome.error) {
      errorObserved.set(true);
      subject.wedgeProbes.set(WEDGE_PROBES);
      counters.increment(Counters.FAULTS_OBSERVED);
      faultObserved(op, outcome, detail);
    }
  }

  private void faultObserved(final Op op, final Outcome outcome, final String detail) {
    final var event = new SoakEvents.FaultObserved();
    if (event.shouldCommit()) {
      event.kind = outcome.name();
      event.ordinal = op.ordinal;
      event.scope = "RPC";
      event.target = op.method.wireName;
      event.detail = detail;
      event.commit();
    }
  }

  private void commit(final Op op, final Outcome outcome, final long elapsedNanos, final String detail) {
    final var event = op.event;
    event.end();
    if (event.shouldCommit()) {
      event.client = op.subject.name;
      event.method = op.method.wireName;
      event.requestId = op.ordinal;
      event.outcome = outcome.name();
      event.httpStatus = op.httpStatus;
      event.responseBytes = op.responseBytes;
      event.gzip = op.gzip;
      event.encoding = op.encoding;
      event.route = op.route;
      // The peer's injected delay is not knowable from this side; the report joins it from
      // `peer-http.tsv` by request ordinal rather than the harness guessing.
      event.peerDelayMillis = -1L;
      event.faultKind = outcome.error ? outcome.name() : "-";
      event.commit();
    }
    if (outcome != Outcome.OK || okOutcomes.incrementAndGet() % OUTCOME_SAMPLE == 0L) {
      final var outcomeEvent = new SoakEvents.RpcOutcome();
      if (outcomeEvent.shouldCommit()) {
        outcomeEvent.method = op.method.wireName;
        outcomeEvent.outcome = outcome.name();
        outcomeEvent.requestId = op.ordinal;
        outcomeEvent.faultKind = outcome.error ? outcome.name() : "-";
        outcomeEvent.elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        outcomeEvent.failure = detail == null ? "-" : truncate(detail);
        outcomeEvent.commit();
      }
    }
  }

  /// A gzip or truncation failure that surfaced cleanly is a W3-C pass, not a finding: the
  /// property the design states is that a malformed body fails cleanly, and only a *successfully
  /// returned wrong value* is the defect.
  ///
  /// That pass is only half an oracle on its own, and this is the other half's client side. "The
  /// body was malformed and the client said so" is a pass exactly when *something malformed it*;
  /// a clean refusal of a body the peer served whole is a decoding regression wearing the same
  /// exception. This side cannot tell the two apart — the fault the peer applied to a request
  /// lives in `peer-http.tsv` and `faults-applied.tsv`, which only the report reads — so this
  /// side's whole job is to leave a row the report can join: one `sava.soak.RpcOutcome` with
  /// outcome `DECODE`, carrying the method and this harness's own request ordinal, and one
  /// increment of `rpc.completed.decode`. What the report subtracts from those is the peer's
  /// applied `BAD_GZIP` / `GZIP_TRAILING` / `TRUNCATE_BODY` / `BAD_CONTENT_LENGTH` rows; whatever
  /// is left is a decoding failure nobody injected.
  ///
  /// Exactly once per request, on either path that reaches here. An operation whose own outcome is
  /// already [Outcome#DECODE] gets both the counter and the event from its ordinary settlement
  /// ([#record] and [#commit]); one the client wrapped in an `UncheckedIOException` classifies as
  /// [Outcome#TRANSPORT] instead — it never reached the predicate, which is what that outcome
  /// records for W3-F — and so would otherwise appear in neither. It is stamped here, and only
  /// there, because a duplicated row would be a decoding failure counted twice on one side of the
  /// join and would read as unexplained.
  private void recordCleanDecodingFailure(final Op op, final Outcome outcome, final Throwable failure) {
    if (ctx.live()) {
      return;
    }
    final var cause = failure instanceof UncheckedIOException unchecked && unchecked.getCause() != null
        ? unchecked.getCause() : failure;
    final var name = cause.getClass().getName();
    if (name.contains("Zip") || name.contains("DataFormat")) {
      counters.increment(Counters.RPC_GZIP_BAD_CLEAN);
    } else {
      counters.increment(Counters.RPC_GZIP_TRUNCATED_CLEAN);
    }
    if (outcome != Outcome.DECODE) {
      counters.increment(RPC_COMPLETED_DECODE);
      commitDecodeOutcome(op, cause);
    }
    if (op.subject.verifiesDecoding) {
      ctx.properties().pass("W3-C");
    }
  }

  /// The joinable row for a clean decoding failure the outcome taxonomy files elsewhere. Same
  /// event, same fields and same `requestId` key as the one an [Outcome#DECODE] settlement
  /// commits, so the report reads one population rather than two.
  private void commitDecodeOutcome(final Op op, final Throwable cause) {
    final var event = new SoakEvents.RpcOutcome();
    if (event.shouldCommit()) {
      event.method = op.method.wireName;
      event.outcome = Outcome.DECODE.name();
      event.requestId = op.ordinal;
      event.faultKind = Outcome.DECODE.name();
      event.elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - op.startNanos);
      event.failure = describe(cause);
      event.commit();
    }
  }

  // --- the method table ---------------------------------------------------------------------------

  private Object expectation(final Rpc method, final SplittableRandom random) {
    return switch (method) {
      case GET_ACCOUNT_INFO -> accountKey(random);
      case GET_MULTIPLE_ACCOUNTS -> {
        final var chosen = new ArrayList<PublicKey>(MULTI_ACCOUNTS_PER_CALL);
        for (int i = 0; i < MULTI_ACCOUNTS_PER_CALL; ++i) {
          chosen.add(accountKey(random));
        }
        yield List.copyOf(chosen);
      }
      case GET_PROGRAM_ACCOUNTS -> programKey;
      case GET_BLOCK -> RpcOracle.BASE_SLOT + random.nextLong(1L << 20);
      case GET_SIGNATURE_STATUSES -> {
        final var signatures = new ArrayList<String>(SIGNATURES_PER_CALL);
        for (int i = 0; i < SIGNATURES_PER_CALL; ++i) {
          final byte[] bytes = new byte[64];
          random.nextBytes(bytes);
          signatures.add(Base58.encode(bytes));
        }
        yield List.copyOf(signatures);
      }
      default -> null;
    };
  }

  private PublicKey accountKey(final SplittableRandom random) {
    return liveAccounts.isEmpty()
        ? keys[random.nextInt(keys.length)]
        : liveAccounts.get(random.nextInt(liveAccounts.size()));
  }

  @SuppressWarnings("unchecked")
  private CompletableFuture<?> invoke(final SolanaRpcClient client, final Rpc method, final Object expectation) {
    return switch (method) {
      case GET_ACCOUNT_INFO -> client.getAccountInfo((PublicKey) expectation);
      // `getAccounts`, not `getMultipleAccounts`: the two send a byte-identical request and differ
      // only in the parser, and only `getAccounts` keeps the result aligned with the keys, which
      // is the alignment W3-A checks. See CONVENTIONS.md, "How absence is represented".
      case GET_MULTIPLE_ACCOUNTS -> client.getAccounts((List<PublicKey>) expectation);
      case GET_PROGRAM_ACCOUNTS -> client.getProgramAccounts((PublicKey) expectation);
      case GET_BLOCK -> client.getBlock((Long) expectation);
      case GET_SIGNATURE_STATUSES -> client.getSignatureStatuses((List<String>) expectation);
      case GET_LATEST_BLOCK_HASH -> client.getLatestBlockHash();
      case GET_SLOT -> client.getSlot();
      case GET_HEALTH -> client.getHealth();
      case GET_VERSION -> client.getVersion();
    };
  }

  /// The W3-A oracle, one branch per method the peer stamps. Returns null on agreement, or the
  /// description that goes into the finding note.
  @SuppressWarnings("unchecked")
  private String verify(final Rpc method, final Object expectation, final Object value) {
    if (value == null) {
      return "the client returned null";
    }
    if (ctx.live()) {
      return null;
    }
    return switch (method) {
      case GET_ACCOUNT_INFO -> verifyAccount((AccountInfo<byte[]>) value, (PublicKey) expectation);
      case GET_MULTIPLE_ACCOUNTS -> {
        final var keysAsked = (List<PublicKey>) expectation;
        final var accounts = (List<AccountInfo<byte[]>>) value;
        if (accounts.size() != keysAsked.size()) {
          yield "asked for " + keysAsked.size() + " accounts, received " + accounts.size();
        }
        for (int i = 0; i < keysAsked.size(); ++i) {
          final var mismatch = verifyAccount(accounts.get(i), keysAsked.get(i));
          if (mismatch != null) {
            yield "index " + i + ": " + mismatch;
          }
        }
        yield null;
      }
      case GET_PROGRAM_ACCOUNTS -> verifyProgramAccounts((List<AccountInfo<byte[]>>) value,
          (PublicKey) expectation);
      case GET_BLOCK -> {
        final var block = (Block) value;
        final long slot = (Long) expectation;
        if (block.blockHeight() != slot) {
          yield "asked for slot " + slot + ", block reports height " + block.blockHeight();
        }
        yield block.parentSlot() == slot - 1 ? null
            : "slot " + slot + " reports parentSlot " + block.parentSlot();
      }
      case GET_SIGNATURE_STATUSES -> {
        final var signatures = (List<String>) expectation;
        final var statuses = (Map<String, TxStatus>) value;
        for (final var signature : signatures) {
          final var status = statuses.get(signature);
          if (status == null) {
            yield "no status for the signature asked about";
          }
          final long expected = RpcOracle.signatureSlot(signature);
          if (status.slot() != expected) {
            yield "signature status slot " + status.slot() + ", expected " + expected;
          }
        }
        yield null;
      }
      case GET_SLOT -> (Long) value >= RpcOracle.BASE_SLOT ? null
          : "slot " + value + " is below the peer's base slot";
      case GET_LATEST_BLOCK_HASH -> ((LatestBlockHash) value).blockHash() == null
          ? "no blockhash in the response" : null;
      // Rate and no-wedge only: the design's table marks these N/A for association.
      case GET_HEALTH, GET_VERSION -> null;
    };
  }

  private String verifyAccount(final AccountInfo<byte[]> account, final PublicKey key) {
    if (account == null) {
      return "no account for " + key.toBase58();
    }
    final byte[] stamp = account.data();
    if (!RpcOracle.stampIntact(stamp)) {
      return "the stamp did not survive the round trip for " + key.toBase58() + ": "
          + RpcOracle.describeStamp(stamp);
    }
    final long expected = RpcOracle.fnv64(key.toBase58());
    if (RpcOracle.stampKeyHash(stamp) != expected) {
      return "the response for " + key.toBase58() + " carries another key's stamp: "
          + RpcOracle.describeStamp(stamp);
    }
    if (!RpcOracle.OWNER.equals(account.owner() == null ? null : account.owner().toBase58())) {
      return "the account for " + key.toBase58() + " reports owner "
          + (account.owner() == null ? "null" : account.owner().toBase58());
    }
    return null;
  }

  private String verifyProgramAccounts(final List<AccountInfo<byte[]>> accounts, final PublicKey program) {
    final int expectedCount = ctx.config().pgaAccounts();
    if (accounts.size() != expectedCount) {
      return "the program response holds " + accounts.size() + " accounts, expected " + expectedCount;
    }
    final var programKey58 = program.toBase58();
    final long expectedHash = RpcOracle.fnv64(programKey58);
    final int[] samples = new int[PGA_SAMPLES];
    for (int s = 0; s < PGA_SAMPLES; ++s) {
      samples[s] = (int) ((long) s * (accounts.size() - 1) / Math.max(1, PGA_SAMPLES - 1));
    }
    for (final int i : samples) {
      final var account = accounts.get(i);
      final byte[] stamp = account.data();
      if (!RpcOracle.stampIntact(stamp) || RpcOracle.stampKeyHash(stamp) != expectedHash) {
        return "program account " + i + " is not stamped for " + programKey58 + ": "
            + RpcOracle.describeStamp(stamp);
      }
      final var derived = RpcOracle.derivedKey(programKey58, i);
      final var actual = account.pubKey() == null ? null : account.pubKey().toBase58();
      if (!derived.equals(actual)) {
        return "program account " + i + " has key " + actual + ", expected " + derived;
      }
    }
    return null;
  }

  /// The raw route's body is the peer's own bytes, undecoded. Only the envelope is checked: this
  /// route asserts no deadline and no decoding, so a full stamp check here would be claiming a
  /// property the design explicitly declines to claim for it.
  private String verifyRaw(final byte[] body, final PublicKey key) {
    if (body == null || body.length == 0) {
      return "the no-wrap route returned an empty body for " + key.toBase58();
    }
    counters.add(Counters.RPC_BYTES_RECEIVED, body.length);
    final var text = new String(body, StandardCharsets.UTF_8);
    return text.indexOf("\"jsonrpc\"") >= 0 ? null
        : "the no-wrap route returned a body with no JSON-RPC envelope";
  }

  // --- W3-C twins and the OVERLAP fan-out ---------------------------------------------------------

  private void twinQuietly() {
    try {
      twin();
    } catch (final RuntimeException e) {
      System.err.println("soak: the W3-C twin failed to launch: " + e);
    }
  }

  /// W3-C's cross-client half: the same key asked of the plain client and the gzip client, and the
  /// key-derived projection of the two answers compared.
  ///
  /// The two bodies can never be byte-equal — each stamp carries its own request id and the peer's
  /// own sequence — so the comparison is over the part of the account that is a function of the
  /// key. A gzip round trip that corrupted the key or the owner shows up; one that merely
  /// renumbered does not, and must not.
  private void twin() {
    if (!running.get() || ctx.live()) {
      return;
    }
    final long ordinal = requestOrdinal.incrementAndGet();
    final var key = keys[(int) (ordinal % keys.length)];
    // Both halves go through the ordinary operation machinery, so a twin counts towards the same
    // outcome, latency and default-route totals as any other call. Routing them around it made
    // W3-F fail on the harness's own bookkeeping: the predicate saw two responses this side had
    // never counted.
    final var plainFuture = plain.client.getAccountInfo(key);
    arm(new Op(plain, Rpc.GET_ACCOUNT_INFO, ordinal, key, begun(), "WRAPPED", null,
        Rpc.GET_ACCOUNT_INFO.bound(SUBJECT_REQUEST_TIMEOUT.toMillis()), false), plainFuture);
    final var gzipFuture = gzip.client.getAccountInfo(key);
    arm(new Op(gzip, Rpc.GET_ACCOUNT_INFO, ordinal, key, begun(), "WRAPPED", null,
        Rpc.GET_ACCOUNT_INFO.bound(SUBJECT_REQUEST_TIMEOUT.toMillis()), false), gzipFuture);
    plainFuture.thenCombine(gzipFuture, (left, right) -> {
      final long leftProjection = projection(left);
      final long rightProjection = projection(right);
      if (leftProjection == rightProjection && leftProjection != -1L) {
        ctx.properties().pass("W3-C");
        counters.increment(Counters.RPC_GZIP_OK);
      } else {
        ctx.properties().fail("W3-C", "twin " + ordinal + " for " + key.toBase58()
            + ": the plain client decoded " + leftProjection + ", the gzip client "
            + rightProjection);
      }
      return null;
    }).exceptionally(failure -> {
      // Either twin failing cleanly is a pass for the malformed sub-cases; the per-response check
      // in `settle` has already classified which kind of clean failure it was.
      return null;
    });
  }

  private static long projection(final AccountInfo<byte[]> account) {
    if (account == null) {
      return -1L;
    }
    return RpcOracle.keyProjection(account.data(),
        account.owner() == null ? null : account.owner().toBase58(), account.space());
  }

  private void overlapQuietly() {
    try {
      overlap();
    } catch (final RuntimeException e) {
      System.err.println("soak: the OVERLAP fan-out failed to launch: " + e);
    }
  }

  /// The OVERLAP overlay: a simultaneous fan-out of large responses, so that the common pool is
  /// busy decoding while W4-C's short-deadline probes are timing themselves against it. Both are
  /// launched together rather than in sequence, because the question is whether the parser and
  /// the deadline timer contend — and they only contend if they overlap.
  private void overlap() {
    if (!running.get() || ctx.live()) {
      return;
    }
    overlapObserved.set(true);
    for (int i = 0; i < OVERLAP_FANOUT; ++i) {
      final long ordinal = requestOrdinal.incrementAndGet();
      final var event = new SoakEvents.RpcCall();
      event.begin();
      final var op = new Op(plain, Rpc.GET_PROGRAM_ACCOUNTS, ordinal, programKey, event, "WRAPPED",
          null, PGA_BOUND.toNanos(), false);
      arm(op, plain.client.getProgramAccounts(programKey));
    }
    for (int i = 0; i < W4C_CALLS; ++i) {
      final long ordinal = requestOrdinal.incrementAndGet();
      final var event = new SoakEvents.RpcCall();
      event.begin();
      final var op = new Op(plain, Rpc.GET_SLOT, ordinal, null, event, "WRAPPED", null,
          2L * W4C_REQUEST_TIMEOUT.toNanos() + OP_GRACE.toNanos(), false);
      op.w4c = true;
      op.exchangeDeadlineNanos = 2L * W4C_REQUEST_TIMEOUT.toNanos();
      op.deadlineProperty = "W4-C";
      // A sentinel beside the probe: the same delay, scheduled the same way sava schedules its
      // cancel (JDK 25's ForkJoinPool scheduler, on the common pool the JDK also re-dispatches
      // every sendAsync completion to). A late deadline next to a timely sentinel is the
      // cancel-to-settlement path being slow; next to a late sentinel it is the pool itself,
      // which is the caller's environment and not a property of the client.
      final long armedNanos = System.nanoTime();
      op.w4cSentinelLateNanos = Long.MIN_VALUE;
      ForkJoinPool.commonPool().schedule(() -> {
        final long late = System.nanoTime() - armedNanos - op.exchangeDeadlineNanos;
        op.w4cSentinelLateNanos = late;
        counters.record(Counters.HIST_HTTP_W4C_SENTINEL_LATE, Math.max(0L, late));
      }, op.exchangeDeadlineNanos, TimeUnit.NANOSECONDS);
      arm(op, deadlineClient.getSlot());
    }
  }

  // --- classification -----------------------------------------------------------------------------

  private static Throwable unwrap(final Throwable failure) {
    var cause = failure;
    while ((cause instanceof CompletionException || cause instanceof ExecutionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  /// Which failures mean "the exchange completed and the client read it" and which mean "the
  /// exchange never got there". The distinction is what makes W3-F countable from this side: the
  /// predicate can only have been invoked for the first kind.
  private static Outcome classify(final Throwable cause) {
    if (cause instanceof CancellationException) {
      return Outcome.CANCELLED;
    } else if (cause instanceof HttpTimeoutException) {
      return Outcome.TIMEOUT;
    } else if (cause instanceof JsonRpcException) {
      return Outcome.RPC_ERROR;
    } else if (cause instanceof UncheckedIOException unchecked) {
      // The client turns a non-2xx status into an UncheckedIOException wrapping an
      // UnknownServiceException; anything else wrapped here came out of reading or inflating the
      // body, which happens before the predicate and is therefore not a completion.
      return unchecked.getCause() instanceof UnknownServiceException
          ? Outcome.HTTP_ERROR : Outcome.TRANSPORT;
    } else if (cause instanceof IOException) {
      return Outcome.TRANSPORT;
    } else {
      // A body that reached the parser and could not be read: the peer corrupts bodies on
      // purpose and refusing one is correct behaviour, so this is never an association finding.
      return Outcome.DECODE;
    }
  }

  private static String describe(final Throwable cause) {
    if (cause instanceof JsonRpcException rpc) {
      return "JSON-RPC error " + rpc.code() + ": " + truncate(String.valueOf(rpc.getMessage()));
    }
    return cause.getClass().getSimpleName() + ": " + truncate(String.valueOf(cause.getMessage()));
  }

  private static String truncate(final String text) {
    return text.length() <= 240 ? text : text.substring(0, 240);
  }

  // --- gauge ----------------------------------------------------------------------------------

  @Override
  public long liveSubscriptions() {
    return -1L;
  }

  @Override
  public long pendingConfirmations() {
    return -1L;
  }

  @Override
  public long inFlightRequests() {
    return inFlight.get();
  }

  /// The late subset of the count above, and the only one of the two that is evidence about the
  /// subject. An operation is late once it has been outstanding past the bound its own route
  /// promises ([Op#overdueAfterNanos]), which for `getProgramAccounts` is its two-minute request
  /// timeout doubled: a call the pace draws in the closing minutes of STEADY is still in flight
  /// when the run drains *by contract*, and reading the in-flight count as a residue turned that
  /// into a finding about the library leaving an exchange hanging.
  @Override
  public long overdueRequests() {
    final long now = System.nanoTime();
    long overdue = 0L;
    for (final var op : inFlightOps) {
      // An operation the watchdog has already settled as TIMEOUT is no longer outstanding from
      // the harness's side: its exchange is open only because the peer still holds the body and
      // the harness deliberately leaves the future for the SHUTDOWN sweep. Counting it here made
      // every stalled caller-body-handler call read as a residue at DRAIN end.
      if (!op.settled.get() && now - op.startNanos > op.overdueAfterNanos()) {
        ++overdue;
      }
    }
    return overdue;
  }

  @Override
  public long openConnections() {
    return -1L;
  }

  @Override
  public long lastMessageAgeMillisMax() {
    return -1L;
  }

  // --- types ------------------------------------------------------------------------------------

  /// One client under test, its predicate counters, and the wedge-probe countdown W3-D consumes.
  private final class Subject {

    private final String name;
    private final boolean hasTestResponse;
    private final boolean verifiesDecoding;
    private final LongAdder testResponseInvoked = new LongAdder();
    private final LongAdder defaultRouteCompleted = new LongAdder();
    private final LongAdder mismatches = new LongAdder();
    /// Exchanges on this client that went unsettled past the bound their own route promises -
    /// W3-E's second term. An operation the harness stopped waiting for *before* the client's
    /// deadline was due is not one of them.
    private final LongAdder overdueSettlements = new LongAdder();
    /// Cancellations this harness drove on this client's futures: W3-F's only numerator
    /// tolerance, and the one the design sanctions.
    private final LongAdder harnessCancelled = new LongAdder();
    /// Exchanges the client's own deadline cancelled. Reported, never tolerated.
    private final LongAdder deadlineSettled = new LongAdder();
    /// This client's share of the in-flight set, so W3-F's band is this subject's outstanding
    /// work rather than the whole workload's.
    private final LongAdder outstanding = new LongAdder();
    private final AtomicInteger wedgeProbes = new AtomicInteger();
    private SolanaRpcClient client;
    /// This subject's W3-D probe route: the same endpoint over the same caller-owned
    /// `HttpClient`, with [HttpWorkload#PROBE_HEADER] on every request so the peer does not answer a question
    /// about wedging with a fault of its own. Per subject rather than per workload, so a probe is
    /// still attributed to the client whose error armed it.
    private SolanaRpcClient probeClient;

    /// Takes one step off the wedge-probe countdown, returning true when this operation is one of
    /// the [#WEDGE_PROBES] that follow an error on this client.
    private boolean claimWedgeProbe() {
      return wedgeProbes.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }

    private Subject(final String name, final SolanaRpcClient client, final boolean hasTestResponse) {
      this.name = name;
      this.client = client;
      this.hasTestResponse = hasTestResponse;
      this.verifiesDecoding = CLIENT_GZIP.equals(name);
    }

    /// The `testResponse` predicate: the documented position from which a caller sees
    /// `(response, body)` for every default-route completion.
    ///
    /// It always returns true — a predicate that returned false would make the client complete
    /// the future with null, which would be the harness suppressing responses rather than
    /// observing them. The body it is handed has already been inflated, so a body that is not a
    /// complete JSON document here is the client having accepted a truncated response as whole:
    /// the W3-C defect, seen at the only point where it is still visible.
    private boolean observe(final HttpResponse<?> response, final byte[] body) {
      testResponseInvoked.increment();
      counters.increment(Counters.RPC_TEST_RESPONSE_INVOKED);
      if (body != null) {
        counters.add(Counters.RPC_BYTES_RECEIVED, body.length);
      }
      final var encoding = response.headers().firstValue("Content-Encoding").orElse("identity");
      if (encoding.contains("gzip")) {
        counters.increment(Counters.RPC_GZIP_OK);
      }
      if (verifiesDecoding && !ctx.live() && response.statusCode() / 100 == 2) {
        if (body != null && body.length >= 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B) {
          // The body still carries the gzip magic after the client said it had read it: the
          // encoding was announced and not applied. A sharp, race-free decoding defect.
          ctx.properties().fail("W3-C", "a body announced as " + encoding
              + " reached testResponse still gzip-framed, " + body.length + " bytes");
        } else if (body != null && body.length > 0 && complete(body)) {
          ctx.properties().pass("W3-C");
        }
        // An incomplete body is deliberately neither a pass nor a fail here. The peer truncates
        // bodies on purpose, and the only question the property asks is whether such a body is
        // ever *successfully returned* - which the parser, not this predicate, decides.
      }
      return true;
    }

    private boolean complete(final byte[] body) {
      for (int i = body.length - 1; i >= 0; --i) {
        final byte b = body[i];
        if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
          continue;
        }
        return b == '}' || b == ']';
      }
      return false;
    }
  }

  /// One in-flight operation. Mutable because the phase-launched probes differ from the paced ones
  /// in only two fields, and a second constructor for each shape would say less than naming them.
  private final class Op {

    private final Subject subject;
    private final Rpc method;
    private final long ordinal;
    private final Object expectation;
    private final SoakEvents.RpcCall event;
    private final String route;
    private final Semaphore permits;
    private final long boundNanos;
    private final long startNanos;
    private final AtomicBoolean settled;
    private final boolean wedgeProbe;

    /// Both are written by the launching thread after construction and read by the gauge thread
    /// and by the SHUTDOWN sweep, which is why they are volatile: a stale route or deadline would
    /// judge an operation against the wrong bound.
    private volatile long exchangeDeadlineNanos;
    private volatile boolean noWrap;
    /// The exchange itself, kept so SHUTDOWN can release one the client still holds. Null until
    /// [#arm], and null forever for an operation whose launch threw.
    private volatile CompletableFuture<?> future;

    private String deadlineProperty;
    private boolean w4c;
    /// How late the W4-C sentinel timer fired, `Long.MIN_VALUE` until it has.
    private volatile long w4cSentinelLateNanos = Long.MIN_VALUE;
    private boolean harnessTimeout;
    /// Set by the harness before it cancels the future itself, so a CancellationException that
    /// arrives without it is the client's own deadline.
    private volatile boolean cancelDriven;
    /// Set with [#cancelDriven] by the SHUTDOWN sweep. Both say "the harness cancelled this", and
    /// only this one says "after the run stopped asking questions": a teardown cancellation is not
    /// W3-E's input and must not widen W3-F's tolerance, which only a cancellation driven while
    /// the predicate could still have run earns.
    private volatile boolean shutdownCancel;
    private boolean gzip;
    private String encoding = "-";
    private int httpStatus;
    private long responseBytes;
    private ScheduledFuture<?> watchdog;

    private Op(final Subject subject,
               final Rpc method,
               final long ordinal,
               final Object expectation,
               final SoakEvents.RpcCall event,
               final String route,
               // null for an operation the phase machine launched directly, which holds no
               // worker permit to return.
               final Semaphore permits,
               final long boundNanos,
               final boolean wedgeProbe) {
      this.subject = subject;
      this.method = method;
      this.ordinal = ordinal;
      this.expectation = expectation;
      this.event = event;
      this.route = route;
      this.permits = permits;
      this.boundNanos = boundNanos;
      this.wedgeProbe = wedgeProbe;
      this.startNanos = System.nanoTime();
      this.settled = new AtomicBoolean();
      // Counted here rather than at `arm`, so that an operation whose launch throws still
      // balances its own decrement in `settle` and the gauge cannot drift negative.
      inFlight.incrementAndGet();
      if (!wedgeProbe) {
        // A probe goes out on the subject's probe client, which carries no predicate, so it is
        // neither a completion W3-F counts nor an outstanding one it makes room for. Both sides
        // of that accounting are excluded together; excluding one alone would bias the band.
        subject.outstanding.increment();
      }
      this.exchangeDeadlineNanos = method.exchangeDeadlineNanos(SUBJECT_REQUEST_TIMEOUT.toMillis());
      this.deadlineProperty = "W3-B";
      this.gzip = CLIENT_GZIP.equals(subject.name);
      this.encoding = this.gzip ? "gzip" : "identity";
      // Last, so every field a reader on another thread consults is already written; the set's
      // own write publishes them.
      track(this);
    }

    /// How long this operation may be outstanding before it is *late*, as opposed to merely in
    /// flight. A wrapped call is judged against the promise the client makes — its exchange
    /// deadline, plus the same [#DEADLINE_SLACK] the deadline properties allow. The
    /// caller-body-handler route is never late: `sendPostRequestNoWrap` carries only the JDK
    /// request timeout by contract, the harness may not invent a library deadline for it, and its
    /// own watchdog is where the harness stops waiting, not a promise the library missed —
    /// judging it against that bound made every stalled no-wrap call an overdue settlement, and
    /// one inside a cancellation window failed W3-E on a pilot.
    private long overdueAfterNanos() {
      return noWrap ? Long.MAX_VALUE : exchangeDeadlineNanos + DEADLINE_SLACK.toNanos();
    }
  }

  /// The harness's own per-operation deadline, distinguished from every client failure by its
  /// type so that `settle` never mistakes "the client gave up" for "we stopped waiting".
  private static final class HarnessDeadline extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private HarnessDeadline(final Op op) {
      super("harness bound reached for " + op.method.wireName, null, false, false);
    }
  }

  /// The outcome taxonomy of `DESIGN.md` §12, plus the two facts the ledger needs from each:
  /// whether it is evidence of a peer fault, and whether the exchange got far enough for
  /// `testResponse` to have run.
  private enum Outcome {

    OK(Counters.RPC_COMPLETED_OK, false, true),
    RPC_ERROR(Counters.RPC_COMPLETED_RPC_ERROR, true, true),
    HTTP_ERROR(Counters.RPC_COMPLETED_HTTP_ERROR, true, true),
    TIMEOUT(Counters.RPC_COMPLETED_TIMEOUT, true, false),
    CANCELLED(Counters.RPC_COMPLETED_CANCELLED, false, false),
    /// A cancellation the harness did not request: the client's own exchange deadline
    /// (`2 x requestTimeout`) cancelled a stalled exchange. Kept apart from [#CANCELLED] because
    /// a harness-driven cancel is W3-E's input and this is W3-B's - the same exception, two
    /// different authors.
    DEADLINE(Counters.RPC_COMPLETED_DEADLINE, true, false),
    TRANSPORT(Counters.RPC_COMPLETED_TRANSPORT, true, false),
    /// A body that reached the parser and could not be read.
    ///
    /// Kept apart from [#MISMATCH] deliberately. The peer truncates and corrupts bodies on
    /// purpose, and a parser that refuses such a body is behaving correctly - folding that into
    /// the association outcome would turn every injected `TRUNCATE_BODY` into a W3-A finding,
    /// which is a false accusation against the library rather than evidence about it. MISMATCH is
    /// reserved for a body that parsed cleanly and answered the wrong request.
    ///
    /// It keeps its own counter rather than sharing `rpc.completed.transport`: the peer kills
    /// connections and corrupts bodies on the same schedule, and only separate totals let a reader
    /// say which of the two a run's failures were.
    DECODE(RPC_COMPLETED_DECODE, true, true),
    MISMATCH(Counters.RPC_COMPLETED_MISMATCH, false, true);

    private final String counter;
    private final boolean error;
    private final boolean defaultRouteCompletion;

    Outcome(final String counter, final boolean error, final boolean defaultRouteCompletion) {
      this.counter = counter;
      this.error = error;
      this.defaultRouteCompletion = defaultRouteCompletion;
    }
  }

  /// The method mix of `DESIGN.md` §13, with each method's wire name — which is what the peer
  /// logs, so the report can join the two sides without a translation table.
  private enum Rpc {

    GET_ACCOUNT_INFO("getAccountInfo", 40, true),
    GET_LATEST_BLOCK_HASH("getLatestBlockhash", 15, false),
    GET_SLOT("getSlot", 10, true),
    GET_MULTIPLE_ACCOUNTS("getMultipleAccounts", 10, true),
    GET_SIGNATURE_STATUSES("getSignatureStatuses", 10, true),
    GET_HEALTH("getHealth", 5, false),
    GET_BLOCK("getBlock", 5, true),
    GET_PROGRAM_ACCOUNTS("getProgramAccounts", 3, true),
    GET_VERSION("getVersion", 2, false);

    private final String wireName;
    private final int weight;
    private final boolean verifiable;

    Rpc(final String wireName, final int weight, final boolean verifiable) {
      this.wireName = wireName;
      this.weight = weight;
      this.verifiable = verifiable;
    }

    /// The client's exchange deadline for this method: twice its request timeout.
    /// `getProgramAccounts` carries `PROGRAM_ACCOUNTS_TIMEOUT` rather than the configured one.
    private long exchangeDeadlineNanos(final long requestTimeoutMillis) {
      return this == GET_PROGRAM_ACCOUNTS
          ? 2L * SUBJECT_PROGRAM_ACCOUNTS_TIMEOUT.toNanos()
          : 2L * TimeUnit.MILLISECONDS.toNanos(requestTimeoutMillis);
    }

    /// The harness's own bound: the design's `2 x requestTimeout + 5 s`, stated as 125 s for
    /// `getProgramAccounts` so a 120 s timeout is not doubled into a four-minute wait.
    private long bound(final long requestTimeoutMillis) {
      return this == GET_PROGRAM_ACCOUNTS
          ? PGA_BOUND.toNanos()
          : 2L * TimeUnit.MILLISECONDS.toNanos(requestTimeoutMillis) + OP_GRACE.toNanos();
    }

    /// The weighted draw, expanded once into a flat table so the hot path is one array index
    /// rather than a cumulative scan.
    private static Rpc[] mix(final boolean live) {
      final var table = new ArrayList<Rpc>(100);
      for (final var method : values()) {
        if (live && method.verifiable && method != GET_SLOT && method != GET_ACCOUNT_INFO) {
          // A live run drives only the methods that are safe and cheap against a real cluster;
          // the rest would be load on someone else's node for evidence the run cannot use.
          continue;
        }
        for (int i = 0; i < method.weight; ++i) {
          table.add(method);
        }
      }
      return table.toArray(new Rpc[0]);
    }
  }

  /// A rate limiter on the monotonic clock, shared by every worker.
  ///
  /// Each call reserves the next slot in the schedule and parks until it is due, in slices short
  /// enough that a rate change or a shutdown is noticed promptly. A reserved slot is always
  /// consumed by the caller that reserved it: the first version of this bucket refused a slot
  /// that lay too far ahead but had already advanced the schedule past it, so every refusal
  /// burnt a token, the refused workers spun re-refusing, and the run issued a fifth of its
  /// configured load while reporting a starvation percentage in the millions. With eight workers
  /// and the quiet sub-phase's rate, a slot several seconds ahead is the configuration, not a
  /// failure to keep up, so it is waited for rather than counted.
  private static final class TokenBucket {

    private final Object lock = new Object();
    private volatile long intervalNanos;
    private long nextNanos;

    private TokenBucket(final int ratePerSecond) {
      rate(ratePerSecond);
      this.nextNanos = System.nanoTime();
    }

    private void rate(final int ratePerSecond) {
      this.intervalNanos = TimeUnit.SECONDS.toNanos(1L) / Math.max(1, ratePerSecond);
    }

    /// Reserves the next slot and parks until it is due. Returns false only when the caller was
    /// interrupted, which is how quiesce stops a worker mid-wait.
    private boolean acquire() {
      final long due;
      synchronized (lock) {
        final long now = System.nanoTime();
        if (nextNanos - now < 0L) {
          nextNanos = now;
        }
        due = nextNanos;
        nextNanos += intervalNanos;
      }
      long remaining;
      while ((remaining = due - System.nanoTime()) > 0L) {
        if (Thread.currentThread().isInterrupted()) {
          return false;
        }
        LockSupport.parkNanos(Math.min(remaining, POLL_PARK_NANOS));
      }
      return true;
    }
  }
}
