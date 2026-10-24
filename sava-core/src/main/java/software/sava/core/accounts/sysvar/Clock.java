package software.sava.core.accounts.sysvar;

import software.sava.core.accounts.PublicKey;
import software.sava.core.borsh.Borsh;
import software.sava.core.encoding.ByteUtil;

import java.util.function.BiFunction;

/// The Clock sysvar, 40 bytes, a layout Alpenglow left unchanged.
///
/// @param epochStartTimestamp The `unixTimestamp` of the first block of the current epoch.
/// @param unixTimestamp       Wall-clock seconds for this slot as the cluster agreed them, the
///                            leader's clock rather than a trusted time source. Under Alpenglow,
///                            which every public cluster has run since October 2026, the leader
///                            writes a nanosecond timestamp into its block footer, which replay
///                            rejects unless it is strictly after the parent block's and at most
///                            twice the slot time elapsed since the parent past it
///                            (`enforce_nanosecond_clock_bounds` in
///                            `agave:runtime/src/block_component_processor.rs`), and agave stores
///                            it here truncated to whole seconds (`update_clock_from_footer` in
///                            `agave:runtime/src/bank.rs`), so the value never decreases from a
///                            block to its child but consecutive blocks commonly share it. Under
///                            TowerBFT it was the stake-weighted median of the timestamps
///                            validators attached to their votes.
public record Clock(PublicKey address,
                    long slot,
                    long epochStartTimestamp,
                    long epoch,
                    long leaderScheduleEpoch,
                    long unixTimestamp) implements Borsh {

  public static final long MAX_SLOT = Long.MIN_VALUE | Long.MAX_VALUE;

  public static final int BYTES = 40;

  public static final BiFunction<PublicKey, byte[], Clock> FACTORY = Clock::read;

  public static Clock read(final byte[] data) {
    return read(data, 0);
  }

  public static Clock read(final byte[] data, final int offset) {
    return read(null, data, offset);
  }

  public static Clock read(final PublicKey address, final byte[] data) {
    return read(address, data, 0);
  }

  public static Clock read(final PublicKey address, final byte[] data, int offset) {
    final long slot = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final long epochStartTimestamp = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final long epoch = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final long leaderScheduleEpoch = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final long unixTimestamp = ByteUtil.getInt64LE(data, offset);
    return new Clock(address, slot, epochStartTimestamp, epoch, leaderScheduleEpoch, unixTimestamp);
  }

  @Override
  public int write(final byte[] data, final int offset) {
    int i = offset;
    ByteUtil.putInt64LE(data, i, slot);
    i += Long.BYTES;
    ByteUtil.putInt64LE(data, i, epochStartTimestamp);
    i += Long.BYTES;
    ByteUtil.putInt64LE(data, i, epoch);
    i += Long.BYTES;
    ByteUtil.putInt64LE(data, i, leaderScheduleEpoch);
    i += Long.BYTES;
    ByteUtil.putInt64LE(data, i, unixTimestamp);
    i += Long.BYTES;
    return i - offset;
  }

  @Override
  public int l() {
    return BYTES;
  }
}
