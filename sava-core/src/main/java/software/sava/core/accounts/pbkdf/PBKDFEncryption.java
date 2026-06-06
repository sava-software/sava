package software.sava.core.accounts.pbkdf;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

public final class PBKDFEncryption {

  static final String CIPHER = "AES/GCM/NoPadding";
  static final int KEY_BITS = 256;
  static final int KEY_BYTES = KEY_BITS / 8;
  static final int SALT_BYTES = 16;
  static final int IV_BYTES = 12;
  static final int GCM_TAG_BITS = 128;

  private PBKDFEncryption() {
  }

  public static Encrypted encrypt(final char[] password,
                                  final SecureRandom secureRandom,
                                  final String plainText,
                                  final KeyDerivation keyDerivation,
                                  final byte[] aad) {
    final byte[] salt = new byte[SALT_BYTES];
    secureRandom.nextBytes(salt);
    final byte[] iv = new byte[IV_BYTES];
    secureRandom.nextBytes(iv);

    final byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
    try {
      final var keyBytes = keyDerivation.derive(password, salt);
      try {
        final var secretKey = new SecretKeySpec(keyBytes, "AES");
        final var cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        if (aad != null && aad.length > 0) {
          cipher.updateAAD(aad);
        }
        final byte[] cipherText = cipher.doFinal(plainBytes);
        return new Encrypted(keyDerivation, salt, iv, cipherText);
      } finally {
        Arrays.fill(keyBytes, (byte) 0);
      }
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException("Failed to encrypt key file.", e);
    } finally {
      Arrays.fill(plainBytes, (byte) 0);
    }
  }

  static byte[] toUtf8Bytes(final char[] password) {
    final var buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
    final byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    Arrays.fill(buffer.array(), (byte) 0);
    return bytes;
  }
}
