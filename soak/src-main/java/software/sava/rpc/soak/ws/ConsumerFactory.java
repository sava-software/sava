package software.sava.rpc.soak.ws;

import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.response.ProcessedSlot;
import software.sava.rpc.json.http.response.TxLogs;
import software.sava.rpc.json.http.response.TxResult;
import software.sava.rpc.soak.Counters;
import software.sava.rpc.soak.Seeds;
import software.sava.rpc.soak.events.SoakEvents;
import systems.comodal.jsoniter.JsonIterator;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// The notification consumers, and the only place a notification's sequence is extracted.
///
/// Consumer behaviour is the independent variable of W2: the peer emits the same stream whatever
/// the client does with it, so the differences between a consumer that returns immediately, one
/// that parks, one that throws and one that does real per-notification work are attributable to the
/// engine's dispatch rather than to the load. The kind is assigned per key from the seed, so a
/// replay puts the same behaviour on the same key.
///
/// The sequence lives in a different field on every channel — the peer writes it where a real node
/// would carry something derived from the slot — so extraction is per channel and is the harness's
/// half of the `DESIGN.md` §7 contract. Every consumer records the sequence **before** it does
/// anything that might not return, which is what lets a throwing consumer's containment be asserted
/// without losing the evidence that the notification arrived.
final class ConsumerFactory {

  /// The four behaviours, in the proportions `DESIGN.md` §13 fixes: 70 / 15 / 10 / 5.
  enum Kind {
    /// Record the sequence and return. The hot path a steady run spends its time on.
    FAST,
    /// Park for `SOAK_SLOW_CONSUMER_MICROS`. Parked rather than spun so `jdk.ThreadPark` attributes
    /// the wait to the engine's listener thread, which is the W2-D measurement.
    SLOW,
    /// Throw every `SOAK_THROW_EVERY`-th notification, after recording the sequence.
    THROWING,
    /// Do the per-notification work the notification allows. On a payload-bearing channel that is
    /// the payload checksum; on a channel that carries no payload there is nothing to compute, so
    /// the W2-D histogram shows HEAVY there at FAST's cost. The kind no longer decides whether W2-A
    /// is evaluated — see [#payloadBearing] — because a per-key behaviour must not be able to
    /// decide which notifications get checked.
    HEAVY
  }

  /// The peer's slot base. Restated here rather than imported because the peer's `Stamp` is
  /// package-private to `peer`; `DESIGN.md` §7 is the shared definition and a change to it is a
  /// change on both sides.
  static final long BASE_SLOT = 10_000_000L;

  private static final long FNV_OFFSET_BASIS = 0xcbf2_9ce4_8422_2325L;
  private static final long FNV_PRIME = 0x0000_0100_0000_01b3L;

  private static final long KIND_SALT = 0x6A09E667F3BCC909L;
  private static final int[] KIND_WEIGHTS = {70, 85, 95, 100};
  private static final Kind[] KINDS = {Kind.FAST, Kind.SLOW, Kind.THROWING, Kind.HEAVY};

  /// What a consumer reports back. Implemented by the workload, which owns the oracles: the factory
  /// knows how to read a notification and nothing about whether the reading was correct.
  interface Sink {

    /// One delivered notification, already decoded. `payload` is null for the channels that carry
    /// none. Called before the consumer's behaviour runs, so a throwing consumer still reports.
    ///
    /// `verifyPayload` is [#payloadBearing] of the registration's channel: true means the peer
    /// promised bytes here, so W2-A is evaluated and an absent or short payload is a failure rather
    /// than a skip. False means the channel carries no payload and W2-A has nothing to say — it is
    /// not an evaluation either way.
    ///
    /// `notifiedKey` is [#notifiedKey]: the pubkey the *wire* carried for this notification, which
    /// only the two program channels have. Null means the channel carries none and W1-J is not an
    /// evaluation either way; empty means the channel promised one and the notification arrived
    /// without it, which is the same severity as a wrong one and is the workload's to fail.
    void observe(EngineHandle engine,
                 EngineHandle.Registration registration,
                 Kind kind,
                 long sequence,
                 int payloadChars,
                 byte[] payload,
                 boolean verifyPayload,
                 String notifiedKey);

    /// A deliberate consumer fault, reported before it is thrown so the containment check can be
    /// scheduled from the delivery thread.
    void deliberateThrow(EngineHandle engine, EngineHandle.Registration registration);

    /// A signature notification whose value is not `receivedSignature`: the one-shot channel's
    /// terminal result. The engine releases the subscription on exactly this rule (it is owed no
    /// replay from then on), so the harness registry retires it from the same delivery rather than
    /// waiting for an unsubscribe the engine never sends for a completed signature.
    void signatureTerminal(EngineHandle engine, EngineHandle.Registration registration);
  }

  private final long seed;
  private final Counters counters;
  private final Sink sink;
  private final long slowNanos;
  private final int throwEvery;
  /// The stall-callback control (`HarnessControls.STALL_CALLBACK`): once armed, the first
  /// delivery on any consumer parks its thread until [#releaseStall] — the subject's own delivery
  /// thread, which is what the stuck-thread detector must see and what a consumer that never
  /// returns does to the engine that called it.
  private final boolean stallOne;
  private final AtomicBoolean stallArmed = new AtomicBoolean();
  private final AtomicBoolean stallTaken = new AtomicBoolean();
  private volatile boolean stallReleased;
  /// The engine whose delivery took the stall, so its retirements can be charged to the harness.
  private volatile String stalledEngine;

  ConsumerFactory(final long seed, final Counters counters, final Sink sink,
                  final int slowConsumerMicros, final int throwEvery, final boolean stallOne) {
    this.seed = seed;
    this.counters = counters;
    this.sink = sink;
    this.slowNanos = Math.max(0L, slowConsumerMicros) * 1_000L;
    this.throwEvery = Math.max(1, throwEvery);
    this.stallOne = stallOne;
  }

  boolean stallEnabled() {
    return stallOne;
  }

  /// The engine whose consumer is parked, or `null` before the stall was taken. A connection
  /// whose listener never returns is retired by the engine (the pong it owes cannot be read),
  /// and that retirement is the harness's doing, not an unexplained one.
  String stalledEngine() {
    return stalledEngine;
  }

  /// Arms the one-off stall; the next delivery on any consumer takes it.
  void armStall() {
    if (stallOne) {
      stallArmed.set(true);
    }
  }

  /// Lets a parked consumer return, at close. The thread is a daemon and the JVM exits through
  /// `System.exit` regardless; releasing it is so the executor's shutdown is not the thing that
  /// waits on a control's residue.
  void releaseStall() {
    stallReleased = true;
  }

  /// The behaviour assigned to a key, stable for the whole run and for every replay at this seed.
  Kind kindFor(final int keyIndex) {
    final int roll = (int) Long.remainderUnsigned(Seeds.mix(seed, KIND_SALT, keyIndex), 100L);
    for (int i = 0; i < KIND_WEIGHTS.length; ++i) {
      if (roll < KIND_WEIGHTS[i]) {
        return KINDS[i];
      }
    }
    return Kind.FAST;
  }

  // -------------------------------------------------------------------------- per channel

  Consumer<AccountInfo<byte[]>> account(final EngineHandle engine,
                                        final EngineHandle.Registration registration,
                                        final Kind kind) {
    return accountInfo -> deliver(engine, registration, kind,
        accountInfo.lamports(), accountInfo.space(), accountInfo.data(),
        notifiedKey(registration.channel, accountInfo));
  }

  Consumer<TxLogs> logs(final EngineHandle engine,
                        final EngineHandle.Registration registration,
                        final Kind kind) {
    return txLogs -> deliver(engine, registration, kind,
        slotSequence(txLogs.context() == null ? 0L : txLogs.context().slot()), 0, null, null);
  }

  Consumer<ProcessedSlot> slot(final EngineHandle engine,
                               final EngineHandle.Registration registration,
                               final Kind kind) {
    return processedSlot -> deliver(engine, registration, kind,
        slotSequence(processedSlot.slot()), 0, null, null);
  }

  Consumer<Long> root(final EngineHandle engine,
                      final EngineHandle.Registration registration,
                      final Kind kind) {
    return root -> deliver(engine, registration, kind,
        slotSequence(root == null ? 0L : root), 0, null, null);
  }

  Consumer<TxResult> signature(final EngineHandle engine,
                               final EngineHandle.Registration registration,
                               final Kind kind) {
    return txResult -> {
      deliver(engine, registration, kind,
          slotSequence(txResult.context() == null ? 0L : txResult.context().slot()), 0, null, null);
      if (!"receivedSignature".equals(txResult.value())) {
        sink.signatureTerminal(engine, registration);
      }
    };
  }

  Consumer<Long> generic(final EngineHandle engine,
                         final EngineHandle.Registration registration,
                         final Kind kind) {
    return slot -> deliver(engine, registration, kind,
        slotSequence(slot == null ? 0L : slot), 0, null, null);
  }

  /// The generic channel's parser, positioned at the notification's `result` value. The peer's
  /// `transactionNotification` result is `{"slot":…,"signature":"…"}`, so the slot is the sequence
  /// carrier and the signature is ignored.
  static Function<JsonIterator, Long> genericParser() {
    return ji -> {
      final long[] slot = {0L};
      ji.testObject((buf, offset, len, parser) -> {
        if (fieldEquals("slot", buf, offset, len)) {
          slot[0] = parser.readLong();
        } else {
          parser.skip();
        }
        return true;
      });
      return slot[0];
    };
  }

  // ------------------------------------------------------------------------------ delivery

  /// The channels whose notification carries a `Stamp.payload`: the account shape and the two
  /// program shapes, which are exactly the ones wired to [#account]. `DESIGN.md` §7 gives the other
  /// five no payload at all, so there is nothing for W2-A to check on them and they are skipped
  /// rather than scored as passes.
  ///
  /// The gate is the channel and not the number of bytes that arrived, because a length-keyed gate
  /// lets a defect exempt its own notification: an 8 MiB LARGE message truncated to a few hundred
  /// bytes falls below any size threshold and is then never checked. The channel comes from what
  /// the harness subscribed to, which no reassembly defect can move. Every consumer kind verifies
  /// on these channels — fragment reassembly is exactly what W2-A is about, and a FAST key drawing
  /// the LARGE message would otherwise waste the evidence.
  static boolean payloadBearing(final SubscriptionPlan.Channel channel) {
    return switch (channel) {
      case ACCOUNT, PROGRAM, KEYED_PROGRAM -> true;
      case LOGS, SIGNATURE, GENERIC, SLOT, ROOT -> false;
    };
  }

  /// The pubkey this notification carried on the wire, or null when its channel carries none.
  ///
  /// Only `programNotification` has a `value.pubkey`, and it is the one field on any channel that
  /// says *which* account inside the subscription the update is for; `DESIGN.md` §7 fixes it to the
  /// key the subscription was granted for, which is what the `WRONG_KEY_ECHO` control corrupts.
  /// A plain account notification carries no such field — sava fills `AccountInfo.pubKey()` from
  /// the registration itself there — so forwarding it would compare the harness against its own
  /// request and manufacture a pass. The empty string is returned rather than null when a program
  /// notification arrived without a pubkey at all: the channel promised one, so its absence is a
  /// failure to report and not a reason to stop looking.
  private static String notifiedKey(final SubscriptionPlan.Channel channel,
                                    final AccountInfo<byte[]> accountInfo) {
    if (channel != SubscriptionPlan.Channel.PROGRAM
        && channel != SubscriptionPlan.Channel.KEYED_PROGRAM) {
      return null;
    }
    final var pubKey = accountInfo == null ? null : accountInfo.pubKey();
    return pubKey == null ? "" : pubKey.toBase58();
  }

  private void deliver(final EngineHandle engine,
                       final EngineHandle.Registration registration,
                       final Kind kind,
                       final long sequence,
                       final int payloadChars,
                       final byte[] payload,
                       final String notifiedKey) {
    final var event = new SoakEvents.NotificationDelivered();
    event.begin();
    final long startNanos = System.nanoTime();
    boolean threw = false;
    try {
      sink.observe(engine, registration, kind, sequence, payloadChars, payload,
          payloadBearing(registration.channel), notifiedKey);
      if (stallArmed.get() && stallTaken.compareAndSet(false, true)) {
        // Parked, not spun: the detector's signature is the same non-idle frame across consecutive
        // thread dumps with no CPU progress, which is exactly what a consumer blocked on a lock or
        // an external call looks like. Parking with this factory as the blocker keeps a soak frame
        // directly beneath the wait, so the classifier cannot read it as a pool thread idling.
        counters.increment("ws.harness.stalledConsumers");
        stalledEngine = engine.name();
        while (!stallReleased) {
          LockSupport.park(this);
        }
      } else if (kind == Kind.SLOW && slowNanos > 0L) {
        LockSupport.parkNanos(slowNanos);
      } else if (kind == Kind.THROWING && sequence > 0 && sequence % throwEvery == 0) {
        threw = true;
        sink.deliberateThrow(engine, registration);
        throw new IllegalStateException("soak: deliberate consumer fault");
      }
    } catch (final RuntimeException e) {
      if (!threw) {
        // A consumer that throws without being asked to is a harness defect or an unreadable
        // notification, and either way it is not the containment evidence W2-C wants.
        counters.increment(Counters.WS_CONSUMER_THREW_UNEXPECTED);
      }
      throw e;
    } finally {
      event.end();
      counters.record(Counters.wsDelivery(kind.name()), System.nanoTime() - startNanos);
      if (event.shouldCommit()) {
        event.engine = engine.name();
        event.channel = registration.channel.name();
        event.key = registration.key;
        event.epoch = engine.currentEpoch();
        event.sequence = sequence;
        event.payloadChars = payloadChars;
        event.consumerKind = kind.name();
        event.threw = threw;
        event.slowByDesign = kind == Kind.SLOW;
        event.commit();
      }
    }
  }

  private static long slotSequence(final long slot) {
    return slot - BASE_SLOT;
  }

  // ------------------------------------------------------------------------------ checksum

  /// The W2-A oracle: the peer writes `8 bytes big-endian seq | 8 bytes fnv64(filler) | filler`, and
  /// the checksum covers the filler only, so a reassembly that dropped or reordered a continuation
  /// frame fails even when the length came out right.
  ///
  /// An absent or sub-header payload **fails**, the way `RpcOracle.stampIntact` fails a short body:
  /// this is only ever asked about a channel the peer always writes a payload on, so "no bytes to
  /// check" is the most severe reassembly outcome there is rather than an excuse from checking.
  /// Reading it as intact was not a deferral either — nothing else in the harness looks at the
  /// payload's length, because W1-E takes its sequence from `value.lamports`. Empty bytes are
  /// reachable without any fragmentation defect: sava's own `JsonUtil.parseEncodedData` answers an
  /// unrecognised encoding token or a non-array `data` field with `new byte[0]`.
  ///
  /// @return true when the payload is intact, false when it is absent, shorter than the 16-byte
  ///         header, or fails its own checksum.
  static boolean payloadIntact(final byte[] payload) {
    return payload != null
        && payload.length >= 16
        && readLong(payload, 8) == fnv64(payload, 16, payload.length);
  }

  /// The two-sided form: the checksum above, plus the two cross-checks that tie the delivered bytes
  /// to the notification they arrived in. The checksum on its own is purely self-consistent, so a
  /// body that was replaced wholesale by another sequence's — or one whose base64 decoded to fewer
  /// bytes than the notification said it held — satisfies it.
  ///
  /// `sequence` is the channel's own sequence field, which the peer writes into the payload header
  /// as well; `payloadChars` is `value.space`, which the peer writes as `payload.length`
  /// (`DESIGN.md` §7). Both are independent of the filler the checksum covers.
  static boolean payloadIntact(final byte[] payload, final long sequence, final int payloadChars) {
    return payloadIntact(payload)
        && payloadSequence(payload) == sequence
        && payloadChars == payload.length;
  }

  /// The sequence the payload itself claims: an input to the two-sided check above, and the example
  /// in the failure message when a check fails.
  static long payloadSequence(final byte[] payload) {
    return payload == null || payload.length < 8 ? -1L : readLong(payload, 0);
  }

  private static long readLong(final byte[] bytes, final int offset) {
    long value = 0L;
    for (int i = offset; i < offset + 8; ++i) {
      value = (value << 8) | (bytes[i] & 0xFFL);
    }
    return value;
  }

  private static long fnv64(final byte[] bytes, final int from, final int to) {
    long hash = FNV_OFFSET_BASIS;
    for (int i = from; i < to; ++i) {
      hash ^= (bytes[i] & 0xFFL);
      hash *= FNV_PRIME;
    }
    return hash;
  }
}
