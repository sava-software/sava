package software.sava.core.accounts.token.extensions;

import software.sava.core.encoding.ByteUtil;

public record TransferFeeAmount(long withHeldAmount) implements MintTokenExtension {

  public static final int BYTES = Long.BYTES;

  public static TransferFeeAmount read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    final long withheldAmount = ByteUtil.getInt64LE(data, offset);
    return new TransferFeeAmount(withheldAmount);
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.TransferFeeAmount;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    ByteUtil.putInt64LE(data, offset, withHeldAmount);
    return BYTES;
  }
}
