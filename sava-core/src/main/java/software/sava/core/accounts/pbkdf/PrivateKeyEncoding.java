package software.sava.core.accounts.pbkdf;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.encoding.Base58;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
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

  /// Loads a [Signer] from properties that may contain an encrypted secret.
  ///
  /// When the {@code encrypted} property is {@code true}, the {@code secret} value is treated as a
  /// Base64 encoded AES/GCM cipher text decrypted using a key derived from the password before
  /// being parsed. All encryption metadata properties are required and an
  /// [IllegalArgumentException] is thrown if any are missing: {@code pubKey}, {@code encoding},
  /// {@code kdf}, {@code cipher}, {@code iterations}, {@code salt}, {@code iv} and {@code secret}.
  /// The {@code kdf} property selects the derivation function: {@code Argon2id} additionally
  /// requires {@code memoryKB} and {@code parallelism}, while {@code PBKDF2WithHmacSHA512} uses
  /// only {@code iterations}.
  public static Signer fromProperties(final String prefix, final Properties properties, final char[] password) {
    final var resolvedPrefix = prefix == null || prefix.isBlank()
        ? ""
        : prefix.endsWith(".") ? prefix : prefix + ".";
    if (password == null || password.length == 0) {
      throw new IllegalArgumentException("A password is required to decrypt an encrypted key file.");
    }
    final var encodingValue = properties.getProperty(resolvedPrefix + "encoding");
    if (encodingValue == null || encodingValue.isBlank()) {
      throw new IllegalArgumentException("Missing required property: " + resolvedPrefix + "encoding");
    }
    final var encoding = PrivateKeyEncoding.valueOf(encodingValue.strip());
    // The public key is bound as GCM additional authenticated data (AAD) when the file is written,
    // so it is required to authenticate and decrypt the secret.
    final var pubKeyValue = properties.getProperty(resolvedPrefix + "pubKey");
    if (pubKeyValue == null || pubKeyValue.isBlank()) {
      throw new IllegalArgumentException("Missing required property: " + resolvedPrefix + "pubKey");
    }
    final var pubKey = pubKeyValue.strip();
    final var aad = pubKey.getBytes(StandardCharsets.UTF_8);
    final var secret = decrypt(resolvedPrefix, properties, password, aad);
    final var signer = encoding.parseSecret(unquote(secret.strip()));
    final var publicKey = PublicKey.fromBase58Encoded(pubKey);
    if (!publicKey.equals(signer.publicKey())) {
      throw new IllegalStateException(String.format("[expected=%s] != [derived=%s]", publicKey, signer.publicKey()));
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

  private static String decrypt(final String resolvedPrefix,
                                final Properties properties,
                                final char[] password,
                                final byte[] aad) {
    final var kdf = requireProperty(resolvedPrefix, properties, "kdf");
    final var cipherName = requireProperty(resolvedPrefix, properties, "cipher");
    final var decoder = Base64.getDecoder();
    final byte[] salt = decoder.decode(requireProperty(resolvedPrefix, properties, "salt"));
    final byte[] iv = decoder.decode(requireProperty(resolvedPrefix, properties, "iv"));
    final byte[] cipherText = decoder.decode(requireProperty(resolvedPrefix, properties, "secret"));

    byte[] keyBytes = null;
    try {
      keyBytes = deriveKey(resolvedPrefix, properties, kdf, password, salt);
      final var secretKey = new SecretKeySpec(keyBytes, "AES");
      final var cipher = Cipher.getInstance(cipherName);
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(PBKDFEncryption.GCM_TAG_BITS, iv));
      if (aad != null && aad.length > 0) {
        cipher.updateAAD(aad);
      }
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

  private static byte[] deriveKey(final String resolvedPrefix,
                                  final Properties properties,
                                  final String kdf,
                                  final char[] password,
                                  final byte[] salt) {
    final int iterations = Integer.parseInt(requireProperty(resolvedPrefix, properties, "iterations"));
    final KeyDerivation keyDerivation;
    if (kdf.equalsIgnoreCase("argon2id")) {
      final int memoryKb = Integer.parseInt(requireProperty(resolvedPrefix, properties, "memoryKB"));
      final int parallelism = Integer.parseInt(requireProperty(resolvedPrefix, properties, "parallelism"));
      keyDerivation = KeyDerivation.createArgon2id(memoryKb, parallelism, iterations);
    } else {
      keyDerivation = KeyDerivation.createPBKDF2WithHmacSHA512(iterations);
    }
    return keyDerivation.derive(password, salt);
  }

  private static String unquote(final String value) {
    if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
