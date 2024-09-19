package software.sava.core.accounts.lookup;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.sysvar.Clock;
import software.sava.core.encoding.ByteUtil;

import java.util.Arrays;
import java.util.function.BiFunction;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

public record AddressLookupTable(PublicKey address,
                                 byte[] discriminator,
                                 long deactivationSlot,
                                 long lastExtendedSlot,
                                 int lastExtendedSlotStartIndex,
                                 PublicKey authority,
                                 PublicKey[] accounts,
                                 AccountIndexLookupTableEntry[] reverseLookupTable,
                                 byte[] data) {

  public static final int LOOKUP_TABLE_MAX_ADDRESSES = 256;

  public static final int LOOKUP_TABLE_META_SIZE = 56;
  public static final int DISCRIMINATOR_OFFSET = 0;
  public static final int DEACTIVATION_SLOT_OFFSET = DISCRIMINATOR_OFFSET + 4;
  public static final int LAST_EXTENDED_OFFSET = DEACTIVATION_SLOT_OFFSET + Long.BYTES;
  public static final int LAST_EXTENDED_SLOT_START_INDEX_OFFSET = LAST_EXTENDED_OFFSET + Long.BYTES;
  public static final int AUTHORITY_OPTION_OFFSET = LAST_EXTENDED_SLOT_START_INDEX_OFFSET + 1;
  public static final int AUTHORITY_OFFSET = AUTHORITY_OPTION_OFFSET + 1;

  public static int getAccountOffset(int index) {
    return LOOKUP_TABLE_META_SIZE + (PUBLIC_KEY_LENGTH * index);
  }

  public static PublicKey getAccount(final byte[] lookupTable, final int index) {
    return PublicKey.readPubKey(lookupTable, getAccountOffset(index));
  }

  public static final BiFunction<PublicKey, byte[], AddressLookupTable> FACTORY = AddressLookupTable::read;

  public static AddressLookupTable read(final PublicKey address, final byte[] data) {
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
    final PublicKey authority;
    if (data[offset++] == 0) {
      authority = null;
    } else {
      authority = readPubKey(data, offset);
    }
    offset = LOOKUP_TABLE_META_SIZE;
    final int to = data.length;
    final int numAccounts = (to - offset) >> 5;
    final var accounts = new PublicKey[numAccounts];
    final var reverseLookupTable = new AccountIndexLookupTableEntry[numAccounts];
    for (int i = 0; offset < data.length; ++i, offset += PUBLIC_KEY_LENGTH) {
      final var pubKey = readPubKey(data, offset);
      accounts[i] = pubKey;
      reverseLookupTable[i] = new AccountIndexLookupTableEntry(pubKey.toByteArray(), i);
    }
    Arrays.sort(reverseLookupTable);
    return new AddressLookupTable(
        address,
        discriminator,
        deactivationSlot,
        lastExtendedSlot,
        lastExtendedSlotStartIndex,
        authority,
        accounts,
        reverseLookupTable,
        data
    );
  }

  public boolean isActive() {
    return deactivationSlot == Clock.MAX_SLOT;
  }

  public PublicKey account(final int index) {
    return accounts[index];
  }

  public int indexOf(final PublicKey publicKey) {
    return AccountIndexLookupTableEntry.lookupAccountIndex(this.reverseLookupTable, publicKey);
  }

  public boolean containKey(final PublicKey publicKey) {
    return indexOf(publicKey) >= 0;
  }

  public byte indexOfOrThrow(final PublicKey publicKey) {
    return AccountIndexLookupTableEntry.lookupAccountIndexOrThrow(this.reverseLookupTable, publicKey);
  }

  public String toString() {
    return String.format("""
            {
              "address": "%s",
              "deactivationSlot": %s,
              "lastExtendedSlot": %s,
              "lastExtendedSlotStartIndex": %d,
              "authority": "%s",
              "accounts": "%s"
            }""",
        address,
        Long.toUnsignedString(deactivationSlot),
        Long.toUnsignedString(lastExtendedSlot),
        lastExtendedSlotStartIndex,
        authority,
        Arrays.toString(accounts)
    );
  }
}
