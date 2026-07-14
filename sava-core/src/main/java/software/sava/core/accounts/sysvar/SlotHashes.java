package software.sava.core.accounts.sysvar;

import software.sava.core.accounts.PublicKey;
import software.sava.core.borsh.Borsh;
import software.sava.core.encoding.ByteUtil;

import java.util.function.BiFunction;

/// The most recent hashes of a slot's parent bank hashes, ordered from most recent to
/// oldest slot.
record SlotHashes(PublicKey address, SlotHash[] slotHashes) implements Borsh {

  public static final int MAX_ENTRIES = 512;

  public static final BiFunction<PublicKey, byte[], SlotHashes> FACTORY = SlotHashes::read;

  public static SlotHashes read(final byte[] data) {
    return read(data, 0);
  }

  public static SlotHashes read(final byte[] data, final int offset) {
    return read(null, data, offset);
  }

  public static SlotHashes read(final PublicKey address, final byte[] data) {
    return read(address, data, 0);
  }

  public static SlotHashes read(final PublicKey address, final byte[] data, int offset) {
    final int numEntries = (int) ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final var slotHashes = new SlotHash[numEntries];
    for (int i = 0; i < numEntries; ++i) {
      slotHashes[i] = SlotHash.read(data, offset);
      offset += SlotHash.BYTES;
    }
    return new SlotHashes(address, slotHashes);
  }

  @Override
  public int write(final byte[] data, final int offset) {
    int i = offset;
    ByteUtil.putInt64LE(data, i, slotHashes.length);
    i += Long.BYTES;
    for (final var slotHash : slotHashes) {
      i += slotHash.write(data, i);
    }
    return i - offset;
  }

  @Override
  public int l() {
    return Long.BYTES + (slotHashes.length * SlotHash.BYTES);
  }
}
