package software.sava.examples;

import software.sava.core.tx.TransactionSkeleton;
import software.sava.rpc.json.http.SolanaNetwork;
import software.sava.rpc.json.http.client.SolanaRpcClient;

import java.net.http.HttpClient;

public final class SimpleTxSkeletonExample {

  public static void main(final String[] args) {
    try (final var httpClient = HttpClient.newBuilder().build()) {
      final var rpcClient = SolanaRpcClient.createClient(SolanaNetwork.MAIN_NET.getEndpoint(), httpClient);

      // pump.fun buy/sell swap transaction.
      final var sig = "3uoR4BXmEo1PiXUrVAEC8kr6Hu619WsTU7obkUGxNAnpAhLgkLNC6WsWuKn7S9PTXWCt6er28ed6q9SpJbPiFLgC";
      final var tx = rpcClient.getTransaction(sig).join();

      if (tx.meta().logMessages().stream().anyMatch(log -> log.equals("Program log: Instruction: Buy"))) {
        final var skeleton = TransactionSkeleton.deserializeSkeleton(tx.data());
        final var instructions = skeleton.parseLegacyInstructions();
        // Instructions Array:
        // 0: Compute Budget: SetComputeUnitLimit
        // 1: Compute Budget: SetComputeUnitPrice
        // 2: System Program: transfer
        // 3: Pump.fun: buy
        // 4: Pump.fun: sell
        final var buyIx = instructions[3];
        final var buyAccounts = buyIx.accounts();
        System.out.format("""
                Buy on Pump.fun:
                 * mint: %s
                 * buyer: %s
                """,
            buyAccounts.get(2).publicKey(),
            buyAccounts.get(6).publicKey()
        );
      }
    }
  }

  private SimpleTxSkeletonExample() {
  }
}
