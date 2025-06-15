package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;

import static software.sava.core.zk.ElGamal.*;

public record ConfidentialMintBurn(byte[] confidentialSupply,
                                   byte[] decryptableSupply,
                                   PublicKey supplyElGamalPubKey,
                                   byte[] pendingBurn) implements MintTokenExtension {

  public static final int BYTES = ELGAMAL_CIPHERTEXT_LEN + AE_CIPHERTEXT_LEN + ELGAMAL_PUBKEY_LEN + ELGAMAL_CIPHERTEXT_LEN;

  public static ConfidentialMintBurn read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    int i = offset;

    final byte[] confidentialSupply = new byte[ELGAMAL_CIPHERTEXT_LEN];
    System.arraycopy(data, i, confidentialSupply, 0, confidentialSupply.length);
    i += confidentialSupply.length;

    final byte[] decryptableSupply = new byte[AE_CIPHERTEXT_LEN];
    System.arraycopy(data, i, decryptableSupply, 0, decryptableSupply.length);
    i += decryptableSupply.length;

    final var supplyElGamalPubKey = PublicKey.readPubKey(data, i);
    i += ELGAMAL_PUBKEY_LEN;

    final byte[] pendingBurn = new byte[ELGAMAL_CIPHERTEXT_LEN];
    System.arraycopy(data, i, pendingBurn, 0, pendingBurn.length);
    i += pendingBurn.length;

    return new ConfidentialMintBurn(
        confidentialSupply,
        decryptableSupply,
        supplyElGamalPubKey,
        pendingBurn
    );
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.ConfidentialMintBurn;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    int i = offset;
    System.arraycopy(confidentialSupply, 0, data, i, confidentialSupply.length);
    i += confidentialSupply.length;
    System.arraycopy(decryptableSupply, 0, data, i, decryptableSupply.length);
    i += decryptableSupply.length;
    supplyElGamalPubKey.write(data, i);
    i += ELGAMAL_PUBKEY_LEN;
    System.arraycopy(pendingBurn, 0, data, i, pendingBurn.length);
    return BYTES;
  }

  @Override
  public int l() {
    return BYTES;
  }
}
