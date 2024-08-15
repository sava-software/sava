package software.sava.core.programs;

import java.util.Arrays;

record DiscriminatorRecord(byte[] data) implements Discriminator {

  @Override
  public byte[] data() {
    return Arrays.copyOf(data, data.length);
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