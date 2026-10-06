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

  /// Solana compares key bytes as unsigned values, so `0x7f` sorts below `0x80`; a signed
  /// comparison reverses them. `Arrays.sort` must leave the index in the chain's order, and the
  /// binary search must then find every key on both sides of that boundary.
  @Test
  void lookupAccountIndexSortsAndSearchesKeysOnBothSidesOfTheSignBit() {
    final int[] fills = {0x80, 0x01, 0xff, 0x7f};
    final var entries = new AccountIndexLookupTableEntry[fills.length];
    for (int i = 0; i < fills.length; ++i) {
      entries[i] = new AccountIndexLookupTableEntry(key(fills[i]), fills[i]);
    }
    Arrays.sort(entries);
    assertArrayEquals(
        new int[]{0x01, 0x7f, 0x80, 0xff},
        Arrays.stream(entries).mapToInt(AccountIndexLookupTableEntry::index).toArray()
    );

    for (final int fill : fills) {
      assertEquals(fill, AccountIndexLookupTableEntry.lookupAccountIndex(entries, PublicKey.createPubKey(key(fill))));
    }
    for (final int miss : new int[]{0x00, 0x02, 0x7e, 0x81, 0xfe}) {
      assertEquals(Integer.MIN_VALUE, AccountIndexLookupTableEntry.lookupAccountIndex(entries, PublicKey.createPubKey(key(miss))));
    }
  }

  /// Each implementation of one key, the view over its own backing table.
  private static PublicKey[] implementations(final byte[] keyBytes) {
    final byte[] table = new byte[5 + PUBLIC_KEY_LENGTH];
    Arrays.fill(table, 0, 5, (byte) 0x77);
    System.arraycopy(keyBytes, 0, table, 5, PUBLIC_KEY_LENGTH);
    return new PublicKey[]{
        PublicKey.createPubKey(keyBytes.clone()),
        new AccountIndexLookupTableEntry(keyBytes.clone(), 1),
        new AccountIndexLookupTableView(table, 5, 2)
    };
  }

  /// The natural order is Solana's: key bytes compared as unsigned values. The two keys here are
  /// equal up to their last byte, `0x7f` against `0x80`, which a signed comparison orders the
  /// other way. Every ordered pair of implementations is checked because each has its own
  /// `compareTo` and a sorted collection may mix them.
  @Test
  void compareToOrdersKeyBytesUnsignedForEveryPairOfImplementations() {
    final byte[] lowBytes = key(0x80);
    lowBytes[PUBLIC_KEY_LENGTH - 1] = 0x7f;
    final var lows = implementations(lowBytes);
    final var highs = implementations(key(0x80));
    for (final var low : lows) {
      for (final var high : highs) {
        final var pair = low.getClass().getSimpleName() + " vs " + high.getClass().getSimpleName();
        assertTrue(low.compareTo(high) < 0, pair);
        assertTrue(high.compareTo(low) > 0, pair);
      }
      for (final var sameKey : lows) {
        assertEquals(0, low.compareTo(sameKey), low.getClass().getSimpleName() + " vs " + sameKey.getClass().getSimpleName());
      }
    }
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
    assertEquals("AccountIndexLookupTableEntry[publicKey=" + base58 + ", index=3]", entry.toString());
  }

  @Test
  void entryEqualsAndHashCodeCompareKeyBytesOnly() {
    final var entry = new AccountIndexLookupTableEntry(key(4), 1);
    //noinspection EqualsWithItself
    assertEquals(entry, entry);
    // the index is not part of equality: any PublicKey with the same bytes matches
    assertEquals(entry, new AccountIndexLookupTableEntry(key(4), 2));
    assertEquals(entry, PublicKey.createPubKey(key(4)));
    assertNotEquals(entry, new AccountIndexLookupTableEntry(key(6), 1));
    //noinspection MisorderedAssertEqualsArguments,AssertBetweenInconvertibleTypes
    assertNotEquals(entry, "not a public key");

    assertEquals(entry.hashCode(), new AccountIndexLookupTableEntry(key(4), 2).hashCode());
    assertNotEquals(entry.hashCode(), new AccountIndexLookupTableEntry(key(6), 1).hashCode());
  }

  /// A key is 32 bytes, as `PublicKey.createPubKey` already insists. An entry over any other
  /// length used to construct: a longer one then disagreed with a view of its first 32 bytes
  /// about order and equality, and a shorter one made a view's `compareTo` and `equals` throw.
  @Test
  void entryRejectsKeysThatAreNotThirtyTwoBytes() {
    for (final int length : new int[]{0, PUBLIC_KEY_LENGTH - 1, PUBLIC_KEY_LENGTH + 1, PUBLIC_KEY_LENGTH << 1}) {
      final byte[] keyBytes = new byte[length];
      assertThrows(IllegalArgumentException.class, () -> PublicKey.createPubKey(keyBytes));
      final var exception = assertThrows(
          IllegalArgumentException.class,
          () -> new AccountIndexLookupTableEntry(keyBytes, 1)
      );
      assertEquals("Public key needs 32 bytes, but " + length + " were given.", exception.getMessage());
    }
    assertThrows(NullPointerException.class, () -> new AccountIndexLookupTableEntry(null, 1));
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
    // the key, not the backing table's identity hash
    assertEquals(
        "AccountIndexLookupTableView[publicKey=" + Base58.encode(key(4)) + ", offset=37, index=1]",
        view.toString()
    );
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

  /// Regression: the view-vs-view branch used to compare `this.lookupTable`
  /// against itself at the argument's offset, so views over *different* backing
  /// tables compared whatever this table held at that offset. Same slot layout,
  /// different bytes: the comparison must read the argument's table.
  @Test
  void viewCompareToReadsTheOtherViewsBackingTable() {
    final byte[] table = backingTable();
    final byte[] otherTable = backingTable();
    // same offset as view(table, 1) but different key bytes behind it
    System.arraycopy(key(9), 0, otherTable, 5 + PUBLIC_KEY_LENGTH, PUBLIC_KEY_LENGTH);

    final var middle = view(table, 1);          // key(4)
    final var otherMiddle = view(otherTable, 1); // key(9)

    assertTrue(middle.compareTo(otherMiddle) < 0, "key(4) must order below key(9) from the other table");
    assertTrue(otherMiddle.compareTo(middle) > 0);
    // identical bytes across distinct tables still compare equal
    assertEquals(0, middle.compareTo(view(backingTable(), 1)));
  }

  @Test
  void viewEqualsAndHashCodeCompareSlotBytesOnly() {
    final byte[] table = backingTable();
    final var view = view(table, 1);

    //noinspection EqualsWithItself
    assertEquals(view, view);
    // the index is not part of equality: any PublicKey with the same bytes matches
    assertEquals(view, new AccountIndexLookupTableView(table, 5 + PUBLIC_KEY_LENGTH, 9));
    assertEquals(view, PublicKey.createPubKey(key(4)));
    //noinspection AssertBetweenInconvertibleTypes
    assertEquals(view, new AccountIndexLookupTableEntry(key(4), 1));
    assertNotEquals(view, view(table, 0));
    //noinspection MisorderedAssertEqualsArguments,AssertBetweenInconvertibleTypes
    assertNotEquals(view, "not a public key");

    assertEquals(view.hashCode(), PublicKey.createPubKey(key(4)).hashCode());
    assertNotEquals(view.hashCode(), view(table, 2).hashCode());
  }

  /// A view's 32 bytes must lie inside its table: the windows `PublicKey.readPubKey` accepts.
  /// Where a window overhung the table's end, `toByteArray` used to pad the missing bytes with
  /// zeros while the view's own `compareTo` threw.
  @Test
  void viewRejectsWindowsThatLeaveTheTable() {
    final byte[] table = backingTable();
    final int lastOffset = table.length - PUBLIC_KEY_LENGTH;
    for (final int offset : new int[]{0, lastOffset}) {
      assertArrayEquals(
          PublicKey.readPubKey(table, offset).toByteArray(),
          new AccountIndexLookupTableView(table, offset, 0).toByteArray(),
          "offset " + offset
      );
    }
    for (final int offset : new int[]{Integer.MIN_VALUE, -1, lastOffset + 1, table.length, table.length + 1, Integer.MAX_VALUE}) {
      assertThrows(IndexOutOfBoundsException.class, () -> PublicKey.readPubKey(table, offset), "offset " + offset);
      assertThrows(
          IndexOutOfBoundsException.class,
          () -> new AccountIndexLookupTableView(table, offset, 0),
          "offset " + offset
      );
    }
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> new AccountIndexLookupTableView(new byte[PUBLIC_KEY_LENGTH - 1], 0, 0)
    );
    assertThrows(NullPointerException.class, () -> new AccountIndexLookupTableView(null, 0, 0));
  }
}
