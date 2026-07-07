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
  /// the inclusive range [32KiB, 256KiB].
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

  int computeUnitLimit();

  TxBuilder computeUnitLimit(final int computeUnitLimit);

  int accountDataSizeLimit();

  TxBuilder accountDataSizeLimit(final int accountDataSizeLimit);

  int heapSize();

  TxBuilder heapSize(final int heapSize);
}
