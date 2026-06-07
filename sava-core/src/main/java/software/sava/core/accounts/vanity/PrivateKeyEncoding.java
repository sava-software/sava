package software.sava.core.accounts.vanity;

import software.sava.core.accounts.Signer;
import software.sava.core.encoding.Base58;

import java.util.Base64;

public enum PrivateKeyEncoding {

  jsonKeyPairArray,
  base64PrivateKey,
  base64KeyPair,
  base58PrivateKey,
  base58KeyPair;

  private static Signer fromJsonArray(final String secret) {
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
}
