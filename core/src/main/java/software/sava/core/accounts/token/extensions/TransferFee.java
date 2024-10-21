package software.sava.core.accounts.token.extensions;

import software.sava.core.encoding.ByteUtil;
import software.sava.core.serial.Serializable;

public record TransferFee(long epoch,
                          long maximumFee,
                          int transferFeeBasisPoints) implements Serializable {

  static final int BYTES = Long.BYTES + Long.BYTES + Short.BYTES;

  static TransferFee read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    int i = offset;

    final var epoch = ByteUtil.getInt64LE(data, i);
    i += Long.BYTES;

    final var maximumFee = ByteUtil.getInt64LE(data, i);
    i += Long.BYTES;

    final int transferFeeBasisPoints = ByteUtil.getInt16LE(data, i);

    return new TransferFee(
        epoch,
        maximumFee,
        transferFeeBasisPoints
    );
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    throw new UnsupportedOperationException("TODO");
  }
}
