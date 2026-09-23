package software.sava.rpc.soak;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.UnixOperatingSystemMXBean;
import jdk.jfr.FlightRecorder;
import software.sava.rpc.soak.events.SoakEvents;

import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/// The ten-second sample, taken on two schedules and published two ways: committed as
/// `sava.soak.Gauge` into the recording on JFR's period, and appended as a row to `client.csv` on
/// the harness's.
///
/// `client.csv` is the whole-run series. It survives the ring rolling over, it survives a killed
/// JVM, and it is what the verdict's trend fits read — the recording is for shape, the CSV for
/// totals. The two therefore read the same counters through the one [#sample(boolean)] method
/// rather than each reaching for the beans itself, so a column and its event field can never
/// disagree about what a number means.
///
/// Only one of the two may *consume* state, and the CSV row is it: the heap column is a minimum
/// over the window since the previous row, so closing that window is a write. The JFR hook is a
/// read-only observer of it.
///
/// The heap column is the minimum after-collection used heap over the window, taken only from
/// collections that reclaimed the old generation (G1 mixed and full pauses, classified from the
/// whole-run JFR stream, plus the `GarbageCollectorMXBean`'s major-collection notifications as
/// the collector-agnostic fallback) and never from `System.gc()`: a soak that collects in order
/// to measure retention has changed the thing it is measuring, and after a young pause the
/// number carries the old generation's fill-up rather than what survived. The
/// fd column comes from `UnixOperatingSystemMXBean` and is -1 on a platform that does not offer
/// it, because a fabricated zero would pass the margin gate.
public final class GaugeSampler implements AutoCloseable {

  /// Workloads expose their live counts through this rather than the sampler knowing their
  /// types: the sampler must not hold a reference to a driver it would then keep alive, and a
  /// workload that is not wired in this run simply does not register.
  ///
  /// Every method returns -1 for "not available in this configuration" — notably the retained-*
  /// probes, which need the `--add-opens` into sava's package-private test seams. -1 travels to
  /// the report as "unavailable"; 0 would read as "measured and empty".
  public interface GaugeSource {

    String name();

    long liveSubscriptions();

    long pendingConfirmations();

    /// How far the harness's read of the peer's log trails the peer's clock: how long after the
    /// peer wrote the newest row read so far the harness read it, in milliseconds, or -1 for a
    /// source without a peer log. A stall shows up in the rows read once it ends.
    default long tailLagMillis() {
      return -1L;
    }

    long inFlightRequests();

    /// The subset of [#inFlightRequests()] that is *late*: an operation still outstanding past the
    /// bound its own route promises, which for a wrapped call is the client's exchange deadline
    /// plus the harness's slack and for the caller-body-handler route is the harness bound alone.
    ///
    /// The two counts answer different questions and only this one is evidence about the subject.
    /// `getProgramAccounts` carries a two-minute request timeout, so a call the pace drew in the
    /// last minutes of STEADY is still in flight when DRAIN ends *by contract* — reading
    /// `inflight_rpc` as a residue turned that into a finding about the library. Default -1 for a
    /// source that tracks no per-operation deadline, which reads as "unavailable" rather than
    /// "measured and empty".
    default long overdueRequests() {
      return -1L;
    }

    long openConnections();

    /// The largest age, in milliseconds, of any engine's `lastMessageReceivedTimestamp()`. This
    /// doubles as the W1-G sample point.
    long lastMessageAgeMillisMax();

    default long retainedRegistrations() {
      return -1L;
    }

    default long retainedTombstones() {
      return -1L;
    }

    default long retainedOrdinals() {
      return -1L;
    }

    default long retainedExceptionSubscribers() {
      return -1L;
    }

    /// The issue #52 totals, when this source owns them. Left at -1 by every other source: the
    /// sampler then takes them from [Counters], which the capture stage increments.
    default long i52Retirements() {
      return -1L;
    }

    default long i52Claims() {
      return -1L;
    }

    default long i52Misattributed() {
      return -1L;
    }
  }

  public static final String CSV_HEADER =
      "epoch_s,phase,live_subs,pending_confirm,inflight_rpc,overdue_rpc,tail_lag_ms,open_conns,delivered,rpc_completed,"
          + "mismatches,faults_observed,retirements,claims,misattributed,retained_regs,"
          + "retained_tombstones,retained_ordinals,retained_exc_subs,heap_after_gc,threads,fds,"
          + "last_msg_age_max_ms,vt_started,vt_ended,submit_failed,pinned,errors_thrown,"
          + "commonpool_par,commonpool_queued,commonpool_active";

  /// A soak whose gauge registry grows without bound would be its own retention defect; the cap
  /// is far above the handful of drivers a run has, and overflow is counted.
  public static final int MAX_SOURCES = 64;

  private final Counters counters;
  private final JfrCounters jfrCounters;
  private final Supplier<Phase> phase;
  private final CopyOnWriteArrayList<GaugeSource> sources;
  private final ScheduledExecutorService scheduler;
  private final BufferedWriter csv;
  private final Object csvLock;
  private final AtomicLong heapAfterGc;
  /// The smallest after-GC used heap among the collections since the previous row, or
  /// `Long.MAX_VALUE` when none ran. A single after-GC reading is whatever happened to be live
  /// at that instant - an 8 MiB message mid-reassembly counts - and the retention slope wants the
  /// floor, so the row carries the minimum and carries the previous floor forward when no
  /// collection happened in between.
  ///
  /// Drained by the `client.csv` path alone — see [#sample(boolean)]; every other reader peeks.
  private final AtomicLong heapAfterGcMinSinceRow;
  private final AtomicLong heapFloorRow;
  private final Set<String> heapPools;
  private final List<NotificationEmitter> gcEmitters;
  private final NotificationListener gcListener;
  private final Runnable periodicHook;
  private volatile boolean closed;

  private GaugeSampler(final Counters counters,
                       final JfrCounters jfrCounters,
                       final Supplier<Phase> phase,
                       final BufferedWriter csv) {
    this.counters = counters;
    this.jfrCounters = jfrCounters;
    this.phase = phase;
    this.csv = csv;
    this.sources = new CopyOnWriteArrayList<>();
    this.csvLock = new Object();
    this.heapAfterGc = new AtomicLong(-1L);
    this.heapAfterGcMinSinceRow = new AtomicLong(Long.MAX_VALUE);
    this.heapFloorRow = new AtomicLong(-1L);
    this.heapPools = heapPoolNames();
    this.gcEmitters = new ArrayList<>(4);
    this.gcListener = this::onGarbageCollection;
    this.periodicHook = this::commitEvent;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
      final var thread = new Thread(runnable, "soak-gauge");
      thread.setDaemon(true);
      return thread;
    });
  }

  /// Opens `client.csv`, subscribes to the GC notifications, registers the periodic JFR event
  /// and starts the sampling schedule.
  public static GaugeSampler start(final SoakConfig config,
                                   final Counters counters,
                                   final JfrCounters jfrCounters,
                                   final Supplier<Phase> phase,
                                   final Path runDir) throws IOException {
    final var file = runDir.resolve("client.csv");
    Files.createDirectories(runDir);
    Files.writeString(file, CSV_HEADER + '\n', StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    final var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    final var sampler = new GaugeSampler(counters, jfrCounters, phase, writer);
    jfrCounters.heapFloorSink(sampler::recordHeapFloor);
    for (final var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (bean instanceof final NotificationEmitter emitter) {
        emitter.addNotificationListener(sampler.gcListener, null, null);
        sampler.gcEmitters.add(emitter);
      }
    }
    FlightRecorder.addPeriodicEvent(SoakEvents.GaugeEvent.class, sampler.periodicHook);
    final long periodSeconds = config.gaugeSeconds();
    sampler.scheduler.scheduleAtFixedRate(sampler::sampleQuietly, periodSeconds, periodSeconds,
        TimeUnit.SECONDS);
    return sampler;
  }

  public void register(final GaugeSource source) {
    if (sources.size() >= MAX_SOURCES) {
      counters.increment(Counters.HARNESS_GAUGE_DROPPED);
      return;
    }
    sources.add(source);
  }

  /// Takes a sample now and writes it. Called at every phase boundary and once more at SHUTDOWN,
  /// so the series has a row on both sides of every transition rather than only wherever the
  /// fixed schedule happened to land.
  public void sampleNow() {
    final var sample = sample(true);
    writeCsv(sample);
  }

  private void sampleQuietly() {
    try {
      sampleNow();
    } catch (final RuntimeException e) {
      System.err.println("soak: the gauge sample failed: " + e);
    }
  }

  /// Takes one snapshot. `consumeHeapFloor` decides who *closes* the heap window: only the
  /// `client.csv` path passes true, because the CSV column is defined as the minimum since the
  /// previous **row** and draining it resets that window. The JFR hook passes false and reads the
  /// same value without resetting — otherwise whichever of the two schedules fired first would
  /// steal the other's collections, and the retention slope the verdict fits would lose roughly
  /// every observation the hook happened to consume.
  private Sample sample(final boolean consumeHeapFloor) {
    long liveSubs = 0L;
    long pendingConfirm = 0L;
    long inflight = 0L;
    long overdue = 0L;
    long tailLag = -1L;
    long openConns = 0L;
    long lastMessageAge = -1L;
    long retainedRegs = -1L;
    long retainedTombstones = -1L;
    long retainedOrdinals = -1L;
    long retainedExcSubs = -1L;
    long i52Retirements = -1L;
    long i52Claims = -1L;
    long i52Misattributed = -1L;
    for (final var source : sources) {
      liveSubs += Math.max(0L, source.liveSubscriptions());
      pendingConfirm += Math.max(0L, source.pendingConfirmations());
      inflight += Math.max(0L, source.inFlightRequests());
      // Summed the same way as the count it refines, so a source that does not offer it
      // contributes nothing rather than dragging the total to -1.
      overdue += Math.max(0L, source.overdueRequests());
      tailLag = Math.max(tailLag, source.tailLagMillis());
      openConns += Math.max(0L, source.openConnections());
      lastMessageAge = Math.max(lastMessageAge, source.lastMessageAgeMillisMax());
      retainedRegs = accumulate(retainedRegs, source.retainedRegistrations());
      retainedTombstones = accumulate(retainedTombstones, source.retainedTombstones());
      retainedOrdinals = accumulate(retainedOrdinals, source.retainedOrdinals());
      retainedExcSubs = accumulate(retainedExcSubs, source.retainedExceptionSubscribers());
      i52Retirements = accumulate(i52Retirements, source.i52Retirements());
      i52Claims = accumulate(i52Claims, source.i52Claims());
      i52Misattributed = accumulate(i52Misattributed, source.i52Misattributed());
    }
    // Every terminal outcome, so the column is the series form of the same total
    // `counters.properties` prints. Two used to be missing: an exchange the client's own deadline
    // cancelled (DEADLINE) and one whose body would not decode (DECODE) are settled exchanges, and
    // dropping them made the whole-run series undercount progress against the counters file — a
    // run whose faults settle mostly by deadline reads as though it had stopped issuing work.
    final long rpcCompleted = counters.get(Counters.RPC_COMPLETED_OK)
        + counters.get(Counters.RPC_COMPLETED_RPC_ERROR)
        + counters.get(Counters.RPC_COMPLETED_HTTP_ERROR)
        + counters.get(Counters.RPC_COMPLETED_TIMEOUT)
        + counters.get(Counters.RPC_COMPLETED_CANCELLED)
        + counters.get(Counters.RPC_COMPLETED_DEADLINE)
        + counters.get(Counters.RPC_COMPLETED_DECODE)
        + counters.get(Counters.RPC_COMPLETED_TRANSPORT)
        + counters.get(Counters.RPC_COMPLETED_MISMATCH);
    final var pool = ForkJoinPool.commonPool();
    final long sinceRow = consumeHeapFloor
        ? heapAfterGcMinSinceRow.getAndSet(Long.MAX_VALUE)
        : heapAfterGcMinSinceRow.get();
    if (consumeHeapFloor && sinceRow != Long.MAX_VALUE) {
      heapFloorRow.set(sinceRow);
    }
    final long carriedFloor = heapFloorRow.get();
    final long heap;
    if (sinceRow != Long.MAX_VALUE) {
      heap = sinceRow;
    } else if (carriedFloor >= 0L) {
      heap = carriedFloor;
    } else {
      heap = jfrCounters.heapAfterGcBytes();
    }
    return new Sample(
        System.currentTimeMillis() / 1_000L,
        phase.get().name(),
        liveSubs,
        pendingConfirm,
        inflight,
        overdue,
        tailLag,
        openConns,
        counters.get(Counters.WS_NOTIFICATIONS_DELIVERED),
        rpcCompleted,
        counters.get(Counters.RPC_COMPLETED_MISMATCH),
        counters.get(Counters.FAULTS_OBSERVED),
        i52Retirements >= 0L ? i52Retirements : counters.get(Counters.I52_RETIREMENTS),
        i52Claims >= 0L ? i52Claims : counters.get(Counters.I52_CLAIMS),
        i52Misattributed >= 0L ? i52Misattributed : counters.get(Counters.I52_MISATTRIBUTED),
        retainedRegs,
        retainedTombstones,
        retainedOrdinals,
        retainedExcSubs,
        heap,
        ManagementFactory.getThreadMXBean().getThreadCount(),
        openFileDescriptors(),
        lastMessageAge,
        jfrCounters.vtStarted(),
        jfrCounters.vtEnded(),
        jfrCounters.submitFailed(),
        jfrCounters.pinned(),
        jfrCounters.errorsThrown(),
        pool.getParallelism(),
        pool.getQueuedSubmissionCount(),
        pool.getActiveThreadCount());
  }

  private static long accumulate(final long running, final long value) {
    if (value < 0L) {
      return running;
    }
    return running < 0L ? value : running + value;
  }

  private void writeCsv(final Sample sample) {
    if (closed) {
      return;
    }
    final var row = new StringBuilder(256);
    row.append(sample.epochSeconds).append(',')
        .append(sample.phase).append(',')
        .append(sample.liveSubscriptions).append(',')
        .append(sample.pendingConfirmations).append(',')
        .append(sample.inFlightRequests).append(',')
        .append(sample.overdueRequests).append(',')
        .append(sample.tailLagMillis).append(',')
        .append(sample.openConnections).append(',')
        .append(sample.delivered).append(',')
        .append(sample.rpcCompleted).append(',')
        .append(sample.mismatches).append(',')
        .append(sample.faultsObserved).append(',')
        .append(sample.retirements).append(',')
        .append(sample.claims).append(',')
        .append(sample.misattributed).append(',')
        .append(sample.retainedRegistrations).append(',')
        .append(sample.retainedTombstones).append(',')
        .append(sample.retainedOrdinals).append(',')
        .append(sample.retainedExceptionSubscribers).append(',')
        .append(sample.heapAfterGc).append(',')
        .append(sample.threads).append(',')
        .append(sample.fds).append(',')
        .append(sample.lastMessageAgeMillisMax).append(',')
        .append(sample.vtStarted).append(',')
        .append(sample.vtEnded).append(',')
        .append(sample.submitFailed).append(',')
        .append(sample.pinned).append(',')
        .append(sample.errorsThrown).append(',')
        .append(sample.commonPoolParallelism).append(',')
        .append(sample.commonPoolQueued).append(',')
        .append(sample.commonPoolActive).append('\n');
    synchronized (csvLock) {
      try {
        csv.write(row.toString());
        csv.flush();
      } catch (final IOException e) {
        System.err.println("soak: client.csv write failed: " + e);
      }
    }
  }

  /// The periodic JFR hook. It takes its own snapshot on JFR's schedule so the event carries
  /// JFR's timestamps; the CSV row on the harness's schedule carries wall-clock seconds. It is a
  /// read-only observer of the heap window — the CSV row owns that window's close — and it takes
  /// no snapshot at all when the event is disabled, so a switched-off gauge costs the run nothing.
  private void commitEvent() {
    if (closed) {
      return;
    }
    final var event = new SoakEvents.GaugeEvent();
    if (!event.shouldCommit()) {
      return;
    }
    final var sample = sample(false);
    event.phase = sample.phase;
    event.liveSubscriptions = sample.liveSubscriptions;
    event.pendingConfirmations = sample.pendingConfirmations;
    event.inFlightRequests = sample.inFlightRequests;
    event.overdueRequests = sample.overdueRequests;
    event.openConnections = sample.openConnections;
    event.deliveredTotal = sample.delivered;
    event.rpcCompletedTotal = sample.rpcCompleted;
    event.mismatchTotal = sample.mismatches;
    event.faultObservedTotal = sample.faultsObserved;
    event.retirementsTotal = sample.retirements;
    event.claimsTotal = sample.claims;
    event.misattributedTotal = sample.misattributed;
    event.retainedRegistrations = sample.retainedRegistrations;
    event.retainedTombstones = sample.retainedTombstones;
    event.retainedOrdinals = sample.retainedOrdinals;
    event.retainedExceptionSubscribers = sample.retainedExceptionSubscribers;
    event.heapAfterLastGc = sample.heapAfterGc;
    event.liveThreads = sample.threads;
    event.openFds = sample.fds;
    event.lastMessageAgeMillisMax = sample.lastMessageAgeMillisMax;
    event.vtStarted = sample.vtStarted;
    event.vtEnded = sample.vtEnded;
    event.submitFailed = sample.submitFailed;
    event.pinned = sample.pinned;
    event.errorsThrown = sample.errorsThrown;
    event.commonPoolParallelism = sample.commonPoolParallelism;
    event.commonPoolQueued = sample.commonPoolQueued;
    event.commonPoolActive = sample.commonPoolActive;
    event.commit();
  }

  private void onGarbageCollection(final javax.management.Notification notification, final Object handback) {
    if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
      return;
    }
    final var userData = notification.getUserData();
    if (!(userData instanceof final CompositeData composite)) {
      return;
    }
    final var info = GarbageCollectionNotificationInfo.from(composite);
    if (!"end of major GC".equals(info.getGcAction())) {
      // A minor collection's after-heap is not a floor (see the class doc); G1's mixed pauses
      // are reported here as minor too, which is why the JFR stream classifies them instead.
      return;
    }
    long used = 0L;
    for (final var entry : info.getGcInfo().getMemoryUsageAfterGc().entrySet()) {
      if (heapPools.contains(entry.getKey())) {
        used += entry.getValue().getUsed();
      }
    }
    recordHeapFloor(0L, used);
  }

  /// One qualifying after-collection reading, from either source; the row takes the minimum.
  private void recordHeapFloor(final long epochSecond, final long used) {
    heapAfterGc.set(used);
    heapAfterGcMinSinceRow.accumulateAndGet(used, Math::min);
  }

  private static Set<String> heapPoolNames() {
    final var names = new HashSet<String>(16);
    for (final var pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP) {
        names.add(pool.getName());
      }
    }
    return Set.copyOf(names);
  }

  private static long openFileDescriptors() {
    final var os = ManagementFactory.getOperatingSystemMXBean();
    return os instanceof final UnixOperatingSystemMXBean unix ? unix.getOpenFileDescriptorCount() : -1L;
  }

  @Override
  public void close() {
    closed = true;
    FlightRecorder.removePeriodicEvent(periodicHook);
    for (final var emitter : gcEmitters) {
      try {
        emitter.removeNotificationListener(gcListener);
      } catch (final javax.management.ListenerNotFoundException e) {
        // the listener was never installed on this bean; nothing to undo
      }
    }
    scheduler.shutdownNow();
    synchronized (csvLock) {
      try {
        csv.flush();
        csv.close();
      } catch (final IOException e) {
        System.err.println("soak: client.csv close failed: " + e);
      }
    }
  }

  private record Sample(long epochSeconds,
                        String phase,
                        long liveSubscriptions,
                        long pendingConfirmations,
                        long inFlightRequests,
                        long overdueRequests,
                        long tailLagMillis,
                        long openConnections,
                        long delivered,
                        long rpcCompleted,
                        long mismatches,
                        long faultsObserved,
                        long retirements,
                        long claims,
                        long misattributed,
                        long retainedRegistrations,
                        long retainedTombstones,
                        long retainedOrdinals,
                        long retainedExceptionSubscribers,
                        long heapAfterGc,
                        long threads,
                        long fds,
                        long lastMessageAgeMillisMax,
                        long vtStarted,
                        long vtEnded,
                        long submitFailed,
                        long pinned,
                        long errorsThrown,
                        int commonPoolParallelism,
                        long commonPoolQueued,
                        int commonPoolActive) {
  }
}
