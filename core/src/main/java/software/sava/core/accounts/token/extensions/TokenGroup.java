package software.sava.core.accounts.token.extensions;

public record TokenGroup() implements TokenExtension {

  public static final TokenGroup INSTANCE = new TokenGroup();

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.TokenGroup;
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
