package software.sava.core.accounts;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import software.sava.core.crypto.Hash;
import software.sava.core.crypto.ed25519.Ed25519Util;
import software.sava.core.encoding.Base58;
import software.sava.core.encoding.ByteUtil;

import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static software.sava.core.crypto.Hash.sha256Digest;
import static software.sava.core.crypto.SunCrypto.ED_25519_KEY_FACTORY;

public interface PublicKey extends Comparable<PublicKey> {

  int PUBLIC_KEY_LENGTH = 32;
  int MAX_SEED_LENGTH = 32;

  /// Maximum number of seed byte arrays in a PDA derivation, including the bump when present.
  int MAX_SEEDS = 16;
  PublicKey NONE = new PublicKeyBytes(new byte[PUBLIC_KEY_LENGTH]); // 11111111111111111111111111111111

  static boolean verifySignature(final java.security.PublicKey publicKey,
                                 final byte[] msg,
                                 final int msgOffset,
                                 final int msgLength,
                                 final byte[] signature) {
    try {
      final var sigAlgo = Signature.getInstance("Ed25519");
      sigAlgo.initVerify(publicKey);
      sigAlgo.update(msg, msgOffset, msgLength);
      return sigAlgo.verify(signature);
    } catch (final NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
      throw new RuntimeException(e);
    }
  }

  static boolean verifySignature(final java.security.PublicKey publicKey,
                                 final byte[] msg,
                                 final byte[] signature) {
    return verifySignature(
        publicKey,
        msg, 0, msg.length,
        signature
    );
  }

  /// Verifies a signature over the UTF-8 encoding of `msg`.
  static boolean verifySignature(final java.security.PublicKey publicKey,
                                 final String msg,
                                 final byte[] signature) {
    return verifySignature(
        publicKey,
        msg.getBytes(UTF_8),
        signature
    );
  }

  static java.security.PublicKey toJavaPublicKey(final byte[] publicKey, final int off, final int len) {
    final var reversed = ByteUtil.reverse(publicKey, off, len);
    final int last = reversed[0] & 0xFF;
    final boolean xOdd = (last & 0b1000_0000) == 0b1000_0000;
    reversed[0] = (byte) (last & Byte.MAX_VALUE);
    final var y = new BigInteger(reversed);
    final var edECPoint = new EdECPoint(xOdd, y);
    final var pubSpec = new EdECPublicKeySpec(NamedParameterSpec.ED25519, edECPoint);
    try {
      return ED_25519_KEY_FACTORY.generatePublic(pubSpec);
    } catch (final InvalidKeySpecException e) {
      throw new RuntimeException(e);
    }
  }

  static java.security.PublicKey toJavaPublicKey(final byte[] publicKey) {
    return toJavaPublicKey(publicKey, 0, PUBLIC_KEY_LENGTH);
  }

  private static boolean verifySignature(final Ed25519PublicKeyParameters publicKeyParameters,
                                         final byte[] msg,
                                         final int msgOffset,
                                         final int msgLength,
                                         final byte[] signature) {
    final var verifier = new Ed25519Signer();
    verifier.init(false, publicKeyParameters);
    verifier.update(msg, msgOffset, msgLength);
    return verifier.verifySignature(signature);
  }

  private static boolean verifySignature(final Ed25519PublicKeyParameters publicKeyParameters,
                                         final byte[] msg,
                                         final byte[] signature) {
    return verifySignature(
        publicKeyParameters,
        msg, 0, msg.length,
        signature
    );
  }

  private static boolean verifySignature(final Ed25519PublicKeyParameters publicKeyParameters,
                                         final String msg,
                                         final byte[] signature) {
    return verifySignature(
        publicKeyParameters,
        msg.getBytes(UTF_8),
        signature
    );
  }

  static boolean verifySignature(final byte[] publicKey,
                                 final int publicKeyOffset,
                                 final byte[] msg,
                                 final int msgOffset,
                                 final int msgLength,
                                 final byte[] signature) {
    final var publicKeyParameters = new Ed25519PublicKeyParameters(publicKey, publicKeyOffset);
    return verifySignature(
        publicKeyParameters,
        msg, msgOffset, msgLength,
        signature
    );
  }

  /// Verifies a signature over the UTF-8 encoding of `msg`.
  static boolean verifySignature(final byte[] publicKey,
                                 final int publicKeyOffset,
                                 final String msg,
                                 final byte[] signature) {
    final byte[] msgBytes = msg.getBytes(UTF_8);
    return verifySignature(publicKey, publicKeyOffset, msgBytes, 0, msgBytes.length, signature);
  }

  /// Verifies a signature over the UTF-8 encoding of `msg`.
  static boolean verifySignature(final byte[] publicKey, final String msg, final byte[] signature) {
    return verifySignature(publicKey, 0, msg, signature);
  }

  static PublicKey readPubKey(final byte[] bytes, final int offset) {
    // Arrays.copyOfRange zero-pads past the end of the source, which would silently
    // fabricate a key from truncated data
    if (bytes.length - offset < PUBLIC_KEY_LENGTH) {
      throw new IndexOutOfBoundsException(String.format(
          "Public key needs %d bytes at offset %d, but only %d are available.",
          PUBLIC_KEY_LENGTH, offset, bytes.length - offset
      ));
    }
    return new PublicKeyBytes(Arrays.copyOfRange(bytes, offset, offset + PublicKey.PUBLIC_KEY_LENGTH));
  }

  static PublicKey readPubKey(final byte[] bytes) {
    return readPubKey(bytes, 0);
  }

  /// Creates a public key that retains `publicKey` without copying it.
  ///
  /// Leave the array unchanged while the key is in use, or pass `publicKey.clone()`: mutating
  /// it can leave the cached [#toBase58()] and [Object#hashCode()] stale, so equal keys may
  /// hash differently.
  ///
  /// @throws IllegalArgumentException if `publicKey` is not [#PUBLIC_KEY_LENGTH] bytes
  static PublicKey createPubKey(final byte[] publicKey) {
    if (publicKey.length != PublicKey.PUBLIC_KEY_LENGTH) {
      throw new IllegalArgumentException("Invalid public key input");
    } else {
      return new PublicKeyBytes(publicKey);
    }
  }

  static PublicKey fromBase58Encoded(final String base58) {
    final byte[] publicKey = new byte[PUBLIC_KEY_LENGTH];
    Base58.decode(base58, publicKey);
    return new PublicKeyBytes(publicKey);
  }

  static PublicKey fromBase58Encoded(final char[] base58) {
    return fromBase58Encoded(base58, 0, base58.length);
  }

  static PublicKey fromBase58Encoded(final char[] base58, final int from, final int len) {
    final byte[] publicKey = new byte[PUBLIC_KEY_LENGTH];
    Base58.decode(base58, from, len, publicKey);
    return new PublicKeyBytes(publicKey);
  }

  /// Decodes a key from base58 text stored as ASCII bytes, e.g. a raw JSON buffer.
  static PublicKey fromBase58Encoded(final byte[] base58, final int from, final int len) {
    final byte[] publicKey = new byte[PUBLIC_KEY_LENGTH];
    Base58.decode(base58, from, len, publicKey);
    return new PublicKeyBytes(publicKey);
  }

  static PublicKey fromBase64Encoded(final String base64) {
    final byte[] publicKey = Base64.getDecoder().decode(base64);
    return createPubKey(publicKey);
  }

  int write(final byte[] out, final int off);

  /// Derives a program address from `seeds` as given, without a bump search.
  ///
  /// @return `null` if the derived address lies on the ed25519 curve
  /// @throws IllegalArgumentException if more than [#MAX_SEEDS] seeds are given or any seed
  ///                                  exceeds [#MAX_SEED_LENGTH] bytes
  static PublicKey createProgramAddress(final List<byte[]> seeds, final PublicKey programId) {
    final byte[] buffer = PublicKeyBytes.createBuffer(seeds, false, programId);
    final byte[] hash = Hash.sha256(buffer);
    return Ed25519Util.isNotOnCurve(hash) ? PublicKey.createPubKey(hash) : null;
  }

  /// Finds the canonical program address, trying bump seeds from 255 down to 1. The bump
  /// counts toward [#MAX_SEEDS], so at most `MAX_SEEDS - 1` caller seeds are allowed.
  ///
  /// @throws IllegalArgumentException if more than `MAX_SEEDS - 1` seeds are given or any seed
  ///                                  exceeds [#MAX_SEED_LENGTH] bytes
  /// @throws RuntimeException if no bump yields an off-curve address
  static ProgramDerivedAddress findProgramAddress(final List<byte[]> seeds, final PublicKey programId) {
    return PublicKeyBytes.findProgramAddress(seeds, programId, Ed25519Util::isNotOnCurve);
  }

  /// Derives an off-curve seeded account, trying nonces from 127 down to 0.
  ///
  /// The returned [AccountWithSeed#asciiSeed()] is the US-ASCII encoding of `baseSeed`
  /// followed by the nonce byte, so `baseSeed` may encode to at most `MAX_SEED_LENGTH - 1`
  /// bytes. Non-ASCII characters encode as Java's US-ASCII replacement byte (`?`).
  ///
  /// @throws IllegalArgumentException if the encoded base seed plus nonce exceeds
  ///                                  [#MAX_SEED_LENGTH] bytes or `programId` ends in the
  ///                                  program-derived-address marker
  /// @throws RuntimeException if no nonce yields an off-curve address
  static AccountWithSeed createOffCurveAccountWithAsciiSeed(final PublicKey base,
                                                            final String baseSeed,
                                                            final PublicKey programId) {
    final byte[] baseSeedBytes = baseSeed.getBytes(US_ASCII);
    if (baseSeedBytes.length >= MAX_SEED_LENGTH) {
      throw new IllegalArgumentException(String.format(
          "Seed [%s] plus nonce exceeds maximum length of [%d].",
          baseSeed, MAX_SEED_LENGTH
      ));
    }
    return PublicKeyBytes.createOffCurveAccountWithAsciiSeed(
        base,
        baseSeedBytes,
        programId,
        Ed25519Util::isNotOnCurve
    );
  }

  /// Derives a seeded address as Solana's `Address::create_with_seed` does, hashing the UTF-8
  /// encoding of `seed`.
  ///
  /// @throws IllegalArgumentException if `seed` exceeds [#MAX_SEED_LENGTH] UTF-8 bytes or
  ///                                  contains an unpaired UTF-16 surrogate (a Rust string
  ///                                  cannot hold one), or `programId` ends in the
  ///                                  program-derived-address marker
  static PublicKey createWithSeed(final PublicKey base,
                                  final String seed,
                                  final PublicKey programId) {
    final byte[] seedBytes = PublicKeyBytes.encodeUtf8Seed(seed);
    if (seedBytes.length > MAX_SEED_LENGTH) {
      throw new IllegalArgumentException(String.format(
          "Seed [%s] exceeds maximum length of [%d].",
          seed, MAX_SEED_LENGTH
      ));
    }
    final byte[] programIdBytes = programId.toByteArray();
    final byte[] marker = PublicKeyBytes.PDA_BYTES;
    if (Arrays.equals(
        programIdBytes, programIdBytes.length - marker.length, programIdBytes.length,
        marker, 0, marker.length
    )) {
      throw new IllegalArgumentException("Owner cannot end with the program derived address marker.");
    }
    final var digest = sha256Digest();
    digest.update(base.toByteArray());
    digest.update(seedBytes);
    digest.update(programIdBytes);
    return PublicKey.createPubKey(digest.digest());
  }

  /// Returns the key bytes, which may be the key's own storage: a key from
  /// [#createPubKey(byte\[\])] returns its retained input array. Do not modify the result; use
  /// [#copyByteArray()] for an independent copy.
  byte[] toByteArray();

  /// Returns an independent copy of the key bytes.
  byte[] copyByteArray();

  String toBase58();

  String toBase64();

  default int l() {
    return PUBLIC_KEY_LENGTH;
  }

  default boolean verifySignature(final byte[] msg,
                                  final int msgOffset,
                                  final int msgLength,
                                  final byte[] signature) {
    return verifySignature(
        toByteArray(), 0,
        msg, msgOffset, msgLength,
        signature
    );
  }

  default boolean verifySignature(final byte[] msg, final byte[] signature) {
    return verifySignature(
        toByteArray(), 0,
        msg, 0, msg.length,
        signature
    );
  }

  /// Verifies a signature over the UTF-8 encoding of `msg`.
  default boolean verifySignature(final String msg, final byte[] signature) {
    return verifySignature(toByteArray(), msg, signature);
  }

  default java.security.PublicKey toJavaPublicKey() {
    return toJavaPublicKey(toByteArray());
  }
}
