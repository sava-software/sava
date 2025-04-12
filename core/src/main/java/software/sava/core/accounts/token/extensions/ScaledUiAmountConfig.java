package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;

public record ScaledUiAmountConfig(PublicKey authority,
                                   double multiplier,
                                   long newMultiplierEffectiveTimestamp,
                                   double newMultiplier) implements TokenExtension {

  public static final int BYTES = PublicKey.PUBLIC_KEY_LENGTH + Double.BYTES + Long.BYTES + Double.BYTES;

  public static ScaledUiAmountConfig read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    final var authority = readPubKey(data, offset);
    int i = offset + PUBLIC_KEY_LENGTH;
    final double multiplier = ByteUtil.getFloat64LE(data, i);
    i += Double.BYTES;
    final long newMultiplierEffectiveTimestamp = ByteUtil.getInt64LE(data, i);
    i += Long.BYTES;
    final double newMultiplier = ByteUtil.getFloat64LE(data, i);
    return new ScaledUiAmountConfig(
        authority,
        multiplier,
        newMultiplierEffectiveTimestamp,
        newMultiplier
    );
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.ScaledUiAmount;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    authority.write(data, offset);
    int i = offset + PUBLIC_KEY_LENGTH;
    ByteUtil.putFloat64LE(data, i, multiplier);
    i += Double.BYTES;
    ByteUtil.putInt64LE(data, i, newMultiplierEffectiveTimestamp);
    i += Long.BYTES;
    ByteUtil.putFloat64LE(data, i, newMultiplier);
    return BYTES;
  }
}
