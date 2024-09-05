package software.sava.core.accounts;

import software.sava.core.crypto.Hash;
import software.sava.core.crypto.ed25519.Ed25519Util;
import software.sava.core.encoding.Base58;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static software.sava.core.accounts.PublicKeyBytes.PDA_BYTES;
import static software.sava.core.crypto.Hash.sha256Digest;

public interface PublicKey extends Comparable<PublicKey> {

  int PUBLIC_KEY_LENGTH = 32;
  PublicKey NONE = new PublicKeyBytes(new byte[PUBLIC_KEY_LENGTH]); // 11111111111111111111111111111111

  static PublicKey readPubKey(final byte[] bytes, final int offset) {
    return new PublicKeyBytes(Arrays.copyOfRange(bytes, offset, offset + PublicKey.PUBLIC_KEY_LENGTH));
  }

  static PublicKey createPubKey(final byte[] publicKey) {
    if (publicKey.length != PublicKey.PUBLIC_KEY_LENGTH) {
      throw new IllegalArgumentException("Invalid public key input");
    } else {
      return new PublicKeyBytes(publicKey);
    }
  }

  static PublicKey fromBase58Encoded(final String base58) {
    final byte[] publicKey = Base58.decode(base58);
    return createPubKey(publicKey);
  }

  static PublicKey fromBase58Encoded(final char[] base58) {
    return fromBase58Encoded(base58, 0, base58.length);
  }

  static PublicKey fromBase58Encoded(final char[] base58, final int from, final int len) {
    final byte[] publicKey = Base58.decode(base58, from, len);
    return createPubKey(publicKey);
  }

  static PublicKey fromBase64Encoded(final String base64) {
    final byte[] publicKey = Base64.getDecoder().decode(base64);
    return createPubKey(publicKey);
  }

  int write(final byte[] out, final int off);

  static PublicKey createProgramAddress(final List<byte[]> seeds, final PublicKey programId) {
    final byte[] buffer = PublicKeyBytes.createBuffer(seeds, false, programId);
    final byte[] hash = Hash.sha256(buffer);
    return Ed25519Util.isNotOnCurve(hash) ? PublicKey.createPubKey(hash) : null;
  }

  static ProgramDerivedAddress findProgramAddress(final List<byte[]> seeds, final PublicKey programId) {
    final byte[] buffer = PublicKeyBytes.createBuffer(seeds, true, programId);
    final int nonceOffset = buffer.length - (1 + PublicKey.PUBLIC_KEY_LENGTH + PDA_BYTES.length);
    final var sha256 = Hash.sha256Digest();
    for (int nonce = 255; nonce >= 0; --nonce) {
      buffer[nonceOffset] = (byte) nonce;
      final byte[] hash = sha256.digest(buffer);
      if (Ed25519Util.isNotOnCurve(hash)) {
        return ProgramDerivedAddress.createPDA(seeds, PublicKey.createPubKey(hash), nonce);
      }
    }
    throw new RuntimeException("Unable to find a viable program derived address nonce");
  }

  static AccountWithSeed createOffCurveAccountWithAsciiSeed(final PublicKey base,
                                                            final String baseSeed,
                                                            final PublicKey programId) {
    final byte[] baseSeedBytes = baseSeed.getBytes(US_ASCII);
    final byte[] buffer = new byte[PUBLIC_KEY_LENGTH + baseSeedBytes.length + 1 + PUBLIC_KEY_LENGTH];
    base.write(buffer, 0);
    System.arraycopy(baseSeedBytes, 0, buffer, PUBLIC_KEY_LENGTH, baseSeedBytes.length);
    programId.write(buffer, buffer.length - PUBLIC_KEY_LENGTH);

    final int nonceOffset = PUBLIC_KEY_LENGTH + baseSeedBytes.length;
    final var sha256 = Hash.sha256Digest();
    for (int nonce = 127; nonce >= 0; --nonce) {
      buffer[nonceOffset] = (byte) nonce;
      final byte[] hash = sha256.digest(buffer);
      if (Ed25519Util.isNotOnCurve(hash)) {
        final byte[] bumpSeedBytes = Arrays.copyOfRange(
            buffer,
            PUBLIC_KEY_LENGTH,
            PUBLIC_KEY_LENGTH + baseSeedBytes.length + 1
        );
        return new AccountWithSeedRecord(base, PublicKey.createPubKey(hash), bumpSeedBytes, programId);
      }
    }
    throw new RuntimeException("Unable to find a viable program derived address nonce");
  }

  static PublicKey createWithSeed(final PublicKey base,
                                  final String seed,
                                  final PublicKey programId) {
    final var digest = sha256Digest();
    digest.update(base.toByteArray());
    digest.update(seed.getBytes(US_ASCII));
    digest.update(programId.toByteArray());
    return PublicKey.createPubKey(digest.digest());
  }

  byte[] toByteArray();

  String toBase58();

  String toBase64();

  default int l() {
    return PUBLIC_KEY_LENGTH;
  }
}
