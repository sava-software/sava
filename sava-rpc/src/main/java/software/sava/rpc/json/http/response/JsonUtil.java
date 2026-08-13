package software.sava.rpc.json.http.response;

import software.sava.core.encoding.Base58;
import software.sava.rpc.json.http.request.RpcEncoding;
import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.JsonException;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import static java.util.Objects.requireNonNull;

public final class JsonUtil {

  // solana_system_interface::MAX_PERMITTED_DATA_LENGTH. Besides enforcing the
  // protocol's account limit, this bounds expansion of untrusted zstd frames.
  private static final int MAX_ACCOUNT_DATA_LENGTH = 10 * 1024 * 1024;

  private static final System.Logger logger = System.getLogger(JsonUtil.class.getName());

  public static final CharBufferFunction<byte[]> DECODE_BASE58 = Base58::decode;

  public static byte[] parseEncodedData(final JsonIterator ji) {
    final var next = ji.whatIsNext();
    return parseEncodedData(ji, next, null);
  }

  static byte[] parseEncodedData(final JsonIterator ji,
                                 final ZstdDecompressor zstdDecompressor) {
    final var next = ji.whatIsNext();
    return parseEncodedData(ji, next, zstdDecompressor);
  }

  public static byte[] parseEncodedData(final JsonIterator ji, final ValueType next) {
    return parseEncodedData(ji, next, null);
  }

  static byte[] parseEncodedData(final JsonIterator ji,
                                 final ValueType next,
                                 final ZstdDecompressor zstdDecompressor) {
    if (next == ValueType.ARRAY) {
      if (ji.openArray().readNull()) {
        ji.skipRestOfArray();
        return new byte[0];
      }
      final int mark = ji.mark();
      ji.skip();
      if (!ji.readArray()) {
        throw new JsonException("Encoded account data array is missing its encoding");
      }
      final var encoding = RpcEncoding.parseEncoding(ji);
      final int mark2 = ji.mark();
      ji.reset(mark);
      final byte[] decodedData = switch (encoding) {
        case base58 -> ji.applyChars(DECODE_BASE58);
        case base64 -> ji.decodeBase64String();
        case base64_zstd -> decompressZstd(ji.decodeBase64String(), zstdDecompressor);
        case null -> new byte[0];
      };
      ji.reset(mark2).skipRestOfArray();
      return decodedData;
    } else if (next == ValueType.STRING) {
      return ji.decodeBase64String();
    } else {
      logger.log(System.Logger.Level.WARNING, "Unsupported {0} encoded data {1}", next, ji.currentBuffer());
      ji.skip();
      return new byte[0];
    }
  }

  private static byte[] decompressZstd(final byte[] compressedData,
                                       final ZstdDecompressor zstdDecompressor) {
    if (zstdDecompressor == null) {
      throw new IllegalStateException("No ZstdDecompressor was provided for base64+zstd account data");
    }
    try (final var compressed = new ByteArrayInputStream(compressedData);
         final var in = requireNonNull(
             zstdDecompressor.decompress(compressed),
             "ZstdDecompressor returned null"
         );
         final var out = new ByteArrayOutputStream()) {
      final byte[] buffer = new byte[8192];
      for (int read; (read = in.read(buffer)) != -1; ) {
        if (read > MAX_ACCOUNT_DATA_LENGTH - out.size()) {
          throw new IOException("base64+zstd account data exceeds Solana's 10 MiB limit");
        }
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    } catch (final RuntimeException e) {
      throw new UncheckedIOException(
          "Failed to decompress base64+zstd account data",
          new IOException(e.getMessage(), e)
      );
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to decompress base64+zstd account data", e);
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
