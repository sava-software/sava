package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.tx.V1TransactionSkeleton.V1_ACCOUNTS_OFFSET;

/// Pins the two SIMD-0385 LegacyHeader rules that `V1TransactionSkeleton#deserialize` enforces.
///
/// Both rules partition the Addresses array — the first splits the signers into writable and
/// read-only, the second separates the signers and read-only non-signers from the writable
/// non-signers. A message violating either does not parse into an invalid-but-faithful view; it
/// parses into a plausible-looking wrong one, where accounts silently carry privileges the wire
/// bytes never granted. That is why these are rejected outright rather than merely reported.
///
/// The SIMD's other constraints — at most 12 signatures, 64 addresses and 64 instructions — are
/// population limits which leave the parse meaningful, so they deliberately stay permissive here
/// and are reported by `exceedsSignatureLimit`/`exceedsAccountLimit`/`exceedsInstructionLimit`
/// instead, matching this library's documented "construct and analyse invalid transactions, leave
/// submission validation to the RPC" stance.
final class V1HeaderSanitizationTests {

  // Byte offsets within a serialized v1 message, from the fixed-width prefix the format guarantees.
  private static final int NUM_REQUIRED_SIGNATURES = 1;
  private static final int NUM_READONLY_SIGNED = 2;
  private static final int NUM_READONLY_UNSIGNED = 3;
  private static final int NUM_ADDRESSES = V1_ACCOUNTS_OFFSET - 1;

  private static Signer signer(final int seed) {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    Arrays.fill(privateKey, (byte) seed);
    return Signer.createFromPrivateKey(privateKey);
  }

  /// A two-signer v1 transaction: a writable fee payer, one writable signer, and the system program
  /// as a read-only non-signer.
  private static byte[] validV1Transaction() {
    final var feePayer = signer(31);
    final var signerB = signer(32);
    final var tx = TxBuilder.createBuilder()
        .feePayer(feePayer.publicKey())
        .addInstruction(Instruction.createInstruction(
            SolanaAccounts.MAIN_NET.systemProgram(),
            List.of(AccountMeta.createWritableSigner(signerB.publicKey())),
            new byte[]{1, 2, 3, 4}
        ))
        .createTransaction();
    final byte[] blockHash = new byte[Transaction.BLOCK_HASH_LENGTH];
    Arrays.fill(blockHash, (byte) 7);
    tx.setRecentBlockHash(blockHash);
    return tx.serialized();
  }

  @Test
  void theUnmodifiedFixtureParses() {
    final byte[] data = validV1Transaction();
    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    assertInstanceOf(V1TransactionSkeleton.class, skeleton);
    assertEquals(2, skeleton.numSignatures());
    assertEquals(0, skeleton.numReadonlySignedAccounts());
    assertEquals(1, skeleton.numReadonlyUnsignedAccounts());
    assertEquals(3, skeleton.numIncludedAccounts());
    // Without this control the rejection tests below could pass for the wrong reason.
    assertTrue(skeleton.parseAccounts()[0].feePayer());
  }

  /// `num_readonly_signed_accounts == num_required_signatures` implies a read-only fee payer, which
  /// SIMD-0385 calls a sanitization failure. Pre-guard this parsed happily and still reported the
  /// fee payer as writable, because `parseAccounts` types index 0 as the fee payer unconditionally.
  @Test
  void aReadOnlyFeePayerIsRejected() {
    final byte[] data = validV1Transaction();
    data[NUM_READONLY_SIGNED] = data[NUM_REQUIRED_SIGNATURES];

    assertEquals(
        "A v1 transaction requiring 2 signatures may not load 2 of them as read-only; the fee payer must be writable.",
        assertThrowsExactly(IllegalStateException.class,
            () -> TransactionSkeleton.deserializeSkeleton(data)).getMessage()
    );
  }

  @Test
  void moreReadOnlySignersThanRequiredSignaturesIsRejected() {
    final byte[] data = validV1Transaction();
    data[NUM_READONLY_SIGNED] = (byte) ((data[NUM_REQUIRED_SIGNATURES] & 0xFF) + 1);

    assertEquals(
        "A v1 transaction requiring 2 signatures may not load 3 of them as read-only; the fee payer must be writable.",
        assertThrowsExactly(IllegalStateException.class,
            () -> TransactionSkeleton.deserializeSkeleton(data)).getMessage()
    );
  }

  /// `num_addresses < num_required_signatures + num_readonly_unsigned_accounts` leaves no room for
  /// the writable non-signers the header implies. Pre-guard this parsed without error and silently
  /// retyped the writable non-signer as read-only, because `parseAccounts` derives the boundary as
  /// `numIncludedAccounts - numReadonlyUnsignedAccounts` and simply ran the writable loop zero
  /// times.
  @Test
  void inflatedReadOnlyNonSignerCountIsRejected() {
    final byte[] data = validV1Transaction();
    final int numAddresses = data[NUM_ADDRESSES] & 0xFF;
    final int numRequiredSignatures = data[NUM_REQUIRED_SIGNATURES] & 0xFF;
    // One more read-only non-signer than the address array can hold alongside the signers.
    data[NUM_READONLY_UNSIGNED] = (byte) (numAddresses - numRequiredSignatures + 1);

    assertEquals(
        String.format(
            "A v1 transaction with %d addresses cannot hold %d signers and %d read-only non-signers.",
            numAddresses, numRequiredSignatures, numAddresses - numRequiredSignatures + 1
        ),
        assertThrowsExactly(IllegalStateException.class,
            () -> TransactionSkeleton.deserializeSkeleton(data)).getMessage()
    );
  }

  /// The boundary case: exactly enough addresses for the signers and read-only non-signers, with no
  /// writable non-signers at all, is legal and must still parse.
  @Test
  void exactlyEnoughAddressesIsAccepted() {
    final byte[] data = validV1Transaction();
    final int numAddresses = data[NUM_ADDRESSES] & 0xFF;
    final int numRequiredSignatures = data[NUM_REQUIRED_SIGNATURES] & 0xFF;
    data[NUM_READONLY_UNSIGNED] = (byte) (numAddresses - numRequiredSignatures);

    final var skeleton = TransactionSkeleton.deserializeSkeleton(data);
    assertEquals(numAddresses - numRequiredSignatures, skeleton.numReadonlyUnsignedAccounts());
    assertEquals(numAddresses, skeleton.parseAccounts().length);
  }
}
