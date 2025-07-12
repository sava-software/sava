package software.sava.core.accounts.meta;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AccountIndexLookupTableEntry;
import software.sava.core.accounts.lookup.AddressLookupTable;

import java.util.Collection;
import java.util.Map;

import static software.sava.core.accounts.lookup.AddressLookupTable.LOOKUP_TABLE_MAX_ADDRESSES;

public interface LookupTableAccountMeta {

  static LookupTableAccountMeta createMeta(final AddressLookupTable lookupTable, final int maxNumAccounts) {
    return new TableAccountMeta(lookupTable, maxNumAccounts);
  }

  static LookupTableAccountMeta[] createMetas(final AddressLookupTable[] lookupTables, final int maxNumAccounts) {
    final LookupTableAccountMeta[] tableAccountMetas = new LookupTableAccountMeta[lookupTables.length];
    for (int i = 0; i < lookupTables.length; ++i) {
      tableAccountMetas[i] = createMeta(lookupTables[i], maxNumAccounts);
    }
    return tableAccountMetas;
  }

  static LookupTableAccountMeta[] createMetas(final Collection<AddressLookupTable> lookupTables, final int maxNumAccounts) {
    final int numTables = lookupTables.size();
    final LookupTableAccountMeta[] tableAccountMetas = new LookupTableAccountMeta[numTables];
    int i = 0;
    for (final var lookupTable : lookupTables) {
      tableAccountMetas[i++] = createMeta(lookupTable, maxNumAccounts);
    }
    return tableAccountMetas;
  }

  static LookupTableAccountMeta createMeta(final AddressLookupTable lookupTable) {
    return new TableAccountMeta(lookupTable, LOOKUP_TABLE_MAX_ADDRESSES);
  }

  static LookupTableAccountMeta[] createMetas(final AddressLookupTable[] lookupTables) {
    return createMetas(lookupTables, LOOKUP_TABLE_MAX_ADDRESSES);
  }

  static LookupTableAccountMeta[] createMetas(final Collection<AddressLookupTable> lookupTables) {
    return createMetas(lookupTables, LOOKUP_TABLE_MAX_ADDRESSES);
  }

  AddressLookupTable lookupTable();

  boolean addAccountIfExists(final AccountMeta account);

  int indexWrites(final AccountIndexLookupTableEntry[] accountIndexLookupTable, int i);

  int indexReads(final AccountIndexLookupTableEntry[] accountIndexLookupTable, int i);

  int indexWrites(final Map<PublicKey, Integer> accountIndexLookupTable, int i);

  int indexReads(final Map<PublicKey, Integer> accountIndexLookupTable, int i);

  void reset();

  int numIndexed();

  int serialize(final byte[] out, int i);
}
