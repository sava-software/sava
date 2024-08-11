package software.sava.core.accounts;

import org.bouncycastle.math.ec.rfc8032.Ed25519;
import software.sava.core.crypto.bip32.wallet.SolanaHdKeyGenerator;
import software.sava.core.crypto.ed25519.Ed25519Util;
import software.sava.core.encoding.Base58;

import java.security.PrivateKey;
import java.util.Arrays;
import java.util.List;

import static software.sava.core.crypto.SunCrypto.SECURE_RANDOM;

public interface Signer {

  int KEY_LENGTH = 32;

  static byte[] generatePrivateKeyBytes() {
    final var privateKey = new byte[Signer.KEY_LENGTH];
    SECURE_RANDOM.nextBytes(privateKey);
    return privateKey;
  }

  static void validateKeyPair(final byte[] privateKey,
                              final byte[] expectedPublicKey) {
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
    Arrays.fill(privateKey, (byte) 0);
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
    Arrays.fill(privateKey, (byte) 0);
    return new KeyPairSigner(
        Arrays.copyOfRange(publicKey, 0, Signer.KEY_LENGTH),
        copiedPrivateKey
    );
  }

  static Signer createFromKeyPair(final PublicKey publicKey, final PrivateKey privateKey) {
    return new KeyPairSigner(publicKey, privateKey);
  }

  static Signer fromBip44Mnemonic(final List<String> words, final String passphrase) {
    final byte[] seed = KeyPairSigner.toSeed(words, passphrase);
    final var hdAddress = SolanaHdKeyGenerator.getPrivateKeyFromBip44Seed(seed);
    return Signer.createFromKeyPair(hdAddress.publicKey().getRawKey(), hdAddress.privateKey().getRawKey());
  }

  static Signer fromBip44MnemonicWithChange(final List<String> words, final String passphrase) {
    final byte[] seed = KeyPairSigner.toSeed(words, passphrase);
    final var hdAddress = SolanaHdKeyGenerator.getPrivateKeyFromBip44SeedWithChange(seed);
    return Signer.createFromKeyPair(hdAddress.publicKey().getRawKey(), hdAddress.privateKey().getRawKey());
  }

  static Signer fromBip39Mnemonic(final List<String> words, final String passphrase) {
    return fromBip39Mnemonic(String.join(" ", words), passphrase);
  }

  static Signer fromBip39Mnemonic(final String words, final String passphrase) {
    final byte[] seed = KeyPairSigner.toSeed(words, passphrase);
    return Signer.createFromPrivateKey(seed);
  }

  PublicKey publicKey();

  PrivateKey privateKey();

  Signer createDedicatedSigner();

  int sign(final byte[] message,
           final int msgOffset,
           final int msgLen,
           final int outPos);

  byte[] sign(byte[] message, int msgOffset, int msgLen);

  byte[] sign(byte[] message);
}
