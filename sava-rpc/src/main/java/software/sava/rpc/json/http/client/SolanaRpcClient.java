package software.sava.rpc.json.http.client;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.core.rpc.Filter;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.request.BlockTxDetails;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.request.ContextBoolVal;
import software.sava.rpc.json.http.request.LargestAccountsFilter;
import software.sava.rpc.json.http.response.*;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SequencedCollection;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import static software.sava.rpc.json.http.client.SolanaJsonRpcClient.DEFAULT_REQUEST_TIMEOUT;
import static software.sava.rpc.json.http.client.SolanaJsonRpcClient.PROGRAM_ACCOUNTS_TIMEOUT;
import static software.sava.rpc.json.http.request.Commitment.CONFIRMED;
import static software.sava.rpc.json.http.request.Commitment.PROCESSED;
import static software.sava.rpc.json.http.response.AccountInfo.BYTES_IDENTITY;

public interface SolanaRpcClient {

  int MAX_MULTIPLE_ACCOUNTS = 100;
  int MAX_GET_SIGNATURES = 1_000;
  int MAX_SIG_STATUS = 256;

  static SolanaRpcClientBuilder build() {
    return new SolanaRpcClientBuilder();
  }

  static SolanaRpcClient createClient(final URI endpoint,
                                      final HttpClient httpClient,
                                      final Duration requestTimeout,
                                      final Commitment defaultCommitment) {
    return new SolanaJsonRpcClient(endpoint, httpClient, requestTimeout, null, null, defaultCommitment, null);
  }

  static SolanaRpcClient createClient(final URI endpoint,
                                      final HttpClient httpClient,
                                      final Commitment defaultCommitment) {
    return createClient(endpoint, httpClient, DEFAULT_REQUEST_TIMEOUT, defaultCommitment);
  }

  static SolanaRpcClient createClient(final URI endpoint, final HttpClient httpClient) {
    return createClient(endpoint, httpClient, DEFAULT_REQUEST_TIMEOUT, CONFIRMED);
  }

  URI endpoint();

  HttpClient httpClient();

  Commitment defaultCommitment();

  Duration defaultRequestTimeout();

  CompletableFuture<NodeHealth> getHealth();

  CompletableFuture<FeeForMessage> getFeeForMessage(final String base64Msg);

  CompletableFuture<FeeForMessage> getFeeForMessage(final Commitment commitment, final String base64Msg);

  CompletableFuture<LatestBlockHash> getLatestBlockHash();

  CompletableFuture<LatestBlockHash> getLatestBlockHash(final Commitment commitment);

  CompletableFuture<NodeHealth> getHealth(final Duration requestTimeout);

  /// A missing account yields a non-null [AccountInfo] whose [AccountInfo#owner()] is `null`,
  /// built by applying `factory` to `null` data.
  <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final PublicKey account,
                                                       final BiFunction<PublicKey, byte[], T> factory);

  /// Missing account: a hollow record, not `null`; see [#getAccountInfo(PublicKey, BiFunction)].
  <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final Commitment commitment,
                                                       final PublicKey account,
                                                       final BiFunction<PublicKey, byte[], T> factory);

  /// Missing account: a hollow record, not `null`; see [#getAccountInfo(PublicKey, BiFunction)].
  default CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final PublicKey account) {
    return getAccountInfo(account, BYTES_IDENTITY);
  }

  /// Missing account: a hollow record, not `null`; see [#getAccountInfo(PublicKey, BiFunction)].
  default CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final Commitment commitment, final PublicKey account) {
    return getAccountInfo(commitment, account, BYTES_IDENTITY);
  }

  /// Missing account: a hollow record, not `null`; see [#getAccountInfo(PublicKey, BiFunction)].
  CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final BigInteger minContextSlot,
                                                        final PublicKey account);

  /// Missing account: a hollow record, not `null`; see [#getAccountInfo(PublicKey, BiFunction)].
  CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final int length,
                                                        final int offset,
                                                        final PublicKey account);

  /// Missing account: a hollow record, not `null`; see [#getAccountInfo(PublicKey, BiFunction)].
  CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final Commitment commitment,
                                                        final BigInteger minContextSlot,
                                                        final PublicKey account);

  /// Missing account: a hollow record, not `null`; see [#getAccountInfo(PublicKey, BiFunction)].
  CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final Commitment commitment,
                                                        final int length,
                                                        final int offset,
                                                        final PublicKey account);

  /// Missing account: a hollow record, not `null`; see [#getAccountInfo(PublicKey, BiFunction)].
  CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final BigInteger minContextSlot,
                                                        final int length,
                                                        final int offset,
                                                        final PublicKey account);

  /// Missing account: a hollow record, not `null`; see [#getAccountInfo(PublicKey, BiFunction)].
  CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final Commitment commitment,
                                                        final BigInteger minContextSlot,
                                                        final int length,
                                                        final int offset,
                                                        final PublicKey account);

  /// Missing account: a hollow record, not `null`; see [#getAccountInfo(PublicKey, BiFunction)].
  <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final BigInteger minContextSlot,
                                                       final PublicKey account,
                                                       final BiFunction<PublicKey, byte[], T> factory);

  /// Missing account: a hollow record, not `null`; see [#getAccountInfo(PublicKey, BiFunction)].
  <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final int length,
                                                       final int offset,
                                                       final PublicKey account,
                                                       final BiFunction<PublicKey, byte[], T> factory);

  /// Missing account: a hollow record, not `null`; see [#getAccountInfo(PublicKey, BiFunction)].
  <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final Commitment commitment,
                                                       final BigInteger minContextSlot,
                                                       final PublicKey account,
                                                       final BiFunction<PublicKey, byte[], T> factory);

  /// Missing account: a hollow record, not `null`; see [#getAccountInfo(PublicKey, BiFunction)].
  <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final Commitment commitment,
                                                       final int length,
                                                       final int offset,
                                                       final PublicKey account,
                                                       final BiFunction<PublicKey, byte[], T> factory);

  /// Missing account: a hollow record, not `null`; see [#getAccountInfo(PublicKey, BiFunction)].
  <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final Commitment commitment,
                                                       final BigInteger minContextSlot,
                                                       final int length,
                                                       final int offset,
                                                       final PublicKey account,
                                                       final BiFunction<PublicKey, byte[], T> factory);

  CompletableFuture<Lamports> getBalance(final PublicKey account);

  CompletableFuture<Lamports> getBalance(final Commitment commitment, final PublicKey account);

  CompletableFuture<Block> getBlock(final long slot);

  CompletableFuture<Block> getBlock(final long slot, final BlockTxDetails blockTxDetails);

  default CompletableFuture<Block> getBlock(final Commitment commitment, final long slot) {
    return getBlock(commitment, slot, BlockTxDetails.none);
  }

  default CompletableFuture<Block> getBlock(final Commitment commitment,
                                            final long slot,
                                            final BlockTxDetails blockTxDetails) {
    return getBlock(commitment, slot, blockTxDetails, true);
  }

  default CompletableFuture<Block> getBlock(final long slot, final boolean rewards) {
    return getBlock(slot, BlockTxDetails.none, rewards);
  }

  CompletableFuture<Block> getBlock(final long slot,
                                    final BlockTxDetails blockTxDetails,
                                    final boolean rewards);

  CompletableFuture<Block> getBlock(final Commitment commitment,
                                    final long slot,
                                    final BlockTxDetails blockTxDetails,
                                    final boolean rewards);

  CompletableFuture<BlockHeight> getBlockHeight();

  CompletableFuture<BlockHeight> getBlockHeight(final Commitment commitment);

  CompletableFuture<BlockProduction> getBlockProduction();

  CompletableFuture<BlockProduction> getBlockProduction(final Commitment commitment);

  CompletableFuture<BlockProduction> getBlockProduction(final PublicKey identity);

  CompletableFuture<BlockProduction> getBlockProduction(final Commitment commitment, final PublicKey identity);

  CompletableFuture<BlockProduction> getBlockProduction(final long firstSlot);

  CompletableFuture<BlockProduction> getBlockProduction(final Commitment commitment, final long firstSlot);

  CompletableFuture<BlockProduction> getBlockProduction(final PublicKey identity, final long firstSlot);

  CompletableFuture<BlockProduction> getBlockProduction(final Commitment commitment,
                                                        final PublicKey identity,
                                                        final long firstSlot);

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

  /// A key with no reward yields an all-zero entry; see [InflationReward#parse].
  CompletableFuture<List<InflationReward>> getInflationReward(final SequencedCollection<PublicKey> keys);

  /// A key with no reward yields an all-zero entry; see [InflationReward#parse].
  CompletableFuture<List<InflationReward>> getInflationReward(final Commitment commitment,
                                                              final SequencedCollection<PublicKey> keys);

  /// A key with no reward yields an all-zero entry; see [InflationReward#parse].
  CompletableFuture<List<InflationReward>> getInflationReward(final SequencedCollection<PublicKey> keys,
                                                              final long epoch);

  /// A key with no reward yields an all-zero entry; see [InflationReward#parse].
  CompletableFuture<List<InflationReward>> getInflationReward(final Commitment commitment,
                                                              final SequencedCollection<PublicKey> keys,
                                                              final long epoch);

  /// A key with no reward yields an all-zero entry; see [InflationReward#parse].
  CompletableFuture<List<InflationReward>> getInflationReward(final Commitment commitment,
                                                              final SequencedCollection<PublicKey> keys,
                                                              final BigInteger minContextSlot);

  /// A key with no reward yields an all-zero entry; see [InflationReward#parse].
  CompletableFuture<List<InflationReward>> getInflationReward(final Commitment commitment,
                                                              final SequencedCollection<PublicKey> keys,
                                                              final long epoch,
                                                              final BigInteger minContextSlot);

  /// Scans every account, so RPC providers commonly disable it: expect an error response unless
  /// the node is known to serve it.
  CompletableFuture<List<AccountLamports>> getLargestAccounts();

  /// @see #getLargestAccounts()
  CompletableFuture<List<AccountLamports>> getLargestAccounts(final Commitment commitment);

  /// @see #getLargestAccounts()
  CompletableFuture<List<AccountLamports>> getLargestAccounts(final LargestAccountsFilter filter);

  /// @see #getLargestAccounts()
  CompletableFuture<List<AccountLamports>> getLargestAccounts(final Commitment commitment,
                                                              final LargestAccountsFilter filter);

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

  /// Returns the accounts that exist, in key order: absent accounts are **omitted**, unlike
  /// the node's response, which holds a null in their place, so later entries shift and
  /// indices no longer match `keys`.
  ///
  /// Dispatch on each entry's [AccountInfo#pubKey()]. To correlate by index, use
  /// [#getAccounts(SequencedCollection, BiFunction)], which sends the same request but keeps a
  /// null entry for each absent account.
  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final SequencedCollection<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  /// Absent accounts are omitted; see [#getMultipleAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final Commitment commitment,
                                                                  final SequencedCollection<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  /// Absent accounts are omitted; see [#getMultipleAccounts(SequencedCollection, BiFunction)].
  default CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final SequencedCollection<PublicKey> keys) {
    return getMultipleAccounts(keys, BYTES_IDENTITY);
  }

  /// Absent accounts are omitted; see [#getMultipleAccounts(SequencedCollection, BiFunction)].
  default CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final Commitment commitment,
                                                                           final SequencedCollection<PublicKey> keys) {
    return getMultipleAccounts(commitment, keys, BYTES_IDENTITY);
  }

  /// Absent accounts are omitted; see [#getMultipleAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final int length,
                                                                   final int offset,
                                                                   final SequencedCollection<PublicKey> keys);

  /// Absent accounts are omitted; see [#getMultipleAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final BigInteger minContextSlot,
                                                                   final SequencedCollection<PublicKey> keys);

  /// Absent accounts are omitted; see [#getMultipleAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final BigInteger minContextSlot,
                                                                   final int length,
                                                                   final int offset,
                                                                   final SequencedCollection<PublicKey> keys);

  /// Absent accounts are omitted; see [#getMultipleAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final Commitment commitment,
                                                                   final BigInteger minContextSlot,
                                                                   final SequencedCollection<PublicKey> keys);

  /// Absent accounts are omitted; see [#getMultipleAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final Commitment commitment,
                                                                   final int length,
                                                                   final int offset,
                                                                   final SequencedCollection<PublicKey> keys);

  /// Absent accounts are omitted; see [#getMultipleAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final Commitment commitment,
                                                                   final BigInteger minContextSlot,
                                                                   final int length,
                                                                   final int offset,
                                                                   final SequencedCollection<PublicKey> keys);

  /// Absent accounts are omitted; see [#getMultipleAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final int length,
                                                                  final int offset,
                                                                  final SequencedCollection<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  /// Absent accounts are omitted; see [#getMultipleAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final BigInteger minContextSlot,
                                                                  final SequencedCollection<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  /// Absent accounts are omitted; see [#getMultipleAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final BigInteger minContextSlot,
                                                                  final int length,
                                                                  final int offset,
                                                                  final SequencedCollection<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  /// Absent accounts are omitted; see [#getMultipleAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final Commitment commitment,
                                                                  final int length,
                                                                  final int offset,
                                                                  final SequencedCollection<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  /// Absent accounts are omitted; see [#getMultipleAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final Commitment commitment,
                                                                  final BigInteger minContextSlot,
                                                                  final SequencedCollection<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  /// Absent accounts are omitted; see [#getMultipleAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final Commitment commitment,
                                                                  final BigInteger minContextSlot,
                                                                  final int length,
                                                                  final int offset,
                                                                  final SequencedCollection<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  /// Returns one entry per key, in key order, null where the account does not exist, as in the
  /// node's `getMultipleAccounts` response.
  ///
  /// Use this rather than [#getMultipleAccounts(SequencedCollection, BiFunction)], which sends
  /// the same request but omits absent accounts, whenever results are matched to keys by index.
  <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final SequencedCollection<PublicKey> keys,
                                                          final BiFunction<PublicKey, byte[], T> factory);

  /// One entry per key, null where absent; see [#getAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final Commitment commitment,
                                                          final SequencedCollection<PublicKey> keys,
                                                          final BiFunction<PublicKey, byte[], T> factory);

  /// One entry per key, null where absent; see [#getAccounts(SequencedCollection, BiFunction)].
  default CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final SequencedCollection<PublicKey> keys) {
    return getAccounts(keys, BYTES_IDENTITY);
  }

  /// One entry per key, null where absent; see [#getAccounts(SequencedCollection, BiFunction)].
  default CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final Commitment commitment,
                                                                   final SequencedCollection<PublicKey> keys) {
    return getAccounts(commitment, keys, BYTES_IDENTITY);
  }

  /// One entry per key, null where absent; see [#getAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final int length,
                                                           final int offset,
                                                           final SequencedCollection<PublicKey> keys);

  /// One entry per key, null where absent; see [#getAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final BigInteger minContextSlot,
                                                           final SequencedCollection<PublicKey> keys);

  /// One entry per key, null where absent; see [#getAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final BigInteger minContextSlot,
                                                           final int length,
                                                           final int offset,
                                                           final SequencedCollection<PublicKey> keys);

  /// One entry per key, null where absent; see [#getAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final Commitment commitment,
                                                           final BigInteger minContextSlot,
                                                           final SequencedCollection<PublicKey> keys);

  /// One entry per key, null where absent; see [#getAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final Commitment commitment,
                                                           final int length,
                                                           final int offset,
                                                           final SequencedCollection<PublicKey> keys);

  /// One entry per key, null where absent; see [#getAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final Commitment commitment,
                                                           final BigInteger minContextSlot,
                                                           final int length,
                                                           final int offset,
                                                           final SequencedCollection<PublicKey> keys);

  /// One entry per key, null where absent; see [#getAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final int length,
                                                          final int offset,
                                                          final SequencedCollection<PublicKey> keys,
                                                          final BiFunction<PublicKey, byte[], T> factory);

  /// One entry per key, null where absent; see [#getAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final BigInteger minContextSlot,
                                                          final SequencedCollection<PublicKey> keys,
                                                          final BiFunction<PublicKey, byte[], T> factory);

  /// One entry per key, null where absent; see [#getAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final BigInteger minContextSlot,
                                                          final int length,
                                                          final int offset,
                                                          final SequencedCollection<PublicKey> keys,
                                                          final BiFunction<PublicKey, byte[], T> factory);

  /// One entry per key, null where absent; see [#getAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final Commitment commitment,
                                                          final int length,
                                                          final int offset,
                                                          final SequencedCollection<PublicKey> keys,
                                                          final BiFunction<PublicKey, byte[], T> factory);

  /// One entry per key, null where absent; see [#getAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final Commitment commitment,
                                                          final BigInteger minContextSlot,
                                                          final SequencedCollection<PublicKey> keys,
                                                          final BiFunction<PublicKey, byte[], T> factory);

  /// One entry per key, null where absent; see [#getAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final Commitment commitment,
                                                          final BigInteger minContextSlot,
                                                          final int length,
                                                          final int offset,
                                                          final SequencedCollection<PublicKey> keys,
                                                          final BiFunction<PublicKey, byte[], T> factory);

  default CompletableFuture<List<AccountInfo<byte[]>>> getProgramAccounts(final PublicKey programId) {
    return getProgramAccounts(programId, BYTES_IDENTITY);
  }

  default CompletableFuture<List<AccountInfo<byte[]>>> getProgramAccounts(final PublicKey programId,
                                                                          final Collection<Filter> filters) {
    return getProgramAccounts(programId, filters, BYTES_IDENTITY);
  }

  default CompletableFuture<List<AccountInfo<byte[]>>> getProgramAccounts(final PublicKey programId,
                                                                          final Commitment commitment,
                                                                          final Collection<Filter> filters) {
    return getProgramAccounts(programId, commitment, filters, BYTES_IDENTITY);
  }

  default <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final PublicKey programId,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, factory);
  }

  default <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final PublicKey programId,
                                                                         final Collection<Filter> filters,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, filters, factory);
  }

  default <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final PublicKey programId,
                                                                         final Commitment commitment,
                                                                         final Collection<Filter> filters,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, commitment, filters, factory);
  }

  <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                 final PublicKey programId,
                                                                 final BiFunction<PublicKey, byte[], T> factory);

  <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                 final PublicKey programId,
                                                                 final Collection<Filter> filters,
                                                                 final BiFunction<PublicKey, byte[], T> factory);

  <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                 final PublicKey programId,
                                                                 final Commitment commitment,
                                                                 final Collection<Filter> filters,
                                                                 final BiFunction<PublicKey, byte[], T> factory);

  /// @param minContextSlot unsigned; **`0` means unset** and omits the field, so the node applies
  ///                       no minimum. Use a [BigInteger] overload to send an explicit `0`.
  <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                 final PublicKey programId,
                                                                 final Commitment commitment,
                                                                 final long minContextSlot,
                                                                 final Collection<Filter> filters,
                                                                 final BiFunction<PublicKey, byte[], T> factory);

  <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                 final PublicKey programId,
                                                                 final Commitment commitment,
                                                                 final Collection<Filter> filters,
                                                                 final int length,
                                                                 final int offset,
                                                                 final BiFunction<PublicKey, byte[], T> factory);

  <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                 final PublicKey programId,
                                                                 final Commitment commitment,
                                                                 final long minContextSlot,
                                                                 final Collection<Filter> filters,
                                                                 final int length,
                                                                 final int offset,
                                                                 final BiFunction<PublicKey, byte[], T> factory);

  default CompletableFuture<List<AccountInfo<byte[]>>> getProgramAccounts(final Duration requestTimeout,
                                                                          final PublicKey programId,
                                                                          final Commitment commitment,
                                                                          final long minContextSlot,
                                                                          final Collection<Filter> filters) {
    return getProgramAccounts(requestTimeout, programId, commitment, minContextSlot, filters, BYTES_IDENTITY);
  }

  default CompletableFuture<List<AccountInfo<byte[]>>> getProgramAccounts(final Duration requestTimeout,
                                                                          final PublicKey programId,
                                                                          final Commitment commitment,
                                                                          final Collection<Filter> filters,
                                                                          final int length,
                                                                          final int offset) {
    return getProgramAccounts(requestTimeout, programId, commitment, filters, length, offset, BYTES_IDENTITY);
  }

  default CompletableFuture<List<AccountInfo<byte[]>>> getProgramAccounts(final Duration requestTimeout,
                                                                          final PublicKey programId,
                                                                          final Commitment commitment,
                                                                          final long minContextSlot,
                                                                          final Collection<Filter> filters,
                                                                          final int length,
                                                                          final int offset) {
    return getProgramAccounts(requestTimeout, programId, commitment, minContextSlot, filters, length, offset, BYTES_IDENTITY);
  }

  default <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final PublicKey programId,
                                                                         final long minContextSlot,
                                                                         final Collection<Filter> filters,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, defaultCommitment(), minContextSlot, filters, factory);
  }

  default <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final PublicKey programId,
                                                                         final Collection<Filter> filters,
                                                                         final int length,
                                                                         final int offset,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, defaultCommitment(), filters, length, offset, factory);
  }

  default <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final PublicKey programId,
                                                                         final long minContextSlot,
                                                                         final Collection<Filter> filters,
                                                                         final int length,
                                                                         final int offset,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, defaultCommitment(), minContextSlot, filters, length, offset, factory);
  }

  default CompletableFuture<List<AccountInfo<byte[]>>> getProgramAccounts(final PublicKey programId,
                                                                          final long minContextSlot,
                                                                          final Collection<Filter> filters) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, defaultCommitment(), minContextSlot, filters, BYTES_IDENTITY);
  }

  default CompletableFuture<List<AccountInfo<byte[]>>> getProgramAccounts(final PublicKey programId,
                                                                          final Collection<Filter> filters,
                                                                          final int length,
                                                                          final int offset) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, defaultCommitment(), filters, length, offset, BYTES_IDENTITY);
  }

  default CompletableFuture<List<AccountInfo<byte[]>>> getProgramAccounts(final PublicKey programId,
                                                                          final long minContextSlot,
                                                                          final Collection<Filter> filters,
                                                                          final int length,
                                                                          final int offset) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, defaultCommitment(), minContextSlot, filters, length, offset, BYTES_IDENTITY);
  }

  default <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final PublicKey programId,
                                                                         final Commitment commitment,
                                                                         final long minContextSlot,
                                                                         final Collection<Filter> filters,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, commitment, minContextSlot, filters, factory);
  }

  default <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final PublicKey programId,
                                                                         final Commitment commitment,
                                                                         final Collection<Filter> filters,
                                                                         final int length,
                                                                         final int offset,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, commitment, filters, length, offset, factory);
  }

  default <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final PublicKey programId,
                                                                         final Commitment commitment,
                                                                         final long minContextSlot,
                                                                         final Collection<Filter> filters,
                                                                         final int length,
                                                                         final int offset,
                                                                         final BiFunction<PublicKey, byte[], T> factory) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, commitment, minContextSlot, filters, length, offset, factory);
  }


  default CompletableFuture<List<AccountInfo<byte[]>>> getProgramAccounts(final PublicKey programId,
                                                                          final Commitment commitment,
                                                                          final long minContextSlot,
                                                                          final Collection<Filter> filters) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, commitment, minContextSlot, filters, BYTES_IDENTITY);
  }

  default CompletableFuture<List<AccountInfo<byte[]>>> getProgramAccounts(final PublicKey programId,
                                                                          final Commitment commitment,
                                                                          final Collection<Filter> filters,
                                                                          final int length,
                                                                          final int offset) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, commitment, filters, length, offset, BYTES_IDENTITY);
  }

  default CompletableFuture<List<AccountInfo<byte[]>>> getProgramAccounts(final PublicKey programId,
                                                                          final Commitment commitment,
                                                                          final long minContextSlot,
                                                                          final Collection<Filter> filters,
                                                                          final int length,
                                                                          final int offset) {
    return getProgramAccounts(PROGRAM_ACCOUNTS_TIMEOUT, programId, commitment, minContextSlot, filters, length, offset, BYTES_IDENTITY);
  }

  <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                 final PublicKey programId,
                                                                 final Commitment commitment,
                                                                 final BigInteger minContextSlot,
                                                                 final Collection<Filter> filters,
                                                                 final BiFunction<PublicKey, byte[], T> factory);

  <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final Duration requestTimeout,
                                                                 final PublicKey programId,
                                                                 final Commitment commitment,
                                                                 final BigInteger minContextSlot,
                                                                 final Collection<Filter> filters,
                                                                 final int length,
                                                                 final int offset,
                                                                 final BiFunction<PublicKey, byte[], T> factory);

  /// @throws IllegalStateException if [ProgramAccountsRequest#encoding()] is `base64_zstd` and
  ///                               [ProgramAccountsRequest#zstdDecompressor()] is `null`.
  <T> CompletableFuture<List<AccountInfo<T>>> getProgramAccounts(final ProgramAccountsRequest<T> request);

  CompletableFuture<List<PerfSample>> getRecentPerformanceSamples(final int limit);

  default CompletableFuture<List<PerfSample>> getRecentPerformanceSamples() {
    return getRecentPerformanceSamples(720);
  }

  default CompletableFuture<List<PrioritizationFee>> getRecentPrioritizationFees() {
    return getRecentPrioritizationFees(null);
  }

  CompletableFuture<List<PrioritizationFee>> getRecentPrioritizationFees(final SequencedCollection<PublicKey> writablePublicKeys);

  CompletableFuture<List<TxSig>> getSignaturesForAddress(final PublicKey address, final int limit);

  CompletableFuture<List<TxSig>> getSignaturesForAddress(final Commitment commitment,
                                                         final PublicKey address,
                                                         final int limit);

  CompletableFuture<List<TxSig>> getSignaturesForAddressBefore(final PublicKey address,
                                                               final int limit,
                                                               final String beforeTxSig);

  CompletableFuture<List<TxSig>> getSignaturesForAddressBefore(final Commitment commitment,
                                                               final PublicKey address,
                                                               final int limit,
                                                               final String beforeTxSig);

  CompletableFuture<List<TxSig>> getSignaturesForAddressUntil(final PublicKey address,
                                                              final int limit,
                                                              final String untilTxSig);

  CompletableFuture<List<TxSig>> getSignaturesForAddressUntil(final Commitment commitment,
                                                              final PublicKey address,
                                                              final int limit,
                                                              final String untilTxSig);

  CompletableFuture<List<TxSig>> getSignaturesForAddress(final Commitment commitment,
                                                         final PublicKey address,
                                                         final int limit,
                                                         final String beforeTxSig,
                                                         final String untilTxSig,
                                                         final BigInteger minContextSlot);

  /// Unknown signatures map to a nil [TxStatus]; see
  /// [#getSignatureStatuses(SequencedCollection, boolean)].
  default CompletableFuture<Map<String, TxStatus>> getSignatureStatuses(final SequencedCollection<String> signatures) {
    return getSignatureStatuses(signatures, false);
  }

  /// An unknown signature maps to a [TxStatus] whose [TxStatus#nil()] is `true`, never to `null`.
  CompletableFuture<Map<String, TxStatus>> getSignatureStatuses(final SequencedCollection<String> signatures,
                                                                final boolean searchTransactionHistory);

  default CompletableFuture<List<TxStatus>> getSigStatusList(final SequencedCollection<String> signatures) {
    return getSigStatusList(signatures, false);
  }

  CompletableFuture<List<TxStatus>> getSigStatusList(final SequencedCollection<String> signatures,
                                                     final boolean searchTransactionHistory);

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

  CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForTokenMintByDelegate(final PublicKey delegate,
                                                                                            final PublicKey tokenMint);

  CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForTokenMintByDelegate(final Commitment commitment,
                                                                                            final PublicKey delegate,
                                                                                            final PublicKey tokenMint);

  CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForProgramByDelegate(final PublicKey delegate,
                                                                                          final PublicKey programId);

  CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForProgramByDelegate(final Commitment commitment,
                                                                                          final PublicKey delegate,
                                                                                          final PublicKey programId);

  CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForTokenMintByOwner(final PublicKey owner,
                                                                                         final PublicKey tokenMint);

  CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForTokenMintByOwner(final Commitment commitment,
                                                                                         final PublicKey owner,
                                                                                         final PublicKey tokenMint);

  CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForProgramByOwner(final PublicKey owner,
                                                                                       final PublicKey programId);

  CompletableFuture<List<AccountInfo<TokenAccount>>> getTokenAccountsForProgramByOwner(final Commitment commitment,
                                                                                       final PublicKey owner,
                                                                                       final PublicKey programId);

  CompletableFuture<List<AccountTokenAmount>> getTokenLargestAccounts(final PublicKey tokenMint);

  CompletableFuture<List<AccountTokenAmount>> getTokenLargestAccounts(final Commitment commitment,
                                                                      final PublicKey tokenMint);

  CompletableFuture<TokenAmount> getTokenSupply(final PublicKey tokenMintAccount);

  CompletableFuture<TokenAmount> getTokenSupply(final Commitment commitment, final PublicKey tokenMintAccount);

  CompletableFuture<Tx> getTransaction(final String txSignature);

  CompletableFuture<Tx> getTransaction(final Commitment commitment, final String txSignature);

  CompletableFuture<Long> getTransactionCount();

  CompletableFuture<Long> getTransactionCount(final Commitment commitment);

  CompletableFuture<Version> getVersion();

  CompletableFuture<VoteAccounts> getVoteAccounts();

  CompletableFuture<VoteAccounts> getVoteAccounts(final Commitment commitment);

  CompletableFuture<VoteAccounts> getVoteAccounts(final PublicKey validatorVoteAddress);

  CompletableFuture<VoteAccounts> getVoteAccounts(final Commitment commitment, final PublicKey validatorVoteAddress);

  CompletableFuture<VoteAccounts> getVoteAccounts(final Commitment commitment,
                                                  final PublicKey validatorVoteAddress,
                                                  final boolean keepUnstakedDelinquents,
                                                  final BigInteger delinquentSlotDistance);

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

  /// Sets the blockhash and signs **positionally** before submitting: the n-th signer writes the
  /// n-th signature slot whatever its public key. To sign by key, submit the result of
  /// [Transaction#signByKeyAndBase64Encode(byte\[\], Collection)] with [#sendTransaction(String)].
  CompletableFuture<String> sendTransaction(final Transaction transaction,
                                            final SequencedCollection<Signer> signers,
                                            final byte[] recentBlockHash);

  /// Signs **positionally**, as [#sendTransaction(Transaction, SequencedCollection, byte\[\])].
  CompletableFuture<String> sendTransaction(final Commitment preflightCommitment,
                                            final Transaction transaction,
                                            final SequencedCollection<Signer> signers,
                                            final byte[] recentBlockHash);

  /// Submits with preflight at the client's default commitment and `maxRetries` of `1`.
  ///
  /// Every overload sends `maxRetries`, so the node's behaviour when it is absent (retry until
  /// finalized or the blockhash expires) is unreachable.
  /// [#sendTransactionSkipPreflight(String)] defaults it to `0` instead.
  default CompletableFuture<String> sendTransaction(final String base64SignedTx) {
    return sendTransaction(base64SignedTx, 1);
  }

  /// @param maxRetries node-side re-sends to the leader; `0` sends once and stops, it is not unset.
  CompletableFuture<String> sendTransaction(final String base64SignedTx, final int maxRetries);

  default CompletableFuture<String> sendTransaction(final Commitment preflightCommitment, final String base64SignedTx) {
    return sendTransaction(preflightCommitment, base64SignedTx, 1);
  }

  CompletableFuture<String> sendTransaction(final Commitment preflightCommitment,
                                            final String base64SignedTx,
                                            final int maxRetries);

  /// Submits without preflight, with `maxRetries` of `0` and preflight commitment
  /// [Commitment#PROCESSED].
  ///
  /// The `0` suits a caller that re-broadcasts until the signature confirms, where node-side
  /// retries would duplicate that work; other callers should pass an explicit `maxRetries`.
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

  /// Routes to [#sendTransactionSkipPreflight(String)] or [#sendTransaction(String)], so
  /// `skipPreFlight` also picks the `maxRetries` default (`0` when skipping, `1` otherwise) and
  /// the preflight commitment ([Commitment#PROCESSED] when skipping, the client default
  /// otherwise). Use [#sendTransaction(String, boolean, int)] to pin `maxRetries`.
  default CompletableFuture<String> sendTransaction(final String base64SignedTx, final boolean skipPreFlight) {
    return skipPreFlight
        ? sendTransactionSkipPreflight(base64SignedTx)
        : sendTransaction(base64SignedTx);
  }

  /// Like [#sendTransaction(String, boolean)] with `maxRetries` pinned, so the flag affects only
  /// preflight and the preflight commitment.
  default CompletableFuture<String> sendTransaction(final String base64SignedTx,
                                                    final boolean skipPreFlight,
                                                    final int maxRetries) {
    return skipPreFlight
        ? sendTransactionSkipPreflight(base64SignedTx, maxRetries)
        : sendTransaction(base64SignedTx, maxRetries);
  }

  /// As [#sendTransaction(String, boolean)], `skipPreFlight` also picks the `maxRetries` default:
  /// `0` when skipping preflight, `1` otherwise.
  default CompletableFuture<String> sendTransaction(final Commitment preflightCommitment,
                                                    final String base64SignedTx,
                                                    final boolean skipPreFlight) {
    return skipPreFlight
        ? sendTransactionSkipPreflight(preflightCommitment, base64SignedTx)
        : sendTransaction(preflightCommitment, base64SignedTx);
  }

  default CompletableFuture<String> sendTransaction(final Commitment preflightCommitment,
                                                    final String base64SignedTx,
                                                    final boolean skipPreFlight,
                                                    final int maxRetries) {
    return skipPreFlight
        ? sendTransactionSkipPreflight(preflightCommitment, base64SignedTx, maxRetries)
        : sendTransaction(preflightCommitment, base64SignedTx, maxRetries);
  }

  /// Simulates with `replaceRecentBlockhash` `true`, so an expired blockhash does not fail it.
  CompletableFuture<TxSimulation> simulateTransaction(final Transaction transaction);

  CompletableFuture<TxSimulation> simulateTransaction(final Transaction transaction,
                                                      final boolean replaceRecentBlockhash);

  CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                      final Transaction transaction,
                                                      final boolean replaceRecentBlockhash);

  /// Simulates with `replaceRecentBlockhash` `true`, so an expired blockhash does not fail it.
  CompletableFuture<TxSimulation> simulateTransaction(final String base64EncodedTx);

  CompletableFuture<TxSimulation> simulateTransaction(final String base64EncodedTx,
                                                      final boolean replaceRecentBlockhash);

  CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                      final String base64EncodedTx,
                                                      final boolean replaceRecentBlockhash);

  CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                      final String base64EncodedTx,
                                                      final boolean replaceRecentBlockhash,
                                                      final boolean innerInstructions);

  /// Simulates with `replaceRecentBlockhash` `true`.
  CompletableFuture<TxSimulation> simulateTransactionWithInnerInstructions(final Commitment commitment,
                                                                           final Transaction transaction);

  /// Simulates with `replaceRecentBlockhash` `true`.
  CompletableFuture<TxSimulation> simulateTransactionWithInnerInstructions(final Commitment commitment,
                                                                           final String base64EncodedTx);

  /// Simulates with `replaceRecentBlockhash` `true`.
  CompletableFuture<TxSimulation> simulateTransactionWithInnerInstructions(final Transaction transaction);

  /// Simulates with `replaceRecentBlockhash` `true`.
  CompletableFuture<TxSimulation> simulateTransactionWithInnerInstructions(final String base64EncodedTx);

  CompletableFuture<TxSimulation> simulateTransaction(final Transaction transaction,
                                                      final boolean replaceRecentBlockhash,
                                                      final boolean innerInstructions);

  CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                      final Transaction transaction,
                                                      final boolean replaceRecentBlockhash,
                                                      final boolean innerInstructions);

  CompletableFuture<TxSimulation> simulateTransaction(final String base64EncodedTx,
                                                      final boolean replaceRecentBlockhash,
                                                      final boolean innerInstructions);

  CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                      final String base64EncodedTx,
                                                      final boolean replaceRecentBlockhash,
                                                      final boolean innerInstructions,
                                                      final SequencedCollection<PublicKey> accounts);
}
