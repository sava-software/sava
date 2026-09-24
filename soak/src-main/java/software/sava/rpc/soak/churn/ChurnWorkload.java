package software.sava.rpc.soak.churn;

import com.sun.management.UnixOperatingSystemMXBean;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.response.JsonRpcException;
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.rpc.json.http.ws.Subscription;
import software.sava.rpc.soak.Counters;
import software.sava.rpc.soak.GaugeSampler;
import software.sava.rpc.soak.Phase;
import software.sava.rpc.soak.SoakConfig;
import software.sava.rpc.soak.SoakContext;
import software.sava.rpc.soak.Workload;
import software.sava.rpc.soak.http.HttpWorkload;
import software.sava.rpc.soak.oracle.Properties;
import software.sava.rpc.soak.oracle.Property;

import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.UnknownServiceException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/// Construction and shutdown churn: does the library give back what it took?
///
/// The two halves are deliberately separate because they rest on opposite guarantees. W5a builds a
/// websocket engine with its own, engine-owned executor and asserts that closing it releases that
/// thread; W5b builds RPC clients over an `HttpClient` the **caller** owns and asserts that
/// discarding them leaves it working. Merging the two into one "resources are released" check would
/// make a pass ambiguous: a client that shut the caller's `HttpClient` down would look tidy.
///
/// The thread and file-descriptor gates are margins rather than slopes. Both are step functions — a
/// pool either exists or it does not, a socket is either open or closed — so a least-squares fit
/// over them would smooth away the very step that says a cycle leaked one.
public final class ChurnWorkload implements Workload, GaugeSampler.GaugeSource {

  /// The workload name, its gauge source name and its counter prefix.
  public static final String NAME = "churn";

  /// The index in `SOAK_WS_PORTS` the peer reserves for churn. The four driven engines take 0..3;
  /// churn takes its own so a connection storm here cannot perturb their sequences.
  public static final int CHURN_PORT_INDEX = 4;

  /// How long one cycle waits for the transport to open. Against loopback this is two orders of
  /// magnitude more than it needs; a cycle that misses it is evidence, not impatience.
  static final long CONNECT_BOUND_MILLIS = 10_000L;

  /// How long one cycle waits for one notification per accepted registration — on a live run,
  /// for the node's grant of each, since the supplied accounts need not move inside any bound (a
  /// cycle drawn from a list's static entries, programs and mints, never would).
  static final long NOTIFY_BOUND_MILLIS = 10_000L;

  /// The period at which a live cycle re-reads its grants. The grant is set by the engine on the
  /// confirmation frame and has no callback of its own, so it is polled, coarsely, inside the
  /// bound above.
  static final long GRANT_POLL_MILLIS = 50L;

  /// The settle window after `close()` before the thread count is read. `close()` sends a polite
  /// close frame and aborts a few seconds later, so reading the count immediately would be reading
  /// it during the shutdown the property is about.
  static final long QUIESCE_MILLIS = 5_000L;

  /// Registrations per engine cycle: enough that a replay or a dispatch bug has somewhere to show,
  /// few enough that a cycle fits inside its bounds. The plan's size; a cycle registers as many
  /// *distinct* keys as the table offers up to it ([#cycleKeys]).
  public static final int REGISTRATIONS_PER_CYCLE = 4;

  /// RPC clients built and dropped per client cycle.
  static final int CLIENTS_PER_CYCLE = 8;

  private static final String ENGINE_CYCLE_FAILED = "churn.engine.cycleFailed";
  private static final String CLIENT_CYCLE_FAILED = "churn.client.cycleFailed";
  // The cause beside the count: the churn ports sit under the same fault schedule as the rest of
  // the peer, so a cycle that could not connect through a stalled handshake, or a health check
  // the peer answered with a scheduled 503, is expected, and a bare count could not say so.
  private static final String ENGINE_CYCLE_FAILED_CONNECT = "churn.engine.cycleFailed.connect";
  private static final String ENGINE_CYCLE_FAILED_NOTIFY = "churn.engine.cycleFailed.notify";
  private static final String ENGINE_CYCLE_FAILED_CONFIRM = "churn.engine.cycleFailed.confirm";
  private static final String ENGINE_CYCLE_FAILED_SUBSCRIBE = "churn.engine.cycleFailed.subscribe";
  private static final String ENGINE_SUBSCRIBE_REFUSED = "churn.engine.subscribeRefused";
  private static final String ENGINE_CYCLE_STOPPED = "churn.engine.cycleStopped";
  private static final String ENGINE_CYCLE_FAILED_ENGINE_ERROR = "churn.engine.cycleFailed.engineError";
  private static final String ENGINE_CYCLE_FAILED_EXCEPTION = "churn.engine.cycleFailed.exception";
  private static final String CLIENT_CYCLE_FAILED_REQUEST = "churn.client.cycleFailed.request";
  private static final String CLIENT_CYCLE_FAILED_EXCEPTION = "churn.client.cycleFailed.exception";
  private static final String BASELINE_MISSING = "churn.baseline.missing";

  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicLong openConnections = new AtomicLong();
  private final AtomicLong liveRegistrations = new AtomicLong();

  private SoakContext ctx;
  private SoakConfig config;
  private Counters counters;
  private URI websocketUri;
  private URI httpUri;
  private Thread engineThread;
  private Thread clientThread;
  private volatile long threadBaseline = -1L;
  private volatile long fdBaseline = -1L;
  private volatile ExecutorService injectedExecutor;

  /// Nothing is built here: [#start(SoakContext)] owns construction, so an unwired workload costs
  /// the run nothing.
  public ChurnWorkload() {
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public void start(final SoakContext context) throws Exception {
    this.ctx = context;
    this.config = context.config();
    this.counters = context.counters();
    if (config.live()) {
      this.websocketUri = URI.create(config.liveWsUrl());
      this.httpUri = URI.create(config.liveHttpUrl());
    } else {
      final var ports = config.wsPorts();
      if (ports.size() <= CHURN_PORT_INDEX) {
        throw new IllegalStateException("SOAK_WS_PORTS has " + ports.size()
            + " entries; the churn workload needs index " + CHURN_PORT_INDEX);
      }
      this.websocketUri = URI.create("ws://127.0.0.1:" + ports.get(CHURN_PORT_INDEX));
      this.httpUri = URI.create("http://127.0.0.1:" + config.httpPort());
    }

    running.set(true);
    engineThread = thread("soak-churn-engine", this::driveEngineCycles);
    clientThread = thread("soak-churn-client", this::driveClientCycles);
    context.registerGauge(this);
  }

  private Thread thread(final String name, final Runnable body) {
    final var created = new Thread(body, name);
    created.setDaemon(true);
    created.start();
    return created;
  }

  /// The baselines are taken at the end of warm-up, not at start-up: the HTTP client's pool, the
  /// engines' check loops and the JFR recorder's own threads are all still appearing during
  /// start-up, and a baseline taken before they exist would read every one of them as a leak.
  @Override
  public void onPhase(final Phase phase) {
    if (phase == Phase.STEADY && threadBaseline < 0L) {
      threadBaseline = liveThreads();
      fdBaseline = openFileDescriptors();
    }
    if (phase == Phase.QUIESCE || phase == Phase.DRAIN || phase == Phase.SHUTDOWN) {
      running.set(false);
    }
  }

  // ------------------------------------------------------------------------- W5a engine cycles

  private void driveEngineCycles() {
    final long periodNanos = Math.max(1L, config.churnPeriodSeconds()) * 1_000_000_000L;
    long deadline = System.nanoTime();
    while (running.get()) {
      try {
        engineCycle();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (final RuntimeException e) {
        counters.increment(ENGINE_CYCLE_FAILED);
        counters.increment(ENGINE_CYCLE_FAILED_EXCEPTION);
      }
      deadline += periodNanos;
      parkUntil(deadline);
    }
  }

  private void engineCycle() throws InterruptedException {
    final var websocket = SolanaRpcWebsocket.build()
        .uri(websocketUri)
        // The caller's `HttpClient` supplies the transport; the engine still owns its own check-loop
        // executor, which is the thing this cycle is about.
        .webSocketBuilder(ctx.httpClient())
        .connectTimeout(config.connectTimeoutMillis())
        .pingDelay(config.pingDelayMillis())
        .subscriptionAndPingCheckDelay(config.checkDelayMillis())
        .commitment(Commitment.CONFIRMED)
        .create();

    // The keys before the latches, and the latches before the first subscribe: a confirmation or
    // a notification can arrive before `accountSubscribe` returns.
    final var keys = cycleKeys(ctx.keyTable(), counters.get(Counters.CHURN_ENGINE_CYCLES));
    final var notified = new CountDownLatch(keys.length);
    // The engine's own handle per registration, from `onSub`. That callback is not the grant: it
    // fires after each successful *send*, before the confirmation and again on every re-send
    // (its javadoc), so counting it as one let a node that never answered open the latch
    // (review). The grant is the handle's `subId()`, which only a confirmation frame sets.
    final var handles = new AtomicReferenceArray<Subscription<AccountInfo<byte[]>>>(keys.length);
    final var sent = new boolean[keys.length];
    boolean confirmedOpen = false;
    int accepted = 0;
    try {
      websocket.exceptionSubscribe(exception -> {
        counters.increment(ENGINE_CYCLE_FAILED);
        counters.increment(ENGINE_CYCLE_FAILED_ENGINE_ERROR);
      });
      final var connect = websocket.connect();
      if (connect == null) {
        counters.increment(ENGINE_CYCLE_FAILED);
        counters.increment(ENGINE_CYCLE_FAILED_CONNECT);
        return;
      }
      openConnections.incrementAndGet();
      confirmedOpen = true;
      try {
        connect.get(CONNECT_BOUND_MILLIS, TimeUnit.MILLISECONDS);
      } catch (final ExecutionException | TimeoutException e) {
        // A cycle that could not connect proves nothing about release; it is counted so a run whose
        // peer went away does not look like a clean set of cycles.
        counters.increment(ENGINE_CYCLE_FAILED);
        counters.increment(ENGINE_CYCLE_FAILED_CONNECT);
      }

      for (int i = 0; i < keys.length; ++i) {
        final int slot = i;
        final var once = new AtomicBoolean();
        sent[i] = websocket.accountSubscribe(Commitment.CONFIRMED, keys[i],
            subscription -> handles.set(slot, subscription),
            accountInfo -> {
              if (once.compareAndSet(false, true)) {
                notified.countDown();
              }
            });
        if (sent[i]) {
          ++accepted;
          liveRegistrations.incrementAndGet();
        } else {
          // The engine's own refusal — a `(commitment, key)` it already holds, or an engine
          // already closed — returns false rather than throwing. Counted, released from the
          // notify latch and skipped by the live grant wait: a cycle cannot wait on a
          // registration that does not exist (review).
          counters.increment(ENGINE_SUBSCRIBE_REFUSED);
          notified.countDown();
        }
      }
      if (accepted == 0) {
        counters.increment(ENGINE_CYCLE_FAILED);
        counters.increment(ENGINE_CYCLE_FAILED_SUBSCRIBE);
      } else if (ctx.live()) {
        // A real node grants every registration but moves only the accounts the runtime or a
        // transaction writes to, so the live cycle's evidence that the engine registered is the
        // node's grant of each accepted registration, not a notification the account may never
        // produce. Every cycle of the first live runs "failed" the notification latch — over a
        // seeded table the driver had not yet swapped for the supplied accounts — and a cycle
        // drawn from a supplied list's programs and mints would fail it the same way.
        switch (awaitGrants(handles, sent, NOTIFY_BOUND_MILLIS, running::get)) {
          case GRANTED -> {
          }
          case ELAPSED -> {
            counters.increment(ENGINE_CYCLE_FAILED);
            counters.increment(ENGINE_CYCLE_FAILED_CONFIRM);
          }
          // The workload is quiescing: a wait cut short by the run ending is not a cycle failure,
          // and holding the full bound here would outlive quiesce's join budget (review).
          case STOPPED -> counters.increment(ENGINE_CYCLE_STOPPED);
        }
      } else if (!notified.await(NOTIFY_BOUND_MILLIS, TimeUnit.MILLISECONDS)) {
        counters.increment(ENGINE_CYCLE_FAILED);
        counters.increment(ENGINE_CYCLE_FAILED_NOTIFY);
      }
    } finally {
      websocket.close();
      liveRegistrations.addAndGet(-accepted);
      if (confirmedOpen) {
        openConnections.decrementAndGet();
      }
      counters.increment(Counters.CHURN_ENGINE_CYCLES);
    }

    LockSupport.parkNanos(QUIESCE_MILLIS * 1_000_000L);
    assertEngineReleased(websocket);
  }

  /// The distinct keys one cycle registers: a walk over the table from the cycle's start index,
  /// stopping at [#REGISTRATIONS_PER_CYCLE] distinct keys or after one lap. The seeded table has
  /// 256 distinct keys, so a local cycle registers four — the same four the plain index arithmetic
  /// picked; a live table is the supplied accounts cycled, and two accounts yield two. The engine
  /// refuses a `(commitment, key)` it already holds, so a latch sized to the plan instead of to
  /// the accepted registrations could never open (review).
  public static PublicKey[] cycleKeys(final PublicKey[] table, final long cycle) {
    final var keys = new LinkedHashSet<PublicKey>(REGISTRATIONS_PER_CYCLE * 2);
    final int start = (int) Math.floorMod(cycle * 7L, (long) table.length);
    for (int i = 0; i < table.length && keys.size() < REGISTRATIONS_PER_CYCLE; ++i) {
      keys.add(table[(start + i) % table.length]);
    }
    return keys.toArray(PublicKey[]::new);
  }

  /// How a live cycle's grant wait ended.
  public enum Grant {
    /// Every accepted registration holds a grant.
    GRANTED,
    /// The bound elapsed with at least one accepted registration ungranted: the cycle failure.
    ELAPSED,
    /// `keepWaiting` turned false first — the workload is quiescing — which is not a cycle failure.
    STOPPED
  }

  /// Waits, inside `boundMillis`, for the node's grant (`Subscription.subId()` non-null) of
  /// every registration the engine accepted; a slot the engine refused is not waited on. Polled
  /// every [#GRANT_POLL_MILLIS], because the grant has no callback: `onSub` reports the send.
  /// Each poll also reads `keepWaiting`, so a run that ends mid-wait releases the cycle at once
  /// instead of holding the bound past quiesce's join budget.
  ///
  /// @throws InterruptedException when the cycle thread is interrupted while waiting; checked
  ///         before the deadline, so the flag never leaks out under an [Grant#ELAPSED] result
  public static Grant awaitGrants(final AtomicReferenceArray<? extends Subscription<?>> handles,
                                  final boolean[] sent,
                                  final long boundMillis,
                                  final BooleanSupplier keepWaiting) throws InterruptedException {
    final long deadline = System.nanoTime() + boundMillis * 1_000_000L;
    while (true) {
      boolean granted = true;
      for (int i = 0; i < sent.length && granted; ++i) {
        if (sent[i]) {
          final var handle = handles.get(i);
          granted = handle != null && handle.subId() != null;
        }
      }
      if (granted) {
        return Grant.GRANTED;
      }
      if (Thread.interrupted()) {
        throw new InterruptedException("interrupted while waiting for subscription grants");
      }
      if (!keepWaiting.getAsBoolean()) {
        return Grant.STOPPED;
      }
      if (System.nanoTime() - deadline >= 0L) {
        return Grant.ELAPSED;
      }
      LockSupport.parkNanos(GRANT_POLL_MILLIS * 1_000_000L);
    }
  }

  /// W5-A. The thread margin is the public half — no reflection, no add-opens — and the
  /// `executorServiceShutdown()` probe is the same statement from the inside when the seam is open.
  private void assertEngineReleased(final SolanaRpcWebsocket websocket) {
    final long baseline = threadBaseline;
    if (baseline < 0L) {
      // Before STEADY the baseline does not exist yet; a warm-up cycle still exercises the code
      // path, it just cannot be the measurement.
      counters.increment(BASELINE_MISSING);
    } else {
      final long threads = liveThreads();
      if (threads <= baseline + config.threadMargin()) {
        pass(Properties.W5_A);
      } else {
        fail(Properties.W5_A, "after " + counters.get(Counters.CHURN_ENGINE_CYCLES)
            + " engine churn cycles the live platform thread count is " + threads
            + ", baseline " + baseline + " margin " + config.threadMargin());
      }
    }

    final long shutdown = executorServiceShutdown(websocket);
    if (shutdown == 1L) {
      pass(Properties.W5_A);
    } else if (shutdown == 0L) {
      fail(Properties.W5_A, "executorServiceShutdown() is false on a churn engine after close()");
    }
  }

  // ------------------------------------------------------------------------- W5b client cycles

  private void driveClientCycles() {
    final long periodNanos = Math.max(1L, config.churnPeriodSeconds()) * 1_000_000_000L;
    long deadline = System.nanoTime();
    while (running.get()) {
      try {
        clientCycle();
      } catch (final RuntimeException e) {
        counters.increment(CLIENT_CYCLE_FAILED);
        counters.increment(CLIENT_CYCLE_FAILED_EXCEPTION);
      }
      deadline += periodNanos;
      parkUntil(deadline);
    }
  }

  private void clientCycle() {
    final var discarded = new ArrayList<SolanaRpcClient>(CLIENTS_PER_CYCLE);
    for (int i = 0; i < CLIENTS_PER_CYCLE; ++i) {
      discarded.add(newClient());
    }
    for (final var client : discarded) {
      try {
        client.getHealth().get(config.connectTimeoutMillis() + 2_000L, TimeUnit.MILLISECONDS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (final ExecutionException | TimeoutException e) {
        // The peer answering an error is fine here: the point is the shared client's survival, and
        // an error travelled through it just as well as a success would have. These requests carry
        // the probe header, so the peer should not be faulting them at all; the count stays
        // because "should not" is a claim about the peer that this run's artifacts have to be able
        // to contradict.
        counters.increment(CLIENT_CYCLE_FAILED);
        counters.increment(CLIENT_CYCLE_FAILED_REQUEST);
      }
    }
    discarded.clear();
    counters.increment(Counters.CHURN_CLIENT_CYCLES);

    // W5-B1: the `HttpClient` belongs to the harness. Discarding every RPC client built over it must
    // leave it usable, so a brand new client on the same instance has to carry an exchange.
    final long boundMillis = config.connectTimeoutMillis() + 2_000L;
    final var survivor = newClient();
    try {
      survivor.getHealth().get(boundMillis, TimeUnit.MILLISECONDS);
      pass(Properties.W5_B1);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (final ExecutionException e) {
      final var cause = unwrap(e);
      if (peerAnswered(cause)) {
        // The same rule the discard loop above states: an error the peer wrote back travelled
        // through the shared client just as well as a success would have, so it is evidence for
        // W5-B1, not against it. It is still a failed request, and it is counted as one.
        counters.increment(CLIENT_CYCLE_FAILED);
        counters.increment(CLIENT_CYCLE_FAILED_REQUEST);
        pass(Properties.W5_B1);
      } else {
        fail(Properties.W5_B1, "a new SolanaRpcClient on the shared HttpClient failed after "
            + CLIENTS_PER_CYCLE + " clients were discarded, with no answer from the peer: "
            + describe(cause));
      }
    } catch (final TimeoutException e) {
      // Deliberately a failure rather than a fault-excused pass: a client whose pool or executor a
      // discarded RPC client shut down stops answering in exactly this shape, and this direction is
      // the whole reason W5-B1 exists. The probe header is what keeps a scheduled stall out of this
      // arm; the message still says what was and was not observed, because a timeout here is the
      // one shape the harness cannot attribute from this side alone.
      fail(Properties.W5_B1, "a new SolanaRpcClient on the shared HttpClient did not settle within "
          + boundMillis + " ms after " + CLIENTS_PER_CYCLE
          + " clients were discarded; no status line came back");
    }
  }

  /// Did the failure carry an answer the peer wrote down the shared `HttpClient`?
  ///
  /// This is W5-B1's survival rule, and it lives here once for both of the property's probes: this
  /// driver's post-discard probe and `HttpWorkload`'s end-of-drain one. Two copies of a rule this
  /// sharp is how one of them drifts — the two sites were measured disagreeing, one passing on a
  /// scheduled `HTTP_429` and the other failing the property for it.
  ///
  /// The peer's RPC port sits under the same fault schedule as everything else, so a scheduled
  /// `HTTP_429` or `HTTP_503` can reach a probe (the [software.sava.rpc.soak.http.HttpWorkload]
  /// probe header exempts one, but the rule must not depend on that exemption holding). sava maps
  /// a non-2xx status to an `UncheckedIOException` wrapping an `UnknownServiceException`, and a
  /// JSON-RPC error envelope to a `JsonRpcException` — in both cases a status line and a body
  /// completed over the caller's client, which is precisely what W5-B1 asks, and the peer's chosen
  /// status is beside the point.
  ///
  /// Everything else stays a failure. A transport `IOException`, a timeout with no status, an
  /// `IllegalStateException` from a closed client and a `RejectedExecutionException` from a shut
  /// down executor are the shapes a caller-owned client that did *not* survive takes, so widening
  /// this to "any exception means it survived" would retire the property rather than fix it.
  ///
  /// @param cause the failure, already unwrapped of its `CompletionException` /
  ///              `ExecutionException` wrappers
  public static boolean peerAnswered(final Throwable cause) {
    return cause instanceof JsonRpcException
        || (cause instanceof final UncheckedIOException unchecked
        && unchecked.getCause() instanceof UnknownServiceException);
  }

  private static Throwable unwrap(final Throwable failure) {
    var cause = failure;
    while ((cause instanceof CompletionException || cause instanceof ExecutionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  private static String describe(final Throwable cause) {
    final var message = String.valueOf(cause.getMessage());
    return cause.getClass().getSimpleName() + ": "
        + (message.length() <= 240 ? message : message.substring(0, 240));
  }

  /// Every client this driver builds is a probe client.
  ///
  /// Nothing here asks a question about fault handling: the discard loop asks whether eight
  /// discarded clients left the ninth usable, and the survivor asks the same of the shared
  /// `HttpClient`. A scheduled fault landing on either is an answer to a question nobody asked,
  /// and on the survivor it is a false W5-B1 failure — so the peer is told to step over these
  /// requests with [software.sava.rpc.soak.http.HttpWorkload#PROBE_HEADER]. The construction path
  /// under test is untouched by the extra header: it is the same builder, the same shared
  /// `HttpClient` and the same `createClient()` a caller would use.
  private SolanaRpcClient newClient() {
    return SolanaRpcClient.build()
        .endpoint(httpUri)
        .httpClient(ctx.httpClient())
        .extendRequest(this::markProbe)
        .createClient();
  }

  /// Counted once per request the client builds rather than once per call site, so the total
  /// cannot drift from the number of requests that carried the header.
  private HttpRequest.Builder markProbe(final HttpRequest.Builder request) {
    return HttpWorkload.markProbe(ctx, counters, request);
  }

  // ------------------------------------------------------------------------------ W5-B2, W5-C

  /// W5-B2: an `ExecutorService` the caller injected must survive the engine that borrowed it.
  ///
  /// The seam is `SolanaRpcWebsocketBuilder.executorService(ExecutorService)`, which is
  /// package-private — it exists for sava's own tests — so this runs only under the add-opens and
  /// otherwise records a stated skip. The distinction matters: a silent skip would read as
  /// UNEXERCISED and fail the run, and a fabricated pass would assert something never observed.
  private void assertInjectedExecutorSurvives() {
    final var builder = SolanaRpcWebsocket.build()
        .uri(websocketUri)
        .webSocketBuilder(ctx.httpClient())
        .commitment(Commitment.CONFIRMED);
    final Method seam;
    try {
      seam = builder.getClass().getDeclaredMethod("executorService", ExecutorService.class);
      if (!seam.trySetAccessible()) {
        notEvaluated(Properties.W5_B2, "executorService(ExecutorService) is not reachable: add "
            + "--add-opens software.sava.rpc/software.sava.rpc.json.http.ws=software.sava.rpc.soak");
        return;
      }
    } catch (final NoSuchMethodException | RuntimeException e) {
      notEvaluated(Properties.W5_B2,
          "this SolanaRpcWebsocket.Builder has no executorService(ExecutorService) seam");
      return;
    }

    final var executor = Executors.newSingleThreadExecutor(runnable -> {
      final var created = new Thread(runnable, "soak-churn-injected");
      created.setDaemon(true);
      return created;
    });
    injectedExecutor = executor;
    try {
      seam.invoke(builder, executor);
      final var websocket = builder.create();
      websocket.connect();
      LockSupport.parkNanos(500L * 1_000_000L);
      websocket.close();
      LockSupport.parkNanos(QUIESCE_MILLIS * 1_000_000L);
      if (executor.isShutdown()) {
        fail(Properties.W5_B2,
            "close() shut down an ExecutorService the caller injected and still owns");
      } else {
        pass(Properties.W5_B2);
      }
    } catch (final ReflectiveOperationException | RuntimeException e) {
      notEvaluated(Properties.W5_B2,
          "the injected-executor cycle could not run: " + e.getClass().getSimpleName());
    } finally {
      executor.shutdownNow();
      injectedExecutor = null;
    }
  }

  /// W5-C. A margin rather than a slope because file descriptors are a step function: a run that
  /// leaks one socket per cycle and a run that leaks none differ by a step, and a fit over the
  /// series would report the step as noise.
  private void assertFileDescriptors() {
    final long baseline = fdBaseline;
    final long open = openFileDescriptors();
    if (baseline < 0L || open < 0L) {
      notEvaluated(Properties.W5_C, open < 0L
          ? "this JVM does not expose UnixOperatingSystemMXBean.getOpenFileDescriptorCount()"
          : "the run never reached STEADY, so no post-warm-up baseline was taken");
      return;
    }
    if (open <= baseline + config.fdMargin()) {
      pass(Properties.W5_C);
    } else {
      fail(Properties.W5_C, "open file descriptors " + open + " exceed the post-warm-up baseline "
          + baseline + " by more than the margin " + config.fdMargin());
    }
  }

  // ---------------------------------------------------------------------------------- phases

  @Override
  public void quiesce(final Duration bound) throws Exception {
    running.set(false);
    final long deadline = System.nanoTime() + bound.toNanos();
    join(engineThread, deadline);
    join(clientThread, deadline);

    // Both remaining properties are about what is left after the run stopped creating things, so
    // they belong here rather than in drain: drain is where the harness closes what it owns, and a
    // descriptor count taken then would include the closing.
    assertInjectedExecutorSurvives();
    LockSupport.parkNanos(Math.min(QUIESCE_MILLIS, millisUntil(deadline)) * 1_000_000L);
    assertFileDescriptors();
  }

  @Override
  public void drain(final Duration bound) throws Exception {
    // Every engine and every client this workload built was closed or dropped inside its own cycle;
    // nothing is held between cycles on purpose, so that a residue here would be the library's and
    // not the harness's.
    final var executor = injectedExecutor;
    if (executor != null) {
      executor.shutdownNow();
    }
    stateUnexercised();
  }

  @Override
  public void close() {
    running.set(false);
    final var executor = injectedExecutor;
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  private void stateUnexercised() {
    for (final var property : List.of(Properties.W5_A, Properties.W5_B1, Properties.W5_C)) {
      if (ctx.properties().evaluated(property.id()) == 0L) {
        ctx.properties().notEvaluated(property.id(),
            "no churn cycle completed in this run; the profile is shorter than one churn period");
      }
    }
    if (ctx.properties().evaluated(Properties.W5_B2.id()) == 0L) {
      ctx.properties().notEvaluated(Properties.W5_B2.id(),
          "the injected-executor cycle never ran");
    }
  }

  // ---------------------------------------------------------------------------- gauge source

  @Override
  public long liveSubscriptions() {
    return liveRegistrations.get();
  }

  @Override
  public long pendingConfirmations() {
    return -1L;
  }

  @Override
  public long inFlightRequests() {
    return -1L;
  }

  @Override
  public long openConnections() {
    return openConnections.get();
  }

  @Override
  public long lastMessageAgeMillisMax() {
    // Churn engines exist for a handful of seconds each; an age taken from one of them would say
    // more about where in its cycle it was than about liveness.
    return -1L;
  }

  // --------------------------------------------------------------------------------- utility

  private static long liveThreads() {
    return ManagementFactory.getThreadMXBean().getThreadCount();
  }

  private static long openFileDescriptors() {
    final var os = ManagementFactory.getOperatingSystemMXBean();
    return os instanceof final UnixOperatingSystemMXBean unix
        ? unix.getOpenFileDescriptorCount()
        : -1L;
  }

  private static long executorServiceShutdown(final SolanaRpcWebsocket websocket) {
    try {
      final var method = websocket.getClass().getDeclaredMethod("executorServiceShutdown");
      if (!method.trySetAccessible()) {
        return -1L;
      }
      return ((Boolean) method.invoke(websocket)) ? 1L : 0L;
    } catch (final ReflectiveOperationException | RuntimeException e) {
      return -1L;
    }
  }

  private static void parkUntil(final long deadlineNanos) {
    final long wait = deadlineNanos - System.nanoTime();
    if (wait > 0L) {
      LockSupport.parkNanos(wait);
    }
  }

  private static long millisUntil(final long deadlineNanos) {
    return Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
  }

  private static void join(final Thread thread, final long deadlineNanos) throws InterruptedException {
    if (thread != null) {
      final long remaining = millisUntil(deadlineNanos);
      if (remaining > 0L) {
        thread.join(Math.min(remaining, 10_000L));
      }
    }
  }

  private void pass(final Property property) {
    ctx.properties().pass(property.id());
  }

  private void fail(final Property property, final String example) {
    ctx.properties().fail(property.id(), example);
  }

  private void notEvaluated(final Property property, final String reason) {
    ctx.properties().notEvaluated(property.id(), reason);
  }
}
