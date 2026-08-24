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
import static software.sava.core.tx.V1TransactionSkeleton.ACCOUNT_DATA_SIZE_LIMIT_MASK;
import static software.sava.core.tx.V1TransactionSkeleton.COMPUTE_UNIT_LIMIT_MASK;
import static software.sava.core.tx.V1TransactionSkeleton.HEAP_SIZE_MASK;
import static software.sava.core.tx.V1TransactionSkeleton.PRIORITY_FEE_MASK;

/// Pins three v1 ConfigValue facts that the rest of the suite leaves unpinned:
///
///  1. **Config precedence.** A v1 message is configured by its ConfigValues alone. One that
///     (malformedly) also embeds ComputeBudgetProgram instructions must still read every budget
///     from the ConfigValues block — set or cleared — and never fall back to the legacy instruction
///     scan. The SIMD-0385 examples repo pins the same property as
///     `a_v1_message_never_falls_back_to_scanning_instructions` (`rust/tests/budget.rs`).
///  2. **Builder zero means unset.** `TxBuilder` treats 0 as the explicit clear: the mask bit and
///     the 4-byte ConfigValue slot are omitted, so an explicit 0 and never calling the setter
///     serialize identically.
///  3. **In-place zero keeps the bit.** `Transaction#set*` on a built v1 transaction writes into an
///     existing slot, so a 0 written in place leaves the TransactionConfigMask and the size alone
///     and reads back as 0 — the shape `@solana/kit` emits for `e_explicit_zeros` (mask `0x0F`, every
///     value 0). The builder cannot produce that shape; the in-place setters cannot produce the
///     builder's.
///
/// The deliberate divergences behind (2) and (3) — the builder's non-zero defaults, 0 as the
/// explicit clear, and the heap request's unconditional bounds — are documented in AGAVE_SYNC.md
/// under "Deliberate divergences: v1 compute budget values" and must not be "fixed" toward agave.
///
/// Keys are derived from fixed private keys — the fee payer from 32 × `0x11` and the recipient
/// from 32 × `0x12`, the same pair the live Agave 4.2.1 captures used — so the serialized account
/// order and every byte compared below is reproducible run to run.
final class V1ConfigPrecedenceTests {

  private static final long PRIORITY_FEE_LAMPORTS = 5_000L;
  private static final int COMPUTE_UNIT_LIMIT = 20_000;
  private static final int ACCOUNT_DATA_SIZE_LIMIT = 65_536;
  private static final int HEAP_SIZE = 65_536;

  // Values carried by the embedded ComputeBudgetProgram instructions. Each differs from its
  // ConfigValue counterpart, from 0, and from the builder defaults, so a reader that consults the
  // instructions is distinguishable from one that consults the ConfigValues whichever way the
  // ConfigValues are set.
  private static final int EMBEDDED_COMPUTE_UNIT_LIMIT = 999_999;
  private static final long EMBEDDED_COMPUTE_UNIT_PRICE = 777_777L;
  private static final int EMBEDDED_ACCOUNT_DATA_SIZE_LIMIT = 888_888;
  private static final int EMBEDDED_HEAP_SIZE = 128 * 1_024;

  private static final int ALL_CONFIG_BITS =
      PRIORITY_FEE_MASK | COMPUTE_UNIT_LIMIT_MASK | ACCOUNT_DATA_SIZE_LIMIT_MASK | HEAP_SIZE_MASK;

  // Compute Budget instruction discriminators, per ComputeBudgetProgram.
  private static final int REQUEST_HEAP_FRAME_DISCRIMINATOR = 1;
  private static final int SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR = 2;
  private static final int SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR = 3;
  private static final int SET_LOADED_ACCOUNTS_DATA_SIZE_LIMIT_DISCRIMINATOR = 4;

  // SystemProgram::Transfer, u32 LE instruction index 2 followed by u64 LE lamports.
  private static final int SYSTEM_TRANSFER_INSTRUCTION = 2;
  private static final long TRANSFER_LAMPORTS = 1_000L;

  private static PublicKey key(final int seed) {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    Arrays.fill(privateKey, (byte) seed);
    return Signer.createFromPrivateKey(privateKey).publicKey();
  }

  private static PublicKey payer() {
    return key(0x11);
  }

  private static PublicKey recipient() {
    return key(0x12);
  }

  private static byte[] blockHash() {
    final byte[] blockHash = new byte[Transaction.BLOCK_HASH_LENGTH];
    for (int b = 0; b < blockHash.length; ++b) {
      blockHash[b] = (byte) (b + 7);
    }
    return blockHash;
  }

  private static Instruction transfer(final PublicKey from, final PublicKey to) {
    final byte[] data = new byte[Integer.BYTES + Long.BYTES];
    ByteUtil.putInt32LE(data, 0, SYSTEM_TRANSFER_INSTRUCTION);
    ByteUtil.putInt64LE(data, Integer.BYTES, TRANSFER_LAMPORTS);
    return Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.invokedSystemProgram(),
        List.of(AccountMeta.createWritableSigner(from), AccountMeta.createWrite(to)),
        data
    );
  }

  private static Instruction computeBudgetInstruction(final int discriminator, final int value) {
    final byte[] data = new byte[1 + Integer.BYTES];
    data[0] = (byte) discriminator;
    ByteUtil.putInt32LE(data, 1, value);
    return Instruction.createInstruction(SolanaAccounts.MAIN_NET.invokedComputeBudgetProgram(), List.of(), data);
  }

  private static Instruction setComputeUnitPrice(final long microLamports) {
    final byte[] data = new byte[1 + Long.BYTES];
    data[0] = (byte) SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR;
    ByteUtil.putInt64LE(data, 1, microLamports);
    return Instruction.createInstruction(SolanaAccounts.MAIN_NET.invokedComputeBudgetProgram(), List.of(), data);
  }

  /// A transfer followed by one of each ComputeBudgetProgram instruction kind, each carrying an
  /// `EMBEDDED_*` value. The order interleaves the budget instructions around the transfer so a
  /// scan that stops at the first, or the first matching, instruction is as wrong as one that
  /// scans them all.
  private static List<Instruction> transferWithEmbeddedBudget() {
    return List.of(
        computeBudgetInstruction(SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR, EMBEDDED_COMPUTE_UNIT_LIMIT),
        setComputeUnitPrice(EMBEDDED_COMPUTE_UNIT_PRICE),
        transfer(payer(), recipient()),
        computeBudgetInstruction(SET_LOADED_ACCOUNTS_DATA_SIZE_LIMIT_DISCRIMINATOR, EMBEDDED_ACCOUNT_DATA_SIZE_LIMIT),
        computeBudgetInstruction(REQUEST_HEAP_FRAME_DISCRIMINATOR, EMBEDDED_HEAP_SIZE)
    );
  }

  private static TxBuilder transferBuilder() {
    return TxBuilder.createBuilder()
        .feePayer(payer())
        .addInstruction(transfer(payer(), recipient()));
  }

  private static TxBuilder embeddedBudgetBuilder() {
    return TxBuilder.createBuilder()
        .feePayer(payer())
        .addInstructions(transferWithEmbeddedBudget());
  }

  private static Transaction v1(final TxBuilder builder) {
    final var tx = builder.createTransaction();
    assertInstanceOf(V1Transaction.class, tx);
    tx.setRecentBlockHash(blockHash());
    return tx;
  }

  private static V1TransactionSkeleton v1Skeleton(final Transaction tx) {
    return assertInstanceOf(V1TransactionSkeleton.class, TransactionSkeleton.deserializeSkeleton(tx.serialized()));
  }

  private static int configMask(final Transaction tx) {
    return v1Skeleton(tx).configMask();
  }

  /// Every ComputeBudgetProgram instruction the message carries, in message order, read back
  /// through the public parse path so the test can prove the instructions really are on the wire
  /// rather than silently dropped by the builder.
  private static Instruction[] embeddedComputeBudgetInstructions(final TransactionSkeleton skeleton) {
    final var computeBudgetProgram = SolanaAccounts.MAIN_NET.computeBudgetProgram();
    return Arrays.stream(skeleton.parseInstructionsWithoutAccounts())
        .filter(ix -> computeBudgetProgram.equals(ix.programId().publicKey()))
        .toArray(Instruction[]::new);
  }

  /// The instruction's leading data byte, i.e. its ComputeBudgetProgram discriminator.
  private static int discriminator(final Instruction ix) {
    return ix.data()[ix.offset()] & 0xFF;
  }

  /// Asserts that all four embedded ComputeBudgetProgram instructions survived serialization with
  /// their `EMBEDDED_*` payloads intact, so the precedence assertions that follow are not vacuous.
  private static void assertEmbeddedBudgetIsOnTheWire(final TransactionSkeleton skeleton) {
    assertEquals(5, skeleton.numInstructions(), "the transfer plus four compute budget instructions");
    final var embedded = embeddedComputeBudgetInstructions(skeleton);
    assertEquals(4, embedded.length);
    assertEquals(SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR, discriminator(embedded[0]));
    assertEquals(EMBEDDED_COMPUTE_UNIT_LIMIT, ByteUtil.getInt32LE(embedded[0].data(), embedded[0].offset() + 1));
    assertEquals(SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR, discriminator(embedded[1]));
    assertEquals(EMBEDDED_COMPUTE_UNIT_PRICE, ByteUtil.getInt64LE(embedded[1].data(), embedded[1].offset() + 1));
    assertEquals(SET_LOADED_ACCOUNTS_DATA_SIZE_LIMIT_DISCRIMINATOR, discriminator(embedded[2]));
    assertEquals(EMBEDDED_ACCOUNT_DATA_SIZE_LIMIT, ByteUtil.getInt32LE(embedded[2].data(), embedded[2].offset() + 1));
    assertEquals(REQUEST_HEAP_FRAME_DISCRIMINATOR, discriminator(embedded[3]));
    assertEquals(EMBEDDED_HEAP_SIZE, ByteUtil.getInt32LE(embedded[3].data(), embedded[3].offset() + 1));
  }

  /// Pins that `V1TransactionSkeleton#priorityFeeLamports()`, `#computeUnitLimit()`,
  /// `#accountDataSizeLimit()` and `#heapSize()` read the ConfigValues block and nothing else when
  /// every TransactionConfigMask bit is set and the message also embeds ComputeBudgetProgram
  /// instructions carrying different values.
  ///
  /// Fails if any reader falls back to, or prefers, the legacy instruction scan: the embedded
  /// values 999_999 / 777_777 / 888_888 / 128KiB would be returned in place of the ConfigValues
  /// 20_000 / 5_000 / 65_536 / 64KiB. Mutation-verified: making `computeUnitLimit()` return the
  /// embedded SetComputeUnitLimit value when one is present fails the 20_000 assertion here and the
  /// 0 assertion in [#v1ClearedConfigBitsReadZeroDespiteEmbeddedComputeBudgetInstructions].
  ///
  /// The instruction count and the parse of the embedded instructions are asserted first so the
  /// precedence assertions cannot pass by the builder having dropped the instructions: it does not,
  /// only `prototypeTransaction()` does, and that is pinned last.
  @Test
  void v1ReadsConfigValuesNotEmbeddedComputeBudgetInstructions() {
    final var tx = v1(embeddedBudgetBuilder()
        .priorityFeeLamports(PRIORITY_FEE_LAMPORTS)
        .computeUnitLimit(COMPUTE_UNIT_LIMIT)
        .accountDataSizeLimit(ACCOUNT_DATA_SIZE_LIMIT)
        .heapSize(HEAP_SIZE));

    final var skeleton = v1Skeleton(tx);
    assertEquals(1, skeleton.version());
    assertEquals(ALL_CONFIG_BITS, skeleton.configMask());
    assertEmbeddedBudgetIsOnTheWire(skeleton);

    // The ConfigValues are the only authority.
    assertEquals(PRIORITY_FEE_LAMPORTS, skeleton.priorityFeeLamports());
    assertEquals(COMPUTE_UNIT_LIMIT, skeleton.computeUnitLimit());
    assertEquals(ACCOUNT_DATA_SIZE_LIMIT, skeleton.accountDataSizeLimit());
    assertEquals(HEAP_SIZE, skeleton.heapSize());
    // Stated explicitly so a scan that wins over the ConfigValues is named, not merely unequal.
    assertNotEquals(EMBEDDED_COMPUTE_UNIT_LIMIT, skeleton.computeUnitLimit());
    assertNotEquals(EMBEDDED_ACCOUNT_DATA_SIZE_LIMIT, skeleton.accountDataSizeLimit());
    assertNotEquals(EMBEDDED_HEAP_SIZE, skeleton.heapSize());
    assertNotEquals(
        TxBuilder.computeUnitPriceToPriorityFeeLamports(EMBEDDED_COMPUTE_UNIT_PRICE, EMBEDDED_COMPUTE_UNIT_LIMIT),
        skeleton.priorityFeeLamports(),
        "a v1 priority fee is an absolute ConfigValue, never price × limit from embedded instructions"
    );

    // Prototyping carries the ConfigValues and drops the no-op instructions (existing behaviour,
    // pinned by V1PrototypeConfigTests for a single kind and here for all four together).
    final var prototype = skeleton.prototypeTransaction();
    assertEquals(PRIORITY_FEE_LAMPORTS, prototype.priorityFeeLamports());
    assertEquals(COMPUTE_UNIT_LIMIT, prototype.computeUnitLimit());
    assertEquals(ACCOUNT_DATA_SIZE_LIMIT, prototype.accountDataSizeLimit());
    assertEquals(HEAP_SIZE, prototype.heapSize());
    final var rebuilt = v1Skeleton(v1(prototype));
    assertEquals(1, rebuilt.numInstructions(), "prototypeTransaction drops every compute budget instruction");
    assertEquals(0, embeddedComputeBudgetInstructions(rebuilt).length);
    assertEquals(ALL_CONFIG_BITS, rebuilt.configMask());
    assertEquals(COMPUTE_UNIT_LIMIT, rebuilt.computeUnitLimit());
  }

  /// The mirror of [#v1ReadsConfigValuesNotEmbeddedComputeBudgetInstructions]: with every
  /// TransactionConfigMask bit cleared, the same embedded instructions must not leak in through the
  /// `offset < 0 ? 0 : ...` branch of each reader. An absent bit is 0 on a v1 message, full stop.
  ///
  /// Fails if any reader consults the instructions when its ConfigValue is absent — the tempting
  /// "use the ConfigValue if present, else scan" shape — because 999_999 / 777_777-derived /
  /// 888_888 / 128KiB would be read instead of 0. Also pins that the prototype carries the four 0s
  /// verbatim and that the rebuilt message reserves no slot for any of them.
  @Test
  void v1ClearedConfigBitsReadZeroDespiteEmbeddedComputeBudgetInstructions() {
    // No fee and no heap were ever requested; the two builder defaults are explicitly cleared.
    final var tx = v1(embeddedBudgetBuilder()
        .computeUnitLimit(0)
        .accountDataSizeLimit(0));

    final var skeleton = v1Skeleton(tx);
    assertEquals(0, skeleton.configMask());
    assertEmbeddedBudgetIsOnTheWire(skeleton);

    assertEquals(0L, skeleton.priorityFeeLamports());
    assertEquals(0, skeleton.computeUnitLimit());
    assertEquals(0, skeleton.accountDataSizeLimit());
    assertEquals(0, skeleton.heapSize());

    final var prototype = skeleton.prototypeTransaction();
    assertEquals(0L, prototype.priorityFeeLamports());
    assertEquals(0, prototype.computeUnitLimit());
    assertEquals(0, prototype.accountDataSizeLimit());
    assertEquals(0, prototype.heapSize());

    final var rebuiltTx = v1(prototype);
    final var rebuilt = v1Skeleton(rebuiltTx);
    assertEquals(0, rebuilt.configMask());
    assertEquals(1, rebuilt.numInstructions());
    assertEquals(0, embeddedComputeBudgetInstructions(rebuilt).length);
    // No slot exists for any value, so nothing can be updated in place.
    assertThrows(IllegalStateException.class, () -> rebuiltTx.setPriorityFeeLamports(PRIORITY_FEE_LAMPORTS));
    assertThrows(IllegalStateException.class, () -> rebuiltTx.setComputeUnitLimit(COMPUTE_UNIT_LIMIT));
    assertThrows(IllegalStateException.class, () -> rebuiltTx.setAccountDataSizeLimit(ACCOUNT_DATA_SIZE_LIMIT));
    assertThrows(IllegalStateException.class, () -> rebuiltTx.setHeapSize(HEAP_SIZE));
  }

  /// Negative control for the two precedence tests, executed rather than argued: the identical
  /// instruction list in a legacy message is read by the instruction scan, so the `EMBEDDED_*`
  /// values are exactly what a v1 reader that fell back to that scan would have returned. Nothing
  /// here is v1 behaviour; the test exists so the v1 assertions above are known to discriminate.
  @Test
  void legacyReaderScansTheSameEmbeddedInstructions() {
    final var tx = Transaction.createTx(payer(), transferWithEmbeddedBudget());
    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    assertTrue(skeleton.isLegacy());
    assertEmbeddedBudgetIsOnTheWire(skeleton);

    assertEquals(EMBEDDED_COMPUTE_UNIT_LIMIT, skeleton.computeUnitLimit());
    assertEquals(EMBEDDED_ACCOUNT_DATA_SIZE_LIMIT, skeleton.accountDataSizeLimit());
    assertEquals(EMBEDDED_HEAP_SIZE, skeleton.heapSize());
    assertEquals(
        TxBuilder.computeUnitPriceToPriorityFeeLamports(EMBEDDED_COMPUTE_UNIT_PRICE, EMBEDDED_COMPUTE_UNIT_LIMIT),
        skeleton.priorityFeeLamports()
    );
  }

  /// Pins `TxBuilderImpl#createTransaction()`'s `if (this.priorityFeeLamports != 0)` pair — the
  /// mask bit and the ConfigValue write: an explicit `priorityFeeLamports(0)` is byte-for-byte the
  /// same message as never calling the setter, with neither fee bit set.
  ///
  /// Fails if the builder starts reserving a fee slot for an explicit 0 (kit's shape), which
  /// would add the two fee bits and 8 bytes and so change both the mask and the array.
  @Test
  void builderZeroPriorityFeeIsByteIdenticalToUnset() {
    final var unset = v1(transferBuilder());
    final var explicitZero = v1(transferBuilder().priorityFeeLamports(0L));

    assertArrayEquals(unset.serialized(), explicitZero.serialized());
    assertEquals(0, configMask(unset) & PRIORITY_FEE_MASK);
    assertEquals(0, configMask(explicitZero) & PRIORITY_FEE_MASK);
    assertEquals(0L, v1Skeleton(explicitZero).priorityFeeLamports());
    assertThrows(IllegalStateException.class, () -> explicitZero.setPriorityFeeLamports(PRIORITY_FEE_LAMPORTS));

    // A non-zero fee is the only thing that reserves the slot: two bits, eight bytes.
    final var withFee = v1(transferBuilder().priorityFeeLamports(PRIORITY_FEE_LAMPORTS));
    assertEquals(PRIORITY_FEE_MASK, configMask(withFee) & PRIORITY_FEE_MASK);
    assertEquals(unset.size() + Long.BYTES, withFee.size());
  }

  /// Pins the builder's explicit clear for the two defaulted limits: `computeUnitLimit(0)` and
  /// `accountDataSizeLimit(0)` each drop their TransactionConfigMask bit and their 4-byte
  /// ConfigValue, so the message shrinks by exactly 4 bytes per cleared limit, and clearing both
  /// leaves an empty ConfigValues block. AGAVE_SYNC.md's divergence table row "`TxBuilder`
  /// defaults" is the contract: both limits are serialized by default so they can be updated in
  /// place, and `0` is the explicit clear.
  ///
  /// Fails if the builder keeps writing a slot for a 0 (size stops shrinking, the bit stays set),
  /// or if clearing one limit disturbs the other's bit. Mutation-verified: forcing the
  /// `if (this.computeUnitLimit != 0)` mask guard to always set the bit fails the mask and size
  /// assertions of the `withoutComputeUnitLimit` shape.
  @Test
  void builderZeroLimitsDropTheBitAndTheSlot() {
    final var defaults = v1(transferBuilder());
    assertEquals(COMPUTE_UNIT_LIMIT_MASK | ACCOUNT_DATA_SIZE_LIMIT_MASK, configMask(defaults));
    assertEquals(TxBuilderImpl.MAX_COMPUTE_UNIT_LIMIT, v1Skeleton(defaults).computeUnitLimit());
    assertEquals(TxBuilderImpl.MAX_ACCOUNT_DATA_SIZE_LIMIT, v1Skeleton(defaults).accountDataSizeLimit());

    final var withoutComputeUnitLimit = v1(transferBuilder().computeUnitLimit(0));
    assertEquals(ACCOUNT_DATA_SIZE_LIMIT_MASK, configMask(withoutComputeUnitLimit));
    assertEquals(defaults.size() - Integer.BYTES, withoutComputeUnitLimit.size());
    assertEquals(0, v1Skeleton(withoutComputeUnitLimit).computeUnitLimit());
    // The surviving neighbour is still readable at its shifted offset.
    assertEquals(TxBuilderImpl.MAX_ACCOUNT_DATA_SIZE_LIMIT, v1Skeleton(withoutComputeUnitLimit).accountDataSizeLimit());
    assertThrows(IllegalStateException.class, () -> withoutComputeUnitLimit.setComputeUnitLimit(COMPUTE_UNIT_LIMIT));

    final var withoutAccountDataSizeLimit = v1(transferBuilder().accountDataSizeLimit(0));
    assertEquals(COMPUTE_UNIT_LIMIT_MASK, configMask(withoutAccountDataSizeLimit));
    assertEquals(defaults.size() - Integer.BYTES, withoutAccountDataSizeLimit.size());
    assertEquals(0, v1Skeleton(withoutAccountDataSizeLimit).accountDataSizeLimit());
    assertEquals(TxBuilderImpl.MAX_COMPUTE_UNIT_LIMIT, v1Skeleton(withoutAccountDataSizeLimit).computeUnitLimit());
    assertThrows(IllegalStateException.class, () -> withoutAccountDataSizeLimit.setAccountDataSizeLimit(ACCOUNT_DATA_SIZE_LIMIT));

    final var withoutEither = v1(transferBuilder().computeUnitLimit(0).accountDataSizeLimit(0));
    assertEquals(0, configMask(withoutEither));
    assertEquals(defaults.size() - (2 * Integer.BYTES), withoutEither.size());
    assertEquals(0, v1Skeleton(withoutEither).computeUnitLimit());
    assertEquals(0, v1Skeleton(withoutEither).accountDataSizeLimit());

    // Everything outside the mask and the ConfigValues block is unchanged by the clear: the two
    // messages differ only in the mask word and the missing 4-byte slot.
    final byte[] full = defaults.serialized();
    final byte[] cleared = withoutComputeUnitLimit.serialized();
    final int slotOffset = V1TransactionSkeleton.configValueOffset(full, COMPUTE_UNIT_LIMIT_MASK);
    assertTrue(slotOffset > V1TransactionSkeleton.V1_ACCOUNTS_OFFSET);
    assertArrayEquals(
        Arrays.copyOfRange(full, V1TransactionSkeleton.V1_RECENT_BLOCK_HASH_INDEX, slotOffset),
        Arrays.copyOfRange(cleared, V1TransactionSkeleton.V1_RECENT_BLOCK_HASH_INDEX, slotOffset),
        "the bytes before the dropped slot are identical"
    );
    assertArrayEquals(
        Arrays.copyOfRange(full, slotOffset + Integer.BYTES, full.length),
        Arrays.copyOfRange(cleared, slotOffset, cleared.length),
        "the bytes after the dropped slot are identical, shifted back by one slot"
    );
  }

  /// Pins how the builder treats a 0 heap request: `TxBuilderImpl#heapSize(int)` reads 0 as
  /// "clear the request" and skips `checkHeapSize` (which would otherwise reject it as below the
  /// 32KiB floor), so `heapSize(0)` — even after a valid request — is byte-identical to never
  /// requesting a heap: no HEAP_SIZE bit and no slot. AGAVE_SYNC.md's rule 7/8 rows record the
  /// split: `TxBuilder#heapSize` rejects when strict *with 0 clearing the request*, while
  /// `Transaction#setHeapSize` rejects unconditionally — so the in-place path cannot write a 0 at
  /// all, which is pinned at the end. Neither path can produce a HEAP_SIZE slot holding 0.
  ///
  /// Fails if `heapSize(0)` starts throwing (the guard becomes `if (strict)`), if a 0 heap starts
  /// reserving a slot, or if `setHeapSize(0)` stops throwing. Mutation-verified: dropping the
  /// `heapSize != 0` term of the guard fails the first `heapSize(0)` call here with
  /// `IllegalArgumentException`.
  @Test
  void builderZeroHeapSizeClearsTheRequestWithoutValidation() {
    final var unset = v1(transferBuilder());
    assertEquals(0, configMask(unset) & HEAP_SIZE_MASK);

    final var explicitZero = v1(transferBuilder().heapSize(0));
    assertArrayEquals(unset.serialized(), explicitZero.serialized());

    // A cleared request after a valid one is the same message again.
    final var builder = transferBuilder().heapSize(HEAP_SIZE);
    assertEquals(HEAP_SIZE, builder.heapSize());
    builder.heapSize(0);
    assertEquals(0, builder.heapSize());
    assertArrayEquals(unset.serialized(), v1(builder).serialized());

    // A requested heap reserves the bit and one slot; the request is what the reader reports.
    final var withHeap = v1(transferBuilder().heapSize(HEAP_SIZE));
    assertEquals(HEAP_SIZE_MASK, configMask(withHeap) & HEAP_SIZE_MASK);
    assertEquals(unset.size() + Integer.BYTES, withHeap.size());
    assertEquals(HEAP_SIZE, v1Skeleton(withHeap).heapSize());

    // In place, 0 is not a clear: the slot cannot be removed, and 0 fails the unconditional bounds
    // check before anything is written.
    final byte[] before = withHeap.serialized().clone();
    assertThrows(IllegalArgumentException.class, () -> withHeap.setHeapSize(0));
    assertArrayEquals(before, withHeap.serialized(), "a refused heap size must not write");
    assertEquals(HEAP_SIZE, v1Skeleton(withHeap).heapSize());
    // Where there is no slot, the bounds check still comes first — 0 is rejected as a value, not
    // as a missing slot.
    assertThrows(IllegalArgumentException.class, () -> unset.setHeapSize(0));
    assertThrows(IllegalStateException.class, () -> unset.setHeapSize(HEAP_SIZE));
  }

  /// Pins `V1Transaction#setComputeUnitLimit(int)`, `#setAccountDataSizeLimit(int)` and
  /// `#setPriorityFeeLamports(long)` as in-place slot writes: on a transaction carrying all four
  /// TransactionConfigMask bits, writing 0 leaves the mask and the size untouched, the slot reads
  /// back as 0, and the neighbouring slots keep their values. That is the shape `@solana/kit`
  /// serializes for `e_explicit_zeros` (mask `0x0F`, fee / limit / data size all 0), which the
  /// builder cannot produce — contrast [#builderZeroLimitsDropTheBitAndTheSlot], where the same 0
  /// removes the bit and the slot. Writing a non-zero value back afterwards works in the same
  /// slot, so a 0 written in place is recoverable where a 0 given to the builder is not.
  ///
  /// Fails if an in-place 0 clears its bit (mask changes), re-serializes (size changes), or lands
  /// in the wrong slot (a neighbour changes). Mutation-verified: pointing
  /// `setAccountDataSizeLimit` at the COMPUTE_UNIT_LIMIT_MASK slot fails the data-size read-back and
  /// the compute-unit-limit neighbour check. The mask and size pins are value-verified only: the
  /// setters have no code path that could change either, so no single in-place mutation exercises
  /// them, and they exist to fail loudly should such a path ever be added.
  @Test
  void inPlaceZeroKeepsTheBitAndTheSlot() {
    final var tx = v1(transferBuilder()
        .priorityFeeLamports(PRIORITY_FEE_LAMPORTS)
        .computeUnitLimit(COMPUTE_UNIT_LIMIT)
        .accountDataSizeLimit(ACCOUNT_DATA_SIZE_LIMIT)
        .heapSize(HEAP_SIZE));
    assertEquals(ALL_CONFIG_BITS, configMask(tx));
    final int size = tx.size();
    final byte[] data = tx.serialized();

    assertSame(tx, tx.setAccountDataSizeLimit(0));
    assertSame(tx, tx.setComputeUnitLimit(0));
    assertSame(tx, tx.setPriorityFeeLamports(0L));

    // Written in place: same array, same length, same mask — every bit still set.
    assertSame(data, tx.serialized());
    assertEquals(size, tx.size());
    assertEquals(ALL_CONFIG_BITS, configMask(tx));

    final var zeroed = v1Skeleton(tx);
    assertEquals(0, zeroed.accountDataSizeLimit());
    assertEquals(0, zeroed.computeUnitLimit());
    assertEquals(0L, zeroed.priorityFeeLamports());
    // The untouched neighbour proves each write landed in its own slot rather than a shifted one.
    assertEquals(HEAP_SIZE, zeroed.heapSize());

    // Contrast with the builder: the same three zeros given to the builder drop three bits and
    // sixteen bytes, so the two shapes are distinguishable on the wire, and the builder's is
    // not updatable in place.
    final var builderZeros = v1(transferBuilder()
        .priorityFeeLamports(0L)
        .computeUnitLimit(0)
        .accountDataSizeLimit(0)
        .heapSize(HEAP_SIZE));
    assertEquals(HEAP_SIZE_MASK, configMask(builderZeros));
    assertEquals(size - (Long.BYTES + Integer.BYTES + Integer.BYTES), builderZeros.size());
    assertEquals(0, v1Skeleton(builderZeros).computeUnitLimit());
    assertEquals(0, v1Skeleton(builderZeros).accountDataSizeLimit());
    assertEquals(0L, v1Skeleton(builderZeros).priorityFeeLamports());
    assertThrows(IllegalStateException.class, () -> builderZeros.setComputeUnitLimit(COMPUTE_UNIT_LIMIT));

    // The slots survived the zeros, so the values come back in place.
    final int restoredComputeUnitLimit = 30_000;
    final int restoredAccountDataSizeLimit = 131_072;
    final long restoredPriorityFeeLamports = 9_999L;
    assertSame(tx, tx.setComputeUnitLimit(restoredComputeUnitLimit));
    assertSame(tx, tx.setAccountDataSizeLimit(restoredAccountDataSizeLimit));
    assertSame(tx, tx.setPriorityFeeLamports(restoredPriorityFeeLamports));
    assertSame(data, tx.serialized());
    assertEquals(size, tx.size());
    assertEquals(ALL_CONFIG_BITS, configMask(tx));
    final var restored = v1Skeleton(tx);
    assertEquals(restoredComputeUnitLimit, restored.computeUnitLimit());
    assertEquals(restoredAccountDataSizeLimit, restored.accountDataSizeLimit());
    assertEquals(restoredPriorityFeeLamports, restored.priorityFeeLamports());
    assertEquals(HEAP_SIZE, restored.heapSize());

    // Each individual write touches exactly its own slot: the array differs from the original in
    // no byte outside the three written slots.
    final byte[] original = v1(transferBuilder()
        .priorityFeeLamports(PRIORITY_FEE_LAMPORTS)
        .computeUnitLimit(COMPUTE_UNIT_LIMIT)
        .accountDataSizeLimit(ACCOUNT_DATA_SIZE_LIMIT)
        .heapSize(HEAP_SIZE)).serialized();
    final int feeOffset = V1TransactionSkeleton.configValueOffset(data, PRIORITY_FEE_MASK);
    final int heapOffset = V1TransactionSkeleton.configValueOffset(data, HEAP_SIZE_MASK);
    assertEquals(feeOffset + Long.BYTES + Integer.BYTES + Integer.BYTES, heapOffset, "fee, limit, data size, heap: ascending bit order");
    assertArrayEquals(Arrays.copyOfRange(original, 0, feeOffset), Arrays.copyOfRange(data, 0, feeOffset));
    assertArrayEquals(Arrays.copyOfRange(original, heapOffset, original.length), Arrays.copyOfRange(data, heapOffset, data.length));
  }
}
