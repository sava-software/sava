package software.sava.core.accounts.sysvar;

import software.sava.core.accounts.PublicKey;
import software.sava.core.borsh.Borsh;
import software.sava.core.encoding.ByteUtil;

import java.util.function.BiFunction;

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
