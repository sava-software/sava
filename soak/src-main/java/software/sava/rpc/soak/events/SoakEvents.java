package software.sava.rpc.soak.events;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

/// Every `sava.soak.*` event except the issue #52 ones, which live with their capture code.
///
/// The events are harness-owned on purpose: sava-rpc's published module descriptor does not
/// require `jdk.jfr`, and this work is not allowed to change that. Everything here is therefore
/// committed from the harness's side of a public seam - a wrapping consumer, a wrapping
/// `WebSocket.Builder`, the response parser's own callback - and never from inside the subject.
///
/// Two conventions the report depends on. Thresholds are set where the design measured them
/// (5 ms for a delivery, 25 ms for a call) so the ring carries the tail and the harness's own
/// histograms carry the distribution; and any thread that matters is an explicit field, because
/// JFR's `eventThread` is the thread that committed the event, which for a retirement or a
/// delivery is frequently not the thread the event is about.
public final class SoakEvents {

  private SoakEvents() {
  }

  /// A phase or sub-phase window. Duration events, so `jfr view` and the report can bound every
  /// other number by the stage that produced it.
  @Name("sava.soak.Phase")
  @Label("Soak Phase")
  @Category({"sava", "soak"})
  @StackTrace(false)
  @Description("One phase or STEADY overlay of a soak run")
  public static final class PhaseEvent extends Event {

    @Label("Name")
    public String name;

    @Label("Index")
    public int index;

    @Label("Detail")
    public String detail;
  }

  /// The ten-second gauge, committed through `FlightRecorder.addPeriodicEvent` and mirrored row
  /// for row into `client.csv`. The CSV is the whole-run series (it survives the ring and a
  /// killed JVM); the event is the same numbers with JFR's own timestamps, for correlation with
  /// everything else in the recording.
  @Name("sava.soak.Gauge")
  @Label("Soak Gauge")
  @Category({"sava", "soak"})
  @StackTrace(false)
  @Period("10 s")
  @Description("Periodic sample of the harness's live counts and the JVM's resources")
  public static final class GaugeEvent extends Event {

    @Label("Phase")
    public String phase;

    @Label("Live Subscriptions")
    public long liveSubscriptions;

    @Label("Pending Confirmations")
    public long pendingConfirmations;

    @Label("In Flight Requests")
    public long inFlightRequests;

    /// The late subset of the field above: operations outstanding past the bound their own route
    /// promises. In flight is the harness's load, overdue is the only half that says anything
    /// about the subject - a `getProgramAccounts` call drawn late in STEADY is still outstanding
    /// when the run drains, by the two-minute timeout it carries.
    @Label("Overdue Requests")
    public long overdueRequests;

    @Label("Open Connections")
    public long openConnections;

    @Label("Delivered")
    public long deliveredTotal;

    @Label("RPC Completed")
    public long rpcCompletedTotal;

    @Label("Mismatches")
    public long mismatchTotal;

    @Label("Faults Observed")
    public long faultObservedTotal;

    @Label("Retirements")
    public long retirementsTotal;

    @Label("Claims")
    public long claimsTotal;

    @Label("Misattributed")
    public long misattributedTotal;

    @Label("Retained Registrations")
    public long retainedRegistrations;

    @Label("Retained Tombstones")
    public long retainedTombstones;

    @Label("Retained Ordinals")
    public long retainedOrdinals;

    @Label("Retained Exception Subscribers")
    public long retainedExceptionSubscribers;

    @Label("Heap After Last GC")
    public long heapAfterLastGc;

    @Label("Live Threads")
    public long liveThreads;

    @Label("Open File Descriptors")
    public long openFds;

    @Label("Last Message Age Max")
    public long lastMessageAgeMillisMax;

    @Label("Virtual Threads Started")
    public long vtStarted;

    @Label("Virtual Threads Ended")
    public long vtEnded;

    @Label("Submit Failed")
    public long submitFailed;

    @Label("Pinned")
    public long pinned;

    @Label("Errors Thrown")
    public long errorsThrown;

    @Label("Common Pool Parallelism")
    public int commonPoolParallelism;

    @Label("Common Pool Queued")
    public long commonPoolQueued;

    @Label("Common Pool Active")
    public int commonPoolActive;
  }

  /// One step in a subscription's life, from the harness's side of the public API.
  @Name("sava.soak.SubscriptionEvent")
  @Label("Soak Subscription")
  @Category({"sava", "soak"})
  @StackTrace(false)
  public static final class SubscriptionEvent extends Event {

    /// REGISTER, SEND_OBSERVED, CONFIRMED, REPLAYED, UNSUB_REQUESTED, UNSUB_ACKED, REFUSED,
    /// DROPPED_ZOMBIE.
    @Label("Action")
    public String action;

    @Label("Engine")
    public String engine;

    @Label("Channel")
    public String channel;

    @Label("Key")
    public String key;

    @Label("Subscription Id")
    public long subId;

    @Label("Message Id")
    public long msgId;

    @Label("Epoch")
    public long epoch;
  }

  /// One notification handed to a consumer. Duration and stack because the 5 ms tail is where a
  /// slow or blocking consumer shows up, and the stack names which consumer it was.
  @Name("sava.soak.NotificationDelivered")
  @Label("Soak Notification Delivered")
  @Category({"sava", "soak"})
  @StackTrace(true)
  @Threshold("5 ms")
  public static final class NotificationDelivered extends Event {

    @Label("Engine")
    public String engine;

    @Label("Channel")
    public String channel;

    @Label("Key")
    public String key;

    @Label("Epoch")
    public long epoch;

    @Label("Sequence")
    public long sequence;

    @Label("Payload Chars")
    public int payloadChars;

    @Label("Consumer Kind")
    public String consumerKind;

    @Label("Threw")
    public boolean threw;

    @Label("Slow By Design")
    public boolean slowByDesign;
  }

  /// A sequence observation that was not `last + 1`. Unthrottled: these are rare by construction
  /// and every one of them is evidence.
  @Name("sava.soak.NotificationAnomaly")
  @Label("Soak Notification Anomaly")
  @Category({"sava", "soak"})
  @StackTrace(false)
  public static final class NotificationAnomaly extends Event {

    /// GAP, DUP, REORDER, ZOMBIE, FIRST_AFTER_ADOPT, LONG_GAP.
    @Label("Kind")
    public String kind;

    @Label("Engine")
    public String engine;

    @Label("Key")
    public String key;

    @Label("Epoch")
    public long epoch;

    @Label("Expected")
    public long expected;

    @Label("Actual")
    public long actual;

    @Label("Connection Ordinal")
    public long connectionOrdinal;
  }

  /// One JSON-RPC call, at the harness's own boundary around the client.
  @Name("sava.soak.RpcCall")
  @Label("Soak RPC Call")
  @Category({"sava", "soak"})
  @StackTrace(false)
  @Threshold("25 ms")
  public static final class RpcCall extends Event {

    /// Which of the three clients: plain, compressed, or extended-with-testResponse.
    @Label("Client")
    public String client;

    @Label("Method")
    public String method;

    @Label("Request Id")
    public long requestId;

    /// OK, RPC_ERROR, HTTP_ERROR, TIMEOUT, CANCELLED, MISMATCH, TRANSPORT.
    @Label("Outcome")
    public String outcome;

    @Label("HTTP Status")
    public int httpStatus;

    @Label("Response Bytes")
    public long responseBytes;

    @Label("Gzip")
    public boolean gzip;

    @Label("Encoding")
    public String encoding;

    /// WRAPPED (a default route, deadline applies) or NO_WRAP (a caller body handler, no
    /// deadline property).
    @Label("Route")
    public String route;

    @Label("Peer Delay Millis")
    public long peerDelayMillis;

    @Label("Fault Kind")
    public String faultKind;
  }

  /// Emitted for every non-OK outcome plus one heartbeat per thousand OK ones, so a recording
  /// whose `RpcCall` events are all under threshold still carries the outcome mix.
  @Name("sava.soak.RpcOutcome")
  @Label("Soak RPC Outcome")
  @Category({"sava", "soak"})
  @StackTrace(false)
  public static final class RpcOutcome extends Event {

    @Label("Method")
    public String method;

    @Label("Outcome")
    public String outcome;

    @Label("Request Id")
    public long requestId;

    @Label("Fault Kind")
    public String faultKind;

    @Label("Elapsed Millis")
    public long elapsedMillis;

    @Label("Failure")
    public String failure;
  }

  /// The window between a connection-level fault and every live registration being confirmed
  /// again: the P7 evidence, as a duration event.
  @Name("sava.soak.Recovery")
  @Label("Soak Recovery")
  @Category({"sava", "soak"})
  @StackTrace(false)
  public static final class Recovery extends Event {

    @Label("Fault Ordinal")
    public long faultOrdinal;

    @Label("Engine")
    public String engine;

    @Label("Fault Kind")
    public String faultKind;

    @Label("Origin Ordinal")
    public long originOrdinal;

    @Label("Attempts Used")
    public int attemptsUsed;

    @Label("Registrations")
    public int registrations;

    @Label("Confirmed")
    public int confirmed;

    /// Registrations the window stopped owing because the caller cancelled them or the engine
    /// completed them inside it.
    @Label("Released")
    public int released;

    /// From the fault row to the confirmation or release that completed the window, or to its
    /// close. Carried as a field because the event is committed at the close and JFR's own
    /// duration would only span the commit.
    @Label("Elapsed ms")
    public long elapsedMillis;

    @Label("Complete")
    public boolean complete;

    @Label("Over Budget")
    public boolean overBudget;

    /// A later connection-level fault displaced this window while it was still inside its budget
    /// and incomplete: not evaluated, and the report keeps it out of the over-budget count.
    @Label("Superseded")
    public boolean superseded;

    /// The window owed nothing (no live registration, or every owed one released before any was
    /// confirmed): not evaluated, and not an episode.
    @Label("Vacuous")
    public boolean vacuous;
  }

  /// A fault the client observed, joined to the peer's `faults-applied.tsv` by ordinal.
  @Name("sava.soak.FaultObserved")
  @Label("Soak Fault Observed")
  @Category({"sava", "soak"})
  @StackTrace(false)
  public static final class FaultObserved extends Event {

    @Label("Kind")
    public String kind;

    @Label("Ordinal")
    public long ordinal;

    @Label("Scope")
    public String scope;

    @Label("Target")
    public String target;

    @Label("Detail")
    public String detail;
  }

  /// The run's own verdict inputs, committed once at SHUTDOWN so a recording read on its own
  /// still says how the run ended.
  @Name("sava.soak.Verdict")
  @Label("Soak Verdict")
  @Category({"sava", "soak"})
  @StackTrace(false)
  public static final class Verdict extends Event {

    @Label("Status")
    public String status;

    @Label("Reasons")
    public String reasons;

    @Label("Notes")
    public String notes;

    @Label("Issue 52")
    public String issue52;
  }
}
