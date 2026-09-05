package software.sava.core.accounts.token.extensions;

public record NonTransferableAccount() implements AccountTokenExtension {

  public static final NonTransferableAccount INSTANCE = new NonTransferableAccount();

  @Override
  public int ordinal() {
    return 13;
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
