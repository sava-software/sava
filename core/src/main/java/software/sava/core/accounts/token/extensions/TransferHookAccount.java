package software.sava.core.accounts.token.extensions;

public record TransferHookAccount(boolean transferring) implements AccountTokenExtension {

  public static final int BYTES = 1;

  public static TransferHookAccount read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    return new TransferHookAccount(data[offset] == 1);
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.TransferHookAccount;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    data[offset] = (byte) (transferring ? 1 : 0);
    return BYTES;
  }
}
