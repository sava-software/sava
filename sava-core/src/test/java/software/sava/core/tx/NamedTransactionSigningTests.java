package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.Base58;
import software.sava.core.encoding.ByteUtil;

import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.tx.Transaction.SIGNATURE_LENGTH;

/// The explicit signing names select slot order independently of a collection's Java type.
/// Fixed signers verify each signature against its intended message span and slot; no network
/// or live accounts are involved.
final class NamedTransactionSigningTests {

  private static byte[] bytes(final int length, final int seed) {
    final byte[] bytes = new byte[length];
    Arrays.fill(bytes, (byte) seed);
    return bytes;
  }

  private static Signer signer(final int seed) {
    return Signer.createFromPrivateKey(bytes(Signer.KEY_LENGTH, seed));
  }

  private static Transaction transaction(final int version, final Signer feePayer, final Signer authority) {
    final var ix = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createWritableSigner(authority.publicKey())),
        new byte[]{1, 2, 3, 4}
    );
    final Transaction tx;
    if (version == 1) {
      tx = TxBuilder.createBuilder().feePayer(feePayer.publicKey()).addInstruction(ix).createTransaction();
    } else if (version == 0) {
      final byte[] tableData = new byte[AddressLookupTable.LOOKUP_TABLE_META_SIZE];
      ByteUtil.putInt32LE(tableData, AddressLookupTable.DISCRIMINATOR_OFFSET, 1);
      ByteUtil.putInt64LE(tableData, AddressLookupTable.DEACTIVATION_SLOT_OFFSET, -1L);
      final var table = AddressLookupTable.read(PublicKey.createPubKey(bytes(32, 33)), tableData);
      tx = Transaction.createTx(feePayer.publicKey(), List.of(ix), table);
    } else {
      tx = Transaction.createTx(feePayer.publicKey(), ix);
    }
    tx.setRecentBlockHash(bytes(Transaction.BLOCK_HASH_LENGTH, 7));
    assertEquals(version, tx.version());
    assertEquals(2, tx.numSigners());
    return tx;
  }

  private static int signatureStart(final Transaction tx) {
    // These fixtures have exactly two signers: legacy/v0 prepend their slots, v1 appends them.
    return tx.version() == 1 ? tx.size() - 2 * SIGNATURE_LENGTH : 1;
  }

  private static byte[] message(final Transaction tx) {
    return tx.version() == 1
        ? Arrays.copyOfRange(tx.serialized(), 0, signatureStart(tx))
        : Arrays.copyOfRange(tx.serialized(), 1 + 2 * SIGNATURE_LENGTH, tx.size());
  }

  private static void assertSignedBy(final Transaction tx, final List<Signer> slotOrder) {
    final byte[] message = message(tx);
    for (int slot = 0; slot < slotOrder.size(); ++slot) {
      final int from = signatureStart(tx) + slot * SIGNATURE_LENGTH;
      final byte[] signature = Arrays.copyOfRange(tx.serialized(), from, from + SIGNATURE_LENGTH);
      assertTrue(slotOrder.get(slot).publicKey().verifySignature(message, signature), "signature slot " + slot);
    }
  }

  @Test
  void aListSelectsPositionalOrByKeySigningByName() {
    for (final int version : new int[]{-128, 0, 1}) {
      final var feePayer = signer(11);
      final var authority = signer(22);
      final var reversed = List.of(authority, feePayer);
      final var tx = transaction(version, feePayer, authority);
      final byte[] unsignedMessage = message(tx);

      tx.signInOrder(reversed);
      assertSignedBy(tx, reversed);
      assertArrayEquals(unsignedMessage, message(tx));

      tx.signByKey(reversed);
      assertSignedBy(tx, List.of(feePayer, authority));
      assertArrayEquals(unsignedMessage, message(tx));
    }
  }

  @Test
  void positionalSigningAcceptsKeysOutsideTheRequiredSignerSet() throws GeneralSecurityException {
    final var feePayer = signer(11);
    final var authority = signer(22);
    final var foreignSigners = List.of(signer(33), signer(44));
    for (final int version : new int[]{-128, 0, 1}) {
      for (int variant = 0; variant < 2; ++variant) {
        final var tx = transaction(version, feePayer, authority);
        final byte[] unsignedMessage = message(tx);
        final byte[] expected = tx.serialized().clone();
        // Positional signing leaves key assignment to the caller. These keys deliberately
        // cannot authorize this transaction; their expected bytes come directly from JDK Ed25519.
        for (int slot = 0; slot < foreignSigners.size(); ++slot) {
          final var signature = Signature.getInstance("Ed25519");
          signature.initSign(foreignSigners.get(slot).privateKey());
          signature.update(unsignedMessage);
          final byte[] expectedSignature = signature.sign();
          assertEquals(SIGNATURE_LENGTH, expectedSignature.length);
          System.arraycopy(expectedSignature, 0, expected,
              signatureStart(tx) + slot * SIGNATURE_LENGTH, SIGNATURE_LENGTH);
        }

        if (variant == 0) {
          tx.signInOrder(foreignSigners);
        } else {
          Transaction.signInOrder(foreignSigners, tx.serialized());
        }

        assertArrayEquals(expected, tx.serialized(), "version " + version + ", signing variant " + variant);
      }
    }
  }

  @Test
  void namedSigningRejectsBadAssignmentsWithoutTouchingExistingSignatures() {
    for (final int version : new int[]{-128, 0, 1}) {
      final var feePayer = signer(11);
      final var authority = signer(22);
      final var unknown = signer(33);
      final var tx = transaction(version, feePayer, authority);
      tx.signByKey(List.of(authority, feePayer));
      final byte[] before = tx.serialized().clone();

      for (final var wrongCount : List.of(List.of(feePayer), List.of(feePayer, authority, unknown))) {
        assertThrows(IllegalArgumentException.class, () -> tx.signInOrder(wrongCount));
        assertArrayEquals(before, tx.serialized());
        assertThrows(IllegalArgumentException.class, () -> tx.signByKey(wrongCount));
        assertArrayEquals(before, tx.serialized());
        assertThrows(IllegalArgumentException.class, () -> Transaction.signInOrder(wrongCount, tx.serialized()));
        assertArrayEquals(before, tx.serialized());
        assertThrows(IllegalArgumentException.class,
            () -> Transaction.signInOrderAndBase64Encode(wrongCount, tx.serialized()));
        assertArrayEquals(before, tx.serialized());
      }
      for (final var badAssignment : List.of(List.of(feePayer, feePayer), List.of(feePayer, unknown))) {
        assertThrows(IllegalArgumentException.class, () -> tx.signByKey(badAssignment));
        assertArrayEquals(before, tx.serialized());
      }
    }
  }

  @Test
  void positionalConveniencesSignTheUpdatedBlockhashAndEncodeTheWholePayload() {
    for (final int version : new int[]{-128, 0, 1}) {
      final var feePayer = signer(11);
      final var authority = signer(22);
      final var reversed = List.of(authority, feePayer);
      for (int variant = 0; variant < 5; ++variant) {
        final var tx = transaction(version, feePayer, authority);
        final byte[] hash = bytes(Transaction.BLOCK_HASH_LENGTH, variant == 0 ? 7 : 40 + variant);
        final String encoded;
        switch (variant) {
          case 0 -> encoded = tx.signInOrderAndBase64Encode(reversed);
          case 1 -> {
            tx.signInOrder(hash, reversed);
            encoded = null;
          }
          case 2 -> {
            tx.signInOrder(Base58.encode(hash), reversed);
            encoded = null;
          }
          case 3 -> encoded = tx.signInOrderAndBase64Encode(hash, reversed);
          case 4 -> encoded = tx.signInOrderAndBase64Encode(Base58.encode(hash), reversed);
          default -> throw new AssertionError(variant);
        }
        assertArrayEquals(hash, TransactionSkeleton.deserializeSkeleton(tx.serialized()).blockHash());
        assertSignedBy(tx, reversed);
        if (variant == 0 || variant >= 3) {
          assertArrayEquals(tx.serialized(), Base64.getDecoder().decode(encoded));
        }
      }
    }
  }

  @Test
  void byKeyConveniencesSignTheUpdatedBlockhashAndEncodeTheWholePayload() {
    for (final int version : new int[]{-128, 0, 1}) {
      final var feePayer = signer(11);
      final var authority = signer(22);
      final var reversed = List.of(authority, feePayer);
      for (int variant = 0; variant < 5; ++variant) {
        final var tx = transaction(version, feePayer, authority);
        final byte[] hash = bytes(Transaction.BLOCK_HASH_LENGTH, variant == 0 ? 7 : 50 + variant);
        final String encoded;
        switch (variant) {
          case 0 -> encoded = tx.signByKeyAndBase64Encode(reversed);
          case 1 -> {
            tx.signByKey(hash, reversed);
            encoded = null;
          }
          case 2 -> {
            tx.signByKey(Base58.encode(hash), reversed);
            encoded = null;
          }
          case 3 -> encoded = tx.signByKeyAndBase64Encode(hash, reversed);
          case 4 -> encoded = tx.signByKeyAndBase64Encode(Base58.encode(hash), reversed);
          default -> throw new AssertionError(variant);
        }
        assertArrayEquals(hash, TransactionSkeleton.deserializeSkeleton(tx.serialized()).blockHash());
        assertSignedBy(tx, List.of(feePayer, authority));
        if (variant == 0 || variant >= 3) {
          assertArrayEquals(tx.serialized(), Base64.getDecoder().decode(encoded));
        }
      }
    }
  }

  @Test
  void staticNamedSigningUsesTheSerializedLayoutAndSuppliedOrder() {
    for (final int version : new int[]{-128, 0, 1}) {
      final var feePayer = signer(11);
      final var authority = signer(22);
      final var reversed = List.of(authority, feePayer);
      final var tx = transaction(version, feePayer, authority);
      final byte[] unsigned = tx.serialized().clone();
      final byte[] unsignedMessage = message(tx);

      Transaction.signInOrder(reversed, tx.serialized());
      assertSignedBy(tx, reversed);
      assertArrayEquals(unsignedMessage, message(tx));

      System.arraycopy(unsigned, 0, tx.serialized(), 0, unsigned.length);
      final String encoded = Transaction.signInOrderAndBase64Encode(reversed, tx.serialized());
      assertArrayEquals(tx.serialized(), Base64.getDecoder().decode(encoded));
      assertSignedBy(tx, reversed);
      assertArrayEquals(unsignedMessage, message(tx));
    }
  }

  @Test
  void explicitOffsetsSignOnlyTheSuppliedSpanAndSlots() {
    final var signers = List.of(signer(11), signer(22));
    final int messageOffset = 5;
    final int messageLength = 9;
    final int signaturesOffset = 31;
    final int signaturesEnd = signaturesOffset + 2 * SIGNATURE_LENGTH;
    final byte[] out = bytes(signaturesEnd + 7, 83);
    for (int i = 0; i < messageLength; ++i) {
      out[messageOffset + i] = (byte) (i + 1);
    }
    final byte[] before = out.clone();
    final byte[] message = Arrays.copyOfRange(before, messageOffset, messageOffset + messageLength);

    Transaction.signInOrder(signers, out, messageOffset, messageLength, signaturesOffset);

    for (int slot = 0; slot < signers.size(); ++slot) {
      final int from = signaturesOffset + slot * SIGNATURE_LENGTH;
      assertTrue(signers.get(slot).publicKey().verifySignature(
          message, Arrays.copyOfRange(out, from, from + SIGNATURE_LENGTH)
      ));
    }
    assertArrayEquals(Arrays.copyOfRange(before, 0, signaturesOffset), Arrays.copyOfRange(out, 0, signaturesOffset));
    assertArrayEquals(Arrays.copyOfRange(before, signaturesEnd, before.length),
        Arrays.copyOfRange(out, signaturesEnd, out.length));
  }
}
