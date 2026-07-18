package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.Base58;
import software.sava.core.programs.Discriminator;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// The static `byte[]`-level helpers — `Transaction.sign`, `signAndBase64Encode`,
/// `setBlockHash`, `getId`/`getBase58Id` over an already-serialized payload — and the
/// default sign-with-block-hash overloads had no mutation coverage:
/// `TransactionSigningTests` exercises instance signing only. Signature verification is
/// the load-bearing assertion throughout: instance signing routes through the same
/// statics, so byte equality between the two paths alone cannot catch a dropped or
/// misplaced write.
final class TransactionByteHelpersTests {

  private static final byte[] HASH_A = hash((byte) 0xA1);
  private static final byte[] HASH_B = hash((byte) 0xB2);

  private static byte[] hash(final byte fill) {
    final byte[] hash = new byte[Transaction.BLOCK_HASH_LENGTH];
    Arrays.fill(hash, fill);
    return hash;
  }

  private static byte[] key(final int fill) {
    final byte[] key = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(key, (byte) fill);
    return key;
  }

  private record Fixture(List<Signer> signers, List<Instruction> instructions) {

    Transaction newTx() {
      return Transaction.createTx(signers.getFirst().publicKey(), instructions);
    }

    Transaction newSignedTx(final byte[] blockHash) {
      final var tx = newTx();
      tx.setRecentBlockHash(blockHash);
      if (signers.size() == 1) {
        tx.sign(signers.getFirst());
      } else {
        tx.sign(signers);
      }
      return tx;
    }
  }

  private static Fixture singleSigner() {
    final var feePayer = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes());
    final var ix = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createWrite(PublicKey.createPubKey(key(7)))),
        new byte[]{1, 2, 3, 4}
    );
    return new Fixture(List.of(feePayer), List.of(ix));
  }

  /// Two signers so multi-signature offset math (`numSigners * SIGNATURE_LENGTH`) is
  /// observable; the sequenced signer order matches the account order: fee payer first.
  private static Fixture twoSigner() {
    final var feePayer = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes());
    final var authority = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes());
    final var ix = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createWritableSigner(authority.publicKey())),
        new byte[]{1, 2, 3, 4}
    );
    return new Fixture(List.of(feePayer, authority), List.of(ix));
  }

  private static void assertSignedWithHash(final Transaction tx, final byte[] expectedHash, final List<Signer> signers) {
    assertSignedWithHash(tx.serialized(), expectedHash, signers);
  }

  private static void assertSignedWithHash(final byte[] data, final byte[] expectedHash, final List<Signer> signers) {
    assertEquals(signers.size(), data[0], "signature count byte");
    assertArrayEquals(expectedHash, TransactionSkeleton.deserializeSkeleton(data).blockHash());
    final int msgOffset = 1 + signers.size() * Transaction.SIGNATURE_LENGTH;
    final int msgLen = data.length - msgOffset;
    for (int i = 0; i < signers.size(); ++i) {
      final int from = 1 + i * Transaction.SIGNATURE_LENGTH;
      final byte[] signature = Arrays.copyOfRange(data, from, from + Transaction.SIGNATURE_LENGTH);
      assertTrue(
          signers.get(i).publicKey().verifySignature(data, msgOffset, msgLen, signature),
          "signature " + i + " does not verify"
      );
    }
  }

  @Test
  void transactionIdRequiresSignature() {
    final var fixture = singleSigner();
    final var tx = fixture.newTx();
    tx.setRecentBlockHash(HASH_A);
    final byte[] data = tx.serialized();
    // a freshly built payload already carries its required-signature count; the
    // unsigned guard fires only on a zero count byte
    final byte[] zeroCount = data.clone();
    zeroCount[0] = 0;
    assertThrows(IllegalStateException.class, () -> Transaction.getBase58Id(zeroCount));
    assertThrows(IllegalStateException.class, () -> Transaction.getId(zeroCount));

    tx.sign(fixture.signers().getFirst());
    final byte[] id = Transaction.getId(data);
    assertArrayEquals(Arrays.copyOfRange(data, 1, 1 + Transaction.SIGNATURE_LENGTH), id);
    assertEquals(Base58.encode(id), Transaction.getBase58Id(data));
  }

  @Test
  void staticSingleSignerSigning() {
    final var fixture = singleSigner();
    final var signer = fixture.signers().getFirst();
    final var instanceSigned = fixture.newSignedTx(HASH_A);

    final var tx = fixture.newTx();
    tx.setRecentBlockHash(HASH_A);
    final byte[] data = tx.serialized();
    Transaction.sign(signer, data);
    assertArrayEquals(instanceSigned.serialized(), data);
    assertSignedWithHash(data, HASH_A, fixture.signers());

    final var encodeTx = fixture.newTx();
    encodeTx.setRecentBlockHash(HASH_A);
    final byte[] encodeData = encodeTx.serialized();
    final String base64 = Transaction.signAndBase64Encode(signer, encodeData);
    assertArrayEquals(encodeData, Base64.getDecoder().decode(base64));
    assertSignedWithHash(encodeData, HASH_A, fixture.signers());
  }

  @Test
  void staticSequencedSigning() {
    final var fixture = twoSigner();
    final var instanceSigned = fixture.newSignedTx(HASH_A);

    final var tx = fixture.newTx();
    tx.setRecentBlockHash(HASH_A);
    final byte[] data = tx.serialized();
    Transaction.sign(fixture.signers(), data);
    assertArrayEquals(instanceSigned.serialized(), data);
    assertSignedWithHash(data, HASH_A, fixture.signers());

    final var encodeTx = fixture.newTx();
    encodeTx.setRecentBlockHash(HASH_A);
    final byte[] encodeData = encodeTx.serialized();
    final String base64 = Transaction.signAndBase64Encode(fixture.signers(), encodeData);
    assertArrayEquals(encodeData, Base64.getDecoder().decode(base64));
    assertSignedWithHash(encodeData, HASH_A, fixture.signers());
  }

  @Test
  void staticSetBlockHashOnLegacyMultiSignerPayload() {
    final var fixture = twoSigner();
    final var tx = fixture.newSignedTx(HASH_A);
    final byte[] data = tx.serialized();

    // independent oracle: an identical twin re-hashed through the instance path, which
    // uses the construction-time index instead of re-deriving offsets from the wire
    final var twin = fixture.newSignedTx(HASH_A);
    twin.setRecentBlockHash(HASH_B);

    Transaction.setBlockHash(data, HASH_B);
    assertArrayEquals(twin.serialized(), data);
    assertArrayEquals(HASH_B, TransactionSkeleton.deserializeSkeleton(data).blockHash());
  }

  @Test
  void staticSetBlockHashOnVersionedPayload() {
    final var fixture = singleSigner();
    final var tx = fixture.newSignedTx(HASH_A);
    assertTrue(TransactionSkeleton.deserializeSkeleton(tx.serialized()).isLegacy());

    // rewrite as a v0 message with an empty lookup-table section, the wire form that
    // pins the versioned (4-byte) branch of the static header walk
    final byte[] legacy = tx.serialized();
    final int messageOffset = ((TransactionRecord) tx).messageOffset();
    final byte[] versioned = new byte[legacy.length + 2];
    System.arraycopy(legacy, 0, versioned, 0, messageOffset);
    versioned[messageOffset] = (byte) 0x80;
    System.arraycopy(legacy, messageOffset, versioned, messageOffset + 1, legacy.length - messageOffset);
    versioned[versioned.length - 1] = 0;

    final var skeleton = TransactionSkeleton.deserializeSkeleton(versioned);
    assertTrue(skeleton.isVersioned());
    final byte[] expected = versioned.clone();
    System.arraycopy(HASH_B, 0, expected, skeleton.recentBlockHashIndex(), Transaction.BLOCK_HASH_LENGTH);

    Transaction.setBlockHash(versioned, HASH_B);
    assertArrayEquals(expected, versioned);
    assertArrayEquals(HASH_B, TransactionSkeleton.deserializeSkeleton(versioned).blockHash());
  }

  @Test
  void singleSignerBlockHashOverloads() {
    final var fixture = singleSigner();
    final var signer = fixture.signers().getFirst();
    final var hashBase58 = Base58.encode(HASH_A);

    final var byBytes = fixture.newTx();
    byBytes.sign(HASH_A, signer);
    assertSignedWithHash(byBytes, HASH_A, fixture.signers());

    final var byString = fixture.newTx();
    byString.sign(hashBase58, signer);
    assertSignedWithHash(byString, HASH_A, fixture.signers());

    final var encodeByBytes = fixture.newTx();
    final String base64ByBytes = encodeByBytes.signAndBase64Encode(HASH_A, signer);
    assertSignedWithHash(encodeByBytes, HASH_A, fixture.signers());
    assertArrayEquals(encodeByBytes.serialized(), Base64.getDecoder().decode(base64ByBytes));

    final var encodeByString = fixture.newTx();
    final String base64ByString = encodeByString.signAndBase64Encode(hashBase58, signer);
    assertSignedWithHash(encodeByString, HASH_A, fixture.signers());
    assertArrayEquals(encodeByString.serialized(), Base64.getDecoder().decode(base64ByString));

    final var preHashed = fixture.newTx();
    preHashed.setRecentBlockHash(HASH_A);
    final String base64PreHashed = preHashed.signAndBase64Encode(signer);
    assertSignedWithHash(preHashed, HASH_A, fixture.signers());
    assertArrayEquals(preHashed.serialized(), Base64.getDecoder().decode(base64PreHashed));
  }

  @Test
  void sequencedBlockHashOverloads() {
    final var fixture = twoSigner();
    final var signers = fixture.signers();
    final var hashBase58 = Base58.encode(HASH_A);

    final var byBytes = fixture.newTx();
    byBytes.sign(HASH_A, signers);
    assertSignedWithHash(byBytes, HASH_A, signers);

    final var byString = fixture.newTx();
    byString.sign(hashBase58, signers);
    assertSignedWithHash(byString, HASH_A, signers);

    final var encodeByBytes = fixture.newTx();
    final String base64ByBytes = encodeByBytes.signAndBase64Encode(HASH_A, signers);
    assertSignedWithHash(encodeByBytes, HASH_A, signers);
    assertArrayEquals(encodeByBytes.serialized(), Base64.getDecoder().decode(base64ByBytes));

    final var encodeByString = fixture.newTx();
    final String base64ByString = encodeByString.signAndBase64Encode(hashBase58, signers);
    assertSignedWithHash(encodeByString, HASH_A, signers);
    assertArrayEquals(encodeByString.serialized(), Base64.getDecoder().decode(base64ByString));

    final var preHashed = fixture.newTx();
    preHashed.setRecentBlockHash(HASH_A);
    final String base64PreHashed = preHashed.signAndBase64Encode(signers);
    assertSignedWithHash(preHashed, HASH_A, signers);
    assertArrayEquals(preHashed.serialized(), Base64.getDecoder().decode(base64PreHashed));
  }

  private static Transaction txOfSize(final int targetSize) {
    final var feePayer = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes());
    final var program = SolanaAccounts.MAIN_NET.systemProgram();
    final var accounts = List.of(AccountMeta.createWrite(PublicKey.createPubKey(key(7))));
    // instruction data in the two-byte compact-u16 range, so size is affine in length
    final int probeLength = 1000;
    final var probe = Transaction.createTx(
        feePayer.publicKey(),
        Instruction.createInstruction(program, accounts, new byte[probeLength])
    );
    final var tx = Transaction.createTx(
        feePayer.publicKey(),
        Instruction.createInstruction(program, accounts, new byte[probeLength + targetSize - probe.size()])
    );
    assertEquals(targetSize, tx.size());
    return tx;
  }

  @Test
  void exceedsSizeLimitBoundary() {
    assertFalse(txOfSize(1232).exceedsSizeLimit(), "1232 bytes is within the limit");
    assertTrue(txOfSize(1233).exceedsSizeLimit(), "1233 bytes exceeds the limit");
  }

  @Test
  void discriminatorInstructionCarriesDiscriminatorData() {
    final var discriminator = Discriminator.toDiscriminator(9, 8, 7, 6, 5, 4, 3, 2);
    final var ix = Instruction.createInstruction(
        AccountMeta.createInvoked(SolanaAccounts.MAIN_NET.systemProgram()),
        List.of(AccountMeta.createWrite(PublicKey.createPubKey(key(7)))),
        discriminator
    );
    assertEquals(discriminator.data().length, ix.len());
    assertArrayEquals(discriminator.data(), ix.data());
  }
}
