package software.sava.helius.demo;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.sysvar.Clock;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.core.encoding.Base58;
import software.sava.core.tx.Transaction;
import software.sava.helius.rpc.json.HeliusRpc;
import software.sava.helius.rpc.json.request.SortOrder;
import software.sava.helius.rpc.json.request.Status;
import software.sava.helius.rpc.json.request.TokenAccounts;
import software.sava.helius.rpc.json.request.TransactionsForAddressRequest;
import software.sava.helius.rpc.json.response.PagedResponse;
import software.sava.helius.rpc.json.response.TxFull;
import software.sava.rpc.json.http.request.Commitment;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;

final class BalanceFetcher {

  private static final PublicKey WRAPPED_SOL_MINT = SolanaAccounts.MAIN_NET.wrappedSolTokenMint();

  private final SolanaAccounts solanaAccounts;
  private final HeliusRpc heliusClient;

  BalanceFetcher(final SolanaAccounts solanaAccounts, final HeliusRpc heliusClient) {
    this.solanaAccounts = solanaAccounts;
    this.heliusClient = heliusClient;
  }

  PublicKey wrappedSolAta(final PublicKey account) {
    return PublicKey.findProgramAddress(List.of(
            account.toByteArray(),
            solanaAccounts.tokenProgram().toByteArray(),
            WRAPPED_SOL_MINT.toByteArray()
        ), solanaAccounts.associatedTokenAccountProgram()
    ).publicKey();
  }

  BalanceReport changeHistory(final PublicKey account, final TokenAccounts tokenAccounts) {
    final var ascRequest = TransactionsForAddressRequest.build(account)
        .limit(100)
        .status(Status.succeeded)
        .tokenAccounts(tokenAccounts)
        .sortOrder(SortOrder.asc)
        .commitment(Commitment.CONFIRMED)
        .createRequest();
    final var ascFuture = heliusClient.getTransactionsForAddress(ascRequest);

    final var descRequest = TransactionsForAddressRequest.build(account)
        .limit(100)
        .status(Status.succeeded)
        .tokenAccounts(tokenAccounts)
        .sortOrder(SortOrder.desc)
        .commitment(Commitment.CONFIRMED)
        .createRequest();
    final var descFuture = heliusClient.getTransactionsForAddress(descRequest);

    final var ascResponse = ascFuture.join();
    final var ascData = ascResponse.data();
    if (ascData.isEmpty()) {
      return null;
    }
    final var latestAscTx = ascData.getLast();
    final long latestAscSlot = latestAscTx.slot();

    final var descResponse = descFuture.join();
    final var descData = descResponse.data();
    final var earliestDescTx = descData.getLast();
    final long earliestDescSlot = earliestDescTx.slot();

    final List<StampedBalance> balanceHistory;
    if (latestAscSlot >= earliestDescSlot) {
      balanceHistory = joinBalanceRecords(account, ascData, descData);
    } else {
      final var taskQueue = new ConcurrentLinkedDeque<CompletableFuture<? extends List<StampedBalance>>>();
      createTasks(
          heliusClient,
          account,
          tokenAccounts,
          ascResponse,
          descResponse,
          taskQueue
      );

      final var balances = new ArrayList<StampedBalance>((ascData.size() + descData.size()) << 4);
      adaptBalances(account, balances, ascData);

      for (CompletableFuture<? extends List<StampedBalance>> future; ; ) {
        future = taskQueue.poll();
        if (future == null) {
          break;
        }
        balances.addAll(future.join());
      }
      adaptBalances(account, balances, descData);

      balanceHistory = balances.stream().distinct().sorted().toList();
    }
    return BalanceReport.createReport(balanceHistory);
  }

  static void createTasks(final HeliusRpc heliusClient,
                          final PublicKey account,
                          final TokenAccounts tokenAccounts,
                          final PagedResponse<List<TxFull>> ascResponse,
                          final PagedResponse<List<TxFull>> descResponse,
                          final Queue<CompletableFuture<? extends List<StampedBalance>>> queue) {
    final var ascRequest = TransactionsForAddressRequest.build(account)
        .paginationToken(ascResponse.paginationToken())
        .limit(100)
        .status(Status.succeeded)
        .tokenAccounts(tokenAccounts)
        .sortOrder(SortOrder.asc)
        .commitment(Commitment.CONFIRMED)
        .createRequest();
    final var ascFuture = heliusClient.getTransactionsForAddress(ascRequest);

    final var descRequest = TransactionsForAddressRequest.build(account)
        .paginationToken(descResponse.paginationToken())
        .limit(100)
        .status(Status.succeeded)
        .tokenAccounts(tokenAccounts)
        .sortOrder(SortOrder.desc)
        .commitment(Commitment.CONFIRMED)
        .createRequest();
    final var descFuture = heliusClient.getTransactionsForAddress(descRequest);

    final long middleSlot = (ascResponse.slot() + descResponse.slot()) >>> 1;
    if (ascResponse.slot() == middleSlot || descResponse.slot() == middleSlot) {
      final var future = ascFuture.thenCombine(
          descFuture,
          (_ascResponse, _descResponse) -> combineFutures(heliusClient, account, tokenAccounts, queue, _ascResponse, _descResponse)
      );
      queue.add(future);
    } else {
      final var middlePaginationToken = middleSlot + ":0";
      final var middleDescRequest = TransactionsForAddressRequest.build(account)
          .paginationToken(middlePaginationToken)
          .limit(100)
          .status(Status.succeeded)
          .tokenAccounts(tokenAccounts)
          .sortOrder(SortOrder.desc)
          .commitment(Commitment.CONFIRMED)
          .createRequest();
      final var middleDescFuture = heliusClient.getTransactionsForAddress(middleDescRequest);

      final var middleAscRequest = TransactionsForAddressRequest.build(account)
          .paginationToken(middlePaginationToken)
          .limit(100)
          .status(Status.succeeded)
          .tokenAccounts(tokenAccounts)
          .sortOrder(SortOrder.asc)
          .commitment(Commitment.CONFIRMED)
          .createRequest();
      final var middleAscFuture = heliusClient.getTransactionsForAddress(middleAscRequest);

      final var beforeFuture = ascFuture.thenCombine(
          middleDescFuture,
          (_ascResponse, _descResponse) -> combineFutures(heliusClient, account, tokenAccounts, queue, _ascResponse, _descResponse)
      );

      final var afterFuture = middleAscFuture.thenCombine(
          descFuture,
          (_ascResponse, _descResponse) -> combineFutures(heliusClient, account, tokenAccounts, queue, _ascResponse, _descResponse)
      );

      queue.add(beforeFuture);
      queue.add(afterFuture);
    }
  }

  private static List<StampedBalance> combineFutures(final HeliusRpc heliusClient,
                                                     final PublicKey account,
                                                     final TokenAccounts tokenAccounts,
                                                     final Queue<CompletableFuture<? extends List<StampedBalance>>> queue,
                                                     final PagedResponse<List<TxFull>> ascResponse,
                                                     final PagedResponse<List<TxFull>> descResponse) {
    final var ascData = ascResponse.data();
    final var latestAscTx = ascData.getLast();
    final long latestAscSlot = latestAscTx.slot();

    final var descData = descResponse.data();
    final var earliestDescTx = descData.getLast();
    final long earliestDescSlot = earliestDescTx.slot();

    if (latestAscSlot < earliestDescSlot) {
      createTasks(
          heliusClient,
          account,
          tokenAccounts,
          ascResponse,
          descResponse,
          queue
      );
    }

    final var balances = new ArrayList<StampedBalance>(ascData.size() + descData.size());
    adaptBalances(account, balances, ascData);
    adaptBalances(account, balances, descData);
    return balances;
  }

  private static void adaptBalances(final PublicKey account,
                                    final List<StampedBalance> balances,
                                    final List<TxFull> data) {
    for (final var tx : data) {
      final var stampedBalance = StampedBalance.createBalance(account, WRAPPED_SOL_MINT, tx);
      if (stampedBalance != null) {
        balances.add(stampedBalance);
      }
    }
  }

  static List<StampedBalance> joinBalanceRecords(final PublicKey account,
                                                 final List<TxFull> ascData,
                                                 final List<TxFull> descData) {
    final var latestAscTx = ascData.getFirst();
    final long joinAfterSlot = latestAscTx.slot();
    final int joinAfterIndex = latestAscTx.transactionIndex();
    final var reversed = descData.reversed();
    int from = 0;
    for (final var tx : reversed) {
      if (tx.slot() >= joinAfterSlot && tx.transactionIndex() > joinAfterIndex) {
        break;
      }
      ++from;
    }
    final int to = reversed.size();
    final var balances = new ArrayList<StampedBalance>(ascData.size() + (to - from));
    for (final var tx : ascData) {
      final var stampedBalance = StampedBalance.createBalance(account, WRAPPED_SOL_MINT, tx);
      if (stampedBalance != null) {
        balances.add(stampedBalance);
      }
    }
    for (; from < to; ++from) {
      final var tx = reversed.get(from);
      final var stampedBalance = StampedBalance.createBalance(account, WRAPPED_SOL_MINT, tx);
      if (stampedBalance != null) {
        balances.add(stampedBalance);
      }
    }
    return balances;
  }

  public List<StampedBalance> firstCurrentDelta(final PublicKey account) {
    final var ascRequest = TransactionsForAddressRequest.build(account)
        .limit(1)
        .status(Status.succeeded)
        .tokenAccounts(TokenAccounts.none)
        .sortOrder(SortOrder.asc)
        .commitment(Commitment.CONFIRMED)
        .createRequest();
    final var ascResponseFuture = heliusClient.getTransactionsForAddress(ascRequest);

    final var wrappedSolAta = wrappedSolAta(account);
    final var rpcClient = heliusClient.rpcClient();
    final var latestAccountsFuture = rpcClient.getAccounts(List.of(
            account, wrappedSolAta, solanaAccounts.clockSysVar()
        )
    );
    final var latestAccounts = latestAccountsFuture.join();
    final var clockAccountInfo = latestAccounts.getLast();
    final var clock = Clock.read(clockAccountInfo.pubKey(), clockAccountInfo.data());
    final int numAccounts = latestAccounts.size();
    final StampedBalance latestNativeBalance;
    if (numAccounts < 3) {
      latestNativeBalance = new StampedBalance(clock.slot(), clock.unixTimestamp(), Integer.MAX_VALUE, 0, 0);
    } else {
      final var accountInfo = latestAccounts.getFirst();
      final var tokenAccountInfo = latestAccounts.get(1);
      if (tokenAccountInfo == null) {
        latestNativeBalance = new StampedBalance(clock.slot(), clock.unixTimestamp(), Integer.MAX_VALUE, accountInfo.lamports(), 0);
      } else {
        final var tokenAccount = TokenAccount.read(tokenAccountInfo.pubKey(), tokenAccountInfo.data());
        final long wrappedSolBalance = tokenAccount.amount();
        latestNativeBalance = new StampedBalance(
            clock.slot(), clock.unixTimestamp(),
            Integer.MAX_VALUE,
            accountInfo.lamports(),
            wrappedSolBalance
        );
      }
    }

    final var ascResponse = ascResponseFuture.join();
    final var ascTransactions = ascResponse.data();
    if (ascTransactions.isEmpty()) {
      return List.of(latestNativeBalance);
    } else {
      final var firstTx = ascTransactions.getFirst();
      final var firstBalance = StampedBalance.createBalance(account, WRAPPED_SOL_MINT, firstTx);
      if (firstBalance == null) {
        throw new UnsupportedOperationException(
            "First transaction is not a balance update: " + Base58.encode(Transaction.getId(firstTx.data()))
        );
      }
      return List.of(firstBalance, latestNativeBalance);
    }
  }
}
