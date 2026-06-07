package software.sava.core.accounts.pbkdf;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PrivateKeyEncodingTests {

  private static PublicKey EXPECTED_PUB_KEY;

  private static byte[] KEY_PAIR;

  @BeforeAll
  static void setup() {
    KEY_PAIR = Signer.generatePrivateKeyPairBytes();
    final var signer = Signer.createFromKeyPair(KEY_PAIR);
    EXPECTED_PUB_KEY = signer.publicKey();
  }

  private static void verifySigner(final Signer signer) {
    assertEquals(EXPECTED_PUB_KEY, signer.publicKey());
  }

  // --- Encrypted properties tests ---

  // Mirrors how vanity key files are encrypted (PBKDF2WithHmacSHA512 + AES/GCM/NoPadding) so that
  // the round-trip through PrivateKeyEncoding.fromProperties(..., password) can be verified.
  private static Properties encryptedProperties(final byte[] payload, final char[] password) {
    final int iterations = 210_000;
    final byte[] salt = new byte[16];
    final byte[] iv = new byte[12];
    final var random = new SecureRandom();
    random.nextBytes(salt);
    random.nextBytes(iv);
    try {
      final var keySpec = new PBEKeySpec(password, salt, iterations, 256);
      final var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
      final byte[] keyBytes = factory.generateSecret(keySpec).getEncoded();
      keySpec.clearPassword();
      final SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");
      final var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
      cipher.updateAAD(EXPECTED_PUB_KEY.toByteArray());
      final byte[] cipherText = cipher.doFinal(payload);
      final var encoder = Base64.getEncoder();
      final var props = new Properties();
      props.setProperty("pubKey", EXPECTED_PUB_KEY.toBase58());
      props.setProperty("kdf", "PBKDF2WithHmacSHA512");
      props.setProperty("iterations", Integer.toString(iterations));
      props.setProperty("aad", encoder.encodeToString(EXPECTED_PUB_KEY.toByteArray()));
      props.setProperty("salt", encoder.encodeToString(salt));
      props.setProperty("iv", encoder.encodeToString(iv));
      props.setProperty("secret", encoder.encodeToString(cipherText));
      return props;
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void encryptedBase64KeyPairFromProperties() {
    final char[] password = "correct horse battery staple".toCharArray();
    final var props = encryptedProperties(KEY_PAIR, password);
    props.setProperty("pubKey", EXPECTED_PUB_KEY.toBase58());
    verifySigner(Signer.fromProperties(props, password));
  }

  @Test
  void encryptedFromPropertiesRequiresPassword() {
    final var props = encryptedProperties(KEY_PAIR, "pw".toCharArray());
    assertThrows(IllegalArgumentException.class, () -> Signer.fromProperties(props, null));
  }

  @Test
  void encryptedFromPropertiesWrongPassword() {
    final var props = encryptedProperties(KEY_PAIR, "right".toCharArray());
    assertThrows(IllegalStateException.class, () -> Signer.fromProperties(props, "wrong".toCharArray()));
  }

  @Test
  void encryptedFromPropertiesMissingPropertyFails() {
    for (final var missing : new String[]{"kdf", "iterations", "salt", "iv", "secret", "pubKey"}) {
      final var props = encryptedProperties(KEY_PAIR, "pw".toCharArray());
      props.remove(missing);
      if (missing.equals("pubKey")) {
        props.remove("aad");
      }
      assertThrows(IllegalArgumentException.class,
          () -> Signer.fromProperties(props, "pw".toCharArray()),
          "Expected failure when '" + missing + "' is missing"
      );
    }
  }

  @Test
  @ResourceLock("argon2id")
  void argon2EncryptedFromPropertiesMissingPropertyFails() {
    for (final var missing : new String[]{"memoryKB", "parallelism"}) {
      final var props = argon2EncryptedProperties(KEY_PAIR, "pw".toCharArray());
      props.remove(missing);
      assertThrows(IllegalArgumentException.class,
          () -> Signer.fromProperties(props, "pw".toCharArray()),
          "Expected failure when '" + missing + "' is missing"
      );
    }
  }

  private static Properties argon2EncryptedProperties(final byte[] payload, final char[] password) {
    final int memoryKb = 65_536;
    final int parallelism = 1;
    final int iterations = 3;
    final byte[] salt = new byte[16];
    final byte[] iv = new byte[12];
    final var random = new SecureRandom();
    random.nextBytes(salt);
    random.nextBytes(iv);
    try {
      final var params = new Argon2Parameters.Builder(
          Argon2Parameters.ARGON2_id)
          .withVersion(Argon2Parameters.ARGON2_VERSION_13)
          .withSalt(salt)
          .withMemoryAsKB(memoryKb)
          .withParallelism(parallelism)
          .withIterations(iterations)
          .build();
      final var generator = new Argon2BytesGenerator();
      generator.init(params);
      final byte[] keyBytes = new byte[32];
      generator.generateBytes(new String(password).getBytes(StandardCharsets.UTF_8), keyBytes);
      final SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");
      final var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
      cipher.updateAAD(EXPECTED_PUB_KEY.toByteArray());
      final byte[] cipherText = cipher.doFinal(payload);
      final var encoder = Base64.getEncoder();
      final var props = new Properties();
      props.setProperty("pubKey", EXPECTED_PUB_KEY.toBase58());
      props.setProperty("kdf", "Argon2id");
      props.setProperty("memoryKB", Integer.toString(memoryKb));
      props.setProperty("parallelism", Integer.toString(parallelism));
      props.setProperty("iterations", Integer.toString(iterations));
      props.setProperty("aad", encoder.encodeToString(EXPECTED_PUB_KEY.toByteArray()));
      props.setProperty("salt", encoder.encodeToString(salt));
      props.setProperty("cipher", "AES/GCM/NoPadding");
      props.setProperty("iv", encoder.encodeToString(iv));
      props.setProperty("secret", encoder.encodeToString(cipherText));
      return props;
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  @ResourceLock("argon2id")
  void argon2EncryptedBase64KeyPairFromProperties() {
    final char[] password = "correct horse battery staple".toCharArray();
    final var props = argon2EncryptedProperties(KEY_PAIR, password);
    props.setProperty("pubKey", EXPECTED_PUB_KEY.toBase58());
    verifySigner(Signer.fromProperties(props, password));
  }

  @Test
  @ResourceLock("argon2id")
  void argon2EncryptedFromPropertiesWrongPassword() {
    final var props = argon2EncryptedProperties(KEY_PAIR, "right".toCharArray());
    assertThrows(IllegalStateException.class, () -> Signer.fromProperties(props, "wrong".toCharArray()));
  }

  @Test
  void encryptedFromPropertiesLiteral() throws java.io.IOException {
    final var propsText = """
        pubKey=4bC4GcP7zzksyv9oeqJ3w7yHHLgkkpbsGKrmpYBLkr8U
        kdf=PBKDF2WithHmacSHA512
        iterations=210000
        aad=NVVPTFl+i9ZQ3b6Iq7KHFzCv2UkD+mFDUR5TGIoz7/0=
        salt=+OnLFicjZxX5UDQCwulAxA==
        cipher=AES/GCM/NoPadding
        iv=Mtl611cPAdqqBR4o
        secret=K/Mg2QuXKhhhrb6q37NoL7ZJe6zpWj9BXsTfvYgcRWWAq1UrfFY0yJPbfXIcFMxqOqu4gNygmt0G6mve3RoJgcHHsBRZMeDRodpQoMLU8dg=
        """;
    final var props = new Properties();
    props.load(new StringReader(propsText));
    final var signer = Signer.fromProperties(props, "asdf".toCharArray());
    assertEquals("4bC4GcP7zzksyv9oeqJ3w7yHHLgkkpbsGKrmpYBLkr8U", signer.publicKey().toBase58());
  }

  @Test
  @ResourceLock("argon2id")
  void argon2idEncryptedFromPropertiesLiteral() throws java.io.IOException {
    final var propsText = """
        pubKey=4bcagbEYKsdngabVBheRETcqrA9MYXEurdzAnk2J9BM3
        kdf=Argon2id
        iterations=3
        memoryKB=262144
        parallelism=4
        aad=NXEKIBcTEMP6g2MZdM13HFf1/CcRQHd7MnhMmmdPXSI=
        salt=BUDAce/8ez0Nne00lAQQGg==
        cipher=AES/GCM/NoPadding
        iv=a75VUXaxCkUNZ1rU
        secret=5ScUfduVAOsbEsH1+QRJwR76TKTEVLOK5m8/d5dvCHR8pax2i0alMLmUlpGOru6q/S+BZCP1qyZxCTOLP2TQaFZi06KLBaxvM48zeQ6YtEs=
        """;
    final var props = new Properties();
    props.load(new java.io.StringReader(propsText));
    final var signer = Signer.fromProperties(props, "asdf".toCharArray());
    assertEquals("4bcagbEYKsdngabVBheRETcqrA9MYXEurdzAnk2J9BM3", signer.publicKey().toBase58());
  }
}
