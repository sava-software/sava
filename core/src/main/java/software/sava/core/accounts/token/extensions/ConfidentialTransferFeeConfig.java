package software.sava.core.accounts.token.extensions;

public record ConfidentialTransferFeeConfig() implements TokenExtension {

  public static ConfidentialTransferFeeConfig read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    throw new UnsupportedOperationException("TODO");
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.ConfidentialTransferFeeConfig;
  }

  @Override
  public int l() {
    throw new UnsupportedOperationException("TODO");
  }

  @Override
  public int write(final byte[] data, final int offset) {
    throw new UnsupportedOperationException("TODO");
  }
}