package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.encoding.Base58;
import software.sava.core.encoding.ByteUtil;
import software.sava.core.programs.Discriminator;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static software.sava.core.tx.Instruction.createInstruction;
import static software.sava.core.tx.Transaction.BLOCK_HASH_LENGTH;
import static software.sava.core.tx.Transaction.SIGNATURE_LENGTH;
import static software.sava.core.tx.TxBuilderImpl.V1_CONFIG_MASK_LENGTH;
import static software.sava.core.tx.TxBuilderImpl.V1_INSTRUCTION_HEADER_LENGTH;

// SIMD-0385 Transaction V1 format skeleton.
final class V1TransactionSkeleton extends BaseTransactionSkeleton {

  // The v1 header and TransactionConfigMask are fixed width, so the recent block hash and the
  // accounts always begin at the same offsets within the serialized message.
  static final int V1_RECENT_BLOCK_HASH_INDEX = 1 /* VersionByte */ + TxBuilderImpl.MSG_HEADER_LENGTH + V1_CONFIG_MASK_LENGTH;
  static final int V1_ACCOUNTS_OFFSET = V1_RECENT_BLOCK_HASH_INDEX + BLOCK_HASH_LENGTH + 2 /* NumInstructions + NumAddresses */;

  // TransactionConfigMask bit positions, ordered ascending as serialized in the ConfigValues block.
  private static final int PRIORITY_FEE_MASK = 0b0000_0011; // Two bits per SIMD-0385.
  private static final int COMPUTE_UNIT_LIMIT_MASK = 0b0000_0100;
  private static final int ACCOUNT_DATA_SIZE_LIMIT_MASK = 0b0000_1000;
  private static final int HEAP_SIZE_MASK = 0b0001_0000;

  private final long priorityFeeLamports;
  private final int computeUnitLimit;
  private final int accountDataSizeLimit;
  private final int heapSize;

  private V1TransactionSkeleton(final byte[] data,
                                final int numSignatures,
                                final int numReadonlySignedAccounts,
                                final int numReadonlyUnsignedAccounts,
                                final int numIncludedAccounts,
                                final int numInstructions,
                                final int instructionsOffset,
                                final int[] invokedIndexes,
                                final long priorityFeeLamports,
                                final int computeUnitLimit,
                                final int accountDataSizeLimit,
                                final int heapSize) {
    super(
        data,
        1,
        numSignatures, numReadonlySignedAccounts, numReadonlyUnsignedAccounts,
        numInstructions, instructionsOffset, invokedIndexes,
        numIncludedAccounts
    );
    this.priorityFeeLamports = priorityFeeLamports;
    this.computeUnitLimit = computeUnitLimit;
    this.accountDataSizeLimit = accountDataSizeLimit;
    this.heapSize = heapSize;
  }

  static TransactionSkeleton deserialize(final byte[] data) {
    int o = 1;
    // LegacyHeader
    final int numRequiredSignatures = data[o++] & 0xFF;
    final int numReadonlySignedAccounts = data[o++] & 0xFF;
    final int numReadonlyUnsignedAccounts = data[o++] & 0xFF;

    // TransactionConfigMask (u32)
    final int configMask = ByteUtil.getInt32LE(data, o);
    o += V1_CONFIG_MASK_LENGTH;

    // LifetimeSpecifier (recent block hash) begins at the fixed V1_RECENT_BLOCK_HASH_INDEX.
    o += BLOCK_HASH_LENGTH;

    final int numInstructions = data[o++] & 0xFF;
    final int numIncludedAccounts = data[o++] & 0xFF;

    // Accounts begin at the fixed V1_ACCOUNTS_OFFSET.
    o += numIncludedAccounts << 5;

    // ConfigValues, ordered by ascending TransactionConfigMask bit position.
    final long priorityFeeLamports;
    if ((configMask & PRIORITY_FEE_MASK) != 0) {
      priorityFeeLamports = ByteUtil.getInt64LE(data, o);
      o += Long.BYTES;
    } else {
      priorityFeeLamports = 0L;
    }
    final int computeUnitLimit;
    if ((configMask & COMPUTE_UNIT_LIMIT_MASK) != 0) {
      computeUnitLimit = ByteUtil.getInt32LE(data, o);
      o += Integer.BYTES;
    } else {
      computeUnitLimit = 0;
    }
    final int accountDataSizeLimit;
    if ((configMask & ACCOUNT_DATA_SIZE_LIMIT_MASK) != 0) {
      accountDataSizeLimit = ByteUtil.getInt32LE(data, o);
      o += Integer.BYTES;
    } else {
      accountDataSizeLimit = 0;
    }
    final int heapSize;
    if ((configMask & HEAP_SIZE_MASK) != 0) {
      heapSize = ByteUtil.getInt32LE(data, o);
      o += Integer.BYTES;
    } else {
      heapSize = 0;
    }

    final int instructionsOffset = o;
    // The v1 format serializes all fixed-width instruction headers contiguously (followed by all
    // the instruction payloads), so the invoked program indexes can be read directly from the header
    // block. The payloads do not need to be walked here: the signatures are simply appended after
    // the message at the end of the transaction.
    final int[] invokedIndexes = new int[numInstructions];
    for (int i = 0; i < numInstructions; ++i) {
      // InstructionHeader: (u8 programIdIndex, u8 numAccounts, u16 LE numDataBytes)
      invokedIndexes[i] = data[instructionsOffset + (i * V1_INSTRUCTION_HEADER_LENGTH)] & 0xFF;
    }
    Arrays.sort(invokedIndexes);

    return new V1TransactionSkeleton(
        data,
        numRequiredSignatures, numReadonlySignedAccounts, numReadonlyUnsignedAccounts,
        numIncludedAccounts,
        numInstructions, instructionsOffset, invokedIndexes,
        priorityFeeLamports, computeUnitLimit, accountDataSizeLimit, heapSize
    );
  }

  @Override
  protected int accountsOffset() {
    return V1_ACCOUNTS_OFFSET;
  }

  @Override
  public int recentBlockHashIndex() {
    return V1_RECENT_BLOCK_HASH_INDEX;
  }

  public int configMask() {
    return data[4] & 0xFF;
  }

  @Override
  public long priorityFeeLamports() {
    return priorityFeeLamports;
  }

  @Override
  public int computeUnitLimit() {
    return computeUnitLimit;
  }

  @Override
  public int accountDataSizeLimit() {
    return accountDataSizeLimit;
  }

  @Override
  public int heapSize() {
    return heapSize;
  }

  @Override
  public String id() {
    final int signaturesOffset = data.length - (numSignatures * SIGNATURE_LENGTH);
    return Base58.encode(data, signaturesOffset, signaturesOffset + SIGNATURE_LENGTH);
  }

  @Override
  public int numIncludedAccounts() {
    return numAccounts;
  }

  @Override
  public PublicKey[] lookupTableAccounts() {
    return BaseTransactionSkeleton.NO_TABLES;
  }

  @Override
  public boolean isVersioned() {
    return true;
  }

  @Override
  public boolean isLegacy() {
    return false;
  }

  @Override
  public AccountMeta[] parseAccounts(final AddressLookupTable lookupTable) {
    return parseAccounts();
  }

  @Override
  public AccountMeta[] parseAccounts(final Map<PublicKey, AddressLookupTable> lookupTables) {
    return parseAccounts();
  }

  // The v1 format serializes all fixed-width instruction headers contiguously, immediately followed
  // by all the instruction payloads.
  private int firstInstructionCursor() {
    return instructionsOffset + (numInstructions * V1_INSTRUCTION_HEADER_LENGTH);
  }

  // InstructionHeader field accessors: (u8 programIdIndex, u8 numAccounts, u16 LE numDataBytes).
  private int programIdIndex(final int header) {
    return data[header] & 0xFF;
  }

  private int numIxAccounts(final int header) {
    return data[header + 1] & 0xFF;
  }

  private int numDataBytes(final int header) {
    return ByteUtil.getInt16LE(data, header + 2) & 0xFFFF;
  }

  @Override
  public int serializedInstructionsLength() {
    // The payload lengths are known from the fixed-width headers, so the payloads never need to be walked.
    int serializedInstructionsLength = numInstructions * V1_INSTRUCTION_HEADER_LENGTH;
    for (int i = 0, header = instructionsOffset; i < numInstructions; ++i, header += V1_INSTRUCTION_HEADER_LENGTH) {
      serializedInstructionsLength += numIxAccounts(header) + numDataBytes(header);
    }
    return serializedInstructionsLength;
  }

  @Override
  public PublicKey[] parseProgramAccounts() {
    final var programs = new PublicKey[numInstructions];
    for (int i = 0, header = instructionsOffset; i < numInstructions; ++i, header += V1_INSTRUCTION_HEADER_LENGTH) {
      programs[i] = accountKey(programIdIndex(header));
    }
    return programs;
  }

  @Override
  public Instruction[] parseInstructions(final AccountMeta[] accounts) {
    final var instructions = new Instruction[numInstructions];
    int cursor = firstInstructionCursor();
    for (int i = 0, header = instructionsOffset; i < numInstructions; ++i, header += V1_INSTRUCTION_HEADER_LENGTH) {
      final var programAccount = accounts[programIdIndex(header)];

      final int numIxAccounts = numIxAccounts(header);
      final var ixAccounts = new AccountMeta[numIxAccounts];
      for (int a = 0, accountIndex; a < numIxAccounts; ++a) {
        accountIndex = data[cursor++] & 0xFF;
        ixAccounts[a] = accountIndex < accounts.length ? accounts[accountIndex] : null;
      }

      final int numDataBytes = numDataBytes(header);
      instructions[i] = createInstruction(programAccount, Arrays.asList(ixAccounts), data, cursor, numDataBytes);
      cursor += numDataBytes;
    }
    return instructions;
  }

  @Override
  public Instruction[] parseInstructionsWithoutAccounts() {
    final var instructions = new Instruction[numInstructions];
    int cursor = firstInstructionCursor();
    for (int i = 0, header = instructionsOffset; i < numInstructions; ++i, header += V1_INSTRUCTION_HEADER_LENGTH) {
      final var programAccount = accountKey(programIdIndex(header));
      final int numDataBytes = numDataBytes(header);
      cursor += numIxAccounts(header);
      instructions[i] = createInstruction(programAccount, NO_ACCOUNTS, data, cursor, numDataBytes);
      cursor += numDataBytes;
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
    int cursor = firstInstructionCursor();
    for (int i = 0, header = instructionsOffset; i < numInstructions; ++i, header += V1_INSTRUCTION_HEADER_LENGTH) {
      final int numIxAccounts = numIxAccounts(header);
      final int numDataBytes = numDataBytes(header);
      final int dataOffset = cursor + numIxAccounts;
      if (discriminator.equals(data, dataOffset)) {
        final var ixAccounts = new AccountMeta[numIxAccounts];
        for (int a = 0, accountIndex; a < numIxAccounts; ++a) {
          accountIndex = data[cursor + a] & 0xFF;
          ixAccounts[a] = accountIndex < accounts.length ? accounts[accountIndex] : null;
        }
        instructions[d++] = createInstruction(accountKey(programIdIndex(header)), Arrays.asList(ixAccounts), data, dataOffset, numDataBytes);
      }
      cursor = dataOffset + numDataBytes;
    }
    return d == numInstructions
        ? instructions
        : Arrays.copyOfRange(instructions, 0, d);
  }

  @Override
  public Instruction[] filterInstructionsWithoutAccounts(final Discriminator discriminator) {
    final var instructions = new Instruction[numInstructions];
    int d = 0;
    int cursor = firstInstructionCursor();
    for (int i = 0, header = instructionsOffset; i < numInstructions; ++i, header += V1_INSTRUCTION_HEADER_LENGTH) {
      final int numDataBytes = numDataBytes(header);
      final int dataOffset = cursor + numIxAccounts(header);
      if (discriminator.equals(data, dataOffset)) {
        instructions[d++] = createInstruction(accountKey(programIdIndex(header)), NO_ACCOUNTS, data, dataOffset, numDataBytes);
      }
      cursor = dataOffset + numDataBytes;
    }
    return d == numInstructions
        ? instructions
        : Arrays.copyOfRange(instructions, 0, d);
  }

  @Override
  public Transaction createTransaction(final LookupTableAccountMeta[] tableAccountMetas) {
    return createTransaction();
  }

  @Override
  public Transaction createTransaction(final List<Instruction> instructions, final AddressLookupTable lookupTable) {
    return createTransaction(instructions);
  }

  @Override
  public Transaction createTransaction(final List<Instruction> instructions,
                                       final LookupTableAccountMeta[] tableAccountMetas) {
    return createTransaction(instructions);
  }

  @Override
  public Transaction createTransaction(final List<Instruction> instructions) {
    return new V1Transaction(
        AccountMeta.createFeePayer(feePayer()),
        instructions,
        data,
        data.length - (numSignatures * SIGNATURE_LENGTH)
    );
  }
}
