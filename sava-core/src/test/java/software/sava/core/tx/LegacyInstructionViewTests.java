package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.programs.Discriminator;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.meta.AccountMeta.createRead;
import static software.sava.core.accounts.meta.AccountMeta.createWrite;
import static software.sava.core.encoding.CompactU16Encoding.decode;
import static software.sava.core.encoding.CompactU16Encoding.getByteLen;
import static software.sava.core.programs.Discriminator.toDiscriminator;

/// The legacy counterparts of [V1InstructionViewTests]: a legacy message carries no address table
/// lookups, so every table aware account view must fall back to the message's own accounts, and an
/// instruction account index outside those accounts must surface as a null meta rather than an
/// array read.
///
/// All inputs are fixed byte-pattern keys — no signing, fully deterministic.
final class LegacyInstructionViewTests {

  private static final byte[] BLOCK_HASH = blockHash();

  private static byte[] blockHash() {
    final byte[] blockHash = new byte[Transaction.BLOCK_HASH_LENGTH];
    for (int b = 0; b < blockHash.length; ++b) {
      blockHash[b] = (byte) (b + 41);
    }
    return blockHash;
  }

  private static PublicKey key(final int fill) {
    final byte[] key = new byte[PUBLIC_KEY_LENGTH];
    Arrays.fill(key, (byte) fill);
    return PublicKey.createPubKey(key);
  }

  private static final PublicKey FEE_PAYER = key(40);
  private static final PublicKey PROGRAM = key(41);
  private static final PublicKey WRITE_ACCOUNT = key(42);
  private static final PublicKey READ_ACCOUNT = key(43);
  private static final PublicKey ALT_ADDRESS = key(44);

  private static Instruction ix(final PublicKey program, final List<AccountMeta> accounts, final int... data) {
    final byte[] ixData = new byte[data.length];
    for (int i = 0; i < data.length; ++i) {
      ixData[i] = (byte) data[i];
    }
    return Instruction.createInstruction(program, accounts, ixData);
  }

  private static byte[] serializedLegacy(final Instruction... instructions) {
    final var tx = Transaction.createTx(FEE_PAYER, List.of(instructions));
    tx.setRecentBlockHash(BLOCK_HASH);
    final byte[] serialized = tx.serialized();
    assertTrue(TransactionSkeleton.deserializeSkeleton(serialized).isLegacy());
    return serialized;
  }

  /// Minimal active lookup table: 56-byte meta with `deactivationSlot = u64::MAX`, followed by the
  /// addresses.
  private static AddressLookupTable alt(final PublicKey tableAddress, final PublicKey... addresses) {
    final byte[] data = new byte[AddressLookupTable.LOOKUP_TABLE_META_SIZE + (addresses.length * PUBLIC_KEY_LENGTH)];
    ByteUtil.putInt32LE(data, AddressLookupTable.DISCRIMINATOR_OFFSET, 1);
    ByteUtil.putInt64LE(data, AddressLookupTable.DEACTIVATION_SLOT_OFFSET, -1L);
    int o = AddressLookupTable.LOOKUP_TABLE_META_SIZE;
    for (final var address : addresses) {
      o += address.write(data, o);
    }
    return AddressLookupTable.read(tableAddress, data);
  }

  private static AccountMeta findMeta(final AccountMeta[] accounts, final PublicKey publicKey) {
    for (final var account : accounts) {
      if (account != null && publicKey.equals(account.publicKey())) {
        return account;
      }
    }
    return fail("No account meta for " + publicKey.toBase58());
  }

  /// Legacy instructions are variable width and contiguous at the instructions offset: a program id
  /// index u8, a compact-u16 account count, one u8 per referenced account, a compact-u16 data
  /// length, then the data. Rewrites one referenced account index in place.
  private static byte[] withInstructionAccountIndex(final byte[] data,
                                                    final int instructionIndex,
                                                    final int accountSlot,
                                                    final int accountIndex) {
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    assertTrue(instructionIndex < skeleton.numInstructions());
    int o = skeleton.instructionsOffset();
    for (int i = 0; i <= instructionIndex; ++i) {
      ++o; // program id index
      final int numIxAccounts = decode(data, o);
      o += getByteLen(data, o);
      if (i == instructionIndex) {
        assertTrue(accountSlot < numIxAccounts, "instruction has no such account slot");
        final byte[] corrupted = Arrays.copyOf(data, data.length);
        corrupted[o + accountSlot] = (byte) accountIndex;
        return corrupted;
      }
      o += numIxAccounts;
      final int len = decode(data, o);
      o += getByteLen(data, o);
      o += len;
    }
    return fail("unreachable");
  }

  /// Pins the `isLegacy()` early return of [TransactionSkeletonImpl#parseAccounts(Map)] — and the
  /// `isLegacy() || lookupTable == null` short circuit that routes the single table overload to the
  /// same place. A legacy message has no address table lookups at all, so every table aware
  /// overload must hand back the message's own accounts; returning null, or resolving anything out
  /// of the supplied table, would both be wrong.
  @Test
  void testLegacyParseAccountsIgnoresLookupTables() {
    final byte[] data = serializedLegacy(
        ix(PROGRAM, List.of(createWrite(WRITE_ACCOUNT), createRead(READ_ACCOUNT)), 1, 2, 3, 4)
    );
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    assertTrue(skeleton.isLegacy());
    assertFalse(skeleton.isVersioned());
    // No table section on the wire: nothing to index into, so numAccounts is the included count.
    assertEquals(0, skeleton.lookupTableAccounts().length);
    assertEquals(0, skeleton.numIndexedAccounts());
    assertEquals(skeleton.numIncludedAccounts(), skeleton.numAccounts());

    final var included = skeleton.parseAccounts();
    assertEquals(4, included.length); // fee payer, write account, read account, program
    assertEquals(skeleton.numIncludedAccounts(), included.length);
    for (final var account : included) {
      assertNotNull(account);
    }

    // A table whose entries are not in the message. Neither its address nor its entries may appear
    // in the parsed accounts, and none of the overloads may return null.
    final var tableEntry = key(45);
    final var table = alt(ALT_ADDRESS, tableEntry, READ_ACCOUNT);

    final var fromEmptyMap = skeleton.parseAccounts(Map.of());
    assertNotNull(fromEmptyMap);
    assertArrayEquals(included, fromEmptyMap);

    final var fromMap = skeleton.parseAccounts(Map.of(table.address(), table));
    assertNotNull(fromMap);
    assertArrayEquals(included, fromMap);

    final var fromStream = skeleton.parseAccounts(Stream.of(table));
    assertNotNull(fromStream);
    assertArrayEquals(included, fromStream);

    final var fromTable = skeleton.parseAccounts(table);
    assertNotNull(fromTable);
    assertArrayEquals(included, fromTable);

    final var fromNullTable = skeleton.parseAccounts((AddressLookupTable) null);
    assertNotNull(fromNullTable);
    assertArrayEquals(included, fromNullTable);

    for (final var parsed : new AccountMeta[][]{fromEmptyMap, fromMap, fromStream, fromTable, fromNullTable}) {
      assertEquals(included.length, parsed.length);
      for (final var account : parsed) {
        assertNotNull(account);
        assertNotEquals(ALT_ADDRESS, account.publicKey());
        assertNotEquals(tableEntry, account.publicKey());
      }
    }

    // The fee payer and the read only accounts keep their legacy classification through the table
    // overloads: no account is promoted to invoked, which is what the versioned parse would do.
    assertTrue(included[0].feePayer());
    assertEquals(createRead(PROGRAM), findMeta(included, PROGRAM));
    assertFalse(findMeta(included, PROGRAM).invoked());
    assertEquals(createRead(READ_ACCOUNT), findMeta(included, READ_ACCOUNT));
    assertEquals(createWrite(WRITE_ACCOUNT), findMeta(included, WRITE_ACCOUNT));
  }

  /// Pins the two bounds of [TransactionSkeletonImpl#parseInstructions(AccountMeta[])], the
  /// sava#57 resolution ported from main: a legacy message declares no table-loaded accounts, so
  /// `numAccounts` equals `numIncludedAccounts` and an instruction account index of exactly
  /// `accounts.length` is undeclared — corruption, refused with the diagnosed rejection
  /// [V1TransactionSkeleton] shares — where it used to read as a null meta the caller would trip
  /// over later. The last declared index must keep resolving: with `>` in place of `>=` the
  /// smallest undeclared index would slip through to the array read.
  @Test
  void testLegacyInstructionAccountIndexOnTheAccountsBoundaryIsRejected() {
    final byte[] data = serializedLegacy(
        ix(PROGRAM, List.of(createWrite(WRITE_ACCOUNT), createRead(READ_ACCOUNT)), 1, 2, 3, 4)
    );
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    final var accounts = skeleton.parseAccounts();
    assertEquals(4, accounts.length);
    assertEquals(skeleton.numIncludedAccounts(), accounts.length);
    assertEquals(skeleton.numAccounts(), accounts.length, "legacy declares no loaded accounts");

    final var parsed = skeleton.parseInstructions(accounts);
    assertEquals(1, parsed.length);
    assertEquals(2, parsed[0].accounts().size());
    assertEquals(WRITE_ACCOUNT, parsed[0].accounts().get(0).publicKey());
    assertEquals(READ_ACCOUNT, parsed[0].accounts().get(1).publicKey());

    // The last declared index still resolves to its own meta.
    final int lastIndex = accounts.length - 1;
    final var inRangeSkeleton = TransactionSkeleton.deserializeSkeleton(
        withInstructionAccountIndex(data, 0, 1, lastIndex)
    );
    final var inRangeAccounts = inRangeSkeleton.parseAccounts();
    final var inRange = inRangeSkeleton.parseInstructions(inRangeAccounts);
    assertNotNull(inRange[0].accounts().get(1));
    assertEquals(inRangeAccounts[lastIndex], inRange[0].accounts().get(1));
    assertEquals(inRangeAccounts[lastIndex].publicKey(), inRange[0].accounts().get(1).publicKey());

    // One past it, exactly accounts.length, is undeclared.
    final byte[] outOfRangeData = withInstructionAccountIndex(data, 0, 1, accounts.length);
    final var outOfRangeSkeleton = TransactionSkeleton.deserializeSkeleton(outOfRangeData);
    final var outOfRangeAccounts = outOfRangeSkeleton.parseAccounts();
    // The index is invisible to the account view: only the instruction views ever read it.
    assertEquals(accounts.length, outOfRangeAccounts.length);
    assertArrayEquals(accounts, outOfRangeAccounts);

    final String expected = "Instruction account index " + accounts.length
        + " is outside the " + accounts.length + " accounts of this transaction.";
    assertEquals(
        expected,
        assertThrowsExactly(
            IndexOutOfBoundsException.class,
            () -> outOfRangeSkeleton.parseInstructions(outOfRangeAccounts)
        ).getMessage()
    );

    // The filter view shares the bound and must agree.
    assertEquals(
        expected,
        assertThrowsExactly(
            IndexOutOfBoundsException.class,
            () -> outOfRangeSkeleton.filterInstructions(outOfRangeAccounts, toDiscriminator(1, 2, 3, 4))
        ).getMessage()
    );
  }

  /// Covers both arms of the legacy filter loops' exact sizing: every instruction matching, and
  /// only some. The returned length must always be the number of matches, never a null padded
  /// array of `numInstructions`.
  @Test
  void testLegacyFilterLengthIsTheMatchCount() {
    final var program = SolanaAccounts.MAIN_NET.systemProgram();
    final var eights = toDiscriminator(8, 8, 8, 8);
    final var sevens = toDiscriminator(7, 7, 7, 7);
    final var nines = toDiscriminator(9, 9, 9, 9);

    final byte[] allMatchData = serializedLegacy(
        ix(program, List.of(createWrite(WRITE_ACCOUNT)), 8, 8, 8, 8),
        ix(program, List.of(), 8, 8, 8, 8),
        ix(program, List.of(createRead(READ_ACCOUNT)), 8, 8, 8, 8)
    );
    final var allMatch = TransactionSkeleton.deserializeSkeleton(allMatchData);
    final var allMatchAccounts = allMatch.parseAccounts();
    final var allParsed = allMatch.parseInstructions(allMatchAccounts);
    assertEquals(3, allMatch.numInstructions());

    final var allFiltered = allMatch.filterInstructions(allMatchAccounts, eights);
    assertEquals(allMatch.numInstructions(), allFiltered.length);
    for (int i = 0; i < allFiltered.length; ++i) {
      assertNotNull(allFiltered[i]);
      assertEquals(allParsed[i], allFiltered[i]);
    }

    final var allFilteredWithoutAccounts = allMatch.filterInstructionsWithoutAccounts(eights);
    assertEquals(allMatch.numInstructions(), allFilteredWithoutAccounts.length);
    for (final var instruction : allFilteredWithoutAccounts) {
      assertNotNull(instruction);
      assertTrue(instruction.accounts().isEmpty());
      assertTrue(instruction.programId().invoked());
      assertArrayEquals(new byte[]{8, 8, 8, 8}, instruction.copyData());
    }

    assertEquals(0, allMatch.filterInstructions(allMatchAccounts, nines).length);
    assertEquals(0, allMatch.filterInstructionsWithoutAccounts(nines).length);

    final byte[] mixedData = serializedLegacy(
        ix(program, List.of(createWrite(WRITE_ACCOUNT)), 7, 7, 7, 7),
        ix(program, List.of(), 8, 8, 8, 8),
        ix(program, List.of(createRead(READ_ACCOUNT)), 7, 7, 7, 7)
    );
    final var mixed = TransactionSkeleton.deserializeSkeleton(mixedData);
    final var mixedAccounts = mixed.parseAccounts();
    final var mixedParsed = mixed.parseInstructions(mixedAccounts);
    assertEquals(3, mixed.numInstructions());

    // Trimmed to the two matches rather than null padded to three.
    final var mixedFiltered = mixed.filterInstructions(mixedAccounts, sevens);
    assertEquals(2, mixedFiltered.length);
    assertEquals(mixedParsed[0], mixedFiltered[0]);
    assertEquals(mixedParsed[2], mixedFiltered[1]);

    final var mixedWithoutAccounts = mixed.filterInstructionsWithoutAccounts(sevens);
    assertEquals(2, mixedWithoutAccounts.length);
    for (final var instruction : mixedWithoutAccounts) {
      assertNotNull(instruction);
      assertArrayEquals(new byte[]{7, 7, 7, 7}, instruction.copyData());
    }

    final var single = mixed.filterInstructions(mixedAccounts, eights);
    assertEquals(1, single.length);
    assertEquals(mixedParsed[1], single[0]);
    assertEquals(1, mixed.filterInstructionsWithoutAccounts(eights).length);

    // A discriminator longer than any instruction's data still sizes the array by the match count.
    final var longDiscriminator = Discriminator.createDiscriminator(new byte[]{
        (byte) 0xEE, (byte) 0xEE, (byte) 0xEE, (byte) 0xEE,
        (byte) 0xEE, (byte) 0xEE, (byte) 0xEE, (byte) 0xEE
    });
    assertEquals(0, mixed.filterInstructions(mixedAccounts, longDiscriminator).length);
    assertEquals(0, mixed.filterInstructionsWithoutAccounts(longDiscriminator).length);
  }

  /// The legacy fee/price conversions short circuit on either operand being zero. The compute unit
  /// limit half is pinned elsewhere; this pins the fee and price half down to a limit of one unit,
  /// where the round-up addends `(cappedComputeUnitLimit - 1)` and `999_999` are at their most
  /// fragile, along with non-zero controls at the same limits so the zero results are not
  /// degenerate.
  @Test
  void testZeroFeeAndZeroPriceConvertToZero() {
    for (final int computeUnitLimit : new int[]{1, 2, 200_000, 1_400_000, 2_000_000, -1}) {
      assertEquals(0L, TransactionRecord.priorityFeeLamportsToComputeUnitPrice(0L, computeUnitLimit));
      assertEquals(0L, TxBuilder.computeUnitPriceToPriorityFeeLamports(0L, computeUnitLimit));
    }

    assertEquals(1_000_000L, TransactionRecord.priorityFeeLamportsToComputeUnitPrice(1L, 1));
    assertEquals(500_000L, TransactionRecord.priorityFeeLamportsToComputeUnitPrice(1L, 2));
    assertEquals(1L, TxBuilder.computeUnitPriceToPriorityFeeLamports(1L, 1));
    assertEquals(1L, TxBuilder.computeUnitPriceToPriorityFeeLamports(1_000_000L, 1));
    assertEquals(2L, TxBuilder.computeUnitPriceToPriorityFeeLamports(1_000_001L, 1));
  }

  /// The zero-limit half of that short circuit is load bearing, not an optimisation: the overflow
  /// guard below it divides by `cappedComputeUnitLimit`, so skipping the early return with a limit
  /// of zero and a non-zero price divides by zero. A zero price cannot show this — it reaches the
  /// same 0 through the arithmetic — so it needs a non-zero price against a cleared limit, which is
  /// exactly what a v1 transaction with TransactionConfigMask bit 2 unset reports.
  @Test
  void testAZeroComputeUnitLimitShortCircuitsBeforeTheOverflowGuardDivides() {
    for (final long microLamportsPerComputeUnit : new long[]{1L, 25_000L, Long.MAX_VALUE, -1L}) {
      assertEquals(0L, TxBuilder.computeUnitPriceToPriorityFeeLamports(microLamportsPerComputeUnit, 0));
    }
    // A limit of one unit is the smallest that reaches the guard, and must not be swallowed too.
    assertEquals(Long.MAX_VALUE, TxBuilder.computeUnitPriceToPriorityFeeLamports(Long.MAX_VALUE, 1));
  }
}
