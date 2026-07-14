package software.sava.core.accounts.sysvar;

import software.sava.core.accounts.PublicKey;
import software.sava.core.borsh.Borsh;
import software.sava.core.encoding.ByteUtil;

import java.util.function.BiFunction;

record Rent(PublicKey address,
            long lamportsPerByteYear,
            double exemptionThreshold,
            int burnPercent) implements Borsh {

  /// Account storage overhead in bytes for calculating the minimum rent exempt balance.
  public static final int ACCOUNT_STORAGE_OVERHEAD = 128;

  public static final int BYTES = Long.BYTES + Double.BYTES + 1;

  public static final BiFunction<PublicKey, byte[], Rent> FACTORY = Rent::read;

  public static Rent read(final byte[] data) {
    return read(data, 0);
  }

  public static Rent read(final byte[] data, final int offset) {
    return read(null, data, offset);
  }

  public static Rent read(final PublicKey address, final byte[] data) {
    return read(address, data, 0);
  }

  public static Rent read(final PublicKey address, final byte[] data, int offset) {
    final long lamportsPerByteYear = ByteUtil.getInt64LE(data, offset);
    offset += Long.BYTES;
    final double exemptionThreshold = ByteUtil.getFloat64LE(data, offset);
    offset += Double.BYTES;
    final int burnPercent = data[offset] & 0xFF;
    return new Rent(address, lamportsPerByteYear, exemptionThreshold, burnPercent);
  }

  /// Minimum balance in lamports for an account with `dataLength` bytes of data to be rent
  /// exempt.
  public long minimumBalance(final long dataLength) {
    return (long) (((ACCOUNT_STORAGE_OVERHEAD + dataLength) * lamportsPerByteYear) * exemptionThreshold);
  }

  @Override
  public int write(final byte[] data, final int offset) {
    int i = offset;
    ByteUtil.putInt64LE(data, i, lamportsPerByteYear);
    i += Long.BYTES;
    ByteUtil.putFloat64LE(data, i, exemptionThreshold);
    i += Double.BYTES;
    data[i] = (byte) burnPercent;
    ++i;
    return i - offset;
  }

  @Override
  public int l() {
    return BYTES;
  }
}
