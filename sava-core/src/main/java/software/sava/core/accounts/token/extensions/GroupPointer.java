package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

/// @param authority    the authority that may update the pointer; all-zero means none, so
///                     compare with [PublicKey#NONE] rather than testing for `null`.
/// @param groupAddress the account holding the group; all-zero means none, compared the same
///                     way.
public record GroupPointer(PublicKey authority, PublicKey groupAddress) implements MintTokenExtension {

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
  public int ordinal() {
    return 20;
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
