package software.sava.rpc.soak;

import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/// The JDK events counted over the whole run, independently of the disk ring the `soak`
/// recording is kept to.
///
/// The ring holds its last hours; an eight-hour campaign's recording cannot answer "did the
/// scheduler ever refuse a continuation?" for the morning. A [RecordingStream] can: it is a
/// second recording in the same JVM whose chunks are released as soon as it has consumed them,
/// so it neither pins the ring's chunks on disk nor retains the events, and event settings are
/// the union over running recordings, so enabling these at the ring's own settings changes
/// nothing about what the ring records.
///
/// Two lifecycle facts are designed around rather than worked around. The consumer thread is not
/// a daemon (`AbstractEventStream` starts it with `daemon=false` and [RecordingStream] offers no
/// switch), so [#start] runs only after the peers are reachable and the first engine has
/// connected — a JVM whose start-up failed must still exit on its own. And [#close(Duration)] is
/// explicit in the shutdown sequence, bounded, with `Runtime.halt` as the backstop: a soak that
/// hangs at exit loses the dump it spent eight hours producing.
public final class JfrCounters implements AutoCloseable {

  public static final Duration PINNED_THRESHOLD = Duration.ofMillis(1);
  public static final Duration CLOSE_BOUND = Duration.ofSeconds(10);

  /// The after-GC heap floor is bucketed at 30 s, which is the resolution the retention slope is
  /// fitted at; the cap bounds an eight-hour campaign's series at roughly four times what it
  /// needs, and overflow is counted rather than dropped in silence.
  public static final int HEAP_BUCKET_SECONDS = 30;
  public static final int MAX_HEAP_BUCKETS = 4096;
  private static final int MAX_PENDING_GCS = 64;

  private final Counters counters;
  private final LongAdder submitFailed;
  private final LongAdder pinned;
  private final LongAdder errorsThrown;
  private final LongAdder linkageProbes;
  private final LongAdder vtStarted;
  private final LongAdder vtEnded;
  private final LongAdder threadStarted;
  private final LongAdder threadEnded;
  private final AtomicLong pinnedMaxMillis;
  private final AtomicLong heapAfterGc;
  private final CopyOnWriteArrayList<long[]> heapFloorSeries;
  /// Collections whose after-GC reading is still waiting for its classification or its summary:
  /// the three events that describe one pause share a `gcId` but arrive in no fixed order.
  private final Map<Long, PendingGc> pendingGcs;
  private final LongAdder gcPauses;
  private final LongAdder floorSamples;
  private volatile HeapFloorSink heapFloorSink;
  private final Object heapLock;
  private RecordingStream stream;
  private long currentBucket;
  private long currentBucketMin;

  private JfrCounters(final Counters counters) {
    this.counters = counters;
    this.submitFailed = new LongAdder();
    this.pinned = new LongAdder();
    this.errorsThrown = new LongAdder();
    this.linkageProbes = new LongAdder();
    this.vtStarted = new LongAdder();
    this.vtEnded = new LongAdder();
    this.threadStarted = new LongAdder();
    this.threadEnded = new LongAdder();
    this.pinnedMaxMillis = new AtomicLong();
    this.heapAfterGc = new AtomicLong(-1L);
    this.heapFloorSeries = new CopyOnWriteArrayList<>();
    this.pendingGcs = new ConcurrentHashMap<>();
    this.gcPauses = new LongAdder();
    this.floorSamples = new LongAdder();
    this.heapLock = new Object();
    this.currentBucket = -1L;
    this.currentBucketMin = Long.MAX_VALUE;
  }

  /// Starts the stream. Called after `READY`, never before: see the class note on the
  /// non-daemon consumer thread.
  public static JfrCounters start(final Counters counters) {
    final var jfrCounters = new JfrCounters(counters);
    final var stream = new RecordingStream();
    stream.enable("jdk.VirtualThreadSubmitFailed").withStackTrace();
    stream.enable("jdk.VirtualThreadPinned").withThreshold(PINNED_THRESHOLD).withStackTrace();
    stream.enable("jdk.JavaErrorThrow").withStackTrace();
    stream.enable("jdk.VirtualThreadStart").withoutStackTrace();
    stream.enable("jdk.VirtualThreadEnd").withoutStackTrace();
    stream.enable("jdk.ThreadStart").withoutStackTrace();
    stream.enable("jdk.ThreadEnd").withoutStackTrace();
    stream.enable("jdk.GCHeapSummary").withoutStackTrace();
    stream.enable("jdk.GarbageCollection").withoutStackTrace();
    stream.enable("jdk.G1GarbageCollection").withoutStackTrace();
    stream.onEvent("jdk.VirtualThreadSubmitFailed", event -> jfrCounters.count(
        jfrCounters.submitFailed, Counters.JFR_SUBMIT_FAILED));
    stream.onEvent("jdk.VirtualThreadPinned", event -> {
      jfrCounters.count(jfrCounters.pinned, Counters.JFR_PINNED);
      final long millis = event.getDuration().toMillis();
      jfrCounters.pinnedMaxMillis.accumulateAndGet(millis, Math::max);
    });
    stream.onEvent("jdk.JavaErrorThrow", event -> {
      if (linkageProbe(event.getStackTrace())) {
        jfrCounters.count(jfrCounters.linkageProbes, Counters.JFR_ERRORS_THROWN_LINKAGE_PROBE);
      } else {
        jfrCounters.count(jfrCounters.errorsThrown, Counters.JFR_ERRORS_THROWN);
      }
    });
    stream.onEvent("jdk.VirtualThreadStart", event -> jfrCounters.count(
        jfrCounters.vtStarted, Counters.JFR_VT_STARTED));
    stream.onEvent("jdk.VirtualThreadEnd", event -> jfrCounters.count(
        jfrCounters.vtEnded, Counters.JFR_VT_ENDED));
    stream.onEvent("jdk.ThreadStart", event -> jfrCounters.count(
        jfrCounters.threadStarted, Counters.JFR_THREAD_STARTED));
    stream.onEvent("jdk.ThreadEnd", event -> jfrCounters.count(
        jfrCounters.threadEnded, Counters.JFR_THREAD_ENDED));
    stream.onEvent("jdk.GCHeapSummary", event -> {
      if (event.hasField("when") && "After GC".equals(event.getString("when")) && event.hasField("heapUsed")
          && event.hasField("gcId")) {
        final var pending = jfrCounters.pending(event.getLong("gcId"));
        pending.epochSecond = event.getStartTime().getEpochSecond();
        pending.afterUsed = event.getLong("heapUsed");
        jfrCounters.resolve(event.getLong("gcId"));
      }
    });
    stream.onEvent("jdk.GarbageCollection", event -> {
      if (!event.hasField("gcId") || !event.hasField("name")) {
        return;
      }
      jfrCounters.count(jfrCounters.gcPauses, Counters.JFR_GC_PAUSES);
      final var name = event.getString("name");
      final var pending = jfrCounters.pending(event.getLong("gcId"));
      if ("G1New".equals(name)) {
        // A G1 young pause and a G1 mixed pause share this name; jdk.G1GarbageCollection's
        // `type` is what tells them apart, so the verdict waits for it.
        pending.g1Pause = true;
      } else {
        pending.verdict = reclaimsOldGeneration(name) ? PendingGc.QUALIFIES : PendingGc.DOES_NOT;
      }
      jfrCounters.resolve(event.getLong("gcId"));
    });
    stream.onEvent("jdk.G1GarbageCollection", event -> {
      if (!event.hasField("gcId") || !event.hasField("type")) {
        return;
      }
      final var pending = jfrCounters.pending(event.getLong("gcId"));
      // A G1 pause of any type (young, mixed, concurrent start) leaves old regions it did not
      // evacuate, so its after-heap is not the live set; only the full collection is.
      pending.verdict = PendingGc.DOES_NOT;
      jfrCounters.resolve(event.getLong("gcId"));
    });
    stream.onError(t -> System.err.println("soak: the JFR event stream failed: " + t));
    stream.startAsync();
    jfrCounters.stream = stream;
    return jfrCounters;
  }

  private void count(final LongAdder adder, final String counterName) {
    adder.increment();
    counters.increment(counterName);
  }

  /// Where every qualifying after-collection reading also goes (the gauge's CSV column).
  @FunctionalInterface
  public interface HeapFloorSink {
    void heapFloor(long epochSecond, long usedBytes);
  }

  public void heapFloorSink(final HeapFloorSink sink) {
    this.heapFloorSink = sink;
  }

  /// One collection's readings until both its summary and its classification have arrived.
  private static final class PendingGc {
    static final int UNKNOWN = 0;
    static final int QUALIFIES = 1;
    static final int DOES_NOT = 2;
    volatile long epochSecond;
    volatile long afterUsed = -1L;
    volatile int verdict = UNKNOWN;
    volatile boolean g1Pause;
  }

  /// A collection whose after-GC reading is a floor: a FULL collection. After a young pause the
  /// used heap carries everything promoted since the old generation was last reclaimed, and after
  /// a mixed pause it carries every old region the pause chose not to evacuate (measured
  /// 2026-09-22: 300 to 750 MiB between mixed pauses on a pilot, 23.6 MiB after its full
  /// collection), so on G1 only `G1Full` qualifies; `G1Old` is the concurrent cycle's
  /// remark/cleanup pause, which evacuates nothing. Other collectors qualify by their full/major
  /// collection names. The harness forces one full collection at each end of STEADY (see
  /// `Phases`), which is where the two readings the gate compares come from.
  private static boolean reclaimsOldGeneration(final String name) {
    if ("G1Old".equals(name) || "G1New".equals(name)) {
      return false;
    }
    return name.contains("Full") || name.contains("Old") || name.contains("Tenured")
        || name.contains("Major") || name.contains("Shenandoah") || name.startsWith("ZGC");
  }

  private PendingGc pending(final long gcId) {
    final var pending = pendingGcs.computeIfAbsent(gcId, id -> new PendingGc());
    if (pendingGcs.size() > MAX_PENDING_GCS) {
      // Bounded: a pause whose third event never came (a stream started mid-pause) is forgotten
      // once it is that far behind, rather than kept for the run.
      pendingGcs.keySet().removeIf(id -> id < gcId - MAX_PENDING_GCS);
    }
    return pending;
  }

  private void resolve(final long gcId) {
    final var pending = pendingGcs.get(gcId);
    if (pending == null || pending.afterUsed < 0L || pending.verdict == PendingGc.UNKNOWN) {
      return;
    }
    if (!pendingGcs.remove(gcId, pending)) {
      return;
    }
    if (pending.verdict == PendingGc.QUALIFIES) {
      count(floorSamples, Counters.JFR_GC_FLOOR_SAMPLES);
      recordHeapAfterGc(pending.epochSecond, pending.afterUsed);
      final var sink = heapFloorSink;
      if (sink != null) {
        sink.heapFloor(pending.epochSecond, pending.afterUsed);
      }
    }
  }

  /// True when the error was raised by `java.lang.invoke`'s own member resolution: the first
  /// frame below the `Throwable` constructors is in that package. Those `NoSuchMethodError`s are
  /// the JDK probing for a pregenerated LambdaForm holder and catching its own miss
  /// (`MemberName$Factory.resolveOrNull`); they never reach the subject or the harness.
  static boolean linkageProbe(final jdk.jfr.consumer.RecordedStackTrace stackTrace) {
    if (stackTrace == null) {
      return false;
    }
    for (final var frame : stackTrace.getFrames()) {
      final var method = frame.getMethod();
      if (method == null || method.getType() == null) {
        return false;
      }
      final var type = method.getType().getName();
      if ("<init>".equals(method.getName()) && type.startsWith("java.lang.") && type.endsWith("Error")) {
        continue;
      }
      return type.startsWith("java.lang.invoke.");
    }
    return false;
  }

  public long linkageProbes() {
    return linkageProbes.sum();
  }

  /// The heap floor is the *minimum* qualifying after-GC used heap in each bucket, not the last
  /// one ([#reclaimsOldGeneration] says which collections qualify): a collection that happens
  /// to run when a large response is live says nothing about retention, and the minimum over 30 s
  /// is the closest cheap estimate of what survived.
  private void recordHeapAfterGc(final long epochSecond, final long heapUsed) {
    heapAfterGc.set(heapUsed);
    final long bucket = epochSecond / HEAP_BUCKET_SECONDS;
    synchronized (heapLock) {
      if (currentBucket < 0L) {
        currentBucket = bucket;
        currentBucketMin = heapUsed;
        return;
      }
      if (bucket == currentBucket) {
        currentBucketMin = Math.min(currentBucketMin, heapUsed);
        return;
      }
      flushBucket();
      currentBucket = bucket;
      currentBucketMin = heapUsed;
    }
  }

  private void flushBucket() {
    if (currentBucket < 0L || currentBucketMin == Long.MAX_VALUE) {
      return;
    }
    if (heapFloorSeries.size() < MAX_HEAP_BUCKETS) {
      heapFloorSeries.add(new long[]{currentBucket * HEAP_BUCKET_SECONDS, currentBucketMin});
    } else {
      counters.increment(Counters.HARNESS_HEAP_SERIES_DROPPED);
    }
  }

  public long submitFailed() {
    return submitFailed.sum();
  }

  public long pinned() {
    return pinned.sum();
  }

  public long pinnedMaxMillis() {
    return pinnedMaxMillis.get();
  }

  public long errorsThrown() {
    return errorsThrown.sum();
  }

  public long vtStarted() {
    return vtStarted.sum();
  }

  public long vtEnded() {
    return vtEnded.sum();
  }

  public long threadStarted() {
    return threadStarted.sum();
  }

  public long threadEnded() {
    return threadEnded.sum();
  }

  /// The last after-GC used heap, or -1 before the first garbage collection. The gauge prefers
  /// its own `GarbageCollectorMXBean` notification and falls back to this.
  public long heapAfterGcBytes() {
    return heapAfterGc.get();
  }

  /// `{epochSeconds, minAfterGcBytes}` per 30 s bucket, oldest first, including the bucket in
  /// progress. The report fits its slope after warm-up; this class never judges it.
  public List<long[]> heapFloorSeries() {
    final var series = new ArrayList<long[]>(heapFloorSeries.size() + 1);
    series.addAll(heapFloorSeries);
    synchronized (heapLock) {
      if (currentBucket >= 0L && currentBucketMin != Long.MAX_VALUE) {
        series.add(new long[]{currentBucket * HEAP_BUCKET_SECONDS, currentBucketMin});
      }
    }
    return List.copyOf(series);
  }

  @Override
  public void close() {
    close(CLOSE_BOUND);
  }

  /// Closes the stream within `bound`, then halts. The halt is not defensive decoration: the
  /// consumer thread is non-daemon, so a stream that will not close prevents the JVM from
  /// exiting, and an exit code of 4 (INVALID) is a far better outcome than a run that hangs
  /// after its final dump.
  public void close(final Duration bound) {
    final var current = stream;
    if (current == null) {
      return;
    }
    stream = null;
    synchronized (heapLock) {
      flushBucket();
      currentBucket = -1L;
      currentBucketMin = Long.MAX_VALUE;
    }
    final var closer = new Thread(current::close, "soak-jfr-close");
    closer.setDaemon(true);
    closer.start();
    try {
      closer.join(bound.toMillis());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (closer.isAlive()) {
      System.err.println("SOAK_EXIT 4");
      System.err.println("soak: the JFR event stream did not close within " + bound + "; halting");
      System.err.flush();
      Runtime.getRuntime().halt(4);
    }
  }
}
