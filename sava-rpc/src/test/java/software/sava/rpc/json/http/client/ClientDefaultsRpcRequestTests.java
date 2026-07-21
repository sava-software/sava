package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.SolanaNetwork;
import software.sava.rpc.json.http.request.BlockTxDetails;
import software.sava.rpc.json.http.request.Commitment;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// The leftover convenience defaults: the static `createClient` factories, and the
/// no-argument forms whose whole job is to supply a constant the caller did not.
/// Each constant is a decision — a sample window, a history flag, a page size —
/// that silently shapes the request.
final class ClientDefaultsRpcRequestTests extends RpcRequestTests {

  private static final PublicKey ACCOUNT =
      PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final String SIGNATURE =
      "5VERd3ZfLb2qBqZzbGjWEnwqZQFbHnLWuGh4H1oGrzXmMPHkOfkq8kSjMWZ7Vw6qLkyoBSc3mCQNMcxbNxJgpp1U";

  /// 720 samples is the RPC's own maximum, so the no-arg overload asks for as much
  /// history as the node will give.
  @Test
  void recentPerformanceSamplesDefaultsToTheMaximumWindow() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getRecentPerformanceSamples","params":[720]}""", """
        {"jsonrpc":"2.0","result":[{"numSlots":126,"numTransactions":126,\
        "numNonVoteTransactions":1,"samplePeriodSecs":60,"slot":348125}],"id":1}""");

    final var samples = rpcClient.getRecentPerformanceSamples().join();
    assertEquals(1, samples.size());
    assertEquals(348125, samples.getFirst().slot());
  }

  /// A larger request is clamped to 720 rather than rejected.
  @Test
  void recentPerformanceSamplesClampsToTheMaximum() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getRecentPerformanceSamples","params":[720]}""", """
        {"jsonrpc":"2.0","result":[],"id":1}""");
    assertTrue(rpcClient.getRecentPerformanceSamples(5_000).join().isEmpty());
  }

  /// No writable accounts means an empty params array, not a null.
  @Test
  void recentPrioritizationFeesDefaultsToNoAccountFilter() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getRecentPrioritizationFees","params":[]}""", """
        {"jsonrpc":"2.0","result":[{"prioritizationFee":0,"slot":348125}],"id":1}""");

    final var fees = rpcClient.getRecentPrioritizationFees().join();
    assertEquals(1, fees.size());
    assertEquals(348125, fees.getFirst().slot());
  }

  /// Signature status lookups default to *not* searching transaction history,
  /// which is the cheap path — a signature older than the node's recent cache
  /// comes back null rather than found.
  @Test
  void signatureStatusesDefaultToSkippingHistory() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSignatureStatuses","params":[["%s"],\
        {"searchTransactionHistory":false}]}""".formatted(SIGNATURE), """
        {"jsonrpc":"2.0","result":{"context":{"slot":82},"value":[{"confirmations":10,\
        "confirmationStatus":"confirmed","err":null,"slot":48}]},"id":1}""");

    final var statuses = rpcClient.getSignatureStatuses(List.of(SIGNATURE)).join();
    assertEquals(1, statuses.size());
    assertFalse(statuses.get(SIGNATURE).nil(), "a real status is not the nil sentinel");
  }

  /// An unknown signature is not dropped from the map — it maps to a sentinel
  /// TxStatus whose `nil()` is true, so results stay keyed by every signature the
  /// caller asked about rather than silently shrinking.
  @Test
  void signatureStatusesCanSearchHistory() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSignatureStatuses","params":[["%s"],\
        {"searchTransactionHistory":true}]}""".formatted(SIGNATURE), """
        {"jsonrpc":"2.0","result":{"context":{"slot":82},"value":[null]},"id":1}""");

    final var statuses = rpcClient.getSignatureStatuses(List.of(SIGNATURE), true).join();
    assertEquals(1, statuses.size(), "an unknown signature keeps its slot in the map");
    assertTrue(statuses.get(SIGNATURE).nil(), "and is reported via nil() rather than absence");
  }

  @Test
  void getBlockWithRewardsFlagDefaultsTransactionDetails() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlock","params":[9,{"encoding":"base64",\
        "commitment":"confirmed","transactionDetails":"none","rewards":true}]}""", """
        {"jsonrpc":"2.0","result":{"blockHeight":8,"blockTime":1700000000,"blockhash":\
        "EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N","parentSlot":8,"previousBlockhash":\
        "11111111111111111111111111111111"},"id":1}""");

    assertEquals(8, rpcClient.getBlock(9, true).join().blockHeight());
  }

  @Test
  void getAccountInfoWithCommitmentOnly() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getAccountInfo","params":["%s",\
        {"encoding":"base64","commitment":"finalized"}]}""".formatted(ACCOUNT.toBase58()), """
        {"jsonrpc":"2.0","result":{"context":{"slot":1,"apiVersion":"2.1.9"},"value":{\
        "lamports":42,"data":["","base64"],"owner":"11111111111111111111111111111111",\
        "executable":false,"rentEpoch":0,"space":0}},"id":1}""");

    assertEquals(42, rpcClient.getAccountInfo(Commitment.FINALIZED, ACCOUNT).join().lamports());
  }

  @Test
  void getMultipleAccountsWithKeysOnly() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getMultipleAccounts","params":[["%s"],\
        {"encoding":"base64","commitment":"confirmed"}]}""".formatted(ACCOUNT.toBase58()), """
        {"jsonrpc":"2.0","result":{"context":{"slot":1,"apiVersion":"2.1.9"},"value":[{\
        "lamports":1,"data":["","base64"],"owner":"11111111111111111111111111111111",\
        "executable":false,"rentEpoch":0,"space":0}]},"id":1}""");

    assertEquals(1, rpcClient.getMultipleAccounts(List.of(ACCOUNT)).join().size());
  }

  @Test
  void sendTransactionWithCommitmentAndSkipFlag() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"sendTransaction","params":["AVXo",\
        {"encoding":"base64","skipPreflight":true,"preflightCommitment":"finalized","maxRetries":0}]}""",
        """
            {"jsonrpc":"2.0","result":"%s","id":1}""".formatted(SIGNATURE));

    assertEquals(SIGNATURE, rpcClient.sendTransaction(Commitment.FINALIZED, "AVXo", true).join());
  }

  @Test
  void sendTransactionWithCommitmentSkipFlagAndRetries() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"sendTransaction","params":["AVXo",\
        {"encoding":"base64","preflightCommitment":"processed","maxRetries":9}]}""",
        """
            {"jsonrpc":"2.0","result":"%s","id":1}""".formatted(SIGNATURE));

    assertEquals(SIGNATURE, rpcClient.sendTransaction(Commitment.PROCESSED, "AVXo", false, 9).join());
  }

  /// The static factories substitute their own defaults rather than routing
  /// through the builder, so each has to be exercised directly.
  @Test
  void staticFactoriesApplyTheirDefaults() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var endpoint = SolanaNetwork.DEV_NET.getEndpoint();

      final var twoArg = SolanaRpcClient.createClient(endpoint, httpClient);
      assertEquals(endpoint, twoArg.endpoint());
      assertEquals(Commitment.CONFIRMED, twoArg.defaultCommitment());
      assertEquals(SolanaJsonRpcClient.DEFAULT_REQUEST_TIMEOUT, twoArg.defaultRequestTimeout());

      final var withCommitment = SolanaRpcClient.createClient(endpoint, httpClient, Commitment.FINALIZED);
      assertEquals(Commitment.FINALIZED, withCommitment.defaultCommitment());
      assertEquals(SolanaJsonRpcClient.DEFAULT_REQUEST_TIMEOUT, withCommitment.defaultRequestTimeout());

      final var withTimeout = SolanaRpcClient.createClient(
          endpoint, httpClient, Duration.ofSeconds(13), Commitment.PROCESSED);
      assertEquals(Duration.ofSeconds(13), withTimeout.defaultRequestTimeout());
      assertEquals(Commitment.PROCESSED, withTimeout.defaultCommitment());
    }
  }

  @Test
  void blockTxDetailsAndRewardsCombine() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlock","params":[11,{"encoding":"base64",\
        "commitment":"confirmed","transactionDetails":"full","rewards":false}]}""", """
        {"jsonrpc":"2.0","result":{"blockHeight":10,"blockTime":1700000000,"blockhash":\
        "EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N","parentSlot":10,"previousBlockhash":\
        "11111111111111111111111111111111"},"id":1}""");

    assertEquals(10, rpcClient.getBlock(11, BlockTxDetails.full, false).join().blockHeight());
  }

  /// With writable accounts the params array carries them; without, it is empty.
  /// Both shapes go through the same join, so both are driven.
  @Test
  void recentPrioritizationFeesWithWritableAccounts() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getRecentPrioritizationFees","params":[["%s"]]}"""
        .formatted(ACCOUNT.toBase58()), """
        {"jsonrpc":"2.0","result":[{"prioritizationFee":1200,"slot":348126}],"id":1}""");

    final var fees = rpcClient.getRecentPrioritizationFees(List.of(ACCOUNT)).join();
    assertEquals(1, fees.size());
    assertEquals(1200, fees.getFirst().prioritizationFee());
  }

  /// An empty collection is treated the same as none at all.
  @Test
  void recentPrioritizationFeesWithAnEmptyCollection() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getRecentPrioritizationFees","params":[]}""", """
        {"jsonrpc":"2.0","result":[],"id":1}""");
    assertTrue(rpcClient.getRecentPrioritizationFees(List.of()).join().isEmpty());
  }
}
