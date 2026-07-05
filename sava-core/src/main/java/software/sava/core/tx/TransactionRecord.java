package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static software.sava.core.encoding.CompactU16Encoding.signedByte;

// Legacy and v0 transaction formats.
final class TransactionRecord extends BaseTransaction {

  static final LookupTableAccountMeta[] NO_TABLES = new LookupTableAccountMeta[0];

  private final AddressLookupTable lookupTable;
  private final LookupTableAccountMeta[] tableAccountMetas;
  private final int numSigners;
  private final int messageOffset;
  private final int accountsOffset;
  private final int recentBlockHashIndex;

  TransactionRecord(final AccountMeta feePayer,
                    final List<Instruction> instructions,
                    final AddressLookupTable lookupTable,
                    final LookupTableAccountMeta[] tableAccountMetas,
                    final byte[] data,
                    final int numSigners,
                    final int messageOffset,
                    final int accountsOffset,
                    final int recentBlockHashIndex) {
    super(feePayer, instructions, data);
    this.lookupTable = lookupTable;
    this.tableAccountMetas = tableAccountMetas;
    this.numSigners = numSigners;
    this.messageOffset = messageOffset;
    this.accountsOffset = accountsOffset;
    this.recentBlockHashIndex = recentBlockHashIndex;
  }

  static int mergeAccounts(final AccountMeta feePayer,
                           final Map<PublicKey, AccountMeta> accounts,
                           final List<Instruction> instructions) {
    final int numInstructions = instructions.size();
    if (numInstructions == 0) {
      throw new IllegalArgumentException("No instructions provided");
    }
    if (feePayer != null) {
      accounts.put(feePayer.publicKey(), feePayer);
    }
    int serializedInstructionLength = 0;
    for (final var instruction : instructions) {
      serializedInstructionLength += instruction.serializedLength();
      for (final var meta : instruction.accounts()) {
        accounts.merge(meta.publicKey(), meta, Transaction.MERGE_ACCOUNT_META);
      }
      final var programMeta = instruction.programId();
      accounts.merge(programMeta.publicKey(), programMeta, Transaction.MERGE_ACCOUNT_META);
    }
    return serializedInstructionLength;
  }

  @Override
  public AddressLookupTable lookupTable() {
    return lookupTable;
  }

  @Override
  public LookupTableAccountMeta[] tableAccountMetas() {
    return tableAccountMetas;
  }

  @Override
  protected int recentBlockHashIndex() {
    return recentBlockHashIndex;
  }

  @Override
  protected int accountsOffset() {
    return accountsOffset;
  }

  @Override
  public int numSigners() {
    return numSigners;
  }

  @Override
  public int messageOffset() {
    return messageOffset;
  }

  @Override
  protected int messageLength() {
    return data.length - messageOffset;
  }

  @Override
  protected int signatureOffset(final int signerIndex) {
    return 1 + (signerIndex * SIGNATURE_LENGTH);
  }

  @Override
  protected void recordNumSignatures(final int numSignatures) {
    this.data[0] = (byte) numSignatures;
  }

  @Override
  protected Transaction createTransaction(final List<Instruction> instructions) {
    return Transaction.createTx(feePayer, instructions, lookupTable, tableAccountMetas);
  }

  @Override
  public int version() {
    final int version = data[messageOffset] & 0xFF;
    return signedByte(version) ? version & 0x7F : VERSIONED_BIT_MASK;
  }

  @Override
  public String getBase58Id() {
    return Transaction.getBase58Id(this.data);
  }

  @Override
  public byte[] getId() {
    return Transaction.getId(this.data);
  }

  @Override
  public boolean equals(final Object o) {
    return (o instanceof final TransactionRecord that) && Arrays.equals(data, that.data);
  }
}
