package software.sava.core.accounts.meta;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AccountIndexLookupTableEntry;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.encoding.CompactU16Encoding;

import java.util.Arrays;

final class TableAccountMeta implements LookupTableAccountMeta {

  final AddressLookupTable lookupTable;
  final AccountMeta[] accounts;
  int len;
  int writes;
  int reads;

  TableAccountMeta(final AddressLookupTable lookupTable,
                   final AccountMeta[] accounts) {
    this.lookupTable = lookupTable;
    this.accounts = accounts;
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
    for (int a = 0; a < len; ++a, ++out) {
      accountIndexLookupTable[out] = new AccountIndexLookupTableEntry(accounts[a].publicKey().toByteArray(), out);
    }
    return out;
  }

  @Override
  public void addAccount(final AccountMeta account) {
    accounts[len] = account;
    ++len;
    if (account.write()) {
      ++writes;
    } else {
      ++reads;
    }
  }

  @Override
  public void reset() {
    Arrays.fill(accounts, null);
    len = 0;
    writes = 0;
    reads = 0;
  }

  @Override
  public int serializationLength() {
    return PublicKey.PUBLIC_KEY_LENGTH + 2 + len;
  }

  @Override
  public int serialize(final byte[] out, int i) {
    i = lookupTable.address().write(out, i);
    i += CompactU16Encoding.encodeLength(out, i, writes);
    int a = 0;
    for (; a < writes; ++a, ++i) {
      out[i] = lookupTable.indexOfOrThrow(accounts[a].publicKey());
    }
    i += CompactU16Encoding.encodeLength(out, i, reads);
    for (; a < len; ++a, ++i) {
      out[i] = lookupTable.indexOfOrThrow(accounts[a].publicKey());
    }
    return i;
  }
}
