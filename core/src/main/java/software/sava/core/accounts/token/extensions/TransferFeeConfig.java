package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

public record TransferFeeConfig(PublicKey transferFeeConfigAuthority,
                                PublicKey withdrawWithheldAuthority,
                                long withheldAmount,
                                TransferFee olderTransferFee,
                                TransferFee newerTransferFee) implements TokenExtension {

  public static final int BYTES = PUBLIC_KEY_LENGTH
      + PUBLIC_KEY_LENGTH
      + Long.BYTES
      + TransferFee.BYTES
      + TransferFee.BYTES;

  public static TransferFeeConfig read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    int i = offset;
    final var transferFeeConfigAuthority = readPubKey(data, i);
    i += PUBLIC_KEY_LENGTH;
    final var withdrawWithheldAuthority = readPubKey(data, i);
    i += PUBLIC_KEY_LENGTH;
    final long withheldAmount = ByteUtil.getInt64LE(data, i);
    i += Long.BYTES;
    final var olderTransferFee = TransferFee.read(data, i);
    i += olderTransferFee.l();
    final var newerTransferFee = TransferFee.read(data, i);
    return new TransferFeeConfig(
        transferFeeConfigAuthority,
        withdrawWithheldAuthority,
        withheldAmount,
        olderTransferFee,
        newerTransferFee
    );
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.TransferFeeConfig;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    transferFeeConfigAuthority.write(data, offset);
    int i = offset + PUBLIC_KEY_LENGTH;
    withdrawWithheldAuthority.write(data, offset);
    i += PUBLIC_KEY_LENGTH;
    ByteUtil.putInt64LE(data, i, withheldAmount);
    i += Long.BYTES;
    i += olderTransferFee.write(data, i);
    newerTransferFee.write(data, i);
    return BYTES;
  }
}
