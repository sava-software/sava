package software.sava.rpc.json.http.request;

import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.JsonIterator;

/// Account-data encodings sava can request from the node.
///
/// `jsonParsed` is absent on purpose: sava parses data client side into typed layouts.
/// Program-specific parsers live in the sibling `idl-clients` project.
public enum RpcEncoding {

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
    } else {
      return null;
    }
  };

  /// Parses a supported request encoding, or returns null for other wire names, including base58.
  public static RpcEncoding parseEncoding(final JsonIterator ji) {
    return ji.applyChars(PARSER);
  }
}
