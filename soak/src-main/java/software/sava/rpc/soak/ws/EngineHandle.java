package software.sava.rpc.soak.ws;

import software.sava.rpc.json.http.ws.Subscription;
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.rpc.soak.Seeds;
import software.sava.rpc.soak.issue52.AttemptTracker;
import software.sava.services.solana.websocket.WebSocketManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/// Everything one engine owns, and the small amount of state the oracles need to ask it about.
///
/// The registration table is the harness's own record of what it asked for, which is what makes
/// W1-A assertable at all: sava replays its internal registry, and an oracle built from that same
/// registry would only be checking the engine against itself. Here the harness made every call, so
/// the set it believes is live is independent evidence, and the peer's view of what arrived on the
/// wire is a third.
///
/// Registrations are identified on the wire by their JSON-RPC message id. That identity is stable
/// for the life of a registration — the engine assigns it once and re-sends the same frame on every
/// replay — so a peer log row joins to a harness registration by `msgId` alone, without the peer
/// ever having to log request parameters.
final class EngineHandle {

  /// One durable registration the harness asked for.
  ///
  /// `msgId` starts unknown because it is only learned from the `onSub` callback, which fires after
  /// the first successful *send*. A registration that has never been sent therefore carries -1 and
  /// is excluded from the wire-side oracles: it has no identity there to look for.
  static final class Registration {

    final SubscriptionPlan.Channel channel;
    final String key;
    final int keyIndex;
    final long createdMillis;
    /// The [software.sava.rpc.soak.oracle.SequenceOracle] slot this registration owns while it is
    /// live. It is a slot rather than the plan's key index because two registrations on one engine
    /// can draw the same key index for different channels, and sharing an oracle slot between two
    /// independent peer sequences would manufacture gaps that nothing sent.
    final int oracleSlot;
    /// Distinguishes successive registrations that reuse one slot. It is folded into the epoch the
    /// oracle is given, so a reused slot resets rather than comparing a new subscription's first
    /// sequence against its predecessor's last.
    final long generation;
    volatile long msgId = -1L;
    volatile long confirmedEpoch = -1L;
    /// The engine's own subscription object, from the send callback; its `subId()` is the grant.
    volatile Subscription<?> subscription;
    volatile long lastDeliveryMillis = -1L;
    volatile boolean unsubscribed;
    /// Set when the server answered this registration's subscribe with a code that blames the
    /// request itself. `SolanaJsonRpcWebsocket` treats such a rejection as the request's terminal
    /// state — it removes the pending subscription and frees the registry slot — so from that
    /// moment the engine owes it neither a replay nor a confirmation, and the oracles that ask for
    /// either would be asking for behaviour sava documents itself as not having.
    volatile boolean requestDefectRetired;
    /// Set while the harness has tombstoned this registration in its own registry but has not yet
    /// put the unsubscribe on the wire. Only the D7 control opens that gap; everywhere else the
    /// two happen together, so the flag is never set and no ordinary run can be judged under it.
    volatile boolean cancelledAwaitingWire;

    Registration(final SubscriptionPlan.Channel channel,
                 final String key,
                 final int keyIndex,
                 final int oracleSlot,
                 final long generation,
                 final long createdMillis) {
      this.channel = channel;
      this.key = key;
      this.keyIndex = keyIndex;
      this.oracleSlot = oracleSlot;
      this.generation = generation;
      this.createdMillis = createdMillis;
    }

    /// The registry identity: a generic key is unique only within its notification method, and a
    /// singleton channel has no key at all, so the channel has to be part of it.
    String identity() {
      return channel.name() + ':' + key;
    }

    /// The value handed to the sequence oracle as its epoch. A change of connection **or** of
    /// registration resets the slot's expectation, which is what NP-1 and slot reuse respectively
    /// require; the connection epoch alone would confuse the second case for a gap.
    long oracleEpoch(final long connectionEpoch) {
      return (connectionEpoch << 24) | (generation & 0xFF_FFFFL);
    }
  }

  /// How many retired message ids stay resolvable after their registration is gone. A peer row can
  /// arrive after the harness has forgotten the registration it names — a notification already on
  /// the wire when the unsubscribe was acked — and resolving it is how a zombie delivery is
  /// recognised rather than ignored. The cap keeps that memory from being the harness's own leak.
  private static final int MSG_ID_HISTORY = 4096;

  private final EngineProfile profile;
  private final String name;
  private final int port;
  private final AttemptTracker tracker;
  private final boolean liveConfirmations;
  private final ConcurrentHashMap<String, Registration> registrations;
  private final Map<Long, Registration> byMsgId;
  private final AtomicLong msgIdOverflow;
  private final AtomicLong epochs;
  private final AtomicLong unsubRequested;
  private final AtomicLong unsubAcked;
  private final ArrayDeque<Integer> freeSlots;
  private final AtomicLong generations;
  private final AtomicLong slotExhaustion;

  private volatile WebSocketManager manager;
  private volatile SolanaRpcWebsocket websocket;
  private volatile long currentEpoch = -1L;
  private volatile long currentAttemptOrdinal = -1L;
  private volatile boolean epochOpen;
  private volatile long lastMessageTimestamp;
  private volatile boolean closedByHarness;

  /// `liveConfirmations`: a live run has no peer log to establish a confirmation from, so
  /// [#pendingConfirmations()] reads the engine's own grant (`Subscription.subId()`) instead.
  EngineHandle(final EngineProfile profile, final int port, final AttemptTracker tracker,
               final boolean liveConfirmations) {
    this.profile = profile;
    this.name = profile.engineName();
    this.port = port;
    this.tracker = tracker;
    this.liveConfirmations = liveConfirmations;
    this.registrations = new ConcurrentHashMap<>(256);
    this.byMsgId = java.util.Collections.synchronizedMap(new LinkedHashMap<>(512, 0.75f, false) {
      @Override
      protected boolean removeEldestEntry(final Map.Entry<Long, Registration> eldest) {
        final boolean evict = size() > MSG_ID_HISTORY;
        if (evict) {
          msgIdOverflow.incrementAndGet();
        }
        return evict;
      }
    });
    this.msgIdOverflow = new AtomicLong();
    this.epochs = new AtomicLong();
    this.unsubRequested = new AtomicLong();
    this.unsubAcked = new AtomicLong();
    this.freeSlots = new ArrayDeque<>(Seeds.KEY_TABLE_SIZE);
    for (int slot = 0; slot < Seeds.KEY_TABLE_SIZE; ++slot) {
      freeSlots.addLast(slot);
    }
    this.generations = new AtomicLong();
    this.slotExhaustion = new AtomicLong();
  }

  EngineProfile profile() {
    return profile;
  }

  String name() {
    return name;
  }

  int port() {
    return port;
  }

  AttemptTracker tracker() {
    return tracker;
  }

  WebSocketManager manager() {
    return manager;
  }

  void manager(final WebSocketManager value) {
    this.manager = value;
  }

  /// The engine instance, taken from `onNewWebSocket` for a managed engine and from `create()` for
  /// the bare one. The manager's `webSocket()` accessor is deliberately never polled: it drives
  /// retries, so reading it to find the instance would make the harness a participant in the
  /// reconnect policy it is observing.
  SolanaRpcWebsocket websocket() {
    return websocket;
  }

  void websocket(final SolanaRpcWebsocket value) {
    this.websocket = value;
  }

  // --------------------------------------------------------------------------- registrations

  /// Registers a new durable subscription, or returns null when one already exists under this
  /// identity or when the oracle slots are exhausted. Both refusals are the caller's cue to skip
  /// the operation rather than to retry it: the first is the engine's own deduplication rule seen
  /// from the harness side, and the second is counted so a run cannot quietly stop checking
  /// sequences.
  Registration register(final SubscriptionPlan.Channel channel,
                        final String key,
                        final int keyIndex) {
    final Integer slot;
    synchronized (freeSlots) {
      slot = freeSlots.pollFirst();
    }
    if (slot == null) {
      slotExhaustion.incrementAndGet();
      return null;
    }
    final var registration = new Registration(channel, key, keyIndex, slot,
        generations.incrementAndGet(), System.currentTimeMillis());
    final var previous = registrations.putIfAbsent(registration.identity(), registration);
    if (previous != null) {
      releaseSlot(slot);
      return null;
    }
    return registration;
  }

  /// Returned to the free list only on removal, so a live registration can never share a slot.
  private void releaseSlot(final int slot) {
    synchronized (freeSlots) {
      freeSlots.addLast(slot);
    }
  }

  long slotExhaustion() {
    return slotExhaustion.get();
  }

  Registration registration(final SubscriptionPlan.Channel channel, final String key) {
    return registrations.get(channel.name() + ':' + key);
  }

  Registration removeRegistration(final SubscriptionPlan.Channel channel, final String key) {
    final var removed = registrations.remove(channel.name() + ':' + key);
    if (removed != null) {
      releaseSlot(removed.oracleSlot);
    }
    return removed;
  }

  /// Drops every registration, for the case the manager replaces the whole wrapper: the successor
  /// instance has its own empty registry, so the harness's belief about what is live has to be
  /// dropped with it rather than carried into a set the engine never had.
  void clearRegistrations() {
    for (final var registration : registrations.values()) {
      releaseSlot(registration.oracleSlot);
    }
    registrations.clear();
  }

  int liveRegistrations() {
    return registrations.size();
  }

  List<Registration> registrations() {
    return new ArrayList<>(registrations.values());
  }

  /// Binds a wire message id to its registration. Called from `onSub`, which fires on every
  /// successful send including replays, so the binding is refreshed rather than assumed.
  void bindMsgId(final long msgId, final Registration registration) {
    registration.msgId = msgId;
    byMsgId.put(msgId, registration);
  }

  Registration byMsgId(final long msgId) {
    return byMsgId.get(msgId);
  }

  long msgIdOverflow() {
    return msgIdOverflow.get();
  }

  /// The message ids of every registration that is live, has been sent at least once, and has not
  /// been unsubscribed — the exact set W1-A expects to see replayed on the next adopted connection.
  List<Long> liveMsgIds() {
    final var ids = new ArrayList<Long>(registrations.size());
    for (final var registration : registrations.values()) {
      final long msgId = registration.msgId;
      if (msgId >= 0 && !registration.unsubscribed) {
        ids.add(msgId);
      }
    }
    return ids;
  }

  /// Registrations whose confirmation has not been observed on the current epoch: the gauge's
  /// `pending_confirm` column and the residual quiescence check read the same number.
  long pendingConfirmations() {
    final long epoch = currentEpoch;
    long pending = 0;
    for (final var registration : registrations.values()) {
      final boolean confirmed = liveConfirmations
          ? registration.subscription != null && registration.subscription.subId() != null
          : registration.confirmedEpoch == epoch;
      if (!registration.unsubscribed && !confirmed) {
        ++pending;
      }
    }
    return pending;
  }

  // -------------------------------------------------------------------------------- epochs

  /// Opens a new epoch. The epoch number is the engine's own count of adopted connections, which is
  /// what [software.sava.rpc.soak.oracle.SequenceOracle] resets on; the attempt ordinal is the
  /// harness's connection identity on the wire, which is what the peer log joins on.
  synchronized long openEpoch(final long attemptOrdinal) {
    final long epoch = epochs.incrementAndGet();
    this.currentEpoch = epoch;
    this.currentAttemptOrdinal = attemptOrdinal;
    this.lastMessageTimestamp = 0L;
    this.epochOpen = true;
    return epoch;
  }

  /// Closes the epoch that was adopted for `attemptOrdinal`, returning its number, or -1 when that
  /// attempt is not the one currently open.
  ///
  /// The ordinal is required rather than assumed to be the current one because
  /// `SolanaRpcWebsocket#connect()`'s javadoc is explicit that "lifecycle callbacks receive this
  /// reusable wrapper and no attempt token, so this API cannot attribute a callback to a particular
  /// future". A retirement observed through `onClose`/`onError` therefore carries no identity, and
  /// a successor adopted before that callback ran would otherwise have *its* epoch closed by its
  /// predecessor's retirement — measured on `F3`, where the manager reconnected in 0 ms and the
  /// run ended with more epochs opened than retired. The caller that does know which attempt
  /// retired is the attempt tracker, so the close is keyed on what it says.
  synchronized long closeEpoch(final long attemptOrdinal) {
    if (!epochOpen || currentAttemptOrdinal != attemptOrdinal) {
      return -1L;
    }
    epochOpen = false;
    return currentEpoch;
  }

  boolean epochOpen() {
    return epochOpen;
  }

  long currentEpoch() {
    return currentEpoch;
  }

  long currentAttemptOrdinal() {
    return currentAttemptOrdinal;
  }

  // ------------------------------------------------------------------------------- counters

  long unsubscribeRequested() {
    return unsubRequested.incrementAndGet();
  }

  long unsubscribeAcked() {
    return unsubAcked.incrementAndGet();
  }

  long unsubscribeAckedCount() {
    return unsubAcked.get();
  }

  /// The last value of `lastMessageReceivedTimestamp()` this engine reported, for the W1-G
  /// monotonicity check. Kept per epoch: the contract resets it when a connection opens.
  long lastMessageTimestamp() {
    return lastMessageTimestamp;
  }

  void lastMessageTimestamp(final long value) {
    this.lastMessageTimestamp = value;
  }

  boolean closedByHarness() {
    return closedByHarness;
  }

  void closedByHarness(final boolean value) {
    this.closedByHarness = value;
  }
}
