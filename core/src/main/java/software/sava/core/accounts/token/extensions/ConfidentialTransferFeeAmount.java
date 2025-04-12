package software.sava.core.accounts.token.extensions;

public record ConfidentialTransferFeeAmount(byte[] withheldAmount) implements TokenExtension {

  public static ConfidentialTransferFeeAmount read(final byte[] data, final int offset, final int to) {
    if (data == null || data.length == 0) {
      return null;
    }
    final byte[] withheldAmount = new byte[to - offset];
    System.arraycopy(data, offset, withheldAmount, 0, withheldAmount.length);
    return new ConfidentialTransferFeeAmount(withheldAmount);
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.ConfidentialTransferFeeAmount;
  }

  @Override
  public int l() {
    return withheldAmount.length;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    System.arraycopy(withheldAmount, 0, data, offset, withheldAmount.length);
    return withheldAmount.length;
  }
}
