package software.sava.rpc.json.http.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

/// First-party defensive fuzzing for response-body decoding. The bytes returned by an
/// RPC provider are untrusted, so this harness drives [JsonHttpClient#readBody] through
/// both supported body representations with valid, fragmented, and deliberately broken
/// gzip streams. Generated valid streams must recover the original bytes; controlled
/// corruptions must retain the public `UncheckedIOException` failure contract.
///
/// Input layout: bytes 0..4 select the mode, Content-Encoding shape, Content-Length
/// shape, stream chunk size, and concatenated-member split. The remaining bytes are the
/// raw payload, capped at [#MAX_PAYLOAD_LENGTH] even when this entry point is called
/// directly. Modes are plain, gzip, concatenated gzip, bad gzip magic, bad gzip CRC, and
/// truncated gzip trailer. Compression is generated here so malformed cases cannot
/// expand an attacker-controlled compressed stream without the payload bound.
///
/// The stream model is deliberately finite and in-memory. It varies short reads and
/// `available()` accurately reports the remaining bytes, which lets the JDK gzip reader
/// discover a following concatenated member without introducing blocking or timing into
/// the campaign.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
public final class JsonHttpClientBodyFuzz {

  static final int PREFIX_LENGTH = 5;
  static final int MAX_PAYLOAD_LENGTH = 8_192;

  private static final int MODE_COUNT = 6;
  private static final int[] STREAM_CHUNK_SIZES = {1, 2, 7, 64, 4_096, Integer.MAX_VALUE};

  public static void fuzzerTestOneInput(final byte[] data) {
    if (data.length < PREFIX_LENGTH) {
      return;
    }

    final int mode = unsigned(data[0]) % MODE_COUNT;
    final byte[] payload = Arrays.copyOfRange(
        data,
        PREFIX_LENGTH,
        Math.min(data.length, PREFIX_LENGTH + MAX_PAYLOAD_LENGTH)
    );
    final byte[] wireBody = switch (mode) {
      case 0 -> payload.clone();
      case 1 -> gzip(payload);
      case 2 -> concatenatedGzip(payload, data[4]);
      case 3 -> withBadMagic(gzip(payload));
      case 4 -> withBadCrc(gzip(payload));
      case 5 -> withTruncatedTrailer(gzip(payload));
      default -> throw new AssertionError("unreachable mode: " + mode);
    };
    final String[] headers = headers(mode, data[1], data[2], wireBody.length);
    final int streamChunkSize = STREAM_CHUNK_SIZES[unsigned(data[3]) % STREAM_CHUNK_SIZES.length];

    check(StubHttpResponse.of(wireBody, headers), payload, mode, "byte[]", wireBody);
    try (final var stream = new FragmentedInputStream(wireBody, streamChunkSize)) {
      check(StubHttpResponse.of(stream, headers), payload, mode, "stream", null);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static int unsigned(final byte value) {
    return value & 0xFF;
  }

  private static byte[] gzip(final byte[] raw) {
    final var out = new ByteArrayOutputStream();
    try (final var gzip = new GZIPOutputStream(out)) {
      gzip.write(raw);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  private static byte[] concatenatedGzip(final byte[] payload, final byte selector) {
    final int selected = unsigned(selector);
    final int split;
    if (payload.length == 0 || selected == 0) {
      split = 0;
    } else if (selected == 255) {
      split = payload.length;
    } else {
      split = 1 + (int) ((long) (selected - 1) * (payload.length - 1) / 254);
    }
    final byte[] first = gzip(Arrays.copyOfRange(payload, 0, split));
    final byte[] second = gzip(Arrays.copyOfRange(payload, split, payload.length));
    final byte[] concatenated = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, concatenated, first.length, second.length);
    return concatenated;
  }

  private static byte[] withBadMagic(final byte[] gzip) {
    final byte[] corrupted = gzip.clone();
    corrupted[0] ^= 1;
    return corrupted;
  }

  private static byte[] withBadCrc(final byte[] gzip) {
    final byte[] corrupted = gzip.clone();
    corrupted[corrupted.length - 8] ^= 1;
    return corrupted;
  }

  private static byte[] withTruncatedTrailer(final byte[] gzip) {
    return Arrays.copyOf(gzip, gzip.length - 1);
  }

  private static String[] headers(final int mode,
                                  final byte encodingSelector,
                                  final byte contentLengthSelector,
                                  final int wireLength) {
    final List<String> headers = new ArrayList<>(6);
    if (mode == 0) {
      switch (unsigned(encodingSelector) % 4) {
        case 1 -> addHeader(headers, "Content-Encoding", "identity");
        case 2 -> addHeader(headers, "content-encoding", "deflate");
        case 3 -> addHeader(headers, "CONTENT-ENCODING", "br");
        default -> {
        }
      }
    } else {
      switch (unsigned(encodingSelector) % 4) {
        case 0 -> addHeader(headers, "Content-Encoding", "gzip");
        case 1 -> addHeader(headers, "content-encoding", "GZIP");
        case 2 -> addHeader(headers, "CONTENT-ENCODING", "GzIp");
        case 3 -> {
          addHeader(headers, "CoNtEnT-EnCoDiNg", "IdEnTiTy");
          addHeader(headers, "CoNtEnT-EnCoDiNg", "gZiP");
        }
        default -> throw new AssertionError("unreachable encoding shape");
      }
    }

    final String contentLength = switch (unsigned(contentLengthSelector) % 14) {
      case 0 -> null;
      case 1 -> Integer.toString(wireLength);
      case 2 -> "0";
      case 3 -> "-1";
      case 4 -> Long.toString(Long.MAX_VALUE);
      case 5 -> Long.toString(Long.MIN_VALUE);
      case 6 -> "9223372036854775808";
      case 7 -> "not-a-number";
      case 8 -> "4095";
      case 9 -> "4096";
      case 10 -> "4097";
      case 11 -> "1048575";
      case 12 -> "1048576";
      case 13 -> "1048577";
      default -> throw new AssertionError("unreachable Content-Length shape");
    };
    if (contentLength != null) {
      addHeader(headers, "Content-Length", contentLength);
    }
    return headers.toArray(String[]::new);
  }

  private static void addHeader(final List<String> headers, final String name, final String value) {
    headers.add(name);
    headers.add(value);
  }

  private static void check(final StubHttpResponse<?> response,
                            final byte[] payload,
                            final int mode,
                            final String route,
                            final byte[] expectedIdentity) {
    final byte[] actual;
    try {
      actual = JsonHttpClient.readBody(response);
    } catch (final UncheckedIOException malformed) {
      if (mode >= 3) {
        return;
      }
      throw new AssertionError(route + " route rejected valid body in mode " + mode, malformed);
    } catch (final RuntimeException unexpected) {
      throw new AssertionError(route + " route used the wrong failure type in mode " + mode, unexpected);
    }
    if (mode >= 3) {
      throw new AssertionError(route + " route accepted malformed gzip in mode " + mode);
    }
    if (!Arrays.equals(payload, actual)) {
      throw new AssertionError(route + " route returned the wrong body in mode " + mode);
    }
    if (mode == 0 && expectedIdentity != null && actual != expectedIdentity) {
      throw new AssertionError("plain byte[] body was copied");
    }
  }

  private static final class FragmentedInputStream extends InputStream {

    private final ByteArrayInputStream delegate;
    private final int maxChunkSize;

    private FragmentedInputStream(final byte[] bytes, final int maxChunkSize) {
      this.delegate = new ByteArrayInputStream(bytes);
      this.maxChunkSize = maxChunkSize;
    }

    @Override
    public int read() {
      return delegate.read();
    }

    @Override
    public int read(final byte[] bytes, final int offset, final int length) {
      Objects.checkFromIndexSize(offset, length, bytes.length);
      if (length == 0) {
        return 0;
      }
      return delegate.read(bytes, offset, Math.min(length, maxChunkSize));
    }

    @Override
    public int available() {
      return delegate.available();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }

  private JsonHttpClientBodyFuzz() {
  }
}
