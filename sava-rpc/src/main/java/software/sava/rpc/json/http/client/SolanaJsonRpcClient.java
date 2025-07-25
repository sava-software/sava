package software.sava.rpc.json.http.client;


import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.core.rpc.Filter;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.PublicKeyEncoding;
import software.sava.rpc.json.http.SolanaNetwork;
import software.sava.rpc.json.http.request.BlockTxDetails;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.request.ContextBoolVal;
import software.sava.rpc.json.http.response.*;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static java.net.http.HttpResponse.BodyHandlers.ofByteArray;
import static java.util.Objects.requireNonNullElse;
import static software.sava.core.rpc.Filter.MAX_MEM_COMP_LENGTH;
import static software.sava.rpc.json.PublicKeyEncoding.parseBase58Encoded;
import static software.sava.rpc.json.http.response.AccountInfo.BYTES_IDENTITY;

final class SolanaJsonRpcClient extends JsonRpcHttpClient implements SolanaRpcClient {

  static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(8);
  static final Duration PROGRAM_ACCOUNTS_TIMEOUT = Duration.ofSeconds(120);

  private static final Function<HttpResponse<byte[]>, LatestBlockHash> LATEST_BLOCK_HASH = applyResponseValue(LatestBlockHash::parse);
  private static final Function<HttpResponse<byte[]>, Lamports> CONTEXT_LONG_VAL = applyResponseValue(Lamports::parse);
  private static final Function<HttpResponse<byte[]>, Block> BLOCK = applyResponseResult(Block::parse);
  private static final Function<HttpResponse<byte[]>, BlockHeight> BLOCK_HEIGHT = applyResponseResult(BlockHeight::parse);
  private static final Function<HttpResponse<byte[]>, BlockProduction> BLOCK_PRODUCTION = applyResponseValue(BlockProduction::parse);
  private static final Function<HttpResponse<byte[]>, BlockCommitment> BLOCK_COMMITMENT = applyResponseResult(BlockCommitment::parse);
  private static final Function<HttpResponse<byte[]>, List<ClusterNode>> CLUSTER_NODES = applyResponseResult(ClusterNode::parse);
  private static final Function<HttpResponse<byte[]>, EpochInfo> EPOCH_INFO = applyResponseResult(EpochInfo::parse);
  private static final Function<HttpResponse<byte[]>, EpochSchedule> EPOCH_SCHEDULE = applyResponseResult(EpochSchedule::parse);
  private static final Function<HttpResponse<byte[]>, FeeForMessage> FEE_FOR_MESSAGE = applyResponseValue(FeeForMessage::parse);
  private static final Function<HttpResponse<byte[]>, HighestSnapshotSlot> HIGHEST_SNAPSHOT_SLOT = applyResponseResult(HighestSnapshotSlot::parse);
  private static final Function<HttpResponse<byte[]>, Identity> IDENTITY = applyResponseResult(Identity::parse);
  private static final Function<HttpResponse<byte[]>, NodeHealth> NODE_HEALTH = applyResponse(NodeHealth::parse);
  private static final Function<HttpResponse<byte[]>, InflationGovernor> INFLATION_GOVERNOR = applyResponseResult(InflationGovernor::parse);
  private static final Function<HttpResponse<byte[]>, InflationRate> INFLATION_RATE = applyResponseResult(InflationRate::parse);
  private static final Function<JsonIterator, long[]> PARSE_LONG_ARRAY = ji -> {
    final var longs = new ArrayList<Long>();
    while (ji.readArray()) {
      longs.add(ji.readLong());
    }
    return longs.stream().mapToLong(Long::longValue).toArray();
  };
  private static final Function<HttpResponse<byte[]>, Map<PublicKey, long[]>> LEADER_SCHEDULE = applyResponseResult(ji -> {
    final var schedule = new HashMap<PublicKey, long[]>();
    for (PublicKey validator; (validator = PublicKeyEncoding.parseBase58Encoded(ji)) != null; ) {
      schedule.put(validator, PARSE_LONG_ARRAY.apply(ji));
    }
    return schedule;
  });
  private static final Function<HttpResponse<byte[]>, List<AccountInfo<TokenAccount>>> TOKEN_ACCOUNTS_PARSER =
      applyResponseValue((ji, context) -> AccountInfo.parseAccounts(ji, context, TokenAccount.FACTORY));
  private static final Function<HttpResponse<byte[]>, List<AccountLamports>> TOP_LAMPORT_ACCOUNTS = applyResponseValue(AccountLamports::parseAccounts);
  private static final Function<HttpResponse<byte[]>, List<InflationReward>> INFLATION_REWARDS = applyResponseResult(InflationReward::parse);
  private static final Function<HttpResponse<byte[]>, VoteAccounts> VOTE_ACCOUNTS = applyResponseResult(VoteAccounts::parse);
  private static final Function<HttpResponse<byte[]>, Long> LONG_VAL = applyResponseResult(JsonIterator::readLong);
  private static final Function<HttpResponse<byte[]>, Instant> INSTANT = applyResponseResult(ji -> Instant.ofEpochSecond(ji.readLong()));
  private static final Function<HttpResponse<byte[]>, ContextBoolVal> CONTEXT_BOOL_VAL = applyResponseValue(ContextBoolVal::parse);
  private static final Function<HttpResponse<byte[]>, String> STRING = applyResponseResult(JsonIterator::readString);
  private static final Function<HttpResponse<byte[]>, PublicKey> PUBLIC_KEY = applyResponseResult(PublicKeyEncoding::parseBase58Encoded);
  private static final Function<HttpResponse<byte[]>, List<PublicKey>> PUBLIC_KEY_LIST = applyResponseResult(ji -> {
    final var strings = new ArrayList<PublicKey>();
    while (ji.readArray()) {
      strings.add(parseBase58Encoded(ji));
    }
    return strings;
  });
  private static final Function<HttpResponse<byte[]>, long[]> LONG_ARRAY = applyResponseResult(PARSE_LONG_ARRAY);
  private static final Function<HttpResponse<byte[]>, List<PerfSample>> PERF_SAMPLE = applyResponseResult(PerfSample::parse);
  private static final Function<HttpResponse<byte[]>, List<PrioritizationFee>> PRIORITIZATION_FEE = applyResponseResult(PrioritizationFee::parse);
  private static final Function<HttpResponse<byte[]>, List<TxSig>> TX_SIGNATURES = applyResponseResult(TxSig::parseSignatures);
  private static final Function<HttpResponse<byte[]>, List<TxStatus>> SIG_STATUS_LIST = applyResponseValue(TxStatus::parseList);
  private static final Function<HttpResponse<byte[]>, Supply> SUPPLY = applyResponseValue(Supply::parse);
  private static final Function<HttpResponse<byte[]>, TokenAmount> TOKEN_AMOUNT = applyResponseValue(TokenAmount::parse);
  private static final Function<HttpResponse<byte[]>, String> SEND_TX_RESPONSE_PARSER = applyResponseResult(JsonIterator::readString);
  private static final Function<HttpResponse<byte[]>, List<AccountTokenAmount>> ACCOUNT_TOKEN_AMOUNT = applyResponseValue(AccountTokenAmount::parse);
  private static final Function<HttpResponse<byte[]>, Tx> TRANSACTION = applyResponseResult(Tx::parse);
  private static final Function<HttpResponse<byte[]>, Version> VERSION = applyResponseResult(Version::parse);

  final AtomicLong id;
  private final Commitment defaultCommitment;
  private final Function<HttpResponse<byte[]>, String> sendTxResponseParser;
  private final Function<HttpResponse<byte[]>, LatestBlockHash> latestBlockhashResponseParser;

  SolanaJsonRpcClient(final URI endpoint,
                      final HttpClient httpClient,
                      final Duration requestTimeout,
                      final UnaryOperator<HttpRequest.Builder> extendRequest,
                      final Predicate<HttpResponse<byte[]>> applyResponse,
                      final Commitment defaultCommitment) {
    super(endpoint, httpClient, requestTimeout, extendRequest, applyResponse);
    this.id = new AtomicLong(System.currentTimeMillis());
    this.defaultCommitment = defaultCommitment;
    this.latestBlockhashResponseParser = wrapParser(LATEST_BLOCK_HASH);
    this.sendTxResponseParser = wrapParser(SEND_TX_RESPONSE_PARSER);
  }

  @Override
  public Commitment defaultCommitment() {
    return defaultCommitment;
  }

  @Override
  public CompletableFuture<NodeHealth> getHealth() {
    return getHealth(this.requestTimeout);
  }

  @Override
  public CompletableFuture<NodeHealth> getHealth(final Duration requestTimeout) {
    return sendPostRequest(SolanaJsonRpcClient.NODE_HEALTH, requestTimeout, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getHealth"}""", id.incrementAndGet()
        )
    );
  }

  @Override
  public <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final PublicKey account,
                                                              final BiFunction<PublicKey, byte[], T> factory) {
    return getAccountInfo(defaultCommitment, account, factory);
  }

  @Override
  public <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final Commitment commitment,
                                                              final PublicKey account,
                                                              final BiFunction<PublicKey, byte[], T> factory) {
    return getAccountInfo(commitment, null, 0, 0, account, factory);
  }

  @Override
  public CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final BigInteger minContextSlot,
                                                               final PublicKey account) {
    return getAccountInfo(defaultCommitment, minContextSlot, account, BYTES_IDENTITY);
  }

  @Override
  public CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final int length,
                                                               final int offset,
                                                               final PublicKey account) {
    return getAccountInfo(defaultCommitment, length, offset, account, BYTES_IDENTITY);
  }

  @Override
  public CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final Commitment commitment,
                                                               final BigInteger minContextSlot,
                                                               final PublicKey account) {
    return getAccountInfo(commitment, minContextSlot, account, BYTES_IDENTITY);
  }

  @Override
  public CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final Commitment commitment,
                                                               final int length,
                                                               final int offset,
                                                               final PublicKey account) {
    return getAccountInfo(commitment, length, offset, account, BYTES_IDENTITY);
  }

  @Override
  public CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final BigInteger minContextSlot,
                                                               final int length,
                                                               final int offset,
                                                               final PublicKey account) {
    return getAccountInfo(defaultCommitment, minContextSlot, length, offset, account, BYTES_IDENTITY);
  }

  @Override
  public CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final Commitment commitment,
                                                               final BigInteger minContextSlot,
                                                               final int length,
                                                               final int offset,
                                                               final PublicKey account) {
    return getAccountInfo(commitment, minContextSlot, length, offset, account, BYTES_IDENTITY);
  }

  @Override
  public <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final BigInteger minContextSlot,
                                                              final PublicKey account,
                                                              final BiFunction<PublicKey, byte[], T> factory) {
    return getAccountInfo(defaultCommitment, minContextSlot, account, factory);
  }

  @Override
  public <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final int length,
                                                              final int offset,
                                                              final PublicKey account,
                                                              final BiFunction<PublicKey, byte[], T> factory) {
    return getAccountInfo(defaultCommitment, length, offset, account, factory);
  }

  @Override
  public <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final Commitment commitment,
                                                              final BigInteger minContextSlot,
                                                              final PublicKey account,
                                                              final BiFunction<PublicKey, byte[], T> factory) {
    return getAccountInfo(commitment, minContextSlot, 0, 0, account, factory);
  }

  @Override
  public <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final Commitment commitment,
                                                              final int length,
                                                              final int offset,
                                                              final PublicKey account,
                                                              final BiFunction<PublicKey, byte[], T> factory) {
    return getAccountInfo(commitment, null, length, offset, account, factory);
  }

  @Override
  public <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final Commitment commitment,
                                                              final BigInteger minContextSlot,
                                                              final int length,
                                                              final int offset,
                                                              final PublicKey account,
                                                              final BiFunction<PublicKey, byte[], T> factory) {
    final var builder = new StringBuilder(256);
    builder.append("""
        {"jsonrpc":"2.0","id":""");
    builder.append(id.incrementAndGet());

    builder.append("""
        ,"method":"getAccountInfo","params":[""");
    builder.append('"');
    builder.append(account.toBase58());
    builder.append('"');

    builder.append("""
        ,{"encoding":"base64","commitment":\"""");
    builder.append(commitment.getValue());
    builder.append('"');

    if (minContextSlot != null) {
      builder.append("""
          ,"minContextSlot":""");
      builder.append(minContextSlot);
    }

    if (length != 0) {
      builder.append("""
          ,"dataSlice":{"length":""");
      builder.append(length);
      builder.append("""
          ,"offset":""");
      builder.append(offset);
      builder.append('}');
    }

    builder.append("}]}");

    return sendPostRequest(
        applyResponseValue((ji, context) -> AccountInfo.parse(account, ji, context, factory)),
        builder.toString()
    );
  }

  @Override
  public CompletableFuture<Lamports> getBalance(final PublicKey account) {
    return getBalance(defaultCommitment, account);
  }

  @Override
  public CompletableFuture<Lamports> getBalance(final Commitment commitment, final PublicKey account) {
    return sendPostRequest(CONTEXT_LONG_VAL, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getBalance","params":["%s",{"commitment":"%s"}]}""",
            id.incrementAndGet(), account, commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<Block> getBlock(final long slot) {
    return getBlock(this.defaultCommitment, slot);
  }

  @Override
  public CompletableFuture<Block> getBlock(final long slot, final BlockTxDetails blockTxDetails) {
    return getBlock(this.defaultCommitment, slot, blockTxDetails);
  }

  @Override
  public CompletableFuture<Block> getBlock(final long slot,
                                           final BlockTxDetails blockTxDetails,
                                           final boolean rewards) {
    return getBlock(this.defaultCommitment, slot, blockTxDetails, rewards);
  }

  @Override
  public CompletableFuture<Block> getBlock(final Commitment commitment,
                                           final long slot,
                                           final BlockTxDetails blockTxDetails,
                                           final boolean rewards) {
    return sendPostRequest(BLOCK, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getBlock","params":[%d,{"encoding":"base64","commitment":"%s","transactionDetails":"%s","rewards":%b}]}""",
            id.incrementAndGet(), slot, commitment.getValue(), blockTxDetails, rewards
        )
    );
  }

  @Override
  public CompletableFuture<Block> getBlock(final long slot, final int maxSupportedTransactionVersion) {
    return getBlock(this.defaultCommitment, slot, maxSupportedTransactionVersion);
  }

  @Override
  public CompletableFuture<Block> getBlock(final long slot,
                                           final BlockTxDetails blockTxDetails,
                                           final int maxSupportedTransactionVersion) {
    return getBlock(this.defaultCommitment, slot, blockTxDetails, maxSupportedTransactionVersion);
  }

  @Override
  public CompletableFuture<Block> getBlock(final long slot,
                                           final BlockTxDetails blockTxDetails,
                                           final int maxSupportedTransactionVersion,
                                           final boolean rewards) {
    return getBlock(this.defaultCommitment, slot, blockTxDetails, maxSupportedTransactionVersion, rewards);
  }

  @Override
  public CompletableFuture<Block> getBlock(final Commitment commitment,
                                           final long slot,
                                           final BlockTxDetails blockTxDetails,
                                           final int maxSupportedTransactionVersion,
                                           final boolean rewards) {
    return sendPostRequest(BLOCK, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getBlock","params":[%d,{"encoding":"base64","commitment":"%s","transactionDetails":"%s","maxSupportedTransactionVersion":%d,"rewards":%b}]}""",
            id.incrementAndGet(), slot, commitment.getValue(), blockTxDetails, maxSupportedTransactionVersion, rewards
        )
    );
  }

  @Override
  public CompletableFuture<BlockHeight> getBlockHeight() {
    return getBlockHeight(defaultCommitment);
  }

  @Override
  public CompletableFuture<BlockHeight> getBlockHeight(final Commitment commitment) {
    return sendPostRequest(BLOCK_HEIGHT, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getBlockHeight","params":[{"commitment":"%s"}]}""",
            id.incrementAndGet(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<BlockProduction> getBlockProduction() {
    return getBlockProduction(defaultCommitment);
  }

  @Override
  public CompletableFuture<BlockProduction> getBlockProduction(final Commitment commitment) {
    return sendPostRequest(BLOCK_PRODUCTION, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getBlockProduction","params":[{"commitment":"%s"}]}""",
            id.incrementAndGet(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<BlockProduction> getBlockProduction(final PublicKey identity) {
    return getBlockProduction(defaultCommitment, identity);
  }

  @Override
  public CompletableFuture<BlockProduction> getBlockProduction(final Commitment commitment, final PublicKey identity) {
    return sendPostRequest(BLOCK_PRODUCTION, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getBlockProduction","params":[{"commitment":"%s","identity":"%s"}]}""",
            id.incrementAndGet(), commitment.getValue(), identity
        )
    );
  }

  @Override
  public CompletableFuture<BlockProduction> getBlockProduction(final long firstSlot) {
    return getBlockProduction(defaultCommitment, firstSlot);
  }

  @Override
  public CompletableFuture<BlockProduction> getBlockProduction(final Commitment commitment, final long firstSlot) {
    return sendPostRequest(BLOCK_PRODUCTION, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getBlockProduction","params":[{"commitment":"%s","firstSlot":%d}]}""",
            id.incrementAndGet(), commitment.getValue(), firstSlot
        )
    );
  }

  @Override
  public CompletableFuture<BlockProduction> getBlockProduction(final PublicKey identity, final long firstSlot) {
    return getBlockProduction(defaultCommitment, identity, firstSlot);
  }

  @Override
  public CompletableFuture<BlockProduction> getBlockProduction(final Commitment commitment,
                                                               final PublicKey identity,
                                                               final long firstSlot) {
    return sendPostRequest(BLOCK_PRODUCTION, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getBlockProduction","params":[{"commitment":"%s","identity":"%s","firstSlot":%d}]}""",
            id.incrementAndGet(), commitment.getValue(), identity, firstSlot
        )
    );
  }

  @Override
  public CompletableFuture<BlockCommitment> getBlockCommitment(final long slot) {
    return sendPostRequest(BLOCK_COMMITMENT, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getBlockCommitment","params":[%d]}""", id.incrementAndGet(), slot
        )
    );
  }

  @Override
  public CompletableFuture<long[]> getBlocks(final long startSlot) {
    return getBlocks(defaultCommitment, startSlot);
  }

  @Override
  public CompletableFuture<long[]> getBlocks(final Commitment commitment, final long startSlot) {
    return sendPostRequest(LONG_ARRAY, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getBlocks","params":[%d,{"commitment":"%s"}]}""",
            id.incrementAndGet(), startSlot, commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<long[]> getBlocks(final long startSlot, final long endSlot) {
    return getBlocks(defaultCommitment, startSlot, endSlot);
  }

  @Override
  public CompletableFuture<long[]> getBlocks(final Commitment commitment, final long startSlot, final long endSlot) {
    return sendPostRequest(LONG_ARRAY, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getBlocks","params":[%d,%d,{"commitment":"%s"}]}""",
            id.incrementAndGet(), startSlot, Math.min(endSlot, startSlot + 500_000), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<long[]> getBlocksWithLimit(final long startSlot, final long limit) {
    return getBlocksWithLimit(defaultCommitment, startSlot, limit);
  }

  @Override
  public CompletableFuture<long[]> getBlocksWithLimit(final Commitment commitment,
                                                      final long startSlot,
                                                      final long limit) {
    return sendPostRequest(LONG_ARRAY, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getBlocksWithLimit","params":[%d,%d,{"commitment":"%s"}]}""",
            id.incrementAndGet(), startSlot, Math.min(limit, 500_000), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<Instant> getBlockTime(final long slot) {
    return sendPostRequest(INSTANT, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getBlockTime","params":[%d]}""", id.incrementAndGet(), slot
        )
    );
  }

  @Override
  public CompletableFuture<List<ClusterNode>> getClusterNodes() {
    return sendPostRequest(CLUSTER_NODES, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getClusterNodes"}""", id.incrementAndGet()
        )
    );
  }

  @Override
  public CompletableFuture<EpochInfo> getEpochInfo() {
    return getEpochInfo(defaultCommitment);
  }

  @Override
  public CompletableFuture<EpochInfo> getEpochInfo(final Commitment commitment) {
    return sendPostRequest(EPOCH_INFO, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getEpochInfo","params":[{"commitment":"%s"}]}""",
            id.incrementAndGet(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<EpochSchedule> getEpochSchedule() {
    return sendPostRequest(EPOCH_SCHEDULE, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getEpochSchedule"}""", id.incrementAndGet()
        )
    );
  }

  @Override
  public CompletableFuture<FeeForMessage> getFeeForMessage(final String base64Msg) {
    return getFeeForMessage(defaultCommitment, base64Msg);
  }

  @Override
  public CompletableFuture<FeeForMessage> getFeeForMessage(final Commitment commitment, final String base64Msg) {
    return sendPostRequest(FEE_FOR_MESSAGE, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getFeeForMessage","params":["%s",{"commitment":"%s"}]}""",
            id.incrementAndGet(), base64Msg, commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<Long> getFirstAvailableBlock() {
    return sendPostRequest(LONG_VAL, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getFirstAvailableBlock"}""", id.incrementAndGet()
        )
    );
  }

  @Override
  public CompletableFuture<String> getGenesisHash() {
    return sendPostRequest(STRING, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getGenesisHash"}""", id.incrementAndGet()
        )
    );
  }

  @Override
  public CompletableFuture<HighestSnapshotSlot> getHighestSnapshotSlot() {
    return sendPostRequest(HIGHEST_SNAPSHOT_SLOT, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getHighestSnapshotSlot"}""", id.incrementAndGet()
        )
    );
  }

  @Override
  public CompletableFuture<Identity> getIdentity() {
    return sendPostRequest(IDENTITY, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getIdentity"}""", id.incrementAndGet()
        )
    );
  }

  @Override
  public CompletableFuture<InflationGovernor> getInflationGovernor() {
    return getInflationGovernor(defaultCommitment);
  }

  @Override
  public CompletableFuture<InflationGovernor> getInflationGovernor(final Commitment commitment) {
    return sendPostRequest(INFLATION_GOVERNOR, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getInflationGovernor","params":[{"commitment":"%s"}]}""",
            id.incrementAndGet(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<InflationRate> getInflationRate() {
    return sendPostRequest(INFLATION_RATE, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getInflationRate"}""", id.incrementAndGet()
        )
    );
  }

  @Override
  public CompletableFuture<List<InflationReward>> getInflationReward(final SequencedCollection<PublicKey> keys) {
    return getInflationReward(defaultCommitment, keys);
  }

  private String joinKeys(final SequencedCollection<PublicKey> keys) {
    return keys == null || keys.isEmpty() ? "[]" : keys.stream()
        .map(PublicKey::toBase58)
        .collect(Collectors.joining("\",\"", "[\"", "\"]"));
  }

  @Override
  public CompletableFuture<List<InflationReward>> getInflationReward(final Commitment commitment,
                                                                     final SequencedCollection<PublicKey> keys) {
    return sendPostRequest(INFLATION_REWARDS, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getInflationReward","params":[%s,{"commitment":"%s"}]}""",
            id.incrementAndGet(), joinKeys(keys), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<List<InflationReward>> getInflationReward(final SequencedCollection<PublicKey> keys,
                                                                     final long epoch) {
    return getInflationReward(defaultCommitment, keys, epoch);
  }

  @Override
  public CompletableFuture<List<InflationReward>> getInflationReward(final Commitment commitment,
                                                                     final SequencedCollection<PublicKey> keys,
                                                                     final long epoch) {
    return sendPostRequest(INFLATION_REWARDS, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getInflationReward","params":[%s,{"commitment":"%s","epoch":%d}]}""",
            id.incrementAndGet(), joinKeys(keys), commitment.getValue(), epoch
        )
    );
  }

  @Override
  public CompletableFuture<List<AccountLamports>> getLargestAccounts() {
    return getLargestAccounts(defaultCommitment);
  }

  @Override
  public CompletableFuture<List<AccountLamports>> getLargestAccounts(final Commitment commitment) {
    return sendPostRequest(TOP_LAMPORT_ACCOUNTS, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getLargestAccounts","params":[{"commitment":"%s"}]}""",
            id.incrementAndGet(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<LatestBlockHash> getLatestBlockHash() {
    return getLatestBlockHash(defaultCommitment);
  }

  @Override
  public CompletableFuture<LatestBlockHash> getLatestBlockHash(final Commitment commitment) {
    return sendPostRequestNoWrap(latestBlockhashResponseParser, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getLatestBlockhash","params":[{"commitment":"%s"}]}""",
            id.incrementAndGet(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule() {
    return getLeaderSchedule(defaultCommitment);
  }

  @Override
  public CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final Commitment commitment) {
    return sendPostRequest(LEADER_SCHEDULE, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getLeaderSchedule","params":[null,{"commitment":"%s"}]}""",
            id.incrementAndGet(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final long slot) {
    return getLeaderSchedule(defaultCommitment, slot);
  }

  @Override
  public CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final Commitment commitment, final long slot) {
    return sendPostRequest(LEADER_SCHEDULE, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getLeaderSchedule","params":[%d,{"commitment":"%s"}]}""",
            id.incrementAndGet(), slot, commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final long slot, final PublicKey identity) {
    return getLeaderSchedule(defaultCommitment, slot, identity);
  }

  @Override
  public CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final Commitment commitment,
                                                                     final long slot,
                                                                     final PublicKey identity) {
    return sendPostRequest(LEADER_SCHEDULE, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getLeaderSchedule","params":[%d,{"commitment":"%s","identity":"%s"}]}""",
            id.incrementAndGet(), slot, commitment.getValue(), identity
        )
    );
  }

  @Override
  public CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final PublicKey identity) {
    return getLeaderSchedule(defaultCommitment, identity);
  }

  @Override
  public CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final Commitment commitment,
                                                                     final PublicKey identity) {
    return sendPostRequest(LEADER_SCHEDULE, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getLeaderSchedule","params":[null,{"commitment":"%s","identity":"%s"}]}""",
            id.incrementAndGet(), commitment.getValue(), identity
        )
    );
  }

  @Override
  public CompletableFuture<Long> getMaxRetransmitSlot() {
    return sendPostRequest(LONG_VAL, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getMaxRetransmitSlot"}""", id.incrementAndGet()
        )
    );
  }

  @Override
  public CompletableFuture<Long> getMaxShredInsertSlot() {
    return sendPostRequest(LONG_VAL, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getMaxShredInsertSlot"}""", id.incrementAndGet()
        )
    );
  }

  @Override
  public CompletableFuture<Long> getMinimumBalanceForRentExemption(final long accountLength) {
    return sendPostRequest(LONG_VAL, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getMinimumBalanceForRentExemption","params":[%d]}""",
            id.incrementAndGet(), accountLength
        )
    );
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final SequencedCollection<PublicKey> keys,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getMultipleAccounts(defaultCommitment, keys, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final Commitment commitment,
                                                                         final SequencedCollection<PublicKey> keys,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getMultipleAccounts(commitment, null, 0, 0, keys, factory);
  }

  @Override
  public CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final int length,
                                                                          final int offset,
                                                                          final SequencedCollection<PublicKey> keys) {
    return getMultipleAccounts(defaultCommitment, length, offset, keys, BYTES_IDENTITY);
  }

  @Override
  public CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final BigInteger minContextSlot,
                                                                          final SequencedCollection<PublicKey> keys) {
    return getMultipleAccounts(defaultCommitment, minContextSlot, keys, BYTES_IDENTITY);
  }

  @Override
  public CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final BigInteger minContextSlot,
                                                                          final int length,
                                                                          final int offset,
                                                                          final SequencedCollection<PublicKey> keys) {
    return getMultipleAccounts(defaultCommitment, minContextSlot, length, offset, keys, BYTES_IDENTITY);
  }

  @Override
  public CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final Commitment commitment,
                                                                          final BigInteger minContextSlot,
                                                                          final SequencedCollection<PublicKey> keys) {
    return getMultipleAccounts(commitment, minContextSlot, keys, BYTES_IDENTITY);
  }

  @Override
  public CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final Commitment commitment,
                                                                          final int length,
                                                                          final int offset,
                                                                          final SequencedCollection<PublicKey> keys) {
    return getMultipleAccounts(commitment, length, offset, keys, BYTES_IDENTITY);
  }

  @Override
  public CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final Commitment commitment,
                                                                          final BigInteger minContextSlot,
                                                                          final int length,
                                                                          final int offset,
                                                                          final SequencedCollection<PublicKey> keys) {
    return getMultipleAccounts(commitment, minContextSlot, length, offset, keys, BYTES_IDENTITY);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final int length,
                                                                         final int offset,
                                                                         final SequencedCollection<PublicKey> keys,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getMultipleAccounts(defaultCommitment, length, offset, keys, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final BigInteger minContextSlot,
                                                                         final SequencedCollection<PublicKey> keys,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getMultipleAccounts(defaultCommitment, minContextSlot, keys, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final BigInteger minContextSlot,
                                                                         final int length,
                                                                         final int offset,
                                                                         final SequencedCollection<PublicKey> keys,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getMultipleAccounts(defaultCommitment, minContextSlot, length, offset, keys, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final Commitment commitment,
                                                                         final int length,
                                                                         final int offset,
                                                                         final SequencedCollection<PublicKey> keys,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getMultipleAccounts(commitment, null, length, offset, keys, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final Commitment commitment,
                                                                         final BigInteger minContextSlot,
                                                                         final SequencedCollection<PublicKey> keys,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getMultipleAccounts(commitment, minContextSlot, 0, 0, keys, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final Commitment commitment,
                                                                         final BigInteger minContextSlot,
                                                                         final int length,
                                                                         final int offset,
                                                                         final SequencedCollection<PublicKey> keys,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getAppliedAccounts(
        commitment, minContextSlot, length, offset, keys,
        (ji, context) -> AccountInfo.parseAccountsFromKeys(keys, ji, context, factory)
    );
  }

  private <R> CompletableFuture<R> getAppliedAccounts(final Commitment commitment,
                                                      final BigInteger minContextSlot,
                                                      final int length,
                                                      final int offset,
                                                      final SequencedCollection<PublicKey> keys,
                                                      final BiFunction<JsonIterator, Context, R> adapter) {
    if (keys.isEmpty()) {
      throw new IllegalArgumentException("keys must not be empty");
    }

    final var builder = new StringBuilder(512);
    builder.append("""
        {"jsonrpc":"2.0","id":""");
    builder.append(id.incrementAndGet());

    builder.append("""
        ,"method":"getMultipleAccounts","params":[[""");
    final var iterator = keys.iterator();
    for (PublicKey key; ; ) {
      key = iterator.next();
      builder.append('"');
      builder.append(key.toBase58());
      builder.append('"');
      if (iterator.hasNext()) {
        builder.append(',');
      } else {
        break;
      }
    }

    builder.append("""
        ],{"encoding":"base64","commitment":\"""");
    builder.append(commitment.getValue());
    builder.append('"');

    if (minContextSlot != null) {
      builder.append("""
          ,"minContextSlot":""");
      builder.append(minContextSlot);
    }

    if (length != 0) {
      builder.append("""
          ,"dataSlice":{"length":""");
      builder.append(length);
      builder.append("""
          ,"offset":""");
      builder.append(offset);
      builder.append('}');
    }

    builder.append("}]}");

    return sendPostRequest(applyResponseValue(adapter), builder.toString());
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final SequencedCollection<PublicKey> keys,
                                                                 final BiFunction<PublicKey, byte[], T> factory) {
    return getAccounts(defaultCommitment, keys, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final Commitment commitment,
                                                                 final SequencedCollection<PublicKey> keys,
                                                                 final BiFunction<PublicKey, byte[], T> factory) {
    return getAccounts(commitment, null, 0, 0, keys, factory);
  }

  @Override
  public CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final int length,
                                                                  final int offset,
                                                                  final SequencedCollection<PublicKey> keys) {
    return getAccounts(defaultCommitment, length, offset, keys, BYTES_IDENTITY);
  }

  @Override
  public CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final BigInteger minContextSlot,
                                                                  final SequencedCollection<PublicKey> keys) {
    return getAccounts(defaultCommitment, minContextSlot, keys, BYTES_IDENTITY);
  }

  @Override
  public CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final BigInteger minContextSlot,
                                                                  final int length,
                                                                  final int offset,
                                                                  final SequencedCollection<PublicKey> keys) {
    return getAccounts(defaultCommitment, minContextSlot, length, offset, keys, BYTES_IDENTITY);
  }

  @Override
  public CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final Commitment commitment,
                                                                  final BigInteger minContextSlot,
                                                                  final SequencedCollection<PublicKey> keys) {
    return getAccounts(commitment, minContextSlot, keys, BYTES_IDENTITY);
  }

  @Override
  public CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final Commitment commitment,
                                                                  final int length,
                                                                  final int offset,
                                                                  final SequencedCollection<PublicKey> keys) {
    return getAccounts(commitment, length, offset, keys, BYTES_IDENTITY);
  }

  @Override
  public CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final Commitment commitment,
                                                                  final BigInteger minContextSlot,
                                                                  final int length,
                                                                  final int offset,
                                                                  final SequencedCollection<PublicKey> keys) {
    return getAccounts(commitment, minContextSlot, length, offset, keys, BYTES_IDENTITY);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final int length,
                                                                 final int offset,
                                                                 final SequencedCollection<PublicKey> keys,
                                                                 final BiFunction<PublicKey, byte[], T> factory) {
    return getAccounts(defaultCommitment, length, offset, keys, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final BigInteger minContextSlot,
                                                                 final SequencedCollection<PublicKey> keys,
                                                                 final BiFunction<PublicKey, byte[], T> factory) {
    return getAccounts(defaultCommitment, minContextSlot, keys, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final BigInteger minContextSlot,
                                                                 final int length,
                                                                 final int offset,
                                                                 final SequencedCollection<PublicKey> keys,
                                                                 final BiFunction<PublicKey, byte[], T> factory) {
    return getAccounts(defaultCommitment, minContextSlot, length, offset, keys, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final Commitment commitment,
                                                                 final int length,
                                                                 final int offset,
                                                                 final SequencedCollection<PublicKey> keys,
                                                                 final BiFunction<PublicKey, byte[], T> factory) {
    return getAccounts(commitment, null, length, offset, keys, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final Commitment commitment,
                                                                 final BigInteger minContextSlot,
                                                                 final SequencedCollection<PublicKey> keys,
                                                                 final BiFunction<PublicKey, byte[], T> factory) {
    return getAccounts(commitment, minContextSlot, 0, 0, keys, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final Commitment commitment,
                                                                 final BigInteger minContextSlot,
                                                                 final int length,
                                                                 final int offset,
                                                                 final SequencedCollection<PublicKey> keys,
                                                                 final BiFunction<PublicKey, byte[], T> factory) {
    return getAppliedAccounts(
        commitment, minContextSlot, length, offset, keys,
        (ji, context) -> AccountInfo.parseAccountsFromKeysWithNulls(keys, ji, context, factory)
    );
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                        final PublicKey programId,
                                                                        final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(requestTimeout, programId, null, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                        final PublicKey programId,
                                                                        final Collection<Filter> filters,
                                                                        final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(requestTimeout, programId, defaultCommitment, filters, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                        final PublicKey programId,
                                                                        final Commitment commitment,
                                                                        final Collection<Filter> filters,
                                                                        final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(requestTimeout, programId, commitment, 0, filters, 0, 0, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                        final PublicKey programId,
                                                                        final Commitment commitment,
                                                                        final long minContextSlot,
                                                                        final Collection<Filter> filters,
                                                                        final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(requestTimeout, programId, commitment, minContextSlot, filters, 0, 0, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                        final PublicKey programId,
                                                                        final Commitment commitment,
                                                                        final Collection<Filter> filters,
                                                                        final int length,
                                                                        final int offset,
                                                                        final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(requestTimeout, programId, commitment, null, filters, length, offset, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                        final PublicKey programId,
                                                                        final Commitment commitment,
                                                                        final long minContextSlot,
                                                                        final Collection<Filter> filters,
                                                                        final int length,
                                                                        final int offset,
                                                                        final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(
        requestTimeout,
        programId,
        commitment,
        minContextSlot == 0 ? null : new BigInteger(Long.toUnsignedString(minContextSlot)),
        filters,
        length,
        offset,
        factory
    );
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                        final PublicKey programId,
                                                                        final Commitment commitment,
                                                                        final BigInteger minContextSlot,
                                                                        final Collection<Filter> filters,
                                                                        final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(requestTimeout, programId, commitment, minContextSlot, filters, 0, 0, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                        final PublicKey programId,
                                                                        final Commitment commitment,
                                                                        final BigInteger minContextSlot,
                                                                        final Collection<Filter> filters,
                                                                        final int length,
                                                                        final int offset,
                                                                        final BiFunction<PublicKey, byte[], T> factory) {
    final int numFilters = filters == null ? 0 : filters.size();
    final var builder = new StringBuilder(256 + (numFilters * MAX_MEM_COMP_LENGTH));

    builder.append("""
        {"jsonrpc":"2.0","id":""");
    builder.append(id.incrementAndGet());

    builder.append("""
        ,"method":"getProgramAccounts","params":[\"""");
    builder.append(programId.toBase58());

    builder.append("""
        ",{"withContext":true,"encoding":"base64","commitment":\"""");
    builder.append(commitment.getValue());
    builder.append('"');

    if (minContextSlot != null) {
      builder.append("""
          ,"minContextSlot":""");
      builder.append(minContextSlot);
    }

    if (length != 0) {
      builder.append("""
          ,"dataSlice":{"length":""");
      builder.append(length);
      builder.append("""
          ,"offset":""");
      builder.append(offset);
      builder.append('}');
    }

    if (numFilters == 0) {
      builder.append("}]}");
    } else {
      builder.append("""
          ,"filters":[""");
      final var iterator = filters.iterator();
      for (Filter filter; ; ) {
        filter = iterator.next();
        builder.append(filter.toJson());
        if (iterator.hasNext()) {
          builder.append(',');
        } else {
          break;
        }
      }
      builder.append("]}]}");
    }

    final var body = builder.toString();
    final var request = newRequest("POST", ofString(body))
        .timeout(requireNonNullElse(requestTimeout, this.requestTimeout))
        .build();
    return httpClient
        .sendAsync(request, ofByteArray())
        .thenApply(wrapParser(applyResponseValue((ji, context) -> AccountInfo.parseAccounts(ji, context, factory))));
  }

  @Override
  public CompletableFuture<List<PerfSample>> getRecentPerformanceSamples(final int limit) {
    return sendPostRequest(PERF_SAMPLE, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getRecentPerformanceSamples","params":[%d]}""", id.incrementAndGet(), Math.min(limit, 720)
        )
    );
  }

  private CompletableFuture<List<PrioritizationFee>> recentPrioritizationFees(final Collection<PublicKey> writablePublicKeys) {
    final var params = writablePublicKeys == null || writablePublicKeys.isEmpty() ? "" : writablePublicKeys.stream()
        .map(PublicKey::toBase58)
        .collect(Collectors.joining("\",\"", "[\"", "\"]"));
    final var body = format("""
        {"jsonrpc":"2.0","id":%d,"method":"getRecentPrioritizationFees","params":[%s]}""", id.incrementAndGet(), params
    );
    return sendPostRequest(PRIORITIZATION_FEE, body);
  }

  @Override
  public CompletableFuture<List<PrioritizationFee>> getRecentPrioritizationFees(final SequencedCollection<PublicKey> writablePublicKeys) {
    return recentPrioritizationFees(writablePublicKeys);
  }

  @Override
  public CompletableFuture<List<TxSig>> getSignaturesForAddress(final PublicKey address, final int limit) {
    return getSignaturesForAddress(defaultCommitment, address, limit);
  }

  @Override
  public CompletableFuture<List<TxSig>> getSignaturesForAddress(final Commitment commitment,
                                                                final PublicKey address,
                                                                final int limit) {
    return sendPostRequest(TX_SIGNATURES, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getSignaturesForAddress","params":["%s",{"commitment":"%s","limit":%d}]}""",
            id.incrementAndGet(), address, commitment.getValue(), Math.min(limit, 1_000)
        )
    );
  }

  @Override
  public CompletableFuture<List<TxSig>> getSignaturesForAddressBefore(final PublicKey address,
                                                                      final int limit,
                                                                      final String beforeTxSig) {
    return getSignaturesForAddressBefore(defaultCommitment, address, limit, beforeTxSig);
  }

  @Override
  public CompletableFuture<List<TxSig>> getSignaturesForAddressBefore(final Commitment commitment,
                                                                      final PublicKey address,
                                                                      final int limit,
                                                                      final String beforeTxSig) {
    return sendPostRequest(TX_SIGNATURES, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getSignaturesForAddress","params":["%s",{"commitment":"%s","limit":%d,"before":"%s"}]}""",
            id.incrementAndGet(), address.toBase58(), commitment.getValue(), Math.min(limit, 1_000), beforeTxSig
        )
    );
  }

  @Override
  public CompletableFuture<List<TxSig>> getSignaturesForAddressUntil(final PublicKey address,
                                                                     final int limit,
                                                                     final String untilTxSig) {
    return getSignaturesForAddressUntil(defaultCommitment, address, limit, untilTxSig);
  }

  @Override
  public CompletableFuture<List<TxSig>> getSignaturesForAddressUntil(final Commitment commitment,
                                                                     final PublicKey address,
                                                                     final int limit,
                                                                     final String untilTxSig) {
    return sendPostRequest(TX_SIGNATURES, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getSignaturesForAddress","params":["%s",{"commitment":"%s","limit":%d,"until":"%s"}]}""",
            id.incrementAndGet(), address.toBase58(), commitment.getValue(), Math.min(limit, 1_000), untilTxSig
        )
    );
  }

  private String sigStatusBody(final SequencedCollection<String> signatures, final boolean searchTransactionHistory) {
    final var joined = String.join("\",\"", signatures);
    return format("""
            {"jsonrpc":"2.0","id":%d,"method":"getSignatureStatuses","params":[["%s"],{"searchTransactionHistory":%b}]}""",
        id.incrementAndGet(), joined, searchTransactionHistory
    );
  }

  @Override
  public CompletableFuture<Map<String, TxStatus>> getSignatureStatuses(final SequencedCollection<String> signatures,
                                                                       final boolean searchTransactionHistory) {
    return sendPostRequest(
        applyResponseValue((ji, context) -> TxStatus.parse(signatures, ji, context)),
        sigStatusBody(signatures, searchTransactionHistory)
    );
  }

  @Override
  public CompletableFuture<List<TxStatus>> getSigStatusList(final SequencedCollection<String> signatures,
                                                            final boolean searchTransactionHistory) {
    return sendPostRequest(SIG_STATUS_LIST, sigStatusBody(signatures, searchTransactionHistory));
  }

  @Override
  public CompletableFuture<Long> getSlot() {
    return getSlot(defaultCommitment);
  }

  @Override
  public CompletableFuture<Long> getSlot(final Commitment commitment) {
    return sendPostRequest(LONG_VAL, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getSlot","params":[{"commitment":"%s"}]}""",
            id.incrementAndGet(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<PublicKey> getSlotLeader() {
    return getSlotLeader(defaultCommitment);
  }

  @Override
  public CompletableFuture<PublicKey> getSlotLeader(final Commitment commitment) {
    return sendPostRequest(PUBLIC_KEY, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getSlotLeader","params":[{"commitment":"%s"}]}""",
            id.incrementAndGet(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<List<PublicKey>> getSlotLeaders(final long from, final int limit) {
    return sendPostRequest(PUBLIC_KEY_LIST, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getSlotLeaders","params":[%d,%d]}""",
            id.incrementAndGet(), from, Math.min(limit, 5_000)
        )
    );
  }

  @Override
  public CompletableFuture<Lamports> getStakeMinimumDelegation() {
    return getStakeMinimumDelegation(defaultCommitment);
  }

  @Override
  public CompletableFuture<Lamports> getStakeMinimumDelegation(final Commitment commitment) {
    return sendPostRequest(CONTEXT_LONG_VAL, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getStakeMinimumDelegation","params":[{"commitment":"%s"}]}""",
            id.incrementAndGet(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<Supply> getSupply() {
    return getSupply(defaultCommitment, false);
  }

  @Override
  public CompletableFuture<Supply> getSupply(final Commitment commitment,
                                             final boolean excludeNonCirculatingAccountsList) {
    return sendPostRequest(SUPPLY, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getSupply","params":[{"commitment":"%s","excludeNonCirculatingAccountsList":%b}]}""",
            id.incrementAndGet(), commitment.getValue(), excludeNonCirculatingAccountsList
        )
    );
  }

  @Override
  public CompletableFuture<TokenAmount> getTokenAccountBalance(final PublicKey tokenAccount) {
    return getTokenAccountBalance(defaultCommitment, tokenAccount);
  }

  @Override
  public CompletableFuture<TokenAmount> getTokenAccountBalance(final Commitment commitment,
                                                               final PublicKey tokenAccount) {
    return sendPostRequest(TOKEN_AMOUNT, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getTokenAccountBalance","params":["%s",{"commitment":"%s"}]}""",
            id.incrementAndGet(), tokenAccount.toBase58(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForTokenMintByDelegate(final PublicKey delegate,
                                                                                                   final PublicKey tokenMint) {
    return getTokenAccountsForTokenMintByDelegate(defaultCommitment, delegate, tokenMint);
  }

  @Override
  public CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForTokenMintByDelegate(final Commitment commitment,
                                                                                                   final PublicKey delegate,
                                                                                                   final PublicKey tokenMint) {
    return sendPostRequest(TOKEN_ACCOUNTS_PARSER, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getTokenAccountsByDelegate","params":["%s",{"mint":"%s"},{"commitment":"%s","encoding":"base64"}]}""",
            id.incrementAndGet(), delegate.toBase58(), tokenMint.toBase58(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForProgramByDelegate(final PublicKey delegate,
                                                                                                 final PublicKey programId) {
    return getTokenAccountsForProgramByDelegate(defaultCommitment, delegate, programId);
  }

  @Override
  public CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForProgramByDelegate(final Commitment commitment,
                                                                                                 final PublicKey delegate,
                                                                                                 final PublicKey programId) {
    return sendPostRequest(TOKEN_ACCOUNTS_PARSER, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getTokenAccountsByDelegate","params":["%s",{"programId":"%s"},{"commitment":"%s","encoding":"base64"}]}""",
            id.incrementAndGet(), delegate.toBase58(), programId.toBase58(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForTokenMintByOwner(final PublicKey owner,
                                                                                                final PublicKey tokenMint) {
    return getTokenAccountsForTokenMintByOwner(defaultCommitment, owner, tokenMint);
  }

  @Override
  public CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForTokenMintByOwner(final Commitment commitment,
                                                                                                final PublicKey owner,
                                                                                                final PublicKey tokenMint) {
    return sendPostRequest(TOKEN_ACCOUNTS_PARSER, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getTokenAccountsByOwner","params":["%s",{"mint":"%s"},{"commitment":"%s","encoding":"base64"}]}""",
            id.incrementAndGet(), owner.toBase58(), tokenMint.toBase58(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForProgramByOwner(final PublicKey owner,
                                                                                              final PublicKey programId) {
    return getTokenAccountsForProgramByOwner(defaultCommitment, owner, programId);
  }

  @Override
  public CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForProgramByOwner(final Commitment commitment,
                                                                                              final PublicKey owner,
                                                                                              final PublicKey programId) {
    return sendPostRequest(TOKEN_ACCOUNTS_PARSER, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getTokenAccountsByOwner","params":["%s",{"programId":"%s"},{"commitment":"%s","encoding":"base64"}]}""",
            id.incrementAndGet(), owner.toBase58(), programId.toBase58(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<List<AccountTokenAmount>> getTokenLargestAccounts(final PublicKey tokenMint) {
    return getTokenLargestAccounts(defaultCommitment, tokenMint);
  }

  @Override
  public CompletableFuture<List<AccountTokenAmount>> getTokenLargestAccounts(final Commitment commitment,
                                                                             final PublicKey tokenMint) {
    return sendPostRequest(ACCOUNT_TOKEN_AMOUNT, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getTokenLargestAccounts","params":["%s",{"commitment":"%s"}]}""",
            id.incrementAndGet(), tokenMint.toBase58(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<TokenAmount> getTokenSupply(final PublicKey tokenMintAccount) {
    return getTokenSupply(defaultCommitment, tokenMintAccount);
  }

  @Override
  public CompletableFuture<TokenAmount> getTokenSupply(final Commitment commitment, final PublicKey tokenMintAccount) {
    return sendPostRequest(TOKEN_AMOUNT, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getTokenSupply","params":["%s",{"commitment":"%s"}]}""",
            id.incrementAndGet(), tokenMintAccount.toBase58(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<Tx> getTransaction(final String txSignature) {
    return getTransaction(defaultCommitment, txSignature);
  }

  @Override
  public CompletableFuture<Tx> getTransaction(final Commitment commitment,
                                              final int maxSupportedTransactionVersion,
                                              final String txSignature) {
    final var maxVersionParam = maxSupportedTransactionVersion < 0
        ? ""
        : String.format("\"maxSupportedTransactionVersion\":%d,", maxSupportedTransactionVersion);
    return sendPostRequest(TRANSACTION, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getTransaction","params":["%s",{"commitment":"%s",%s"encoding":"base64"}]}""",
            id.incrementAndGet(), txSignature, commitment.getValue(), maxVersionParam
        )
    );
  }

  @Override
  public CompletableFuture<Long> getTransactionCount() {
    return getTransactionCount(defaultCommitment);
  }

  @Override
  public CompletableFuture<Long> getTransactionCount(final Commitment commitment) {
    return sendPostRequest(LONG_VAL, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getTransactionCount","params":[{"commitment":"%s"}]}""",
            id.incrementAndGet(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<Version> getVersion() {
    return sendPostRequest(VERSION, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getVersion"}""", id.incrementAndGet()
        )
    );
  }

  @Override
  public CompletableFuture<VoteAccounts> getVoteAccounts() {
    return getVoteAccounts(defaultCommitment);
  }

  @Override
  public CompletableFuture<VoteAccounts> getVoteAccounts(final Commitment commitment) {
    return sendPostRequest(VOTE_ACCOUNTS, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getVoteAccounts","params":[{"commitment":"%s"}]}""",
            id.incrementAndGet(), commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<VoteAccounts> getVoteAccounts(final PublicKey validatorVoteAddress) {
    return getVoteAccounts(defaultCommitment, validatorVoteAddress);
  }

  @Override
  public CompletableFuture<VoteAccounts> getVoteAccounts(final Commitment commitment,
                                                         final PublicKey validatorVoteAddress) {
    return sendPostRequest(VOTE_ACCOUNTS, format("""
                {"jsonrpc":"2.0","id":%d,"method":"getVoteAccounts","params":[{"commitment":"%s","votePubkey":"%s"}]}""",
            id.incrementAndGet(), commitment.getValue(), validatorVoteAddress.toBase58()
        )
    );
  }

  @Override
  public CompletableFuture<ContextBoolVal> isBlockHashValid(final String b58BlockHash) {
    return isBlockHashValid(defaultCommitment, b58BlockHash);
  }

  @Override
  public CompletableFuture<ContextBoolVal> isBlockHashValid(final Commitment commitment, final String b58BlockHash) {
    return sendPostRequest(CONTEXT_BOOL_VAL, format("""
                {"jsonrpc":"2.0","id":%d,"method":"isBlockhashValid","params":["%s",{"commitment":"%s"}]}""",
            id.incrementAndGet(), b58BlockHash, commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<Long> minimumLedgerSlot() {
    return sendPostRequest(LONG_VAL, format("""
            {"jsonrpc":"2.0","id":%d,"method":"minimumLedgerSlot"}""", id.incrementAndGet()
        )
    );
  }

  @Override
  public CompletableFuture<String> requestAirdrop(final PublicKey key, final long lamports) {
    return requestAirdrop(defaultCommitment, key, lamports);
  }

  @Override
  public CompletableFuture<String> requestAirdrop(final Commitment commitment,
                                                  final PublicKey key,
                                                  final long lamports) {
    return sendPostRequest(STRING, format("""
                {"jsonrpc":"2.0","id":%d,"method":"requestAirdrop","params":["%s",%d,{"commitment":"%s"}]}""",
            id.incrementAndGet(), key.toBase58(), lamports, commitment.getValue()
        )
    );
  }

  @Override
  public CompletableFuture<String> sendTransaction(final Transaction transaction,
                                                   final Signer signer,
                                                   final byte[] recentBlockHash) {
    return sendTransaction(defaultCommitment, transaction, signer, recentBlockHash);
  }

  @Override
  public CompletableFuture<String> sendTransaction(final Commitment preflightCommitment,
                                                   final Transaction transaction,
                                                   final Signer signer,
                                                   final byte[] recentBlockHash) {
    final var base64SignedTx = transaction.signAndBase64Encode(recentBlockHash, signer);
    return sendTransaction(preflightCommitment, base64SignedTx);
  }

  @Override
  public CompletableFuture<String> sendTransaction(final Transaction transaction,
                                                   final SequencedCollection<Signer> signers,
                                                   final byte[] recentBlockHash) {
    return sendTransaction(defaultCommitment, transaction, signers, recentBlockHash);
  }

  @Override
  public CompletableFuture<String> sendTransaction(final Commitment preflightCommitment,
                                                   final Transaction transaction,
                                                   final SequencedCollection<Signer> signers,
                                                   final byte[] recentBlockHash) {
    final var base64SignedTx = transaction.signAndBase64Encode(recentBlockHash, signers);
    return sendTransaction(preflightCommitment, base64SignedTx);
  }

  @Override
  public CompletableFuture<String> sendTransaction(final String base64SignedTx, final int maxRetries) {
    return sendTransaction(this.defaultCommitment, base64SignedTx, maxRetries);
  }

  @Override
  public CompletableFuture<String> sendTransaction(final Commitment preflightCommitment,
                                                   final String base64SignedTx,
                                                   final int maxRetries) {
    return sendPostRequestNoWrap(sendTxResponseParser, format("""
                {"jsonrpc":"2.0","id":%d,"method":"sendTransaction","params":["%s",{"encoding":"base64","preflightCommitment":"%s","maxRetries":%d}]}""",
            id.incrementAndGet(), base64SignedTx, preflightCommitment.getValue(), maxRetries
        )
    );
  }

  @Override
  public CompletableFuture<String> sendTransactionSkipPreflight(final Commitment preflightCommitment,
                                                                final String base64SignedTx,
                                                                final int maxRetries) {
    return sendPostRequestNoWrap(sendTxResponseParser, format("""
                {"jsonrpc":"2.0","id":%d,"method":"sendTransaction","params":["%s",{"encoding":"base64","skipPreflight":true,"preflightCommitment":"%s","maxRetries":%d}]}""",
            id.incrementAndGet(), base64SignedTx, preflightCommitment.getValue(), maxRetries
        )
    );
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Transaction transaction,
                                                             final PublicKey signer,
                                                             final SequencedCollection<PublicKey> accounts) {
    return simulateTransaction(defaultCommitment, transaction, signer, accounts);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                             final Transaction transaction,
                                                             final PublicKey signer,
                                                             final SequencedCollection<PublicKey> accounts) {
    final var base64TxData = transaction.base64EncodeToString();
    return simulateTransaction(commitment, base64TxData, signer, accounts);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final String base64EncodedTx,
                                                             final PublicKey signer,
                                                             final SequencedCollection<PublicKey> accounts) {
    return simulateTransaction(defaultCommitment, base64EncodedTx, signer, accounts);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                             final String base64EncodedTx,
                                                             final PublicKey signer,
                                                             final SequencedCollection<PublicKey> accounts) {
    return simulateTransaction(commitment, base64EncodedTx, List.of(signer), accounts);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Transaction transaction,
                                                             final SequencedCollection<PublicKey> signers,
                                                             final SequencedCollection<PublicKey> accounts) {
    return simulateTransaction(defaultCommitment, transaction, signers, accounts);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                             final Transaction transaction,
                                                             final SequencedCollection<PublicKey> signers,
                                                             final SequencedCollection<PublicKey> accounts) {
    final var base64TxData = transaction.base64EncodeToString();
    return simulateTransaction(commitment, base64TxData, signers, accounts);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final String base64EncodedTx,
                                                             final SequencedCollection<PublicKey> signers,
                                                             final SequencedCollection<PublicKey> accounts) {
    return simulateTransaction(defaultCommitment, base64EncodedTx, signers, accounts);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                             final String base64EncodedTx,
                                                             final SequencedCollection<PublicKey> signers,
                                                             final SequencedCollection<PublicKey> accounts) {
    final SequencedCollection<PublicKey> returnAccounts;
    if (accounts.isEmpty()) {
      if (signers.isEmpty()) {
        return simulateTransaction(commitment, base64EncodedTx, true);
      } else {
        returnAccounts = signers;
      }
    } else {
      returnAccounts = accounts;
    }
    final var joinedAccounts = returnAccounts.stream()
        .map(PublicKey::toBase58)
        .collect(Collectors.joining("\",\"", ",\"accounts\":{\"addresses\":[\"", "\"],\"encoding\":\"jsonParsed\"}"));
    return sendPostRequest(applyResponseValue((ji, context) -> TxSimulation.parse(returnAccounts, ji, context)), format("""
                {"jsonrpc":"2.0","id":%d,"method":"simulateTransaction","params":["%s",{"encoding":"base64","sigVerify":false,"replaceRecentBlockhash":true,"commitment":"%s"%s}]}""",
            id.incrementAndGet(), base64EncodedTx, commitment.getValue(), joinedAccounts
        )
    );
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Transaction transaction) {
    return simulateTransaction(defaultCommitment, transaction, true);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Transaction transaction,
                                                             final boolean replaceRecentBlockhash) {
    return simulateTransaction(defaultCommitment, transaction, replaceRecentBlockhash);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                             final Transaction transaction,
                                                             final boolean replaceRecentBlockhash) {
    final var base64TxData = transaction.base64EncodeToString();
    return simulateTransaction(commitment, base64TxData, replaceRecentBlockhash);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final String base64EncodedTx) {
    return simulateTransaction(defaultCommitment, base64EncodedTx, true);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final String base64EncodedTx,
                                                             final boolean replaceRecentBlockhash) {
    return simulateTransaction(defaultCommitment, base64EncodedTx, replaceRecentBlockhash);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                             final String base64EncodedTx,
                                                             final boolean replaceRecentBlockhash) {
    return simulateTransaction(commitment, base64EncodedTx, replaceRecentBlockhash, false);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransactionWithInnerInstructions(final Commitment commitment,
                                                                                  final Transaction transaction) {
    return simulateTransaction(commitment, transaction, true, true);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransactionWithInnerInstructions(final Transaction transaction) {
    return simulateTransactionWithInnerInstructions(defaultCommitment, transaction);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransactionWithInnerInstructions(final Commitment commitment,
                                                                                  final String base64EncodedTx) {
    return simulateTransaction(commitment, base64EncodedTx, true, true);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransactionWithInnerInstructions(final String base64EncodedTx) {
    return simulateTransactionWithInnerInstructions(defaultCommitment, base64EncodedTx);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Transaction transaction,
                                                             final boolean replaceRecentBlockhash,
                                                             final boolean innerInstructions) {
    return simulateTransaction(defaultCommitment, transaction, replaceRecentBlockhash, innerInstructions);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                             final Transaction transaction,
                                                             final boolean replaceRecentBlockhash,
                                                             final boolean innerInstructions) {
    final var base64TxData = transaction.base64EncodeToString();
    return simulateTransaction(commitment, base64TxData, replaceRecentBlockhash, innerInstructions);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final String base64EncodedTx,
                                                             final boolean replaceRecentBlockhash,
                                                             final boolean innerInstructions) {
    return simulateTransaction(defaultCommitment, base64EncodedTx, replaceRecentBlockhash, innerInstructions);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                             final String base64EncodedTx,
                                                             final boolean replaceRecentBlockhash,
                                                             final boolean innerInstructions) {
    return sendPostRequest(applyResponseValue((ji, context) -> TxSimulation.parse(List.of(), ji, context)), format("""
                {"jsonrpc":"2.0","id":%d,"method":"simulateTransaction","params":["%s",{"encoding":"base64","sigVerify":false,"replaceRecentBlockhash":%b,"innerInstructions":%b,"commitment":"%s"}]}""",
            id.incrementAndGet(), base64EncodedTx, replaceRecentBlockhash, innerInstructions, commitment.getValue()
        )
    );
  }

  private static void logSimulationResult(final TxSimulation simulationResult) {
    System.out.format("""
            
            Simulation Result:
              program: %s
              CU consumed: %d
              error: %s
              blockhash: %s
              inner instructions:
              %s
              logs:
              %s
            
            """,
        simulationResult.programId(),
        simulationResult.unitsConsumed().orElse(-1),
        simulationResult.error(),
        simulationResult.replacementBlockHash(),
        simulationResult.innerInstructions().stream().map(InnerInstructions::toString)
            .collect(Collectors.joining("\n    * ", "  * ", "")),
        simulationResult.logs().stream().collect(Collectors.joining("\n    * ", "  * ", ""))
    );
  }

  public static void main(String[] args) {
    final var rpcEndpoint = args.length > 0 ? URI.create(args[0]) : SolanaNetwork.MAIN_NET.getEndpoint();
    final var devNet = SolanaNetwork.DEV_NET.getEndpoint();
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var rpcClient = SolanaRpcClient.createClient(
//          devNet,
          rpcEndpoint,
          httpClient,
          response -> {
//            final var json = new String(response.body());
//            System.out.println(json);
//            try {
//              Files.write(Path.of("get_block.data.json"), response.body(), StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
//            } catch (final IOException e) {
//              throw new UncheckedIOException(e);
//            }
            return true;
          }
      );
    }
  }
}
