package software.sava.rpc.json.http.response;

import software.sava.core.encoding.Base58;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.Base64;

public final class JsonUtil {

  public static byte[] parseEncodedData(final JsonIterator ji) {
    final var next = ji.whatIsNext();
    return parseEncodedData(ji, next);
  }

  public static byte[] parseEncodedData(final JsonIterator ji, final ValueType next) {
    if (next == ValueType.ARRAY) {
      final var data = ji.openArray().readString();
      if (data == null || data.isBlank()) {
        ji.skipRestOfArray();
        return new byte[0];
      } else if (ji.readArray()) {
        final var encoding = ji.readString();
        final byte[] decodedData;
        if (encoding.equalsIgnoreCase("base64")) {
          decodedData = Base64.getDecoder().decode(data);
        } else if (encoding.equalsIgnoreCase("base58")) {
          decodedData = Base58.decode(data);
        } else {
          decodedData = new byte[0];
        }
        ji.skipRestOfArray();
        return decodedData;
      } else {
        return Base64.getDecoder().decode(data);
      }
    } else if (next == ValueType.STRING) {
      return ji.decodeBase64String();
    } else {
      System.out.println("Unhandled parsed data: " + ji.currentBuffer());
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
