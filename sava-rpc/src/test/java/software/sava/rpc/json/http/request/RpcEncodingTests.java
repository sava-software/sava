package software.sava.rpc.json.http.request;

import org.junit.jupiter.api.Test;
import systems.comodal.jsoniter.JsonIterator;

import static org.junit.jupiter.api.Assertions.*;

final class RpcEncodingTests {

  @Test
  void supportedRequestEncodingsRetainTheirWireNames() {
    assertEquals("base64", RpcEncoding.base64.value());
    assertEquals("base64+zstd", RpcEncoding.base64_zstd.value());
    assertSame(RpcEncoding.base64, RpcEncoding.parseEncoding(JsonIterator.parse("\"base64\"")));
    assertSame(RpcEncoding.base64_zstd, RpcEncoding.parseEncoding(JsonIterator.parse("\"base64+zstd\"")));
  }

  @Test
  void unsupportedRequestEncodingsReturnNullAndConsumeTheirValue() {
    for (final var name : new String[]{"base58", "jsonParsed", "BASE64", "base64+zstdx", "base64+gzip", ""}) {
      final var ji = JsonIterator.parse("{\"encoding\":\"" + name + "\",\"after\":37}");
      ji.skipUntil("encoding");
      assertNull(RpcEncoding.parseEncoding(ji), name);
      assertEquals(37, ji.skipUntil("after").readInt(), name);
    }
  }
}
