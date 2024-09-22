package software.sava.core.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.sysvar.Clock;
import software.sava.core.encoding.ByteUtil;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;
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
    if (data == null || data.length == 0) {
      return null;
    }
    final byte[] discriminator = new byte[4];
    System.arraycopy(data, 0, discriminator, 0, 4);
    int offset = 4;
    final long deactivationSlot = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final long lastExtendedSlot = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final int lastExtendedSlotStartIndex = data[offset] & 0xFF;
    ++offset;
    final var authority = data[offset] == 0
        ? null
        : readPubKey(data, offset + 1);
    offset = LOOKUP_TABLE_META_SIZE;
    final int to = data.length;
    final int numAccounts = (to - offset) >> 5;
    final var distinctAccounts = HashMap.<PublicKey, PublicKey>newHashMap(numAccounts);
    final var accounts = new PublicKey[numAccounts];
    final var reverseLookupTable = new AccountIndexLookupTableEntry[numAccounts];
    for (int i = 0; offset < data.length; ++i, offset += PUBLIC_KEY_LENGTH) {
      final var pubKey = readPubKey(data, offset);
      final var previous = distinctAccounts.putIfAbsent(pubKey, pubKey);
      if (previous == null) {
        accounts[i] = pubKey;
        reverseLookupTable[i] = new AccountIndexLookupTableEntry(pubKey.toByteArray(), i);
      } else {
        accounts[i] = previous;
        reverseLookupTable[i] = new AccountIndexLookupTableEntry(previous.toByteArray(), i);
      }
    }
    Arrays.sort(reverseLookupTable);
    return new AddressLookupTableWithReverseLookup(
        address,
        discriminator,
        deactivationSlot,
        lastExtendedSlot,
        lastExtendedSlotStartIndex,
        authority,
        distinctAccounts,
        accounts,
        reverseLookupTable,
        data
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

  int numUniqueAccounts();

  int write(final byte[] out, final int offset);

  byte[] data();

  default int dataLength() {
    return data().length;
  }

  Set<PublicKey> uniqueAccounts();
}
