package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;


public record ConfidentialTransferMint(PublicKey authority,
                                       boolean autoApproveNewAccounts,
                                       PublicKey auditorElGamalKey) implements TokenExtension {

  public static final int BYTES = PUBLIC_KEY_LENGTH + PUBLIC_KEY_LENGTH + 1;

  public static ConfidentialTransferMint read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    final var authority = readPubKey(data, offset);
    final boolean autoApproveNewAccounts = data[offset + PUBLIC_KEY_LENGTH] == 1;
    final var auditorElGamalKey = readPubKey(data, offset + PUBLIC_KEY_LENGTH + 1);
    return new ConfidentialTransferMint(
        authority,
        autoApproveNewAccounts,
        auditorElGamalKey
    );
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.ConfidentialTransferMint;
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