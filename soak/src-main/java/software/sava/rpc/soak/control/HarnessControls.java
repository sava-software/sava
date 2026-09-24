package software.sava.rpc.soak.control;

import software.sava.rpc.soak.Counters;
import software.sava.rpc.soak.GaugeSampler;
import software.sava.rpc.soak.Phase;
import software.sava.rpc.soak.RunIdentity;
import software.sava.rpc.soak.SoakConfig;
import software.sava.rpc.soak.SoakContext;
import software.sava.rpc.soak.Workload;
import software.sava.rpc.soak.events.SoakEvents;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/// The deliberately broken runs: negative controls that must make a gate fail.
///
/// A gate nobody has ever seen fail is a gate nobody knows works. `controls.tsv` names each of
/// these with the gate it is expected to trip, and `soak.sh --controls` runs them as short
/// `control`-profile runs; a control that *passes* the run is itself the failure. This class owns
/// the harness-side injections — the peer owns D1–D6 and D12, which are wire behaviours, not
/// harness ones.
///
/// | id | injection | the gate it must trip |
/// |---|---|---|
/// | D7 | a cancellation tombstoned two seconds before its unsubscribe is sent | `W1-D` |
/// | D8 | 64 unjoined parked platform threads | `SOAK_THREAD_MARGIN` (W5-A) |
/// | D9 | 256 sockets opened to the peer and never closed | `SOAK_FD_MARGIN` (W5-C) |
/// | D10 | a static list retained at the rate that fills a quarter of the heap over STEADY | the after-GC heap-floor slope |
/// | D11 | the websocket driver suppressed | every W1/W2 property `UNEXERCISED` |
/// | D13 | HTTP in-flight forced to 1 at 200 rps | `SOAK_SKIP_LIMIT_PCT` starvation → INVALID |
/// | D14 | a fake subject code source reported | the subject gate → INVALID |
///
/// Each injection is bounded even though its purpose is to look unbounded: an injection that
/// actually exhausted the machine would take the run's artifacts with it, and a control whose
/// evidence is a dead JVM proves nothing. The bound is far above the gate's threshold and its
/// overflow is counted, so "the control hit its own cap" is never silently mistaken for "the
/// gate held".
///
/// **An injection has to land where the gate can see it.** Every gate here is a comparison
/// against a *baseline* or a *limit* that the run itself computes, so an injection that precedes
/// the baseline, or that is smaller than the limit the run derives, is not a negative control at
/// all — it is a control that can never fail, which is the same blind spot it exists to rule out.
/// That is why D8 and D9 inject after the `STEADY` baselines rather than at start-up
/// ([#onPhase]), and why D10's rate is sized against the limit the run derives rather than the
/// one it is configured with ([#D10_MAX_BYTES_PER_MINUTE]). Both rules were written after a measured
/// miss, not from first principles.
public final class HarnessControls implements Workload, GaugeSampler.GaugeSource {

  /// Stable, short, and used as a column value: see [Workload#name()].
  public static final String NAME = "control";

  public static final String D7_TOMBSTONE_BEFORE_UNSUB = "D7";
  public static final String D8_UNJOINED_THREADS = "D8";
  public static final String D9_UNCLOSED_SOCKETS = "D9";
  public static final String D10_RETAINED_ALLOCATION = "D10";
  public static final String D11_NO_WEBSOCKET_DRIVER = "D11";
  public static final String D13_STARVED_HTTP = "D13";
  public static final String D14_FAKE_SUBJECT = "D14";
  /// A SIGTERM to this JVM a few seconds into STEADY - what a ctrl-c or a host shutdown delivers -
  /// so the sheet proves an interrupted run reads INCOMPLETE. The shutdown hook writes the counters
  /// and an ABORTED phase row and the runner sees exit 143: a copied run with exactly those
  /// artifacts re-reported as PASS before the verdict read either signal (review, 2026-09-24).
  public static final String INTERRUPTED = "INTERRUPTED";
  /// One notification consumer parks for the rest of the run on the thread the subject delivers
  /// on. Staged inside the consumer factory (see `ws.ConsumerFactory`), because the stall has to
  /// happen where deliveries happen; this class only owns the id and the arming delay.
  public static final String STALL_CALLBACK = "STALL-CALLBACK";

  static final int D8_THREADS = 64;
  static final int D9_SOCKETS = 256;

  /// How long D7 holds a cancellation in the websocket driver's own registry before the unsubscribe
  /// goes on the wire.
  ///
  /// It is a harness injection and not a peer one because a peer-side zombie cannot move W1-D on a
  /// correct client: sava's own cancellation tombstones drop a notification for a subId it has
  /// cancelled, which is exactly what the `F7` fault control demonstrates. The defect W1-D's oracle
  /// can actually read is a *client* that forgets a registration before it cancels it upstream, so
  /// that is what this stages. Two seconds because the peer emits for a subscribed key several times
  /// a second at the control profile's churn rate: the gap has to be wide enough to certainly
  /// contain a delivery, and short enough that the plan's later steps for that key are not all
  /// inside it.
  static final long D7_TOMBSTONE_LEAD_MILLIS = 2_000L;

  /// D10's retention rate is derived per run, not fixed: the control has to keep the after-GC
  /// floor RISING for the whole of STEADY, because the gate fits a slope over that window and a
  /// retention that reaches its cap early reads as a plateau (measured 2026-09-22: a fixed rate
  /// filled the quarter-heap cap during warm-up and the fitted slope was a seventh of the limit).
  /// The rate is therefore the cap spread over STEADY, clamped between these two: the floor keeps
  /// a short STEADY from asking for less than the gate's applied limit can see, the ceiling keeps
  /// a long one from allocating faster than the collector can classify. Nothing about the gate's
  /// own thresholds is relaxed to meet it: loosening a floor would weaken every real run.
  static final int D10_MIN_BYTES_PER_MINUTE = 16 << 20;
  static final int D10_MAX_BYTES_PER_MINUTE = 96 << 20;
  static final int D10_CHUNK_BYTES = 1 << 20;

  /// The allocator's period. A minute's worth delivered once a minute is a step, not a slope: a
  /// control run's fit window holds one such tick, and a least-squares line through one step
  /// reads as noise. Retaining the same rate every few seconds gives the fit the shape it is
  /// looking for, which is what makes the measured slope match the injected rate.
  static final int D10_TICK_SECONDS = 5;

  /// Chunks per tick for a rate, derived from the rate and the period so the two can never
  /// disagree; at least one, so a rate below one chunk a tick still allocates rather than silently
  /// stopping.
  static int chunksPerTick(final long bytesPerMinute) {
    return (int) Math.max(1L, bytesPerMinute * D10_TICK_SECONDS / 60L / D10_CHUNK_BYTES);
  }

  /// How long after the `STEADY` boundary D8's and D9's injections land. It only has to clear the
  /// boundary's own `sampleNow()` and the same notification pass's baselines (see [#onPhase]);
  /// `STEADY` is 45 s or more even in a control run, so a few seconds costs the control no
  /// measured window and buys a baseline the injection is not part of.
  static final int INJECT_DELAY_SECONDS = 5;

  static final int D13_INFLIGHT = 1;
  static final int D13_RPS = 200;

  /// The value D14 reports as the subject's code source. Deliberately outside
  /// `<savaRoot>/sava-rpc/build`, which is the whole point: the gate must refuse it.
  public static final String D14_CODE_SOURCE = "file:///nonexistent/fake-subject/sava-rpc.jar";

  /// Counter names this class owns. They are not in [Counters]' fixed list because they describe
  /// the harness's own controls rather than the subject, but they are written to
  /// `counters.properties` like any other name so a control run's evidence is in the artifacts.
  static final String RETAIN_CAPPED = "harness.control.retainCapped";
  static final String SOCKETS_OPENED = "harness.control.socketsOpened";
  static final String SOCKETS_REFUSED = "harness.control.socketsRefused";
  static final String THREADS_PARKED = "harness.control.threadsParked";
  static final String INTERRUPT_SENT = "harness.control.interruptSent";

  /// D10's retention is `static` on purpose: a field on the workload would be released when the
  /// workload is, and the control has to survive `drain()` to be visible in the heap floor at the
  /// end of the run. It is bounded so the control cannot OOM the JVM it is evidence about.
  private static final List<byte[]> RETAINED = new CopyOnWriteArrayList<>();

  private final SoakContext ctx;
  private final String id;
  private final long retainCapBytes;
  /// D10's derived rate (see [#D10_MIN_BYTES_PER_MINUTE]) and the chunks each tick adds for it.
  private final long retainBytesPerMinute;
  private final int retainChunksPerTick;
  /// Copy-on-write because the injection now lands on a timer thread while the gauge reads
  /// [#openConnections()] and `close()` releases from the phase thread; 256 copies of a small
  /// array once per run is not a cost worth a lock.
  private final List<Socket> sockets;
  private final List<Thread> parked;
  private final AtomicBoolean stopped;
  private final AtomicBoolean injected;
  private volatile java.util.concurrent.ScheduledFuture<?> allocator;
  private volatile java.util.concurrent.ScheduledFuture<?> injection;

  private HarnessControls(final SoakContext ctx, final String id, final long retainCapBytes) {
    this.ctx = ctx;
    this.id = id;
    this.retainCapBytes = retainCapBytes;
    final var config = ctx.config();
    // The window the slope is fitted over: STEADY minus the injection delay, never less than a
    // tick's worth of minutes.
    final long steadySeconds = Math.max(D10_TICK_SECONDS, config.durationSeconds() - config.warmupSeconds()
        - config.quiesceSeconds() - config.drainSeconds() - INJECT_DELAY_SECONDS);
    this.retainBytesPerMinute = Math.clamp(retainCapBytes * 60L / steadySeconds,
        D10_MIN_BYTES_PER_MINUTE, D10_MAX_BYTES_PER_MINUTE);
    this.retainChunksPerTick = chunksPerTick(retainBytesPerMinute);
    this.sockets = new CopyOnWriteArrayList<>();
    this.parked = new CopyOnWriteArrayList<>();
    this.stopped = new AtomicBoolean();
    this.injected = new AtomicBoolean();
  }

  /// The control workload for this run, or `null` when `SOAK_CONTROL` names nothing this class
  /// owns. Returning `null` rather than a no-op instance keeps a control run's workload list
  /// honest: the report's "which drivers ran" line is then the truth.
  public static HarnessControls of(final SoakContext ctx) {
    final var id = normalise(ctx.config().control());
    if (!owns(id)) {
      return null;
    }
    // A quarter of the heap: far above the slope gate, far below what would end the run.
    final long cap = Math.max(D10_MAX_BYTES_PER_MINUTE, (long) ctx.config().heapMb() * (1L << 20) / 4L);
    return new HarnessControls(ctx, id, cap);
  }

  /// True when `id` is one of the injections this class performs, so that a `SOAK_CONTROL`
  /// naming a peer-side control (D1–D6, D12) does not produce an idle harness workload.
  public static boolean owns(final String id) {
    return switch (normalise(id)) {
      case D7_TOMBSTONE_BEFORE_UNSUB, D8_UNJOINED_THREADS, D9_UNCLOSED_SOCKETS,
           D10_RETAINED_ALLOCATION, D11_NO_WEBSOCKET_DRIVER, D13_STARVED_HTTP,
           D14_FAKE_SUBJECT, STALL_CALLBACK, INTERRUPTED -> true;
      default -> false;
    };
  }

  /// See [#INTERRUPTED]. A real signal rather than System.exit, so the JVM takes the same path a
  /// user's interrupt takes: signal handler, shutdown hooks, exit 143.
  private void interruptSelf(final Counters counters) {
    counters.increment(INTERRUPT_SENT);
    try {
      new ProcessBuilder("kill", "-TERM", Long.toString(ProcessHandle.current().pid())).inheritIO().start();
    } catch (final java.io.IOException e) {
      throw new IllegalStateException("the interrupted control could not signal this JVM", e);
    }
  }

  private static String normalise(final String control) {
    return control == null ? "" : control.strip().toUpperCase(java.util.Locale.ROOT);
  }

  private static boolean is(final SoakConfig config, final String id) {
    return id.equals(normalise(config.control()));
  }

  // --- the hooks the other drivers consult ------------------------------------------------------

  /// False under D11, which suppresses the websocket driver so that every W1/W2 property reads
  /// `UNEXERCISED` — the control that proves an unexercised property is a FAIL rather than a
  /// clean sheet.
  public static boolean webSocketDriverEnabled(final SoakConfig config) {
    return !is(config, D11_NO_WEBSOCKET_DRIVER);
  }

  /// D13's half of the starvation control: one future in flight per worker, so the harness itself
  /// cannot keep up with its own rate and `harness.opsSkipped.inflightCap` climbs past
  /// `SOAK_SKIP_LIMIT_PCT`.
  public static int httpInflight(final SoakConfig config) {
    return is(config, D13_STARVED_HTTP) ? D13_INFLIGHT : config.httpInflight();
  }

  /// D13's other half: the rate the starved workers are asked for.
  public static int httpRps(final SoakConfig config) {
    return is(config, D13_STARVED_HTTP) ? D13_RPS : config.httpRps();
  }

  /// D7's injection, read by the websocket driver: how long a cancellation stays in the harness's
  /// own registry before the unsubscribe is sent. Zero on every other run, which is the ordinary
  /// behaviour - the tombstone and the wire call happen in one step - so no other run can be judged
  /// under the gap this opens. See [#D7_TOMBSTONE_LEAD_MILLIS].
  public static long unsubscribeTombstoneLeadMillis(final SoakConfig config) {
    return is(config, D7_TOMBSTONE_BEFORE_UNSUB) ? D7_TOMBSTONE_LEAD_MILLIS : 0L;
  }

  /// True when this run stages the stall-callback control: the consumer factory then parks the
  /// first delivery after [#stallArmDelayMillis] of STEADY, on the subject's delivery thread, and
  /// never returns from it.
  public static boolean stallCallback(final SoakConfig config) {
    return is(config, STALL_CALLBACK);
  }

  /// The same delay as the other deferred injections: after the STEADY baselines are taken, so a
  /// gauge's first STEADY row is not already the injected state.
  public static long stallArmDelayMillis() {
    return INJECT_DELAY_SECONDS * 1_000L;
  }

  /// The fake code source D14 wants `run-client.json` to carry, or `null`.
  ///
  /// [RunIdentity] reads this from a system property because it captures identity before any
  /// workload exists — the subject gate runs before `READY`, which is exactly the point of it.
  /// The property therefore has to be on the launch line
  /// (`-Dsoak.subject.codeSource=<value>`); this method exists so the runner and the control
  /// table read the value from one place, and [#start(SoakContext)] says so out loud when the
  /// launch line forgot it.
  public static String subjectCodeSourceOverride(final SoakConfig config) {
    return is(config, D14_FAKE_SUBJECT) ? D14_CODE_SOURCE : null;
  }

  // --- lifecycle --------------------------------------------------------------------------------

  /// Serves both [Workload#name()] and [GaugeSampler.GaugeSource#name()]: one driver, one name,
  /// so the gauge row and the phase log cannot disagree about which workload they describe.
  @Override
  public String name() {
    return NAME;
  }

  public String id() {
    return id;
  }

  @Override
  public void start(final SoakContext context) {
    final var counters = ctx.counters();
    switch (id) {
      case D8_UNJOINED_THREADS, D9_UNCLOSED_SOCKETS, D10_RETAINED_ALLOCATION, INTERRUPTED -> {
        // Deferred to STEADY: see onPhase. Announced here anyway, so a run whose phases never
        // reached STEADY still says which control it was asked to be. D10 is deferred for the
        // same reason as the other two and one more: retention that starts at launch spends its
        // cap before the window the slope is fitted over has begun.
      }
      case D7_TOMBSTONE_BEFORE_UNSUB, D11_NO_WEBSOCKET_DRIVER, D13_STARVED_HTTP, STALL_CALLBACK -> {
        // Nothing to inject here: each is read by the other drivers through the static hooks
        // above, so that the injection happens where the behaviour is rather than at a distance.
      }
      case D14_FAKE_SUBJECT -> announceSubjectOverride();
      default -> throw new IllegalStateException("not a harness control: " + id);
    }
    observed(id, describe(), 0L);
    ctx.registerGauge(this);
  }

  /// Installs D8's threads and D9's sockets [#INJECT_DELAY_SECONDS] seconds into `STEADY`, and
  /// leaves them installed for the rest of the run.
  ///
  /// They are *not* installed in [#start(SoakContext)], because the two gates they exist to trip
  /// are margins over a baseline and both baselines are taken at the `STEADY` boundary, which is
  /// long after start-up: `soak.sh` reads `thr_base`/`fd_base` from the first `STEADY` row of
  /// `client.csv`, and `ChurnWorkload.onPhase` captures its own thread and descriptor baselines
  /// on this same notification pass — this workload is first in the list, so notifying it first
  /// would otherwise hand the churn driver a poisoned baseline too. An injection that precedes
  /// either reading is baselined away rather than measured: on 2026-09-21 D8's 64 threads sat
  /// inside a 124-thread `STEADY` baseline, so `threads <= baseline + margin` held all run and
  /// the thread-margin gate could not have fired however badly the subject leaked. The delay is
  /// what keeps the injection out of the boundary's own `sampleNow()`, which runs immediately
  /// after this pass.
  ///
  /// A run that never reaches `STEADY` never injects, deliberately: the residue would have no
  /// baseline to be measured against, and a control whose gate had nothing to compare is not
  /// evidence either way.
  @Override
  public void onPhase(final Phase phase) {
    if (phase != Phase.STEADY || !isDeferred() || !injected.compareAndSet(false, true)) {
      return;
    }
    injection = ctx.timer().schedule(
        this::inject, INJECT_DELAY_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
  }

  /// True for the two injections that have to land after the baselines rather than at start-up.
  private boolean isDeferred() {
    return D8_UNJOINED_THREADS.equals(id) || D9_UNCLOSED_SOCKETS.equals(id)
        || D10_RETAINED_ALLOCATION.equals(id) || INTERRUPTED.equals(id);
  }

  private void inject() {
    if (stopped.get()) {
      return;
    }
    final var counters = ctx.counters();
    try {
      switch (id) {
        case D8_UNJOINED_THREADS -> parkThreads(counters);
        case D9_UNCLOSED_SOCKETS -> openSockets(counters);
        case D10_RETAINED_ALLOCATION -> startRetaining(counters);
        case INTERRUPTED -> {
          interruptSelf(counters);
          return;
        }
        default -> throw new IllegalStateException("not a deferred injection: " + id);
      }
    } catch (final RuntimeException | Error e) {
      // A scheduled task's throw lands in a future nobody reads, and a control that silently did
      // not inject is the failure this whole class exists to rule out: say so on the run's log.
      System.err.println("soak: control " + id + " failed to inject: " + e);
      throw e;
    }
    if (stopped.get()) {
      // close() ran while the injection was landing. Release it here rather than leave this run's
      // residue in the next run's baseline; release() is idempotent, so racing close() is safe.
      release();
    } else {
      observed(id, installed(), 1L);
    }
  }

  @Override
  public void quiesce(final Duration bound) {
    // Deliberately nothing. The residue is the evidence.
  }

  @Override
  public void drain(final Duration bound) {
    // Deliberately nothing: D8's threads and D9's sockets must still be there when the ownership
    // and margin gates read them, which is the whole control.
  }

  @Override
  public void close() {
    if (!stopped.compareAndSet(false, true)) {
      return;
    }
    final var allocator = this.allocator;
    if (allocator != null) {
      allocator.cancel(false);
    }
    final var injection = this.injection;
    if (injection != null) {
      // A control that never got past the delay has nothing to release; one that is mid-injection
      // releases itself when it finds `stopped`.
      injection.cancel(false);
    }
    release();
    RETAINED.clear();
  }

  /// Releases D8's threads and D9's sockets.
  ///
  /// SHUTDOWN is past every gate, so the injections are released here rather than left for the
  /// operating system: a control run that leaves 256 sockets behind makes the *next* run's
  /// baseline wrong. Idempotent, because an injection that lands during shutdown has to be able
  /// to release itself.
  private void release() {
    for (final var socket : sockets) {
      try {
        socket.close();
      } catch (final IOException ignored) {
        // Closing a socket that the peer already reset is not news at shutdown.
      }
    }
    sockets.clear();
    for (final var thread : parked) {
      thread.interrupt();
    }
    parked.clear();
  }

  // --- the injections ---------------------------------------------------------------------------

  /// 64 daemon platform threads that park forever.
  ///
  /// Daemon, so that a control run still exits and still writes its artifacts; the gate reads
  /// `ThreadMXBean.getThreadCount()`, which counts a daemon thread exactly like any other, so
  /// daemon-ness costs the control nothing and buys back the run's evidence.
  private void parkThreads(final Counters counters) {
    for (int i = 0; i < D8_THREADS; ++i) {
      final var thread = new Thread(HarnessControls::parkForever, "soak-control-d8-" + (i + 1));
      thread.setDaemon(true);
      thread.start();
      parked.add(thread);
      counters.increment(THREADS_PARKED);
    }
  }

  private static void parkForever() {
    while (!Thread.currentThread().isInterrupted()) {
      LockSupport.park();
    }
  }

  /// 256 connections to the peer's HTTP port, opened and never read from.
  ///
  /// They are opened against the peer rather than against an arbitrary port so the file
  /// descriptors are the same *kind* the gate is meant to notice, and so the peer's own
  /// `openConnections` reflects them too — two independent readings of one leak.
  private void openSockets(final Counters counters) {
    final int port = ctx.config().httpPort();
    if (ctx.live() || port <= 0) {
      System.out.println("soak: control D9 needs the local peer's HTTP port; none configured");
      return;
    }
    for (int i = 0; i < D9_SOCKETS && !stopped.get(); ++i) {
      try {
        final var socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port),
            (int) SoakContext.CONNECT_TIMEOUT.toMillis());
        sockets.add(socket);
        counters.increment(SOCKETS_OPENED);
      } catch (final IOException e) {
        counters.increment(SOCKETS_REFUSED);
      }
    }
  }

  /// [#retainBytesPerMinute] into a static list, paced on the shared timer rather than a sleeping
  /// thread, in [#D10_TICK_SECONDS] steps so the retention reads as a slope rather than a step.
  private void startRetaining(final Counters counters) {
    allocator = ctx.timer().scheduleAtFixedRate(
        () -> retain(counters), 0L, D10_TICK_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
  }

  private void retain(final Counters counters) {
    if (stopped.get()) {
      return;
    }
    if ((long) RETAINED.size() * D10_CHUNK_BYTES >= retainCapBytes) {
      // The control has made its point and the gate has long since had its evidence; retaining
      // more would only risk the artifacts this run exists to produce.
      counters.increment(RETAIN_CAPPED);
      return;
    }
    for (int i = 0; i < retainChunksPerTick; ++i) {
      final byte[] chunk = new byte[D10_CHUNK_BYTES];
      // Touch it: an untouched array is a page the collector and the RSS sample may never see.
      chunk[0] = 1;
      chunk[chunk.length - 1] = 1;
      RETAINED.add(chunk);
    }
  }

  private void announceSubjectOverride() {
    final var expected = D14_CODE_SOURCE;
    final var actual = System.getProperty(RunIdentity.SUBJECT_OVERRIDE_PROPERTY);
    if (!expected.equals(actual)) {
      System.out.println("soak: control D14 needs -D" + RunIdentity.SUBJECT_OVERRIDE_PROPERTY
          + '=' + expected + " on the launch line; identity is captured before any workload exists");
    }
  }

  /// What the control is going to be, announced at start-up. For the deferred pair this is a
  /// plan rather than a reading, and says so: at start-up nothing is parked and no socket is
  /// open, and a line claiming otherwise would be the first thing to mislead a reader of the log.
  private String describe() {
    return switch (id) {
      case D7_TOMBSTONE_BEFORE_UNSUB -> "registry tombstone " + D7_TOMBSTONE_LEAD_MILLIS
          + " ms before the unsubscribe goes on the wire";
      case D8_UNJOINED_THREADS -> D8_THREADS + " parked daemon threads, "
          + INJECT_DELAY_SECONDS + "s into STEADY";
      case D9_UNCLOSED_SOCKETS -> D9_SOCKETS + " unclosed sockets, "
          + INJECT_DELAY_SECONDS + "s into STEADY";
      case D10_RETAINED_ALLOCATION -> retainBytesPerMinute + " bytes per minute retained every "
          + D10_TICK_SECONDS + "s from " + INJECT_DELAY_SECONDS + "s into STEADY, cap " + retainCapBytes;
      case D11_NO_WEBSOCKET_DRIVER -> "websocket driver suppressed";
      case D13_STARVED_HTTP -> "http inflight " + D13_INFLIGHT + " at " + D13_RPS + " rps";
      case D14_FAKE_SUBJECT -> "subject code source " + D14_CODE_SOURCE;
      case STALL_CALLBACK -> "one notification consumer parked for the rest of the run, armed "
          + INJECT_DELAY_SECONDS + "s into STEADY";
      default -> id;
    };
  }

  /// What the deferred injection actually installed, announced once it has. The counts are the
  /// realised ones and are printed out of `D8_THREADS`/`D9_SOCKETS`, so a control the peer
  /// refused half of is not read as a control that ran.
  private String installed() {
    return switch (id) {
      case D8_UNJOINED_THREADS -> parked.size() + " of " + D8_THREADS + " daemon threads parked";
      case D9_UNCLOSED_SOCKETS -> sockets.size() + " of " + D9_SOCKETS + " sockets open";
      default -> describe();
    };
  }

  /// `ordinal` separates the start-up announcement (0) from the injection's own (1), so the two
  /// `CONTROL_<id>` events a deferred control commits are distinguishable in the recording.
  private void observed(final String kind, final String detail, final long ordinal) {
    final var event = new SoakEvents.FaultObserved();
    if (event.shouldCommit()) {
      event.kind = "CONTROL_" + kind;
      event.ordinal = ordinal;
      event.scope = "HARNESS";
      event.target = NAME;
      event.detail = detail;
      event.commit();
    }
    final var state = ordinal != 0L ? " installed - " : isDeferred() ? " armed - " : " active - ";
    System.out.println("soak: control " + kind + state + detail);
  }

  // --- gauge ------------------------------------------------------------------------------------

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
    return -1L;
  }

  /// D9's sockets, so the leak is visible in `client.csv` beside the fd count rather than only in
  /// the fd count — two readings that must move together.
  @Override
  public long openConnections() {
    return sockets.size();
  }

  @Override
  public long lastMessageAgeMillisMax() {
    return -1L;
  }
}
