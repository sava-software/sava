package software.sava.rpc.json.http.request;

import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.JsonIterator;

public enum RpcEncoding {

  @Deprecated
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
