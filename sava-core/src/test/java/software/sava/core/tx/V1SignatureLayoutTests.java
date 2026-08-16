package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.encoding.Base58;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.tx.Transaction.SIGNATURE_LENGTH;

/// Pins the two v1 signature-boundary invariants.
///
/// A SIMD-0385 v1 message appends its signatures *after* the instruction payloads, so nothing on
/// the wire separates the message from the signature block: the boundary is implied by the
/// serialized length alone. Both fixes exist because a boundary derived from a length the message
/// itself does not corroborate lands inside the message, and signing then writes signature bytes
/// over the tail of the very message being signed.
///
/// Every signer in this class is derived from a fixed private key so that a failure is exactly
/// reproducible rather than dependent on a generated key pair.
final class V1SignatureLayoutTests {

  // Instruction payload large enough that a boundary derived from an over-supplied signer count
  // (message end - 64) still lands well inside the instruction payloads.
  private static final int IX_DATA_LENGTH = 256;

  private static Signer signer(final int seed) {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    Arrays.fill(privateKey, (byte) seed);
    return Signer.createFromPrivateKey(privateKey);
  }

  private static byte[] blockHash() {
    final byte[] blockHash = new byte[Transaction.BLOCK_HASH_LENGTH];
    for (int b = 0; b < blockHash.length; ++b) {
      blockHash[b] = (byte) (b + 1);
    }
    return blockHash;
  }

  private static Instruction twoSignerInstruction(final Signer signerB) {
    final byte[] ixData = new byte[IX_DATA_LENGTH];
    for (int i = 0; i < ixData.length; ++i) {
      ixData[i] = (byte) (i + 1);
    }
    return Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createWritableSigner(signerB.publicKey())),
        ixData
    );
  }

  /// A two-signer v1 transaction: the fee payer plus one writable signer.
  private static Transaction createV1Transaction(final Signer feePayer, final Signer signerB) {
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

  /// The end of the parsed message, i.e. where the signature block must begin.
  private static int messageEnd(final TransactionSkeleton skeleton) {
    return skeleton.instructionsOffset() + skeleton.serializedInstructionsLength();
  }

  private static String expectedLayoutMessage(final TransactionSkeleton skeleton) {
    final byte[] data = skeleton.data();
    return String.format(
        "v1 message ends at offset %d but its %d signature slots begin at offset %d.",
        messageEnd(skeleton),
        skeleton.numSignatures(),
        data.length - (skeleton.numSignatures() * SIGNATURE_LENGTH)
    );
  }

  /// Every `createTransaction` overload funnels through
  /// `V1TransactionSkeleton#createTransaction(List)`, which is the only guarded one, so all of them
  /// must reject a payload whose length does not corroborate the parsed message end.
  private static void assertEveryV1MutableCreationRejects(final TransactionSkeleton skeleton) {
    final byte[] before = skeleton.data().clone();
    final var accounts = skeleton.parseAccounts();
    final var instructions = Arrays.asList(skeleton.parseInstructions(accounts));
    final var instructionArray = instructions.toArray(Instruction[]::new);
    final var expectedMessage = expectedLayoutMessage(skeleton);

    final var thrown = assertThrowsExactly(IllegalStateException.class, skeleton::createTransaction);
    assertEquals(expectedMessage, thrown.getMessage());

    assertEquals(expectedMessage, assertThrowsExactly(
        IllegalStateException.class,
        () -> skeleton.createTransaction(instructions)
    ).getMessage());
    assertEquals(expectedMessage, assertThrowsExactly(
        IllegalStateException.class,
        () -> skeleton.createTransaction(instructionArray)
    ).getMessage());
    assertEquals(expectedMessage, assertThrowsExactly(
        IllegalStateException.class,
        () -> skeleton.createTransaction(accounts)
    ).getMessage());
    assertEquals(expectedMessage, assertThrowsExactly(
        IllegalStateException.class,
        () -> skeleton.createTransaction((AddressLookupTable) null)
    ).getMessage());
    assertEquals(expectedMessage, assertThrowsExactly(
        IllegalStateException.class,
        () -> skeleton.createTransaction(new LookupTableAccountMeta[0])
    ).getMessage());
    assertEquals(expectedMessage, assertThrowsExactly(
        IllegalStateException.class,
        () -> skeleton.createTransaction(instructions, (AddressLookupTable) null)
    ).getMessage());
    assertEquals(expectedMessage, assertThrowsExactly(
        IllegalStateException.class,
        () -> skeleton.createTransaction(instructions, new LookupTableAccountMeta[0])
    ).getMessage());
    // The remaining deprecated convenience overloads delegate to the same guarded method.
    assertEquals(expectedMessage, assertThrowsExactly(
        IllegalStateException.class,
        () -> skeleton.createTransaction(instructionArray, (AddressLookupTable) null)
    ).getMessage());
    assertEquals(expectedMessage, assertThrowsExactly(
        IllegalStateException.class,
        () -> skeleton.createTransaction(accounts, (AddressLookupTable) null)
    ).getMessage());
    assertEquals(expectedMessage, assertThrowsExactly(
        IllegalStateException.class,
        () -> skeleton.createTransaction(accounts, new LookupTableAccountMeta[0])
    ).getMessage());

    assertArrayEquals(before, skeleton.data(), "a rejected conversion must not mutate the parsed bytes");
  }

  // ---------------------------------------------------------------------------------------------
  // FIX 1: V1TransactionSkeleton#signaturesOffset()
  // ---------------------------------------------------------------------------------------------

  /// Positive control for FIX 1. Pins that
  /// `V1TransactionSkeleton#signaturesOffset()` accepts a well-formed payload and
  /// returns the true signature offset, so signing writes only into the appended signature slots.
  ///
  /// This test passes both with and without the fix; it exists so the rejection tests below cannot
  /// be satisfied by a guard that rejects everything.
  @Test
  void validV1PayloadRoundTripsAndSigningLeavesTheMessageIntact() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final byte[] data = createV1Transaction(feePayer, signerB).serialized();
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    assertInstanceOf(V1TransactionSkeleton.class, skeleton);
    assertEquals(2, skeleton.numSignatures());

    final int messageEnd = messageEnd(skeleton);
    // The invariant signaturesOffset() asserts.
    assertEquals(data.length - (2 * SIGNATURE_LENGTH), messageEnd);

    final byte[] message = Arrays.copyOfRange(data, 0, messageEnd);

    final var tx = assertInstanceOf(V1Transaction.class, skeleton.createTransaction());
    assertSame(data, tx.serialized(), "the mutable transaction shares the parsed buffer");
    tx.sign(feePayer);
    tx.sign(signerB);

    assertArrayEquals(message, Arrays.copyOfRange(data, 0, messageEnd),
        "signing must not touch a single byte of the message"
    );
    assertTrue(feePayer.publicKey().verifySignature(
        data, 0, messageEnd,
        Arrays.copyOfRange(data, messageEnd, messageEnd + SIGNATURE_LENGTH)
    ));
    assertTrue(signerB.publicKey().verifySignature(
        data, 0, messageEnd,
        Arrays.copyOfRange(data, messageEnd + SIGNATURE_LENGTH, data.length)
    ));
  }

  /// The skeleton guard only covers callers that parsed a skeleton. `Transaction.getBase58Id(byte[])`
  /// and `getId(byte[])` are public statics over raw bytes and reach the same length-derived offset
  /// through `BaseTransaction#feePayerSignatureOffset`, so they need the corroboration too — without
  /// it a padded buffer returns a *different, wrong* id with no exception at all, which is worse
  /// than throwing because nothing signals it.
  @Test
  void paddedV1BytesCannotReportATransactionIdThroughTheStaticHelpers() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final var tx = createV1Transaction(feePayer, signerB);
    tx.sign(feePayer);
    tx.sign(signerB);
    final byte[] signed = tx.serialized();
    final String trueId = tx.getBase58Id();

    final byte[] padded = Arrays.copyOf(signed, signed.length + 4);
    final int slidOffset = padded.length - (2 * SIGNATURE_LENGTH);
    final String wrongId = Base58.encode(padded, slidOffset, slidOffset + SIGNATURE_LENGTH);
    assertNotEquals(trueId, wrongId, "the padding must actually move the id window");

    final var expected = String.format(
        "A v1 message ending at offset %d does not corroborate the %d signature slots a %d byte buffer places at offset %d.",
        V1TransactionSkeleton.messageEnd(padded), 2, padded.length, slidOffset
    );
    assertEquals(expected,
        assertThrowsExactly(IllegalArgumentException.class, () -> Transaction.getBase58Id(padded)).getMessage()
    );
    assertEquals(expected,
        assertThrowsExactly(IllegalArgumentException.class, () -> Transaction.getId(padded)).getMessage()
    );
    assertEquals(trueId, Transaction.getBase58Id(signed), "the unpadded buffer still reports the real id");
  }

  /// The sibling of the `SequencedCollection` overload, left unguarded by the first pass. It takes
  /// its signer count from the header already, but still derived the message boundary from
  /// `out.length`, so a padded buffer signed the wrong span and wrote the signature past the real
  /// fee-payer slot — the same corruption, reached through a different entry point.
  @Test
  void staticSingleSignerRejectsAPaddedV1Payload() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final byte[] signable = createV1Transaction(feePayer, signerB).serialized();
    final byte[] padded = Arrays.copyOf(signable, signable.length + 4);
    final byte[] before = padded.clone();

    assertThrowsExactly(IllegalArgumentException.class, () -> Transaction.sign(feePayer, padded));
    assertArrayEquals(before, padded, "a rejected signing must not mutate a single byte");

    // The unpadded buffer still signs, and lands in the real fee payer slot.
    final byte[] good = signable.clone();
    final int messageEnd = messageEnd(TransactionSkeleton.deserializeSkeleton(good));
    Transaction.sign(feePayer, good);
    assertTrue(feePayer.publicKey().verifySignature(
        good, 0, messageEnd,
        Arrays.copyOfRange(good, messageEnd, messageEnd + SIGNATURE_LENGTH)
    ));
  }

  /// A hostile header count must fail as a diagnosed v1 layout error rather than as an opaque
  /// argument error raised from inside the Ed25519 signer once the offset has already gone negative.
  @Test
  void staticSingleSignerRejectsAnImpossibleHeaderCount() {
    final var feePayer = signer(11);
    final byte[] out = createV1Transaction(feePayer, signer(22)).serialized();
    out[1] = (byte) 250; // 250 * 64 bytes of signatures cannot fit

    assertEquals(
        String.format("A v1 transaction of %d bytes cannot hold the 250 signatures its header declares.", out.length),
        assertThrowsExactly(IllegalArgumentException.class, () -> Transaction.sign(feePayer, out)).getMessage()
    );
  }

  /// `signaturesOffset()` bounds the fixed-width header block before walking it, because
  /// `serializedInstructionsLength()` reads three bytes per header that `deserialize` never touched.
  /// A payload truncated *inside* the header block must therefore still produce the documented
  /// `IllegalStateException` rather than the `ArrayIndexOutOfBoundsException` the bare walk raises.
  /// Every other rejection test truncates in the payload region, which never reaches this branch.
  @Test
  void aPayloadTruncatedInsideTheInstructionHeadersIsDiagnosed() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final byte[] signable = createV1Transaction(feePayer, signerB).serialized();
    final var whole = TransactionSkeleton.deserializeSkeleton(signable);
    final int instructionsOffset = whole.instructionsOffset();
    final int numInstructions = whole.numInstructions();
    final int headerBlockEnd = instructionsOffset + (numInstructions * 4);
    assertTrue(numInstructions > 0 && headerBlockEnd < signable.length, "fixture must have headers");

    // Cut one byte short of the end of the header block: deserialize only reads header byte 0, so
    // it still succeeds, and the guard is the only thing standing between the caller and an AIOOBE.
    final byte[] truncated = Arrays.copyOfRange(signable, 0, headerBlockEnd - 1);
    final var skeleton = TransactionSkeleton.deserializeSkeleton(truncated);
    assertInstanceOf(V1TransactionSkeleton.class, skeleton);

    final var expected = String.format(
        "A v1 message of %d bytes cannot hold %d instruction headers and %d signature slots.",
        truncated.length, numInstructions, 2
    );
    assertEquals(expected, assertThrowsExactly(IllegalStateException.class, skeleton::id).getMessage());
    assertEquals(expected, assertThrowsExactly(
        IllegalStateException.class,
        () -> skeleton.createTransaction(List.<Instruction>of())
    ).getMessage());

    // The bare walk, without the bound, is what the guard replaces.
    assertThrowsExactly(ArrayIndexOutOfBoundsException.class, skeleton::serializedInstructionsLength);
    // ...and it is why the guard only helps the entry points that reach it WITHOUT parsing: id(),
    // createTransaction(List), createTransaction(Instruction[]) and the lookup-table overloads that
    // are handed instructions. The parse-first overloads — createTransaction(),
    // createTransaction(AccountMeta[]), createTransaction(AddressLookupTable),
    // createTransaction(LookupTableAccountMeta[]) — walk the truncated headers to build their
    // instructions before they ever reach the guard. Pinned so the split is deliberate: bounding
    // every public view is the separate, main-inherited concern that
    // TransactionSkeletonImpl.serializedInstructionsLength shares.
    assertThrowsExactly(ArrayIndexOutOfBoundsException.class, skeleton::createTransaction);
  }

  /// The lower half of the same guard: a buffer whose declared signature block starts before the
  /// header block even ends is rejected by the `signaturesOffset < headerBlockEnd` arm.
  @Test
  void aSignatureBlockOverlappingTheInstructionHeadersIsDiagnosed() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final byte[] signable = createV1Transaction(feePayer, signerB).serialized();
    final var whole = TransactionSkeleton.deserializeSkeleton(signable);
    final int numInstructions = whole.numInstructions();
    // Keep every header byte but drop enough payload that 2 * 64 signature bytes would start inside
    // the header block.
    final int headerBlockEnd = whole.instructionsOffset() + (numInstructions * 4);
    final byte[] truncated = Arrays.copyOfRange(signable, 0, headerBlockEnd + (2 * SIGNATURE_LENGTH) - 1);
    final var skeleton = TransactionSkeleton.deserializeSkeleton(truncated);

    assertEquals(
        String.format(
            "A v1 message of %d bytes cannot hold %d instruction headers and %d signature slots.",
            truncated.length, numInstructions, 2
        ),
        assertThrowsExactly(
            IllegalStateException.class,
            () -> skeleton.createTransaction(List.<Instruction>of())
        ).getMessage()
    );
  }

  /// The exact boundary of the header-block bound: a transaction whose instructions carry no
  /// accounts and no data ends precisely where its header block does, so `signaturesOffset` equals
  /// `headerBlockEnd`. The guard uses `<`, and this pins that it must stay `<` — widening it to
  /// `<=` would reject a legal, minimal transaction.
  @Test
  void aTransactionWhoseInstructionsHaveNoAccountsOrDataSitsExactlyOnTheHeaderBound() {
    final var feePayer = signer(11);
    final var tx = TxBuilder.createBuilder()
        .feePayer(feePayer.publicKey())
        .addInstruction(Instruction.createInstruction(
            SolanaAccounts.MAIN_NET.systemProgram(), List.of(), new byte[0]
        ))
        .createTransaction();
    tx.setRecentBlockHash(blockHash());

    final byte[] data = tx.serialized();
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    final int headerBlockEnd = skeleton.instructionsOffset() + (skeleton.numInstructions() * 4);
    final int signaturesOffset = data.length - (skeleton.numSignatures() * SIGNATURE_LENGTH);
    assertEquals(headerBlockEnd, signaturesOffset, "fixture must sit exactly on the bound");

    // Both guarded entry points, and the raw-byte one, must accept it.
    assertDoesNotThrow(skeleton::id);
    assertDoesNotThrow(() -> skeleton.createTransaction(List.<Instruction>of()));
    tx.sign(feePayer);
    assertDoesNotThrow(() -> Transaction.getBase58Id(data));
  }

  /// `V1TransactionSkeleton#messageEnd` returns -1 when the buffer cannot hold the headers its own
  /// count declares, rather than walking off the end. Pinned through the raw-byte entry point,
  /// asserting the sentinel reaches the caller's diagnostic verbatim.
  @Test
  void anImpossibleInstructionCountYieldsNoMessageEnd() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final byte[] data = createV1Transaction(feePayer, signerB).serialized();
    // NumInstructions sits one byte before NumAddresses, itself one byte before the accounts.
    data[V1TransactionSkeleton.V1_ACCOUNTS_OFFSET - 2] = (byte) 200;

    assertEquals(-1, V1TransactionSkeleton.messageEnd(data));
    assertEquals(
        String.format(
            "A v1 message ending at offset -1 does not corroborate the 2 signature slots a %d byte buffer places at offset %d.",
            data.length, data.length - (2 * SIGNATURE_LENGTH)
        ),
        assertThrowsExactly(IllegalArgumentException.class, () -> Transaction.getBase58Id(data)).getMessage()
    );
  }

  /// The unparsed walk and the parsed skeleton must agree, or the two guards drift apart.
  @Test
  void theUnparsedMessageEndAgreesWithTheParsedSkeleton() {
    final byte[] data = createV1Transaction(signer(11), signer(22)).serialized();
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    assertEquals(messageEnd(skeleton), V1TransactionSkeleton.messageEnd(data));
    assertEquals(data.length - (2 * SIGNATURE_LENGTH), V1TransactionSkeleton.messageEnd(data));
  }

  /// Pins that the same guard covers the *read* path, not only the conversion to a mutable
  /// transaction. A v1 transaction's id is its fee payer signature, which lives at the same
  /// `data.length`-derived offset the guard exists to distrust, so trailing bytes silently slide
  /// the 64-byte window and hand the caller a value that is not the transaction's id — with no
  /// error to notice, and nothing to poll for confirmation against. Legacy is immune because its
  /// first signature slot is always at offset 1.
  @Test
  void paddedV1PayloadCannotReportATransactionId() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final var tx = createV1Transaction(feePayer, signerB);
    tx.sign(feePayer);
    tx.sign(signerB);
    final byte[] signed = tx.serialized();
    final String trueId = tx.getBase58Id();

    final byte[] padded = Arrays.copyOf(signed, signed.length + 4);
    final var skeleton = TransactionSkeleton.deserializeSkeleton(padded);
    assertInstanceOf(V1TransactionSkeleton.class, skeleton);

    // What the unguarded derivation returns: a 64 byte window slid forward by the four pad bytes.
    final int slidOffset = padded.length - (2 * SIGNATURE_LENGTH);
    final String wrongId = Base58.encode(padded, slidOffset, slidOffset + SIGNATURE_LENGTH);
    assertNotEquals(trueId, wrongId, "the padding must actually move the id window");

    assertEquals(
        String.format(
            "v1 message ends at offset %d but its 2 signature slots begin at offset %d.",
            messageEnd(skeleton), slidOffset
        ),
        assertThrowsExactly(IllegalStateException.class, skeleton::id).getMessage()
    );
    assertEquals(trueId, TransactionSkeleton.deserializeSkeleton(signed).id(),
        "the unpadded payload still reports the real id"
    );
  }

  /// Pins `BaseTransaction#feePayerSignatureOffset`'s bound on the header's signature count. The
  /// count is untrusted here — `Transaction#getBase58Id(byte[])` is public and takes raw bytes —
  /// and a count larger than the buffer can hold drives the offset negative, which without the
  /// bound reads from a negative index deep inside Base58 rather than reporting a malformed
  /// transaction.
  @Test
  void aV1BufferTooShortForItsDeclaredSignaturesIsRejected() {
    final byte[] hostile = new byte[40];
    hostile[0] = (byte) 129; // v1 version byte
    hostile[1] = 2;          // claims two 64 byte signatures inside 40 bytes

    final var expected = "A v1 transaction of 40 bytes cannot hold the 2 signatures its header declares.";
    assertEquals(expected,
        assertThrowsExactly(IllegalArgumentException.class, () -> Transaction.getBase58Id(hostile)).getMessage()
    );
    assertEquals(expected,
        assertThrowsExactly(IllegalArgumentException.class, () -> Transaction.getId(hostile)).getMessage()
    );
  }

  /// Pins `V1TransactionSkeleton#signaturesOffset()`'s
  /// `if (messageEnd != signaturesOffset) throw ...` against a payload truncated by one byte.
  ///
  /// This is the reviewer's reproduction. Without the check, `createTransaction` derived the
  /// signature offset from `data.length - numSignatures * SIGNATURE_LENGTH` alone, which for a
  /// one-byte-short payload is one byte *inside* the final instruction payload, and signing
  /// overwrote that byte. The second half of this test replays that exact pre-fix arithmetic
  /// through the package-private `V1Transaction` constructor — no production source is modified —
  /// and shows the corruption the guard prevents.
  @Test
  void truncatedV1PayloadCannotBecomeMutable() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final byte[] signable = createV1Transaction(feePayer, signerB).serialized();
    final byte[] truncated = Arrays.copyOfRange(signable, 0, signable.length - 1);

    final var skeleton = TransactionSkeleton.deserializeSkeleton(truncated);
    assertInstanceOf(V1TransactionSkeleton.class, skeleton);
    assertEquals(2, skeleton.numSignatures());

    final int messageEnd = messageEnd(skeleton);
    final int impliedSignaturesOffset = truncated.length - (2 * SIGNATURE_LENGTH);
    // The pre-fix arithmetic: one byte short of the parsed message end, i.e. inside the message.
    assertEquals(messageEnd - 1, impliedSignaturesOffset);
    assertTrue(impliedSignaturesOffset < messageEnd, "the pre-fix boundary lands inside the message");

    final byte[] before = truncated.clone();
    final var thrown = assertThrowsExactly(IllegalStateException.class, skeleton::createTransaction);
    assertEquals(
        String.format(
            "v1 message ends at offset %d but its 2 signature slots begin at offset %d.",
            messageEnd, impliedSignaturesOffset
        ),
        thrown.getMessage()
    );
    assertArrayEquals(before, truncated, "a rejected conversion must not mutate the parsed bytes");

    // Replay the pre-fix construction on a scratch copy: same feePayer, same instructions, same
    // buffer, but the unchecked `data.length - numSignatures * SIGNATURE_LENGTH` boundary.
    final byte[] preFixBuffer = truncated.clone();
    final var preFixTx = new V1Transaction(
        AccountMeta.createFeePayer(skeleton.feePayer()),
        Arrays.asList(skeleton.parseInstructions(skeleton.parseAccounts())),
        preFixBuffer,
        impliedSignaturesOffset
    );
    preFixTx.sign(feePayer);
    preFixTx.sign(signerB);

    // The signature block now starts one byte early, so the last instruction payload byte holds a
    // signature byte and no candidate slot authenticates the message the wire format defines.
    // Both assertions are cryptographic, not probabilistic: an ed25519 signature over
    // data[0, messageEnd - 1) cannot verify over data[0, messageEnd).
    assertFalse(feePayer.publicKey().verifySignature(
        preFixBuffer, 0, messageEnd,
        Arrays.copyOfRange(preFixBuffer, impliedSignaturesOffset, impliedSignaturesOffset + SIGNATURE_LENGTH)
    ), "the pre-fix layout signs a message one byte shorter than the parsed message");
    assertFalse(feePayer.publicKey().verifySignature(
        preFixBuffer, 0, messageEnd,
        Arrays.copyOfRange(preFixBuffer, messageEnd, messageEnd + SIGNATURE_LENGTH)
    ), "nor is a valid signature recoverable from the correctly located slot");
  }

  /// Pins the same guard against a payload padded by one byte, where the implied signature offset
  /// runs one byte *past* the parsed message end and signing would leave the first byte of the
  /// signature block inside what the runtime treats as the message.
  @Test
  void paddedV1PayloadCannotBecomeMutable() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final byte[] signable = createV1Transaction(feePayer, signerB).serialized();
    final byte[] padded = Arrays.copyOf(signable, signable.length + 1);

    final var skeleton = TransactionSkeleton.deserializeSkeleton(padded);
    assertInstanceOf(V1TransactionSkeleton.class, skeleton);

    final int messageEnd = messageEnd(skeleton);
    final int impliedSignaturesOffset = padded.length - (2 * SIGNATURE_LENGTH);
    assertEquals(messageEnd + 1, impliedSignaturesOffset);

    final byte[] before = padded.clone();
    final var thrown = assertThrowsExactly(IllegalStateException.class, skeleton::createTransaction);
    assertEquals(
        String.format(
            "v1 message ends at offset %d but its 2 signature slots begin at offset %d.",
            messageEnd, impliedSignaturesOffset
        ),
        thrown.getMessage()
    );
    assertArrayEquals(before, padded, "a rejected conversion must not mutate the parsed bytes");
  }

  /// Pins that the guard is reached from every `createTransaction` overload on
  /// `V1TransactionSkeleton`, including the ones inherited as `TransactionSkeleton` defaults and
  /// the v1 lookup-table overrides which discard their table arguments.
  @Test
  void everyCreateTransactionOverloadRejectsATruncatedV1Payload() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final byte[] signable = createV1Transaction(feePayer, signerB).serialized();
    final byte[] truncated = Arrays.copyOfRange(signable, 0, signable.length - 1);
    assertEveryV1MutableCreationRejects(TransactionSkeleton.deserializeSkeleton(truncated));

    final byte[] padded = Arrays.copyOf(signable, signable.length + 1);
    assertEveryV1MutableCreationRejects(TransactionSkeleton.deserializeSkeleton(padded));
  }

  /// Parsing stays deliberately permissive for offline analysis: everything that does not depend on
  /// the unverified serialized length still works on a truncated payload. Only the conversion to a
  /// mutable transaction, which is what would rewrite bytes, is refused.
  @Test
  void truncatedV1PayloadRemainsReadableButCannotBecomeMutable() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final var tx = createV1Transaction(feePayer, signerB);
    final byte[] signable = tx.serialized();
    final byte[] truncated = Arrays.copyOfRange(signable, 0, signable.length - 1);

    final var skeleton = TransactionSkeleton.deserializeSkeleton(truncated);
    assertEquals(1, skeleton.version());
    assertTrue(skeleton.isVersioned());
    assertFalse(skeleton.isLegacy());
    assertEquals(2, skeleton.numSignatures());
    assertEquals(1, skeleton.numInstructions());
    assertEquals(feePayer.publicKey(), skeleton.feePayer());
    assertArrayEquals(blockHash(), skeleton.blockHash());

    final var accounts = skeleton.parseAccounts();
    assertEquals(3, accounts.length);
    assertEquals(feePayer.publicKey(), accounts[0].publicKey());
    assertEquals(signerB.publicKey(), accounts[1].publicKey());

    final var instructions = skeleton.parseInstructions(accounts);
    assertEquals(1, instructions.length);
    assertEquals(SolanaAccounts.MAIN_NET.systemProgram(), instructions[0].programId().publicKey());
    assertEquals(IX_DATA_LENGTH, instructions[0].len());
    assertArrayEquals(
        new PublicKey[]{SolanaAccounts.MAIN_NET.systemProgram()},
        skeleton.parseProgramAccounts()
    );
    assertEquals(1, skeleton.parseInstructionsWithoutAccounts().length);
    assertEquals(1, skeleton.parseInstructionsWithoutTableAccounts().length);

    // ... but the mutable conversion is refused, because signing it would corrupt the message.
    assertThrowsExactly(IllegalStateException.class, skeleton::createTransaction);
  }

  // ---------------------------------------------------------------------------------------------
  // FIX 2: Transaction#sign(SequencedCollection, byte[]) — the v1 branch
  // ---------------------------------------------------------------------------------------------

  /// Pins `Transaction#sign(SequencedCollection, byte[])`'s v1 branch:
  /// `final int numRequiredSignatures = out[1] & 0xFF;` plus the
  /// `if (numSigners != numRequiredSignatures) throw new IllegalArgumentException(...)` guard.
  ///
  /// Without them the boundary came from `signers.size()`. For an under-supplied collection that
  /// offset sits inside the appended signature block, so the message span signed includes an empty
  /// signature slot and the one signature produced lands in the wrong slot. The second half of this
  /// test replays that pre-fix arithmetic through the public low-level
  /// `Transaction#sign(SequencedCollection, byte[], int, int, int)` overload, which is literally
  /// the call the v1 branch makes.
  @Test
  void staticSignRejectsTooFewSignersForAV1Payload() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final byte[] data = createV1Transaction(feePayer, signerB).serialized();
    final int messageEnd = data.length - (2 * SIGNATURE_LENGTH);
    final byte[] before = data.clone();

    final var tooFew = List.of(feePayer);
    final var thrown = assertThrowsExactly(
        IllegalArgumentException.class,
        () -> Transaction.sign(tooFew, data)
    );
    assertEquals("Expected 2 signers, only passed 1.", thrown.getMessage());
    assertArrayEquals(before, data, "a rejected signing must not mutate a single byte");

    // Pre-fix arithmetic: the boundary derived from the collection size, not from the header.
    final int preFixSigOffset = data.length - (tooFew.size() * SIGNATURE_LENGTH);
    assertEquals(messageEnd + SIGNATURE_LENGTH, preFixSigOffset);

    final byte[] preFixBuffer = data.clone();
    Transaction.sign(tooFew, preFixBuffer, 0, preFixSigOffset, preFixSigOffset);
    // The fee payer's slot is still empty and the signature went into the second slot, over a
    // message span that wrongly included the first, empty, signature slot.
    assertArrayEquals(
        new byte[SIGNATURE_LENGTH],
        Arrays.copyOfRange(preFixBuffer, messageEnd, messageEnd + SIGNATURE_LENGTH),
        "the pre-fix layout leaves the fee payer signature slot unwritten"
    );
    assertFalse(
        Arrays.equals(
            before, preFixSigOffset, before.length,
            preFixBuffer, preFixSigOffset, preFixBuffer.length
        ),
        "the pre-fix layout writes the fee payer signature into the second slot"
    );
    assertFalse(feePayer.publicKey().verifySignature(
        preFixBuffer, 0, messageEnd,
        Arrays.copyOfRange(preFixBuffer, preFixSigOffset, preFixBuffer.length)
    ), "the pre-fix layout signs a span that is not the v1 message");
  }

  /// Pins the same guard for an over-supplied collection, which is the destructive direction: the
  /// pre-fix boundary `out.length - signers.size() * 64` lands 64 bytes inside the message, so the
  /// signatures were written straight over the tail of the instruction payloads.
  @Test
  void staticSignRejectsTooManySignersForAV1Payload() {
    final var feePayer = signer(11);
    final var signerB = signer(22);
    final var signerC = signer(33);

    final byte[] data = createV1Transaction(feePayer, signerB).serialized();
    final int messageEnd = data.length - (2 * SIGNATURE_LENGTH);
    final byte[] before = data.clone();

    final var tooMany = List.of(feePayer, signerB, signerC);
    final var thrown = assertThrowsExactly(
        IllegalArgumentException.class,
        () -> Transaction.sign(tooMany, data)
    );
    assertEquals("Expected 2 signers, only passed 3.", thrown.getMessage());
    assertArrayEquals(before, data, "a rejected signing must not mutate a single byte");

    // Pre-fix arithmetic: 64 bytes inside the message.
    final int preFixSigOffset = data.length - (tooMany.size() * SIGNATURE_LENGTH);
    assertEquals(messageEnd - SIGNATURE_LENGTH, preFixSigOffset);
    assertTrue(preFixSigOffset > TransactionSkeleton.deserializeSkeleton(data).instructionsOffset(),
        "the pre-fix boundary lands inside the instruction payloads"
    );

    final byte[] preFixBuffer = data.clone();
    Transaction.sign(tooMany, preFixBuffer, 0, preFixSigOffset, preFixSigOffset);
    assertFalse(
        Arrays.equals(before, 0, messageEnd, preFixBuffer, 0, messageEnd),
        "the pre-fix layout writes signature bytes over the tail of the message"
    );
  }

  /// Pins that the header-derived boundary is the same one the instance API uses, so the added
  /// validation did not change the signing behaviour for a correctly-sized collection.
  @Test
  void staticSignWithTheRequiredSignerCountMatchesTheInstanceApi() {
    final var feePayer = signer(11);
    final var signerB = signer(22);
    final var signers = List.of(feePayer, signerB);

    final var instanceTx = createV1Transaction(feePayer, signerB);
    final byte[] staticData = createV1Transaction(feePayer, signerB).serialized();
    assertArrayEquals(instanceTx.serialized(), staticData, "both fixtures start byte identical");

    instanceTx.sign(signers);
    Transaction.sign(signers, staticData);
    assertArrayEquals(instanceTx.serialized(), staticData);

    final int messageEnd = staticData.length - (2 * SIGNATURE_LENGTH);
    assertTrue(feePayer.publicKey().verifySignature(
        staticData, 0, messageEnd,
        Arrays.copyOfRange(staticData, messageEnd, messageEnd + SIGNATURE_LENGTH)
    ));
    assertTrue(signerB.publicKey().verifySignature(
        staticData, 0, messageEnd,
        Arrays.copyOfRange(staticData, messageEnd + SIGNATURE_LENGTH, staticData.length)
    ));
  }

  /// Pins the untouched legacy branch of `Transaction#sign(SequencedCollection, byte[])`:
  /// `out[0] = (byte) numSigners; final int sigLen = 1 + (numSigners * SIGNATURE_LENGTH);`.
  ///
  /// A legacy message carries its signature count as the first byte and its signatures *before* the
  /// message, so the v1 header check must not be hoisted out of the v1 branch — a legacy buffer has
  /// no `out[1]` signature count to read.
  ///
  /// Two different things are pinned below, and they must not be conflated.
  ///
  /// The matching-signer-count case is the CONTRACT: signatures land in the prefix and the message
  /// is untouched.
  ///
  /// The under-supplied case is CURRENT BEHAVIOUR PENDING sava#54, not a guarantee. It is recorded
  /// because under-supply happens to shrink the prefix, so the writes stay inside it. **The
  /// over-supplied direction is the defect** — it grows the prefix past the real message start and
  /// writes signature bytes over the message header — and is deliberately not asserted here because
  /// it is to be fixed on `main` and rebased in, not fixed on this branch. Nothing below may be read
  /// as evidence that the legacy branch is safe against a mismatch in general.
  @Test
  void staticSignLegacyBranchIsUnchanged() {
    final var feePayer = signer(11);
    final var signerB = signer(22);

    final var legacyTx = Transaction.createTx(feePayer.publicKey(), twoSignerInstruction(signerB));
    legacyTx.setRecentBlockHash(blockHash());
    assertEquals(2, legacyTx.numSigners());
    assertNotEquals(1, legacyTx.version());

    final byte[] unsigned = legacyTx.serialized().clone();
    final int messageOffset = 1 + (2 * SIGNATURE_LENGTH);

    final byte[] data = unsigned.clone();
    Transaction.sign(List.of(feePayer, signerB), data);
    assertEquals(2, data[0], "the count byte is written from the collection");
    assertArrayEquals(
        Arrays.copyOfRange(unsigned, messageOffset, unsigned.length),
        Arrays.copyOfRange(data, messageOffset, data.length),
        "the legacy branch never writes into the message"
    );
    final int messageLength = data.length - messageOffset;
    assertTrue(feePayer.publicKey().verifySignature(
        data, messageOffset, messageLength,
        Arrays.copyOfRange(data, 1, 1 + SIGNATURE_LENGTH)
    ));
    assertTrue(signerB.publicKey().verifySignature(
        data, messageOffset, messageLength,
        Arrays.copyOfRange(data, 1 + SIGNATURE_LENGTH, messageOffset)
    ));

    // CURRENT BEHAVIOUR PENDING sava#54, not a guarantee. An UNDER-supplied collection is accepted
    // by the legacy branch, because the v1 header check is confined to the v1 branch. It happens to
    // be harmless only because fewer signers shrink the prefix: sigLen = 1 + 1*64 is smaller than
    // the real 1 + 2*64, so the writes stay inside it. The OVER-supplied direction is the sava#54
    // defect and grows the prefix into the message; it is deliberately not asserted here.
    final byte[] underSupplied = unsigned.clone();
    assertDoesNotThrow(() -> Transaction.sign(List.of(feePayer), underSupplied));
    assertEquals(1, underSupplied[0], "the count byte follows the collection, not the header");
    assertArrayEquals(
        Arrays.copyOfRange(unsigned, messageOffset, unsigned.length),
        Arrays.copyOfRange(underSupplied, messageOffset, underSupplied.length),
        "under-supply shrinks the prefix, so this direction leaves the message intact"
    );
    // Only the first signature slot was touched.
    assertArrayEquals(
        Arrays.copyOfRange(unsigned, 1 + SIGNATURE_LENGTH, messageOffset),
        Arrays.copyOfRange(underSupplied, 1 + SIGNATURE_LENGTH, messageOffset)
    );
  }
}
