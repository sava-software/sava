package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AccountIndexLookupTableEntry;
import software.sava.core.accounts.meta.AccountMeta;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.meta.AccountMeta.createRead;
import static software.sava.core.accounts.meta.AccountMeta.createWrite;

/// Building-side coverage for [Instruction] and [Transaction]: the account-appending,
/// prefix-matching, and instruction-splicing API. Mutation testing showed this surface had
/// no coverage at all, so an append that dropped or reordered an account — silently
/// re-pointing an instruction at the wrong account — was invisible.
final class InstructionBuildingTests {

  private static PublicKey key(final int seed) {
    final byte[] bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(bytes, (byte) seed);
    return PublicKey.createPubKey(bytes);
  }

  private static final PublicKey PROGRAM = key(1);
  private static final AccountMeta ACCOUNT_A = createWrite(key(2));
  private static final AccountMeta ACCOUNT_B = createRead(key(3));
  private static final AccountMeta ACCOUNT_C = createWrite(key(4));
  private static final AccountMeta ACCOUNT_D = createRead(key(5));

  private static Instruction instruction() {
    return Instruction.createInstruction(PROGRAM, List.of(ACCOUNT_A, ACCOUNT_B), new byte[]{1, 2, 3, 4});
  }

  private static void assertUnchangedBase(final Instruction original) {
    assertEquals(List.of(ACCOUNT_A, ACCOUNT_B), original.accounts(), "extra* must not mutate the receiver");
  }

  @Test
  void extraAccountAppends() {
    final var base = instruction();

    final var one = base.extraAccount(ACCOUNT_C);
    assertEquals(List.of(ACCOUNT_A, ACCOUNT_B, ACCOUNT_C), one.accounts());
    assertEquals(base.programId(), one.programId());
    assertArrayEquals(base.data(), one.data());
    assertEquals(base.len(), one.len());
    assertUnchangedBase(base);

    // appends compose in order
    assertEquals(List.of(ACCOUNT_A, ACCOUNT_B, ACCOUNT_C, ACCOUNT_D), one.extraAccount(ACCOUNT_D).accounts());

    // a null account is a no-op returning the receiver untouched
    assertSame(base, base.extraAccount((AccountMeta) null));
  }

  @Test
  void extraAccountsAppends() {
    final var base = instruction();

    assertEquals(List.of(ACCOUNT_A, ACCOUNT_B, ACCOUNT_C, ACCOUNT_D),
        base.extraAccounts(List.of(ACCOUNT_C, ACCOUNT_D)).accounts());
    assertUnchangedBase(base);

    // the single-element path delegates to extraAccount but must agree with it
    assertEquals(base.extraAccount(ACCOUNT_C).accounts(), base.extraAccounts(List.of(ACCOUNT_C)).accounts());

    // an empty list is a no-op returning the receiver untouched
    assertSame(base, base.extraAccounts(List.of()));
  }

  @Test
  void extraAccountsAppliesMetaFactory() {
    final var base = instruction();

    final var appended = base.extraAccounts(List.of(key(4), key(5)), AccountMeta::createWrite);
    assertEquals(List.of(ACCOUNT_A, ACCOUNT_B, createWrite(key(4)), createWrite(key(5))), appended.accounts());
    assertUnchangedBase(base);

    // the factory decides the meta: the same key appended read-only must not be writable
    final var readOnly = base.extraAccount(key(4), AccountMeta::createRead);
    assertEquals(createRead(key(4)), readOnly.accounts().getLast());
    assertFalse(readOnly.accounts().getLast().write());

    assertSame(base, base.extraAccounts(List.of(), AccountMeta::createWrite));
    assertSame(base, base.extraAccount((PublicKey) null, AccountMeta::createWrite));
  }

  @Test
  void beginsWithMatchesDataPrefix() {
    final var base = instruction(); // data {1, 2, 3, 4}

    assertTrue(base.beginsWith(new byte[]{1}));
    assertTrue(base.beginsWith(new byte[]{1, 2}));
    assertTrue(base.beginsWith(new byte[]{1, 2, 3, 4}));
    assertTrue(base.beginsWith(new byte[0]));

    assertFalse(base.beginsWith(new byte[]{2}));
    assertFalse(base.beginsWith(new byte[]{1, 2, 4}));
    // a prefix longer than the data cannot match
    assertFalse(base.beginsWith(new byte[]{1, 2, 3, 4, 5}));
  }

  @Test
  void beginsWithRespectsSliceBounds() {
    // slice-backed instruction: the discriminator must be read from `offset`, not from the
    // start of the backing array, and must not run past `len`
    final byte[] backing = {9, 9, 1, 2, 3, 4, 9, 9};
    final var sliced = Instruction.createInstruction(PROGRAM, List.of(ACCOUNT_A), backing, 2, 4);

    assertEquals(4, sliced.len());
    assertEquals(2, sliced.offset());
    assertTrue(sliced.beginsWith(new byte[]{1, 2}));
    assertTrue(sliced.beginsWith(new byte[]{1, 2, 3, 4}));
    // the backing prefix is not the instruction's data
    assertFalse(sliced.beginsWith(new byte[]{9, 9}));
    // must not read past len into the backing array's tail
    assertFalse(sliced.beginsWith(new byte[]{1, 2, 3, 4, 9}));
  }

  @Test
  void spliceInstructions() {
    final var first = Instruction.createInstruction(PROGRAM, List.of(ACCOUNT_A), new byte[]{1});
    final var second = Instruction.createInstruction(PROGRAM, List.of(ACCOUNT_B), new byte[]{2});
    final var third = Instruction.createInstruction(PROGRAM, List.of(ACCOUNT_C), new byte[]{3});
    final var feePayer = key(9);

    final var tx = Transaction.createTx(feePayer, List.of(second));
    assertEquals(List.of(second), tx.instructions());

    assertEquals(List.of(first, second), tx.prependIx(first).instructions());
    assertEquals(List.of(second, third), tx.appendIx(third).instructions());
    assertEquals(List.of(first, third, second), tx.prependInstructions(first, third).instructions());
    assertEquals(List.of(first, third, second), tx.prependInstructions(List.of(first, third)).instructions());
    assertEquals(List.of(second, first, third), tx.appendInstructions(List.of(first, third)).instructions());

    // splicing yields a new transaction; the receiver keeps its own instruction list
    assertEquals(List.of(second), tx.instructions());
  }

  /// Index lookups in the order Transaction assigns them: the fee payer is always 0, so an
  /// instruction's program may never resolve to it.
  private static Map<PublicKey, Integer> indexMap(final PublicKey feePayer, final PublicKey... rest) {
    final var map = new HashMap<PublicKey, Integer>();
    map.put(feePayer, 0);
    for (int i = 0; i < rest.length; ++i) {
      map.put(rest[i], i + 1);
    }
    return map;
  }

  private static AccountIndexLookupTableEntry[] indexEntries(final Map<PublicKey, Integer> map) {
    // the array overload binary-searches, so entries must be sorted by key
    return map.entrySet().stream()
        .map(e -> new AccountIndexLookupTableEntry(e.getKey().toByteArray(), e.getValue()))
        .sorted()
        .toArray(AccountIndexLookupTableEntry[]::new);
  }

  @Test
  void serializeOverloadsAgree() {
    // the Map overload is what Transaction serialization uses; the array overload is public
    // API with no in-repo caller. They must encode identically.
    final var ix = instruction();
    final var map = indexMap(key(9), PROGRAM, ACCOUNT_A.publicKey(), ACCOUNT_B.publicKey());
    final var entries = indexEntries(map);

    final byte[] viaMap = new byte[64];
    final byte[] viaArray = new byte[64];
    final int endMap = ix.serialize(viaMap, 3, map);
    final int endArray = ix.serialize(viaArray, 3, entries);

    assertEquals(endMap, endArray, "serialize overloads must consume the same length");
    assertArrayEquals(viaMap, viaArray, "serialize overloads must encode identical bytes");
    assertEquals(3 + ix.serializedLength(), endMap, "serialize must advance by serializedLength");

    // wire layout: programIdIndex, compact-u16 account count, one index per account,
    // compact-u16 data length, data
    assertEquals(map.get(PROGRAM).byteValue(), viaMap[3]);
    assertEquals(2, viaMap[4]);
    assertEquals(map.get(ACCOUNT_A.publicKey()).byteValue(), viaMap[5]);
    assertEquals(map.get(ACCOUNT_B.publicKey()).byteValue(), viaMap[6]);
    assertEquals(4, viaMap[7]);
    assertArrayEquals(new byte[]{1, 2, 3, 4}, Arrays.copyOfRange(viaMap, 8, 12));
  }

  @Test
  void serializeRejectsFeePayerAsProgram() {
    // index 0 is the fee payer; an instruction invoking it is malformed and both overloads
    // must refuse rather than emit a transaction that invokes the fee payer
    final var ix = instruction();
    final var map = indexMap(PROGRAM, ACCOUNT_A.publicKey(), ACCOUNT_B.publicKey());
    final var entries = indexEntries(map);
    final byte[] out = new byte[64];

    assertThrows(IllegalStateException.class, () -> ix.serialize(out, 0, map));
    assertThrows(IllegalStateException.class, () -> ix.serialize(out, 0, entries));
  }

  @Test
  void serializeRejectsUnknownAccount() {
    // an account missing from the index table must throw, not encode a bogus index
    final var ix = instruction();
    final var missingAccount = indexMap(key(9), PROGRAM, ACCOUNT_A.publicKey());
    final byte[] out = new byte[64];

    assertThrows(IllegalStateException.class, () -> ix.serialize(out, 0, missingAccount));
    assertThrows(IllegalStateException.class, () -> ix.serialize(out, 0, indexEntries(missingAccount)));
  }

  @Test
  void copyDataAndDiscriminatorRespectSlice() {
    final byte[] backing = {9, 9, 1, 2, 3, 4, 9, 9};
    final var sliced = Instruction.createInstruction(PROGRAM, List.of(ACCOUNT_A), backing, 2, 4);

    // copyData must return the instruction's own bytes, not the backing array
    assertArrayEquals(new byte[]{1, 2, 3, 4}, sliced.copyData());
    assertNotSame(backing, sliced.copyData());

    // wrapDiscriminator takes a prefix of the instruction data, again from the offset
    assertArrayEquals(new byte[]{1, 2}, sliced.wrapDiscriminator(2).data());
    assertArrayEquals(new byte[]{1, 2, 3, 4}, sliced.wrapDiscriminator(4).data());
  }

  @Test
  void equalsComparesDataContentNotBacking() {
    final var plain = Instruction.createInstruction(PROGRAM, List.of(ACCOUNT_A), new byte[]{1, 2, 3, 4});
    // same logical data, different backing array and offset
    final var sliced = Instruction.createInstruction(
        PROGRAM, List.of(ACCOUNT_A), new byte[]{7, 1, 2, 3, 4, 7}, 1, 4);

    assertEquals(plain, sliced);
    assertEquals(sliced, plain);

    assertNotEquals(plain, Instruction.createInstruction(PROGRAM, List.of(ACCOUNT_A), new byte[]{1, 2, 3, 5}));
    assertNotEquals(plain, Instruction.createInstruction(PROGRAM, List.of(ACCOUNT_A), new byte[]{1, 2, 3}));
    assertNotEquals(plain, Instruction.createInstruction(PROGRAM, List.of(ACCOUNT_B), new byte[]{1, 2, 3, 4}));
    assertNotEquals(plain, Instruction.createInstruction(key(6), List.of(ACCOUNT_A), new byte[]{1, 2, 3, 4}));
    assertNotEquals(plain, "not an instruction");
  }

  @Test
  void toStringRendersProgramAccountsAndData() {
    final byte[] backing = {9, 1, 2, 3, 4, 9};
    final var sliced = Instruction.createInstruction(PROGRAM, List.of(ACCOUNT_A, ACCOUNT_B), backing, 1, 4);
    final var rendered = sliced.toString();

    assertTrue(rendered.contains(PROGRAM.toBase58()), rendered);
    assertTrue(rendered.contains(ACCOUNT_A.publicKey().toBase58()), rendered);
    assertTrue(rendered.contains(ACCOUNT_B.publicKey().toBase58()), rendered);
    // the data must be the slice, base64 encoded — not the backing array
    assertTrue(rendered.contains(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4})), rendered);

    // empty accounts and empty data render without blowing up
    final var bare = Instruction.createInstruction(PROGRAM, List.of(), new byte[0]);
    final var bareRendered = bare.toString();
    assertTrue(bareRendered.contains("[]"), bareRendered);
    assertTrue(bareRendered.contains("\"data\": \"\""), bareRendered);
  }

  @Test
  void mergeAccountsCollectsProgramAndAccounts() {
    final var ix = instruction();
    final var merged = new HashMap<PublicKey, AccountMeta>();

    assertEquals(ix.serializedLength(), ix.mergeAccounts(merged), "mergeAccounts returns the wire length");
    // the program itself must land in the account map alongside the instruction's accounts
    assertEquals(Set.of(PROGRAM, ACCOUNT_A.publicKey(), ACCOUNT_B.publicKey()), merged.keySet());
    assertTrue(merged.get(PROGRAM).invoked(), "the program must be marked invoked");

    // merging a second instruction unions the accounts and escalates privileges
    final var writableB = Instruction.createInstruction(
        key(6), List.of(createWrite(ACCOUNT_B.publicKey())), new byte[]{5});
    writableB.mergeAccounts(merged);
    assertEquals(Set.of(PROGRAM, ACCOUNT_A.publicKey(), ACCOUNT_B.publicKey(), key(6)), merged.keySet());
    assertTrue(merged.get(ACCOUNT_B.publicKey()).write(), "a read-only account merged as writable must become writable");
  }

  @Test
  void accountComparatorsOrderTheMessage() {
    // The account order decides every index in the serialized message, so the ordering
    // rules are load bearing: fee payer, then signers, then writables. The v0 comparator
    // additionally sorts invoked programs ahead of other read-only accounts.
    final var feePayer = AccountMeta.createFeePayer(key(1));
    final var writableSigner = AccountMeta.createWritableSigner(key(2));
    final var readOnlySigner = AccountMeta.createReadOnlySigner(key(3));
    final var writable = createWrite(key(4));
    final var invoked = AccountMeta.createInvoked(key(5));
    final var readOnly = createRead(key(6));

    for (final var comparator : List.of(TransactionRecord.LEGACY_META_COMPARATOR, TransactionRecord.VO_META_COMPARATOR)) {
      // the fee payer outranks everything, from either side
      assertTrue(comparator.compare(feePayer, writableSigner) < 0);
      assertTrue(comparator.compare(writableSigner, feePayer) > 0);
      // signers outrank non-signers
      assertTrue(comparator.compare(readOnlySigner, writable) < 0);
      assertTrue(comparator.compare(writable, readOnlySigner) > 0);
      // among signers, writable first
      assertTrue(comparator.compare(writableSigner, readOnlySigner) < 0);
      // among non-signers, writable first
      assertTrue(comparator.compare(writable, readOnly) < 0);
      // reflexive
      assertEquals(0, comparator.compare(readOnly, readOnly));
    }

    // the legacy comparator ignores invoked: two read-only non-signers tie
    assertEquals(0, TransactionRecord.LEGACY_META_COMPARATOR.compare(invoked, readOnly));
    assertEquals(0, TransactionRecord.LEGACY_META_COMPARATOR.compare(readOnly, invoked));

    // the v0 comparator breaks that tie, invoked first
    assertTrue(TransactionRecord.VO_META_COMPARATOR.compare(invoked, readOnly) < 0);
    assertTrue(TransactionRecord.VO_META_COMPARATOR.compare(readOnly, invoked) > 0);
    assertEquals(0, TransactionRecord.VO_META_COMPARATOR.compare(invoked, AccountMeta.createInvoked(key(7))));
  }

  @Test
  void exceedsSizeLimitTracksSerializedLength() {
    final var feePayer = key(9);

    final var small = Transaction.createTx(feePayer,
        Instruction.createInstruction(PROGRAM, List.of(ACCOUNT_A), new byte[8]));
    assertFalse(small.exceedsSizeLimit());
    assertEquals(small.serialized().length, small.size());
    assertTrue(small.size() <= Transaction.MAX_SERIALIZED_LENGTH);

    final var big = Transaction.createTx(feePayer,
        Instruction.createInstruction(PROGRAM, List.of(ACCOUNT_A), new byte[Transaction.MAX_SERIALIZED_LENGTH]));
    assertTrue(big.exceedsSizeLimit());
    assertEquals(big.serialized().length, big.size());
    assertTrue(big.size() > Transaction.MAX_SERIALIZED_LENGTH);
  }
}
