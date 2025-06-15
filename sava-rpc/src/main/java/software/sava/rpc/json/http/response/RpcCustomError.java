package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.OptionalLong;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

// https://github.com/solana-labs/solana/blob/27eff8408b7223bb3c4ab70523f8a8dca3ca6645/rpc-client-api/src/custom_error.rs#L29
public sealed interface RpcCustomError permits RpcCustomError.BlockCleanedUp, RpcCustomError.BlockNotAvailable, RpcCustomError.BlockStatusNotAvailableYet, RpcCustomError.KeyExcludedFromSecondaryIndex, RpcCustomError.LongTermStorageSlotSkipped, RpcCustomError.MinContextSlotNotReached, RpcCustomError.NoSnapshot, RpcCustomError.NodeUnhealthy, RpcCustomError.ScanError, RpcCustomError.SendTransactionPreflightFailure, RpcCustomError.SlotSkipped, RpcCustomError.TransactionHistoryNotAvailable, RpcCustomError.TransactionPrecompileVerificationFailure, RpcCustomError.TransactionSignatureLenMismatch, RpcCustomError.TransactionSignatureVerificationFailure, RpcCustomError.Unknown, RpcCustomError.UnsupportedTransactionVersion {

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
}
