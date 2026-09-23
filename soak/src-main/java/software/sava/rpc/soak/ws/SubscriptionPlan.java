package software.sava.rpc.soak.ws;

import software.sava.core.encoding.Base58;
import software.sava.rpc.soak.Seeds;

import java.util.SplittableRandom;

/// The deterministic churn script one engine's driver thread follows.
///
/// Every step is a pure function of `(seed, engineIndex, ordinal)` rather than a draw from a
/// long-lived generator. That matters for replay: a generator's nth value depends on how many
/// values were taken before it, so a run that skipped an operation because the registration cap was
/// full would silently shift every later step. Here the operation at ordinal *k* is the same on a
/// busy machine as on a quiet one, and a finding's `replay.json` can name it by number.
///
/// The plan proposes; the driver disposes. `SUBSCRIBE` at the registration cap is refused by the
/// driver, not by the plan, so the refusal is visible as a skipped operation rather than as a
/// different script.
final class SubscriptionPlan {

  /// What one operation does. `SUBSCRIBE_DUPLICATE_PARAMS` deliberately re-subscribes a key that is
  /// already registered: the engine is expected to deduplicate it client side and return false, so
  /// the peer should never see a second byte-identical request on that connection (W1-B).
  enum Op {
    SUBSCRIBE,
    UNSUBSCRIBE,
    RESUBSCRIBE_SAME_KEY,
    SUBSCRIBE_DUPLICATE_PARAMS,
    NOOP
  }

  /// The eight registration shapes a run exercises. `SLOT` and `ROOT` are singletons inside one
  /// engine — the interface offers exactly one of each — so the plan draws them rarely and the
  /// driver treats a second draw as a duplicate.
  enum Channel {
    ACCOUNT,
    LOGS,
    PROGRAM,
    KEYED_PROGRAM,
    SIGNATURE,
    GENERIC,
    SLOT,
    ROOT;

    /// True for the two channels that take no key: their registration identity is the channel
    /// itself, and a plan step naming a key for them is ignored.
    boolean singleton() {
      return this == SLOT || this == ROOT;
    }
  }

  /// One scripted operation. `keyIndex` indexes the run's 256-key table for every channel except
  /// the two singletons, where it is retained only so a log line can say which draw produced it.
  record Step(Op op, Channel channel, int keyIndex) {
  }

  /// The generic channel's methods. Helius' `transactionSubscribe` is the shape the peer answers
  /// with `transactionNotification`, and the engine binds the un-subscription method to the
  /// notification method on first registration, which is itself part of what W1-A replays.
  static final String GENERIC_SUBSCRIBE = "transactionSubscribe";
  static final String GENERIC_UNSUBSCRIBE = "transactionUnsubscribe";
  static final String GENERIC_NOTIFICATION = "transactionNotification";

  private static final long OP_SALT = 0x5851F42D4C957F2DL;
  private static final long CHANNEL_SALT = 0x14057B7EF767814FL;
  private static final long KEY_SALT = 0x2545F4914F6CDD1DL;
  private static final long SIGNATURE_SALT = 0x9E3779B97F4A7C55L;

  /// Cumulative percentage thresholds for the operation mix. Subscribe dominates because a run
  /// that unsubscribes as often as it subscribes never reaches its registration cap, and the cap is
  /// where replay and retention pressure live.
  private static final int[] OP_WEIGHTS = {45, 65, 80, 90, 100};
  private static final Op[] OPS = {
      Op.SUBSCRIBE, Op.UNSUBSCRIBE, Op.RESUBSCRIBE_SAME_KEY, Op.SUBSCRIBE_DUPLICATE_PARAMS, Op.NOOP
  };

  /// Cumulative percentage thresholds for the channel mix. The two singletons share the last five
  /// points: one registration each per engine is all the interface allows, so weighting them like
  /// the keyed channels would spend the run drawing duplicates.
  private static final int[] CHANNEL_WEIGHTS = {30, 48, 62, 74, 86, 95, 98, 100};
  private static final Channel[] CHANNELS = {
      Channel.ACCOUNT, Channel.LOGS, Channel.PROGRAM, Channel.KEYED_PROGRAM,
      Channel.SIGNATURE, Channel.GENERIC, Channel.SLOT, Channel.ROOT
  };

  private final long seed;
  private final int engineIndex;

  SubscriptionPlan(final long seed, final int engineIndex) {
    this.seed = seed;
    this.engineIndex = engineIndex;
  }

  /// The operation at `ordinal`, identical for every process that knows the seed.
  Step step(final long ordinal) {
    final long salt = OP_SALT ^ ((long) engineIndex << 32);
    final int opRoll = roll(salt, ordinal);
    final int channelRoll = roll(CHANNEL_SALT ^ ((long) engineIndex << 32), ordinal);
    final int keyRoll = (int) Long.remainderUnsigned(
        Seeds.mix(seed, KEY_SALT ^ ((long) engineIndex << 32), ordinal), Seeds.KEY_TABLE_SIZE);
    return new Step(pick(OPS, OP_WEIGHTS, opRoll), pick(CHANNELS, CHANNEL_WEIGHTS, channelRoll), keyRoll);
  }

  /// A synthetic 64-byte transaction signature for the signature channel, base58 encoded.
  ///
  /// It is generated rather than taken from the key table because `signatureSubscribe` validates
  /// the string for frame safety and correlates by it: a real base58 signature of the right length
  /// is what the peer and the engine both expect, and the peer answers it with
  /// `receivedSignature` followed by one terminal `{"err":null}`.
  String signature(final int keyIndex) {
    final var random = new SplittableRandom(Seeds.mix(seed, SIGNATURE_SALT, keyIndex));
    final byte[] bytes = new byte[64];
    random.nextBytes(bytes);
    return Base58.encode(bytes);
  }

  /// The raw `params` text for a generic `transactionSubscribe` registration.
  ///
  /// It is placed verbatim inside the request's params array by the engine, so it must be valid
  /// JSON and must be stable for a given key: the agave dialect deduplicates on the byte-identical
  /// request text, which is exactly the behaviour W1-B and the D6 control probe.
  static String genericParams(final String key) {
    return "{\"vote\":false,\"failed\":false,\"accountInclude\":[\"" + key + "\"]},{\"commitment\":\"confirmed\"}";
  }

  private int roll(final long salt, final long ordinal) {
    return (int) Long.remainderUnsigned(Seeds.mix(seed, salt, ordinal), 100L);
  }

  private static <T> T pick(final T[] values, final int[] cumulative, final int roll) {
    for (int i = 0; i < cumulative.length; ++i) {
      if (roll < cumulative[i]) {
        return values[i];
      }
    }
    return values[values.length - 1];
  }
}
