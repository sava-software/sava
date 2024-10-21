package software.sava.core.accounts.token.extensions;

import software.sava.core.encoding.ByteUtil;
import software.sava.core.serial.Serializable;

public record TransferFee(long epoch,
                          long maximumFee,
                          int transferFeeBasisPoints) implements Serializable {

  public static final int BYTES = Long.BYTES + Long.BYTES + Short.BYTES;

  public static TransferFee read(final byte[] data, final int offset) {
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
    ByteUtil.putInt64LE(data, offset, epoch);
    ByteUtil.putInt64LE(data, offset + Long.BYTES, maximumFee);
    ByteUtil.putInt16LE(data, offset + Long.BYTES + Long.BYTES, transferFeeBasisPoints);
    return BYTES;
  }
}
