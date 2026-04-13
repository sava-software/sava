package software.sava.helius.demo;

import software.sava.core.util.LamportDecimal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

record BalanceReport(List<StampedBalance> balances,
                     Instant firstTx,
                     Instant lastTx,
                     BigDecimal delta) {

  static BalanceReport createReport(final List<StampedBalance> balances) {
    final int numBalances = balances.size();
    if (numBalances == 0) {
      return null;
    } else {
      final var firstBalance = balances.getFirst();
      final var firstTimestamp = Instant.ofEpochSecond(firstBalance.epochMillis());
      if (numBalances == 1) {
        return new BalanceReport(balances, firstTimestamp, firstTimestamp, BigDecimal.ZERO);
      } else {
        final var lastBalance = balances.getLast();
        final var lastTimestamp = Instant.ofEpochSecond(lastBalance.epochMillis());
        final var lamportDelta = new BigDecimal(Long.toUnsignedString(lastBalance.totalLamports()))
            .subtract(new BigDecimal(Long.toUnsignedString(firstBalance.totalLamports())));
        final var delta = LamportDecimal.toBigDecimal(lamportDelta).stripTrailingZeros();
        return new BalanceReport(balances, firstTimestamp, lastTimestamp, delta);
      }
    }
  }
}
