package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.encoding.Base58;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.programs.Discriminator;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.meta.AccountMeta.createRead;
import static software.sava.core.accounts.meta.AccountMeta.createWrite;
import static software.sava.core.encoding.CompactU16Encoding.decode;
import static software.sava.core.encoding.CompactU16Encoding.getByteLen;
import static software.sava.core.tx.Instruction.createInstruction;
import static software.sava.core.tx.Transaction.SIGNATURE_LENGTH;

// Skeleton for legacy and v0 transaction messages.
final class TransactionSkeletonImpl extends BaseTransactionSkeleton {

  private final int messageOffset;
  private final int numIncludedAccounts;
  private final int accountsOffset;
  private final int recentBlockHashIndex;
  private final int lookupTablesOffset;
  private final PublicKey[] lookupTableAccounts;

  TransactionSkeletonImpl(final byte[] data,
                          final int version,
                          final int messageOffset,
                          final int numSignatures,
                          final int numReadonlySignedAccounts,
                          final int numReadonlyUnsignedAccounts,
                          final int numIncludedAccounts,
                          final int accountsOffset,
                          final int recentBlockHashIndex,
                          final int numInstructions,
                          final int instructionsOffset,
                          final int[] invokedIndexes,
                          final int lookupTablesOffset,
                          final PublicKey[] lookupTableAccounts,
                          final int numAccounts) {
    super(
        data,
        version,
        numSignatures, numReadonlySignedAccounts, numReadonlyUnsignedAccounts,
        numInstructions, instructionsOffset, invokedIndexes,
        numAccounts
    );
    this.messageOffset = messageOffset;
    this.numIncludedAccounts = numIncludedAccounts;
    this.accountsOffset = accountsOffset;
    this.recentBlockHashIndex = recentBlockHashIndex;
    this.lookupTablesOffset = lookupTablesOffset;
    this.lookupTableAccounts = lookupTableAccounts;
  }

  @Override
  protected int accountsOffset() {
    return accountsOffset;
  }

  @Override
  public int recentBlockHashIndex() {
    return recentBlockHashIndex;
  }

  @Override
  public String id() {
    return Base58.encode(data, 1, 1 + SIGNATURE_LENGTH);
  }

  private int computeBudgetValueOffset(final byte discriminator) {
    final var computeBudgetProgram = SolanaAccounts.MAIN_NET.computeBudgetProgram();
    for (int i = 0, o = instructionsOffset; i < numInstructions; ++i) {
      final var programAccount = super.accountKey(decode(data, o));
      o += getByteLen(data, o);
      final int numAccounts = decode(data, o);
      o += getByteLen(data, o);
      o += numAccounts;
      final int numDataBytes = decode(data, o);
      o += getByteLen(data, o);

      if (computeBudgetProgram.equals(programAccount) && data[o] == discriminator) {
        return o + 1;
      }
      o += numDataBytes;
    }
    return 0;
  }

  // Runtime default compute unit limit granted per non-compute-budget instruction when no
  // SetComputeUnitLimit instruction is present. An estimate; per SIMD-0170 the runtime only
  // allocates 3,000 units for each builtin program instruction.
  static final int DEFAULT_INSTRUCTION_COMPUTE_UNIT_LIMIT = 200_000;

  private int numNonComputeBudgetInstructions() {
    final var computeBudgetProgram = SolanaAccounts.MAIN_NET.computeBudgetProgram();
    int count = 0;
    for (int i = 0, o = instructionsOffset; i < numInstructions; ++i) {
      final var programAccount = super.accountKey(decode(data, o));
      o += getByteLen(data, o);
      final int numAccounts = decode(data, o);
      o += getByteLen(data, o);
      o += numAccounts;
      final int numDataBytes = decode(data, o);
      o += getByteLen(data, o);
      o += numDataBytes;
      if (!computeBudgetProgram.equals(programAccount)) {
        ++count;
      }
    }
    return count;
  }

  @Override
  public long priorityFeeLamports() {
    final int priceOffset = computeBudgetValueOffset((byte) 3);
    if (priceOffset <= 0) {
      return 0;
    }
    final long microLamportsPerComputeUnit = ByteUtil.getInt64LE(data, priceOffset);
    long computeUnitLimit = computeUnitLimit() & 0xFFFF_FFFFL;
    if (computeUnitLimit == 0) {
      computeUnitLimit = (long) DEFAULT_INSTRUCTION_COMPUTE_UNIT_LIMIT * numNonComputeBudgetInstructions();
    }
    computeUnitLimit = Math.min(computeUnitLimit, TxBuilderImpl.MAX_COMPUTE_UNIT_LIMIT);
    // Round up to whole lamports, mirroring the runtime's prioritization fee calculation.
    return ((microLamportsPerComputeUnit * computeUnitLimit) + 999_999) / 1_000_000;
  }

  @Override
  public int computeUnitLimit() {
    final int offset = computeBudgetValueOffset((byte) 2);
    return offset > 0 ? ByteUtil.getInt32LE(data, offset) : 0;
  }

  @Override
  public int accountDataSizeLimit() {
    final int offset = computeBudgetValueOffset((byte) 4);
    return offset > 0 ? ByteUtil.getInt32LE(data, offset) : 0;
  }

  @Override
  public int heapSize() {
    final int offset = computeBudgetValueOffset((byte) 1);
    return offset > 0 ? ByteUtil.getInt32LE(data, offset) : 0;
  }

  @Override
  public int serializedInstructionsLength() {
    int serializedInstructionsLength = 0;
    int o = instructionsOffset;
    for (int i = 0, numAccounts, len; i < numInstructions; ++i) {
      numAccounts = decode(data, o);
      o += getByteLen(data, o);
      o += numAccounts;

      len = decode(data, o);
      o += getByteLen(data, o);
      o += len;

      serializedInstructionsLength += 1 // programId index
          + getByteLen(numAccounts) + numAccounts + getByteLen(len) + len;
    }
    return serializedInstructionsLength;
  }

  @Override
  public int numIncludedAccounts() {
    return numIncludedAccounts;
  }

  @Override
  public PublicKey[] lookupTableAccounts() {
    return lookupTableAccounts;
  }

  @Override
  public boolean isVersioned() {
    return version != BaseTransaction.VERSIONED_BIT_MASK;
  }

  @Override
  public boolean isLegacy() {
    return version == BaseTransaction.VERSIONED_BIT_MASK;
  }

  @Override
  public AccountMeta[] parseAccounts(final AddressLookupTable lookupTable) {
    return isLegacy() || lookupTable == null
        ? parseAccounts()
        : parseAccounts(Map.of(lookupTable.address(), lookupTable));
  }

  @Override
  public AccountMeta[] parseAccounts(final Map<PublicKey, AddressLookupTable> lookupTables) {
    if (isLegacy()) {
      return parseAccounts();
    }
    final var accounts = new AccountMeta[numAccounts];
    int a = parseVersionedIncludedAccounts(accounts);

    // Parse Writes
    int o = lookupTablesOffset;
    for (final var lookupTableKey : lookupTableAccounts) {
      final var lookupTable = lookupTables.get(lookupTableKey);
      o += PUBLIC_KEY_LENGTH;
      final int numWriteIndexes = decode(data, o);
      o += getByteLen(data, o);
      for (int w = 0; w < numWriteIndexes; ++w, ++a, ++o) {
        accounts[a] = createWrite(lookupTable.account(data[o] & 0xFF));
      }

      final int numReadIndexes = decode(data, o);
      o += getByteLen(data, o);
      o += numReadIndexes;
    }

    // Parse Reads
    o = lookupTablesOffset;
    for (final var lookupTableKey : lookupTableAccounts) {
      final var lookupTable = lookupTables.get(lookupTableKey);
      o += PUBLIC_KEY_LENGTH;
      final int numWriteIndexes = decode(data, o);
      o += getByteLen(data, o);
      o += numWriteIndexes;

      final int numReadIndexes = decode(data, o);
      o += getByteLen(data, o);
      for (int r = 0; r < numReadIndexes; ++r, ++a, ++o) {
        accounts[a] = createRead(lookupTable.account(data[o] & 0xFF));
      }
    }
    return accounts;
  }

  @Override
  public PublicKey[] parseProgramAccounts() {
    final var programs = new PublicKey[numInstructions];
    for (int i = 0, o = instructionsOffset, programAccountIndex, numIxAccounts, len; i < numInstructions; ++i) {
      programAccountIndex = decode(data, o);
      o += getByteLen(data, o);
      programs[i] = accountKey(programAccountIndex);

      numIxAccounts = decode(data, o);
      o += getByteLen(data, o);
      o += numIxAccounts;

      len = decode(data, o);
      o += getByteLen(data, o);
      o += len;
    }
    return programs;
  }

  @Override
  public Instruction[] parseInstructions(final AccountMeta[] accounts) {
    final var instructions = new Instruction[numInstructions];
    for (int i = 0, o = instructionsOffset, numIxAccounts, accountIndex; i < numInstructions; ++i) {
      final var programAccount = accounts[decode(data, o)];
      o += getByteLen(data, o);

      numIxAccounts = decode(data, o);
      final var ixAccounts = new AccountMeta[numIxAccounts];
      o += getByteLen(data, o);
      for (int a = 0; a < numIxAccounts; ++a) {
        accountIndex = data[o++] & 0xFF;
        ixAccounts[a] = accountIndex < accounts.length ? accounts[accountIndex] : null;
      }

      final int len = decode(data, o);
      o += getByteLen(data, o);
      instructions[i] = createInstruction(programAccount, Arrays.asList(ixAccounts), data, o, len);
      o += len;
    }
    return instructions;
  }

  @Override
  public Instruction[] parseInstructionsWithoutAccounts() {
    final var instructions = new Instruction[numInstructions];
    for (int i = 0, o = instructionsOffset, numIxAccounts, len; i < numInstructions; ++i) {
      final int programAccountIndex = decode(data, o);
      o += getByteLen(data, o);
      final var programAccount = accountKey(programAccountIndex);

      numIxAccounts = decode(data, o);
      o += getByteLen(data, o);
      o += numIxAccounts;

      len = decode(data, o);
      o += getByteLen(data, o);
      instructions[i] = createInstruction(programAccount, NO_ACCOUNTS, data, o, len);
      o += len;
    }
    return instructions;
  }

  @Override
  public Instruction[] parseInstructionsWithoutTableAccounts() {
    final var accounts = new AccountMeta[numAccounts];
    parseVersionedIncludedAccounts(accounts);
    return parseInstructions(accounts);
  }

  @Override
  public Instruction[] filterInstructions(final AccountMeta[] accounts, final Discriminator discriminator) {
    final var instructions = new Instruction[numInstructions];
    int d = 0;
    for (int i = 0, o = instructionsOffset, numIxAccounts, len; i < numInstructions; ++i) {
      final int programAccountIndex = decode(data, o);
      o += getByteLen(data, o);

      numIxAccounts = decode(data, o);
      o += getByteLen(data, o);
      int accountsOffset = o;
      o += numIxAccounts;

      len = decode(data, o);
      o += getByteLen(data, o);

      if (discriminator.equals(data, o)) {
        final var ixAccounts = new AccountMeta[numIxAccounts];
        for (int a = 0; a < numIxAccounts; ++a) {
          final int accountIndex = data[accountsOffset++] & 0xFF;
          ixAccounts[a] = accountIndex < accounts.length ? accounts[accountIndex] : null;
        }
        instructions[d++] = createInstruction(accountKey(programAccountIndex), Arrays.asList(ixAccounts), data, o, len);
      }
      o += len;
    }
    return d == numInstructions
        ? instructions
        : Arrays.copyOfRange(instructions, 0, d);
  }

  @Override
  public Instruction[] filterInstructionsWithoutAccounts(final Discriminator discriminator) {
    final var instructions = new Instruction[numInstructions];
    int d = 0;
    for (int i = 0, o = instructionsOffset, numIxAccounts, len; i < numInstructions; ++i) {
      final int programAccountIndex = decode(data, o);
      o += getByteLen(data, o);

      numIxAccounts = decode(data, o);
      o += getByteLen(data, o);
      o += numIxAccounts;

      len = decode(data, o);
      o += getByteLen(data, o);

      if (discriminator.equals(data, o)) {
        instructions[d++] = createInstruction(accountKey(programAccountIndex), NO_ACCOUNTS, data, o, len);
      }
      o += len;
    }
    return d == numInstructions
        ? instructions
        : Arrays.copyOfRange(instructions, 0, d);
  }

  @Override
  public Transaction createTransaction(final List<Instruction> instructions,
                                       final AddressLookupTable lookupTable) {
    return new TransactionRecord(
        AccountMeta.createFeePayer(feePayer()),
        instructions,
        lookupTable,
        TransactionRecord.NO_TABLES,
        data,
        numSignatures,
        messageOffset,
        accountsOffset,
        recentBlockHashIndex
    );
  }

  @Override
  public Transaction createTransaction(final List<Instruction> instructions,
                                       final LookupTableAccountMeta[] tableAccountMetas) {
    return new TransactionRecord(
        AccountMeta.createFeePayer(feePayer()),
        instructions,
        null,
        tableAccountMetas,
        data,
        numSignatures,
        messageOffset,
        accountsOffset,
        recentBlockHashIndex
    );
  }

  @Override
  public Transaction createTransaction(final LookupTableAccountMeta[] tableAccountMetas) {
    final var accounts = parseAccounts(Arrays.stream(tableAccountMetas).map(LookupTableAccountMeta::lookupTable));
    return createTransaction(accounts, tableAccountMetas);
  }

  @Override
  public Transaction createTransaction(final List<Instruction> instructions) {
    return new TransactionRecord(
        AccountMeta.createFeePayer(feePayer()),
        instructions,
        null,
        TransactionRecord.NO_TABLES,
        data,
        numSignatures,
        messageOffset,
        accountsOffset,
        recentBlockHashIndex
    );
  }
}
