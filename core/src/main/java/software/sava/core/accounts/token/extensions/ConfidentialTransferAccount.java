package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;

public record ConfidentialTransferAccount(boolean approved,
                                          PublicKey elgamalPubkey,
                                          short pendingBalanceLo,
                                          byte[] data) implements TokenExtension {

  public static ConfidentialTransferAccount read(final byte[] data, final int offset, final int to) {
    if (data == null || data.length == 0) {
      return null;
    }
    final boolean approved = data[offset] == 1;
    int i = offset + 1;
    final var elgamalPubkey = PublicKey.readPubKey(data, i);
    i += PUBLIC_KEY_LENGTH;
    final short pendingBalanceLo = ByteUtil.getInt16LE(data, i);
    final byte[] accountData = new byte[to - offset];
    System.arraycopy(data, offset, accountData, 0, accountData.length);
    return new ConfidentialTransferAccount(
        approved,
        elgamalPubkey,
        pendingBalanceLo,
        accountData
    );
  }
//  pub approved: PodBool,
//
//  /// The public key associated with ElGamal encryption
//  pub elgamal_pubkey: PodElGamalPubkey,
//
//  /// The low 16 bits of the pending balance (encrypted by `elgamal_pubkey`)
//  pub pending_balance_lo: EncryptedBalance,
//
//  /// The high 48 bits of the pending balance (encrypted by `elgamal_pubkey`)
//  pub pending_balance_hi: EncryptedBalance,
//
//  /// The available balance (encrypted by `encrypiton_pubkey`)
//  pub available_balance: EncryptedBalance,
//
//  /// The decryptable available balance
//  pub decryptable_available_balance: DecryptableBalance,
//
//  /// If `false`, the extended account rejects any incoming confidential
//  /// transfers
//  pub allow_confidential_credits: PodBool,
//
//  /// If `false`, the base account rejects any incoming transfers
//  pub allow_non_confidential_credits: PodBool,
//
//  /// The total number of `Deposit` and `Transfer` instructions that have
//  /// credited `pending_balance`
//  pub pending_balance_credit_counter: PodU64,
//
//  /// The maximum number of `Deposit` and `Transfer` instructions that can
//  /// credit `pending_balance` before the `ApplyPendingBalance`
//  /// instruction is executed
//  pub maximum_pending_balance_credit_counter: PodU64,
//
//  /// The `expected_pending_balance_credit_counter` value that was included in
//  /// the last `ApplyPendingBalance` instruction
//  pub expected_pending_balance_credit_counter: PodU64,
//
//  /// The actual `pending_balance_credit_counter` when the last
//  /// `ApplyPendingBalance` instruction was executed
//  pub actual_pending_balance_credit_counter: PodU64,

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.ConfidentialTransferAccount;
  }

  @Override
  public int l() {
    return this.data.length;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    System.arraycopy(this.data, 0, data, offset, this.data.length);
    return this.data.length;
  }
}