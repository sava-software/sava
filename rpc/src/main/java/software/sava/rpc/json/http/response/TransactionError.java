package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.FieldBufferFunction;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

// See https://github.com/anza-xyz/agave/blob/7cb2b8a04a7d35711759d3fa4b636fdd6992ebf1/sdk/transaction-error/src/lib.rs#L17
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

  record InstructionError(int ixIndex,
                          IxError ixError) implements TransactionError {
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

  static TransactionError parseError(final JsonIterator ji) {
    return switch (ji.whatIsNext()) {
      case STRING -> ji.applyChars(PARSER);
      case OBJECT -> ji.applyObject(OBJECT_PARSER);
      default -> {
        ji.skip();
        yield null;
      }
    };
  }

  CharBufferFunction<TransactionError> PARSER = (buf, offset, len) -> {
    if (fieldEquals("AccountInUse", buf, offset, len)) {
      return AccountInUse.INSTANCE;
    } else if (fieldEquals("AccountLoadedTwice", buf, offset, len)) {
      return AccountLoadedTwice.INSTANCE;
    } else if (fieldEquals("AccountNotFound", buf, offset, len)) {
      return AccountNotFound.INSTANCE;
    } else if (fieldEquals("ProgramAccountNotFound", buf, offset, len)) {
      return ProgramAccountNotFound.INSTANCE;
    } else if (fieldEquals("InsufficientFundsForFee", buf, offset, len)) {
      return InsufficientFundsForFee.INSTANCE;
    } else if (fieldEquals("InvalidAccountForFee", buf, offset, len)) {
      return InvalidAccountForFee.INSTANCE;
    } else if (fieldEquals("AlreadyProcessed", buf, offset, len)) {
      return AlreadyProcessed.INSTANCE;
    } else if (fieldEquals("BlockhashNotFound", buf, offset, len)) {
      return BlockhashNotFound.INSTANCE;
    } else if (fieldEquals("CallChainTooDeep", buf, offset, len)) {
      return CallChainTooDeep.INSTANCE;
    } else if (fieldEquals("MissingSignatureForFee", buf, offset, len)) {
      return MissingSignatureForFee.INSTANCE;
    } else if (fieldEquals("InvalidAccountIndex", buf, offset, len)) {
      return InvalidAccountIndex.INSTANCE;
    } else if (fieldEquals("SignatureFailure", buf, offset, len)) {
      return SignatureFailure.INSTANCE;
    } else if (fieldEquals("InvalidProgramForExecution", buf, offset, len)) {
      return InvalidProgramForExecution.INSTANCE;
    } else if (fieldEquals("SanitizeFailure", buf, offset, len)) {
      return SanitizeFailure.INSTANCE;
    } else if (fieldEquals("ClusterMaintenance", buf, offset, len)) {
      return ClusterMaintenance.INSTANCE;
    } else if (fieldEquals("AccountBorrowOutstanding", buf, offset, len)) {
      return AccountBorrowOutstanding.INSTANCE;
    } else if (fieldEquals("WouldExceedMaxBlockCostLimit", buf, offset, len)) {
      return WouldExceedMaxBlockCostLimit.INSTANCE;
    } else if (fieldEquals("UnsupportedVersion", buf, offset, len)) {
      return UnsupportedVersion.INSTANCE;
    } else if (fieldEquals("InvalidWritableAccount", buf, offset, len)) {
      return InvalidWritableAccount.INSTANCE;
    } else if (fieldEquals("WouldExceedMaxAccountCostLimit", buf, offset, len)) {
      return WouldExceedMaxAccountCostLimit.INSTANCE;
    } else if (fieldEquals("WouldExceedAccountDataBlockLimit", buf, offset, len)) {
      return WouldExceedAccountDataBlockLimit.INSTANCE;
    } else if (fieldEquals("TooManyAccountLocks", buf, offset, len)) {
      return TooManyAccountLocks.INSTANCE;
    } else if (fieldEquals("AddressLookupTableNotFound", buf, offset, len)) {
      return AddressLookupTableNotFound.INSTANCE;
    } else if (fieldEquals("InvalidAddressLookupTableOwner", buf, offset, len)) {
      return InvalidAddressLookupTableOwner.INSTANCE;
    } else if (fieldEquals("InvalidAddressLookupTableData", buf, offset, len)) {
      return InvalidAddressLookupTableData.INSTANCE;
    } else if (fieldEquals("InvalidAddressLookupTableIndex", buf, offset, len)) {
      return InvalidAddressLookupTableIndex.INSTANCE;
    } else if (fieldEquals("InvalidRentPayingAccount", buf, offset, len)) {
      return InvalidRentPayingAccount.INSTANCE;
    } else if (fieldEquals("WouldExceedMaxVoteCostLimit", buf, offset, len)) {
      return WouldExceedMaxVoteCostLimit.INSTANCE;
    } else if (fieldEquals("WouldExceedAccountDataTotalLimit", buf, offset, len)) {
      return WouldExceedAccountDataTotalLimit.INSTANCE;
    } else if (fieldEquals("MaxLoadedAccountsDataSizeExceeded", buf, offset, len)) {
      return MaxLoadedAccountsDataSizeExceeded.INSTANCE;
    } else if (fieldEquals("InvalidLoadedAccountsDataSizeLimit", buf, offset, len)) {
      return InvalidLoadedAccountsDataSizeLimit.INSTANCE;
    } else if (fieldEquals("ResanitizationNeeded", buf, offset, len)) {
      return ResanitizationNeeded.INSTANCE;
    } else if (fieldEquals("UnbalancedTransaction", buf, offset, len)) {
      return UnbalancedTransaction.INSTANCE;
    } else if (fieldEquals("ProgramCacheHitMaxLimit", buf, offset, len)) {
      return ProgramCacheHitMaxLimit.INSTANCE;
    } else {
      final var type = new String(buf, offset, len);
      return new Unknown(type);
    }
  };

  FieldBufferFunction<TransactionError> OBJECT_PARSER = (buf, offset, len, ji) -> {
    if (fieldEquals("InstructionError", buf, offset, len)) {
      final int index = ji.openArray().readInt();
      ji.continueArray();
      final var error = IxError.parseError(ji);
      ji.skipRestOfArray();
      ji.skipRestOfObject();
      return new InstructionError(index, error);
    } else if (fieldEquals("InsufficientFundsForRent", buf, offset, len)) {
      final int accountIndex = ji.skipUntil("account_index").readInt();
      ji.skipRestOfObject();
      ji.skipRestOfObject();
      return new InsufficientFundsForRent(accountIndex);
    } else if (fieldEquals("ProgramExecutionTemporarilyRestricted", buf, offset, len)) {
      final int accountIndex = ji.skipUntil("account_index").readInt();
      ji.skipRestOfObject();
      ji.skipRestOfObject();
      return new ProgramExecutionTemporarilyRestricted(accountIndex);
    } else if (fieldEquals("DuplicateInstruction", buf, offset, len)) {
      final int index = ji.readInt();
      return new DuplicateInstruction(index);
    } else {
      final var type = new String(buf, offset, len);
      ji.skip();
      return new Unknown(type);
    }
  };
}
