package software.sava.core.accounts.token.extensions;

public record MemoTransfer(boolean requireIncomingTransferAmount) implements TokenExtension {

  public static final int BYTES = 1;

  public static MemoTransfer read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    return new MemoTransfer(data[offset] == 1);
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.MemoTransfer;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    data[offset] = (byte) (requireIncomingTransferAmount ? 1 : 0);
    return BYTES;
  }
}