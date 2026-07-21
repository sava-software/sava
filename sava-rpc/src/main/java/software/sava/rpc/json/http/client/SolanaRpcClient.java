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
    return new SolanaJsonRpcClient(endpoint, httpClient, requestTimeout, null, null, defaultCommitment);
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

  <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final PublicKey account,
                                                       final BiFunction<PublicKey, byte[], T> factory);

  <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final Commitment commitment,
                                                       final PublicKey account,
                                                       final BiFunction<PublicKey, byte[], T> factory);

  default CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final PublicKey account) {
    return getAccountInfo(account, BYTES_IDENTITY);
  }

  default CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final Commitment commitment, final PublicKey account) {
    return getAccountInfo(commitment, account, BYTES_IDENTITY);
  }

  CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final BigInteger minContextSlot,
                                                        final PublicKey account);

  CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final int length,
                                                        final int offset,
                                                        final PublicKey account);

  CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final Commitment commitment,
                                                        final BigInteger minContextSlot,
                                                        final PublicKey account);

  CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final Commitment commitment,
                                                        final int length,
                                                        final int offset,
                                                        final PublicKey account);

  CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final BigInteger minContextSlot,
                                                        final int length,
                                                        final int offset,
                                                        final PublicKey account);

  CompletableFuture<AccountInfo<byte[]>> getAccountInfo(final Commitment commitment,
                                                        final BigInteger minContextSlot,
                                                        final int length,
                                                        final int offset,
                                                        final PublicKey account);

  <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final BigInteger minContextSlot,
                                                       final PublicKey account,
                                                       final BiFunction<PublicKey, byte[], T> factory);

  <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final int length,
                                                       final int offset,
                                                       final PublicKey account,
                                                       final BiFunction<PublicKey, byte[], T> factory);

  <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final Commitment commitment,
                                                       final BigInteger minContextSlot,
                                                       final PublicKey account,
                                                       final BiFunction<PublicKey, byte[], T> factory);

  <T> CompletableFuture<AccountInfo<T>> getAccountInfo(final Commitment commitment,
                                                       final int length,
                                                       final int offset,
                                                       final PublicKey account,
                                                       final BiFunction<PublicKey, byte[], T> factory);

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

  CompletableFuture<List<InflationReward>> getInflationReward(final SequencedCollection<PublicKey> keys);

  CompletableFuture<List<InflationReward>> getInflationReward(final Commitment commitment,
                                                              final SequencedCollection<PublicKey> keys);

  CompletableFuture<List<InflationReward>> getInflationReward(final SequencedCollection<PublicKey> keys,
                                                              final long epoch);

  CompletableFuture<List<InflationReward>> getInflationReward(final Commitment commitment,
                                                              final SequencedCollection<PublicKey> keys,
                                                              final long epoch);

  CompletableFuture<List<InflationReward>> getInflationReward(final Commitment commitment,
                                                              final SequencedCollection<PublicKey> keys,
                                                              final BigInteger minContextSlot);

  CompletableFuture<List<InflationReward>> getInflationReward(final Commitment commitment,
                                                              final SequencedCollection<PublicKey> keys,
                                                              final long epoch,
                                                              final BigInteger minContextSlot);

  /// Requires a scan over all accounts, which is expensive enough that RPC providers commonly
  /// disable this method. Expect an error response unless the node is known to serve it.
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

  /// Accounts which do not exist are **omitted** from the returned List rather than
  /// occupying their slot, so the result is shorter than `keys` and every entry after
  /// a missing account shifts down by one. This does not match the Solana RPC
  /// response one to one — the node returns a null in place of each missing account.
  ///
  /// That contract is the right fit when absence does not matter: each returned
  /// account carries its own [AccountInfo#pubKey()], so a caller dispatching on the
  /// entries themselves — switching on owner, key, or contents — never sees a gap.
  /// It is the wrong fit for indexed correlation: zipping the result back against
  /// `keys` by index attributes data to the wrong account, silently. For that, use
  /// [#getAccounts(SequencedCollection, BiFunction)], which keeps a null in each
  /// absent account's slot and stays aligned with `keys`.
  ///
  /// Every `getMultipleAccounts` overload behaves this way, not just this one; both
  /// families send an identical `getMultipleAccounts` request and differ only in how
  /// the response is parsed.
  ///
  /// @return the accounts which exist, in key order, with absent accounts omitted.
  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final SequencedCollection<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  /// Absent accounts are omitted — see
  /// [#getMultipleAccounts(SequencedCollection, BiFunction)] for when that fits;
  /// [#getAccounts(Commitment, SequencedCollection, BiFunction)] keeps the result aligned with `keys`.
  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final Commitment commitment,
                                                                  final SequencedCollection<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  /// Absent accounts are omitted rather than nulled — see
  /// [#getMultipleAccounts(SequencedCollection, BiFunction)] for when that fits;
  /// [#getAccounts(SequencedCollection)] stays aligned with `keys`.
  default CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final SequencedCollection<PublicKey> keys) {
    return getMultipleAccounts(keys, BYTES_IDENTITY);
  }

  /// Absent accounts are omitted — see
  /// [#getMultipleAccounts(SequencedCollection, BiFunction)] for when that fits;
  /// [#getAccounts(Commitment, SequencedCollection)] keeps the result aligned with `keys`.
  default CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final Commitment commitment,
                                                                           final SequencedCollection<PublicKey> keys) {
    return getMultipleAccounts(commitment, keys, BYTES_IDENTITY);
  }

  /// Absent accounts are omitted — see
  /// [#getMultipleAccounts(SequencedCollection, BiFunction)] for when that fits;
  /// [#getAccounts(int, int, SequencedCollection)] keeps the result aligned with `keys`.
  CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final int length,
                                                                   final int offset,
                                                                   final SequencedCollection<PublicKey> keys);

  /// Absent accounts are omitted — see
  /// [#getMultipleAccounts(SequencedCollection, BiFunction)] for when that fits;
  /// [#getAccounts(BigInteger, SequencedCollection)] keeps the result aligned with `keys`.
  CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final BigInteger minContextSlot,
                                                                   final SequencedCollection<PublicKey> keys);

  /// Absent accounts are omitted — see
  /// [#getMultipleAccounts(SequencedCollection, BiFunction)] for when that fits;
  /// [#getAccounts(BigInteger, int, int, SequencedCollection)] keeps the result aligned with `keys`.
  CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final BigInteger minContextSlot,
                                                                   final int length,
                                                                   final int offset,
                                                                   final SequencedCollection<PublicKey> keys);

  /// Absent accounts are omitted — see
  /// [#getMultipleAccounts(SequencedCollection, BiFunction)] for when that fits;
  /// [#getAccounts(Commitment, BigInteger, SequencedCollection)] keeps the result aligned with `keys`.
  CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final Commitment commitment,
                                                                   final BigInteger minContextSlot,
                                                                   final SequencedCollection<PublicKey> keys);

  /// Absent accounts are omitted — see
  /// [#getMultipleAccounts(SequencedCollection, BiFunction)] for when that fits;
  /// [#getAccounts(Commitment, int, int, SequencedCollection)] keeps the result aligned with `keys`.
  CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final Commitment commitment,
                                                                   final int length,
                                                                   final int offset,
                                                                   final SequencedCollection<PublicKey> keys);

  /// Absent accounts are omitted — see
  /// [#getMultipleAccounts(SequencedCollection, BiFunction)] for when that fits;
  /// [#getAccounts(Commitment, BigInteger, int, int, SequencedCollection)] keeps the result aligned with `keys`.
  CompletableFuture<List<AccountInfo<byte[]>>> getMultipleAccounts(final Commitment commitment,
                                                                   final BigInteger minContextSlot,
                                                                   final int length,
                                                                   final int offset,
                                                                   final SequencedCollection<PublicKey> keys);

  /// Absent accounts are omitted — see
  /// [#getMultipleAccounts(SequencedCollection, BiFunction)] for when that fits;
  /// [#getAccounts(int, int, SequencedCollection, BiFunction)] keeps the result aligned with `keys`.
  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final int length,
                                                                  final int offset,
                                                                  final SequencedCollection<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  /// Absent accounts are omitted — see
  /// [#getMultipleAccounts(SequencedCollection, BiFunction)] for when that fits;
  /// [#getAccounts(BigInteger, SequencedCollection, BiFunction)] keeps the result aligned with `keys`.
  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final BigInteger minContextSlot,
                                                                  final SequencedCollection<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  /// Absent accounts are omitted — see
  /// [#getMultipleAccounts(SequencedCollection, BiFunction)] for when that fits;
  /// [#getAccounts(BigInteger, int, int, SequencedCollection, BiFunction)] keeps the result aligned with `keys`.
  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final BigInteger minContextSlot,
                                                                  final int length,
                                                                  final int offset,
                                                                  final SequencedCollection<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  /// Absent accounts are omitted — see
  /// [#getMultipleAccounts(SequencedCollection, BiFunction)] for when that fits;
  /// [#getAccounts(Commitment, int, int, SequencedCollection, BiFunction)] keeps the result aligned with `keys`.
  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final Commitment commitment,
                                                                  final int length,
                                                                  final int offset,
                                                                  final SequencedCollection<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  /// Absent accounts are omitted — see
  /// [#getMultipleAccounts(SequencedCollection, BiFunction)] for when that fits;
  /// [#getAccounts(Commitment, BigInteger, SequencedCollection, BiFunction)] keeps the result aligned with `keys`.
  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final Commitment commitment,
                                                                  final BigInteger minContextSlot,
                                                                  final SequencedCollection<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  /// Absent accounts are omitted — see
  /// [#getMultipleAccounts(SequencedCollection, BiFunction)] for when that fits;
  /// [#getAccounts(Commitment, BigInteger, int, int, SequencedCollection, BiFunction)] keeps the result aligned with `keys`.
  <T> CompletableFuture<List<AccountInfo<T>>> getMultipleAccounts(final Commitment commitment,
                                                                  final BigInteger minContextSlot,
                                                                  final int length,
                                                                  final int offset,
                                                                  final SequencedCollection<PublicKey> keys,
                                                                  final BiFunction<PublicKey, byte[], T> factory);

  /// Fetches several accounts in one `getMultipleAccounts` request, keeping the result
  /// positionally aligned with `keys`: an account which does not exist is a null entry
  /// at its own index, matching the Solana RPC response one to one.
  ///
  /// This is the difference from [#getMultipleAccounts(SequencedCollection, BiFunction)],
  /// which sends the identical request but drops absent accounts from the List. Use
  /// this family whenever the results are correlated back to the keys by index.
  ///
  /// @return one entry per key, in key order, null where the account does not exist.
  <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final SequencedCollection<PublicKey> keys,
                                                          final BiFunction<PublicKey, byte[], T> factory);

  /// Result aligned with `keys`, null where an account does not exist — see
  /// [#getAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final Commitment commitment,
                                                          final SequencedCollection<PublicKey> keys,
                                                          final BiFunction<PublicKey, byte[], T> factory);

  /// Result aligned with `keys`, null where an account does not exist — see
  /// [#getAccounts(SequencedCollection, BiFunction)].
  default CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final SequencedCollection<PublicKey> keys) {
    return getAccounts(keys, BYTES_IDENTITY);
  }

  /// Result aligned with `keys`, null where an account does not exist — see
  /// [#getAccounts(SequencedCollection, BiFunction)].
  default CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final Commitment commitment,
                                                                   final SequencedCollection<PublicKey> keys) {
    return getAccounts(commitment, keys, BYTES_IDENTITY);
  }

  /// Result aligned with `keys`, null where an account does not exist — see
  /// [#getAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final int length,
                                                           final int offset,
                                                           final SequencedCollection<PublicKey> keys);

  /// Result aligned with `keys`, null where an account does not exist — see
  /// [#getAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final BigInteger minContextSlot,
                                                           final SequencedCollection<PublicKey> keys);

  /// Result aligned with `keys`, null where an account does not exist — see
  /// [#getAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final BigInteger minContextSlot,
                                                           final int length,
                                                           final int offset,
                                                           final SequencedCollection<PublicKey> keys);

  /// Result aligned with `keys`, null where an account does not exist — see
  /// [#getAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final Commitment commitment,
                                                           final BigInteger minContextSlot,
                                                           final SequencedCollection<PublicKey> keys);

  /// Result aligned with `keys`, null where an account does not exist — see
  /// [#getAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final Commitment commitment,
                                                           final int length,
                                                           final int offset,
                                                           final SequencedCollection<PublicKey> keys);

  /// Result aligned with `keys`, null where an account does not exist — see
  /// [#getAccounts(SequencedCollection, BiFunction)].
  CompletableFuture<List<AccountInfo<byte[]>>> getAccounts(final Commitment commitment,
                                                           final BigInteger minContextSlot,
                                                           final int length,
                                                           final int offset,
                                                           final SequencedCollection<PublicKey> keys);

  /// Result aligned with `keys`, null where an account does not exist — see
  /// [#getAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final int length,
                                                          final int offset,
                                                          final SequencedCollection<PublicKey> keys,
                                                          final BiFunction<PublicKey, byte[], T> factory);

  /// Result aligned with `keys`, null where an account does not exist — see
  /// [#getAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final BigInteger minContextSlot,
                                                          final SequencedCollection<PublicKey> keys,
                                                          final BiFunction<PublicKey, byte[], T> factory);

  /// Result aligned with `keys`, null where an account does not exist — see
  /// [#getAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final BigInteger minContextSlot,
                                                          final int length,
                                                          final int offset,
                                                          final SequencedCollection<PublicKey> keys,
                                                          final BiFunction<PublicKey, byte[], T> factory);

  /// Result aligned with `keys`, null where an account does not exist — see
  /// [#getAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final Commitment commitment,
                                                          final int length,
                                                          final int offset,
                                                          final SequencedCollection<PublicKey> keys,
                                                          final BiFunction<PublicKey, byte[], T> factory);

  /// Result aligned with `keys`, null where an account does not exist — see
  /// [#getAccounts(SequencedCollection, BiFunction)].
  <T> CompletableFuture<List<AccountInfo<T>>> getAccounts(final Commitment commitment,
                                                          final BigInteger minContextSlot,
                                                          final SequencedCollection<PublicKey> keys,
                                                          final BiFunction<PublicKey, byte[], T> factory);

  /// Result aligned with `keys`, null where an account does not exist — see
  /// [#getAccounts(SequencedCollection, BiFunction)].
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

  /// @param minContextSlot **0 means unset**, not "slot 0" — the field is omitted
  ///                       from the request entirely, so the node applies no
  ///                       minimum. Slots are u64, so a negative value is read as
  ///                       the unsigned slot (`-1` is 18446744073709551615). Use
  ///                       the [BigInteger] overloads to pass an explicit 0 or a
  ///                       slot above [Long#MAX_VALUE].
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

  default CompletableFuture<Map<String, TxStatus>> getSignatureStatuses(final SequencedCollection<String> signatures) {
    return getSignatureStatuses(signatures, false);
  }

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

  CompletableFuture<String> sendTransaction(final Transaction transaction,
                                            final SequencedCollection<Signer> signers,
                                            final byte[] recentBlockHash);

  CompletableFuture<String> sendTransaction(final Commitment preflightCommitment,
                                            final Transaction transaction,
                                            final SequencedCollection<Signer> signers,
                                            final byte[] recentBlockHash);

  /// Submits with preflight checks, asking the node to retry once.
  ///
  /// `maxRetries` is how many times the RPC node re-sends the transaction to the
  /// leader on the caller's behalf. `0` means send once and stop — it is not
  /// "unset". Every overload in this client sends the field, so the node's own
  /// behaviour when it is absent (retry until the transaction is finalized or the
  /// blockhash expires) is not reachable here; pass an explicit `maxRetries` to
  /// choose something other than the defaults.
  ///
  /// The two families default it differently on purpose — see
  /// [#sendTransactionSkipPreflight(String)].
  default CompletableFuture<String> sendTransaction(final String base64SignedTx) {
    return sendTransaction(base64SignedTx, 1);
  }

  /// @param maxRetries node-side re-sends to the leader; `0` sends once and stops.
  CompletableFuture<String> sendTransaction(final String base64SignedTx, final int maxRetries);

  default CompletableFuture<String> sendTransaction(final Commitment preflightCommitment, final String base64SignedTx) {
    return sendTransaction(preflightCommitment, base64SignedTx, 1);
  }

  CompletableFuture<String> sendTransaction(final Commitment preflightCommitment,
                                            final String base64SignedTx,
                                            final int maxRetries);

  /// Submits without preflight checks, asking the node **not** to retry.
  ///
  /// The `maxRetries` default of `0` is deliberate and differs from the
  /// preflighting family's `1`: skipping preflight is what a caller does when it
  /// is driving its own submission loop — re-signing or re-broadcasting until the
  /// signature confirms — and node-side retries would duplicate that work against
  /// the same blockhash. Callers who are not rebroadcasting themselves should pass
  /// an explicit `maxRetries`.
  ///
  /// Preflight commitment defaults to [Commitment#PROCESSED] here, since there is
  /// no simulation to run at a stronger commitment.
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

  /// Selects between the two families by flag.
  ///
  /// **`skipPreFlight` changes more than preflight.** It routes to
  /// [#sendTransactionSkipPreflight(String)] or [#sendTransaction(String)], and
  /// those differ in their `maxRetries` default as well — `true` sends with `0`
  /// node-side retries, `false` with `1`. The flag also selects the preflight
  /// commitment ([Commitment#PROCESSED] when skipping, the client default
  /// otherwise). Use the three argument overload to fix `maxRetries` across both
  /// branches.
  default CompletableFuture<String> sendTransaction(final String base64SignedTx, final boolean skipPreFlight) {
    return skipPreFlight
        ? sendTransactionSkipPreflight(base64SignedTx)
        : sendTransaction(base64SignedTx);
  }

  /// Selects between the two families by flag, with `maxRetries` pinned, so unlike
  /// [#sendTransaction(String, boolean)] the flag only affects preflight and the
  /// preflight commitment.

  default CompletableFuture<String> sendTransaction(final String base64SignedTx,
                                                    final boolean skipPreFlight,
                                                    final int maxRetries) {
    return skipPreFlight
        ? sendTransactionSkipPreflight(base64SignedTx, maxRetries)
        : sendTransaction(base64SignedTx, maxRetries);
  }

  /// As [#sendTransaction(String, boolean)], the flag also picks the `maxRetries`
  /// default — `0` when skipping preflight, `1` otherwise.
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

  CompletableFuture<TxSimulation> simulateTransaction(final Transaction transaction);

  CompletableFuture<TxSimulation> simulateTransaction(final Transaction transaction,
                                                      final boolean replaceRecentBlockhash);

  CompletableFuture<TxSimulation> simulateTransaction(final Commitment commitment,
                                                      final Transaction transaction,
                                                      final boolean replaceRecentBlockhash);

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

  CompletableFuture<TxSimulation> simulateTransactionWithInnerInstructions(final Commitment commitment,
                                                                           final Transaction transaction);

  CompletableFuture<TxSimulation> simulateTransactionWithInnerInstructions(final Commitment commitment,
                                                                           final String base64EncodedTx);

  CompletableFuture<TxSimulation> simulateTransactionWithInnerInstructions(final Transaction transaction);

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
