package software.sava.core.accounts.sysvar;

import software.sava.core.accounts.PublicKey;
import software.sava.core.borsh.Borsh;
import software.sava.core.encoding.ByteUtil;

import java.util.function.BiFunction;

record EpochSchedule(PublicKey address,
                     long slotsPerEpoch,
                     long leaderScheduleSlotOffset,
                     boolean warmup,
                     long firstNormalEpoch,
                     long firstNormalSlot) implements Borsh {

  public static final int BYTES = Long.BYTES + Long.BYTES + 1 + Long.BYTES + Long.BYTES;

  public static final BiFunction<PublicKey, byte[], EpochSchedule> FACTORY = EpochSchedule::read;

  public static EpochSchedule read(final byte[] data) {
    return read(data, 0);
  }

  public static EpochSchedule read(final byte[] data, final int offset) {
    return read(null, data, offset);
  }

  public static EpochSchedule read(final PublicKey address, final byte[] data) {
    return read(address, data, 0);
  }

  public static EpochSchedule read(final PublicKey address, final byte[] data, int offset) {
    final long slotsPerEpoch = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final long leaderScheduleSlotOffset = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final boolean warmup = data[offset] == 1;
    ++offset;
    final long firstNormalEpoch = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final long firstNormalSlot = ByteUtil.getInt64LE(data, offset);
    return new EpochSchedule(
        address,
        slotsPerEpoch,
        leaderScheduleSlotOffset,
        warmup,
        firstNormalEpoch,
        firstNormalSlot
    );
  }

  @Override
  public int write(final byte[] data, final int offset) {
    int i = offset;
    ByteUtil.putInt64LE(data, i, slotsPerEpoch);
    i += Long.BYTES;
    ByteUtil.putInt64LE(data, i, leaderScheduleSlotOffset);
    i += Long.BYTES;
    data[i] = (byte) (warmup ? 1 : 0);
    ++i;
    ByteUtil.putInt64LE(data, i, firstNormalEpoch);
    i += Long.BYTES;
    ByteUtil.putInt64LE(data, i, firstNormalSlot);
    i += Long.BYTES;
    return i - offset;
  }

  @Override
  public int l() {
    return BYTES;
  }
}
