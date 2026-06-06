package software.sava.core.accounts.vanity;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/// Password based encryption for vanity key files.
///
/// Uses PBKDF2 (HMAC-SHA-512) derived 256-bit key combined with AES in GCM mode to provide
/// authenticated encryption.
final class KeyFileEncryption {

  static final String KDF = "PBKDF2WithHmacSHA512";
  static final String CIPHER = "AES/GCM/NoPadding";
  // OWASP recommended minimum for PBKDF2-HMAC-SHA512.
  static final int ITERATIONS = 210_000;
  static final int KEY_BITS = 256;
  static final int SALT_BYTES = 16;
  static final int IV_BYTES = 12;
  static final int GCM_TAG_BITS = 128;

  private KeyFileEncryption() {
  }

  record Encrypted(byte[] salt, byte[] iv, byte[] cipherText) {
  }

  static Encrypted encrypt(final char[] password,
                           final SecureRandom secureRandom,
                           final String plainText) {
    final byte[] salt = new byte[SALT_BYTES];
    secureRandom.nextBytes(salt);
    final byte[] iv = new byte[IV_BYTES];
    secureRandom.nextBytes(iv);

    final byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
    SecretKey secretKey;
    try {
      final var keySpec = new PBEKeySpec(password, salt, ITERATIONS, KEY_BITS);
      final byte[] keyBytes;
      try {
        final var factory = SecretKeyFactory.getInstance(KDF);
        keyBytes = factory.generateSecret(keySpec).getEncoded();
      } finally {
        keySpec.clearPassword();
      }
      secretKey = new SecretKeySpec(keyBytes, "AES");
      Arrays.fill(keyBytes, (byte) 0);

      final var cipher = Cipher.getInstance(CIPHER);
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
      final byte[] cipherText = cipher.doFinal(plainBytes);
      return new Encrypted(salt, iv, cipherText);
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException("Failed to encrypt key file.", e);
    } finally {
      Arrays.fill(plainBytes, (byte) 0);
    }
  }
}
