package software.sava.core.accounts.pbkdf;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;

public final class PBKDFEncryption {

  private static final String CIPHER = "AES/GCM/NoPadding";
  private static final int KEY_BITS = 256;
  static final int SALT_BYTES = 16;
  static final int IV_BYTES = 12;
  static final int GCM_TAG_BITS = 128;

  private PBKDFEncryption() {
  }

  public static EncryptionEnvelope encrypt(final char[] password,
                                           final SecureRandom secureRandom,
                                           final byte[] data,
                                           final KeyDerivation keyDerivation,
                                           final byte[] aad) {
    final byte[] salt = new byte[SALT_BYTES];
    secureRandom.nextBytes(salt);
    final byte[] iv = new byte[IV_BYTES];
    secureRandom.nextBytes(iv);

    try {
      final var keyBytes = keyDerivation.derive(password, salt, KEY_BITS);
      try {
        final var secretKey = new SecretKeySpec(keyBytes, "AES");
        final var cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        if (aad != null && aad.length > 0) {
          cipher.updateAAD(aad);
        }
        final byte[] cipherText = cipher.doFinal(data);
        return new EncryptionEnvelope(keyDerivation, aad, salt, iv, cipherText);
      } finally {
        Arrays.fill(keyBytes, (byte) 0);
      }
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException("Failed to encrypt key file.", e);
    }
  }

  public static byte[] decrypt(final char[] password,
                               final KeyDerivation keyDerivation,
                               final byte[] aad,
                               final byte[] salt,
                               final byte[] iv,
                               final byte[] cipherText) {
    final var keyBytes = keyDerivation.derive(password, salt, KEY_BITS);
    try {
      return decrypt(keyBytes, aad, iv, cipherText);
    } finally {
      if (keyBytes != null) {
        Arrays.fill(keyBytes, (byte) 0);
      }
    }
  }

  public static byte[] decrypt(final byte[] keyBytes,
                               final byte[] aad,
                               final byte[] iv,
                               final byte[] cipherText) {
    try {
      final var secretKey = new SecretKeySpec(keyBytes, "AES");
      final var cipher = Cipher.getInstance(CIPHER);
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
      if (aad != null && aad.length > 0) {
        cipher.updateAAD(aad);
      }
      return cipher.doFinal(cipherText);
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException("Failed to decrypt key file.", e);
    }
  }

  static byte[] toUtf8Bytes(final char[] password) {
    final var buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
    try {
      final byte[] bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
      return bytes;
    } finally {
      Arrays.fill(buffer.array(), (byte) 0);
    }
  }

  public static byte[] decrypt(final String resolvedPrefix, final Properties properties, final char[] password) {
    final var aadValue = properties.getProperty(resolvedPrefix + "aad");
    final byte[] aad = aadValue == null || aadValue.isBlank() ? new byte[0] : Base64.getDecoder().decode(aadValue);
    return decrypt(resolvedPrefix, properties, password, aad);
  }

  public static byte[] decrypt(final String resolvedPrefix,
                               final Properties properties,
                               final char[] password,
                               final byte[] aad) {
    final var kdf = requireProperty(resolvedPrefix, properties, "kdf");
    final var decoder = Base64.getDecoder();
    final byte[] salt = decoder.decode(requireProperty(resolvedPrefix, properties, "salt"));
    final byte[] iv = decoder.decode(requireProperty(resolvedPrefix, properties, "iv"));
    final byte[] cipherText = decoder.decode(requireProperty(resolvedPrefix, properties, "secret"));

    final int iterations = Integer.parseInt(requireProperty(resolvedPrefix, properties, "iterations"));
    final KeyDerivation keyDerivation;
    if (kdf.equalsIgnoreCase("argon2id")) {
      final int memoryKb = Integer.parseInt(requireProperty(resolvedPrefix, properties, "memoryKB"));
      final int parallelism = Integer.parseInt(requireProperty(resolvedPrefix, properties, "parallelism"));
      keyDerivation = KeyDerivation.createArgon2id(memoryKb, parallelism, iterations);
    } else if (kdf.equalsIgnoreCase("pbkdf2WithHmacSHA512")) {
      keyDerivation = KeyDerivation.createPBKDF2WithHmacSHA512(iterations);
    } else {
      throw new IllegalArgumentException("Unsupported key derivation function: " + kdf);
    }
    return decrypt(password, keyDerivation, aad, salt, iv, cipherText);
  }

  private static String requireProperty(final String resolvedPrefix, final Properties properties, final String name) {
    final var value = properties.getProperty(resolvedPrefix + name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing required property: " + resolvedPrefix + name);
    }
    return value.strip();
  }
}
