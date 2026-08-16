package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;

import java.util.Arrays;
import java.util.List;
import java.util.SequencedCollection;

/// Builds {@link Transaction}s. For now only the SIMD-0385 v1 format is supported.
///
public interface TxBuilder {

  static TxBuilder createBuilder() {
    return new TxBuilderImpl();
  }

  Transaction createTransaction();

  /// Whether strict mode is enabled. Strict mode is enabled by default.
  ///
  /// When enabled, [#createTransaction()] validates that the resulting transaction conforms to the
  /// SIMD-0385 v1 limits and throws an exception if any are exceeded:
  ///  - at least one and at most 64 instructions
  ///  - at most 64 accounts
  ///  - at most 12 required signatures
  ///  - a serialized size within the v1 maximum
  ///
  /// Additionally, [#heapSize(int)] validates that a requested heap size is a multiple of 1KiB within
  /// the inclusive range [32KiB,256KiB].
  ///
  /// @return {@code true} if strict mode is enabled
  boolean strict();

  /// Enables or disables strict mode.
  ///
  /// When enabled (the default), the builder enforces the SIMD-0385 v1 limits described by [#strict()].
  /// When disabled, these validations are skipped, allowing transactions that may violate the v1 limits.
  ///
  /// @param strict {@code true} to enable strict mode, {@code false} to disable it
  void strict(final boolean strict);

  AccountMeta feePayer();

  TxBuilder feePayer(final PublicKey feePayer);

  TxBuilder feePayer(final AccountMeta feePayer);

  TxBuilder addInstruction(final Instruction instruction);

  TxBuilder addInstructions(final List<Instruction> instructions);

  TxBuilder addInstructions(final SequencedCollection<Instruction> instructions);

  default TxBuilder addInstructions(final Instruction[] instructions) {
    addInstructions(Arrays.asList(instructions));
    return this;
  }

  /***
   *
   * @throws IndexOutOfBoundsException if the index is out of range
   *         ({@code index < 0 || index >= size()})
   */
  TxBuilder setInstruction(final int index, final Instruction instruction);

  /***
   *
   * @throws IndexOutOfBoundsException if the index is out of range
   *         ({@code index < 0 || index >= size()})
   */
  TxBuilder insertInstruction(int index, Instruction instruction);

  long priorityFeeLamports();

  TxBuilder priorityFeeLamports(final long priorityFeeLamports);

  /// Converts a legacy/v0 SetComputeUnitPrice compute budget price, denominated in micro-lamports
  /// per compute unit, into the equivalent v1 priority fee in lamports for the given compute unit
  /// limit.
  ///
  /// The price is multiplied by the compute unit limit, capped at the 1.4 million maximum, then
  /// converted to lamports, rounding up, mirroring the runtime's prioritization fee calculation.
  /// Saturates at {@link Long#MAX_VALUE} if the fee overflows, which far exceeds the total supply
  /// of SOL.
  ///
  /// @param microLamportsPerComputeUnit the legacy compute unit price in micro-lamports per compute unit
  /// @param computeUnitLimit            the compute unit limit the price applies to
  /// @return the equivalent priority fee in lamports
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

  /// Sets the priority fee from a legacy/v0 SetComputeUnitPrice compute budget price, denominated
  /// in micro-lamports per compute unit, converting it to lamports against this builder's current
  /// {@link #computeUnitLimit()}.
  ///
  /// Set the desired compute unit limit before calling this method so the conversion reflects the
  /// intended limit.
  ///
  /// @param microLamportsPerComputeUnit the legacy compute unit price in micro-lamports per compute unit
  /// @see #computeUnitPriceToPriorityFeeLamports(long, int)
  TxBuilder priorityFeeLamportsFromComputeUnitPrice(final long microLamportsPerComputeUnit);

  int computeUnitLimit();

  /// Sets the compute unit limit, capped by the runtime at 1.4 million.
  ///
  /// Defaults to the 1.4 million maximum so that the corresponding ConfigValue is serialized and
  /// may be updated in place later via {@link Transaction#setComputeUnitLimit(int)}. Set to 0 to
  /// clear, omitting the ConfigValue, which per SIMD-0385 defaults the limit to 0 compute units.
  ///
  /// If not known ahead of time, simulate the transaction with the default maximum,
  /// then set the limit to the units consumed reported by the RPC simulation,
  /// plus a buffer if desired, before signing and sending.
  TxBuilder computeUnitLimit(final int computeUnitLimit);

  int accountDataSizeLimit();

  /// Sets the loaded accounts data size limit, in bytes, capped by the runtime at 64MiB.
  ///
  /// Defaults to the 64MiB maximum, mirroring the legacy/v0 default, so that the corresponding
  /// ConfigValue is serialized and may be updated in place later via
  /// {@link Transaction#setAccountDataSizeLimit(int)}. Set to 0 to clear, omitting the
  /// ConfigValue, which per SIMD-0385 defaults the limit to 0 bytes; a transaction which loads
  /// account data without a sufficient limit will fail with MaxLoadedAccountsDataSizeExceeded
  /// and still pay fees.
  ///
  /// If not known ahead of time, simulate the transaction with the default maximum,
  /// then set the limit to the loaded accounts data size reported by the RPC simulation,
  /// plus a buffer if desired, before signing and sending.
  TxBuilder accountDataSizeLimit(final int accountDataSizeLimit);

  int heapSize();

  TxBuilder heapSize(final int heapSize);
}
