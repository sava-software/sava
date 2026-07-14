package software.sava.core.accounts.sysvar;

import software.sava.core.encoding.ByteUtil;
import software.sava.core.serial.Serializable;

record SlotHash(long slot, byte[] hash) implements Serializable {

  public static final int HASH_LENGTH = 32;
  public static final int BYTES = Long.BYTES + HASH_LENGTH;

  public static SlotHash read(final byte[] data, final int offset) {
    final long slot = ByteUtil.getInt64LE(data, offset);
    final byte[] hash = new byte[HASH_LENGTH];
    System.arraycopy(data, offset + Long.BYTES, hash, 0, HASH_LENGTH);
    return new SlotHash(slot, hash);
  }

  @Override
  public int write(final byte[] data, final int offset) {
    ByteUtil.putInt64LE(data, offset, slot);
    System.arraycopy(hash, 0, data, offset + Long.BYTES, HASH_LENGTH);
    return BYTES;
  }

  @Override
  public int l() {
    return BYTES;
  }
}
