package software.sava.rpc.json.http.response;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.encoding.Base58;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

/// Field-level coverage for the hand-rolled json_iterator response parsers: every field
/// predicate branch, both sides of each sentinel conditional, and the records the golden
/// fixtures never reach (websocket notification shapes, custom error variants, simulation
/// results). A compromised or buggy RPC provider is the threat model — a parser that
/// silently drops or defaults a field must fail a test here.
final class ParseResponseFieldTests {

  private static final Context CONTEXT = new Context(1234, "2.3.4");
  private static final String KEY_1 = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
  private static final String KEY_2 = "So11111111111111111111111111111111111111112";

  private static JsonIterator ji(final String json) {
    return JsonIterator.parse(json);
  }

  private static PublicKey key(final String base58) {
    return PublicKey.fromBase58Encoded(base58);
  }

  @Test
  void context() {
    final var context = Context.parse(ji("""
        {"slot":333,"apiVersion":"2.1.0","future":true}"""));
    assertEquals(333, context.slot());
    assertEquals("2.1.0", context.apiVersion());
  }

  @Test
  void processedSlot() {
    final var slot = ProcessedSlot.parse(ji("""
        {"slot":7,"parent":6,"root":5,"future":null}"""));
    assertEquals(7, slot.slot());
    assertEquals(6, slot.parent());
    assertEquals(5, slot.root());
  }

  @Test
  void txLogs() {
    final var logs = TxLogs.parse(ji("""
        {"signature":"5j7s6NiJS3JAkvgkoc18WVAsiSaci2pxB2A6ueCJP4tprA2TFg9wSyTLeYouxPBJEMzJinENTkpA52YStRW5Dia7",
        "err":null,"logs":["Program log: one","Program log: two"],"future":{}}"""), CONTEXT);
    assertSame(CONTEXT, logs.context());
    assertEquals("5j7s6NiJS3JAkvgkoc18WVAsiSaci2pxB2A6ueCJP4tprA2TFg9wSyTLeYouxPBJEMzJinENTkpA52YStRW5Dia7", logs.signature());
    assertNull(logs.error());
    assertEquals(List.of("Program log: one", "Program log: two"), logs.logs());

    final var failed = TxLogs.parse(ji("""
        {"signature":"sig","err":"AccountInUse","logs":[]}"""), CONTEXT);
    assertEquals("AccountInUse", failed.error().getClass().getSimpleName());
    assertEquals(List.of(), failed.logs());
  }

  @Test
  void innerInstructions() {
    final var data = "he11o";
    final var ix = InnerIx.parseIX(ji("""
        {"program":"spl-token","programId":"%s","stackHeight":2,
        "accounts":["%s","%s"],"data":"%s","future":1}""".formatted(KEY_1, KEY_1, KEY_2, data)));
    assertEquals(key(KEY_1), ix.programId());
    assertEquals(2, ix.stackHeight());
    assertEquals(List.of(key(KEY_1), key(KEY_2)), ix.accounts());
    assertArrayEquals(Base58.decode(data), ix.data());

    final var instructions = InnerInstructions.parseInstructions(ji("""
        [{"index":3,"instructions":[{"programId":"%s","stackHeight":1,"accounts":[],"data":""}],"future":0}]"""
        .formatted(KEY_2)));
    assertEquals(1, instructions.size());
    final var inner = instructions.getFirst();
    assertEquals(3, inner.index());
    assertEquals(1, inner.instructions().size());
    assertEquals(key(KEY_2), inner.instructions().getFirst().programId());
    assertEquals(1, inner.instructions().getFirst().stackHeight());
  }

  @Test
  void txSimulationFull() {
    final var accountKeys = List.of(key(KEY_1), key(KEY_2));
    final var simulation = TxSimulation.parse(accountKeys, ji("""
        {"err":null,"fee":5000,"loadedAccountsDataSize":128,
        "logs":["Program log: sim"],
        "preBalances":[10,20],"postBalances":[8,22],
        "preTokenBalances":[],"postTokenBalances":[],
        "accounts":[null,{"executable":false,"lamports":42,"owner":"%s","rentEpoch":361,"space":9,"data":["aGVsbG8=","base64"]}],
        "unitsConsumed":777,
        "returnData":{"programId":"%s","data":["aGk=","base64"],"future":[]},
        "innerInstructions":[{"index":0,"instructions":[]}],
        "replacementBlockhash":{"blockhash":"hash111","lastValidBlockHeight":9000},
        "future":"x"}""".formatted(KEY_1, KEY_2)), CONTEXT);

    assertSame(CONTEXT, simulation.context());
    assertNull(simulation.error());
    assertEquals(OptionalLong.of(5000), simulation.fee());
    assertEquals(128, simulation.loadedAccountsDataSize());
    assertEquals(List.of("Program log: sim"), simulation.logs());
    assertEquals(List.of(10L, 20L), simulation.preBalances());
    assertEquals(List.of(8L, 22L), simulation.postBalances());
    assertEquals(List.of(), simulation.preTokenBalances());
    assertEquals(List.of(), simulation.postTokenBalances());

    final var accounts = simulation.accounts();
    assertEquals(2, accounts.size());
    assertNull(accounts.getFirst());
    final var account = accounts.getLast();
    assertEquals(key(KEY_2), account.pubKey());
    assertFalse(account.executable());
    assertEquals(42, account.lamports());
    assertEquals(key(KEY_1), account.owner());
    assertEquals(BigInteger.valueOf(361), account.rentEpoch());
    assertEquals(9, account.space());
    assertArrayEquals("hello".getBytes(), account.data());

    assertEquals(OptionalInt.of(777), simulation.unitsConsumed());
    assertEquals(key(KEY_2), simulation.programId());
    assertArrayEquals("hi".getBytes(), simulation.data());
    assertEquals(1, simulation.innerInstructions().size());
    assertEquals(0, simulation.innerInstructions().getFirst().index());
    assertEquals("hash111", simulation.replacementBlockHash().blockhash());
    assertEquals(9000, simulation.replacementBlockHash().lastValidBlockHeight());
  }

  @Test
  void txSimulationDefaults() {
    // absent fields and a non-number fee: fee/unitsConsumed empty, list defaults, accounts
    // skipped when no keys were provided to resolve them against
    final var simulation = TxSimulation.parse(ji("""
        {"err":"AccountInUse","fee":null,"accounts":[{"lamports":1}],"unitsConsumed":null}"""));
    assertNull(simulation.context());
    assertEquals("AccountInUse", simulation.error().getClass().getSimpleName());
    assertTrue(simulation.fee().isEmpty());
    assertTrue(simulation.unitsConsumed().isEmpty());
    assertEquals(0, simulation.loadedAccountsDataSize());
    assertEquals(List.of(), simulation.logs());
    assertEquals(List.of(), simulation.accounts());
    assertEquals(List.of(), simulation.innerInstructions());
    assertNull(simulation.preBalances());
    assertNull(simulation.replacementBlockHash());
    assertNull(simulation.programId());
    assertNull(simulation.data());
  }

  @Test
  void txStatuses() {
    final var statuses = TxStatus.parse(List.of("sigA", "sigB", "sigC"), ji("""
        [null,{"slot":55,"confirmations":3,"err":null,"confirmationStatus":"confirmed","future":9},null]"""), CONTEXT);
    final var nil = statuses.get("sigA");
    assertTrue(nil.nil());
    assertEquals(0, nil.slot());
    assertTrue(nil.confirmations().isEmpty());
    assertNull(nil.error());
    assertNull(nil.confirmationStatus());
    // null entries share one nil instance
    assertSame(nil, statuses.get("sigC"));

    final var status = statuses.get("sigB");
    assertFalse(status.nil());
    assertEquals(55, status.slot());
    assertEquals(OptionalInt.of(3), status.confirmations());
    assertNull(status.error());
    assertEquals(Commitment.CONFIRMED, status.confirmationStatus());

    // each nil() condition flips the verdict on its own
    assertFalse(new TxStatus(CONTEXT, 1, OptionalInt.empty(), null, null).nil());
    assertFalse(new TxStatus(CONTEXT, 0, OptionalInt.of(1), null, null).nil());
    assertFalse(new TxStatus(CONTEXT, 0, OptionalInt.empty(), new TransactionError.AccountInUse(), null).nil());
    assertFalse(new TxStatus(CONTEXT, 0, OptionalInt.empty(), null, Commitment.FINALIZED).nil());

    // non-number confirmations are skipped
    final var finalized = TxStatus.parseList(ji("""
        [{"slot":77,"confirmations":null,"err":"AccountInUse","confirmationStatus":"finalized"}]"""), CONTEXT);
    assertEquals(1, finalized.size());
    assertEquals(77, finalized.getFirst().slot());
    assertTrue(finalized.getFirst().confirmations().isEmpty());
    assertEquals("AccountInUse", finalized.getFirst().error().getClass().getSimpleName());
    assertEquals(Commitment.FINALIZED, finalized.getFirst().confirmationStatus());
  }

  @Test
  void rpcCustomErrorCodes() {
    assertSame(RpcCustomError.BlockCleanedUp.INSTANCE, RpcCustomError.parseError(-32001));
    assertSame(RpcCustomError.TransactionSignatureVerificationFailure.INSTANCE, RpcCustomError.parseError(-32003));
    assertSame(RpcCustomError.BlockNotAvailable.INSTANCE, RpcCustomError.parseError(-32004));
    assertSame(RpcCustomError.TransactionPrecompileVerificationFailure.INSTANCE, RpcCustomError.parseError(-32006));
    assertSame(RpcCustomError.SlotSkipped.INSTANCE, RpcCustomError.parseError(-32007));
    assertSame(RpcCustomError.NoSnapshot.INSTANCE, RpcCustomError.parseError(-32008));
    assertSame(RpcCustomError.LongTermStorageSlotSkipped.INSTANCE, RpcCustomError.parseError(-32009));
    assertSame(RpcCustomError.KeyExcludedFromSecondaryIndex.INSTANCE, RpcCustomError.parseError(-32010));
    assertSame(RpcCustomError.TransactionHistoryNotAvailable.INSTANCE, RpcCustomError.parseError(-32011));
    assertSame(RpcCustomError.ScanError.INSTANCE, RpcCustomError.parseError(-32012));
    assertSame(RpcCustomError.TransactionSignatureLenMismatch.INSTANCE, RpcCustomError.parseError(-32013));
    assertSame(RpcCustomError.BlockStatusNotAvailableYet.INSTANCE, RpcCustomError.parseError(-32014));
    assertSame(RpcCustomError.UnsupportedTransactionVersion.INSTANCE, RpcCustomError.parseError(-32015));
    assertSame(RpcCustomError.LongTermStorageUnreachable.INSTANCE, RpcCustomError.parseError(-32019));
    assertSame(RpcCustomError.FilterTransactionNotFound.INSTANCE, RpcCustomError.parseError(-32020));
    assertSame(RpcCustomError.NoSlotHistory.INSTANCE, RpcCustomError.parseError(-32021));
    assertSame(RpcCustomError.Unknown.INSTANCE, RpcCustomError.parseError(-32000));

    // long overloads clamp out-of-int-range codes to Unknown
    assertSame(RpcCustomError.Unknown.INSTANCE, RpcCustomError.parseError(Long.MIN_VALUE));
    assertSame(RpcCustomError.Unknown.INSTANCE, RpcCustomError.parseError(Long.MAX_VALUE));
    assertSame(RpcCustomError.SlotSkipped.INSTANCE, RpcCustomError.parseError((long) -32007));
  }

  @Test
  void rpcCustomErrorData() {
    final var preflight = RpcCustomError.parseError(-32002, ji("""
        {"err":null,"logs":["Program log: preflight"],"unitsConsumed":42}"""));
    final var simulation = assertInstanceOf(RpcCustomError.SendTransactionPreflightFailure.class, preflight).simulation();
    assertEquals(List.of("Program log: preflight"), simulation.logs());
    assertEquals(OptionalInt.of(42), simulation.unitsConsumed());

    final var unhealthy = assertInstanceOf(
        RpcCustomError.NodeUnhealthy.class,
        RpcCustomError.parseError(-32005, ji("""
            {"numSlotsBehind":123,"future":0}""")));
    assertEquals(OptionalLong.of(123), unhealthy.numSlotsBehind());
    final var unhealthyUnknown = assertInstanceOf(
        RpcCustomError.NodeUnhealthy.class,
        RpcCustomError.parseError(-32005, ji("{}")));
    assertTrue(unhealthyUnknown.numSlotsBehind().isEmpty());

    final var minContext = assertInstanceOf(
        RpcCustomError.MinContextSlotNotReached.class,
        RpcCustomError.parseError(-32016, ji("""
            {"contextSlot":991,"future":0}""")));
    assertEquals(991, minContext.contextSlot());

    final var rewardsActive = assertInstanceOf(
        RpcCustomError.EpochRewardsPeriodActive.class,
        RpcCustomError.parseError(-32017, ji("""
            {"slot":11,"currentBlockHeight":22,"rewardsCompleteBlockHeight":33,"future":0}""")));
    assertEquals(OptionalLong.of(11), rewardsActive.slot());
    assertEquals(22, rewardsActive.currentBlockHeight());
    assertEquals(33, rewardsActive.rewardsCompleteBlockHeight());
    // pre-Agave-3.0 nodes do not report the slot
    final var legacyRewardsActive = assertInstanceOf(
        RpcCustomError.EpochRewardsPeriodActive.class,
        RpcCustomError.parseError(-32017, ji("""
            {"currentBlockHeight":22,"rewardsCompleteBlockHeight":33}""")));
    assertTrue(legacyRewardsActive.slot().isEmpty());

    final var notBoundary = assertInstanceOf(
        RpcCustomError.SlotNotEpochBoundary.class,
        RpcCustomError.parseError(-32018, ji("""
            {"slot":444,"future":0}""")));
    assertEquals(444, notBoundary.slot());

    // codes without a data payload skip it and fall back to the code mapping
    assertSame(RpcCustomError.SlotSkipped.INSTANCE, RpcCustomError.parseError(-32007, ji("{}")));
    assertSame(RpcCustomError.Unknown.INSTANCE, RpcCustomError.parseError(Long.MAX_VALUE, ji("{}")));
  }

  @Test
  void jsonRpcException() {
    final var exception = JsonRpcException.parseException(ji("""
        {"code":-32005,"message":"Node is behind by 42 slots","data":{"numSlotsBehind":42},"future":0}"""), OptionalLong.of(7));
    assertEquals(-32005, exception.code());
    assertEquals("Node is behind by 42 slots", exception.getMessage());
    assertEquals(OptionalLong.of(7), exception.retryAfterSeconds());
    final var unhealthy = assertInstanceOf(RpcCustomError.NodeUnhealthy.class, exception.customError());
    assertEquals(OptionalLong.of(42), unhealthy.numSlotsBehind());

    // without a data payload the custom error falls back to the code mapping
    final var noData = JsonRpcException.parseException(ji("""
        {"code":-32007,"message":"skipped"}"""), null);
    assertEquals(-32007, noData.code());
    assertTrue(noData.retryAfterSeconds().isEmpty());
    assertSame(RpcCustomError.SlotSkipped.INSTANCE, noData.customError());

    final var preflight = JsonRpcException.parseException(ji("""
        {"code":-32002,"message":"preflight","data":{"logs":["Program log: fail"]}}"""), null);
    final var failure = assertInstanceOf(RpcCustomError.SendTransactionPreflightFailure.class, preflight.customError());
    assertEquals(List.of("Program log: fail"), failure.simulation().logs());
  }

  @Test
  void jsonUtilEncodedData() {
    assertArrayEquals("hello".getBytes(), JsonUtil.parseEncodedData(ji("\"aGVsbG8=\"")));
    assertArrayEquals(new byte[0], JsonUtil.parseEncodedData(ji("[null]")));
    assertArrayEquals("hello".getBytes(), JsonUtil.parseEncodedData(ji("""
        ["aGVsbG8=","base64"]""")));
    assertArrayEquals(Base58.decode("he11o"), JsonUtil.parseEncodedData(ji("""
        ["he11o","base58"]""")));
    assertArrayEquals("hello".getBytes(), JsonUtil.parseEncodedData(ji("""
        ["aGVsbG8=","base64+zstd"]""")));
    assertArrayEquals(new byte[0], JsonUtil.parseEncodedData(ji("42")));
    // the single-element-array branch is unreachable from real providers (agave always
    // sends [data, encoding] pairs) and throws: the cursor is already past the element
    // when decodeBase64String runs
    assertThrows(systems.comodal.jsoniter.JsonException.class,
        () -> JsonUtil.parseEncodedData(ji("[\"aGVsbG8=\"]")));
  }

  @Test
  void jsonUtilToJsonIntArray() {
    assertEquals("null", JsonUtil.toJsonIntArray(null));
    assertEquals("[]", JsonUtil.toJsonIntArray(new byte[0]));
    assertEquals("[7]", JsonUtil.toJsonIntArray(new byte[]{7}));
    // bytes are unsigned on the wire
    assertEquals("[0,127,128,255]", JsonUtil.toJsonIntArray(new byte[]{0, 127, (byte) 128, (byte) 255}));
  }

  @Test
  void epochSchedule() {
    final var schedule = EpochSchedule.parse(ji("""
        {"firstNormalEpoch":14,"firstNormalSlot":524256,"leaderScheduleSlotOffset":432000,
        "slotsPerEpoch":432000,"warmup":true,"future":0}"""));
    assertEquals(14, schedule.firstNormalEpoch());
    assertEquals(524256, schedule.firstNormalSlot());
    assertEquals(432000, schedule.leaderScheduleSlotOffset());
    assertEquals(432000, schedule.slotsPerEpoch());
    assertTrue(schedule.warmup());
  }

  @Test
  void clusterNode() {
    final var nodes = ClusterNode.parse(ji("""
        [{"gossip":"g:8001","pubkey":"%s","rpc":"r:8899","pubsub":"p:8900","serveRepair":"s:8010",
        "tpu":"t:8003","tpuForwards":"tf:8004","tpuForwardsQuic":"tfq:8005","tpuQuic":"tq:8006",
        "tpuVote":"tv:8007","tvu":"tu:8002","version":"2.3.6","clientId":"agave","featureSet":1234567,
        "shredVersion":50093,"future":0}]""".formatted(KEY_1)));
    assertEquals(1, nodes.size());
    final var node = nodes.getFirst();
    assertEquals("g:8001", node.gossip());
    assertEquals(key(KEY_1), node.publicKey());
    assertEquals("r:8899", node.rpc());
    assertEquals("p:8900", node.pubsub());
    assertEquals("s:8010", node.serveRepair());
    assertEquals("t:8003", node.tpu());
    assertEquals("tf:8004", node.tpuForwards());
    assertEquals("tfq:8005", node.tpuForwardsQuic());
    assertEquals("tq:8006", node.tpuQuic());
    assertEquals("tv:8007", node.tpuVote());
    assertEquals("tu:8002", node.tvu());
    assertEquals("2.3.6", node.version());
    assertEquals("agave", node.clientId());
    assertEquals(1234567, node.featureSet());
    assertEquals(50093, node.shredVersion());

    // null ports and versions are skipped, not zeroed through readLong
    final var sparse = ClusterNode.parse(ji("""
        [{"pubkey":"%s","featureSet":null,"shredVersion":null}]""".formatted(KEY_2))).getFirst();
    assertEquals(0, sparse.featureSet());
    assertEquals(0, sparse.shredVersion());
    assertNull(sparse.rpc());
  }

  @Test
  void lamports() {
    final var lamports = Lamports.parse(ji("5000"), CONTEXT);
    assertSame(CONTEXT, lamports.context());
    assertEquals(5000, lamports.lamports());
    assertEquals(5000, lamports.asLong());
    assertEquals(BigInteger.valueOf(5000), lamports.amount());
    assertEquals(9, lamports.decimals());
    assertEquals(0, new BigDecimal("0.000005").compareTo(lamports.toDecimal()));

    // negative longs are u64 lamport balances beyond Long.MAX_VALUE
    final var u64 = new Lamports(CONTEXT, -1);
    assertEquals(new BigInteger("18446744073709551615"), u64.amount());
    assertEquals(-1, u64.asLong());
  }

  @Test
  void accountInfo() {
    final var account = AccountInfo.parseAccount(ji("""
        {"pubkey":"%s","account":{"executable":true,"lamports":88,"owner":"%s","rentEpoch":361,"space":4,
        "data":["aGk=","base64"],"future":0}}""".formatted(KEY_1, KEY_2)), CONTEXT, AccountInfo.BYTES_IDENTITY);
    assertEquals(key(KEY_1), account.pubKey());
    assertSame(CONTEXT, account.context());
    assertTrue(account.executable());
    assertEquals(88, account.lamports());
    assertEquals(key(KEY_2), account.owner());
    assertEquals(BigInteger.valueOf(361), account.rentEpoch());
    assertEquals(4, account.space());
    assertArrayEquals("hi".getBytes(), account.data());
    assertEquals(88, account.asLong());
    assertEquals(BigInteger.valueOf(88), account.amount());
    assertEquals(9, account.decimals());
  }

  @Test
  void txAndSkeleton() {
    // a minimal legacy transaction: 1 signature, 3-byte header, 1 account, blockhash, no
    // instructions — enough for TransactionSkeleton to deserialize
    final byte[] txBytes = new byte[1 + 64 + 3 + 1 + 32 + 32 + 1];
    txBytes[0] = 1;
    txBytes[65] = 1;
    final var txBase64 = java.util.Base64.getEncoder().encodeToString(txBytes);

    final var tx = Tx.parse(ji("""
        {"slot":100,"blockTime":1700000000,"meta":null,"transaction":["%s","base64"],
        "version":0,"transactionIndex":0,"future":9}""".formatted(txBase64)));
    assertEquals(100, tx.slot());
    assertEquals(OptionalLong.of(1700000000), tx.blockTime());
    assertNull(tx.meta());
    assertArrayEquals(txBytes, tx.data());
    assertEquals(0, tx.version());
    assertFalse(tx.isLegacy());
    assertEquals(OptionalInt.of(0), tx.transactionIndex());
    assertNotNull(tx.skeleton());
    assertEquals(1, tx.skeleton().numSignatures());

    // non-number and non-array shapes are skipped; zero blockTime stays empty
    final var sparse = Tx.parse(ji("""
        {"blockTime":0,"transaction":"legacy","version":null,"transactionIndex":null}"""));
    assertTrue(sparse.blockTime().isEmpty());
    assertNull(sparse.data());
    assertTrue(sparse.isLegacy());
    assertTrue(sparse.transactionIndex().isEmpty());
    assertNull(sparse.skeleton());

    final var blockTx = BlockTx.parse(ji("""
        {"meta":null,"transaction":["%s","base64"],"zDecoyArr":["QQ==","base64"]}""".formatted(txBase64)));
    assertNull(blockTx.meta());
    assertArrayEquals(txBytes, blockTx.data());
    assertNull(BlockTx.parse(ji("""
        {"transaction":"jsonParsed"}""")).data());
  }

  @Test
  void block() {
    final var block = Block.parse(ji("""
        {"blockHeight":5,"blockTime":6,"blockhash":"hashA","previousBlockhash":"hashB",
        "parentSlot":7,"numRewardPartitions":2,
        "rewards":[{"pubkey":"%s","lamports":11,"postBalance":22,"zDecoyNum":123,"rewardType":"Voting","commission":1}],
        "signatures":["sigA","sigB"],
        "transactions":[{"meta":null,"transaction":"skipped"}],"future":0}""".formatted(KEY_1)));
    assertEquals(5, block.blockHeight());
    assertEquals(6, block.blockTime());
    assertEquals("hashA", block.blockHash());
    assertEquals("hashB", block.previousBlockHash());
    assertEquals(7, block.parentSlot());
    assertEquals(2, block.numRewardPartitions());
    assertEquals(1, block.rewards().size());
    final var reward = block.rewards().getFirst();
    assertEquals(key(KEY_1), reward.publicKey());
    assertEquals(11, reward.lamports());
    assertEquals(22, reward.postBalance());
    assertEquals(software.sava.rpc.json.http.response.RewardType.VOTING, reward.rewardType());
    assertEquals(1, reward.commission());
    assertEquals(List.of("sigA", "sigB"), block.signatures());
    assertEquals(1, block.transactions().size());

    // absent collections default to empty; non-number partition counts are skipped
    final var sparse = Block.parse(ji("""
        {"numRewardPartitions":null,"rewards":[{"commission":null}]}"""));
    assertEquals(0, sparse.numRewardPartitions());
    assertEquals(List.of(), sparse.signatures());
    assertEquals(List.of(), sparse.transactions());
    assertEquals(1, sparse.rewards().size());
    assertEquals(0, sparse.rewards().getFirst().commission());
  }

  @Test
  void blockCommitment() {
    // 33 entries forces the 32-slot buffer to grow
    final var values = new StringBuilder();
    for (int i = 1; i <= 33; ++i) {
      if (i > 1) {
        values.append(',');
      }
      values.append(i);
    }
    final var commitment = BlockCommitment.parse(ji("""
        {"commitment":[%s],"totalStake":999,"future":0}""".formatted(values)));
    assertEquals(999, commitment.totalStake());
    assertTrue(commitment.commitment().length >= 33);
    for (int i = 0; i < 33; ++i) {
      assertEquals(i + 1, commitment.commitment()[i]);
    }

    final var nullCommitment = BlockCommitment.parse(ji("""
        {"commitment":null,"totalStake":1}"""));
    assertEquals(0, nullCommitment.commitment().length);
    assertEquals(1, nullCommitment.totalStake());
  }

  @Test
  void txInstruction() {
    final var instructions = TxInstruction.parseInstructions(ji("""
        [{"programIdIndex":4,"accounts":[1,2,3],"data":"he11o","stackHeight":2,"future":0},
        {"programIdIndex":5,"accounts":[],"data":"","stackHeight":null}]"""));
    assertEquals(2, instructions.size());
    final var ix = instructions.getFirst();
    assertEquals(4, ix.programIdIndex());
    assertArrayEquals(new int[]{1, 2, 3}, ix.accountIndices());
    assertEquals("he11o", ix.b58Data());
    assertEquals(2, ix.stackHeight());
    assertEquals(5, instructions.getLast().programIdIndex());
  }

  @Test
  void txSigs() {
    final var sigs = TxSig.parseSignatures(ji("""
        [{"slot":9,"transactionIndex":0,"blockTime":1700000001,"confirmationStatus":"processed",
        "memo":"a memo","signature":"sigX","err":null,"zDecoyStr":"AccountInUse"},
        {"slot":10,"blockTime":0,"error":"AccountInUse","signature":"sigY"},
        {"slot":11,"err":"AccountInUse","signature":"sigZ"}]"""));
    assertEquals(3, sigs.size());
    final var sig = sigs.getFirst();
    assertEquals(9, sig.slot());
    assertEquals(OptionalInt.of(0), sig.transactionIndex());
    assertEquals(OptionalLong.of(1700000001), sig.blockTime());
    assertEquals(Commitment.PROCESSED, sig.confirmationStatus());
    assertEquals("a memo", sig.memo());
    assertEquals("sigX", sig.signature());
    assertNull(sig.transactionError());

    final var failed = sigs.get(1);
    assertEquals(10, failed.slot());
    assertTrue(failed.transactionIndex().isEmpty());
    assertTrue(failed.blockTime().isEmpty());
    assertEquals("AccountInUse", failed.transactionError().getClass().getSimpleName());
    assertNull(failed.memo());
    assertEquals("AccountInUse", sigs.getLast().transactionError().getClass().getSimpleName());
  }

  @Test
  void smallRecords() {
    final var version = Version.parse(ji("""
        {"feature-set":3271415109,"solana-core":"2.3.6","future":0}"""));
    assertEquals(3271415109L, version.featureSet());
    assertEquals("2.3.6", version.version());
    assertEquals(0, Version.parse(ji("""
        {"feature-set":null}""")).featureSet());

    final var samples = PerfSample.parse(ji("""
        [{"slot":1,"numSlots":2,"numTransactions":3,"numNonVoteTransaction":4,"samplePeriodSecs":5,"future":0}]"""));
    final var sample = samples.getFirst();
    assertEquals(1, sample.slot());
    assertEquals(2, sample.numSlots());
    assertEquals(3, sample.numTransactions());
    assertEquals(4, sample.numNonVoteTransaction());
    assertEquals(5, sample.samplePeriodSecs());

    final var governor = InflationGovernor.parse(ji("""
        {"foundation":0.05,"foundationTerm":7.5,"initial":0.08,"taper":0.15,"terminal":0.015,"future":0}"""));
    assertEquals(0.05, governor.foundation());
    assertEquals(7.5, governor.foundationTerm());
    assertEquals(0.08, governor.initial());
    assertEquals(0.15, governor.taper());
    assertEquals(0.015, governor.terminal());

    final var rate = InflationRate.parse(ji("""
        {"epoch":800,"foundation":0.01,"total":0.06,"validator":0.05,"future":0}"""));
    assertEquals(800, rate.epoch());
    assertEquals(0.01, rate.foundation());
    assertEquals(0.06, rate.total());
    assertEquals(0.05, rate.validator());

    final var rewards = InflationReward.parse(ji("""
        [null,{"amount":10,"effectiveSlot":11,"epoch":12,"postBalance":13,"commission":14,"future":0},
        {"commissionBps":1400},{"commissionBps":null}]"""));
    assertEquals(4, rewards.size());
    assertEquals(0, rewards.getFirst().amount());
    final var inflationReward = rewards.get(1);
    assertEquals(10, inflationReward.amount());
    assertEquals(11, inflationReward.effectiveSlot());
    assertEquals(12, inflationReward.epoch());
    assertEquals(13, inflationReward.postBalance());
    assertEquals(14, inflationReward.commission());
    assertFalse(inflationReward.commissionBps());
    // basis points overwrite the whole-percent commission and flag the unit
    assertEquals(1400, rewards.get(2).commission());
    assertTrue(rewards.get(2).commissionBps());
    assertFalse(rewards.getLast().commissionBps());

    // the unknown field leads, so a parser that stops iterating early loses the identity
    final var identity = Identity.parse(ji("""
        {"future":0,"identity":"%s"}""".formatted(KEY_1)));
    assertEquals(key(KEY_1), identity.identityKey());

    final var snapshot = HighestSnapshotSlot.parse(ji("""
        {"full":100,"incremental":150,"future":0}"""));
    assertEquals(100, snapshot.full());
    assertEquals(150, snapshot.incremental());
    assertEquals(0, HighestSnapshotSlot.parse(ji("""
        {"full":1,"incremental":null}""")).incremental());

    final var epochInfo = EpochInfo.parse(ji("""
        {"absoluteSlot":1,"blockHeight":2,"epoch":3,"slotIndex":4,"slotsInEpoch":5,"transactionCount":6,"future":0}"""));
    assertEquals(1, epochInfo.absoluteSlot());
    assertEquals(2, epochInfo.blockHeight());
    assertEquals(3, epochInfo.epoch());
    assertEquals(4, epochInfo.slotIndex());
    assertEquals(5, epochInfo.slotsInEpoch());
    assertEquals(6, epochInfo.transactionCount());
    assertEquals(0, EpochInfo.parse(ji("""
        {"transactionCount":null}""")).transactionCount());
  }

  @Test
  void zeroSentinels() {
    // zero is a present value, not the absent sentinel
    final var unhealthy = assertInstanceOf(
        RpcCustomError.NodeUnhealthy.class,
        RpcCustomError.parseError(-32005, ji("""
            {"future":0,"numSlotsBehind":0}""")));
    assertEquals(OptionalLong.of(0), unhealthy.numSlotsBehind());

    final var rewardsActive = assertInstanceOf(
        RpcCustomError.EpochRewardsPeriodActive.class,
        RpcCustomError.parseError(-32017, ji("""
            {"future":0,"slot":0,"currentBlockHeight":1,"rewardsCompleteBlockHeight":2}""")));
    assertEquals(OptionalLong.of(0), rewardsActive.slot());

    final var minContext = assertInstanceOf(
        RpcCustomError.MinContextSlotNotReached.class,
        RpcCustomError.parseError(-32016, ji("""
            {"future":0,"contextSlot":5}""")));
    assertEquals(5, minContext.contextSlot());

    final var notBoundary = assertInstanceOf(
        RpcCustomError.SlotNotEpochBoundary.class,
        RpcCustomError.parseError(-32018, ji("""
            {"future":0,"slot":6}""")));
    assertEquals(6, notBoundary.slot());

    final var status = TxStatus.parseList(ji("""
        [{"slot":1,"confirmations":0}]"""), CONTEXT).getFirst();
    assertEquals(OptionalInt.of(0), status.confirmations());

    final var simulation = TxSimulation.parse(List.of(), ji("""
        {"fee":0,"unitsConsumed":0,"logs":[],"accounts":[{"lamports":1}]}"""), CONTEXT);
    assertEquals(OptionalLong.of(0), simulation.fee());
    assertEquals(OptionalInt.of(0), simulation.unitsConsumed());
    assertSame(TxSimulation.NO_LOGS, simulation.logs());
    assertSame(TxSimulation.NO_ACCOUNTS, simulation.accounts());

    // an empty accounts array parsed against real keys still collapses to the sentinel
    final var emptyAccounts = TxSimulation.parse(List.of(key(KEY_1)), ji("""
        {"accounts":[]}"""), CONTEXT);
    assertSame(TxSimulation.NO_ACCOUNTS, emptyAccounts.accounts());
  }

  @Test
  void txResult() {
    assertNull(TxResult.parseResult(ji("null"), CONTEXT));
    assertNull(TxResult.parseResult(ji("[1,2]"), CONTEXT));

    final var string = TxResult.parseResult(ji("\"ok\""), CONTEXT);
    assertEquals("ok", string.value());
    assertNull(string.error());
    assertSame(CONTEXT, string.context());

    assertEquals("42", TxResult.parseResult(ji("42"), CONTEXT).value());
    assertEquals("true", TxResult.parseResult(ji("true"), CONTEXT).value());
    assertEquals("false", TxResult.parseResult(ji("false"), CONTEXT).value());

    final var error = TxResult.parseResult(ji("""
        {"err":"AccountInUse","future":0}"""), CONTEXT);
    assertNull(error.value());
    assertEquals("AccountInUse", error.error().getClass().getSimpleName());
  }

  /// A dropped skip on an unknown-field or unknown-payload branch leaves the iterator
  /// mid-value — invisible when the skipped value is the last thing parsed, which is
  /// where the trailing-decoy fixtures sit. These fixtures lead with the skipped value
  /// and require a real field after it to still parse.
  @Test
  void skippedValuesLeaveTheIteratorAligned() {
    final var slot = ProcessedSlot.parse(ji("""
        {"future":[7,8],"slot":7,"parent":6,"root":5}"""));
    assertEquals(7, slot.slot());
    assertEquals(6, slot.parent());
    assertEquals(5, slot.root());

    final var logs = TxLogs.parse(ji("""
        {"future":{"nested":[1]},"signature":"sig","err":null,"logs":["l"]}"""), CONTEXT);
    assertEquals("sig", logs.signature());
    assertEquals(List.of("l"), logs.logs());

    // null and array results are skipped whole, then parsing continues after them
    var it = ji("""
        {"result":null,"after":1}""");
    it.skipUntil("result");
    assertNull(TxResult.parseResult(it, CONTEXT));
    assertEquals(1, it.skipUntil("after").readInt());

    it = ji("""
        {"result":[1,2],"after":2}""");
    it.skipUntil("result");
    assertNull(TxResult.parseResult(it, CONTEXT));
    assertEquals(2, it.skipUntil("after").readInt());

    // an unknown custom-error code consumes its whole data payload
    it = ji("""
        {"data":{"detail":[1,2]},"after":3}""");
    it.skipUntil("data");
    assertSame(RpcCustomError.Unknown.INSTANCE, RpcCustomError.parseError(-32000, it));
    assertEquals(3, it.skipUntil("after").readInt());

    // non-string, non-object instruction errors are skipped and yield null
    it = ji("""
        {"err":42,"after":4}""");
    it.skipUntil("err");
    assertNull(IxError.parseError(it));
    assertEquals(4, it.skipUntil("after").readInt());

    // every encoded-data branch must consume the whole value: the [null] fast path,
    // the [data, encoding] pair, and the unsupported-type fallback
    it = ji("""
        {"data":[null,13],"after":5}""");
    it.skipUntil("data");
    assertArrayEquals(new byte[0], JsonUtil.parseEncodedData(it));
    assertEquals(5, it.skipUntil("after").readInt());

    it = ji("""
        {"data":["aGVsbG8=","base64"],"after":6}""");
    it.skipUntil("data");
    assertArrayEquals("hello".getBytes(), JsonUtil.parseEncodedData(it));
    assertEquals(6, it.skipUntil("after").readInt());

    it = ji("""
        {"data":true,"after":7}""");
    it.skipUntil("data");
    assertArrayEquals(new byte[0], JsonUtil.parseEncodedData(it));
    assertEquals(7, it.skipUntil("after").readInt());
  }

  /// The trailing decoy fields in these fixtures carry values of the same JSON type as a
  /// real field with a different value: a parser mutated to treat every field name as a
  /// match lets the decoy overwrite the real value and fails the assertion.
  @Test
  void dispatchDecoys() {
    final var latest = LatestBlockHash.parse(ji("""
        {"blockhash":"hashX","lastValidBlockHeight":500,"zDecoyNum":999,"zDecoyStr":"decoy"}"""), CONTEXT);
    assertEquals("hashX", latest.blockHash());
    assertEquals(500, latest.lastValidBlockHeight());

    final var replacement = ReplacementBlockHash.parse(ji("""
        {"blockhash":"hashY","lastValidBlockHeight":600,"zDecoyNum":999,"zDecoyStr":"decoy"}"""));
    assertEquals("hashY", replacement.blockhash());
    assertEquals(600, replacement.lastValidBlockHeight());

    final var fees = PrioritizationFee.parse(ji("""
        [{"slot":10,"prioritizationFee":20,"zDecoyNum":999}]"""));
    assertEquals(10, fees.getFirst().slot());
    assertEquals(20, fees.getFirst().prioritizationFee());

    final var balance = TokenBalance.parse(ji("""
        {"accountIndex":2,"mint":"%s","owner":"%s","programId":"%s",
        "uiTokenAmount":{"amount":"77","decimals":5,"zDecoyNum":9},
        "zDecoyObj":{"amount":"1","decimals":1}}""".formatted(KEY_1, KEY_2, KEY_1)));
    assertEquals(2, balance.accountIndex());
    assertEquals(key(KEY_1), balance.mint());
    assertEquals(key(KEY_2), balance.owner());
    assertEquals(key(KEY_1), balance.programId());
    assertEquals(BigInteger.valueOf(77), balance.amount());
    assertEquals(5, balance.decimals());

    final var inner = TxInnerInstruction.parse(ji("""
        {"index":6,"instructions":[{"programIdIndex":1,"accounts":[0],"data":"he11o","stackHeight":1}],
        "zDecoyArr":[{"programIdIndex":9,"accounts":[],"data":"","stackHeight":9}]}"""));
    assertEquals(6, inner.index());
    assertEquals(1, inner.instructions().size());
    assertEquals(1, inner.instructions().getFirst().programIdIndex());

    final var returnData = TxReturnData.parse(ji("""
        {"programId":"%s","data":["aGk=","base64"],"zDecoyArr":["QQ==","base64"]}""".formatted(KEY_1)));
    assertEquals(key(KEY_1), returnData.programId());
    assertArrayEquals("hi".getBytes(), returnData.data());
    assertNull(TxReturnData.parse(ji("null")));

    final var loaded = LoadedAddresses.parse(ji("""
        {"readonly":["%s"],"writable":["%s"],"zDecoyArr":["%s"]}""".formatted(KEY_1, KEY_2, KEY_1)));
    assertEquals(List.of(key(KEY_1)), loaded.readonly());
    assertEquals(List.of(key(KEY_2)), loaded.writable());
    final var emptyLoaded = LoadedAddresses.parse(ji("""
        {"readonly":[],"writable":[]}"""));
    assertEquals(List.of(), emptyLoaded.readonly());
    assertSame(emptyLoaded.readonly(), emptyLoaded.writable());

    final var accounts = AccountLamports.parseAccounts(ji("""
        [{"lamports":9,"address":"%s","zDecoyNum":1,"zDecoyStr":"%s"}]""".formatted(KEY_1, KEY_2)), CONTEXT);
    assertEquals(9, accounts.getFirst().lamports());
    assertEquals(key(KEY_1), accounts.getFirst().addressKey());

    final var production = BlockProduction.parse(ji("""
        {"byIdentity":{"%s":[8,6]},"range":{"firstSlot":100,"lastSlot":200,"zDecoyNum":9},
        "zDecoyObj":{"firstSlot":1,"lastSlot":2}}""".formatted(KEY_1)), CONTEXT);
    assertEquals(Map.of(key(KEY_1), new ValidatorLeaderInfo(8, 6)), production.leaderInfoMap());
    assertEquals(100, production.firstSlot());
    assertEquals(200, production.lastSlot());

    final var supply = Supply.parse(ji("""
        {"total":30,"circulating":20,"nonCirculating":10,"nonCirculatingAccounts":["%s"],
        "zDecoyArr":["%s"]}""".formatted(KEY_1, KEY_2)), CONTEXT);
    assertEquals(30, supply.total());
    assertEquals(20, supply.circulating());
    assertEquals(10, supply.nonCirculating());
    assertEquals(List.of(key(KEY_1)), supply.nonCirculatingAccountKeys());
    assertEquals(List.of(), Supply.parse(ji("{}"), CONTEXT).nonCirculatingAccountKeys());

    final var voteAccounts = VoteAccounts.parse(ji("""
        {"current":[{"votePubkey":"%s","nodePubkey":"%s","activatedStake":7,"epochVoteAccount":true,
        "commission":5,"inflationRewardsCommissionBps":0,"lastVote":8,"epochCredits":[[1,2,3]],"rootSlot":9,"zDecoyNum":4}],
        "delinquent":[{"votePubkey":"%s","rootSlot":1}],
        "zDecoyArr":[{"votePubkey":"%s","rootSlot":2}]}""".formatted(KEY_1, KEY_2, KEY_2, KEY_1)));
    assertEquals(1, voteAccounts.current().size());
    final var voteAccount = voteAccounts.current().getFirst();
    assertEquals(key(KEY_1), voteAccount.voteKey());
    assertEquals(key(KEY_2), voteAccount.nodeKey());
    assertEquals(7, voteAccount.activatedStake());
    assertTrue(voteAccount.epochVoteAccount());
    assertEquals(5, voteAccount.commission());
    assertEquals(OptionalInt.of(0), voteAccount.inflationRewardsCommissionBps());
    assertEquals(8, voteAccount.lastVote());
    assertEquals(9, voteAccount.rootSlot());
    assertEquals(1, voteAccounts.delinquent().size());
    assertEquals(1, voteAccounts.delinquent().getFirst().rootSlot());
    assertTrue(voteAccounts.delinquent().getFirst().inflationRewardsCommissionBps().isEmpty());
  }

  @Test
  void guardAndSentinelEdges() {
    // non-number values behind NUMBER guards are skipped, not misread
    final var tx = Tx.parse(ji("""
        {"blockTime":null,"transaction":["",""],"version":0}"""));
    assertTrue(tx.blockTime().isEmpty());
    assertEquals(0, tx.data().length);
    // empty transaction data yields no skeleton rather than a deserialization attempt
    assertNull(tx.skeleton());

    final var sig = TxSig.parseSignatures(ji("""
        [{"slot":1,"transactionIndex":null,"blockTime":null,"signature":"s"}]""")).getFirst();
    assertTrue(sig.transactionIndex().isEmpty());
    assertTrue(sig.blockTime().isEmpty());

    final var sample = PerfSample.parse(ji("""
        [{"slot":1,"numNonVoteTransaction":null}]""")).getFirst();
    assertEquals(0, sample.numNonVoteTransaction());

    final var account = AccountInfo.parseAccount(ji("""
        {"pubkey":"%s","account":{"lamports":1,"space":null}}""".formatted(KEY_1)), CONTEXT, AccountInfo.BYTES_IDENTITY);
    assertEquals(0, account.space());

    assertEquals(List.of(), Block.parse(ji("{}")).rewards());

    final var txResult = TxResult.parseResult(ji("""
        {"future":0,"err":"AccountInUse"}"""), CONTEXT);
    assertEquals("AccountInUse", txResult.error().getClass().getSimpleName());
  }
}
