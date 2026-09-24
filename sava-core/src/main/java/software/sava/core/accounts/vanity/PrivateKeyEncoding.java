package software.sava.core.accounts.vanity;

import software.sava.core.accounts.Signer;
import software.sava.core.encoding.Base58;

import java.util.Base64;

public enum PrivateKeyEncoding {

  /// A JSON array containing the 64 unsigned key-pair bytes, as the Solana CLI writes it.
  /// [#parseSecret(String)] reads it as solana-sdk's `read_keypair` does: the trimmed input must
  /// be one array of exactly 64 integers from 0 to 255, with whitespace allowed around each, or it
  /// throws `IllegalArgumentException`.
  jsonKeyPairArray,
  base64PrivateKey,
  base64KeyPair,
  base58PrivateKey,
  base58KeyPair;

  private static Signer fromJsonArray(final String secret) {
    final var trimmed = secret.strip();
    final int last = trimmed.length() - 1;
    if (last < 1 || trimmed.charAt(0) != '[' || trimmed.charAt(last) != ']') {
      throw new IllegalArgumentException("Input must be a JSON array");
    }
    final var elements = trimmed.substring(1, last).split(",", -1);
    final var keyPair = new byte[Signer.KEY_LENGTH << 1];
    if (elements.length != keyPair.length) {
      throw new IllegalArgumentException("Expected " + keyPair.length + " elements, found " + elements.length);
    }
    for (int i = 0; i < keyPair.length; ++i) {
      final int value = Integer.parseInt(elements[i].strip());
      if (value < 0 || value > 0xFF) {
        throw new IllegalArgumentException("Element " + i + " must be 0 to 255, found " + value);
      }
      keyPair[i] = (byte) value;
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
}
