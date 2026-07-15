package software.sava.core.encoding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CompactU16EncodingTest {

  @Test
  void testRoundTripAllValues() {
    // every representable value through every entry point, at an offset
    final byte[] buf = new byte[5];
    for (int len = 0; len <= 0xffff; ++len) {
      final byte[] encoded = CompactU16Encoding.encodeLength(len);
      assertEquals(encoded.length, CompactU16Encoding.getByteLen(len));
      final int written = CompactU16Encoding.encodeLength(buf, 1, len);
      assertEquals(encoded.length, written);
      for (int i = 0; i < written; ++i) {
        assertEquals(encoded[i], buf[1 + i]);
      }
      assertEquals(len, CompactU16Encoding.decode(buf, 1));
      assertEquals(written, CompactU16Encoding.getByteLen(buf, 1));
    }
    assertThrows(IllegalArgumentException.class, () -> CompactU16Encoding.encodeLength(buf, 1, 0xffff + 1));
    assertThrows(IllegalArgumentException.class, () -> CompactU16Encoding.getByteLen(0xffff + 1));
  }

  @Test
  void testAgaveVectors() {
    // https://github.com/anza-xyz/solana-sdk short-vec/src/lib.rs test_short_vec_encode_decode
    final int[][] vectors = {
        {0x0000, 0x00},
        {0x007f, 0x7f},
        {0x0080, 0x80, 0x01},
        {0x00ff, 0xff, 0x01},
        {0x0100, 0x80, 0x02},
        {0x07ff, 0xff, 0x0f},
        {0x3fff, 0xff, 0x7f},
        {0x4000, 0x80, 0x80, 0x01},
        {0x7fff, 0xff, 0xff, 0x01},
        {0xffff, 0xff, 0xff, 0x03}
    };
    for (final int[] vector : vectors) {
      final byte[] encoded = new byte[vector.length - 1];
      for (int i = 0; i < encoded.length; ++i) {
        encoded[i] = (byte) vector[i + 1];
      }
      assertArrayEquals(encoded, CompactU16Encoding.encodeLength(vector[0]), "encode " + vector[0]);
      assertEquals(vector[0], CompactU16Encoding.decode(encoded, 0), "decode " + vector[0]);
    }
  }

  @Test
  void testSignedByte() {
    assertTrue(CompactU16Encoding.signedByte(0x80));
    assertTrue(CompactU16Encoding.signedByte(0xFF));
    assertFalse(CompactU16Encoding.signedByte(0x7F));
    assertFalse(CompactU16Encoding.signedByte(0));
  }

  @Test
  public void encodeLength() {
    assertArrayEquals(new byte[]{0} /* [0] */, CompactU16Encoding.encodeLength(0));
    assertArrayEquals(new byte[]{1} /* [1] */, CompactU16Encoding.encodeLength(1));
    assertArrayEquals(new byte[]{5} /* [5] */, CompactU16Encoding.encodeLength(5));
    assertArrayEquals(new byte[]{Byte.MAX_VALUE} /* [0x7f] */, CompactU16Encoding.encodeLength(127)); // 0x7f
    assertArrayEquals(new byte[]{Byte.MIN_VALUE, 1}/* [0x80, 0x01] */, CompactU16Encoding.encodeLength(128)); // 0x80
    assertArrayEquals(new byte[]{-1, 1} /* [0xff, 0x01] */, CompactU16Encoding.encodeLength(255)); // 0xff
    assertArrayEquals(new byte[]{Byte.MIN_VALUE, 2} /* [0x80, 0x02] */, CompactU16Encoding.encodeLength(256)); // 0x100
    assertArrayEquals(new byte[]{-1, Byte.MAX_VALUE} /* [0xff, 0xff, 0x01] */, CompactU16Encoding.encodeLength(16_383)); // 0x3fff
    assertArrayEquals(new byte[]{Byte.MIN_VALUE, Byte.MIN_VALUE, 1} /* [0xff, 0xff, 0x01] */, CompactU16Encoding.encodeLength(16_384)); // 0x4000
    assertArrayEquals(new byte[]{-1, -1, 1} /* [0xff, 0xff, 0x01] */, CompactU16Encoding.encodeLength(32767)); // 0x7fff
    assertArrayEquals(new byte[]{-1, -1, 3} /* [0xff, 0xff, 0x03] */, CompactU16Encoding.encodeLength(0xffff));
    // bits above 15 are not representable: 65_536 previously encoded to the same bytes as 0
    assertThrows(IllegalArgumentException.class, () -> CompactU16Encoding.encodeLength(0xffff + 1));
    assertThrows(IllegalArgumentException.class, () -> CompactU16Encoding.encodeLength(0x3ffff));
  }
}
