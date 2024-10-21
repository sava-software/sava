package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.ExtensionType;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

public record MintCloseAuthority(PublicKey closeAuthority) implements TokenExtension {

  public static MintCloseAuthority read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    final var closeAuthority = readPubKey(data, offset);
    return new MintCloseAuthority(closeAuthority);
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.MintCloseAuthority;
  }

  @Override
  public int l() {
    return PUBLIC_KEY_LENGTH;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    throw new UnsupportedOperationException("TODO");
  }
}
