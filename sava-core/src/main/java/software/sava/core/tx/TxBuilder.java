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

  TxBuilder feePayer(final PublicKey feePayer);

  TxBuilder feePayer(final AccountMeta feePayer);

  TxBuilder addInstruction(final Instruction instruction);

  TxBuilder addInstructions(final List<Instruction> instructions);

  TxBuilder addInstructions(final SequencedCollection<Instruction> instructions);

  default TxBuilder addInstructions(final Instruction[] instructions) {
    addInstructions(Arrays.asList(instructions));
    return this;
  }

  TxBuilder priorityFeeLamports(final long priorityFeeLamports);

  TxBuilder computeUnitLimit(final int computeUnitLimit);

  TxBuilder accountDataSizeLimit(final int accountDataSizeLimit);

  TxBuilder heapSize(final int heapSize);
}
