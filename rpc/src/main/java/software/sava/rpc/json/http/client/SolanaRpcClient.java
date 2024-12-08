package software.sava.rpc.json.http.client;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.core.rpc.Filter;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.request.ContextBoolVal;
import software.sava.rpc.json.http.request.RpcEncoding;
import software.sava.rpc.json.http.response.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static software.sava.rpc.json.http.client.SolanaJsonRpcClient.DEFAULT_REQUEST_TIMEOUT;
import static software.sava.rpc.json.http.client.SolanaJsonRpcClient.PROGRAM_ACCOUNTS_TIMEOUT;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.PROCESSED;
import static software.sava.rpc.json.http.response.AccountInfo.BYTES_IDENTITY;

public interface SolanaRpcClient {

  int MAX_MULTIPLE_ACCOUNTS = 100;
  int MAX_GET_SIGNATURES = 1_000;

  static SolanaRpcClient createClient(final URI endpoint,
                                      final HttpClient httpClient,
                                      final Duration requestTimeout,
                                      final UnaryOperator<HttpRequest.Builder> extendRequest,
                                      final Predicate<HttpResponse<byte[]>> applyResponse,
                                      final Commitment defaultCommitment) {
    return new SolanaJsonRpcClient(endpoint, httpClient, requestTimeout, extendRequest, applyResponse, defaultCommitment);
  }

  static SolanaRpcClient createClient(final URI endpoint,
                                      final HttpClient httpClient,
                                      final UnaryOperator<HttpRequest.Builder> extendRequest,
                                      final Predicate<HttpResponse<byte[]>> applyResponse) {
    return createClient(endpoint, httpClient, DEFAULT_REQUEST_TIMEOUT, extendRequest, applyResponse, CONFIRMED);
  }

  static SolanaRpcClient createClient(final URI endpoint,
                                      final HttpClient httpClient,
                                      final Predicate<HttpResponse<byte[]>> applyResponse) {
    return createClient(endpoint, httpClient, null, applyResponse);
  }

  static SolanaRpcClient createClient(final URI endpoint,
                                      final HttpClient httpClient,
                                      final Duration requestTimeout,
                                      final Commitment defaultCommitment) {
    return createClient(endpoint, httpClient, requestTimeout, null, null, defaultCommitment);
  }

  static SolanaRpcClient createClient(final URI endpoint,
                                      final HttpClient httpClient,
                                      final Commitment defaultCommitment) {
    return createClient(endpoint, httpClient, DEFAULT_REQUEST_TIMEOUT, null, null, defaultCommitment);
  }

  static SolanaRpcClient createClient(final URI endpoint, final HttpClient httpClient) {
    return createClient(endpoint, httpClient, DEFAULT_REQUEST_TIMEOUT, null, null, CONFIRMED);
  }

  URI endpoint();

  HttpClient httpClient();

  CompletableFuture<NodeHealth> getHealth();

  CompletableFuture<FeeForMessage> getFeeForMessage(final String base64Msg);

  CompletableFuture<FeeForMessage> getFeeForMessage(final Commitment commitment, final String base64Msg);

  CompletableFuture<LatestBlockHash> getLatestBlockHash();

  CompletableFuture<LatestBlockHash> getLatestBlockHash(final Commitment commitment);

  CompletableFuture<NodeHealth> getHealth(final Duration requestTimeout);

  <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final PublicKey account, BiFunction<PublicKey, byte[], T> factory);

  <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final Commitment commitment,
                                                       final PublicKey account,
                                                       final BiFunction<PublicKey, byte[], T> factory);

  default CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final PublicKey account) {
    return getAccountInfo(account, BYTES_IDENTITY);
  }

  default CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final Commitment commitment, final PublicKey account) {
    return getAccountInfo(commitment, account, BYTES_IDENTITY);
  }

  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final List<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final Commitment commitment,
                                                                  final List<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  default CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final List<PublicKey> keys) {
    return getMultipleAccounts(keys, BYTES_IDENTITY);
  }

  default CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final Commitment commitment,
                                                                           final List<PublicKey> keys) {
    return getMultipleAccounts(commitment, keys, BYTES_IDENTITY);
  }

  CompletableFuture<Lamports> getBalance(final PublicKey account);

  CompletableFuture<Lamports> getBalance(final Commitment commitment, final PublicKey account);

  CompletableFuture<Block> getBlock(final long slot);

  CompletableFuture<Block> getBlock(final Commitment commitment, final long slot);

  CompletableFuture<BlockHeight> getBlockHeight();

  CompletableFuture<BlockHeight> getBlockHeight(final Commitment commitment);

  CompletableFuture<BlockProduction> getBlockProduction();

  CompletableFuture<BlockProduction> getBlockProduction(final Commitment commitment);

  CompletableFuture<BlockProduction> getBlockProduction(final PublicKey identity);

  CompletableFuture<BlockProduction> getBlockProduction(final Commitment commitment, final PublicKey identity);

  CompletableFuture<BlockProduction> getBlockProduction(final long firstSlot);

  CompletableFuture<BlockProduction> getBlockProduction(final Commitment commitment, final long firstSlot);

  CompletableFuture<BlockProduction> getBlockProduction(final PublicKey identity, final long firstSlot);

  CompletableFuture<BlockProduction> getBlockProduction(final Commitment commitment, final PublicKey identity, final long firstSlot);

  CompletableFuture<BlockCommitment> getBlockCommitment(final long slot);

  CompletableFuture<long[]> getBlocks(final long startSlot);

  CompletableFuture<long[]> getBlocks(final Commitment commitment, final long startSlot);

  CompletableFuture<long[]> getBlocks(final long startSlot, final long endSlot);

  CompletableFuture<long[]> getBlocks(final Commitment commitment, final long startSlot, final long endSlot);

  CompletableFuture<long[]> getBlocksWithLimit(final long startSlot, final long limit);

  CompletableFuture<long[]> getBlocksWithLimit(final Commitment commitment, final long startSlot, final long limit);

  CompletableFuture<Instant> getBlockTime(final long slot);

  CompletableFuture<List<ClusterNode>> getClusterNodes();

  CompletableFuture<EpochInfo> getEpochInfo();

  CompletableFuture<EpochInfo> getEpochInfo(final Commitment commitment);

  CompletableFuture<EpochSchedule> getEpochSchedule();

  CompletableFuture<String> getGenesisHash();

  CompletableFuture<HighestSnapshotSlot> getHighestSnapshotSlot();

  CompletableFuture<Identity> getIdentity();

  CompletableFuture<Long> getFirstAvailableBlock();

  CompletableFuture<InflationGovernor> getInflationGovernor();

  CompletableFuture<InflationGovernor> getInflationGovernor(final Commitment commitment);

  CompletableFuture<InflationRate> getInflationRate();

  CompletableFuture<List<InflationReward>> getInflationReward(final List<PublicKey> keys);

  CompletableFuture<List<InflationReward>> getInflationReward(final Commitment commitment,
                                                              final List<PublicKey> keys);

  CompletableFuture<List<InflationReward>> getInflationReward(final List<PublicKey> keys, final long epoch);

  CompletableFuture<List<InflationReward>> getInflationReward(final Commitment commitment,
                                                              final List<PublicKey> keys,
                                                              final long epoch);

  CompletableFuture<List<AccountLamports>> getLargestAccounts();

  CompletableFuture<List<AccountLamports>> getLargestAccounts(final Commitment commitment);

  CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule();

  CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final Commitment commitment);

  CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final long slot);

  CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final Commitment commitment, final long slot);

  CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final long slot, final PublicKey identity);

  CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final Commitment commitment,
                                                              final long slot,
                                                              final PublicKey identity);

  CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final PublicKey identity);

  CompletableFuture<Map<PublicKey, long[]>> getLeaderSchedule(final Commitment commitment, final PublicKey identity);

  CompletableFuture<Long> getMaxRetransmitSlot();

  CompletableFuture<Long> getMaxShredInsertSlot();

  CompletableFuture<Long> getMinimumBalanceForRentExemption(final long accountLength);

  default CompletableFuture<List<PerfSample>> getRecentPerformanceSamples() {
    return getRecentPerformanceSamples(720);
  }

  default CompletableFuture<List<AccountInfo<byte[]>>> getProgramAccounts(final PublicKey programId) {
    return getProgramAccounts(programId, BYTES_IDENTITY);
  }

  default CompletableFuture<List<AccountInfo<byte[]>>> getProgramAccounts(final PublicKey programId,
                                                                          final List<Filter> filters) {
    return getProgramAccounts(programId, filters, BYTES_IDENTITY);
  }

  default CompletableFuture<List<AccountInfo<byte[]>>> getProgramAccounts(final PublicKey programId,
                                                                          final Commitment commitment,
                                                                          final List<Filter> filters) {
    return getProgramAccounts(programId, commitment, filters, BYTES_IDENTITY);
  }

  default <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final PublicKey programId,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, factory);
  }

  default <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final PublicKey programId,
                                                                         final List<Filter> filters,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, filters, factory);
  }

  default <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final PublicKey programId,
                                                                         final Commitment commitment,
                                                                         final List<Filter> filters,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, commitment, filters, factory);
  }

  <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                 final PublicKey programId,
                                                                 final BiFunction<PublicKey, byte[], T> factory);

  <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                 final PublicKey programId,
                                                                 final List<Filter> filters,
                                                                 final BiFunction<PublicKey, byte[], T> factory);

  <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                 final PublicKey programId,
                                                                 final Commitment commitment,
                                                                 final List<Filter> filters,
                                                                 final BiFunction<PublicKey, byte[], T> factory);

  CompletableFuture<List<PerfSample>> getRecentPerformanceSamples(final int limit);

  default CompletableFuture<List<PrioritizationFee>> getRecentPrioritizationFees() {
    return getRecentPrioritizationFees(null);
  }

  CompletableFuture<List<PrioritizationFee>> getRecentPrioritizationFees(final Collection<PublicKey> writablePublicKeys);

  CompletableFuture<List<TxSig>> getSignaturesForAddress(final PublicKey address, final int limit);

  CompletableFuture<List<TxSig>> getSignaturesForAddress(final Commitment commitment, final PublicKey address, final int limit);

  CompletableFuture<List<TxSig>> getSignaturesForAddressBefore(final PublicKey address, final int limit, final String beforeTxSig);

  CompletableFuture<List<TxSig>> getSignaturesForAddressBefore(final Commitment commitment, final PublicKey address, final int limit, final String beforeTxSig);

  CompletableFuture<List<TxSig>> getSignaturesForAddressUntil(final PublicKey address, final int limit, final String untilTxSig);

  CompletableFuture<List<TxSig>> getSignaturesForAddressUntil(final Commitment commitment, final PublicKey address, final int limit, final String untilTxSig);

  default CompletableFuture<Map<String, TxStatus>> getSignatureStatuses(final List<String> txIds) {
    return getSignatureStatuses(txIds, false);
  }

  CompletableFuture<Map<String, TxStatus>> getSignatureStatuses(final List<String> txIds, final boolean searchTransactionHistory);

  CompletableFuture<Long> getSlot();

  CompletableFuture<Long> getSlot(final Commitment commitment);

  CompletableFuture<PublicKey> getSlotLeader();

  CompletableFuture<PublicKey> getSlotLeader(final Commitment commitment);

  CompletableFuture<List<PublicKey>> getSlotLeaders(final long from, final int limit);

  CompletableFuture<Lamports> getStakeMinimumDelegation();

  CompletableFuture<Lamports> getStakeMinimumDelegation(final Commitment commitment);

  CompletableFuture<Supply> getSupply();

  CompletableFuture<Supply> getSupply(final Commitment commitment, final boolean excludeNonCirculatingAccountsList);

  CompletableFuture<TokenAmount> getTokenAccountBalance(final PublicKey tokenAccount);

  CompletableFuture<TokenAmount> getTokenAccountBalance(final Commitment commitment, final PublicKey tokenAccount);

  CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForTokenMintByDelegate(final PublicKey delegate, final PublicKey tokenMint);

  CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForTokenMintByDelegate(final Commitment commitment, final PublicKey delegate, final PublicKey tokenMint);

  CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForProgramByDelegate(final PublicKey delegate, final PublicKey programId);

  CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForProgramByDelegate(final Commitment commitment, final PublicKey delegate, final PublicKey programId);

  CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForTokenMintByOwner(final PublicKey owner, final PublicKey tokenMint);

  CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForTokenMintByOwner(final Commitment commitment, final PublicKey owner, final PublicKey tokenMint);

  CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForProgramByOwner(final PublicKey owner, final PublicKey programId);

  CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForProgramByOwner(final Commitment commitment, final PublicKey owner, final PublicKey programId);

  CompletableFuture<List<AccountTokenAmount>> getTokenLargestAccounts(final PublicKey tokenMint);

  CompletableFuture<List<AccountTokenAmount>> getTokenLargestAccounts(final Commitment commitment, final PublicKey tokenMint);

  CompletableFuture<TokenAmount> getTokenSupply(final PublicKey tokenMintAccount);

  CompletableFuture<TokenAmount> getTokenSupply(final Commitment commitment, final PublicKey tokenMintAccount);

  CompletableFuture<Tx> getTransaction(final String txSignature);

  default CompletableFuture<Tx> getTransaction(final Commitment commitment, final String txSignature) {
    return getTransaction(commitment, txSignature, 0, RpcEncoding.base64.name());
  }

  default CompletableFuture<Tx> getTransaction(final int maxSupportedTransactionVersion, final String txSignature) {
    return getTransaction(txSignature, maxSupportedTransactionVersion, RpcEncoding.base64.name());
  }

  default CompletableFuture<Tx> getTransaction(final Commitment commitment,
                                               final int maxSupportedTransactionVersion,
                                               final String txSignature) {
    return getTransaction(commitment, txSignature, maxSupportedTransactionVersion, RpcEncoding.base64.name());
  }

  @Deprecated()
  default CompletableFuture<Tx> getTransaction(final String txSignature, final String encoding) {
    return getTransaction(txSignature, 0, encoding);
  }

  @Deprecated()
  default CompletableFuture<Tx> getTransaction(final String txSignature, final RpcEncoding encoding) {
    return getTransaction(txSignature, 0, encoding.name());
  }

  @Deprecated()
  CompletableFuture<Tx> getTransaction(final String txSignature,
                                       final int maxSupportedTransactionVersion,
                                       final String encoding);

  @Deprecated()
  default CompletableFuture<Tx> getTransaction(final String txSignature,
                                               final int maxSupportedTransactionVersion,
                                               final RpcEncoding encoding) {
    return getTransaction(txSignature, maxSupportedTransactionVersion, encoding.name());
  }

  @Deprecated()
  CompletableFuture<Tx> getTransaction(final Commitment commitment,
                                       final String txSignature,
                                       final int maxSupportedTransactionVersion,
                                       final String encoding);

  @Deprecated()
  default CompletableFuture<Tx> getTransaction(final Commitment commitment,
                                               final String txSignature,
                                               final int maxSupportedTransactionVersion,
                                               final RpcEncoding encoding) {
    return getTransaction(commitment, txSignature, maxSupportedTransactionVersion, encoding.name());
  }

  CompletableFuture<Long> getTransactionCount();

  CompletableFuture<Long> getTransactionCount(final Commitment commitment);

  CompletableFuture<Version> getVersion();

  CompletableFuture<VoteAccounts> getVoteAccounts();

  CompletableFuture<VoteAccounts> getVoteAccounts(final Commitment commitment);

  CompletableFuture<VoteAccounts> getVoteAccounts(final PublicKey validatorVoteAddress);

  CompletableFuture<VoteAccounts> getVoteAccounts(final Commitment commitment, final PublicKey validatorVoteAddress);

  CompletableFuture<ContextBoolVal> isBlockHashValid(final String b58BlockHash);

  CompletableFuture<ContextBoolVal> isBlockHashValid(final Commitment commitment, final String b58BlockHash);

  CompletableFuture<Long> minimumLedgerSlot();

  CompletableFuture<String> requestAirdrop(final PublicKey key, final long lamports);

  CompletableFuture<String> requestAirdrop(final Commitment commitment, final PublicKey key, final long lamports);

  CompletableFuture<String> sendTransaction(final Transaction transaction,
                                            final Signer signer,
                                            final byte[] recentBlockHash);

  CompletableFuture<String> sendTransaction(final Commitment preflightCommitment,
                                            final Transaction transaction,
                                            final Signer signer,
                                            final byte[] recentBlockHash);

  CompletableFuture<String> sendTransaction(final Transaction transaction,
                                            final List<Signer> signers,
                                            final byte[] recentBlockHash);

  CompletableFuture<String> sendTransaction(final Commitment preflightCommitment,
                                            final Transaction transaction,
                                            final List<Signer> signers,
                                            final byte[] recentBlockHash);

  default CompletableFuture<String> sendTransaction(final String base64SignedTx) {
    return sendTransaction(base64SignedTx, 1);
  }

  CompletableFuture<String> sendTransaction(final String base64SignedTx, final int maxRetries);

  default CompletableFuture<String> sendTransaction(final Commitment preflightCommitment, final String base64SignedTx) {
    return sendTransaction(preflightCommitment, base64SignedTx, 1);
  }

  CompletableFuture<String> sendTransaction(final Commitment preflightCommitment, final String base64SignedTx, final int maxRetries);

  default CompletableFuture<String> sendTransactionSkipPreflight(final String base64SignedTx) {
    return sendTransactionSkipPreflight(PROCESSED, base64SignedTx, 0);
  }

  default CompletableFuture<String> sendTransactionSkipPreflight(final String base64SignedTx, final int maxRetries) {
    return sendTransactionSkipPreflight(PROCESSED, base64SignedTx, maxRetries);
  }

  default CompletableFuture<String> sendTransactionSkipPreflight(final Commitment preflightCommitment,
                                                                 final String base64SignedTx) {
    return sendTransactionSkipPreflight(preflightCommitment, base64SignedTx, 0);
  }

  CompletableFuture<String> sendTransactionSkipPreflight(final Commitment preflightCommitment,
                                                         final String base64SignedTx,
                                                         final int maxRetries);

  default CompletableFuture<String> sendTransaction(final String base64SignedTx, final boolean skipPreFlight) {
    return skipPreFlight
        ? sendTransactionSkipPreflight(base64SignedTx)
        : sendTransaction(base64SignedTx);
  }

  default CompletableFuture<String> sendTransaction(final String base64SignedTx, final boolean skipPreFlight, final int maxRetries) {
    return skipPreFlight
        ? sendTransactionSkipPreflight(base64SignedTx, maxRetries)
        : sendTransaction(base64SignedTx, maxRetries);
  }

  default CompletableFuture<String> sendTransaction(final Commitment preflightCommitment, final String base64SignedTx, final boolean skipPreFlight) {
    return skipPreFlight
        ? sendTransactionSkipPreflight(preflightCommitment, base64SignedTx)
        : sendTransaction(preflightCommitment, base64SignedTx);
  }

  default CompletableFuture<String> sendTransaction(final Commitment preflightCommitment, final String base64SignedTx, final boolean skipPreFlight, final int maxRetries) {
    return skipPreFlight
        ? sendTransactionSkipPreflight(preflightCommitment, base64SignedTx, maxRetries)
        : sendTransaction(preflightCommitment, base64SignedTx, maxRetries);
  }

  CompletableFuture<TxSimulation> simulateTransaction(final Transaction transaction,
                                                      final PublicKey signer,
                                                      final List<PublicKey> accounts);

  CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                      final Transaction transaction,
                                                      final PublicKey signer,
                                                      final List<PublicKey> accounts);

  CompletableFuture<TxSimulation> simulateTransaction(final Transaction transaction);

  CompletableFuture<TxSimulation> simulateTransaction(final Transaction transaction,
                                                      final boolean replaceRecentBlockhash);

  CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                      final Transaction transaction,
                                                      final boolean replaceRecentBlockhash);

  CompletableFuture<TxSimulation> simulateTransaction(final String base64EncodedTx);

  CompletableFuture<TxSimulation> simulateTransaction(final String base64EncodedTx,
                                                      final PublicKey signer,
                                                      final List<PublicKey> accounts);

  CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                      final String base64EncodedTx,
                                                      final PublicKey signer,
                                                      final List<PublicKey> accounts);

  CompletableFuture<TxSimulation> simulateTransaction(final Transaction transaction,
                                                      final List<PublicKey> signers,
                                                      final List<PublicKey> accounts);

  CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                      final Transaction transaction,
                                                      final List<PublicKey> signers,
                                                      final List<PublicKey> accounts);

  CompletableFuture<TxSimulation> simulateTransaction(final String base64EncodedTx,
                                                      final List<PublicKey> signers,
                                                      final List<PublicKey> accounts);

  CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                      final String base64EncodedTx,
                                                      final List<PublicKey> signers,
                                                      final List<PublicKey> accounts);

  CompletableFuture<TxSimulation> simulateTransaction(final String base64EncodedTx,
                                                      final boolean replaceRecentBlockhash);

  CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                      final String base64EncodedTx,
                                                      final boolean replaceRecentBlockhash);

}
