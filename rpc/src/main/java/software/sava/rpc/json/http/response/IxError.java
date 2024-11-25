package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.CharBufferFunction;
import systems.comodal.jsoniter.FieldBufferFunction;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public sealed interface IxError permits
    IxError.GenericError,
    IxError.InvalidArgument,
    IxError.InvalidInstructionData,
    IxError.InvalidAccountData,
    IxError.AccountDataTooSmall,
    IxError.InsufficientFunds,
    IxError.IncorrectProgramId,
    IxError.MissingRequiredSignature,
    IxError.AccountAlreadyInitialized,
    IxError.UninitializedAccount,
    IxError.UnbalancedInstruction,
    IxError.ModifiedProgramId,
    IxError.ExternalAccountLamportSpend,
    IxError.ExternalAccountDataModified,
    IxError.ReadonlyLamportChange,
    IxError.ReadonlyDataModified,
    IxError.DuplicateAccountIndex,
    IxError.ExecutableModified,
    IxError.RentEpochModified,
    IxError.NotEnoughAccountKeys,
    IxError.AccountDataSizeChanged,
    IxError.AccountNotExecutable,
    IxError.AccountBorrowFailed,
    IxError.AccountBorrowOutstanding,
    IxError.DuplicateAccountOutOfSync,
    IxError.Custom,
    IxError.InvalidError,
    IxError.ExecutableDataModified,
    IxError.ExecutableLamportChange,
    IxError.ExecutableAccountNotRentExempt,
    IxError.UnsupportedProgramId,
    IxError.CallDepth,
    IxError.MissingAccount,
    IxError.ReentrancyNotAllowed,
    IxError.MaxSeedLengthExceeded,
    IxError.InvalidSeeds,
    IxError.InvalidRealloc,
    IxError.ComputationalBudgetExceeded,
    IxError.PrivilegeEscalation,
    IxError.ProgramEnvironmentSetupFailure,
    IxError.ProgramFailedToComplete,
    IxError.ProgramFailedToCompile,
    IxError.Immutable,
    IxError.IncorrectAuthority,
    IxError.BorshIoError,
    IxError.AccountNotRentExempt,
    IxError.InvalidAccountOwner,
    IxError.ArithmeticOverflow,
    IxError.UnsupportedSysvar,
    IxError.IllegalOwner,
    IxError.MaxAccountsDataAllocationsExceeded,
    IxError.MaxAccountsExceeded,
    IxError.MaxInstructionTraceLengthExceeded,
    IxError.BuiltinProgramsMustConsumeComputeUnits,
    IxError.Unknown {

  record Unknown(String type) implements IxError {

  }

  record GenericError() implements IxError {
    static final GenericError INSTANCE = new GenericError();
  }

  record InvalidArgument() implements IxError {
    static final InvalidArgument INSTANCE = new InvalidArgument();
  }

  record InvalidInstructionData() implements IxError {
    static final InvalidInstructionData INSTANCE = new InvalidInstructionData();
  }

  record InvalidAccountData() implements IxError {
    static final InvalidAccountData INSTANCE = new InvalidAccountData();
  }

  record AccountDataTooSmall() implements IxError {
    static final AccountDataTooSmall INSTANCE = new AccountDataTooSmall();
  }

  record InsufficientFunds() implements IxError {
    static final InsufficientFunds INSTANCE = new InsufficientFunds();
  }

  record IncorrectProgramId() implements IxError {
    static final IncorrectProgramId INSTANCE = new IncorrectProgramId();
  }

  record MissingRequiredSignature() implements IxError {
    static final MissingRequiredSignature INSTANCE = new MissingRequiredSignature();
  }

  record AccountAlreadyInitialized() implements IxError {
    static final AccountAlreadyInitialized INSTANCE = new AccountAlreadyInitialized();
  }

  record UninitializedAccount() implements IxError {
    static final UninitializedAccount INSTANCE = new UninitializedAccount();
  }

  record UnbalancedInstruction() implements IxError {
    static final UnbalancedInstruction INSTANCE = new UnbalancedInstruction();
  }

  record ModifiedProgramId() implements IxError {
    static final ModifiedProgramId INSTANCE = new ModifiedProgramId();
  }

  record ExternalAccountLamportSpend() implements IxError {
    static final ExternalAccountLamportSpend INSTANCE = new ExternalAccountLamportSpend();
  }

  record ExternalAccountDataModified() implements IxError {
    static final ExternalAccountDataModified INSTANCE = new ExternalAccountDataModified();
  }

  record ReadonlyLamportChange() implements IxError {
    static final ReadonlyLamportChange INSTANCE = new ReadonlyLamportChange();
  }

  record ReadonlyDataModified() implements IxError {
    static final ReadonlyDataModified INSTANCE = new ReadonlyDataModified();
  }

  record DuplicateAccountIndex() implements IxError {
    static final DuplicateAccountIndex INSTANCE = new DuplicateAccountIndex();
  }

  record ExecutableModified() implements IxError {
    static final ExecutableModified INSTANCE = new ExecutableModified();
  }

  record RentEpochModified() implements IxError {
    static final RentEpochModified INSTANCE = new RentEpochModified();
  }

  record NotEnoughAccountKeys() implements IxError {
    static final NotEnoughAccountKeys INSTANCE = new NotEnoughAccountKeys();
  }

  record AccountDataSizeChanged() implements IxError {
    static final AccountDataSizeChanged INSTANCE = new AccountDataSizeChanged();
  }

  record AccountNotExecutable() implements IxError {
    static final AccountNotExecutable INSTANCE = new AccountNotExecutable();
  }

  record AccountBorrowFailed() implements IxError {
    static final AccountBorrowFailed INSTANCE = new AccountBorrowFailed();
  }

  record AccountBorrowOutstanding() implements IxError {
    static final AccountBorrowOutstanding INSTANCE = new AccountBorrowOutstanding();
  }

  record DuplicateAccountOutOfSync() implements IxError {
    static final DuplicateAccountOutOfSync INSTANCE = new DuplicateAccountOutOfSync();
  }

  record Custom(long error) implements IxError {
  }

  record InvalidError() implements IxError {
    static final InvalidError INSTANCE = new InvalidError();
  }

  record ExecutableDataModified() implements IxError {
    static final ExecutableDataModified INSTANCE = new ExecutableDataModified();
  }

  record ExecutableLamportChange() implements IxError {
    static final ExecutableLamportChange INSTANCE = new ExecutableLamportChange();
  }

  record ExecutableAccountNotRentExempt() implements IxError {
    static final ExecutableAccountNotRentExempt INSTANCE = new ExecutableAccountNotRentExempt();
  }

  record UnsupportedProgramId() implements IxError {
    static final UnsupportedProgramId INSTANCE = new UnsupportedProgramId();
  }

  record CallDepth() implements IxError {
    static final CallDepth INSTANCE = new CallDepth();
  }

  record MissingAccount() implements IxError {
    static final MissingAccount INSTANCE = new MissingAccount();
  }

  record ReentrancyNotAllowed() implements IxError {
    static final ReentrancyNotAllowed INSTANCE = new ReentrancyNotAllowed();
  }

  record MaxSeedLengthExceeded() implements IxError {
    static final MaxSeedLengthExceeded INSTANCE = new MaxSeedLengthExceeded();
  }

  record InvalidSeeds() implements IxError {
    static final InvalidSeeds INSTANCE = new InvalidSeeds();
  }

  record InvalidRealloc() implements IxError {
    static final InvalidRealloc INSTANCE = new InvalidRealloc();
  }

  record ComputationalBudgetExceeded() implements IxError {
    static final ComputationalBudgetExceeded INSTANCE = new ComputationalBudgetExceeded();
  }

  record PrivilegeEscalation() implements IxError {
    static final PrivilegeEscalation INSTANCE = new PrivilegeEscalation();
  }

  record ProgramEnvironmentSetupFailure() implements IxError {
    static final ProgramEnvironmentSetupFailure INSTANCE = new ProgramEnvironmentSetupFailure();
  }

  record ProgramFailedToComplete() implements IxError {
    static final ProgramFailedToComplete INSTANCE = new ProgramFailedToComplete();
  }

  record ProgramFailedToCompile() implements IxError {
    static final ProgramFailedToCompile INSTANCE = new ProgramFailedToCompile();
  }

  record Immutable() implements IxError {
    static final Immutable INSTANCE = new Immutable();
  }

  record IncorrectAuthority() implements IxError {
    static final IncorrectAuthority INSTANCE = new IncorrectAuthority();
  }

  record BorshIoError(String error) implements IxError {
  }

  record AccountNotRentExempt() implements IxError {
    static final AccountNotRentExempt INSTANCE = new AccountNotRentExempt();
  }

  record InvalidAccountOwner() implements IxError {
    static final InvalidAccountOwner INSTANCE = new InvalidAccountOwner();
  }

  record ArithmeticOverflow() implements IxError {
    static final ArithmeticOverflow INSTANCE = new ArithmeticOverflow();
  }

  record UnsupportedSysvar() implements IxError {
    static final UnsupportedSysvar INSTANCE = new UnsupportedSysvar();
  }

  record IllegalOwner() implements IxError {
    static final IllegalOwner INSTANCE = new IllegalOwner();
  }

  record MaxAccountsDataAllocationsExceeded() implements IxError {
    static final MaxAccountsDataAllocationsExceeded INSTANCE = new MaxAccountsDataAllocationsExceeded();
  }

  record MaxAccountsExceeded() implements IxError {
    static final MaxAccountsExceeded INSTANCE = new MaxAccountsExceeded();
  }

  record MaxInstructionTraceLengthExceeded() implements IxError {
    static final MaxInstructionTraceLengthExceeded INSTANCE = new MaxInstructionTraceLengthExceeded();
  }

  record BuiltinProgramsMustConsumeComputeUnits() implements IxError {
    static final BuiltinProgramsMustConsumeComputeUnits INSTANCE = new BuiltinProgramsMustConsumeComputeUnits();
  }

  static IxError parseError(final JsonIterator ji) {
    return switch (ji.whatIsNext()) {
      case STRING -> ji.applyChars(PARSER);
      case OBJECT -> ji.applyObject(OBJECT_PARSER);
      default -> {
        ji.skip();
        yield null;
      }
    };
  }

  CharBufferFunction<IxError> PARSER = (buf, offset, len) -> {
    if (fieldEquals("GenericError", buf, offset, len)) {
      return GenericError.INSTANCE;
    } else if (fieldEquals("InvalidArgument", buf, offset, len)) {
      return InvalidArgument.INSTANCE;
    } else if (fieldEquals("InvalidInstructionData", buf, offset, len)) {
      return InvalidInstructionData.INSTANCE;
    } else if (fieldEquals("InvalidAccountData", buf, offset, len)) {
      return InvalidAccountData.INSTANCE;
    } else if (fieldEquals("AccountDataTooSmall", buf, offset, len)) {
      return AccountDataTooSmall.INSTANCE;
    } else if (fieldEquals("InsufficientFunds", buf, offset, len)) {
      return InsufficientFunds.INSTANCE;
    } else if (fieldEquals("IncorrectProgramId", buf, offset, len)) {
      return IncorrectProgramId.INSTANCE;
    } else if (fieldEquals("MissingRequiredSignature", buf, offset, len)) {
      return MissingRequiredSignature.INSTANCE;
    } else if (fieldEquals("AccountAlreadyInitialized", buf, offset, len)) {
      return AccountAlreadyInitialized.INSTANCE;
    } else if (fieldEquals("UninitializedAccount", buf, offset, len)) {
      return UninitializedAccount.INSTANCE;
    } else if (fieldEquals("UnbalancedInstruction", buf, offset, len)) {
      return UnbalancedInstruction.INSTANCE;
    } else if (fieldEquals("ModifiedProgramId", buf, offset, len)) {
      return ModifiedProgramId.INSTANCE;
    } else if (fieldEquals("ExternalAccountLamportSpend", buf, offset, len)) {
      return ExternalAccountLamportSpend.INSTANCE;
    } else if (fieldEquals("ExternalAccountDataModified", buf, offset, len)) {
      return ExternalAccountDataModified.INSTANCE;
    } else if (fieldEquals("ReadonlyLamportChange", buf, offset, len)) {
      return ReadonlyLamportChange.INSTANCE;
    } else if (fieldEquals("ReadonlyDataModified", buf, offset, len)) {
      return ReadonlyDataModified.INSTANCE;
    } else if (fieldEquals("DuplicateAccountIndex", buf, offset, len)) {
      return DuplicateAccountIndex.INSTANCE;
    } else if (fieldEquals("ExecutableModified", buf, offset, len)) {
      return ExecutableModified.INSTANCE;
    } else if (fieldEquals("RentEpochModified", buf, offset, len)) {
      return RentEpochModified.INSTANCE;
    } else if (fieldEquals("NotEnoughAccountKeys", buf, offset, len)) {
      return NotEnoughAccountKeys.INSTANCE;
    } else if (fieldEquals("AccountDataSizeChanged", buf, offset, len)) {
      return AccountDataSizeChanged.INSTANCE;
    } else if (fieldEquals("AccountNotExecutable", buf, offset, len)) {
      return AccountNotExecutable.INSTANCE;
    } else if (fieldEquals("AccountBorrowFailed", buf, offset, len)) {
      return AccountBorrowFailed.INSTANCE;
    } else if (fieldEquals("AccountBorrowOutstanding", buf, offset, len)) {
      return AccountBorrowOutstanding.INSTANCE;
    } else if (fieldEquals("DuplicateAccountOutOfSync", buf, offset, len)) {
      return DuplicateAccountOutOfSync.INSTANCE;
    } else if (fieldEquals("InvalidError", buf, offset, len)) {
      return InvalidError.INSTANCE;
    } else if (fieldEquals("ExecutableDataModified", buf, offset, len)) {
      return ExecutableDataModified.INSTANCE;
    } else if (fieldEquals("ExecutableLamportChange", buf, offset, len)) {
      return ExecutableLamportChange.INSTANCE;
    } else if (fieldEquals("ExecutableAccountNotRentExempt", buf, offset, len)) {
      return ExecutableAccountNotRentExempt.INSTANCE;
    } else if (fieldEquals("UnsupportedProgramId", buf, offset, len)) {
      return UnsupportedProgramId.INSTANCE;
    } else if (fieldEquals("CallDepth", buf, offset, len)) {
      return CallDepth.INSTANCE;
    } else if (fieldEquals("MissingAccount", buf, offset, len)) {
      return MissingAccount.INSTANCE;
    } else if (fieldEquals("ReentrancyNotAllowed", buf, offset, len)) {
      return ReentrancyNotAllowed.INSTANCE;
    } else if (fieldEquals("MaxSeedLengthExceeded", buf, offset, len)) {
      return MaxSeedLengthExceeded.INSTANCE;
    } else if (fieldEquals("InvalidSeeds", buf, offset, len)) {
      return InvalidSeeds.INSTANCE;
    } else if (fieldEquals("InvalidRealloc", buf, offset, len)) {
      return InvalidRealloc.INSTANCE;
    } else if (fieldEquals("ComputationalBudgetExceeded", buf, offset, len)) {
      return ComputationalBudgetExceeded.INSTANCE;
    } else if (fieldEquals("PrivilegeEscalation", buf, offset, len)) {
      return PrivilegeEscalation.INSTANCE;
    } else if (fieldEquals("ProgramEnvironmentSetupFailure", buf, offset, len)) {
      return ProgramEnvironmentSetupFailure.INSTANCE;
    } else if (fieldEquals("ProgramFailedToComplete", buf, offset, len)) {
      return ProgramFailedToComplete.INSTANCE;
    } else if (fieldEquals("ProgramFailedToCompile", buf, offset, len)) {
      return ProgramFailedToCompile.INSTANCE;
    } else if (fieldEquals("Immutable", buf, offset, len)) {
      return Immutable.INSTANCE;
    } else if (fieldEquals("IncorrectAuthority", buf, offset, len)) {
      return IncorrectAuthority.INSTANCE;
    } else if (fieldEquals("AccountNotRentExempt", buf, offset, len)) {
      return AccountNotRentExempt.INSTANCE;
    } else if (fieldEquals("InvalidAccountOwner", buf, offset, len)) {
      return InvalidAccountOwner.INSTANCE;
    } else if (fieldEquals("ArithmeticOverflow", buf, offset, len)) {
      return ArithmeticOverflow.INSTANCE;
    } else if (fieldEquals("UnsupportedSysvar", buf, offset, len)) {
      return UnsupportedSysvar.INSTANCE;
    } else if (fieldEquals("IllegalOwner", buf, offset, len)) {
      return IllegalOwner.INSTANCE;
    } else if (fieldEquals("MaxAccountsDataAllocationsExceeded", buf, offset, len)) {
      return MaxAccountsDataAllocationsExceeded.INSTANCE;
    } else if (fieldEquals("MaxAccountsExceeded", buf, offset, len)) {
      return MaxAccountsExceeded.INSTANCE;
    } else if (fieldEquals("MaxInstructionTraceLengthExceeded", buf, offset, len)) {
      return MaxInstructionTraceLengthExceeded.INSTANCE;
    } else if (fieldEquals("BuiltinProgramsMustConsumeComputeUnits", buf, offset, len)) {
      return BuiltinProgramsMustConsumeComputeUnits.INSTANCE;
    } else {
      final var type = new String(buf, offset, len);
      return new Unknown(type);
    }
  };

  FieldBufferFunction<IxError> OBJECT_PARSER = (buf, offset, len, ji) -> {
    if (fieldEquals("Custom", buf, offset, len)) {
      final long error = ji.readLong();
      ji.skipRestOfObject();
      return new Custom(error);
    } else if (fieldEquals("BorshIoError", buf, offset, len)) {
      final var error = ji.readString();
      ji.skipRestOfObject();
      return new BorshIoError(error);
    } else {
      final var type = new String(buf, offset, len);
      ji.skip();
      return new Unknown(type);
    }
  };
}
