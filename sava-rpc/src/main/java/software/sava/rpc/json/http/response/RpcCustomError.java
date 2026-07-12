package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.OptionalLong;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

// https://github.com/anza-xyz/agave/blob/master/rpc-client-api/src/custom_error.rs
public sealed interface RpcCustomError permits
    RpcCustomError.BlockCleanedUp,
    RpcCustomError.BlockNotAvailable,
    RpcCustomError.BlockStatusNotAvailableYet,
    RpcCustomError.EpochRewardsPeriodActive,
    RpcCustomError.FilterTransactionNotFound,
    RpcCustomError.KeyExcludedFromSecondaryIndex,
    RpcCustomError.LongTermStorageSlotSkipped,
    RpcCustomError.LongTermStorageUnreachable,
    RpcCustomError.MinContextSlotNotReached,
    RpcCustomError.NoSlotHistory,
    RpcCustomError.NoSnapshot,
    RpcCustomError.NodeUnhealthy,
    RpcCustomError.ScanError,
    RpcCustomError.SendTransactionPreflightFailure,
    RpcCustomError.SlotNotEpochBoundary,
    RpcCustomError.SlotSkipped,
    RpcCustomError.TransactionHistoryNotAvailable,
    RpcCustomError.TransactionPrecompileVerificationFailure,
    RpcCustomError.TransactionSignatureLenMismatch,
    RpcCustomError.TransactionSignatureVerificationFailure,
    RpcCustomError.Unknown,
    RpcCustomError.UnsupportedTransactionVersion {

  static RpcCustomError parseError(final int code) {
    return switch (code) {
      case -32001 -> BlockCleanedUp.INSTANCE;
      case -32003 -> TransactionSignatureVerificationFailure.INSTANCE;
      case -32004 -> BlockNotAvailable.INSTANCE;
      case -32006 -> TransactionPrecompileVerificationFailure.INSTANCE;
      case -32007 -> SlotSkipped.INSTANCE;
      case -32008 -> NoSnapshot.INSTANCE;
      case -32009 -> LongTermStorageSlotSkipped.INSTANCE;
      case -32010 -> KeyExcludedFromSecondaryIndex.INSTANCE;
      case -32011 -> TransactionHistoryNotAvailable.INSTANCE;
      case -32012 -> ScanError.INSTANCE;
      case -32013 -> TransactionSignatureLenMismatch.INSTANCE;
      case -32014 -> BlockStatusNotAvailableYet.INSTANCE;
      case -32015 -> UnsupportedTransactionVersion.INSTANCE;
      case -32019 -> LongTermStorageUnreachable.INSTANCE;
      case -32020 -> FilterTransactionNotFound.INSTANCE;
      case -32021 -> NoSlotHistory.INSTANCE;
      default -> Unknown.INSTANCE;
    };
  }

  static RpcCustomError parseError(final long code) {
    if (code < Integer.MIN_VALUE || code > Integer.MAX_VALUE) {
      return Unknown.INSTANCE;
    } else {
      return parseError((int) code);
    }
  }

  static RpcCustomError parseError(final long code, final JsonIterator ji) {
    if (code < Integer.MIN_VALUE || code > Integer.MAX_VALUE) {
      ji.skip();
      return Unknown.INSTANCE;
    }
    final int intCode = (int) code;
    return switch (intCode) {
      case -32002 -> new SendTransactionPreflightFailure(TxSimulation.parse(ji));
      case -32005 -> NodeUnhealthy.parseRecord(ji);
      case -32016 -> MinContextSlotNotReached.parseRecord(ji);
      case -32017 -> EpochRewardsPeriodActive.parseRecord(ji);
      case -32018 -> SlotNotEpochBoundary.parseRecord(ji);
      default -> {
        ji.skip();
        yield parseError(intCode);
      }
    };
  }

  record Unknown() implements RpcCustomError {
    static final RpcCustomError.Unknown INSTANCE = new RpcCustomError.Unknown();
  }

  record BlockCleanedUp() implements RpcCustomError {
    static final RpcCustomError.BlockCleanedUp INSTANCE = new RpcCustomError.BlockCleanedUp();
  }

  record SendTransactionPreflightFailure(TxSimulation simulation) implements RpcCustomError {

  }

  record TransactionSignatureVerificationFailure() implements RpcCustomError {
    static final RpcCustomError.TransactionSignatureVerificationFailure INSTANCE = new RpcCustomError.TransactionSignatureVerificationFailure();
  }

  record BlockNotAvailable() implements RpcCustomError {
    static final RpcCustomError.BlockNotAvailable INSTANCE = new RpcCustomError.BlockNotAvailable();
  }

  record NodeUnhealthy(OptionalLong numSlotsBehind) implements RpcCustomError {

    static NodeUnhealthy parseRecord(final JsonIterator ji) {
      final var parser = new Parser();
      ji.testObject(parser);
      return parser.createRecord();
    }

    private static final class Parser implements FieldBufferPredicate {

      private long numSlotsBehind = Long.MIN_VALUE;

      private Parser() {
      }

      NodeUnhealthy createRecord() {
        return new NodeUnhealthy(numSlotsBehind < 0
            ? OptionalLong.empty()
            : OptionalLong.of(numSlotsBehind)
        );
      }

      @Override
      public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
        if (fieldEquals("numSlotsBehind", buf, offset, len)) {
          numSlotsBehind = ji.readLong();
        } else {
          ji.skip();
        }
        return true;
      }
    }
  }

  record TransactionPrecompileVerificationFailure() implements RpcCustomError {
    static final RpcCustomError.TransactionPrecompileVerificationFailure INSTANCE = new RpcCustomError.TransactionPrecompileVerificationFailure();
  }

  record SlotSkipped() implements RpcCustomError {
    static final RpcCustomError.SlotSkipped INSTANCE = new RpcCustomError.SlotSkipped();
  }

  record NoSnapshot() implements RpcCustomError {
    static final RpcCustomError.NoSnapshot INSTANCE = new RpcCustomError.NoSnapshot();
  }

  record LongTermStorageSlotSkipped() implements RpcCustomError {
    static final RpcCustomError.LongTermStorageSlotSkipped INSTANCE = new RpcCustomError.LongTermStorageSlotSkipped();
  }

  record KeyExcludedFromSecondaryIndex() implements RpcCustomError {
    static final RpcCustomError.KeyExcludedFromSecondaryIndex INSTANCE = new RpcCustomError.KeyExcludedFromSecondaryIndex();
  }

  record TransactionHistoryNotAvailable() implements RpcCustomError {
    static final RpcCustomError.TransactionHistoryNotAvailable INSTANCE = new RpcCustomError.TransactionHistoryNotAvailable();
  }

  record ScanError() implements RpcCustomError {
    static final RpcCustomError.ScanError INSTANCE = new RpcCustomError.ScanError();
  }

  record TransactionSignatureLenMismatch() implements RpcCustomError {
    static final RpcCustomError.TransactionSignatureLenMismatch INSTANCE = new RpcCustomError.TransactionSignatureLenMismatch();
  }

  record BlockStatusNotAvailableYet() implements RpcCustomError {
    static final RpcCustomError.BlockStatusNotAvailableYet INSTANCE = new RpcCustomError.BlockStatusNotAvailableYet();
  }

  record UnsupportedTransactionVersion() implements RpcCustomError {
    static final RpcCustomError.UnsupportedTransactionVersion INSTANCE = new RpcCustomError.UnsupportedTransactionVersion();
  }

  record MinContextSlotNotReached(long contextSlot) implements RpcCustomError {

    static MinContextSlotNotReached parseRecord(final JsonIterator ji) {
      final var parser = new Parser();
      ji.testObject(parser);
      return parser.createRecord();
    }

    private static final class Parser implements FieldBufferPredicate {

      private long contextSlot;

      private Parser() {
      }

      MinContextSlotNotReached createRecord() {
        return new MinContextSlotNotReached(contextSlot);
      }

      @Override
      public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
        if (fieldEquals("contextSlot", buf, offset, len)) {
          contextSlot = ji.readLong();
        } else {
          ji.skip();
        }
        return true;
      }
    }
  }

  /// @param slot empty when responding nodes pre-date the Agave 3.0 data schema.
  record EpochRewardsPeriodActive(OptionalLong slot,
                                  long currentBlockHeight,
                                  long rewardsCompleteBlockHeight) implements RpcCustomError {

    static EpochRewardsPeriodActive parseRecord(final JsonIterator ji) {
      final var parser = new Parser();
      ji.testObject(parser);
      return parser.createRecord();
    }

    private static final class Parser implements FieldBufferPredicate {

      private long slot = Long.MIN_VALUE;
      private long currentBlockHeight;
      private long rewardsCompleteBlockHeight;

      private Parser() {
      }

      EpochRewardsPeriodActive createRecord() {
        return new EpochRewardsPeriodActive(
            slot < 0 ? OptionalLong.empty() : OptionalLong.of(slot),
            currentBlockHeight,
            rewardsCompleteBlockHeight
        );
      }

      @Override
      public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
        if (fieldEquals("slot", buf, offset, len)) {
          slot = ji.readLong();
        } else if (fieldEquals("currentBlockHeight", buf, offset, len)) {
          currentBlockHeight = ji.readLong();
        } else if (fieldEquals("rewardsCompleteBlockHeight", buf, offset, len)) {
          rewardsCompleteBlockHeight = ji.readLong();
        } else {
          ji.skip();
        }
        return true;
      }
    }
  }

  record SlotNotEpochBoundary(long slot) implements RpcCustomError {

    static SlotNotEpochBoundary parseRecord(final JsonIterator ji) {
      final var parser = new Parser();
      ji.testObject(parser);
      return parser.createRecord();
    }

    private static final class Parser implements FieldBufferPredicate {

      private long slot;

      private Parser() {
      }

      SlotNotEpochBoundary createRecord() {
        return new SlotNotEpochBoundary(slot);
      }

      @Override
      public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
        if (fieldEquals("slot", buf, offset, len)) {
          slot = ji.readLong();
        } else {
          ji.skip();
        }
        return true;
      }
    }
  }

  record LongTermStorageUnreachable() implements RpcCustomError {
    static final RpcCustomError.LongTermStorageUnreachable INSTANCE = new RpcCustomError.LongTermStorageUnreachable();
  }

  record FilterTransactionNotFound() implements RpcCustomError {
    static final RpcCustomError.FilterTransactionNotFound INSTANCE = new RpcCustomError.FilterTransactionNotFound();
  }

  record NoSlotHistory() implements RpcCustomError {
    static final RpcCustomError.NoSlotHistory INSTANCE = new RpcCustomError.NoSlotHistory();
  }
}
