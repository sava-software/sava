package software.sava.core.accounts.meta;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AccountIndexLookupTableEntry;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.encoding.CompactU16Encoding;

import java.util.Arrays;

final class TableAccountMeta implements LookupTableAccountMeta {

  final AddressLookupTable lookupTable;
  final AccountMeta[] writeAccounts;
  final AccountMeta[] readAccounts;
  int writes;
  int reads;

  TableAccountMeta(final AddressLookupTable lookupTable, final int maxAccounts) {
    this.lookupTable = lookupTable;
    this.writeAccounts = new AccountMeta[maxAccounts];
    this.readAccounts = new AccountMeta[maxAccounts];
  }

  @Override
  public AddressLookupTable lookupTable() {
    return lookupTable;
  }

  @Override
  public int indexOf(final PublicKey publicKey) {
    return lookupTable.indexOf(publicKey);
  }

  @Override
  public byte indexOfOrThrow(final PublicKey publicKey) {
    return lookupTable.indexOfOrThrow(publicKey);
  }

  @Override
  public int addReverseLookupEntries(final AccountIndexLookupTableEntry[] accountIndexLookupTable, int out) {
    for (final var writeAccount : writeAccounts) {
      accountIndexLookupTable[out] = new AccountIndexLookupTableEntry(writeAccount.publicKey().toByteArray(), out);
      ++out;
    }
    for (final var readAccount : readAccounts) {
      accountIndexLookupTable[out] = new AccountIndexLookupTableEntry(readAccount.publicKey().toByteArray(), out);
      ++out;
    }
    return out;
  }

  @Override
  public void addAccount(final AccountMeta account) {
    if (account.write()) {
      writeAccounts[writes++] = account;
    } else {
      readAccounts[reads++] = account;
    }
  }

  @Override
  public void reset() {
    Arrays.fill(writeAccounts, null);
    Arrays.fill(readAccounts, null);
    writes = 0;
    reads = 0;
  }

  @Override
  public int serializationLength() {
    return PublicKey.PUBLIC_KEY_LENGTH + 2 + writes + reads;
  }

  @Override
  public int serialize(final byte[] out, int i) {
    i += lookupTable.address().write(out, i);
    i += CompactU16Encoding.encodeLength(out, i, writes);
    for (int w = 0; w < writes; ++w, ++i) {
      out[i] = lookupTable.indexOfOrThrow(writeAccounts[w].publicKey());
    }
    i += CompactU16Encoding.encodeLength(out, i, reads);
    for (int r = 0; r < reads; ++r, ++i) {
      out[i] = lookupTable.indexOfOrThrow(readAccounts[r].publicKey());
    }
    return i;
  }
}
