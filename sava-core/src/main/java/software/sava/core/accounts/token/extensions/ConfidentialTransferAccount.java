package software.sava.core.accounts.token.extensions;

import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.ByteUtil;

import java.util.Arrays;
import java.util.Objects;

import static software.sava.core.zk.ElGamal.*;

public record ConfidentialTransferAccount(boolean approved,
                                          PublicKey elgamalPubkey,
                                          byte[] pendingBalanceLo,
                                          byte[] pendingBalanceHi,
                                          byte[] availableBalance,
                                          byte[] decryptableAvailableBalance,
                                          boolean allowConfidentialCredits,
                                          boolean allowNonConfidentialCredits,
                                          long pendingBalanceCreditCounter,
                                          long maximumPendingBalanceCreditCounter,
                                          long expectedPendingBalanceCreditCounter,
                                          long actualPendingBalanceCreditCounter) implements AccountTokenExtension {

  /// Maximum bit length of any deposit or transfer amount
  ///
  /// Any deposit or transfer amount must be less than `2^48`
  public static final long MAXIMUM_DEPOSIT_TRANSFER_AMOUNT = 65_535 + (1 << 16) * 4_294_967_295L;

  /// Bit length of the low bits of pending balance plaintext
  public static final int PENDING_BALANCE_LO_BIT_LENGTH = 16;

  /// The default maximum pending balance credit counter.
  public static final int DEFAULT_MAXIMUM_PENDING_BALANCE_CREDIT_COUNTER = 65_536;

  public static final int BYTES = 1
      + ELGAMAL_PUBKEY_LEN
      + ELGAMAL_CIPHERTEXT_LEN
      + ELGAMAL_CIPHERTEXT_LEN
      + ELGAMAL_CIPHERTEXT_LEN
      + AE_CIPHERTEXT_LEN
      + 2
      + Long.BYTES + Long.BYTES + Long.BYTES + Long.BYTES;

  public static ConfidentialTransferAccount read(final byte[] data, final int offset) {
    if (data == null || data.length == 0) {
      return null;
    }
    final boolean approved = data[offset] == 1;
    int i = offset + 1;
    final var elgamalPubkey = PublicKey.readPubKey(data, i);
    i += ELGAMAL_PUBKEY_LEN;

    final byte[] pendingBalanceLo = new byte[ELGAMAL_CIPHERTEXT_LEN];
    System.arraycopy(data, i, pendingBalanceLo, 0, pendingBalanceLo.length);
    i += pendingBalanceLo.length;

    final byte[] pendingBalanceHi = new byte[ELGAMAL_CIPHERTEXT_LEN];
    System.arraycopy(data, i, pendingBalanceHi, 0, pendingBalanceHi.length);
    i += pendingBalanceHi.length;

    final byte[] availableBalance = new byte[ELGAMAL_CIPHERTEXT_LEN];
    System.arraycopy(data, i, availableBalance, 0, availableBalance.length);
    i += availableBalance.length;

    final byte[] decryptableAvailableBalance = new byte[AE_CIPHERTEXT_LEN];
    System.arraycopy(data, i, decryptableAvailableBalance, 0, decryptableAvailableBalance.length);
    i += decryptableAvailableBalance.length;

    final boolean allowConfidentialCredits = data[i] == 1;
    ++i;
    final boolean allowNonConfidentialCredits = data[i] == 1;
    ++i;

    final long pendingBalanceCreditCounter = ByteUtil.getInt64LE(data, i);
    i += Long.BYTES;
    final long maximumPendingBalanceCreditCounter = ByteUtil.getInt64LE(data, i);
    i += Long.BYTES;
    final long expectedPendingBalanceCreditCounter = ByteUtil.getInt64LE(data, i);
    i += Long.BYTES;
    final long actualPendingBalanceCreditCounter = ByteUtil.getInt64LE(data, i);

    return new ConfidentialTransferAccount(
        approved,
        elgamalPubkey,
        pendingBalanceLo,
        pendingBalanceHi,
        availableBalance,
        decryptableAvailableBalance,
        allowConfidentialCredits,
        allowNonConfidentialCredits,
        pendingBalanceCreditCounter,
        maximumPendingBalanceCreditCounter,
        expectedPendingBalanceCreditCounter,
        actualPendingBalanceCreditCounter
    );
  }

  @Override
  public ExtensionType extensionType() {
    return ExtensionType.ConfidentialTransferAccount;
  }

  @Override
  public int write(final byte[] data, final int offset) {
    int i = offset;
    data[i] = (byte) (approved ? 1 : 0);
    ++i;

    elgamalPubkey.write(data, i);
    i += ELGAMAL_PUBKEY_LEN;

    System.arraycopy(pendingBalanceLo, 0, data, i, pendingBalanceLo.length);
    i += pendingBalanceLo.length;
    System.arraycopy(pendingBalanceHi, 0, data, i, pendingBalanceHi.length);
    i += pendingBalanceHi.length;
    System.arraycopy(availableBalance, 0, data, i, availableBalance.length);
    i += availableBalance.length;
    System.arraycopy(decryptableAvailableBalance, 0, data, i, decryptableAvailableBalance.length);
    i += decryptableAvailableBalance.length;

    data[i] = (byte) (allowConfidentialCredits ? 1 : 0);
    ++i;
    data[i] = (byte) (allowNonConfidentialCredits ? 1 : 0);
    ++i;

    ByteUtil.putInt64LE(data, i, pendingBalanceCreditCounter);
    i += Long.BYTES;
    ByteUtil.putInt64LE(data, i, maximumPendingBalanceCreditCounter);
    i += Long.BYTES;
    ByteUtil.putInt64LE(data, i, expectedPendingBalanceCreditCounter);
    i += Long.BYTES;
    ByteUtil.putInt64LE(data, i, actualPendingBalanceCreditCounter);

    return BYTES;
  }

  @Override
  public int l() {
    return BYTES;
  }

  @Override
  public boolean equals(final Object o) {
    return o instanceof ConfidentialTransferAccount other
        && approved == other.approved
        && Objects.equals(elgamalPubkey, other.elgamalPubkey)
        && Arrays.equals(pendingBalanceLo, other.pendingBalanceLo)
        && Arrays.equals(pendingBalanceHi, other.pendingBalanceHi)
        && Arrays.equals(availableBalance, other.availableBalance)
        && Arrays.equals(decryptableAvailableBalance, other.decryptableAvailableBalance)
        && allowConfidentialCredits == other.allowConfidentialCredits
        && allowNonConfidentialCredits == other.allowNonConfidentialCredits
        && pendingBalanceCreditCounter == other.pendingBalanceCreditCounter
        && maximumPendingBalanceCreditCounter == other.maximumPendingBalanceCreditCounter
        && expectedPendingBalanceCreditCounter == other.expectedPendingBalanceCreditCounter
        && actualPendingBalanceCreditCounter == other.actualPendingBalanceCreditCounter;
  }

  @Override
  public int hashCode() {
    int result = Boolean.hashCode(approved);
    result = 31 * result + Objects.hashCode(elgamalPubkey);
    result = 31 * result + Arrays.hashCode(pendingBalanceLo);
    result = 31 * result + Arrays.hashCode(pendingBalanceHi);
    result = 31 * result + Arrays.hashCode(availableBalance);
    result = 31 * result + Arrays.hashCode(decryptableAvailableBalance);
    result = 31 * result + Boolean.hashCode(allowConfidentialCredits);
    result = 31 * result + Boolean.hashCode(allowNonConfidentialCredits);
    result = 31 * result + Long.hashCode(pendingBalanceCreditCounter);
    result = 31 * result + Long.hashCode(maximumPendingBalanceCreditCounter);
    result = 31 * result + Long.hashCode(expectedPendingBalanceCreditCounter);
    result = 31 * result + Long.hashCode(actualPendingBalanceCreditCounter);
    return result;
  }

  @Override
  public String toString() {
    return "ConfidentialTransferAccount[approved=" + approved
        + ", elgamalPubkey=" + elgamalPubkey
        + ", allowConfidentialCredits=" + allowConfidentialCredits
        + ", allowNonConfidentialCredits=" + allowNonConfidentialCredits
        + ", pendingBalanceCreditCounter=" + pendingBalanceCreditCounter
        + ", maximumPendingBalanceCreditCounter=" + maximumPendingBalanceCreditCounter
        + ", expectedPendingBalanceCreditCounter=" + expectedPendingBalanceCreditCounter
        + ", actualPendingBalanceCreditCounter=" + actualPendingBalanceCreditCounter
        + ']';
  }
}
