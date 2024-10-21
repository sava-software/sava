package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.ExtensionType;

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
    int i = offset;

    final var authority = readPubKey(data, i);
    i += PUBLIC_KEY_LENGTH;

    final boolean autoApproveNewAccounts = data[i] == 1;
    ++i;

    final var auditorElGamalKey = readPubKey(data, i);

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
    throw new UnsupportedOperationException("TODO");
  }
}