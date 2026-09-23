package software.sava.rpc.soak.ws;

import software.sava.rpc.soak.issue52.HarnessBackoffs;
import software.sava.services.core.remote.call.Backoff;

import java.util.function.Supplier;

/// The four websocket engines a run drives, and what makes each one different.
///
/// They are a fixed set rather than a count because the differences are the experiment. Two are
/// supervised by ravina's `WebSocketManager` under backoffs that sit either side of the issue #52
/// trigger condition (a positive escalating delay versus one whose first delay is zero); one is
/// driven bare through `connect()` so the raw engine's reconnect contract is exercised without a
/// supervisor in the way; and one carries a deliberately tiny `maxMessageLength` so the overflow
/// path (W2-B) can be provoked with a 70,000-char message instead of a 128 MiB one.
///
/// The dialect is derived from the port index rather than stored: the peer assigns `agave` to even
/// indices in `SOAK_WS_PORTS` and `helius` to odd ones, so a second copy of that rule here could
/// only ever drift out of agreement with the peer that actually implements it.
enum EngineProfile {

  /// The reference engine: a manager with the backoff the manager's own javadoc recommends. Its
  /// retirements are the #52 evidence that matters, because a positive escalating delay is the
  /// configuration the issue says would justify revisiting the decision.
  EXPONENTIAL(0, true, 1 << 26, HarnessBackoffs::exponential250to30s),
  /// A manager whose first delay is zero and which then escalates: the state the manager's javadoc
  /// says a run passes through once rather than stays in.
  ZERO_INITIAL_ESCALATING(1, true, 1 << 26, HarnessBackoffs::zeroInitialEscalating),
  /// No manager. The harness owns the reconnect policy, so the raw engine's `connect()` contract —
  /// single-flight attempts, replay on adoption, `close()` terminal — is what is under test.
  BARE(2, false, 1 << 26, HarnessBackoffs::exponential250to30s),
  /// The overflow engine. The other three keep `SolanaRpcWebsocketBuilder`'s own 2^26-char default,
  /// repeated as a literal because an enum constant's arguments cannot name a field of its own
  /// class. 65,536 chars is under the peer's 70,000-char `MESSAGE_OVERFLOW` message
  /// and far under any legitimate notification, so the cap fires on exactly the message the fault
  /// schedule sends and on nothing else.
  OVERFLOW(3, true, 1 << 16, HarnessBackoffs::exponential250to30s);

  private final int portIndex;
  private final boolean managed;
  private final int maxMessageLength;
  private final Supplier<Backoff> backoff;

  EngineProfile(final int portIndex,
                final boolean managed,
                final int maxMessageLength,
                final Supplier<Backoff> backoff) {
    this.portIndex = portIndex;
    this.managed = managed;
    this.maxMessageLength = maxMessageLength;
    this.backoff = backoff;
  }

  /// The counter suffix, the `client.csv` engine name and the `RecoveryLedger` key. Lower case
  /// because it travels into file names and property examples.
  String engineName() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }

  /// Index into `SOAK_WS_PORTS`. The fifth port belongs to the churn workload, which is why this
  /// enum stops at three.
  int portIndex() {
    return portIndex;
  }

  /// True when a ravina `WebSocketManager` owns reconnect policy, which also means the harness
  /// must never call `connect()` on this engine.
  boolean managed() {
    return managed;
  }

  int maxMessageLength() {
    return maxMessageLength;
  }

  /// A fresh `Backoff` per engine: `RecordingBackoff` wraps one instance and a shared delegate
  /// would make two engines' error counts indistinguishable in the claim records.
  Backoff backoff() {
    return backoff.get();
  }

  /// The peer's dialect for this engine's port, derived from the index exactly as the peer derives
  /// it. `agave` dedupes byte-identical params to one subscription id; `helius` grants a fresh id
  /// every time.
  String dialect() {
    return (portIndex & 1) == 0 ? "agave" : "helius";
  }

  /// The engine's slot in the run's [software.sava.rpc.soak.oracle.SequenceOracle], which is sized
  /// `max(8, wsEngines * 2)` so the churn engine's slot can sit above these four.
  int oracleIndex() {
    return ordinal();
  }
}
