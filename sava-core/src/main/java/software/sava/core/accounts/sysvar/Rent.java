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

  /// Maximum permitted account-data length in Solana's rent calculation (10 MiB).
  static final long MAX_PERMITTED_DATA_LENGTH = 10L * 1024 * 1024;

  private static final long SIMD0194_MAX_LAMPORTS_PER_BYTE = 1_759_197_129_867L;
  private static final long CURRENT_MAX_LAMPORTS_PER_BYTE = 879_598_564_933L;
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
  /// exempt. This follows the current `solana-rent` validation and uses its exact integer
  /// paths for exemption thresholds `1.0` and `2.0`.
  public long minimumBalance(final long dataLength) {
    if (dataLength < 0 || dataLength > MAX_PERMITTED_DATA_LENGTH) {
      throw new IllegalArgumentException("Maximum permitted data length exceeded: " + dataLength);
    }
    if (exemptionThreshold == 1.0) {
      validateLamportsPerByte(SIMD0194_MAX_LAMPORTS_PER_BYTE);
      return (ACCOUNT_STORAGE_OVERHEAD + dataLength) * lamportsPerByteYear;
    }
    if (exemptionThreshold == 2.0) {
      validateLamportsPerByte(CURRENT_MAX_LAMPORTS_PER_BYTE);
      return 2 * (ACCOUNT_STORAGE_OVERHEAD + dataLength) * lamportsPerByteYear;
    }
    // Rust multiplies in u64 first, then widens that raw unsigned value to f64. A Java
    // long with its high bit set must therefore be widened as an unsigned magnitude,
    // not converted to a negative double.
    final long rawLamports = (ACCOUNT_STORAGE_OVERHEAD + dataLength) * lamportsPerByteYear;
    final double lamports = (double) (rawLamports & Long.MAX_VALUE)
        + ((rawLamports >>> 63) * 0x1.0p63);
    return saturatingUInt64(lamports * exemptionThreshold);
  }

  /// Matches Rust's saturating `f64 as u64`: NaN and negative values become zero,
  /// positive overflow becomes `u64::MAX`, and finite in-range values truncate.
  private static long saturatingUInt64(final double value) {
    // Every value below one truncates to zero; spelling the boundary this way also keeps
    // the exact value one observable rather than routing zero through an equivalent branch.
    if (!(value >= 1.0)) {
      return 0;
    }
    if (value < 0x1.0p63) {
      return (long) value;
    }
    // Java's double-to-long conversion saturates at Long.MAX_VALUE. Subtracting 2^63
    // moves the unsigned upper half into the signed domain; values at or above 2^64
    // saturate to Long.MAX_VALUE here and setting the high bit therefore yields u64::MAX.
    return ((long) (value - 0x1.0p63)) | Long.MIN_VALUE;
  }

  private void validateLamportsPerByte(final long maximum) {
    if (Long.compareUnsigned(lamportsPerByteYear, maximum) > 0) {
      throw new IllegalArgumentException("Maximum permitted lamports per byte exceeded: "
          + Long.toUnsignedString(lamportsPerByteYear));
    }
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
