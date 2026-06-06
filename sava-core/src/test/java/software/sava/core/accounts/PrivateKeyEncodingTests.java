package software.sava.core.accounts;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.sava.core.encoding.Base58;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PrivateKeyEncodingTests {

  private static String EXPECTED_PUB_KEY;

  private static String JSON_ARRAY;
  private static String BASE64_KEY_PAIR;
  private static String BASE64_PRIVATE_KEY;
  private static String BASE58_KEY_PAIR;
  private static String BASE58_PRIVATE_KEY;

  @BeforeAll
  static void setup() {
    final byte[] keyPair = Signer.generatePrivateKeyPairBytes();
    final byte[] privateKey = Arrays.copyOfRange(keyPair, 0, Signer.KEY_LENGTH);
    final var signer = Signer.createFromKeyPair(keyPair);
    EXPECTED_PUB_KEY = signer.publicKey().toBase58();

    final var joiner = new StringJoiner(",", "[", "]");
    for (final byte b : keyPair) {
      joiner.add(Integer.toString(Byte.toUnsignedInt(b)));
    }
    JSON_ARRAY = joiner.toString();

    BASE64_KEY_PAIR = Base64.getEncoder().encodeToString(keyPair);
    BASE64_PRIVATE_KEY = Base64.getEncoder().encodeToString(privateKey);
    BASE58_KEY_PAIR = Base58.encode(keyPair);
    BASE58_PRIVATE_KEY = Base58.encode(privateKey);
  }

  private static void verifySigner(final Signer signer) {
    assertEquals(EXPECTED_PUB_KEY, signer.publicKey().toBase58());
  }


  // --- Properties tests ---

  @Test
  void jsonKeyPairArrayFromProperties() {
    final var props = new Properties();
    props.setProperty("encoding", "jsonKeyPairArray");
    props.setProperty("secret", JSON_ARRAY);
    verifySigner(PrivateKeyEncoding.fromProperties(props));
  }

  @Test
  void base64PrivateKeyFromProperties() {
    final var props = new Properties();
    props.setProperty("encoding", "base64PrivateKey");
    props.setProperty("secret", BASE64_PRIVATE_KEY);
    verifySigner(PrivateKeyEncoding.fromProperties(props));
  }

  @Test
  void base64KeyPairFromProperties() {
    final var props = new Properties();
    props.setProperty("encoding", "base64KeyPair");
    props.setProperty("secret", BASE64_KEY_PAIR);
    verifySigner(PrivateKeyEncoding.fromProperties(props));
  }

  @Test
  void base58PrivateKeyFromProperties() {
    final var props = new Properties();
    props.setProperty("encoding", "base58PrivateKey");
    props.setProperty("secret", BASE58_PRIVATE_KEY);
    verifySigner(PrivateKeyEncoding.fromProperties(props));
  }

  @Test
  void base58KeyPairFromProperties() {
    final var props = new Properties();
    props.setProperty("encoding", "base58KeyPair");
    props.setProperty("secret", BASE58_KEY_PAIR);
    verifySigner(PrivateKeyEncoding.fromProperties(props));
  }

  @Test
  void fromPropertiesWithPrefix() {
    final var props = new Properties();
    props.setProperty("signer.encoding", "base58PrivateKey");
    props.setProperty("signer.secret", BASE58_PRIVATE_KEY);
    props.setProperty("signer.pubKey", EXPECTED_PUB_KEY);
    verifySigner(PrivateKeyEncoding.fromProperties("signer", props));
  }

  @Test
  void fromPropertiesWithDotSuffixedPrefix() {
    final var props = new Properties();
    props.setProperty("signer.encoding", "base64KeyPair");
    props.setProperty("signer.secret", BASE64_KEY_PAIR);
    verifySigner(PrivateKeyEncoding.fromProperties("signer.", props));
  }

  @Test
  void fromPropertiesWithPubKeyValidation() {
    final var props = new Properties();
    props.setProperty("encoding", "base58KeyPair");
    props.setProperty("secret", BASE58_KEY_PAIR);
    props.setProperty("pubKey", EXPECTED_PUB_KEY);
    verifySigner(PrivateKeyEncoding.fromProperties(props));
  }

  @Test
  void fromPropertiesMissingEncoding() {
    final var props = new Properties();
    props.setProperty("secret", BASE58_PRIVATE_KEY);
    assertThrows(IllegalArgumentException.class, () -> PrivateKeyEncoding.fromProperties(props));
  }

  @Test
  void fromPropertiesMissingSecret() {
    final var props = new Properties();
    props.setProperty("encoding", "base58PrivateKey");
    assertThrows(IllegalArgumentException.class, () -> PrivateKeyEncoding.fromProperties(props));
  }

  @Test
  void fromPropertiesWrongPubKey() {
    final var props = new Properties();
    props.setProperty("encoding", "base58PrivateKey");
    props.setProperty("secret", BASE58_PRIVATE_KEY);
    props.setProperty("pubKey", "11111111111111111111111111111111");
    assertThrows(IllegalStateException.class, () -> PrivateKeyEncoding.fromProperties(props));
  }

  // --- Encrypted properties tests ---

  // Mirrors how vanity key files are encrypted (PBKDF2WithHmacSHA512 + AES/GCM/NoPadding) so that
  // the round-trip through PrivateKeyEncoding.fromProperties(..., password) can be verified.
  private static Properties encryptedProperties(final String prefix,
                                                final String encoding,
                                                final String payload,
                                                final char[] password) {
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
      final byte[] cipherText = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
      final var encoder = Base64.getEncoder();
      final var props = new Properties();
      props.setProperty(prefix + "encoding", encoding);
      props.setProperty(prefix + "encrypted", "true");
      props.setProperty(prefix + "kdf", "PBKDF2WithHmacSHA512");
      props.setProperty(prefix + "iterations", Integer.toString(iterations));
      props.setProperty(prefix + "salt", encoder.encodeToString(salt));
      props.setProperty(prefix + "cipher", "AES/GCM/NoPadding");
      props.setProperty(prefix + "iv", encoder.encodeToString(iv));
      props.setProperty(prefix + "secret", encoder.encodeToString(cipherText));
      return props;
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void encryptedBase64KeyPairFromProperties() {
    final char[] password = "correct horse battery staple".toCharArray();
    final var props = encryptedProperties("", "base64KeyPair", '"' + BASE64_KEY_PAIR + '"', password);
    props.setProperty("pubKey", EXPECTED_PUB_KEY);
    verifySigner(PrivateKeyEncoding.fromProperties(props, password));
  }

  @Test
  void encryptedJsonKeyPairArrayFromProperties() {
    final char[] password = "hunter2".toCharArray();
    final var props = encryptedProperties("", "jsonKeyPairArray", JSON_ARRAY, password);
    verifySigner(PrivateKeyEncoding.fromProperties(props, password));
  }

  @Test
  void encryptedFromPropertiesWithPrefix() {
    final char[] password = "swordfish".toCharArray();
    final var props = encryptedProperties("signer.", "base58PrivateKey", '"' + BASE58_PRIVATE_KEY + '"', password);
    verifySigner(PrivateKeyEncoding.fromProperties("signer", props, password));
  }

  @Test
  void encryptedFromPropertiesRequiresPassword() {
    final var props = encryptedProperties("", "base64KeyPair", '"' + BASE64_KEY_PAIR + '"', "pw".toCharArray());
    assertThrows(IllegalArgumentException.class, () -> PrivateKeyEncoding.fromProperties(props, null));
  }

  @Test
  void encryptedFromPropertiesWrongPassword() {
    final var props = encryptedProperties("", "base64KeyPair", '"' + BASE64_KEY_PAIR + '"', "right".toCharArray());
    assertThrows(IllegalStateException.class, () -> PrivateKeyEncoding.fromProperties(props, "wrong".toCharArray()));
  }

  @Test
  void unencryptedFromPropertiesWithPasswordArg() {
    final var props = new Properties();
    props.setProperty("encoding", "base64KeyPair");
    props.setProperty("secret", BASE64_KEY_PAIR);
    verifySigner(PrivateKeyEncoding.fromProperties(props, "ignored".toCharArray()));
  }

  @Test
  void encryptedFromPropertiesLiteral() throws java.io.IOException {
    final var propsText = """
        pubKey=4bcoVWVXfw6xKsEYdM6s7AeZQMgDG958kK5Uzhc2sw37
        encoding=base64KeyPair
        encrypted=true
        kdf=PBKDF2WithHmacSHA512
        iterations=210000
        salt=06MhP/SqfiBN5pSOoj7fOw==
        cipher=AES/GCM/NoPadding
        iv=VOntOEOa1Gz0x560
        secret=8BO9O/HjnIp9gyQrY6XrJ4yqPRbW7s4HhPWlQMRJ17hlJ9HeAMv5qpLD1RX+XlYlF1pyzX5KdZ0qpqu9HbKngtUrdN/VbBrd3pjfvJfxrBN3gYNiD+fao3aBKvTMGpNsACe9bGfO3fWGWQ==
        """;
    final var props = new Properties();
    props.load(new java.io.StringReader(propsText));
    final var signer = PrivateKeyEncoding.fromProperties(props, "asdf".toCharArray());
    assertEquals("4bcoVWVXfw6xKsEYdM6s7AeZQMgDG958kK5Uzhc2sw37", signer.publicKey().toBase58());
  }

  // --- parseSecret tests ---

  @Test
  void parseSecretJsonKeyPairArray() {
    verifySigner(PrivateKeyEncoding.jsonKeyPairArray.parseSecret(JSON_ARRAY));
  }

  @Test
  void parseSecretBase64PrivateKey() {
    verifySigner(PrivateKeyEncoding.base64PrivateKey.parseSecret(BASE64_PRIVATE_KEY));
  }

  @Test
  void parseSecretBase64KeyPair() {
    verifySigner(PrivateKeyEncoding.base64KeyPair.parseSecret(BASE64_KEY_PAIR));
  }

  @Test
  void parseSecretBase58PrivateKey() {
    verifySigner(PrivateKeyEncoding.base58PrivateKey.parseSecret(BASE58_PRIVATE_KEY));
  }

  @Test
  void parseSecretBase58KeyPair() {
    verifySigner(PrivateKeyEncoding.base58KeyPair.parseSecret(BASE58_KEY_PAIR));
  }
}
