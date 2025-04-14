package software.sava.core.accounts.token.extensions;

public record PausableAccount() implements AccountTokenExtension {

  public static final PausableAccount INSTANCE = new PausableAccount();

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.PausableAccount;
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
