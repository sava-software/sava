package software.sava.core.accounts.sysvar;

import software.sava.core.borsh.Borsh;
import software.sava.core.encoding.ByteUtil;

public record Clock(long slot,
                    long epochStartTimestamp,
                    long epoch,
                    long leaderScheduleEpoch,
                    long unixTimestamp) implements Borsh {

  public static final int BYTES = 40;

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

  public static Clock read(final byte[] data, int offset) {
    final long slot = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final long epochStartTimestamp = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final long epoch = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final long leaderScheduleEpoch = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final long unixTimestamp = ByteUtil.getInt64LE(data, offset);
    return new Clock(slot, epochStartTimestamp, epoch, leaderScheduleEpoch, unixTimestamp);
  }

  @Override
  public int l() {
    return BYTES;
  }
}
