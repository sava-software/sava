package software.sava.core.accounts.lookup;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.Base58;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;

/// The two index-lookup helper records back account-to-index resolution when building
/// versioned transactions; mutation testing (2026-07-18 tx baseline) showed neither had
/// any coverage. Fixed keys throughout: the ratchet needs deterministic kills.
final class AccountIndexLookupTableTests {

  private static byte[] key(final int fill) {
    final byte[] key = new byte[PUBLIC_KEY_LENGTH];
    Arrays.fill(key, (byte) fill);
    return key;
  }

  /// Entries sorted ascending by key bytes, as `lookupAccountIndex`'s binary search requires.
  private static AccountIndexLookupTableEntry[] sortedEntries() {
    final var entries = new AccountIndexLookupTableEntry[]{
        new AccountIndexLookupTableEntry(key(1), 7),
        new AccountIndexLookupTableEntry(key(3), 0),
        new AccountIndexLookupTableEntry(key(5), 42)
    };
    Arrays.sort(entries);
    return entries;
  }

  @Test
  void lookupAccountIndexFindsEveryEntryAndFloorsMisses() {
    final var entries = sortedEntries();
    // the first slot pins the `index < 0` boundary: binarySearch returns 0 there
    assertEquals(7, AccountIndexLookupTableEntry.lookupAccountIndex(entries, PublicKey.createPubKey(key(1))));
    assertEquals(0, AccountIndexLookupTableEntry.lookupAccountIndex(entries, PublicKey.createPubKey(key(3))));
    assertEquals(42, AccountIndexLookupTableEntry.lookupAccountIndex(entries, PublicKey.createPubKey(key(5))));
    // misses between entries and past both ends
    assertEquals(Integer.MIN_VALUE, AccountIndexLookupTableEntry.lookupAccountIndex(entries, PublicKey.createPubKey(key(0))));
    assertEquals(Integer.MIN_VALUE, AccountIndexLookupTableEntry.lookupAccountIndex(entries, PublicKey.createPubKey(key(2))));
    assertEquals(Integer.MIN_VALUE, AccountIndexLookupTableEntry.lookupAccountIndex(entries, PublicKey.createPubKey(key(6))));

    assertEquals((byte) 42, AccountIndexLookupTableEntry.lookupAccountIndexOrThrow(entries, PublicKey.createPubKey(key(5))));
    assertThrows(
        IllegalStateException.class,
        () -> AccountIndexLookupTableEntry.lookupAccountIndexOrThrow(entries, PublicKey.createPubKey(key(2)))
    );
  }

  @Test
  void indexOfResolvesMapEntriesAndFloorsMisses() {
    final var present = PublicKey.createPubKey(key(1));
    final var zeroIndexed = PublicKey.createPubKey(key(3));
    final var absent = PublicKey.createPubKey(key(9));
    final Map<PublicKey, Integer> accountMap = Map.of(present, 5, zeroIndexed, 0);

    assertEquals(5, AccountIndexLookupTableEntry.indexOf(accountMap, present));
    // index 0 pins the `index < 0` boundary
    assertEquals(0, AccountIndexLookupTableEntry.indexOf(accountMap, zeroIndexed));
    assertEquals(Integer.MIN_VALUE, AccountIndexLookupTableEntry.indexOf(accountMap, absent));

    assertEquals((byte) 5, AccountIndexLookupTableEntry.indexOfOrThrow(accountMap, present));
    assertEquals((byte) 0, AccountIndexLookupTableEntry.indexOfOrThrow(accountMap, zeroIndexed));
    assertThrows(IllegalStateException.class, () -> AccountIndexLookupTableEntry.indexOfOrThrow(accountMap, absent));

    // the `< 0` guard normalizes any negative map value to the MIN_VALUE sentinel
    final Map<PublicKey, Integer> negativeIndexed = Map.of(present, -3);
    assertEquals(Integer.MIN_VALUE, AccountIndexLookupTableEntry.indexOf(negativeIndexed, present));
    assertThrows(IllegalStateException.class, () -> AccountIndexLookupTableEntry.indexOfOrThrow(negativeIndexed, present));
  }

  @Test
  void entrySerializationAndRenders() {
    final byte[] keyBytes = key(9);
    final var entry = new AccountIndexLookupTableEntry(keyBytes, 3);

    assertSame(keyBytes, entry.toByteArray(), "toByteArray exposes the backing array");
    assertNotSame(keyBytes, entry.copyByteArray(), "copyByteArray must copy");
    assertArrayEquals(keyBytes, entry.copyByteArray());

    // write into a dirty buffer at a non-zero offset: dropped writes must be observable
    final byte[] out = new byte[PUBLIC_KEY_LENGTH + 8];
    Arrays.fill(out, (byte) 0xAA);
    assertEquals(PUBLIC_KEY_LENGTH, entry.write(out, 4));
    assertArrayEquals(keyBytes, Arrays.copyOfRange(out, 4, 4 + PUBLIC_KEY_LENGTH));
    for (final int untouched : new int[]{0, 1, 2, 3, out.length - 4, out.length - 3, out.length - 2, out.length - 1}) {
      assertEquals((byte) 0xAA, out[untouched], "byte " + untouched + " must not be written");
    }

    final var base58 = Base58.encode(keyBytes);
    assertEquals(base58, entry.toBase58());
    assertEquals(Base64.getEncoder().encodeToString(keyBytes), entry.toBase64());
    final var string = entry.toString();
    assertTrue(string.contains(base58), string);
    assertTrue(string.contains("index=3"), string);
  }

  @Test
  void entryEqualsAndHashCodeCompareKeyBytesOnly() {
    final var entry = new AccountIndexLookupTableEntry(key(4), 1);
    assertEquals(entry, entry);
    // the index is not part of equality: any PublicKey with the same bytes matches
    assertEquals(entry, new AccountIndexLookupTableEntry(key(4), 2));
    assertEquals(entry, PublicKey.createPubKey(key(4)));
    assertNotEquals(entry, new AccountIndexLookupTableEntry(key(6), 1));
    assertNotEquals(entry, (Object) "not a public key");

    assertEquals(entry.hashCode(), new AccountIndexLookupTableEntry(key(4), 2).hashCode());
    assertNotEquals(entry.hashCode(), new AccountIndexLookupTableEntry(key(6), 1).hashCode());
  }

  /// One backing array holding three keys behind a junk prefix, so every view offset is
  /// non-zero and offset-arithmetic mutants cannot hide at offset 0.
  private static byte[] backingTable() {
    final byte[] table = new byte[5 + 3 * PUBLIC_KEY_LENGTH];
    Arrays.fill(table, 0, 5, (byte) 0x77);
    System.arraycopy(key(2), 0, table, 5, PUBLIC_KEY_LENGTH);
    System.arraycopy(key(4), 0, table, 5 + PUBLIC_KEY_LENGTH, PUBLIC_KEY_LENGTH);
    System.arraycopy(key(6), 0, table, 5 + 2 * PUBLIC_KEY_LENGTH, PUBLIC_KEY_LENGTH);
    return table;
  }

  private static AccountIndexLookupTableView view(final byte[] table, final int slot) {
    return new AccountIndexLookupTableView(table, 5 + slot * PUBLIC_KEY_LENGTH, slot);
  }

  @Test
  void viewSerializationAndRenders() {
    final byte[] table = backingTable();
    final var view = view(table, 1);

    assertArrayEquals(key(4), view.toByteArray());
    assertArrayEquals(key(4), view.copyByteArray());

    final byte[] out = new byte[PUBLIC_KEY_LENGTH + 8];
    Arrays.fill(out, (byte) 0xAA);
    assertEquals(PUBLIC_KEY_LENGTH, view.write(out, 4));
    assertArrayEquals(key(4), Arrays.copyOfRange(out, 4, 4 + PUBLIC_KEY_LENGTH));
    for (final int untouched : new int[]{0, 1, 2, 3, out.length - 4, out.length - 3, out.length - 2, out.length - 1}) {
      assertEquals((byte) 0xAA, out[untouched], "byte " + untouched + " must not be written");
    }

    assertEquals(Base58.encode(key(4)), view.toBase58());
    assertEquals(Base64.getEncoder().encodeToString(key(4)), view.toBase64());
  }

  @Test
  void viewCompareToOrdersBySlotBytes() {
    final byte[] table = backingTable();
    final var first = view(table, 0);
    final var middle = view(table, 1);
    final var last = view(table, 2);

    // view vs view: both sides resolve through the shared backing table
    assertTrue(first.compareTo(middle) < 0);
    assertTrue(last.compareTo(middle) > 0);
    assertEquals(0, middle.compareTo(view(table, 1)));

    // view vs any other PublicKey implementation takes the toByteArray branch
    assertTrue(middle.compareTo(PublicKey.createPubKey(key(6))) < 0);
    assertTrue(middle.compareTo(PublicKey.createPubKey(key(2))) > 0);
    assertEquals(0, middle.compareTo(PublicKey.createPubKey(key(4))));
  }

  @Test
  void viewEqualsAndHashCodeCompareSlotBytesOnly() {
    final byte[] table = backingTable();
    final var view = view(table, 1);

    assertEquals(view, view);
    // the index is not part of equality: any PublicKey with the same bytes matches
    assertEquals(view, new AccountIndexLookupTableView(table, 5 + PUBLIC_KEY_LENGTH, 9));
    assertEquals(view, PublicKey.createPubKey(key(4)));
    assertEquals(view, new AccountIndexLookupTableEntry(key(4), 1));
    assertNotEquals(view, view(table, 0));
    assertNotEquals(view, (Object) "not a public key");

    assertEquals(view.hashCode(), PublicKey.createPubKey(key(4)).hashCode());
    assertNotEquals(view.hashCode(), view(table, 2).hashCode());
  }
}
