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

  static boolean isV1(final byte[] txData) {
    return (txData[0] & VERSIONED_BIT_MASK) == VERSIONED_BIT_MASK;
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
    final int configMask = ByteUtil.getInt32LE(data, V1_CONFIG_MASK_OFFSET);
    int o = V1_ACCOUNTS_OFFSET + ((data[V1_ACCOUNTS_OFFSET - 1] & 0xFF) << 5);
    if ((configMask & PRIORITY_FEE_MASK) == PRIORITY_FEE_MASK) {
      builder.priorityFeeLamports(ByteUtil.getInt64LE(data, o));
      o += Long.BYTES;
    }
    if ((configMask & COMPUTE_UNIT_LIMIT_MASK) != 0) {
      builder.computeUnitLimit(ByteUtil.getInt32LE(data, o));
      o += Integer.BYTES;
    } else {
      builder.computeUnitLimit(0);
    }
    if ((configMask & ACCOUNT_DATA_SIZE_LIMIT_MASK) != 0) {
      builder.accountDataSizeLimit(ByteUtil.getInt32LE(data, o));
      o += Integer.BYTES;
    } else {
      builder.accountDataSizeLimit(0);
    }
    if ((configMask & HEAP_SIZE_MASK) != 0) {
      builder.heapSize(ByteUtil.getInt32LE(data, o));
    }
    return builder.createTransaction();
  }

  // Returns the offset of the ConfigValue corresponding to the given TransactionConfigMask bits.
  private int configValueOffset(final int maskBits) {
    final int configMask = ByteUtil.getInt32LE(data, V1_CONFIG_MASK_OFFSET);
    if ((configMask & maskBits) != maskBits) {
      throw new IllegalStateException(
          "The TransactionConfigMask bits 0x" + Integer.toHexString(maskBits)
              + " are not set for this v1 transaction, re-create it with a TxBuilder instead."
      );
    }
    return V1_ACCOUNTS_OFFSET
        + ((data[V1_ACCOUNTS_OFFSET - 1] & 0xFF) << 5)
        + (Integer.bitCount(configMask & (Integer.lowestOneBit(maskBits) - 1)) << 2);
  }

  @Override
  public Transaction setPriorityFeeLamports(final long priorityFeeLamports) {
    ByteUtil.putInt64LE(data, configValueOffset(PRIORITY_FEE_MASK), priorityFeeLamports);
    return this;
  }

  @Override
  public Transaction setPriorityFeeLamportsFromComputeUnitPrice(final long microLamportsPerComputeUnit) {
    final int configMask = ByteUtil.getInt32LE(data, V1_CONFIG_MASK_OFFSET);
    final int computeUnitLimit = (configMask & COMPUTE_UNIT_LIMIT_MASK) != 0
        ? ByteUtil.getInt32LE(data, configValueOffset(COMPUTE_UNIT_LIMIT_MASK))
        : TxBuilderImpl.MAX_COMPUTE_UNIT_LIMIT;
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
