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

  /// @throws IllegalArgumentException if the entry count claims more entries than the
  ///                                  bytes after it can hold — a corrupt or hostile
  ///                                  count must fail here, not size an allocation
  public static SlotHashes read(final PublicKey address, final byte[] data, int offset) {
    final long numEntries = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    // validating the count against the bytes actually present bounds the allocation
    // below against a corrupt count. Compared unsigned because the count is a u64: a
    // signed comparison finds one with the top bit set smaller than the bound rather
    // than larger, passing it through the int cast to the array constructor
    if (Long.compareUnsigned(numEntries, (data.length - offset) / SlotHash.BYTES) > 0) {
      throw new IllegalArgumentException("Invalid slot hash count: " + Long.toUnsignedString(numEntries));
    }
    final var slotHashes = new SlotHash[(int) numEntries];
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
