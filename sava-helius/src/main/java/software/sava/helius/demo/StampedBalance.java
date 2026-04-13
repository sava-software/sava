package software.sava.helius.demo;

import software.sava.core.accounts.PublicKey;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.helius.rpc.json.response.TxFull;

import java.util.Objects;

record StampedBalance(long slot,
                      long epochMillis,
                      int index,
                      long lamports,
                      long wrappedLamports) implements Comparable<StampedBalance> {

  static StampedBalance createBalance(final PublicKey account, final PublicKey wrappedSolMint, final TxFull tx) {
    final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.data());
    final int accountIndex = skeleton.accountIndex(account.toByteArray());
    final long lamports;
    if (accountIndex < 0) {
      if (skeleton.isVersioned()) {
        throw new UnsupportedOperationException("Accounts indexed into lookup tables are not supported.");
      } else {
        lamports = -1;
      }
    } else {
      lamports = tx.meta().postBalances().get(accountIndex);
    }
    final long wrappedSolBalance = tx.meta().postTokenBalances().stream()
        .filter(tokenBalance -> tokenBalance.mint().equals(wrappedSolMint) && Objects.equals(tokenBalance.owner(), account))
        .mapToLong(tokenBalance -> tokenBalance.amount().longValueExact())
        .findFirst().orElse(-1L);
    if (lamports == -1 && wrappedSolBalance == -1) {
      return null; // Unrelated Token Account Transaction.
    } else {
      return new StampedBalance(tx.slot(), tx.blockTime().orElse(0L), tx.transactionIndex(), lamports, wrappedSolBalance);
    }
  }

  @Override
  public int compareTo(final StampedBalance o) {
    final int slotCompare = Long.compare(this.slot, o.slot);
    return slotCompare == 0 ? Long.compare(this.index, o.index) : slotCompare;
  }
}
