package software.sava.core.accounts.sysvar;

import software.sava.core.accounts.PublicKey;
import software.sava.core.borsh.Borsh;
import software.sava.core.encoding.ByteUtil;

import java.util.function.BiFunction;

/// Stake activation/deactivation history per epoch, ordered from most recent to oldest
/// epoch.
record StakeHistory(PublicKey address, StakeHistoryEntry[] entries) implements Borsh {

  public static final int MAX_ENTRIES = 512;

  public static final BiFunction<PublicKey, byte[], StakeHistory> FACTORY = StakeHistory::read;

  public static StakeHistory read(final byte[] data) {
    return read(data, 0);
  }

  public static StakeHistory read(final byte[] data, final int offset) {
    return read(null, data, offset);
  }

  public static StakeHistory read(final PublicKey address, final byte[] data) {
    return read(address, data, 0);
  }

  public static StakeHistory read(final PublicKey address, final byte[] data, int offset) {
    final int numEntries = (int) ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final var entries = new StakeHistoryEntry[numEntries];
    for (int i = 0; i < numEntries; ++i) {
      entries[i] = StakeHistoryEntry.read(data, offset);
      offset += StakeHistoryEntry.BYTES;
    }
    return new StakeHistory(address, entries);
  }

  @Override
  public int write(final byte[] data, final int offset) {
    int i = offset;
    ByteUtil.putInt64LE(data, i, entries.length);
    i += Long.BYTES;
    for (final var entry : entries) {
      i += entry.write(data, i);
    }
    return i - offset;
  }

  @Override
  public int l() {
    return Long.BYTES + (entries.length * StakeHistoryEntry.BYTES);
  }
}
