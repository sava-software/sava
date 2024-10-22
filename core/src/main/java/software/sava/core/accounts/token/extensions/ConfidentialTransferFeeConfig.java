package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;

public record ConfidentialTransferFeeConfig(PublicKey authority,
                                            PublicKey withdrawWithheldAuthorityElgamalPubkey,
                                            boolean harvestToMintEnabled,
                                            byte[] withheldAmount) implements TokenExtension {

  public static ConfidentialTransferFeeConfig read(final byte[] data, final int offset, final int to) {
    if (data == null || data.length == 0) {
      return null;
    }
    final var authority = PublicKey.readPubKey(data, offset);
    int i = offset + PUBLIC_KEY_LENGTH;
    final var withdrawWithheldAuthorityElgamalPubkey = PublicKey.readPubKey(data, i);
    i += PUBLIC_KEY_LENGTH;
    final boolean harvestToMintEnabled = data[i] == 1;
    ++i;
    final byte[] withheldAmount = new byte[to - i];
    System.arraycopy(data, i, withheldAmount, 0, withheldAmount.length);
    return new ConfidentialTransferFeeConfig(
        authority,
        withdrawWithheldAuthorityElgamalPubkey,
        harvestToMintEnabled,
        withheldAmount
    );
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.ConfidentialTransferFeeConfig;
  }

  @Override
  public int l() {
    return PUBLIC_KEY_LENGTH + PUBLIC_KEY_LENGTH + 1 + withheldAmount.length;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    authority.write(data, offset);
    int i = offset + PUBLIC_KEY_LENGTH;
    i += withdrawWithheldAuthorityElgamalPubkey.write(data, i);
    data[i] = (byte) (harvestToMintEnabled ? 1 : 0);
    ++i;
    System.arraycopy(withheldAmount, 0, data, i, withheldAmount.length);
    i += withheldAmount.length;
    return i - offset;
  }
}