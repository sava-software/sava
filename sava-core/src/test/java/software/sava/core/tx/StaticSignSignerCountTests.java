package software.sava.core.tx;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// The static `byte[]` signing helpers take the signer count from the serialized header rather than
/// from the caller.
///
/// They are static over raw bytes, so the count is untrusted input, and they used to size the
/// signature block from the caller's argument and overwrite the count byte to match. A caller
/// passing fewer signers than the payload requires therefore moved the message start backwards and
/// wrote a signature across the message header — silently, producing a transaction that still looks
/// well formed. The overwrite existed because transaction construction once did not set the count
/// byte; every `createTx` path writes it at allocation now.
final class StaticSignSignerCountTests {

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

  /// A transaction requiring two signatures: the fee payer plus a writable signer.
  private static Transaction twoSignerTx(final Signer feePayer, final Signer authority) {
    final var ix = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createWritableSigner(authority.publicKey())),
        new byte[]{1, 2, 3, 4}
    );
    final var tx = Transaction.createTx(feePayer.publicKey(), ix);
    tx.setRecentBlockHash(new byte[Transaction.BLOCK_HASH_LENGTH]);
    return tx;
  }

  @Test
  void constructionAlwaysWritesTheRequiredSignatureCount() {
    final var tx = twoSignerTx(nextSigner(), nextSigner());

    assertEquals(2, tx.numSigners());
    assertEquals(2, tx.serialized()[0], "createTx sets the count byte, so signing need not");
  }

  @Test
  void aSequencedCollectionShorterThanTheHeaderIsRefused() {
    final var feePayer = nextSigner();
    final var tx = twoSignerTx(feePayer, nextSigner());
    final byte[] data = tx.serialized();
    final byte[] before = data.clone();

    assertEquals(
        "Expected 2 signers, only passed 1.",
        assertThrows(IllegalArgumentException.class, () -> Transaction.sign(List.of(feePayer), data)).getMessage()
    );
    assertArrayEquals(before, data, "the payload must be untouched — the refusal precedes every write");
  }

  @Test
  void aSingleSignerAgainstAWiderHeaderIsRefused() {
    final var feePayer = nextSigner();
    final var tx = twoSignerTx(feePayer, nextSigner());
    final byte[] data = tx.serialized();
    final byte[] before = data.clone();

    assertEquals(
        "Expected 2 signers, only passed 1.",
        assertThrows(IllegalArgumentException.class, () -> Transaction.sign(feePayer, data)).getMessage()
    );
    assertArrayEquals(before, data);

    assertThrows(
        IllegalArgumentException.class,
        () -> Transaction.signAndBase64Encode(feePayer, data),
        "the base64 wrapper routes through the same check"
    );
  }

  @Test
  void moreSignersThanTheHeaderRequiresIsAlsoRefused() {
    final var feePayer = nextSigner();
    final var ix = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createWrite(PublicKey.createPubKey(new byte[PublicKey.PUBLIC_KEY_LENGTH]))),
        new byte[]{1}
    );
    final var tx = Transaction.createTx(feePayer.publicKey(), ix);
    tx.setRecentBlockHash(new byte[Transaction.BLOCK_HASH_LENGTH]);
    final byte[] data = tx.serialized();
    assertEquals(1, data[0]);

    assertEquals(
        "Expected 1 signers, only passed 2.",
        assertThrows(
            IllegalArgumentException.class,
            () -> Transaction.sign(List.of(feePayer, nextSigner()), data)
        ).getMessage()
    );
  }

  /// The matching case must still sign, and every signature must verify against the message span
  /// the header implies — which is the property a relocated message start would break.
  @Test
  void aMatchingSignerCountStillSigns() {
    final var feePayer = nextSigner();
    final var authority = nextSigner();
    final var signers = List.of(feePayer, authority);
    final byte[] data = twoSignerTx(feePayer, authority).serialized();

    assertDoesNotThrow(() -> Transaction.sign(signers, data));

    assertEquals(2, data[0], "the count byte is unchanged, not rewritten");
    final int msgOffset = 1 + (2 * Transaction.SIGNATURE_LENGTH);
    final int msgLen = data.length - msgOffset;
    for (int i = 0; i < signers.size(); ++i) {
      final int from = 1 + (i * Transaction.SIGNATURE_LENGTH);
      assertTrue(
          signers.get(i).publicKey().verifySignature(
              data, msgOffset, msgLen,
              Arrays.copyOfRange(data, from, from + Transaction.SIGNATURE_LENGTH)
          ),
          "signature " + i + " does not verify over the header's message span"
      );
    }
  }

  private static Transaction singleSignerTx(final Signer feePayer) {
    final var ix = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createWrite(PublicKey.createPubKey(new byte[PublicKey.PUBLIC_KEY_LENGTH]))),
        new byte[]{1}
    );
    final var tx = Transaction.createTx(feePayer.publicKey(), ix);
    tx.setRecentBlockHash(new byte[Transaction.BLOCK_HASH_LENGTH]);
    return tx;
  }

  /// A buffer assembled without its count byte is signable once it declares one, which is how a
  /// caller keeps the freedom the overwrite used to provide.
  @Test
  void aHandBuiltBufferSignsOnceItDeclaresItsCount() {
    final var signer = nextSigner();
    final byte[] out = singleSignerTx(signer).serialized().clone();
    final int msgOffset = 1 + Transaction.SIGNATURE_LENGTH;
    out[0] = 0; // as though assembled without setting the prefix

    assertEquals(
        "Expected 0 signers, only passed 1.",
        assertThrows(IllegalArgumentException.class, () -> Transaction.sign(signer, out)).getMessage()
    );

    out[0] = 1;
    assertDoesNotThrow(() -> Transaction.sign(signer, out));
    assertTrue(signer.publicKey().verifySignature(
        out, msgOffset, out.length - msgOffset,
        Arrays.copyOfRange(out, 1, msgOffset)
    ));
  }

  /// The prefix positions the message; the header states the same number independently. A payload
  /// whose two copies disagree cannot be signed coherently under either reading, so trusting the
  /// prefix alone would have relocated the defect rather than closed it.
  @Test
  void aPrefixContradictingTheMessageHeaderIsRefused() {
    final var feePayer = nextSigner();
    final byte[] data = singleSignerTx(feePayer).serialized().clone();
    final int msgOffset = 1 + Transaction.SIGNATURE_LENGTH;
    assertEquals(1, data[0] & 0xFF);
    assertEquals(1, data[msgOffset] & 0xFF, "legacy: the header's count is the first message byte");

    // Forge only the header, leaving the prefix — and therefore the caller's count — agreeing.
    data[msgOffset] = 2;
    final byte[] before = data.clone();
    assertEquals(
        "Serialized signature count 1 does not match the message header's required signature count 2.",
        assertThrows(IllegalArgumentException.class, () -> Transaction.sign(feePayer, data)).getMessage()
    );
    assertArrayEquals(before, data, "refused before any write");
  }

  /// The prefix also decides how far into the payload the header is looked for, so a count larger
  /// than the payload can hold must be refused rather than read out of bounds.
  @Test
  void aPrefixLargerThanThePayloadIsRefused() {
    final byte[] tooShort = new byte[8];
    tooShort[0] = (byte) 4; // implies 1 + 256 bytes before the message even starts

    assertEquals(
        "A 8 byte payload cannot hold 4 signatures and a message.",
        assertThrows(
            IllegalArgumentException.class,
            () -> Transaction.sign(java.util.Collections.nCopies(4, nextSigner()), tooShort)
        ).getMessage()
    );
  }

  /// Both length guards are `>=`, and both sit immediately before a read at that exact index, so a
  /// boundary slipped to `>` does not merely admit one malformed payload — it reads off the end.
  @Test
  void aPayloadEndingExactlyWhereTheMessageWouldStartIsRefused() {
    // 1 prefix byte + one signature slot, and nothing after it: the message would begin at 65.
    final byte[] noMessage = new byte[1 + Transaction.SIGNATURE_LENGTH];
    noMessage[0] = 1;

    assertEquals(
        "A 65 byte payload cannot hold 1 signatures and a message.",
        assertThrows(
            IllegalArgumentException.class, () -> Transaction.sign(nextSigner(), noMessage)
        ).getMessage()
    );
  }

  @Test
  void aVersionedPayloadEndingExactlyWhereItsHeaderWouldStartIsRefused() {
    // One byte of message, and it is a version byte — so the count would have to follow it, at 66.
    final byte[] versionOnly = new byte[2 + Transaction.SIGNATURE_LENGTH];
    versionOnly[0] = 1;
    versionOnly[1 + Transaction.SIGNATURE_LENGTH] = (byte) 0x80;

    assertEquals(
        "A 66 byte payload cannot hold 1 signatures and a message header.",
        assertThrows(
            IllegalArgumentException.class, () -> Transaction.sign(nextSigner(), versionOnly)
        ).getMessage()
    );
  }

  /// A versioned payload keeps its count *after* the version byte, so the two formats must be told
  /// apart rather than both read at the message offset. The version byte here is `0x80`, which as a
  /// count would read as 128 — nothing like the 1 this payload requires — so reading the wrong one
  /// cannot pass by coincidence.
  @Test
  void aVersionedPayloadReadsItsCountAfterTheVersionByte() {
    final var signer = nextSigner();
    final byte[] out = new byte[1 + Transaction.SIGNATURE_LENGTH + 96];
    final int msgOffset = 1 + Transaction.SIGNATURE_LENGTH;
    out[0] = 1;
    out[msgOffset] = (byte) 0x80;      // versioned, version 0
    out[msgOffset + 1] = 1;            // num_required_signatures
    out[msgOffset + 2] = 0;            // num_readonly_signed
    out[msgOffset + 3] = 0;            // num_readonly_unsigned

    assertDoesNotThrow(() -> Transaction.sign(signer, out));
    assertTrue(signer.publicKey().verifySignature(
        out, msgOffset, out.length - msgOffset,
        Arrays.copyOfRange(out, 1, msgOffset)
    ));
  }

  /// A legacy payload whose message is the last byte of the buffer. Nothing follows the count, so
  /// treating the payload as versioned would look one byte past the end and refuse it — which is
  /// what makes the version test observable here rather than merely computed.
  @Test
  void aLegacyPayloadEndingRightAfterItsCountStillSigns() {
    final var signer = nextSigner();
    final byte[] out = new byte[2 + Transaction.SIGNATURE_LENGTH];
    final int msgOffset = 1 + Transaction.SIGNATURE_LENGTH;
    out[0] = 1;
    out[msgOffset] = 1; // legacy: the count is the first message byte, and the only one

    assertDoesNotThrow(() -> Transaction.sign(signer, out));
    assertTrue(signer.publicKey().verifySignature(
        out, msgOffset, 1, Arrays.copyOfRange(out, 1, msgOffset)
    ));
  }

  /// The mirror of the case above: a versioned payload whose header disagrees with its prefix must
  /// still be caught, which is only possible if the count is read past the version byte.
  @Test
  void aVersionedHeaderContradictingItsPrefixIsRefused() {
    final byte[] out = new byte[1 + Transaction.SIGNATURE_LENGTH + 96];
    final int msgOffset = 1 + Transaction.SIGNATURE_LENGTH;
    out[0] = 1;
    out[msgOffset] = (byte) 0x80;
    out[msgOffset + 1] = 3; // the header wants three signatures, the prefix reserved one slot

    assertEquals(
        "Serialized signature count 1 does not match the message header's required signature count 3.",
        assertThrows(
            IllegalArgumentException.class, () -> Transaction.sign(nextSigner(), out)
        ).getMessage()
    );
  }
}
