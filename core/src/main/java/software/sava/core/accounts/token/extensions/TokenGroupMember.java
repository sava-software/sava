package software.sava.core.accounts.token.extensions;

public record TokenGroupMember() implements MintTokenExtension {

  public static final TokenGroupMember INSTANCE = new TokenGroupMember();

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.TokenGroupMember;
  }

  @Override
  public int l() {
    return 0;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    return 0;
  }
}
