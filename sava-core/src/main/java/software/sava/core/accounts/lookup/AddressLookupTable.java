package software.sava.core.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.sysvar.Clock;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.rpc.Filter;

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

  static Filter activeFilter() {
    return AddressLookupTableRoot.ACTIVE_FILTER;
  }

  static Filter authorityFilter(final PublicKey authority) {
    return Filter.createMemCompFilter(AUTHORITY_OFFSET, authority);
  }

  BiFunction<PublicKey, byte[], AddressLookupTable> FACTORY = AddressLookupTable::read;

  static AddressLookupTable read(final PublicKey address, final byte[] data) {
    if (data == null || data.length == 0) {
      return null;
    }
    final byte[] discriminator = new byte[4];
    System.arraycopy(data, 0, discriminator, 0, 4);
    final long deactivationSlot = ByteUtil.getInt64LE(data, DEACTIVATION_SLOT_OFFSET);
    final long lastExtendedSlot = ByteUtil.getInt64LE(data, LAST_EXTENDED_OFFSET);
    final int lastExtendedSlotStartIndex = data[LAST_EXTENDED_SLOT_START_INDEX_OFFSET] & 0xFF;
    final var authority = data[AUTHORITY_OPTION_OFFSET] == 0
        ? null
        : readPubKey(data, AUTHORITY_OFFSET);
    final int numAccounts = (data.length - LOOKUP_TABLE_META_SIZE) >> 5;
    final var accounts = new PublicKey[numAccounts];
    final var distinctAccounts = HashMap.<PublicKey, Integer>newHashMap(numAccounts);

    for (int i = 0, o = LOOKUP_TABLE_META_SIZE; i < numAccounts; ++i, o += PUBLIC_KEY_LENGTH) {
      final var pubKey = readPubKey(data, o);
      distinctAccounts.putIfAbsent(pubKey, i);
      accounts[i] = pubKey;
    }
    return new AddressLookupTableWithReverseLookup(
        address,
        discriminator,
        deactivationSlot,
        lastExtendedSlot,
        lastExtendedSlotStartIndex,
        authority,
        distinctAccounts,
        accounts,
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

  int length();

  Set<PublicKey> uniqueAccounts();
}
