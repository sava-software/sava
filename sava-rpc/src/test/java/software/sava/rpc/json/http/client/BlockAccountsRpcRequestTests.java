package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.BlockTxDetails;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountKeySource;
import software.sava.rpc.json.http.response.BlockTx;
import software.sava.rpc.json.http.response.JsonRpcException;
import software.sava.rpc.json.http.response.RewardType;
import software.sava.rpc.json.http.response.RpcCustomError;
import software.sava.rpc.json.http.response.TxAccountKey;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.rpc.json.http.client.ParseRpcResponseTests.readFileString;

/// `getBlock` with `transactionDetails` `accounts`: the request names the version ceiling the
/// node enforces for this mode too, and the response replaces each transaction's payload with
/// its signatures and annotated account keys over reduced status metadata.
///
/// The block fixture was captured on 2026-11-01 from `https://api.devnet.solana.com`
/// (Agave 4.4.0-beta.1), finalized slot 517568132: 62 transactions, of which 53 legacy, 5 v0 and
/// 4 v1, with 19 account keys loaded from lookup tables. The refusal fixture is the same node's
/// answer to the same slot when the ceiling was omitted.
final class BlockAccountsRpcRequestTests extends RpcRequestTests {

  private static final long SLOT = 517_568_132L;
  private static final String REQUEST = """
      {"jsonrpc":"2.0","id":1,"method":"getBlock","params":[517568132,{"encoding":"base64","commitment":"finalized","transactionDetails":"accounts","rewards":false,"maxSupportedTransactionVersion":1}]}""";
  private static final String TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";

  private static PublicKey key(final String base58) {
    return PublicKey.fromBase58Encoded(base58);
  }

  private static void assertKey(final TxAccountKey accountKey,
                                final String pubKey,
                                final boolean writable,
                                final boolean signer,
                                final AccountKeySource source) {
    assertEquals(key(pubKey), accountKey.pubKey());
    assertEquals(writable, accountKey.writable());
    assertEquals(signer, accountKey.signer());
    assertEquals(source, accountKey.source());
  }

  @Test
  void theLiveDevnetBlockParsesSignaturesAndAnnotatedKeys() {
    registerRequest(REQUEST, readFileString("getBlock-accounts-devnet-4.4.0-beta.1.json"));
    final var block = rpcClient.getBlock(Commitment.FINALIZED, SLOT, BlockTxDetails.accounts, false).join();

    assertEquals(504_737_843L, block.blockHeight());
    assertEquals(1_793_553_743L, block.blockTime());
    assertEquals("Gsi86i5eLQvL92WxUrENCkYN2MFZmHa8VsbYLeoxNK92", block.blockHash());
    assertEquals("58bksCr81zmx9Xmt2SVdewjSanqqBnkQxjQd3EHehVK9", block.previousBlockHash());
    assertEquals(517_568_131L, block.parentSlot());
    assertEquals(0, block.numRewardPartitions(), "absent");
    assertTrue(block.rewards().isEmpty(), "rewards were not requested");
    assertTrue(block.signatures().isEmpty(), "signatures travel inside each transaction in this mode");

    final var transactions = block.transactions();
    assertEquals(62, transactions.size());
    assertEquals(53, transactions.stream().filter(BlockTx::isLegacy).count());
    assertEquals(5, transactions.stream().filter(tx -> tx.version() == 0).count());
    assertEquals(4, transactions.stream().filter(tx -> tx.version() == 1).count());
    assertEquals(19, transactions.stream()
        .flatMap(tx -> tx.accountKeys().stream())
        .filter(accountKey -> accountKey.source() == AccountKeySource.lookupTable)
        .count());
    for (final var tx : transactions) {
      assertNull(tx.data());
      assertNull(tx.skeleton());
      assertFalse(tx.signatures().isEmpty());
      assertEquals(tx.signatures().size(), tx.accountKeys().stream().filter(TxAccountKey::signer).count(),
          "one signature per signer");
      final var meta = tx.meta();
      assertNotNull(meta);
      assertNull(meta.error());
      assertEquals(tx.accountKeys().size(), meta.preBalances().size());
      assertEquals(tx.accountKeys().size(), meta.postBalances().size());
      // the members agave leaves out of the reduced meta read as absent
      assertNull(meta.logMessages());
      assertNull(meta.innerInstructions());
      assertNull(meta.loadedAddresses());
      assertNull(meta.returnData());
      assertNull(meta.costUnits());
      assertNull(meta.rewards());
      assertEquals(0, meta.computeUnitsConsumed());
    }

    final var legacy = transactions.getFirst();
    assertTrue(legacy.isLegacy());
    assertTrue(legacy.version() < 0);
    assertEquals(List.of("2KCbYd3jTeacvKsiqK6hFuQLtKMgxgzqCicdbvzpZnRfGtwzYhDt8XjhMFWVKaPHGo8tmjavyuPGqrNa4CgP9L5z"),
        legacy.signatures());
    final var legacyKeys = legacy.accountKeys();
    assertEquals(5, legacyKeys.size());
    assertKey(legacyKeys.get(0), "6KTJdcRg6peSppa7ncdR69eQPGYsgxRhVssvLU7gzyD5", true, true, AccountKeySource.transaction);
    assertKey(legacyKeys.get(1), "GUvzajrrfWpizSGj38iCC7TfB7rbbWxTgCszPAh1VRyh", true, false, AccountKeySource.transaction);
    assertKey(legacyKeys.get(2), "HyMKxfct1S9aFtq21GG3FUYUViQkmpZdk5pwGRtAz48C", true, false, AccountKeySource.transaction);
    assertKey(legacyKeys.get(3), "ComputeBudget111111111111111111111111111111", false, false, AccountKeySource.transaction);
    assertKey(legacyKeys.get(4), "GaXu3DXKYcMs8WmZsgfWcHXQSDva2qe7Aguvn3YHKSHS", false, false, AccountKeySource.transaction);
    final var legacyMeta = legacy.meta();
    assertEquals(19_000L, legacyMeta.fee());
    assertEquals(List.of(6_705_368_680L, 49_230_280L, 132_585_120L, 1L, 833_120L), legacyMeta.preBalances());
    assertEquals(List.of(6_705_349_680L, 49_230_280L, 132_585_120L, 1L, 833_120L), legacyMeta.postBalances());
    assertTrue(legacyMeta.preTokenBalances().isEmpty());
    assertTrue(legacyMeta.postTokenBalances().isEmpty());

    // a v0 transaction: its static keys first, then the eight it loaded from lookup tables
    final var v0 = transactions.get(9);
    assertEquals(0, v0.version());
    assertFalse(v0.isLegacy());
    assertEquals(List.of("26Si5uxVGvCXQj5jApqPQfMjYaZqYXqHhGgFU5QaBNcNUuXoP2CWzAbHUDWhyPLdDp5XBRWZmYSm2fDmutV97baJ"),
        v0.signatures());
    final var v0Keys = v0.accountKeys();
    assertEquals(16, v0Keys.size());
    assertKey(v0Keys.getFirst(), "9syWnBNEPtpNgo7Wgr1yi9td6VbxCiFSvcvPbHiJrp5y", true, true, AccountKeySource.transaction);
    for (int i = 1; i < 8; i++) {
      assertEquals(AccountKeySource.transaction, v0Keys.get(i).source(), "static key " + i);
      assertFalse(v0Keys.get(i).signer(), "static key " + i);
    }
    assertKey(v0Keys.get(8), "4DYrQk4Vvx3ita5jQ2pc6jPQpHegJGyo7Wbu3a832gs1", true, false, AccountKeySource.lookupTable);
    for (int i = 9; i < 14; i++) {
      assertKey(v0Keys.get(i), v0Keys.get(i).pubKey().toBase58(), true, false, AccountKeySource.lookupTable);
    }
    assertKey(v0Keys.get(14), "Sysvar1nstructions1111111111111111111111111", false, false, AccountKeySource.lookupTable);
    assertKey(v0Keys.get(15), TOKEN_PROGRAM, false, false, AccountKeySource.lookupTable);
    final var v0Meta = v0.meta();
    assertEquals(5_000L, v0Meta.fee());
    assertEquals(7_191_076_914L, v0Meta.preBalances().getFirst());
    assertEquals(7_191_071_914L, v0Meta.postBalances().getFirst());
    final var preTokenBalances = v0Meta.preTokenBalances();
    assertEquals(8, preTokenBalances.size());
    final var preTokenBalance = preTokenBalances.getFirst();
    assertEquals(1, preTokenBalance.accountIndex());
    assertEquals(key("FJQ3XGuZdRhiicMkVk1JpVCE6fxZVgA3AwvdtrqdYXt8"), preTokenBalance.mint());
    assertEquals(key("9syWnBNEPtpNgo7Wgr1yi9td6VbxCiFSvcvPbHiJrp5y"), preTokenBalance.owner());
    assertEquals(key(TOKEN_PROGRAM), preTokenBalance.programId());
    assertEquals(new BigInteger("8508456345"), preTokenBalance.amount());
    assertEquals(6, preTokenBalance.decimals());
    assertEquals(8, v0Meta.postTokenBalances().size());
    assertEquals(new BigInteger("8506699642"), v0Meta.postTokenBalances().getFirst().amount());

    // a v1 transaction: every key is static, so every source is the transaction itself
    final var v1 = transactions.get(2);
    assertEquals(1, v1.version());
    assertFalse(v1.isLegacy());
    assertEquals(List.of("bv31RUUwC1B4rQWgHSnTiyYzAkV9Tf4MauATwwBsTDDzGkSNtphnA1ujVqQMwrfRdg3eGoU2uG12TKcFcQ83jKm"),
        v1.signatures());
    final var v1Keys = v1.accountKeys();
    assertEquals(4, v1Keys.size());
    assertKey(v1Keys.get(0), "2dbChKurXcZj4iDQPdEPyYHJGaiAqJno2omaPjHCQ7EN", true, true, AccountKeySource.transaction);
    assertKey(v1Keys.get(1), "AWVYnCT2ZdLsWZf1X9KXatZhC2TyruRM22y8KZqVeupr", true, false, AccountKeySource.transaction);
    assertKey(v1Keys.get(2), "SysvarC1ock11111111111111111111111111111111", false, false, AccountKeySource.transaction);
    assertKey(v1Keys.get(3), "BiSoNHVpsVZW2F7rx2eQ59yQwKxzU5NvBcmKshCSUypi", false, false, AccountKeySource.transaction);
    assertEquals(5_000L, v1.meta().fee());
    assertEquals(List.of(503_480_000L, 15_144_960L, 1_169_280L, 1_141_440L), v1.meta().preBalances());
    assertEquals(List.of(503_475_000L, 15_144_960L, 1_169_280L, 1_141_440L), v1.meta().postBalances());

    // a legacy transaction with two signers carries both signatures in message order
    final var twoSigners = transactions.get(17);
    assertTrue(twoSigners.isLegacy());
    assertEquals(List.of(
        "3kKfmoJfzA73jEPxWw8kuurDRdcjJJtCodJ9sYLA8eQ2vDjT25iHh8R6ruToPmBC7MTkH9HYdpZyEdECkALwGf2Q",
        "YkqjToAHbij6mKp8P4qCtomNc5P7Dwijc2ckedi5cxdEnHx8nTWJMkNSzsUkDpvhEiqLdYa6ziAqcdgVYAztKxY"
    ), twoSigners.signatures());
    assertEquals(7, twoSigners.accountKeys().size());
    assertTrue(twoSigners.accountKeys().get(0).signer());
    assertTrue(twoSigners.accountKeys().get(1).signer());
    assertFalse(twoSigners.accountKeys().get(2).signer());
  }

  /// The convenience overloads keep the client's commitment and request rewards, and the ceiling
  /// is still named; the synthetic reply exercises a null `source` and the per-transaction
  /// `rewards` member the reduced meta carries whenever rewards are requested.
  @Test
  void theConveniencesKeepTheClientCommitmentAndRewards() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlock","params":[7,{"encoding":"base64","commitment":"confirmed","transactionDetails":"accounts","rewards":true,"maxSupportedTransactionVersion":1}]}""", """
        {"jsonrpc":"2.0","result":{"blockHeight":5,"blockTime":6,"blockhash":"hashA","previousBlockhash":"hashB","parentSlot":6,\
        "rewards":[{"pubkey":"%s","lamports":11,"postBalance":22,"rewardType":"Fee","commission":null}],\
        "transactions":[{"meta":{"err":null,"fee":5000,"postBalances":[1],"postTokenBalances":[],"preBalances":[6000],"preTokenBalances":[],\
        "rewards":[{"pubkey":"%s","lamports":3,"postBalance":4,"rewardType":"Rent","commission":null}],"status":{"Ok":null}},\
        "transaction":{"accountKeys":[{"pubkey":"%s","signer":true,"source":null,"writable":false}],"signatures":["sigA","sigB"]},"version":0}]},"id":1}"""
        .formatted(TOKEN_PROGRAM, TOKEN_PROGRAM, TOKEN_PROGRAM));
    final var block = rpcClient.getBlock(7, BlockTxDetails.accounts).join();
    assertEquals(5, block.blockHeight());
    assertEquals(1, block.rewards().size());
    assertEquals(11, block.rewards().getFirst().lamports());
    assertTrue(block.signatures().isEmpty());

    final var tx = block.transactions().getFirst();
    assertEquals(0, tx.version());
    assertFalse(tx.isLegacy());
    assertNull(tx.data());
    assertEquals(List.of("sigA", "sigB"), tx.signatures());
    assertEquals(1, tx.accountKeys().size());
    assertKey(tx.accountKeys().getFirst(), TOKEN_PROGRAM, false, true, null);
    final var meta = tx.meta();
    assertEquals(5000, meta.fee());
    assertEquals(List.of(6000L), meta.preBalances());
    assertEquals(List.of(1L), meta.postBalances());
    final var rewards = meta.rewards();
    assertEquals(1, rewards.size());
    final var reward = rewards.getFirst();
    assertEquals(key(TOKEN_PROGRAM), reward.publicKey());
    assertEquals(3, reward.lamports());
    assertEquals(4, reward.postBalance());
    assertEquals(RewardType.RENT, reward.rewardType());
  }

  /// The explicit-commitment convenience also requests rewards, for a finalized block here.
  @Test
  void theCommitmentConvenienceNamesTheCeiling() {
    registerRequest("""
        {"jsonrpc":"2.0","id":1,"method":"getBlock","params":[8,{"encoding":"base64","commitment":"finalized","transactionDetails":"accounts","rewards":true,"maxSupportedTransactionVersion":1}]}""", """
        {"jsonrpc":"2.0","result":{"blockHeight":9,"blockhash":"hashC","previousBlockhash":"hashD","parentSlot":7,"rewards":[],"transactions":[]},"id":1}""");
    final var block = rpcClient.getBlock(Commitment.FINALIZED, 8, BlockTxDetails.accounts).join();
    assertEquals(9, block.blockHeight());
    assertTrue(block.transactions().isEmpty());
  }

  /// What the node answered for the same block when the ceiling was omitted: the client never
  /// sends that request for this mode any more, so the captured body is replayed through the
  /// client's error path to pin its classification and the message that names the parameter.
  @Test
  void anOmittedCeilingIsTheNodesUnsupportedVersionRefusal() {
    registerRequest(REQUEST, """
        {"jsonrpc":"2.0","error":{"code":-32015,"message":"Transaction version (0) is not supported by the requesting client. Please try the request again with the following configuration parameter: \\"maxSupportedTransactionVersion\\": 0"},"id":1}""");
    final var exception = assertThrows(CompletionException.class,
        () -> rpcClient.getBlock(Commitment.FINALIZED, SLOT, BlockTxDetails.accounts, false).join());
    final var rpcException = assertInstanceOf(JsonRpcException.class, exception.getCause());
    assertEquals(-32015, rpcException.code());
    assertInstanceOf(RpcCustomError.UnsupportedTransactionVersion.class, rpcException.customError());
    assertEquals(
        "Transaction version (0) is not supported by the requesting client. "
            + "Please try the request again with the following configuration parameter: "
            + "\"maxSupportedTransactionVersion\": 0",
        rpcException.getMessage()
    );
  }
}
