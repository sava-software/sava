package software.sava.helius.demo;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.helius.rpc.json.HeliusRpc;
import software.sava.helius.rpc.json.request.TokenAccounts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

public final class Entrypoint {

  static void main(final String[] args) {
    final var rpcEndpoint = URI.create("https://mainnet.helius-rpc.com/?api-key=" + args[0]);

    final var account = PublicKey.fromBase58Encoded(args[1]);

    final boolean firstLastOnly = Boolean.parseBoolean(System.getProperty("firstLastOnly", "false"));
    final boolean trackWrappedSol = Boolean.parseBoolean(System.getProperty("trackWrappedSol", "false"));

    try (final var httpClient = HttpClient.newHttpClient()) {
      final var rpcClient = HeliusRpc.build()
          .httpClient(httpClient)
          .endpoint(rpcEndpoint)
          .compressResponses()
          .createClient();

      final var balanceFetcher = new BalanceFetcher(SolanaAccounts.MAIN_NET, rpcClient);

      final long startNanos = System.nanoTime();
      final BalanceReport balanceReport;
      if (firstLastOnly) {
        final var balances = balanceFetcher.firstCurrentDelta(account);
        balanceReport = BalanceReport.createReport(balances);
      } else {
        balanceReport = balanceFetcher.changeHistory(account, trackWrappedSol ? TokenAccounts.balanceChanged : TokenAccounts.none);
      }
      long durationNanos = System.nanoTime() - startNanos;
      if (balanceReport == null) {
        System.out.println("No balance history found for " + account);
      } else {
        final var stampedBalances = balanceReport.balances();
        System.out.format(
            "SOL Balance Report [account=%s] [fetchDuration=%sms] [numTxs=%d] [delta=%s]%n",
            account, NANOSECONDS.toMillis(durationNanos), stampedBalances.size(), balanceReport.delta().toPlainString()
        );
        if (firstLastOnly) {
          System.out.format(
              ""
          );
        } else {
          final var csv = new StringBuilder(stampedBalances.size() * 128);
          csv.append("slot,epochMillis,index,lamports");
          if (trackWrappedSol) {
            csv.append(",wrappedLamports\n");
          } else {
            csv.append('\n');
          }
          for (final var balance : stampedBalances) {
            csv.append(Long.toUnsignedString(balance.slot())).append(',')
                .append(Long.toUnsignedString(balance.epochMillis())).append(',')
                .append(balance.index()).append(',')
                .append(balance.lamports());
            if (trackWrappedSol) {
              csv.append(',').append(balance.wrappedLamports());
            }
            csv.append('\n');
          }
          final var csvString = csv.toString();
          final var outputDir = System.getProperty("outputDir", ".");
          try {
            final var outputPath = Paths.get(outputDir, account + "_balance_report.csv").toAbsolutePath();
            Files.createDirectories(outputPath.getParent());
            Files.writeString(
                outputPath,
                csvString,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
            );
            System.out.println("Report saved to " + outputPath.getFileName());
          } catch (final IOException e) {
            throw new UncheckedIOException(e);
          }
        }
      }
    }
  }
}
