package software.sava.core.accounts;

import software.sava.core.encoding.Base58;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;

public enum PrivateKeyEncoding {

  jsonKeyPairArray,
  base64PrivateKey,
  base64KeyPair,
  base58PrivateKey,
  base58KeyPair;

  public static Signer fromJsonArray(final String secret) {
    final var trimmed = secret.strip();
    final int keyPairLength = Signer.KEY_LENGTH * 2;
    final var keyPair = new byte[keyPairLength];
    int from = trimmed.indexOf('[') + 1;
    for (int i = 0; i < keyPairLength; ++i) {
      int to = trimmed.indexOf(',', from);
      if (to < 0) {
        to = trimmed.indexOf(']', from);
        if (to < 0) {
          to = trimmed.length();
        }
      }
      keyPair[i] = (byte) Integer.parseInt(trimmed, from, to, 10);
      from = to + 1;
    }
    return Signer.createFromKeyPair(keyPair);
  }

  public Signer parseSecret(final String secret) {
    return switch (this) {
      case jsonKeyPairArray -> fromJsonArray(secret);
      case base64PrivateKey -> Signer.createFromPrivateKey(Base64.getDecoder().decode(secret));
      case base64KeyPair -> Signer.createFromKeyPair(Base64.getDecoder().decode(secret));
      case base58PrivateKey -> Signer.createFromPrivateKey(Base58.decode(secret));
      case base58KeyPair -> Signer.createFromKeyPair(Base58.decode(secret));
    };
  }

  public static Signer fromProperties(final String prefix, final Properties properties) {
    final var resolvedPrefix = prefix == null || prefix.isBlank()
        ? ""
        : prefix.endsWith(".") ? prefix : prefix + ".";
    final var encodingValue = properties.getProperty(resolvedPrefix + "encoding");
    if (encodingValue == null || encodingValue.isBlank()) {
      throw new IllegalArgumentException("Missing required property: " + resolvedPrefix + "encoding");
    }
    final var encoding = PrivateKeyEncoding.valueOf(encodingValue.strip());
    final var secret = properties.getProperty(resolvedPrefix + "secret");
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("Missing required property: " + resolvedPrefix + "secret");
    }
    final var signer = encoding.parseSecret(secret.strip());
    final var pubKeyValue = properties.getProperty(resolvedPrefix + "pubKey");
    if (pubKeyValue != null && !pubKeyValue.isBlank()) {
      final var publicKey = PublicKey.fromBase58Encoded(pubKeyValue.strip());
      if (!publicKey.equals(signer.publicKey())) {
        throw new IllegalStateException(String.format("[expected=%s] != [derived=%s]", publicKey, signer.publicKey()));
      }
    }
    return signer;
  }

  public static Signer fromProperties(final Properties properties) {
    return fromProperties(null, properties);
  }

  // Authenticated-decryption parameters; must match those used to encrypt vanity key files
  // (software.sava.core.accounts.vanity.KeyFileEncryption).
  private static final String DEFAULT_KDF = "PBKDF2WithHmacSHA512";
  private static final String DEFAULT_CIPHER = "AES/GCM/NoPadding";
  private static final int KEY_BITS = 256;
  private static final int GCM_TAG_BITS = 128;

  /// Loads a [Signer] from properties that may contain an encrypted secret.
  ///
  /// When the {@code encrypted} property is {@code true}, the {@code secret} value is treated as a
  /// Base64 encoded AES/GCM cipher text decrypted using a PBKDF2 derived key before being
  /// parsed. The supplied {@code password} is required in that case. When the entry is not
  /// encrypted this behaves like [#fromProperties(String, Properties)] and the {@code password}
  /// argument is ignored.
  public static Signer fromProperties(final String prefix, final Properties properties, final char[] password) {
    final var resolvedPrefix = prefix == null || prefix.isBlank()
        ? ""
        : prefix.endsWith(".") ? prefix : prefix + ".";
    final var encryptedValue = properties.getProperty(resolvedPrefix + "encrypted");
    if (encryptedValue == null || !Boolean.parseBoolean(encryptedValue.strip())) {
      return fromProperties(prefix, properties);
    }
    if (password == null || password.length == 0) {
      throw new IllegalArgumentException("A password is required to decrypt an encrypted key file.");
    }
    final var encodingValue = properties.getProperty(resolvedPrefix + "encoding");
    if (encodingValue == null || encodingValue.isBlank()) {
      throw new IllegalArgumentException("Missing required property: " + resolvedPrefix + "encoding");
    }
    final var encoding = PrivateKeyEncoding.valueOf(encodingValue.strip());
    final var secret = decrypt(resolvedPrefix, properties, password);
    final var signer = encoding.parseSecret(unquote(secret.strip()));
    final var pubKeyValue = properties.getProperty(resolvedPrefix + "pubKey");
    if (pubKeyValue != null && !pubKeyValue.isBlank()) {
      final var publicKey = PublicKey.fromBase58Encoded(pubKeyValue.strip());
      if (!publicKey.equals(signer.publicKey())) {
        throw new IllegalStateException(String.format("[expected=%s] != [derived=%s]", publicKey, signer.publicKey()));
      }
    }
    return signer;
  }

  public static Signer fromProperties(final Properties properties, final char[] password) {
    return fromProperties(null, properties, password);
  }

  private static String requireProperty(final String resolvedPrefix, final Properties properties, final String name) {
    final var value = properties.getProperty(resolvedPrefix + name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing required property: " + resolvedPrefix + name);
    }
    return value.strip();
  }

  private static String decrypt(final String resolvedPrefix, final Properties properties, final char[] password) {
    final var kdfValue = properties.getProperty(resolvedPrefix + "kdf");
    final var kdf = kdfValue == null || kdfValue.isBlank() ? DEFAULT_KDF : kdfValue.strip();
    final var cipherValue = properties.getProperty(resolvedPrefix + "cipher");
    final var cipherName = cipherValue == null || cipherValue.isBlank() ? DEFAULT_CIPHER : cipherValue.strip();
    final var iterationsValue = requireProperty(resolvedPrefix, properties, "iterations");
    final int iterations = Integer.parseInt(iterationsValue);
    final var decoder = Base64.getDecoder();
    final byte[] salt = decoder.decode(requireProperty(resolvedPrefix, properties, "salt"));
    final byte[] iv = decoder.decode(requireProperty(resolvedPrefix, properties, "iv"));
    final byte[] cipherText = decoder.decode(requireProperty(resolvedPrefix, properties, "secret"));

    byte[] keyBytes = null;
    try {
      final var keySpec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
      try {
        final var factory = SecretKeyFactory.getInstance(kdf);
        keyBytes = factory.generateSecret(keySpec).getEncoded();
      } finally {
        keySpec.clearPassword();
      }
      final SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");
      final var cipher = Cipher.getInstance(cipherName);
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
      final byte[] plainBytes = cipher.doFinal(cipherText);
      try {
        return new String(plainBytes, StandardCharsets.UTF_8);
      } finally {
        Arrays.fill(plainBytes, (byte) 0);
      }
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException("Failed to decrypt key file.", e);
    } finally {
      if (keyBytes != null) {
        Arrays.fill(keyBytes, (byte) 0);
      }
    }
  }

  private static String unquote(final String value) {
    if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
