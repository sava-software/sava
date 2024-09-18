package software.sava.core.programs;

import software.sava.core.encoding.ByteUtil;
import software.sava.core.tx.Instruction;

import java.util.function.Predicate;

public interface Discriminator extends Predicate<Instruction> {

  int NATIVE_DISCRIMINATOR_LENGTH = Integer.BYTES;

  static Discriminator createDiscriminator(final byte[] discriminator) {
    return new DiscriminatorRecord(discriminator);
  }

  static Discriminator toDiscriminator(final int... val) {
    final int len = val.length;
    final byte[] d = new byte[len];
    for (int i = 0; i < len; ++i) {
      d[i] = (byte) val[i];
    }
    return new DiscriminatorRecord(d);
  }

  static byte[] serializeDiscriminator(final Enum<?> ixEnum) {
    final byte[] data = new byte[NATIVE_DISCRIMINATOR_LENGTH];
    ByteUtil.putInt32LE(data, 0, ixEnum.ordinal());
    return data;
  }

  static void serializeDiscriminator(final byte[] data, final Enum<?> ixEnum) {
    ByteUtil.putInt32LE(data, 0, ixEnum.ordinal());
  }

  byte[] data();

  default int write(final byte[] bytes) {
    return write(bytes, 0);
  }

  default int[] toIntArray() {
    final byte[] data = data();
    final int[] d = new int[data.length];
    for (int i = 0; i < d.length; ++i) {
      d[i] = data[i] & 0xff;
    }
    return d;
  }

  default int write(final byte[] bytes, final int i) {
    final byte[] data = data();
    System.arraycopy(data, 0, bytes, i, data.length);
    return data.length;
  }

  default int length() {
    return data().length;
  }

  boolean equals(final byte[] data, final int offset);
}
