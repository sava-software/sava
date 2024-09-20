package software.sava.core.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.sysvar.Clock;
import software.sava.core.encoding.ByteUtil;

import java.util.Arrays;
import java.util.function.BiFunction;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

public interface AddressLookupTable {

  int LOOKUP_TABLE_MAX_ADDRESSES = 256;

  int LOOKUP_TABLE_META_SIZE = 56;
  int DISCRIMINATOR_OFFSET = 0;
  int DEACTIVATION_SLOT_OFFSET = AddressLookupTable.DISCRIMINATOR_OFFSET + 4;
  int LAST_EXTENDED_OFFSET = AddressLookupTable.DEACTIVATION_SLOT_OFFSET + Long.BYTES;
  int LAST_EXTENDED_SLOT_START_INDEX_OFFSET = AddressLookupTable.LAST_EXTENDED_OFFSET + Long.BYTES;
  int AUTHORITY_OPTION_OFFSET = AddressLookupTable.LAST_EXTENDED_SLOT_START_INDEX_OFFSET + 1;
  int AUTHORITY_OFFSET = AddressLookupTable.AUTHORITY_OPTION_OFFSET + 1;

  BiFunction<PublicKey, byte[], AddressLookupTable> FACTORY = AddressLookupTable::read;

  static AddressLookupTable read(final PublicKey address, final byte[] data) {
    return read(address, data, 0);
  }

  static AddressLookupTable read(final PublicKey address, final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    final byte[] discriminator = new byte[4];
    System.arraycopy(data, 0, discriminator, 0, 4);
    int o = offset + 4;
    final long deactivationSlot = ByteUtil.getInt64LE(data, o);
    o += Long.BYTES;
    final long lastExtendedSlot = ByteUtil.getInt64LE(data, o);
    o += Long.BYTES;
    final int lastExtendedSlotStartIndex = data[o] & 0xFF;
    ++o;
    final var authority = data[o] == 0
        ? null
        : readPubKey(data, o + 1);
    o = LOOKUP_TABLE_META_SIZE;
    final int to = data.length;
    final int numAccounts = (to - o) >> 5;
    final var accounts = new PublicKey[numAccounts];
    final var reverseLookupTable = new AccountIndexLookupTableEntry[numAccounts];
    for (int i = 0; o < data.length; ++i, o += PUBLIC_KEY_LENGTH) {
      final var pubKey = readPubKey(data, o);
      accounts[i] = pubKey;
      reverseLookupTable[i] = new AccountIndexLookupTableEntry(pubKey.toByteArray(), i);
    }
    Arrays.sort(reverseLookupTable);
    return new AddressLookupTableWithReverseLookup(
        address,
        discriminator,
        deactivationSlot,
        lastExtendedSlot,
        lastExtendedSlotStartIndex,
        authority,
        accounts,
        reverseLookupTable,
        offset == 0 && o == data.length ? data : Arrays.copyOfRange(data, offset, o)
    );
  }

  static AddressLookupTable readWithoutReverseLookup(final PublicKey address, final byte[] data) {
    if (data == null || data.length == 0) {
      return null;
    }
    return new AddressLookupTableOverlay(address, data);
  }

  AddressLookupTable withReverseLookup();

  default boolean isActive() {
    return deactivationSlot() == Clock.MAX_SLOT;
  }

  PublicKey account(final int index);

  int indexOf(final PublicKey publicKey);

  default boolean containKey(final PublicKey publicKey) {
    return indexOf(publicKey) >= 0;
  }

  byte indexOfOrThrow(final PublicKey publicKey);

  PublicKey address();

  byte[] discriminator();

  long deactivationSlot();

  long lastExtendedSlot();

  int lastExtendedSlotStartIndex();

  PublicKey authority();

  int numAccounts();

  int write(final byte[] out, final int offset);

  byte[] data();

  default int dataLength() {
    return data().length;
  }
}
