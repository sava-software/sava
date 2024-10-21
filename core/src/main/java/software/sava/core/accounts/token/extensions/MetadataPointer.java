package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.ExtensionType;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

public record MetadataPointer(PublicKey authority, PublicKey metadataAddress) implements TokenExtension {

  public static final int BYTES = PUBLIC_KEY_LENGTH + PUBLIC_KEY_LENGTH;

  public static MetadataPointer read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    final var authority = readPubKey(data, offset);
    final var metadataAddress = readPubKey(data, offset);
    return new MetadataPointer(authority, metadataAddress);
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.MetadataPointer;
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
