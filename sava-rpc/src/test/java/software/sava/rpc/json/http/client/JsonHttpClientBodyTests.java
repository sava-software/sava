package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/// Response body decoding is the first thing that touches bytes off the wire, and
/// `sava-rpc/build.gradle.kts` states the threat model plainly: "a compromised or
/// buggy provider". These drive [JsonHttpClient#readBody] directly with hostile
/// and malformed responses rather than through a live server.
final class JsonHttpClientBodyTests {

  private static byte[] ascii(final String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] gzip(final byte[] raw) {
    final var out = new ByteArrayOutputStream();
    try (final var gzipOut = new GZIPOutputStream(out)) {
      gzipOut.write(raw);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  @Test
  void plainByteArrayBodyIsPassedThroughUntouched() {
    final byte[] body = ascii("{\"jsonrpc\":\"2.0\"}");
    final byte[] read = JsonHttpClient.readBody(StubHttpResponse.of(body));
    assertSame(body, read, "an unencoded body should not be copied");
  }

  @Test
  void nullBodyReadsAsNull() {
    assertNull(JsonHttpClient.readBody(StubHttpResponse.of(null)));
  }

  @Test
  void emptyBodyShortCircuitsBeforeTheGzipPath() {
    final byte[] empty = new byte[0];
    // an empty body with a gzip header is not a valid gzip stream; the length
    // check has to come first or this would throw
    assertSame(empty, JsonHttpClient.readBody(StubHttpResponse.of(empty, "Content-Encoding", "gzip")));
  }

  @Test
  void gzippedByteArrayBodyIsInflated() {
    final byte[] raw = ascii("{\"result\":[1,2,3]}");
    final var response = StubHttpResponse.of(gzip(raw), "Content-Encoding", "gzip");
    assertArrayEquals(raw, JsonHttpClient.readBody(response));
  }

  /// Header values are matched with equalsIgnoreCase, and HTTP header names are
  /// case insensitive too.
  @Test
  void gzipDetectionIsCaseInsensitive() {
    final byte[] raw = ascii("{\"ok\":true}");
    for (final String name : new String[]{"Content-Encoding", "content-encoding", "CONTENT-ENCODING"}) {
      for (final String value : new String[]{"gzip", "GZIP", "GzIp"}) {
        assertArrayEquals(raw, JsonHttpClient.readBody(StubHttpResponse.of(gzip(raw), name, value)),
            name + ": " + value
        );
      }
    }
  }

  /// A proxy can append encodings; gzip anywhere in the list must be honoured.
  @Test
  void gzipIsFoundAmongMultipleEncodingHeaders() {
    final byte[] raw = ascii("{\"ok\":true}");
    assertArrayEquals(raw, JsonHttpClient.readBody(
        StubHttpResponse.of(gzip(raw), "Content-Encoding", "identity", "Content-Encoding", "gzip"))
    );
  }

  @Test
  void nonGzipEncodingsLeaveTheBodyAlone() {
    final byte[] body = ascii("{\"ok\":true}");
    for (final String encoding : new String[]{"identity", "deflate", "br", "compress", ""}) {
      assertSame(body, JsonHttpClient.readBody(StubHttpResponse.of(body, "Content-Encoding", encoding)), encoding);
    }
  }

  @Test
  void inputStreamBodyIsReadFully() {
    final byte[] raw = ascii("{\"result\":\"streamed\"}");
    final InputStream stream = new ByteArrayInputStream(raw);
    assertArrayEquals(raw, JsonHttpClient.readBody(StubHttpResponse.of(stream)));
  }

  @Test
  void gzippedInputStreamBodyIsInflated() {
    final byte[] raw = ascii("{\"result\":\"streamed and squeezed\"}");
    final InputStream stream = new ByteArrayInputStream(gzip(raw));
    assertArrayEquals(raw, JsonHttpClient.readBody(
        StubHttpResponse.of(stream, "Content-Encoding", "gzip"))
    );
  }

  @Test
  void nullInputStreamReadsAsNull() {
    assertNull(JsonHttpClient.readBody(StubHttpResponse.of((InputStream) null)));
  }

  /// The Solana RPC server does not send Content-Length, so the gzip path falls
  /// back to a 4096 byte buffer. That is a capacity hint only — a response an
  /// order of magnitude larger must still come back whole.
  @Test
  void largeGzippedStreamInflatesFullyWithoutContentLength() {
    final byte[] raw = new byte[1 << 20];
    new Random(42).nextBytes(raw);
    final InputStream stream = new ByteArrayInputStream(gzip(raw));
    assertArrayEquals(raw, JsonHttpClient.readBody(
        StubHttpResponse.of(stream, "Content-Encoding", "gzip"))
    );
  }

  @Test
  void contentLengthIsOnlyASizeHint() {
    final byte[] raw = ascii("{\"result\":\"a body much longer than the declared length\"}");
    final var gzipped = gzip(raw);
    // deliberately wrong, but small and positive: correctness must not depend on it
    assertArrayEquals(raw, JsonHttpClient.readBody(StubHttpResponse.of(
        new ByteArrayInputStream(gzipped), "Content-Encoding", "gzip", "Content-Length", "1"))
    );
  }

  @Test
  void unsupportedBodyTypeIsRejected() {
    final var ex = assertThrows(IllegalArgumentException.class,
        () -> JsonHttpClient.readBody(StubHttpResponse.of("a String body"))
    );
    assertTrue(ex.getMessage().contains("Unsupported response body type"), ex.getMessage());
    assertTrue(ex.getMessage().contains("String"), ex.getMessage());
  }

  @Test
  void readHttpResponseReturnsItsAlreadyReadBody() {
    final byte[] alreadyRead = ascii("{\"cached\":true}");
    // the wrapped response carries a different body, which must be ignored
    final var wrapped = new ReadHttpResponse<>(StubHttpResponse.of(ascii("ignored")), alreadyRead);
    assertSame(alreadyRead, JsonHttpClient.readBody(wrapped));
  }

  /// A provider claiming gzip and sending plaintext must fail, not be silently
  /// passed through as if it had parsed.
  @Test
  void gzipHeaderOnAPlainBodyFails() {
    final var ex = assertThrows(UncheckedIOException.class, () -> JsonHttpClient.readBody(
        StubHttpResponse.of(ascii("{\"not\":\"compressed\"}"), "Content-Encoding", "gzip"))
    );
    assertInstanceOf(java.util.zip.ZipException.class, ex.getCause());
  }

  @Test
  void truncatedGzipStreamFails() {
    final byte[] full = gzip(ascii("{\"result\":\"a body that gets cut off part way through\"}"));
    final byte[] truncated = Arrays.copyOf(full, full.length / 2);
    assertThrows(UncheckedIOException.class, () -> JsonHttpClient.readBody(
        StubHttpResponse.of(truncated, "Content-Encoding", "gzip"))
    );
    assertThrows(UncheckedIOException.class, () -> JsonHttpClient.readBody(
        StubHttpResponse.of(new ByteArrayInputStream(truncated), "Content-Encoding", "gzip"))
    );
  }

  @Test
  void garbageGzipBodyFails() {
    final byte[] garbage = new byte[512];
    new Random(7).nextBytes(garbage);
    assertThrows(UncheckedIOException.class, () -> JsonHttpClient.readBody(
        StubHttpResponse.of(garbage, "Content-Encoding", "gzip"))
    );
  }

  /// Content-Length is server controlled, unrelated to the bytes actually sent,
  /// and feeds a [java.util.zip.GZIPInputStream] buffer that is allocated eagerly.
  /// Unclamped it let a provider size a client-side allocation at will —
  /// `Content-Length: 200000000` against a 31 byte body allocated 200MB, and
  /// large enough values were an OutOfMemoryError. Values past int range narrowed
  /// to a negative or zero buffer and escaped as a raw IllegalArgumentException
  /// rather than the UncheckedIOException the rest of this path throws.
  ///
  /// Every one of these now decodes normally. The values below are safe to run
  /// precisely because they are clamped; before the fix the first two threw and
  /// anything large enough to matter could not be executed at all.
  @Test
  void hostileContentLengthIsClamped() {
    final byte[] raw = ascii("{\"ok\":true}");
    final String[] hostile = {
        "2147483648",           // 2^31, narrowed to Integer.MIN_VALUE
        "4294967296",           // 2^32, narrowed to exactly 0
        "9223372036854775807",  // Long.MAX_VALUE
        "600000000",            // in int range, but overflowed the output sizing
        "200000000",            // in int range, and used to allocate 200MB
        "-1",                   // negative
        "0",                    // zero
        "not-a-number",         // unparseable, must not throw NumberFormatException
        "",                     // empty
    };
    for (final String contentLength : hostile) {
      assertArrayEquals(raw, JsonHttpClient.readBody(StubHttpResponse.of(
              new ByteArrayInputStream(gzip(raw)),
              "Content-Encoding", "gzip", "Content-Length", contentLength
          )),
          "Content-Length: " + contentLength
      );
    }
  }

  /// A plausible Content-Length still has to produce a correct body — the clamp
  /// must not have turned the hint into a cap on what can be read.
  @Test
  void clampingDoesNotTruncateLargeBodies() {
    final byte[] raw = new byte[4 << 20];
    new Random(11).nextBytes(raw);
    final var gzipped = gzip(raw);
    // honest length, well past MAX_GZIP_BUFFER
    assertArrayEquals(raw, JsonHttpClient.readBody(StubHttpResponse.of(
            new ByteArrayInputStream(gzipped),
            "Content-Encoding", "gzip", "Content-Length", Integer.toString(gzipped.length)
        ))
    );
  }

  /// The equivalent byte[] path sizes from the array itself, so the same hostile
  /// header is harmless there. This is what the stream path should look like.
  @Test
  void hostileContentLengthIsHarmlessOnTheByteArrayPath() {
    final byte[] raw = ascii("{\"ok\":true}");
    assertArrayEquals(raw, JsonHttpClient.readBody(StubHttpResponse.of(
        gzip(raw), "Content-Encoding", "gzip", "Content-Length", "2147483648"))
    );
  }
}
