package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

public record InterestBearingConfig(PublicKey rateAuthority,
                                    long unixTimestamp,
                                    int preUpdateAverageRate,
                                    long lastUpdateTimestamp,
                                    int currentRate) implements MintTokenExtension {

  public static final int BYTES = PUBLIC_KEY_LENGTH + Long.BYTES + Short.BYTES + Long.BYTES + Short.BYTES;

  public static InterestBearingConfig read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    final var rateAuthority = readPubKey(data, offset);
    int i = offset + PUBLIC_KEY_LENGTH;
    final long unixTimestamp = ByteUtil.getInt64LE(data, i);
    i += Long.BYTES;
    final int preUpdateAverageRate = ByteUtil.getInt16LE(data, i);
    i += Short.BYTES;
    final long lastUpdateTimestamp = ByteUtil.getInt64LE(data, i);
    i += Long.BYTES;
    final int currentRate = ByteUtil.getInt16LE(data, i);
    return new InterestBearingConfig(
        rateAuthority,
        unixTimestamp,
        preUpdateAverageRate,
        lastUpdateTimestamp,
        currentRate
    );
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.InterestBearingConfig;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    rateAuthority.write(data, offset);
    int i = offset + PUBLIC_KEY_LENGTH;
    ByteUtil.putInt64LE(data, i, unixTimestamp);
    i += Long.BYTES;
    ByteUtil.putInt16LE(data, i, preUpdateAverageRate);
    i += Short.BYTES;
    ByteUtil.putInt64LE(data, i, lastUpdateTimestamp);
    i += Long.BYTES;
    ByteUtil.putInt16LE(data, i, currentRate);
    return BYTES;
  }
}
