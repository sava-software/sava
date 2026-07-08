package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.encoding.ByteUtil;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static software.sava.core.accounts.meta.AccountMeta.ACCOUNT_META_ARRAY_GENERATOR;
import static software.sava.core.encoding.CompactU16Encoding.decode;
import static software.sava.core.encoding.CompactU16Encoding.getByteLen;
import static software.sava.core.encoding.CompactU16Encoding.signedByte;

// Legacy and v0 transaction formats.
final class TransactionRecord extends BaseTransaction {

  static final int VERSIONED_MSG_HEADER_LENGTH = 1 + TxBuilderImpl.MSG_HEADER_LENGTH;
  static final int BASE_LOOKUP_TABLE_LEN = PublicKey.PUBLIC_KEY_LENGTH + 2;
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
        accounts.merge(meta.publicKey(), meta, TxBuilderImpl.MERGE_ACCOUNT_META);
      }
      final var programMeta = instruction.programId();
      accounts.merge(programMeta.publicKey(), programMeta, TxBuilderImpl.MERGE_ACCOUNT_META);
    }
    return serializedInstructionLength;
  }

  private static final Comparator<AccountMeta> VO_META_COMPARATOR = (am1, am2) -> {
    if (am1.feePayer()) {
      return -1;
    } else if (am2.feePayer()) {
      return 1;
    } else if (am1.signer() == am2.signer()) {
      if (am1.write() == am2.write()) {
        return am1.invoked() == am2.invoked() ? 0 : am1.invoked() ? -1 : 1;
      } else {
        return am1.write() ? -1 : 1;
      }
    } else {
      return am1.signer() ? -1 : 1;
    }
  };

  static AccountMeta[] sortV0Accounts(final Map<PublicKey, AccountMeta> mergedAccounts) {
    final AccountMeta[] accountMetas = mergedAccounts.values().toArray(ACCOUNT_META_ARRAY_GENERATOR);
    Arrays.sort(accountMetas, VO_META_COMPARATOR);
    return accountMetas;
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

  private static final byte REQUEST_HEAP_FRAME_DISCRIMINATOR = (byte) 1;
  private static final byte SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR = (byte) 2;
  private static final byte SET_LOADED_ACCOUNTS_DATA_SIZE_LIMIT_DISCRIMINATOR = (byte) 4;

  private Transaction setComputeBudgetValue(final byte discriminator, final byte[] ixData) {
    final var invokedComputeBudgetProgram = SolanaAccounts.MAIN_NET.invokedComputeBudgetProgram();
    final var ix = Instruction.createInstruction(invokedComputeBudgetProgram, List.of(), ixData);
    final var computeBudgetProgram = invokedComputeBudgetProgram.publicKey();
    for (int i = 0, numInstructions = instructions.size(); i < numInstructions; ++i) {
      final var instruction = instructions.get(i);
      if (computeBudgetProgram.equals(instruction.programId().publicKey())
          && instruction.len() > 0
          && instruction.data()[instruction.offset()] == discriminator) {
        return replaceInstruction(i, ix);
      }
    }
    return prependIx(ix);
  }

  private Transaction setComputeBudgetValue(final byte discriminator, final int value) {
    final byte[] ixData = new byte[1 + Integer.BYTES];
    ixData[0] = discriminator;
    ByteUtil.putInt32LE(ixData, 1, value);
    return setComputeBudgetValue(discriminator, ixData);
  }

  @Override
  public Transaction setPriorityFeeLamports(final long priorityFeeLamports) {
    throw new UnsupportedOperationException(
        "The legacy/v0 SetComputeUnitPrice compute budget instruction is priced in micro-lamports"
            + " per compute unit, not lamports, include a SetComputeUnitPrice instruction instead."
    );
  }

  @Override
  public Transaction setHeapSize(final int heapSize) {
    return setComputeBudgetValue(REQUEST_HEAP_FRAME_DISCRIMINATOR, heapSize);
  }

  @Override
  public Transaction setComputeUnitLimit(final int computeUnitLimit) {
    return setComputeBudgetValue(SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR, computeUnitLimit);
  }

  @Override
  public Transaction setAccountDataSizeLimit(final int accountDataSizeLimit) {
    return setComputeBudgetValue(SET_LOADED_ACCOUNTS_DATA_SIZE_LIMIT_DISCRIMINATOR, accountDataSizeLimit);
  }

  @Override
  public int version() {
    final int version = data[messageOffset] & 0xFF;
    return signedByte(version) ? version & 0x7F : BaseTransaction.VERSIONED_BIT_MASK;
  }

  @Override
  public boolean exceedsSizeLimit() {
    return size() > Transaction.MAX_SERIALIZED_LENGTH;
  }

  @Override
  public int numAccounts() {
    final boolean versioned = signedByte(data[messageOffset]);
    int numAccounts = decode(data, messageOffset + (versioned ? 4 : 3));
    if (versioned) {
      // Skip over the instructions to the address table lookups and add the indexed account counts.
      int o = recentBlockHashIndex + Transaction.BLOCK_HASH_LENGTH;
      final int numInstructions = decode(data, o);
      o += getByteLen(data, o);
      for (int i = 0, len; i < numInstructions; ++i) {
        ++o; // programId index
        len = decode(data, o);
        o += getByteLen(data, o) + len;
        len = decode(data, o);
        o += getByteLen(data, o) + len;
      }
      final int numTables = decode(data, o);
      o += getByteLen(data, o);
      for (int t = 0, len; t < numTables; ++t) {
        o += PublicKey.PUBLIC_KEY_LENGTH;
        len = decode(data, o);
        numAccounts += len;
        o += getByteLen(data, o) + len;
        len = decode(data, o);
        numAccounts += len;
        o += getByteLen(data, o) + len;
      }
    }
    return numAccounts;
  }

  @Override
  public boolean exceedsSignatureLimit() {
    return false;
  }

  @Override
  public boolean equals(final Object o) {
    return (o instanceof final TransactionRecord that) && Arrays.equals(data, that.data);
  }
}
