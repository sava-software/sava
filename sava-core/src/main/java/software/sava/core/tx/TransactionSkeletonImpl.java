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
  private final int serializedSignatureCount;
  private final int numIncludedAccounts;
  private final int accountsOffset;
  private final int recentBlockHashIndex;
  private final int lookupTablesOffset;
  private final PublicKey[] lookupTableAccounts;

  TransactionSkeletonImpl(final byte[] data,
                          final int version,
                          final int messageOffset,
                          final int serializedSignatureCount,
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
    this.serializedSignatureCount = serializedSignatureCount;
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

  // Returns the offset of the first matching compute budget instruction value, or 0 if not
  // present, exiting early on a match; compute budget instructions are conventionally first.
  private int computeBudgetValueOffset(final byte discriminator, final int valueLength) {
    final var computeBudgetProgram = SolanaAccounts.MAIN_NET.computeBudgetProgram();
    for (int i = 0, o = instructionsOffset; i < numInstructions; ++i) {
      final var programAccount = getProgramAccount(data[o++] & 0xFF);
      final int numAccounts = decode(data, o);
      o += getByteLen(data, o);
      o += numAccounts;
      final int numDataBytes = decode(data, o);
      o += getByteLen(data, o);

      // Guard the data length before reading the discriminator and value, a malformed compute
      // budget instruction must not read into the next instruction or past the message.
      if (numDataBytes > valueLength
          && computeBudgetProgram.equals(programAccount)
          && data[o] == discriminator) {
        return o + 1;
      }
      o += numDataBytes;
    }
    return 0;
  }

  @Override
  public long priorityFeeLamports() {
    // A single walk collecting the price and limit, exiting early once both are found; the
    // per-instruction default budget is only needed when no limit instruction is present.
    final var computeBudgetProgram = SolanaAccounts.MAIN_NET.computeBudgetProgram();
    int priceOffset = 0;
    int limitOffset = 0;
    // The runtime budgets every instruction, builtins at a far lower rate than the rest; compute
    // budget instructions are themselves builtins and are counted like any other.
    long defaultComputeUnitLimit = 0;
    for (int i = 0, o = instructionsOffset; i < numInstructions; ++i) {
      final var programAccount = getProgramAccount(data[o++] & 0xFF);
      final int numAccounts = decode(data, o);
      o += getByteLen(data, o);
      o += numAccounts;
      final int numDataBytes = decode(data, o);
      o += getByteLen(data, o);
      if (computeBudgetProgram.equals(programAccount)) {
        if (numDataBytes > Integer.BYTES) {
          if (priceOffset == 0
              && numDataBytes > Long.BYTES
              && data[o] == TransactionRecord.SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR) {
            priceOffset = o + 1;
          } else if (limitOffset == 0 && data[o] == TransactionRecord.SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR) {
            limitOffset = o + 1;
          }
          // A zero limit falls back to the per-instruction default below, and that needs the rest
          // of the walk, so only a limit that will actually be used lets the scan exit early.
          if (priceOffset != 0
              && limitOffset != 0
              && (ByteUtil.getInt32LE(data, limitOffset) & 0xFFFF_FFFFL) != 0) {
            break;
          }
        }
      }
      defaultComputeUnitLimit += BuiltinPrograms.defaultComputeUnitLimit(programAccount);
      o += numDataBytes;
    }
    if (priceOffset == 0) {
      return 0;
    }
    final long microLamportsPerComputeUnit = ByteUtil.getInt64LE(data, priceOffset);
    long computeUnitLimit = limitOffset == 0 ? 0 : ByteUtil.getInt32LE(data, limitOffset) & 0xFFFF_FFFFL;
    if (computeUnitLimit == 0) {
      computeUnitLimit = Math.min(defaultComputeUnitLimit, TxBuilderImpl.MAX_COMPUTE_UNIT_LIMIT);
    }
    return TxBuilder.computeUnitPriceToPriorityFeeLamports(microLamportsPerComputeUnit, (int) computeUnitLimit);
  }

  @Override
  public int computeUnitLimit() {
    final int offset = computeBudgetValueOffset(TransactionRecord.SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR, Integer.BYTES);
    return offset == 0 ? 0 : ByteUtil.getInt32LE(data, offset);
  }

  @Override
  public int accountDataSizeLimit() {
    final int offset = computeBudgetValueOffset(TransactionRecord.SET_LOADED_ACCOUNTS_DATA_SIZE_LIMIT_DISCRIMINATOR, Integer.BYTES);
    return offset == 0 ? 0 : ByteUtil.getInt32LE(data, offset);
  }

  @Override
  public int heapSize() {
    final int offset = computeBudgetValueOffset(TransactionRecord.REQUEST_HEAP_FRAME_DISCRIMINATOR, Integer.BYTES);
    return offset == 0 ? 0 : ByteUtil.getInt32LE(data, offset);
  }

  @Override
  public int serializedInstructionsLength() {
    int serializedInstructionsLength = 0;
    int o = instructionsOffset;
    for (int i = 0, numAccounts, len; i < numInstructions; ++i) {
      ++o; // raw u8 program index

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
      programAccountIndex = data[o++] & 0xFF;
      programs[i] = getProgramAccount(programAccountIndex);

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
    for (int i = 0, o = instructionsOffset, programAccountIndex, numIxAccounts, accountIndex;
         i < numInstructions; ++i) {
      programAccountIndex = data[o++] & 0xFF;
      requireIncludedProgramAccount(programAccountIndex);
      final var programAccount = invokedProgramAccount(accounts[programAccountIndex]);

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

  private void requireIncludedProgramAccount(final int accountIndex) {
    if (accountIndex >= numIncludedAccounts) {
      throw new IndexOutOfBoundsException(String.format(
          "Program account index %d is outside the %d included accounts.",
          accountIndex, numIncludedAccounts
      ));
    }
  }

  private PublicKey getProgramAccount(final int accountIndex) {
    requireIncludedProgramAccount(accountIndex);
    return accountKey(accountIndex);
  }

  @Override
  public Instruction[] parseInstructionsWithoutAccounts() {
    final var instructions = new Instruction[numInstructions];
    for (int i = 0, o = instructionsOffset, numIxAccounts, len; i < numInstructions; ++i) {
      final int programAccountIndex = data[o++] & 0xFF;
      final var programAccount = getProgramAccount(programAccountIndex);

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
      final int programAccountIndex = data[o++] & 0xFF;
      requireIncludedProgramAccount(programAccountIndex);

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
        instructions[d++] = createInstruction(getProgramAccount(programAccountIndex), Arrays.asList(ixAccounts), data, o, len);
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
      final int programAccountIndex = data[o++] & 0xFF;
      requireIncludedProgramAccount(programAccountIndex);

      numIxAccounts = decode(data, o);
      o += getByteLen(data, o);
      o += numIxAccounts;

      len = decode(data, o);
      o += getByteLen(data, o);

      if (discriminator.equals(data, o)) {
        instructions[d++] = createInstruction(getProgramAccount(programAccountIndex), NO_ACCOUNTS, data, o, len);
      }
      o += len;
    }
    return d == numInstructions
        ? instructions
        : Arrays.copyOfRange(instructions, 0, d);
  }

  @Override
  public Transaction createTransaction(final List<Instruction> instructions) {
    requireSignableSignatureLayout();
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

  private void requireSignableSignatureLayout() {
    if (serializedSignatureCount != numSignatures) {
      throw new IllegalStateException(String.format(
          "Serialized signature count %d does not match the message header's required signature count %d.",
          serializedSignatureCount, numSignatures
      ));
    }
    final int signaturePrefixLength = messageOffset - (serializedSignatureCount * SIGNATURE_LENGTH);
    if (signaturePrefixLength != 1) {
      throw new IllegalStateException(String.format(
          "Serialized signature count %d uses a %d-byte prefix; mutable transactions require a one-byte prefix.",
          serializedSignatureCount, signaturePrefixLength
      ));
    }
  }

  @Override
  public Transaction createTransaction(final List<Instruction> instructions,
                                       final AddressLookupTable lookupTable) {
    requireSignableSignatureLayout();
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
    requireSignableSignatureLayout();
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
}
