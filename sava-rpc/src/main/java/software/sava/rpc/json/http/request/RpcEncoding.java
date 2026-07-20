package software.sava.rpc.json.http.request;

import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.JsonIterator;

/// Wire encodings for account and transaction data.
///
/// `jsonParsed` is deliberately absent and should not be added. sava's purpose is
/// to parse account and transaction data client side — asking the node to do it
/// gives up the typed layouts this library exists to provide, costs more bytes on
/// the wire, and only covers the programs the node happens to know. Generated
/// serialization helpers for specific programs live in the sibling `idl-clients`
/// project.
public enum RpcEncoding {

  @Deprecated(forRemoval = true)
  base58,
  base64,
  base64_zstd("base64+zstd");

  private final String value;

  RpcEncoding() {
    this.value = name();
  }

  RpcEncoding(final String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  private static final CharBufferFunction<RpcEncoding> PARSER = (buf, offset, len) -> {
    if (JsonIterator.fieldEquals("base64", buf, offset, len)) {
      return base64;
    } else if (JsonIterator.fieldEquals("base64+zstd", buf, offset, len)) {
      return base64_zstd;
    } else if (JsonIterator.fieldEquals("base58", buf, offset, len)) {
      return base58;
    } else {
      return null;
    }
  };

  public static RpcEncoding parseEncoding(final JsonIterator ji) {
    return ji.applyChars(PARSER);
  }
}
