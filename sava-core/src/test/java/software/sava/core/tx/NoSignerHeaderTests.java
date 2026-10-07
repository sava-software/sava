package software.sava.core.tx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;

/// A legacy header declaring zero required signatures. No valid message does: Solana's message
/// sanitizer rejects `num_readonly_signed_accounts >= num_required_signatures` ("there should be at
/// least 1 RW fee-payer account", solana-sdk `message/src/legacy.rs` at 2e1ebc4e), so a zero count
/// means the message names no fee payer. The bytes are representable, and the skeleton is fed
/// untrusted bytes.
///
/// The contract is the one `HeaderCountConsistencyTests` applies to the opposite contradiction:
/// garbage in may throw, but whatever a reader returns must come from the field it claims to read.
/// The address-coverage guard compares signers to addresses with `<`, which zero signers always
/// pass, so a second guard refuses the header wherever a fee payer is resolved, while the readers
/// that resolve none keep reading their own fields verbatim. Found by the fee-payer cross-checks
/// in [TransactionSkeletonFuzz], which reject both inputs.
final class NoSignerHeaderTests {

  private static final String NO_FEE_PAYER =
      "Header declares no required signatures, so no address is the fee payer.";

  private static final byte FIRST_ADDRESS_BYTE = 0x11;
  private static final byte SECOND_ADDRESS_BYTE = 0x22;
  private static final byte BLOCKHASH_BYTE = 0x33;

  /// Signature count, the three header bytes, the address count, the addresses, a distinguishable
  /// blockhash, and an empty instruction list. The serialized signature count agrees with the
  /// header's, so only the zero signer count is contradictory.
  private static byte[] noSigners(final int numAddresses) {
    final byte[] data = new byte[5 + ((numAddresses + 1) * PUBLIC_KEY_LENGTH) + 1];
    data[0] = 0;  // serialized signature count, agreeing with the header below
    data[1] = 0;  // num_required_signatures; below 0x80, so a legacy message
    data[2] = 0;  // num_readonly_signed
    data[3] = 0;  // num_readonly_unsigned
    data[4] = (byte) numAddresses;
    int o = 5;
    for (int a = 0; a < numAddresses; ++a, o += PUBLIC_KEY_LENGTH) {
      Arrays.fill(data, o, o + PUBLIC_KEY_LENGTH, a == 0 ? FIRST_ADDRESS_BYTE : SECOND_ADDRESS_BYTE);
    }
    Arrays.fill(data, o, o + PUBLIC_KEY_LENGTH, BLOCKHASH_BYTE);
    // the final byte is the zero instruction count
    return data;
  }

  private static PublicKey filledKey(final byte b) {
    final byte[] key = new byte[PUBLIC_KEY_LENGTH];
    Arrays.fill(key, b);
    return PublicKey.createPubKey(key);
  }

  /// With no addresses at all there is no fee payer to return. Any key `feePayer()` handed back
  /// would be read from outside the address array: before the guard it was the blockhash, the
  /// same misread #58 closed for a header declaring more signers than addresses.
  @Test
  void aMessageWithNoSignersAndNoAddressesHasNoFeePayer() {
    final var skeleton = TransactionSkeleton.deserializeSkeleton(noSigners(0));
    assertEquals(0, skeleton.numSigners());
    assertEquals(0, skeleton.numIncludedAccounts());

    final var refused = assertThrowsExactly(IllegalStateException.class, skeleton::feePayer);
    assertEquals(NO_FEE_PAYER, refused.getMessage());
  }

  /// With addresses but no signers, every parser that resolves the fee payer refuses the header,
  /// and the parsers that resolve none read their fields verbatim: no signer keys, and every
  /// included address as a non-signer. Before the guard, the included-account parse filled slot
  /// zero from the fee payer and then restarted the non-signer walk one address later at slot
  /// zero, so the first address was dropped, every key shifted down a slot, and the blockhash
  /// came back as the last account, while `feePayer()` still answered the first address: two
  /// views that disagreed without either throwing.
  @Test
  void aMessageWithNoSignersIsRefusedWhereAFeePayerIsResolvedAndReadVerbatimElsewhere() {
    final var skeleton = TransactionSkeleton.deserializeSkeleton(noSigners(2));
    assertEquals(0, skeleton.numSigners());
    assertEquals(2, skeleton.numIncludedAccounts());

    for (final var resolvesFeePayer : List.<Executable>of(
        skeleton::parseAccounts, skeleton::parseSignerAccounts, skeleton::feePayer
    )) {
      final var refused = assertThrowsExactly(IllegalStateException.class, resolvesFeePayer);
      assertEquals(NO_FEE_PAYER, refused.getMessage());
    }

    final var expected = new PublicKey[]{filledKey(FIRST_ADDRESS_BYTE), filledKey(SECOND_ADDRESS_BYTE)};
    assertArrayEquals(expected, skeleton.parseNonSignerPublicKeys(), "every included address is a non-signer");
    assertArrayEquals(
        expected,
        Arrays.stream(skeleton.parseNonSignerAccounts()).map(AccountMeta::publicKey).toArray(PublicKey[]::new)
    );
    assertEquals(0, skeleton.parseSignerPublicKeys().length, "no signer keys to read");
  }
}
