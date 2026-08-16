package software.sava.core.tx;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.SequencedCollection;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.tx.TxBuilderImpl.MAX_SERIALIZED_LENGTH_V1;
import static software.sava.core.tx.TxBuilderImpl.MAX_V1_ACCOUNTS;
import static software.sava.core.tx.TxBuilderImpl.MAX_V1_INSTRUCTIONS;
import static software.sava.core.tx.TxBuilderImpl.MAX_V1_SIGNATURES;

/// Pins the [TxBuilder] validation branches which only a fixture sitting exactly on a limit can
/// distinguish: each of the SIMD-0385 v1 population limits is exercised at the limit, one past it,
/// and — where the limit is gated on strict mode — one past it with strict mode disabled.
///
/// Every fixture is deliberately shaped so that only the limit under test can be the one that
/// rejects it. The instruction count fixtures share a single read account, the account count
/// fixtures carry a single instruction, and none of them approach the serialized size limit, so a
/// removed check cannot be masked by a later one throwing the same exception type.
final class TxBuilderValidationTests {

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

  private static PublicKey newKey() {
    return nextSigner().publicKey();
  }

  /// A one byte, account free instruction whose single data byte identifies it positionally.
  private static Instruction markerInstruction(final int marker) {
    return Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(),
        new byte[]{(byte) marker}
    );
  }

  /// Reads back the leading data byte of each serialized instruction, so a builder's instruction
  /// list is observed through the wire format rather than through the list it was handed.
  private static byte[] instructionMarkers(final Transaction tx) {
    final var instructions = TransactionSkeleton.deserializeSkeleton(tx.serialized())
        .parseInstructionsWithoutAccounts();
    final byte[] markers = new byte[instructions.length];
    for (int i = 0; i < instructions.length; ++i) {
      final var ix = instructions[i];
      markers[i] = ix.data()[ix.offset()];
    }
    return markers;
  }

  /// `numInstructions` instructions which between them reference exactly one account, so the
  /// transaction holds three accounts no matter how many instructions it carries.
  private static List<Instruction> sharedAccountInstructions(final int numInstructions, final PublicKey account) {
    final var instructions = new ArrayList<Instruction>(numInstructions);
    final var meta = AccountMeta.createRead(account);
    for (int i = 0; i < numInstructions; ++i) {
      instructions.add(Instruction.createInstruction(
          SolanaAccounts.MAIN_NET.systemProgram(),
          List.of(meta),
          new byte[]{(byte) i}
      ));
    }
    return List.copyOf(instructions);
  }

  /// A single instruction referencing `numAccounts` distinct read accounts.
  private static Instruction instructionWithReadAccounts(final int numAccounts) {
    final var accounts = new ArrayList<AccountMeta>(numAccounts);
    for (int i = 0; i < numAccounts; ++i) {
      accounts.add(AccountMeta.createRead(newKey()));
    }
    return Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.copyOf(accounts),
        new byte[]{1}
    );
  }

  /// A single instruction referencing `numSigners` distinct writable signers, each of which adds a
  /// required signature on top of the fee payer's.
  private static Instruction instructionWithWritableSigners(final int numSigners) {
    final var accounts = new ArrayList<AccountMeta>(numSigners);
    for (int i = 0; i < numSigners; ++i) {
      accounts.add(AccountMeta.createWritableSigner(newKey()));
    }
    return Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.copyOf(accounts),
        new byte[]{1}
    );
  }

  private static TxBuilder laxBuilder(final PublicKey feePayer) {
    final var builder = TxBuilder.createBuilder().feePayer(feePayer);
    builder.strict(false);
    return builder;
  }

  /// The `numInstructions > MAX_V1_INSTRUCTIONS` limit, at and one past the boundary.
  ///
  /// Exactly 64 instructions must build: a `>=` boundary would reject them. 65 must be rejected
  /// while strict, and only by the instruction count check — the fixture holds three accounts and
  /// well under 4KiB, so neither the account nor the size check can stand in for it, which is what
  /// makes a removed `if (strict)` or a removed comparison observable.
  @Test
  void testStrictInstructionCountBoundary() {
    final var feePayer = newKey();
    final var account = newKey();

    final var atLimit = TxBuilder.createBuilder()
        .feePayer(feePayer)
        .addInstructions(sharedAccountInstructions(MAX_V1_INSTRUCTIONS, account))
        .createTransaction();
    assertEquals(MAX_V1_INSTRUCTIONS, atLimit.numInstructions());
    assertFalse(atLimit.exceedsInstructionLimit());
    // The fee payer, the shared read account and the system program.
    assertEquals(3, atLimit.numAccounts());
    assertFalse(atLimit.exceedsAccountLimit());
    assertFalse(atLimit.exceedsSizeLimit());

    final var overLimit = TxBuilder.createBuilder()
        .feePayer(feePayer)
        .addInstructions(sharedAccountInstructions(MAX_V1_INSTRUCTIONS + 1, account));
    final var thrown = assertThrows(IllegalStateException.class, overLimit::createTransaction);
    assertTrue(
        thrown.getMessage().contains(MAX_V1_INSTRUCTIONS + " instructions"),
        "the instruction count check must be the one which rejects it: " + thrown.getMessage()
    );

    // Without strict mode the same 65 instructions build.
    final var lax = laxBuilder(feePayer)
        .addInstructions(sharedAccountInstructions(MAX_V1_INSTRUCTIONS + 1, account));
    final var laxTx = lax.createTransaction();
    assertEquals(MAX_V1_INSTRUCTIONS + 1, laxTx.numInstructions());
    assertTrue(laxTx.exceedsInstructionLimit());
    // Neither of the other strict limits could have rejected the 65 instruction fixture either.
    assertEquals(3, laxTx.numAccounts());
    assertFalse(laxTx.exceedsSizeLimit());
    assertFalse(laxTx.exceedsSignatureLimit());
  }

  /// The `numAccounts > MAX_V1_ACCOUNTS` limit, at and one past the boundary.
  ///
  /// The single instruction references 62 or 63 distinct read accounts; the fee payer and the
  /// system program bring the totals to exactly 64 and 65. Both fixtures serialize to well under
  /// 4KiB, so the size check cannot reject the 65 account one in the account check's place.
  @Test
  void testStrictAccountCountBoundary() {
    final var feePayer = newKey();

    final var atLimit = TxBuilder.createBuilder()
        .feePayer(feePayer)
        .addInstruction(instructionWithReadAccounts(MAX_V1_ACCOUNTS - 2))
        .createTransaction();
    assertEquals(MAX_V1_ACCOUNTS, atLimit.numAccounts());
    assertFalse(atLimit.exceedsAccountLimit());
    assertFalse(atLimit.exceedsSizeLimit());

    final var overLimit = TxBuilder.createBuilder()
        .feePayer(feePayer)
        .addInstruction(instructionWithReadAccounts(MAX_V1_ACCOUNTS - 1));
    final var thrown = assertThrows(IllegalStateException.class, overLimit::createTransaction);
    assertTrue(
        thrown.getMessage().contains(MAX_V1_ACCOUNTS + " accounts"),
        "the account count check must be the one which rejects it: " + thrown.getMessage()
    );

    // Without strict mode the same 65 accounts build.
    final var laxTx = laxBuilder(feePayer)
        .addInstruction(instructionWithReadAccounts(MAX_V1_ACCOUNTS - 1))
        .createTransaction();
    assertEquals(MAX_V1_ACCOUNTS + 1, laxTx.numAccounts());
    assertTrue(laxTx.exceedsAccountLimit());
    assertFalse(laxTx.exceedsSizeLimit());
  }

  /// The `numRequiredSignatures > MAX_V1_SIGNATURES` limit, at and one past the boundary.
  ///
  /// The fee payer signs, so 11 writable signers require exactly 12 signatures and 12 require 13.
  @Test
  void testStrictSignatureCountBoundary() {
    final var feePayer = newKey();

    final var atLimit = TxBuilder.createBuilder()
        .feePayer(feePayer)
        .addInstruction(instructionWithWritableSigners(MAX_V1_SIGNATURES - 1))
        .createTransaction();
    assertEquals(MAX_V1_SIGNATURES, atLimit.numSigners());
    assertFalse(atLimit.exceedsSignatureLimit());
    assertFalse(atLimit.exceedsAccountLimit());
    assertFalse(atLimit.exceedsSizeLimit());

    final var overLimit = TxBuilder.createBuilder()
        .feePayer(feePayer)
        .addInstruction(instructionWithWritableSigners(MAX_V1_SIGNATURES));
    final var thrown = assertThrows(IllegalStateException.class, overLimit::createTransaction);
    assertTrue(
        thrown.getMessage().contains(MAX_V1_SIGNATURES + " signatures"),
        "the signature count check must be the one which rejects it: " + thrown.getMessage()
    );

    final var laxTx = laxBuilder(feePayer)
        .addInstruction(instructionWithWritableSigners(MAX_V1_SIGNATURES))
        .createTransaction();
    assertEquals(MAX_V1_SIGNATURES + 1, laxTx.numSigners());
    assertTrue(laxTx.exceedsSignatureLimit());
  }

  /// The `bufferSize > MAX_SERIALIZED_LENGTH_V1` limit, at and one past the boundary.
  ///
  /// A single account free instruction of `dataLength` bytes serializes to a fixed overhead plus
  /// its data: 1 VersionByte + 3 LegacyHeader + 4 TransactionConfigMask + 32 LifetimeSpecifier
  /// + 1 NumInstructions + 1 NumAddresses + 64 for the fee payer and the system program
  /// + 8 for the two ConfigValues the builder writes by default + 4 InstructionHeader
  /// + 64 for the fee payer's signature slot = 182 bytes.
  private static final int V1_FIXED_OVERHEAD = 182;

  private static TxBuilder builderWithDataLength(final PublicKey feePayer, final int dataLength) {
    return TxBuilder.createBuilder()
        .feePayer(feePayer)
        .addInstruction(Instruction.createInstruction(
            SolanaAccounts.MAIN_NET.systemProgram(),
            List.of(),
            new byte[dataLength]
        ));
  }

  @Test
  void testStrictSerializedLengthBoundary() {
    final var feePayer = newKey();

    final var atLimit = builderWithDataLength(feePayer, MAX_SERIALIZED_LENGTH_V1 - V1_FIXED_OVERHEAD)
        .createTransaction();
    // Exactly at the limit, which a `>=` boundary would reject.
    assertEquals(MAX_SERIALIZED_LENGTH_V1, atLimit.size());
    assertFalse(atLimit.exceedsSizeLimit());

    final var overLimit = builderWithDataLength(feePayer, (MAX_SERIALIZED_LENGTH_V1 - V1_FIXED_OVERHEAD) + 1);
    final var thrown = assertThrows(IllegalStateException.class, overLimit::createTransaction);
    assertTrue(
        thrown.getMessage().contains(MAX_SERIALIZED_LENGTH_V1 + " bytes"),
        "the serialized size check must be the one which rejects it: " + thrown.getMessage()
    );

    // The size limit is gated on strict mode: one byte over must build without it.
    final var laxTx = laxBuilder(feePayer)
        .addInstruction(Instruction.createInstruction(
            SolanaAccounts.MAIN_NET.systemProgram(),
            List.of(),
            new byte[(MAX_SERIALIZED_LENGTH_V1 - V1_FIXED_OVERHEAD) + 1]
        ))
        .createTransaction();
    assertEquals(MAX_SERIALIZED_LENGTH_V1 + 1, laxTx.size());
    assertTrue(laxTx.exceedsSizeLimit());
  }

  /// The per instruction account cap imposed by the u8 InstructionHeader count field, at and one
  /// past the boundary.
  ///
  /// Account indices may repeat within an instruction, so 255 references cost only one unique
  /// account and the transaction stays inside every other v1 limit while strict.
  @Test
  void testInstructionAccountCountBoundary() {
    final var feePayer = newKey();
    final var account = newKey();

    final var atLimit = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        Collections.nCopies(0xFF, AccountMeta.createRead(account)),
        new byte[]{1}
    );
    final var tx = TxBuilder.createBuilder().feePayer(feePayer).addInstruction(atLimit).createTransaction();
    // The fee payer, the referenced account and the system program.
    assertEquals(3, tx.numAccounts());
    assertFalse(tx.exceedsSizeLimit());
    // The InstructionHeader follows the addresses and the two default ConfigValues; its second
    // field is the u8 account count, which 255 references must fill exactly rather than being
    // rejected by a `>=` boundary.
    final int headerOffset = V1TransactionSkeleton.V1_ACCOUNTS_OFFSET
        + (3 * PublicKey.PUBLIC_KEY_LENGTH)
        + 8;
    assertEquals(0xFF, tx.serialized()[headerOffset + 1] & 0xFF);

    final var overLimit = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        Collections.nCopies(0x100, AccountMeta.createRead(account)),
        new byte[]{1}
    );
    final var builder = TxBuilder.createBuilder().feePayer(feePayer).addInstruction(overLimit);
    final var thrown = assertThrows(IllegalStateException.class, builder::createTransaction);
    assertTrue(
        thrown.getMessage().contains("255 accounts"),
        "the per instruction account cap must be the one which rejects it: " + thrown.getMessage()
    );
  }

  /// The fee payer must sort to index 0. It is the builder's own fee payer which carries the flag,
  /// so a builder left without one produces a first account that is merely a writable signer, and
  /// the transaction must be rejected instead of silently promoting that signer to fee payer.
  ///
  /// The check is not gated on strict mode.
  @Test
  void testCreateTransactionRequiresAFeePayer() {
    final var signer = newKey();
    final var ix = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createWritableSigner(signer)),
        new byte[]{1}
    );

    final var strictBuilder = TxBuilder.createBuilder().addInstruction(ix);
    final var thrown = assertThrows(IllegalStateException.class, strictBuilder::createTransaction);
    assertTrue(
        thrown.getMessage().contains("Fee payer must be the first account"),
        thrown.getMessage()
    );

    final var lax = TxBuilder.createBuilder().addInstruction(ix);
    lax.strict(false);
    assertThrows(IllegalStateException.class, lax::createTransaction);

    // The same instruction builds once a fee payer is configured.
    final var tx = TxBuilder.createBuilder().feePayer(newKey()).addInstruction(ix).createTransaction();
    assertEquals(2, tx.numSigners());
  }

  /// The LegacyHeader's `numReadonlySignedAccounts` field, which only a read only signer that is
  /// not the fee payer can move off 0. It must count up: counting down writes 0xFF into a u8 field
  /// and declares 255 read only signed accounts.
  @Test
  void testReadonlySignedAccountCount() {
    final var feePayer = newKey();
    final var readOnlySigner = newKey();

    final byte[] data = TxBuilder.createBuilder()
        .feePayer(feePayer)
        .addInstruction(Instruction.createInstruction(
            SolanaAccounts.MAIN_NET.systemProgram(),
            List.of(AccountMeta.createReadOnlySigner(readOnlySigner)),
            new byte[]{1}
        ))
        .createTransaction()
        .serialized();
    assertEquals(2, data[1] & 0xFF, "numRequiredSignatures");
    assertEquals(1, data[2] & 0xFF, "numReadonlySignedAccounts");
    assertEquals(1, data[3] & 0xFF, "numReadonlyUnsignedAccounts: the system program");

    // A writable signer is not read only, so the same shape leaves the field at 0.
    final byte[] writableData = TxBuilder.createBuilder()
        .feePayer(feePayer)
        .addInstruction(Instruction.createInstruction(
            SolanaAccounts.MAIN_NET.systemProgram(),
            List.of(AccountMeta.createWritableSigner(readOnlySigner)),
            new byte[]{1}
        ))
        .createTransaction()
        .serialized();
    assertEquals(2, writableData[1] & 0xFF, "numRequiredSignatures");
    assertEquals(0, writableData[2] & 0xFF, "numReadonlySignedAccounts");
  }

  /// The `SequencedCollection` overload of `addInstructions`, which a `List` argument never reaches
  /// because the `List` overload is more specific. An `ArrayDeque` is a `SequencedCollection` and
  /// not a `List`, so it selects the overload under test.
  ///
  /// The first call must copy into a fresh list and the second must append to it, in order; the
  /// builder itself must be returned so calls chain.
  @Test
  void testAddInstructionsSequencedCollectionCopiesThenAppends() {
    final var feePayer = newKey();
    final SequencedCollection<Instruction> first = new ArrayDeque<>(List.of(
        markerInstruction(1), markerInstruction(2)
    ));
    final SequencedCollection<Instruction> second = new ArrayDeque<>(List.of(markerInstruction(3)));

    final var builder = TxBuilder.createBuilder().feePayer(feePayer);
    assertSame(builder, builder.addInstructions(first), "addInstructions must return the builder");
    assertSame(builder, builder.addInstructions(second), "addInstructions must return the builder");

    final var tx = builder.createTransaction();
    assertEquals(3, tx.numInstructions());
    assertArrayEquals(new byte[]{1, 2, 3}, instructionMarkers(tx));

    // The copy is defensive: mutating the source afterwards must not reach the builder.
    first.add(markerInstruction(4));
    assertEquals(3, builder.createTransaction().numInstructions());
  }

  /// The `List` overload's sibling behaviour: a second call appends rather than replacing.
  @Test
  void testAddInstructionsListCopiesThenAppends() {
    final var feePayer = newKey();
    final var builder = TxBuilder.createBuilder().feePayer(feePayer);
    assertSame(builder, builder.addInstructions(List.of(markerInstruction(1), markerInstruction(2))));
    assertSame(builder, builder.addInstructions(List.of(markerInstruction(3))));

    final var tx = builder.createTransaction();
    assertEquals(3, tx.numInstructions());
    assertArrayEquals(new byte[]{1, 2, 3}, instructionMarkers(tx));
  }

  /// The overflow guard in
  /// [TxBuilder#computeUnitPriceToPriorityFeeLamports(long,int)], exactly on its boundary.
  ///
  /// The guard saturates when `price * limit + 999_999` would overflow, i.e. when the price exceeds
  /// `(Long.MAX_VALUE - 999_999) / limit`. The largest price which still fits must be multiplied
  /// out rather than saturated, and the next one up must saturate — the true overflow point, since
  /// `(threshold + 1) * 1_400_000 + 999_999` is greater than `Long.MAX_VALUE`.
  @Test
  void testComputeUnitPriceOverflowBoundary() {
    final int limit = TxBuilderImpl.MAX_COMPUTE_UNIT_LIMIT;
    final long threshold = (Long.MAX_VALUE - 999_999L) / limit;
    assertEquals(6_588_122_883_466L, threshold, "the fixture must sit on the guard's boundary");

    // 6,588,122,883,466 * 1,400,000 micro-lamports, rounded up to whole lamports.
    assertEquals(9_223_372_036_853L, TxBuilder.computeUnitPriceToPriorityFeeLamports(threshold, limit));
    assertEquals(Long.MAX_VALUE, TxBuilder.computeUnitPriceToPriorityFeeLamports(threshold + 1, limit));

    // A zero compute unit limit is short circuited to a zero fee. Without that arm the guard
    // divides by it.
    assertEquals(0L, TxBuilder.computeUnitPriceToPriorityFeeLamports(25_000L, 0));
    assertEquals(0L, TxBuilder.computeUnitPriceToPriorityFeeLamports(Long.MAX_VALUE, 0));
    // A negative limit is read as unsigned and capped, so it is not mistaken for a zero limit.
    assertEquals(
        TxBuilder.computeUnitPriceToPriorityFeeLamports(1_000L, limit),
        TxBuilder.computeUnitPriceToPriorityFeeLamports(1_000L, -1)
    );
  }
}
