package software.sava.core.accounts.pbkdf;

import org.bouncycastle.crypto.params.Argon2Parameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

final class PBKDFEncryptionTest {

  private static final byte[] AAD = "4bcoVWVXfw6xKsEYdM6s7AeZQMgDG958kK5Uzhc2sw37".getBytes(StandardCharsets.UTF_8);

  private static byte[] decrypt(final char[] password, final Encrypted encrypted) throws Exception {
    return decrypt(password, encrypted, AAD);
  }

  private static byte[] decrypt(final char[] password, final Encrypted encrypted, final byte[] aad) throws Exception {
    final var kdf = assertInstanceOf(PBKDF2WithHmacSHA512.class, encrypted.keyDerivation());
    final var keySpec = new PBEKeySpec(password, encrypted.salt(), kdf.iterations(), PBKDFEncryption.KEY_BITS);
    final var factory = SecretKeyFactory.getInstance(kdf.getClass().getSimpleName());
    final byte[] keyBytes = factory.generateSecret(keySpec).getEncoded();
    final var secretKey = new SecretKeySpec(keyBytes, "AES");
    final var cipher = Cipher.getInstance(PBKDFEncryption.CIPHER);
    cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(PBKDFEncryption.GCM_TAG_BITS, encrypted.iv()));
    if (aad != null && aad.length > 0) {
      cipher.updateAAD(aad);
    }
    return cipher.doFinal(encrypted.cipherText());
  }

  @Test
  void roundTrip() throws Exception {
    final var secureRandom = new SecureRandom();
    final var secret = "\"abc123SecretKeyPairBase64==\"";
    final var encrypted = PBKDFEncryption.encrypt(
        "correct horse".toCharArray(), secureRandom, secret, KeyDerivation.defaultPBKDF2WithHmacSHA512(), AAD
    );

    assertEquals(PBKDFEncryption.SALT_BYTES, encrypted.salt().length);
    assertEquals(PBKDFEncryption.IV_BYTES, encrypted.iv().length);
    assertFalse(new String(encrypted.cipherText(), StandardCharsets.UTF_8).contains("abc123"));

    final byte[] decrypted = decrypt("correct horse".toCharArray(), encrypted);
    assertArrayEquals(secret.getBytes(StandardCharsets.UTF_8), decrypted);
  }

  @Test
  void wrongAadFails() {
    final var secureRandom = new SecureRandom();
    final var encrypted = PBKDFEncryption.encrypt(
        "right".toCharArray(), secureRandom, "some-secret", KeyDerivation.defaultPBKDF2WithHmacSHA512(), AAD
    );
    final var tamperedAad = "tampered-public-key".getBytes(StandardCharsets.UTF_8);
    assertThrows(AEADBadTagException.class, () -> decrypt("right".toCharArray(), encrypted, tamperedAad));
  }

  @Test
  void wrongPasswordFails() {
    final var secureRandom = new SecureRandom();
    final var encrypted = PBKDFEncryption.encrypt(
        "right".toCharArray(), secureRandom, "some-secret", KeyDerivation.defaultPBKDF2WithHmacSHA512(), AAD
    );
    assertThrows(AEADBadTagException.class, () -> decrypt("wrong".toCharArray(), encrypted));
  }

  // Argon2id is memory-hard; run it sequentially so concurrent tests don't exhaust heap.
  @Test
  @ResourceLock("argon2id")
  void argon2RoundTrip() throws Exception {
    final var secureRandom = new SecureRandom();
    final var secret = "\"abc123SecretKeyPairBase64==\"";
    final var encrypted = PBKDFEncryption.encrypt(
        "correct horse".toCharArray(), secureRandom, secret, KeyDerivation.defaultArgon2id(), AAD
    );

    final var kdf = assertInstanceOf(Argon2id.class, encrypted.keyDerivation());

    assertEquals(Argon2id.ARGON2_MEMORY_KB, kdf.memoryKB());
    assertEquals(Argon2id.ARGON2_PARALLELISM, kdf.parallelism());
    assertEquals(Argon2id.ARGON2_ITERATIONS, kdf.iterations());
    assertEquals(PBKDFEncryption.SALT_BYTES, encrypted.salt().length);
    assertEquals(PBKDFEncryption.IV_BYTES, encrypted.iv().length);
    assertFalse(new String(encrypted.cipherText(), StandardCharsets.UTF_8).contains("abc123"));

    final byte[] decrypted = decryptArgon2("correct horse".toCharArray(), encrypted);
    assertArrayEquals(secret.getBytes(StandardCharsets.UTF_8), decrypted);
  }

  private static byte[] decryptArgon2(final char[] password, final Encrypted encrypted) throws Exception {
    final var kdf = assertInstanceOf(Argon2id.class, encrypted.keyDerivation());
    final var params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withSalt(encrypted.salt())
        .withMemoryAsKB(kdf.memoryKB())
        .withParallelism(kdf.parallelism())
        .withIterations(kdf.iterations())
        .build();
    final var generator = new org.bouncycastle.crypto.generators.Argon2BytesGenerator();
    generator.init(params);
    final byte[] keyBytes = new byte[PBKDFEncryption.KEY_BYTES];
    generator.generateBytes(new String(password).getBytes(StandardCharsets.UTF_8), keyBytes);
    final var secretKey = new SecretKeySpec(keyBytes, "AES");
    final var cipher = Cipher.getInstance(PBKDFEncryption.CIPHER);
    cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(PBKDFEncryption.GCM_TAG_BITS, encrypted.iv()));
    cipher.updateAAD(AAD);
    return cipher.doFinal(encrypted.cipherText());
  }
}
