package software.sava.core.accounts.sysvar;

import software.sava.core.encoding.ByteUtil;
import software.sava.core.serial.Serializable;

record StakeHistoryEntry(long epoch,
                         long effective,
                         long activating,
                         long deactivating) implements Serializable {

  public static final int BYTES = Long.BYTES + Long.BYTES + Long.BYTES + Long.BYTES;

  public static StakeHistoryEntry read(final byte[] data, int offset) {
    final long epoch = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final long effective = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final long activating = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final long deactivating = ByteUtil.getInt64LE(data, offset);
    return new StakeHistoryEntry(epoch, effective, activating, deactivating);
  }

  @Override
  public int write(final byte[] data, final int offset) {
    int i = offset;
    ByteUtil.putInt64LE(data, i, epoch);
    i += Long.BYTES;
    ByteUtil.putInt64LE(data, i, effective);
    i += Long.BYTES;
    ByteUtil.putInt64LE(data, i, activating);
    i += Long.BYTES;
    ByteUtil.putInt64LE(data, i, deactivating);
    i += Long.BYTES;
    return i - offset;
  }

  @Override
  public int l() {
    return BYTES;
  }
}
