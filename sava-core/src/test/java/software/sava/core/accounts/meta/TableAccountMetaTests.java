package software.sava.core.accounts.meta;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AccountIndexLookupTableEntry;
import software.sava.core.accounts.lookup.AddressLookupTable;

import java.util.Arrays;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.lookup.AddressLookupTable.LOOKUP_TABLE_MAX_ADDRESSES;
import static software.sava.core.accounts.lookup.AddressLookupTable.LOOKUP_TABLE_META_SIZE;

/// Indices into an address lookup table are stored as `byte` and read back with
/// `& 0xFF`. A lookup table holds up to 256 addresses, so every index from 128 up
/// is negative once narrowed — the pairing has to be exact or a transaction
/// silently references the wrong account. Nothing referenced this class before.
final class TableAccountMetaTests {

  private static final PublicKey TABLE_ADDRESS = key(0xEE);

  private static PublicKey key(final int seed) {
    final byte[] bytes = new byte[32];
    bytes[0] = (byte) seed;
    bytes[1] = (byte) (seed >> 8);
    return PublicKey.createPubKey(bytes);
  }

  /// A full 256 address table: 56 bytes of zeroed meta (authority option 0 means
  /// no authority) followed by one distinct key per slot.
  private static AddressLookupTable table(final int numAccounts) {
    final byte[] data = new byte[LOOKUP_TABLE_META_SIZE + (numAccounts << 5)];
    for (int i = 0, o = LOOKUP_TABLE_META_SIZE; i < numAccounts; ++i, o += 32) {
      System.arraycopy(key(i).toByteArray(), 0, data, o, 32);
    }
    return AddressLookupTable.read(TABLE_ADDRESS, data);
  }

  private static LookupTableAccountMeta meta(final AddressLookupTable table) {
    return LookupTableAccountMeta.createMeta(table);
  }

  @Test
  void accountsOutsideTheTableAreRejected() {
    final var accountMeta = meta(table(8));
    assertFalse(accountMeta.addAccountIfExists(AccountMeta.createWrite(key(9_999))));
    assertFalse(accountMeta.addAccountIfExists(AccountMeta.createRead(key(9_999))));
    assertEquals(0, accountMeta.numIndexed());
  }

  @Test
  void writesAndReadsAreCountedSeparately() {
    final var accountMeta = meta(table(8));
    assertTrue(accountMeta.addAccountIfExists(AccountMeta.createWrite(key(0))));
    assertTrue(accountMeta.addAccountIfExists(AccountMeta.createWrite(key(1))));
    assertTrue(accountMeta.addAccountIfExists(AccountMeta.createRead(key(2))));
    assertEquals(3, accountMeta.numIndexed());

    final var indexed = new HashMap<PublicKey, Integer>();
    assertEquals(2, accountMeta.indexWrites(indexed, 0));
    assertEquals(3, accountMeta.indexReads(indexed, 2));
    assertEquals(0, indexed.get(key(0)));
    assertEquals(1, indexed.get(key(1)));
    assertEquals(2, indexed.get(key(2)));
  }

  /// Signers are not writable unless they say so — routing is on write() alone.
  @Test
  void routingFollowsWriteFlagOnly() {
    final var accountMeta = meta(table(8));
    accountMeta.addAccountIfExists(AccountMeta.createReadOnlySigner(key(0)));
    accountMeta.addAccountIfExists(AccountMeta.createWritableSigner(key(1)));
    accountMeta.addAccountIfExists(AccountMeta.createInvoked(key(2)));

    final var indexed = new HashMap<PublicKey, Integer>();
    final int afterWrites = accountMeta.indexWrites(indexed, 0);
    assertEquals(1, afterWrites, "only the writable signer should be a write");
    assertEquals(0, indexed.get(key(1)));

    accountMeta.indexReads(indexed, afterWrites);
    assertEquals(1, indexed.get(key(0)));
    assertEquals(2, indexed.get(key(2)));
  }

  /// The whole point: every index in a full table must survive the byte narrowing.
  @Test
  void everyIndexInAFullTableRoundTrips() {
    final var lookupTable = table(LOOKUP_TABLE_MAX_ADDRESSES);
    assertEquals(LOOKUP_TABLE_MAX_ADDRESSES, lookupTable.numAccounts());

    for (int index = 0; index < LOOKUP_TABLE_MAX_ADDRESSES; ++index) {
      final var accountMeta = meta(lookupTable);
      assertTrue(accountMeta.addAccountIfExists(AccountMeta.createWrite(key(index))), "index " + index);

      final var entries = new AccountIndexLookupTableEntry[1];
      assertEquals(1, accountMeta.indexWrites(entries, 0));
      assertArrayEquals(key(index).toByteArray(), entries[0].publicKey(),
          "index " + index + " narrowed to the wrong account");

      final var indexed = new HashMap<PublicKey, Integer>();
      accountMeta.indexWrites(indexed, 0);
      assertEquals(0, indexed.get(key(index)), "index " + index + " via the map overload");
    }
  }

  /// The high half is where a missing `& 0xFF` would show up.
  @Test
  void indicesPastTheSignBoundaryAreUnsigned() {
    final var lookupTable = table(LOOKUP_TABLE_MAX_ADDRESSES);
    final var accountMeta = meta(lookupTable);
    for (final int index : new int[]{127, 128, 129, 200, 254, 255}) {
      accountMeta.addAccountIfExists(AccountMeta.createWrite(key(index)));
    }
    final var indexed = new HashMap<PublicKey, Integer>();
    assertEquals(6, accountMeta.indexWrites(indexed, 0));
    assertEquals(0, indexed.get(key(127)));
    assertEquals(1, indexed.get(key(128)));
    assertEquals(2, indexed.get(key(129)));
    assertEquals(3, indexed.get(key(200)));
    assertEquals(4, indexed.get(key(254)));
    assertEquals(5, indexed.get(key(255)));
  }

  @Test
  void indexingStartsFromTheGivenOffset() {
    final var accountMeta = meta(table(8));
    accountMeta.addAccountIfExists(AccountMeta.createWrite(key(0)));
    accountMeta.addAccountIfExists(AccountMeta.createRead(key(1)));

    final var entries = new AccountIndexLookupTableEntry[10];
    assertEquals(6, accountMeta.indexWrites(entries, 5));
    assertEquals(7, accountMeta.indexReads(entries, 6));
    assertNull(entries[4]);
    assertArrayEquals(key(0).toByteArray(), entries[5].publicKey());
    assertArrayEquals(key(1).toByteArray(), entries[6].publicKey());
    assertEquals(5, entries[5].index());
    assertEquals(6, entries[6].index());
  }

  @Test
  void resetClearsCountsAndStaleEntriesAreNotSurfaced() {
    final var accountMeta = meta(table(8));
    accountMeta.addAccountIfExists(AccountMeta.createWrite(key(0)));
    accountMeta.addAccountIfExists(AccountMeta.createRead(key(1)));
    assertEquals(2, accountMeta.numIndexed());

    accountMeta.reset();
    assertEquals(0, accountMeta.numIndexed());

    final var indexed = new HashMap<PublicKey, Integer>();
    assertEquals(0, accountMeta.indexWrites(indexed, 0));
    assertEquals(0, accountMeta.indexReads(indexed, 0));
    assertTrue(indexed.isEmpty(), "reset must not leave stale indices readable");

    // and the meta is reusable afterwards
    assertTrue(accountMeta.addAccountIfExists(AccountMeta.createWrite(key(3))));
    assertEquals(1, accountMeta.numIndexed());
    accountMeta.indexWrites(indexed, 0);
    assertEquals(0, indexed.get(key(3)));
  }

  /// Wire format for a message address table lookup: the table address, then a
  /// compact-u16 length prefixed array of writable indices, then the same for
  /// readonly indices.
  @Test
  void serializeMatchesTheMessageWireFormat() {
    final var accountMeta = meta(table(LOOKUP_TABLE_MAX_ADDRESSES));
    accountMeta.addAccountIfExists(AccountMeta.createWrite(key(3)));
    accountMeta.addAccountIfExists(AccountMeta.createWrite(key(200)));
    accountMeta.addAccountIfExists(AccountMeta.createRead(key(7)));

    final byte[] out = new byte[64];
    final int end = accountMeta.serialize(out, 0);

    // 32 address + 1 length + 2 writes + 1 length + 1 read
    assertEquals(37, end);
    assertArrayEquals(TABLE_ADDRESS.toByteArray(), Arrays.copyOfRange(out, 0, 32));
    assertEquals(2, out[32], "writable count");
    assertEquals(3, out[33] & 0xFF);
    assertEquals(200, out[34] & 0xFF, "index 200 must serialize unsigned");
    assertEquals(1, out[35], "readonly count");
    assertEquals(7, out[36] & 0xFF);
    // nothing written past the end
    for (int i = end; i < out.length; ++i) {
      assertEquals(0, out[i], "wrote past the field at " + i);
    }
  }

  @Test
  void serializeHonoursTheStartingOffsetAndEmptyArrays() {
    final var accountMeta = meta(table(8));
    final byte[] out = new byte[48];
    final int end = accountMeta.serialize(out, 4);
    // 32 address + two empty compact-u16 lengths
    assertEquals(4 + 34, end);
    assertEquals(0, out[0]);
    assertArrayEquals(TABLE_ADDRESS.toByteArray(), Arrays.copyOfRange(out, 4, 36));
    assertEquals(0, out[36]);
    assertEquals(0, out[37]);
  }

  @Test
  void lookupTableIsExposedAndIndexOfDelegates() {
    final var lookupTable = table(8);
    final var accountMeta = meta(lookupTable);
    assertSame(lookupTable, accountMeta.lookupTable());
    assertEquals(TABLE_ADDRESS, accountMeta.lookupTable().address());
  }

  @Test
  void createMetasFromACollectionMirrorsIterationOrder() {
    final var first = table(8);
    final var second = table(4);
    final var metas = LookupTableAccountMeta.createMetas(java.util.List.of(first, second));
    assertEquals(2, metas.length);
    assertSame(first, metas[0].lookupTable());
    assertSame(second, metas[1].lookupTable());
  }

  /// The bounded overloads size the index arrays; anything past the bound is a
  /// caller error rather than a silent truncation.
  @Test
  void maxAccountsBoundsTheIndexArrays() {
    final var lookupTable = table(8);
    final var accountMeta = LookupTableAccountMeta.createMeta(lookupTable, 2);
    assertTrue(accountMeta.addAccountIfExists(AccountMeta.createWrite(key(0))));
    assertTrue(accountMeta.addAccountIfExists(AccountMeta.createWrite(key(1))));
    assertThrows(ArrayIndexOutOfBoundsException.class,
        () -> accountMeta.addAccountIfExists(AccountMeta.createWrite(key(2))));

    // reads and writes are bounded independently
    final var byCollection = LookupTableAccountMeta.createMetas(java.util.List.of(lookupTable), 1);
    assertTrue(byCollection[0].addAccountIfExists(AccountMeta.createWrite(key(0))));
    assertTrue(byCollection[0].addAccountIfExists(AccountMeta.createRead(key(1))));
    assertEquals(2, byCollection[0].numIndexed());
  }

  /// createMetas mirrors the input order and gives each table its own counters.
  @Test
  void createMetasIsIndependentPerTable() {
    final var first = table(8);
    final var second = table(4);
    final var metas = LookupTableAccountMeta.createMetas(new AddressLookupTable[]{first, second});
    assertEquals(2, metas.length);
    assertSame(first, metas[0].lookupTable());
    assertSame(second, metas[1].lookupTable());

    metas[0].addAccountIfExists(AccountMeta.createWrite(key(0)));
    assertEquals(1, metas[0].numIndexed());
    assertEquals(0, metas[1].numIndexed(), "metas must not share counters");
  }
}
