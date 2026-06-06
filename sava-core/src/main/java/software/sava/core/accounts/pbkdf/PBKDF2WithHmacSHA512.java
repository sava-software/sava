package software.sava.core.accounts.pbkdf;

import software.sava.core.accounts.PublicKey;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

import static software.sava.core.accounts.pbkdf.PBKDFEncryption.KEY_BITS;

public record PBKDF2WithHmacSHA512(int iterations) implements KeyDerivation {

  // OWASP recommended minimum for PBKDF2-HMAC-SHA512.
  static final int ITERATIONS = 210_000;

  static final PBKDF2WithHmacSHA512 DEFAULT = new PBKDF2WithHmacSHA512(ITERATIONS);

  @Override
  public byte[] derive(final char[] password, final byte[] salt) {
    final var keySpec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
    try {
      final var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
      return factory.generateSecret(keySpec).getEncoded();
    } catch (final NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new RuntimeException(e);
    } finally {
      keySpec.clearPassword();
    }
  }

  @Override
  public String toJson(final PublicKey publicKey,
                       final PrivateKeyEncoding privateKeyEncoding,
                       final Encrypted encrypted) {
    final var encoder = Base64.getEncoder();
    return String.format(
        """
            {
              "pubKey": "%s",
              "encoding": "%s",
              "kdf": "PBKDF2WithHmacSHA512",
              "iterations": %d,
              "salt": "%s",
              "cipher": "%s",
              "iv": "%s",
              "secret": "%s"
            }""",
        publicKey.toBase58(),
        privateKeyEncoding,
        iterations,
        encoder.encodeToString(encrypted.salt()),
        PBKDFEncryption.CIPHER,
        encoder.encodeToString(encrypted.iv()),
        encoder.encodeToString(encrypted.cipherText())
    );
  }

  @Override
  public String toProperties(final PublicKey publicKey,
                             final PrivateKeyEncoding privateKeyEncoding,
                             final Encrypted encrypted) {
    final var encoder = Base64.getEncoder();
    return String.format(
        """
            pubKey=%s
            encoding=%s
            kdf=PBKDF2WithHmacSHA512
            iterations=%d
            salt=%s
            cipher=%s
            iv=%s
            secret=%s
            """,
        publicKey,
        privateKeyEncoding,
        iterations,
        encoder.encodeToString(encrypted.salt()),
        PBKDFEncryption.CIPHER,
        encoder.encodeToString(encrypted.iv()),
        encoder.encodeToString(encrypted.cipherText())
    );
  }
}
