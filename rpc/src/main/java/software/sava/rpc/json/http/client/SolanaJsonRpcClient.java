package software.sava.rpc.json.http.client;


import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.core.rpc.Filter;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.rpc.json.PublicKeyEncoding;
import software.sava.rpc.json.http.SolanaNetwork;
import software.sava.rpc.json.http.request.BlockTxDetails;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.request.ContextBoolVal;
import software.sava.rpc.json.http.response.*;
import systems.comodal.jsoniter.JsonIterator;

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
import static software.sava.rpc.json.PublicKeyEncoding.parseBase58Encoded;

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

  private final AtomicLong id;
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
  public CompletableFuture<NodeHealth> getHealth() {
    return getHealth(this.requestTimeout);
  }

  @Override
  public CompletableFuture<NodeHealth> getHealth(final Duration requestTimeout) {
    return sendPostRequest(SolanaJsonRpcClient.NODE_HEALTH, requestTimeout, format("""
        {"jsonrpc":"2.0","id":%d,"method":"getHealth"}""", id.incrementAndGet()));
  }

  @Override
  public <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final PublicKey account, final BiFunction<PublicKey, byte[], T> factory) {
    return getAccountInfo(defaultCommitment, account, factory);
  }

  @Override
  public <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final Commitment commitment,
                                                              final PublicKey account,
                                                              final BiFunction<PublicKey, byte[], T> factory) {
    return sendPostRequest(applyResponseValue((ji, context) -> AccountInfo.parse(account, ji, context, factory)), format("""
            {"jsonrpc":"2.0","id":%d,"method":"getAccountInfo","params":["%s",{"commitment":"%s","encoding":"base64"}]}""",
        id.incrementAndGet(), account.toBase58(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<Lamports> getBalance(final PublicKey account) {
    return getBalance(defaultCommitment, account);
  }

  @Override
  public CompletableFuture<Lamports> getBalance(final Commitment commitment, final PublicKey account) {
    return sendPostRequest(CONTEXT_LONG_VAL, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getBalance","params":["%s",{"commitment":"%s"}]}""",
        id.incrementAndGet(), account, commitment.getValue()));
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
  public CompletableFuture<Block> getBlock(final Commitment commitment,
                                           final long slot,
                                           final BlockTxDetails blockTxDetails) {
    return sendPostRequest(BLOCK, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getBlock","params":[%d,{"commitment":"%s","transactionDetails":"%s","rewards":true}]}"""
        , id.incrementAndGet(), slot, commitment.getValue(), blockTxDetails));
  }

  @Override
  public CompletableFuture<BlockHeight> getBlockHeight() {
    return getBlockHeight(defaultCommitment);
  }

  @Override
  public CompletableFuture<BlockHeight> getBlockHeight(final Commitment commitment) {
    return sendPostRequest(BLOCK_HEIGHT, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getBlockHeight","params":[{"commitment":"%s"}]}""",
        id.incrementAndGet(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<BlockProduction> getBlockProduction() {
    return getBlockProduction(defaultCommitment);
  }

  @Override
  public CompletableFuture<BlockProduction> getBlockProduction(final Commitment commitment) {
    return sendPostRequest(BLOCK_PRODUCTION, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getBlockProduction","params":[{"commitment":"%s"}]}""",
        id.incrementAndGet(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<BlockProduction> getBlockProduction(final PublicKey identity) {
    return getBlockProduction(defaultCommitment, identity);
  }

  @Override
  public CompletableFuture<BlockProduction> getBlockProduction(final Commitment commitment, final PublicKey identity) {
    return sendPostRequest(BLOCK_PRODUCTION, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getBlockProduction","params":[{"commitment":"%s","identity":"%s"}]}""",
        id.incrementAndGet(), commitment.getValue(), identity));
  }

  @Override
  public CompletableFuture<BlockProduction> getBlockProduction(final long firstSlot) {
    return getBlockProduction(defaultCommitment, firstSlot);
  }

  @Override
  public CompletableFuture<BlockProduction> getBlockProduction(final Commitment commitment, final long firstSlot) {
    return sendPostRequest(BLOCK_PRODUCTION, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getBlockProduction","params":[{"commitment":"%s","firstSlot":%d}]}""",
        id.incrementAndGet(), commitment.getValue(), firstSlot));
  }

  @Override
  public CompletableFuture<BlockProduction> getBlockProduction(final PublicKey identity, final long firstSlot) {
    return getBlockProduction(defaultCommitment, identity, firstSlot);
  }

  @Override
  public CompletableFuture<BlockProduction> getBlockProduction(final Commitment commitment, final PublicKey identity, final long firstSlot) {
    return sendPostRequest(BLOCK_PRODUCTION, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getBlockProduction","params":[{"commitment":"%s","identity":"%s","firstSlot":%d}]}""",
        id.incrementAndGet(), commitment.getValue(), identity, firstSlot));
  }

  @Override
  public CompletableFuture<BlockCommitment> getBlockCommitment(final long slot) {
    return sendPostRequest(BLOCK_COMMITMENT, format("""
        {"jsonrpc":"2.0","id":%d,"method":"getBlockCommitment","params":[%d]}""", id.incrementAndGet(), slot));
  }

  @Override
  public CompletableFuture<long[]> getBlocks(final long startSlot) {
    return getBlocks(defaultCommitment, startSlot);
  }

  @Override
  public CompletableFuture<long[]> getBlocks(final Commitment commitment, final long startSlot) {
    return sendPostRequest(LONG_ARRAY, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getBlocks","params":[%d,{"commitment":"%s"}]}""",
        id.incrementAndGet(), startSlot, commitment.getValue()));
  }

  @Override
  public CompletableFuture<long[]> getBlocks(final long startSlot, final long endSlot) {
    return getBlocks(defaultCommitment, startSlot, endSlot);
  }

  @Override
  public CompletableFuture<long[]> getBlocks(final Commitment commitment, final long startSlot, final long endSlot) {
    return sendPostRequest(LONG_ARRAY, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getBlocks","params":[%d,%d,{"commitment":"%s"}]}""",
        id.incrementAndGet(), startSlot, Math.min(endSlot, startSlot + 500_000), commitment.getValue()));
  }

  @Override
  public CompletableFuture<long[]> getBlocksWithLimit(final long startSlot, final long limit) {
    return getBlocksWithLimit(defaultCommitment, startSlot, limit);
  }

  @Override
  public CompletableFuture<long[]> getBlocksWithLimit(final Commitment commitment, final long startSlot, final long limit) {
    return sendPostRequest(LONG_ARRAY, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getBlocksWithLimit","params":[%d,%d,{"commitment":"%s"}]}""",
        id.incrementAndGet(), startSlot, Math.min(limit, 500_000), commitment.getValue()));
  }

  @Override
  public CompletableFuture<Instant> getBlockTime(final long slot) {
    return sendPostRequest(INSTANT, format("""
        {"jsonrpc":"2.0","id":%d,"method":"getBlockTime","params":[%d]}""", id.incrementAndGet(), slot));
  }

  @Override
  public CompletableFuture<List<ClusterNode>> getClusterNodes() {
    return sendPostRequest(CLUSTER_NODES, format("""
        {"jsonrpc":"2.0","id":%d,"method":"getClusterNodes"}""", id.incrementAndGet()));
  }

  @Override
  public CompletableFuture<EpochInfo> getEpochInfo() {
    return getEpochInfo(defaultCommitment);
  }

  @Override
  public CompletableFuture<EpochInfo> getEpochInfo(final Commitment commitment) {
    return sendPostRequest(EPOCH_INFO, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getEpochInfo","params":[{"commitment":"%s"}]}""",
        id.incrementAndGet(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<EpochSchedule> getEpochSchedule() {
    return sendPostRequest(EPOCH_SCHEDULE, format("""
        {"jsonrpc":"2.0","id":%d,"method":"getEpochSchedule"}""", id.incrementAndGet()));
  }

  @Override
  public CompletableFuture<FeeForMessage> getFeeForMessage(final String base64Msg) {
    return getFeeForMessage(defaultCommitment, base64Msg);
  }

  @Override
  public CompletableFuture<FeeForMessage> getFeeForMessage(final Commitment commitment, final String base64Msg) {
    return sendPostRequest(FEE_FOR_MESSAGE, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getFeeForMessage","params":["%s",{"commitment":"%s"}]}""",
        id.incrementAndGet(), base64Msg, commitment.getValue()));
  }

  @Override
  public CompletableFuture<Long> getFirstAvailableBlock() {
    return sendPostRequest(LONG_VAL, format("""
        {"jsonrpc":"2.0","id":%d,"method":"getFirstAvailableBlock"}""", id.incrementAndGet()));
  }

  @Override
  public CompletableFuture<String> getGenesisHash() {
    return sendPostRequest(STRING, format("""
        {"jsonrpc":"2.0","id":%d,"method":"getGenesisHash"}""", id.incrementAndGet()));
  }

  @Override
  public CompletableFuture<HighestSnapshotSlot> getHighestSnapshotSlot() {
    return sendPostRequest(HIGHEST_SNAPSHOT_SLOT, format("""
        {"jsonrpc":"2.0","id":%d,"method":"getHighestSnapshotSlot"}""", id.incrementAndGet()));
  }

  @Override
  public CompletableFuture<Identity> getIdentity() {
    return sendPostRequest(IDENTITY, format("""
        {"jsonrpc":"2.0","id":%d,"method":"getIdentity"}""", id.incrementAndGet()));
  }

  @Override
  public CompletableFuture<InflationGovernor> getInflationGovernor() {
    return getInflationGovernor(defaultCommitment);
  }

  @Override
  public CompletableFuture<InflationGovernor> getInflationGovernor(final Commitment commitment) {
    return sendPostRequest(INFLATION_GOVERNOR, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getInflationGovernor","params":[{"commitment":"%s"}]}""",
        id.incrementAndGet(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<InflationRate> getInflationRate() {
    return sendPostRequest(INFLATION_RATE, format("""
        {"jsonrpc":"2.0","id":%d,"method":"getInflationRate"}""", id.incrementAndGet()));
  }

  @Override
  public CompletableFuture<List<InflationReward>> getInflationReward(final List<PublicKey> keys) {
    return getInflationReward(defaultCommitment, keys);
  }

  @Override
  public CompletableFuture<List<InflationReward>> getInflationReward(final Commitment commitment,
                                                                     final List<PublicKey> keys) {
    final var joined = keys.isEmpty() ? "[]" : keys.stream()
        .map(PublicKey::toBase58)
        .collect(Collectors.joining("\",\"", "[\"", "\"]"));
    return sendPostRequest(INFLATION_REWARDS, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getInflationReward","params":[%s,{"commitment":"%s"}]}""",
        id.incrementAndGet(), joined, commitment.getValue()));
  }

  @Override
  public CompletableFuture<List<InflationReward>> getInflationReward(final List<PublicKey> keys, final long epoch) {
    return getInflationReward(defaultCommitment, List.of(), epoch);
  }

  @Override
  public CompletableFuture<List<InflationReward>> getInflationReward(final Commitment commitment,
                                                                     final List<PublicKey> keys,
                                                                     final long epoch) {
    final var joined = keys.isEmpty() ? "[]" : keys.stream()
        .map(PublicKey::toBase58)
        .collect(Collectors.joining("\",\"", "[\"", "\"]"));
    return sendPostRequest(INFLATION_REWARDS, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getInflationReward","params":[%s,{"commitment":"%s",{"epoch":%d}}]}""",
        id.incrementAndGet(), joined, commitment.getValue(), epoch));
  }

  @Override
  public CompletableFuture<List<AccountLamports>> getLargestAccounts() {
    return getLargestAccounts(defaultCommitment);
  }

  @Override
  public CompletableFuture<List<AccountLamports>> getLargestAccounts(final Commitment commitment) {
    return sendPostRequest(TOP_LAMPORT_ACCOUNTS, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getLargestAccounts","params":[{"commitment":"%s"}]}""",
        id.incrementAndGet(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<LatestBlockHash> getLatestBlockHash() {
    return getLatestBlockHash(defaultCommitment);
  }

  @Override
  public CompletableFuture<LatestBlockHash> getLatestBlockHash(final Commitment commitment) {
    return sendPostRequestNoWrap(latestBlockhashResponseParser, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getLatestBlockhash","params":[{"commitment":"%s"}]}""",
        id.incrementAndGet(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule() {
    return getLeaderSchedule(defaultCommitment);
  }

  @Override
  public CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final Commitment commitment) {
    return sendPostRequest(LEADER_SCHEDULE, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getLeaderSchedule","params":[null,{"commitment":"%s"}]}""",
        id.incrementAndGet(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final long slot) {
    return getLeaderSchedule(defaultCommitment, slot);
  }

  @Override
  public CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final Commitment commitment, final long slot) {
    return sendPostRequest(LEADER_SCHEDULE, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getLeaderSchedule","params":[%d,{"commitment":"%s"}]}""",
        id.incrementAndGet(), slot, commitment.getValue()));
  }

  @Override
  public CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final long slot, final PublicKey identity) {
    return getLeaderSchedule(defaultCommitment, slot, identity);
  }

  @Override
  public CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final Commitment commitment, final long slot, final PublicKey identity) {
    return sendPostRequest(LEADER_SCHEDULE, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getLeaderSchedule","params":[%d,{"commitment":"%s","identity":"%s"}]}""",
        id.incrementAndGet(), slot, commitment.getValue(), identity));
  }

  @Override
  public CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final PublicKey identity) {
    return getLeaderSchedule(defaultCommitment, identity);
  }

  @Override
  public CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final Commitment commitment, final PublicKey identity) {
    return sendPostRequest(LEADER_SCHEDULE, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getLeaderSchedule","params":[null,{"commitment":"%s","identity":"%s"}]}""",
        id.incrementAndGet(), commitment.getValue(), identity));
  }

  @Override
  public CompletableFuture<Long> getMaxRetransmitSlot() {
    return sendPostRequest(LONG_VAL, format("""
        {"jsonrpc":"2.0","id":%d,"method":"getMaxRetransmitSlot"}""", id.incrementAndGet()));
  }

  @Override
  public CompletableFuture<Long> getMaxShredInsertSlot() {
    return sendPostRequest(LONG_VAL, format("""
        {"jsonrpc":"2.0","id":%d,"method":"getMaxShredInsertSlot"}""", id.incrementAndGet()));
  }

  @Override
  public CompletableFuture<Long> getMinimumBalanceForRentExemption(final long accountLength) {
    return sendPostRequest(LONG_VAL, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getMinimumBalanceForRentExemption","params":[%d]}""",
        id.incrementAndGet(), accountLength));
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final List<PublicKey> keys,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getMultipleAccounts(defaultCommitment, keys, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final Commitment commitment,
                                                                         final List<PublicKey> keys,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    final var joinedAccounts = keys.stream().map(PublicKey::toBase58).collect(Collectors.joining("\",\""));
    return sendPostRequest(applyResponseValue((ji, context) -> AccountInfo.parseAccountsFromKeys(keys, ji, context, factory)),
        format("""
                {"jsonrpc":"2.0","id":%d,"method":"getMultipleAccounts","params":[["%s"],{"commitment":"%s","encoding":"base64"}]}""",
            id.incrementAndGet(), joinedAccounts, commitment.getValue()));
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
                                                                        final List<Filter> filters,
                                                                        final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(requestTimeout, programId, defaultCommitment, filters, factory);
  }

  @Override
  public <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                        final PublicKey programId,
                                                                        final Commitment commitment,
                                                                        final List<Filter> filters,
                                                                        final BiFunction<PublicKey, byte[], T> factory) {
    final var filtersJson = filters == null || filters.isEmpty() ? "" : filters.stream()
        .map(Filter::toJson)
        .collect(Collectors.joining(",", ",\"filters\":[", "]"));
    final var body = format("""
            {"jsonrpc":"2.0","id":%d,"method":"getProgramAccounts","params":["%s",{"commitment":"%s","withContext":true,"encoding":"base64"%s}]}""",
        id.incrementAndGet(), programId.toBase58(), commitment.getValue(), filtersJson
    );
    final var request = newRequest("POST", ofString(body)).build();
    return httpClient
        .sendAsync(request, ofByteArray())
        .thenApply(wrapParser(applyResponseValue((ji, context) -> AccountInfo.parseAccounts(ji, context, factory))));
  }

  @Override
  public CompletableFuture<List<PerfSample>> getRecentPerformanceSamples(final int limit) {
    return sendPostRequest(PERF_SAMPLE, format("""
        {"jsonrpc":"2.0","id":%d,"method":"getRecentPerformanceSamples","params":[%d]}""", id.incrementAndGet(), Math.min(limit, 720)));
  }

  @Override
  public CompletableFuture<List<PrioritizationFee>> getRecentPrioritizationFees(final Collection<PublicKey> writablePublicKeys) {
    final var params = writablePublicKeys == null || writablePublicKeys.isEmpty() ? "" : writablePublicKeys.stream()
        .map(PublicKey::toBase58)
        .collect(Collectors.joining("\",\"", "[\"", "\"]"));
    final var body = format("""
        {"jsonrpc":"2.0","id":%d,"method":"getRecentPrioritizationFees","params":[%s]}""", id.incrementAndGet(), params);
    return sendPostRequest(PRIORITIZATION_FEE, body);
  }

  @Override
  public CompletableFuture<List<TxSig>> getSignaturesForAddress(final PublicKey address, final int limit) {
    return getSignaturesForAddress(defaultCommitment, address, limit);
  }

  @Override
  public CompletableFuture<List<TxSig>> getSignaturesForAddress(final Commitment commitment, final PublicKey address, final int limit) {
    return sendPostRequest(TX_SIGNATURES, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getSignaturesForAddress","params":["%s",{"commitment":"%s","limit":%d}]}""",
        id.incrementAndGet(), address, commitment.getValue(), Math.min(limit, 1_000)));
  }

  @Override
  public CompletableFuture<List<TxSig>> getSignaturesForAddressBefore(final PublicKey address, final int limit, final String beforeTxSig) {
    return getSignaturesForAddressBefore(defaultCommitment, address, limit, beforeTxSig);
  }

  @Override
  public CompletableFuture<List<TxSig>> getSignaturesForAddressBefore(final Commitment commitment, final PublicKey address, final int limit, final String beforeTxSig) {
    return sendPostRequest(TX_SIGNATURES, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getSignaturesForAddress","params":["%s",{"commitment":"%s","limit":%d,"before":"%s"}]}""",
        id.incrementAndGet(), address.toBase58(), commitment.getValue(), Math.min(limit, 1_000), beforeTxSig));
  }

  @Override
  public CompletableFuture<List<TxSig>> getSignaturesForAddressUntil(final PublicKey address, final int limit, final String untilTxSig) {
    return getSignaturesForAddressUntil(defaultCommitment, address, limit, untilTxSig);
  }

  @Override
  public CompletableFuture<List<TxSig>> getSignaturesForAddressUntil(final Commitment commitment, final PublicKey address, final int limit, final String untilTxSig) {
    return sendPostRequest(TX_SIGNATURES, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getSignaturesForAddress","params":["%s",{"commitment":"%s","limit":%d,"until":"%s"}]}""",
        id.incrementAndGet(), address.toBase58(), commitment.getValue(), Math.min(limit, 1_000), untilTxSig));
  }

  private String sigStatusBody(final Collection<String> signatures, final boolean searchTransactionHistory) {
    final var joined = String.join("\",\"", signatures);
    return format("""
            {"jsonrpc":"2.0","id":%d,"method":"getSignatureStatuses","params":[["%s"],{"searchTransactionHistory":%b}]}""",
        id.incrementAndGet(), joined, searchTransactionHistory
    );
  }

  @Override
  public CompletableFuture<Map<String, TxStatus>> getSignatureStatuses(final List<String> signatures, final boolean searchTransactionHistory) {
    return sendPostRequest(
        applyResponseValue((ji, context) -> TxStatus.parse(signatures, ji, context)),
        sigStatusBody(signatures, searchTransactionHistory)
    );
  }

  @Override
  public CompletableFuture<List<TxStatus>> getSigStatusList(final List<String> signatures, final boolean searchTransactionHistory) {
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
        id.incrementAndGet(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<PublicKey> getSlotLeader() {
    return getSlotLeader(defaultCommitment);
  }

  @Override
  public CompletableFuture<PublicKey> getSlotLeader(final Commitment commitment) {
    return sendPostRequest(PUBLIC_KEY, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getSlotLeader","params":[{"commitment":"%s"}]}""",
        id.incrementAndGet(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<List<PublicKey>> getSlotLeaders(final long from, final int limit) {
    return sendPostRequest(PUBLIC_KEY_LIST, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getSlotLeaders","params":[%d,%d]}""",
        id.incrementAndGet(), from, Math.min(limit, 5_000)));
  }

  @Override
  public CompletableFuture<Lamports> getStakeMinimumDelegation() {
    return getStakeMinimumDelegation(defaultCommitment);
  }

  @Override
  public CompletableFuture<Lamports> getStakeMinimumDelegation(final Commitment commitment) {
    return sendPostRequest(CONTEXT_LONG_VAL, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getStakeMinimumDelegation","params":[{"commitment":"%s"}]}""",
        id.incrementAndGet(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<Supply> getSupply() {
    return getSupply(defaultCommitment, false);
  }

  @Override
  public CompletableFuture<Supply> getSupply(final Commitment commitment, final boolean excludeNonCirculatingAccountsList) {
    return sendPostRequest(SUPPLY, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getSupply","params":[{"commitment":"%s","excludeNonCirculatingAccountsList":%b}]}""",
        id.incrementAndGet(), commitment.getValue(), excludeNonCirculatingAccountsList));
  }

  @Override
  public CompletableFuture<TokenAmount> getTokenAccountBalance(final PublicKey tokenAccount) {
    return getTokenAccountBalance(defaultCommitment, tokenAccount);
  }

  @Override
  public CompletableFuture<TokenAmount> getTokenAccountBalance(final Commitment commitment, final PublicKey tokenAccount) {
    return sendPostRequest(TOKEN_AMOUNT, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getTokenAccountBalance","params":["%s",{"commitment":"%s"}]}""",
        id.incrementAndGet(), tokenAccount.toBase58(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForTokenMintByDelegate(final PublicKey delegate, final PublicKey tokenMint) {
    return getTokenAccountsForTokenMintByDelegate(defaultCommitment, delegate, tokenMint);
  }

  @Override
  public CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForTokenMintByDelegate(final Commitment commitment, final PublicKey delegate, final PublicKey tokenMint) {
    return sendPostRequest(TOKEN_ACCOUNTS_PARSER, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getTokenAccountsByDelegate","params":["%s",{"mint":"%s"},{"commitment":"%s","encoding":"base64"}]}""",
        id.incrementAndGet(), delegate.toBase58(), tokenMint.toBase58(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForProgramByDelegate(final PublicKey delegate, final PublicKey programId) {
    return getTokenAccountsForProgramByDelegate(defaultCommitment, delegate, programId);
  }

  @Override
  public CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForProgramByDelegate(final Commitment commitment, final PublicKey delegate, final PublicKey programId) {
    return sendPostRequest(TOKEN_ACCOUNTS_PARSER, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getTokenAccountsByDelegate","params":["%s",{"programId":"%s"},{"commitment":"%s","encoding":"base64"}]}""",
        id.incrementAndGet(), delegate.toBase58(), programId.toBase58(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForTokenMintByOwner(final PublicKey owner, final PublicKey tokenMint) {
    return getTokenAccountsForTokenMintByOwner(defaultCommitment, owner, tokenMint);
  }

  @Override
  public CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForTokenMintByOwner(final Commitment commitment, final PublicKey owner, final PublicKey tokenMint) {
    return sendPostRequest(TOKEN_ACCOUNTS_PARSER, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getTokenAccountsByOwner","params":["%s",{"mint":"%s"},{"commitment":"%s","encoding":"base64"}]}""",
        id.incrementAndGet(), owner.toBase58(), tokenMint.toBase58(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForProgramByOwner(final PublicKey owner, final PublicKey programId) {
    return getTokenAccountsForProgramByOwner(defaultCommitment, owner, programId);
  }

  @Override
  public CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForProgramByOwner(final Commitment commitment, final PublicKey owner, final PublicKey programId) {
    return sendPostRequest(TOKEN_ACCOUNTS_PARSER, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getTokenAccountsByOwner","params":["%s",{"programId":"%s"},{"commitment":"%s","encoding":"base64"}]}""",
        id.incrementAndGet(), owner.toBase58(), programId.toBase58(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<List<AccountTokenAmount>> getTokenLargestAccounts(final PublicKey tokenMint) {
    return getTokenLargestAccounts(defaultCommitment, tokenMint);
  }

  @Override
  public CompletableFuture<List<AccountTokenAmount>> getTokenLargestAccounts(final Commitment commitment, final PublicKey tokenMint) {
    return sendPostRequest(ACCOUNT_TOKEN_AMOUNT, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getTokenLargestAccounts","params":["%s",{"commitment":"%s"}]}""",
        id.incrementAndGet(), tokenMint.toBase58(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<TokenAmount> getTokenSupply(final PublicKey tokenMintAccount) {
    return getTokenSupply(defaultCommitment, tokenMintAccount);
  }

  @Override
  public CompletableFuture<TokenAmount> getTokenSupply(final Commitment commitment, final PublicKey tokenMintAccount) {
    return sendPostRequest(TOKEN_AMOUNT, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getTokenSupply","params":["%s",{"commitment":"%s"}]}""",
        id.incrementAndGet(), tokenMintAccount.toBase58(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<Tx> getTransaction(final String txSignature) {
    return getTransaction(defaultCommitment, txSignature);
  }

  @Override
  public CompletableFuture<Tx> getTransaction(final String txSignature,
                                              final int maxSupportedTransactionVersion,
                                              final String encoding) {
    return getTransaction(defaultCommitment, txSignature, maxSupportedTransactionVersion, encoding);
  }

  @Override
  public CompletableFuture<Tx> getTransaction(final Commitment commitment,
                                              final String txSignature,
                                              final int maxSupportedTransactionVersion,
                                              final String encoding) {
    final var maxVersionParam = maxSupportedTransactionVersion < 0
        ? ""
        : String.format("\"maxSupportedTransactionVersion\":%d,", maxSupportedTransactionVersion);
    return sendPostRequest(TRANSACTION, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getTransaction","params":["%s",{"commitment":"%s",%s"encoding":"base64"}]}""",
        id.incrementAndGet(), txSignature, commitment.getValue(), maxVersionParam));
  }

  @Override
  public CompletableFuture<Long> getTransactionCount() {
    return getTransactionCount(defaultCommitment);
  }

  @Override
  public CompletableFuture<Long> getTransactionCount(final Commitment commitment) {
    return sendPostRequest(LONG_VAL, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getTransactionCount","params":[{"commitment":"%s"}]}""",
        id.incrementAndGet(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<Version> getVersion() {
    return sendPostRequest(VERSION, format("""
        {"jsonrpc":"2.0","id":%d,"method":"getVersion"}""", id.incrementAndGet()));
  }

  @Override
  public CompletableFuture<VoteAccounts> getVoteAccounts() {
    return getVoteAccounts(defaultCommitment);
  }

  @Override
  public CompletableFuture<VoteAccounts> getVoteAccounts(final Commitment commitment) {
    return sendPostRequest(VOTE_ACCOUNTS, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getVoteAccounts","params":[{"commitment":"%s"}]}""",
        id.incrementAndGet(), commitment.getValue()));
  }

  @Override
  public CompletableFuture<VoteAccounts> getVoteAccounts(final PublicKey validatorVoteAddress) {
    return getVoteAccounts(defaultCommitment, validatorVoteAddress);
  }

  @Override
  public CompletableFuture<VoteAccounts> getVoteAccounts(final Commitment commitment, final PublicKey validatorVoteAddress) {
    return sendPostRequest(VOTE_ACCOUNTS, format("""
            {"jsonrpc":"2.0","id":%d,"method":"getVoteAccounts","params":[{"commitment":"%s","votePubkey":"%s"}]}""",
        id.incrementAndGet(), commitment.getValue(), validatorVoteAddress.toBase58()));
  }

  @Override
  public CompletableFuture<ContextBoolVal> isBlockHashValid(final String b58BlockHash) {
    return isBlockHashValid(defaultCommitment, b58BlockHash);
  }

  @Override
  public CompletableFuture<ContextBoolVal> isBlockHashValid(final Commitment commitment, final String b58BlockHash) {
    return sendPostRequest(CONTEXT_BOOL_VAL, format("""
            {"jsonrpc":"2.0","id":%d,"method":"isBlockhashValid","params":["%s",{"commitment":"%s"}]}""",
        id.incrementAndGet(), b58BlockHash, commitment.getValue()));
  }

  @Override
  public CompletableFuture<Long> minimumLedgerSlot() {
    return sendPostRequest(LONG_VAL, format("""
        {"jsonrpc":"2.0","id":%d,"method":"minimumLedgerSlot"}""", id.incrementAndGet()));
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
        id.incrementAndGet(), key.toBase58(), lamports, commitment.getValue()));
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
                                                   final List<Signer> signers,
                                                   final byte[] recentBlockHash) {
    return sendTransaction(defaultCommitment, transaction, signers, recentBlockHash);
  }

  @Override
  public CompletableFuture<String> sendTransaction(final Commitment preflightCommitment,
                                                   final Transaction transaction,
                                                   final List<Signer> signers,
                                                   final byte[] recentBlockHash) {
    final var base64SignedTx = transaction.signAndBase64Encode(recentBlockHash, signers);
    return sendTransaction(preflightCommitment, base64SignedTx);
  }

  @Override
  public CompletableFuture<String> sendTransaction(final String base64SignedTx, final int maxRetries) {
    return sendTransaction(this.defaultCommitment, base64SignedTx, maxRetries);
  }

  @Override
  public CompletableFuture<String> sendTransaction(final Commitment preflightCommitment, final String base64SignedTx, final int maxRetries) {
    return sendPostRequestNoWrap(sendTxResponseParser, format("""
            {"jsonrpc":"2.0","id":%d,"method":"sendTransaction","params":["%s",{"encoding":"base64","preflightCommitment":"%s","maxRetries":%d}]}""",
        id.incrementAndGet(), base64SignedTx, preflightCommitment.getValue(), maxRetries));
  }

  @Override
  public CompletableFuture<String> sendTransactionSkipPreflight(final Commitment preflightCommitment, final String base64SignedTx, final int maxRetries) {
    return sendPostRequestNoWrap(sendTxResponseParser, format("""
            {"jsonrpc":"2.0","id":%d,"method":"sendTransaction","params":["%s",{"encoding":"base64","skipPreflight":true,"preflightCommitment":"%s","maxRetries":%d}]}""",
        id.incrementAndGet(), base64SignedTx, preflightCommitment.getValue(), maxRetries));
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Transaction transaction,
                                                             final PublicKey signer,
                                                             final List<PublicKey> accounts) {
    return simulateTransaction(defaultCommitment, transaction, signer, accounts);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                             final Transaction transaction,
                                                             final PublicKey signer,
                                                             final List<PublicKey> accounts) {
    final var base64TxData = transaction.base64EncodeToString();
    return simulateTransaction(commitment, base64TxData, signer, accounts);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final String base64EncodedTx,
                                                             final PublicKey signer,
                                                             final List<PublicKey> accounts) {
    return simulateTransaction(defaultCommitment, base64EncodedTx, signer, accounts);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                             final String base64EncodedTx,
                                                             final PublicKey signer,
                                                             final List<PublicKey> accounts) {
    return simulateTransaction(commitment, base64EncodedTx, List.of(signer), accounts);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Transaction transaction,
                                                             final List<PublicKey> signers,
                                                             final List<PublicKey> accounts) {
    return simulateTransaction(defaultCommitment, transaction, signers, accounts);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                             final Transaction transaction,
                                                             final List<PublicKey> signers,
                                                             final List<PublicKey> accounts) {
    final var base64TxData = transaction.base64EncodeToString();
    return simulateTransaction(commitment, base64TxData, signers, accounts);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final String base64EncodedTx,
                                                             final List<PublicKey> signers,
                                                             final List<PublicKey> accounts) {
    return simulateTransaction(defaultCommitment, base64EncodedTx, signers, accounts);
  }

  @Override
  public CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                             final String base64EncodedTx,
                                                             final List<PublicKey> signers,
                                                             final List<PublicKey> accounts) {
    final List<PublicKey> returnAccounts;
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
        id.incrementAndGet(), base64EncodedTx, commitment.getValue(), joinedAccounts));
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
        id.incrementAndGet(), base64EncodedTx, replaceRecentBlockhash, innerInstructions, commitment.getValue()));
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

  public static void main(String[] args) throws InterruptedException {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var rpcClient = SolanaRpcClient.createClient(
          SolanaNetwork.MAIN_NET.getEndpoint(),
          httpClient,
          response -> {
            System.out.println(new String(response.body()));
            return true;
          }
      );

      final var sig = "2yAHUUaFPnF8iDQ4rNm2nkxMjE3LtZm3KKfYVvVjYevpNp7nhQ9vMGrGHyAvTxKe6TyQYagEMDTLMAHDu4iwh2J5";
      final var tx = rpcClient.getTransaction(sig).join();
      final byte[] txData = tx.data();
      System.out.println(Base64.getEncoder().encodeToString(txData));


      final var skeleton = TransactionSkeleton.deserializeSkeleton(txData);
      final var tableKey = skeleton.lookupTableAccounts()[0];
//      final var tableAccountInfo = rpcClient.getAccountInfo(tableKey).join();
//      final byte[] tableData = tableAccountInfo.data();
//      System.out.println(Base64.getEncoder().encodeToString(tableData));
      final byte[] tableData = Base64.getDecoder().decode("""
          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
          """.stripTrailing());
      final var table = AddressLookupTable.read(tableKey, tableData);
      final var accounts = skeleton.parseAccounts(table);
      final var instructions = skeleton.parseInstructions(accounts);
      for (final var ix : instructions) {
        System.out.format("IX %s:%n", ix.programId());
        int a = 0;
        for (final var acct : ix.accounts()) {
          System.out.format("  %d: %s%n", a++, acct);
        }
      }
    }
  }
}
