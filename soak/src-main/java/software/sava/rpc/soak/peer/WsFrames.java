package software.sava.rpc.soak.peer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/// RFC 6455 framing, by hand, because the JDK ships no server-side websocket and
/// `com.sun.net.httpserver` cannot upgrade.
///
/// Scope is deliberately small and matches what sava's client actually speaks: HTTP/1.1 upgrade,
/// text frames, continuation frames, ping/pong, close. No extensions, no `permessage-deflate`, no
/// subprotocols, no binary payloads.
///
/// Two rules are enforced rather than assumed, because the peer's job is to be *correct* about the
/// protocol so that any misbehaviour the client shows is the client's:
/// - a client frame must be masked (RFC 6455 §5.1); an unmasked one is a protocol error the peer
///   closes on, rather than something it quietly tolerates and then blames the client for;
/// - server frames are never masked.
///
/// The 16 MiB payload cap is the peer's own limit, distinct from the engine's `maxMessageLength`:
/// a client frame above it draws close 1009 so a hostile-sized frame cannot make the peer allocate
/// without bound while it is busy proving the client does not.
final class WsFrames {

  static final int OP_CONTINUATION = 0x0;
  static final int OP_TEXT = 0x1;
  static final int OP_BINARY = 0x2;
  static final int OP_CLOSE = 0x8;
  static final int OP_PING = 0x9;
  static final int OP_PONG = 0xA;

  static final int MAX_PAYLOAD = 16 * 1024 * 1024;
  static final int CLOSE_NORMAL = 1000;
  static final int CLOSE_PROTOCOL_ERROR = 1002;
  static final int CLOSE_TOO_BIG = 1009;
  static final int CLOSE_INTERNAL = 1011;

  /// The RFC 6455 GUID concatenated with `Sec-WebSocket-Key` before hashing.
  private static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

  private WsFrames() {
  }

  /// A framing violation, carrying the close status the peer must answer with.
  static final class ProtocolException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int closeCode;

    ProtocolException(final int closeCode, final String message) {
      super(message);
      this.closeCode = closeCode;
    }

    int closeCode() {
      return closeCode;
    }
  }

  /// One decoded frame; `payload` is already unmasked.
  record Frame(boolean fin, int rsv, int opcode, byte[] payload) {

    boolean control() {
      return opcode >= OP_CLOSE;
    }

    String text() {
      return new String(payload, StandardCharsets.UTF_8);
    }
  }

  // --- handshake --------------------------------------------------------------------------------

  /// `base64(SHA-1(key + GUID))`, the value a correct `Sec-WebSocket-Accept` carries.
  static String acceptKey(final String secWebSocketKey) {
    try {
      final var sha1 = MessageDigest.getInstance("SHA-1");
      return Base64.getEncoder().encodeToString(
          sha1.digest((secWebSocketKey + ACCEPT_GUID).getBytes(StandardCharsets.UTF_8)));
    } catch (final NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-1 is required by every JDK", ex);
    }
  }

  // --- reading ----------------------------------------------------------------------------------

  /// Reads one frame, or null at a clean EOF. Client frames must be masked; the mask is applied in
  /// place so a 16 MiB frame costs one array, not two.
  static Frame read(final InputStream in) throws IOException {
    final int b0 = in.read();
    if (b0 < 0) {
      return null;
    }
    final int b1 = readByte(in);
    final boolean fin = (b0 & 0x80) != 0;
    final int rsv = (b0 >>> 4) & 0x07;
    final int opcode = b0 & 0x0F;
    if (rsv != 0) {
      throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "RSV bits set with no extension negotiated: " + rsv);
    }
    final boolean masked = (b1 & 0x80) != 0;
    long length = b1 & 0x7F;
    if (length == 126) {
      length = (readByte(in) << 8) | readByte(in);
    } else if (length == 127) {
      length = 0;
      for (int i = 0; i < 8; ++i) {
        length = (length << 8) | readByte(in);
      }
    }
    if (length < 0 || length > MAX_PAYLOAD) {
      throw new ProtocolException(CLOSE_TOO_BIG, "frame payload " + length + " exceeds the peer cap " + MAX_PAYLOAD);
    }
    if (opcode >= OP_CLOSE) {
      if (!fin) {
        throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "fragmented control frame, opcode " + opcode);
      }
      if (length > 125) {
        throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "control frame payload " + length + " exceeds 125");
      }
    }
    if (!masked) {
      throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "unmasked client frame, opcode " + opcode);
    }
    final byte[] mask = new byte[4];
    readFully(in, mask, 0, 4);
    final byte[] payload = new byte[(int) length];
    readFully(in, payload, 0, payload.length);
    for (int i = 0; i < payload.length; ++i) {
      payload[i] ^= mask[i & 3];
    }
    return new Frame(fin, rsv, opcode, payload);
  }

  private static int readByte(final InputStream in) throws IOException {
    final int b = in.read();
    if (b < 0) {
      throw new java.io.EOFException("truncated websocket frame header");
    }
    return b;
  }

  private static void readFully(final InputStream in, final byte[] buffer, final int offset, final int length)
      throws IOException {
    int read = 0;
    while (read < length) {
      final int n = in.read(buffer, offset + read, length - read);
      if (n < 0) {
        throw new java.io.EOFException("truncated websocket frame payload");
      }
      read += n;
    }
  }

  // --- writing ----------------------------------------------------------------------------------

  /// One unmasked server frame. Callers already hold the connection's write lock.
  static void write(final OutputStream out,
                    final boolean fin,
                    final int opcode,
                    final byte[] payload,
                    final int offset,
                    final int length) throws IOException {
    final var header = new byte[10];
    int h = 0;
    header[h++] = (byte) ((fin ? 0x80 : 0x00) | opcode);
    if (length < 126) {
      header[h++] = (byte) length;
    } else if (length < 65536) {
      header[h++] = 126;
      header[h++] = (byte) (length >>> 8);
      header[h++] = (byte) length;
    } else {
      header[h++] = 127;
      for (int shift = 56; shift >= 0; shift -= 8) {
        header[h++] = (byte) (((long) length) >>> shift);
      }
    }
    out.write(header, 0, h);
    if (length > 0) {
      out.write(payload, offset, length);
    }
    out.flush();
  }

  static void writeText(final OutputStream out, final byte[] utf8) throws IOException {
    write(out, true, OP_TEXT, utf8, 0, utf8.length);
  }

  /// A TEXT frame with FIN=0 and **no** continuation: the `DANGLING_FRAGMENT` fault, shaped after
  /// `sava-rpc/src/test/resources/fuzz/ws/dangling_fragment`. The next message follows normally, so
  /// the property under test is that the engine's reassembly buffer was not left corrupted.
  static void writeDanglingFragment(final OutputStream out, final byte[] utf8) throws IOException {
    write(out, false, OP_TEXT, utf8, 0, utf8.length);
  }

  /// Splits one logical message into `[TEXT, CONT..., CONT(FIN)]` at the given split points.
  ///
  /// @param gapMillis   milliseconds between frames (`SLOW_WRITE`), 0 for back to back.
  /// @param flipPayload when >= 0, the index of the fragment whose first payload byte is flipped —
  ///                    the `FLIP_CONTINUATION_BYTE` control, which corrupts *content* while the
  ///                    framing stays valid, so a client that reassembles correctly still fails the
  ///                    checksum.
  static void writeFragments(final OutputStream out,
                             final byte[] utf8,
                             final int[] splits,
                             final long gapMillis,
                             final int flipPayload) throws IOException {
    int start = 0;
    for (int i = 0; i <= splits.length; ++i) {
      final int end = i == splits.length ? utf8.length : Math.min(utf8.length, splits[i]);
      if (end <= start && i < splits.length) {
        continue;
      }
      final boolean last = i == splits.length;
      if (i > 0 && gapMillis > 0) {
        pause(gapMillis);
      }
      if (flipPayload == i && end > start) {
        utf8[start] = (byte) (utf8[start] ^ 0x20);
      }
      write(out, last, i == 0 ? OP_TEXT : OP_CONTINUATION, utf8, start, end - start);
      start = end;
    }
  }

  static void writePong(final OutputStream out, final byte[] payload) throws IOException {
    write(out, true, OP_PONG, payload, 0, Math.min(payload.length, 125));
  }

  static void writePing(final OutputStream out, final byte[] payload) throws IOException {
    write(out, true, OP_PING, payload, 0, Math.min(payload.length, 125));
  }

  static void writeClose(final OutputStream out, final int code, final String reason) throws IOException {
    final byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
    final int length = Math.min(123, reasonBytes.length);
    final byte[] payload = new byte[2 + length];
    payload[0] = (byte) (code >>> 8);
    payload[1] = (byte) code;
    System.arraycopy(reasonBytes, 0, payload, 2, length);
    write(out, true, OP_CLOSE, payload, 0, payload.length);
  }

  /// Seeded split points for a fragmented message: `fragments - 1` strictly increasing offsets.
  /// Seeded rather than even, so reassembly meets ragged boundaries (including ones that land
  /// inside a multi-byte UTF-8 sequence's neighbourhood) the same way on every replay.
  static int[] splitPoints(final long seed, final int length, final int fragments) {
    final int count = Math.max(0, Math.min(fragments, length) - 1);
    if (count == 0) {
      return new int[0];
    }
    final var random = new SplittableRandom(seed);
    final var points = new int[count];
    final int band = Math.max(1, length / (count + 1));
    int at = 0;
    for (int i = 0; i < count; ++i) {
      at += 1 + random.nextInt(Math.max(1, band));
      if (at >= length) {
        at = length - 1;
      }
      points[i] = at;
    }
    return points;
  }

  /// A monotonic pause. Deliberately not `Thread.sleep`: the harness's own rule is that nothing
  /// paces work by sleeping, and a park to a deadline cannot drift with spurious wakeups.
  static void pause(final long millis) {
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    for (long remaining = deadline - System.nanoTime(); remaining > 0; remaining = deadline - System.nanoTime()) {
      LockSupport.parkNanos(remaining);
      if (Thread.currentThread().isInterrupted()) {
        return;
      }
    }
  }
}
