package software.sava.rpc.soak;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/// The harness's own whole-run totals: one [LongAdder] per counter and one [LatencyHistogram]
/// per measured operation, written to `counters.properties` at every phase boundary and by the
/// shutdown hook.
///
/// Two rules the verdict depends on. First, the fixed names below are pre-registered, so a
/// counter that never fired is written as `name=0` rather than being absent — "never happened"
/// and "never measured" must not look the same to the runner. Second, both maps are bounded:
/// a name that is not pre-registered may still be created (histograms are per-method and per
/// consumer kind), but past [#MAX_NAMES] the creation is refused and counted in
/// `harness.counters.dropped`, because an unbounded map inside the process whose retention is
/// under test would be a leak the harness itself introduced.
public final class Counters {

  public static final int MAX_NAMES = 512;

  public static final String WS_NOTIFICATIONS_DELIVERED = "ws.notifications.delivered";
  public static final String WS_SEQUENCE_OK = "ws.notifications.sequence.ok";
  public static final String WS_SEQUENCE_GAP = "ws.notifications.sequence.gap";
  public static final String WS_SEQUENCE_DUP = "ws.notifications.sequence.dup";
  public static final String WS_SEQUENCE_REORDER = "ws.notifications.sequence.reorder";
  public static final String WS_NOTIFICATIONS_ZOMBIE = "ws.notifications.zombie";
  public static final String WS_NOTIFICATIONS_UNKNOWN_SUB = "ws.notifications.unknownSub";
  public static final String WS_CONSUMER_THREW = "ws.consumer.threw";
  public static final String WS_CONSUMER_THREW_UNEXPECTED = "ws.consumer.threw.unexpected";
  public static final String WS_SUBSCRIBE_REQUESTED = "ws.subscribe.requested";
  public static final String WS_SUBSCRIBE_CONFIRMED = "ws.subscribe.confirmed";
  public static final String WS_SUBSCRIBE_REPLAYED = "ws.subscribe.replayed";
  public static final String WS_SUBSCRIBE_REFUSED = "ws.subscribe.refused";
  public static final String WS_UNSUBSCRIBE_REQUESTED = "ws.unsubscribe.requested";
  public static final String WS_UNSUBSCRIBE_ACKED = "ws.unsubscribe.acked";
  public static final String WS_UNSUBSCRIBE_REFUSED = "ws.unsubscribe.refused";
  public static final String WS_EPOCH_OPENED = "ws.epoch.opened";
  public static final String WS_EPOCH_RETIRED = "ws.epoch.retired";
  public static final String WS_RETIREMENT_EXPECTED = "ws.retirement.expected";
  public static final String WS_RETIREMENT_UNEXPLAINED = "ws.retirement.unexplained";
  public static final String WS_EXCEPTION_SUBSCRIBE_RECEIVED = "ws.exceptionSubscribe.received";
  public static final String WS_CLOSE_TERMINAL = "ws.close.terminal";

  public static final String RPC_COMPLETED_OK = "rpc.completed.ok";
  public static final String RPC_COMPLETED_RPC_ERROR = "rpc.completed.rpcError";
  public static final String RPC_COMPLETED_HTTP_ERROR = "rpc.completed.httpError";
  public static final String RPC_COMPLETED_TIMEOUT = "rpc.completed.timeout";
  public static final String RPC_COMPLETED_CANCELLED = "rpc.completed.cancelled";
  /// Settled by the client's own exchange deadline (`2 x requestTimeout`, `JsonHttpClient`
  /// `withResponseDeadline`), which surfaces as a cancellation the harness did not request.
  public static final String RPC_COMPLETED_DEADLINE = "rpc.completed.deadline";
  /// The body arrived and the client could not turn it into a value: a parse, inflate or
  /// truncation failure. Kept apart from [#RPC_COMPLETED_TRANSPORT], which is a socket that died,
  /// because a decoding regression and a peer that dropped the connection are different defects
  /// and sharing one counter made them indistinguishable in `counters.properties`.
  public static final String RPC_COMPLETED_DECODE = "rpc.completed.decode";
  public static final String RPC_COMPLETED_TRANSPORT = "rpc.completed.transport";
  public static final String RPC_COMPLETED_MISMATCH = "rpc.completed.mismatch";
  public static final String RPC_TEST_RESPONSE_INVOKED = "rpc.testResponse.invoked";
  public static final String RPC_BYTES_RECEIVED = "rpc.bytes.received";
  public static final String RPC_DEADLINE_HONOURED = "rpc.deadline.honoured";
  public static final String RPC_DEADLINE_MISSED = "rpc.deadline.missed";
  public static final String RPC_NOWRAP_COMPLETED = "rpc.nowrap.completed";
  /// Operations the harness issued outside the paced mix: W4-C's short-deadline fan-out and W3-D's
  /// wedge probes. They draw no pace token, so they belong in neither side of the starvation
  /// ratio; counted apart, they also separate "the property was never exercised" from "its probes
  /// were sent and none of them settled". Pre-registered, so a run that launched no probe says so
  /// with a `0` rather than by the key's absence.
  public static final String RPC_PROBE_SENT = "rpc.probe.sent";
  /// Futures the harness cancelled at SHUTDOWN because the exchange was still open. A peer that
  /// holds a body for five minutes outlives the run otherwise: the per-operation watchdog records
  /// `TIMEOUT` and deliberately leaves the future alone, so something has to release the exchange
  /// once no property can still be observed. Cancelled with the harness's own flag set, so the
  /// settlement is filed as harness-cancelled rather than as the client's deadline.
  public static final String RPC_SHUTDOWN_CANCELLED = "rpc.shutdown.cancelled";
  /// Operations the HTTP driver could not add to its in-flight registry because the registry was
  /// at its cap. They are still issued and still counted everywhere else; what is lost is their
  /// contribution to `overdue_rpc` and their shutdown cancellation, so the total has to be
  /// visible rather than leaving those two quietly short.
  public static final String HTTP_INFLIGHT_UNTRACKED = "harness.http.inflightUntracked";
  public static final String RPC_GZIP_OK = "rpc.gzip.ok";
  public static final String RPC_GZIP_BAD_CLEAN = "rpc.gzip.badGzipClean";
  public static final String RPC_GZIP_TRUNCATED_CLEAN = "rpc.gzip.truncatedClean";
  /// Gzip-client answers the peer's rotation served with no encoding: whole, and evidence of
  /// nothing about decoding, so they earn no W3-C pass.
  public static final String RPC_GZIP_IDENTITY_SERVED = "rpc.gzip.identityServed";
  /// W3-C twins whose gzip half was one of those; no verdict either way.
  public static final String HTTP_TWIN_IDENTITY_SERVED = "http.twin.identityServed";

  public static final String CHURN_ENGINE_CYCLES = "churn.engine.cycles";
  public static final String CHURN_CLIENT_CYCLES = "churn.client.cycles";

  public static final String FAULTS_OBSERVED = "faults.observed";
  public static final String RECOVERY_OPENED = "recovery.opened";
  public static final String RECOVERY_COMPLETED = "recovery.completed";
  public static final String RECOVERY_OVER_BUDGET = "recovery.overBudget";
  /// Windows a later connection-level fault displaced inside their budget: not evaluated.
  public static final String RECOVERY_SUPERSEDED = "recovery.superseded";
  /// Windows opened with no live registration: nothing owed, not evaluated.
  public static final String RECOVERY_VACUOUS = "recovery.vacuous";

  /// Cross-driver progress: every operation any workload attempted. It answers "did the run do
  /// work", and nothing else — it is the wrong denominator for the HTTP starvation ratio, whose
  /// numerator ([#HARNESS_OPS_SKIPPED_INFLIGHT]) is HTTP-only. Use [#HTTP_OPS_ATTEMPTED] there.
  public static final String HARNESS_OPS_ATTEMPTED = "harness.opsAttempted";
  /// The HTTP driver's own attempts, counted where a paced draw is admitted. This is the
  /// denominator the starvation ratio needs: dividing an HTTP-only skip count by the cross-driver
  /// total diluted it by every websocket plan step and could not trip.
  public static final String HTTP_OPS_ATTEMPTED = "http.ops.attempted";
  public static final String HARNESS_OPS_SKIPPED_INFLIGHT = "harness.opsSkipped.inflightCap";
  /// Reserved for a driver that cannot issue an operation inside its own pace window. The HTTP
  /// driver's token bucket no longer refuses slots (a reserved slot is waited for, never burnt),
  /// so no driver increments this today; the key stays in `counters.properties` because the
  /// runner's starvation gate reads it.
  public static final String HARNESS_OPS_SKIPPED_DEADLINE = "harness.opsSkipped.deadline";
  public static final String HARNESS_PEER_LOG_DROPPED = "harness.peerLog.dropped";
  /// `1` when a peer exists but its `/__soak/stats` `logDropped` total could not be read at
  /// SHUTDOWN, `0` when it was read. [#HARNESS_PEER_LOG_DROPPED] alone cannot tell "the peer
  /// reported no dropped log lines" from "the peer was never asked", and a record whose
  /// completeness is unknown must not certify as complete.
  public static final String HARNESS_PEER_STATS_UNAVAILABLE = "harness.peerStats.unavailable";
  public static final String HARNESS_TSV_DROPPED = "harness.tsv.dropped";
  public static final String HARNESS_COUNTERS_DROPPED = "harness.counters.dropped";
  public static final String HARNESS_GAUGE_DROPPED = "harness.gauge.dropped";
  public static final String HARNESS_RECOVERY_DROPPED = "harness.recovery.dropped";
  public static final String HARNESS_HEAP_SERIES_DROPPED = "harness.heapSeries.dropped";

  public static final String I52_RETIREMENTS = "i52.retirements";
  public static final String I52_CLAIMS = "i52.claims";
  public static final String I52_DOUBLE_CLAIMS = "i52.doubleClaims";
  public static final String I52_MISATTRIBUTED = "i52.misattributed";
  public static final String I52_DOUBLE_CLAIMS_UNEXPLAINED = "i52.doubleClaimsUnexplained";
  public static final String I52_OPPORTUNITIES = "i52.opportunities";
  public static final String I52_CLAIMS_UNROUTED = "i52.claimsUnrouted";
  public static final String I52_ORIGIN_UNKNOWN = "i52.originUnknown";
  public static final String I52_RETIREMENTS_INSIDE_ADOPT = "i52.retirementsInsideAdopt";
  public static final String I52_FUTURE_SETTLED_BY_RETIREMENT = "i52.futureSettledByRetirement";

  public static final String JFR_SUBMIT_FAILED = "jfr.submitFailed";
  public static final String JFR_PINNED = "jfr.pinned";
  public static final String JFR_ERRORS_THROWN = "jfr.errorsThrown";
  /// `jdk.JavaErrorThrow` events raised inside `java.lang.invoke`'s own linkage probing (a
  /// `NoSuchMethodError` from `MemberName$Factory.resolve` that `resolveOrNull` catches while
  /// looking for a pregenerated LambdaForm holder). The JDK throws dozens of these at start-up
  /// and at every new method-handle shape; they never escape and say nothing about the subject,
  /// so they are counted apart from the errors the verdict gates on rather than hidden.
  public static final String JFR_ERRORS_THROWN_LINKAGE_PROBE = "jfr.errorsThrown.linkageProbe";
  public static final String JFR_VT_STARTED = "jfr.vtStarted";
  public static final String JFR_VT_ENDED = "jfr.vtEnded";
  public static final String JFR_THREAD_STARTED = "jfr.threadStarted";
  public static final String JFR_THREAD_ENDED = "jfr.threadEnded";
  /// Every collection pause the whole-run stream saw, and the subset whose after-GC heap is a floor
  /// sample (old generation reclaimed). A window with pauses but no floor samples is why a heap
  /// slope reads `n/a`.
  public static final String JFR_GC_PAUSES = "jfr.gc.pauses";
  public static final String JFR_GC_FLOOR_SAMPLES = "jfr.gc.floorSamples";

  public static final String HIST_WS_REPLAY_LATENCY = "ws.replay.latency";
  public static final String HIST_WS_RECONNECT_LATENCY = "ws.reconnect.latency";
  /// How long after the peer wrote a row the websocket tail read it: every 256th row, plus every
  /// row read more than a second late.
  public static final String HIST_WS_TAIL_LAG = "ws.tail.lag";
  public static final String HIST_HTTP_DEADLINE_LATE = "http.deadlineLate";
  /// The W4-C sentinel timer's own lateness (a bare scheduled task beside each probe).
  public static final String HIST_HTTP_W4C_SENTINEL_LATE = "http.w4cSentinelLate";
  /// W4-C probes whose deadline was late while the sentinel beside them was late too.
  public static final String HTTP_W4C_POOL_STARVED = "http.w4c.poolStarved";
  public static final String HIST_I52_GAP = "i52.gap";
  public static final String HIST_I52_RETRY_MARGIN = "i52.retryMargin";

  /// `rpc.latency.<method>` and its injected-delay-subtracted twin, so a latency number can be
  /// quoted either as the caller saw it or as the client's own cost.
  public static String rpcLatency(final String method) {
    return "rpc.latency." + method;
  }

  public static String rpcLatencyNetOfInjectedDelay(final String method) {
    return "rpc.latency." + method + ".netOfInjectedDelay";
  }

  public static String wsDelivery(final String consumerKind) {
    return "ws.delivery." + consumerKind;
  }

  private static final List<String> FIXED_COUNTERS = List.of(
      WS_NOTIFICATIONS_DELIVERED, WS_SEQUENCE_OK, WS_SEQUENCE_GAP, WS_SEQUENCE_DUP, WS_SEQUENCE_REORDER,
      WS_NOTIFICATIONS_ZOMBIE, WS_NOTIFICATIONS_UNKNOWN_SUB, WS_CONSUMER_THREW, WS_CONSUMER_THREW_UNEXPECTED,
      WS_SUBSCRIBE_REQUESTED, WS_SUBSCRIBE_CONFIRMED, WS_SUBSCRIBE_REPLAYED, WS_SUBSCRIBE_REFUSED,
      WS_UNSUBSCRIBE_REQUESTED, WS_UNSUBSCRIBE_ACKED, WS_UNSUBSCRIBE_REFUSED,
      WS_EPOCH_OPENED, WS_EPOCH_RETIRED, WS_RETIREMENT_EXPECTED, WS_RETIREMENT_UNEXPLAINED,
      WS_EXCEPTION_SUBSCRIBE_RECEIVED, WS_CLOSE_TERMINAL,
      // Names the websocket driver owns (declared beside their writers in ws.WsWorkload) but
      // whose zero must print: an absent row reads as "not measured" and each of these is a
      // gate input or the explanation of one.
      "ws.retirement.peerOverflow", "containment.excusedByCause",
      "ws.subscribe.requestDefectRetired", "ws.subscribe.requestDefectObserved",
      "ws.harness.retirementCallbacks", "ws.harness.retirementWithoutOrdinal",
      "ws.harness.bareConnectFailed", "ws.harness.recoveryAnchoredAtAdoption",
      "ws.harness.closeWithoutLiveConnection", "ws.harness.deliveryToTombstoned",
      "ws.harness.stalledConsumers", "ws.retirement.harnessStalled", "ws.harness.w1gConnectionGone",
      "ws.signature.terminated", "ws.signature.terminalObserved",
      "ws.retirement.overflowCap", "ws.retirement.rejectEscalated", "ws.harness.w1hContactAfterSwallow",
      "ws.harness.w1hProbeRetirements", "ws.harness.w1hOtherRetirements",
      "ws.harness.recoveryHeldPastCasualty", "ws.harness.recoveryPendingOvertaken",
      "ws.harness.judgedUnderTailLag", "ws.harness.replayRowsLate", "ws.harness.liveGenericSkipped",
      "ws.plan.duplicateOffered", "ws.plan.duplicateAccepted", "ws.plan.duplicateThrew",
      RPC_COMPLETED_OK, RPC_COMPLETED_RPC_ERROR, RPC_COMPLETED_HTTP_ERROR, RPC_COMPLETED_TIMEOUT,
      RPC_COMPLETED_CANCELLED, RPC_COMPLETED_DEADLINE, RPC_COMPLETED_DECODE, RPC_COMPLETED_TRANSPORT,
      RPC_COMPLETED_MISMATCH,
      RPC_TEST_RESPONSE_INVOKED, RPC_BYTES_RECEIVED, RPC_DEADLINE_HONOURED, RPC_DEADLINE_MISSED,
      RPC_NOWRAP_COMPLETED, RPC_PROBE_SENT, RPC_SHUTDOWN_CANCELLED, HTTP_INFLIGHT_UNTRACKED, HTTP_W4C_POOL_STARVED,
      RPC_GZIP_OK, RPC_GZIP_BAD_CLEAN, RPC_GZIP_TRUNCATED_CLEAN, RPC_GZIP_IDENTITY_SERVED,
      HTTP_TWIN_IDENTITY_SERVED,
      CHURN_ENGINE_CYCLES, CHURN_CLIENT_CYCLES,
      FAULTS_OBSERVED, RECOVERY_OPENED, RECOVERY_COMPLETED, RECOVERY_OVER_BUDGET,
      RECOVERY_SUPERSEDED, RECOVERY_VACUOUS,
      HARNESS_OPS_ATTEMPTED, HTTP_OPS_ATTEMPTED, HARNESS_OPS_SKIPPED_INFLIGHT, HARNESS_OPS_SKIPPED_DEADLINE,
      HARNESS_PEER_LOG_DROPPED, HARNESS_PEER_STATS_UNAVAILABLE,
      HARNESS_TSV_DROPPED, HARNESS_COUNTERS_DROPPED, HARNESS_GAUGE_DROPPED,
      HARNESS_RECOVERY_DROPPED, HARNESS_HEAP_SERIES_DROPPED,
      I52_RETIREMENTS, I52_CLAIMS, I52_DOUBLE_CLAIMS, I52_MISATTRIBUTED, I52_DOUBLE_CLAIMS_UNEXPLAINED,
      I52_OPPORTUNITIES, I52_CLAIMS_UNROUTED, I52_ORIGIN_UNKNOWN, I52_RETIREMENTS_INSIDE_ADOPT,
      I52_FUTURE_SETTLED_BY_RETIREMENT,
      JFR_SUBMIT_FAILED, JFR_PINNED, JFR_ERRORS_THROWN, JFR_ERRORS_THROWN_LINKAGE_PROBE,
      JFR_VT_STARTED, JFR_VT_ENDED, JFR_THREAD_STARTED, JFR_THREAD_ENDED, JFR_GC_PAUSES, JFR_GC_FLOOR_SAMPLES);

  private static final List<String> FIXED_HISTOGRAMS = List.of(
      HIST_WS_REPLAY_LATENCY, HIST_WS_RECONNECT_LATENCY, HIST_HTTP_DEADLINE_LATE, HIST_HTTP_W4C_SENTINEL_LATE,
      HIST_I52_GAP, HIST_I52_RETRY_MARGIN, HIST_WS_TAIL_LAG);

  private final ConcurrentHashMap<String, LongAdder> counters;
  private final ConcurrentHashMap<String, LatencyHistogram> histograms;
  private final LongAdder dropped;

  public Counters() {
    this.counters = new ConcurrentHashMap<>(256);
    this.histograms = new ConcurrentHashMap<>(64);
    this.dropped = new LongAdder();
    for (final var name : FIXED_COUNTERS) {
      counters.put(name, new LongAdder());
    }
    for (final var name : FIXED_HISTOGRAMS) {
      histograms.put(name, new LatencyHistogram());
    }
  }

  public void increment(final String name) {
    add(name, 1L);
  }

  public void add(final String name, final long delta) {
    final var adder = counter(name);
    if (adder != null) {
      adder.add(delta);
    }
  }

  /// Sets an absolute value. Only for mirroring a total that another structure owns — the
  /// recovery ledger, the peer's own stats — where one publisher writes and nobody increments.
  /// A counter that is both set and incremented would race, and the loser would be the number in
  /// the verdict.
  public void set(final String name, final long value) {
    final var adder = counter(name);
    if (adder != null) {
      adder.add(value - adder.sum());
    }
  }

  public long get(final String name) {
    final var adder = counters.get(name);
    return adder == null ? 0L : adder.sum();
  }

  /// Records one latency sample against a named histogram, creating it on first use so that
  /// per-method and per-consumer-kind names need no registry.
  public void record(final String histogram, final long nanos) {
    final var target = histogram(histogram);
    if (target != null) {
      target.record(nanos);
    }
  }

  public LatencyHistogram histogram(final String name) {
    final var existing = histograms.get(name);
    if (existing != null) {
      return existing;
    }
    if (counters.size() + histograms.size() >= MAX_NAMES) {
      dropped.increment();
      counters.get(HARNESS_COUNTERS_DROPPED).increment();
      return null;
    }
    return histograms.computeIfAbsent(name, key -> new LatencyHistogram());
  }

  private LongAdder counter(final String name) {
    final var existing = counters.get(name);
    if (existing != null) {
      return existing;
    }
    if (counters.size() + histograms.size() >= MAX_NAMES) {
      dropped.increment();
      counters.get(HARNESS_COUNTERS_DROPPED).increment();
      return null;
    }
    return counters.computeIfAbsent(name, key -> new LongAdder());
  }

  /// Names refused because the map was full. Non-zero makes the run's denominators incomplete
  /// and is reported, never silently absorbed.
  public long droppedNames() {
    return dropped.sum();
  }

  public Map<String, Long> snapshot() {
    final var snapshot = new TreeMap<String, Long>();
    counters.forEach((name, adder) -> snapshot.put(name, adder.sum()));
    return snapshot;
  }

  /// `name=value` for every counter and `hist.<name>.{count,p50,p99,max}` for every histogram,
  /// sorted, written whole each time. `soak.sh` reads this file and `jfr-metrics.properties`
  /// for the verdict, and nothing else.
  public void writeProperties(final Path file) throws IOException {
    final var out = new StringBuilder(8192);
    final var sorted = new TreeMap<String, LongAdder>(counters);
    for (final var entry : sorted.entrySet()) {
      out.append(entry.getKey()).append('=').append(entry.getValue().sum()).append('\n');
    }
    final var sortedHistograms = new TreeMap<String, LatencyHistogram>(histograms);
    for (final var entry : sortedHistograms.entrySet()) {
      final var histogram = entry.getValue();
      final var prefix = "hist." + entry.getKey();
      out.append(prefix).append(".count=").append(histogram.count()).append('\n');
      // a percentile is a bucket's upper edge and the max is exact, so a percentile is clamped
      // to the max: p50 3047 ms next to max 3020 ms was measured and reads as a contradiction
      final double max = histogram.maxMillis();
      out.append(prefix).append(".p50=").append(millis(Math.min(histogram.percentileMillis(0.50d), max))).append('\n');
      out.append(prefix).append(".p99=").append(millis(Math.min(histogram.percentileMillis(0.99d), max))).append('\n');
      out.append(prefix).append(".max=").append(millis(max)).append('\n');
    }
    final var parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(file, out, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
  }

  private static String millis(final double value) {
    return String.format(Locale.ROOT, "%.3f", value);
  }
}
