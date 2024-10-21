package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.ExtensionType;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

public record TransferHook(PublicKey authority, PublicKey programId) implements TokenExtension {

  public static final int BYTES = PUBLIC_KEY_LENGTH + PUBLIC_KEY_LENGTH;

  public static TransferHook read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    final var authority = readPubKey(data, offset);
    final var programId = readPubKey(data, offset + PUBLIC_KEY_LENGTH);
    return new TransferHook(authority, programId);
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.TransferHook;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    authority.write(data, offset);
    programId.write(data, offset + PUBLIC_KEY_LENGTH);
    return BYTES;
  }
}
