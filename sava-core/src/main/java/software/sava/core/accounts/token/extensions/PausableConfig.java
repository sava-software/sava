package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

/// @param authority the authority that may pause and resume the mint; all-zero means none, so
///                  compare with [PublicKey#NONE] rather than testing for `null`.
/// @param paused    whether transfers, minting and burning are currently paused.
public record PausableConfig(PublicKey authority, boolean paused) implements MintTokenExtension {

  public static final int BYTES = PUBLIC_KEY_LENGTH + 1;

  public static PausableConfig read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    final var authority = readPubKey(data, offset);
    final boolean paused = data[offset + PUBLIC_KEY_LENGTH] != 0;
    return new PausableConfig(authority, paused);
  }

  @Override
  public int ordinal() {
    return 26;
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
