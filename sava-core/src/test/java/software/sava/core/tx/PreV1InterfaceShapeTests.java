package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.programs.Discriminator;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SequencedCollection;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// Recompilation compatibility with implementations built against the pre-v1 interfaces.
///
/// The two nested implementations below declare **exactly** the abstract surface of `Transaction`
/// and `TransactionSkeleton` as released in 25.10.0, delegating each method to a built-in instance.
/// Everything v1 added to those interfaces must therefore arrive as a `default`, and this class is
/// the pin: adding an abstract method without one stops this file compiling, which is the
/// published-library rule enforced by the compiler instead of by review. The assertions then hold
/// the inherited defaults to their documented behaviour, so a default cannot quietly change
/// meaning either.
final class PreV1InterfaceShapeTests {

  private static final AtomicInteger KEY_SEED = new AtomicInteger();

  private static Signer nextSigner() {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    Arrays.fill(privateKey, (byte) KEY_SEED.incrementAndGet());
    return Signer.createFromPrivateKey(privateKey);
  }

  /// A `Transaction` with the abstract surface of 25.10.0 and nothing else: every pre-v1 method
  /// delegates, every v1-era method is inherited. Nothing v1 added may be overridden here — this
  /// class is the compilation pin, and an override would keep it compiling even if that method
  /// reverted to abstract, silently narrowing the pin. Overrides for specific assertions belong in
  /// subclasses.
  private static class PreV1Transaction implements Transaction {

    private final Transaction delegate;

    private PreV1Transaction(final Transaction delegate) {
      this.delegate = delegate;
    }

    @Override
    public void sign(final Signer signer) {
      delegate.sign(signer);
    }

    @Override
    public void sign(final int index, final Signer signer) {
      delegate.sign(index, signer);
    }

    @Override
    public void sign(final Collection<Signer> signers) {
      delegate.sign(signers);
    }

    @Override
    public void sign(final SequencedCollection<Signer> signers) {
      delegate.sign(signers);
    }

    @Override
    public String getBase58Id() {
      return delegate.getBase58Id();
    }

    @Override
    public byte[] getId() {
      return delegate.getId();
    }

    @Override
    public int size() {
      return delegate.size();
    }

    @Override
    public List<Instruction> instructions() {
      return delegate.instructions();
    }

    @Override
    public AddressLookupTable lookupTable() {
      return delegate.lookupTable();
    }

    @Override
    public LookupTableAccountMeta[] tableAccountMetas() {
      return delegate.tableAccountMetas();
    }

    @Override
    public void setRecentBlockHash(final byte[] recentBlockHash) {
      delegate.setRecentBlockHash(recentBlockHash);
    }

    @Override
    public void setRecentBlockHash(final String recentBlockHash) {
      delegate.setRecentBlockHash(recentBlockHash);
    }

    @Override
    public byte[] recentBlockHash() {
      return delegate.recentBlockHash();
    }

    @Override
    public int version() {
      return delegate.version();
    }

    @Override
    public int numSigners() {
      return delegate.numSigners();
    }

    @Override
    public byte[] serialized() {
      return delegate.serialized();
    }

    @Override
    public Transaction prependIx(final Instruction ix) {
      return delegate.prependIx(ix);
    }

    @Override
    public Transaction prependInstructions(final Instruction ix1, final Instruction ix2) {
      return delegate.prependInstructions(ix1, ix2);
    }

    @Override
    public Transaction prependInstructions(final SequencedCollection<Instruction> instructions) {
      return delegate.prependInstructions(instructions);
    }

    @Override
    public Transaction appendIx(final Instruction ix) {
      return delegate.appendIx(ix);
    }

    @Override
    public Transaction appendInstructions(final SequencedCollection<Instruction> instructions) {
      return delegate.appendInstructions(instructions);
    }

    @Override
    public Transaction replaceInstruction(final int index, final Instruction instruction) {
      return delegate.replaceInstruction(index, instruction);
    }

    @Override
    public AccountMeta feePayer() {
      return delegate.feePayer();
    }
  }

  /// A `TransactionSkeleton` with the abstract surface of 25.10.0, delegating everything; the four
  /// v1-era config readers are inherited and must reparse [#data()] rather than return a blind
  /// zero, because a pre-v1 legacy skeleton can sit over real compute-budget instructions.
  private record PreV1Skeleton(TransactionSkeleton d) implements TransactionSkeleton {

    @Override
    public byte[] data() {
      return d.data();
    }

    @Override
    public int numSignatures() {
      return d.numSignatures();
    }

    @Override
    public String id() {
      return d.id();
    }

    @Override
    public int version() {
      return d.version();
    }

    @Override
    public boolean isVersioned() {
      return d.isVersioned();
    }

    @Override
    public boolean isLegacy() {
      return d.isLegacy();
    }

    @Override
    public int numReadonlySignedAccounts() {
      return d.numReadonlySignedAccounts();
    }

    @Override
    public int numReadonlyUnsignedAccounts() {
      return d.numReadonlyUnsignedAccounts();
    }

    @Override
    public int recentBlockHashIndex() {
      return d.recentBlockHashIndex();
    }

    @Override
    public byte[] blockHash() {
      return d.blockHash();
    }

    @Override
    public String base58BlockHash() {
      return d.base58BlockHash();
    }

    @Override
    public int numInstructions() {
      return d.numInstructions();
    }

    @Override
    public int instructionsOffset() {
      return d.instructionsOffset();
    }

    @Override
    public int numIncludedAccounts() {
      return d.numIncludedAccounts();
    }

    @Override
    public int numAccounts() {
      return d.numAccounts();
    }

    @Override
    public PublicKey[] lookupTableAccounts() {
      return d.lookupTableAccounts();
    }

    @Override
    public AccountMeta[] parseAccounts() {
      return d.parseAccounts();
    }

    @Override
    public AccountMeta[] parseAccounts(final Map<PublicKey, AddressLookupTable> lookupTables) {
      return d.parseAccounts(lookupTables);
    }

    @Override
    public AccountMeta[] parseAccounts(final List<PublicKey> writableLoaded, final List<PublicKey> readonlyLoaded) {
      return d.parseAccounts(writableLoaded, readonlyLoaded);
    }

    @Override
    public PublicKey feePayer() {
      return d.feePayer();
    }

    @Override
    public AccountMeta[] parseSignerAccounts() {
      return d.parseSignerAccounts();
    }

    @Override
    public PublicKey[] parseSignerPublicKeys() {
      return d.parseSignerPublicKeys();
    }

    @Override
    public AccountMeta[] parseNonSignerAccounts() {
      return d.parseNonSignerAccounts();
    }

    @Override
    public PublicKey[] parseNonSignerPublicKeys() {
      return d.parseNonSignerPublicKeys();
    }

    @Override
    public AccountMeta[] parseAccounts(final AddressLookupTable lookupTable) {
      return d.parseAccounts(lookupTable);
    }

    @Override
    public PublicKey[] parseProgramAccounts() {
      return d.parseProgramAccounts();
    }

    @Override
    public int serializedInstructionsLength() {
      return d.serializedInstructionsLength();
    }

    @Override
    public Instruction[] parseInstructions(final AccountMeta[] accounts) {
      return d.parseInstructions(accounts);
    }

    @Override
    public Instruction[] parseInstructionsWithoutAccounts() {
      return d.parseInstructionsWithoutAccounts();
    }

    @Override
    public Instruction[] parseInstructionsWithoutTableAccounts() {
      return d.parseInstructionsWithoutTableAccounts();
    }

    @Override
    public Instruction[] filterInstructions(final AccountMeta[] accounts, final Discriminator discriminator) {
      return d.filterInstructions(accounts, discriminator);
    }

    @Override
    public Instruction[] filterInstructionsWithoutAccounts(final Discriminator discriminator) {
      return d.filterInstructionsWithoutAccounts(discriminator);
    }

    @Override
    public Transaction createTransaction(final List<Instruction> instructions) {
      return d.createTransaction(instructions);
    }

    @Override
    public Transaction createTransaction(final List<Instruction> instructions, final AddressLookupTable lookupTable) {
      return d.createTransaction(instructions, lookupTable);
    }

    @Override
    public Transaction createTransaction(final List<Instruction> instructions, final LookupTableAccountMeta[] tableAccountMetas) {
      return d.createTransaction(instructions, tableAccountMetas);
    }
  }

  private static Transaction legacyTx(final int dataLength) {
    final var ix = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createRead(nextSigner().publicKey())),
        new byte[dataLength]
    );
    final var tx = Transaction.createTx(nextSigner().publicKey(), ix);
    tx.setRecentBlockHash(new byte[Transaction.BLOCK_HASH_LENGTH]);
    return tx;
  }

  @Test
  void countsAndLimitsDefaultToTheirDocumentedMeanings() {
    final var delegate = legacyTx(4);
    final var preV1 = new PreV1Transaction(delegate);

    assertEquals(1, preV1.numInstructions(), "instructions().size()");
    assertEquals(
        TransactionSkeleton.deserializeSkeleton(delegate.serialized()).numAccounts(),
        preV1.numAccounts(),
        "the skeleton's wire-declared account total"
    );
    assertFalse(preV1.exceedsSignatureLimit(), "only v1 bounds the signature count");
    assertFalse(preV1.exceedsSizeLimit());
  }

  /// The size default keeps main's exact boundary: `size() > 1232`, not `>=`.
  @Test
  void sizeLimitDefaultSitsExactlyAtTheLegacyBoundary() {
    // The instruction data length is a compact-u16, so the serialization overhead itself grows by
    // a byte once the data passes 127; measure at the target scale and correct once.
    int dataLength = Transaction.MAX_SERIALIZED_LENGTH - (legacyTx(8).size() - 8);
    dataLength += Transaction.MAX_SERIALIZED_LENGTH - legacyTx(dataLength).size();
    final var atLimit = new PreV1Transaction(legacyTx(dataLength));
    assertEquals(Transaction.MAX_SERIALIZED_LENGTH, atLimit.size(), "fixture must land exactly on the limit");
    assertFalse(atLimit.exceedsSizeLimit(), "the limit itself is within bounds");

    final var pastLimit = new PreV1Transaction(legacyTx(dataLength + 1));
    assertTrue(pastLimit.exceedsSizeLimit());
  }

  /// The signature-limit formula, pinned at its boundary through a shape that reports v1 without
  /// needing a 13-signer fixture: only the two values the default reads are overridden.
  @Test
  void signatureLimitDefaultBindsAtTwelveForV1() {
    final class Reporting extends PreV1Transaction {
      private final int version;
      private final int numSigners;

      private Reporting(final Transaction delegate, final int version, final int numSigners) {
        super(delegate);
        this.version = version;
        this.numSigners = numSigners;
      }

      @Override
      public int version() {
        return version;
      }

      @Override
      public int numSigners() {
        return numSigners;
      }
    }
    final var delegate = legacyTx(4);
    assertFalse(new Reporting(delegate, 1, 12).exceedsSignatureLimit(), "twelve is the v1 maximum, not past it");
    assertTrue(new Reporting(delegate, 1, 13).exceedsSignatureLimit());
    assertFalse(new Reporting(delegate, 0, 13).exceedsSignatureLimit(), "v0 has no signature-count limit");
  }

  @Test
  void mutatorDefaultsThrowWithoutTouchingAnything() {
    final var preV1 = new PreV1Transaction(legacyTx(4));
    final byte[] before = preV1.serialized().clone();

    assertThrows(UnsupportedOperationException.class, () -> preV1.setComputeUnitLimit(200_000));
    assertThrows(UnsupportedOperationException.class, () -> preV1.setPriorityFeeLamports(1L));
    assertThrows(UnsupportedOperationException.class, () -> preV1.setPriorityFeeLamportsFromComputeUnitPrice(1L));
    assertThrows(UnsupportedOperationException.class, () -> preV1.setAccountDataSizeLimit(1));
    assertThrows(UnsupportedOperationException.class, () -> preV1.setHeapSize(32 * 1024));
    assertThrows(
        UnsupportedOperationException.class,
        () -> preV1.setPriorityFeeLamportsFromComputeUnitPrice(1L, 200_000)
    );
    assertArrayEquals(before, preV1.serialized(), "a refused mutator leaves the payload untouched");
  }

  /// The two-argument conversion's default must throw *directly*. Composed from
  /// [Transaction#setComputeUnitLimit(int)] plus the one-argument overload, an implementation
  /// defining its own limit setter would mutate it before the inherited fee call throws. The
  /// detector lives in a subclass so the base fixture stays strictly pre-v1-shaped.
  @Test
  void twoArgumentFeeDefaultDoesNotComposeThroughTheLimitSetter() {
    final class LimitDetecting extends PreV1Transaction {
      private int computeUnitLimitCalls;

      private LimitDetecting(final Transaction delegate) {
        super(delegate);
      }

      @Override
      public Transaction setComputeUnitLimit(final int computeUnitLimit) {
        ++computeUnitLimitCalls;
        return this;
      }
    }
    final var detecting = new LimitDetecting(legacyTx(4));
    assertThrows(
        UnsupportedOperationException.class,
        () -> detecting.setPriorityFeeLamportsFromComputeUnitPrice(1L, 200_000)
    );
    assertEquals(0, detecting.computeUnitLimitCalls, "the throwing default must not compose through setComputeUnitLimit");
  }

  /// A pre-v1 legacy skeleton over real compute-budget instructions: the inherited readers must
  /// reparse and report those values, not a blind zero.
  @Test
  void skeletonReaderDefaultsReparseRatherThanReturnZero() {
    // Every reader gets a distinct non-zero value, so a default degraded to `return 0` cannot
    // hide behind a fixture that happens to hold the zero it returns.
    final var withBudget = legacyTx(4)
        .setComputeUnitLimit(123_456)
        .setPriorityFeeLamports(7_777L)
        .setAccountDataSizeLimit(1_048_576)
        .setHeapSize(64 * 1024);
    withBudget.setRecentBlockHash(new byte[Transaction.BLOCK_HASH_LENGTH]);
    final var builtIn = TransactionSkeleton.deserializeSkeleton(withBudget.serialized());
    final var preV1 = new PreV1Skeleton(builtIn);

    assertEquals(123_456, preV1.computeUnitLimit());
    assertTrue(preV1.priorityFeeLamports() > 0, "a set priority fee must be visible through the default");
    assertEquals(builtIn.priorityFeeLamports(), preV1.priorityFeeLamports());
    assertEquals(1_048_576, preV1.accountDataSizeLimit());
    assertEquals(64 * 1024, preV1.heapSize());

    // And a v1 payload's config values flow through the same defaults.
    final var v1 = TxBuilder.createBuilder()
        .feePayer(nextSigner().publicKey())
        .addInstruction(Instruction.createInstruction(
            SolanaAccounts.MAIN_NET.systemProgram(),
            List.of(AccountMeta.createRead(nextSigner().publicKey())),
            new byte[]{1}
        ))
        .computeUnitLimit(654_321)
        .createTransaction();
    final var v1Skeleton = new PreV1Skeleton(TransactionSkeleton.deserializeSkeleton(v1.serialized()));
    assertEquals(654_321, v1Skeleton.computeUnitLimit());
  }
}
