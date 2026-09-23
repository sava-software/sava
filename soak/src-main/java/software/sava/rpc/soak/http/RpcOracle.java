package software.sava.rpc.soak.http;

import software.sava.core.encoding.Base58;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static java.nio.charset.StandardCharsets.UTF_8;

/// The client half of the peer's stamping contract (`DESIGN.md` §7), reimplemented here on
/// purpose.
///
/// The design says the harness-side oracle *reimplements* `peer.Stamp` rather than calling it,
/// and the peer's class is package-private, so there is no shortcut available even if one were
/// wanted. That is the right shape for an oracle: if both sides shared one implementation, a bug
/// in that implementation would agree with itself and the association check would pass while the
/// wire bytes were wrong. Two independent expressions of one contract disagree when either is
/// wrong — which is the whole value of W3-A.
///
/// A change here is a change to `DESIGN.md` §7 and must be made on both sides.
final class RpcOracle {

  /// Slot numbers are offset from a fixed base so a synthetic slot is obviously synthetic.
  static final long BASE_SLOT = 10_000_000L;

  /// `[0..8) requestId`, `[8..16) fnv64(pubkey base58)`, `[16..24) peer rpc seq`,
  /// `[24..32) fnv64(bytes[0..24))`.
  static final int STAMP_LENGTH = 32;

  /// The mask the peer applies to a signature hash when deriving a status slot.
  private static final long SIGNATURE_SLOT_MASK = 0xFFFFFL;

  private static final long FNV_OFFSET_BASIS = 0xcbf2_9ce4_8422_2325L;
  private static final long FNV_PRIME = 0x0000_0100_0000_01b3L;

  /// The single owner every synthetic account reports, derived the way the peer derives it.
  static final String OWNER = Base58.encode(sha256("sava-soak-owner"));

  private RpcOracle() {
  }

  private static byte[] sha256(final String input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input.getBytes(UTF_8));
    } catch (final NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required by every JDK", ex);
    }
  }

  static long fnv64(final byte[] bytes, final int from, final int to) {
    long hash = FNV_OFFSET_BASIS;
    for (int i = from; i < to; ++i) {
      hash ^= (bytes[i] & 0xFFL);
      hash *= FNV_PRIME;
    }
    return hash;
  }

  static long fnv64(final byte[] bytes) {
    return fnv64(bytes, 0, bytes.length);
  }

  static long fnv64(final String string) {
    return fnv64(string.getBytes(UTF_8));
  }

  static long readLong(final byte[] bytes, final int offset) {
    return ((long) (bytes[offset] & 0xFF) << 56)
        | ((long) (bytes[offset + 1] & 0xFF) << 48)
        | ((long) (bytes[offset + 2] & 0xFF) << 40)
        | ((long) (bytes[offset + 3] & 0xFF) << 32)
        | ((long) (bytes[offset + 4] & 0xFF) << 24)
        | ((long) (bytes[offset + 5] & 0xFF) << 16)
        | ((long) (bytes[offset + 6] & 0xFF) << 8)
        | (bytes[offset + 7] & 0xFFL);
  }

  /// True when the stamp is 32 bytes and its own trailing checksum covers its leading 24. A
  /// truncated body that still parsed as JSON fails here, which is the defect W3-C hunts.
  static boolean stampIntact(final byte[] stamp) {
    return stamp != null
        && stamp.length == STAMP_LENGTH
        && readLong(stamp, 24) == fnv64(stamp, 0, 24);
  }

  static long stampRequestId(final byte[] stamp) {
    return readLong(stamp, 0);
  }

  /// The hash of the key the peer believed it was answering about. W3-A is exactly the
  /// comparison of this against the key **the caller asked for**.
  static long stampKeyHash(final byte[] stamp) {
    return readLong(stamp, 8);
  }

  static long stampSeq(final byte[] stamp) {
    return readLong(stamp, 16);
  }

  /// The `getProgramAccounts` account key at index `i`, recomputed so that an account handed back
  /// under another program's response is visible without holding a copy of the request.
  static String derivedKey(final String program, final int i) {
    return Base58.encode(sha256("derived:" + program + ':' + i));
  }

  /// `slot_i = BASE_SLOT + (fnv64(sig_i) & 0xFFFFF)`: derived from the signature the client sent,
  /// so a status that landed on the wrong future names another signature's slot.
  static long signatureSlot(final String signature) {
    return BASE_SLOT + (fnv64(signature) & SIGNATURE_SLOT_MASK);
  }

  /// A short, stable projection of one stamped account for the W3-C twin comparison.
  ///
  /// The two twins can never be byte-equal — the stamp carries the request id and the peer's own
  /// sequence, both of which differ between the two requests — so the comparison is over the part
  /// of the account that is a function of the *key*: the key hash and the owner. A gzip round
  /// trip that corrupted either would show up here; one that merely renumbered would not, and
  /// must not, because that is not a decoding defect.
  static long keyProjection(final byte[] stamp, final String owner, final int space) {
    if (!stampIntact(stamp)) {
      return -1L;
    }
    long projection = stampKeyHash(stamp);
    projection = projection * 31L + fnv64(owner == null ? "" : owner);
    return projection * 31L + space;
  }

  /// A short description of a stamp for a finding example. Never the whole body: a finding note
  /// with a megabyte of base64 in it is one nobody reads.
  static String describeStamp(final byte[] stamp) {
    if (stamp == null) {
      return "stamp=null";
    } else if (stamp.length != STAMP_LENGTH) {
      return "stamp.length=" + stamp.length;
    } else {
      return "stamp[requestId=" + stampRequestId(stamp)
          + ",keyHash=" + stampKeyHash(stamp)
          + ",seq=" + stampSeq(stamp)
          + ",intact=" + stampIntact(stamp) + ']';
    }
  }
}
