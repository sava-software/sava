package software.sava.core.programs;

import software.sava.core.encoding.ByteUtil;

public interface Discriminator {

  int SOLANA_PROGRAMS_LENGTH = Integer.BYTES;

  static Discriminator toDiscriminator(final int... val) {
    final int len = val.length;
    final byte[] d = new byte[len];
    for (int i = 0; i < len; ++i) {
      d[i] = (byte) val[i];
    }
    return new DiscriminatorRecord(d);
  }

  static byte[] serializeDiscriminator(final Enum<?> ixEnum) {
    final byte[] data = new byte[SOLANA_PROGRAMS_LENGTH];
    ByteUtil.putInt32LE(data, 0, ixEnum.ordinal());
    return data;
  }

  static void serializeDiscriminator(final byte[] data, final Enum<?> ixEnum) {
    ByteUtil.putInt32LE(data, 0, ixEnum.ordinal());
  }

  byte[] data();
}
