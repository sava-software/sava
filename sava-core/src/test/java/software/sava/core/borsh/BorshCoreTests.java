package software.sava.core.borsh;

import org.junit.jupiter.api.Test;
import software.sava.core.encoding.ByteUtil;

import java.util.Arrays;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/// Exact wire-format coverage for the Borsh string and byte primitives —
/// [Borsh#len(String)], [Borsh#write(String, byte[], int)],
/// [Borsh#writeArray(byte[], byte[], int)], [Borsh#writeVector(byte[], byte[], int)], and
/// the [Borsh#writeOptional()] default; the vector and enum families live in
/// BorshPrimitiveVectorTests, BorshReferenceVectorTests, and RustEnumTests. Buffers are
/// pre-filled with a non-zero pattern so dropped writes and overwrites past the promised
/// length are both observable.
final class BorshCoreTests {

  private static byte[] dirty(final int length) {
    final byte[] data = new byte[length];
    Arrays.fill(data, (byte) 0xAA);
    return data;
  }

  private static void assertUntouched(final byte[] data, final int from, final int to) {
    for (int i = from; i < to; ++i) {
      assertEquals((byte) 0xAA, data[i], "byte " + i + " was written");
    }
  }

  @Test
  void stringLenIsPrefixPlusUtf8Bytes() {
    assertEquals(Integer.BYTES, Borsh.len(""));
    assertEquals(Integer.BYTES + 5, Borsh.len("hello"));
    // 'é' is two UTF-8 bytes, '☕' three; char count would under-size the buffer
    assertEquals(Integer.BYTES + 9, Borsh.len("Café ☕"));
    assertEquals(Integer.BYTES + 4, Borsh.len("🚀")); // 🚀, one surrogate pair
  }

  @Test
  void stringWriteMatchesTheWireFormat() {
    for (final var val : new String[]{"", "hello", "Café ☕", "🚀"}) {
      final byte[] utf8 = val.getBytes(UTF_8);
      final int offset = 3;
      final byte[] data = dirty(offset + Borsh.len(val) + 2);

      final int written = Borsh.write(val, data, offset);

      assertEquals(Borsh.len(val), written, val);
      assertEquals(utf8.length, ByteUtil.getInt32LE(data, offset), val);
      assertArrayEquals(utf8, Arrays.copyOfRange(data, offset + Integer.BYTES, offset + written), val);
      assertEquals(val, new String(data, offset + Integer.BYTES, utf8.length, UTF_8));
      assertUntouched(data, 0, offset);
      assertUntouched(data, offset + written, data.length);
    }
  }

  @Test
  void byteArrayWriteCopiesVerbatim() {
    final byte[] array = {1, 2, 3, 4, 5};
    final int offset = 2;
    final byte[] data = dirty(offset + array.length + 2);

    assertEquals(array.length, Borsh.writeArray(array, data, offset));

    assertArrayEquals(array, Arrays.copyOfRange(data, offset, offset + array.length));
    assertUntouched(data, 0, offset);
    assertUntouched(data, offset + array.length, data.length);

    assertEquals(0, Borsh.writeArray(new byte[0], data, offset));
  }

  @Test
  void byteVectorWritePrefixesTheLength() {
    final byte[] array = {9, 8, 7};
    final int offset = 1;
    final byte[] data = dirty(offset + Integer.BYTES + array.length + 2);

    final int written = Borsh.writeVector(array, data, offset);

    assertEquals(Integer.BYTES + array.length, written);
    assertEquals(array.length, ByteUtil.getInt32LE(data, offset));
    assertArrayEquals(array, Arrays.copyOfRange(data, offset + Integer.BYTES, offset + written));
    assertUntouched(data, 0, offset);
    assertUntouched(data, offset + written, data.length);

    final byte[] empty = dirty(Integer.BYTES);
    assertEquals(Integer.BYTES, Borsh.writeVector(new byte[0], empty, 0));
    assertEquals(0, ByteUtil.getInt32LE(empty, 0));
  }

  @Test
  void writeOptionalPrefixesTheSomeTag() {
    record Payload(byte value) implements Borsh {

      @Override
      public int l() {
        return 1;
      }

      @Override
      public int write(final byte[] data, final int offset) {
        data[offset] = value;
        return 1;
      }
    }
    assertArrayEquals(new byte[]{1, 7}, new Payload((byte) 7).writeOptional());
  }
}
