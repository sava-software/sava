package software.sava.core.programs;

import software.sava.core.encoding.ByteUtil;
import software.sava.core.tx.Instruction;

import java.util.Arrays;
import java.util.function.Predicate;

public interface Discriminator extends Predicate<Instruction> {

  int NATIVE_DISCRIMINATOR_LENGTH = Integer.BYTES;
  int ANCHOR_DISCRIMINATOR_LENGTH = 8;

  static Discriminator createDiscriminator(final byte[] discriminator) {
    return new DiscriminatorRecord(discriminator);
  }

  static Discriminator createDiscriminator(final byte[] data, final int offset, final int length) {
    final byte[] discriminator = new byte[length];
    System.arraycopy(data, offset, discriminator, 0, length);
    return createDiscriminator(discriminator);
  }

  static Discriminator createDiscriminator(final byte[] data, final int length) {
    return createDiscriminator(data, 0, length);
  }

  static Discriminator createAnchorDiscriminator(final byte[] data, final int offset) {
    return createDiscriminator(data, offset, ANCHOR_DISCRIMINATOR_LENGTH);
  }

  static Discriminator createAnchorDiscriminator(final byte[] data) {
    return createAnchorDiscriminator(data, 0);
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

  default int length() {
    return data().length;
  }

  default int write(final byte[] bytes, final int offset) {
    final byte[] data = data();
    System.arraycopy(data, 0, bytes, offset, data.length);
    return data.length;
  }

  default int write(final byte[] bytes) {
    return write(bytes, 0);
  }

  default boolean equals(final byte[] data, final int offset) {
    final byte[] thisData = data();
    final int len = data.length - offset;
    return len >= thisData.length && Arrays.equals(
        thisData, 0, thisData.length,
        data, offset, offset + thisData.length
    );
  }

  @Override
  default boolean test(final Instruction ix) {
    return equals(ix.data(), ix.offset());
  }

  default int[] toIntArray() {
    final byte[] data = data();
    final int[] d = new int[data.length];
    for (int i = 0; i < d.length; ++i) {
      d[i] = data[i] & 0xff;
    }
    return d;
  }
}
