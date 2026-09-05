package software.sava.core.serial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SerializableTests {

  @Test
  void defaultWritesSerializeExactlyTheDeclaredBytes() {
    final var value = new MutableValue((byte) 0x5A);
    final byte[] destination = {9, 8, 7, 6};
    assertEquals(3, value.write(destination));
    assertArrayEquals(new byte[]{0x41, 0x5A, (byte) 0xE3, 6}, destination);

    final byte[] allocated = value.write();
    assertArrayEquals(new byte[]{0x41, 0x5A, (byte) 0xE3}, allocated);
    allocated[1] = 42;
    assertArrayEquals(new byte[]{0x41, 0x5A, (byte) 0xE3}, value.write());
  }

  @Test
  void reusableCapturesBytesAndWritesThemWithoutRevisitingTheSource() {
    final var source = new MutableValue((byte) 0x5A);
    final var reusable = source.reusable();
    assertEquals(1, source.writes);
    assertEquals(3, reusable.l());

    source.value = 0x6B;
    assertArrayEquals(new byte[]{0x41, 0x6B, (byte) 0xE3}, source.write());
    final byte[] destination = {9, 8, 7, 6, 5, 4};
    assertEquals(3, reusable.write(destination, 2));
    assertArrayEquals(new byte[]{9, 8, 0x41, 0x5A, (byte) 0xE3, 4}, destination);

    final byte[] first = reusable.write();
    assertArrayEquals(new byte[]{0x41, 0x5A, (byte) 0xE3}, first);
    first[1] = 42;
    assertArrayEquals(new byte[]{0x41, 0x5A, (byte) 0xE3}, reusable.write());
    assertEquals(2, source.writes, "reusing the captured bytes must not serialize the source again");
  }

  private static final class MutableValue implements Serializable {

    private byte value;
    private int writes;

    private MutableValue(final byte value) {
      this.value = value;
    }

    @Override
    public int l() {
      return 3;
    }

    @Override
    public int write(final byte[] data, final int offset) {
      ++writes;
      data[offset] = 0x41;
      data[offset + 1] = value;
      data[offset + 2] = (byte) 0xE3;
      return 3;
    }
  }
}
