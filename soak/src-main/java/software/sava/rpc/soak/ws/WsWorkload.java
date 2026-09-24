package software.sava.rpc.soak.ws;

import software.sava.core.accounts.PublicKey;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.JsonRpcException;
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.rpc.json.http.ws.Subscription;
import software.sava.rpc.soak.Counters;
import software.sava.rpc.soak.GaugeSampler;
import software.sava.rpc.soak.Phase;
import software.sava.rpc.soak.SoakConfig;
import software.sava.rpc.soak.SoakContext;
import software.sava.rpc.soak.Workload;
import software.sava.rpc.soak.control.HarnessControls;
import software.sava.rpc.soak.events.SoakEvents;
import software.sava.rpc.soak.issue52.AttemptTracker;
import software.sava.rpc.soak.issue52.ClaimRecord;
import software.sava.rpc.soak.issue52.HarnessBackoffs;
import software.sava.rpc.soak.issue52.ManagerLogCapture;
import software.sava.rpc.soak.issue52.RetirementRecord;
import software.sava.rpc.soak.oracle.Properties;
import software.sava.rpc.soak.oracle.Property;
import software.sava.rpc.soak.oracle.RecoveryLedger;
import software.sava.services.solana.websocket.WebSocketManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/// The websocket driver: four engines, a deterministic churn plan each, and the oracles for every
/// W1 and W2 property plus P7.
///
/// This is first-party defensive testing of sava-rpc's own websocket engine. The peers it talks to
/// deliberately swallow subscribe requests, grant duplicate ids, send notifications for
/// subscriptions that were never granted, truncate fragmented messages and reset connections mid
/// handshake, because those are the things a hostile or broken node does to a client that parses
/// what it is sent. Finding the engine's reaction to them here is the point.
///
/// Two independent records make the assertions possible. The harness made every subscription call,
/// so it knows what *should* be live; and the peer logs every frame it received and wrote, so there
/// is a second account of what actually crossed the wire. Neither is derived from the engine's own
/// registry, which is what would make the oracle circular. The join between them is the JSON-RPC
/// message id: the engine assigns one per registration and re-sends the same frame on every replay,
/// so a peer row names a harness registration without the peer ever logging request parameters.
///
/// Everything time-based is bounded by the guarantee it tests, never by a guess: the replay bound
/// comes from `subscriptionResendDelay`'s javadoc, the escalation bound from the same javadoc's
/// "times four" clause, and the ping bounds from `pingDelay`'s two windows.
public final class WsWorkload implements Workload, GaugeSampler.GaugeSource, ConsumerFactory.Sink {

  /// The workload name, used as the gauge source name and in property examples.
  public static final String NAME = "ws";

  /// How many deferred oracle checks may be outstanding at once. Each is a timer task holding a few
  /// longs; the cap exists so a pathological fault rate cannot turn the oracle into the run's own
  /// retention defect. Overflow is counted, never silent.
  static final int MAX_PENDING_CHECKS = 8192;

  /// How many peer connections stay joinable. A run adopts one connection per retirement, so this
  /// is hours of churn; older views are evicted and counted.
  static final int MAX_CONNECTION_VIEWS = 256;

  /// Per connection, how many message ids stay joinable.
  static final int MAX_MSG_IDS_PER_CONNECTION = 2048;

  /// How many attempt ordinals keep their epoch record for the deferred checks to read.
  static final int MAX_EPOCH_RECORDS = 512;

  /// The grace after a retirement within which an applied fault still explains it.
  static final long RETIREMENT_EXPLANATION_MILLIS = 5_000L;

  /// W1-F's and W2-C's containment window: the connection must still be alive this long after the
  /// event that should not have killed it.
  static final long CONTAINMENT_MILLIS = 2_000L;

  /// W1-I's bound: `close()`'s javadoc promises the transport is aborted "a few seconds later" even
  /// if the peer never answers the close frame.
  static final long CLOSE_BOUND_MILLIS = 10_000L;

  /// W2-B's bound. The overflow abort is synchronous with the offending message's reassembly, so
  /// this is generous by an order of magnitude and only fails a connection that survived.
  static final long OVERFLOW_BOUND_MILLIS = 10_000L;

  /// Slack on a bound that joins a peer timestamp to a client one. Both processes read the same
  /// wall clock on the same host, so this covers the write and the loopback hop, nothing more; it
  /// is never used to widen a bound the engine is judged against.
  static final long CLOCK_JOIN_SLACK_MILLIS = 250L;

  /// Keys in one of the per-kind or per-property tallies. They come off log lines, so the map is
  /// capped; the ceiling is far above the peer's whole fault catalogue.
  static final int MAX_TALLY_KEYS = 128;

  /// Retiring fault kinds remembered per attempt. A connection that took more than a handful is
  /// already unreadable for attribution purposes.
  static final int MAX_KINDS_PER_ATTEMPT = 16;

  private static final String CHECKS_DROPPED = "ws.harness.checksDropped";
  /// A containment check (W1-F, W2-C) whose retirement a destructive fault on the same attempt
  /// explains: not evidence about the property either way, so neither a pass nor a failure.
  static final String CONTAINMENT_EXCUSED = "ws.containment.excused";
  /// A containment check the *retirement's own recorded cause* excuses: the connection went for a
  /// reason that is not the notification or the consumer throw the property is about. Its own name
  /// rather than [#CONTAINMENT_EXCUSED], because that one says "a fault the schedule applied
  /// explains it" and this one says "the engine's retirement record names something else", and a
  /// reviewer reading a run with nothing to say needs to know which.
  private static final String CONTAINMENT_EXCUSED_BY_CAUSE = "containment.excusedByCause";
  /// A retirement the peer's own `overflow` row explains: its outbound queue filled and it closed
  /// 1011. That is the peer running out of room, not the engine reacting to anything sava did, so
  /// it is neither an expected retirement nor an unexplained one.
  private static final String RETIREMENT_PEER_OVERFLOW = "ws.retirement.peerOverflow";
  /// Retirements of the OVERFLOW engine by its own configured cap, whatever message tripped it.
  private static final String RETIREMENT_OVERFLOW_CAP = "ws.retirement.overflowCap";
  /// Retirements the engine escalated as unanswered after the peer refused a subscribe -32603.
  private static final String RETIREMENT_REJECT_ESCALATED = "ws.retirement.rejectEscalated";
  /// W1-G samples that read 0 because the engine had no connection at that instant.
  private static final String W1G_CONNECTION_GONE = "ws.harness.w1gConnectionGone";
  /// W1-H(ii) checks dropped because the peer sent a frame after the swallowed ping.
  private static final String W1H_CONTACT_AFTER_SWALLOW = "ws.harness.w1hContactAfterSwallow";
  /// W1-H(ii) passes whose retirement the engine reported as its ping probe timing out.
  private static final String W1H_PROBE_RETIREMENTS = "ws.harness.w1hProbeRetirements";
  /// W1-H(ii) passes retired by another detector inside the bound — the unanswered-request
  /// deadline the same silence starves, when the ping window is longer than four resend delays.
  private static final String W1H_OTHER_RETIREMENTS = "ws.harness.w1hOtherRetirements";
  /// Agave's node-limit refusal, the server-condition code sava keeps pending rather than retiring.
  private static final long SUBSCRIPTION_REFUSED = -32603L;
  /// Retirements on the engine whose consumer the stall-callback control parked.
  private static final String RETIREMENT_HARNESS_STALLED = "ws.retirement.harnessStalled";
  /// A subscribe the peer answered with a code `SolanaJsonRpcWebsocket.isRequestDefect` treats as
  /// terminal, joined back to the registration it retired.
  private static final String REQUEST_DEFECT_RETIRED = "ws.subscribe.requestDefectRetired";
  /// The same rejection seen from the client side, through `exceptionSubscribe`. It cannot be
  /// joined to a registration — see [#onNewWebSocket] — so it is a corroborating count only.
  private static final String REQUEST_DEFECT_OBSERVED = "ws.subscribe.requestDefectObserved";
  /// A retirement record whose origin attempt is unknown, so no epoch could be closed for it.
  private static final String RETIREMENT_WITHOUT_ORDINAL = "ws.harness.retirementWithoutOrdinal";
  /// Retirements this workload saw through `onClose`/`onError`. Those callbacks carry no attempt
  /// token, so the count is an observation total and never an attribution; comparing it with
  /// `ws.epoch.retired` is what says how often the two channels reported one retirement twice.
  private static final String RETIREMENT_CALLBACKS = "ws.harness.retirementCallbacks";
  /// A `connect()` future that completed exceptionally on the bare engine, so the harness had to
  /// re-arm the reconnect itself: a failed build is not a retirement and fires no retirement hook.
  private static final String BARE_CONNECT_FAILED = "ws.harness.bareConnectFailed";
  /// A fault applied before its connection's handshake completed, whose recovery window was
  /// therefore anchored at the next adoption rather than at the fault.
  private static final String RECOVERY_ANCHORED_AT_ADOPTION = "ws.harness.recoveryAnchoredAtAdoption";
  /// A pending fault whose row named the very attempt being adopted, held for the next adoption:
  /// that attempt is the fault's casualty, not the recovery.
  private static final String RECOVERY_HELD_PAST_CASUALTY = "ws.harness.recoveryHeldPastCasualty";
  /// A pending fault replaced by a newer one before any adoption anchored it: no record, no verdict.
  private static final String RECOVERY_PENDING_OVERTAKEN = "ws.harness.recoveryPendingOvertaken";
  /// A W1-A or P7 judgment made while the tail had still not read past the bound it judges, after
  /// the grace ran out: the verdict may be charging the harness's own read latency to the engine.
  private static final String JUDGED_UNDER_TAIL_LAG = "ws.harness.judgedUnderTailLag";
  /// Replay rows the peer wrote after W1-A's bound: late by the wire's clock, not credited.
  private static final String REPLAY_ROWS_LATE = "ws.harness.replayRowsLate";
  /// Plan steps on the generic channel a live run skipped: a real node has no synthetic method.
  private static final String LIVE_GENERIC_SKIPPED = "ws.harness.liveGenericSkipped";
  /// The duplicate subscribes a SUBSCRIBE_DUPLICATE_PARAMS step handed the library directly, and
  /// how many it accepted (the contract is none) or threw on.
  private static final String DUPLICATE_OFFERED = "ws.plan.duplicateOffered";
  private static final String DUPLICATE_ACCEPTED = "ws.plan.duplicateAccepted";
  private static final String DUPLICATE_THREW = "ws.plan.duplicateThrew";
  /// `close()` reached the bare engine with no connection the peer could see closing.
  private static final String CLOSE_WITHOUT_LIVE_CONNECTION = "ws.harness.closeWithoutLiveConnection";
  /// A delivery to a registration the harness had tombstoned but whose unsubscribe it had not yet
  /// sent — the D7 control's staged client-side bookkeeping defect.
  private static final String DELIVERY_TO_TOMBSTONED = "ws.harness.deliveryToTombstoned";
  private static final String PEER_ROWS_DROPPED = "ws.harness.peerRowsDropped";
  private static final String PEER_ROWS_READ = "ws.harness.peerRowsRead";
  private static final String CONNECTION_VIEWS_EVICTED = "ws.harness.connectionViewsEvicted";
  private static final String ORACLE_SLOTS_EXHAUSTED = "ws.harness.oracleSlotsExhausted";
  private static final String REPLAY_EPISODES_SUPERSEDED = "ws.harness.replayEpisodesSuperseded";
  private static final String MSG_IDS_EVICTED = "ws.harness.msgIdsEvicted";
  /// A `SUBSCRIBE` step refused because the engine already holds `SOAK_WS_SUBSCRIPTIONS`
  /// registrations. Its own name, not `harness.opsSkipped.inflightCap`: the cap is the plan's
  /// configured ceiling, and a driver sitting at its ceiling is driving the subject at full
  /// subscription load, which is the opposite of starvation.
  private static final String SUBSCRIBE_AT_CAP = "ws.plan.subscribeAtCap";
  /// A subscribe the peer answered with an error and the engine then re-sent under the same
  /// JSON-RPC id. Observed, never asserted: a refused request is an answered one, so a retry is
  /// the engine's policy rather than the duplicate W1-B forbids.
  private static final String RESENT_AFTER_ERROR = "ws.subscribe.resentAfterError";
  /// A one-shot signature registration the engine unsubscribed on its own after the terminal
  /// notification, which is that channel's documented lifecycle.
  private static final String SIGNATURE_TERMINATED = "ws.signature.terminated";
  /// Terminal signature notifications the consumer saw; equal to the terminated count in a
  /// healthy run (the fallback path only adds what the delivery path missed).
  private static final String SIGNATURE_TERMINAL_OBSERVED = "ws.signature.terminalObserved";
  /// An unsubscribe the engine sent for a registration the harness still holds and never
  /// cancelled, on a channel that is not one-shot. Observed, never asserted.
  private static final String ENGINE_UNSUBSCRIBED_LIVE = "ws.harness.engineUnsubscribedLive";
  /// A `MESSAGE_OVERFLOW` fault applied to an engine whose `maxMessageLength` is above the
  /// message, so nothing overflowed and W2-B has nothing to say.
  private static final String OVERFLOW_BELOW_CAP = "ws.harness.overflowBelowCap";
  /// A `sub_req` row with no `fp=` fingerprint, so W1-B fell back to keying on the JSON-RPC id and
  /// cannot see a duplicate that arrived under a fresh one. Non-zero says which rule the run's
  /// W1-B verdicts were actually reached under.
  private static final String W1B_MSG_ID_FALLBACK = "ws.harness.w1bMsgIdFallback";

  /// Where one engine's [AttemptTracker.Stats] lands in `counters.properties`.
  private static final String I52_HARNESS_PREFIX = "i52.harness.";

  /// The two [AttemptTracker.Stats] entries that are process-wide rather than per engine: the
  /// delivery-frame registry is one static structure every tracker reads, and `frameRegistryThreads`
  /// is a *level* rather than a count. Summing either over four engines would report the same
  /// number four times, so they are set once from one place instead.
  private static final Set<String> PROCESS_WIDE_TRACKER_STATS =
      Set.of("frameRegistryOverflow", "frameRegistryThreads");

  /// The longest `fp=` token the join will read. The peer writes a fixed-width hash; the cap is
  /// there so a corrupted row cannot turn a whole line into one key.
  private static final int MAX_FINGERPRINT_CHARS = 32;

  /// The fault kinds a deferred check names as its own stimulus. Declared once because each is used
  /// both in the mirrored catalogue below and in the attribution test that keeps one fault's
  /// retirement from being read as another fault's evidence.
  private static final String KIND_SWALLOW_SUBSCRIBE = "SWALLOW_SUBSCRIBE";
  private static final String KIND_SWALLOW_UNSUB = "SWALLOW_UNSUB";
  private static final String KIND_SWALLOW_PING = "SWALLOW_PING";
  private static final String KIND_MESSAGE_OVERFLOW = "MESSAGE_OVERFLOW";
  private static final String KIND_ZOMBIE_NOTIFICATION = "ZOMBIE_NOTIFICATION";
  private static final String KIND_UNKNOWN_SUB_NOTIFICATION = "UNKNOWN_SUB_NOTIFICATION";
  /// The two faults that answer a subscribe with an error, and the only thing that distinguishes
  /// them: `ERROR_RESPONSE` sends -32602 and `REJECT_SUBSCRIBE` sends -32603. The peer's `sub_err`
  /// row carries the message id but not the code, so this pair is what tells the two apart — and
  /// they part company in sava, which retires the registration for the first and retries it under
  /// the second.
  private static final String KIND_ERROR_RESPONSE = "ERROR_RESPONSE";
  private static final String KIND_REJECT_SUBSCRIBE = "REJECT_SUBSCRIBE";

  /// Retirement causes the engine reports through `onError`, which is the observation W1-H(ii) and
  /// W2-B are about. Taken from the attempt tracker's record because that record names the attempt
  /// it belongs to and an `onError` callback does not.
  private static final Set<RetirementRecord.CauseClass> ERROR_REPORTED_CAUSES = EnumSet.of(
      RetirementRecord.CauseClass.JDK_ERROR,
      RetirementRecord.CauseClass.UNANSWERED_REQUEST,
      RetirementRecord.CauseClass.PING_SEND_TIMEOUT,
      RetirementRecord.CauseClass.PING_RESPONSE_TIMEOUT,
      RetirementRecord.CauseClass.PING_SEND_FAILURE,
      RetirementRecord.CauseClass.MAX_MESSAGE_OVERFLOW,
      RetirementRecord.CauseClass.ID_COLLISION,
      RetirementRecord.CauseClass.UNSUB_NONEQUIVALENT);

  /// Retirement causes that are not the engine reacting to the notification or the consumer throw a
  /// containment property is about. A transport the JDK closed or errored, a probe that timed out,
  /// a request that went unanswered, a message past the cap, an instance that died, a build
  /// cancelled before install and the harness's own injected retirement each end the connection for
  /// a reason of their own, so a containment check that finds the connection gone for one of them
  /// has observed nothing about its own property.
  private static final Set<RetirementRecord.CauseClass> CONTAINMENT_EXCUSING_CAUSES = EnumSet.of(
      RetirementRecord.CauseClass.JDK_CLOSE,
      RetirementRecord.CauseClass.JDK_ERROR,
      RetirementRecord.CauseClass.UNANSWERED_REQUEST,
      RetirementRecord.CauseClass.PING_SEND_TIMEOUT,
      RetirementRecord.CauseClass.PING_RESPONSE_TIMEOUT,
      RetirementRecord.CauseClass.PING_SEND_FAILURE,
      RetirementRecord.CauseClass.MAX_MESSAGE_OVERFLOW,
      RetirementRecord.CauseClass.INSTANCE_DEATH,
      RetirementRecord.CauseClass.CANCELLED_BEFORE_INSTALL,
      RetirementRecord.CauseClass.HARNESS_INJECTED);

  /// The fault kinds the peer marks `expectedRetirement()`. Mirrored as names because
  /// `peer.FaultKind` is package-private to the peer, which is correct: the client must not be able
  /// to reach into the peer's catalogue, only to recognise the names it writes into its own log.
  ///
  /// Membership is what admits a fault as an explanation, so a missing name charges sava with a
  /// retirement the harness asked for and a surplus one excuses a retirement nobody asked for.
  private static final Set<String> EXPECTED_RETIREMENT_KINDS = Set.of(
      "TCP_RESET", "HALF_CLOSE", "CLOSE_FRAME", "HANDSHAKE_500", "HANDSHAKE_STALL",
      "HANDSHAKE_BAD_ACCEPT", "HANDSHAKE_RST", "CONNECT_REFUSE", KIND_SWALLOW_PING,
      KIND_SWALLOW_SUBSCRIBE, "DUP_SUBID", KIND_MESSAGE_OVERFLOW,
      // the JDK's own reader rejects the frame that follows a dangling one and closes the
      // transport, so this fault retires the connection below sava (peer FaultKind, measured)
      "DANGLING_FRAGMENT",
      // A swallowed unsubscribe is a sent-but-never-answered request, and `subscriptionResendDelay`
      // retires the connection for one after four resend delays — the same escalation W1-C asserts
      // for a swallowed subscribe. While this name was missing, every fault control read the
      // engine's correct escalation as `ws.retirement.unexplained` and failed the W1-F and W2-C
      // containment checks that shared the attempt.
      KIND_SWALLOW_UNSUB,
      // The one control-only kind the peer also marks expectedRetirement(): reusing one subId
      // across different params kills the id the engine is waiting on, so the connection the
      // control asked to retire is retired. D1 (DROP_REPLAYED_SUBSCRIBES) is deliberately absent:
      // the peer now answers the replay it refuses to record, so nothing retires and a name here
      // would excuse a retirement nobody asked for.
      "SUBID_REUSE_ACROSS_PARAMS");

  /// The engine the `I1-*` controls repurpose as the forced #52 reproduction.
  ///
  /// Repurposed rather than added as a fifth engine because the four profiles are indexed into
  /// `SOAK_WS_PORTS` and into the run's sequence oracle, and the fifth port belongs to the churn
  /// workload: a new profile would take the churn port and the oracle slot above it. This one is
  /// the natural donor — it is already a manager, and its ordinary backoff is the one the forced
  /// mode replaces with a constant zero.
  private static final EngineProfile FORCED_ENGINE = EngineProfile.ZERO_INITIAL_ESCALATING;

  /// How many retirements a forced control injects. The forced engine reconnects as fast as the
  /// manager will let it under a constant-zero backoff, so this is a bound on the run rather than a
  /// target: `i52_natural_win_rate` needs a denominator in the low hundreds and an unbounded
  /// injector would spend a 120 s control run hammering the peer.
  private static final long MAX_FORCED_INJECTIONS = 200L;

  /// The step between armed attempt ordinals, for every forced mode.
  ///
  /// One, not three. A client-side injection is the only thing retiring this engine's connections,
  /// so with a step above one the first un-injected attempt has no reason to end and the series
  /// stalls on it: `I1-forced` armed one injection and fired none. Keeping clean connections
  /// between injections was the argument for a wider step, and it costs the control the very
  /// repetitions it exists to produce.
  private static final int FORCED_INJECTION_PERIOD = 1;

  /// The arming, counted once. The tracker owns what happened afterwards — `injectedRetirements`
  /// and `injectionCapReached` are exported under `i52.harness.*` — so there is no second copy of
  /// the ceiling here to disagree with it.
  private static final String FORCED_INJECTIONS_ARMED = "ws.harness.i52InjectionsArmed";

  /// How long a `sub_err` row waits before it is read as a rejection of a particular class. The
  /// code is not in the row; it is in the `faults-applied.tsv` record the peer wrote immediately
  /// before the frame, and that record reaches the harness through a second tail. The wait is the
  /// tail's poll period several times over, so the two are joined from the files rather than from
  /// an assumption about which one flushed first.
  private static final long REQUEST_DEFECT_JOIN_MILLIS = 1_000L;

  /// How far apart the peer's own two timestamps for one rejection may be for the join to hold: the
  /// fault record and the frame it explains are written by the same thread, microseconds apart, so
  /// this is generous by three orders of magnitude and exists only to keep a millisecond boundary
  /// from splitting the pair.
  private static final long REJECTION_JOIN_SLACK_MILLIS = 250L;

  private static final long BARE_RECONNECT_MIN_MILLIS = 250L;
  private static final long BARE_RECONNECT_MAX_MILLIS = 30_000L;
  private static final long TAIL_POLL_MILLIS = 200L;
  /// How long a W1-A or P7 judgment waits, past its own bound, for the tail to have read the
  /// peer's rows up to that bound before judging anyway. Measured on a campaign: the tail fell
  /// about 20 s behind the peer for half a minute, several times in eight hours, and a replay
  /// that was on the wire inside 9 ms read as three registrations never re-sent.
  private static final long TAIL_GRACE_MILLIS = 60_000L;
  private static final long TAIL_WAIT_STEP_MILLIS = 1_000L;
  /// Once the tail is at the end of the file and this much has passed since the deadline, the
  /// peer has flushed anything it wrote before it (its own log lag is measured in tens of
  /// milliseconds), so a judgment may proceed even though no newer row moved the watermark — a
  /// quiet peer (quiesce, drain) otherwise leaves every deadline waiting out the whole grace.
  private static final long TAIL_SETTLE_MARGIN_MILLIS = 2_000L;
  /// Every this many rows the newest row's read latency goes to the `ws.tail.lag` histogram, plus
  /// every row read more than a second after the peer wrote it.
  private static final int TAIL_LAG_SAMPLE_EVERY = 256;
  private static final long DRIVER_JOIN_MILLIS = 5_000L;

  private final EnumMap<EngineProfile, EngineHandle> engines = new EnumMap<>(EngineProfile.class);
  private final Map<Integer, EngineHandle> byPort = new ConcurrentHashMap<>(8);
  private final EnumMap<EngineProfile, SubscriptionPlan> plans = new EnumMap<>(EngineProfile.class);
  private final EnumMap<EngineProfile, AtomicReference<PendingReplay>> replays =
      new EnumMap<>(EngineProfile.class);
  private final EnumMap<EngineProfile, AtomicLong> bareBackoff = new EnumMap<>(EngineProfile.class);
  private final List<Thread> drivers = new ArrayList<>(4);
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicInteger pendingChecks = new AtomicInteger();
  private final AtomicLong connectionViewOverflow = new AtomicLong();
  private final AtomicLong epochRecordOverflow = new AtomicLong();

  /// Keyed on `(port, connId)`: the peer numbers connections per port, so four ports' first
  /// connections all read `connId=1` and a view keyed on the id alone would merge them.
  private final Map<Long, ConnectionView> connections =
      bounded(MAX_CONNECTION_VIEWS, connectionViewOverflow);
  private final Map<Long, EpochRecord> epochsByAttempt =
      bounded(MAX_EPOCH_RECORDS, epochRecordOverflow);
  private final Map<Long, AttemptFaults> faultsByAttempt = bounded(MAX_EPOCH_RECORDS, new AtomicLong());
  /// The two subscribe-answering faults, per attempt, with the peer's own timestamp for each. They
  /// are not retiring faults and so have no place in [#faultsByAttempt]; they are kept because the
  /// `sub_err` frame they produce carries no error code, and which of the two answered decides
  /// whether sava retired the registration or will retry it.
  private final Map<Long, SubscribeAnswers> answersByAttempt =
      bounded(MAX_EPOCH_RECORDS, new AtomicLong());
  /// The engines by the name their retirement records carry, which is how a record is routed back
  /// to the handle whose epoch it closes.
  private final Map<String, EngineHandle> byEngineName = new ConcurrentHashMap<>(8);
  /// Per engine, one fault that landed before a connection existed, waiting for the adoption its
  /// recovery window is measured from.
  private final EnumMap<EngineProfile, AtomicReference<PendingFault>> pendingFaults =
      new EnumMap<>(EngineProfile.class);

  /// How many faults of each kind the peer recorded as *applied*, counted straight off
  /// `faults-applied.tsv`. A stated skip may say "no `<kind>` fault was applied in this run" only
  /// when this says so; the reason text is read as a statement of fact about the run.
  private final Map<String, AtomicLong> appliedByKind = new ConcurrentHashMap<>(64);
  /// Per property: deferred checks whose bound had not expired when the run ended, checks that
  /// could not be joined to a connection attempt, checks confounded by another retiring fault on
  /// the same attempt, and checks whose retirement an applied fault already explains. Each is a
  /// different reason a property can end a run with nothing to say, and a skip reason that cannot
  /// name one of them is not stated at all.
  private final Map<String, AtomicLong> checksOutstanding = new ConcurrentHashMap<>(16);
  private final Map<String, AtomicLong> checksUnjoinable = new ConcurrentHashMap<>(16);
  private final Map<String, AtomicLong> checksConfounded = new ConcurrentHashMap<>(16);
  private final Map<String, AtomicLong> checksExcused = new ConcurrentHashMap<>(16);
  /// `sub_req` and `ping_in` rows the join actually saw, and silence windows the ping sweep judged:
  /// the antecedents of W1-B and W1-H, which no counter in `counters.properties` records.
  private final AtomicLong subRequestRows = new AtomicLong();
  private final AtomicLong pingRows = new AtomicLong();
  private final AtomicLong silenceWindows = new AtomicLong();
  /// Program and keyed-program notifications delivered: W1-J's antecedent, and the only thing that
  /// can honestly account for that property having nothing to say.
  private final AtomicLong programNotifications = new AtomicLong();
  /// Replay episodes that settled without a W1-A verdict because nothing was owed: no registration
  /// was live at the retirement, or none of the live ones had reached the wire.
  private final AtomicLong replayEpisodesWithoutVerdict = new AtomicLong();

  /// The forced #52 mode this run is under, null for every ordinary run.
  private ForcedMode forcedMode;

  /// How long the harness holds a cancellation in its own registry before the unsubscribe goes on
  /// the wire. Zero everywhere but the `D7` control; see [#unsubscribe].
  private long unsubscribeTombstoneLeadMillis;

  private SoakContext ctx;
  private SoakConfig config;
  private Counters counters;
  private ConsumerFactory consumers;
  private PublicKey[] keyTable;
  private Thread tailThread;
  private TailReader wsTail;
  private TailReader faultTail;
  private volatile boolean tailRunning;
  private volatile boolean peerOracleAvailable;

  /// Constructed with nothing: every collaborator arrives through [#start(SoakContext)], which is
  /// also where the engines are built, so a workload that is never started has allocated nothing.
  public WsWorkload() {
  }

  @Override
  public String name() {
    return NAME;
  }

  // ----------------------------------------------------------------------------------- start

  @Override
  public void start(final SoakContext context) throws Exception {
    this.ctx = context;
    this.config = context.config();
    this.counters = context.counters();
    this.keyTable = context.keyTable();
    this.consumers = new ConsumerFactory(context.seed(), counters, this,
        config.slowConsumerMicros(), config.throwEvery(), HarnessControls.stallCallback(config));
    this.peerOracleAvailable = !config.live();

    // One global JUL handler, installed before any manager exists: the manager's own reconnect
    // decisions are only visible in its log, and a handler attached after the first failure would
    // miss exactly the line the #52 evidence is built from.
    final var logCapture = ManagerLogCapture.install();
    if (!logCapture.selfCheck()) {
      throw new IllegalStateException(
          "ManagerLogCapture could not classify a synthetic attempt failure; the #52 claim routing "
              + "would be blind for this run");
    }

    this.forcedMode = ForcedMode.of(config.control());
    this.unsubscribeTombstoneLeadMillis = HarnessControls.unsubscribeTombstoneLeadMillis(config);

    final var ports = config.wsPorts();
    for (final var profile : EngineProfile.values()) {
      if (!config.live() && profile.portIndex() >= ports.size()) {
        throw new IllegalStateException("SOAK_WS_PORTS has " + ports.size()
            + " entries; engine " + profile.engineName() + " needs index " + profile.portIndex());
      }
      // A live run has no peer and no port list: every engine dials SOAK_LIVE_WS_URL, and the
      // port that keys the peer-row joins (which a live run never makes) is the profile's index,
      // unique per engine and never a real port. Measured 2026-09-23 on the first live launch,
      // which failed at start-up on the empty list.
      final int port = config.live() ? profile.portIndex() : ports.get(profile.portIndex());
      final boolean forced = forced(profile);
      final var tracker = AttemptTracker.forEngine(profile.engineName(),
          this::onRetirement, this::onClaim, forced, config.live());
      if (forced) {
        configureForced(tracker);
      }
      final var handle = new EngineHandle(profile, port, tracker, config.live());
      engines.put(profile, handle);
      byPort.put(port, handle);
      byEngineName.put(handle.name(), handle);
      plans.put(profile, new SubscriptionPlan(context.seed(), profile.oracleIndex()));
      replays.put(profile, new AtomicReference<>());
      pendingFaults.put(profile, new AtomicReference<>());
      bareBackoff.put(profile, new AtomicLong(BARE_RECONNECT_MIN_MILLIS));
      build(handle);
    }

    startPeerTail();
    running.set(true);
    for (final var handle : engines.values()) {
      seedRegistrations(handle);
      final var thread = new Thread(() -> drive(handle), "soak-ws-" + handle.name());
      thread.setDaemon(true);
      drivers.add(thread);
      thread.start();
    }

    // The tracker's lazy frames age out on a tick rather than on a delivery, so the sweep has to
    // come from somewhere; the gauge cadence is the one period the run already agrees on.
    ctx.timer().scheduleWithFixedDelay(this::periodicSweep,
        config.gaugeSeconds(), config.gaugeSeconds(), TimeUnit.SECONDS);
    context.registerGauge(this);
  }

  private void build(final EngineHandle handle) {
    final var profile = handle.profile();
    final var tracker = handle.tracker();
    final var prototype = SolanaRpcWebsocket.build()
        .uri(config.live() ? URI.create(config.liveWsUrl()) : URI.create("ws://127.0.0.1:" + handle.port()))
        .webSocketBuilder(tracker.wrap(ctx.httpClient()))
        .connectTimeout(config.connectTimeoutMillis())
        .pingDelay(config.pingDelayMillis())
        .subscriptionAndPingCheckDelay(config.checkDelayMillis())
        .subscriptionResendDelay(config.subscriptionResendMillis())
        .maxMessageLength(profile.maxMessageLength())
        .commitment(Commitment.CONFIRMED);

    if (profile.managed()) {
      final var instrumented = observe(tracker.instrument(prototype), handle);
      // A forced #52 control needs the manager's own javadoc-discouraged policy: with any positive
      // delay the successor is built after the callback has run, and the window the control exists
      // to open never opens at all.
      final var backoff = forced(profile) ? HarnessBackoffs.constantZero() : profile.backoff();
      final var manager = WebSocketManager.createManager(
          tracker.backoff(backoff), instrumented, websocket -> onNewWebSocket(handle, websocket));
      handle.manager(manager);
      // Exactly once: this accessor is what starts the first attempt, and polling it would make the
      // harness a participant in the retry pacing it is here to observe.
      manager.webSocket();
    } else {
      final var instrumented = observe(
          tracker.instrument(prototype, websocket -> scheduleBareReconnect(handle)), handle);
      final var websocket = instrumented.create();
      onNewWebSocket(handle, websocket);
      reconnectIfBuildFails(handle, websocket.connect());
    }
  }

  /// Re-arms the bare engine's reconnect when a connection *attempt* fails.
  ///
  /// The engine's retirement hook is the only other thing that schedules one, and a refused connect
  /// or a rejected upgrade never reaches it: nothing was ever adopted, so nothing retired. Without
  /// this the bare engine stopped for the rest of the run at its first `HANDSHAKE_*` or
  /// `CONNECT_REFUSE` fault, and every later oracle that asked the peer about it was asking about
  /// a connection id the peer had long since abandoned.
  private void reconnectIfBuildFails(final EngineHandle handle, final CompletableFuture<?> attempt) {
    if (attempt == null) {
      // `connect()` answers null once the instance is closed, which is the one case that must not
      // re-arm anything.
      return;
    }
    attempt.whenComplete((result, thrown) -> {
      if (thrown != null) {
        counters.increment(BARE_CONNECT_FAILED);
        scheduleBareReconnect(handle);
      }
    });
  }

  /// Composes the harness's observational handlers *after* the tracker's, so the #52 attribution of
  /// a retirement is already fixed by the time this bookkeeping runs and the time it takes is
  /// reported as harness time in the gap rather than confused for engine time.
  private SolanaRpcWebsocket.Builder observe(final SolanaRpcWebsocket.Builder builder,
                                             final EngineHandle handle) {
    final Consumer<SolanaRpcWebsocket> onOpen = websocket -> onOpen(handle, websocket);
    final var existingOpen = builder.onOpen();
    var composed = builder.onOpen(existingOpen == null ? onOpen : existingOpen.andThen(onOpen));

    final SolanaRpcWebsocket.OnClose onClose = (websocket, statusCode, reason) -> onRetire();
    final var existingClose = composed.onClose();
    composed = composed.onClose(existingClose == null ? onClose : existingClose.andThen(onClose));

    final BiConsumer<SolanaRpcWebsocket, Throwable> onError = (websocket, thrown) -> onRetire();
    final var existingError = composed.onError();
    return composed.onError(existingError == null ? onError : existingError.andThen(onError));
  }

  private void onNewWebSocket(final EngineHandle handle, final SolanaRpcWebsocket websocket) {
    final var previous = handle.websocket();
    if (previous != null && previous != websocket) {
      // A replaced wrapper starts with an empty registry, so the harness's belief about what is live
      // has to be dropped with it; carrying it would make W1-A expect a replay of registrations the
      // successor instance never had.
      handle.clearRegistrations();
    }
    handle.websocket(websocket);
    websocket.exceptionSubscribe(exception -> {
      counters.increment(Counters.WS_EXCEPTION_SUBSCRIBE_RECEIVED);
      if (exception instanceof JsonRpcException rejection && requestDefect(rejection.code())) {
        // The client side sees the code but not the request: `JsonRpcException` carries no id, and
        // sava says so where it builds one — "The request id stays local: JsonRpcException has no
        // field for it, and it must not masquerade as a backoff". So this cannot say *which*
        // registration the engine just retired, only that it retired one, and the join that names
        // it has to come from the peer's `sub_err` row. Counted so the two accounts can be
        // compared.
        counters.increment(REQUEST_DEFECT_OBSERVED);
      }
    });
  }

  /// The bare engine's reconnect policy, scheduled rather than run: the callback that triggers it is
  /// the engine's own retirement path, and calling `connect()` on it would re-enter the engine from
  /// inside its own lifecycle handler.
  private void scheduleBareReconnect(final EngineHandle handle) {
    if (!running.get()) {
      return;
    }
    final var backoff = bareBackoff.get(handle.profile());
    final long delay = backoff.getAndUpdate(
        current -> Math.min(BARE_RECONNECT_MAX_MILLIS, current << 1));
    later(delay, () -> {
      final var websocket = handle.websocket();
      if (running.get() && websocket != null && !websocket.closed()) {
        reconnectIfBuildFails(handle, websocket.connect());
      }
    });
  }

  // ------------------------------------------------------------------------ forced #52 mode

  /// The three `I1-*` controls, and the one thing each of them changes about the same injection.
  ///
  /// Issue #52's residual condition needs a successor installed inside the same-thread gap between
  /// the attempt future settling and the lifecycle callback running. On JDK 25 the build future
  /// normally settles on the handshake thread *before* `onOpen` runs, so the gap does not exist to
  /// be raced: `deferBuildCompletionUntilOnOpen` is what creates it, and `jdk25` is the control
  /// that runs the same injection without it to record what the JDK actually does. `forced` holds
  /// the retiring thread in the gap until the successor's `buildAsync` has been observed, which
  /// makes the misattribution deterministic; `natural` removes the hold and repeats, so the share
  /// of repetitions that misattribute unaided is a measurement (`i52_natural_win_rate`) rather than
  /// a construction. Every one of them runs on a tracker built `forced=true`, so no row from this
  /// engine can ever meet the revisit trigger.
  private enum ForcedMode {

    /// `I1-forced`: the blocking handler.
    FORCED("I1-forced", "INJECTED_RETIREMENT", true, true),
    /// `I1-natural`: the same injection unblocked, for a win-rate denominator.
    NATURAL("I1-natural", "INJECTED_RETIREMENT_UNBLOCKED", false, true),
    /// `I1-jdk25`: the JDK's own completion order — future before `onOpen` — so the run records
    /// what JDK 25 does with no gap manufactured at all.
    JDK25("I1-jdk25", "INJECTED_RETIREMENT_JDK_ORDER", false, false);

    private final String control;
    private final String reason;
    private final boolean blockAttemptFailed;
    private final boolean deferBuildCompletion;

    ForcedMode(final String control, final String reason, final boolean blockAttemptFailed,
               final boolean deferBuildCompletion) {
      this.control = control;
      this.reason = reason;
      this.blockAttemptFailed = blockAttemptFailed;
      this.deferBuildCompletion = deferBuildCompletion;
    }

    private static ForcedMode of(final String control) {
      if (control == null || control.isBlank()) {
        return null;
      }
      for (final var mode : values()) {
        if (mode.control.equalsIgnoreCase(control.trim())) {
          return mode;
        }
      }
      return null;
    }
  }

  private boolean forced(final EngineProfile profile) {
    return forcedMode != null && profile == FORCED_ENGINE;
  }

  private void configureForced(final AttemptTracker tracker) {
    final var mode = forcedMode;
    tracker.forcedReason(mode.reason);
    tracker.blockAttemptFailedUntilSuccessor(mode.blockAttemptFailed);
    tracker.deferBuildCompletionUntilOnOpen(mode.deferBuildCompletion);
    // Armed once, as a series. Re-arming from the retirement callback was a race the injector lost:
    // under the constant-zero backoff the successor is built and adopted before that callback
    // returns, so the ordinal computed there already named an attempt that had gone by.
    // `injectRetirementEvery` decides the whole series on this thread instead, and no reconnect can
    // outrun arithmetic that was fixed before it started.
    tracker.injectRetirementEvery(FORCED_INJECTION_PERIOD, MAX_FORCED_INJECTIONS);
    counters.increment(FORCED_INJECTIONS_ARMED);
  }

  // ------------------------------------------------------------------------- engine callbacks

  private void onOpen(final EngineHandle handle, final SolanaRpcWebsocket websocket) {
    InternalProbes.detect(websocket);
    final long attemptOrdinal = handle.tracker().currentOrdinal();
    final long epoch = handle.openEpoch(attemptOrdinal);
    counters.increment(Counters.WS_EPOCH_OPENED);
    bareBackoff.get(handle.profile()).set(BARE_RECONNECT_MIN_MILLIS);
    putEpoch(attemptKey(handle, attemptOrdinal), new EpochRecord(epoch));

    // W1-G's second half: the javadoc says 0 means "no message since this connection opened", and a
    // freshly adopted connection has had none. The JDK serialises listener callbacks, so nothing can
    // have been delivered while this runs.
    final long timestamp = websocket.lastMessageReceivedTimestamp();
    if (timestamp != 0L) {
      fail(Properties.W1_G, "engine=" + handle.name() + " epoch=" + epoch
          + " lastMessageReceivedTimestamp=" + timestamp + " at adoption, expected 0");
    } else {
      pass(Properties.W1_G);
    }

    ctx.recovery().adopted(handle.name(), attemptOrdinal);
    openPendingRecovery(handle, attemptOrdinal);
    final var replay = replays.get(handle.profile()).get();
    if (replay != null) {
      bindAdoption(handle, replay, attemptOrdinal);
    }
  }

  /// A retirement as the *lifecycle callbacks* see it: counted, and nothing more.
  ///
  /// `SolanaRpcWebsocket#connect()`'s javadoc settles what this observation can be used for:
  /// "lifecycle callbacks receive this reusable wrapper and no attempt token, so this API cannot
  /// attribute a callback to a particular future … recovery code that consumes both channels must
  /// treat them as potentially overlapping, uncorrelated signals rather than independently
  /// attributing each one to the current attempt." Reading "the attempt that is current now" as
  /// "the attempt that just retired" is exactly the attribution the javadoc says the API cannot
  /// support, and on `F3` it produced the measured consequence: the manager reconnected in 0 ms, the
  /// successor's `onOpen` ran first, and its predecessor's `onError` then closed the *successor's*
  /// epoch and stamped the successor's retirement time. The run ended with more epochs opened than
  /// retired and W1-C failed a connection the tracker recorded retiring on time.
  ///
  /// So the bookkeeping is driven from [#onRetirement], whose record names the attempt it belongs
  /// to. This path keeps the NP-2 double-observation count and nothing else.
  private void onRetire() {
    counters.increment(RETIREMENT_CALLBACKS);
  }

  /// One retirement, attributed to the connection attempt it ended.
  ///
  /// Everything the deferred oracles read about a retirement is written here: the epoch is closed
  /// for that attempt and no other, the attempt's own record is stamped, and the replay episode is
  /// opened against the registrations that were live. A record whose origin attempt is unknown
  /// closes nothing — guessing would put the stamp on a live connection.
  private void retire(final EngineHandle handle, final RetirementRecord retirement) {
    final long attemptOrdinal = retirement.originOrdinal();
    if (attemptOrdinal < 0L) {
      counters.increment(RETIREMENT_WITHOUT_ORDINAL);
      return;
    }
    final long retiredMillis = System.currentTimeMillis();
    // Clearing the open flag and writing the attempt's record are separate steps on purpose. When a
    // successor was adopted before this retirement arrived, the flag already belongs to the
    // successor and must be left alone — but the attempt that retired is still owed its stamp and
    // its replay episode, which is exactly the evidence the old "close whatever is open" form lost.
    final long closedEpoch = handle.closeEpoch(attemptOrdinal);
    final var record = epochsByAttempt.get(attemptKey(handle, attemptOrdinal));
    if (record == null) {
      if (closedEpoch < 0L) {
        // No epoch was ever opened for this attempt — a refused connect or a rejected upgrade — or
        // the join window aged its record out and the close already happened.
        return;
      }
    } else if (!record.markRetired(retiredMillis, retirement.causeClass())) {
      // `connect()`'s javadoc allows one retirement to be reported twice; the first report owns the
      // bookkeeping and NP-2 counts the second rather than failing on it.
      return;
    }
    if (record != null && ERROR_REPORTED_CAUSES.contains(retirement.causeClass())) {
      // W1-H(ii) and W2-B ask whether the engine *reported* the failure. The `onError` callback that
      // reported it carries no attempt token; this record does, and it is built from that same
      // callback.
      ++record.errorsObserved;
    }
    counters.increment(Counters.WS_EPOCH_RETIRED);

    final var outstanding = new LinkedHashSet<>(handle.liveMsgIds());
    final var replay = new PendingReplay(handle.name(), attemptOrdinal, retiredMillis,
        outstanding, handle.liveRegistrations());
    final var previous = replays.get(handle.profile()).getAndSet(replay);
    if (previous != null && !previous.settled) {
      previous.settled = true;
      counters.increment(REPLAY_EPISODES_SUPERSEDED);
    }
    if (handle.epochOpen()) {
      // A successor was adopted before this retirement reached the harness, so its `onOpen` had no
      // episode to bind to. Binding it here keeps the replay evidence rather than letting the
      // episode be superseded unjudged.
      bindAdoption(handle, replay, handle.currentAttemptOrdinal());
    }

    later(RETIREMENT_EXPLANATION_MILLIS,
        () -> classifyRetirement(handle, attemptOrdinal, retiredMillis,
            retirement.causeClass().name()));
  }

  /// The successor this replay episode is owed on, and the clock W1-A is measured against. Called
  /// from `onOpen` for the ordinary order and from [#retire] when the successor got there first.
  private void bindAdoption(final EngineHandle handle, final PendingReplay replay,
                            final long attemptOrdinal) {
    synchronized (replay) {
      if (replay.settled || replay.adoptedAttemptOrdinal >= 0L) {
        return;
      }
      replay.adoptedAttemptOrdinal = attemptOrdinal;
      replay.adoptedMillis = System.currentTimeMillis();
    }
    counters.record(Counters.HIST_WS_RECONNECT_LATENCY,
        (replay.adoptedMillis - replay.retiredMillis) * 1_000_000L);
    final long bound = replayBoundMillis();
    later(bound, () -> whenTailPast(replay.adoptedMillis + bound, () -> settleReplay(handle, replay, false)));
  }

  /// A retirement is *expected* when the fault schedule asked for one on that connection, when the
  /// overflow engine took the message it is configured to reject, or when the harness closed the
  /// engine itself. Anything else is unexplained, which is the gate the run's verdict reads.
  private void classifyRetirement(final EngineHandle handle,
                                  final long attemptOrdinal,
                                  final long retiredMillis,
                                  final String detail) {
    if (peerOverflowOn(handle, attemptOrdinal)) {
      // The peer's own `overflow` row says its outbound queue filled and it closed 1011. Nothing
      // sava did produced that and nothing in the fault schedule asked for it, so it belongs in
      // neither column: charging it to `unexplained` made the peer's capacity the subject under
      // test, and every control run carried one or two.
      counters.increment(RETIREMENT_PEER_OVERFLOW);
      anomaly("PEER_OVERFLOW", handle, "", handle.currentEpoch(), -1L, -1L, attemptOrdinal);
      return;
    }
    final boolean explained = retirementExplained(handle, attemptOrdinal);
    if (explained) {
      counters.increment(Counters.WS_RETIREMENT_EXPECTED);
      return;
    }
    // The two fallback classes below apply only to a retirement no scheduled fault explains: a
    // scheduled MESSAGE_OVERFLOW keeps its `expected` column (measured on F5, where every one of
    // its overflows was a scheduled fault and read as a cap trip when this ran first).
    if (RetirementRecord.CauseClass.MAX_MESSAGE_OVERFLOW.name().equals(detail)
        && handle.profile() == EngineProfile.OVERFLOW) {
      // The engine enforced the cap this harness configured it with. The message that tripped it
      // is not always a scheduled MESSAGE_OVERFLOW: the peer's periodic large notification
      // (SOAK_LARGE_PERIOD_SECONDS) is above the OVERFLOW profile's cap too, so on a pilot every
      // one of those retired the engine on schedule and read as unexplained. Any other engine's
      // cap is far above every message the peer sends, so the same cause there stays unexplained.
      counters.increment(RETIREMENT_OVERFLOW_CAP);
      anomaly("OVERFLOW_CAP", handle, "", handle.currentEpoch(), -1L, -1L, attemptOrdinal);
      return;
    }
    if (RetirementRecord.CauseClass.UNANSWERED_REQUEST.name().equals(detail)
        && serverConditionAnswerOn(handle, attemptOrdinal)) {
      // The peer refused a subscribe with a server-condition code (-32603) on this attempt and the
      // engine, which keeps such a request pending rather than retiring it, escalated it as
      // unanswered after its resend windows. The peer's refusal is the cause; whether the engine
      // should have re-sent first is an observation for the owner, counted here and reported,
      // never charged as unexplained.
      counters.increment(RETIREMENT_REJECT_ESCALATED);
      anomaly("REJECT_ESCALATED", handle, "", handle.currentEpoch(), -1L, -1L, attemptOrdinal);
      return;
    }
    if (!peerOracleAvailable) {
      // Live mode has no fault schedule, so "unexplained" would say only that the provider closed a
      // connection, which is not evidence of anything.
      counters.increment(Counters.WS_RETIREMENT_EXPECTED);
    } else {
      counters.increment(Counters.WS_RETIREMENT_UNEXPLAINED);
      anomaly("UNEXPLAINED_RETIREMENT", handle, "", handle.currentEpoch(), -1L, -1L, attemptOrdinal);
    }
  }

  private boolean serverConditionAnswerOn(final EngineHandle handle, final long attemptOrdinal) {
    final var answers = answersByAttempt.get(attemptKey(handle, attemptOrdinal));
    return answers != null && answers.serverConditionTransmitted;
  }

  private void markServerConditionTransmitted(final long attemptKey) {
    final SubscribeAnswers answers;
    synchronized (answersByAttempt) {
      answers = answersByAttempt.computeIfAbsent(attemptKey, key -> new SubscribeAnswers());
    }
    answers.serverConditionTransmitted = true;
  }

  /// A retirement is explained when the harness closed the engine or when a fault the peer marks
  /// `expectedRetirement()` was applied on that attempt. Timing alone is deliberately not an
  /// explanation: at the validate fault density some benign fault lands within seconds of every
  /// retirement, and a rule that excused on proximity would excuse everything.
  private boolean retirementExplained(final EngineHandle handle, final long attemptOrdinal) {
    if (handle.closedByHarness()) {
      return true;
    }
    if (handle.name().equals(consumers.stalledEngine())) {
      // The stall-callback control parked this engine's listener thread: every retirement on it
      // from then on is the engine reacting to a consumer that never returned (measured: a ping
      // response timeout inside the first check delays), which the harness staged.
      counters.increment(RETIREMENT_HARNESS_STALLED);
      return true;
    }
    final var fault = faultsByAttempt.get(attemptKey(handle, attemptOrdinal));
    return fault != null;
  }

  private void settleReplay(final EngineHandle handle, final PendingReplay replay, final boolean early) {
    synchronized (replay) {
      if (replay.settled) {
        return;
      }
      replay.settled = true;
    }
    if (!peerOracleAvailable) {
      // W1-A's evidence is the peer's own sub_req rows, and a live run has none: nothing ever
      // clears the outstanding set, so the episode could only FAIL (or, once emptied by releases,
      // falsely PASS). No verdict, counted with the other episodes that render none.
      replayEpisodesWithoutVerdict.incrementAndGet();
      return;
    }
    pruneDead(handle, replay);
    if (replay.registrationsAtRetirement == 0 || replay.vacuous()) {
      // Nothing was owed, so the episode renders no W1-A verdict. Counted, because "no connection
      // was retired while registrations were live" is otherwise a claim nobody measured.
      replayEpisodesWithoutVerdict.incrementAndGet();
    } else {
      if (replay.outstanding.isEmpty()) {
        pass(Properties.W1_A);
      } else {
        final var missing = new ArrayList<>(replay.outstanding);
        fail(Properties.W1_A, "engine=" + replay.engine
            + " retiredAttempt=" + replay.retiredAttemptOrdinal
            + " adoptedAttempt=" + replay.adoptedAttemptOrdinal
            + " registrations=" + replay.registrationsAtRetirement
            + " notReplayed=" + missing.subList(0, Math.min(3, missing.size()))
            + " of=" + replay.expected
            + " boundMs=" + replayBoundMillis()
            + (early ? " (settled early)" : ""));
      }
    }
  }

  /// One recovery window closed, by whichever of the ledger's three closes: the completing
  /// confirmation, the budget timer, or displacement by the next fault. The replay bound (W1-A,
  /// "was every live registration re-sent") and the recovery budget (P7, "was every one confirmed
  /// again") are deliberately separate clocks: closing the window when the last replay was *sent*
  /// was measured closing it a few hundred milliseconds before the last confirmation arrived.
  /// A registration that left the promised set is no longer owed a confirmation by any open
  /// recovery window: the plan cancelled it, the engine completed it, or the run is quiescing.
  private void released(final EngineHandle handle, final EngineHandle.Registration registration) {
    if (registration.msgId >= 0L && ctx.recovery().released(handle.name(), registration.msgId)) {
      recoveryClosed(ctx.recovery().closeCompleted(handle.name()));
    }
  }

  private void recoveryClosed(final RecoveryLedger.Record record) {
    if (record == null) {
      return;
    }
    final var event = new SoakEvents.Recovery();
    event.elapsedMillis = record.elapsedMillis();
    event.faultOrdinal = record.faultOrdinal();
    event.engine = record.engine();
    event.faultKind = record.faultKind();
    event.originOrdinal = record.originOrdinal();
    event.attemptsUsed = 1;
    event.registrations = record.registrationsLive();
    event.confirmed = record.confirmed();
    event.released = record.released();
    event.complete = record.complete();
    event.overBudget = record.overBudget();
    event.superseded = record.superseded();
    event.vacuous = !record.superseded() && !record.evaluated();
    event.commit();
  }

  /// Only a registration that is STILL live is owed a replay: one the plan unsubscribed inside the
  /// window, a one-shot signature the engine completed, or one whose subscribe the server rejected
  /// with a code that blames the request has left the set the javadoc's promise is about. Pruned
  /// both at the bound and on every replayed row, so the window closes (and P7's clock stops) the
  /// moment the last live registration is back rather than at the bound.
  private static void pruneDead(final EngineHandle handle, final PendingReplay replay) {
    replay.outstanding.removeIf(msgId -> {
      final var registration = handle.byMsgId(msgId);
      return registration == null || registration.unsubscribed || registration.requestDefectRetired
          || handle.registration(registration.channel, registration.key) != registration;
    });
  }

  /// The replay bound, from `subscriptionResendDelay`'s javadoc: a re-queued subscription keeps its
  /// last attempt stamp and is paced at that delay, and four of them is the same multiple the
  /// javadoc uses for escalation.
  ///
  /// **Deviation from `DESIGN.md` §8**, deliberately: the design measures the bound from the
  /// retirement and includes `connectTimeout + reConnectDelay`. A managed engine's reconnect is
  /// paced by ravina's `Backoff`, not by `reConnectDelay` — the manager zeroes that on purpose — so
  /// a bound measured from the retirement would be testing the backoff's escalation, not sava's
  /// replay promise. This measures from adoption instead, which is what the promise is about.
  private long replayBoundMillis() {
    return 4L * config.subscriptionResendMillis() + 2_000L;
  }

  // ----------------------------------------------------------------------------- the driver

  private void drive(final EngineHandle handle) {
    final var plan = plans.get(handle.profile());
    final long periodNanos = Math.max(1L, 60_000_000_000L / Math.max(1, config.wsChurnPerMinute()));
    long ordinal = 0L;
    long deadline = System.nanoTime();
    while (running.get()) {
      final var phase = ctx.currentPhase();
      if (phase == Phase.QUIESCE || phase == Phase.DRAIN || phase == Phase.SHUTDOWN) {
        return;
      }
      final var step = plan.step(ordinal++);
      counters.increment(Counters.HARNESS_OPS_ATTEMPTED);
      try {
        apply(handle, plan, step);
      } catch (final RuntimeException e) {
        // A driver that dies stops exercising its engine, and a silently dead driver is the failure
        // mode this whole harness is supposed to expose in other people's code.
        counters.increment("ws.driver.threw");
      }
      deadline += periodNanos;
      final long wait = deadline - System.nanoTime();
      if (wait > 0L) {
        LockSupport.parkNanos(wait);
      } else {
        deadline = System.nanoTime();
      }
    }
  }

  private void apply(final EngineHandle handle, final SubscriptionPlan plan,
                     final SubscriptionPlan.Step step) {
    switch (step.op()) {
      case SUBSCRIBE -> subscribe(handle, plan, step.channel(), step.keyIndex());
      case UNSUBSCRIBE -> unsubscribe(handle, plan, step.channel(), step.keyIndex());
      case RESUBSCRIBE_SAME_KEY -> {
        unsubscribe(handle, plan, step.channel(), step.keyIndex());
        subscribe(handle, plan, step.channel(), step.keyIndex());
      }
      case SUBSCRIBE_DUPLICATE_PARAMS -> {
        // Deliberately re-subscribes an identity the engine already holds. The engine is expected to
        // refuse it client side; the peer must therefore never see a second byte-identical request
        // on that connection, which is the wire half of W1-B. The second offer goes to the library
        // directly: the harness's own registry refused the duplicate first, so the library never
        // saw one and the step could not have caught a regression (measured in review against a
        // fake subject that accepted every duplicate and received one call).
        subscribe(handle, plan, step.channel(), step.keyIndex());
        offerDuplicate(handle, plan, step.channel(), step.keyIndex());
      }
      case NOOP -> {
      }
    }
  }

  private void seedRegistrations(final EngineHandle handle) {
    final var plan = plans.get(handle.profile());
    final int base = handle.profile().oracleIndex() * 8;
    for (int i = 0; i < 4; ++i) {
      subscribe(handle, plan, SubscriptionPlan.Channel.ACCOUNT, (base + i) & 0xFF);
    }
  }

  // ------------------------------------------------------------------------------ operations

  private boolean subscribe(final EngineHandle handle, final SubscriptionPlan plan,
                            final SubscriptionPlan.Channel channel, final int keyIndex) {
    final var websocket = handle.websocket();
    if (websocket == null) {
      return false;
    }
    if (config.live() && channel == SubscriptionPlan.Channel.GENERIC) {
      // The generic channel exercises sava's caller-defined subscribe API against the local peer's
      // synthetic `transactionSubscribe`. A real node has no such method — public devnet answered
      // every one with a request-defect code and the engine released it, measured 2026-09-23 —
      // and Helius's own method of that name takes a different parameter shape. Neither says
      // anything about sava, so a live plan skips the step and counts it.
      counters.increment(LIVE_GENERIC_SKIPPED);
      return false;
    }
    if (handle.liveRegistrations() >= config.wsSubscriptions()) {
      counters.increment(SUBSCRIBE_AT_CAP);
      return false;
    }
    final var key = keyFor(plan, channel, keyIndex);
    final var registration = handle.register(channel, key, keyIndex);
    if (registration == null) {
      if (handle.registration(channel, key) == null) {
        counters.increment(ORACLE_SLOTS_EXHAUSTED);
      }
      return false;
    }
    final var kind = consumers.kindFor(keyIndex);
    final boolean accepted;
    try {
      accepted = send(handle, websocket, registration, channel, keyIndex, kind);
    } catch (final RuntimeException e) {
      handle.removeRegistration(channel, key);
      throw e;
    }
    if (accepted) {
      counters.increment(Counters.WS_SUBSCRIBE_REQUESTED);
      subscriptionEvent("REGISTER", handle, registration, -1L, registration.msgId);
    } else {
      handle.removeRegistration(channel, key);
    }
    return accepted;
  }

  /// The duplicate half of a SUBSCRIBE_DUPLICATE_PARAMS step: the same subscribe, byte for byte,
  /// handed to the library while its registration is live, bypassing this harness's registry.
  /// The library's contract is to refuse it (the plan-level `subscribe` returns false); a library
  /// that accepted it would put a second byte-identical request on the wire, which the peer's
  /// `sub_req` rows and W1-B would then see. Counted either way.
  private void offerDuplicate(final EngineHandle handle, final SubscriptionPlan plan,
                              final SubscriptionPlan.Channel channel, final int keyIndex) {
    final var websocket = handle.websocket();
    final var registration = handle.registration(channel, keyFor(plan, channel, keyIndex));
    if (websocket == null || registration == null || registration.unsubscribed) {
      return;
    }
    counters.increment(DUPLICATE_OFFERED);
    final boolean accepted;
    try {
      accepted = send(handle, websocket, registration, channel, keyIndex, consumers.kindFor(keyIndex));
    } catch (final RuntimeException e) {
      counters.increment(DUPLICATE_THREW);
      return;
    }
    if (accepted) {
      counters.increment(DUPLICATE_ACCEPTED);
    }
  }

  private boolean send(final EngineHandle handle,
                       final SolanaRpcWebsocket websocket,
                       final EngineHandle.Registration registration,
                       final SubscriptionPlan.Channel channel,
                       final int keyIndex,
                       final ConsumerFactory.Kind kind) {
    final var commitment = Commitment.CONFIRMED;
    final var publicKey = keyTable[keyIndex];
    return switch (channel) {
      case ACCOUNT -> websocket.accountSubscribe(commitment, publicKey,
          onSub(handle, registration), consumers.account(handle, registration, kind));
      case LOGS -> websocket.logsSubscribe(commitment, publicKey,
          onSub(handle, registration), consumers.logs(handle, registration, kind));
      case PROGRAM -> websocket.programSubscribe(commitment, publicKey, filtersFor(keyIndex),
          onSub(handle, registration), consumers.account(handle, registration, kind));
      case KEYED_PROGRAM -> websocket.keyedProgramSubscribe(commitment, registration.key,
          keyedProgram(keyIndex), filtersFor(keyIndex),
          onSub(handle, registration), consumers.account(handle, registration, kind));
      case SIGNATURE -> websocket.signatureSubscribe(commitment, true, registration.key,
          onSub(handle, registration), consumers.signature(handle, registration, kind));
      case GENERIC -> websocket.subscribe(SubscriptionPlan.GENERIC_SUBSCRIBE,
          SubscriptionPlan.GENERIC_UNSUBSCRIBE, SubscriptionPlan.GENERIC_NOTIFICATION,
          registration.key, SubscriptionPlan.genericParams(registration.key),
          ConsumerFactory.genericParser(),
          onSub(handle, registration), consumers.generic(handle, registration, kind));
      case SLOT -> websocket.slotSubscribe(
          onSub(handle, registration), consumers.slot(handle, registration, kind));
      case ROOT -> websocket.rootSubscribe(
          onSub(handle, registration), consumers.root(handle, registration, kind));
    };
  }

  private boolean unsubscribe(final EngineHandle handle, final SubscriptionPlan plan,
                              final SubscriptionPlan.Channel channel, final int keyIndex) {
    final var websocket = handle.websocket();
    if (websocket == null) {
      return false;
    }
    final var key = keyFor(plan, channel, keyIndex);
    final var registration = handle.registration(channel, key);
    if (registration == null) {
      return false;
    }
    registration.unsubscribed = true;
    handle.removeRegistration(channel, key);
    handle.unsubscribeRequested();
    counters.increment(Counters.WS_UNSUBSCRIBE_REQUESTED);
    subscriptionEvent("UNSUB_REQUESTED", handle, registration, -1L, registration.msgId);
    released(handle, registration);
    final long lead = unsubscribeTombstoneLeadMillis;
    if (lead <= 0L) {
      return sendUnsubscribe(websocket, registration, channel, keyIndex);
    }
    // The `D7` control, and nothing else. The registry above has already tombstoned the
    // registration while the engine has not been told about it at all, so every notification the
    // peer delivers in the gap reaches a registration the harness's own bookkeeping says is
    // cancelled — which is what W1-D forbids, staged on the *client* side where the oracle looks. A
    // peer-side zombie cannot move W1-D on a correct client: sava's own cancellation tombstones drop
    // it, which is what `F7` exists to demonstrate, so the peer behaviour it stands on could only
    // ever pass.
    registration.cancelledAwaitingWire = true;
    final boolean deferred = later(lead, () -> {
      try {
        sendUnsubscribe(websocket, registration, channel, keyIndex);
      } finally {
        registration.cancelledAwaitingWire = false;
      }
    });
    if (!deferred) {
      registration.cancelledAwaitingWire = false;
      return sendUnsubscribe(websocket, registration, channel, keyIndex);
    }
    return true;
  }

  private boolean sendUnsubscribe(final SolanaRpcWebsocket websocket,
                                  final EngineHandle.Registration registration,
                                  final SubscriptionPlan.Channel channel,
                                  final int keyIndex) {
    final var commitment = Commitment.CONFIRMED;
    return switch (channel) {
      case ACCOUNT -> websocket.accountUnsubscribe(commitment, keyTable[keyIndex]);
      case LOGS -> websocket.logsUnsubscribe(commitment, keyTable[keyIndex]);
      case PROGRAM -> websocket.programUnsubscribe(commitment, keyTable[keyIndex]);
      case KEYED_PROGRAM -> websocket.keyedProgramUnsubscribe(commitment, registration.key);
      case SIGNATURE -> websocket.signatureUnsubscribe(commitment, registration.key);
      case GENERIC -> websocket.unsubscribe(SubscriptionPlan.GENERIC_NOTIFICATION, registration.key);
      case SLOT -> websocket.slotUnsubscribe();
      case ROOT -> websocket.rootUnsubscribe();
    };
  }

  private <T> Consumer<Subscription<T>> onSub(final EngineHandle handle,
                                              final EngineHandle.Registration registration) {
    return subscription -> {
      // Fires after every successful send, including each replay, so this is also where a replayed
      // registration re-announces the message id the peer log will name. The subscription object
      // is kept too: its `subId()` is the engine's own record of a grant, which is what a live run
      // (no peer log) reads to say whether the registration is confirmed.
      registration.subscription = subscription;
      handle.bindMsgId(subscription.msgId(), registration);
      subscriptionEvent("SEND_OBSERVED", handle, registration, -1L, subscription.msgId());
    };
  }

  private String keyFor(final SubscriptionPlan plan,
                        final SubscriptionPlan.Channel channel,
                        final int keyIndex) {
    return switch (channel) {
      case SLOT, ROOT -> channel.name();
      case SIGNATURE -> plan.signature(keyIndex);
      case KEYED_PROGRAM -> "kp-" + keyIndex;
      default -> keyTable[keyIndex].toBase58();
    };
  }

  /// The program a `KEYED_PROGRAM` registration subscribes to. That channel's registry identity is
  /// the caller-chosen key (`kp-<n>`), so the program is a *different* key from the table, and the
  /// subscribe call and W1-J's expectation must name the same one — hence one method rather than
  /// the same index arithmetic written twice.
  private PublicKey keyedProgram(final int keyIndex) {
    return keyTable[(keyIndex + 1) % keyTable.length];
  }

  /// The key W1-J expects a program notification to name: the program the harness subscribed to.
  /// Null for every other channel, and for a registration whose key index is outside the table —
  /// there is then nothing to compare against, which is a dropped check rather than a verdict.
  private String expectedProgramKey(final EngineHandle.Registration registration) {
    if (registration.keyIndex < 0 || registration.keyIndex >= keyTable.length) {
      return null;
    }
    return switch (registration.channel) {
      case PROGRAM -> keyTable[registration.keyIndex].toBase58();
      case KEYED_PROGRAM -> keyedProgram(registration.keyIndex).toBase58();
      default -> null;
    };
  }

  /// A memcmp filter at offset 0 against a key from the table. The filter's only job here is to make
  /// the program registration's parameters distinct and non-trivial: the peer answers every program
  /// subscription the same way, so the filter is exercised as request text, not as a predicate.
  private List<Filter> filtersFor(final int keyIndex) {
    return List.of(Filter.createMemCompFilter(0, keyTable[(keyIndex + 2) & 0xFF]));
  }

  // ------------------------------------------------------------------------- delivery oracle

  @Override
  public void observe(final EngineHandle handle,
                      final EngineHandle.Registration registration,
                      final ConsumerFactory.Kind kind,
                      final long sequence,
                      final int payloadChars,
                      final byte[] payload,
                      final boolean verifyPayload,
                      final String notifiedKey) {
    counters.increment(Counters.WS_NOTIFICATIONS_DELIVERED);
    registration.lastDeliveryMillis = System.currentTimeMillis();
    if (registration.unsubscribed) {
      // The harness asked for this registration to go away and the engine still dispatched to it.
      // W1-D's own check is scheduled from the peer's zombie row; this is the client-side half.
      counters.increment(Counters.WS_NOTIFICATIONS_ZOMBIE);
      if (registration.cancelledAwaitingWire) {
        // The cancellation exists only in the harness's registry so far, which is the `D7` control's
        // staged defect: a client that tombstones a registration before it cancels it upstream
        // delivers to something it has already forgotten. Ordinary runs close that gap in the same
        // call, so this branch cannot fire in one.
        counters.increment(DELIVERY_TO_TOMBSTONED);
        fail(Properties.W1_D, "engine=" + handle.name() + " channel=" + registration.channel
            + " key=" + registration.key + " seq=" + sequence
            + " consumer invoked for a registration the registry had already cancelled, "
            + unsubscribeTombstoneLeadMillis + " ms before its unsubscribe went on the wire");
      }
    }

    // W1-G on the designated engine: the timestamp must never go backwards inside one epoch.
    if (handle.profile() == EngineProfile.EXPONENTIAL) {
      final var websocket = handle.websocket();
      if (websocket != null) {
        final long timestamp = websocket.lastMessageReceivedTimestamp();
        final long previous = handle.lastMessageTimestamp();
        if (timestamp == 0L && previous > 0L) {
          // The engine has no connection at this instant (retired, not yet re-adopted): its javadoc
          // makes 0 "no evidence", and the harness's own epoch counter lags the engine's boundary
          // by the callback that reports it (one sample in 47k under a subscribe-swallow storm).
          // An epoch boundary, not a decrease inside one; the next non-zero reading starts afresh.
          counters.increment(W1G_CONNECTION_GONE);
          handle.lastMessageTimestamp(0L);
        } else if (timestamp < previous) {
          fail(Properties.W1_G, "engine=" + handle.name() + " epoch=" + handle.currentEpoch()
              + " lastMessageReceivedTimestamp went " + previous + " -> " + timestamp);
        } else {
          handle.lastMessageTimestamp(timestamp);
          pass(Properties.W1_G);
        }
      }
    }

    if (verifyPayload) {
      // The two-sided form: the filler's own checksum, plus the two cross-checks that tie these
      // bytes to the notification they arrived in. The checksum alone is self-consistent, so a
      // payload swapped wholesale for another sequence's, or one that decoded to fewer bytes than
      // `value.space` announced, satisfies it; `space` and the sequence are what catch those.
      if (ConsumerFactory.payloadIntact(payload, sequence, payloadChars)) {
        pass(Properties.W2_A);
      } else {
        fail(Properties.W2_A, "engine=" + handle.name() + " channel=" + registration.channel
            + " key=" + registration.key + " bytes=" + (payload == null ? -1 : payload.length)
            + " space=" + payloadChars
            + " payloadSeq=" + ConsumerFactory.payloadSequence(payload)
            + " notificationSeq=" + sequence + " checksum mismatch");
      }
    }

    if (notifiedKey != null) {
      // W1-J: only the program channels carry a pubkey, and the peer writes the key the
      // subscription was granted for. The expectation is the key the harness asked for, which is
      // independent of anything the notification says about itself.
      programNotifications.incrementAndGet();
      final var expected = expectedProgramKey(registration);
      if (expected == null) {
        // The registration's own program key cannot be recovered, so there is nothing to compare
        // against and nothing is asserted.
        dropUnjoinable(Properties.W1_J);
      } else if (expected.equals(notifiedKey)) {
        pass(Properties.W1_J);
      } else {
        fail(Properties.W1_J, "engine=" + handle.name() + " channel=" + registration.channel
            + " key=" + registration.key + " seq=" + sequence + " granted=" + expected
            + " notified=" + (notifiedKey.isEmpty() ? "<absent>" : notifiedKey));
      }
    }

    if (!peerOracleAvailable) {
      // W1-E is peer-established: the sequence is what the controlled peer stamps on every
      // notification. A real node stamps nothing, and the field the consumers read there is the
      // slot, which no account changes on every one of — measured 2026-09-23 on public devnet,
      // where the "sequence" oracle failed 7,188 of 18,672 notifications for gaps that were
      // simply slots the account did not change in. Not evaluated, as the ledger states.
      return;
    }
    final long epoch = handle.currentEpoch();
    final var result = ctx.sequences().observe(handle.profile().oracleIndex(),
        registration.oracleSlot, registration.oracleEpoch(epoch), sequence);
    switch (result) {
      case OK -> {
        counters.increment(Counters.WS_SEQUENCE_OK);
        pass(Properties.W1_E);
      }
      case FIRST_OF_EPOCH -> {
        // NP-1: delivery across a disconnect is not promised, so the first sequence of an epoch
        // carries no expectation and is not an evaluation of W1-E either way.
      }
      case GAP -> sequenceAnomaly(handle, registration, "GAP", epoch, sequence,
          Counters.WS_SEQUENCE_GAP);
      case DUP -> sequenceAnomaly(handle, registration, "DUP", epoch, sequence,
          Counters.WS_SEQUENCE_DUP);
      case REORDER -> sequenceAnomaly(handle, registration, "REORDER", epoch, sequence,
          Counters.WS_SEQUENCE_REORDER);
    }
  }

  private void sequenceAnomaly(final EngineHandle handle,
                               final EngineHandle.Registration registration,
                               final String kind,
                               final long epoch,
                               final long sequence,
                               final String counter) {
    counters.increment(counter);
    final long last = ctx.sequences().lastSequence(
        handle.profile().oracleIndex(), registration.oracleSlot);
    fail(Properties.W1_E, "engine=" + handle.name() + " channel=" + registration.channel
        + " key=" + registration.key + " epoch=" + epoch + ' ' + kind
        + " expected=" + (last + 1) + " actual=" + sequence);
    anomaly(kind, handle, registration.key, epoch, last + 1, sequence, handle.currentAttemptOrdinal());
  }

  @Override
  public void deliberateThrow(final EngineHandle handle, final EngineHandle.Registration registration) {
    counters.increment(Counters.WS_CONSUMER_THREW);
    final long attemptOrdinal = handle.currentAttemptOrdinal();
    final long epoch = handle.currentEpoch();
    deferred(Properties.W2_C, CONTAINMENT_MILLIS, () -> {
      // W2-C: a consumer throwing must not cost the connection. A fault applied in the same window
      // is a legitimate reason for the connection to be gone, so it is not held against the engine.
      if (handle.epochOpen() && handle.currentEpoch() == epoch) {
        pass(Properties.W2_C);
      } else {
        judgeContainment(handle, attemptOrdinal, Properties.W2_C, () -> "engine=" + handle.name()
            + " epoch=" + epoch + " retired within " + CONTAINMENT_MILLIS
            + " ms of a deliberate consumer throw on key=" + registration.key);
      }
    });
  }

  /// A containment property whose connection is gone is judged only once the retirement's
  /// explanation has had time to arrive. The fault rows come through the peer-log tail, which
  /// was measured lagging a fault storm by more than the 2 s containment window (the row that
  /// explained each of three "failures" was written 20 ms before the retirement and read after
  /// the check ran), and `classifyRetirement` already waits `RETIREMENT_EXPLANATION_MILLIS` for
  /// the same reason. A retirement a destructive fault explains is not evidence either way.
  private void judgeContainment(final EngineHandle handle, final long attemptOrdinal,
                                final Property property, final Supplier<String> example) {
    // One verdict per (engine, attempt, property). The property is about the connection, not about
    // the notification that prompted the look: every unknown-id notification on one attempt asks
    // the same question of the same connection and gets the same answer, so counting each of them
    // reports one retirement many times over — `D10` recorded eighteen W1-F failures from two
    // retirements, which reads as a storm rather than as the two events it was.
    if (!claimContainmentVerdict(handle, attemptOrdinal, property)) {
      return;
    }
    if (containmentExcusedByCause(handle, attemptOrdinal)) {
      excusedByCause(property);
      return;
    }
    if (retirementExplained(handle, attemptOrdinal)) {
      excused(property);
      return;
    }
    deferred(property, RETIREMENT_EXPLANATION_MILLIS, () -> {
      if (containmentExcusedByCause(handle, attemptOrdinal)) {
        excusedByCause(property);
      } else if (retirementExplained(handle, attemptOrdinal)) {
        excused(property);
      } else {
        fail(property, example.get());
      }
    });
  }

  /// True for the first containment check of one property on one connection attempt, false for
  /// every one after it. An attempt with no record left to claim on is judged: losing the join
  /// window must not turn into silence.
  private boolean claimContainmentVerdict(final EngineHandle handle, final long attemptOrdinal,
                                          final Property property) {
    final var record = epochsByAttempt.get(attemptKey(handle, attemptOrdinal));
    return record == null || record.claimVerdict(property.id());
  }

  /// True when the retirement's own recorded cause is something other than the notification or the
  /// consumer throw the property is about.
  ///
  /// Admission is by what the retirement *was*, not by when it happened. The attempt tracker
  /// classifies every retirement from the engine's own report — a JDK close or error on the
  /// transport, a probe that timed out, a request that went unanswered, a message past
  /// `maxMessageLength`, the harness's own injection — and none of those is the engine treating a
  /// stray notification as fatal. The peer's `overflow` row is admitted the same way: the peer shut
  /// the connection because its own queue filled.
  private boolean containmentExcusedByCause(final EngineHandle handle, final long attemptOrdinal) {
    if (peerOverflowOn(handle, attemptOrdinal)) {
      return true;
    }
    final var record = epochsByAttempt.get(attemptKey(handle, attemptOrdinal));
    return record != null && record.causeClass != null
        && CONTAINMENT_EXCUSING_CAUSES.contains(record.causeClass);
  }

  /// A containment check whose retirement an applied fault already explains. Counted per property
  /// as well as in the run's own counter, so a property that ends the run with nothing to say can
  /// name how many of its checks went this way instead of asserting a cause it never measured.
  private void excused(final Property property) {
    counters.increment(CONTAINMENT_EXCUSED);
    tally(checksExcused, property.id()).incrementAndGet();
  }

  /// A containment check the retirement's recorded cause excuses. Counted in the same per-property
  /// tally as [#excused], so a stated skip still accounts for it, under its own run counter.
  private void excusedByCause(final Property property) {
    counters.increment(CONTAINMENT_EXCUSED_BY_CAUSE);
    tally(checksExcused, property.id()).incrementAndGet();
  }

  // ---------------------------------------------------------------------------- peer oracles

  private void startPeerTail() throws IOException {
    if (!peerOracleAvailable) {
      return;
    }
    final var runDir = ctx.runDir();
    wsTail = new TailReader(runDir.resolve("peer-ws.tsv"));
    faultTail = new TailReader(runDir.resolve("faults-applied.tsv"));
    tailRunning = true;
    tailThread = new Thread(this::tail, "soak-ws-peertail");
    tailThread.setDaemon(true);
    tailThread.start();
  }

  /// The peer's clock as far as the tail has read: the newest row timestamp seen. A judgment
  /// about what the peer had written by time T is sound only once this has passed T; before
  /// that a missing row is a row not yet read, not a row never written.
  private volatile long tailWatermarkMillis;
  /// How long after the peer wrote the newest row the tail read it: the lag proper, unlike the
  /// watermark's age, which grows with the peer's own silence.
  private volatile long tailReadLagMillis = -1L;
  private int tailLagSampleCounter;

  private void tail() {
    while (tailRunning) {
      pollTails();
      LockSupport.parkNanos(TAIL_POLL_MILLIS * 1_000_000L);
    }
    pollTails();
  }

  /// Runs `judgment` once the tail has read past `peerDeadlineMillis`, re-checking every
  /// TAIL_WAIT_STEP_MILLIS, or after TAIL_GRACE_MILLIS beyond it — counted, so a verdict made
  /// against rows the harness had not read is visible as such. Without a peer log (live mode)
  /// there is nothing to wait for.
  private void whenTailPast(final long peerDeadlineMillis, final Runnable judgment) {
    if (!peerOracleAvailable || tailWatermarkMillis >= peerDeadlineMillis) {
      judgment.run();
      return;
    }
    final long now = System.currentTimeMillis();
    final var tail = wsTail;
    if (tail != null && tail.caughtUp() && now >= peerDeadlineMillis + TAIL_SETTLE_MARGIN_MILLIS) {
      // Nothing left to read and the peer has had time to flush: the rows up to the deadline are
      // all in hand, the peer simply wrote nothing newer to move the watermark.
      judgment.run();
      return;
    }
    if (now >= peerDeadlineMillis + TAIL_GRACE_MILLIS) {
      counters.increment(JUDGED_UNDER_TAIL_LAG);
      judgment.run();
      return;
    }
    if (!later(TAIL_WAIT_STEP_MILLIS, () -> whenTailPast(peerDeadlineMillis, judgment))) {
      // The check budget is spent: judge now rather than never, and say so.
      counters.increment(JUDGED_UNDER_TAIL_LAG);
      judgment.run();
    }
  }

  /// Serialised because the tail thread is not its only caller: quiesce, drain and the W1-I close
  /// check all drive it directly so the peer's last rows are read before the oracles are closed, and
  /// two readers sharing one file position would splice two half lines into one row.
  private synchronized void pollTails() {
    try {
      // Faults first: the peer records a fault and then writes the frame it produced, so reading the
      // records before the frames means a frame's explanation is already in hand when the frame's
      // own row is read. The two files flush independently, so this narrows the gap rather than
      // closing it — the joins that depend on it wait as well.
      faultTail.poll(this::onFaultRow);
      wsTail.poll(this::onPeerWsRow);
    } catch (final IOException | RuntimeException e) {
      counters.increment(PEER_ROWS_DROPPED);
    }
  }

  /// `peer-ws.tsv`: `wallMillis nanosSinceAnchor port kind connId attemptOrdinal subId msgId seq
  /// bytes detail`, no header, first line the anchor row.
  private void onPeerWsRow(final String line) {
    final var cells = line.split("\t", -1);
    if (cells.length < 11) {
      counters.increment(PEER_ROWS_DROPPED);
      return;
    }
    counters.increment(PEER_ROWS_READ);
    final long wallMillis = parseLong(cells[0]);
    if (wallMillis > tailWatermarkMillis) {
      tailWatermarkMillis = wallMillis;
      final long readLag = Math.max(0L, System.currentTimeMillis() - wallMillis);
      tailReadLagMillis = readLag;
      if (readLag > 1_000L || (++tailLagSampleCounter & (TAIL_LAG_SAMPLE_EVERY - 1)) == 0) {
        counters.record(Counters.HIST_WS_TAIL_LAG, readLag * 1_000_000L);
      }
    }
    final int port = (int) parseLong(cells[2]);
    final var kind = cells[3];
    final long connId = parseLong(cells[4]);
    final long attemptOrdinal = parseLong(cells[5]);
    final long subId = parseLong(cells[6]);
    final long msgId = parseLong(cells[7]);
    final var detail = cells[10];
    final var handle = byPort.get(port);
    if (handle == null || connId < 0L) {
      // The churn workload's port and the anchor row both land here; neither belongs to an engine
      // this workload drives.
      return;
    }
    final var view = view(connId, port, attemptOrdinal);
    switch (kind) {
      case "sub_req" -> onSubRequest(handle, view, msgId, wallMillis, detail);
      case "sub_ack" -> onSubAck(handle, view, subId, msgId, wallMillis);
      case "sub_err" -> {
        counters.increment(Counters.WS_SUBSCRIBE_REFUSED);
        view.lastPeerFrameMillis = wallMillis;
        // An error answer completes the request. The engine re-sends a refused subscribe under
        // the same id after its resend delay (observed against REJECT_SUBSCRIBE), and that
        // retry is not the duplicate W1-B forbids, which is about an UNANSWERED request.
        final var identity = view.identityFor(msgId);
        if (identity != null) {
          view.answeredWithError(identity);
        }
        onSubscribeRejected(handle, view, msgId, wallMillis, detail);
        if (codeToken(detail) == SUBSCRIPTION_REFUSED) {
          // The row is the frame leaving the peer; a faults-applied row is written at enqueue and
          // can name a refusal the peer never transmitted (measured: one held behind a silence).
          markServerConditionTransmitted(attemptKey(handle, view.attemptOrdinal));
        }
      }
      case "unsub_req" -> onUnsubRequest(handle, view, subId, detail);
      case "unsub_ack" -> {
        counters.increment(Counters.WS_UNSUBSCRIBE_ACKED);
        handle.unsubscribeAcked();
        view.lastPeerFrameMillis = wallMillis;
      }
      case "unsub_err" -> {
        counters.increment(Counters.WS_UNSUBSCRIBE_REFUSED);
        view.lastPeerFrameMillis = wallMillis;
      }
      case "notify" -> onNotify(handle, view, subId, wallMillis, attemptOrdinal, detail, parseLong(cells[9]));
      case "ping_in" -> onPing(handle, view, wallMillis, attemptOrdinal, detail);
      case "pong_out" -> {
        view.lastPeerFrameMillis = wallMillis;
        view.lastPongMillis = wallMillis;
      }
      case "handshake" -> {
        // A view only becomes a connection when the peer answered `101`. Everything else is a
        // refused upgrade, and a refused upgrade is owed no keep-alive, no close observation and no
        // recovery measured from itself.
        view.handshakeOk = "101".equals(detail);
        view.handshakeAnswered = true;
      }
      case "overflow" -> {
        // The peer's outbound queue filled and it is about to close 1011. Recorded on the view so
        // the retirement that follows is attributed to the peer rather than to sava.
        view.peerOverflow = true;
        view.lastPeerFrameMillis = wallMillis;
      }
      case "close_in", "close_out", "abort" -> view.closed = true;
      default -> {
      }
    }
  }

  /// A subscribe the peer answered with an error, read for what sava does about it.
  ///
  /// `SolanaJsonRpcWebsocket` splits error answers by code: `isRequestDefect` — `INVALID_REQUEST`,
  /// `METHOD_NOT_FOUND`, `INVALID_PARAMS` — is "that request's terminal state: re-sending the same
  /// frame can only collect the same answer, so the entry is retired and its registry slot freed",
  /// while any other code, `-32603` included, "is the server's condition, not the request's, so the
  /// entry stays pending and the resend pacing retries it". So the first class ends the
  /// registration and the second leaves it live, and W1-A and P7 must ask about the second only.
  ///
  /// The `sub_err` row carries the message id but not the code, so the code comes from the fault the
  /// peer recorded for the same connection microseconds earlier: `ERROR_RESPONSE` answers -32602 and
  /// `REJECT_SUBSCRIBE` answers -32603. The deferral is the tail's, not the engine's.
  private void onSubscribeRejected(final EngineHandle handle, final ConnectionView view,
                                   final long msgId, final long wallMillis, final String detail) {
    if (msgId < 0L || handle.byMsgId(msgId) == null) {
      return;
    }
    // The row names the code the engine was answered with (`code=<n>`), which is the exact input
    // sava's isRequestDefect decides on: a request-defect code retires the registration now, a
    // server-condition code leaves it live (sava retries it), and neither needs a fault row.
    final long code = codeToken(detail);
    if (code != 0L) {
      if (requestDefect(code)) {
        final var registration = handle.byMsgId(msgId);
        if (registration != null && !registration.requestDefectRetired) {
          retireOnRejection(handle, registration, msgId);
        }
      }
      return;
    }
    // A row without the token (an older peer): the peer's own refusal of a method it does not
    // recognise is -32601, which sava also classes as terminal, and it writes the reason into the
    // row; every other rejection is joined to the fault row that produced it inside a window.
    final boolean unknownMethod = detail.startsWith("unknown method");
    final long attemptOrdinal = view.attemptOrdinal;
    later(REQUEST_DEFECT_JOIN_MILLIS, () -> {
      final var registration = handle.byMsgId(msgId);
      if (registration == null || registration.requestDefectRetired) {
        return;
      }
      final var answers = answersByAttempt.get(attemptKey(handle, attemptOrdinal));
      if (!unknownMethod && (answers == null || !answers.requestDefectAt(wallMillis))) {
        // No recorded request-defect answer within the join window: either the peer answered with a
        // server-condition code, which sava retries and which leaves the registration live, or the
        // rejection cannot be classified at all. Both leave the registration where it is.
        return;
      }
      retireOnRejection(handle, registration, msgId);
    });
  }

  /// Takes a registration out of the harness's own live set because sava took it out of its
  /// registry. The slot is released the same way a cancelled or completed registration's is: the
  /// engine freed its channel slot, so the plan may legitimately subscribe that identity again.
  private void retireOnRejection(final EngineHandle handle,
                                 final EngineHandle.Registration registration,
                                 final long msgId) {
    registration.requestDefectRetired = true;
    counters.increment(REQUEST_DEFECT_RETIRED);
    if (handle.registration(registration.channel, registration.key) == registration) {
      handle.removeRegistration(registration.channel, registration.key);
    }
    subscriptionEvent("REFUSED", handle, registration, -1L, msgId);
    released(handle, registration);
    final var replay = replays.get(handle.profile()).get();
    if (replay != null && !replay.settled) {
      pruneDead(handle, replay);
      if (replay.outstanding.isEmpty() && replay.adoptedAttemptOrdinal >= 0L) {
        settleReplay(handle, replay, true);
      }
    }
  }

  /// The `code=<n>` token a peer error row carries, or 0 when the row has none (no JSON-RPC error
  /// code is 0).
  static long codeToken(final String detail) {
    final int at = detail.indexOf("code=");
    if (at < 0) {
      return 0L;
    }
    int end = at + "code=".length();
    final int start = end;
    if (end < detail.length() && detail.charAt(end) == '-') {
      ++end;
    }
    while (end < detail.length() && Character.isDigit(detail.charAt(end))) {
      ++end;
    }
    try {
      return Long.parseLong(detail, start, end, 10);
    } catch (final NumberFormatException e) {
      return 0L;
    }
  }

  /// The codes `SolanaJsonRpcWebsocket.isRequestDefect` treats as terminal, named from sava's own
  /// constants rather than copied as literals.
  private static boolean requestDefect(final long code) {
    return code == JsonRpcException.INVALID_REQUEST
        || code == JsonRpcException.METHOD_NOT_FOUND
        || code == JsonRpcException.INVALID_PARAMS;
  }

  private void onSubRequest(final EngineHandle handle, final ConnectionView view,
                            final long msgId, final long wallMillis, final String detail) {
    subRequestRows.incrementAndGet();
    final var identity = subscribeIdentity(detail, msgId);
    if (identity != null) {
      view.bindIdentity(msgId, identity, counters);
      final int requests = view.request(identity, msgId, counters);
      if (view.resentAfterError(identity)) {
        counters.increment(RESENT_AFTER_ERROR);
      }
      if (requests > 1) {
        // W1-B: the peer received the first request, which is exactly the javadoc's "successfully
        // sent" condition, so a second byte-identical one on the same connection would create a
        // second server-side subscription that nothing will ever cancel. Whether this second one
        // is that duplicate depends on what became of the registrations the earlier ids belonged
        // to, which is the harness's own record and not the peer's.
        switch (duplicateVerdict(handle, view, identity, msgId)) {
          case DUPLICATE -> fail(Properties.W1_B, "engine=" + handle.name()
              + " dialect=" + handle.profile().dialect() + " conn=" + view.connId
              + " attempt=" + view.attemptOrdinal + " subscribe=" + identity
              + " msgId=" + msgId + " earlierIds=" + idList(view.msgIds(identity), msgId)
              + " received " + requests + " requests for one subscribe identity with no"
              + " intervening unsubscribe");
          case SUPERSEDED -> {
            // Every earlier registration under this identity had been cancelled, so this is a new
            // registration's first request and the count starts again from it.
            view.restart(identity, msgId);
            pass(Properties.W1_B);
          }
          case UNJOINABLE -> dropUnjoinable(Properties.W1_B);
        }
      } else {
        pass(Properties.W1_B);
      }
    }

    if (detail.startsWith("swallowed")) {
      // W1-C: the peer has the request and will never answer it. The javadoc says an unanswered
      // request replaces the connection after four resend delays. The pass therefore needs the
      // retirement to be *this* escalation: a retirement before the deadline could not have been
      // it, and one on an attempt another retiring fault also hit cannot be told apart from it.
      final long escalationMillis = 4L * config.subscriptionResendMillis();
      final long bound = escalationMillis + config.checkDelayMillis() + 2_000L;
      final long attemptOrdinal = view.attemptOrdinal;
      final long sentMillis = wallMillis;
      deferred(Properties.W1_C, 2L * bound, () -> {
        final var record = epochsByAttempt.get(attemptKey(handle, attemptOrdinal));
        if (record == null) {
          // The attempt is no longer joinable; saying nothing is better than guessing.
          dropUnjoinable(Properties.W1_C);
        } else if (confounded(handle, attemptOrdinal, KIND_SWALLOW_SUBSCRIBE)) {
          dropConfounded(Properties.W1_C);
        } else if (record.retiredMillis <= 0L) {
          fail(Properties.W1_C, "engine=" + handle.name() + " attempt=" + attemptOrdinal
              + " msgId=" + msgId + " swallowed subscribe did not retire the connection within "
              + (2L * bound) + " ms (bound " + bound + " ms)");
        } else if (record.retiredMillis - sentMillis < escalationMillis - CLOCK_JOIN_SLACK_MILLIS) {
          // Gone before the deadline the property is about, so whatever took the connection, it
          // was not the four-resend escalation.
          dropConfounded(Properties.W1_C);
        } else {
          pass(Properties.W1_C);
        }
      });
    }

    final var replay = replays.get(handle.profile()).get();
    if (replay != null && !replay.settled && replay.adoptedAttemptOrdinal == view.attemptOrdinal) {
      if (replay.adoptedMillis > 0L && wallMillis - replay.adoptedMillis > replayBoundMillis()) {
        // Late by the wire's clock: the peer received this re-send after W1-A's bound, so it is
        // not credited whenever the tail read it. Counted, so a late replay is a number.
        if (replay.outstanding.contains(msgId)) {
          counters.increment(REPLAY_ROWS_LATE);
        }
      } else if (replay.outstanding.remove(msgId)) {
        counters.increment(Counters.WS_SUBSCRIBE_REPLAYED);
        counters.record(Counters.HIST_WS_REPLAY_LATENCY,
            (wallMillis - replay.retiredMillis) * 1_000_000L);
        final var registration = handle.byMsgId(msgId);
        if (registration != null) {
          subscriptionEvent("REPLAYED", handle, registration, -1L, msgId);
        }
        pruneDead(handle, replay);
        if (replay.outstanding.isEmpty()) {
          settleReplay(handle, replay, true);
        }
      }
    }
  }

  private void onSubAck(final EngineHandle handle, final ConnectionView view,
                        final long subId, final long msgId, final long wallMillis) {
    view.lastPeerFrameMillis = wallMillis;
    counters.increment(Counters.WS_SUBSCRIBE_CONFIRMED);
    view.bindSubId(subId, msgId, counters);
    final var record = epochsByAttempt.get(attemptKey(handle, view.attemptOrdinal));
    final var registration = handle.byMsgId(msgId);
    if (registration != null) {
      if (record != null) {
        registration.confirmedEpoch = record.epoch;
      }
      subscriptionEvent("CONFIRMED", handle, registration, subId, msgId);
      if (ctx.recovery().confirmed(handle.name(), msgId, view.attemptOrdinal, wallMillis)) {
        recoveryClosed(ctx.recovery().closeCompleted(handle.name()));
      }
    }
  }

  /// `unsub_req` rows name the subscribe that granted the id in their detail
  /// (`<method> subMsgId=<n>`), which is how a cancellation is joined to its registration even
  /// when the confirmation was delayed and the harness has not seen the `sub_ack` yet.
  private void onUnsubRequest(final EngineHandle handle, final ConnectionView view,
                              final long subId, final String detail) {
    Long msgId = subMsgId(detail);
    if (msgId == null) {
      msgId = view.msgIdFor(subId);
    }
    if (msgId == null) {
      return;
    }
    // A subsequent subscribe of the same identity on this connection is legitimate once the
    // registration has been cancelled, so the W1-B count for that identity starts again.
    final var identity = view.identityFor(msgId);
    if (identity != null) {
      view.cancelled(identity);
    }
    final var registration = handle.byMsgId(msgId);
    if (registration == null || registration.unsubscribed) {
      return;
    }
    if (registration.channel == SubscriptionPlan.Channel.SIGNATURE) {
      // The engine's own cancellation of a completed signature subscription reaching the peer;
      // normally the terminal notification already retired the registration here (see
      // signatureTerminal), and this is the idempotent fallback.
      retireSignature(handle, registration);
    } else {
      counters.increment(ENGINE_UNSUBSCRIBED_LIVE);
    }
  }

  /// The one-shot channel's end: from the terminal notification on, the engine holds no
  /// registration for it and owes it no replay (SolanaJsonRpcWebsocket releases the subscription
  /// on the same `receivedSignature` rule), so W1-A's "live at retirement" and P7's owed set must
  /// not include it. Measured 2026-09-22 under a reset storm: a signature that completed 49 ms
  /// before its connection was reset produced no unsubscribe on the wire (the reset truncated the
  /// peer's surplus frames), and a registry that waited for one charged sava with a lost replay.
  @Override
  public void signatureTerminal(final EngineHandle handle, final EngineHandle.Registration registration) {
    counters.increment(SIGNATURE_TERMINAL_OBSERVED);
    retireSignature(handle, registration);
  }

  private void retireSignature(final EngineHandle handle, final EngineHandle.Registration registration) {
    if (registration.unsubscribed) {
      return;
    }
    registration.unsubscribed = true;
    handle.removeRegistration(registration.channel, registration.key);
    counters.increment(SIGNATURE_TERMINATED);
    released(handle, registration);
  }

  /// What W1-B counts requests against: the peer's fingerprint of the subscribe's `method+params`
  /// when the `sub_req` row carries one, and the JSON-RPC id when it does not.
  ///
  /// The fingerprint is the identity the property is actually about — "a second byte-identical
  /// request on one connection" — and an id-keyed count cannot see a duplicate that arrived under a
  /// fresh id, which is the whole of the client-side deduplication W1-B exists to check. The
  /// fallback keeps the older, weaker join working against a peer that does not write `fp=` yet,
  /// and counts itself so a reviewer can tell which rule a run's verdicts were reached under
  /// rather than inferring it from the peer's version.
  private String subscribeIdentity(final String detail, final long msgId) {
    final var fingerprint = fingerprint(detail);
    if (fingerprint != null) {
      return "fp=" + fingerprint;
    }
    counters.increment(W1B_MSG_ID_FALLBACK);
    return msgId < 0L ? null : "msgId=" + msgId;
  }

  /// The `fp=<hex>` token anywhere in a `sub_req` row's detail, or null when the row carries none.
  /// Read positionally from the token rather than from a fixed column so the peer can place it
  /// beside the method name or after a fault name without the join caring which.
  private static String fingerprint(final String detail) {
    final int at = detail.indexOf("fp=");
    if (at < 0) {
      return null;
    }
    int end = at + "fp=".length();
    final int start = end;
    while (end < detail.length() && end - start < MAX_FINGERPRINT_CHARS
        && isHex(detail.charAt(end))) {
      ++end;
    }
    return end == start ? null : detail.substring(start, end);
  }

  private static boolean isHex(final char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  /// What a second request for one subscribe identity on one connection means.
  private enum DuplicateVerdict {
    /// An earlier registration under this identity is still live, or the very same JSON-RPC id was
    /// sent twice: either way the peer received a request it already had.
    DUPLICATE,
    /// Every earlier registration under this identity had been cancelled before this request, so
    /// this is a new registration's first send and W1-B has nothing against it.
    SUPERSEDED,
    /// An earlier id cannot be resolved to a registration any more, so neither answer is evidence.
    UNJOINABLE
  }

  private DuplicateVerdict duplicateVerdict(final EngineHandle handle, final ConnectionView view,
                                            final String identity, final long msgId) {
    final long[] ids = view.msgIds(identity);
    boolean unresolved = view.truncated(identity);
    boolean earlier = false;
    for (final long id : ids) {
      if (id == msgId) {
        continue;
      }
      earlier = true;
      final var registration = handle.byMsgId(id);
      if (registration == null) {
        unresolved = true;
      } else if (!registration.unsubscribed
          && handle.registration(registration.channel, registration.key) == registration) {
        return DuplicateVerdict.DUPLICATE;
      }
    }
    if (!earlier) {
      // The same id arrived twice, which is the re-send of a successfully sent request the
      // javadoc forbids outright, whatever became of its registration.
      return DuplicateVerdict.DUPLICATE;
    }
    return unresolved ? DuplicateVerdict.UNJOINABLE : DuplicateVerdict.SUPERSEDED;
  }

  /// The ids seen under one identity before this request, for the failure example.
  private static String idList(final long[] ids, final long exclude) {
    final var list = new ArrayList<Long>(ids.length);
    for (final long id : ids) {
      if (id != exclude) {
        list.add(id);
      }
    }
    return list.toString();
  }

  private static Long subMsgId(final String detail) {
    final int at = detail.indexOf("subMsgId=");
    if (at < 0) {
      return null;
    }
    int end = at + "subMsgId=".length();
    while (end < detail.length() && Character.isDigit(detail.charAt(end))) {
      ++end;
    }
    try {
      return Long.parseLong(detail, at + "subMsgId=".length(), end, 10);
    } catch (final NumberFormatException e) {
      return null;
    }
  }

  private void onNotify(final EngineHandle handle, final ConnectionView view, final long subId,
                        final long wallMillis, final long attemptOrdinal, final String detail,
                        final long messageChars) {
    view.lastPeerFrameMillis = wallMillis;
    switch (detail) {
      case "UNKNOWN_SUB_NOTIFICATION" -> {
        counters.increment(Counters.WS_NOTIFICATIONS_UNKNOWN_SUB);
        deferred(Properties.W1_F, CONTAINMENT_MILLIS, () -> {
          // W1-F: an id the client never asked for must be dropped, not treated as fatal. A
          // retirement inside the window that a destructive fault on the same attempt explains is
          // not evidence either way: at the validate fault density some unknown-id notification
          // precedes every retirement, so without this exclusion the property could only fail.
          final var record = epochsByAttempt.get(attemptKey(handle, attemptOrdinal));
          if (record == null) {
            dropUnjoinable(Properties.W1_F);
          } else if (record.retiredMillis < 0L
              || record.retiredMillis - wallMillis > CONTAINMENT_MILLIS) {
            pass(Properties.W1_F);
          } else {
            judgeContainment(handle, attemptOrdinal, Properties.W1_F, () -> "engine=" + handle.name()
                + " attempt=" + attemptOrdinal + " retired " + (record.retiredMillis - wallMillis)
                + " ms after an unknown-subscription notification");
          }
        });
      }
      case "ZOMBIE_NOTIFICATION" -> {
        final var msgId = view.msgIdFor(subId);
        final var registration = msgId == null ? null : handle.byMsgId(msgId);
        if (registration == null) {
          // Nothing to attribute it to: the subscription is older than the join window, so this row
          // cannot say whether a consumer was invoked.
          dropUnjoinable(Properties.W1_D);
          return;
        }
        deferred(Properties.W1_D, CONTAINMENT_MILLIS, () -> {
          // W1-D: the peer sent one more notification for a subscription it had already
          // acknowledged as unsubscribed. The consumer must not see it.
          if (registration.lastDeliveryMillis >= wallMillis) {
            counters.increment(Counters.WS_NOTIFICATIONS_ZOMBIE);
            fail(Properties.W1_D, "engine=" + handle.name() + " key=" + registration.key
                + " subId=" + subId + " consumer invoked "
                + (registration.lastDeliveryMillis - wallMillis)
                + " ms after the peer emitted for an unsubscribed subscription");
          } else {
            pass(Properties.W1_D);
            subscriptionEvent("DROPPED_ZOMBIE", handle, registration, subId, registration.msgId);
          }
        });
      }
      case "MESSAGE_OVERFLOW" -> {
        if (messageChars >= 0L && messageChars <= handle.profile().maxMessageLength()) {
          // The fault schedule lands MESSAGE_OVERFLOW on every port; only the engine built with
          // the small cap actually overflows on it. Elsewhere it is an ordinary large message.
          counters.increment(OVERFLOW_BELOW_CAP);
          return;
        }
        deferred(Properties.W2_B, OVERFLOW_BOUND_MILLIS, () -> {
          // W2-B: a message the cap excludes aborts the connection and surfaces through onError.
          // The retired-and-errored pair is only evidence when this overflow is the one thing that
          // could have produced it; another retiring fault on the attempt supplies the same pair.
          final var record = epochsByAttempt.get(attemptKey(handle, attemptOrdinal));
          if (record == null) {
            dropUnjoinable(Properties.W2_B);
          } else if (confounded(handle, attemptOrdinal, KIND_MESSAGE_OVERFLOW)) {
            dropConfounded(Properties.W2_B);
          } else if (record.retiredMillis > 0L && record.errorsObserved > 0L) {
            pass(Properties.W2_B);
          } else {
            fail(Properties.W2_B, "engine=" + handle.name() + " attempt=" + attemptOrdinal
                + " a message beyond maxMessageLength(" + handle.profile().maxMessageLength()
                + ") left the connection alive: retired=" + (record.retiredMillis > 0L)
                + " onErrors=" + record.errorsObserved + " within " + OVERFLOW_BOUND_MILLIS + " ms");
          }
        });
      }
      default -> {
      }
    }
  }

  private void onPing(final EngineHandle handle, final ConnectionView view,
                      final long wallMillis, final long attemptOrdinal, final String detail) {
    pingRows.incrementAndGet();
    view.lastPingInMillis = wallMillis;
    if ("swallowed".equals(detail)) {
      // W1-H(ii): a probe the peer never answers has two windows to run out — the send window and
      // the response window — and then the transport is unresponsive and must be reported.
      final long bound = 2L * config.pingDelayMillis() + config.checkDelayMillis() + 2_000L;
      deferred(Properties.W1_H, bound, () -> {
        final var record = epochsByAttempt.get(attemptKey(handle, attemptOrdinal));
        if (record == null) {
          dropUnjoinable(Properties.W1_H);
        } else if (confounded(handle, attemptOrdinal, KIND_SWALLOW_PING)) {
          // Another retiring fault hit this attempt, so the retirement and the onError it reports
          // are not evidence that the unanswered probe was what ended the connection.
          dropConfounded(Properties.W1_H);
        } else if (view.lastPeerFrameMillis > wallMillis) {
          // The peer sent something after swallowing the ping (an answer to a request that
          // arrived inside its silence), and by the pingDelay contract any peer frame answers the
          // probe: the antecedent "a swallowed ping with nothing else arriving" did not hold.
          counters.increment(W1H_CONTACT_AFTER_SWALLOW);
          dropConfounded(Properties.W1_H);
        } else if (record.retiredMillis > 0L && record.errorsObserved > 0L) {
          final var cause = record.causeClass;
          if (cause == RetirementRecord.CauseClass.PING_RESPONSE_TIMEOUT
              || cause == RetirementRecord.CauseClass.PING_SEND_TIMEOUT) {
            counters.increment(W1H_PROBE_RETIREMENTS);
          } else {
            // The peer's phase-two silence starves every request sent into it as well, and the
            // engine's unanswered-request deadline (four resend delays) falls inside a ping
            // window longer than that — the pilot's 15 s pingDelay against a 12 s deadline. The
            // connection was still replaced and reported inside the probe's bound, which is what
            // the property asks; which detector fired is counted here, not judged.
            counters.increment(W1H_OTHER_RETIREMENTS);
          }
          pass(Properties.W1_H);
        } else {
          fail(Properties.W1_H, "engine=" + handle.name() + " attempt=" + attemptOrdinal
              + " swallowed ping: retired=" + (record.retiredMillis > 0L)
              + " onErrors=" + record.errorsObserved + " within " + bound + " ms");
        }
      });
      return;
    }
    // W1-H(i): the latest thing the peer said is the start of the silence the probe answers, so
    // taking the latest of message and pong makes this a one-sided check that only fails a probe
    // that really was late.
    final long silenceStart = Math.max(Math.max(view.lastPeerFrameMillis, view.firstSeenMillis),
        view.lastPongMillis);
    if (silenceStart <= 0L) {
      return;
    }
    final long due = silenceStart + config.pingDelayMillis();
    if (wallMillis < due) {
      // An early probe is the keep-alive poking a peer that has heard nothing from us; it is not
      // evidence about the ping window either way.
      return;
    }
    if (wallMillis <= due + config.checkDelayMillis() + 2_000L) {
      pass(Properties.W1_H);
    } else {
      fail(Properties.W1_H, "engine=" + handle.name() + " conn=" + view.connId
          + " ping arrived " + (wallMillis - due) + " ms after it was due (pingDelay="
          + config.pingDelayMillis() + " checkDelay=" + config.checkDelayMillis() + ')');
    }
  }

  /// `faults-applied.tsv`: `ordinal kind scope port connId attemptOrdinal wallMillis
  /// nanosSinceAnchor outcome detail`, with a header row.
  private void onFaultRow(final String line) {
    if (line.startsWith("ordinal\t")) {
      return;
    }
    final var cells = line.split("\t", -1);
    if (cells.length < 10) {
      counters.increment(PEER_ROWS_DROPPED);
      return;
    }
    if (!"applied".equals(cells[8])) {
      return;
    }
    final long ordinal = parseLong(cells[0]);
    final var kind = cells[1];
    final var scope = cells[2];
    final int port = (int) parseLong(cells[3]);
    final long connId = parseLong(cells[4]);
    final long attemptOrdinal = parseLong(cells[5]);
    final long wallMillis = parseLong(cells[6]);
    counters.increment(Counters.FAULTS_OBSERVED);
    // Tallied before the per-engine filters below, so "no <kind> fault was applied in this run" is
    // a statement about the peer's own log — the file a reviewer would check it against.
    countApplied(kind);

    final var event = new SoakEvents.FaultObserved();
    event.kind = kind;
    event.ordinal = ordinal;
    event.scope = scope;
    event.target = "port " + port;
    event.detail = cells[9];
    event.commit();

    final var handle = byPort.get(port);
    if (handle == null) {
      return;
    }
    if (attemptOrdinal >= 0L && (KIND_ERROR_RESPONSE.equals(kind) || KIND_REJECT_SUBSCRIBE.equals(kind))) {
      // Recorded before the retiring-fault filter below drops them: neither of these retires a
      // connection, and both are the only thing that says which error code a `sub_err` row carried.
      recordSubscribeAnswer(attemptKey(handle, attemptOrdinal), kind, wallMillis);
    }
    if (!EXPECTED_RETIREMENT_KINDS.contains(kind)) {
      // Admission is by what a fault *does*, never by where it lands. A CONN-scope fault the peer
      // does not mark `expectedRetirement()` — `SLOW_WRITE`, `CROSSTALK`, `NEVER_APPLY_KIND` — would
      // otherwise excuse an unexplained retirement it did not cause, excuse every containment check
      // on that attempt, and displace a genuine recovery window. Those kinds consequently no longer
      // open a P7 window either, which is the same correction seen from the other side.
      return;
    }
    if (KIND_MESSAGE_OVERFLOW.equals(kind) && overflowChars(cells[9]) <= handle.profile().maxMessageLength()) {
      // Not an overflow for this engine: its cap is above the message, so no retirement is owed.
      return;
    }
    if (attemptOrdinal >= 0L) {
      // Only the faults that retire a connection are remembered per attempt: they are what explain
      // a retirement. Every one of them is kept rather than the last, because a deferred check has
      // to know whether some *other* retiring fault could have produced the retirement it reads.
      recordFault(attemptKey(handle, attemptOrdinal), kind);
    }
    final long budgetMillis = recoveryBudgetMillis(kind);
    // P7 asks whether every live registration was confirmed again after a connection-level fault,
    // and its clock starts at the ADOPTION that follows the fault, for every kind. What lies
    // between the fault and that adoption is the manager's reconnect pacing (ravina's backoff)
    // and whatever the peer does to the attempts in between - a refusal window, a stalled
    // upgrade, a reset on the next attempt - none of which is sava's replay promise. Measured
    // twice: on `D7` a CONNECT_REFUSE window plus the backoff outlasted the budget before a socket
    // existed (`origin=-1`), and on a pilot a reset on one attempt was followed by six refused or
    // failed attempts, so a window opened at the reset aged out with nothing to recover on.
    // A window already open on this engine is displaced (superseded, no verdict): the fault it was
    // waiting through has been overtaken by this one, and the measurement re-opens at the
    // adoption that finally comes, with the base budget.
    recoveryClosed(ctx.recovery().displace(handle.name()));
    if (pendingFaults.get(handle.profile())
        .getAndSet(new PendingFault(ordinal, kind, budgetMillis, attemptOrdinal)) != null) {
      // The fault this one replaces never reached an adoption: overtaken before the engine had a
      // connection to recover on, so it leaves no record and no verdict. Counted, so the gap
      // between the faults anchored here and the windows opened is a number and not a mystery.
      counters.increment(RECOVERY_PENDING_OVERTAKEN);
    }
    counters.increment(RECOVERY_ANCHORED_AT_ADOPTION);
  }

  /// Opens the recovery window the last fault deferred, anchored on the connection that was
  /// finally adopted. The fault's own ordinal and kind travel with it so the record still names what
  /// broke, and confirmations count from this attempt onward — it is the recovery attempt, not the
  /// attempt the fault hit.
  private void openPendingRecovery(final EngineHandle handle, final long attemptOrdinal) {
    final var ref = pendingFaults.get(handle.profile());
    final var pending = ref.get();
    if (pending == null) {
      return;
    }
    if (pending.attemptOrdinal >= attemptOrdinal) {
      // The row names this very attempt: the fault hit it, and the peer's row outran the JDK's
      // onOpen. Measured on a pilot: a reset 2 ms after the handshake was tailed 7 ms before
      // onOpen fired, so the window opened on the casualty and expired before the recovery
      // attempt existed (a 20 s refusal window and the backoff lay between), reading P7 as a
      // 64-registration miss against an attempt that replayed and was confirmed within 5 ms.
      // This attempt is what the fault retires; the recovery is the adoption after it.
      counters.increment(RECOVERY_HELD_PAST_CASUALTY);
      return;
    }
    if (!ref.compareAndSet(pending, null)) {
      // A newer fault replaced it between the read and here; its own adoption opens it.
      return;
    }
    final long openedWallMillis = System.currentTimeMillis();
    recoveryClosed(ctx.recovery().openAtAdoption(pending.ordinal, handle.name(),
        pending.attemptOrdinal, attemptOrdinal, handle.liveMsgIds(), pending.kind,
        pending.budgetMillis, openedWallMillis));
    counters.increment(Counters.RECOVERY_OPENED);
    // The budget timer judges the peer's rows up to the deadline, so it waits for the tail to
    // have read that far (whenTailPast) before it closes an incomplete window.
    later(pending.budgetMillis, () -> whenTailPast(openedWallMillis + pending.budgetMillis,
        () -> recoveryClosed(ctx.recovery().closeIfOpen(handle.name(), pending.ordinal))));
  }

  /// `faults-applied.tsv`'s detail for an overflow is `message of <n> chars`.
  private static long overflowChars(final String detail) {
    final int at = detail.indexOf("message of ");
    if (at < 0) {
      return Long.MAX_VALUE;
    }
    int end = at + "message of ".length();
    final int start = end;
    while (end < detail.length() && Character.isDigit(detail.charAt(end))) {
      ++end;
    }
    try {
      return Long.parseLong(detail, start, end, 10);
    } catch (final NumberFormatException e) {
      return Long.MAX_VALUE;
    }
  }

  /// P7's budget for one fault kind. The derived budget assumes the engine learns of the fault at
  /// once; a swallowed ping can only be noticed by the two-phase probe, so that kind is owed the
  /// probe's own windows on top (`pingDelay` javadoc: a send window and a response window).
  private long recoveryBudgetMillis(final String kind) {
    final long base = ctx.recovery().budgetMillis();
    return KIND_SWALLOW_PING.equals(kind)
        ? base + 2L * config.pingDelayMillis() + config.checkDelayMillis()
        : base;
  }

  // ------------------------------------------------------------------------------ issue #52

  private void onRetirement(final RetirementRecord record) {
    counters.increment(Counters.I52_RETIREMENTS);
    // The tracker's record is the only account of a retirement that names the connection attempt it
    // ended, so it is what drives this workload's own epoch bookkeeping.
    final var retired = byEngineName.get(record.engine());
    if (retired != null) {
      retire(retired, record);
    }
    if (record.doubleClaim()) {
      counters.increment(Counters.I52_DOUBLE_CLAIMS);
    }
    switch (record.verdict()) {
      case DOUBLE_CLAIM_MISATTRIBUTED -> counters.increment(Counters.I52_MISATTRIBUTED);
      case DOUBLE_CLAIM_UNEXPLAINED -> counters.increment(Counters.I52_DOUBLE_CLAIMS_UNEXPLAINED);
      default -> {
      }
    }
    if (record.threadKind() == RetirementRecord.ThreadKind.ADOPTING) {
      counters.increment(Counters.I52_RETIREMENTS_INSIDE_ADOPT);
    }
    if (record.buildCancelObserved() && !record.buildWasDoneAtCancel()) {
      counters.increment(Counters.I52_FUTURE_SETTLED_BY_RETIREMENT);
    }
    if (record.futureRoute() == RetirementRecord.FutureRoute.ACCEPTED) {
      counters.record(Counters.HIST_I52_GAP, record.gapFutureToCallbackUs() * 1_000L);
      counters.record(Counters.HIST_I52_RETRY_MARGIN, Math.max(0L, record.retryMarginUs()) * 1_000L);
      if (record.retryDelayMs() * 1_000L <= record.gapFutureToCallbackUs()) {
        counters.increment(Counters.I52_OPPORTUNITIES);
      }
    }
    ctx.retirements().appendLine(record.toTsv());
  }

  private void onClaim(final ClaimRecord record) {
    counters.increment(Counters.I52_CLAIMS);
    if (record.route() == ClaimRecord.Route.UNROUTED) {
      counters.increment(Counters.I52_CLAIMS_UNROUTED);
    }
    if (record.originQuality() == ClaimRecord.OriginQuality.UNKNOWN) {
      counters.increment(Counters.I52_ORIGIN_UNKNOWN);
    }
    ctx.claims().appendLine(record.toTsv());
  }

  private void periodicSweep() {
    sweepTrackers();
    sweepPingWindows();
  }

  private void sweepTrackers() {
    for (final var handle : engines.values()) {
      handle.tracker().sweep();
    }
  }

  /// W1-H(i)'s antecedent, which no peer row can announce. The property is about a Ping the engine
  /// is supposed to *send*, so an engine that stopped pinging writes no `ping_in` row at all and the
  /// arrival-side check in [#onPing] is never reached: the whole property would then read as
  /// "nothing to say" rather than as the failure it is. This walks the connections the harness
  /// believes are open and fails the property for one that has been silent past the bound with no
  /// probe since that silence began.
  ///
  /// One-sided on purpose. Silence starts at the latest thing the peer said or answered, so a
  /// connection carrying notifications is never due a Ping; a view whose attempt is no longer the
  /// one the engine is driving is skipped rather than charged for a probe nobody was going to
  /// send; and the bound is the javadoc's own window plus the peer tail's measured lag, because
  /// this comparison — unlike the arrival-side one — puts a peer timestamp against the harness's
  /// own clock and must not charge the engine for the harness's read latency.
  private void sweepPingWindows() {
    if (!peerOracleAvailable) {
      return;
    }
    pollTails();
    final long now = System.currentTimeMillis();
    final long pingDelayMillis = config.pingDelayMillis();
    final long bound = pingDelayMillis + config.checkDelayMillis() + 2_000L
        + RETIREMENT_EXPLANATION_MILLIS;
    final List<ConnectionView> views;
    synchronized (connections) {
      views = new ArrayList<>(connections.values());
    }
    for (final var view : views) {
      if (view.closed || !view.handshakeOk) {
        // A view whose upgrade the peer refused is not a connection: `HANDSHAKE_RST` and its kin
        // leave the peer's side open with no close row and no frames, and charging the engine for a
        // keep-alive on one asks for a probe over a socket the peer reset. Measured on `I3`, where
        // the one W1-H failure of the run was a `HANDSHAKE_RST` view reported silent for 31 s.
        continue;
      }
      final var handle = byPort.get(view.port);
      if (handle == null || !handle.epochOpen()
          || handle.currentAttemptOrdinal() != view.attemptOrdinal) {
        // The connection is gone on this side, or the peer's view outlived the attempt the harness
        // is driving. Either way no Ping is owed on it.
        continue;
      }
      final long silenceStart = Math.max(Math.max(view.lastPeerFrameMillis, view.firstSeenMillis),
          view.lastPongMillis);
      if (silenceStart <= 0L || now - silenceStart <= bound) {
        continue;
      }
      if (view.lastPingInMillis >= silenceStart) {
        // The engine did probe inside this silence, so the antecedent this sweep exists for did not
        // happen. Whether the probe was *on time* is [#onPing]'s judgment, made against two peer
        // timestamps rather than against this sweep's clock; an unanswered one is W1-H(ii)'s.
        continue;
      }
      if (view.silenceReportedForStart == silenceStart) {
        continue;
      }
      view.silenceReportedForStart = silenceStart;
      silenceWindows.incrementAndGet();
      fail(Properties.W1_H, "engine=" + handle.name() + " conn=" + view.connId
          + " no ping after " + (now - silenceStart) + " ms of peer silence (pingDelay="
          + pingDelayMillis + " checkDelay=" + config.checkDelayMillis() + ')');
    }
  }

  // ---------------------------------------------------------------------------------- phases

  @Override
  public void onPhase(final Phase phase) {
    if (phase == Phase.QUIESCE || phase == Phase.DRAIN || phase == Phase.SHUTDOWN) {
      running.set(false);
    } else if (phase == Phase.STEADY && consumers.stallEnabled()) {
      // After the STEADY baselines, like the other deferred controls: the stall must be a change
      // the gauges can see against a baseline, not the baseline itself.
      later(HarnessControls.stallArmDelayMillis(), consumers::armStall);
    }
  }

  @Override
  public void quiesce(final Duration bound) throws Exception {
    running.set(false);
    final long deadline = System.nanoTime() + bound.toNanos();
    for (final var driver : drivers) {
      final long remaining = Math.min(DRIVER_JOIN_MILLIS, millisUntil(deadline));
      if (remaining > 0L) {
        driver.join(remaining);
      }
    }

    final long ackedBefore = acked();
    long requested = 0L;
    for (final var handle : engines.values()) {
      final var websocket = handle.websocket();
      if (websocket == null) {
        continue;
      }
      for (final var registration : handle.registrations()) {
        registration.unsubscribed = true;
        if (unsubscribeQuietly(handle, websocket, registration)) {
          ++requested;
        }
      }
    }

    // Wait for the peer to acknowledge what was asked for, so the residual is a real residue rather
    // than a race with the network.
    final long ackDeadline = Math.min(deadline, System.nanoTime() + Duration.ofSeconds(20).toNanos());
    while (System.nanoTime() < ackDeadline && acked() - ackedBefore < requested) {
      LockSupport.parkNanos(100L * 1_000_000L);
    }
    pollTails();
    // The quiesced peer stops emitting, which is the longest silence of the run and the one window
    // every open connection is certainly owed a keep-alive Ping for.
    sweepPingWindows();

    // W2-D is a measurement, never a verdict: the engine dispatches on the listener thread, so
    // head-of-line coupling between a slow consumer and a fast one is expected and NP-3 declines to
    // assert its absence.
    final var fast = counters.histogram(Counters.wsDelivery(ConsumerFactory.Kind.FAST.name()));
    final var slow = counters.histogram(Counters.wsDelivery(ConsumerFactory.Kind.SLOW.name()));
    if (fast != null && slow != null && fast.count() > 0L && slow.count() > 0L) {
      pass(Properties.W2_D);
    } else {
      notEvaluated(Properties.W2_D,
          "no run carried both FAST and SLOW deliveries; nothing to compare");
    }

    for (final var handle : engines.values()) {
      handle.tracker().flush();
      // The harness's own bounded structures report their overflow, because an oracle that quietly
      // stopped joining rows would read as a run with nothing to say rather than as a gap. Each one
      // gets its own name: "the join window aged a message id out" and "a peer row was unreadable"
      // are different failures and a single number could not tell the report which happened.
      counters.add(MSG_IDS_EVICTED, handle.msgIdOverflow());
      exportTrackerStats(handle);
    }
    for (final var view : connectionViews()) {
      // The per-connection join windows age ids, identities and subscription ids out under the
      // same name: each eviction is one row the W1-B and zombie joins can no longer resolve.
      counters.add(MSG_IDS_EVICTED, view.overflow());
    }
    counters.add(CONNECTION_VIEWS_EVICTED, connectionViewOverflow.get() + epochRecordOverflow.get());
    // The frame registry is process-wide, so its two numbers are set once here rather than summed
    // per engine: every tracker reads the same pair and adding them would multiply one level by
    // the engine count.
    final var registry = engines.isEmpty()
        ? Map.<String, Long>of()
        : engines.values().iterator().next().tracker().stats().snapshot();
    for (final var key : PROCESS_WIDE_TRACKER_STATS) {
      final var value = registry.get(key);
      if (value != null) {
        counters.set(I52_HARNESS_PREFIX + key, value);
      }
    }
  }

  @Override
  public void drain(final Duration bound) throws Exception {
    final long deadline = System.nanoTime() + bound.toNanos();

    // W1-I is asserted on the bare engine only: the manager treats a directly closed websocket as a
    // terminal wrapper failure, so closing a managed engine's instance would be testing the
    // manager's reaction rather than `close()`'s contract.
    final var bare = engines.get(EngineProfile.BARE);
    if (bare != null && bare.websocket() != null) {
      closeAndAssertTerminal(bare);
    }

    for (final var handle : engines.values()) {
      handle.closedByHarness(true);
      final var manager = handle.manager();
      if (manager != null) {
        manager.close();
      } else if (handle.profile() != EngineProfile.BARE) {
        final var websocket = handle.websocket();
        if (websocket != null) {
          websocket.close();
        }
      }
    }

    // W5-A for the engines this workload owns: after `close()` the engine's own check-loop executor
    // must be shut down. Without the add-opens there is no way to observe it, and a stated skip is a
    // NOTE while an unstated one is a FAIL.
    if (InternalProbes.available()) {
      for (final var handle : engines.values()) {
        final var websocket = handle.websocket();
        final long shutdown = InternalProbes.executorServiceShutdown(websocket);
        if (shutdown == 1L) {
          pass(Properties.W5_A);
        } else if (shutdown == 0L) {
          fail(Properties.W5_A, "engine=" + handle.name()
              + " executorServiceShutdown() false after close()");
        }
      }
    }

    final long remaining = millisUntil(deadline);
    if (remaining > 0L) {
      // Let the peer's view of the closes reach the log before the tail stops.
      LockSupport.parkNanos(Math.min(remaining, 1_000L) * 1_000_000L);
    }
    pollTails();
    stateUnexercised();
  }

  private void closeAndAssertTerminal(final EngineHandle handle) {
    final var websocket = handle.websocket();
    // Resolved before the close, while the epoch is still open: this is the connection the peer must
    // be seen closing, and it is the epoch's own, not the newest id on the port.
    pollTails();
    final var live = openPeerConnection(handle);
    handle.closedByHarness(true);
    websocket.close();
    counters.increment(Counters.WS_CLOSE_TERMINAL);

    if (websocket.connect() != null) {
      fail(Properties.W1_I, "engine=" + handle.name() + " connect() returned non-null after close()");
      return;
    }
    if (websocket.accountSubscribe(Commitment.CONFIRMED, keyTable[0], accountInfo -> {
    })) {
      fail(Properties.W1_I, "engine=" + handle.name() + " accountSubscribe returned true after close()");
      return;
    }
    if (!peerOracleAvailable) {
      pass(Properties.W1_I);
      return;
    }
    if (live == null) {
      // The two API halves of the contract are decided above; the transport half has no subject.
      // After the bare engine re-arms its own reconnect this should not happen, so it is counted and
      // stated rather than passed over: a NOTE beside a pass says which half of W1-I this run
      // actually established.
      counters.increment(CLOSE_WITHOUT_LIVE_CONNECTION);
      pass(Properties.W1_I);
      notEvaluated(Properties.W1_I, "close() reached the bare engine with no connection the peer had"
          + " open on the epoch's own attempt, so only the two API halves of the contract were"
          + " established; the bounded transport abort was not (" + CLOSE_WITHOUT_LIVE_CONNECTION
          + '=' + counters.get(CLOSE_WITHOUT_LIVE_CONNECTION) + ')');
      return;
    }
    final long deadline = System.nanoTime() + CLOSE_BOUND_MILLIS * 1_000_000L;
    while (System.nanoTime() < deadline) {
      pollTails();
      if (live.closed) {
        pass(Properties.W1_I);
        return;
      }
      LockSupport.parkNanos(100L * 1_000_000L);
    }
    fail(Properties.W1_I, "engine=" + handle.name() + " conn=" + live.connId
        + " attempt=" + live.attemptOrdinal
        + " peer did not observe the socket closed within " + CLOSE_BOUND_MILLIS + " ms");
  }

  @Override
  public void close() {
    running.set(false);
    consumers.releaseStall();
    tailRunning = false;
    final var thread = tailThread;
    if (thread != null) {
      try {
        thread.join(2_000L);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    for (final var handle : engines.values()) {
      try {
        handle.tracker().close();
      } catch (final RuntimeException e) {
        counters.increment("ws.close.trackerThrew");
      }
    }
    closeQuietly(wsTail);
    closeQuietly(faultTail);
  }

  /// Every property this workload owns that never got an observation is recorded with the reason it
  /// did not, because an unstated skip reads as UNEXERCISED and fails the run. The reasons are
  /// facts about the run, not excuses: a validate profile short enough to miss a fault kind is a
  /// real gap in the evidence and says so.
  ///
  /// Each reason is therefore *derived* from what this run measured — the peer's own applied-fault
  /// tally, this workload's drop and excuse tallies, the recovery ledger's counts — and never
  /// asserted from the shape of the code. A stated skip is what turns a FAIL into a NOTE, and a
  /// reason is a statement of fact in an artifact a human reads, so a cause the harness cannot show
  /// is left unsaid and the property keeps its UNEXERCISED verdict. Where a reason restates the
  /// evaluation's own antecedent ("no connection was adopted"), it is a derived fact too: every one
  /// of those produces a verdict, so an absent verdict is an absent antecedent.
  private void stateUnexercised() {
    final var ledger = ctx.properties();
    if (!peerOracleAvailable) {
      for (final var property : List.of(Properties.W1_A, Properties.W1_B, Properties.W1_C,
          Properties.W1_D, Properties.W1_E, Properties.W1_F, Properties.W1_H, Properties.W1_J,
          Properties.W2_A, Properties.W2_B, Properties.P7)) {
        if (ledger.evaluated(property.id()) == 0L) {
          ledger.notEvaluated(property.id(),
              "live run: no controlled peer, so this property has no oracle");
        }
      }
    } else {
      skipIfUnevaluated(Properties.W1_A, this::replaySkipReason);
      skipIfUnevaluated(Properties.W1_B, this::subscribeSkipReason);
      skipIfUnevaluated(Properties.W1_C,
          () -> faultDrivenSkipReason(KIND_SWALLOW_SUBSCRIBE, Properties.W1_C));
      skipIfUnevaluated(Properties.W1_D,
          () -> faultDrivenSkipReason(KIND_ZOMBIE_NOTIFICATION, Properties.W1_D));
      skipIfUnevaluated(Properties.W1_E, this::sequenceSkipReason);
      skipIfUnevaluated(Properties.W1_F,
          () -> faultDrivenSkipReason(KIND_UNKNOWN_SUB_NOTIFICATION, Properties.W1_F));
      skipIfUnevaluated(Properties.W1_H, this::pingSkipReason);
      skipIfUnevaluated(Properties.W1_J, this::programKeySkipReason);
      skipIfUnevaluated(Properties.W2_A, this::payloadSkipReason);
      skipIfUnevaluated(Properties.W2_B, this::overflowSkipReason);
      // P7 belongs to this workload too: the recovery ledger renders the verdict, but this driver
      // opens every window, and a schedule denser than the recovery budget supersedes them all.
      // Without a reason of its own such a run reads "UNEXERCISED with no stated skip" and fails —
      // over evidence the design deliberately declines to collect.
      skipIfUnevaluated(Properties.P7, this::recoverySkipReason);
    }
    // These three need no peer join, so they are stated in either mode.
    skipIfUnevaluated(Properties.W1_G, this::adoptionSkipReason);
    skipIfUnevaluated(Properties.W1_I, this::closeSkipReason);
    skipIfUnevaluated(Properties.W2_C, this::containmentSkipReason);
    if (!InternalProbes.available() && ctx.properties().evaluated(Properties.W5_A.id()) == 0L) {
      ctx.properties().notEvaluated(Properties.W5_A.id(),
          "executorServiceShutdown() needs --add-opens software.sava.rpc/"
              + "software.sava.rpc.json.http.ws=software.sava.rpc.soak");
    }
  }

  /// Records a stated skip only when the run can say why the property had no observation. A
  /// supplier that returns null has nothing measured to offer, and the property is left
  /// UNEXERCISED on purpose.
  private void skipIfUnevaluated(final Property property, final Supplier<String> reason) {
    if (ctx.properties().evaluated(property.id()) != 0L) {
      return;
    }
    final var stated = reason.get();
    if (stated != null) {
      ctx.properties().notEvaluated(property.id(), stated);
    }
  }

  /// The shape every fault-driven property's skip shares: "no `<kind>` fault was applied" is said
  /// only when the peer's applied tally says so, and otherwise the reason must name the exits that
  /// consumed the faults that *were* applied.
  private String faultDrivenSkipReason(final String kind, final Property property) {
    final long applied = appliedCount(kind);
    if (applied == 0L) {
      return "no " + kind + " fault was applied in this run";
    }
    final var exits = unverdicted(property);
    return exits == null
        ? null
        : applied + " " + kind + " fault(s) were applied and none produced a verdict: " + exits;
  }

  /// The measured ways a deferred check can end without a verdict once its stimulus *was* applied.
  /// Null when none of them fired: an absence of evidence that nothing accounts for is exactly what
  /// UNEXERCISED is for, and a reason invented here would convert it into a NOTE.
  private String unverdicted(final Property property) {
    final long unjoinable = count(checksUnjoinable, property);
    final long confounded = count(checksConfounded, property);
    final long excused = count(checksExcused, property);
    final long outstanding = count(checksOutstanding, property);
    if (unjoinable + confounded + excused + outstanding == 0L) {
      return null;
    }
    return unjoinable + " could not be joined to a connection attempt, " + confounded
        + " could not be attributed to their own stimulus (another retiring fault on the same"
        + " attempt, or a retirement before the property's own deadline), " + excused
        + " had a retirement an applied fault explains, " + outstanding
        + " were still inside their own bound when the run ended";
  }

  private String replaySkipReason() {
    final long retired = counters.get(Counters.WS_EPOCH_RETIRED);
    if (retired == 0L) {
      return "no connection was retired in this run, so no replay was owed";
    }
    final long superseded = counters.get(REPLAY_EPISODES_SUPERSEDED);
    final long unowed = replayEpisodesWithoutVerdict.get();
    final long pending = pendingReplayEpisodes();
    if (superseded + unowed + pending == 0L) {
      return null;
    }
    return retired + " connection retirement(s) produced no replay verdict: " + superseded
        + " episode(s) superseded by the next retirement, " + unowed
        + " owed nothing (no live registration the peer had seen sent), " + pending
        + " still inside the replay bound when the run ended";
  }

  private long pendingReplayEpisodes() {
    long pending = 0L;
    for (final var reference : replays.values()) {
      final var replay = reference.get();
      if (replay != null && !replay.settled) {
        ++pending;
      }
    }
    return pending;
  }

  private String subscribeSkipReason() {
    final long rows = subRequestRows.get();
    if (rows == 0L) {
      return "the peer recorded no subscribe request";
    }
    final long unjoinable = count(checksUnjoinable, Properties.W1_B);
    if (unjoinable > 0L) {
      return rows + " subscribe request row(s) were read and " + unjoinable + " of them could not"
          + " be judged: the connection's join window was full, or an earlier id under the same"
          + " subscribe identity no longer resolves to a registration ("
          + PEER_ROWS_DROPPED + '=' + counters.get(PEER_ROWS_DROPPED) + ')';
    }
    // Every row that carries an identity produces a verdict or a counted drop, so a run with
    // neither read only rows with no identity at all: no fp= fingerprint and no JSON-RPC id.
    return rows + " subscribe request row(s) were read, none of them carrying a subscribe identity"
        + " (no fp= fingerprint and no JSON-RPC id; " + W1B_MSG_ID_FALLBACK + '='
        + counters.get(W1B_MSG_ID_FALLBACK) + ')';
  }

  private String sequenceSkipReason() {
    final long delivered = counters.get(Counters.WS_NOTIFICATIONS_DELIVERED);
    if (delivered == 0L) {
      return "no notification was delivered in this run";
    }
    return delivered + " notification(s) were delivered, each the first the oracle saw for its"
        + " (key, epoch), which NP-1 declines to judge";
  }

  private String adoptionSkipReason() {
    // Every adoption evaluates W1-G, so a zero here can only mean no adoption; anything else would
    // be a defect in the oracle rather than a gap in the evidence, and is left UNEXERCISED.
    return counters.get(Counters.WS_EPOCH_OPENED) == 0L
        ? "no connection was adopted in this run"
        : null;
  }

  private String closeSkipReason() {
    final var bare = engines.get(EngineProfile.BARE);
    return bare == null || bare.websocket() == null
        ? "the bare engine was never built, and W1-I is asserted on that engine only"
        : null;
  }

  /// W1-J is evaluated on every program and keyed-program notification, so the only run that can
  /// leave it silent is one that carried none — the plan draws those two channels from a weighted
  /// mix, and a short profile can miss both. Anything else would mean a delivery produced neither a
  /// verdict nor a drop, which is a defect in the oracle rather than a gap in the evidence, and is
  /// left UNEXERCISED to say so.
  private String programKeySkipReason() {
    final long delivered = programNotifications.get();
    if (delivered == 0L) {
      return "no program or keyed-program notification was delivered in this run";
    }
    final long unjoinable = count(checksUnjoinable, Properties.W1_J);
    return unjoinable >= delivered
        ? delivered + " program notification(s) were delivered, none of them for a registration"
            + " whose granted program key could be recovered"
        : null;
  }

  private String payloadSkipReason() {
    // Every checksum-bearing delivery is compared, so no verdict means no such delivery.
    return "no checksum-bearing payload was delivered in this run";
  }

  private String overflowSkipReason() {
    final long applied = appliedCount(KIND_MESSAGE_OVERFLOW);
    if (applied == 0L) {
      return "no MESSAGE_OVERFLOW fault was applied in this run";
    }
    final long belowCap = counters.get(OVERFLOW_BELOW_CAP);
    if (belowCap >= applied) {
      return applied + " MESSAGE_OVERFLOW fault(s) were applied, every one of them below the"
          + " receiving engine's maxMessageLength, so nothing overflowed (" + OVERFLOW_BELOW_CAP
          + '=' + belowCap + ')';
    }
    final var exits = unverdicted(Properties.W2_B);
    return exits == null
        ? null
        : applied + " MESSAGE_OVERFLOW fault(s) were applied, " + belowCap
            + " of them below the receiving engine's cap, and none produced a verdict: " + exits;
  }

  private String containmentSkipReason() {
    final long threw = counters.get(Counters.WS_CONSUMER_THREW);
    if (threw == 0L) {
      return "no deliberate consumer throw occurred in this run";
    }
    final var exits = unverdicted(Properties.W2_C);
    return exits == null
        ? null
        : threw + " deliberate consumer throw(s) produced no verdict: " + exits;
  }

  /// W1-H has two halves and they fail for different reasons: (i) needs a silence window the engine
  /// owed a probe, (ii) needs a SWALLOW_PING fault. The reason names whichever of them the run can
  /// account for, and claims "the peer recorded no inbound ping" only when that is what happened.
  private String pingSkipReason() {
    final long pings = pingRows.get();
    final long windows = silenceWindows.get();
    final long swallowed = appliedCount(KIND_SWALLOW_PING);
    final var dueClause = pings == 0L
        ? "the peer recorded no inbound ping, and no silence window longer than pingDelay was"
            + " observed, so none was due"
        : pings + " inbound ping(s) were recorded, every one of them before pingDelay had elapsed"
            + " since the peer's last frame, so none was due";
    if (windows > 0L) {
      // A judged silence window always yields a verdict, so reaching here with one would mean the
      // sweep and the ledger disagree: say nothing rather than explain the contradiction away.
      return null;
    }
    if (swallowed == 0L) {
      return "no SWALLOW_PING fault was applied, and " + dueClause;
    }
    final var exits = unverdicted(Properties.W1_H);
    return exits == null
        ? null
        : swallowed + " SWALLOW_PING fault(s) were applied and none produced a verdict: " + exits
            + "; and " + dueClause;
  }

  /// P7's windows are opened by this driver and judged by the recovery ledger, which deliberately
  /// gives a superseded or vacuous window no verdict. That is the whole reason a dense schedule can
  /// leave the property unevaluated, so the numbers behind it are what the skip says.
  private String recoverySkipReason() {
    final long opened = counters.get(Counters.RECOVERY_OPENED);
    final long anchored = counters.get(RECOVERY_ANCHORED_AT_ADOPTION);
    if (opened == 0L) {
      return anchored == 0L
          ? "no connection-level fault opened a recovery window in this run"
          : anchored + " connection-level fault(s) landed before their connection's handshake"
              + " completed, so each waited for the next adoption to measure a recovery from, and"
              + " the run ended before one arrived (" + RECOVERY_ANCHORED_AT_ADOPTION + '='
              + anchored + ')';
    }
    final var recovery = ctx.recovery();
    final long superseded = recovery.superseded();
    final long vacuous = recovery.vacuous();
    final long stillOpen = recovery.openWindows().size();
    if (superseded + vacuous + stillOpen == 0L) {
      return null;
    }
    return opened + " recovery window(s) opened and none carried a verdict: " + superseded
        + " superseded by the next connection-level fault inside their budget, " + vacuous
        + " owed no confirmation, " + stillOpen + " still open when the run ended. A superseded"
        + " window is given no verdict by design, so a fault schedule denser than the recovery"
        + " budget leaves this property nothing to judge";
  }

  // ------------------------------------------------------------------------------ gauge source

  @Override
  public long liveSubscriptions() {
    long total = 0L;
    for (final var handle : engines.values()) {
      total += handle.liveRegistrations();
    }
    return total;
  }

  @Override
  public long tailLagMillis() {
    return peerOracleAvailable ? tailReadLagMillis : -1L;
  }

  @Override
  public long pendingConfirmations() {
    long total = 0L;
    for (final var handle : engines.values()) {
      total += handle.pendingConfirmations();
    }
    return total;
  }

  @Override
  public long inFlightRequests() {
    // This workload issues no HTTP requests; -1 says "not this source's number" rather than
    // contributing a zero the report would read as a measurement.
    return -1L;
  }

  @Override
  public long openConnections() {
    long open = 0L;
    for (final var handle : engines.values()) {
      if (handle.epochOpen()) {
        ++open;
      }
    }
    return open;
  }

  @Override
  public long lastMessageAgeMillisMax() {
    final long now = System.currentTimeMillis();
    long max = -1L;
    for (final var handle : engines.values()) {
      final var websocket = handle.websocket();
      if (websocket == null) {
        continue;
      }
      final long timestamp = websocket.lastMessageReceivedTimestamp();
      if (timestamp > 0L) {
        max = Math.max(max, now - timestamp);
      }
    }
    return max;
  }

  @Override
  public long retainedRegistrations() {
    return probeSum(InternalProbes::retainedRegistrations);
  }

  @Override
  public long retainedTombstones() {
    return probeSum(InternalProbes::retainedCancellationTombstones);
  }

  @Override
  public long retainedOrdinals() {
    return probeSum(InternalProbes::retainedOrdinalEntries);
  }

  @Override
  public long retainedExceptionSubscribers() {
    return probeSum(InternalProbes::retainedExceptionSubscribers);
  }

  private long probeSum(final java.util.function.ToLongFunction<SolanaRpcWebsocket> probe) {
    if (!InternalProbes.available()) {
      return InternalProbes.UNAVAILABLE;
    }
    long total = 0L;
    for (final var handle : engines.values()) {
      final var websocket = handle.websocket();
      if (websocket == null) {
        continue;
      }
      final long value = probe.applyAsLong(websocket);
      if (value < 0L) {
        return InternalProbes.UNAVAILABLE;
      }
      total += value;
    }
    return total;
  }

  // ----------------------------------------------------------------------------------- utility

  private boolean unsubscribeQuietly(final EngineHandle handle,
                                     final SolanaRpcWebsocket websocket,
                                     final EngineHandle.Registration registration) {
    try {
      handle.removeRegistration(registration.channel, registration.key);
      handle.unsubscribeRequested();
      counters.increment(Counters.WS_UNSUBSCRIBE_REQUESTED);
      released(handle, registration);
      // Quiesce sends immediately whatever the control asked of the steady-state path: the residual
      // the run is about to measure has to be a residue, not a cancellation still on a timer.
      registration.cancelledAwaitingWire = false;
      return sendUnsubscribe(websocket, registration, registration.channel, registration.keyIndex);
    } catch (final RuntimeException e) {
      return false;
    }
  }

  private long acked() {
    long total = 0L;
    for (final var handle : engines.values()) {
      total += handle.unsubscribeAckedCount();
    }
    return total;
  }

  /// One engine's attempt-tracker counters, copied into the run's own counters under
  /// `i52.harness.*`.
  ///
  /// [AttemptTracker.Stats] is where the #52 capture records that it stopped being able to
  /// attribute — a frame stack too deep, a sub-frame that did not fit, a wrapper identity that did
  /// not match — and nothing else in any artifact carries those numbers, so a truncated capture
  /// reads exactly like a clean one until they are exported. There is one tracker per engine, so
  /// the per-engine counters are *added*; the two process-wide registry numbers are excluded here
  /// and set once by the caller.
  private void exportTrackerStats(final EngineHandle handle) {
    for (final var entry : handle.tracker().stats().snapshot().entrySet()) {
      if (PROCESS_WIDE_TRACKER_STATS.contains(entry.getKey())) {
        continue;
      }
      counters.add(I52_HARNESS_PREFIX + entry.getKey(), entry.getValue());
    }
  }

  private List<ConnectionView> connectionViews() {
    synchronized (connections) {
      return new ArrayList<>(connections.values());
    }
  }

  private ConnectionView view(final long connId, final int port, final long attemptOrdinal) {
    synchronized (connections) {
      return connections.computeIfAbsent(viewKey(port, connId),
          key -> new ConnectionView(connId, port, attemptOrdinal, System.currentTimeMillis()));
    }
  }

  private static long viewKey(final int port, final long connId) {
    return ((long) port << 40) | (connId & 0xFF_FFFF_FFFFL);
  }

  private void putEpoch(final long attemptOrdinal, final EpochRecord record) {
    synchronized (epochsByAttempt) {
      epochsByAttempt.put(attemptOrdinal, record);
    }
  }

  private void recordFault(final long attemptKey, final String kind) {
    final AttemptFaults faults;
    synchronized (faultsByAttempt) {
      faults = faultsByAttempt.computeIfAbsent(attemptKey, key -> new AttemptFaults());
    }
    faults.add(kind);
  }

  private void recordSubscribeAnswer(final long attemptKey, final String kind, final long wallMillis) {
    final SubscribeAnswers answers;
    synchronized (answersByAttempt) {
      answers = answersByAttempt.computeIfAbsent(attemptKey, key -> new SubscribeAnswers());
    }
    answers.add(kind, wallMillis);
  }

  /// True when the peer recorded its outbound queue overflowing on one of this attempt's
  /// connections. Scanned rather than keyed, because a refused upgrade and its retry can share one
  /// attempt ordinal and the peer numbers them as separate connections.
  private boolean peerOverflowOn(final EngineHandle handle, final long attemptOrdinal) {
    if (attemptOrdinal < 0L) {
      return false;
    }
    for (final var view : connectionViews()) {
      if (view.peerOverflow && view.port == handle.port() && view.attemptOrdinal == attemptOrdinal) {
        return true;
      }
    }
    return false;
  }

  /// The peer's view of the connection this engine currently has open, or null when it has none.
  ///
  /// The join is the attempt ordinal the harness stamps on the upgrade request and the peer records,
  /// which is the one identity both processes agree on. The peer's *latest* connection id on the port
  /// is not that: on the bare engine it was a refused upgrade the peer had already reset, so waiting
  /// for the peer to observe that socket closing could only ever time out, which is how W1-I failed
  /// on `I3`.
  private ConnectionView openPeerConnection(final EngineHandle handle) {
    if (!handle.epochOpen()) {
      return null;
    }
    final long attemptOrdinal = handle.currentAttemptOrdinal();
    for (final var view : connectionViews()) {
      if (view.port == handle.port() && view.attemptOrdinal == attemptOrdinal
          && view.handshakeOk && !view.closed) {
        return view;
      }
    }
    return null;
  }

  /// True when a retiring fault *other than* this check's own stimulus was applied on the same
  /// connection attempt. A deferred check that reads `retiredMillis > 0` can then be reading the
  /// other fault's retirement, which says nothing either way about the property under test — so
  /// such a check is dropped rather than passed, and the drop is counted.
  private boolean confounded(final EngineHandle handle, final long attemptOrdinal, final String stimulus) {
    final var faults = faultsByAttempt.get(attemptKey(handle, attemptOrdinal));
    return faults != null && faults.anyKindOtherThan(stimulus);
  }

  private void countApplied(final String kind) {
    tally(appliedByKind, kind).incrementAndGet();
  }

  private long appliedCount(final String kind) {
    final var applied = appliedByKind.get(kind);
    return applied == null ? 0L : applied.get();
  }

  /// A check the harness could not join to the attempt it is about.
  private void dropUnjoinable(final Property property) {
    counters.increment(CHECKS_DROPPED);
    tally(checksUnjoinable, property.id()).incrementAndGet();
  }

  /// A check whose retirement cannot be attributed to its own stimulus: another retiring fault hit
  /// the same connection attempt, or the connection was already gone before the deadline the
  /// property is about. Neither a pass nor a failure — the check simply saw nothing it can read.
  private void dropConfounded(final Property property) {
    counters.increment(CHECKS_DROPPED);
    tally(checksConfounded, property.id()).incrementAndGet();
  }

  /// One named counter per key, created on first use and capped: the keys are fault kinds and
  /// property ids read off a log line, and a corrupted row must not be able to grow the map.
  private static AtomicLong tally(final Map<String, AtomicLong> tallies, final String key) {
    final var existing = tallies.get(key);
    if (existing != null) {
      return existing;
    }
    if (tallies.size() >= MAX_TALLY_KEYS) {
      return new AtomicLong();
    }
    return tallies.computeIfAbsent(key, name -> new AtomicLong());
  }

  private static long count(final Map<String, AtomicLong> tallies, final Property property) {
    final var value = tallies.get(property.id());
    return value == null ? 0L : value.get();
  }

  /// Schedules a deferred check, reporting whether it was actually scheduled: a caller that is
  /// counting its own outstanding checks must not go on believing in one the cap refused.
  private boolean later(final long delayMillis, final Runnable task) {
    if (pendingChecks.incrementAndGet() > MAX_PENDING_CHECKS) {
      pendingChecks.decrementAndGet();
      counters.increment(CHECKS_DROPPED);
      return false;
    }
    try {
      ctx.timer().schedule(() -> {
        try {
          task.run();
        } catch (final RuntimeException e) {
          counters.increment(CHECKS_DROPPED);
        } finally {
          pendingChecks.decrementAndGet();
        }
      }, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
      return true;
    } catch (final RejectedExecutionException e) {
      pendingChecks.decrementAndGet();
      counters.increment(CHECKS_DROPPED);
      return false;
    }
  }

  /// A deferred check that belongs to one property, counted while it is outstanding. A run that
  /// ends inside a check's own bound left that property no chance to speak, and that is a fact the
  /// ledger can state — as against guessing at a cause, which is what turns a missing verdict into
  /// a NOTE it has not earned.
  private void deferred(final Property property, final long delayMillis, final Runnable task) {
    final var outstanding = tally(checksOutstanding, property.id());
    outstanding.incrementAndGet();
    final boolean scheduled = later(delayMillis, () -> {
      try {
        task.run();
      } finally {
        outstanding.decrementAndGet();
      }
    });
    if (!scheduled) {
      outstanding.decrementAndGet();
    }
  }

  private void pass(final Property property) {
    ctx.properties().pass(property.id());
  }

  private void fail(final Property property, final String example) {
    ctx.properties().fail(property.id(), example);
  }

  private void notEvaluated(final Property property, final String reason) {
    ctx.properties().notEvaluated(property.id(), reason);
  }

  private void subscriptionEvent(final String action, final EngineHandle handle,
                                 final EngineHandle.Registration registration,
                                 final long subId, final long msgId) {
    final var event = new SoakEvents.SubscriptionEvent();
    if (!event.shouldCommit()) {
      return;
    }
    event.action = action;
    event.engine = handle.name();
    event.channel = registration.channel.name();
    event.key = registration.key;
    event.subId = subId;
    event.msgId = msgId;
    event.epoch = handle.currentEpoch();
    event.commit();
  }

  private void anomaly(final String kind, final EngineHandle handle, final String key,
                       final long epoch, final long expected, final long actual,
                       final long connectionOrdinal) {
    final var event = new SoakEvents.NotificationAnomaly();
    if (!event.shouldCommit()) {
      return;
    }
    event.kind = kind;
    event.engine = handle.name();
    event.key = key;
    event.epoch = epoch;
    event.expected = expected;
    event.actual = actual;
    event.connectionOrdinal = connectionOrdinal;
    event.commit();
  }

  /// Each engine's [AttemptTracker] numbers its own attempts from one, so an ordinal alone names
  /// four different connections. The engine's index is folded in to make the key unique across the
  /// run without allocating a composite object on the delivery path.
  private static long attemptKey(final EngineHandle handle, final long attemptOrdinal) {
    return ((long) handle.profile().oracleIndex() << 48) | (attemptOrdinal & 0xFFFF_FFFF_FFFFL);
  }

  private static long millisUntil(final long deadlineNanos) {
    return Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
  }

  private static long parseLong(final String cell) {
    try {
      return Long.parseLong(cell.trim());
    } catch (final NumberFormatException e) {
      return -1L;
    }
  }

  private static void closeQuietly(final TailReader reader) {
    if (reader != null) {
      reader.close();
    }
  }

  private static <K, V> Map<K, V> bounded(final int max, final AtomicLong overflow) {
    return Collections.synchronizedMap(new LinkedHashMap<K, V>(Math.min(1024, max), 0.75f, false) {
      @Override
      protected boolean removeEldestEntry(final Map.Entry<K, V> eldest) {
        final boolean evict = size() > max;
        if (evict) {
          overflow.incrementAndGet();
        }
        return evict;
      }
    });
  }

  // -------------------------------------------------------------------------- internal state

  /// One peer connection as the harness sees it in the peer's log. The join between the two
  /// processes is `attemptOrdinal`: the harness stamps it on the upgrade request, and the peer
  /// records what it received, so neither side has to trust the other's clock for identity.
  private static final class ConnectionView {

    private final long connId;
    private final int port;
    private final long attemptOrdinal;
    private final long firstSeenMillis;
    /// Keyed on the *subscribe identity* — the peer's `fp=` fingerprint of `method+params` where
    /// the row carries one — rather than on the JSON-RPC id, because W1-B is about a byte-identical
    /// request reaching the peer twice and a fresh id makes a duplicate invisible to an id-keyed
    /// count. The ids that were sent under each identity are kept alongside it, since deciding
    /// whether a second request is a re-send or a new registration needs them.
    private final Map<String, Subscribes> subscribeCounts;
    private final Map<Long, String> identityByMsgId;
    private final Map<Long, Long> msgIdBySubId;
    private final AtomicLong overflow = new AtomicLong();
    private volatile long lastPeerFrameMillis;
    private volatile long lastPongMillis;
    /// The last inbound Ping the peer logged, and the silence window this connection has already
    /// been failed for. Without the second, one unanswered silence would be reported on every
    /// sweep; the value is the window's own start, so a new silence is reported again.
    private volatile long lastPingInMillis;
    private volatile long silenceReportedForStart = -1L;
    private volatile boolean closed;
    /// Whether the peer answered this connection's upgrade at all, and whether the answer was `101`.
    /// A refused upgrade never became a connection: it is owed no keep-alive probe, and asking the
    /// peer whether it observed such a socket closing asks about a socket the peer reset itself.
    private volatile boolean handshakeAnswered;
    private volatile boolean handshakeOk;
    /// The peer's own `overflow` row for this connection: its outbound queue filled and it closed
    /// 1011. The retirement that follows is the peer's, not the engine's.
    private volatile boolean peerOverflow;

    private ConnectionView(final long connId, final int port, final long attemptOrdinal,
                           final long firstSeenMillis) {
      this.connId = connId;
      this.port = port;
      this.attemptOrdinal = attemptOrdinal;
      this.firstSeenMillis = firstSeenMillis;
      this.subscribeCounts = bounded(MAX_MSG_IDS_PER_CONNECTION, overflow);
      this.identityByMsgId = bounded(MAX_MSG_IDS_PER_CONNECTION, overflow);
      this.msgIdBySubId = bounded(MAX_MSG_IDS_PER_CONNECTION, overflow);
    }

    /// Message ids, identities and subscription ids this view aged out. Reported with the engine's
    /// own eviction count: a join window that silently stopped joining reads as a run with nothing
    /// to say rather than as the gap it is.
    private long overflow() {
      return overflow.get();
    }

    /// The counts for one subscribe identity, created on first sight. At the join window's ceiling
    /// the eldest identity is evicted rather than this one refused, and the eviction is counted:
    /// the harness then forgets an identity's history, which can hide a later duplicate but can
    /// never manufacture one.
    private Subscribes subscribes(final String identity, final Counters counters) {
      synchronized (subscribeCounts) {
        final var existing = subscribeCounts.get(identity);
        if (existing != null) {
          return existing;
        }
        if (subscribeCounts.size() >= MAX_MSG_IDS_PER_CONNECTION) {
          counters.increment(PEER_ROWS_DROPPED);
        }
        final var fresh = new Subscribes();
        subscribeCounts.put(identity, fresh);
        return fresh;
      }
    }

    /// Remembers which identity a message id was sent under, so the rows that carry only an id —
    /// `sub_ack`, `sub_err`, `unsub_req` — can still find it.
    private void bindIdentity(final long msgId, final String identity, final Counters counters) {
      if (msgId < 0L) {
        return;
      }
      synchronized (identityByMsgId) {
        if (identityByMsgId.size() >= MAX_MSG_IDS_PER_CONNECTION) {
          counters.increment(PEER_ROWS_DROPPED);
        }
        identityByMsgId.put(msgId, identity);
      }
    }

    private String identityFor(final long msgId) {
      return msgId < 0L ? null : identityByMsgId.get(msgId);
    }

    private void bindSubId(final long subId, final long msgId, final Counters counters) {
      if (subId < 0L || msgId < 0L) {
        return;
      }
      synchronized (msgIdBySubId) {
        if (msgIdBySubId.size() >= MAX_MSG_IDS_PER_CONNECTION) {
          counters.increment(PEER_ROWS_DROPPED);
        }
        msgIdBySubId.put(subId, msgId);
      }
    }

    private Long msgIdFor(final long subId) {
      return msgIdBySubId.get(subId);
    }

    /// One subscribe request received for this identity, returning how many now stand without an
    /// intervening cancellation. A connection whose join window is full keeps counting and evicts
    /// its eldest identity — which can only *miss* a later duplicate, never invent one — and every
    /// eviction is counted and reported.
    private int request(final String identity, final long msgId, final Counters counters) {
      final var subscribes = subscribes(identity, counters);
      synchronized (subscribeCounts) {
        subscribes.record(msgId);
        return ++subscribes.requests;
      }
    }

    /// The message ids seen under one identity on this connection, oldest first. Empty when the
    /// identity is unknown; a list that hit its own cap says so through [Subscribes#truncated].
    private long[] msgIds(final String identity) {
      synchronized (subscribeCounts) {
        final var subscribes = subscribeCounts.get(identity);
        return subscribes == null ? new long[0] : subscribes.msgIds();
      }
    }

    private boolean truncated(final String identity) {
      synchronized (subscribeCounts) {
        final var subscribes = subscribeCounts.get(identity);
        return subscribes != null && subscribes.truncated;
      }
    }

    /// Starts the identity's count again from this request: the registrations its earlier ids
    /// belonged to are all gone, so nothing before it can make a later request a re-send.
    private void restart(final String identity, final long msgId) {
      synchronized (subscribeCounts) {
        final var subscribes = subscribeCounts.get(identity);
        if (subscribes != null) {
          subscribes.restartWith(msgId);
        }
      }
    }

    /// An unsubscribe of the id this identity was granted under: a later subscribe of the same
    /// identity is then a new registration rather than the duplicate W1-B forbids.
    private void cancelled(final String identity) {
      synchronized (subscribeCounts) {
        final var subscribes = subscribeCounts.get(identity);
        if (subscribes != null) {
          subscribes.clear();
        }
      }
    }

    /// Identities the peer answered with an error; a later subscribe under one of them is the
    /// engine's retry, counted as such rather than as a W1-B duplicate.
    private void answeredWithError(final String identity) {
      synchronized (subscribeCounts) {
        final var subscribes = subscribeCounts.get(identity);
        if (subscribes != null) {
          subscribes.answeredWithError = true;
          if (subscribes.requests > 0) {
            subscribes.requests = 0;
          }
        }
      }
    }

    private boolean resentAfterError(final String identity) {
      synchronized (subscribeCounts) {
        final var subscribes = subscribeCounts.get(identity);
        if (subscribes != null && subscribes.answeredWithError) {
          subscribes.answeredWithError = false;
          return true;
        }
        return false;
      }
    }
  }

  /// What one subscribe identity has done on one connection: how many requests for it the peer
  /// received without an intervening cancellation, the ids they were sent under, and whether the
  /// peer answered the last one with an error.
  ///
  /// The id list is bounded because it comes off a log the peer writes: an identity that keeps
  /// arriving under new ids is already a W1-B failure at the second one, so a cap that keeps the
  /// first few ids loses nothing a verdict needs. It is flagged when it binds, because a truncated
  /// list can no longer prove that *every* earlier registration was gone.
  private static final class Subscribes {

    private static final int MAX_MSG_IDS = 8;

    private final long[] msgIds = new long[MAX_MSG_IDS];
    private int msgIdCount;
    private boolean truncated;
    private int requests;
    private boolean answeredWithError;

    private void record(final long msgId) {
      if (msgId < 0L) {
        return;
      }
      for (int i = 0; i < msgIdCount; ++i) {
        if (msgIds[i] == msgId) {
          return;
        }
      }
      if (msgIdCount == MAX_MSG_IDS) {
        truncated = true;
        return;
      }
      msgIds[msgIdCount++] = msgId;
    }

    private long[] msgIds() {
      return Arrays.copyOf(msgIds, msgIdCount);
    }

    /// This request is the identity's first again: everything before it belonged to registrations
    /// that are gone.
    private void restartWith(final long msgId) {
      clear();
      requests = 1;
      record(msgId);
    }

    private void clear() {
      msgIdCount = 0;
      truncated = false;
      requests = 0;
      answeredWithError = false;
    }
  }

  /// What the deferred checks need to know about one adopted connection after the callback that
  /// created it has returned.
  private static final class EpochRecord {

    private final long epoch;
    private volatile long retiredMillis = -1L;
    private volatile long errorsObserved;
    /// What the attempt tracker recorded this connection retiring of, or null while it is live. It
    /// is what admits or excuses a containment check: the retirement's cause, not its timing.
    private volatile RetirementRecord.CauseClass causeClass;
    /// The containment properties that have already rendered their one verdict for this attempt.
    private final Set<String> judged = new LinkedHashSet<>(4);

    private EpochRecord(final long epoch) {
      this.epoch = epoch;
    }

    /// True for the first caller per property id: the connection's containment is one question with
    /// one answer, however many notifications ask it.
    private synchronized boolean claimVerdict(final String propertyId) {
      return judged.add(propertyId);
    }

    /// True for the first report of this attempt's retirement, which is the one that writes the
    /// stamp every deferred oracle then reads.
    private synchronized boolean markRetired(final long millis,
                                             final RetirementRecord.CauseClass cause) {
      if (retiredMillis > 0L) {
        return false;
      }
      retiredMillis = millis;
      causeClass = cause;
      return true;
    }
  }

  /// The retiring faults applied on one connection attempt — all of them, not just the last. Two
  /// retiring faults can land on one connection, and a deferred check that had seen only the second
  /// would credit its own stimulus with the other one's retirement.
  private static final class AttemptFaults {

    private final Set<String> kinds = new LinkedHashSet<>(4);

    private synchronized void add(final String kind) {
      if (kinds.size() < MAX_KINDS_PER_ATTEMPT) {
        kinds.add(kind);
      }
    }

    private synchronized boolean anyKindOtherThan(final String stimulus) {
      for (final var kind : kinds) {
        if (!kind.equals(stimulus)) {
          return true;
        }
      }
      return false;
    }
  }

  /// The subscribe-answering faults on one attempt, each with the peer's own timestamp.
  ///
  /// Two are kept apart rather than one flag, because they answer with different codes and sava
  /// treats the codes differently: a `sub_err` frame has to be matched to the record that produced
  /// it, not merely to an attempt that had rejections on it. A cap, because the list comes off a log
  /// the peer writes and an attempt with more rejections than this is past the point where matching
  /// one frame to one record means anything.
  private static final class SubscribeAnswers {

    private static final int MAX_ANSWERS = 32;

    private final long[] millis = new long[MAX_ANSWERS];
    private final boolean[] requestDefect = new boolean[MAX_ANSWERS];
    private int count;
    /// A -32603 the peer actually wrote on this attempt (its `sub_err` row), as opposed to one it
    /// recorded as applied at enqueue time.
    private volatile boolean serverConditionTransmitted;

    private synchronized void add(final String kind, final long wallMillis) {
      if (count == MAX_ANSWERS) {
        return;
      }
      millis[count] = wallMillis;
      requestDefect[count] = KIND_ERROR_RESPONSE.equals(kind);
      ++count;
    }

    /// Whether the rejection the peer wrote at `wallMillis` was one sava treats as terminal. The
    /// nearest recorded answer inside the slack decides; nothing inside it answers nothing, and the
    /// caller then leaves the registration alone.
    private synchronized boolean requestDefectAt(final long wallMillis) {
      long bestDistance = Long.MAX_VALUE;
      boolean best = false;
      for (int i = 0; i < count; ++i) {
        final long distance = Math.abs(millis[i] - wallMillis);
        if (distance <= REJECTION_JOIN_SLACK_MILLIS && distance < bestDistance) {
          bestDistance = distance;
          best = requestDefect[i];
        }
      }
      return bestDistance != Long.MAX_VALUE && best;
    }
  }

  /// A fault that landed before a connection existed, waiting for the adoption its recovery window
  /// is measured from. `attemptOrdinal` is the fault's own, kept for the record even when it is -1:
  /// the report has to be able to say the fault hit no identifiable connection.
  private static final class PendingFault {

    private final long ordinal;
    private final String kind;
    private final long budgetMillis;
    private final long attemptOrdinal;

    private PendingFault(final long ordinal, final String kind, final long budgetMillis,
                         final long attemptOrdinal) {
      this.ordinal = ordinal;
      this.kind = kind;
      this.budgetMillis = budgetMillis;
      this.attemptOrdinal = attemptOrdinal;
    }
  }

  /// One replay episode: the registrations that were live when a connection was retired, and the
  /// successor that owes them a subscribe request.
  private static final class PendingReplay {

    private final String engine;
    private final long retiredAttemptOrdinal;
    private final long retiredMillis;
    private final Set<Long> outstanding;
    private final int expected;
    private final int registrationsAtRetirement;
    private volatile long adoptedAttemptOrdinal = -1L;
    private volatile long adoptedMillis = -1L;
    private volatile boolean settled;

    private PendingReplay(final String engine, final long retiredAttemptOrdinal,
                          final long retiredMillis, final Set<Long> outstanding,
                          final int registrationsAtRetirement) {
      this.engine = engine;
      this.retiredAttemptOrdinal = retiredAttemptOrdinal;
      this.retiredMillis = retiredMillis;
      this.outstanding = Collections.synchronizedSet(outstanding);
      this.expected = outstanding.size();
      this.registrationsAtRetirement = registrationsAtRetirement;
    }

    /// True when nothing on the wire identified a live registration — every one of them had been
    /// registered but never sent, so there is no message id to look for and no evidence either way.
    private boolean vacuous() {
      return expected == 0;
    }
  }

  /// A follow-the-file reader for an append-only peer log.
  ///
  /// It splits on bytes rather than characters so a multi-byte sequence straddling two reads cannot
  /// be corrupted, and it carries an unterminated tail forward because the peer flushes on a line
  /// boundary but the file system does not. A tail that grows past the cap without a newline is
  /// discarded and counted: a log that is no longer line-oriented has stopped being evidence.
  private static final class TailReader {

    private static final int READ_BYTES = 1 << 16;
    private static final int MAX_CARRY = 1 << 20;

    private final Path file;
    private final ByteBuffer buffer = ByteBuffer.allocate(READ_BYTES);
    private final ByteArrayOutputStream carry = new ByteArrayOutputStream(1024);
    private FileChannel channel;
    private long position;
    /// True when the last poll found nothing new: the reader is at the end of what the peer has
    /// flushed. A judgment waiting for rows up to a deadline may proceed on this once the peer
    /// has had time to flush anything it wrote before that deadline.
    private volatile boolean caughtUp;

    private TailReader(final Path file) {
      this.file = file;
    }

    private boolean caughtUp() {
      return caughtUp;
    }

    private void poll(final Consumer<String> lines) throws IOException {
      if (channel == null) {
        if (!Files.exists(file)) {
          return;
        }
        channel = FileChannel.open(file, StandardOpenOption.READ);
      }
      while (true) {
        buffer.clear();
        final int read = channel.read(buffer, position);
        if (read <= 0) {
          caughtUp = true;
          return;
        }
        caughtUp = false;
        position += read;
        buffer.flip();
        while (buffer.hasRemaining()) {
          final byte b = buffer.get();
          if (b == '\n') {
            lines.accept(carry.toString(StandardCharsets.UTF_8));
            carry.reset();
          } else if (carry.size() < MAX_CARRY) {
            carry.write(b);
          } else {
            carry.reset();
          }
        }
      }
    }

    private void close() {
      final var open = channel;
      channel = null;
      if (open != null) {
        try {
          open.close();
        } catch (final IOException e) {
          // Nothing left to do with a log we are finished reading.
        }
      }
    }
  }
}
