package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

public record PermissionedBurnConfig(PublicKey authority) implements MintTokenExtension {

  public static final int BYTES = PUBLIC_KEY_LENGTH;

  public static PermissionedBurnConfig read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    final var authority = readPubKey(data, offset);
    return new PermissionedBurnConfig(authority);
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.PermissionedBurn;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    authority.write(data, offset);
    return BYTES;
  }
}
