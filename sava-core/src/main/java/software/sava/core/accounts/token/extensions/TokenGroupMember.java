package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

public record TokenGroupMember(PublicKey mint,
                               PublicKey group,
                               long memberNumber) implements MintTokenExtension {

  public static final int BYTES = PUBLIC_KEY_LENGTH
      + PUBLIC_KEY_LENGTH
      + Long.BYTES;

  public static TokenGroupMember read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    int i = offset;
    final var mint = readPubKey(data, i);
    i += PUBLIC_KEY_LENGTH;
    final var group = readPubKey(data, i);
    i += PUBLIC_KEY_LENGTH;
    final long memberNumber = ByteUtil.getInt64LE(data, i);
    return new TokenGroupMember(mint, group, memberNumber);
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.TokenGroupMember;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    mint.write(data, offset);
    int i = offset + PUBLIC_KEY_LENGTH;
    group.write(data, i);
    i += PUBLIC_KEY_LENGTH;
    ByteUtil.putInt64LE(data, i, memberNumber);
    return BYTES;
  }
}
