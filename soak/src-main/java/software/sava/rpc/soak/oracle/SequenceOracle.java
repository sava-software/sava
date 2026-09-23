package software.sava.rpc.soak.oracle;

/// Per-(engine, key) sequence tracking for W1-E, on the notification delivery path.
///
/// Allocation-free by construction: two `long[]`s indexed by `engine * keys + key`, a fixed array
/// of stripe monitors, and an enum result. The delivery path runs on the JDK's websocket listener
/// thread, so anything allocated here is allocated once per notification of the whole run and
/// would show up in the very retention measurements this harness exists to take.
///
/// A new epoch — a new connection ordinal for that engine — resets the slot to "monotone, any gap
/// allowed" and reports [Result#FIRST_OF_EPOCH], because NP-1 declines to assert delivery across a
/// disconnect. Within an epoch the peer assigned the sequence under its own write lock immediately
/// before writing the bytes, so a gap cannot be peer-caused and the expectation is exact.
public final class SequenceOracle {

  /// What the observation was relative to the last one on that (engine, key) slot.
  public enum Result {
    /// `seq == last + 1`: exactly what the peer wrote next.
    OK,
    /// `seq > last + 1`: something between them never arrived.
    GAP,
    /// `seq == last`: the same sequence delivered twice.
    DUP,
    /// `seq < last`: an earlier sequence arrived after a later one.
    REORDER,
    /// The first observation of a new epoch; no expectation is carried across the boundary.
    FIRST_OF_EPOCH
  }

  private static final int STRIPES = 64;

  private final int engines;
  private final int keys;
  private final long[] lastSeq;
  private final long[] epochs;
  private final Object[] stripes;

  public SequenceOracle(final int engines, final int keys) {
    if (engines <= 0 || keys <= 0) {
      throw new IllegalArgumentException("the oracle needs a positive (engines, keys) shape");
    }
    this.engines = engines;
    this.keys = keys;
    this.lastSeq = new long[engines * keys];
    this.epochs = new long[engines * keys];
    this.stripes = new Object[STRIPES];
    for (int i = 0; i < STRIPES; ++i) {
      stripes[i] = new Object();
    }
    for (int i = 0; i < epochs.length; ++i) {
      epochs[i] = -1L;
    }
  }

  public int engines() {
    return engines;
  }

  public int keys() {
    return keys;
  }

  /// Observes one delivered sequence. `epoch` is the connection ordinal the notification arrived
  /// on; `key` is an index into the run's key table.
  public Result observe(final int engine, final int key, final long epoch, final long seq) {
    if (engine < 0 || engine >= engines) {
      throw new IllegalArgumentException("engine index " + engine + " outside [0, " + engines + ')');
    }
    if (key < 0 || key >= keys) {
      throw new IllegalArgumentException("key index " + key + " outside [0, " + keys + ')');
    }
    final int slot = engine * keys + key;
    synchronized (stripes[slot & (STRIPES - 1)]) {
      if (epochs[slot] != epoch) {
        epochs[slot] = epoch;
        lastSeq[slot] = seq;
        return Result.FIRST_OF_EPOCH;
      }
      final long last = lastSeq[slot];
      if (seq == last + 1L) {
        lastSeq[slot] = seq;
        return Result.OK;
      }
      if (seq > last) {
        lastSeq[slot] = seq;
        return Result.GAP;
      }
      return seq == last ? Result.DUP : Result.REORDER;
    }
  }

  /// The last sequence seen on a slot, or -1 when the slot is untouched. For the finding example,
  /// never on the hot path.
  public long lastSequence(final int engine, final int key) {
    final int slot = engine * keys + key;
    synchronized (stripes[slot & (STRIPES - 1)]) {
      return epochs[slot] < 0L ? -1L : lastSeq[slot];
    }
  }
}
