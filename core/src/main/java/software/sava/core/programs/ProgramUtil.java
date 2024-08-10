package software.sava.core.programs;

import software.sava.core.encoding.ByteUtil;

public final class ProgramUtil {

  public static final int DISCRIMINATOR_LENGTH = Integer.BYTES;

  public static byte[] toDiscriminator(final int... val) {
    final int len = val.length;
    final byte[] d = new byte[len];
    for (int i = 0; i < len; ++i) {
      d[i] = (byte) val[i];
    }
    return d;
  }

  public static byte[] createDiscriminator(final Enum<?> ixEnum) {
    final byte[] data = new byte[DISCRIMINATOR_LENGTH];
    ByteUtil.putInt32LE(data, 0, ixEnum.ordinal());
    return data;
  }

  public static void setDiscriminator(final byte[] data, final Enum<?> ixEnum) {
    ByteUtil.putInt32LE(data, 0, ixEnum.ordinal());
  }

  private ProgramUtil() {
  }
}
