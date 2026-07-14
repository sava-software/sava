package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

public record TokenGroup(PublicKey updateAuthority,
                         PublicKey mint,
                         long size,
                         long maxSize) implements MintTokenExtension {

  public static final int BYTES = PUBLIC_KEY_LENGTH
      + PUBLIC_KEY_LENGTH
      + Long.BYTES
      + Long.BYTES;

  public static TokenGroup read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    int i = offset;
    final var updateAuthority = readPubKey(data, i);
    i += PUBLIC_KEY_LENGTH;
    final var mint = readPubKey(data, i);
    i += PUBLIC_KEY_LENGTH;
    final long size = ByteUtil.getInt64LE(data, i);
    i += Long.BYTES;
    final long maxSize = ByteUtil.getInt64LE(data, i);
    return new TokenGroup(updateAuthority, mint, size, maxSize);
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.TokenGroup;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    updateAuthority.write(data, offset);
    int i = offset + PUBLIC_KEY_LENGTH;
    mint.write(data, i);
    i += PUBLIC_KEY_LENGTH;
    ByteUtil.putInt64LE(data, i, size);
    i += Long.BYTES;
    ByteUtil.putInt64LE(data, i, maxSize);
    return BYTES;
  }
}
