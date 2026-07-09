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
import static software.sava.core.encoding.CompactU16Encoding.*;

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

  /// Converts a v1 priority fee, denominated in lamports, into the equivalent legacy/v0
  /// SetComputeUnitPrice compute budget price in micro-lamports per compute unit for the given
  /// compute unit limit.
  ///
  /// The fee is converted to micro-lamports then divided by the compute unit limit, capped at the
  /// 1.4 million maximum, rounding up so that the prioritization fee charged by the runtime is at
  /// least the given lamports. Saturates at {@link Long#MAX_VALUE} if the price overflows.
  ///
  /// The inverse of {@link #computeUnitPriceToPriorityFeeLamports(long, int)}; converting the
  /// resulting price back yields the given fee whenever the fee scales to a whole number of
  /// micro-lamports per compute unit.
  ///
  /// @param priorityFeeLamports the priority fee in lamports
  /// @param computeUnitLimit    the compute unit limit the fee applies to
  /// @return the equivalent compute unit price in micro-lamports per compute unit
  public static long priorityFeeLamportsToComputeUnitPrice(final long priorityFeeLamports, final int computeUnitLimit) {
    final long cappedComputeUnitLimit = Math.min(computeUnitLimit & 0xFFFF_FFFFL, TxBuilderImpl.MAX_COMPUTE_UNIT_LIMIT);
    if (cappedComputeUnitLimit == 0 || priorityFeeLamports == 0) {
      return 0;
    } else if (priorityFeeLamports < 0 || priorityFeeLamports > Long.MAX_VALUE / 1_000_000) {
      return Long.MAX_VALUE;
    }
    final long microLamports = priorityFeeLamports * 1_000_000;
    // Round up so that the fee charged by the runtime is at least the given lamports.
    return (microLamports + (cappedComputeUnitLimit - 1)) / cappedComputeUnitLimit;
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
  private static final byte SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR = (byte) 3;
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

  private static byte[] computeBudgetIxData(final byte discriminator, final int value) {
    final byte[] ixData = new byte[1 + Integer.BYTES];
    ixData[0] = discriminator;
    ByteUtil.putInt32LE(ixData, 1, value);
    return ixData;
  }

  private static byte[] computeBudgetIxData(final byte discriminator, final long value) {
    final byte[] ixData = new byte[1 + Long.BYTES];
    ixData[0] = discriminator;
    ByteUtil.putInt64LE(ixData, 1, value);
    return ixData;
  }

  private Transaction setComputeBudgetValue(final byte discriminator, final int value) {
    return setComputeBudgetValue(discriminator, computeBudgetIxData(discriminator, value));
  }

  private Transaction setComputeBudgetValue(final byte discriminator, final long value) {
    return setComputeBudgetValue(discriminator, computeBudgetIxData(discriminator, value));
  }

  /// Replaces or prepends two compute budget instructions in a single pass, rebuilding the
  /// transaction only once rather than once per instruction.
  private Transaction setComputeBudgetValues(final byte discriminator1, final byte[] ixData1,
                                             final byte discriminator2, final byte[] ixData2) {
    final var invokedComputeBudgetProgram = SolanaAccounts.MAIN_NET.invokedComputeBudgetProgram();
    final var computeBudgetProgram = invokedComputeBudgetProgram.publicKey();
    final var ix1 = Instruction.createInstruction(invokedComputeBudgetProgram, List.of(), ixData1);
    final var ix2 = Instruction.createInstruction(invokedComputeBudgetProgram, List.of(), ixData2);

    final int numInstructions = instructions.size();
    final var updated = new Instruction[numInstructions];
    boolean found1 = false;
    boolean found2 = false;
    for (int i = 0; i < numInstructions; ++i) {
      final var instruction = instructions.get(i);
      if (computeBudgetProgram.equals(instruction.programId().publicKey()) && instruction.len() > 0) {
        final byte discriminator = instruction.data()[instruction.offset()];
        if (!found1 && discriminator == discriminator1) {
          updated[i] = ix1;
          found1 = true;
          continue;
        }
        if (!found2 && discriminator == discriminator2) {
          updated[i] = ix2;
          found2 = true;
          continue;
        }
      }
      updated[i] = instruction;
    }

    final int numToPrepend = (found1 ? 0 : 1) + (found2 ? 0 : 1);
    if (numToPrepend == 0) {
      return setBlockHash(createTransaction(Arrays.asList(updated)));
    }
    final var ixArray = new Instruction[numToPrepend + numInstructions];
    int i = 0;
    if (!found1) {
      ixArray[i++] = ix1;
    }
    if (!found2) {
      ixArray[i++] = ix2;
    }
    System.arraycopy(updated, 0, ixArray, i, numInstructions);
    return setBlockHash(createTransaction(Arrays.asList(ixArray)));
  }

  private int effectiveComputeUnitLimit() {
    final var computeBudgetProgram = SolanaAccounts.MAIN_NET.computeBudgetProgram();
    int numNonComputeBudgetInstructions = 0;
    for (final var instruction : instructions) {
      if (computeBudgetProgram.equals(instruction.programId().publicKey())) {
        if (instruction.len() > 0 && instruction.data()[instruction.offset()] == SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR) {
          return ByteUtil.getInt32LE(instruction.data(), instruction.offset() + 1);
        }
      } else {
        ++numNonComputeBudgetInstructions;
      }
    }
    return (int) Math.min(
        (long) TransactionSkeletonImpl.DEFAULT_INSTRUCTION_COMPUTE_UNIT_LIMIT * numNonComputeBudgetInstructions,
        TxBuilderImpl.MAX_COMPUTE_UNIT_LIMIT
    );
  }

  @Override
  public Transaction setPriorityFeeLamports(final long priorityFeeLamports) {
    final long microLamportsPerComputeUnit = priorityFeeLamportsToComputeUnitPrice(
        priorityFeeLamports, effectiveComputeUnitLimit()
    );
    return setComputeBudgetValue(SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR, microLamportsPerComputeUnit);
  }

  @Override
  public Transaction setPriorityFeeLamportsFromComputeUnitPrice(final long microLamportsPerComputeUnit) {
    return setComputeBudgetValue(SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR, microLamportsPerComputeUnit);
  }

  @Override
  public Transaction setPriorityFeeLamportsFromComputeUnitPrice(final long microLamportsPerComputeUnit,
                                                                final int computeUnitLimit) {
    return setComputeBudgetValues(
        SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR, computeBudgetIxData(SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR, computeUnitLimit),
        SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR, computeBudgetIxData(SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR, microLamportsPerComputeUnit)
    );
  }

  @Override
  public Transaction setHeapSize(final int heapSize) {
    TxBuilderImpl.checkHeapSize(heapSize);
    return setComputeBudgetValue(REQUEST_HEAP_FRAME_DISCRIMINATOR, heapSize);
  }

  @Override
  public Transaction setComputeUnitLimit(final int computeUnitLimit) {
    return setComputeBudgetValue(SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR, computeUnitLimit);
  }

  @Override
  public Transaction setAccountDataSizeLimit(final int accountDataSizeLimit) {
    // The runtime rejects a SetLoadedAccountsDataSizeLimit instruction with a value of 0 with
    // InvalidLoadedAccountsDataSizeLimit. Negative values would serialize as u32 values beyond
    // the 64MiB maximum.
    if (accountDataSizeLimit <= 0) {
      throw new IllegalArgumentException(
          "A loaded accounts data size limit must be greater than 0, was " + accountDataSizeLimit + '.'
      );
    }
    return setComputeBudgetValue(SET_LOADED_ACCOUNTS_DATA_SIZE_LIMIT_DISCRIMINATOR, accountDataSizeLimit);
  }

  @Override
  public int version() {
    final int version = data[messageOffset] & 0xFF;
    return signedByte(version) ? version & 0x7F : BaseTransaction.VERSIONED_BIT_MASK;
  }

  @Override
  public boolean exceedsSizeLimit() {
    return size() > 1232;
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
