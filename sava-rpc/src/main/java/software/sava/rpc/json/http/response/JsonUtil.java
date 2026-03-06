package software.sava.rpc.json.http.response;

import software.sava.core.encoding.Base58;
import software.sava.rpc.json.http.request.RpcEncoding;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

public final class JsonUtil {

  private static final System.Logger logger = System.getLogger(JsonUtil.class.getName());

  public static byte[] parseEncodedData(final JsonIterator ji) {
    final var next = ji.whatIsNext();
    return parseEncodedData(ji, next);
  }

  public static byte[] parseEncodedData(final JsonIterator ji, final ValueType next) {
    if (next == ValueType.ARRAY) {
      if (ji.openArray().readNull()) {
        ji.skipRestOfArray();
        return new byte[0];
      }
      final int mark = ji.mark();
      ji.skip();
      if (ji.readArray()) {
        final var encoding = RpcEncoding.parseEncoding(ji);
        final int mark2 = ji.mark();
        ji.reset(mark);
        final byte[] decodedData = switch (encoding) {
          case base58 -> Base58.decode(ji.readString());
          case base64, base64_zstd -> ji.decodeBase64String();
          case null -> new byte[0];
        };
        ji.reset(mark2).skipRestOfArray();
        return decodedData;
      } else {
        return ji.decodeBase64String();
      }
    } else if (next == ValueType.STRING) {
      return ji.decodeBase64String();
    } else {
      logger.log(System.Logger.Level.WARNING, "Unsupported {0} encoded data {1}", next, ji.currentBuffer());
      ji.skip();
      return new byte[0];
    }
  }

  public static String toJsonIntArray(final byte[] data) {
    if (data == null) {
      return "null";
    } else if (data.length == 0) {
      return "[]";
    } else {
      final var builder = new StringBuilder((data.length << 2) + 2);
      builder.append('[');
      for (int i = 0; ; ) {
        final byte b = data[i];
        builder.append(b & 0xFF);
        if (++i == data.length) {
          break;
        } else {
          builder.append(',');
        }
      }
      return builder.append(']').toString();
    }
  }

  private JsonUtil() {
  }
}
