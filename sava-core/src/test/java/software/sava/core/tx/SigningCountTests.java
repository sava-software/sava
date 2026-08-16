package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.encoding.ByteUtil;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.SequencedCollection;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.tx.Transaction.BLOCK_HASH_LENGTH;
import static software.sava.core.tx.Transaction.SIGNATURE_LENGTH;
import static software.sava.core.tx.V1TransactionSkeleton.V1_ACCOUNTS_OFFSET;

/// Pins the serialized signature *count* and the signed/unsigned decision that reads it.
///
/// Three distinct bytes carry a count, and each is written by a different rule:
///
///  * legacy/v0 lead with a compact-u16 signature count at `data[0]`, rewritten by
///    `BaseTransaction#recordNumSignatures` on every signing path;
///  * v1 fixes its count in the header at build time, so `V1Transaction#recordNumSignatures` is a
///    deliberate no-op and signing must leave the header byte-identical;
///  * `BaseTransaction#feePayerSignatureOffset` decides signed-vs-unsigned by scanning the fee
///    payer's 64 signature bytes, over a count it must not trust — `Transaction#getId(byte[])` is
///    public and takes raw bytes.
///
/// Every signer is derived from a fixed private key so failures are exactly reproducible.
final class SigningCountTests {

  private static Signer signer(final int seed) {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    Arrays.fill(privateKey, (byte) seed);
    return Signer.createFromPrivateKey(privateKey);
  }

  private static byte[] blockHash() {
    final byte[] blockHash = new byte[BLOCK_HASH_LENGTH];
    for (int b = 0; b < blockHash.length; ++b) {
      blockHash[b] = (byte) (b + 1);
    }
    return blockHash;
  }

  private static PublicKey key(final int fill) {
    final byte[] keyBytes = new byte[PUBLIC_KEY_LENGTH];
    Arrays.fill(keyBytes, (byte) fill);
    return PublicKey.createPubKey(keyBytes);
  }

  private static Instruction twoSignerInstruction(final Signer signerB) {
    return Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createWritableSigner(signerB.publicKey())),
        new byte[]{1, 2, 3, 4}
    );
  }

  /// A legacy transaction requiring two signatures: the fee payer plus one writable signer.
  private static Transaction legacyTwoSignerTx(final Signer feePayer, final Signer signerB) {
    final var tx = Transaction.createTx(feePayer.publicKey(), twoSignerInstruction(signerB));
    tx.setRecentBlockHash(blockHash());
    assertEquals(2, tx.numSigners());
    assertInstanceOf(TransactionRecord.class, tx);
    assertEquals(2, tx.serialized()[0], "the factory stamps the count it computed");
    return tx;
  }

  /// A v1 transaction requiring two signatures, same shape as the legacy fixture above.
  private static Transaction v1TwoSignerTx(final Signer feePayer, final Signer signerB) {
    final var tx = TxBuilder.createBuilder()
        .feePayer(feePayer.publicKey())
        .addInstruction(twoSignerInstruction(signerB))
        .createTransaction();
    tx.setRecentBlockHash(blockHash());
    assertEquals(1, tx.version());
    assertEquals(2, tx.numSigners());
    assertInstanceOf(V1Transaction.class, tx);
    return tx;
  }

  /// The legacy message begins after the one-byte count and the signature slots it declares.
  private static int legacyMessageOffset(final int numSigners) {
    return 1 + (numSigners * SIGNATURE_LENGTH);
  }

  private static void assertLegacySlotVerifies(final byte[] data,
                                               final int slot,
                                               final Signer signer) {
    final int messageOffset = legacyMessageOffset(2);
    final int signatureOffset = 1 + (slot * SIGNATURE_LENGTH);
    assertTrue(
        signer.publicKey().verifySignature(
            data, messageOffset, data.length - messageOffset,
            Arrays.copyOfRange(data, signatureOffset, signatureOffset + SIGNATURE_LENGTH)
        ),
        "slot " + slot + " must hold a signature over the whole legacy message"
    );
  }

  /// Drives `data[0]` away from the transaction's real signer count using nothing but public API:
  /// the static single-signer helper stamps a count of 1, which is what a caller who partially
  /// signed through `Transaction#signAndBase64Encode(Signer, byte[])` is left holding.
  private static void partiallySignThroughTheStaticHelper(final Transaction tx, final Signer feePayer) {
    Transaction.sign(feePayer, tx.serialized());
    assertEquals(1, tx.serialized()[0], "the static single-signer helper stamps a count of 1");
  }

  // ---------------------------------------------------------------------------------------------
  // BaseTransaction#sign — recordNumSignatures on all three overloads
  // ---------------------------------------------------------------------------------------------

  /// `sign(Signer)` must restamp the serialized signature count.
  ///
  /// The count byte is what a validator uses to find where the message starts, so a transaction
  /// whose prefix says 1 while its header requires 2 deserializes as a different message. Dropping
  /// `recordNumSignatures` leaves the stale 1 in place and the transaction is unusable, even though
  /// both signatures are present and correct.
  @Test
  void signByKeyRestampsTheSerializedSignatureCount() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final var tx = legacyTwoSignerTx(feePayer, signerB);
    final byte[] data = tx.serialized();
    partiallySignThroughTheStaticHelper(tx, feePayer);

    tx.sign(feePayer);
    assertEquals(2, data[0], "sign(Signer) must rewrite the serialized signature count");

    tx.sign(signerB);
    assertEquals(2, data[0]);
    assertLegacySlotVerifies(data, 0, feePayer);
    assertLegacySlotVerifies(data, 1, signerB);
  }

  /// The positional `sign(SequencedCollection)` overload restamps the count it validated against.
  @Test
  void signSequencedRestampsTheSerializedSignatureCount() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final var tx = legacyTwoSignerTx(feePayer, signerB);
    final byte[] data = tx.serialized();
    partiallySignThroughTheStaticHelper(tx, feePayer);

    final SequencedCollection<Signer> inOrder = List.of(feePayer, signerB);
    tx.sign(inOrder);
    assertEquals(2, data[0], "sign(SequencedCollection) must rewrite the serialized signature count");
    assertLegacySlotVerifies(data, 0, feePayer);
    assertLegacySlotVerifies(data, 1, signerB);
  }

  /// The by-key `sign(Collection)` overload restamps the count too. Passed out of message order so
  /// the assignment, not the iteration order, decides which slot each signature lands in.
  @Test
  void signByKeyCollectionRestampsTheSerializedSignatureCount() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final var tx = legacyTwoSignerTx(feePayer, signerB);
    final byte[] data = tx.serialized();
    partiallySignThroughTheStaticHelper(tx, feePayer);

    // A Collection static type is required to select the by-key overload; List binds to the
    // SequencedCollection one.
    final Collection<Signer> outOfOrder = List.of(signerB, feePayer);
    tx.sign(outOfOrder);
    assertEquals(2, data[0], "sign(Collection) must rewrite the serialized signature count");
    assertLegacySlotVerifies(data, 0, feePayer);
    assertLegacySlotVerifies(data, 1, signerB);
  }

  /// The v1 counterpart, and the reason the three tests above are the only way to reach those
  /// calls: a v1 transaction carries its signature count inside the signed message header, fixed at
  /// build time, so `V1Transaction#recordNumSignatures` is deliberately empty. Rewriting the count
  /// while signing would change the very bytes being signed. Every signing path must therefore
  /// leave the header byte-identical.
  @Test
  void v1SigningNeverRewritesTheHeaderSignatureCount() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final var tx = v1TwoSignerTx(feePayer, signerB);
    final byte[] data = tx.serialized();
    final byte[] header = Arrays.copyOfRange(data, 0, V1_ACCOUNTS_OFFSET);
    assertEquals((byte) 129, header[0]);
    assertEquals(2, header[1]);

    tx.sign(feePayer);
    assertArrayEquals(header, Arrays.copyOfRange(data, 0, V1_ACCOUNTS_OFFSET));

    final SequencedCollection<Signer> inOrder = List.of(feePayer, signerB);
    tx.sign(inOrder);
    assertArrayEquals(header, Arrays.copyOfRange(data, 0, V1_ACCOUNTS_OFFSET));

    final Collection<Signer> outOfOrder = List.of(signerB, feePayer);
    tx.sign(outOfOrder);
    assertArrayEquals(header, Arrays.copyOfRange(data, 0, V1_ACCOUNTS_OFFSET));

    final int messageEnd = data.length - (2 * SIGNATURE_LENGTH);
    assertTrue(feePayer.publicKey().verifySignature(
        data, 0, messageEnd, Arrays.copyOfRange(data, messageEnd, messageEnd + SIGNATURE_LENGTH)
    ));
    assertTrue(signerB.publicKey().verifySignature(
        data, 0, messageEnd, Arrays.copyOfRange(data, messageEnd + SIGNATURE_LENGTH, data.length)
    ));
  }

  // ---------------------------------------------------------------------------------------------
  // BaseTransaction#feePayerSignatureOffset — the legacy length bound
  // ---------------------------------------------------------------------------------------------

  /// The exact boundary of the legacy length bound. A 65-byte buffer is the smallest that can hold
  /// the one-byte count plus one signature, so it must be accepted: the guard is `length < 1 + 64`
  /// and widening it to `<=` rejects the minimal legal shape.
  @Test
  void aLegacyBufferOfExactlyOneCountByteAndOneSignatureIsAccepted() {
    final byte[] data = new byte[1 + SIGNATURE_LENGTH];
    data[0] = 1;
    for (int i = 1; i < data.length; ++i) {
      data[i] = (byte) i;
    }
    assertFalse(V1Transaction.isV1(data));

    assertEquals(1, BaseTransaction.feePayerSignatureOffset(data));
    assertArrayEquals(Arrays.copyOfRange(data, 1, data.length), Transaction.getId(data));
  }

  /// A buffer that declares a signature it cannot hold is a malformed transaction, and must be
  /// diagnosed as one. Without the bound the fee payer scan walks 64 bytes from offset 1 straight
  /// off the end of the array, so the caller sees an opaque index error from inside the scan
  /// instead of a description of what is wrong with their bytes.
  @Test
  void aLegacyBufferTooShortForItsDeclaredSignatureIsDiagnosed() {
    final byte[] tooShort = new byte[40];
    tooShort[0] = 1; // one signature declared, 39 bytes left to hold 64

    final var expected = "A transaction of 40 bytes cannot hold a signature.";
    assertEquals(expected, assertThrowsExactly(
        IllegalArgumentException.class, () -> BaseTransaction.feePayerSignatureOffset(tooShort)
    ).getMessage());
    assertEquals(expected, assertThrowsExactly(
        IllegalArgumentException.class, () -> Transaction.getBase58Id(tooShort)
    ).getMessage());
    assertEquals(expected, assertThrowsExactly(
        IllegalArgumentException.class, () -> Transaction.getId(tooShort)
    ).getMessage());
  }

  /// The complement: a buffer declaring *no* signatures has nothing to bound, so the same short
  /// length must pass straight through to the unsigned answer rather than being rejected. This is
  /// the short-circuit half of `numSigners != 0 && length < 1 + 64` — a zero count has no signature
  /// slot at all, so there is no length it could be too short for.
  @Test
  void aLegacyBufferDeclaringNoSignaturesIsUnsignedRatherThanMalformed() {
    final byte[] noSigners = new byte[40]; // data[0] == 0

    assertEquals(-1, BaseTransaction.feePayerSignatureOffset(noSigners));
    assertEquals("Transaction has not been signed by the fee payer yet.", assertThrowsExactly(
        IllegalStateException.class, () -> Transaction.getId(noSigners)
    ).getMessage());
  }

  /// A zero count must also suppress the fee payer scan itself, not just the length bound. Here the
  /// buffer is long enough to scan and every byte in the would-be fee payer slot is non-zero, so a
  /// scan that ran anyway would report the transaction signed and hand back 64 bytes of message as
  /// its id.
  @Test
  void aZeroSignatureCountSuppressesTheFeePayerScan() {
    final byte[] noSigners = new byte[200];
    Arrays.fill(noSigners, (byte) 0x11);
    noSigners[0] = 0;

    assertEquals(-1, BaseTransaction.feePayerSignatureOffset(noSigners));
    assertEquals("Transaction has not been signed by the fee payer yet.", assertThrowsExactly(
        IllegalStateException.class, () -> Transaction.getBase58Id(noSigners)
    ).getMessage());
  }

  /// The scan window is exactly the fee payer's own 64 bytes: `[1, 65)` for legacy. A single
  /// non-zero byte at index 64 — the last byte of the slot — is enough to call it signed, and a
  /// single non-zero byte at index 65 — the first byte of the *second* signer's slot — is not.
  @Test
  void theLegacyFeePayerScanCoversExactlyItsOwnSlot() {
    final byte[] lastByteOfSlot = new byte[300];
    lastByteOfSlot[0] = 2;
    lastByteOfSlot[SIGNATURE_LENGTH] = 1;
    assertEquals(1, BaseTransaction.feePayerSignatureOffset(lastByteOfSlot));

    final byte[] firstByteOfNextSlot = new byte[300];
    firstByteOfNextSlot[0] = 2;
    firstByteOfNextSlot[1 + SIGNATURE_LENGTH] = 1;
    assertEquals(-1, BaseTransaction.feePayerSignatureOffset(firstByteOfNextSlot),
        "a co-signer's signature does not make the transaction identifiable"
    );
  }

  /// The same signed/unsigned decision over a real legacy transaction, and over a real v1
  /// transaction whose signature block is appended rather than prefixed.
  @Test
  void signedAndUnsignedOffsetsForBothFormats() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final var legacy = legacyTwoSignerTx(feePayer, signerB);
    assertEquals(-1, BaseTransaction.feePayerSignatureOffset(legacy.serialized()));
    legacy.sign(feePayer);
    assertEquals(1, BaseTransaction.feePayerSignatureOffset(legacy.serialized()));

    final var v1 = v1TwoSignerTx(feePayer, signerB);
    final byte[] unsigned = v1.serialized().clone();
    final int signaturesOffset = unsigned.length - (2 * SIGNATURE_LENGTH);
    assertEquals(-1, BaseTransaction.feePayerSignatureOffset(unsigned));
    v1.sign(feePayer);
    assertEquals(signaturesOffset, BaseTransaction.feePayerSignatureOffset(v1.serialized()));

    // The v1 scan window is the appended fee payer slot, and only it.
    final byte[] lastByteOfSlot = unsigned.clone();
    lastByteOfSlot[signaturesOffset + SIGNATURE_LENGTH - 1] = 1;
    assertEquals(signaturesOffset, BaseTransaction.feePayerSignatureOffset(lastByteOfSlot));

    final byte[] firstByteOfNextSlot = unsigned.clone();
    firstByteOfNextSlot[signaturesOffset + SIGNATURE_LENGTH] = 1;
    assertEquals(-1, BaseTransaction.feePayerSignatureOffset(firstByteOfNextSlot));
  }

  // ---------------------------------------------------------------------------------------------
  // V1Transaction#isV1 — the discriminator
  // ---------------------------------------------------------------------------------------------

  /// `isV1` requires *both* halves of its discriminator.
  ///
  /// A legacy transaction needing 128 or more signature slots encodes its count as compact-u16, so
  /// its first byte carries the high bit too; and `0x81 0x00` is a non-canonical two-byte encoding
  /// of 1 that leads with the exact v1 version byte. Neither is a v1 transaction, and treating
  /// either as one reads the signature count out of the wrong byte.
  @Test
  void bothHalvesOfTheV1DiscriminatorAreRequired() {
    final var feePayer = signer(11);
    final var signerB = signer(22);
    assertTrue(V1Transaction.isV1(v1TwoSignerTx(feePayer, signerB).serialized()));

    // Right count byte, wrong version byte: an ordinary two-signer legacy transaction, signed so
    // that its second byte — the first byte of the fee payer signature — is non-zero and the
    // version half of the check is the only thing that can reject it.
    final var legacyTx = legacyTwoSignerTx(feePayer, signerB);
    legacyTx.sign(feePayer);
    final byte[] legacy = legacyTx.serialized();
    assertEquals(2, legacy[0]);
    assertNotEquals(0, legacy[1] & 0xFF, "the fixture must exercise the version half of the check");
    assertFalse(V1Transaction.isV1(legacy));

    // Right version byte, zero count byte: the non-canonical legacy prefix 0x81 0x00.
    final byte[] nonCanonicalPrefix = new byte[200];
    Arrays.fill(nonCanonicalPrefix, (byte) 0x33);
    nonCanonicalPrefix[0] = (byte) 0x81;
    nonCanonicalPrefix[1] = 0;
    assertFalse(V1Transaction.isV1(nonCanonicalPrefix));
    // ...so it is read as legacy: the fee payer signature slot starts at offset 1 and byte 2 of the
    // buffer, inside that slot, is non-zero. Read as v1 the header would declare zero signatures
    // and the length-implied signature block would not corroborate the message.
    assertEquals(1, BaseTransaction.feePayerSignatureOffset(nonCanonicalPrefix));
  }

  // ---------------------------------------------------------------------------------------------
  // BaseTransaction#setBlockHash — the non-BaseTransaction branch
  // ---------------------------------------------------------------------------------------------

  /// `setBlockHash` copies the recent block hash straight between two `BaseTransaction` buffers
  /// when it can, and falls back to the public `Transaction#setRecentBlockHash(byte[])` when the
  /// derived transaction is some other `Transaction` implementation. `Transaction` is a public
  /// interface, so that fallback is the only thing carrying the block hash across for an
  /// implementation this module did not write — and derived transactions that silently lose the
  /// block hash are signed against a hash of zeros and rejected by the cluster.
  @Test
  void aDerivedTransactionOutsideTheBaseHierarchyStillReceivesTheBlockHash() {
    final byte[] data = new byte[BLOCK_HASH_LENGTH];
    System.arraycopy(blockHash(), 0, data, 0, BLOCK_HASH_LENGTH);

    final var derived = new ForeignTransaction();
    final var source = new BlockHashOnlyTransaction(data, derived);
    assertArrayEquals(blockHash(), source.recentBlockHash());
    assertArrayEquals(new byte[BLOCK_HASH_LENGTH], derived.recentBlockHash());

    final var ix = Instruction.createInstruction(SolanaAccounts.MAIN_NET.systemProgram(), List.of(), new byte[0]);
    assertSame(derived, source.appendIx(ix));
    assertArrayEquals(blockHash(), derived.recentBlockHash(),
        "the derived transaction must carry the source's recent block hash"
    );
  }

  // ---------------------------------------------------------------------------------------------
  // Transaction#createTx — lookup table selection
  // ---------------------------------------------------------------------------------------------

  /// Minimal active lookup table: a 56-byte meta with `deactivationSlot = u64::MAX`, then the
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

  private static final PublicKey FEE_PAYER = key(10);
  private static final PublicKey PROGRAM = key(11);
  private static final PublicKey WRITE_ACCOUNT = key(12);
  private static final PublicKey READ_ACCOUNT = key(13);
  private static final PublicKey TABLE_READ_2 = key(15);
  private static final PublicKey ALT_ADDRESS = key(30);
  private static final PublicKey ALT_ADDRESS_2 = key(31);

  private static Instruction tableInstruction() {
    return Instruction.createInstruction(
        PROGRAM,
        List.of(AccountMeta.createWrite(WRITE_ACCOUNT), AccountMeta.createRead(READ_ACCOUNT)),
        new byte[]{1, 2, 3}
    );
  }

  /// A one-element `LookupTableAccountMeta[]` must be unwrapped and routed through the single-table
  /// factory, not walked as a degenerate multi-table transaction. The serialized bytes agree either
  /// way, but the resulting transaction does not: the single-table factory records the table on
  /// `lookupTable()`, which is what `TransactionRecord#createTransaction` reads back when deriving
  /// a transaction, so routing a single table down the multi-table path loses it and every derived
  /// transaction silently drops to a table-free layout.
  @Test
  void aSingleTableMetaRoutesThroughTheSingleTableFactory() {
    final var ix = tableInstruction();
    final var feePayerMeta = AccountMeta.createFeePayer(FEE_PAYER);
    final var table = alt(ALT_ADDRESS, key(21), READ_ACCOUNT, key(22));

    final var accounts = HashMap.<PublicKey, AccountMeta>newHashMap(Transaction.MAX_ACCOUNTS);
    final int ixLength = TransactionRecord.mergeAccounts(feePayerMeta, accounts, List.of(ix));
    final var tx = Transaction.createTx(
        List.of(ix),
        ixLength,
        TransactionRecord.sortV0Accounts(accounts),
        new LookupTableAccountMeta[]{LookupTableAccountMeta.createMeta(table)}
    );

    assertNotNull(tx.lookupTable(), "a single table belongs on lookupTable()");
    assertEquals(ALT_ADDRESS, tx.lookupTable().address());
    assertEquals(0, tx.tableAccountMetas().length, "...and not on tableAccountMetas()");
  }

  /// When every supplied table indexed at least one account the caller's array is carried through
  /// as-is. The two branches serialize identically — the filtered branch just re-serializes the
  /// same tables in the same order — so the array the transaction hands back is the only thing that
  /// distinguishes them, and a caller holding `LookupTableAccountMeta` instances to reset and reuse
  /// needs the ones the transaction actually indexed against.
  @Test
  void allTablesIndexedCarriesTheCallersMetaArrayThrough() {
    final var ix = Instruction.createInstruction(
        PROGRAM,
        List.of(
            AccountMeta.createWrite(WRITE_ACCOUNT),
            AccountMeta.createRead(READ_ACCOUNT),
            AccountMeta.createRead(TABLE_READ_2)
        ),
        new byte[]{7}
    );
    final var table1 = alt(ALT_ADDRESS, key(21), READ_ACCOUNT);
    final var table2 = alt(ALT_ADDRESS_2, TABLE_READ_2, key(22));
    final var tableMetas = new LookupTableAccountMeta[]{
        LookupTableAccountMeta.createMeta(table1),
        LookupTableAccountMeta.createMeta(table2)
    };

    final var tx = Transaction.createTx(AccountMeta.createFeePayer(FEE_PAYER), List.of(ix), tableMetas);
    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.serialized());
    assertArrayEquals(new PublicKey[]{ALT_ADDRESS, ALT_ADDRESS_2}, skeleton.lookupTableAccounts(),
        "the fixture must have both tables indexing an account"
    );
    assertEquals(1, tableMetas[0].numIndexed());
    assertEquals(1, tableMetas[1].numIndexed());

    assertSame(tableMetas, tx.tableAccountMetas());
  }

  // ---------------------------------------------------------------------------------------------
  // Test doubles for the non-BaseTransaction branch of setBlockHash
  // ---------------------------------------------------------------------------------------------

  /// A `Transaction` outside the `BaseTransaction` hierarchy, recording only the block hash handed
  /// to it. Nothing else is reachable from `BaseTransaction#setBlockHash`.
  private static final class ForeignTransaction implements Transaction {

    private final byte[] recentBlockHash = new byte[BLOCK_HASH_LENGTH];

    @Override
    public void setRecentBlockHash(final byte[] recentBlockHash) {
      System.arraycopy(recentBlockHash, 0, this.recentBlockHash, 0, BLOCK_HASH_LENGTH);
    }

    @Override
    public byte[] recentBlockHash() {
      return recentBlockHash.clone();
    }

    @Override
    public void setRecentBlockHash(final String recentBlockHash) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void sign(final Signer signer) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void sign(final int index, final Signer signer) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void sign(final Collection<Signer> signers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void sign(final SequencedCollection<Signer> signers) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getBase58Id() {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getId() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int size() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean exceedsSizeLimit() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int numAccounts() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int numInstructions() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int numSigners() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean exceedsSignatureLimit() {
      throw new UnsupportedOperationException();
    }

    @Override
    public AccountMeta feePayer() {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Instruction> instructions() {
      throw new UnsupportedOperationException();
    }

    @Override
    public AddressLookupTable lookupTable() {
      throw new UnsupportedOperationException();
    }

    @Override
    public LookupTableAccountMeta[] tableAccountMetas() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int version() {
      throw new UnsupportedOperationException();
    }

    @Override
    public byte[] serialized() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Transaction prependIx(final Instruction ix) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Transaction prependInstructions(final Instruction ix1, final Instruction ix2) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Transaction prependInstructions(final SequencedCollection<Instruction> instructions) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Transaction appendIx(final Instruction ix) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Transaction appendInstructions(final SequencedCollection<Instruction> instructions) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Transaction replaceInstruction(final int index, final Instruction instruction) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Transaction setPriorityFeeLamports(final long priorityFeeLamports) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Transaction setComputeUnitLimit(final int computeUnitLimit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Transaction setPriorityFeeLamportsFromComputeUnitPrice(final long microLamportsPerComputeUnit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Transaction setAccountDataSizeLimit(final int accountDataSizeLimit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Transaction setHeapSize(final int heapSize) {
      throw new UnsupportedOperationException();
    }
  }

  /// A `BaseTransaction` whose serialized data is nothing but a recent block hash, and whose
  /// derived transactions are `ForeignTransaction`s. The two production subclasses both derive
  /// their own kind, so this is the only way to exercise the fallback arm of `setBlockHash`.
  private static final class BlockHashOnlyTransaction extends BaseTransaction {

    private final ForeignTransaction derived;

    private BlockHashOnlyTransaction(final byte[] data, final ForeignTransaction derived) {
      super(AccountMeta.createFeePayer(FEE_PAYER), List.of(), data);
      this.derived = derived;
    }

    @Override
    protected int recentBlockHashIndex() {
      return 0;
    }

    @Override
    protected int accountsOffset() {
      throw new UnsupportedOperationException();
    }

    @Override
    protected int messageOffset() {
      throw new UnsupportedOperationException();
    }

    @Override
    protected int messageLength() {
      throw new UnsupportedOperationException();
    }

    @Override
    protected int signatureOffset(final int signerIndex) {
      throw new UnsupportedOperationException();
    }

    @Override
    protected void recordNumSignatures(final int numSignatures) {
      throw new UnsupportedOperationException();
    }

    @Override
    protected Transaction createTransaction(final List<Instruction> instructions) {
      return derived;
    }

    @Override
    public AddressLookupTable lookupTable() {
      return null;
    }

    @Override
    public LookupTableAccountMeta[] tableAccountMetas() {
      return TransactionRecord.NO_TABLES;
    }

    @Override
    public int numSigners() {
      return 0;
    }

    @Override
    public int version() {
      return 0;
    }

    @Override
    public boolean exceedsSizeLimit() {
      return false;
    }

    @Override
    public int numAccounts() {
      return 0;
    }

    @Override
    public boolean exceedsSignatureLimit() {
      return false;
    }

    @Override
    public Transaction setPriorityFeeLamports(final long priorityFeeLamports) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Transaction setComputeUnitLimit(final int computeUnitLimit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Transaction setPriorityFeeLamportsFromComputeUnitPrice(final long microLamportsPerComputeUnit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Transaction setAccountDataSizeLimit(final int accountDataSizeLimit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Transaction setHeapSize(final int heapSize) {
      throw new UnsupportedOperationException();
    }
  }
}
