package software.sava.core.tx;

import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;

import java.util.Arrays;
import java.util.List;

import static software.sava.core.tx.TransactionRecord.NO_TABLES;
import static software.sava.core.tx.V1TransactionSkeleton.V1_ACCOUNTS_OFFSET;
import static software.sava.core.tx.V1TransactionSkeleton.V1_RECENT_BLOCK_HASH_INDEX;

// SIMD-0385 V1 Transaction format.
final class V1Transaction extends BaseTransaction {

  // Maximum serialized length of a v1 transaction as defined by SIMD-0385.
  public static final int MAX_SERIALIZED_LENGTH_V1 = 4_096;

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
    return TxBuilder.createBuilder().feePayer(feePayer).addInstructions(instructions).createTransaction();
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
    return size() > MAX_SERIALIZED_LENGTH_V1;
  }

  @Override
  public boolean equals(final Object o) {
    return (o instanceof final V1Transaction that) && Arrays.equals(data, that.data);
  }
}
