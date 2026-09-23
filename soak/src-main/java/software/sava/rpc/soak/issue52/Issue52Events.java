package software.sava.rpc.soak.issue52;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// Harness-only JFR events for the issue #52 capture layer (DESIGN.md §12). They live here and
/// not in `events.SoakEvents` so this package stays independent of the core stage: the two are
/// written concurrently, and a shared class would make one compile depend on the other.
///
/// Origin threads are explicit `Thread` fields because JFR's `eventThread` is only the thread
/// that *committed* the event — for a retirement that is the delivering thread, which is what
/// we want, but for a claim or a log line it can differ from the thread whose frame it was
/// tagged with, and the attribution rule is a same-thread rule.
public final class Issue52Events {

  private Issue52Events() {
  }

  /// One `buildAsync` call, from the harness ordinal being minted to the attempt's outcome.
  @Name("sava.soak.ConnectAttempt")
  @Label("Connect Attempt")
  @Category({"sava", "soak"})
  @StackTrace(false)
  public static final class ConnectAttempt extends Event {

    @Label("Ordinal")
    public long ordinal;
    @Label("Engine")
    public String engine;
    @Label("Endpoint")
    public String endpoint;
    /// OPEN | FAILED | CANCELLED
    @Label("Outcome")
    public String outcome;
    @Label("Failure")
    public String failure;
    /// FUTURE_FIRST | ONOPEN_FIRST | UNKNOWN
    @Label("Open Order")
    public String openOrder;
    @Label("Build Thread")
    public Thread buildThread;
    @Label("Completion Thread")
    public Thread completionThread;
  }

  /// One retirement record: every `retirements.tsv` column (DESIGN.md §10) plus the harness
  /// nanoTime the row carries so JFR time and harness time can be joined.
  @Name("sava.soak.Retirement")
  @Label("Websocket Retirement")
  @Category({"sava", "soak"})
  @StackTrace(false)
  public static final class Retirement extends Event {

    @Label("Retirement Id")
    public long retirementId;
    @Label("Engine")
    public String engine;
    @Label("Backoff Class")
    public String backoffClass;
    @Label("Forced")
    public boolean forced;
    @Label("Forced Reason")
    public String forcedReason;
    @Label("Origin Ordinal")
    public long originOrdinal;
    @Label("Origin Generation")
    public long originGeneration;
    @Label("Adopted At Nanos")
    public long adoptedAtNanos;
    @Label("Retired At Nanos")
    public long retiredAtNanos;
    @Label("Connection Age Ms")
    public long connectionAgeMs;
    @Label("Open Order")
    public String openOrder;
    @Label("Cause Class")
    public String causeClass;
    @Label("Cause Detail")
    public String causeDetail;
    @Label("Delivery Thread")
    public Thread deliveryThread;
    @Label("Thread Kind")
    public String threadKind;
    @Label("Frame Kind")
    public String frameKind;
    @Label("Retired Newer Than Listener")
    public boolean retiredNewerThanListener;
    @Label("Abort Seq")
    public long abortSeq;
    @Label("Abort Before Callback")
    public boolean abortBeforeCallback;
    @Label("Abort Classifier")
    public String abortClassifier;
    @Label("Abort To Handler Us")
    public long abortToHandlerUs;
    @Label("Build Cancel Observed")
    public boolean buildCancelObserved;
    @Label("Build Was Done At Cancel")
    public boolean buildWasDoneAtCancel;
    @Label("Future Route")
    public String futureRoute;
    @Label("Callback Route")
    public String callbackRoute;
    @Label("Ping Route")
    public String pingRoute;
    @Label("Claim Count")
    public int claimCount;
    @Label("Error Count Before")
    public long errorCountBefore;
    @Label("Error Count After")
    public long errorCountAfter;
    @Label("Retry Delay Ms")
    public long retryDelayMs;
    @Label("Logged Retry Ms")
    public long loggedRetryMs;
    @Label("Gap Future To Callback Us")
    public long gapFutureToCallbackUs;
    @Label("Retry Margin Us")
    public long retryMarginUs;
    @Label("Harness Time In Gap Us")
    public long harnessTimeInGapUs;
    @Label("Current Ordinal At Callback Claim")
    public long currentOrdinalAtCallbackClaim;
    @Label("Successor Built Inside Gap")
    public boolean successorBuiltInsideGap;
    @Label("Verdict")
    public String verdict;
    @Label("Next Attempt Ordinal")
    public long nextAttemptOrdinal;
    @Label("Next Attempt Outcome")
    public String nextAttemptOutcome;
    @Label("Wake Latency Us")
    public long wakeLatencyUs;
    @Label("Subscription Recovery Ms")
    public long subscriptionRecoveryMs;
  }

  /// One accepted manager claim, i.e. one `Backoff.delay(errorCount, unit)` call.
  @Name("sava.soak.ManagerClaim")
  @Label("Manager Claim")
  @Category({"sava", "soak"})
  @StackTrace(false)
  public static final class ManagerClaim extends Event {

    @Label("Claim Seq")
    public long claimSeq;
    @Label("Engine")
    public String engine;
    @Label("Error Count")
    public long errorCount;
    @Label("Delay Millis")
    public long delayMillis;
    @Label("Route")
    public String route;
    @Label("Origin Ordinal")
    public long originOrdinal;
    @Label("Origin Quality")
    public String originQuality;
    @Label("Current Ordinal")
    public long currentOrdinal;
    @Label("Inside Build Cancel")
    public boolean insideBuildCancel;
    @Label("Claim Thread")
    public Thread claimThread;
    @Label("Harness Nanos")
    public long harnessNanos;
  }

  /// One classified log record from the manager or the engine logger.
  @Name("sava.soak.ManagerLog")
  @Label("Manager Log")
  @Category({"sava", "soak"})
  @StackTrace(false)
  public static final class ManagerLog extends Event {

    @Label("Level")
    public String level;
    @Label("Prefix Class")
    public String prefixClass;
    @Label("Message")
    public String message;
    @Label("Thrown Class")
    public String thrownClass;
    @Label("Log Thread")
    public Thread logThread;
    @Label("Origin Ordinal")
    public long originOrdinal;
    @Label("Inside Build Cancel")
    public boolean insideBuildCancel;
    @Label("Harness Nanos")
    public long harnessNanos;
  }

  /// A double-claim/misattributed retirement. The one event with a stack trace: it is rare by
  /// construction and the trace shows which engine path delivered it.
  @Name("sava.soak.Misattribution")
  @Label("Attempt Misattribution")
  @Category({"sava", "soak"})
  @StackTrace(true)
  public static final class Misattribution extends Event {

    @Label("Retirement Id")
    public long retirementId;
    @Label("Engine")
    public String engine;
    @Label("Origin Ordinal")
    public long originOrdinal;
    @Label("Successor Ordinal")
    public long successorOrdinal;
    @Label("Gap Us")
    public long gapUs;
    @Label("Retry Delay Ms")
    public long retryDelayMs;
    @Label("Error Count Inflation")
    public long errorCountInflation;
    @Label("Backoff Class")
    public String backoffClass;
    @Label("Forced")
    public boolean forced;
  }
}
