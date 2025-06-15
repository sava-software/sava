package software.sava.core.zk;

public final class ElGamal {

  public static final int UNIT_LEN = 32;
  /// Byte length of a compressed Ristretto point in Curve25519
  public static final int RISTRETTO_POINT_LEN = UNIT_LEN;
  /// Byte length of a scalar in Curve25519
  public static final int SCALAR_LEN = UNIT_LEN;

  /// Byte length of a decrypt handle
  public static final int DECRYPT_HANDLE_LEN = RISTRETTO_POINT_LEN;

  /// Byte length of a Pedersen commitment.
  public static final int PEDERSEN_COMMITMENT_LEN = RISTRETTO_POINT_LEN;

  /// Byte length of an ElGamal ciphertext
  public static final int ELGAMAL_CIPHERTEXT_LEN = PEDERSEN_COMMITMENT_LEN + DECRYPT_HANDLE_LEN;

  /// Byte length of an ElGamal public key
  public static final int ELGAMAL_PUBKEY_LEN = RISTRETTO_POINT_LEN;

  /// Byte length of an ElGamal secret key
  public static final int ELGAMAL_SECRET_KEY_LEN = SCALAR_LEN;

  /// Byte length of an ElGamal keypair
  public static final int ELGAMAL_KEYPAIR_LEN = ELGAMAL_PUBKEY_LEN + ELGAMAL_SECRET_KEY_LEN;

  /// Byte length of an authenticated encryption secret key
  public static final int AE_KEY_LEN = 16;

  /// Byte length of a complete authenticated encryption ciphertext component that includes the
  /// ciphertext and nonce components
  public static final int AE_CIPHERTEXT_LEN = 36;

  private ElGamal() {
  }
}
