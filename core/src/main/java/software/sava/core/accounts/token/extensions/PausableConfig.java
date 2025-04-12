package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

public record PausableConfig(PublicKey authority, boolean paused) implements TokenExtension {

  public static final int BYTES = PUBLIC_KEY_LENGTH + 1;

  public static PausableConfig read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    final var authority = readPubKey(data, offset);
    final boolean paused = data[offset + PUBLIC_KEY_LENGTH] == 1;
    return new PausableConfig(authority, paused);
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.Pausable;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    authority.write(data, offset);
    data[offset + PUBLIC_KEY_LENGTH] = (byte) (paused ? 1 : 0);
    return BYTES;
  }
}
