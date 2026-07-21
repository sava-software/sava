package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.BlockTxDetails;
import software.sava.rpc.json.http.request.Commitment;

import static org.junit.jupiter.api.Assertions.*;

/// Validator and block production queries. Several of these take an optional
/// filter key — a vote account, an identity — which changes the request shape
/// rather than just a value, so both forms are driven.
final class ValidatorInfoRpcRequestTests extends RpcRequestTests {

  private static final PublicKey VOTE_ACCOUNT =
      PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final PublicKey IDENTITY =
      PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final PublicKey MINT =
      PublicKey.fromBase58Encoded("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v");

  private static final String VOTE_ACCOUNTS_RESPONSE = """
      {"jsonrpc":"2.0","result":{"current":[{"activatedStake":42,"commission":5,\
      "epochCredits":[[1,64,0]],"epochVoteAccount":true,"lastVote":147,\
      "nodePubkey":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA","rootSlot":146,\
      "votePubkey":"So11111111111111111111111111111111111111112"}],"delinquent":[]},"id":1}""";

  private static final String BLOCK_PRODUCTION_RESPONSE = """
      {"jsonrpc":"2.0","result":{"context":{"slot":9887},"value":{"byIdentity":{\
      "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA":[9888,9886]},\
      "range":{"firstSlot":0,"lastSlot":9887}}},"id":1}""";

  @Test
  void getVoteAccountsUsesTheDefaultCommitment() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getVoteAccounts","params":[{"commitment":"confirmed"}]}""",
        VOTE_ACCOUNTS_RESPONSE);

    final var voteAccounts = rpcClient.getVoteAccounts().join();
    assertEquals(1, voteAccounts.current().size());
    assertTrue(voteAccounts.delinquent().isEmpty());
    assertEquals(42, voteAccounts.current().getFirst().activatedStake());
  }

  @Test
  void getVoteAccountsHonoursAnExplicitCommitment() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getVoteAccounts","params":[{"commitment":"finalized"}]}""",
        VOTE_ACCOUNTS_RESPONSE);
    assertNotNull(rpcClient.getVoteAccounts(Commitment.FINALIZED).join());
  }

  /// Filtering to one vote account adds a field rather than changing the method.
  @Test
  void getVoteAccountsForASingleVotePubkey() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getVoteAccounts","params":[{"commitment":"confirmed",\
        "votePubkey":"So11111111111111111111111111111111111111112"}]}""", VOTE_ACCOUNTS_RESPONSE);

    assertEquals(1, rpcClient.getVoteAccounts(VOTE_ACCOUNT).join().current().size());
  }

  @Test
  void getVoteAccountsForASingleVotePubkeyWithCommitment() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getVoteAccounts","params":[{"commitment":"processed",\
        "votePubkey":"So11111111111111111111111111111111111111112"}]}""", VOTE_ACCOUNTS_RESPONSE);

    assertNotNull(rpcClient.getVoteAccounts(Commitment.PROCESSED, VOTE_ACCOUNT).join());
  }

  @Test
  void getBlockProductionUsesTheDefaultCommitment() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlockProduction","params":[{"commitment":"confirmed"}]}""",
        BLOCK_PRODUCTION_RESPONSE);

    final var production = rpcClient.getBlockProduction().join();
    assertEquals(0, production.firstSlot());
    assertEquals(9887, production.lastSlot());
    assertEquals(1, production.leaderInfoMap().size());
  }

  @Test
  void getBlockProductionHonoursAnExplicitCommitment() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlockProduction","params":[{"commitment":"finalized"}]}""",
        BLOCK_PRODUCTION_RESPONSE);
    assertNotNull(rpcClient.getBlockProduction(Commitment.FINALIZED).join());
  }

  @Test
  void getBlockProductionForOneIdentity() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlockProduction","params":[{"commitment":"confirmed",\
        "identity":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"}]}""", BLOCK_PRODUCTION_RESPONSE);

    assertEquals(1, rpcClient.getBlockProduction(IDENTITY).join().leaderInfoMap().size());
  }

  @Test
  void getBlockProductionForOneIdentityWithCommitment() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlockProduction","params":[{"commitment":"processed",\
        "identity":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"}]}""", BLOCK_PRODUCTION_RESPONSE);

    assertNotNull(rpcClient.getBlockProduction(Commitment.PROCESSED, IDENTITY).join());
  }

  /// The slotless overload sends a literal `null` for the epoch slot, which is how
  /// the RPC asks for the current epoch.
  @Test
  void getLeaderScheduleForTheCurrentEpochSendsNull() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getLeaderSchedule","params":[null,{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":{"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA":[0,1,2,3]},"id":1}""");

    final var schedule = rpcClient.getLeaderSchedule().join();
    assertEquals(1, schedule.size());
    assertEquals(4, schedule.get(IDENTITY).length);
  }

  @Test
  void getLeaderScheduleForASlot() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getLeaderSchedule","params":[123,{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":{"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA":[0,1]},"id":1}""");

    assertEquals(2, rpcClient.getLeaderSchedule(123).join().get(IDENTITY).length);
  }

  @Test
  void getTokenSupply() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenSupply","params":["EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",\
        {"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":1},"value":{"amount":"100000",\
        "decimals":6,"uiAmount":0.1,"uiAmountString":"0.1"}},"id":1}""");

    final var supply = rpcClient.getTokenSupply(MINT).join();
    assertEquals(6, supply.decimals());
    assertEquals(100_000, supply.amount().longValue());
  }

  @Test
  void getTokenLargestAccounts() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTokenLargestAccounts","params":[\
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",{"commitment":"confirmed"}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":1},"value":[{\
        "address":"So11111111111111111111111111111111111111112","amount":"77","decimals":6,\
        "uiAmount":0.000077,"uiAmountString":"0.000077"}]},"id":1}""");

    final var largest = rpcClient.getTokenLargestAccounts(MINT).join();
    assertEquals(1, largest.size());
    assertEquals(77, largest.getFirst().amount().longValue());
  }

  /// getBlock carries two flags that change the payload size dramatically, so both
  /// must reach the request.
  @Test
  void getBlockSendsDetailAndRewardFlags() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlock","params":[5,{"encoding":"base64",\
        "commitment":"confirmed","transactionDetails":"none","rewards":false}]}""", """
        {"jsonrpc":"2.0","result":{"blockHeight":4,"blockTime":1700000000,"blockhash":\
        "EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N","parentSlot":4,"previousBlockhash":\
        "11111111111111111111111111111111"},"id":1}""");

    final var block = rpcClient.getBlock(5, BlockTxDetails.none, false).join();
    assertEquals(4, block.blockHeight());
    assertEquals(4, block.parentSlot());
  }

  /// Filtering the schedule to one identity keeps the null epoch slot and adds the
  /// identity to the options object.
  @Test
  void getLeaderScheduleForOneIdentity() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getLeaderSchedule","params":[null,{"commitment":"confirmed",\
        "identity":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"}]}""", """
        {"jsonrpc":"2.0","result":{"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA":[7]},"id":1}""");

    assertEquals(1, rpcClient.getLeaderSchedule(IDENTITY).join().get(IDENTITY).length);
  }

  @Test
  void getLeaderScheduleForOneIdentityWithCommitment() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getLeaderSchedule","params":[null,{"commitment":"finalized",\
        "identity":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"}]}""", """
        {"jsonrpc":"2.0","result":{"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA":[7]},"id":1}""");

    assertNotNull(rpcClient.getLeaderSchedule(Commitment.FINALIZED, IDENTITY).join());
  }

  /// getSupply always sends the exclusion flag, and the no-arg overload defaults it
  /// to **false** — so the response carries the full non-circulating account list,
  /// which is hundreds of addresses on mainnet. Pass `true` if only the totals are
  /// wanted.
  @Test
  void getSupplyDefaultsToIncludingNonCirculatingAccounts() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSupply","params":[{"commitment":"confirmed",\
        "excludeNonCirculatingAccountsList":false}]}""", """
        {"jsonrpc":"2.0","result":{"context":{"slot":1},"value":{"circulating":16000,\
        "nonCirculating":1000000,"nonCirculatingAccounts":[],"total":1016000}},"id":1}""");

    final var supply = rpcClient.getSupply().join();
    assertEquals(16_000, supply.circulating());
    assertEquals(1_016_000, supply.total());
  }

  /// The two-argument getBlock keeps rewards on, unlike the three-argument form
  /// where the caller chooses.
  @Test
  void getBlockWithDetailsKeepsRewards() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlock","params":[6,{"encoding":"base64",\
        "commitment":"confirmed","transactionDetails":"signatures","rewards":true}]}""", """
        {"jsonrpc":"2.0","result":{"blockHeight":5,"blockTime":1700000000,"blockhash":\
        "EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N","parentSlot":5,"previousBlockhash":\
        "11111111111111111111111111111111"},"id":1}""");

    assertEquals(5, rpcClient.getBlock(6, BlockTxDetails.signatures).join().blockHeight());
  }

  /// The full overload appends two further options, each only when set — so all
  /// four combinations produce a different request.
  @Test
  void getVoteAccountsWithDelinquentOptions() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getVoteAccounts","params":[{"commitment":"confirmed",\
        "votePubkey":"So11111111111111111111111111111111111111112","keepUnstakedDelinquents":true,\
        "delinquentSlotDistance":128}]}""", VOTE_ACCOUNTS_RESPONSE);

    assertNotNull(rpcClient.getVoteAccounts(
        Commitment.CONFIRMED, VOTE_ACCOUNT, true, java.math.BigInteger.valueOf(128)).join());
  }

  @Test
  void getVoteAccountsOmitsUnsetDelinquentOptions() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getVoteAccounts","params":[{"commitment":"confirmed"}]}""",
        VOTE_ACCOUNTS_RESPONSE);

    assertNotNull(rpcClient.getVoteAccounts(Commitment.CONFIRMED, null, false, null).join());
  }

  @Test
  void getVoteAccountsKeepUnstakedDelinquentsOnly() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getVoteAccounts","params":[{"commitment":"confirmed",\
        "keepUnstakedDelinquents":true}]}""", VOTE_ACCOUNTS_RESPONSE);

    assertNotNull(rpcClient.getVoteAccounts(Commitment.CONFIRMED, null, true, null).join());
  }

  @Test
  void getVoteAccountsDelinquentSlotDistanceOnly() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getVoteAccounts","params":[{"commitment":"confirmed",\
        "delinquentSlotDistance":64}]}""", VOTE_ACCOUNTS_RESPONSE);

    assertNotNull(rpcClient.getVoteAccounts(
        Commitment.CONFIRMED, null, false, java.math.BigInteger.valueOf(64)).join());
  }
}
