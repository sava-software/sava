package software.sava.core.accounts;

import org.bouncycastle.math.ec.rfc8032.Ed25519;
import software.sava.core.accounts.pbkdf.EncryptionEnvelope;
import software.sava.core.accounts.pbkdf.KeyDerivation;
import software.sava.core.accounts.pbkdf.PBKDFEncryption;
import software.sava.core.crypto.ed25519.Ed25519Util;
import software.sava.core.encoding.Base58;

import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;

import static software.sava.core.crypto.SunCrypto.SECURE_RANDOM;

public interface Signer {

  int KEY_LENGTH = 32;

  static byte[] generatePrivateKeyBytes() {
    final var privateKey = new byte[Signer.KEY_LENGTH];
    SECURE_RANDOM.nextBytes(privateKey);
    return privateKey;
  }

  static void validateKeyPair(final byte[] privateKey, final byte[] expectedPublicKey) {
    final byte[] publicKey = new byte[Signer.KEY_LENGTH];
    Ed25519.generatePublicKey(privateKey, 0, publicKey, 0);
    if (!Arrays.equals(expectedPublicKey, publicKey)) {
      throw new IllegalStateException(String.format("%s <> %s", Base58.encode(expectedPublicKey), Base58.encode(publicKey)));
    }
    if (!Ed25519.validatePublicKeyFull(publicKey, 0)) {
      throw new IllegalStateException("Invalid public key " + Base58.encode(publicKey));
    }
  }

  static void validateKeyPair(final byte[] keyPair) {
    final byte[] publicKey = new byte[Signer.KEY_LENGTH];
    final byte[] privateKey = Arrays.copyOfRange(keyPair, 0, Signer.KEY_LENGTH);
    Ed25519Util.generatePublicKey(privateKey, publicKey);
    if (!Ed25519.validatePublicKeyFull(publicKey, 0)) {
      throw new IllegalStateException("Invalid public key " + Base58.encode(publicKey));
    }
    if (!Arrays.equals(keyPair, KEY_LENGTH, KEY_LENGTH << 1, publicKey, 0, KEY_LENGTH)) {
      throw new IllegalStateException(String.format("%s <> %s", Base58.encode(keyPair, KEY_LENGTH, KEY_LENGTH << 1), Base58.encode(publicKey)));
    }
  }

  static byte[] generatePrivateKeyPairBytes() {
    final var privateKey = new byte[Signer.KEY_LENGTH];
    SECURE_RANDOM.nextBytes(privateKey);
    final byte[] keyPair = new byte[Signer.KEY_LENGTH << 1];
    Ed25519Util.generatePublicKey(privateKey, 0, keyPair, Signer.KEY_LENGTH);
    System.arraycopy(privateKey, 0, keyPair, 0, Signer.KEY_LENGTH);
    validateKeyPair(keyPair);
    return keyPair;
  }

  static byte[] createKeyPairBytesFromPrivateKey(final byte[] privateKey) {
    final byte[] keyPair = new byte[Signer.KEY_LENGTH << 1];
    Ed25519Util.generatePublicKey(privateKey, 0, keyPair, Signer.KEY_LENGTH);
    System.arraycopy(privateKey, 0, keyPair, 0, Signer.KEY_LENGTH);
    validateKeyPair(keyPair);
    return keyPair;
  }

  static Signer createFromPrivateKey(final byte[] privateKey) {
    final byte[] copiedPrivateKey = Arrays.copyOfRange(privateKey, 0, Signer.KEY_LENGTH);
    final byte[] publicKey = new byte[Signer.KEY_LENGTH];
    Ed25519Util.generatePublicKey(copiedPrivateKey, 0, publicKey, 0);
    validateKeyPair(copiedPrivateKey, publicKey);
    return new KeyPairSigner(publicKey, copiedPrivateKey);
  }

  static Signer createFromKeyPair(final byte[] keyPair) {
    final var privateKey = Arrays.copyOfRange(keyPair, 0, Signer.KEY_LENGTH);
    final var publicKey = Arrays.copyOfRange(keyPair, Signer.KEY_LENGTH, Signer.KEY_LENGTH << 1);
    validateKeyPair(privateKey, publicKey);
    return new KeyPairSigner(publicKey, privateKey);
  }

  static Signer createFromKeyPair(final byte[] publicKey, final byte[] privateKey) {
    validateKeyPair(privateKey, publicKey);
    final var copiedPrivateKey = Arrays.copyOfRange(privateKey, 0, Signer.KEY_LENGTH);
    return new KeyPairSigner(
        Arrays.copyOfRange(publicKey, 0, Signer.KEY_LENGTH),
        copiedPrivateKey
    );
  }

  static Signer createFromKeyPair(final PublicKey publicKey, final PrivateKey privateKey) {
    return new KeyPairSigner(publicKey, privateKey);
  }

  static EncryptionEnvelope encryptKeyPair(final byte[] keyPair, final char[] password, final KeyDerivation kdf) {
    final var publicKey = Arrays.copyOfRange(keyPair, Signer.KEY_LENGTH, Signer.KEY_LENGTH << 1);
    return PBKDFEncryption.encrypt(password, SECURE_RANDOM, keyPair, kdf, publicKey);
  }

  static Signer fromProperties(final String prefix, final Properties properties, final char[] password) {
    final var resolvedPrefix = prefix == null || prefix.isBlank()
        ? ""
        : prefix.endsWith(".") ? prefix : prefix + ".";
    if (password == null || password.length == 0) {
      throw new IllegalArgumentException("A password is required to decrypt an encrypted key file.");
    }
    final PublicKey publicKey;
    final var pubKeyValue = properties.getProperty(resolvedPrefix + "pubKey");
    if (pubKeyValue == null || pubKeyValue.isBlank()) {
      final var aadValue = properties.getProperty(resolvedPrefix + "aad");
      if (aadValue == null || aadValue.isBlank()) {
        throw new IllegalArgumentException(String.format("The public key must be provided by %spubKey or %saad", resolvedPrefix, resolvedPrefix));
      }
      publicKey = PublicKey.fromBase64Encoded(aadValue);
    } else {
      publicKey = PublicKey.fromBase58Encoded(pubKeyValue.strip());
    }
    final var secret = decrypt(resolvedPrefix, properties, password, publicKey.toByteArray());
    final var signer = Signer.createFromKeyPair(secret);
    if (!publicKey.equals(signer.publicKey())) {
      throw new IllegalStateException(String.format("[expected=%s] != [derived=%s]", publicKey, signer.publicKey()));
    }
    return signer;
  }

  static Signer fromProperties(final Properties properties, final char[] password) {
    return fromProperties(null, properties, password);
  }

  private static byte[] decrypt(final String resolvedPrefix,
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
    } else {
      keyDerivation = KeyDerivation.createPBKDF2WithHmacSHA512(iterations);
    }
    return PBKDFEncryption.decrypt(password, keyDerivation, aad, salt, iv, cipherText);
  }

  private static String requireProperty(final String resolvedPrefix, final Properties properties, final String name) {
    final var value = properties.getProperty(resolvedPrefix + name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing required property: " + resolvedPrefix + name);
    }
    return value.strip();
  }

  PublicKey publicKey();

  PrivateKey privateKey();

  Signer createDedicatedSigner();

  int sign(final byte[] message,
           final int msgOffset,
           final int msgLen,
           final int outPos);

  byte[] sign(final byte[] message, final int msgOffset, final int msgLen);

  byte[] sign(final byte[] message);
}
