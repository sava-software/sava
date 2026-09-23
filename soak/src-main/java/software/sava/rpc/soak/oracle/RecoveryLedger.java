package software.sava.rpc.soak.oracle;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/// One record per connection-level fault: when it was applied, which registrations were live,
/// how many were confirmed again, and how long the whole recovery took. It is the evidence behind
/// P7 and behind the report's "does it come back?" section.
///
/// The budget is derived from the run's own timings rather than chosen: `connectTimeout +
/// reConnectDelay + 4 x subscriptionResendDelay + 2 s` is the sum of the delays sava's own
/// javadoc promises for a reconnect and a replay, so a record over budget is a statement about
/// sava's published pacing, not about a number the harness liked.
///
/// A window is owed exactly the registrations that were live when the fault landed, by message
/// id. A confirmation of one of them on a later attempt takes it off the list; a plan
/// unsubscribe, a one-shot signature the engine completed, or the quiesce at the end of the run
/// *releases* it, because a registration the caller cancelled is no longer promised anything (the
/// last two windows of a run were measured failing on the two registrations the quiesce cancelled
/// before their replay was confirmed). A confirmation of a registration the window never owed -
/// a plan subscribe issued inside it - does not count.
///
/// A window closes in exactly one of three ways, and the driver reports each closed record:
///
/// - **complete**: the confirmation or release that empties the owed list closes it at once
///   (`confirmed`/`released` return true, then `closeCompleted`);
/// - **budget expired**: the driver's timer calls `closeIfOpen` when the window's own budget is
///   up, and an incomplete window is then a P7 failure with the fault kind in its example;
/// - **superseded**: a second connection-level fault on the same engine displaces the window.
///   One already complete, or already past its budget, is judged as such. One still inside its
///   budget and incomplete is *not evaluated*: at the validate fault density the next
///   destructive fault lands well inside the 25 s budget on most windows, and a rule that failed
///   those would make P7 fail whenever the schedule is denser than the budget, which says
///   nothing about the client. They are counted (`recovery.superseded`) so the report can say
///   how much of the evidence the schedule itself discarded.
///
/// A window that owes nothing - opened with no live registration, or every owed registration
/// released before any was confirmed - is vacuous: nothing is evaluated, and `recovery.vacuous`
/// counts it.
public final class RecoveryLedger {

  /// One recovery in progress or finished. `openedNanos` is `System.nanoTime()` at the open;
  /// `closedNanos` is that plus the elapsed the window is judged on, which is *wire* time: the
  /// peer's own row timestamps, so a confirmation the harness's tail read late still counts by
  /// when the peer wrote it (see [#confirmed(String, long, long, long)]), and one the peer wrote
  /// after the budget is `late` and never recovers the window whatever time it was read.
  public record Record(long faultOrdinal,
                       String engine,
                       String faultKind,
                       long originOrdinal,
                       int registrationsLive,
                       long openedNanos,
                       long closedNanos,
                       int confirmed,
                       int released,
                       int late,
                       long nextAttemptOrdinal,
                       boolean complete,
                       boolean overBudget,
                       long budgetMillis,
                       boolean superseded) {

    public long elapsedMillis() {
      return closedNanos <= 0L ? -1L : (closedNanos - openedNanos) / 1_000_000L;
    }

    /// The registrations the window still owed when it closed.
    public int outstanding() {
      return Math.max(0, registrationsLive - confirmed - released);
    }

    /// True when the record carries a P7 verdict: not superseded inside its budget, and not
    /// vacuous.
    public boolean evaluated() {
      return !superseded && registrationsLive - released > 0;
    }
  }

  /// Evaluated records are kept for the report; the cap keeps a long campaign's list from being
  /// the harness's own retention defect. Overflow is counted, never silently dropped.
  public static final int MAX_COMPLETED = 4096;

  private final long budgetMillis;
  private final PropertyLedger properties;
  private final ConcurrentHashMap<String, Open> open;
  private final CopyOnWriteArrayList<Record> completed;
  private final AtomicInteger dropped;
  private final AtomicInteger overBudget;
  private final AtomicInteger superseded;
  private final AtomicInteger vacuous;
  private final AtomicInteger late;

  public RecoveryLedger(final long budgetMillis) {
    this(budgetMillis, null);
  }

  /// With a ledger attached, closing a record evaluates P7 here rather than in each driver: a
  /// property a caller can forget to record is a property that silently reads as unexercised.
  public RecoveryLedger(final long budgetMillis, final PropertyLedger properties) {
    this.budgetMillis = budgetMillis;
    this.properties = properties;
    this.open = new ConcurrentHashMap<>(16);
    this.completed = new CopyOnWriteArrayList<>();
    this.dropped = new AtomicInteger();
    this.overBudget = new AtomicInteger();
    this.superseded = new AtomicInteger();
    this.vacuous = new AtomicInteger();
    this.late = new AtomicInteger();
  }

  /// The derived budget for this run's timings: `connectTimeout + reConnectDelay +
  /// 4 x subscriptionResendDelay + 2 s`.
  public static long budgetMillis(final long connectTimeoutMillis,
                                  final long reconnectDelayMillis,
                                  final long subscriptionResendMillis) {
    return connectTimeoutMillis + reconnectDelayMillis + 4L * subscriptionResendMillis + 2_000L;
  }

  public long budgetMillis() {
    return budgetMillis;
  }

  /// Count-only form: the window is complete after `registrationsLive` confirmations of any
  /// registration. Kept for callers without message ids; the websocket driver uses the id form.
  public Record open(final long faultOrdinal, final String engine, final long originOrdinal,
                     final int registrationsLive) {
    return open(faultOrdinal, engine, originOrdinal, registrationsLive, "");
  }

  public Record open(final long faultOrdinal, final String engine, final long originOrdinal,
                     final int registrationsLive, final String faultKind) {
    return open(faultOrdinal, engine, originOrdinal, registrationsLive, null, faultKind, budgetMillis);
  }

  /// Opens the window for `engine`, owed exactly `owedMsgIds`, with its own budget. The derived
  /// budget assumes the engine learns of the fault at once; a fault the engine can only discover
  /// through its ping probe is owed the probe's windows as well, and the driver that knows the
  /// kind passes the sum here. A second open for an engine whose window is still open displaces
  /// it; the displaced window is finished as the class comment describes and returned so the
  /// driver can report it, or null when nothing was open.
  public Record open(final long faultOrdinal, final String engine, final long originOrdinal,
                     final Collection<Long> owedMsgIds, final String faultKind, final long windowBudgetMillis) {
    return open(faultOrdinal, engine, originOrdinal, owedMsgIds.size(), owedMsgIds, faultKind, windowBudgetMillis);
  }

  /// Opens a window whose clock starts at an *adoption* rather than at the fault.
  ///
  /// A fault that landed before a connection existed — a refused connect, a rejected upgrade — has
  /// no connection to be recovered on, so a window opened at the fault measures the peer's refusal
  /// window and the reconnect backoff instead of the replay and confirmation P7 is about. The
  /// fault's own ordinal and kind stay in the record, so it still names what broke, and `-1` there
  /// honestly says the fault hit no identifiable connection. Confirmations count from
  /// `adoptedOrdinal` *inclusive*: that attempt is the recovery, not the casualty.
  /// `openedWallMillis` is the adoption's wall-clock instant (both JVMs share the machine's
  /// clock), the origin every confirmation's row timestamp is measured from.
  public Record openAtAdoption(final long faultOrdinal, final String engine, final long originOrdinal,
                               final long adoptedOrdinal, final Collection<Long> owedMsgIds,
                               final String faultKind, final long windowBudgetMillis,
                               final long openedWallMillis) {
    final var window = new Open(faultOrdinal, engine, faultKind == null ? "" : faultKind, originOrdinal,
        owedMsgIds.size(), owedMsgIds, System.nanoTime(), openedWallMillis, windowBudgetMillis,
        adoptedOrdinal - 1L);
    window.adoptedAttemptOrdinal = adoptedOrdinal;
    final var previous = open.put(engine, window);
    return previous == null ? null : finish(previous, true);
  }

  private Record open(final long faultOrdinal, final String engine, final long originOrdinal,
                      final int registrationsLive, final Collection<Long> owedMsgIds,
                      final String faultKind, final long windowBudgetMillis) {
    final var window = new Open(faultOrdinal, engine, faultKind == null ? "" : faultKind, originOrdinal,
        registrationsLive, owedMsgIds, System.nanoTime(), System.currentTimeMillis(), windowBudgetMillis,
        originOrdinal);
    final var previous = open.put(engine, window);
    return previous == null ? null : finish(previous, true);
  }

  /// Displaces `engine`'s open window without opening another: finished as displaced (superseded
  /// unless it had already completed or run over), or null when nothing was open. For a fault that
  /// hit an attempt before it existed while a window from an earlier fault was still waiting.
  public Record displace(final String engine) {
    final var previous = open.remove(engine);
    return previous == null ? null : finish(previous, true);
  }

  /// The successor attempt the engine adopted inside the open window, for the record.
  public void adopted(final String engine, final long attemptOrdinal) {
    final var window = open.get(engine);
    if (window != null && window.adoptedAttemptOrdinal < 0L) {
      window.adoptedAttemptOrdinal = attemptOrdinal;
    }
  }

  /// Time-blind form: no row timestamp, so no confirmation is ever late.
  public boolean confirmed(final String engine, final long msgId, final long attemptOrdinal) {
    return confirmed(engine, msgId, attemptOrdinal, -1L);
  }

  /// Registration `msgId` confirmed on attempt `attemptOrdinal`, by a row the peer wrote at
  /// `wallMillis` (-1 when the caller has no row time). Only a confirmation on an attempt after
  /// the one the fault hit counts: the retired connection can still acknowledge a plan subscribe
  /// in the milliseconds between the fault and its close, and that is not recovery. The window is
  /// judged on the wire's clock: a row written inside the budget counts whenever the tail reads
  /// it, and one written after the budget is `late` — counted, never credited — because the
  /// property asks when the peer confirmed, not when the harness noticed. Measured on a
  /// campaign: the tail fell about 20 s behind for half a minute, and a window whose 128
  /// confirmations were all on the wire inside 6 s closed with 94 outstanding. Returns true
  /// exactly once, when this confirmation completed the window.
  public boolean confirmed(final String engine, final long msgId, final long attemptOrdinal,
                           final long wallMillis) {
    final var window = open.get(engine);
    if (window == null) {
      return false;
    }
    if (window.confirmFromExclusive >= 0L && attemptOrdinal >= 0L
        && attemptOrdinal <= window.confirmFromExclusive) {
      return false;
    }
    final boolean lateByWire = wallMillis > 0L
        && wallMillis - window.openedWallMillis > window.budgetMillis;
    if (window.owed != null) {
      if (lateByWire) {
        if (window.owed.contains(msgId)) {
          window.late.incrementAndGet();
          late.incrementAndGet();
        }
        return false;
      }
      if (!window.owed.remove(msgId)) {
        return false;
      }
      window.confirmed.incrementAndGet();
      window.noteCounted(wallMillis);
      return window.markCompleteIfDone();
    }
    if (window.registrationsLive == 0) {
      return false;
    }
    if (lateByWire) {
      window.late.incrementAndGet();
      late.incrementAndGet();
      return false;
    }
    window.noteCounted(wallMillis);
    if (window.confirmed.incrementAndGet() == window.registrationsLive) {
      window.completedNanos = System.nanoTime();
      window.completedWallMillis = window.lastCountedWallMillis > 0L
          ? window.lastCountedWallMillis
          : System.currentTimeMillis();
      return true;
    }
    return false;
  }

  /// Registration `msgId` left the promised set - a plan unsubscribe, a one-shot signature the
  /// engine completed, the quiesce - so the window no longer owes it. Returns true exactly once,
  /// when this release emptied the owed list.
  public boolean released(final String engine, final long msgId) {
    final var window = open.get(engine);
    if (window == null || window.owed == null || !window.owed.remove(msgId)) {
      return false;
    }
    window.released.incrementAndGet();
    return window.markCompleteIfDone();
  }

  /// Closes the engine's window if it is the one `faultOrdinal` opened and it is still open:
  /// the driver's budget timer, which must not close a window a later fault has since replaced.
  public Record closeIfOpen(final String engine, final long faultOrdinal) {
    final var window = open.get(engine);
    if (window == null || window.faultOrdinal != faultOrdinal || !open.remove(engine, window)) {
      return null;
    }
    return finish(window, false);
  }

  /// Closes the engine's window if it owes nothing more: the driver calls this on the
  /// confirmation or release `confirmed`/`released` reported as completing.
  public Record closeCompleted(final String engine) {
    final var window = open.get(engine);
    if (window == null || window.completedNanos <= 0L || !open.remove(engine, window)) {
      return null;
    }
    return finish(window, false);
  }

  /// Closes the window unconditionally. Returns the finished record, or null when nothing was
  /// open for that engine (a retirement outside any fault, which the unexplained-retirement
  /// counter owns).
  public Record close(final String engine, final long nextAttemptOrdinal) {
    final var window = open.remove(engine);
    if (window == null) {
      return null;
    }
    if (window.adoptedAttemptOrdinal < 0L) {
      window.adoptedAttemptOrdinal = nextAttemptOrdinal;
    }
    return finish(window, false);
  }

  private Record finish(final Open window, final boolean displaced) {
    final long closedWallMillis = System.currentTimeMillis();
    final int confirmed = window.confirmed.get();
    final int released = window.released.get();
    final boolean complete = window.owed != null
        ? window.owed.isEmpty()
        : confirmed >= window.registrationsLive;
    // A window that completed is judged on when it completed, not on when it was closed, and
    // both instants are wire time: the newest counted row's timestamp, or the close itself for an
    // incomplete window (the driver closes one only once the tail has read past its budget).
    final long judgedWallMillis = complete && window.completedWallMillis > 0L
        ? window.completedWallMillis
        : closedWallMillis;
    final long elapsedMillis = Math.max(0L, judgedWallMillis - window.openedWallMillis);
    final long judgedNanos = window.openedNanos + elapsedMillis * 1_000_000L;
    final boolean over = elapsedMillis > window.budgetMillis;
    final boolean cutShort = displaced && !complete && !over;
    final var record = new Record(window.faultOrdinal, window.engine, window.faultKind, window.originOrdinal,
        window.registrationsLive, window.openedNanos, judgedNanos, confirmed, released, window.late.get(),
        window.adoptedAttemptOrdinal, complete, over, window.budgetMillis, cutShort);
    if (cutShort) {
      superseded.incrementAndGet();
      return record;
    }
    if (window.registrationsLive - released <= 0) {
      vacuous.incrementAndGet();
      return record;
    }
    if (over) {
      overBudget.incrementAndGet();
    }
    if (completed.size() < MAX_COMPLETED) {
      completed.add(record);
    } else {
      dropped.incrementAndGet();
    }
    if (properties != null) {
      if (over || !complete) {
        properties.fail(Properties.P7.id(), example(record, window.budgetMillis));
      } else {
        properties.pass(Properties.P7.id());
      }
    }
    return record;
  }

  /// The example text for a P7 failure: the fault kind is in it because "recovery took too long"
  /// without naming what broke the connection cannot be triaged.
  public static String example(final Record record, final long budgetMillis) {
    return "engine=" + record.engine()
        + " fault=" + (record.faultKind().isEmpty() ? "?" : record.faultKind())
        + " ordinal=" + record.faultOrdinal()
        + " origin=" + record.originOrdinal()
        + " registrations=" + record.registrationsLive()
        + " confirmed=" + record.confirmed()
        + " released=" + record.released()
        + (record.late() > 0 ? " late=" + record.late() : "")
        + " outstanding=" + record.outstanding()
        + " elapsedMs=" + record.elapsedMillis()
        + " budgetMs=" + budgetMillis;
  }

  /// The records that carry a P7 verdict.
  public List<Record> completed() {
    return List.copyOf(completed);
  }

  public Map<String, Long> openWindows() {
    final var windows = new ConcurrentHashMap<String, Long>(open.size() * 2);
    open.forEach((engine, window) -> windows.put(engine, window.faultOrdinal));
    return Map.copyOf(windows);
  }

  public int overBudget() {
    return overBudget.get();
  }

  /// Confirmations the peer wrote after their window's budget, over every window: judged at the
  /// row's own timestamp, so they say the wire was late, never that the tail was.
  public int late() {
    return late.get();
  }

  /// Windows a later connection-level fault displaced while still inside their budget and
  /// incomplete: evidence the schedule discarded, not a verdict either way.
  public int superseded() {
    return superseded.get();
  }

  /// Windows that owed nothing: nothing evaluated.
  public int vacuous() {
    return vacuous.get();
  }

  /// Evaluated records that could not be kept because the list is full. Non-zero means the
  /// recovery section's denominators are incomplete and the report says so.
  public int dropped() {
    return dropped.get();
  }

  private static final class Open {

    private final long faultOrdinal;
    private final String engine;
    private final String faultKind;
    private final long originOrdinal;
    private final int registrationsLive;
    /// The message ids still owed, or null in the count-only form.
    private final Set<Long> owed;
    private final long openedNanos;
    /// Wall-clock instant of the open, the origin the peer's row timestamps are measured from.
    private final long openedWallMillis;
    private final long budgetMillis;
    /// The highest attempt ordinal whose confirmations do *not* count towards this window. It is the
    /// fault's own attempt for a window opened at the fault — the retired connection can still
    /// acknowledge a plan subscribe in the milliseconds before it closes, and that is not recovery —
    /// and one below the adopted attempt for a window opened at an adoption.
    private final long confirmFromExclusive;
    private final AtomicInteger confirmed;
    private final AtomicInteger released;
    /// Confirmations the peer wrote after the budget: counted, never credited.
    private final AtomicInteger late;
    private volatile long adoptedAttemptOrdinal = -1L;
    private volatile long completedNanos;
    /// The newest row timestamp among the counted confirmations, and the wire-time completion.
    private volatile long lastCountedWallMillis;
    private volatile long completedWallMillis;

    private Open(final long faultOrdinal, final String engine, final String faultKind,
                 final long originOrdinal, final int registrationsLive, final Collection<Long> owedMsgIds,
                 final long openedNanos, final long openedWallMillis, final long budgetMillis,
                 final long confirmFromExclusive) {
      this.confirmFromExclusive = confirmFromExclusive;
      this.faultOrdinal = faultOrdinal;
      this.engine = engine;
      this.faultKind = faultKind;
      this.originOrdinal = originOrdinal;
      this.registrationsLive = registrationsLive;
      if (owedMsgIds == null) {
        this.owed = null;
      } else {
        this.owed = ConcurrentHashMap.newKeySet(Math.max(16, owedMsgIds.size() * 2));
        this.owed.addAll(owedMsgIds);
      }
      this.openedNanos = openedNanos;
      this.openedWallMillis = openedWallMillis;
      this.budgetMillis = budgetMillis;
      this.confirmed = new AtomicInteger();
      this.released = new AtomicInteger();
      this.late = new AtomicInteger();
    }

    private void noteCounted(final long wallMillis) {
      if (wallMillis > lastCountedWallMillis) {
        lastCountedWallMillis = wallMillis;
      }
    }

    /// True exactly once: on the confirmation or release that emptied the owed list.
    private boolean markCompleteIfDone() {
      if (!owed.isEmpty() || completedNanos > 0L) {
        return false;
      }
      synchronized (this) {
        if (completedNanos > 0L) {
          return false;
        }
        completedNanos = System.nanoTime();
        completedWallMillis = lastCountedWallMillis > 0L ? lastCountedWallMillis : System.currentTimeMillis();
        return true;
      }
    }
  }
}
