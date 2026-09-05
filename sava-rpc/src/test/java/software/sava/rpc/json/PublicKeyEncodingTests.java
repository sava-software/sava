package software.sava.rpc.json;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import java.util.HexFormat;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.*;

final class PublicKeyEncodingTests {

  @Test
  void integerArrayPreservesPublicKeyBytesAndTheOuterArrayCursor() {
    // RFC 8032 section 7.1, test 1: a fixed public key with unsigned bytes above 127.
    final byte[] expected = HexFormat.of().parseHex(
        "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    final var keyArray = new StringJoiner(",", "[", "]");
    for (final byte value : expected) {
      keyArray.add(Integer.toString(Byte.toUnsignedInt(value)));
    }
    final var ji = JsonIterator.parse("[" + keyArray + ",73]");
    assertTrue(ji.readArray());
    final var key = PublicKeyEncoding.parseIntArrayEncoded(ji);
    assertNotNull(key);
    assertArrayEquals(expected, key.copyByteArray());
    assertTrue(ji.readArray());
    assertEquals(73, ji.readInt());
    assertFalse(ji.readArray());
  }
}
