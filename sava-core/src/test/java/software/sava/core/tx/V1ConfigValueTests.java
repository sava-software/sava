package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.LookupTableAccountMeta;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.tx.TxBuilderImpl.V1_INSTRUCTION_HEADER_LENGTH;
import static software.sava.core.tx.V1TransactionSkeleton.ACCOUNT_DATA_SIZE_LIMIT_MASK;
import static software.sava.core.tx.V1TransactionSkeleton.COMPUTE_UNIT_LIMIT_MASK;
import static software.sava.core.tx.V1TransactionSkeleton.HEAP_SIZE_MASK;
import static software.sava.core.tx.V1TransactionSkeleton.PRIORITY_FEE_MASK;

/// Covers the v1 ConfigValue block and the invoked-program indexes derived from the fixed-width
/// InstructionHeaders.
///
/// Two families live here:
///
///  1. `V1TransactionSkeleton#deserialize`'s invoked-index loop, whose only observable effect is
///     which read-only unsigned accounts `parseAccounts(List, List)` types as invoked. Every
///     assertion below therefore reads a program's account meta back through that view rather than
///     through an instruction's `programId()`, which `invokedProgramAccount(...)` marks invoked
///     unconditionally and so cannot distinguish a wrong index array from a right one.
///  2. The ConfigValue carry-over in `V1Transaction#createTransaction(List)`, exercised one
///     TransactionConfigMask bit at a time — the existing suite only covers all four bits set and
///     all four cleared, which cannot tell the four carry-over statements apart.
///
/// Every key is derived from a fixed private key so the serialized account order, and therefore
/// every program id index asserted here, is reproducible run to run.
final class V1ConfigValueTests {

  private static final int HEAP_SIZE = 64 * 1_024;
  private static final int COMPUTE_UNIT_LIMIT = 200_000;
  private static final int ACCOUNT_DATA_SIZE_LIMIT = 65_536;
  private static final long PRIORITY_FEE_LAMPORTS = 7_777L;

  private static PublicKey key(final int seed) {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    Arrays.fill(privateKey, (byte) seed);
    return Signer.createFromPrivateKey(privateKey).publicKey();
  }

  private static byte[] blockHash() {
    final byte[] blockHash = new byte[Transaction.BLOCK_HASH_LENGTH];
    for (int b = 0; b < blockHash.length; ++b) {
      blockHash[b] = (byte) (b + 41);
    }
    return blockHash;
  }

  /// An account free instruction whose single data byte identifies it.
  private static Instruction markerIx(final PublicKey program, final int marker) {
    return Instruction.createInstruction(program, List.of(), new byte[]{(byte) marker});
  }

  private static Instruction markerIx(final int marker) {
    return markerIx(SolanaAccounts.MAIN_NET.systemProgram(), marker);
  }

  /// A v1 transaction over a fee payer and two account free instructions with distinct programs.
  /// Neither program signs or is written, so both land in the read-only unsigned region — the only
  /// region `parseVersionedIncludedAccounts` consults the invoked indexes for.
  private static byte[] twoProgramV1(final PublicKey feePayer,
                                     final PublicKey firstProgram,
                                     final PublicKey secondProgram) {
    final var tx = TxBuilder.createBuilder()
        .feePayer(feePayer)
        .addInstruction(markerIx(firstProgram, 1))
        .addInstruction(markerIx(secondProgram, 2))
        .createTransaction();
    assertInstanceOf(V1Transaction.class, tx);
    tx.setRecentBlockHash(blockHash());
    return tx.serialized();
  }

  /// The program id index of an instruction, read straight off its fixed-width InstructionHeader.
  private static int programIdIndex(final byte[] data, final int instructionsOffset, final int instructionIndex) {
    return data[instructionsOffset + (instructionIndex * V1_INSTRUCTION_HEADER_LENGTH)] & 0xFF;
  }

  /// Pins `V1TransactionSkeleton#deserialize`'s invoked-index loop:
  /// `for (int i = 0; i < numInstructions; ++i)` and
  /// `invokedIndexes[i] = data[instructionsOffset + (i * V1_INSTRUCTION_HEADER_LENGTH)] & 0xFF;`.
  ///
  /// Both instruction programs are read-only unsigned accounts, so both indexes must survive into
  /// `invokedIndexes` for `parseAccounts(List, List)` to type them as invoked. That fails if:
  ///
  ///  - the loop never runs, leaving `invokedIndexes` all zeros — neither index is 0, since a v1
  ///    instruction program may not be the fee payer;
  ///  - `i * V1_INSTRUCTION_HEADER_LENGTH` becomes `i / V1_INSTRUCTION_HEADER_LENGTH`, which is 0
  ///    for both i = 0 and i = 1, so the second instruction's program index is replaced by the
  ///    first's;
  ///  - `instructionsOffset + ...` becomes `instructionsOffset - ...`, which reads the low byte of
  ///    the last ConfigValue instead — asserted below to be 0, and again neither index is 0;
  ///  - `& 0xFF` becomes `| 0xFF`, which yields 255 for every header, an index no account has.
  @Test
  void invokedIndexesAreReadFromEveryInstructionHeader() {
    final var feePayer = key(51);
    final byte[] data = twoProgramV1(feePayer, key(52), key(53));

    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    assertInstanceOf(V1TransactionSkeleton.class, skeleton);
    assertEquals(2, skeleton.numInstructions());
    // The fee payer plus the two programs, of which only the fee payer signs.
    assertEquals(3, skeleton.numIncludedAccounts());
    assertEquals(1, skeleton.numSignatures());
    assertEquals(2, skeleton.numReadonlyUnsignedAccounts());

    final int instructionsOffset = skeleton.instructionsOffset();
    final int firstProgramIndex = programIdIndex(data, instructionsOffset, 0);
    final int secondProgramIndex = programIdIndex(data, instructionsOffset, 1);
    assertNotEquals(firstProgramIndex, secondProgramIndex);
    // A v1 instruction program may not be the fee payer, so neither index can be 0.
    assertTrue(firstProgramIndex > 0);
    assertTrue(secondProgramIndex > 0);

    // Negative control for the `instructionsOffset - (i * 4)` mutation: the four bytes preceding the
    // header block are the last ConfigValue, the 64MiB accounts data size limit, whose low byte is 0.
    assertEquals(0, data[instructionsOffset - V1_INSTRUCTION_HEADER_LENGTH] & 0xFF);
    assertEquals(
        COMPUTE_UNIT_LIMIT_MASK | ACCOUNT_DATA_SIZE_LIMIT_MASK,
        ((V1TransactionSkeleton) skeleton).configMask()
    );
    assertEquals(TxBuilderImpl.MAX_ACCOUNT_DATA_SIZE_LIMIT, skeleton.accountDataSizeLimit());

    // parseAccounts(List, List) is the only account view that consults the invoked indexes.
    final var accounts = skeleton.parseAccounts(List.<PublicKey>of(), List.<PublicKey>of());
    assertEquals(3, accounts.length);
    assertTrue(accounts[0].feePayer());
    assertEquals(feePayer, accounts[0].publicKey());

    assertTrue(
        accounts[firstProgramIndex].invoked(),
        "the first instruction's program must be typed invoked"
    );
    assertTrue(
        accounts[secondProgramIndex].invoked(),
        "the second instruction's program must be typed invoked"
    );

    // Neither program is writable, so `invoked` is the whole of what the index array contributes.
    assertFalse(accounts[firstProgramIndex].write());
    assertFalse(accounts[secondProgramIndex].write());
  }

  /// Pins `Arrays.sort(invokedIndexes)` in `V1TransactionSkeleton#deserialize`.
  ///
  /// `parseVersionedIncludedAccounts` resolves invoked accounts with
  /// `Arrays.binarySearch(invokedIndexes, a)`, which requires a sorted array. The instruction order
  /// is chosen below so the indexes are read descending: dropping the sort leaves
  /// `{higher, lower}`, and a binary search for `lower` compares it against `higher` at the midpoint
  /// and reports it absent, demoting an invoked program to a plain read-only account.
  @Test
  void invokedIndexesAreSortedForTheBinarySearch() {
    final var feePayer = key(61);
    final var programA = key(62);
    final var programB = key(63);

    // Read the indexes descending, whichever way round the two programs sort into the address array.
    byte[] data = twoProgramV1(feePayer, programA, programB);
    var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    int firstProgramIndex = programIdIndex(data, skeleton.instructionsOffset(), 0);
    int secondProgramIndex = programIdIndex(data, skeleton.instructionsOffset(), 1);
    if (firstProgramIndex < secondProgramIndex) {
      data = twoProgramV1(feePayer, programB, programA);
      skeleton = TransactionSkeleton.deserializeSkeleton(data);
      firstProgramIndex = programIdIndex(data, skeleton.instructionsOffset(), 0);
      secondProgramIndex = programIdIndex(data, skeleton.instructionsOffset(), 1);
    }
    assertTrue(
        firstProgramIndex > secondProgramIndex,
        "the invoked indexes must be read in descending order for the sort to matter"
    );

    // Negative control, executed rather than argued: the array as the loop reads it hides the
    // second program from a binary search, and sorting it is what recovers it.
    final int[] asRead = {firstProgramIndex, secondProgramIndex};
    assertTrue(Arrays.binarySearch(asRead, secondProgramIndex) < 0);
    final int[] sorted = asRead.clone();
    Arrays.sort(sorted);
    assertTrue(Arrays.binarySearch(sorted, secondProgramIndex) >= 0);
    assertTrue(Arrays.binarySearch(sorted, firstProgramIndex) >= 0);

    final var accounts = skeleton.parseAccounts(List.<PublicKey>of(), List.<PublicKey>of());
    assertEquals(3, accounts.length);
    assertTrue(
        accounts[secondProgramIndex].invoked(),
        "the lower program index must still be found once the invoked indexes are sorted"
    );
    assertTrue(accounts[firstProgramIndex].invoked());
  }

  /// Pins the three `V1TransactionSkeleton` lookup-table `createTransaction` overrides and its
  /// `parseAccounts(Map)` override, all of which discard the lookup metadata a v1 message cannot
  /// carry and delegate to the plain view. The existing suite only reaches them over payloads whose
  /// signature layout is rejected, so the delegating `return` statements themselves never ran.
  @Test
  void lookupTableOverloadsDelegateToTheV1Views() {
    final var feePayer = key(71);
    final byte[] data = twoProgramV1(feePayer, key(72), key(73));

    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    final var accounts = skeleton.parseAccounts();
    final var instructions = List.of(skeleton.parseInstructions(accounts));
    final var noTables = new LookupTableAccountMeta[0];

    // parseAccounts(Map) returns the same accounts as the no-argument view, not null and not an
    // empty array.
    final var mapAccounts = skeleton.parseAccounts(Map.<PublicKey, AddressLookupTable>of());
    assertNotNull(mapAccounts);
    assertNotSame(accounts, mapAccounts);
    assertArrayEquals(accounts, mapAccounts);
    assertEquals(3, mapAccounts.length);
    // The Stream overload funnels through the same Map overload.
    assertArrayEquals(accounts, skeleton.parseAccounts(Arrays.stream(noTables).map(LookupTableAccountMeta::lookupTable)));

    final List<Transaction> created = List.of(
        skeleton.createTransaction(noTables),
        skeleton.createTransaction(instructions, (AddressLookupTable) null),
        skeleton.createTransaction(instructions, noTables)
    );
    for (final var transaction : created) {
      assertNotNull(transaction);
      assertInstanceOf(V1Transaction.class, transaction);
      // A v1 transaction is rebuilt over the very bytes it was parsed from.
      assertSame(data, transaction.serialized());
      assertEquals(1, transaction.version());
      assertEquals(2, transaction.numInstructions());
      assertEquals(feePayer, transaction.feePayer().publicKey());
      assertArrayEquals(blockHash(), transaction.recentBlockHash());
      // The discarded lookup metadata is reported as absent rather than echoed back.
      assertNull(transaction.lookupTable());
      assertEquals(0, transaction.tableAccountMetas().length);
    }
  }

  private static Transaction v1With(final PublicKey feePayer,
                                    final long priorityFeeLamports,
                                    final int computeUnitLimit,
                                    final int accountDataSizeLimit,
                                    final int heapSize) {
    final var tx = TxBuilder.createBuilder()
        .feePayer(feePayer)
        .addInstruction(markerIx(1))
        .priorityFeeLamports(priorityFeeLamports)
        .computeUnitLimit(computeUnitLimit)
        .accountDataSizeLimit(accountDataSizeLimit)
        .heapSize(heapSize)
        .createTransaction();
    tx.setRecentBlockHash(blockHash());
    return tx;
  }

  /// Asserts that appending an instruction to a v1 transaction carrying exactly one ConfigValue
  /// reproduces that ConfigValue, and only that one, on the derived transaction.
  private static void assertCarriesOnly(final int expectedMask,
                                        final long priorityFeeLamports,
                                        final int computeUnitLimit,
                                        final int accountDataSizeLimit,
                                        final int heapSize) {
    final var feePayer = key(81);
    final var tx = v1With(feePayer, priorityFeeLamports, computeUnitLimit, accountDataSizeLimit, heapSize);

    final var source = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    assertEquals(expectedMask, ((V1TransactionSkeleton) source).configMask());

    final var derived = tx.appendIx(markerIx(2));
    assertNotSame(tx, derived);
    assertEquals(2, derived.numInstructions());
    assertArrayEquals(blockHash(), derived.recentBlockHash());

    final var skeleton = TransactionSkeleton.deserializeSkeleton(derived.serialized());
    // No ConfigValue slot is gained or lost, so the derived transaction is updatable in exactly the
    // same places the source was.
    assertEquals(expectedMask, ((V1TransactionSkeleton) skeleton).configMask());
    assertEquals(priorityFeeLamports, skeleton.priorityFeeLamports());
    assertEquals(computeUnitLimit, skeleton.computeUnitLimit());
    assertEquals(accountDataSizeLimit, skeleton.accountDataSizeLimit());
    assertEquals(heapSize, skeleton.heapSize());
  }

  /// Covers `V1Transaction#createTransaction(List)`'s four ConfigValue carry-over statements one bit
  /// at a time. With every other bit clear, each case pins exactly one of them: carrying the wrong
  /// value, or carrying a value whose mask bit the source did not set, changes the derived
  /// TransactionConfigMask as well as the value read back.
  ///
  /// The compute unit limit and the accounts data size limit are the two the builder defaults to a
  /// non-zero runtime maximum, so their `offset < 0 ? 0 : ...` carry-over is what keeps a deliberate
  /// clear cleared rather than silently restoring 1.4M units and 64MiB.
  @Test
  void derivedTransactionCarriesEachConfigValueBitIndividually() {
    assertCarriesOnly(PRIORITY_FEE_MASK, PRIORITY_FEE_LAMPORTS, 0, 0, 0);
    assertCarriesOnly(COMPUTE_UNIT_LIMIT_MASK, 0L, COMPUTE_UNIT_LIMIT, 0, 0);
    assertCarriesOnly(ACCOUNT_DATA_SIZE_LIMIT_MASK, 0L, 0, ACCOUNT_DATA_SIZE_LIMIT, 0);
    assertCarriesOnly(HEAP_SIZE_MASK, 0L, 0, 0, HEAP_SIZE);
  }
}
