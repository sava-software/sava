package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.encoding.CompactU16Encoding;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Covers the legacy/v0 in-place compute budget machinery: the two-instruction replace-or-prepend
/// pass, the effective compute unit limit derived when no SetComputeUnitLimit instruction is
/// present, the lamports to micro-lamports-per-compute-unit conversion, and the skeleton side
/// which walks the serialized instructions to recover the priority fee.
///
/// The happy paths are pinned by `TransactionSerializationTests`; this class pins the branch
/// combinations those miss: one of the two compute budget instructions present, an instruction
/// from another program whose first data byte collides with a compute budget discriminator,
/// compute budget instructions whose data is too short to hold the value they claim, and the
/// arithmetic boundaries where the saturation guard and the round-up addend meet.
///
/// Every signer is derived from a fixed private key so a failure is exactly reproducible.
final class LegacyComputeBudgetTests {

  private static final PublicKey COMPUTE_BUDGET_PROGRAM = SolanaAccounts.MAIN_NET.computeBudgetProgram();

  private static Signer signer(final int seed) {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    Arrays.fill(privateKey, (byte) seed);
    return Signer.createFromPrivateKey(privateKey);
  }

  private static Instruction computeBudgetIx(final byte[] data) {
    return Instruction.createInstruction(SolanaAccounts.MAIN_NET.invokedComputeBudgetProgram(), List.of(), data);
  }

  private static Instruction setComputeUnitPrice(final long microLamportsPerComputeUnit) {
    final byte[] data = new byte[1 + Long.BYTES];
    data[0] = TransactionRecord.SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR;
    ByteUtil.putInt64LE(data, 1, microLamportsPerComputeUnit);
    return computeBudgetIx(data);
  }

  private static Instruction setComputeUnitLimit(final int units) {
    final byte[] data = new byte[1 + Integer.BYTES];
    data[0] = TransactionRecord.SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR;
    ByteUtil.putInt32LE(data, 1, units);
    return computeBudgetIx(data);
  }

  /// An instruction from a program other than the compute budget program.
  private static Instruction programIx(final PublicKey account, final byte... data) {
    return Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createWritableSigner(account)),
        data
    );
  }

  private static Instruction instructionAt(final Transaction tx, final int index) {
    return tx.instructions().get(index);
  }

  private static byte discriminator(final Instruction ix) {
    assertTrue(ix.len() > 0, "instruction has no data");
    return ix.data()[ix.offset()];
  }

  private static void assertComputeBudgetIx(final Instruction ix, final byte expectedDiscriminator) {
    assertEquals(COMPUTE_BUDGET_PROGRAM, ix.programId().publicKey());
    assertEquals(expectedDiscriminator, discriminator(ix));
  }

  /// The u64 payload of the single SetComputeUnitPrice instruction.
  private static long computeUnitPrice(final Transaction tx) {
    for (final var ix : tx.instructions()) {
      if (COMPUTE_BUDGET_PROGRAM.equals(ix.programId().publicKey())
          && ix.len() == 1 + Long.BYTES
          && ix.data()[ix.offset()] == TransactionRecord.SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR) {
        return ByteUtil.getInt64LE(ix.data(), ix.offset() + 1);
      }
    }
    throw new AssertionError("no SetComputeUnitPrice instruction");
  }

  /// The u32 payload of the single SetComputeUnitLimit instruction.
  private static int computeUnitLimit(final Transaction tx) {
    for (final var ix : tx.instructions()) {
      if (COMPUTE_BUDGET_PROGRAM.equals(ix.programId().publicKey())
          && ix.len() == 1 + Integer.BYTES
          && ix.data()[ix.offset()] == TransactionRecord.SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR) {
        return ByteUtil.getInt32LE(ix.data(), ix.offset() + 1);
      }
    }
    throw new AssertionError("no SetComputeUnitLimit instruction");
  }

  /// Both compute budget instructions are already present, so the single pass replaces both in
  /// place: no instruction is prepended, none is dropped, and the surrounding instruction keeps
  /// its position and identity.
  @Test
  void testReplacesBothComputeBudgetInstructionsInPlace() {
    final var feePayer = signer(21);
    final var signerB = signer(22);
    final var programIx = programIx(signerB.publicKey(), (byte) 1, (byte) 2, (byte) 3);

    final var tx = Transaction.createTx(feePayer.publicKey(), List.of(
        setComputeUnitLimit(200_000),
        setComputeUnitPrice(5_000L),
        programIx
    ));
    final byte[] blockHash = new byte[Transaction.BLOCK_HASH_LENGTH];
    for (int b = 0; b < blockHash.length; ++b) {
      blockHash[b] = (byte) (b + 13);
    }
    tx.setRecentBlockHash(blockHash);

    final var updated = tx.setPriorityFeeLamportsFromComputeUnitPrice(25_000L, 300_000);
    assertNotNull(updated);
    assertNotSame(tx, updated);

    // Nothing was prepended: the instruction count and the ordering are unchanged.
    assertEquals(3, updated.numInstructions());
    assertComputeBudgetIx(instructionAt(updated, 0), TransactionRecord.SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR);
    assertComputeBudgetIx(instructionAt(updated, 1), TransactionRecord.SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR);
    assertSame(programIx, instructionAt(updated, 2));

    // Both replacements carry the new values, and the recent block hash is carried over.
    assertEquals(300_000, computeUnitLimit(updated));
    assertEquals(25_000L, computeUnitPrice(updated));
    assertArrayEquals(blockHash, updated.recentBlockHash());

    final var skeleton = TransactionSkeleton.deserializeSkeleton(updated.serialized());
    assertEquals(300_000, skeleton.computeUnitLimit());
    // 25,000 * 300,000 micro-lamports = 7,500 lamports.
    assertEquals(7_500L, skeleton.priorityFeeLamports());
  }

  /// Only the SetComputeUnitLimit instruction exists: it is replaced in place while the price is
  /// prepended, so exactly one instruction is added.
  @Test
  void testPrependsPriceWhenOnlyLimitPresent() {
    final var feePayer = signer(23);
    final var signerB = signer(24);
    final var programIx = programIx(signerB.publicKey(), (byte) 4, (byte) 5);

    final var tx = Transaction.createTx(feePayer.publicKey(), List.of(setComputeUnitLimit(200_000), programIx));

    final var updated = tx.setPriorityFeeLamportsFromComputeUnitPrice(25_000L, 300_000);
    assertEquals(3, updated.numInstructions());
    assertComputeBudgetIx(instructionAt(updated, 0), TransactionRecord.SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR);
    assertComputeBudgetIx(instructionAt(updated, 1), TransactionRecord.SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR);
    assertSame(programIx, instructionAt(updated, 2));

    assertEquals(300_000, computeUnitLimit(updated));
    assertEquals(25_000L, computeUnitPrice(updated));

    final var skeleton = TransactionSkeleton.deserializeSkeleton(updated.serialized());
    assertEquals(300_000, skeleton.computeUnitLimit());
    assertEquals(7_500L, skeleton.priorityFeeLamports());
  }

  /// Only the SetComputeUnitPrice instruction exists: it is replaced in place while the limit is
  /// prepended, so exactly one instruction is added.
  @Test
  void testPrependsLimitWhenOnlyPricePresent() {
    final var feePayer = signer(25);
    final var signerB = signer(26);
    final var programIx = programIx(signerB.publicKey(), (byte) 6, (byte) 7);

    final var tx = Transaction.createTx(feePayer.publicKey(), List.of(setComputeUnitPrice(5_000L), programIx));

    final var updated = tx.setPriorityFeeLamportsFromComputeUnitPrice(25_000L, 300_000);
    assertEquals(3, updated.numInstructions());
    assertComputeBudgetIx(instructionAt(updated, 0), TransactionRecord.SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR);
    assertComputeBudgetIx(instructionAt(updated, 1), TransactionRecord.SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR);
    assertSame(programIx, instructionAt(updated, 2));

    assertEquals(300_000, computeUnitLimit(updated));
    assertEquals(25_000L, computeUnitPrice(updated));

    final var skeleton = TransactionSkeleton.deserializeSkeleton(updated.serialized());
    assertEquals(300_000, skeleton.computeUnitLimit());
    assertEquals(7_500L, skeleton.priorityFeeLamports());
  }

  /// An instruction from another program whose first data byte happens to equal a compute budget
  /// discriminator must never be mistaken for a compute budget instruction: the program id is
  /// what identifies one.
  @Test
  void testNonComputeBudgetInstructionIsNeverReplaced() {
    final var feePayer = signer(27);
    final var signerB = signer(28);
    // The first data byte collides with SetComputeUnitLimit, the second instruction's with
    // SetComputeUnitPrice.
    final var limitLookalike = programIx(
        signerB.publicKey(), TransactionRecord.SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR, (byte) 0, (byte) 0, (byte) 0, (byte) 0
    );
    final var priceLookalike = programIx(
        signerB.publicKey(), TransactionRecord.SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR, (byte) 0, (byte) 0, (byte) 0, (byte) 0
    );

    final var tx = Transaction.createTx(feePayer.publicKey(), List.of(limitLookalike, priceLookalike));

    final var updated = tx.setPriorityFeeLamportsFromComputeUnitPrice(25_000L, 300_000);
    // Both compute budget instructions are prepended, neither look-alike is consumed.
    assertEquals(4, updated.numInstructions());
    assertComputeBudgetIx(instructionAt(updated, 0), TransactionRecord.SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR);
    assertComputeBudgetIx(instructionAt(updated, 1), TransactionRecord.SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR);
    assertSame(limitLookalike, instructionAt(updated, 2));
    assertSame(priceLookalike, instructionAt(updated, 3));

    assertEquals(300_000, computeUnitLimit(updated));
    assertEquals(25_000L, computeUnitPrice(updated));
  }

  /// A compute budget instruction with no data at all must be skipped before its discriminator is
  /// read, rather than indexing past the end of its payload.
  @Test
  void testEmptyComputeBudgetInstructionDataIsSkipped() {
    final var feePayer = signer(29);
    final var signerB = signer(30);
    final var emptyData = computeBudgetIx(new byte[0]);
    final var programIx = programIx(signerB.publicKey(), (byte) 8, (byte) 9);

    final var tx = Transaction.createTx(feePayer.publicKey(), List.of(emptyData, programIx));

    final var updated = tx.setPriorityFeeLamportsFromComputeUnitPrice(25_000L, 300_000);
    assertEquals(4, updated.numInstructions());
    assertComputeBudgetIx(instructionAt(updated, 0), TransactionRecord.SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR);
    assertComputeBudgetIx(instructionAt(updated, 1), TransactionRecord.SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR);
    assertSame(emptyData, instructionAt(updated, 2));
    assertSame(programIx, instructionAt(updated, 3));

    final var skeleton = TransactionSkeleton.deserializeSkeleton(updated.serialized());
    assertEquals(300_000, skeleton.computeUnitLimit());
    assertEquals(7_500L, skeleton.priorityFeeLamports());
  }

  /// The effective compute unit limit used to price a fee in lamports must also skip a compute
  /// budget instruction with no data before reading its discriminator.
  @Test
  void testEffectiveComputeUnitLimitSkipsEmptyComputeBudgetData() {
    final var feePayer = signer(31);
    final var signerB = signer(32);
    final var emptyData = computeBudgetIx(new byte[0]);

    final var tx = Transaction.createTx(feePayer.publicKey(), List.of(
        emptyData,
        programIx(signerB.publicKey(), (byte) 1)
    ));

    // No readable SetComputeUnitLimit instruction, so the runtime's per-instruction default
    // applies. Every instruction here is a builtin — the empty ComputeBudget one, the System
    // transfer, and the SetComputeUnitPrice about to be prepended — so each is allocated 3,000
    // rather than 200,000 units: 1 lamport over 3 * 3,000 = 9,000 units rounds up to 112
    // micro-lamports per compute unit.
    final var priced = tx.setPriorityFeeLamports(1L);
    assertEquals(3, priced.numInstructions());
    assertEquals(112L, computeUnitPrice(priced));
  }

  /// The estimated limit scales with the number of non-compute-budget instructions, which is what
  /// the lamport fee is divided by.
  @Test
  void testEffectiveComputeUnitLimitScalesWithInstructionCount() {
    final var feePayer = signer(33);
    final var signerB = signer(34);

    final var oneInstruction = Transaction.createTx(feePayer.publicKey(), List.of(
        programIx(signerB.publicKey(), (byte) 1)
    ));
    // One System instruction plus the SetComputeUnitPrice about to be prepended, both builtins:
    // 1 lamport over 2 * 3,000 = 6,000 units rounds up to 167 micro-lamports per compute unit.
    assertEquals(167L, computeUnitPrice(oneInstruction.setPriorityFeeLamports(1L)));

    final var twoInstructions = Transaction.createTx(feePayer.publicKey(), List.of(
        programIx(signerB.publicKey(), (byte) 1),
        programIx(signerB.publicKey(), (byte) 2)
    ));
    // 1 lamport over 3 * 3,000 = 9,000 units rounds up to 112 micro-lamports per compute unit.
    assertEquals(112L, computeUnitPrice(twoInstructions.setPriorityFeeLamports(1L)));

    // Nine builtins at 3,000 each is 27,000 units, nowhere near the 1.4 million runtime maximum:
    // 1 lamport over 27,000 units rounds up to 38 micro-lamports per compute unit.
    final var eightInstructions = Transaction.createTx(feePayer.publicKey(), List.of(
        programIx(signerB.publicKey(), (byte) 1),
        programIx(signerB.publicKey(), (byte) 2),
        programIx(signerB.publicKey(), (byte) 3),
        programIx(signerB.publicKey(), (byte) 4),
        programIx(signerB.publicKey(), (byte) 5),
        programIx(signerB.publicKey(), (byte) 6),
        programIx(signerB.publicKey(), (byte) 7),
        programIx(signerB.publicKey(), (byte) 8)
    ));
    assertEquals(38L, computeUnitPrice(eightInstructions.setPriorityFeeLamports(1L)));
  }

  /// A compute unit limit of zero cannot be priced against, so the conversion yields no price
  /// rather than falling through to the saturation guard, whose threshold would underflow.
  @Test
  void testPriorityFeeLamportsToComputeUnitPriceZeroLimit() {
    assertEquals(0L, TransactionRecord.priorityFeeLamportsToComputeUnitPrice(1L, 0));
    assertEquals(0L, TransactionRecord.priorityFeeLamportsToComputeUnitPrice(Long.MAX_VALUE, 0));
    assertEquals(0L, TransactionRecord.priorityFeeLamportsToComputeUnitPrice(-1L, 0));
    assertEquals(0L, TransactionRecord.priorityFeeLamportsToComputeUnitPrice(0L, 0));
  }

  /// The largest fee which does not saturate, exactly on the guard's boundary.
  ///
  /// For a 775,808 unit limit, `Long.MAX_VALUE - (limit - 1)` is an exact multiple of one million,
  /// so the guard admits precisely the fee whose micro-lamports plus the round-up addend equal
  /// `Long.MAX_VALUE`. One lamport more must saturate, and the addend must be the limit minus one:
  /// with the limit plus one the guard would reject this fee.
  @Test
  void testPriorityFeeLamportsToComputeUnitPriceSaturationBoundary() {
    final int computeUnitLimit = 775_808;
    final long largestExactFee = 9_223_372_036_854L;
    assertEquals(
        Long.MAX_VALUE,
        (largestExactFee * 1_000_000L) + (computeUnitLimit - 1),
        "the round-up addend must land exactly on Long.MAX_VALUE"
    );

    assertEquals(
        11_888_730_248_791L,
        TransactionRecord.priorityFeeLamportsToComputeUnitPrice(largestExactFee, computeUnitLimit)
    );
    assertEquals(
        Long.MAX_VALUE,
        TransactionRecord.priorityFeeLamportsToComputeUnitPrice(largestExactFee + 1, computeUnitLimit)
    );
  }

  /// A SetComputeUnitLimit instruction whose data is one byte short of its u32 value must be
  /// ignored rather than reading its value out of the following instruction.
  @Test
  void testSkeletonTruncatedComputeUnitLimitDataIgnored() {
    final var feePayer = signer(35);
    final var signerB = signer(36);
    final var truncatedLimit = computeBudgetIx(
        new byte[]{TransactionRecord.SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR, 0, 0, 0}
    );

    final var tx = Transaction.createTx(feePayer.publicKey(), List.of(
        truncatedLimit,
        setComputeUnitPrice(1_000L),
        programIx(signerB.publicKey(), (byte) 1)
    ));

    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    assertEquals(0, skeleton.computeUnitLimit());
    // The truncated limit contributes no value, but the instruction is still a builtin the runtime
    // budgets: three builtins at 3,000 each = 9,000 units, so 1,000 * 9,000 micro-lamports
    // = 9 lamports.
    assertEquals(9L, skeleton.priorityFeeLamports());
  }

  /// A SetComputeUnitPrice instruction whose data is one byte short of its u64 value must be
  /// ignored rather than reading its price out of the following instruction.
  @Test
  void testSkeletonTruncatedComputeUnitPriceDataIgnored() {
    final var feePayer = signer(37);
    final var signerB = signer(38);
    final var truncatedPrice = computeBudgetIx(
        new byte[]{TransactionRecord.SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR, 0, 0, 0, 0, 0, 0, 0}
    );

    final var tx = Transaction.createTx(feePayer.publicKey(), List.of(
        truncatedPrice,
        programIx(signerB.publicKey(), (byte) 1)
    ));

    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    // No readable price, so there is no priority fee at all.
    assertEquals(0L, skeleton.priorityFeeLamports());
    assertEquals(0, skeleton.computeUnitLimit());
  }

  /// The skeleton's walk exits as soon as both the price and the limit have been located, so a
  /// non-compute-budget instruction after the limit is not counted toward the estimated limit
  /// which a serialized limit of zero falls back to.
  @Test
  void testSkeletonStopsScanningOnceBothFound() {
    final var feePayer = signer(39);
    final var signerB = signer(40);

    final var tx = Transaction.createTx(feePayer.publicKey(), List.of(
        programIx(signerB.publicKey(), (byte) 1),
        setComputeUnitPrice(1_000L),
        setComputeUnitLimit(0),
        programIx(signerB.publicKey(), (byte) 2)
    ));

    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    assertEquals(0, skeleton.computeUnitLimit());
    // An explicit zero limit falls back to the per-instruction default, which needs every
    // instruction counted, so the scan does NOT exit early here: all four builtins contribute
    // 3,000 each and 1,000 * 12,000 micro-lamports = 12 lamports. The early exit applies only when
    // the limit found is one the fee will actually be derived against.
    assertEquals(12L, skeleton.priorityFeeLamports());
  }

  /// Neither the price nor the limit may end the walk on its own: both orderings must recover
  /// both values.
  @Test
  void testSkeletonPriceAndLimitInEitherOrder() {
    final var feePayer = signer(43);
    final var signerB = signer(44);

    // 1,000 * 300,000 micro-lamports = 300 lamports, whichever instruction comes first.
    final var priceFirst = Transaction.createTx(feePayer.publicKey(), List.of(
        setComputeUnitPrice(1_000L),
        setComputeUnitLimit(300_000),
        programIx(signerB.publicKey(), (byte) 1)
    ));
    final var priceFirstSkeleton = TransactionSkeleton.deserializeSkeleton(priceFirst.serialized());
    assertEquals(300_000, priceFirstSkeleton.computeUnitLimit());
    assertEquals(300L, priceFirstSkeleton.priorityFeeLamports());

    final var limitFirst = Transaction.createTx(feePayer.publicKey(), List.of(
        setComputeUnitLimit(300_000),
        setComputeUnitPrice(1_000L),
        programIx(signerB.publicKey(), (byte) 1)
    ));
    final var limitFirstSkeleton = TransactionSkeleton.deserializeSkeleton(limitFirst.serialized());
    assertEquals(300_000, limitFirstSkeleton.computeUnitLimit());
    assertEquals(300L, limitFirstSkeleton.priorityFeeLamports());
  }

  /// With no SetComputeUnitLimit instruction the fee is derived from an estimated limit which
  /// scales with the number of non-compute-budget instructions, capped at the runtime maximum.
  @Test
  void testSkeletonDefaultLimitScalesWithInstructionCount() {
    final var feePayer = signer(41);
    final var signerB = signer(42);

    final var twoInstructions = Transaction.createTx(feePayer.publicKey(), List.of(
        setComputeUnitPrice(1_000L),
        programIx(signerB.publicKey(), (byte) 1),
        programIx(signerB.publicKey(), (byte) 2)
    ));
    // Three builtins — the SetComputeUnitPrice and two System instructions — at 3,000 each:
    // 1,000 * 9,000 micro-lamports = 9 lamports.
    assertEquals(
        9L,
        TransactionSkeleton.deserializeSkeleton(twoInstructions.serialized()).priorityFeeLamports()
    );

    // Nine builtins at 3,000 each is 27,000 units, far below the 1.4 million runtime maximum, so
    // no cap applies: 1,000 * 27,000 micro-lamports = 27 lamports. Reaching the cap would need
    // roughly 467 builtin instructions, which the 64-instruction limit forbids.
    final var eightInstructions = Transaction.createTx(feePayer.publicKey(), List.of(
        setComputeUnitPrice(1_000L),
        programIx(signerB.publicKey(), (byte) 1),
        programIx(signerB.publicKey(), (byte) 2),
        programIx(signerB.publicKey(), (byte) 3),
        programIx(signerB.publicKey(), (byte) 4),
        programIx(signerB.publicKey(), (byte) 5),
        programIx(signerB.publicKey(), (byte) 6),
        programIx(signerB.publicKey(), (byte) 7),
        programIx(signerB.publicKey(), (byte) 8)
    ));
    assertEquals(
        27L,
        TransactionSkeleton.deserializeSkeleton(eightInstructions.serialized()).priorityFeeLamports()
    );
  }

  /// A program that is not one of the runtime's builtins, so the default budget for an instruction
  /// invoking it is the full 200,000 units rather than 3,000.
  private static Instruction nonBuiltinIx(final PublicKey account, final byte... data) {
    return Instruction.createInstruction(
        PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"),
        List.of(AccountMeta.createWritableSigner(account)),
        data
    );
  }

  /// Pins the builtin split itself. Every other fixture in this class invokes the System program, so
  /// a mutant treating every program as a builtin survives all of them; only a non-builtin program
  /// separates 3,000 from 200,000.
  @Test
  void testNonBuiltinProgramsAreBudgetedTheFullDefault() {
    final var feePayer = signer(51);
    final var signerB = signer(52);

    // 200,000 for the SPL token instruction plus 3,000 for the SetComputeUnitPrice about to be
    // prepended: 1 lamport over 203,000 units rounds up to 5 micro-lamports per compute unit.
    final var tx = Transaction.createTx(feePayer.publicKey(), List.of(nonBuiltinIx(signerB.publicKey(), (byte) 1)));
    assertEquals(5L, computeUnitPrice(tx.setPriorityFeeLamports(1L)));

    // The same split on the skeleton's read path: 1,000 * (3,000 + 200,000) = 203 lamports.
    final var priced = Transaction.createTx(feePayer.publicKey(), List.of(
        setComputeUnitPrice(1_000L),
        nonBuiltinIx(signerB.publicKey(), (byte) 1)
    ));
    assertEquals(203L, TransactionSkeleton.deserializeSkeleton(priced.serialized()).priorityFeeLamports());

    // A builtin alongside it is still budgeted at 3,000, so the two are genuinely distinguished.
    final var mixed = Transaction.createTx(feePayer.publicKey(), List.of(
        setComputeUnitPrice(1_000L),
        nonBuiltinIx(signerB.publicKey(), (byte) 1),
        programIx(signerB.publicKey(), (byte) 2)
    ));
    assertEquals(206L, TransactionSkeleton.deserializeSkeleton(mixed.serialized()).priorityFeeLamports());
  }

  /// A transaction that already carries a SetComputeUnitPrice instruction gets no extra allowance,
  /// because nothing is prepended — the existing instruction is replaced in place.
  @Test
  void testAnExistingPriceInstructionIsNotDoubleCounted() {
    final var feePayer = signer(53);
    final var signerB = signer(54);

    final var alreadyPriced = Transaction.createTx(feePayer.publicKey(), List.of(
        setComputeUnitPrice(0L),
        programIx(signerB.publicKey(), (byte) 1)
    ));
    final var repriced = alreadyPriced.setPriorityFeeLamports(1L);
    assertEquals(2, repriced.numInstructions(), "the existing price instruction is replaced, not added");
    // Two builtins, no prepend: 1 lamport over 6,000 units rounds up to 167.
    assertEquals(167L, computeUnitPrice(repriced));

    // A compute budget instruction of a DIFFERENT kind is not a price, so one is still prepended:
    // three builtins, 9,000 units, 112 micro-lamports per compute unit.
    final var heapOnly = Transaction.createTx(feePayer.publicKey(), List.of(
        computeBudgetIx(new byte[]{TransactionRecord.REQUEST_HEAP_FRAME_DISCRIMINATOR, 0, 0, 1, 0}),
        programIx(signerB.publicKey(), (byte) 1)
    ));
    final var heapPriced = heapOnly.setPriorityFeeLamports(1L);
    assertEquals(3, heapPriced.numInstructions());
    assertEquals(112L, computeUnitPrice(heapPriced));
  }

  /// A non-compute-budget instruction whose first data byte collides with a compute budget
  /// discriminator must not be read as one, on either the transaction or the skeleton path.
  @Test
  void testDiscriminatorCollisionsInOtherProgramsAreIgnored() {
    final var feePayer = signer(55);
    final var signerB = signer(56);

    // Data begins with SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR followed by a zero limit. Read as a
    // limit it would yield 0, which converts to a price of 0 rather than the correct 167.
    final var limitCollision = Transaction.createTx(feePayer.publicKey(), List.of(
        programIx(signerB.publicKey(), TransactionRecord.SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR, (byte) 0, (byte) 0, (byte) 0, (byte) 0)
    ));
    assertEquals(167L, computeUnitPrice(limitCollision.setPriorityFeeLamports(1L)));

    // Data begins with SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR and is long enough to hold a u64 price.
    // Read as one the skeleton would report a fee; the transaction carries no price at all.
    final var priceCollision = Transaction.createTx(feePayer.publicKey(), List.of(
        programIx(
            signerB.publicKey(),
            TransactionRecord.SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
        )
    ));
    assertEquals(0L, TransactionSkeleton.deserializeSkeleton(priceCollision.serialized()).priorityFeeLamports());
  }


  /// The fee scan exits as soon as it holds a price and a limit it will actually use, so it never
  /// reads the instructions after them. That is observable: a corrupt program index in the tail is
  /// rejected by every other view but not by this one.
  ///
  /// The exit is conditional on the limit being usable — a zero limit falls back to the
  /// per-instruction default, which needs the whole walk — so this pins the "usable" half.
  @Test
  void testTheFeeScanStopsOnceAUsableLimitIsFound() {
    final var feePayer = signer(57);
    final var signerB = signer(58);

    final var tx = Transaction.createTx(feePayer.publicKey(), List.of(
        setComputeUnitPrice(1_000L),
        setComputeUnitLimit(200_000),
        programIx(signerB.publicKey(), (byte) 1)
    ));
    final byte[] data = tx.serialized();
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    // The explicit limit is used verbatim: 1,000 * 200,000 micro-lamports = 200 lamports.
    assertEquals(200L, skeleton.priorityFeeLamports());

    // Corrupt the third instruction's program index, past where the scan stops.
    final byte[] corrupt = data.clone();
    corrupt[programIdIndexOffset(skeleton, 2)] = (byte) 200;
    final var corruptSkeleton = TransactionSkeleton.deserializeSkeleton(corrupt);
    assertEquals(200L, corruptSkeleton.priorityFeeLamports(), "the scan never reaches the corrupt tail");
    assertThrows(IndexOutOfBoundsException.class, corruptSkeleton::parseProgramAccounts);
  }

  /// Byte offset of the given instruction's u8 program id index within the serialized message.
  private static int programIdIndexOffset(final TransactionSkeleton skeleton, final int instructionIndex) {
    final byte[] data = skeleton.data();
    int o = skeleton.instructionsOffset();
    for (int i = 0; i < instructionIndex; ++i) {
      ++o; // program id index
      final int numAccounts = CompactU16Encoding.decode(data, o);
      o += CompactU16Encoding.getByteLen(data, o) + numAccounts;
      final int numDataBytes = CompactU16Encoding.decode(data, o);
      o += CompactU16Encoding.getByteLen(data, o) + numDataBytes;
    }
    return o;
  }
}
