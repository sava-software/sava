package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.sava.core.accounts.Signer;
import software.sava.core.encoding.Base58;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.core.tx.TxBuilder;
import software.sava.rpc.json.http.request.BlockTxDetails;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.Tx;

import java.net.URI;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// First-party defensive integration smoke test for Sava's v1 builder, signer and RPC reader.
/// Runs only on demand against a disposable local validator with v1 enabled and an airdrop faucet:
/// `SAVA_V1_LIVE=true ./gradlew :sava-rpc:test --tests '*LiveV1ValidatorCheck' --rerun`.
/// See `src/test/solana/v1-live/README.md` for setup. Detailed protocol regressions remain in the
/// offline Rust/Kit vector tests and captured RPC response tests.
@EnabledIfEnvironmentVariable(named = "SAVA_V1_LIVE", matches = "true")
final class LiveV1ValidatorCheck {

  private static final long PRIORITY_FEE = 5_000L;
  private static final int HEAP_SIZE = 65_536;

  private static Signer randomSigner() {
    final byte[] seed = new byte[Signer.KEY_LENGTH];
    new SecureRandom().nextBytes(seed);
    return Signer.createFromPrivateKey(seed);
  }

  private static URI localRpcUrl() {
    final var configured = System.getenv("SAVA_V1_RPC_URL");
    final var url = URI.create(configured == null || configured.isBlank()
        ? "http://127.0.0.1:8899" : configured);
    assertTrue(
        "http".equals(url.getScheme()) && url.getHost() != null
            && Set.of("127.0.0.1", "localhost", "[::1]").contains(url.getHost())
            && url.getUserInfo() == null,
        "SAVA_V1_RPC_URL must name a local HTTP validator: " + url
    );
    return url;
  }

  /// Confirmation and indexing are asynchronous even on a local validator. These bounded waits
  /// belong only to this opt-in integration check, which is excluded from mutation testing.
  private static void awaitConfirmed(final SolanaRpcClient client, final String signature) throws InterruptedException {
    for (int i = 0; i < 240; ++i) {
      final var status = client.getSignatureStatuses(List.of(signature)).join().get(signature);
      if (status != null && !status.nil()) {
        assertNull(status.error(), () -> signature + " failed on the local validator");
        final var confirmation = status.confirmationStatus();
        if (confirmation == Commitment.CONFIRMED || confirmation == Commitment.FINALIZED) {
          return;
        }
      }
      Thread.sleep(250);
    }
    fail("timed out waiting for confirmation of " + signature);
  }

  private static Tx fetchTx(final SolanaRpcClient client, final String signature) throws InterruptedException {
    for (int i = 0; i < 40; ++i) {
      final var tx = client.getTransaction(signature).join();
      if (tx != null && tx.data() != null) {
        return tx;
      }
      Thread.sleep(250);
    }
    return fail("getTransaction never served " + signature);
  }

  /// A large transfer exercises the v1 wire path, simulation-driven config updates, signing,
  /// submission and both transaction/block readers in one round trip.
  @Test
  void basicV1Transfer() throws Exception {
    final var url = localRpcUrl();
    try (final var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      final var client = SolanaRpcClient.createClient(url, httpClient, Commitment.CONFIRMED);
      final var payer = randomSigner();
      awaitConfirmed(client, client.requestAirdrop(payer.publicKey(), 1_000_000_000L).join());

      final var tx = TxBuilder.createBuilder()
          .feePayer(payer.publicKey())
          .addInstruction(V1AgaveTestFixtures.transfer(
              payer.publicKey(), randomSigner().publicKey(), 1_000_000L, 3_000))
          .priorityFeeLamports(PRIORITY_FEE)
          .heapSize(HEAP_SIZE)
          .createTransaction();
      tx.setRecentBlockHash(Base58.decode(client.getLatestBlockHash().join().blockHash()));
      assertEquals(1, tx.version());
      assertTrue(tx.size() > 1_232, "the wire must exceed the legacy packet limit");
      assertFalse(tx.exceedsSizeLimit());

      final var simulation = client.simulateTransaction(tx).join();
      assertNull(simulation.error(), () -> "local validator must support v1: " + simulation);
      final int units = simulation.unitsConsumed().orElseThrow();
      final int loaded = simulation.loadedAccountsDataSize();
      assertTrue(units > 0);
      assertTrue(loaded > 0);
      tx.setComputeUnitLimit(units);
      tx.setAccountDataSizeLimit(loaded);
      tx.sign(payer);
      final byte[] sent = tx.serialized().clone();

      final var signature = client.sendTransaction(tx.base64EncodeToString()).join();
      assertEquals(tx.getBase58Id(), signature);
      awaitConfirmed(client, signature);
      final var landed = fetchTx(client, signature);
      assertEquals(1, landed.version());
      assertArrayEquals(sent, landed.data());
      assertNull(landed.meta().error());
      assertEquals(5_000L + PRIORITY_FEE, landed.meta().fee(), "local genesis base fee plus priority fee");
      assertEquals(units, landed.meta().computeUnitsConsumed());

      final var skeleton = TransactionSkeleton.deserializeSkeleton(landed.data());
      assertEquals(1, skeleton.version());
      assertEquals(PRIORITY_FEE, skeleton.priorityFeeLamports());
      assertEquals(units, skeleton.computeUnitLimit());
      assertEquals(loaded, skeleton.accountDataSizeLimit());
      assertEquals(HEAP_SIZE, skeleton.heapSize());
      assertEquals(1, skeleton.numSignatures());
      assertEquals(1, skeleton.numInstructions());

      final var block = client.getBlock(landed.slot(), BlockTxDetails.full).join();
      assertNotNull(block);
      assertNotNull(block.transactions());
      assertTrue(
          block.transactions().stream().anyMatch(blockTx -> Arrays.equals(blockTx.data(), sent)),
          "full getBlock must carry the byte-exact v1 transaction"
      );
      System.out.println("[v1-live] basicV1Transfer OK slot=" + landed.slot() + " size=" + sent.length);
    }
  }
}
