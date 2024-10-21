package software.sava.core.accounts.token.extensions;

public record NonTransferableAccount() implements TokenExtension {

  public static final NonTransferableAccount INSTANCE = new NonTransferableAccount();

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.NonTransferableAccount;
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
