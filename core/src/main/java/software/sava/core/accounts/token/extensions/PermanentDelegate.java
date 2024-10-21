package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

public record PermanentDelegate(PublicKey delegate) implements TokenExtension {

  public static final int BYTES = PUBLIC_KEY_LENGTH;

  public static PermanentDelegate read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    final var closeAuthority = readPubKey(data, offset);
    return new PermanentDelegate(closeAuthority);
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.PermanentDelegate;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    delegate.write(data, offset);
    return BYTES;
  }
}
