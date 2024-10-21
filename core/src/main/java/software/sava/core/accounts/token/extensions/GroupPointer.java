package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.ExtensionType;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

public record GroupPointer(PublicKey authority, PublicKey groupAddress) implements TokenExtension {

  public static final int BYTES = PUBLIC_KEY_LENGTH + PUBLIC_KEY_LENGTH;

  public static GroupPointer read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    final var authority = readPubKey(data, offset);
    final var groupAddress = readPubKey(data, offset + PUBLIC_KEY_LENGTH);
    return new GroupPointer(authority, groupAddress);
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.GroupPointer;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    authority.write(data, offset);
    groupAddress.write(data, offset + PUBLIC_KEY_LENGTH);
    return BYTES;
  }
}
