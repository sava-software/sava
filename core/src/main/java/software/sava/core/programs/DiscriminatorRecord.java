package software.sava.core.programs;

import java.util.Arrays;

record DiscriminatorRecord(byte[] data) implements Discriminator {

  @Override
  public byte[] data() {
    return Arrays.copyOf(data, data.length);
  }

  @Override
  public int[] toIntArray() {
    final int[] d = new int[data.length];
    for (int i = 0; i < d.length; ++i) {
      d[i] = data[i] & 0xff;
    }
    return d;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    System.arraycopy(this.data, 0, data, offset, this.data.length);
    return data.length;
  }

  @Override
  public int length() {
    return data.length;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o instanceof Discriminator discriminator) {
      return Arrays.equals(this.data, discriminator.data());
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(data);
  }
}
