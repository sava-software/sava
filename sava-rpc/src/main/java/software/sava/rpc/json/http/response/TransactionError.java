package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.FieldBufferFunction;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

// See https://github.com/anza-xyz/solana-sdk/blob/master/transaction-error/src/lib.rs
public sealed interface TransactionError permits
    TransactionError.AccountInUse,
    TransactionError.AccountLoadedTwice,
    TransactionError.AccountNotFound,
    TransactionError.ProgramAccountNotFound,
    TransactionError.InsufficientFundsForFee,
    TransactionError.InvalidAccountForFee,
    TransactionError.AlreadyProcessed,
    TransactionError.BlockhashNotFound,
    TransactionError.InstructionError,
    TransactionError.CallChainTooDeep,
    TransactionError.MissingSignatureForFee,
    TransactionError.InvalidAccountIndex,
    TransactionError.SignatureFailure,
    TransactionError.InvalidProgramForExecution,
    TransactionError.SanitizeFailure,
    TransactionError.ClusterMaintenance,
    TransactionError.AccountBorrowOutstanding,
    TransactionError.WouldExceedMaxBlockCostLimit,
    TransactionError.UnsupportedVersion,
    TransactionError.InvalidWritableAccount,
    TransactionError.WouldExceedMaxAccountCostLimit,
    TransactionError.WouldExceedAccountDataBlockLimit,
    TransactionError.TooManyAccountLocks,
    TransactionError.AddressLookupTableNotFound,
    TransactionError.InvalidAddressLookupTableOwner,
    TransactionError.InvalidAddressLookupTableData,
    TransactionError.InvalidAddressLookupTableIndex,
    TransactionError.InvalidRentPayingAccount,
    TransactionError.WouldExceedMaxVoteCostLimit,
    TransactionError.WouldExceedAccountDataTotalLimit,
    TransactionError.DuplicateInstruction,
    TransactionError.InsufficientFundsForRent,
    TransactionError.MaxLoadedAccountsDataSizeExceeded,
    TransactionError.InvalidLoadedAccountsDataSizeLimit,
    TransactionError.ResanitizationNeeded,
    TransactionError.ProgramExecutionTemporarilyRestricted,
    TransactionError.UnbalancedTransaction,
    TransactionError.ProgramCacheHitMaxLimit,
    TransactionError.CommitCancelled,
    TransactionError.Unknown {

  record Unknown(String type) implements TransactionError {
  }

  record AccountInUse() implements TransactionError {
    static final AccountInUse INSTANCE = new AccountInUse();
  }

  record AccountLoadedTwice() implements TransactionError {
    static final AccountLoadedTwice INSTANCE = new AccountLoadedTwice();
  }

  record AccountNotFound() implements TransactionError {
    static final AccountNotFound INSTANCE = new AccountNotFound();
  }

  record ProgramAccountNotFound() implements TransactionError {
    static final ProgramAccountNotFound INSTANCE = new ProgramAccountNotFound();
  }

  record InsufficientFundsForFee() implements TransactionError {
    static final InsufficientFundsForFee INSTANCE = new InsufficientFundsForFee();
  }

  record InvalidAccountForFee() implements TransactionError {
    static final InvalidAccountForFee INSTANCE = new InvalidAccountForFee();
  }

  record AlreadyProcessed() implements TransactionError {
    static final AlreadyProcessed INSTANCE = new AlreadyProcessed();
  }

  record BlockhashNotFound() implements TransactionError {
    static final BlockhashNotFound INSTANCE = new BlockhashNotFound();
  }

  record InstructionError(int ixIndex, IxError ixError) implements TransactionError {
  }

  record CallChainTooDeep() implements TransactionError {
    static final CallChainTooDeep INSTANCE = new CallChainTooDeep();
  }

  record MissingSignatureForFee() implements TransactionError {
    static final MissingSignatureForFee INSTANCE = new MissingSignatureForFee();
  }

  record InvalidAccountIndex() implements TransactionError {
    static final InvalidAccountIndex INSTANCE = new InvalidAccountIndex();
  }

  record SignatureFailure() implements TransactionError {
    static final SignatureFailure INSTANCE = new SignatureFailure();
  }

  record InvalidProgramForExecution() implements TransactionError {
    static final InvalidProgramForExecution INSTANCE = new InvalidProgramForExecution();
  }

  record SanitizeFailure() implements TransactionError {
    static final SanitizeFailure INSTANCE = new SanitizeFailure();
  }

  record ClusterMaintenance() implements TransactionError {
    static final ClusterMaintenance INSTANCE = new ClusterMaintenance();
  }

  record AccountBorrowOutstanding() implements TransactionError {
    static final AccountBorrowOutstanding INSTANCE = new AccountBorrowOutstanding();
  }

  record WouldExceedMaxBlockCostLimit() implements TransactionError {
    static final WouldExceedMaxBlockCostLimit INSTANCE = new WouldExceedMaxBlockCostLimit();
  }

  record UnsupportedVersion() implements TransactionError {
    static final UnsupportedVersion INSTANCE = new UnsupportedVersion();
  }

  record InvalidWritableAccount() implements TransactionError {
    static final InvalidWritableAccount INSTANCE = new InvalidWritableAccount();
  }

  record WouldExceedMaxAccountCostLimit() implements TransactionError {
    static final WouldExceedMaxAccountCostLimit INSTANCE = new WouldExceedMaxAccountCostLimit();
  }

  record WouldExceedAccountDataBlockLimit() implements TransactionError {
    static final WouldExceedAccountDataBlockLimit INSTANCE = new WouldExceedAccountDataBlockLimit();
  }

  record TooManyAccountLocks() implements TransactionError {
    static final TooManyAccountLocks INSTANCE = new TooManyAccountLocks();
  }

  record AddressLookupTableNotFound() implements TransactionError {
    static final AddressLookupTableNotFound INSTANCE = new AddressLookupTableNotFound();
  }

  record InvalidAddressLookupTableOwner() implements TransactionError {
    static final InvalidAddressLookupTableOwner INSTANCE = new InvalidAddressLookupTableOwner();
  }

  record InvalidAddressLookupTableData() implements TransactionError {
    static final InvalidAddressLookupTableData INSTANCE = new InvalidAddressLookupTableData();
  }

  record InvalidAddressLookupTableIndex() implements TransactionError {
    static final InvalidAddressLookupTableIndex INSTANCE = new InvalidAddressLookupTableIndex();
  }

  record InvalidRentPayingAccount() implements TransactionError {
    static final InvalidRentPayingAccount INSTANCE = new InvalidRentPayingAccount();
  }

  record WouldExceedMaxVoteCostLimit() implements TransactionError {
    static final WouldExceedMaxVoteCostLimit INSTANCE = new WouldExceedMaxVoteCostLimit();
  }

  record WouldExceedAccountDataTotalLimit() implements TransactionError {
    static final WouldExceedAccountDataTotalLimit INSTANCE = new WouldExceedAccountDataTotalLimit();
  }

  record DuplicateInstruction(int index) implements TransactionError {
  }

  record InsufficientFundsForRent(int accountIndex) implements TransactionError {
  }

  record MaxLoadedAccountsDataSizeExceeded() implements TransactionError {
    static final MaxLoadedAccountsDataSizeExceeded INSTANCE = new MaxLoadedAccountsDataSizeExceeded();
  }

  record InvalidLoadedAccountsDataSizeLimit() implements TransactionError {
    static final InvalidLoadedAccountsDataSizeLimit INSTANCE = new InvalidLoadedAccountsDataSizeLimit();
  }

  record ResanitizationNeeded() implements TransactionError {
    static final ResanitizationNeeded INSTANCE = new ResanitizationNeeded();
  }

  record ProgramExecutionTemporarilyRestricted(int accountIndex) implements TransactionError {
  }

  record UnbalancedTransaction() implements TransactionError {
    static final UnbalancedTransaction INSTANCE = new UnbalancedTransaction();
  }

  record ProgramCacheHitMaxLimit() implements TransactionError {
    static final ProgramCacheHitMaxLimit INSTANCE = new ProgramCacheHitMaxLimit();
  }

  record CommitCancelled() implements TransactionError {
    static final CommitCancelled INSTANCE = new CommitCancelled();
  }

  static TransactionError parseError(final JsonIterator ji) {
    return switch (ji.whatIsNext()) {
      case STRING -> ji.applyChars(PARSER);
      case OBJECT -> {
        final var error = ji.applyObject(OBJECT_PARSER);
        ji.skipRestOfObject();
        yield error;
      }
      default -> {
        ji.skip();
        yield null;
      }
    };
  }

  // Variant indices follow ERRORS' declaration order.
  FieldMatcher ERRORS = FieldMatcher.of(
      "AccountInUse",
      "AccountLoadedTwice",
      "AccountNotFound",
      "ProgramAccountNotFound",
      "InsufficientFundsForFee",
      "InvalidAccountForFee",
      "AlreadyProcessed",
      "BlockhashNotFound",
      "CallChainTooDeep",
      "MissingSignatureForFee",
      "InvalidAccountIndex",
      "SignatureFailure",
      "InvalidProgramForExecution",
      "SanitizeFailure",
      "ClusterMaintenance",
      "AccountBorrowOutstanding",
      "WouldExceedMaxBlockCostLimit",
      "UnsupportedVersion",
      "InvalidWritableAccount",
      "WouldExceedMaxAccountCostLimit",
      "WouldExceedAccountDataBlockLimit",
      "TooManyAccountLocks",
      "AddressLookupTableNotFound",
      "InvalidAddressLookupTableOwner",
      "InvalidAddressLookupTableData",
      "InvalidAddressLookupTableIndex",
      "InvalidRentPayingAccount",
      "WouldExceedMaxVoteCostLimit",
      "WouldExceedAccountDataTotalLimit",
      "MaxLoadedAccountsDataSizeExceeded",
      "InvalidLoadedAccountsDataSizeLimit",
      "ResanitizationNeeded",
      "UnbalancedTransaction",
      "ProgramCacheHitMaxLimit",
      "CommitCancelled"
  );

  TransactionError[] VARIANTS = {
      AccountInUse.INSTANCE,
      AccountLoadedTwice.INSTANCE,
      AccountNotFound.INSTANCE,
      ProgramAccountNotFound.INSTANCE,
      InsufficientFundsForFee.INSTANCE,
      InvalidAccountForFee.INSTANCE,
      AlreadyProcessed.INSTANCE,
      BlockhashNotFound.INSTANCE,
      CallChainTooDeep.INSTANCE,
      MissingSignatureForFee.INSTANCE,
      InvalidAccountIndex.INSTANCE,
      SignatureFailure.INSTANCE,
      InvalidProgramForExecution.INSTANCE,
      SanitizeFailure.INSTANCE,
      ClusterMaintenance.INSTANCE,
      AccountBorrowOutstanding.INSTANCE,
      WouldExceedMaxBlockCostLimit.INSTANCE,
      UnsupportedVersion.INSTANCE,
      InvalidWritableAccount.INSTANCE,
      WouldExceedMaxAccountCostLimit.INSTANCE,
      WouldExceedAccountDataBlockLimit.INSTANCE,
      TooManyAccountLocks.INSTANCE,
      AddressLookupTableNotFound.INSTANCE,
      InvalidAddressLookupTableOwner.INSTANCE,
      InvalidAddressLookupTableData.INSTANCE,
      InvalidAddressLookupTableIndex.INSTANCE,
      InvalidRentPayingAccount.INSTANCE,
      WouldExceedMaxVoteCostLimit.INSTANCE,
      WouldExceedAccountDataTotalLimit.INSTANCE,
      MaxLoadedAccountsDataSizeExceeded.INSTANCE,
      InvalidLoadedAccountsDataSizeLimit.INSTANCE,
      ResanitizationNeeded.INSTANCE,
      UnbalancedTransaction.INSTANCE,
      ProgramCacheHitMaxLimit.INSTANCE,
      CommitCancelled.INSTANCE
  };

  CharBufferFunction<TransactionError> PARSER = (buf, offset, len) -> {
    final int i = ERRORS.match(buf, offset, len);
    return i < 0 ? new Unknown(new String(buf, offset, len)) : VARIANTS[i];
  };

  FieldMatcher OBJECT_ERRORS = FieldMatcher.of(
      "InstructionError",
      "InsufficientFundsForRent",
      "ProgramExecutionTemporarilyRestricted",
      "DuplicateInstruction"
  );

  FieldBufferFunction<TransactionError> OBJECT_PARSER = (buf, offset, len, ji) -> {
    switch (OBJECT_ERRORS.match(buf, offset, len)) {
      case 0 -> {
        final int index = ji.openArray().readInt();
        ji.continueArray();
        final var error = IxError.parseError(ji);
        ji.skipRestOfArray();
        return new InstructionError(index, error);
      }
      case 1 -> {
        final int accountIndex = ji.skipUntil("account_index").readInt();
        ji.skipRestOfObject();
        return new InsufficientFundsForRent(accountIndex);
      }
      case 2 -> {
        final int accountIndex = ji.skipUntil("account_index").readInt();
        ji.skipRestOfObject();
        return new ProgramExecutionTemporarilyRestricted(accountIndex);
      }
      case 3 -> {
        return new DuplicateInstruction(ji.readInt());
      }
      default -> {
        final var type = new String(buf, offset, len);
        ji.skip();
        return new Unknown(type);
      }
    }
  };
}
