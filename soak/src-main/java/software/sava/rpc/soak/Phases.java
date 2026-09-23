package software.sava.rpc.soak;

import software.sava.rpc.soak.events.SoakEvents;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/// The run's clock: it drives the workloads through STARTUP → WARMUP → STEADY → QUIESCE → DRAIN →
/// SHUTDOWN, overlays the STEADY sub-phases on top, records every boundary in `phases.tsv` and as
/// a `sava.soak.Phase` event, and bounds each stage.
///
/// Three decisions worth stating. Every deadline is monotonic (`System.nanoTime` plus a latch
/// await), never wall-clock arithmetic or a sleep loop — a soak that drifts with the system clock
/// cannot align its phases with the peer's log. Each stage runs on its own thread with its bound
/// plus a sixty-second grace, and a breach ends the run `INCOMPLETE` rather than `FAIL`: nothing
/// was disproved, the evidence is short, and those are different verdicts. And the STEADY
/// overlays are computed up front from the configuration, so the schedule a run followed is a
/// pure function of `run.env` and can be replayed.
final class Phases implements AutoCloseable {

  /// A stage gets its own bound plus this, after which the run is INCOMPLETE. The grace is the
  /// room a bounded stage needs to notice its own bound and unwind.
  static final Duration GRACE = Duration.ofSeconds(60);

  /// The HTTP quiet/loaded alternation and the OVERLAP fan-out period, both from the design's
  /// W4 overlay. `OVERLAP_SECONDS` is the period between fan-outs; [#firstOverlap] places the
  /// first one, which on a short profile is earlier than a whole period in.
  static final long SUB_PHASE_SECONDS = 120L;
  static final long OVERLAP_SECONDS = 300L;
  static final long STORM_PERIOD_SECONDS = 3600L;

  private static final int SUB_TOGGLE = 0;
  private static final int OVERLAP_TRIGGER = 1;
  private static final int STORM_START = 2;
  private static final int STORM_END = 3;

  /// The outcome of the phase machine. `reason` is empty on a complete run and otherwise names
  /// the stage that breached, for `summary.md`.
  record Result(boolean complete, String reason) {

    static final Result COMPLETE = new Result(true, "");
  }

  private final SoakContext ctx;
  private final List<Workload> workloads;
  private GaugeSampler gauges;
  private final Tsv phasesTsv;
  private final ExecutorService stageExecutor;
  private final CountDownLatch stop;
  private final AtomicInteger index;
  private SoakEvents.PhaseEvent openWindow;

  private Phases(final SoakContext ctx,
                 final List<Workload> workloads,
                 final Tsv phasesTsv) {
    this.ctx = ctx;
    this.workloads = workloads;
    this.phasesTsv = phasesTsv;
    this.stageExecutor = Executors.newSingleThreadExecutor(SoakContext.named("soak-phase"));
    this.stop = new CountDownLatch(1);
    this.index = new AtomicInteger();
  }

  /// `phases.tsv` is headerless `epochMillis\tname\tindex`, exactly as the artifact table
  /// specifies: the report joins it against wall-clock rows from two processes, and a header
  /// would have to be special-cased by every reader.
  static Phases open(final SoakContext ctx, final List<Workload> workloads) throws IOException {
    final var tsv = Tsv.open(ctx.runDir().resolve("phases.tsv"), null, ctx.counters());
    return new Phases(ctx, workloads, tsv);
  }

  /// The sampler is attached after STARTUP, because it needs the JFR counters, which must not
  /// start until the peers are reachable. Boundaries recorded before it exists simply take no
  /// sample.
  void gauges(final GaugeSampler sampler) {
    this.gauges = sampler;
  }

  /// Records the STARTUP boundary. Called before the workloads start, so every artifact row a
  /// driver writes during start-up is already attributed to a phase.
  void startup() {
    boundary(Phase.STARTUP, "peers, subject, self-tests, first registrations");
  }

  /// Drives WARMUP through SHUTDOWN. SHUTDOWN always runs, even after a breach: a run that ran
  /// out of time still owes its counters, its ledger and its final gauge.
  Result drive() {
    final var config = ctx.config();
    final long steadySeconds = Math.max(1L, (long) config.durationSeconds() - config.warmupSeconds());
    var result = stage(Phase.WARMUP, Duration.ofSeconds(config.warmupSeconds()),
        "faults off, correctness on", this::warmup);
    if (result.complete()) {
      result = stage(Phase.STEADY, Duration.ofSeconds(steadySeconds),
          "faults on, overlays scheduled", bound -> steady(bound, steadySeconds, config.stormSeconds()));
    }
    if (result.complete()) {
      result = stage(Phase.QUIESCE, Duration.ofSeconds(config.quiesceSeconds()),
          "no new work, in-flight drained", this::quiesce);
    }
    if (result.complete()) {
      result = stage(Phase.DRAIN, Duration.ofSeconds(config.drainSeconds()),
          "engines and per-cycle clients closed", this::drain);
    }
    final var shutdown = stage(Phase.SHUTDOWN, Duration.ofSeconds(60L),
        "final gauge, counters, ledger", this::shutdown);
    return result.complete() ? shutdown : result;
  }

  private void warmup(final Duration bound) {
    await(bound.toNanos());
  }

  private void steady(final Duration bound, final long steadySeconds, final long stormSeconds) {
    final long startNanos = System.nanoTime();
    final long endNanos = startNanos + bound.toNanos();
    var sub = Phase.HTTP_QUIET;
    boundary(sub, "steady sub-phase");
    // One full collection at each end of STEADY, and none in between. The retention lens is the
    // after-collection heap, and on G1 only a full collection reads the live set: after a young
    // or a mixed pause the number carries whatever the old generation had not yet been asked to
    // give back (measured on a pilot: 300 to 750 MiB between mixed pauses against 23.6 MiB after
    // the full collection at the end). The rule against collecting to measure is about a
    // collection inside the window; a boundary collection is the before and the after.
    fullCollection("STEADY start");
    for (final var event : schedule(steadySeconds, stormSeconds)) {
      final long dueNanos = startNanos + event[0] * 1_000_000_000L;
      final long waitNanos = dueNanos - System.nanoTime();
      if (waitNanos > 0L && !await(waitNanos)) {
        return;
      }
      switch ((int) event[1]) {
        case SUB_TOGGLE -> {
          sub = sub == Phase.HTTP_QUIET ? Phase.HTTP_LOADED : Phase.HTTP_QUIET;
          boundary(sub, "steady sub-phase");
        }
        case OVERLAP_TRIGGER -> marker(Phase.OVERLAP, "fan-out trigger");
        case STORM_START -> boundary(Phase.FAULT_STORM, "fault storm begins");
        case STORM_END -> boundary(sub, "fault storm ends");
        default -> throw new IllegalStateException("unknown scheduled event " + event[1]);
      }
    }
    final long remaining = endNanos - System.nanoTime();
    if (remaining > 0L) {
      await(remaining);
    }
    fullCollection("STEADY end");
  }

  /// The boundary collection (see [#steady]), recorded as a marker so the phase log says when the
  /// floor's two readings were taken.
  private void fullCollection(final String at) {
    marker(Phase.STEADY, "full collection forced at " + at);
    System.gc();
  }

  /// The STEADY overlay schedule as `{offsetSeconds, eventCode}` pairs, in time order. Pure and
  /// deterministic: the same `run.env` yields the same schedule, so a finding's phase context is
  /// reproducible without reading the run's own `phases.tsv`.
  static List<long[]> schedule(final long steadySeconds, final long stormSeconds) {
    final var events = new ArrayList<long[]>();
    for (long at = SUB_PHASE_SECONDS; at < steadySeconds; at += SUB_PHASE_SECONDS) {
      events.add(new long[]{at, SUB_TOGGLE});
    }
    for (long at = firstOverlap(steadySeconds); at < steadySeconds; at += OVERLAP_SECONDS) {
      events.add(new long[]{at, OVERLAP_TRIGGER});
    }
    for (final var storm : storms(steadySeconds, stormSeconds)) {
      events.add(new long[]{storm[0], STORM_START});
      events.add(new long[]{storm[0] + storm[1], STORM_END});
    }
    // a stable sort, so a toggle and a storm boundary landing on the same second are applied in
    // the order they were added: the sub-phase moves first, then the storm window wraps it.
    events.sort(Comparator.comparingLong(event -> event[0]));
    return List.copyOf(events);
  }

  /// When the first OVERLAP fan-out fires, measured from the start of STEADY: the period, or the
  /// midpoint of a STEADY shorter than twice the period.
  ///
  /// The plain `at = OVERLAP_SECONDS` start placed the first overlay 300 s in, which is past the
  /// end of every short profile's STEADY — a 240 s validate run has 210 s of it — so the fan-out
  /// never ran, W4-C was never evaluated, and the run recorded a stated skip ("the run ended
  /// before an OVERLAP overlay occurred") for the one property that exists to catch a common pool
  /// too busy to fire its own deadline timer. A validation that cannot reach the code path it
  /// validates is not a shorter campaign, it is a different one; the storm windows already follow
  /// this rule for the same reason. Long profiles are unchanged: at 3240 s of STEADY the midpoint
  /// is well past the period, so the first fan-out stays at 300 s.
  static long firstOverlap(final long steadySeconds) {
    return Math.min(OVERLAP_SECONDS, steadySeconds / 2L);
  }

  /// The storm windows, once per hour of STEADY. The first one sits at the half-hour mark, or at
  /// the midpoint of a run shorter than an hour, so that a four-minute validate run still
  /// exercises a storm instead of silently skipping the code path the campaign depends on.
  static List<long[]> storms(final long steadySeconds, final long stormSeconds) {
    final var storms = new ArrayList<long[]>();
    if (stormSeconds <= 0L) {
      return List.of();
    }
    final long firstOffset = Math.min(STORM_PERIOD_SECONDS / 2L, steadySeconds / 2L);
    for (long k = 0L; ; ++k) {
      final long start = k * STORM_PERIOD_SECONDS + firstOffset;
      if (start + stormSeconds >= steadySeconds) {
        break;
      }
      storms.add(new long[]{start, stormSeconds});
    }
    return List.copyOf(storms);
  }

  private void quiesce(final Duration bound) {
    final long deadline = System.nanoTime() + bound.toNanos();
    if (ctx.peerAdmin().available()) {
      ctx.peerAdmin().quiesce();
    }
    for (final var workload : workloads) {
      try {
        workload.quiesce(remaining(deadline, bound));
      } catch (final Exception e) {
        System.err.println("soak: " + workload.name() + " failed to quiesce: " + Redaction.text(e.toString()));
      }
    }
    final long left = deadline - System.nanoTime();
    if (left > 0L) {
      await(left);
    }
  }

  private void drain(final Duration bound) {
    final long deadline = System.nanoTime() + bound.toNanos();
    for (final var workload : workloads) {
      try {
        workload.drain(remaining(deadline, bound));
      } catch (final Exception e) {
        System.err.println("soak: " + workload.name() + " failed to drain: " + Redaction.text(e.toString()));
      }
    }
    final long left = deadline - System.nanoTime();
    if (left > 0L) {
      await(left);
    }
  }

  /// The final gauge is taken first, before the close loop dismantles anything the harness owns.
  /// A sample taken after that loop measures the teardown rather than the subject: the runner's
  /// thread and descriptor gates read the last `client.csv` row against the STEADY baseline, so a
  /// post-teardown endpoint hands them credit for every thread and socket the harness itself has
  /// just released — the descriptor control's sockets are closed by this very loop, and the gate
  /// that exists to trip on them saw them gone. `ChurnWorkload` states the same rule for its own
  /// descriptor assertion, which is why that one runs in QUIESCE and not in DRAIN.
  ///
  /// The counters, the ledger and the peer's totals are still gathered after the loop, so a
  /// close-time increment reaches `counters.properties`; only the gauge endpoint has to precede
  /// the closing.
  private void shutdown(final Duration bound) {
    if (gauges != null) {
      gauges.sampleNow();
    }
    for (final var workload : workloads) {
      try {
        workload.close();
      } catch (final RuntimeException e) {
        System.err.println("soak: " + workload.name() + " failed to close: " + Redaction.text(e.toString()));
      }
    }
    if (ctx.peerAdmin().available()) {
      ctx.peerAdmin().flush();
      final var stats = ctx.peerAdmin().stats();
      // `logDropped` is negative when the read itself failed (PeerAdmin reports an unreachable or
      // non-2xx /__soak/stats as Stats.UNAVAILABLE) or when the body carried no such field, and a
      // negative value must never be published as a total. Publishing whether it was read at all
      // is what keeps "the peer dropped no log lines" apart from "the peer was never asked".
      ctx.counters().set(Counters.HARNESS_PEER_STATS_UNAVAILABLE, stats.logDropped() < 0L ? 1L : 0L);
      if (stats.logDropped() > 0L) {
        ctx.counters().set(Counters.HARNESS_PEER_LOG_DROPPED, stats.logDropped());
      }
    }
    writeArtifacts();
  }

  private static Duration remaining(final long deadlineNanos, final Duration bound) {
    final long left = deadlineNanos - System.nanoTime();
    return left <= 0L ? Duration.ZERO : Duration.ofNanos(Math.min(left, bound.toNanos()));
  }

  private Result stage(final Phase phase, final Duration bound, final String detail,
                       final Stage body) {
    boundary(phase, detail + "; bound " + bound.toSeconds() + " s");
    final Callable<Void> task = () -> {
      body.run(bound);
      return null;
    };
    final var future = stageExecutor.submit(task);
    try {
      future.get(bound.plus(GRACE).toSeconds(), TimeUnit.SECONDS);
      return Result.COMPLETE;
    } catch (final TimeoutException e) {
      future.cancel(true);
      return new Result(false, "phase " + phase + " did not finish within its bound of "
          + bound.toSeconds() + " s plus " + GRACE.toSeconds() + " s of grace");
    } catch (final ExecutionException e) {
      return new Result(false, "phase " + phase + " failed: "
          + Redaction.text(String.valueOf(e.getCause())));
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(false, "interrupted during phase " + phase);
    }
  }

  /// A phase window: closes the previous duration event, opens a new one, records the row, tells
  /// every workload, and takes a gauge sample so the series has a point on both sides of the
  /// transition.
  private void boundary(final Phase phase, final String detail) {
    final int at = index.incrementAndGet();
    phasesTsv.append(Long.toString(System.currentTimeMillis()), phase.name(), Integer.toString(at));
    if (!phase.overlay()) {
      ctx.setPhase(phase);
    }
    closeWindow();
    final var event = new SoakEvents.PhaseEvent();
    event.begin();
    event.name = phase.name();
    event.index = at;
    event.detail = detail;
    openWindow = event;
    notifyWorkloads(phase);
    if (gauges != null) {
      gauges.sampleNow();
    }
    writeArtifacts();
  }

  /// A momentary trigger inside the current window — OVERLAP is a fan-out the drivers start, not
  /// a stretch of time — so it gets a row and a callback but does not break the window chain.
  private void marker(final Phase phase, final String detail) {
    final int at = index.incrementAndGet();
    phasesTsv.append(Long.toString(System.currentTimeMillis()), phase.name(), Integer.toString(at));
    final var event = new SoakEvents.PhaseEvent();
    event.name = phase.name();
    event.index = at;
    event.detail = detail;
    event.commit();
    notifyWorkloads(phase);
  }

  private void notifyWorkloads(final Phase phase) {
    for (final var workload : workloads) {
      try {
        workload.onPhase(phase);
      } catch (final RuntimeException e) {
        System.err.println("soak: " + workload.name() + " failed on " + phase + ": "
            + Redaction.text(e.toString()));
      }
    }
  }

  private void closeWindow() {
    final var window = openWindow;
    if (window != null) {
      window.end();
      window.commit();
      openWindow = null;
    }
  }

  /// `counters.properties` and `properties.tsv` at every boundary, so a killed run still has the
  /// numbers it had reached rather than only the ones a final write would have produced.
  private void writeArtifacts() {
    final var runDir = ctx.runDir();
    publishRecoveryTotals();
    try {
      ctx.counters().writeProperties(runDir.resolve("counters.properties"));
      ctx.properties().write(runDir.resolve("properties.tsv"));
    } catch (final IOException e) {
      System.err.println("soak: could not write the phase-boundary artifacts: " + e);
    }
  }

  /// The recovery ledger owns its own records; `counters.properties` is what the runner reads,
  /// so the totals are mirrored into it at every boundary rather than only at the end.
  private void publishRecoveryTotals() {
    final var recovery = ctx.recovery();
    final var counters = ctx.counters();
    counters.set(Counters.RECOVERY_COMPLETED, recovery.completed().size());
    counters.set(Counters.RECOVERY_OVER_BUDGET, recovery.overBudget());
    counters.set(Counters.RECOVERY_SUPERSEDED, recovery.superseded());
    counters.set(Counters.RECOVERY_VACUOUS, recovery.vacuous());
    counters.set(Counters.HARNESS_RECOVERY_DROPPED, recovery.dropped());
  }

  /// Waits `nanos` on the stop latch. Returns false when the run was aborted, so every caller
  /// unwinds instead of finishing a schedule nobody is listening to.
  private boolean await(final long nanos) {
    try {
      return !stop.await(nanos, TimeUnit.NANOSECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /// The shutdown hook's path: record that the run ended outside the schedule and flush, so the
  /// artifacts say `ABORTED` rather than simply stopping mid-phase.
  void abort() {
    stop.countDown();
    phasesTsv.append(Long.toString(System.currentTimeMillis()), "ABORTED",
        Integer.toString(index.incrementAndGet()));
    phasesTsv.flushNow(Duration.ofSeconds(2));
  }

  @Override
  public void close() {
    stop.countDown();
    closeWindow();
    stageExecutor.shutdownNow();
    phasesTsv.close();
  }

  @FunctionalInterface
  private interface Stage {

    void run(Duration bound) throws Exception;
  }
}
