package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.JsonRpcException;
import software.sava.rpc.json.http.response.RpcCustomError;

import java.math.BigInteger;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/// Defensive client-side request and response tests for Agave 4.4's additive RPC options.
/// The literal wire expectations pin Agave's config names and defaults independently of the
/// request builders; deliberately distinct identity and vote keys pin the leader filter contract.
final class Agave44RpcRequestTests extends RpcRequestTests {

  private static final String SIGNATURE =
      "5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW";
  private static final String OTHER_SIGNATURE =
      "5j7s6NiJS3JAkvgkoc18WVAsiSaci2pxB2A6ueCJP4tprA2TFg9wSyTLeYouxPBJEMzJinENTkpA52YStRW5Dia7";
  private static final List<String> SIGNATURES = List.of(SIGNATURE, OTHER_SIGNATURE);
  private static final BigInteger U64_MAX = new BigInteger("18446744073709551615");
  private static final PublicKey IDENTITY =
      PublicKey.fromBase58Encoded("4Qkev8aNZcqFNSRhQzwyLMFSsi94jHqE8WNVTJzTP99F");
  private static final PublicKey VOTE_ACCOUNT =
      PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");

  private static final String STATUS_RESPONSE = """
      {"jsonrpc":"2.0","id":1,"result":{"context":{"slot":82,"apiVersion":"4.4.0"},\
      "value":[{"slot":48,"confirmations":null,"err":null,"confirmationStatus":"finalized"},\
      {"slot":72,"confirmations":10,"err":null,"confirmationStatus":"confirmed"}]}}""";
  private static final String TRANSACTION_RESPONSE = """
      {"jsonrpc":"2.0","id":1,"result":{"slot":91,"blockTime":1700000000,"meta":null,\
      "transaction":["AQID","base64"],"version":1,"transactionIndex":7}}""";

  @Test
  void statusMapWithCommitmentAndLiteralZeroKeepsSignatureCorrelation() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSignatureStatuses","params":[["%s","%s"],\
        {"searchTransactionHistory":true,"commitment":"confirmed","minContextSlot":0}]}"""
        .formatted(SIGNATURE, OTHER_SIGNATURE), """
        {"jsonrpc":"2.0","id":1,"result":{"context":{"slot":82,"apiVersion":"4.4.0"},\
        "value":[null,{"slot":72,"confirmations":10,"err":null,"confirmationStatus":"confirmed"}]}}""");

    final var statuses = rpcClient.getSignatureStatuses(Commitment.CONFIRMED, SIGNATURES, true, BigInteger.ZERO).join();
    assertEquals(2, statuses.size());
    assertTrue(statuses.get(SIGNATURE).nil());
    assertEquals(82, statuses.get(SIGNATURE).context().slot());
    final var found = statuses.get(OTHER_SIGNATURE);
    assertFalse(found.nil());
    assertEquals(72, found.slot());
    assertEquals(OptionalInt.of(10), found.confirmations());
    assertEquals(Commitment.CONFIRMED, found.confirmationStatus());
    assertEquals("4.4.0", found.context().apiVersion());
  }

  @Test
  void statusListWithUnsignedMaximumKeepsResponseOrder() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSignatureStatuses","params":[["%s","%s"],\
        {"searchTransactionHistory":false,"commitment":"finalized","minContextSlot":18446744073709551615}]}"""
        .formatted(SIGNATURE, OTHER_SIGNATURE), STATUS_RESPONSE);

    final var statuses = rpcClient.getSigStatusList(Commitment.FINALIZED, SIGNATURES, false, U64_MAX).join();
    assertEquals(2, statuses.size());
    assertEquals(48, statuses.getFirst().slot());
    assertEquals(Commitment.FINALIZED, statuses.getFirst().confirmationStatus());
    assertTrue(statuses.getFirst().confirmations().isEmpty());
    assertEquals(72, statuses.getLast().slot());
    assertEquals(Commitment.CONFIRMED, statuses.getLast().confirmationStatus());
    assertEquals(82, statuses.getLast().context().slot());
  }

  @Test
  void statusMinSlotConveniencesUseProcessedRegardlessOfClientDefault() {
    assertEquals(Commitment.CONFIRMED, rpcClient.defaultCommitment());
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSignatureStatuses","params":[["%s","%s"],\
        {"searchTransactionHistory":false,"commitment":"processed","minContextSlot":0}]}"""
        .formatted(SIGNATURE, OTHER_SIGNATURE), STATUS_RESPONSE);
    assertEquals(48, rpcClient.getSignatureStatuses(SIGNATURES, false, BigInteger.ZERO).join().get(SIGNATURE).slot());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSignatureStatuses","params":[["%s","%s"],\
        {"searchTransactionHistory":true,"commitment":"processed","minContextSlot":18446744073709551615}]}"""
        .formatted(SIGNATURE, OTHER_SIGNATURE), STATUS_RESPONSE);
    assertEquals(72, rpcClient.getSigStatusList(SIGNATURES, true, U64_MAX).join().getLast().slot());
  }

  @Test
  void statusCommitmentConveniencesOmitAbsentMinimum() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSignatureStatuses","params":[["%s","%s"],\
        {"searchTransactionHistory":true,"commitment":"finalized"}]}"""
        .formatted(SIGNATURE, OTHER_SIGNATURE), STATUS_RESPONSE);
    assertEquals(2, rpcClient.getSignatureStatuses(Commitment.FINALIZED, SIGNATURES, true).join().size());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSignatureStatuses","params":[["%s","%s"],\
        {"searchTransactionHistory":false,"commitment":"confirmed"}]}"""
        .formatted(SIGNATURE, OTHER_SIGNATURE), STATUS_RESPONSE);
    assertEquals(2, rpcClient.getSigStatusList(Commitment.CONFIRMED, SIGNATURES, false).join().size());
  }

  @Test
  void statusOptionsCanOmitCommitmentAndMinimum() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSignatureStatuses","params":[["%s","%s"],\
        {"searchTransactionHistory":false}]}""".formatted(SIGNATURE, OTHER_SIGNATURE), STATUS_RESPONSE, 2);
    assertEquals(2, rpcClient.getSignatureStatuses(null, SIGNATURES, false, null).join().size());
    assertEquals(2, rpcClient.getSigStatusList(null, SIGNATURES, false, null).join().size());
  }

  @Test
  void transactionMinimumPreservesUnsignedMaximumAndBase64VersionOne() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTransaction","params":["%s",\
        {"commitment":"finalized","maxSupportedTransactionVersion":1,"encoding":"base64",\
        "minContextSlot":18446744073709551615}]}""".formatted(SIGNATURE), TRANSACTION_RESPONSE);

    final var transaction = rpcClient.getTransaction(Commitment.FINALIZED, SIGNATURE, U64_MAX).join();
    assertEquals(91, transaction.slot());
    assertEquals(OptionalLong.of(1700000000), transaction.blockTime());
    assertEquals(1, transaction.version());
    assertEquals(OptionalInt.of(7), transaction.transactionIndex());
    assertArrayEquals(new byte[]{1, 2, 3}, transaction.data());
  }

  @Test
  void transactionMinimumConvenienceUsesClientCommitmentAndLiteralZero() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTransaction","params":["%s",\
        {"commitment":"confirmed","maxSupportedTransactionVersion":1,"encoding":"base64","minContextSlot":0}]}"""
        .formatted(SIGNATURE), TRANSACTION_RESPONSE);
    assertEquals(91, rpcClient.getTransaction(SIGNATURE, BigInteger.ZERO).join().slot());
  }

  @Test
  void transactionNullMinimumLeavesTheExistingWireOptionsUnchanged() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTransaction","params":["%s",\
        {"commitment":"confirmed","maxSupportedTransactionVersion":1,"encoding":"base64"}]}"""
        .formatted(SIGNATURE), TRANSACTION_RESPONSE);
    assertEquals(91, rpcClient.getTransaction(Commitment.CONFIRMED, SIGNATURE, null).join().slot());
  }

  @Test
  void voteKeyScheduleStillFiltersByValidatorIdentityAndSerializesUnsignedSlot() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getLeaderSchedule","params":[18446744073709551615,\
        {"commitment":"finalized","identity":"%s","keyByVoteAccount":true}]}""".formatted(IDENTITY), """
        {"jsonrpc":"2.0","id":1,"result":{"%s":[0,3]}}""".formatted(VOTE_ACCOUNT));

    final var schedule = rpcClient.getLeaderSchedule(Commitment.FINALIZED, -1L, IDENTITY, true).join();
    assertEquals(1, schedule.size());
    assertFalse(schedule.containsKey(IDENTITY));
    assertArrayEquals(new long[]{0, 3}, schedule.get(VOTE_ACCOUNT));
  }

  @Test
  void identityKeyScheduleSendsExplicitFalseAndLiteralZeroSlot() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getLeaderSchedule","params":[0,\
        {"commitment":"confirmed","keyByVoteAccount":false}]}""", """
        {"jsonrpc":"2.0","id":1,"result":{"%s":[1,5]}}""".formatted(IDENTITY));
    assertArrayEquals(new long[]{1, 5},
        rpcClient.getLeaderSchedule(Commitment.CONFIRMED, 0L, null, false).join().get(IDENTITY));
  }

  @Test
  void leaderScheduleConveniencesUseNullSlotAndTheirCommitment() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getLeaderSchedule","params":[null,\
        {"commitment":"confirmed","keyByVoteAccount":true}]}""", """
        {"jsonrpc":"2.0","id":1,"result":{"%s":[4]}}""".formatted(VOTE_ACCOUNT));
    assertArrayEquals(new long[]{4}, rpcClient.getLeaderSchedule(true).join().get(VOTE_ACCOUNT));

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getLeaderSchedule","params":[null,\
        {"commitment":"finalized","keyByVoteAccount":false}]}""", """
        {"jsonrpc":"2.0","id":1,"result":{"%s":[8]}}""".formatted(IDENTITY));
    assertArrayEquals(new long[]{8}, rpcClient.getLeaderSchedule(Commitment.FINALIZED, false).join().get(IDENTITY));
  }

  @Test
  void minimumSlotErrorsPropagateFromStatusAndTransactionRequests() {
    final var response = """
        {"jsonrpc":"2.0","id":41,"error":{"code":-32016,\
        "message":"Minimum context slot has not been reached","data":{"contextSlot":81}}}""";
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getSignatureStatuses","params":[["%s","%s"],\
        {"searchTransactionHistory":false,"commitment":"confirmed","minContextSlot":100}]}"""
        .formatted(SIGNATURE, OTHER_SIGNATURE), response);
    final var statusError = joinExpectingRpcException(() ->
        rpcClient.getSignatureStatuses(Commitment.CONFIRMED, SIGNATURES, false, BigInteger.valueOf(100)).join());
    assertEquals(-32016, statusError.code());
    assertEquals(OptionalLong.of(41), statusError.requestId());
    assertEquals(81, assertInstanceOf(RpcCustomError.MinContextSlotNotReached.class, statusError.customError()).contextSlot());

    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getTransaction","params":["%s",\
        {"commitment":"confirmed","maxSupportedTransactionVersion":1,"encoding":"base64","minContextSlot":100}]}"""
        .formatted(SIGNATURE), response);
    final var transactionError = joinExpectingRpcException(() ->
        rpcClient.getTransaction(SIGNATURE, BigInteger.valueOf(100)).join());
    assertEquals(-32016, transactionError.code());
    assertEquals(81, assertInstanceOf(RpcCustomError.MinContextSlotNotReached.class, transactionError.customError()).contextSlot());
  }

  @Test
  void missingLeaderIdentityIsAClassifiedRpcError() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getLeaderSchedule","params":[null,\
        {"commitment":"confirmed","identity":"%s","keyByVoteAccount":true}]}""".formatted(IDENTITY), """
        {"jsonrpc":"2.0","id":42,"error":{"code":-32022,"message":"Leader schedule identity not found"}}""");

    final var error = joinExpectingRpcException(() ->
        rpcClient.getLeaderSchedule(Commitment.CONFIRMED, null, IDENTITY, true).join());
    assertEquals(-32022, error.code());
    assertEquals(OptionalLong.of(42), error.requestId());
    assertEquals("Leader schedule identity not found", error.getMessage());
    assertInstanceOf(RpcCustomError.LeaderScheduleIdentityNotFound.class, error.customError());
  }

  private static JsonRpcException joinExpectingRpcException(final Runnable join) {
    final var exception = assertThrows(CompletionException.class, join::run);
    return assertInstanceOf(JsonRpcException.class, exception.getCause());
  }
}
