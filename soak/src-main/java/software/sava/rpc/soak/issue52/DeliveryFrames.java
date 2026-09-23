package software.sava.rpc.soak.issue52;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/// Per-thread delivery frames: the attribution rule's "same thread, same synchronous
/// activation" made into a data structure (attribution.md rule, frame mechanics).
///
/// A frame is pushed by each of the three harness entry points — a listener callback, the
/// harness's own completion of its build future, and the `buildAsync` call — and popped when
/// that entry point returns. Inside a frame, a retirement `abort()` pushes a sub-frame; the
/// build-future cancel, the manager's `Backoff` claims and its log lines attach to that
/// sub-frame; the prototype `onClose`/`onError`/`onPingError` handlers consume it
/// innermost-first. A thread with no entry point (the engine's check loop) gets one *lazy*
/// frame, created by the first retirement abort, replaced by the next one, invalidated by any
/// later entry-point push, and aged out at [#LAZY_AGE_NANOS] by a sweep.
///
/// Why frames are strictly LIFO and sub-frames live in the frame that created them: a user
/// handler may re-enter `connect()` synchronously and a wrapping builder may deliver a nested
/// `onOpen`, a nested adoption and a nested retirement before the outer handler resumes. With
/// LIFO frames the inner handlers consume the inner sub-frame and, once the inner frame pops,
/// the outer sub-frame is innermost again for the outer `onPingError`. Every abort carries a
/// global sequence number and a frame consumes only sub-frames newer than what it (and its
/// thread, at creation) last consumed, so a stale sub-frame can never be re-read.
///
/// Allocation discipline: the listener path pushes and pops a frame per inbound message, so
/// frames are pre-sized per thread and re-initialised in place; sub-frames and their bounded
/// claim/log arrays are allocated on the retirement path only, which runs at fault rate.
final class DeliveryFrames {

  enum FrameKind {
    ON_OPEN,
    ON_TEXT,
    ON_BINARY,
    ON_PING,
    ON_PONG,
    ON_CLOSE,
    ON_ERROR,
    BUILD_COMPLETION,
    BUILD_ASYNC,
    LAZY;

    boolean listener() {
      return ordinal() <= ON_ERROR.ordinal();
    }

    RetirementRecord.FrameKind recordKind() {
      return RetirementRecord.FrameKind.valueOf(name());
    }
  }

  static final int MAX_DEPTH = 16;
  static final int MAX_SUBS = 8;
  static final int MAX_CLAIMS = 4;
  static final int MAX_MANAGER_LINES = 6;
  static final int MAX_ENGINE_LINES = 4;
  static final int MAX_PENDING_LINES = 4;
  static final int MAX_THREADS = 512;
  /// A lazy frame outlives its retirement by at most this; anything unconsumed by then was
  /// delivered to nobody the harness could see.
  static final long LAZY_AGE_NANOS = 2_000_000_000L;
  /// An engine cause line (logged under the lock, before the abort) is adopted by the next
  /// retirement abort on its thread only within this window; older lines are stale noise.
  static final long PENDING_LINE_AGE_NANOS = 1_000_000_000L;

  static final int CONSUMED_ON_CLOSE = 1;
  static final int CONSUMED_ON_ERROR = 2;
  static final int CONSUMED_ON_PING_ERROR = 4;

  static final String ENGINE_CLASS = "software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket";
  static final String RETIREMENT_CLASSIFIER = "retireConnection";

  private static final AtomicLong ABORT_SEQ = new AtomicLong();
  private static final ConcurrentHashMap<Thread, DeliveryFrames> REGISTRY = new ConcurrentHashMap<>();
  static final LongAdder REGISTRY_OVERFLOW = new LongAdder();
  private static final ThreadLocal<DeliveryFrames> THREADS = ThreadLocal.withInitial(DeliveryFrames::register);

  static long nextAbortSeq() {
    return ABORT_SEQ.incrementAndGet();
  }

  private static DeliveryFrames register() {
    final var frames = new DeliveryFrames(Thread.currentThread());
    if (REGISTRY.size() < MAX_THREADS) {
      REGISTRY.put(frames.thread, frames);
    } else {
      REGISTRY_OVERFLOW.increment();
    }
    return frames;
  }

  static DeliveryFrames of() {
    return THREADS.get();
  }

  /// Ages out lazy frames on every registered thread and finalises the lazy frames of threads
  /// that have died. Called from the tracker's `sweep`, never from the hot path.
  static void sweepAll(final long nowNanos) {
    for (final var entry : REGISTRY.entrySet()) {
      final var frames = entry.getValue();
      final var lazy = frames.lazy;
      synchronized (lazy) {
        if (lazy.active && (!frames.thread.isAlive() || nowNanos - lazy.enteredNanos > LAZY_AGE_NANOS)) {
          lazy.finalizeUnconsumed(nowNanos, "aged-out");
          lazy.active = false;
        }
      }
      if (!frames.thread.isAlive()) {
        REGISTRY.remove(entry.getKey(), frames);
      }
    }
  }

  static int registeredThreads() {
    return REGISTRY.size();
  }

  final Thread thread;
  private final Frame[] stack = new Frame[MAX_DEPTH];
  private int depth;
  final Frame lazy;
  /// Nesting count of `HarnessBuildFuture.cancel(true)` on this thread; positive means every
  /// claim and log line arriving now belongs to the future route.
  int buildCancelDepth;
  /// The newest abort sequence any frame on this thread consumed; a lazy frame created later
  /// starts from it so a stale sub-frame cannot be re-consumed across a lazy replacement.
  long lastConsumedSeq;
  private final String[] pendingLines = new String[MAX_PENDING_LINES];
  private final long[] pendingLineNanos = new long[MAX_PENDING_LINES];
  private int pendingCount;

  private DeliveryFrames(final Thread thread) {
    this.thread = thread;
    this.lazy = new Frame(this, true);
  }

  boolean insideBuildCancel() {
    return buildCancelDepth > 0;
  }

  int depth() {
    return depth;
  }

  /// Pushes an entry-point frame. Returns null on depth overflow, which the caller tolerates
  /// (the callback still runs; only attribution for that activation is lost and counted).
  Frame push(final AttemptTracker tracker, final FrameKind kind, final long ordinal, final long nowNanos) {
    if (lazy.active) {
      // Any harness entry point invalidates a lazily created frame on this thread: a retirement
      // it holds was delivered to nobody the harness could see.
      synchronized (lazy) {
        if (lazy.active) {
          lazy.finalizeUnconsumed(nowNanos == 0 ? System.nanoTime() : nowNanos, "entry-point");
          lazy.active = false;
        }
      }
    }
    if (depth == MAX_DEPTH) {
      tracker.stats.frameDepthOverflow.increment();
      return null;
    }
    Frame frame = stack[depth];
    if (frame == null) {
      frame = new Frame(this, false);
      stack[depth] = frame;
    }
    frame.reset(kind, ordinal, tracker, nowNanos);
    ++depth;
    return frame;
  }

  void pop(final Frame frame) {
    if (frame == null) {
      return;
    }
    if (frame.subCount > 0) {
      frame.finalizeUnconsumed(System.nanoTime(), "frame-pop");
    }
    frame.active = false;
    frame.tracker = null;
    --depth;
  }

  /// The innermost explicit frame, else the lazy frame while it is active, else null.
  Frame current() {
    if (depth > 0) {
      return stack[depth - 1];
    }
    return lazy.active ? lazy : null;
  }

  Frame currentExplicit() {
    return depth > 0 ? stack[depth - 1] : null;
  }

  boolean hasEnclosing(final FrameKind kind) {
    for (int i = depth - 1; i >= 0; --i) {
      if (stack[i].kind == kind) {
        return true;
      }
    }
    return false;
  }

  /// Records a retirement abort candidate in the innermost explicit frame, or creates/replaces
  /// the lazy frame on a thread with no entry point.
  SubFrame retirementAbort(final AttemptTracker tracker,
                           final HarnessSocket socket,
                           final long seq,
                           final long nowNanos,
                           final String classifier) {
    final var explicit = currentExplicit();
    if (explicit != null) {
      return explicit.addRetirement(tracker, socket, seq, nowNanos, classifier);
    }
    synchronized (lazy) {
      if (lazy.active) {
        // Replaced by the next retirement abort on the thread: whatever the previous one still
        // held unconsumed was delivered to nobody the harness saw.
        lazy.finalizeUnconsumed(nowNanos, "replaced");
      }
      lazy.reset(FrameKind.LAZY, -1, tracker, nowNanos);
      return lazy.addRetirement(tracker, socket, seq, nowNanos, classifier);
    }
  }

  /// An engine cause line logged before its abort, held until the abort adopts it.
  void addPendingEngineLine(final String line, final long nowNanos) {
    final int slot = pendingCount < MAX_PENDING_LINES ? pendingCount++ : (int) (nowNanos & (MAX_PENDING_LINES - 1));
    pendingLines[slot] = line;
    pendingLineNanos[slot] = nowNanos;
  }

  private void drainPendingInto(final SubFrame sub, final long nowNanos) {
    for (int i = 0; i < pendingCount; ++i) {
      if (nowNanos - pendingLineNanos[i] <= PENDING_LINE_AGE_NANOS) {
        sub.addEngineLine(pendingLines[i], pendingLineNanos[i]);
      }
      pendingLines[i] = null;
    }
    pendingCount = 0;
  }

  /// One entry-point activation, or the thread's lazy frame.
  static final class Frame {

    final DeliveryFrames owner;
    final boolean lazy;
    FrameKind kind;
    long ordinal;
    AttemptTracker tracker;
    long enteredNanos;
    volatile boolean active;
    private SubFrame[] subs;
    int subCount;
    long lastConsumedSeq;

    private Frame(final DeliveryFrames owner, final boolean lazy) {
      this.owner = owner;
      this.lazy = lazy;
      this.kind = lazy ? FrameKind.LAZY : FrameKind.BUILD_ASYNC;
      this.ordinal = -1;
    }

    private void reset(final FrameKind kind, final long ordinal, final AttemptTracker tracker, final long nowNanos) {
      this.kind = kind;
      this.ordinal = ordinal;
      this.tracker = tracker;
      this.enteredNanos = nowNanos;
      this.subCount = 0;
      this.lastConsumedSeq = owner.lastConsumedSeq;
      this.active = true;
    }

    RetirementRecord.FrameKind recordKind() {
      return kind.recordKind();
    }

    private SubFrame addRetirement(final AttemptTracker tracker,
                                   final HarnessSocket socket,
                                   final long seq,
                                   final long nowNanos,
                                   final String classifier) {
      if (subs == null) {
        subs = new SubFrame[MAX_SUBS];
      }
      if (subCount == MAX_SUBS) {
        tracker.stats.subFrameOverflow.increment();
        return null;
      }
      SubFrame sub = subs[subCount];
      if (sub == null) {
        sub = new SubFrame();
        subs[subCount] = sub;
      }
      sub.reset(tracker, socket, seq, nowNanos, classifier);
      ++subCount;
      owner.drainPendingInto(sub, nowNanos);
      return sub;
    }

    /// Innermost retirement sub-frame, consumed or not: the manager's claims and log lines
    /// arrive before the harness handler consumes it, and `onPingError` after.
    SubFrame innermostRetirement() {
      for (int i = subCount - 1; i >= 0; --i) {
        if (subs[i].retirement) {
          return subs[i];
        }
      }
      return null;
    }

    SubFrame findRetirement(final long ordinal) {
      for (int i = subCount - 1; i >= 0; --i) {
        if (subs[i].retirement && subs[i].ordinal == ordinal) {
          return subs[i];
        }
      }
      return null;
    }

    /// Consumption for `onClose`/`onError`: the innermost unconsumed retirement sub-frame newer
    /// than this frame's watermark. For `onPingError`: the innermost sub-frame `onError` already
    /// consumed and no `onPingError` has, so the pair reads one record.
    SubFrame consume(final int kind, final long nowNanos) {
      for (int i = subCount - 1; i >= 0; --i) {
        final var sub = subs[i];
        if (!sub.retirement) {
          continue;
        }
        if (kind == CONSUMED_ON_PING_ERROR) {
          if ((sub.consumedBy & CONSUMED_ON_ERROR) != 0 && (sub.consumedBy & CONSUMED_ON_PING_ERROR) == 0) {
            sub.consumedBy |= CONSUMED_ON_PING_ERROR;
            return sub;
          }
        } else if (sub.consumedBy == 0 && sub.seq > lastConsumedSeq) {
          sub.consumedBy |= kind;
          sub.consumedNanos = nowNanos;
          lastConsumedSeq = sub.seq;
          if (sub.seq > owner.lastConsumedSeq) {
            owner.lastConsumedSeq = sub.seq;
          }
          return sub;
        }
      }
      return null;
    }

    /// Finalises what no handler read: an unconsumed retirement is a delivery the harness never
    /// observed; a consumed `onError` still waiting for its promised `onPingError` is engine
    /// drift. Both become rows, never silent drops.
    void finalizeUnconsumed(final long nowNanos, final String why) {
      for (int i = 0; i < subCount; ++i) {
        final var sub = subs[i];
        if (!sub.retirement || sub.finalized) {
          continue;
        }
        sub.finalized = true;
        if (sub.consumedBy == 0) {
          sub.tracker.emitUnobserved(sub, this, nowNanos, why);
        } else if (sub.pending != null) {
          sub.tracker.emitPendingWithoutPing(sub, why);
        }
      }
    }
  }

  /// One retirement abort candidate and everything that attached to it on its thread.
  static final class SubFrame {

    long seq;
    long ordinal;
    long abortNanos;
    String classifier;
    boolean retirement;
    HarnessSocket socket;
    AttemptTracker tracker;
    boolean buildCancelObserved;
    boolean wasDoneAtCancel;
    long cancelEnterNanos;
    long cancelReturnNanos;
    long ordinalAtCancelReturn;
    ClaimRecord[] claims;
    int claimCount;
    int claimOverflow;
    String[] managerLines;
    long[] managerLineNanos;
    int managerLineCount;
    long loggedRetryMs;
    String[] engineLines;
    long[] engineLineNanos;
    int engineLineCount;
    int consumedBy;
    long consumedNanos;
    long harnessNanosInGap;
    /// A record consumed by `onError` whose `onPingError` is promised by the delivery stack;
    /// emitted when that arrives, or at frame end flagged EXPECTED_MISSING.
    RetirementRecord.Builder pending;
    boolean finalized;

    private void reset(final AttemptTracker tracker,
                       final HarnessSocket socket,
                       final long seq,
                       final long nowNanos,
                       final String classifier) {
      this.tracker = tracker;
      this.socket = socket;
      this.seq = seq;
      this.ordinal = socket.ordinal();
      this.abortNanos = nowNanos;
      this.classifier = classifier;
      this.retirement = true;
      this.buildCancelObserved = false;
      this.wasDoneAtCancel = false;
      this.cancelEnterNanos = -1;
      this.cancelReturnNanos = -1;
      this.ordinalAtCancelReturn = -1;
      this.claimCount = 0;
      this.claimOverflow = 0;
      this.managerLineCount = 0;
      this.loggedRetryMs = -1;
      this.engineLineCount = 0;
      this.consumedBy = 0;
      this.consumedNanos = -1;
      this.harnessNanosInGap = 0;
      this.pending = null;
      this.finalized = false;
    }

    void addClaim(final ClaimRecord claim) {
      if (claims == null) {
        claims = new ClaimRecord[MAX_CLAIMS];
      }
      if (claimCount == MAX_CLAIMS) {
        ++claimOverflow;
        return;
      }
      claims[claimCount++] = claim;
    }

    ClaimRecord futureClaim() {
      for (int i = 0; i < claimCount; ++i) {
        if (claims[i].insideBuildCancel()) {
          return claims[i];
        }
      }
      return null;
    }

    ClaimRecord callbackClaim() {
      for (int i = 0; i < claimCount; ++i) {
        if (!claims[i].insideBuildCancel()) {
          return claims[i];
        }
      }
      return null;
    }

    ClaimRecord lastClaim() {
      return claimCount == 0 ? null : claims[claimCount - 1];
    }

    void addManagerLine(final String line, final long nowNanos) {
      if (managerLines == null) {
        managerLines = new String[MAX_MANAGER_LINES];
        managerLineNanos = new long[MAX_MANAGER_LINES];
      }
      if (managerLineCount == MAX_MANAGER_LINES) {
        return;
      }
      managerLines[managerLineCount] = line;
      managerLineNanos[managerLineCount] = nowNanos;
      ++managerLineCount;
    }

    void addEngineLine(final String line, final long nowNanos) {
      if (engineLines == null) {
        engineLines = new String[MAX_ENGINE_LINES];
        engineLineNanos = new long[MAX_ENGINE_LINES];
      }
      if (engineLineCount == MAX_ENGINE_LINES) {
        return;
      }
      engineLines[engineLineCount] = line;
      engineLineNanos[engineLineCount] = nowNanos;
      ++engineLineCount;
    }

    /// Cause lines with microsecond offsets from the abort, for the row's `causeDetail`.
    void appendEngineLines(final StringBuilder sb) {
      for (int i = 0; i < engineLineCount; ++i) {
        sb.append(" | ").append((engineLineNanos[i] - abortNanos) / 1_000L).append("us:")
            .append(engineLines[i], 0, Math.min(engineLines[i].length(), 96));
      }
    }
  }
}
