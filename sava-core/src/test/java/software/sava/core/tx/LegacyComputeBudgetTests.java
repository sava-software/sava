package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.ByteUtil;

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

    // No readable SetComputeUnitLimit instruction, so the single non-compute-budget instruction
    // is estimated at the 200,000 unit default: 1 lamport over 200,000 units rounds up to 5
    // micro-lamports per compute unit.
    final var priced = tx.setPriorityFeeLamports(1L);
    assertEquals(3, priced.numInstructions());
    assertEquals(5L, computeUnitPrice(priced));
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
    // 1 lamport over 1 * 200,000 units = 5 micro-lamports per compute unit.
    assertEquals(5L, computeUnitPrice(oneInstruction.setPriorityFeeLamports(1L)));

    final var twoInstructions = Transaction.createTx(feePayer.publicKey(), List.of(
        programIx(signerB.publicKey(), (byte) 1),
        programIx(signerB.publicKey(), (byte) 2)
    ));
    // 1 lamport over 2 * 200,000 units = 2.5, rounded up to 3 micro-lamports per compute unit.
    assertEquals(3L, computeUnitPrice(twoInstructions.setPriorityFeeLamports(1L)));

    // Eight instructions would estimate 1.6 million units, which the runtime maximum caps at
    // 1.4 million: 1 lamport over 1,400,000 units rounds up to 1 micro-lamport per compute unit.
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
    assertEquals(1L, computeUnitPrice(eightInstructions.setPriorityFeeLamports(1L)));
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
    // The truncated limit contributes nothing, so the single non-compute-budget instruction is
    // estimated at 200,000 units: 1,000 * 200,000 micro-lamports = 200 lamports.
    assertEquals(200L, skeleton.priorityFeeLamports());
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
    // Only the one instruction preceding the limit was counted before the walk exited, so the
    // zero limit falls back to a 200,000 unit estimate: 1,000 * 200,000 = 200 lamports.
    assertEquals(200L, skeleton.priorityFeeLamports());
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
    // 1,000 * (2 * 200,000) micro-lamports = 400 lamports.
    assertEquals(
        400L,
        TransactionSkeleton.deserializeSkeleton(twoInstructions.serialized()).priorityFeeLamports()
    );

    // Eight instructions estimate 1.6 million units, capped at the 1.4 million runtime maximum:
    // 1,000 * 1,400,000 micro-lamports = 1,400 lamports.
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
        1_400L,
        TransactionSkeleton.deserializeSkeleton(eightInstructions.serialized()).priorityFeeLamports()
    );
  }
}
