package software.sava.core.accounts.vanity;

import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.SecretKeyFactory;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class KeyFileEncryptionTest {

  private static byte[] decrypt(final char[] password, final KeyFileEncryption.Encrypted encrypted) throws Exception {
    final var keySpec = new PBEKeySpec(password, encrypted.salt(), KeyFileEncryption.ITERATIONS, KeyFileEncryption.KEY_BITS);
    final var factory = SecretKeyFactory.getInstance(KeyFileEncryption.KDF);
    final byte[] keyBytes = factory.generateSecret(keySpec).getEncoded();
    final var secretKey = new SecretKeySpec(keyBytes, "AES");
    final var cipher = Cipher.getInstance(KeyFileEncryption.CIPHER);
    cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(KeyFileEncryption.GCM_TAG_BITS, encrypted.iv()));
    return cipher.doFinal(encrypted.cipherText());
  }

  @Test
  void roundTrip() throws Exception {
    final var secureRandom = new SecureRandom();
    final var secret = "\"abc123SecretKeyPairBase64==\"";
    final var encrypted = KeyFileEncryption.encrypt("correct horse".toCharArray(), secureRandom, secret);

    assertEquals(KeyFileEncryption.SALT_BYTES, encrypted.salt().length);
    assertEquals(KeyFileEncryption.IV_BYTES, encrypted.iv().length);
    assertFalse(new String(encrypted.cipherText(), StandardCharsets.UTF_8).contains("abc123"));

    final byte[] decrypted = decrypt("correct horse".toCharArray(), encrypted);
    assertArrayEquals(secret.getBytes(StandardCharsets.UTF_8), decrypted);
  }

  @Test
  void wrongPasswordFails() {
    final var secureRandom = new SecureRandom();
    final var encrypted = KeyFileEncryption.encrypt("right".toCharArray(), secureRandom, "some-secret");
    assertThrows(AEADBadTagException.class, () -> decrypt("wrong".toCharArray(), encrypted));
  }
}
