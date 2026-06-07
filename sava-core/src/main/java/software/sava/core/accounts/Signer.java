package software.sava.core.accounts;

import org.bouncycastle.math.ec.rfc8032.Ed25519;
import software.sava.core.accounts.pbkdf.EncryptionEnvelope;
import software.sava.core.accounts.pbkdf.KeyDerivation;
import software.sava.core.accounts.pbkdf.PBKDFEncryption;
import software.sava.core.crypto.ed25519.Ed25519Util;
import software.sava.core.encoding.Base58;

import java.security.PrivateKey;
import java.util.Arrays;
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

  static EncryptionEnvelope encryptKey(final byte[] key, final char[] password, final KeyDerivation kdf) {
    final byte[] publicKey;
    if (key.length == KEY_LENGTH) {
      publicKey = new byte[Signer.KEY_LENGTH];
      Ed25519.generatePublicKey(key, 0, publicKey, 0);
    } else if (key.length == KEY_LENGTH << 1) {
      publicKey = Arrays.copyOfRange(key, Signer.KEY_LENGTH, Signer.KEY_LENGTH << 1);
    } else {
      throw new IllegalArgumentException("Invalid secret key or key pair length");
    }
    return PBKDFEncryption.encrypt(password, SECURE_RANDOM, key, kdf, publicKey);
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
      publicKey = PublicKey.fromBase64Encoded(aadValue.strip());
    } else {
      publicKey = PublicKey.fromBase58Encoded(pubKeyValue.strip());
    }
    final var secret = PBKDFEncryption.decrypt(resolvedPrefix, properties, password, publicKey.toByteArray());
    final Signer signer;
    if (secret.length == KEY_LENGTH) {
      signer = Signer.createFromPrivateKey(secret);
    } else if (secret.length == KEY_LENGTH << 1) {
      signer = Signer.createFromKeyPair(secret);
    } else {
      throw new IllegalArgumentException("Invalid private key or key pair length");
    }
    if (!publicKey.equals(signer.publicKey())) {
      throw new IllegalStateException(String.format("[expected=%s] != [derived=%s]", publicKey, signer.publicKey()));
    }
    return signer;
  }

  static Signer fromProperties(final Properties properties, final char[] password) {
    return fromProperties(null, properties, password);
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
