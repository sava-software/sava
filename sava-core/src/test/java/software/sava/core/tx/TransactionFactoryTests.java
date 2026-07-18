package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.encoding.ByteUtil;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;

/// Every `createTx` overload must be a pure convenience over the canonical
/// (instructions, serializedInstructionLength, sortedAccounts[, table]) factories:
/// serialized-byte equality against the canonical construction is the invariant.
/// Mutation testing (2026-07-18 tx baseline) showed most overloads had no coverage.
/// All inputs are fixed byte-pattern keys — no signing, fully deterministic.
final class TransactionFactoryTests {

  private static byte[] keyBytes(final int fill) {
    final byte[] key = new byte[PUBLIC_KEY_LENGTH];
    Arrays.fill(key, (byte) fill);
    return key;
  }

  private static PublicKey key(final int fill) {
    return PublicKey.createPubKey(keyBytes(fill));
  }

  private static final PublicKey FEE_PAYER = key(10);
  private static final PublicKey PROGRAM = key(11);
  private static final PublicKey WRITE_ACCOUNT = key(12);
  private static final PublicKey READ_ACCOUNT = key(13);
  private static final PublicKey ALT_ADDRESS = key(30);
  private static final PublicKey ALT_ADDRESS_2 = key(31);
  // key fills chosen so table-resolvable accounts sort ahead of message accounts within
  // each meta rank, forcing the account-compaction paths (single swap and arraycopy)
  private static final PublicKey RO_SIGNER = key(20);
  private static final PublicKey TABLE_WRITE = key(2);
  private static final PublicKey TABLE_WRITE_2 = key(3);
  private static final PublicKey TABLE_READ_1 = key(14);
  private static final PublicKey TABLE_READ_2 = key(15);
  private static final PublicKey MSG_READ = key(16);

  private static Instruction instruction() {
    return Instruction.createInstruction(
        PROGRAM,
        List.of(AccountMeta.createWrite(WRITE_ACCOUNT), AccountMeta.createRead(READ_ACCOUNT)),
        new byte[]{1, 2, 3}
    );
  }

  /// An instruction carrying its own fee-payer meta, for the overloads without one.
  private static Instruction instructionWithFeePayer() {
    return Instruction.createInstruction(
        PROGRAM,
        List.of(AccountMeta.createFeePayer(FEE_PAYER), AccountMeta.createWrite(WRITE_ACCOUNT)),
        new byte[]{1, 2, 3}
    );
  }

  /// Minimal active lookup table: 56-byte meta with `deactivationSlot = u64::MAX`,
  /// followed by the addresses.
  private static AddressLookupTable alt(final PublicKey tableAddress, final PublicKey... addresses) {
    final byte[] data = new byte[AddressLookupTable.LOOKUP_TABLE_META_SIZE + addresses.length * PUBLIC_KEY_LENGTH];
    ByteUtil.putInt32LE(data, AddressLookupTable.DISCRIMINATOR_OFFSET, 1);
    ByteUtil.putInt64LE(data, AddressLookupTable.DEACTIVATION_SLOT_OFFSET, -1L);
    int o = AddressLookupTable.LOOKUP_TABLE_META_SIZE;
    for (final var address : addresses) {
      o += address.write(data, o);
    }
    return AddressLookupTable.read(tableAddress, data);
  }

  @Test
  void legacyOverloadsMatchCanonicalConstruction() {
    final var ix = instruction();
    final var feePayerMeta = AccountMeta.createFeePayer(FEE_PAYER);
    final byte[] canonical = Transaction.createTx(feePayerMeta, List.of(ix)).serialized();

    assertArrayEquals(canonical, Transaction.createTx(FEE_PAYER, List.of(ix)).serialized());
    assertArrayEquals(canonical, Transaction.createTx(feePayerMeta, ix).serialized());
    assertArrayEquals(canonical, Transaction.createTx(FEE_PAYER, ix).serialized());

    final var withFeePayer = instructionWithFeePayer();
    final byte[] noPayerCanonical = Transaction.createTx((AccountMeta) null, List.of(withFeePayer)).serialized();
    assertArrayEquals(noPayerCanonical, Transaction.createTx(List.of(withFeePayer)).serialized());
    assertArrayEquals(noPayerCanonical, Transaction.createTx(withFeePayer).serialized());
  }

  @Test
  void pushOverloadsPrependInstructions() {
    final var first = instruction();
    final var second = Instruction.createInstruction(
        PROGRAM, List.of(AccountMeta.createWrite(WRITE_ACCOUNT)), new byte[]{4, 5});

    final byte[] canonical = Transaction.createTx(FEE_PAYER, List.of(first, second)).serialized();
    assertArrayEquals(canonical, Transaction.createTx(FEE_PAYER, List.of(second), first).serialized());

    final var pushWithFeePayer = instructionWithFeePayer();
    assertArrayEquals(
        Transaction.createTx((PublicKey) null, List.of(second, pushWithFeePayer)).serialized(),
        Transaction.createTx(List.of(pushWithFeePayer), second).serialized()
    );

    // more pushes than instructions: the combined-capacity arithmetic must not go negative
    assertArrayEquals(
        Transaction.createTx(FEE_PAYER, List.of(first, second)).serialized(),
        Transaction.createTx(FEE_PAYER, List.of(), first, second).serialized()
    );
  }

  @Test
  void sortedAccountOverloadsMatchCanonicalConstruction() {
    final var ix = instruction();
    final var feePayerMeta = AccountMeta.createFeePayer(FEE_PAYER);
    final byte[] canonical = Transaction.createTx(feePayerMeta, List.of(ix)).serialized();

    final var legacyAccounts = HashMap.<PublicKey, AccountMeta>newHashMap(Transaction.MAX_ACCOUNTS);
    final int ixLength = TransactionRecord.mergeAccounts(feePayerMeta, legacyAccounts, List.of(ix));
    final var sortedLegacy = TransactionRecord.sortLegacyAccounts(legacyAccounts);
    assertArrayEquals(canonical, Transaction.createTx(List.of(ix), sortedLegacy).serialized());
    assertArrayEquals(canonical, Transaction.createTx(ix, sortedLegacy).serialized());
    assertThrows(IllegalArgumentException.class, () -> Transaction.createTx(List.of(), sortedLegacy));

    final var v0Accounts = HashMap.<PublicKey, AccountMeta>newHashMap(Transaction.MAX_ACCOUNTS);
    final int v0IxLength = TransactionRecord.mergeAccounts(feePayerMeta, v0Accounts, List.of(ix));
    final var mapCopy = new HashMap<>(v0Accounts);
    assertArrayEquals(
        Transaction.createTx(List.of(ix), v0IxLength, TransactionRecord.sortV0Accounts(v0Accounts)).serialized(),
        Transaction.createTx(new Instruction[]{ix}, v0IxLength, mapCopy).serialized()
    );

    final var nullTableAccounts = new HashMap<>(mapCopy);
    assertArrayEquals(
        canonical,
        Transaction.createTx(List.of(ix), ixLength, nullTableAccounts, (AddressLookupTable) null).serialized()
    );
    assertArrayEquals(
        canonical,
        Transaction.createTx(List.of(ix), ixLength, sortedLegacy, (AddressLookupTable) null).serialized()
    );
  }

  @Test
  void lookupTableOverloadsMatchCanonicalConstruction() {
    final var ix = instruction();
    final var feePayerMeta = AccountMeta.createFeePayer(FEE_PAYER);
    final var table = alt(ALT_ADDRESS, key(21), READ_ACCOUNT, key(22));
    final byte[] legacyCanonical = Transaction.createTx(feePayerMeta, List.of(ix)).serialized();

    // a null table falls back to the legacy factories
    assertArrayEquals(
        legacyCanonical,
        Transaction.createTx(feePayerMeta, List.of(ix), (AddressLookupTable) null).serialized()
    );

    final byte[] canonical = Transaction.createTx(feePayerMeta, List.of(ix), table).serialized();
    assertArrayEquals(canonical, Transaction.createTx(FEE_PAYER, List.of(ix), table).serialized());
    assertArrayEquals(canonical, Transaction.createTx(feePayerMeta, List.of(ix), table, null).serialized());
    assertArrayEquals(
        canonical,
        Transaction.createTx(feePayerMeta, List.of(ix), table, new LookupTableAccountMeta[0]).serialized()
    );

    final var withFeePayer = instructionWithFeePayer();
    assertArrayEquals(
        Transaction.createTx((AccountMeta) null, List.of(withFeePayer), table).serialized(),
        Transaction.createTx(List.of(withFeePayer), table).serialized()
    );

    final var accounts = HashMap.<PublicKey, AccountMeta>newHashMap(Transaction.MAX_ACCOUNTS);
    final int ixLength = TransactionRecord.mergeAccounts(feePayerMeta, accounts, List.of(ix));
    assertArrayEquals(canonical, Transaction.createTx(List.of(ix), ixLength, accounts, table).serialized());
  }

  @Test
  void tableMetaOverloadsRouteAndReject() {
    final var ix = instruction();
    final var feePayerMeta = AccountMeta.createFeePayer(FEE_PAYER);
    final var table = alt(ALT_ADDRESS, key(21), READ_ACCOUNT, key(22));
    final var tableMetas = new LookupTableAccountMeta[]{LookupTableAccountMeta.createMeta(table)};

    assertThrows(
        IllegalStateException.class,
        () -> Transaction.createTx(feePayerMeta, List.of(ix), table, tableMetas),
        "a single table and table metas are mutually exclusive"
    );
    assertArrayEquals(
        Transaction.createTx(feePayerMeta, List.of(ix), tableMetas).serialized(),
        Transaction.createTx(feePayerMeta, List.of(ix), (AddressLookupTable) null, tableMetas).serialized()
    );
  }

  @Test
  void lookupTableTransactionRoundTrips() {
    final var ix = instruction();
    final var table = alt(ALT_ADDRESS, key(21), READ_ACCOUNT, key(22));
    final var tx = Transaction.createTx(AccountMeta.createFeePayer(FEE_PAYER), List.of(ix), table);

    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    assertTrue(skeleton.isVersioned());
    assertArrayEquals(new PublicKey[]{ALT_ADDRESS}, skeleton.lookupTableAccounts());
    // the read-only non-invoked account resolves through the table instead of the message
    assertEquals(1, skeleton.numIndexedAccounts());
    assertEquals(skeleton.numAccounts() - 1, skeleton.numIncludedAccounts());

    final var rebuilt = skeleton.createTransaction(table);
    assertArrayEquals(tx.serialized(), rebuilt.serialized());

    final var accounts = skeleton.parseAccounts(table);
    assertTrue(Arrays.stream(accounts).anyMatch(meta -> meta.publicKey().equals(READ_ACCOUNT)),
        "table-resolved account must surface when parsing with the table");
  }

  /// A shape wide enough to drive every account-classification branch: a read-only
  /// signer, message-only write and read accounts, a table-resolvable write and two
  /// table-resolvable reads, and the invoked program present in the table.
  private static Instruction richInstruction() {
    return Instruction.createInstruction(
        PROGRAM,
        List.of(
            AccountMeta.createReadOnlySigner(RO_SIGNER),
            AccountMeta.createWrite(WRITE_ACCOUNT),
            AccountMeta.createRead(MSG_READ),
            AccountMeta.createWrite(TABLE_WRITE),
            AccountMeta.createRead(TABLE_READ_1),
            AccountMeta.createRead(TABLE_READ_2)
        ),
        new byte[]{7}
    );
  }

  private static void assertRichShapeHeader(final TransactionSkeleton skeleton) {
    assertTrue(skeleton.isVersioned());
    assertEquals(FEE_PAYER, skeleton.feePayer());
    assertEquals(2, skeleton.numSignatures());
    assertEquals(1, skeleton.numReadonlySignedAccounts());
    // the invoked program stays in the message despite being table-resolvable,
    // and counts as read-only unsigned alongside the message-only read
    assertEquals(2, skeleton.numReadonlyUnsignedAccounts());
    assertEquals(3, skeleton.numIndexedAccounts());
  }

  @Test
  void singleTableCompactionAndHeaderCounts() {
    final var ix = richInstruction();
    final var table = alt(ALT_ADDRESS, key(21), TABLE_WRITE, TABLE_READ_1, TABLE_READ_2, PROGRAM);
    final var tx = Transaction.createTx(AccountMeta.createFeePayer(FEE_PAYER), List.of(ix), table);
    assertEquals(FEE_PAYER, tx.feePayer().publicKey());

    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    assertRichShapeHeader(skeleton);
    assertArrayEquals(new PublicKey[]{ALT_ADDRESS}, skeleton.lookupTableAccounts());
    assertArrayEquals(tx.serialized(), skeleton.createTransaction(table).serialized());

    final var parsed = skeleton.parseAccounts(table);
    for (final var expected : List.of(
        FEE_PAYER, RO_SIGNER, WRITE_ACCOUNT, MSG_READ, TABLE_WRITE, TABLE_READ_1, TABLE_READ_2, PROGRAM)) {
      assertTrue(
          Arrays.stream(parsed).anyMatch(meta -> meta.publicKey().equals(expected)),
          "missing account " + expected.toBase58()
      );
    }
  }

  @Test
  void multiTableCompactionAndHeaderCounts() {
    final var ix = richInstruction();
    final var table1 = alt(ALT_ADDRESS, key(21), TABLE_WRITE, TABLE_READ_1);
    final var table2 = alt(ALT_ADDRESS_2, TABLE_READ_2, key(22), PROGRAM);
    final var tableMetas = new LookupTableAccountMeta[]{
        LookupTableAccountMeta.createMeta(table1),
        LookupTableAccountMeta.createMeta(table2)
    };
    final var tx = Transaction.createTx(AccountMeta.createFeePayer(FEE_PAYER), List.of(ix), tableMetas);
    assertEquals(FEE_PAYER, tx.feePayer().publicKey());

    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    assertRichShapeHeader(skeleton);
    assertArrayEquals(new PublicKey[]{ALT_ADDRESS, ALT_ADDRESS_2}, skeleton.lookupTableAccounts());
    assertArrayEquals(tx.serialized(), skeleton.createTransaction(tableMetas).serialized());
  }

  @Test
  void tableMetaStaticRoutes() {
    final var ix = instruction();
    final var withFeePayer = instructionWithFeePayer();
    final var feePayerMeta = AccountMeta.createFeePayer(FEE_PAYER);
    final var table1 = alt(ALT_ADDRESS, key(21), TABLE_WRITE, TABLE_READ_1);
    final var table2 = alt(ALT_ADDRESS_2, TABLE_READ_2, key(22), PROGRAM);

    // empty metas fall all the way back to the plain factory
    final var emptyMetaAccounts = HashMap.<PublicKey, AccountMeta>newHashMap(Transaction.MAX_ACCOUNTS);
    final int emptyMetaIxLength = TransactionRecord.mergeAccounts(null, emptyMetaAccounts, List.of(withFeePayer));
    assertArrayEquals(
        Transaction.createTx(List.of(withFeePayer)).serialized(),
        Transaction.createTx(
            List.of(withFeePayer),
            emptyMetaIxLength,
            TransactionRecord.sortV0Accounts(emptyMetaAccounts),
            new LookupTableAccountMeta[0]
        ).serialized()
    );

    // a single meta routes through the single-table factory; sorted arrays are built
    // per call because the v0 factories compact them in place
    final var singleTableAccounts = HashMap.<PublicKey, AccountMeta>newHashMap(Transaction.MAX_ACCOUNTS);
    final int ixLength = TransactionRecord.mergeAccounts(feePayerMeta, singleTableAccounts, List.of(ix));
    final var singleMetaAccounts = new HashMap<>(singleTableAccounts);
    assertArrayEquals(
        Transaction.createTx(
            List.of(ix), ixLength, TransactionRecord.sortV0Accounts(singleTableAccounts), table1).serialized(),
        Transaction.createTx(
            List.of(ix), ixLength, TransactionRecord.sortV0Accounts(singleMetaAccounts),
            new LookupTableAccountMeta[]{LookupTableAccountMeta.createMeta(table1)}).serialized()
    );

    // null fee payers route identically through the PublicKey and AccountMeta overloads
    assertArrayEquals(
        Transaction.createTx((AccountMeta) null, List.of(withFeePayer), table1).serialized(),
        Transaction.createTx((PublicKey) null, List.of(withFeePayer), table1).serialized()
    );
    assertArrayEquals(
        Transaction.createTx(
            (AccountMeta) null,
            List.of(withFeePayer),
            new LookupTableAccountMeta[]{
                LookupTableAccountMeta.createMeta(table1),
                LookupTableAccountMeta.createMeta(table2)
            }).serialized(),
        Transaction.createTx(
            (PublicKey) null,
            List.of(withFeePayer),
            new LookupTableAccountMeta[]{
                LookupTableAccountMeta.createMeta(table1),
                LookupTableAccountMeta.createMeta(table2)
            }).serialized()
    );
  }

  @Test
  void mapVariantMetaRoutes() {
    final var ix = instruction();
    final var feePayerMeta = AccountMeta.createFeePayer(FEE_PAYER);
    final byte[] legacyCanonical = Transaction.createTx(feePayerMeta, List.of(ix)).serialized();
    final var table1 = alt(ALT_ADDRESS, key(21), READ_ACCOUNT);
    final var table2 = alt(ALT_ADDRESS_2, TABLE_READ_2, key(22));

    final var nullMetaAccounts = HashMap.<PublicKey, AccountMeta>newHashMap(Transaction.MAX_ACCOUNTS);
    final int ixLength = TransactionRecord.mergeAccounts(feePayerMeta, nullMetaAccounts, List.of(ix));
    assertArrayEquals(
        legacyCanonical,
        Transaction.createTx(
            List.of(ix), ixLength, new HashMap<>(nullMetaAccounts), (LookupTableAccountMeta[]) null).serialized()
    );
    assertArrayEquals(
        legacyCanonical,
        Transaction.createTx(
            List.of(ix), ixLength, new HashMap<>(nullMetaAccounts), new LookupTableAccountMeta[0]).serialized()
    );

    assertArrayEquals(
        Transaction.createTx(
            feePayerMeta,
            List.of(ix),
            new LookupTableAccountMeta[]{
                LookupTableAccountMeta.createMeta(table1),
                LookupTableAccountMeta.createMeta(table2)
            }).serialized(),
        Transaction.createTx(
            List.of(ix),
            ixLength,
            new HashMap<>(nullMetaAccounts),
            new LookupTableAccountMeta[]{
                LookupTableAccountMeta.createMeta(table1),
                LookupTableAccountMeta.createMeta(table2)
            }).serialized()
    );
  }

  @Test
  void legacyFactoryCapturesTheFeePayer() {
    final var ix = Instruction.createInstruction(
        PROGRAM,
        List.of(AccountMeta.createReadOnlySigner(RO_SIGNER), AccountMeta.createWrite(WRITE_ACCOUNT)),
        new byte[]{1}
    );
    final var tx = Transaction.createTx(AccountMeta.createFeePayer(FEE_PAYER), List.of(ix));
    assertEquals(FEE_PAYER, tx.feePayer().publicKey());
    assertEquals(FEE_PAYER, TransactionSkeleton.deserializeSkeleton(tx.serialized()).feePayer());
  }

  /// All writable non-signers are table-resolvable, so the invoked program — the next
  /// meta rank — displaces across both of them at once: the `len > 1` arraycopy
  /// compaction path, unreachable from shapes with a single indexed account per rank.
  private static Instruction displacementInstruction() {
    return Instruction.createInstruction(
        PROGRAM,
        List.of(
            AccountMeta.createWrite(TABLE_WRITE),
            AccountMeta.createWrite(TABLE_WRITE_2),
            AccountMeta.createRead(MSG_READ)
        ),
        new byte[]{9}
    );
  }

  @Test
  void singleTableDisplacementAcrossRanks() {
    final var table = alt(ALT_ADDRESS, TABLE_WRITE, TABLE_READ_1, TABLE_WRITE_2);
    final var tx = Transaction.createTx(
        AccountMeta.createFeePayer(FEE_PAYER), List.of(displacementInstruction()), table);

    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    assertTrue(skeleton.isVersioned());
    assertEquals(2, skeleton.numIndexedAccounts());
    assertEquals(skeleton.numAccounts() - 2, skeleton.numIncludedAccounts());
    assertArrayEquals(tx.serialized(), skeleton.createTransaction(table).serialized());

    final var parsed = skeleton.parseAccounts(table);
    for (final var expected : List.of(FEE_PAYER, PROGRAM, TABLE_WRITE, TABLE_WRITE_2, MSG_READ)) {
      assertTrue(
          Arrays.stream(parsed).anyMatch(meta -> meta.publicKey().equals(expected)),
          "missing account " + expected.toBase58()
      );
    }
  }

  @Test
  void multiTableDisplacementAcrossRanks() {
    final var table1 = alt(ALT_ADDRESS, key(21), TABLE_WRITE);
    final var table2 = alt(ALT_ADDRESS_2, TABLE_WRITE_2, key(22));
    final var tableMetas = new LookupTableAccountMeta[]{
        LookupTableAccountMeta.createMeta(table1),
        LookupTableAccountMeta.createMeta(table2)
    };
    final var tx = Transaction.createTx(
        AccountMeta.createFeePayer(FEE_PAYER), List.of(displacementInstruction()), tableMetas);

    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    assertTrue(skeleton.isVersioned());
    assertEquals(2, skeleton.numIndexedAccounts());
    assertArrayEquals(new PublicKey[]{ALT_ADDRESS, ALT_ADDRESS_2}, skeleton.lookupTableAccounts());
    assertArrayEquals(tx.serialized(), skeleton.createTransaction(tableMetas).serialized());
  }
}
