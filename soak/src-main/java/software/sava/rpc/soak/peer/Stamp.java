package software.sava.rpc.soak.peer;

import software.sava.core.encoding.Base58;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.SplittableRandom;

import static java.nio.charset.StandardCharsets.UTF_8;

/// Deterministic stamping shared by both peers.
///
/// Every byte either peer emits has to be a pure function of `(seed, ordinal)` so that a replay at
/// the same seed produces the same wire bytes: that is what lets the client assert *association*
/// (this response answers the request I sent) and *gaplessness* (this notification follows the one
/// before it) instead of merely asserting that nothing crashed. The harness-side `RpcOracle` and
/// `SequenceOracle` reimplement these same functions, so a change here is a change to the contract
/// in `DESIGN.md` §7 and must be made on both sides.
///
/// Nothing in here may read a clock, a `ThreadLocalRandom`, or a `HashMap` iteration order.
final class Stamp {

  /// Slot numbers are offset from a fixed base so a slot in a log is obviously synthetic and so
  /// `slot - 32` (the root of a `slotNotification`) can never go negative.
  static final long BASE_SLOT = 10_000_000L;

  private static final long FNV_OFFSET_BASIS = 0xcbf2_9ce4_8422_2325L;
  private static final long FNV_PRIME = 0x0000_0100_0000_01b3L;

  /// The single owner every synthetic account reports. Fixed base58 of 32 bytes, derived from a
  /// constant so it is stable across runs and machines without being a real program address.
  static final String OWNER = Base58.encode(sha256("sava-soak-owner".getBytes(UTF_8)));

  private Stamp() {
  }

  static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required by every JDK", ex);
    }
  }

  static byte[] sha256(final byte[] input) {
    return sha256Digest().digest(input);
  }

  static byte[] sha256(final String input) {
    return sha256(input.getBytes(UTF_8));
  }

  static String sha256Hex(final byte[] input) {
    return hex(sha256(input));
  }

  static String hex(final byte[] bytes) {
    final var sb = new StringBuilder(bytes.length << 1);
    for (final byte b : bytes) {
      sb.append(Character.forDigit((b >>> 4) & 0x0F, 16)).append(Character.forDigit(b & 0x0F, 16));
    }
    return sb.toString();
  }

  static long fnv64(final byte[] bytes) {
    return fnv64(bytes, 0, bytes.length);
  }

  static long fnv64(final byte[] bytes, final int from, final int to) {
    long hash = FNV_OFFSET_BASIS;
    for (int i = from; i < to; ++i) {
      hash ^= (bytes[i] & 0xFFL);
      hash *= FNV_PRIME;
    }
    return hash;
  }

  static long fnv64(final String string) {
    return fnv64(string.getBytes(UTF_8));
  }

  static void putLong(final byte[] out, final int offset, final long value) {
    out[offset] = (byte) (value >>> 56);
    out[offset + 1] = (byte) (value >>> 48);
    out[offset + 2] = (byte) (value >>> 40);
    out[offset + 3] = (byte) (value >>> 32);
    out[offset + 4] = (byte) (value >>> 24);
    out[offset + 5] = (byte) (value >>> 16);
    out[offset + 6] = (byte) (value >>> 8);
    out[offset + 7] = (byte) value;
  }

  static String base58(final byte[] bytes) {
    return Base58.encode(bytes);
  }

  /// The `getProgramAccounts` account key at index `i` for `program`. The client recomputes this to
  /// check that the account it was handed belongs to the program it asked about.
  static String derivedKey(final String program, final int i) {
    return Base58.encode(sha256("derived:" + program + ':' + i));
  }

  /// A 64-byte synthetic transaction signature for `logsNotification` / `transactionNotification`.
  ///
  /// `DESIGN.md` §7 says "base58 of `sha256("logs:" + seq)` 64 bytes"; SHA-256 yields 32, so the
  /// second half is the digest of a distinct label rather than a repeat of the first — a repeated
  /// half would make two different sequences share 32 bytes of signature.
  static String signature(final long seq) {
    final byte[] sig = new byte[64];
    System.arraycopy(sha256("logs:" + seq), 0, sig, 0, 32);
    System.arraycopy(sha256("logs:" + seq + ":hi"), 0, sig, 32, 32);
    return Base58.encode(sig);
  }

  /// A deterministic 32-byte blockhash-shaped value.
  static String blockHash(final String label, final long ordinal) {
    return Base58.encode(sha256(label + ':' + ordinal));
  }

  /// The HTTP account stamp of `DESIGN.md` §7: `[0..8) requestId`, `[8..16) fnv64(pubkey)`,
  /// `[16..24) peer rpc seq`, `[24..32) fnv64(bytes[0..24))`.
  ///
  /// The client asserts byte `[8..16)` against the key **it** asked for, which is the whole
  /// request/response association property (W3-A): a response that arrived on the wrong future
  /// carries another key's hash.
  static byte[] accountStamp(final long requestId, final String pubKey, final long seq) {
    final byte[] stamp = new byte[32];
    putLong(stamp, 0, requestId);
    putLong(stamp, 8, fnv64(pubKey));
    putLong(stamp, 16, seq);
    putLong(stamp, 24, fnv64(stamp, 0, 24));
    return stamp;
  }

  /// The websocket notification payload of `DESIGN.md` §7: 8 bytes big-endian `seq`, 8 bytes
  /// `fnv64(filler)`, then `size - 16` bytes of filler from `SplittableRandom(seed ^ subId ^ seq)`.
  ///
  /// The checksum is over the filler only, so a client that reassembled fragments in the wrong
  /// order or dropped a continuation frame fails the check even when the length came out right.
  static byte[] payload(final long seed, final long subId, final long seq, final int size) {
    final int length = Math.max(16, size);
    final byte[] payload = new byte[length];
    putLong(payload, 0, seq);
    final var random = new SplittableRandom(seed ^ subId ^ seq);
    int i = 16;
    for (final int end = length - 7; i < end; i += 8) {
      putLong(payload, i, random.nextLong());
    }
    if (i < length) {
      long tail = random.nextLong();
      while (i < length) {
        payload[i++] = (byte) (tail >>> 56);
        tail <<= 8;
      }
    }
    putLong(payload, 8, fnv64(payload, 16, length));
    return payload;
  }
}
