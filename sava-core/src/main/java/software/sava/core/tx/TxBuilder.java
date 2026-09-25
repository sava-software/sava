package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;

import java.util.Arrays;
import java.util.List;
import java.util.SequencedCollection;

/// Builds SIMD-0385 v1 [Transaction]s, the only format it supports.
public interface TxBuilder {

  static TxBuilder createBuilder() {
    return new TxBuilderImpl();
  }

  /// Builds a v1 transaction from the configured fee payer and instructions.
  ///
  /// @throws IllegalArgumentException if instructions were supplied only as an empty collection
  /// @throws IllegalStateException    if no add, insert, or set call has succeeded, the accounts
  ///                                  include no fee payer, an instruction's program is the fee
  ///                                  payer, a [#strict()] limit is exceeded, or a count or length
  ///                                  does not fit its wire field
  Transaction createTransaction();

  /// Whether strict mode, the default, is enabled.
  ///
  /// Strict mode enforces the SIMD-0385 v1 limits: [#createTransaction()] rejects more than 64
  /// instructions, more than [Transaction#MAX_ACCOUNTS] accounts, more than 12 required signatures,
  /// or a serialized size above the v1 maximum, and [#heapSize(int)] rejects a nonzero heap size
  /// that is not a multiple of 1KiB from 32KiB to 256KiB.
  boolean strict();

  /// Enables or disables the SIMD-0385 limit checks described by [#strict()]. When disabled, the
  /// builder still refuses values its wire fields cannot encode.
  void strict(final boolean strict);

  AccountMeta feePayer();

  TxBuilder feePayer(final PublicKey feePayer);

  TxBuilder feePayer(final AccountMeta feePayer);

  TxBuilder addInstruction(final Instruction instruction);

  /// Appends the instructions in iteration order, copying rather than retaining the collection.
  TxBuilder addInstructions(final SequencedCollection<Instruction> instructions);

  default TxBuilder addInstructions(final Instruction[] instructions) {
    addInstructions(Arrays.asList(instructions));
    return this;
  }

  /// Replaces the instruction at `index`. While the builder holds no instructions, index 0 adds the
  /// first one, as [#addInstruction(Instruction)] does.
  ///
  /// @throws IndexOutOfBoundsException if `index` is negative or, outside that empty case, not
  ///                                   below the instruction count
  TxBuilder setInstruction(final int index, final Instruction instruction);

  /// Inserts the instruction at `index`, shifting later instructions.
  ///
  /// @throws IndexOutOfBoundsException if `index` is negative or above the instruction count
  TxBuilder insertInstruction(int index, Instruction instruction);

  long priorityFeeLamports();

  TxBuilder priorityFeeLamports(final long priorityFeeLamports);

  /// Converts a legacy/v0 SetComputeUnitPrice price, in micro-lamports per compute unit, into the
  /// equivalent v1 priority fee in lamports for `computeUnitLimit`.
  ///
  /// Multiplies by the limit (read as unsigned, capped at the 1.4 million runtime maximum) and
  /// rounds up to whole lamports, as the runtime does. Returns 0 for a zero price or limit;
  /// otherwise saturates at [Long#MAX_VALUE] for a negative price or on overflow.
  ///
  /// **A one-time conversion, not a binding.** A v1 priority fee is an absolute lamport ConfigValue
  /// charged verbatim, so the result does not follow a limit changed later; SIMD-0385 decouples
  /// them so a caller can tighten the limit after simulating without lowering the bid. Convert
  /// again if the fee should follow the limit.
  static long computeUnitPriceToPriorityFeeLamports(final long microLamportsPerComputeUnit,
                                                    final int computeUnitLimit) {
    final long cappedComputeUnitLimit = Math.min(computeUnitLimit & 0xFFFF_FFFFL, TxBuilderImpl.MAX_COMPUTE_UNIT_LIMIT);
    if (cappedComputeUnitLimit == 0 || microLamportsPerComputeUnit == 0) {
      return 0;
    } else if (microLamportsPerComputeUnit < 0
        || microLamportsPerComputeUnit > (Long.MAX_VALUE - 999_999) / cappedComputeUnitLimit) {
      return Long.MAX_VALUE;
    }
    // Round up to whole lamports, mirroring the runtime's prioritization fee calculation.
    return ((microLamportsPerComputeUnit * cappedComputeUnitLimit) + 999_999) / 1_000_000;
  }

  /// Sets the priority fee from a legacy/v0 price in micro-lamports per compute unit, converted
  /// against this builder's current [#computeUnitLimit()]; a cleared (0) limit gives a fee of 0.
  ///
  /// Set the limit first: the fee is fixed at conversion and does not follow a later limit change.
  ///
  /// @see #computeUnitPriceToPriorityFeeLamports(long, int)
  TxBuilder priorityFeeLamportsFromComputeUnitPrice(final long microLamportsPerComputeUnit);

  int computeUnitLimit();

  /// Sets the compute unit limit; the runtime caps it at 1.4 million.
  ///
  /// Defaults to that maximum so the ConfigValue is serialized and can be updated in place with
  /// [Transaction#setComputeUnitLimit(int)]. 0 clears it, omitting the ConfigValue, which
  /// SIMD-0385 reads as a limit of 0 units. To size it, simulate with the default, then set the
  /// units consumed plus any buffer before signing.
  TxBuilder computeUnitLimit(final int computeUnitLimit);

  int accountDataSizeLimit();

  /// Sets the loaded accounts data size limit in bytes; the runtime caps it at 64MiB.
  ///
  /// Defaults to that maximum, as legacy/v0 do, so the ConfigValue is serialized and can be
  /// updated in place with [Transaction#setAccountDataSizeLimit(int)]. 0 clears it, omitting the
  /// ConfigValue, which SIMD-0385 reads as a limit of 0 bytes: a transaction that then loads
  /// account data fails with MaxLoadedAccountsDataSizeExceeded and still pays fees. To size it,
  /// simulate with the default, then set the reported loaded accounts data size plus any buffer
  /// before signing.
  TxBuilder accountDataSizeLimit(final int accountDataSizeLimit);

  int heapSize();

  TxBuilder heapSize(final int heapSize);
}
