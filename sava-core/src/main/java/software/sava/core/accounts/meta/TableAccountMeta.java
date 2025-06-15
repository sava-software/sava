package software.sava.core.accounts.meta;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AccountIndexLookupTableEntry;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.encoding.CompactU16Encoding;

import java.util.Map;

final class TableAccountMeta implements LookupTableAccountMeta {

  private final AddressLookupTable lookupTable;
  private final byte[] writeAccounts;
  private final byte[] readAccounts;
  private int numWrites;
  private int numReads;

  TableAccountMeta(final AddressLookupTable lookupTable, final int maxAccounts) {
    this.lookupTable = lookupTable;
    this.writeAccounts = new byte[maxAccounts];
    this.readAccounts = new byte[maxAccounts];
  }

  @Override
  public AddressLookupTable lookupTable() {
    return lookupTable;
  }

  public int indexOf(final PublicKey publicKey) {
    return lookupTable.indexOf(publicKey);
  }

  @Override
  public boolean addAccountIfExists(final AccountMeta account) {
    final int index = indexOf(account.publicKey());
    if (index < 0) {
      return false;
    } else if (account.write()) {
      writeAccounts[numWrites++] = (byte) index;
    } else {
      readAccounts[numReads++] = (byte) index;
    }
    return true;
  }

  private AccountIndexLookupTableEntry createAccountIndexLookupTableEntry(final int index, final int i) {
    return new AccountIndexLookupTableEntry(lookupTable.account(index).toByteArray(), i);
  }

  @Override
  public int indexWrites(final AccountIndexLookupTableEntry[] accountIndexLookupTable, int i) {
    for (int w = 0; w < numWrites; ++w, ++i) {
      accountIndexLookupTable[i] = createAccountIndexLookupTableEntry(writeAccounts[w] & 0xFF, i);
    }
    return i;
  }

  @Override
  public int indexReads(final AccountIndexLookupTableEntry[] accountIndexLookupTable, int i) {
    for (int r = 0; r < numReads; ++r, ++i) {
      accountIndexLookupTable[i] = createAccountIndexLookupTableEntry(readAccounts[r] & 0xFF, i);
    }
    return i;
  }

  @Override
  public int indexWrites(final Map<PublicKey, Integer> accountIndexLookupTable, int i) {
    for (int w = 0; w < numWrites; ++w, ++i) {
      accountIndexLookupTable.put(lookupTable.account(writeAccounts[w] & 0xFF), i);
    }
    return i;
  }

  @Override
  public int indexReads(final Map<PublicKey, Integer> accountIndexLookupTable, int i) {
    for (int r = 0; r < numReads; ++r, ++i) {
      accountIndexLookupTable.put(lookupTable.account(readAccounts[r] & 0xFF), i);
    }
    return i;
  }

  @Override
  public void reset() {
    numWrites = 0;
    numReads = 0;
  }

  @Override
  public int serialize(final byte[] out, int i) {
    i += lookupTable.address().write(out, i);
    i += CompactU16Encoding.encodeLength(out, i, numWrites);
    System.arraycopy(writeAccounts, 0, out, i, numWrites);
    i += numWrites;
    i += CompactU16Encoding.encodeLength(out, i, numReads);
    System.arraycopy(readAccounts, 0, out, i, numReads);
    i += numReads;
    return i;
  }
}
