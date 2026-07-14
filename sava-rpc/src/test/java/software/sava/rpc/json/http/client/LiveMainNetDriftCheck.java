package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.JsonRpcException;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.fail;

/// Live smoke check against main-net to detect response shape drift between agave and the
/// hand-written response parsers. Rate limited requests are skipped, any other failure,
/// e.g. parsing, fails the check. Not part of the default test suite, run on demand:
///
/// `DRIFT_CHECK=true ./gradlew :sava-rpc:test --tests '*LiveMainNetDriftCheck'`
@EnabledIfEnvironmentVariable(named = "DRIFT_CHECK", matches = "true")
final class LiveMainNetDriftCheck {

  private static final PublicKey PYUSD_MINT = PublicKey.fromBase58Encoded("2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo");

  private final List<String> skipped = new ArrayList<>();
  private final List<String> failed = new ArrayList<>();

  private <T> T check(final String method, final Supplier<T> call) throws InterruptedException {
    try {
      final var result = call.get();
      System.out.println("[drift-check] " + method + " OK");
      return result;
    } catch (final CompletionException e) {
      if (e.getCause() instanceof JsonRpcException rpcException
          && String.valueOf(rpcException.getMessage()).contains("Too many requests")) {
        skipped.add(method);
        System.out.println("[drift-check] " + method + " rate limited, skipped");
      } else {
        failed.add(method + ": " + e.getCause());
      }
      return null;
    } catch (final RuntimeException e) {
      failed.add(method + ": " + e);
      return null;
    } finally {
      Thread.sleep(4_000);
    }
  }

  @Test
  void parseCurrentMainNetResponses() throws InterruptedException {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var rpcClient = SolanaRpcClient.createClient(
          URI.create("https://api.mainnet-beta.solana.com"),
          httpClient,
          Commitment.CONFIRMED
      );

      final var epochInfo = check("getEpochInfo", () -> rpcClient.getEpochInfo().join());
      check("getLatestBlockhash", () -> rpcClient.getLatestBlockHash().join());
      check("getSupply", () -> rpcClient.getSupply().join());
      check("getRecentPerformanceSamples", () -> rpcClient.getRecentPerformanceSamples(5).join());
      check("getRecentPrioritizationFees", () -> rpcClient.getRecentPrioritizationFees().join());
      check("getBlockProduction", () -> rpcClient.getBlockProduction().join());
      check("getClusterNodes", () -> rpcClient.getClusterNodes().join());
      check("getVoteAccounts", () -> rpcClient.getVoteAccounts().join());
      check("getMultipleAccounts", () -> rpcClient.getAccounts(List.of(PYUSD_MINT)).join());

      final var signatures = check("getSignaturesForAddress", () -> rpcClient.getSignaturesForAddress(PYUSD_MINT, 5).join());
      if (signatures != null) {
        check("getTransaction", () -> rpcClient.getTransaction(signatures.getFirst().signature()).join());
      }
      if (epochInfo != null) {
        check("getBlock", () -> rpcClient.getBlock(epochInfo.absoluteSlot() - 100).join());
      }

      if (!skipped.isEmpty()) {
        System.out.println("[drift-check] rate limited, re-run later: " + skipped);
      }
      if (!failed.isEmpty()) {
        fail("Response shape drift or unexpected errors: " + failed);
      }
    }
  }
}
