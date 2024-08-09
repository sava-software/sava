package software.sava.core.accounts.meta;

import software.sava.core.accounts.lookup.AccountIndexLookupTableEntry;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.PublicKey;

public interface LookupTableAccountMeta {

  static LookupTableAccountMeta createMeta(final AddressLookupTable lookupTable, final int maxNumAccounts) {
    return new TableAccountMeta(lookupTable, new AccountMeta[maxNumAccounts]);
  }

  static LookupTableAccountMeta[] createMetas(final AddressLookupTable[] lookupTables, final int maxNumAccounts) {
    final LookupTableAccountMeta[] tableAccountMetas = new LookupTableAccountMeta[lookupTables.length];
    for (int i = 0; i < lookupTables.length; ++i) {
      tableAccountMetas[i] = createMeta(lookupTables[i], maxNumAccounts);
    }
    return tableAccountMetas;
  }

  AddressLookupTable lookupTable();

  int indexOf(final PublicKey publicKey);

  byte indexOfOrThrow(final PublicKey publicKey);

  int addReverseLookupEntries(final AccountIndexLookupTableEntry[] accountIndexLookupTable, int out);

  void addAccount(final AccountMeta account);

  void reset();

  int serializationLength();

  int serialize(final byte[] out, int i);
}
