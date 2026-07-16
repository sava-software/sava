package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// Bulk and indexed signing. `testTxSigning` covers only the single-signer
/// [Transaction#sign(Signer)]; mutation testing showed the multi-signer overloads — the
/// ones a multisig or fee-payer-plus-authority transaction goes through — had no coverage,
/// so a signature written to the wrong slot, or a dropped signer, was invisible.
final class TransactionSigningTests {

  private record Fixture(Transaction tx, Signer feePayer, Signer authority) {

    byte[] signature(final int index) {
      final byte[] data = tx.serialized();
      final int from = 1 + (index * Transaction.SIGNATURE_LENGTH);
      return Arrays.copyOfRange(data, from, from + Transaction.SIGNATURE_LENGTH);
    }

    void assertSignedBy(final Signer signer, final int index) {
      final byte[] data = tx.serialized();
      final int messageOffset = ((TransactionRecord) tx).messageOffset();
      assertTrue(
          signer.publicKey().verifySignature(data, messageOffset, data.length - messageOffset, signature(index)),
          "signature " + index + " does not verify for " + signer.publicKey()
      );
    }
  }

  /// A two-signer transaction: the fee payer plus a writable signer authority.
  private static Fixture twoSignerTx() {
    final var feePayer = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes());
    final var authority = Signer.createFromKeyPair(Signer.generatePrivateKeyPairBytes());
    final var ix = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createWritableSigner(authority.publicKey())),
        new byte[]{1, 2, 3, 4}
    );
    final var tx = Transaction.createTx(feePayer.publicKey(), ix);
    assertEquals(2, tx.numSigners());
    return new Fixture(tx, feePayer, authority);
  }

  @Test
  void signCollectionMatchesIndividualSigning() {
    // signing one-by-one is the covered path; the bulk overload must reproduce it exactly.
    // A List binds to the SequencedCollection overload, so this uses a Set to reach the
    // plain Collection overload, which resolves each signer's slot by public key.
    final var individually = twoSignerTx();
    individually.tx().sign(individually.feePayer());
    individually.tx().sign(individually.authority());
    final byte[] expected = individually.tx().serialized().clone();

    final var bulk = new Fixture(
        rebuild(individually), individually.feePayer(), individually.authority()
    );
    final Collection<Signer> unordered = Set.of(bulk.feePayer(), bulk.authority());
    bulk.tx().sign(unordered);

    assertArrayEquals(expected, bulk.tx().serialized(), "bulk signing must equal individual signing");
    bulk.assertSignedBy(bulk.feePayer(), 0);
    bulk.assertSignedBy(bulk.authority(), 1);
  }

  @Test
  void signSequencedCollectionMatchesIndividualSigning() {
    final var individually = twoSignerTx();
    individually.tx().sign(individually.feePayer());
    individually.tx().sign(individually.authority());
    final byte[] expected = individually.tx().serialized().clone();

    final var bulk = new Fixture(rebuild(individually), individually.feePayer(), individually.authority());
    // the SequencedCollection overload signs positionally, so order is the contract
    bulk.tx().sign((java.util.SequencedCollection<Signer>) List.of(bulk.feePayer(), bulk.authority()));

    assertArrayEquals(expected, bulk.tx().serialized(), "sequenced bulk signing must equal individual signing");
  }

  @Test
  void signCollectionRejectsWrongSignerCount() {
    final var fixture = twoSignerTx();
    assertThrows(IllegalArgumentException.class, () -> fixture.tx().sign(List.of(fixture.feePayer())));
    assertThrows(IllegalArgumentException.class, () -> fixture.tx().sign(Set.<Signer>of()));
    assertThrows(IllegalArgumentException.class, () -> fixture.tx().sign(
        (java.util.SequencedCollection<Signer>) List.of(fixture.feePayer())
    ));
    // a rejected call must not have written a partial signature
    assertArrayEquals(new byte[Transaction.SIGNATURE_LENGTH], fixture.signature(0));
    assertArrayEquals(new byte[Transaction.SIGNATURE_LENGTH], fixture.signature(1));
  }

  @Test
  void signIndexWritesTheAddressedSlot() {
    final var fixture = twoSignerTx();

    fixture.tx().sign(1, fixture.authority());
    fixture.assertSignedBy(fixture.authority(), 1);
    // slot 0 is untouched by an indexed write to slot 1
    assertArrayEquals(new byte[Transaction.SIGNATURE_LENGTH], fixture.signature(0));

    fixture.tx().sign(0, fixture.feePayer());
    fixture.assertSignedBy(fixture.feePayer(), 0);
    fixture.assertSignedBy(fixture.authority(), 1);

    // the slots are distinct: each signer's signature must not verify at the other's index
    assertFalse(fixture.feePayer().publicKey().verifySignature(
        fixture.tx().serialized(),
        ((TransactionRecord) fixture.tx()).messageOffset(),
        fixture.tx().serialized().length - ((TransactionRecord) fixture.tx()).messageOffset(),
        fixture.signature(1)
    ), "authority's slot must not verify as the fee payer");
  }

  /// Rebuilds an identical unsigned transaction for the same signers.
  private static Transaction rebuild(final Fixture fixture) {
    final var ix = Instruction.createInstruction(
        SolanaAccounts.MAIN_NET.systemProgram(),
        List.of(AccountMeta.createWritableSigner(fixture.authority().publicKey())),
        new byte[]{1, 2, 3, 4}
    );
    return Transaction.createTx(fixture.feePayer().publicKey(), ix);
  }
}
