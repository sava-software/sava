package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;


/// @param authority              the authority that may update the configuration; all-zero
///                               means none, so compare with [PublicKey#NONE] rather than
///                               testing for `null`.
/// @param autoApproveNewAccounts whether new confidential accounts are approved on creation.
/// @param auditorElGamalKey      the auditor's ElGamal key; all-zero means none, compared the
///                               same way.
public record ConfidentialTransferMint(PublicKey authority,
                                       boolean autoApproveNewAccounts,
                                       PublicKey auditorElGamalKey) implements MintTokenExtension {

  public static final int BYTES = PUBLIC_KEY_LENGTH + PUBLIC_KEY_LENGTH + 1;

  public static ConfidentialTransferMint read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    final var authority = readPubKey(data, offset);
    final boolean autoApproveNewAccounts = data[offset + PUBLIC_KEY_LENGTH] != 0;
    final var auditorElGamalKey = readPubKey(data, offset + PUBLIC_KEY_LENGTH + 1);
    return new ConfidentialTransferMint(
        authority,
        autoApproveNewAccounts,
        auditorElGamalKey
    );
  }

  @Override
  public int ordinal() {
    return 4;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    authority.write(data, offset);
    data[offset + PUBLIC_KEY_LENGTH] = (byte) (autoApproveNewAccounts ? 1 : 0);
    auditorElGamalKey.write(data, offset + PUBLIC_KEY_LENGTH + 1);
    return BYTES;
  }
}
