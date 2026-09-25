package software.sava.core.zk;

/// Byte lengths of ElGamal, Pedersen, and authenticated-encryption (AE) values.
public final class ElGamal {

  public static final int UNIT_LEN = 32;
  /// A compressed Ristretto point.
  public static final int RISTRETTO_POINT_LEN = UNIT_LEN;
  /// A Curve25519 scalar.
  public static final int SCALAR_LEN = UNIT_LEN;

  public static final int DECRYPT_HANDLE_LEN = RISTRETTO_POINT_LEN;

  public static final int PEDERSEN_COMMITMENT_LEN = RISTRETTO_POINT_LEN;

  public static final int ELGAMAL_CIPHERTEXT_LEN = PEDERSEN_COMMITMENT_LEN + DECRYPT_HANDLE_LEN;

  public static final int ELGAMAL_PUBKEY_LEN = RISTRETTO_POINT_LEN;

  public static final int ELGAMAL_SECRET_KEY_LEN = SCALAR_LEN;

  public static final int ELGAMAL_KEYPAIR_LEN = ELGAMAL_PUBKEY_LEN + ELGAMAL_SECRET_KEY_LEN;

  public static final int AE_KEY_LEN = 16;

  /// A complete AE ciphertext, nonce included.
  public static final int AE_CIPHERTEXT_LEN = 36;

  private ElGamal() {
  }
}
