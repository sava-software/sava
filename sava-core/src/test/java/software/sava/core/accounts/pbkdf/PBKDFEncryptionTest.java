package software.sava.core.accounts.pbkdf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import software.sava.core.accounts.PublicKey;

import javax.crypto.AEADBadTagException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

final class PBKDFEncryptionTest {

  private static final byte[] AAD = PublicKey.fromBase58Encoded("4bcoVWVXfw6xKsEYdM6s7AeZQMgDG958kK5Uzhc2sw37").toByteArray();

  // Use the minimum supported PBKDF2 iteration count so these tests' key derivation stays fast.
  private static KeyDerivation minPBKDF2() {
    return KeyDerivation.createPBKDF2WithHmacSHA512(PBKDF2WithHmacSHA512.MIN_ITERATIONS);
  }

  @Test
  void roundTrip() {
    final var secureRandom = new SecureRandom();
    final var secret = new byte[64];
    secureRandom.nextBytes(secret);
    final var expectedSecret = Arrays.copyOf(secret, secret.length);
    final var kdf = minPBKDF2();
    final var encrypted = PBKDFEncryption.encrypt(
        "correct horse".toCharArray(), secureRandom, secret, kdf, AAD
    );

    assertEquals(PBKDFEncryption.SALT_BYTES, encrypted.salt().length);
    assertEquals(PBKDFEncryption.IV_BYTES, encrypted.iv().length);
    assertFalse(Arrays.equals(expectedSecret, encrypted.cipherText()));

    final byte[] decrypted = encrypted.decrypt("correct horse".toCharArray());
    assertArrayEquals(expectedSecret, decrypted);
  }

  @Test
  void wrongAadFails() {
    final var secureRandom = new SecureRandom();
    final var encrypted = PBKDFEncryption.encrypt(
        "right".toCharArray(), secureRandom, "some-secret".getBytes(StandardCharsets.UTF_8), minPBKDF2(), AAD
    );
    final var tamperedAad = "tampered-public-key".getBytes(StandardCharsets.UTF_8);
    final var runtimeEx = assertThrows(IllegalStateException.class, () -> PBKDFEncryption.decrypt(
            "right".toCharArray(),
            minPBKDF2(),
            tamperedAad,
            encrypted.salt(),
            encrypted.iv(),
            encrypted.cipherText()
        )
    );
    assertInstanceOf(AEADBadTagException.class, runtimeEx.getCause());
  }

  @Test
  void wrongPasswordFails() {
    final var secureRandom = new SecureRandom();
    final var encrypted = PBKDFEncryption.encrypt(
        "right".toCharArray(),
        secureRandom,
        "some-secret".getBytes(StandardCharsets.UTF_8),
        minPBKDF2(),
        AAD
    );
    final var runtimeEx = assertThrows(IllegalStateException.class, () -> encrypted.decrypt("wrong".toCharArray()));
    assertInstanceOf(AEADBadTagException.class, runtimeEx.getCause());
  }

  // Argon2id is memory-hard; run it sequentially so concurrent tests don't exhaust heap.
  @Test
  @ResourceLock("argon2id")
  void argon2RoundTrip() {
    final var secureRandom = new SecureRandom();
    final var secret = new byte[64];
    secureRandom.nextBytes(secret);
    final byte[] expectedSecret = Arrays.copyOf(secret, secret.length);
    final var encrypted = PBKDFEncryption.encrypt(
        "correct horse".toCharArray(), secureRandom, secret, KeyDerivation.defaultArgon2id(), AAD
    );

    final var kdf = assertInstanceOf(Argon2id.class, encrypted.keyDerivation());

    assertEquals(Argon2id.ARGON2_MEMORY_KB, kdf.memoryKB());
    assertEquals(Argon2id.ARGON2_PARALLELISM, kdf.parallelism());
    assertEquals(Argon2id.ARGON2_ITERATIONS, kdf.iterations());
    assertEquals(PBKDFEncryption.SALT_BYTES, encrypted.salt().length);
    assertEquals(PBKDFEncryption.IV_BYTES, encrypted.iv().length);
    assertFalse(Arrays.equals(expectedSecret, encrypted.cipherText()));

    final byte[] decrypted = encrypted.decrypt("correct horse".toCharArray());
    assertArrayEquals(expectedSecret, decrypted);
  }
}
