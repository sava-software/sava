package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.response.IxError;
import software.sava.rpc.json.http.response.TransactionError;
import systems.comodal.jsoniter.JsonIterator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies every enum variant serialized by the RPC API parses to its typed record rather
/// than falling through to the Unknown catch-all.
///
/// The variant name lists mirror `solana-sdk:transaction-error/src/lib.rs` and
/// `solana-sdk:instruction-error/src/lib.rs`; when new variants are appended upstream, add
/// them here and to the corresponding Java parser.
final class ParseErrorVariantsTests {

  /// Unit variants only; InstructionError, DuplicateInstruction, InsufficientFundsForRent
  /// and ProgramExecutionTemporarilyRestricted carry data and serialize as objects.
  private static final List<String> TRANSACTION_ERROR_UNIT_VARIANTS = List.of(
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

  /// Unit variants only; Custom and BorshIoError may carry data.
  private static final List<String> IX_ERROR_UNIT_VARIANTS = List.of(
      "GenericError",
      "InvalidArgument",
      "InvalidInstructionData",
      "InvalidAccountData",
      "AccountDataTooSmall",
      "InsufficientFunds",
      "IncorrectProgramId",
      "MissingRequiredSignature",
      "AccountAlreadyInitialized",
      "UninitializedAccount",
      "UnbalancedInstruction",
      "ModifiedProgramId",
      "ExternalAccountLamportSpend",
      "ExternalAccountDataModified",
      "ReadonlyLamportChange",
      "ReadonlyDataModified",
      "DuplicateAccountIndex",
      "ExecutableModified",
      "RentEpochModified",
      "NotEnoughAccountKeys",
      "AccountDataSizeChanged",
      "AccountNotExecutable",
      "AccountBorrowFailed",
      "AccountBorrowOutstanding",
      "DuplicateAccountOutOfSync",
      "InvalidError",
      "ExecutableDataModified",
      "ExecutableLamportChange",
      "ExecutableAccountNotRentExempt",
      "UnsupportedProgramId",
      "CallDepth",
      "MissingAccount",
      "ReentrancyNotAllowed",
      "MaxSeedLengthExceeded",
      "InvalidSeeds",
      "InvalidRealloc",
      "ComputationalBudgetExceeded",
      "PrivilegeEscalation",
      "ProgramEnvironmentSetupFailure",
      "ProgramFailedToComplete",
      "ProgramFailedToCompile",
      "Immutable",
      "IncorrectAuthority",
      "AccountNotRentExempt",
      "InvalidAccountOwner",
      "ArithmeticOverflow",
      "UnsupportedSysvar",
      "IllegalOwner",
      "MaxAccountsDataAllocationsExceeded",
      "MaxAccountsExceeded",
      "MaxInstructionTraceLengthExceeded",
      "BuiltinProgramsMustConsumeComputeUnits"
  );

  @Test
  void transactionErrorUnitVariants() {
    for (final var variant : TRANSACTION_ERROR_UNIT_VARIANTS) {
      final var ji = JsonIterator.parse('"' + variant + '"');
      final var error = TransactionError.parseError(ji);
      assertNotNull(error, variant);
      assertEquals(variant, error.getClass().getSimpleName(), variant);
    }
  }

  @Test
  void transactionErrorDataVariants() {
    var ji = JsonIterator.parse("""
        {"InstructionError":[3,"GenericError"]}""");
    final var instructionError = (TransactionError.InstructionError) TransactionError.parseError(ji);
    assertEquals(3, instructionError.ixIndex());
    assertInstanceOf(IxError.GenericError.class, instructionError.ixError());

    ji = JsonIterator.parse("""
        {"DuplicateInstruction":7}""");
    assertEquals(new TransactionError.DuplicateInstruction(7), TransactionError.parseError(ji));

    ji = JsonIterator.parse("""
        {"InsufficientFundsForRent":{"account_index":2}}""");
    assertEquals(new TransactionError.InsufficientFundsForRent(2), TransactionError.parseError(ji));

    ji = JsonIterator.parse("""
        {"ProgramExecutionTemporarilyRestricted":{"account_index":4}}""");
    assertEquals(new TransactionError.ProgramExecutionTemporarilyRestricted(4), TransactionError.parseError(ji));
  }

  @Test
  void transactionErrorUnknownFallback() {
    var ji = JsonIterator.parse("\"SomeFutureVariant\"");
    assertEquals(new TransactionError.Unknown("SomeFutureVariant"), TransactionError.parseError(ji));

    ji = JsonIterator.parse("""
        {"SomeFutureVariant":{"field":1}}""");
    assertEquals(new TransactionError.Unknown("SomeFutureVariant"), TransactionError.parseError(ji));
  }

  @Test
  void ixErrorUnitVariants() {
    for (final var variant : IX_ERROR_UNIT_VARIANTS) {
      final var ji = JsonIterator.parse('"' + variant + '"');
      final var error = IxError.parseError(ji);
      assertNotNull(error, variant);
      assertEquals(variant, error.getClass().getSimpleName(), variant);
    }
  }

  @Test
  void ixErrorDataVariants() {
    var ji = JsonIterator.parse("""
        {"Custom":6001}""");
    final var custom = (IxError.Custom) IxError.parseError(ji);
    assertEquals(6001, custom.error());

    // Historical form: BorshIoError carried a message before solana-sdk v3.
    ji = JsonIterator.parse("""
        {"BorshIoError":"Unknown"}""");
    assertEquals(new IxError.BorshIoError("Unknown"), IxError.parseError(ji));

    // Current form: unit variant.
    ji = JsonIterator.parse("\"BorshIoError\"");
    assertEquals(new IxError.BorshIoError(null), IxError.parseError(ji));
  }

  @Test
  void ixErrorUnknownFallback() {
    var ji = JsonIterator.parse("\"SomeFutureVariant\"");
    assertEquals(new IxError.Unknown("SomeFutureVariant"), IxError.parseError(ji));

    ji = JsonIterator.parse("""
        {"SomeFutureVariant":42}""");
    assertEquals(new IxError.Unknown("SomeFutureVariant"), IxError.parseError(ji));
  }
}
