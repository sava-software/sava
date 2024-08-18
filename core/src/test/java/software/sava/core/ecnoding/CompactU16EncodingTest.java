package software.sava.core.ecnoding;

import org.junit.jupiter.api.Test;
import software.sava.core.encoding.CompactU16Encoding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CompactU16EncodingTest {

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
    assertArrayEquals(new byte[]{-1, -1, 3} /* [0xff, 0xff, 0x01] */, CompactU16Encoding.encodeLength(0x3ffff));
    assertThrows(IllegalArgumentException.class, () -> CompactU16Encoding.encodeLength(0x3ffff + 1));
  }
}
