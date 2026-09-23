package software.sava.rpc.soak.report;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeSet;

/// One streaming pass over the flight recording, and the report sections built from it.
///
/// Streaming and typed on purpose: `RecordingFile` is the same reader `jfr print` is built on, so
/// field access here is independent of that tool's text layout, and an eight-hour campaign's ring
/// never has to fit in memory at once. Every unbounded collection below is capped with a counted
/// overflow, because a report that dies of `OutOfMemoryError` while explaining a memory problem
/// is worse than one that says "the first 200,000 samples".
///
/// Facts this class is built on, each measured on this JDK (`design/facts.md`) and each one a
/// silent wrong answer if ignored:
///
/// - `jdk.ExecutionSample` carries its thread in `sampledThread`; `eventThread` is null on every
///   sample, and reading it produces an empty hot-thread table with no error.
/// - `jdk.MethodTiming.invocations` is CUMULATIVE across chunks. Rows are never summed; the
///   per-interval rate is the difference between consecutive rows for one method.
/// - `jdk.ThreadPark` never fires for a virtual thread, and `jdk.ThreadDump` lists platform
///   threads only. Both sections say so rather than implying the absence of an observation is
///   the observation of an absence.
/// - `jdk.OldObjectSample` re-emits the same sample at every chunk rotation, and only the
///   emission at a `path-to-gc-roots=true` dump carries a root — hence the dedupe below, keyed on
///   allocation time and shape.
/// - `jdk.SocketRead` is attributed to the `HttpClient` SelectorManager and `jdk.SocketWrite` to
///   the calling thread, never to the caller of the RPC. Per-request bytes come from the
///   harness's own counters; these tables are transport shape only.
final class JfrPass {

  static final int TOP_FRAMES = 8;
  static final int TOP_EVENT_TYPES = 30;
  static final int TOP_SITES = 10;
  static final int TOP_STACKS = 10;
  static final int TOP_THREADS = 8;
  static final int TOP_PINNED = 5;
  static final int TOP_SPAWNERS = 8;
  static final int TOP_NMT_TYPES = 12;
  static final int TOP_ROWS = 15;
  /// How many rows a tail keeps. The table prints [#TOP_ROWS]; the rest is the margin that lets
  /// the kept set be the worst rows of the whole run rather than its first ones.
  static final int TAIL_ROWS = TOP_ROWS * 8;
  static final int MAX_REFERRER_HOPS = 12;
  static final int MAX_TRACKED_THREADS = 200_000;
  static final int MAX_STACKS_PER_FRAME = 64;
  static final int MAX_SAMPLES = 200_000;
  static final int MAX_DUMPS = 4_096;
  static final int MAX_KEYS = 4_096;
  /// One thread holding this share of at least [#DOMINANT_MIN_SAMPLES] execution samples is the
  /// wedged-thread signature: a spinning loop or a handler that never returns.
  static final double DOMINANT_SHARE = 0.5d;
  static final long DOMINANT_MIN_SAMPLES = 100L;
  /// A socket operation at or over this is in the tail table; the .jfc threshold decides what was
  /// recorded at all, this decides what is worth a line.
  static final long SLOW_SOCKET_MS = 20L;
  /// `DESIGN.md` §11: `jdk.MethodTrace` outliers only. A filter at 0 ms emits one stack per call.
  static final long SLOW_METHOD_TRACE_MS = 50L;
  /// `jdk.ThreadPark` below this is ordinary pacing, not a stall.
  static final long SLOW_PARK_MS = 10L;
  /// The same non-idle top frame in this many CONSECUTIVE dumps is the stuck-thread signature.
  static final int STUCK_DUMPS = 3;
  /// The smallest `cpu=` advance a `jdk.ThreadDump` header can print, and therefore the smallest
  /// one that says a waiting thread woke and ran between two dumps. A thread that is parked burns
  /// none at all, so any printable advance is progress and a flat counter across a whole dump
  /// interval is not.
  static final double MIN_CPU_PROGRESS_MILLIS = 0.01d;
  /// Below this, a gap between the unthrottled exception count and the recorded throws is start-up
  /// noise rather than a throttle that bit.
  static final long MIN_THROTTLE_DEFICIT = 100L;
  /// How long after an OVERLAP marker a delivery still counts as inside the fan-out.
  ///
  /// `Phases.marker` commits the OVERLAP `sava.soak.Phase` event with no `begin()`/`end()`, so JFR
  /// stamps it at the commit instant with zero duration: the run records the fan-out TRIGGER, not
  /// a window, and `PhaseWindow.holds` on a zero-width window can only be true by nanosecond
  /// coincidence. Left that way the W4 table prints an empty "inside OVERLAP" row beside a
  /// populated "outside" one and reads as proof the fan-out cost nothing. So the report widens the
  /// marker itself, and says so wherever it prints the comparison.
  ///
  /// The value is the fan-out's own W4-C bound — `HttpWorkload` launches 32 `getSlot` probes at
  /// `2 x W4C_REQUEST_TIMEOUT` (500 ms) plus `OP_GRACE` (5 s) alongside the eight large
  /// `getProgramAccounts` parses, and those parses are measured in tens of milliseconds unfaulted.
  /// `PGA_BOUND` (125 s) is the other candidate and is rejected: it would swallow a third of the
  /// 300 s OVERLAP period and call an idle stretch contention. Those constants are package-private
  /// to `software.sava.rpc.soak.http`, so this one is stated rather than derived; it moves when
  /// they do.
  static final Duration OVERLAP_MARKER_WINDOW = Duration.ofSeconds(6);

  /// A distribution kept for percentiles. Samples past [#MAX_SAMPLES] are counted but not kept —
  /// the count, sum and max stay exact, and the percentiles are over the kept prefix, which the
  /// rendered line states whenever it happened.
  static final class Dist {
    long count;
    long sum;
    long max;
    long min = Long.MAX_VALUE;
    long unsampled;
    final List<Long> samples = new ArrayList<>();

    void add(final long value) {
      ++count;
      sum += value;
      max = Math.max(max, value);
      min = Math.min(min, value);
      if (samples.size() < MAX_SAMPLES) {
        samples.add(value);
      } else {
        ++unsampled;
      }
    }

    long p(final double quantile) {
      return TsvReader.percentile(samples, quantile);
    }

    double mean() {
      return count == 0 ? 0d : (double) sum / count;
    }
  }

  /// A retained allocation site: one object type allocated from one top frame.
  static final class Site {
    long samples;
    long bytes;
    List<String> frames;
    String root;
    List<String> path;
  }

  /// One deduplicated old-object sample. The key is allocation time plus shape because the same
  /// object is re-emitted at every chunk rotation under a different event start time.
  record OldSample(String type, long bytes, List<String> frames, String root, List<String> path, Instant emitted) {
  }

  /// Execution samples sharing a top frame, with the most common full stack beneath it.
  static final class Hot {
    long samples;
    final Map<String, long[]> stacks = new HashMap<>();
    final Map<String, List<String>> frames = new HashMap<>();
  }

  /// Events sharing a stack (pinned, monitor-enter, park).
  static final class Stack {
    long count;
    long maxNanos;
    long sumNanos;
    List<String> frames;
    String detail;
  }

  /// A `sava.soak.Phase` window. `OVERLAP` windows are what the W4 comparison splits on, and every
  /// one of them is `synthetic`: the run records a zero-width marker and the report widens it by
  /// [#OVERLAP_MARKER_WINDOW]. The flag travels with the window so the section that prints the
  /// comparison can say the window is the report's rather than the run's.
  record PhaseWindow(String name, int index, Instant from, Instant to, boolean synthetic) {

    boolean holds(final Instant at) {
      return at != null && !at.isBefore(from) && !at.isAfter(to);
    }

    /// A window a delivery can actually fall inside. Counting a zero-width one as a window is what
    /// suppresses the "this comparison is not evidence" caveat while the inside row stays empty.
    boolean measurable() {
      return to.isAfter(from);
    }
  }

  /// A bounded worst-N tail.
  ///
  /// Rows arrive in time order and are admitted against a key — the duration in nanos — so the
  /// table holds the slowest events of the WHOLE run rather than the slowest of its first
  /// [#TAIL_ROWS]. A chronological prefix was measured freezing the "slowest deliveries" table on
  /// the opening minutes of a run and still labelling it "top 15 of 120", with nothing saying the
  /// rest had been discarded. Ordering is on the admitted key, never on a reparse of a rendered
  /// cell: the socket tail's first cell is the direction, and sorting it as a number scored every
  /// row 0.0 and printed the table in arrival order under a heading that promised the worst.
  static final class Tail {

    private record Row(long key, String[] cells) {
    }

    private final int capacity;
    /// Min-heap on the key, so the row evicted when the cap binds is the fastest one kept.
    private final PriorityQueue<Row> kept;
    /// Everything over the threshold, including what was evicted: the denominator the heading owes
    /// its reader.
    long admitted;
    long evicted;

    Tail(final int capacity) {
      this.capacity = capacity;
      this.kept = new PriorityQueue<>(Comparator.comparingLong(Row::key));
    }

    void add(final long key, final String[] cells) {
      ++admitted;
      kept.add(new Row(key, cells));
      if (kept.size() > capacity) {
        kept.poll();
        ++evicted;
      }
    }

    boolean isEmpty() {
      return kept.isEmpty();
    }

    /// Worst first.
    List<String[]> rows() {
      final var ordered = new ArrayList<>(kept);
      ordered.sort((a, b) -> Long.compare(b.key(), a.key()));
      final var cells = new ArrayList<String[]>(ordered.size());
      for (final var row : ordered) {
        cells.add(row.cells());
      }
      return cells;
    }
  }

  private final Path recording;
  private final Path methodTraceLog;
  private final boolean present;

  // ---- census ----
  long events;
  Instant first;
  Instant last;
  final Map<String, long[]> eventCounts = new HashMap<>();
  final TreeSet<Instant> chunkStarts = new TreeSet<>();
  long chunkEvents;
  Instant runStart;
  Instant runEnd;
  String runWindowNote = "";
  final List<String> filtersRequested = new ArrayList<>();
  final List<String> filtersUnresolved = new ArrayList<>();
  int timingEntryLines;
  String methodTraceLogNote = "";

  // ---- retention ----
  final Map<String, OldSample> oldSamples = new HashMap<>();
  long oldSampleEvents;

  // ---- profile ----
  long executionSamples;
  final Map<String, Hot> hot = new HashMap<>();
  final Map<Long, long[]> threadSamples = new HashMap<>();
  final Map<Long, String> threadLabels = new HashMap<>();
  long untrackedThreadSamples;

  // ---- virtual threads and errors ----
  long pinned;
  final Map<String, Stack> pins = new HashMap<>();
  long submitFailed;
  final List<String> submitFailures = new ArrayList<>();
  long errorsThrown;
  long errorsLinkageProbe;
  final Map<String, long[]> errorTypes = new HashMap<>();
  long exceptionsThrown;
  final Map<String, long[]> exceptionTypes = new HashMap<>();
  long exceptionStatisticsMax;
  long vtStarted;
  long vtEnded;

  // ---- platform threads ----
  long threadStarts;
  long threadEnds;
  final Map<String, long[]> spawners = new HashMap<>();

  // ---- gc ----
  final List<Long> gcPauses = new ArrayList<>();
  long gcLongestPause;
  final Map<String, long[]> gcCauses = new HashMap<>();
  final Map<String, long[]> gcNames = new HashMap<>();
  long heapAfterGcSamples;
  Instant heapFirstAt;
  Instant heapLastAt;
  long heapFirstUsed;
  long heapLastUsed;
  long heapMinUsed = Long.MAX_VALUE;
  long heapMaxUsed;

  // ---- native memory ----
  long nmtTotalSamples;
  Instant nmtFirstAt;
  long nmtFirstCommitted;
  Instant nmtLastAt;
  long nmtLastCommitted;
  long nmtLastReserved;
  final Map<String, long[]> nmtTypes = new HashMap<>();
  final Map<String, Instant[]> nmtTypeAt = new HashMap<>();

  // ---- transport ----
  long socketEvents;
  final Map<String, long[]> socketReadByHost = new HashMap<>();
  final Map<String, long[]> socketWriteByHost = new HashMap<>();
  final Map<String, long[]> socketReadByThread = new HashMap<>();
  final Map<String, long[]> socketWriteByThread = new HashMap<>();
  final Tail socketTail = new Tail(MAX_SAMPLES);

  // ---- stalls ----
  final Map<String, Stack> monitors = new HashMap<>();
  long monitorEvents;
  final Map<String, Stack> parks = new HashMap<>();
  long parkEvents;
  long parkVirtualEvents;
  final List<Object[]> threadDumps = new ArrayList<>();
  long threadDumpsDropped;
  final Map<String, Dist> methodTraces = new HashMap<>();
  long methodTraceEvents;
  final Map<String, List<long[]>> methodTiming = new LinkedHashMap<>();
  final Map<String, long[]> methodTimingLatest = new HashMap<>();

  // ---- harness events ----
  final List<PhaseWindow> phases = new ArrayList<>();
  final Map<String, Instant[]> openPhases = new LinkedHashMap<>();
  long gaugeEvents;
  RecordedEvent lastGauge;
  final Map<String, Dist> rpcByMethod = new HashMap<>();
  final Map<String, long[]> rpcByOutcome = new HashMap<>();
  final Dist rpcAll = new Dist();
  final Tail rpcTail = new Tail(TAIL_ROWS);
  final Map<String, Dist> notificationsByConsumer = new HashMap<>();
  final Dist notificationsInOverlap = new Dist();
  final Dist notificationsOutsideOverlap = new Dist();
  final Tail notificationTail = new Tail(TAIL_ROWS);
  long recoveryEvents;
  long recoveryIncomplete;
  long recoverySuperseded;
  long recoveryVacuous;
  final Dist recoveryDuration = new Dist();
  final Map<String, long[]> faultsObserved = new HashMap<>();
  long retirementEvents;
  final Map<String, long[]> retirementVerdicts = new HashMap<>();
  long claimEvents;
  final Map<String, long[]> claimRoutes = new HashMap<>();
  long misattributionEvents;
  final List<String> misattributionExamples = new ArrayList<>();
  long anomalyEvents;
  final Map<String, long[]> anomalyKinds = new HashMap<>();

  JfrPass(final Path recording, final Path methodTraceLog) {
    this.recording = recording;
    this.methodTraceLog = methodTraceLog;
    this.present = recording != null;
  }

  boolean present() {
    return present;
  }

  /// The run's wall window, from `phases.tsv`, against which ring coverage is measured. A ring
  /// that rolled covers only its last hours, and the census has to say so before any section
  /// built from ring counts is read as a whole-run number.
  void runWindow(final Instant from, final Instant to, final String note) {
    this.runStart = from;
    this.runEnd = to;
    this.runWindowNote = note == null ? "" : note;
  }

  void read() throws IOException {
    if (!present) {
      return;
    }
    try (final var file = new RecordingFile(recording)) {
      while (file.hasMoreEvents()) {
        accept(file.readEvent());
      }
    }
    readMethodTraceLog();
  }

  private void accept(final RecordedEvent event) {
    ++events;
    final var at = event.getStartTime();
    if (first == null || at.isBefore(first)) {
      first = at;
    }
    if (last == null || at.isAfter(last)) {
      last = at;
    }
    final var type = event.getEventType().getName();
    count(eventCounts, type);
    switch (type) {
      case "jdk.JVMInformation" -> chunkStart(at);
      case "jdk.OldObjectSample" -> oldObject(event);
      case "jdk.ExecutionSample" -> execution(event);
      case "jdk.VirtualThreadPinned" -> pinnedEvent(event);
      case "jdk.VirtualThreadSubmitFailed" -> submitFailedEvent(event);
      case "jdk.VirtualThreadStart" -> ++vtStarted;
      case "jdk.VirtualThreadEnd" -> ++vtEnded;
      case "jdk.JavaErrorThrow" -> {
        // java.lang.invoke's own pregenerated-LambdaForm probing throws and catches a
        // NoSuchMethodError per miss; those never reach the subject and are counted apart
        if (linkageProbe(event.getStackTrace())) {
          ++errorsLinkageProbe;
        } else {
          ++errorsThrown;
          count(errorTypes, classOf(event, "thrownClass"));
        }
      }
      case "jdk.JavaExceptionThrow" -> {
        ++exceptionsThrown;
        count(exceptionTypes, classOf(event, "thrownClass"));
      }
      case "jdk.ExceptionStatistics" ->
          exceptionStatisticsMax = Math.max(exceptionStatisticsMax, event.getLong("throwables"));
      case "jdk.GarbageCollection" -> gc(event);
      case "jdk.GCHeapSummary" -> heapSummary(event);
      case "jdk.ThreadStart" -> threadStart(event);
      case "jdk.ThreadEnd" -> ++threadEnds;
      case "jdk.NativeMemoryUsageTotal" -> nmtTotal(event);
      case "jdk.NativeMemoryUsage" -> nmtType(event);
      case "jdk.SocketRead" -> socket(event, true);
      case "jdk.SocketWrite" -> socket(event, false);
      case "jdk.JavaMonitorEnter" -> monitor(event);
      case "jdk.ThreadPark" -> park(event);
      case "jdk.ThreadDump" -> threadDump(event);
      case "jdk.MethodTrace" -> methodTrace(event);
      case "jdk.MethodTiming" -> methodTiming(event);
      case "sava.soak.Phase" -> phase(event);
      case "sava.soak.Gauge" -> {
        ++gaugeEvents;
        lastGauge = event;
      }
      case "sava.soak.RpcCall" -> rpcCall(event);
      case "sava.soak.NotificationDelivered" -> notification(event);
      case "sava.soak.NotificationAnomaly" -> {
        ++anomalyEvents;
        count(anomalyKinds, string(event, "kind"));
      }
      case "sava.soak.Recovery" -> recovery(event);
      case "sava.soak.FaultObserved" -> count(faultsObserved, string(event, "kind"));
      case "sava.soak.Retirement" -> {
        ++retirementEvents;
        count(retirementVerdicts, string(event, "verdict"));
      }
      case "sava.soak.ManagerClaim" -> {
        ++claimEvents;
        count(claimRoutes, string(event, "route"));
      }
      case "sava.soak.Misattribution" -> misattribution(event);
      default -> {
      }
    }
  }

  // ---------------------------------------------------------------- census

  private void chunkStart(final Instant at) {
    ++chunkEvents;
    if (chunkStarts.size() < MAX_KEYS) {
      chunkStarts.add(at);
    }
  }

  long chunks() {
    return chunkStarts.isEmpty() ? (events > 0 ? 1L : 0L) : chunkStarts.size();
  }

  /// `-Xlog:jfr+methodtrace=debug` is the ONLY evidence that a method filter took: a typo appears
  /// in the installed-filter line and then simply never gets an entry, with no warning, and a
  /// wildcard disables the tracer outright. `requested` is what the filter string asked for,
  /// `resolved` is how many of those the JVM actually instrumented. The runner gates on the pair.
  private void readMethodTraceLog() throws IOException {
    if (methodTraceLog == null || !Files.isRegularFile(methodTraceLog)) {
      methodTraceLogNote = "no jfr-methodtrace.log";
      return;
    }
    final var lines = Files.readAllLines(methodTraceLog, StandardCharsets.UTF_8);
    int installedAt = -1;
    final var timingLines = new ArrayList<String>();
    for (int at = 0; at < lines.size(); ++at) {
      final var line = lines.get(at);
      if (line.contains("New filter installed")) {
        installedAt = at;
      } else if (line.contains("Timing entry added")) {
        ++timingEntryLines;
        timingLines.add(line);
      }
    }
    if (installedAt < 0) {
      methodTraceLogNote = "no 'New filter installed' line — the method tracer never initialised"
          + " (a wildcard or malformed filter disables it silently)";
      return;
    }
    // The block is printed either inline as '{ a, b }' or, as this JDK does, with one entry per
    // following log line and a lone '}' to close it. Both forms are read here because a report
    // that quietly found no entries would report every filter as unresolved.
    final var entries = new ArrayList<String>();
    final var head = message(lines.get(installedAt));
    final int open = head.indexOf('{');
    final int close = head.lastIndexOf('}');
    if (open >= 0 && close > open) {
      entries.addAll(List.of(head.substring(open + 1, close).split(",")));
    } else if (open >= 0) {
      for (int at = installedAt + 1; at < lines.size(); ++at) {
        final var line = message(lines.get(at));
        if (line.startsWith("}")) {
          break;
        }
        entries.addAll(List.of(line.split(",")));
      }
    } else {
      methodTraceLogNote = "'New filter installed' line carried no { ... } block";
      return;
    }
    for (final var entry : entries) {
      final var trimmed = entry.strip();
      if (trimmed.isEmpty()) {
        continue;
      }
      // entries render as 'pkg.Class::method +timing'; the flags are not part of the filter
      final var name = trimmed.split("\\s+")[0];
      filtersRequested.add(name);
      // one filter entry can instrument several methods (every overload), so the question is
      // whether ANY timing entry names it, never how many lines it produced
      boolean found = false;
      for (final var line : timingLines) {
        if (line.contains(name)) {
          found = true;
          break;
        }
      }
      if (!found) {
        filtersUnresolved.add(name);
      }
    }
  }

  /// `[0.214s][debug][jfr,methodtrace]  SynthSoak::tick +timing` -> `SynthSoak::tick +timing`.
  private static String message(final String line) {
    final int close = line.lastIndexOf(']');
    return (close < 0 ? line : line.substring(close + 1)).strip();
  }

  int filtersResolved() {
    return filtersRequested.size() - filtersUnresolved.size();
  }

  // ---------------------------------------------------------------- handlers

  private void oldObject(final RecordedEvent event) {
    ++oldSampleEvents;
    final var object = value(event, "object");
    final var type = object == null ? "?" : className(object.getClass("type"));
    final long bytes = event.hasField("objectSize") ? event.getLong("objectSize") : 0L;
    final var allocated = event.hasField("allocationTime") ? event.getInstant("allocationTime") : event.getStartTime();
    final int elements = event.hasField("arrayElements") ? event.getInt("arrayElements") : Integer.MIN_VALUE;
    final var key = allocated + "|" + type + '|' + bytes + '|' + elements;
    final var previous = oldSamples.get(key);
    if (previous != null && !previous.emitted().isBefore(event.getStartTime())) {
      return;
    }
    final var root = value(event, "root");
    final var rootLine = root == null ? (previous == null ? null : previous.root()) : rootLine(root);
    final var path = root == null ? (previous == null ? List.<String>of() : previous.path()) : referrerPath(object);
    if (previous == null && oldSamples.size() >= MAX_KEYS) {
      return;
    }
    oldSamples.put(key, new OldSample(type, bytes, frames(event.getStackTrace()), rootLine, path, event.getStartTime()));
  }

  private void execution(final RecordedEvent event) {
    ++executionSamples;
    final var frames = frames(event.getStackTrace());
    final var top = frames.isEmpty() ? "(no stack)" : frames.getFirst();
    final var entry = hot.computeIfAbsent(top, ignored -> new Hot());
    ++entry.samples;
    final var signature = String.join(" < ", frames);
    final var counter = entry.stacks.get(signature);
    if (counter != null) {
      ++counter[0];
    } else if (entry.stacks.size() < MAX_STACKS_PER_FRAME) {
      entry.stacks.put(signature, new long[]{1L});
      entry.frames.put(signature, frames);
    }
    // sampledThread, never eventThread: eventThread is null on every execution sample, and
    // reading it yields an empty table with no error (measured)
    final var thread = event.hasField("sampledThread") ? event.getThread("sampledThread") : null;
    if (thread == null) {
      ++untrackedThreadSamples;
      return;
    }
    final long id = thread.getJavaThreadId();
    final var counted = threadSamples.get(id);
    if (counted != null) {
      ++counted[0];
    } else if (threadSamples.size() < MAX_TRACKED_THREADS) {
      threadSamples.put(id, new long[]{1L});
      threadLabels.put(id, threadLabel(thread));
    } else {
      ++untrackedThreadSamples;
    }
  }

  private void pinnedEvent(final RecordedEvent event) {
    ++pinned;
    final var frames = frames(event.getStackTrace());
    final var reason = string(event, "pinnedReason");
    final var operation = string(event, "blockingOperation");
    final var why = join(reason, operation);
    stack(pins, (why == null ? "" : why) + '\n' + String.join(" < ", frames), frames, why, event.getDuration().toNanos());
  }

  private void submitFailedEvent(final RecordedEvent event) {
    ++submitFailed;
    if (submitFailures.size() < 3) {
      final var frames = frames(event.getStackTrace());
      submitFailures.add((event.hasField("javaThreadId") ? "thread #" + event.getLong("javaThreadId") + ": " : "")
          + string(event, "exceptionMessage") + (frames.isEmpty() ? "" : " at " + frames.getFirst()));
    }
  }

  private void gc(final RecordedEvent event) {
    final var pauses = event.hasField("sumOfPauses") ? event.getDuration("sumOfPauses") : event.getDuration();
    gcPauses.add(pauses.toNanos());
    if (event.hasField("longestPause")) {
      gcLongestPause = Math.max(gcLongestPause, event.getDuration("longestPause").toNanos());
    }
    count(gcCauses, string(event, "cause"));
    count(gcNames, string(event, "name"));
  }

  /// The after-GC used-heap series from the ring. This is the CROSS-CHECK only: the ring holds
  /// its last hours, so a whole-run retention trend can only come from `client.csv`, which the
  /// client writes for every gauge tick of the run.
  private void heapSummary(final RecordedEvent event) {
    if (!"After GC".equals(string(event, "when"))) {
      return;
    }
    ++heapAfterGcSamples;
    final long used = event.getLong("heapUsed");
    final var at = event.getStartTime();
    if (heapFirstAt == null || at.isBefore(heapFirstAt)) {
      heapFirstAt = at;
      heapFirstUsed = used;
    }
    if (heapLastAt == null || !at.isBefore(heapLastAt)) {
      heapLastAt = at;
      heapLastUsed = used;
    }
    heapMinUsed = Math.min(heapMinUsed, used);
    heapMaxUsed = Math.max(heapMaxUsed, used);
  }

  private void threadStart(final RecordedEvent event) {
    ++threadStarts;
    final var frames = frames(event.getStackTrace());
    var spawner = "(no stack)";
    for (final var frame : frames) {
      if (!frame.startsWith("java.lang.Thread.") && !frame.startsWith("java.lang.System$")) {
        spawner = frame;
        break;
      }
    }
    count(spawners, spawner);
  }

  private void nmtTotal(final RecordedEvent event) {
    ++nmtTotalSamples;
    final var at = event.getStartTime();
    final long committed = event.getLong("committed");
    if (nmtFirstAt == null || at.isBefore(nmtFirstAt)) {
      nmtFirstAt = at;
      nmtFirstCommitted = committed;
    }
    if (nmtLastAt == null || !at.isBefore(nmtLastAt)) {
      nmtLastAt = at;
      nmtLastCommitted = committed;
      nmtLastReserved = event.getLong("reserved");
    }
  }

  private void nmtType(final RecordedEvent event) {
    final var type = string(event, "type");
    final var at = event.getStartTime();
    final long committed = event.getLong("committed");
    final var values = nmtTypes.get(type);
    if (values == null) {
      if (nmtTypes.size() >= MAX_KEYS) {
        return;
      }
      nmtTypes.put(type, new long[]{committed, committed});
      nmtTypeAt.put(type, new Instant[]{at, at});
      return;
    }
    final var when = nmtTypeAt.get(type);
    if (at.isBefore(when[0])) {
      when[0] = at;
      values[0] = committed;
    }
    if (!at.isBefore(when[1])) {
      when[1] = at;
      values[1] = committed;
    }
  }

  private void socket(final RecordedEvent event, final boolean read) {
    ++socketEvents;
    final var host = hostLabel(event);
    final long bytes = read
        ? (event.hasField("bytesRead") ? event.getLong("bytesRead") : 0L)
        : (event.hasField("bytesWritten") ? event.getLong("bytesWritten") : 0L);
    final long nanos = event.getDuration().toNanos();
    add(read ? socketReadByHost : socketWriteByHost, host, bytes, nanos);
    add(read ? socketReadByThread : socketWriteByThread, threadName(event.getThread()), bytes, nanos);
    if (nanos >= SLOW_SOCKET_MS * 1_000_000L) {
      // admitted on the duration, printed with the direction first: the table's column order is
      // its own, and the tail's order is the key it was admitted on
      socketTail.add(nanos, new String[]{
          read ? "read" : "write", host, threadName(event.getThread()),
          Long.toString(bytes), millis(nanos), event.getStartTime().toString()
      });
    }
  }

  private void monitor(final RecordedEvent event) {
    ++monitorEvents;
    final var frames = frames(event.getStackTrace());
    final var monitorClass = classOf(event, "monitorClass");
    stack(monitors, monitorClass + '\n' + (frames.isEmpty() ? "(no stack)" : frames.getFirst()),
        frames, monitorClass, event.getDuration().toNanos());
  }

  /// Platform threads only — `LockSupport.park` branches to `parkVirtualThread` for a virtual
  /// thread and the VM event brackets only the `Unsafe.park` branch (measured). A virtual thread
  /// blocked on a park is invisible here; the section says so.
  private void park(final RecordedEvent event) {
    final long nanos = event.getDuration().toNanos();
    final var thread = event.getThread();
    if (thread != null && thread.isVirtual()) {
      ++parkVirtualEvents;
    }
    if (nanos < SLOW_PARK_MS * 1_000_000L) {
      return;
    }
    ++parkEvents;
    final var frames = frames(event.getStackTrace());
    final var top = significantFrame(frames);
    stack(parks, top, frames, threadName(thread), nanos);
  }

  private void threadDump(final RecordedEvent event) {
    if (threadDumps.size() >= MAX_DUMPS) {
      ++threadDumpsDropped;
      return;
    }
    threadDumps.add(new Object[]{event.getStartTime(), parseThreadDump(string(event, "result"))});
  }

  private void methodTrace(final RecordedEvent event) {
    ++methodTraceEvents;
    final long nanos = event.getDuration().toNanos();
    if (nanos < SLOW_METHOD_TRACE_MS * 1_000_000L) {
      return;
    }
    final var method = methodLabel(event.hasField("method") ? event.getValue("method") : null, event.getStackTrace());
    methodTraces.computeIfAbsent(method, ignored -> new Dist()).add(nanos);
  }

  /// `invocations` is a running total across chunks (measured: 14,200,000 then 31,900,000 for the
  /// same method). Rows are kept in time order per method and never summed; the report prints the
  /// latest total and the differences between consecutive rows, which are the per-interval rates.
  private void methodTiming(final RecordedEvent event) {
    // Keyed on the descriptor as well: one filter entry instruments every overload, each with its
    // own cumulative counter, and diffing interleaved rows of two overloads under one name was
    // measured printing negative per-interval rates.
    final var recorded = event.hasField("method") ? event.getValue("method") : null;
    final var method = recorded instanceof RecordedMethod rm
        ? methodLabel(rm, null) + (rm.getDescriptor() == null ? "" : rm.getDescriptor())
        : methodLabel(recorded, null);
    final long invocations = event.hasField("invocations") ? event.getLong("invocations") : 0L;
    final var rows = methodTiming.computeIfAbsent(method, ignored -> new ArrayList<>());
    if (rows.size() < MAX_KEYS) {
      rows.add(new long[]{event.getStartTime().toEpochMilli(), invocations});
    }
    final long average = event.hasField("average") ? nanosOf(event, "average") : 0L;
    final long maximum = event.hasField("maximum") ? nanosOf(event, "maximum") : 0L;
    methodTimingLatest.put(method, new long[]{invocations, average, maximum});
  }

  /// `Phases.boundary` brackets a real window with `begin()`/`end()`; `Phases.marker` does not,
  /// and OVERLAP is only ever a marker — the fan-out is an overlay that must not close the
  /// `HTTP_QUIET`/`HTTP_LOADED` chain. JFR therefore stamps an OVERLAP event at its commit instant
  /// with zero duration, which no delivery can fall inside. Widening it to
  /// [#OVERLAP_MARKER_WINDOW] is what gives the W4 split something to split on, and the window is
  /// flagged `synthetic` so the section says whose window it is.
  private void phase(final RecordedEvent event) {
    final var name = string(event, "name");
    final int index = event.hasField("index") ? event.getInt("index") : -1;
    final var from = event.getStartTime();
    final var end = event.getEndTime();
    final var to = end == null ? from : end;
    if (phases.size() >= MAX_KEYS) {
      return;
    }
    if (isOverlap(name) && !to.isAfter(from)) {
      phases.add(new PhaseWindow(name, index, from, from.plus(OVERLAP_MARKER_WINDOW), true));
    } else {
      phases.add(new PhaseWindow(name == null ? "?" : name, index, from, to, false));
    }
  }

  private void rpcCall(final RecordedEvent event) {
    final long nanos = event.getDuration().toNanos();
    rpcAll.add(nanos);
    final var method = string(event, "method");
    final var outcome = string(event, "outcome");
    count(rpcByOutcome, outcome);
    if (rpcByMethod.size() < MAX_KEYS || rpcByMethod.containsKey(method)) {
      rpcByMethod.computeIfAbsent(method == null ? "?" : method, ignored -> new Dist()).add(nanos);
    }
    // `faultKind` on the event is the client-side error kind (the outcome name), never the
    // peer's injected fault: the two are joined by ordinal in the harness TSVs, not here
    rpcTail.add(nanos, new String[]{millis(nanos), method == null ? "?" : method, outcome == null ? "?" : outcome,
        Long.toString(event.hasField("httpStatus") ? event.getInt("httpStatus") : -1),
        Long.toString(event.hasField("responseBytes") ? event.getLong("responseBytes") : -1),
        string(event, "route"), string(event, "faultKind"), event.getStartTime().toString()});
  }

  private void notification(final RecordedEvent event) {
    final long nanos = event.getDuration().toNanos();
    final var consumer = string(event, "consumerKind");
    notificationsByConsumer.computeIfAbsent(consumer == null ? "?" : consumer, ignored -> new Dist()).add(nanos);
    if (inOverlap(event.getStartTime())) {
      notificationsInOverlap.add(nanos);
    } else {
      notificationsOutsideOverlap.add(nanos);
    }
    notificationTail.add(nanos, new String[]{millis(nanos), string(event, "engine"), string(event, "channel"),
        consumer == null ? "?" : consumer, Long.toString(event.hasField("sequence") ? event.getLong("sequence") : -1),
        Boolean.toString(event.hasField("threw") && event.getBoolean("threw")),
        Boolean.toString(event.hasField("slowByDesign") && event.getBoolean("slowByDesign")),
        event.getStartTime().toString()});
  }

  private void recovery(final RecordedEvent event) {
    if (event.hasField("superseded") && event.getBoolean("superseded")) {
      // displaced inside its budget by the next fault: no verdict, and not an episode duration
      ++recoverySuperseded;
      return;
    }
    if (event.hasField("vacuous") && event.getBoolean("vacuous")) {
      // owed nothing: no verdict, and not an episode duration
      ++recoveryVacuous;
      return;
    }
    ++recoveryEvents;
    // the window spans the fault row to its close; the event itself is committed at the close
    recoveryDuration.add(event.hasField("elapsedMillis")
        ? event.getLong("elapsedMillis") * 1_000_000L
        : event.getDuration().toNanos());
    // the metric is named over budget and P7 fails on either: over budget OR incomplete
    if ((event.hasField("overBudget") && event.getBoolean("overBudget"))
        || (event.hasField("complete") && !event.getBoolean("complete"))) {
      ++recoveryIncomplete;
    }
  }

  private void misattribution(final RecordedEvent event) {
    ++misattributionEvents;
    if (misattributionExamples.size() < 5) {
      misattributionExamples.add("retirement " + string(event, "retirementId")
          + ": origin " + string(event, "originOrdinal") + " -> successor " + string(event, "successorOrdinal")
          + ", gap " + string(event, "gapUs") + " us, retry " + string(event, "retryDelayMs") + " ms, "
          + string(event, "backoffClass") + (event.hasField("forced") && event.getBoolean("forced") ? ", forced" : ""));
    }
  }

  /// Classified as the stream goes past, against the OVERLAP windows read SO FAR. That works only
  /// because an OVERLAP window comes from a marker, which JFR commits at the trigger instant and
  /// therefore hands this pass before the deliveries the window covers; a duration event is
  /// committed at its close and would arrive after them. Deferring the split instead would mean
  /// keeping every delivery's start instant, which is the one thing this pass will not do.
  private boolean inOverlap(final Instant at) {
    for (final var window : phases) {
      if (isOverlap(window.name()) && window.measurable() && window.holds(at)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isOverlap(final String name) {
    return name != null && name.contains("OVERLAP");
  }

  // ---------------------------------------------------------------- sections

  void writeCensus(final PrintWriter out, final Metrics metrics) throws IOException {
    out.println();
    out.println("## (0) Recording census");
    out.println();
    if (!present) {
      out.println("- (missing) no recording: SoakReport was run with `-`, so every JFR-derived number below is absent,"
          + " not zero. The harness counters in `counters.properties` are unaffected.");
      return;
    }
    metrics.put("events_total", events);
    metrics.put("chunks", chunks());
    out.println("- file: `" + recording + "` (" + bytes(Files.size(recording)) + ')');
    if (first == null) {
      out.println("- **no events**: the recording is readable and empty — treat every section below as missing,"
          + " not clean");
      return;
    }
    final var span = Duration.between(first, last);
    out.println("- events: " + events + " of " + eventCounts.size() + " types, " + first + " to " + last
        + " (" + seconds(span) + "), " + chunks() + " chunk(s)"
        + (chunkStarts.isEmpty() ? " (estimated: no jdk.JVMInformation events)" : ""));
    if (runStart != null && runEnd != null) {
      // The recording ends at its dump, which for the final dump is inside DRAIN by design, so
      // the window that can be covered ends there too: coverage answers "did the ring roll", and
      // a recording whose first event precedes the run's first phase row did not.
      final var windowEnd = last.isBefore(runEnd) ? last : runEnd;
      final var coveredFrom = first.isAfter(runStart) ? first : runStart;
      final var window = Duration.between(runStart, windowEnd);
      final var covered = Duration.between(coveredFrom, windowEnd);
      final double coverage = window.toMillis() <= 0 ? 0d
          : Math.max(0d, Math.min(100d, covered.toMillis() * 100d / window.toMillis()));
      metrics.putRate("ring_coverage_pct", coverage, 1);
      out.println("- ring coverage: " + String.format(Locale.ROOT, "%.1f", coverage) + " % of the run up to the dump ("
          + seconds(window) + " from " + runStart + " to " + windowEnd
          + (runEnd.isAfter(windowEnd) ? "; the run went on to " + runEnd + " and `soak-exit.jfr` holds the rest" : "")
          + (runWindowNote.isEmpty() ? "" : ", " + runWindowNote)
          + "). Under 100 % the ring rolled: counts below are of what SURVIVED in the ring, and only"
          + " `counters.properties` carries whole-run totals.");
    } else {
      out.println("- ring coverage: (missing) no `phases.tsv` window to measure against"
          + (runWindowNote.isEmpty() ? "" : " — " + runWindowNote));
    }
    metrics.put("method_filters_requested", filtersRequested.size());
    metrics.put("method_filters_resolved", filtersResolved());
    out.print("- method filters: " + filtersResolved() + " of " + filtersRequested.size() + " resolved");
    if (!methodTraceLogNote.isEmpty()) {
      out.print(" — " + methodTraceLogNote);
    } else {
      out.print(" (" + timingEntryLines + " `Timing entry added` line(s); one filter entry can instrument every"
          + " overload, so the count of lines is not the count of entries)");
    }
    out.println();
    if (!filtersUnresolved.isEmpty()) {
      out.println("  - **unresolved** (silently never instrumented — a typo produces no warning): `"
          + String.join("`, `", filtersUnresolved) + '`');
    }
    long withRoot = 0;
    final var types = new HashMap<String, long[]>();
    for (final var sample : oldSamples.values()) {
      if (sample.root() != null) {
        ++withRoot;
      }
      count(types, sample.type());
    }
    metrics.put("old_object_samples", oldSamples.size());
    metrics.put("old_object_samples_with_root", withRoot);
    metrics.put("old_object_top_types", topTypes(types, 3));
    out.println("- jdk.OldObjectSample: " + oldSamples.size() + " distinct object(s) from " + oldSampleEvents
        + " event(s), " + withRoot + " with a path to a GC root");
    if (oldSamples.isEmpty()) {
      out.println("  - **INCONCLUSIVE for retention**: zero samples means the sampler was starved, NOT that nothing"
          + " is retained. Measured yield on this JDK swung 0 / 10 / 1035 across GC and heap-size combinations on"
          + " one leak program, so a zero is a configuration result, not a clean bill of health.");
    } else if (withRoot == 0) {
      out.println("  - no rooted sample: the dump either ran without `path-to-gc-roots=true` or the best-effort,"
          + " time-budgeted root walk found none. Retention evidence rests on the slopes, not on this section.");
    }
    final double socketShare = events == 0 ? 0d : socketEvents * 100d / events;
    metrics.putRate("socket_event_share_pct", socketShare, 1);
    out.println("- socket events: " + socketEvents + " (" + String.format(Locale.ROOT, "%.1f", socketShare)
        + " % of all events) — the .jfc throttle, not the threshold, is what caps this");
    final long thrown = exceptionsThrown + errorsThrown;
    // jdk.ExceptionStatistics is an unthrottled running total from JVM start, so it legitimately
    // exceeds the recorded throws by the handful created before the recording was enabled. The
    // flag needs a deficit big enough to mean the 100/s throttle actually bit.
    final long deficit = exceptionStatisticsMax - thrown;
    final boolean saturated = deficit > Math.max(MIN_THROTTLE_DEFICIT, exceptionStatisticsMax / 20L);
    metrics.put("exception_throttle_saturated", saturated);
    out.println("- exception throttle: jdk.ExceptionStatistics counted " + exceptionStatisticsMax
        + " throwable(s) created (unthrottled, and counted from JVM start), jdk.JavaExceptionThrow +"
        + " jdk.JavaErrorThrow recorded " + thrown + " -> "
        + (saturated ? "**saturated**: the 100/s throttle is biting, so the recorded throws are a sample and"
        + " not a census" : "not saturated (deficit " + deficit + ", which is within what VM start-up creates"
        + " before the recording begins)"));
    out.println();
    out.println("| event type | count |");
    out.println("|---|---:|");
    final var sorted = new ArrayList<>(eventCounts.entrySet());
    sorted.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
    for (final var entry : sorted.subList(0, Math.min(TOP_EVENT_TYPES, sorted.size()))) {
      out.println("| " + entry.getKey() + " | " + entry.getValue()[0] + " |");
    }
  }

  /// Section (f): the ring's view of latency. The harness histograms in `counters.properties` are
  /// the measurement with the full denominator; these are the tails, which is what a recording is
  /// good for — plus the W4 comparison, which is the whole point of the OVERLAP fan-out.
  void writeLatency(final PrintWriter out, final Metrics metrics) {
    out.println();
    out.println("## (f2) Latency tails (ring)");
    out.println();
    if (!present) {
      out.println("- (missing) no recording");
      return;
    }
    if (rpcAll.count == 0 && notificationsByConsumer.isEmpty()) {
      out.println("- (missing) no `sava.soak.RpcCall` or `sava.soak.NotificationDelivered` events in the ring"
          + " — both carry a threshold (25 ms / 5 ms), so an absence here means nothing was slow, not that"
          + " nothing ran; the counts are in `counters.properties`");
    }
    if (rpcAll.count > 0) {
      out.println("`sava.soak.RpcCall` (recorded only over its 25 ms threshold): " + rpcAll.count + " event(s), p50 "
          + millis(rpcAll.p(0.5)) + " ms, p99 " + millis(rpcAll.p(0.99)) + " ms, max " + millis(rpcAll.max) + " ms");
      out.println();
      out.println("| method | over threshold | p50 ms | p99 ms | max ms |");
      out.println("|---|---:|---:|---:|---:|");
      final var byMethod = new ArrayList<>(rpcByMethod.entrySet());
      byMethod.sort((a, b) -> Long.compare(b.getValue().max, a.getValue().max));
      for (final var entry : byMethod.subList(0, Math.min(TOP_ROWS, byMethod.size()))) {
        final var dist = entry.getValue();
        out.println("| " + entry.getKey() + " | " + dist.count + " | " + millis(dist.p(0.5)) + " | "
            + millis(dist.p(0.99)) + " | " + millis(dist.max) + " |");
      }
      out.println();
      out.println("- outcomes over threshold: " + joinCounts(rpcByOutcome, 10));
      writeTail(out, "slowest RPC calls", List.of("ms", "method", "outcome", "status", "bytes", "route", "error", "at"), rpcTail);
    }
    if (!notificationsByConsumer.isEmpty()) {
      out.println();
      out.println("`sava.soak.NotificationDelivered` (over its 5 ms threshold), by consumer kind:");
      out.println();
      out.println("| consumer | over threshold | p50 ms | p99 ms | max ms |");
      out.println("|---|---:|---:|---:|---:|");
      final var byConsumer = new ArrayList<>(notificationsByConsumer.entrySet());
      byConsumer.sort((a, b) -> Long.compare(b.getValue().count, a.getValue().count));
      for (final var entry : byConsumer) {
        final var dist = entry.getValue();
        out.println("| " + entry.getKey() + " | " + dist.count + " | " + millis(dist.p(0.5)) + " | "
            + millis(dist.p(0.99)) + " | " + millis(dist.max) + " |");
      }
      writeTail(out, "slowest deliveries", List.of("ms", "engine", "channel", "consumer", "seq", "threw", "slowByDesign", "at"), notificationTail);
    }
    out.println();
    final long overlapMarkers = phases.stream().filter(w -> isOverlap(w.name())).count();
    final long overlapWindows = phases.stream().filter(w -> isOverlap(w.name()) && w.measurable()).count();
    final long syntheticWindows = phases.stream().filter(w -> isOverlap(w.name()) && w.measurable() && w.synthetic()).count();
    out.println("**W4 — delivery inside vs outside the OVERLAP fan-out** (" + overlapWindows
        + " OVERLAP window(s) from " + overlapMarkers + " `sava.soak.Phase` event(s)). The fan-out puts eight large"
        + " `getProgramAccounts` parses on `ForkJoinPool.commonPool` — the same pool sava's response-deadline"
        + " timers use — while the peer bursts notifications, so a difference here is contention between"
        + " parsing and delivery, not a slow peer.");
    if (syntheticWindows > 0) {
      out.println();
      out.println("- the window is the REPORT's, not the run's: `Phases.marker` commits the OVERLAP event with no"
          + " `begin()`/`end()`, so the run records the fan-out trigger as a zero-width instant and "
          + syntheticWindows + " of these window(s) are that instant widened to " + seconds(OVERLAP_MARKER_WINDOW)
          + ". \"Inside\" therefore means \"began within " + seconds(OVERLAP_MARKER_WINDOW) + " of a trigger\" —"
          + " the fan-out's own W4-C bound (32 `getSlot` probes at 2 x 500 ms plus the 5 s operation grace) —"
          + " and a large parse held open past it by a fault is counted outside.");
    }
    out.println();
    out.println("| window | deliveries over threshold | p50 ms | p99 ms | max ms |");
    out.println("|---|---:|---:|---:|---:|");
    out.println("| inside OVERLAP | " + notificationsInOverlap.count + " | " + millis(notificationsInOverlap.p(0.5))
        + " | " + millis(notificationsInOverlap.p(0.99)) + " | " + millis(notificationsInOverlap.max) + " |");
    out.println("| outside OVERLAP | " + notificationsOutsideOverlap.count + " | " + millis(notificationsOutsideOverlap.p(0.5))
        + " | " + millis(notificationsOutsideOverlap.p(0.99)) + " | " + millis(notificationsOutsideOverlap.max) + " |");
    if (overlapWindows == 0) {
      out.println();
      out.println("- (missing) no OVERLAP window: every delivery counts as outside, and the comparison is not"
          + " evidence of anything" + (overlapMarkers == 0
          ? " (the run recorded no `sava.soak.Phase` OVERLAP event at all)"
          : " (" + overlapMarkers + " OVERLAP event(s) were recorded, none of them a window a delivery could"
          + " fall inside — report it: an OVERLAP marker is supposed to be widened here)"));
    }
    if (!methodTimingLatest.isEmpty()) {
      writeMethodTiming(out);
    }
  }

  private void writeMethodTiming(final PrintWriter out) {
    out.println();
    out.println("`jdk.MethodTiming` — **cumulative** invocation totals. Rows are never summed; the per-interval"
        + " rate is the difference between consecutive rows for one method.");
    out.println();
    out.println("| method | latest invocations | consecutive diffs | avg | max |");
    out.println("|---|---:|---|---:|---:|");
    final var sorted = new ArrayList<>(methodTimingLatest.entrySet());
    sorted.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
    for (final var entry : sorted.subList(0, Math.min(TOP_ROWS, sorted.size()))) {
      final var rows = methodTiming.getOrDefault(entry.getKey(), List.of());
      final var diffs = new StringBuilder();
      for (int i = 1; i < rows.size() && i <= 8; ++i) {
        diffs.append(diffs.isEmpty() ? "" : ", ").append(rows.get(i)[1] - rows.get(i - 1)[1]);
      }
      if (rows.size() > 9) {
        diffs.append(", … (").append(rows.size() - 1).append(" intervals)");
      }
      out.println("| `" + entry.getKey() + "` | " + entry.getValue()[0] + " | " + (diffs.isEmpty() ? "(one row)" : diffs)
          + " | " + millis(entry.getValue()[1]) + " ms | " + millis(entry.getValue()[2]) + " ms |");
    }
  }

  /// Section (g), ring half: what the recording can say about retention and thread/handle
  /// lifetimes. The whole-run series live in `client.csv`, `rss.csv` and `nmt.csv` and are written
  /// by [SoakReport]; these numbers cross-check them over whatever window the ring still holds.
  void writeResources(final PrintWriter out, final Metrics metrics) {
    out.println();
    out.println("## (g2) Resources (ring)");
    out.println();
    if (!present) {
      out.println("- (missing) no recording");
      return;
    }
    metrics.put("submit_failed", submitFailed);
    metrics.put("pinned", pinned);
    metrics.put("errors_thrown", errorsThrown);
    metrics.put("thread_starts", threadStarts);
    metrics.put("thread_ends", threadEnds);
    metrics.put("vt_started", vtStarted);
    metrics.put("vt_ended", vtEnded);
    metrics.put("execution_samples", executionSamples);
    metrics.put("gc_count", gcPauses.size());
    long pinnedMax = 0;
    for (final var pin : pins.values()) {
      pinnedMax = Math.max(pinnedMax, pin.maxNanos);
    }
    metrics.put("pinned_max_ms", millis(pinnedMax));
    final long gcMax = gcPauses.isEmpty() ? 0L : Collections.max(gcPauses);
    metrics.put("gc_pause_max_ms", millis(gcMax));

    out.println("- platform threads: " + threadStarts + " start(s), " + threadEnds + " end(s), net "
        + (threadStarts - threadEnds) + "; started from: " + joinCounts(spawners, TOP_SPAWNERS));
    out.println("- virtual threads: " + vtStarted + " started, " + vtEnded + " ended, net " + (vtStarted - vtEnded)
        + " (both events are off by default and are enabled in the soak .jfc precisely so they can be balanced)");
    out.println("- jdk.VirtualThreadPinned: " + pinned + " event(s), longest " + millis(pinnedMax)
        + " ms. On this JDK monitors no longer pin, so any event is a genuine native/VM-frame finding, not"
        + " JEP-491 noise.");
    out.println("- jdk.VirtualThreadSubmitFailed: " + submitFailed
        + (submitFailures.isEmpty() ? "" : " — " + String.join("; ", submitFailures)));
    out.println("- jdk.JavaErrorThrow: " + errorsThrown + (errorTypes.isEmpty() ? "" : " — " + joinCounts(errorTypes, 5))
        + " (plus " + errorsLinkageProbe + " raised and caught inside java.lang.invoke's own LambdaForm-holder probing,"
        + " which the gate does not count)"
        + "; jdk.JavaExceptionThrow: " + exceptionsThrown
        + (exceptionTypes.isEmpty() ? "" : " — " + joinCounts(exceptionTypes, 8)));
    out.println("- GC: " + gcPauses.size() + " collection(s), sum-of-pauses max " + millis(gcMax) + " ms, p99 "
        + millis(TsvReader.percentile(gcPauses, 0.99d)) + " ms, longest single pause " + millis(gcLongestPause)
        + " ms" + (gcNames.isEmpty() ? "" : "; collectors " + joinCounts(gcNames, 3) + "; causes " + joinCounts(gcCauses, 5)));
    if (heapAfterGcSamples == 0) {
      out.println("- after-GC heap (ring cross-check): (missing) no jdk.GCHeapSummary `After GC` events");
    } else {
      out.println("- after-GC heap (ring cross-check, " + heapAfterGcSamples + " sample(s) from " + heapFirstAt
          + " to " + heapLastAt + "): first " + bytes(heapFirstUsed) + ", last " + bytes(heapLastUsed) + ", min "
          + bytes(heapMinUsed == Long.MAX_VALUE ? 0 : heapMinUsed) + ", max " + bytes(heapMaxUsed)
          + " — the whole-run series is `client.csv`'s `heap_after_gc`, and the slope is fitted there");
    }
    writePinned(out);
    writeNmt(out);
    writeSockets(out);
    writeExecution(out, metrics);
    writeOldObjects(out);
  }

  private void writePinned(final PrintWriter out) {
    if (pins.isEmpty()) {
      return;
    }
    final var sorted = new ArrayList<>(pins.values());
    sorted.sort((a, b) -> Long.compare(b.count, a.count));
    int rank = 0;
    for (final var pin : sorted.subList(0, Math.min(TOP_PINNED, sorted.size()))) {
      out.println();
      out.println("Pinned " + (++rank) + ". " + pin.count + " event(s), max " + millis(pin.maxNanos) + " ms, total "
          + millis(pin.sumNanos) + " ms" + (pin.detail == null ? "" : " (" + pin.detail + ')'));
      out.println();
      out.println("```");
      for (final var frame : pin.frames) {
        out.println(frame);
      }
      if (pin.frames.isEmpty()) {
        out.println("(no stack)");
      }
      out.println("```");
    }
  }

  private void writeNmt(final PrintWriter out) {
    out.println();
    if (nmtTotalSamples == 0) {
      out.println("- jdk.NativeMemoryUsageTotal: (missing) no events — is `-XX:NativeMemoryTracking=summary` on?"
          + " Without it both NMT events record nothing at all.");
      return;
    }
    out.println("- jdk.NativeMemoryUsageTotal (" + nmtTotalSamples + " sample(s) over the ring): committed "
        + bytes(nmtFirstCommitted) + " -> " + bytes(nmtLastCommitted) + " (" + signed(nmtLastCommitted - nmtFirstCommitted)
        + "), reserved last " + bytes(nmtLastReserved) + ". This total INCLUDES `Tracing` (the recorder's own"
        + " buffers) and `Arena Chunk` (transient JVM arenas); the gated series the verdict fits is `nmt.csv`.");
    final var sorted = new ArrayList<>(nmtTypes.entrySet());
    sorted.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
    out.println();
    out.println("| memory tag | committed first | committed last | delta |");
    out.println("|---|---:|---:|---:|");
    for (final var entry : sorted.subList(0, Math.min(TOP_NMT_TYPES, sorted.size()))) {
      final var values = entry.getValue();
      out.println("| " + entry.getKey() + " | " + bytes(values[0]) + " | " + bytes(values[1]) + " | "
          + signed(values[1] - values[0]) + " |");
    }
  }

  private void writeSockets(final PrintWriter out) {
    out.println();
    out.println("Transport (jdk.SocketRead / jdk.SocketWrite). Reads are attributed to the `HttpClient`"
        + " SelectorManager and writes to the CALLING thread, never to the caller of the RPC, so these are"
        + " transport shape — per-request bytes come from the harness counters.");
    out.println();
    out.println("| direction | peer | events | bytes | max ms |");
    out.println("|---|---|---:|---:|---:|");
    writeHostRows(out, "read", socketReadByHost);
    writeHostRows(out, "write", socketWriteByHost);
    out.println();
    out.println("| direction | thread | events | bytes | max ms |");
    out.println("|---|---|---:|---:|---:|");
    writeHostRows(out, "read", socketReadByThread);
    writeHostRows(out, "write", socketWriteByThread);
    writeTail(out, "socket operations at or over " + SLOW_SOCKET_MS + " ms",
        List.of("direction", "peer", "thread", "bytes", "ms", "at"), socketTail);
  }

  private void writeHostRows(final PrintWriter out, final String direction, final Map<String, long[]> by) {
    final var sorted = new ArrayList<>(by.entrySet());
    sorted.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
    for (final var entry : sorted.subList(0, Math.min(TOP_ROWS, sorted.size()))) {
      final var values = entry.getValue();
      out.println("| " + direction + " | " + entry.getKey() + " | " + values[0] + " | " + values[1] + " | "
          + millis(values[2]) + " |");
    }
  }

  private void writeExecution(final PrintWriter out, final Metrics metrics) {
    out.println();
    out.println("Hottest stacks (jdk.ExecutionSample, read through `sampledThread`): " + executionSamples
        + " sample(s) over " + threadSamples.size() + " thread(s)"
        + (untrackedThreadSamples > 0 ? " (+" + untrackedThreadSamples + " untracked)" : ""));
    final var threads = new ArrayList<>(threadSamples.entrySet());
    threads.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
    if (!threads.isEmpty() && executionSamples > 0) {
      final var top = threads.getFirst();
      final double share = top.getValue()[0] / (double) executionSamples;
      final boolean dominates = share >= DOMINANT_SHARE && executionSamples >= DOMINANT_MIN_SAMPLES;
      metrics.put("dominant_thread", threadLabels.get(top.getKey()));
      metrics.put("dominant_thread_share_pct", Math.round(share * 100));
      metrics.put("dominant_thread_flag", dominates);
      final var sb = new StringBuilder();
      for (final var entry : threads.subList(0, Math.min(TOP_THREADS, threads.size()))) {
        sb.append(sb.isEmpty() ? "" : ", ").append(threadLabels.get(entry.getKey())).append(' ')
            .append(entry.getValue()[0]).append(" (").append(Math.round(entry.getValue()[0] * 100d / executionSamples))
            .append(" %)");
      }
      out.println("- by thread: " + sb);
      out.println("- " + (dominates
          ? "**one thread dominates**: " + threadLabels.get(top.getKey()) + " holds " + Math.round(share * 100)
          + " % of the samples — the wedged-thread signature"
          : "no single thread dominates (top share " + Math.round(share * 100) + " %; the signature is >= "
          + Math.round(DOMINANT_SHARE * 100) + " % over >= " + DOMINANT_MIN_SAMPLES + " samples)"));
    }
    final var sorted = new ArrayList<>(hot.entrySet());
    sorted.sort((a, b) -> Long.compare(b.getValue().samples, a.getValue().samples));
    if (sorted.isEmpty()) {
      return;
    }
    out.println();
    out.println("| samples | share | top frame | most common stack beneath it |");
    out.println("|---:|---:|---|---|");
    for (final var entry : sorted.subList(0, Math.min(TOP_STACKS, sorted.size()))) {
      final var entryHot = entry.getValue();
      String best = null;
      long bestCount = -1;
      for (final var stack : entryHot.stacks.entrySet()) {
        if (stack.getValue()[0] > bestCount) {
          bestCount = stack.getValue()[0];
          best = stack.getKey();
        }
      }
      final var frames = best == null ? List.<String>of() : entryHot.frames.get(best);
      final var beneath = frames.size() > 1 ? String.join(" < ", frames.subList(1, frames.size())) : "";
      out.println("| " + entryHot.samples + " | " + Math.round(entryHot.samples * 100d / Math.max(1, executionSamples))
          + " % | `" + entry.getKey() + "` | " + (beneath.isEmpty() ? "" : "`" + beneath + '`') + " |");
    }
  }

  private void writeOldObjects(final PrintWriter out) {
    if (oldSamples.isEmpty()) {
      return;
    }
    final var sites = new HashMap<String, Site>();
    for (final var sample : oldSamples.values()) {
      final var top = sample.frames().isEmpty() ? "(no stack)" : sample.frames().getFirst();
      final var site = sites.computeIfAbsent(sample.type() + '\n' + top, ignored -> new Site());
      ++site.samples;
      site.bytes += sample.bytes();
      if (site.frames == null || (site.root == null && sample.root() != null)) {
        site.frames = sample.frames();
      }
      if (site.root == null && sample.root() != null) {
        site.root = sample.root();
        site.path = sample.path();
      }
    }
    final var sorted = new ArrayList<>(sites.entrySet());
    // rooted first: a site the dump proved reachable is the evidence, the rest is context
    sorted.sort((a, b) -> {
      final int rooted = Boolean.compare(b.getValue().root != null, a.getValue().root != null);
      if (rooted != 0) {
        return rooted;
      }
      final int samples = Long.compare(b.getValue().samples, a.getValue().samples);
      return samples != 0 ? samples : Long.compare(b.getValue().bytes, a.getValue().bytes);
    });
    out.println();
    out.println("Retained allocation sites (jdk.OldObjectSample, top " + TOP_SITES + " of " + sites.size() + "):");
    int rank = 0;
    for (final var entry : sorted.subList(0, Math.min(TOP_SITES, sorted.size()))) {
      final var site = entry.getValue();
      final var type = entry.getKey().substring(0, entry.getKey().indexOf('\n'));
      out.println();
      out.println((++rank) + ". `" + type + "`: " + site.samples + " sample(s), " + bytes(site.bytes) + " sampled, "
          + (site.root != null ? "reachable from a GC root" : "no path to a GC root recorded"));
      out.println();
      out.println("```");
      for (final var frame : site.frames == null ? List.<String>of() : site.frames) {
        out.println(frame);
      }
      if (site.root != null) {
        out.println("root: " + site.root);
        if (site.path != null && !site.path.isEmpty()) {
          out.println("path: " + String.join(" <- ", site.path));
        }
      }
      out.println("```");
    }
  }

  /// Section (i): where work stopped. Every instrument here has a blind spot that the section
  /// states, because a reader who assumes otherwise reads an empty table as "nothing was stuck".
  void writeStalls(final PrintWriter out, final Metrics metrics) {
    out.println();
    out.println("## (i) Stalls");
    out.println();
    if (!present) {
      out.println("- (missing) no recording");
      metrics.put("thread_dump_stuck_threads", 0L);
      return;
    }
    final var stuck = stuckThreads();
    metrics.put("thread_dump_stuck_threads", stuck.size());
    out.println("- jdk.ThreadDump: " + threadDumps.size() + " dump(s)"
        + (threadDumpsDropped > 0 ? " (+" + threadDumpsDropped + " past the " + MAX_DUMPS + " kept)" : "")
        + ", **platform threads only** — a stuck virtual thread cannot appear here; "
        + stuck.size() + " thread(s) held the same non-idle frame across " + STUCK_DUMPS
        + " consecutive dumps without waking");
    out.println("  - \"without waking\" is the second half of the signature and it is what keeps a poll loop"
        + " out of this table: a WAITING thread whose `cpu=` advanced between two dumps left its site and"
        + " came back, so the site is the loop idling. A RUNNABLE thread is never acquitted that way — a"
        + " spin loop burns CPU because it is wedged — and a dump with no `cpu=` acquits nothing. The `cpu"
        + " ms` column is the advance over the run below, so `0.00` beside a park is a thread that never ran.");
    if (!stuck.isEmpty()) {
      out.println();
      out.println("| thread | frame | consecutive dumps | cpu ms over the run |");
      out.println("|---|---|---:|---:|");
      for (final var row : stuck) {
        out.println("| " + row[0] + " | `" + row[1] + "` | " + row[2] + " | " + row[3] + " |");
      }
    }
    out.println();
    out.println("- jdk.ThreadPark >= " + SLOW_PARK_MS + " ms: " + parkEvents + " event(s) over "
        + parks.size() + " frame(s). **Platform threads only**: `LockSupport.park` branches to"
        + " `parkVirtualThread` for a virtual thread and the VM event brackets only the `Unsafe.park`"
        + " branch, so a parked virtual thread is invisible here — use `jdk.MethodTiming` on the suspect"
        + " method instead." + (parkVirtualEvents > 0 ? " (" + parkVirtualEvents + " event(s) DID carry a"
        + " virtual event thread, which contradicts that measurement — report it.)" : ""));
    writeStackTable(out, parks, "park frame", "thread");
    out.println();
    out.println("- jdk.JavaMonitorEnter: " + monitorEvents + " event(s) over " + monitors.size()
        + " site(s). This one DOES fire for virtual threads, so it is how contention on sava's connection"
        + " lock and on `HttpClientImpl`'s per-client monitor shows up.");
    writeStackTable(out, monitors, "site", "monitor");
    out.println();
    if (methodTraces.isEmpty()) {
      out.println("- jdk.MethodTrace over " + SLOW_METHOD_TRACE_MS + " ms: none ("
          + methodTraceEvents + " event(s) recorded in total)");
    } else {
      out.println("- jdk.MethodTrace over " + SLOW_METHOD_TRACE_MS + " ms, of " + methodTraceEvents + " recorded:");
      out.println();
      out.println("| method | over threshold | p99 ms | max ms |");
      out.println("|---|---:|---:|---:|");
      final var sorted = new ArrayList<>(methodTraces.entrySet());
      sorted.sort((a, b) -> Long.compare(b.getValue().max, a.getValue().max));
      for (final var entry : sorted.subList(0, Math.min(TOP_ROWS, sorted.size()))) {
        out.println("| `" + entry.getKey() + "` | " + entry.getValue().count + " | " + millis(entry.getValue().p(0.99))
            + " | " + millis(entry.getValue().max) + " |");
      }
    }
  }

  private void writeStackTable(final PrintWriter out, final Map<String, Stack> by, final String what, final String detail) {
    if (by.isEmpty()) {
      return;
    }
    final var sorted = new ArrayList<>(by.values());
    sorted.sort((a, b) -> Long.compare(b.maxNanos, a.maxNanos));
    out.println();
    out.println("| events | max ms | total ms | " + what + " | " + detail + " |");
    out.println("|---:|---:|---:|---|---|");
    for (final var entry : sorted.subList(0, Math.min(TOP_ROWS, sorted.size()))) {
      out.println("| " + entry.count + " | " + millis(entry.maxNanos) + " | " + millis(entry.sumNanos) + " | `"
          + (entry.frames.isEmpty() ? "(no stack)" : significantFrame(entry.frames)) + "` | "
          + (entry.detail == null ? "" : entry.detail) + " |");
    }
  }

  /// Section (e) cross-check: the ring's own view of the issue-52 events. The decision numbers
  /// come from `issue52/*.tsv` and live in `issue52.md`; these say whether the events agree with
  /// the files, which is the only way to notice a writer that stopped.
  void writeIssue52Events(final PrintWriter out) {
    out.println();
    if (!present) {
      out.println("- ring cross-check: (missing) no recording");
      return;
    }
    out.println("- ring cross-check: `sava.soak.Retirement` " + retirementEvents + " ("
        + joinCounts(retirementVerdicts, 8) + "), `sava.soak.ManagerClaim` " + claimEvents
        + (claimRoutes.isEmpty() ? "" : " (" + joinCounts(claimRoutes, 6) + ')')
        + ", `sava.soak.Misattribution` " + misattributionEvents
        + ". A ring that rolled holds fewer of these than the TSVs; a ring that covers the run and"
        + " disagrees with them is a writer defect, not a measurement.");
    for (final var example : misattributionExamples) {
      out.println("  - " + example);
    }
  }

  // ---------------------------------------------------------------- thread dumps

  /// Threads whose SIGNIFICANT frame (the first frame below the park/wait plumbing) is identical
  /// across [#STUCK_DUMPS] consecutive dumps, is not a known idle wait, and showed no sign of
  /// having run in between. Consecutive matters: a pool thread that is idle at three unrelated
  /// moments is not stuck, and a worker that never moved between three adjacent dumps is.
  ///
  /// The frame alone is not enough, and saying so is the whole of [#progressed]. sava's four
  /// check loops park inside `SolanaJsonRpcWebsocket.prepareCheckCycleDelivery` for their entire
  /// life, waking every `subscriptionAndPingCheckDelay`; the frame never changes and the thread is
  /// perfectly healthy. Reporting those is not a harmless false positive — `soak.sh` turns a
  /// non-zero count into a FAIL whose reason names a sava production method as a stall, which a
  /// reader cannot tell from a real check-loop wedge.
  List<String[]> stuckThreads() {
    final var rows = new ArrayList<String[]>();
    if (threadDumps.size() < STUCK_DUMPS) {
      return rows;
    }
    threadDumps.sort((a, b) -> ((Instant) a[0]).compareTo((Instant) b[0]));
    // thread -> its state in each dump, aligned by dump index; null where the thread was absent
    // from that dump, which breaks a run exactly as a changed frame does
    final var byThread = new LinkedHashMap<String, ThreadState[]>();
    for (int dump = 0; dump < threadDumps.size(); ++dump) {
      @SuppressWarnings("unchecked") final var states = (Map<String, ThreadState>) threadDumps.get(dump)[1];
      for (final var entry : states.entrySet()) {
        if (byThread.size() >= MAX_KEYS && !byThread.containsKey(entry.getKey())) {
          continue;
        }
        byThread.computeIfAbsent(entry.getKey(), ignored -> new ThreadState[threadDumps.size()])[dump] = entry.getValue();
      }
    }
    for (final var entry : byThread.entrySet()) {
      final var thread = entry.getKey();
      final var states = entry.getValue();
      String current = null;
      ThreadState anchor = null;
      int run = 0;
      String longestFrame = null;
      int longest = 0;
      double longestCpu = 0d;
      for (final var state : states) {
        if (state == null || isIdle(thread, state.frame())) {
          current = null;
          anchor = null;
          run = 0;
          continue;
        }
        if (!state.frame().equals(current)) {
          current = state.frame();
          anchor = state;
          run = 1;
        } else if (progressed(anchor, state)) {
          // It left this site and came back between the two dumps: the site is a poll loop idling,
          // not a thread that stopped. The run restarts HERE rather than at zero, so a wedge that
          // sets in after real work still needs only STUCK_DUMPS dumps to be seen.
          anchor = state;
          run = 1;
        } else {
          ++run;
        }
        if (run > longest) {
          longest = run;
          longestFrame = current;
          longestCpu = anchor == null || anchor.cpuMillis() < 0d || state.cpuMillis() < 0d
              ? -1d : state.cpuMillis() - anchor.cpuMillis();
        }
      }
      if (longest >= STUCK_DUMPS) {
        rows.add(new String[]{thread, longestFrame, Integer.toString(longest),
            longestCpu < 0d ? "n/a" : String.format(Locale.ROOT, "%.2f", longestCpu)});
      }
    }
    rows.sort((a, b) -> Integer.compare(Integer.parseInt(b[2]), Integer.parseInt(a[2])));
    return rows;
  }

  /// Did the thread do work between the dump that anchored this run and `now`?
  ///
  /// Only a WAITING thread can be acquitted this way, and the asymmetry is deliberate. A parked
  /// thread burns no CPU while it is parked, so a `cpu=` counter that advanced proves it woke, ran
  /// and parked at the same site again — that is a poll loop, and every one of this harness's
  /// false positives was one (sava's check loops advanced 21.31 -> 28.65 -> 31.77 ms across three
  /// dumps while parked at the same frame). A RUNNABLE thread is never acquitted here, because a
  /// spin loop burns CPU precisely BECAUSE it is wedged; for those the frame-repetition signature
  /// stands on its own.
  ///
  /// A dump whose header carried no `cpu=` field yields `-1` and acquits nothing, so a parse that
  /// degrades falls back to the old frame-only signature rather than to silence.
  private static boolean progressed(final ThreadState anchor, final ThreadState now) {
    return anchor != null && anchor.waiting() && now.waiting()
        && anchor.cpuMillis() >= 0d && now.cpuMillis() >= 0d
        && now.cpuMillis() - anchor.cpuMillis() >= MIN_CPU_PROGRESS_MILLIS;
  }

  /// One thread's entry in a `jdk.ThreadDump`: its significant frame plus the two progress signals
  /// the dump already carries and the detector used to throw away.
  ///
  /// `waiting` is true only when `java.lang.Thread.State:` named a wait (`WAITING`,
  /// `TIMED_WAITING`, `BLOCKED`); an absent or unrecognised state reads as RUNNABLE, which is the
  /// conservative side — a RUNNABLE thread is never acquitted by CPU. `cpuMillis` is `-1` when the
  /// header printed no `cpu=` field.
  record ThreadState(String frame, boolean waiting, double cpuMillis) {
  }

  /// `jdk.ThreadDump`'s `result` is the jstack-shaped text dump: a
  /// `"name" #id [tid] … cpu=21.31ms elapsed=59.95s …` header line per thread, then an indented
  /// `java.lang.Thread.State: TIMED_WAITING (parking)` line, then tab-indented
  /// `at pkg.Class.method(File.java:12)` frames.
  static Map<String, ThreadState> parseThreadDump(final String text) {
    final var threads = new LinkedHashMap<String, ThreadState>();
    if (text == null || text.isEmpty()) {
      return threads;
    }
    String thread = null;
    boolean waiting = false;
    double cpuMillis = -1d;
    final var stack = new ArrayList<String>();
    for (final var raw : text.split("\n")) {
      final var line = raw.strip();
      if (line.startsWith("\"")) {
        if (thread != null) {
          threads.put(thread, new ThreadState(significantFrame(stack), waiting, cpuMillis));
        }
        final int close = line.indexOf('"', 1);
        thread = close > 1 ? line.substring(1, close) : line;
        waiting = false;
        // past the closing quote, so a thread NAMED "cpu=..." cannot be read as a measurement
        cpuMillis = cpuMillis(close > 1 ? line.substring(close + 1) : "");
        stack.clear();
      } else if (line.startsWith("java.lang.Thread.State:")) {
        final var state = line.substring("java.lang.Thread.State:".length()).strip();
        waiting = state.startsWith("WAITING") || state.startsWith("TIMED_WAITING") || state.startsWith("BLOCKED");
      } else if (line.startsWith("at ")) {
        stack.add(frameName(line.substring(3)));
      }
    }
    if (thread != null) {
      threads.put(thread, new ThreadState(significantFrame(stack), waiting, cpuMillis));
    }
    return threads;
  }

  /// The header's `cpu=<n>ms` field, or `-1` when it is absent or unreadable. The unit is always
  /// `ms` in the JDK's own writer, so a value in any other unit is refused rather than guessed at.
  static double cpuMillis(final String header) {
    final int at = header.indexOf("cpu=");
    if (at < 0) {
      return -1d;
    }
    final int end = header.indexOf("ms", at);
    if (end < 0) {
      return -1d;
    }
    try {
      return Double.parseDouble(header.substring(at + 4, end).strip());
    } catch (final NumberFormatException e) {
      return -1d;
    }
  }

  /// `java.base@25.0.2/java.lang.Thread.sleep(Thread.java:509)` -> `java.lang.Thread.sleep`.
  static String frameName(final String frame) {
    var name = frame;
    final int module = name.indexOf('/');
    final int paren = name.indexOf('(');
    if (module > 0 && (paren < 0 || module < paren)) {
      name = name.substring(module + 1);
    }
    final int arguments = name.indexOf('(');
    return (arguments < 0 ? name : name.substring(0, arguments)).strip();
  }

  /// The first frame that is not park/wait plumbing. Every parked thread's top frame is
  /// `jdk.internal.misc.Unsafe.park`, so a signature taken from the literal top frame would put
  /// every idle and every stuck thread in one bucket and say nothing.
  static String significantFrame(final List<String> frames) {
    for (final var frame : frames) {
      final var name = frameName(frame);
      if (!isPlumbing(name)) {
        return name;
      }
    }
    return frames.isEmpty() ? "(no stack)" : frameName(frames.getFirst());
  }

  private static boolean isPlumbing(final String frame) {
    return frame.startsWith("jdk.internal.misc.Unsafe.park")
        || frame.startsWith("java.util.concurrent.locks.LockSupport.")
        || frame.startsWith("java.util.concurrent.locks.AbstractQueuedSynchronizer")
        // JDK 25 routes every AQS park through ForkJoinPool's blocker hooks
        || frame.startsWith("java.util.concurrent.ForkJoinPool.unmanagedBlock")
        || frame.startsWith("java.util.concurrent.ForkJoinPool.managedBlock")
        || frame.startsWith("java.lang.Object.wait")
        || frame.startsWith("java.lang.Thread.sleep")
        || frame.startsWith("java.lang.VirtualThread.")
        || frame.startsWith("jdk.internal.vm.Continuation.")
        || frame.startsWith("java.lang.Thread.yield")
        || frame.endsWith("(Native Method)");
  }

  /// Idle waits that are the JVM working correctly: a pool thread waiting for work, a selector
  /// waiting for readiness, a reference handler waiting for a reference. None of these is a stall
  /// and all of them repeat across every dump of a healthy run.
  static boolean isIdle(final String thread, final String frame) {
    final var name = thread == null ? "" : thread;
    // The harness's own pacing loops park at one site for the whole run by design (a driver
    // waiting for its next slot, the peer-log tail, the phase thread waiting on a stage); they
    // are the harness, not the subject, and they hold that frame across every dump of a healthy
    // run. The subject's threads (HttpClient-*, pool-N-thread-1, ForkJoinPool.*) stay in scope.
    // A harness driver parked in a bare JDK wait primitive is the same case wearing the JDK's
    // name: `soak-phase-1` waits out a whole stage on a CountDownLatch, and because AQS is
    // plumbing its significant frame is `java.util.concurrent.CountDownLatch.await`, not a soak
    // frame. Scoped to the harness's DRIVER threads on purpose, and never to a frame the subject
    // owns — see [#harnessDriver] and [#subjectFrame].
    if (harnessDriver(name) && !subjectFrame(frame)
        && (frame.startsWith("software.sava.rpc.soak.") || isWait(frame))) {
      return true;
    }
    if ("main".equals(name) && frame.contains("FutureTask.awaitDone")) {
      return true;
    }
    if (name.startsWith("Reference Handler") || name.startsWith("Finalizer") || name.startsWith("Signal Dispatcher")
        || name.startsWith("Notification Thread") || name.startsWith("Common-Cleaner") || name.startsWith("process reaper")
        || name.startsWith("Attach Listener") || name.startsWith("DestroyJavaVM") || name.startsWith("JFR ")
        || name.startsWith("G1 ") || name.startsWith("GC Thread") || name.startsWith("VM Thread")
        || name.startsWith("VM Periodic Task Thread") || name.startsWith("C1 CompilerThread")
        || name.startsWith("C2 CompilerThread") || name.startsWith("Service Thread")
        || name.startsWith("Monitor Deflation Thread") || name.startsWith("Sweeper thread")
        // a JVM-internal platform thread like the rest of this list: it blocks in
        // `VirtualThread.takeVirtualThreadListToUnblock` for the whole run and reports RUNNABLE,
        // and its significant frame is the JVM's own lambda, whose name the harness cannot own
        || name.startsWith("VirtualThread-unblocker")) {
      return true;
    }
    return frame.contains("ForkJoinPool.awaitWork")
        || frame.contains("ForkJoinPool.deactivate")
        || frame.contains("ForkJoinPool.scan")
        || frame.contains("ForkJoinPool.runWorker")
        || frame.contains("ForkJoinPool.managedBlock")
        || frame.contains("ForkJoinWorkerThread.run")
        || frame.contains("ThreadPoolExecutor.getTask")
        || frame.contains("DelayedWorkQueue.take")
        || frame.endsWith("Queue.take")
        || frame.endsWith("Queue.poll")
        || frame.contains("SynchronousQueue")
        || frame.contains("SelectorImpl.select")
        || frame.contains("SelectorImpl.doSelect")
        || frame.contains("KQueue.poll")
        || frame.contains("EPoll.wait")
        || frame.contains("sun.nio.ch.Net.poll")
        || frame.contains("ReferenceHandler.run")
        || frame.contains("CleanerImpl.run")
        || frame.contains("TimerThread.mainLoop")
        || frame.contains("DelayScheduler.loop")
        || frame.startsWith("jdk.jfr.internal.")
        || frame.contains("ScheduledThreadPoolExecutor");
    // No `ConditionObject.await` clause: AQS is plumbing, so that frame can never BE the
    // significant one, and the clause that used to sit here — aimed at the engine's check loop —
    // was unreachable. The check loop is acquitted where it should be, on evidence that it woke:
    // see `progressed`.
  }

  /// Is this one of the harness's own driver and plumbing threads?
  ///
  /// Enumerated name by name rather than taken from the `soak-` prefix, because the harness also
  /// NAMES threads it hands to the subject. `soak-hc-N` is the fixed pool `SoakContext` gives
  /// `HttpClient`, so sava's WebSocket listener and this harness's notification consumers run
  /// there; `soak-churn-injected` is the executor the W5-B2 seam hands to a
  /// `SolanaRpcWebsocket.Builder`. **The subject's threads are never exempt by name** — a consumer
  /// parked forever on `soak-hc-5` is precisely the stall the stall-callback control stages, and
  /// the prefix test classified it idle. Each prefix below is a creation site in this harness (the
  /// phase thread, the websocket and HTTP drivers, the timer and gauge samplers, the TSV writers,
  /// the peer-log tails, the shutdown and JFR-close hooks, the control injections, the self-test
  /// loop, the churn driver, and every thread of the peer process).
  private static final List<String> HARNESS_DRIVER_THREADS = List.of(
      "soak-phase", "soak-ws-", "soak-ws-peertail", "soak-http-", "soak-timer", "soak-gauge",
      "soak-tsv-", "soak-peer-log", "soak-shutdown", "soak-jfr-close", "soak-control-",
      "soak-selftest-loop", "soak-churn-engine", "soak-churn-client", "peer-");

  static boolean harnessDriver(final String thread) {
    for (final var prefix : HARNESS_DRIVER_THREADS) {
      if (thread.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /// Frames that make a park a wedge whoever named the thread.
  ///
  /// `significantFrame` has already stepped below the park/wait primitive, so the frame handed
  /// here is the one that OWNS the wait — and when that owner is sava's own client code, or this
  /// harness's notification consumer running on sava's callback thread, no thread name acquits it.
  /// Without this a consumer parked inside `ConsumerFactory` on a harness-named thread would be
  /// exempted by the `software.sava.rpc.soak.` clause below, which exists for the pacing loops.
  static boolean subjectFrame(final String frame) {
    return frame.startsWith("software.sava.rpc.json.")
        || frame.startsWith("software.sava.rpc.soak.ws.ConsumerFactory");
  }

  /// JDK wait primitives that survive `significantFrame` because the plumbing beneath them was
  /// stripped. A thread sitting in one of these is waiting for something another thread owes it,
  /// which says nothing on its own — only [#isIdle]'s caller scoping makes it an acquittal.
  private static boolean isWait(final String frame) {
    return frame.startsWith("java.util.concurrent.CountDownLatch.await")
        || frame.startsWith("java.util.concurrent.CyclicBarrier.")
        || frame.startsWith("java.util.concurrent.Semaphore.acquire")
        || frame.startsWith("java.util.concurrent.Exchanger.")
        || frame.startsWith("java.util.concurrent.FutureTask.awaitDone")
        // the ForkJoinPool-managed block behind CompletableFuture.get(), named frame by frame
        // rather than by prefix: `CompletableFuture.uniApply` and its siblings are work, not waits
        || frame.startsWith("java.util.concurrent.CompletableFuture$Signaller.block")
        || frame.startsWith("java.util.concurrent.CompletableFuture.waitingGet")
        || frame.startsWith("java.util.concurrent.CompletableFuture.timedGet")
        || frame.contains("ConditionObject.await");
  }

  // ---------------------------------------------------------------- helpers

  /// The tail is the point, so the rows arrive from [Tail] already ordered by the duration they
  /// were admitted on. The denominator is everything that passed the threshold, and when the cap
  /// bound the heading says how many of those rows the table can no longer see — a tail that
  /// silently drops the stall it exists to show is worse than one that admits the drop.
  private void writeTail(final PrintWriter out, final String what, final List<String> header, final Tail tail) {
    if (tail.isEmpty()) {
      return;
    }
    final var sorted = tail.rows();
    out.println();
    out.println("Tail — " + what + " (top " + Math.min(TOP_ROWS, sorted.size()) + " of " + tail.admitted
        + (tail.evicted > 0 ? "; only the worst " + sorted.size() + " were retained, " + tail.evicted
        + " were not" : "") + "):");
    out.println();
    out.println("| " + String.join(" | ", header) + " |");
    out.println("|" + "---|".repeat(header.size()));
    for (final var row : sorted.subList(0, Math.min(TOP_ROWS, sorted.size()))) {
      final var sb = new StringBuilder("|");
      for (final var cell : row) {
        sb.append(' ').append(cell == null ? "" : cell).append(" |");
      }
      out.println(sb);
    }
  }

  private static void stack(final Map<String, Stack> by, final String key, final List<String> frames,
                            final String detail, final long nanos) {
    var entry = by.get(key);
    if (entry == null) {
      if (by.size() >= MAX_KEYS) {
        return;
      }
      entry = new Stack();
      entry.frames = frames;
      entry.detail = detail;
      by.put(key, entry);
    }
    ++entry.count;
    entry.sumNanos += nanos;
    entry.maxNanos = Math.max(entry.maxNanos, nanos);
  }

  private static void add(final Map<String, long[]> by, final String key, final long bytes, final long nanos) {
    final var values = by.get(key);
    if (values != null) {
      ++values[0];
      values[1] += bytes;
      values[2] = Math.max(values[2], nanos);
    } else if (by.size() < MAX_KEYS) {
      by.put(key, new long[]{1L, bytes, nanos});
    }
  }

  private static String hostLabel(final RecordedEvent event) {
    final var host = string(event, "host");
    final var address = string(event, "address");
    final var port = event.hasField("port") ? Integer.toString(event.getInt("port")) : "?";
    final var where = host == null || host.isBlank() ? address : host;
    return (where == null ? "?" : where) + ':' + port;
  }

  private static String threadName(final RecordedThread thread) {
    return thread == null ? "(no thread)" : threadLabel(thread);
  }

  private static String topTypes(final Map<String, long[]> types, final int limit) {
    if (types.isEmpty()) {
      return "n/a";
    }
    final var sorted = new ArrayList<>(types.entrySet());
    sorted.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
    final var sb = new StringBuilder();
    for (final var entry : sorted.subList(0, Math.min(limit, sorted.size()))) {
      sb.append(sb.isEmpty() ? "" : ";").append(entry.getKey()).append('=').append(entry.getValue()[0]);
    }
    return sb.toString();
  }

  private static long nanosOf(final RecordedEvent event, final String field) {
    final var duration = event.getDuration(field);
    // MethodTiming documents Long.MIN_VALUE for "clock resolution too low"; it is not a duration
    return duration == null || duration.isNegative() ? 0L : duration.toNanos();
  }

  private static String join(final String left, final String right) {
    if (left == null) {
      return right;
    }
    return right == null ? left : left + ", " + right;
  }

  static RecordedObject value(final RecordedObject holder, final String field) {
    if (holder == null || !holder.hasField(field)) {
      return null;
    }
    final var value = holder.getValue(field);
    return value instanceof RecordedObject object ? object : null;
  }

  static String string(final RecordedObject holder, final String field) {
    if (holder == null || !holder.hasField(field)) {
      return null;
    }
    final Object value = holder.getValue(field);
    return value == null ? null : value.toString();
  }

  static String classOf(final RecordedEvent event, final String field) {
    if (!event.hasField(field)) {
      return "?";
    }
    final Object value = event.getValue(field);
    return value instanceof RecordedClass type ? className(type) : "?";
  }

  static String methodLabel(final Object method, final RecordedStackTrace fallback) {
    if (method instanceof RecordedMethod recorded) {
      final var type = recorded.getType() == null ? "?" : recorded.getType().getName();
      return type + '.' + recorded.getName();
    }
    final var frames = frames(fallback);
    return frames.isEmpty() ? "?" : frames.getFirst();
  }

  static String rootLine(final RecordedObject root) {
    final var description = string(root, "description");
    final var system = string(root, "system");
    final var type = string(root, "type");
    return (system == null ? "?" : system) + " / " + (type == null ? "?" : type)
        + (description == null || description.equals("N/A") ? "" : " (" + description + ')');
  }

  /// The referrer chain from the sampled object towards the root: `holder.field` or
  /// `holder[index]` per hop, with a `(skip n)` where JFR elided hops.
  static List<String> referrerPath(final RecordedObject object) {
    final var path = new ArrayList<String>();
    var reference = value(object, "referrer");
    for (int hop = 0; reference != null && hop < MAX_REFERRER_HOPS; ++hop) {
      final var holder = value(reference, "object");
      final var field = value(reference, "field");
      final var array = value(reference, "array");
      final int skip = reference.hasField("skip") ? reference.getInt("skip") : 0;
      final var holderType = holder == null ? "?" : className(holder.getClass("type"));
      final var via = field != null ? "." + string(field, "name")
          : array != null ? "[" + array.getInt("index") + " of " + array.getInt("size") + ']'
          : "";
      path.add(holderType + via + (skip > 0 ? " (skip " + skip + ')' : ""));
      reference = value(holder, "referrer");
    }
    return path;
  }

  /// `[Ljava.lang.String;` as `java.lang.String[]`, `[B` as `byte[]`.
  /// See `JfrCounters.linkageProbe`: the first frame below the Throwable constructors is in
  /// `java.lang.invoke`, so the error is the JDK's own member-resolution miss, caught by it.
  static boolean linkageProbe(final RecordedStackTrace stackTrace) {
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

  static String className(final RecordedClass type) {
    if (type == null) {
      return "?";
    }
    final var name = type.getName();
    int dimensions = 0;
    while (dimensions < name.length() && name.charAt(dimensions) == '[') {
      ++dimensions;
    }
    if (dimensions == 0) {
      return name;
    }
    final var element = name.substring(dimensions);
    final var base = switch (element) {
      case "B" -> "byte";
      case "C" -> "char";
      case "D" -> "double";
      case "F" -> "float";
      case "I" -> "int";
      case "J" -> "long";
      case "S" -> "short";
      case "Z" -> "boolean";
      default -> element.startsWith("L") && element.endsWith(";") ? element.substring(1, element.length() - 1) : element;
    };
    return base + "[]".repeat(dimensions);
  }

  static List<String> frames(final RecordedStackTrace stackTrace) {
    if (stackTrace == null) {
      return List.of();
    }
    final var frames = stackTrace.getFrames();
    final var labels = new ArrayList<String>(Math.min(TOP_FRAMES, frames.size()));
    for (final var frame : frames.subList(0, Math.min(TOP_FRAMES, frames.size()))) {
      labels.add(frameLabel(frame));
    }
    return labels;
  }

  static String frameLabel(final RecordedFrame frame) {
    final var method = frame.getMethod();
    final var type = method == null || method.getType() == null ? "?" : method.getType().getName();
    final var name = method == null ? "?" : method.getName();
    final int line = frame.getLineNumber();
    return type + '.' + name + (line > 0 ? ":" + line : "");
  }

  static String threadLabel(final RecordedThread thread) {
    final var name = thread.getJavaName();
    final var label = name == null || name.isEmpty() ? "#" + thread.getJavaThreadId() : name;
    return thread.isVirtual() ? label + " (virtual)" : label;
  }

  static void count(final Map<String, long[]> counts, final String key) {
    final var name = key == null ? "?" : key;
    final var counter = counts.get(name);
    if (counter != null) {
      ++counter[0];
    } else if (counts.size() < MAX_KEYS) {
      counts.put(name, new long[]{1L});
    }
  }

  static String joinCounts(final Map<String, long[]> counts, final int limit) {
    if (counts.isEmpty()) {
      return "none";
    }
    final var sorted = new ArrayList<>(counts.entrySet());
    sorted.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
    final var sb = new StringBuilder();
    for (final var entry : sorted.subList(0, Math.min(limit, sorted.size()))) {
      sb.append(sb.isEmpty() ? "" : ", ").append(entry.getKey()).append(" (").append(entry.getValue()[0]).append(')');
    }
    return sb.toString();
  }

  static String millis(final long nanos) {
    return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000d);
  }

  static String seconds(final Duration duration) {
    return String.format(Locale.ROOT, "%.0f s", duration.toMillis() / 1_000d);
  }

  static String bytes(final long value) {
    if (value < 0) {
      return "-" + bytes(-value);
    }
    if (value >= 1L << 30) {
      return String.format(Locale.ROOT, "%.2f GiB", value / (double) (1L << 30));
    }
    if (value >= 1L << 20) {
      return String.format(Locale.ROOT, "%.1f MiB", value / (double) (1L << 20));
    }
    return (value >> 10) + " KiB";
  }

  static String signed(final long value) {
    return value >= 0 ? "+" + bytes(value) : bytes(value);
  }
}
