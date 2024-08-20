package software.sava.core.accounts;

import software.sava.core.tx.Transaction;

import java.security.*;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.InvalidKeySpecException;

import static java.security.spec.NamedParameterSpec.ED25519;
import static software.sava.core.crypto.SunCrypto.EdDSA_KEY_FACTORY;
import static software.sava.core.crypto.SunCrypto.SUN_EC_PROVIDER;

final class KeyPairSigner implements Signer {

  private final PublicKey publicKey;
  private final PrivateKey privateKey;
  private final Signature signature;

  KeyPairSigner(final byte[] publicKey, final byte[] privateKey) {
    this(new PublicKeyBytes(publicKey), generatePrivateKey(privateKey));
  }

  KeyPairSigner(final PublicKey publicKey, final PrivateKey privateKey) {
    this.publicKey = publicKey;
    this.privateKey = privateKey;
    this.signature = createSignature(this.privateKey);
  }

  static PrivateKey generatePrivateKey(final byte[] privateKey) {
    try {
      final var privateKeySpec = new EdECPrivateKeySpec(ED25519, privateKey);
      return EdDSA_KEY_FACTORY.generatePrivate(privateKeySpec);
    } catch (final InvalidKeySpecException e) {
      throw new RuntimeException(e);
    }
  }

  private KeyPairSigner(final PublicKey publicKey,
                        final PrivateKey privateKey,
                        final Signature signature) {
    this.publicKey = publicKey;
    this.privateKey = privateKey;
    this.signature = signature;
  }

  private static Signature createSignature(final PrivateKey privateKey) {
    try {
      final var signature = Signature.getInstance(ED25519.getName(), SUN_EC_PROVIDER);
      signature.initSign(privateKey);
      return signature;
    } catch (final NoSuchAlgorithmException | InvalidKeyException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public PublicKey publicKey() {
    return publicKey;
  }

  @Override
  public PrivateKey privateKey() {
    return privateKey;
  }

  @Override
  public Signer createDedicatedSigner() {
    return new KeyPairSigner(publicKey, privateKey, createSignature(privateKey));
  }

  @Override
  public int sign(final byte[] message, final int msgOffset, final int msgLen, final int outPos) {
    try {
      signature.update(message, msgOffset, msgLen);
      signature.sign(message, outPos, Transaction.SIGNATURE_LENGTH);
      return outPos + Transaction.SIGNATURE_LENGTH;
    } catch (final SignatureException ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  public byte[] sign(final byte[] message, final int msgOffset, final int msgLen) {
    try {
      signature.update(message, msgOffset, msgLen);
      return signature.sign();
    } catch (final SignatureException ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  public byte[] sign(final byte[] message) {
    return sign(message, 0, message.length);
  }
}