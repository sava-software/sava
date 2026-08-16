package software.sava.core.tx;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.ByteUtil;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.tx.V1TransactionSkeleton.ACCOUNT_DATA_SIZE_LIMIT_MASK;
import static software.sava.core.tx.V1TransactionSkeleton.COMPUTE_UNIT_LIMIT_MASK;
import static software.sava.core.tx.V1TransactionSkeleton.HEAP_SIZE_MASK;
import static software.sava.core.tx.V1TransactionSkeleton.PRIORITY_FEE_MASK;

/// Covers {@link V1TransactionSkeleton#prototypeTransaction(Instruction[])} carrying v1 ConfigValues
/// through verbatim, the deliberately asymmetric legacy/v0 {@link TransactionSkeleton} default which
/// skips an unset 0, and {@link TxBuilderImpl#withoutComputeBudgetInstructions(Instruction[])}.
final class V1PrototypeConfigTests {

  private static final long PRIORITY_FEE_LAMPORTS = 5_000L;
  private static final int COMPUTE_UNIT_LIMIT = 200_000;
  private static final int ACCOUNT_DATA_SIZE_LIMIT = 65_536;
  private static final int HEAP_SIZE = 64 * 1_024;

  // Compute Budget instruction discriminators, per ComputeBudgetProgram.
  private static final int REQUEST_HEAP_FRAME_DISCRIMINATOR = 1;
  private static final int SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR = 2;
  private static final int SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR = 3;
  private static final int SET_LOADED_ACCOUNTS_DATA_SIZE_LIMIT_DISCRIMINATOR = 4;

  /// AGENTS.md requires randomized tests to use fixed seeds: PIT re-runs the suite once per mutant,
  /// so a generated key pair would make a kill non-reproducible. The counter is reset before each
  /// test so the keys depend on neither execution order nor how many tests ran first.
  private static final AtomicInteger KEY_SEED = new AtomicInteger();

  @BeforeEach
  void resetKeySeed() {
    KEY_SEED.set(0);
  }

  private static Signer nextSigner() {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    Arrays.fill(privateKey, (byte) KEY_SEED.incrementAndGet());
    return Signer.createFromPrivateKey(privateKey);
  }

  private static Signer newSigner() {
    return nextSigner();
  }

  /// A one byte, account free instruction whose single data byte identifies it, so an instruction
  /// list can be compared positionally after a round trip through the wire format.
  private static Instruction markerInstruction(final int marker) {
    return Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(),
        new byte[]{(byte) marker}
    );
  }

  private static Instruction computeBudgetInstruction(final int discriminator, final int value) {
    final byte[] data = new byte[5];
    data[0] = (byte) discriminator;
    ByteUtil.putInt32LE(data, 1, value);
    return Instruction.createInstruction(SolanaAccounts.MAIN_NET.invokedComputeBudgetProgram(), List.of(), data);
  }

  private static Instruction requestHeapFrame(final int heapSize) {
    return computeBudgetInstruction(REQUEST_HEAP_FRAME_DISCRIMINATOR, heapSize);
  }

  private static Instruction setComputeUnitLimit(final int units) {
    return computeBudgetInstruction(SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR, units);
  }

  private static Instruction setLoadedAccountsDataSizeLimit(final int accountDataSizeLimit) {
    return computeBudgetInstruction(SET_LOADED_ACCOUNTS_DATA_SIZE_LIMIT_DISCRIMINATOR, accountDataSizeLimit);
  }

  private static Instruction setComputeUnitPrice(final long microLamports) {
    final byte[] data = new byte[9];
    data[0] = (byte) SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR;
    ByteUtil.putInt64LE(data, 1, microLamports);
    return Instruction.createInstruction(SolanaAccounts.MAIN_NET.invokedComputeBudgetProgram(), List.of(), data);
  }

  /// Builds the transaction and reads back the leading data byte of each serialized instruction, so
  /// a builder's instruction list is observed through the public API rather than its internals.
  private static byte[] instructionMarkers(final TxBuilder builder) {
    final var skeleton = TransactionSkeleton.deserializeSkeleton(builder.createTransaction().serialized());
    final var instructions = skeleton.parseInstructionsWithoutAccounts();
    final byte[] markers = new byte[instructions.length];
    for (int i = 0; i < instructions.length; ++i) {
      final var ix = instructions[i];
      markers[i] = ix.data()[ix.offset()];
    }
    return markers;
  }

  private static Transaction v1WithClearedLimits(final PublicKey feePayer) {
    return TxBuilder.createBuilder()
        .feePayer(feePayer)
        .addInstruction(markerInstruction(11))
        .priorityFeeLamports(PRIORITY_FEE_LAMPORTS)
        .heapSize(HEAP_SIZE)
        .computeUnitLimit(0)
        .accountDataSizeLimit(0)
        .createTransaction();
  }

  /// FIX 5. Pins `V1TransactionSkeleton#prototypeTransaction(Instruction[])`'s
  /// `.computeUnitLimit(computeUnitLimit())` and `.accountDataSizeLimit(accountDataSizeLimit())`.
  ///
  /// Reverting the override delegates to `TransactionSkeleton#prototypeTransaction(Instruction[])`,
  /// whose `if (computeUnitLimit != 0)` / `if (accountDataSizeLimit != 0)` guards leave the
  /// [TxBuilderImpl] constructor defaults of 1.4M units and 64MiB in place, so both assertions of 0
  /// below fail.
  @Test
  void testV1PrototypeCarriesClearedLimitsVerbatim() {
    final var feePayer = newSigner();
    final var tx = v1WithClearedLimits(feePayer.publicKey());

    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    final var v1Skeleton = assertInstanceOf(V1TransactionSkeleton.class, skeleton);
    // Neither limit bit is set on the wire, so per SIMD-0385 both values really are 0 rather than
    // "no compute budget instruction was present".
    assertEquals(PRIORITY_FEE_MASK | HEAP_SIZE_MASK, v1Skeleton.configMask());
    assertEquals(0, v1Skeleton.configMask() & COMPUTE_UNIT_LIMIT_MASK);
    assertEquals(0, v1Skeleton.configMask() & ACCOUNT_DATA_SIZE_LIMIT_MASK);
    assertEquals(0, skeleton.computeUnitLimit());
    assertEquals(0, skeleton.accountDataSizeLimit());

    final var prototype = skeleton.prototypeTransaction();
    assertEquals(0, prototype.computeUnitLimit());
    assertEquals(0, prototype.accountDataSizeLimit());
    // The pre-fix values, stated explicitly so the negative control is self evident.
    assertNotEquals(TxBuilderImpl.MAX_COMPUTE_UNIT_LIMIT, prototype.computeUnitLimit());
    assertNotEquals(TxBuilderImpl.MAX_ACCOUNT_DATA_SIZE_LIMIT, prototype.accountDataSizeLimit());
    assertNotEquals(1_400_000, prototype.computeUnitLimit());
    assertNotEquals(67_108_864, prototype.accountDataSizeLimit());

    // The remaining two ConfigValues are carried over by both the override and the default.
    assertEquals(PRIORITY_FEE_LAMPORTS, prototype.priorityFeeLamports());
    assertEquals(HEAP_SIZE, prototype.heapSize());
    assertEquals(feePayer.publicKey(), prototype.feePayer().publicKey());
    assertTrue(prototype.feePayer().feePayer());

    // Negative control, executed rather than argued. The interface default this override replaced is
    // still reachable, unmodified, through any legacy skeleton. Feeding it the identical 0 compute
    // unit limit and 0 accounts data size limit shows exactly what the v1 skeleton returned before
    // the override existed: the builder's runtime maximums, not 0.
    final var legacySkeleton = TransactionSkeleton.deserializeSkeleton(
        Transaction.createTx(feePayer.publicKey(), List.of(markerInstruction(11))).serialized()
    );
    assertEquals(skeleton.computeUnitLimit(), legacySkeleton.computeUnitLimit());
    assertEquals(skeleton.accountDataSizeLimit(), legacySkeleton.accountDataSizeLimit());
    final var defaultPrototype = legacySkeleton.prototypeTransaction();
    assertEquals(TxBuilderImpl.MAX_COMPUTE_UNIT_LIMIT, defaultPrototype.computeUnitLimit());
    assertEquals(TxBuilderImpl.MAX_ACCOUNT_DATA_SIZE_LIMIT, defaultPrototype.accountDataSizeLimit());
    // Same skipped-0 input, different carried-over output: that difference is the whole fix.
    assertNotEquals(defaultPrototype.computeUnitLimit(), prototype.computeUnitLimit());
    assertNotEquals(defaultPrototype.accountDataSizeLimit(), prototype.accountDataSizeLimit());
  }

  /// FIX 5. Pins the same two override lines through a full serialization round trip: the prototyped
  /// transaction must not acquire TransactionConfigMask bits the source did not have.
  ///
  /// Reverting the override raises the builder to 1.4M units and 64MiB, both of which are non-zero,
  /// so [TxBuilderImpl#createTransaction()] sets bits 2 and 3 and writes two extra ConfigValues; the
  /// mask assertion, the 0/0 assertions and the two `assertThrows` all fail.
  @Test
  void testV1PrototypeClearedLimitsRoundTrip() {
    final var feePayer = newSigner();
    final var skeleton = TransactionSkeleton.deserializeSkeleton(v1WithClearedLimits(feePayer.publicKey()).serialized());

    final var rebuilt = skeleton.prototypeTransaction().createTransaction();
    final var rebuiltSkeleton = TransactionSkeleton.deserializeSkeleton(rebuilt.serialized());
    final var v1Rebuilt = assertInstanceOf(V1TransactionSkeleton.class, rebuiltSkeleton);

    assertEquals(PRIORITY_FEE_MASK | HEAP_SIZE_MASK, v1Rebuilt.configMask());
    assertEquals(0, rebuiltSkeleton.computeUnitLimit());
    assertEquals(0, rebuiltSkeleton.accountDataSizeLimit());
    assertEquals(PRIORITY_FEE_LAMPORTS, rebuiltSkeleton.priorityFeeLamports());
    assertEquals(HEAP_SIZE, rebuiltSkeleton.heapSize());

    // No ConfigValue slot was reserved for either limit, which is only true if the 0s were carried
    // through rather than replaced by the builder defaults.
    assertThrows(IllegalStateException.class, () -> rebuilt.setComputeUnitLimit(COMPUTE_UNIT_LIMIT));
    assertThrows(IllegalStateException.class, () -> rebuilt.setAccountDataSizeLimit(ACCOUNT_DATA_SIZE_LIMIT));
  }

  /// FIX 5. Guards the override against over-correcting: a v1 source with both limit bits set must
  /// carry those exact values, not the builder defaults and not 0.
  ///
  /// Pins `.computeUnitLimit(computeUnitLimit())` / `.accountDataSizeLimit(accountDataSizeLimit())`
  /// against being hard coded, e.g. to 0 or to the runtime maximums.
  @Test
  void testV1PrototypeCarriesNonZeroLimitsVerbatim() {
    final var feePayer = newSigner();
    final var tx = TxBuilder.createBuilder()
        .feePayer(feePayer.publicKey())
        .addInstruction(markerInstruction(11))
        .priorityFeeLamports(PRIORITY_FEE_LAMPORTS)
        .computeUnitLimit(COMPUTE_UNIT_LIMIT)
        .accountDataSizeLimit(ACCOUNT_DATA_SIZE_LIMIT)
        .heapSize(HEAP_SIZE)
        .createTransaction();

    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    final var v1Skeleton = assertInstanceOf(V1TransactionSkeleton.class, skeleton);
    assertEquals(
        PRIORITY_FEE_MASK | COMPUTE_UNIT_LIMIT_MASK | ACCOUNT_DATA_SIZE_LIMIT_MASK | HEAP_SIZE_MASK,
        v1Skeleton.configMask()
    );

    final var prototype = skeleton.prototypeTransaction();
    assertEquals(COMPUTE_UNIT_LIMIT, prototype.computeUnitLimit());
    assertEquals(ACCOUNT_DATA_SIZE_LIMIT, prototype.accountDataSizeLimit());
    assertEquals(PRIORITY_FEE_LAMPORTS, prototype.priorityFeeLamports());
    assertEquals(HEAP_SIZE, prototype.heapSize());

    final var rebuiltSkeleton = TransactionSkeleton.deserializeSkeleton(
        prototype.createTransaction().serialized()
    );
    assertEquals(COMPUTE_UNIT_LIMIT, rebuiltSkeleton.computeUnitLimit());
    assertEquals(ACCOUNT_DATA_SIZE_LIMIT, rebuiltSkeleton.accountDataSizeLimit());
    assertEquals(PRIORITY_FEE_LAMPORTS, rebuiltSkeleton.priorityFeeLamports());
    assertEquals(HEAP_SIZE, rebuiltSkeleton.heapSize());
  }

  /// FIX 5, legacy regression. Pins the deliberate asymmetry: the interface default
  /// `TransactionSkeleton#prototypeTransaction(Instruction[])` must keep skipping an unset 0 for a
  /// legacy source, because there a 0 means "no SetComputeUnit* instruction was present, so the
  /// runtime default applied".
  ///
  /// Pins the `if (computeUnitLimit != 0)` and `if (accountDataSizeLimit != 0)` guards; deleting
  /// either — the obvious way to "fix" the asymmetry once the v1 override exists — drops the builder
  /// to 0/0 and fails the two maximum assertions plus the two in-place setters below.
  @Test
  void testLegacyPrototypeStillSkipsUnsetLimits() {
    final var feePayer = newSigner();
    final var tx = Transaction.createTx(feePayer.publicKey(), List.of(markerInstruction(11)));

    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    assertTrue(skeleton.isLegacy());
    assertEquals(0, skeleton.computeUnitLimit());
    assertEquals(0, skeleton.accountDataSizeLimit());
    assertEquals(0L, skeleton.priorityFeeLamports());
    assertEquals(0, skeleton.heapSize());

    final var prototype = skeleton.prototypeTransaction();
    assertEquals(TxBuilderImpl.MAX_COMPUTE_UNIT_LIMIT, prototype.computeUnitLimit());
    assertEquals(TxBuilderImpl.MAX_ACCOUNT_DATA_SIZE_LIMIT, prototype.accountDataSizeLimit());
    assertNotEquals(0, prototype.computeUnitLimit());
    assertNotEquals(0, prototype.accountDataSizeLimit());
    assertEquals(0L, prototype.priorityFeeLamports());
    assertEquals(0, prototype.heapSize());

    // Both ConfigValue slots are therefore reserved on the wire and update in place.
    final var v1Tx = prototype.createTransaction();
    final var v1Skeleton = assertInstanceOf(
        V1TransactionSkeleton.class,
        TransactionSkeleton.deserializeSkeleton(v1Tx.serialized())
    );
    assertEquals(COMPUTE_UNIT_LIMIT_MASK | ACCOUNT_DATA_SIZE_LIMIT_MASK, v1Skeleton.configMask());
    assertSame(v1Tx, v1Tx.setComputeUnitLimit(COMPUTE_UNIT_LIMIT));
    assertSame(v1Tx, v1Tx.setAccountDataSizeLimit(ACCOUNT_DATA_SIZE_LIMIT));
  }

  /// FIX 5. The v1 override must not lose the ComputeBudgetProgram filtering the interface default
  /// performs: pins `.addInstructions(TxBuilderImpl.withoutComputeBudgetInstructions(instructions))`
  /// in `V1TransactionSkeleton#prototypeTransaction(Instruction[])`.
  ///
  /// Dropping the `withoutComputeBudgetInstructions` wrapper leaves five instructions whose markers
  /// are {11, 3, 22, 2, 33}, failing the array comparison.
  @Test
  void testV1PrototypeDropsComputeBudgetInstructions() {
    final var feePayer = newSigner();
    final var tx = TxBuilder.createBuilder()
        .feePayer(feePayer.publicKey())
        .addInstruction(markerInstruction(11))
        .addInstruction(setComputeUnitPrice(25_000L))
        .addInstruction(markerInstruction(22))
        .addInstruction(setComputeUnitLimit(COMPUTE_UNIT_LIMIT))
        .addInstruction(markerInstruction(33))
        .createTransaction();

    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    assertEquals(5, skeleton.numInstructions());

    // Non compute budget instructions survive in their original order.
    assertArrayEquals(new byte[]{11, 22, 33}, instructionMarkers(skeleton.prototypeTransaction()));
  }

  /// FIX 5. The same filtering for a legacy source, which reaches
  /// `TxBuilderImpl#withoutComputeBudgetInstructions` through the interface default rather than the
  /// v1 override. All four ComputeBudgetProgram instruction kinds are exercised.
  @Test
  void testLegacyPrototypeDropsComputeBudgetInstructions() {
    final var feePayer = newSigner();
    final var tx = Transaction.createTx(feePayer.publicKey(), List.of(
        setComputeUnitPrice(25_000L),
        markerInstruction(11),
        setComputeUnitLimit(COMPUTE_UNIT_LIMIT),
        markerInstruction(22),
        setLoadedAccountsDataSizeLimit(ACCOUNT_DATA_SIZE_LIMIT),
        markerInstruction(33),
        requestHeapFrame(HEAP_SIZE)
    ));

    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    assertEquals(7, skeleton.numInstructions());
    assertEquals(COMPUTE_UNIT_LIMIT, skeleton.computeUnitLimit());
    assertEquals(ACCOUNT_DATA_SIZE_LIMIT, skeleton.accountDataSizeLimit());
    assertEquals(HEAP_SIZE, skeleton.heapSize());

    assertArrayEquals(new byte[]{11, 22, 33}, instructionMarkers(skeleton.prototypeTransaction()));
  }

  /// FIX 5, extraction. Pins the fast path of
  /// `TxBuilderImpl#withoutComputeBudgetInstructions(Instruction[])`:
  /// `numRetained == instructions.length ? instructions : Arrays.copyOfRange(...)`.
  ///
  /// Forcing the copy branch fails `assertSame`; forcing the identity branch fails the mixed and
  /// all-compute-budget cases below.
  @Test
  void testWithoutComputeBudgetInstructions() {
    final var marker1 = markerInstruction(11);
    final var marker2 = markerInstruction(22);
    final var price = setComputeUnitPrice(25_000L);
    final var limit = setComputeUnitLimit(COMPUTE_UNIT_LIMIT);
    final var dataSize = setLoadedAccountsDataSizeLimit(ACCOUNT_DATA_SIZE_LIMIT);

    // No compute budget instructions: the same array instance is returned, never a copy.
    final var none = new Instruction[]{marker1, marker2};
    assertSame(none, TxBuilderImpl.withoutComputeBudgetInstructions(none));

    // An empty input also takes the identity fast path.
    final var empty = new Instruction[0];
    assertSame(empty, TxBuilderImpl.withoutComputeBudgetInstructions(empty));

    // All compute budget instructions: everything is dropped.
    final var all = new Instruction[]{price, limit, dataSize};
    final var noneRetained = TxBuilderImpl.withoutComputeBudgetInstructions(all);
    assertNotSame(all, noneRetained);
    assertEquals(0, noneRetained.length);

    // A mix retains only the non compute budget instructions, in order.
    final var mixed = new Instruction[]{price, marker1, limit, marker2, dataSize};
    final var retained = TxBuilderImpl.withoutComputeBudgetInstructions(mixed);
    assertNotSame(mixed, retained);
    assertArrayEquals(new Instruction[]{marker1, marker2}, retained);
    // The input array is not mutated.
    assertArrayEquals(new Instruction[]{price, marker1, limit, marker2, dataSize}, mixed);

    // A single trailing compute budget instruction still forces the copy branch.
    final var trailing = new Instruction[]{marker1, marker2, limit};
    final var withoutTrailing = TxBuilderImpl.withoutComputeBudgetInstructions(trailing);
    assertNotSame(trailing, withoutTrailing);
    assertArrayEquals(new Instruction[]{marker1, marker2}, withoutTrailing);
  }

  /// Coverage for `TxBuilder#createBuilder()` and the [TxBuilderImpl] constructor defaults the v1
  /// prototype override relies on being overwritten.
  @Test
  void testCreateBuilderDefaults() {
    final var builder = TxBuilder.createBuilder();
    assertNotNull(builder);
    assertInstanceOf(TxBuilderImpl.class, builder);

    assertTrue(builder.strict());
    assertNull(builder.feePayer());
    assertEquals(0L, builder.priorityFeeLamports());
    assertEquals(0, builder.heapSize());
    assertEquals(TxBuilderImpl.MAX_COMPUTE_UNIT_LIMIT, builder.computeUnitLimit());
    assertEquals(TxBuilderImpl.MAX_ACCOUNT_DATA_SIZE_LIMIT, builder.accountDataSizeLimit());

    // Each call yields an independent builder.
    final var other = TxBuilder.createBuilder();
    assertNotSame(builder, other);
    builder.computeUnitLimit(1);
    assertEquals(TxBuilderImpl.MAX_COMPUTE_UNIT_LIMIT, other.computeUnitLimit());

    // Strict mode toggles both ways.
    other.strict(false);
    assertFalse(other.strict());
    other.strict(true);
    assertTrue(other.strict());
  }

  /// Coverage for `TxBuilderImpl#feePayer(PublicKey)`'s null branch and its wrapping of a bare key.
  @Test
  void testBuilderFeePayerSetters() {
    final var key = newSigner().publicKey();
    final var builder = TxBuilder.createBuilder();

    assertSame(builder, builder.feePayer((PublicKey) null));
    assertNull(builder.feePayer());

    assertSame(builder, builder.feePayer(key));
    assertEquals(key, builder.feePayer().publicKey());
    assertTrue(builder.feePayer().feePayer());
    assertTrue(builder.feePayer().signer());
    assertTrue(builder.feePayer().write());

    final var meta = AccountMeta.createFeePayer(key);
    assertSame(builder, builder.feePayer(meta));
    assertSame(meta, builder.feePayer());

    assertSame(builder, builder.feePayer((AccountMeta) null));
    assertNull(builder.feePayer());
  }

  /// Coverage for the exact `TxBuilderImpl#checkHeapSize(int)` boundaries, which the existing suite
  /// only probes well away from: 32KiB and 256KiB are inclusive, and a 0 clears the request without
  /// validation.
  @Test
  void testBuilderHeapSizeBoundaries() {
    final int minHeapSize = 32 * 1_024;
    final int maxHeapSize = 256 * 1_024;

    final var builder = TxBuilder.createBuilder();
    assertSame(builder, builder.heapSize(minHeapSize));
    assertEquals(minHeapSize, builder.heapSize());
    builder.heapSize(maxHeapSize);
    assertEquals(maxHeapSize, builder.heapSize());

    // 0 clears the request and skips validation entirely.
    builder.heapSize(0);
    assertEquals(0, builder.heapSize());

    // One 1KiB step outside each inclusive bound.
    assertThrows(IllegalArgumentException.class, () -> builder.heapSize(minHeapSize - 1_024));
    assertThrows(IllegalArgumentException.class, () -> builder.heapSize(maxHeapSize + 1_024));
    // In range but not a multiple of 1KiB.
    assertThrows(IllegalArgumentException.class, () -> builder.heapSize(minHeapSize + 1));
    assertThrows(IllegalArgumentException.class, () -> builder.heapSize(-1_024));
    // The rejected values never reach the field.
    assertEquals(0, builder.heapSize());

    // Non strict mode skips the validation.
    final var lax = TxBuilder.createBuilder();
    lax.strict(false);
    lax.heapSize(1);
    assertEquals(1, lax.heapSize());
  }

  /// Coverage for `TxBuilderImpl#priorityFeeLamportsFromComputeUnitPrice(long)`, which must price
  /// against the builder's current compute unit limit rather than a constant.
  @Test
  void testBuilderPriorityFeeFromComputeUnitPrice() {
    final var builder = TxBuilder.createBuilder();
    // The default 1.4M unit limit: 1 * 1,400,000 micro-lamports rounds up to 2 lamports.
    assertSame(builder, builder.priorityFeeLamportsFromComputeUnitPrice(1L));
    assertEquals(2L, builder.priorityFeeLamports());

    builder.computeUnitLimit(COMPUTE_UNIT_LIMIT);
    builder.priorityFeeLamportsFromComputeUnitPrice(25_000L);
    assertEquals(PRIORITY_FEE_LAMPORTS, builder.priorityFeeLamports());

    // A cleared compute unit limit prices at 0.
    builder.computeUnitLimit(0);
    builder.priorityFeeLamportsFromComputeUnitPrice(25_000L);
    assertEquals(0L, builder.priorityFeeLamports());

    // A 0 price is also free, whatever the limit.
    builder.computeUnitLimit(COMPUTE_UNIT_LIMIT);
    builder.priorityFeeLamportsFromComputeUnitPrice(0L);
    assertEquals(0L, builder.priorityFeeLamports());
  }

  /// Regression pin for a defect found while writing these tests and **since fixed**:
  /// `TxBuilderImpl#addInstructions(List)` used to assign the caller's list to its field rather than
  /// copying it, so the builder returned by `prototypeTransaction` did not own its instruction list
  /// and could neither be extended nor edited safely. It now copies, matching the
  /// `SequencedCollection` overload. The mechanism, retained because it is what this test pins:
  ///
  /// `TxBuilder#addInstructions(Instruction[])` delegates to `addInstructions(Arrays.asList(...))`,
  /// which binds the `List` overload. When that overload assigned rather than copied, its field
  /// became the fixed-size `Arrays.asList` view of the very array
  /// `TxBuilderImpl#withoutComputeBudgetInstructions` returns, which on its fast path is the
  /// caller's own array. That had two consequences, both still asserted here so a regression is
  /// caught:
  ///
  ///  1. `setInstruction` wrote straight through into the caller's `Instruction[]`.
  ///  2. `addInstruction` and `insertInstruction` threw `UnsupportedOperationException`, which
  ///     defeats the documented purpose of a prototype.
  ///
  /// Either fix works — copying in `TxBuilderImpl#addInstructions(List)`, which is what was done, or
  /// routing the `TxBuilder#addInstructions(Instruction[])` default to the `SequencedCollection`
  /// overload.
  @Test
  void testPrototypeBuilderOwnsItsInstructionList() {
    final var feePayer = newSigner();
    final var tx = TxBuilder.createBuilder()
        .feePayer(feePayer.publicKey())
        .addInstruction(markerInstruction(11))
        .createTransaction();

    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    final var instructions = skeleton.parseInstructionsWithoutTableAccounts();
    final var prototype = skeleton.prototypeTransaction(instructions);

    assertAll(
        () -> {
          prototype.setInstruction(0, markerInstruction(99));
          final var first = instructions[0];
          assertEquals(
              (byte) 11, first.data()[first.offset()],
              "prototypeTransaction's builder must not write through to the caller's Instruction[]"
          );
        },
        () -> assertDoesNotThrow(
            () -> prototype.addInstruction(markerInstruction(22)),
            "a prototype builder must accept additional instructions"
        ),
        // insertInstruction is the other fixed-size casualty: Arrays.asList rejects it for the same
        // reason it rejects addInstruction, so the javadoc's claim is only true if both are pinned.
        () -> assertDoesNotThrow(
            () -> prototype.insertInstruction(0, markerInstruction(33)),
            "a prototype builder must accept inserted instructions"
        )
    );
  }

  /// Coverage for the `TxBuilderImpl#createTransaction()` instruction count validations, both the
  /// null list guard and the strict empty list guard, plus the non strict fall through into
  /// `mergeAccounts`.
  @Test
  void testBuilderCreateTransactionRequiresInstructions() {
    final var key = newSigner().publicKey();

    final var noInstructions = TxBuilder.createBuilder().feePayer(key);
    assertThrows(IllegalStateException.class, noInstructions::createTransaction);

    // The null list guard is not gated on strict mode.
    final var laxNoInstructions = TxBuilder.createBuilder().feePayer(key);
    laxNoInstructions.strict(false);
    assertThrows(IllegalStateException.class, laxNoInstructions::createTransaction);

    final var emptyStrict = TxBuilder.createBuilder().feePayer(key).addInstructions(List.<Instruction>of());
    assertThrows(IllegalArgumentException.class, emptyStrict::createTransaction);

    // Without strict mode the empty list reaches mergeAccounts, which rejects it too.
    final var emptyLax = TxBuilder.createBuilder().feePayer(key).addInstructions(List.<Instruction>of());
    emptyLax.strict(false);
    assertThrows(IllegalArgumentException.class, emptyLax::createTransaction);
  }
}
