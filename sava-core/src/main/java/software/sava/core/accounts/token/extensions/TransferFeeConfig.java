package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

/// @param transferFeeConfigAuthority the authority that may update the fee; all-zero means
///                                   none, so compare with [PublicKey#NONE] rather than
///                                   testing for `null`.
/// @param withdrawWithheldAuthority  the authority that may withdraw withheld fees; all-zero
///                                   means none, compared the same way.
/// @param withheldAmount             fees withheld on the mint itself.
/// @param olderTransferFee           the fee in force before the newer one took effect.
/// @param newerTransferFee           the fee in force from its own epoch onwards.
public record TransferFeeConfig(PublicKey transferFeeConfigAuthority,
                                PublicKey withdrawWithheldAuthority,
                                long withheldAmount,
                                TransferFee olderTransferFee,
                                TransferFee newerTransferFee) implements MintTokenExtension {

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
  public int ordinal() {
    return 1;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    transferFeeConfigAuthority.write(data, offset);
    int i = offset + PUBLIC_KEY_LENGTH;
    withdrawWithheldAuthority.write(data, i);
    i += PUBLIC_KEY_LENGTH;
    ByteUtil.putInt64LE(data, i, withheldAmount);
    i += Long.BYTES;
    i += olderTransferFee.write(data, i);
    newerTransferFee.write(data, i);
    return BYTES;
  }
}
