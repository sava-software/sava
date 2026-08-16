package software.sava.core.tx;

import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.encoding.ByteUtil;

import java.util.Arrays;
import java.util.List;

import static software.sava.core.tx.TransactionRecord.NO_TABLES;
import static software.sava.core.tx.V1TransactionSkeleton.*;

final class V1Transaction extends BaseTransaction {

  private final int signaturesOffset;

  V1Transaction(final AccountMeta feePayer,
                final List<Instruction> instructions,
                final byte[] data,
                final int signaturesOffset) {
    super(feePayer, instructions, data);
    this.signaturesOffset = signaturesOffset;
  }

  // Must match the discriminator TransactionSkeleton#deserializeSkeleton dispatches on: the exact
  // SIMD-0385 version byte, not every byte with the versioned bit set — a legacy message needing
  // 128 or more signature slots also leads with a high-bit byte (compact-u16 0x80 0x01) — and a
  // non-zero num_required_signatures, which rules out the non-canonical legacy prefix 0x81 0x00.
  static boolean isV1(final byte[] txData) {
    return (txData[0] & 0xFF) == (TxBuilderImpl.V1_VERSION_BYTE & 0xFF) && txData[1] != 0;
  }

  @Override
  public AddressLookupTable lookupTable() {
    return null;
  }

  @Override
  public LookupTableAccountMeta[] tableAccountMetas() {
    return NO_TABLES;
  }

  @Override
  protected int recentBlockHashIndex() {
    return V1_RECENT_BLOCK_HASH_INDEX;
  }

  @Override
  protected int accountsOffset() {
    return V1_ACCOUNTS_OFFSET;
  }

  // The v1 message begins at the start of the serialized data.
  @Override
  protected int messageOffset() {
    return 0;
  }

  @Override
  protected int messageLength() {
    return signaturesOffset;
  }

  @Override
  protected int signatureOffset(final int signerIndex) {
    return signaturesOffset + (signerIndex * SIGNATURE_LENGTH);
  }

  @Override
  protected void recordNumSignatures(final int numSignatures) {
  }

  @Override
  protected Transaction createTransaction(final List<Instruction> instructions) {
    final var builder = TxBuilder.createBuilder()
        .feePayer(feePayer)
        .addInstructions(instructions);
    // Carry over the ConfigValues so that derived transactions preserve the fee and resource
    // requests of this transaction instead of resetting them to the builder defaults.
    int offset = V1TransactionSkeleton.configValueOffset(data, PRIORITY_FEE_MASK);
    if (offset >= 0) {
      builder.priorityFeeLamports(ByteUtil.getInt64LE(data, offset));
    }
    offset = V1TransactionSkeleton.configValueOffset(data, COMPUTE_UNIT_LIMIT_MASK);
    builder.computeUnitLimit(offset < 0 ? 0 : ByteUtil.getInt32LE(data, offset));
    offset = V1TransactionSkeleton.configValueOffset(data, ACCOUNT_DATA_SIZE_LIMIT_MASK);
    builder.accountDataSizeLimit(offset < 0 ? 0 : ByteUtil.getInt32LE(data, offset));
    offset = V1TransactionSkeleton.configValueOffset(data, HEAP_SIZE_MASK);
    if (offset >= 0) {
      builder.heapSize(ByteUtil.getInt32LE(data, offset));
    }
    return builder.createTransaction();
  }

  // Returns the offset of the ConfigValue corresponding to the given TransactionConfigMask bits.
  private int configValueOffset(final int maskBits) {
    final int offset = V1TransactionSkeleton.configValueOffset(data, maskBits);
    if (offset < 0) {
      throw new IllegalStateException(
          "The TransactionConfigMask bits 0x" + Integer.toHexString(maskBits)
              + " are not set for this v1 transaction, re-create it with a TxBuilder instead."
      );
    }
    return offset;
  }

  @Override
  public Transaction setPriorityFeeLamports(final long priorityFeeLamports) {
    ByteUtil.putInt64LE(data, configValueOffset(PRIORITY_FEE_MASK), priorityFeeLamports);
    return this;
  }

  /// **Deliberate divergence from agave — do not "correct" this to 0.** An absent compute-unit-limit
  /// ConfigValue reads as 0 everywhere else in this library, matching SIMD-0385 and agave's
  /// `compute_unit_limit().unwrap_or(0)`, and [TransactionSkeleton#prototypeTransaction] preserves
  /// that 0 exactly. Pricing is a different operation from preservation, and 0 is not a value any
  /// usable transaction can carry: the compute meter *is* the limit, so a 0 budget fails on the
  /// first metered instruction, and only an empty or precompile-only transaction can succeed with
  /// one. Deriving a fee of 0 for a transaction that cannot execute is useless, so an unset limit is
  /// priced at the runtime maximum instead — which is also what [TxBuilder] itself writes into the
  /// slot unless the caller explicitly clears it, so this prices an absent slot at exactly what this
  /// library would have put there.
  ///
  /// agave has no counterpart to this conversion for v1: a v1 priority fee is an absolute lamport
  /// ConfigValue, never price × limit. The only related agave function is the inverse,
  /// `compute_unit_price_in_microlamports()`, which returns 0 on a 0 limit purely because that is
  /// what the division degenerates to.
  @Override
  public Transaction setPriorityFeeLamportsFromComputeUnitPrice(final long microLamportsPerComputeUnit) {
    final int offset = V1TransactionSkeleton.configValueOffset(data, COMPUTE_UNIT_LIMIT_MASK);
    final int computeUnitLimit = offset < 0
        ? TxBuilderImpl.MAX_COMPUTE_UNIT_LIMIT
        : ByteUtil.getInt32LE(data, offset);
    return setPriorityFeeLamports(
        TxBuilder.computeUnitPriceToPriorityFeeLamports(microLamportsPerComputeUnit, computeUnitLimit)
    );
  }

  @Override
  public Transaction setHeapSize(final int heapSize) {
    TxBuilderImpl.checkHeapSize(heapSize);
    ByteUtil.putInt32LE(data, configValueOffset(HEAP_SIZE_MASK), heapSize);
    return this;
  }

  @Override
  public Transaction setComputeUnitLimit(final int computeUnitLimit) {
    ByteUtil.putInt32LE(data, configValueOffset(COMPUTE_UNIT_LIMIT_MASK), computeUnitLimit);
    return this;
  }

  @Override
  public Transaction setAccountDataSizeLimit(final int accountDataSizeLimit) {
    ByteUtil.putInt32LE(data, configValueOffset(ACCOUNT_DATA_SIZE_LIMIT_MASK), accountDataSizeLimit);
    return this;
  }

  @Override
  public int version() {
    return data[0] & 0x7F;
  }

  @Override
  public int numSigners() {
    return data[1] & 0xFF;
  }

  @Override
  public boolean exceedsSizeLimit() {
    return size() > TxBuilderImpl.MAX_SERIALIZED_LENGTH_V1;
  }

  @Override
  public int numAccounts() {
    return data[V1_ACCOUNTS_OFFSET - 1] & 0xFF;
  }

  @Override
  public boolean exceedsSignatureLimit() {
    return numSigners() > TxBuilderImpl.MAX_V1_SIGNATURES;
  }

  @Override
  public boolean equals(final Object o) {
    return (o instanceof final V1Transaction that) && Arrays.equals(data, that.data);
  }
}
