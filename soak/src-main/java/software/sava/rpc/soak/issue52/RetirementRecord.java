package software.sava.rpc.soak.issue52;

import java.util.Set;

/// One retirement row: every `retirements.tsv` column of DESIGN.md §10, in that order, with
/// that section's enumerations. A record rather than a class so the detector and the self-test
/// read fields without a second accessor layer; the `Builder` exists because 41 positional
/// arguments are unreviewable and because the tracker fills a row in several steps.
///
/// Unknown numerics are `-1`. `retryMarginUs` can legitimately be negative (that is what an
/// opportunity is), so its `-1` is ambiguous on its own: readers decide whether the gap columns
/// are defined from `futureRoute == ACCEPTED`, never from the sentinel.
public record RetirementRecord(long retirementId,
                               String engine,
                               String backoffClass,
                               boolean forced,
                               String forcedReason,
                               long originOrdinal,
                               long originGeneration,
                               long adoptedAtNanos,
                               long retiredAtNanos,
                               long connectionAgeMs,
                               OpenOrder openOrder,
                               CauseClass causeClass,
                               String causeDetail,
                               String deliveryThread,
                               ThreadKind threadKind,
                               FrameKind frameKind,
                               boolean retiredNewerThanListener,
                               long abortSeq,
                               boolean abortBeforeCallback,
                               String abortClassifier,
                               long abortToHandlerUs,
                               boolean buildCancelObserved,
                               boolean buildWasDoneAtCancel,
                               FutureRoute futureRoute,
                               CallbackRoute callbackRoute,
                               PingRoute pingRoute,
                               int claimCount,
                               long errorCountBefore,
                               long errorCountAfter,
                               long retryDelayMs,
                               long loggedRetryMs,
                               long gapFutureToCallbackUs,
                               long retryMarginUs,
                               long harnessTimeInGapUs,
                               long currentOrdinalAtCallbackClaim,
                               boolean successorBuiltInsideGap,
                               Verdict verdict,
                               long nextAttemptOrdinal,
                               NextAttemptOutcome nextAttemptOutcome,
                               long wakeLatencyUs,
                               long subscriptionRecoveryMs) {

  public enum CauseClass {
    JDK_CLOSE,
    JDK_ERROR,
    UNANSWERED_REQUEST,
    PING_SEND_TIMEOUT,
    PING_RESPONSE_TIMEOUT,
    PING_SEND_FAILURE,
    MAX_MESSAGE_OVERFLOW,
    ID_COLLISION,
    UNSUB_NONEQUIVALENT,
    INSTANCE_DEATH,
    CANCELLED_BEFORE_INSTALL,
    HARNESS_INJECTED,
    UNKNOWN
  }

  public enum ThreadKind {
    JDK_LISTENER,
    CHECK_LOOP,
    ADOPTING,
    BUILD_COMPLETION,
    OTHER
  }

  /// The harness frame the delivery ran in: a listener callback kind, a build-future completion,
  /// a `buildAsync` call, a lazily created frame (no harness entry point on the thread), or none.
  public enum FrameKind {
    ON_OPEN,
    ON_TEXT,
    ON_BINARY,
    ON_PING,
    ON_PONG,
    ON_CLOSE,
    ON_ERROR,
    BUILD_COMPLETION,
    BUILD_ASYNC,
    LAZY,
    NONE
  }

  public enum FutureRoute {
    NONE_ALREADY_SETTLED,
    FIRED_IGNORED,
    FENCED,
    ACCEPTED
  }

  public enum CallbackRoute {
    ON_CLOSE_ACCEPTED,
    ON_CLOSE_FENCED,
    ON_ERROR_ACCEPTED,
    ON_ERROR_FENCED,
    UNOBSERVED
  }

  /// PRESENT: `onPingError` ran on the same sub-frame after `onError`; ABSENT: the engine path
  /// promised no ping observer; EXPECTED_MISSING: the delivery stack showed
  /// `deliverRetiredPingFailure` but no `onPingError` reached the harness before the frame
  /// ended — impossible in the current engine, so it flags drift.
  public enum PingRoute {
    PRESENT,
    ABSENT,
    EXPECTED_MISSING
  }

  public enum Verdict {
    ATTRIBUTED_SINGLE_CLAIM,
    FENCED_CORRECTLY,
    OPPORTUNITY_NOT_REALIZED,
    DOUBLE_CLAIM_MISATTRIBUTED,
    DOUBLE_CLAIM_UNEXPLAINED,
    INSTANCE_DEATH,
    UNOBSERVED_DELIVERY
  }

  public enum NextAttemptOutcome {
    OPENED_ACKNOWLEDGED,
    OPENED_UNACKNOWLEDGED_REPLACED,
    REJOINED_ACKNOWLEDGED,
    FAILED,
    CANCELLED_BEFORE_INSTALL,
    PENDING
  }

  public enum OpenOrder {
    FUTURE_FIRST,
    ONOPEN_FIRST,
    UNKNOWN
  }

  /// `forcedReason` values where the harness MADE the retirement happen and then got out of the
  /// way of the #52 race itself. `INJECTED_RETIREMENT` is deliberately absent: that mode's
  /// blocking JUL handler holds the retiring thread inside the gap until the successor's
  /// `buildAsync` has been observed, which decides the race rather than measuring it.
  ///
  /// `INJECTED_RETIREMENT_JDK_ORDER` is included. It differs from `INJECTED_RETIREMENT_UNBLOCKED`
  /// only in not deferring the build future's completion until `onOpen` returns; nothing holds
  /// its gap open either, so whether the successor gets charged is decided by the same machine
  /// timing. The two never share a denominator in practice — a run directory carries one control
  /// — so including it reports that control's own rate instead of suppressing it.
  private static final Set<String> UNBLOCKED_FORCED_REASONS = Set.of(
      "INJECTED_RETIREMENT_UNBLOCKED",
      "INJECTED_RETIREMENT_JDK_ORDER"
  );

  /// Was this retirement's race left to run at the machine's own speed? True for every unforced
  /// retirement and for the injections above. It is the denominator of `i52_natural_win_rate`,
  /// and it is NOT the revisit trigger: a forced row can never meet that, however unblocked.
  /// A null or unrecognised reason on a forced row reads as blocked: the conservative answer
  /// keeps a row the harness cannot vouch for out of a measurement, and `Set.of` would throw on
  /// the null rather than answer it.
  public static boolean raceUnblocked(final boolean forced, final String forcedReason) {
    return !forced || (forcedReason != null && UNBLOCKED_FORCED_REASONS.contains(forcedReason.strip()));
  }

  public boolean raceUnblocked() {
    return raceUnblocked(forced, forcedReason);
  }

  public static final String TSV_HEADER = "retirementId\tengine\tbackoffClass\tforced\tforcedReason"
      + "\toriginOrdinal\toriginGeneration\tadoptedAtNanos\tretiredAtNanos\tconnectionAgeMs\topenOrder"
      + "\tcauseClass\tcauseDetail\tdeliveryThread\tthreadKind\tframeKind\tretiredNewerThanListener"
      + "\tabortSeq\tabortBeforeCallback\tabortClassifier\tabortToHandlerUs\tbuildCancelObserved"
      + "\tbuildWasDoneAtCancel\tfutureRoute\tcallbackRoute\tpingRoute\tclaimCount\terrorCountBefore"
      + "\terrorCountAfter\tretryDelayMs\tloggedRetryMs\tgapFutureToCallbackUs\tretryMarginUs"
      + "\tharnessTimeInGapUs\tcurrentOrdinalAtCallbackClaim\tsuccessorBuiltInsideGap\tverdict"
      + "\tnextAttemptOrdinal\tnextAttemptOutcome\twakeLatencyUs\tsubscriptionRecoveryMs";

  public static String tsvHeader() {
    return TSV_HEADER;
  }

  public String toTsv() {
    final var sb = new StringBuilder(512);
    toTsv(sb);
    return sb.toString();
  }

  public void toTsv(final StringBuilder sb) {
    sb.append(retirementId).append('\t')
        .append(engine).append('\t')
        .append(backoffClass).append('\t')
        .append(forced).append('\t')
        .append(forcedReason).append('\t')
        .append(originOrdinal).append('\t')
        .append(originGeneration).append('\t')
        .append(adoptedAtNanos).append('\t')
        .append(retiredAtNanos).append('\t')
        .append(connectionAgeMs).append('\t')
        .append(openOrder).append('\t')
        .append(causeClass).append('\t')
        .append(tsvSafe(causeDetail, 240)).append('\t')
        .append(tsvSafe(deliveryThread, 64)).append('\t')
        .append(threadKind).append('\t')
        .append(frameKind).append('\t')
        .append(retiredNewerThanListener).append('\t')
        .append(abortSeq).append('\t')
        .append(abortBeforeCallback).append('\t')
        .append(tsvSafe(abortClassifier, 80)).append('\t')
        .append(abortToHandlerUs).append('\t')
        .append(buildCancelObserved).append('\t')
        .append(buildWasDoneAtCancel).append('\t')
        .append(futureRoute).append('\t')
        .append(callbackRoute).append('\t')
        .append(pingRoute).append('\t')
        .append(claimCount).append('\t')
        .append(errorCountBefore).append('\t')
        .append(errorCountAfter).append('\t')
        .append(retryDelayMs).append('\t')
        .append(loggedRetryMs).append('\t')
        .append(gapFutureToCallbackUs).append('\t')
        .append(retryMarginUs).append('\t')
        .append(harnessTimeInGapUs).append('\t')
        .append(currentOrdinalAtCallbackClaim).append('\t')
        .append(successorBuiltInsideGap).append('\t')
        .append(verdict).append('\t')
        .append(nextAttemptOrdinal).append('\t')
        .append(nextAttemptOutcome).append('\t')
        .append(wakeLatencyUs).append('\t')
        .append(subscriptionRecoveryMs);
  }

  /// Tabs and line breaks would split a row; a null reads as the literal `null`, which is
  /// distinguishable from an empty string in the report.
  static String tsvSafe(final String value, final int max) {
    if (value == null) {
      return "null";
    }
    final int len = Math.min(value.length(), max);
    StringBuilder sb = null;
    for (int i = 0; i < len; ++i) {
      final char c = value.charAt(i);
      final boolean bad = c == '\t' || c == '\n' || c == '\r';
      if (bad && sb == null) {
        sb = new StringBuilder(len);
        sb.append(value, 0, i);
      }
      if (sb != null) {
        sb.append(bad ? ' ' : c);
      }
    }
    if (sb == null) {
      return len == value.length() ? value : value.substring(0, len);
    }
    return sb.toString();
  }

  public boolean doubleClaim() {
    return futureRoute == FutureRoute.ACCEPTED && callbackAccepted();
  }

  public boolean callbackAccepted() {
    return callbackRoute == CallbackRoute.ON_CLOSE_ACCEPTED || callbackRoute == CallbackRoute.ON_ERROR_ACCEPTED;
  }

  public Builder toBuilder() {
    final var b = new Builder();
    b.retirementId = retirementId;
    b.engine = engine;
    b.backoffClass = backoffClass;
    b.forced = forced;
    b.forcedReason = forcedReason;
    b.originOrdinal = originOrdinal;
    b.originGeneration = originGeneration;
    b.adoptedAtNanos = adoptedAtNanos;
    b.retiredAtNanos = retiredAtNanos;
    b.connectionAgeMs = connectionAgeMs;
    b.openOrder = openOrder;
    b.causeClass = causeClass;
    b.causeDetail = causeDetail;
    b.deliveryThread = deliveryThread;
    b.threadKind = threadKind;
    b.frameKind = frameKind;
    b.retiredNewerThanListener = retiredNewerThanListener;
    b.abortSeq = abortSeq;
    b.abortBeforeCallback = abortBeforeCallback;
    b.abortClassifier = abortClassifier;
    b.abortToHandlerUs = abortToHandlerUs;
    b.buildCancelObserved = buildCancelObserved;
    b.buildWasDoneAtCancel = buildWasDoneAtCancel;
    b.futureRoute = futureRoute;
    b.callbackRoute = callbackRoute;
    b.pingRoute = pingRoute;
    b.claimCount = claimCount;
    b.errorCountBefore = errorCountBefore;
    b.errorCountAfter = errorCountAfter;
    b.retryDelayMs = retryDelayMs;
    b.loggedRetryMs = loggedRetryMs;
    b.gapFutureToCallbackUs = gapFutureToCallbackUs;
    b.retryMarginUs = retryMarginUs;
    b.harnessTimeInGapUs = harnessTimeInGapUs;
    b.currentOrdinalAtCallbackClaim = currentOrdinalAtCallbackClaim;
    b.successorBuiltInsideGap = successorBuiltInsideGap;
    b.verdict = verdict;
    b.nextAttemptOrdinal = nextAttemptOrdinal;
    b.nextAttemptOutcome = nextAttemptOutcome;
    b.wakeLatencyUs = wakeLatencyUs;
    b.subscriptionRecoveryMs = subscriptionRecoveryMs;
    return b;
  }

  /// Mutable staging for one row. Defaults are the "unknown" values so a partially filled row is
  /// honest rather than zero-looking.
  public static final class Builder {

    public long retirementId = -1;
    public String engine = "";
    public String backoffClass = "NONE";
    public boolean forced;
    public String forcedReason = "NONE";
    public long originOrdinal = -1;
    public long originGeneration = -1;
    public long adoptedAtNanos = -1;
    public long retiredAtNanos = -1;
    public long connectionAgeMs = -1;
    public OpenOrder openOrder = OpenOrder.UNKNOWN;
    public CauseClass causeClass = CauseClass.UNKNOWN;
    public String causeDetail = "";
    public String deliveryThread = "";
    public ThreadKind threadKind = ThreadKind.OTHER;
    public FrameKind frameKind = FrameKind.NONE;
    public boolean retiredNewerThanListener;
    public long abortSeq = -1;
    public boolean abortBeforeCallback;
    public String abortClassifier = "none";
    public long abortToHandlerUs = -1;
    public boolean buildCancelObserved;
    public boolean buildWasDoneAtCancel;
    public FutureRoute futureRoute = FutureRoute.NONE_ALREADY_SETTLED;
    public CallbackRoute callbackRoute = CallbackRoute.UNOBSERVED;
    public PingRoute pingRoute = PingRoute.ABSENT;
    public int claimCount;
    public long errorCountBefore = -1;
    public long errorCountAfter = -1;
    public long retryDelayMs = -1;
    public long loggedRetryMs = -1;
    public long gapFutureToCallbackUs = -1;
    public long retryMarginUs = -1;
    public long harnessTimeInGapUs = -1;
    public long currentOrdinalAtCallbackClaim = -1;
    public boolean successorBuiltInsideGap;
    public Verdict verdict = Verdict.ATTRIBUTED_SINGLE_CLAIM;
    public long nextAttemptOrdinal = -1;
    public NextAttemptOutcome nextAttemptOutcome = NextAttemptOutcome.PENDING;
    public long wakeLatencyUs = -1;
    public long subscriptionRecoveryMs = -1;

    public RetirementRecord build() {
      return new RetirementRecord(
          retirementId, engine, backoffClass, forced, forcedReason,
          originOrdinal, originGeneration, adoptedAtNanos, retiredAtNanos, connectionAgeMs, openOrder,
          causeClass, causeDetail, deliveryThread, threadKind, frameKind, retiredNewerThanListener,
          abortSeq, abortBeforeCallback, abortClassifier, abortToHandlerUs,
          buildCancelObserved, buildWasDoneAtCancel, futureRoute, callbackRoute, pingRoute,
          claimCount, errorCountBefore, errorCountAfter, retryDelayMs, loggedRetryMs,
          gapFutureToCallbackUs, retryMarginUs, harnessTimeInGapUs, currentOrdinalAtCallbackClaim,
          successorBuiltInsideGap, verdict, nextAttemptOrdinal, nextAttemptOutcome, wakeLatencyUs,
          subscriptionRecoveryMs
      );
    }
  }
}
