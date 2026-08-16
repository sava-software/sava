package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.ByteUtil;

import java.util.*;

import static software.sava.core.accounts.lookup.AccountIndexLookupTableEntry.indexOfOrThrow;

final class TxBuilderImpl implements TxBuilder {

  // ComputeBudgetProgram instructions configure a legacy/v0 transaction, but a v1 transaction is
  // configured by its ConfigValues; per SIMD-0385 the v1 runtime processes them as no-ops which
  // still consume compute units. Prototyping carries their values over as ConfigValues instead,
  // so the instructions themselves are dropped.
  static Instruction[] withoutComputeBudgetInstructions(final Instruction[] instructions) {
    final var computeBudgetProgram = SolanaAccounts.MAIN_NET.computeBudgetProgram();
    int numRetained = 0;
    final var retained = new Instruction[instructions.length];
    for (final var instruction : instructions) {
      if (!computeBudgetProgram.equals(instruction.programId().publicKey())) {
        retained[numRetained++] = instruction;
      }
    }
    return numRetained == instructions.length
        ? instructions
        : Arrays.copyOfRange(retained, 0, numRetained);
  }

  static final int MAX_SERIALIZED_LENGTH_V1 = 4_096;
  // SIMD-0385 Transaction V1 format.
  // The version byte that distinguishes a v1 transaction from the legacy and v0 formats.
  static final byte V1_VERSION_BYTE = (byte) 129;
  // Length, in bytes, of the v1 TransactionConfigMask field.
  static final int V1_CONFIG_MASK_LENGTH = 4;
  // Length, in bytes, of a single v1 InstructionHeader: (u8 programIdIndex, u8 numAccounts, u16 numDataBytes).
  static final int V1_INSTRUCTION_HEADER_LENGTH = 4;
  // Maximum number of instructions and accounts permitted in a v1 transaction, the same limits
  // Transaction#exceedsInstructionLimit and Transaction#exceedsAccountLimit are checked against.
  static final int MAX_V1_INSTRUCTIONS = BaseTransaction.MAX_INSTRUCTIONS;
  static final int MAX_V1_ACCOUNTS = Transaction.MAX_ACCOUNTS;
  // Maximum number of signatures permitted in a v1 transaction.
  static final int MAX_V1_SIGNATURES = 12;
  // Wire-field ceilings, distinct from the network limits above. `strict` decides whether a
  // transaction is acceptable to the network; these decide whether the bytes can be written at all,
  // so they hold either way. Truncating one of these counts would emit a header block that no longer
  // describes its own payload block — every later instruction offset moves, and the message cannot
  // be read back — which is a worse outcome than the over-limit-but-coherent bytes a relaxed builder
  // is meant to produce.
  // Per-instruction limit imposed by the u8 account count header field.
  private static final int MAX_V1_INSTRUCTION_ACCOUNTS = 0xFF;
  // Limits imposed by the u8 NumInstructions and NumAddresses fields.
  private static final int MAX_ENCODABLE_V1_INSTRUCTIONS = 0xFF;
  private static final int MAX_ENCODABLE_V1_ACCOUNTS = 0xFF;
  // Per-instruction limit imposed by the u16 data length header field.
  private static final int MAX_V1_INSTRUCTION_DATA_LENGTH = 0xFFFF;
  private static final int MIN_HEAP_SIZE = 32 * 1_024;
  private static final int MAX_HEAP_SIZE = 256 * 1_024;
  static final int MAX_COMPUTE_UNIT_LIMIT = 1_400_000;
  static final int MAX_ACCOUNT_DATA_SIZE_LIMIT = 64 * 1_024 * 1_024;

  private boolean strict;
  private AccountMeta feePayer;
  private List<Instruction> instructions;
  private long priorityFeeLamports;
  private int computeUnitLimit;
  private int accountDataSizeLimit;
  private int heapSize;

  TxBuilderImpl() {
    strict = true;
    computeUnitLimit = MAX_COMPUTE_UNIT_LIMIT;
    accountDataSizeLimit = MAX_ACCOUNT_DATA_SIZE_LIMIT;
  }

  @Override
  public boolean strict() {
    return strict;
  }

  @Override
  public void strict(final boolean strict) {
    this.strict = strict;
  }

  @Override
  public AccountMeta feePayer() {
    return feePayer;
  }

  @Override
  public TxBuilder feePayer(final PublicKey feePayer) {
    return feePayer(feePayer == null ? null : AccountMeta.createFeePayer(feePayer));
  }

  @Override
  public TxBuilder feePayer(final AccountMeta feePayer) {
    this.feePayer = feePayer;
    return this;
  }

  @Override
  public TxBuilder addInstruction(final Instruction instruction) {
    if (this.instructions == null) {
      this.instructions = new ArrayList<>();
    }
    this.instructions.add(instruction);
    return this;
  }

  @Override
  public TxBuilder addInstructions(final SequencedCollection<Instruction> instructions) {
    if (this.instructions == null) {
      // Copy rather than alias. Aliasing the caller's collection leaves the builder unable to accept
      // a later addInstruction/insertInstruction when it was handed a fixed-size view such as
      // Arrays.asList, and lets setInstruction write through to the caller's array — reachable from
      // prototypeTransaction, which passes an Instruction[].
      this.instructions = new ArrayList<>(instructions);
    } else {
      this.instructions.addAll(instructions);
    }
    return this;
  }

  @Override
  public TxBuilder setInstruction(final int index, final Instruction instruction) {
    if (this.instructions == null) {
      if (index != 0) {
        throw new IndexOutOfBoundsException(String.format("Index %s out of bounds for length 0", index));
      }
      this.instructions = new ArrayList<>();
      this.instructions.add(instruction);
    } else {
      this.instructions.set(index, instruction);
    }
    return this;
  }

  @Override
  public TxBuilder insertInstruction(final int index, final Instruction instruction) {
    if (this.instructions == null) {
      if (index != 0) {
        throw new IndexOutOfBoundsException(String.format("Index %s out of bounds for length 0", index));
      }
      this.instructions = new ArrayList<>();
      this.instructions.add(instruction);
    } else {
      this.instructions.add(index, instruction);
    }
    return this;
  }

  @Override
  public long priorityFeeLamports() {
    return priorityFeeLamports;
  }

  @Override
  public TxBuilder priorityFeeLamports(final long priorityFeeLamports) {
    this.priorityFeeLamports = priorityFeeLamports;
    return this;
  }

  @Override
  public TxBuilder priorityFeeLamportsFromComputeUnitPrice(final long microLamportsPerComputeUnit) {
    return priorityFeeLamports(TxBuilder.computeUnitPriceToPriorityFeeLamports(microLamportsPerComputeUnit, computeUnitLimit()));
  }

  @Override
  public int computeUnitLimit() {
    return computeUnitLimit;
  }

  @Override
  public TxBuilder computeUnitLimit(final int computeUnitLimit) {
    this.computeUnitLimit = computeUnitLimit;
    return this;
  }

  @Override
  public int accountDataSizeLimit() {
    return accountDataSizeLimit;
  }

  @Override
  public TxBuilder accountDataSizeLimit(final int accountDataSizeLimit) {
    this.accountDataSizeLimit = accountDataSizeLimit;
    return this;
  }

  @Override
  public int heapSize() {
    return heapSize;
  }

  static void checkHeapSize(final int heapSize) {
    if (heapSize < MIN_HEAP_SIZE || heapSize > MAX_HEAP_SIZE || heapSize % 1_024 != 0) {
      throw new IllegalArgumentException(
          "A requested heap size must be a multiple of 1KiB in the inclusive range [32KiB, 256KiB]."
      );
    }
  }

  @Override
  public TxBuilder heapSize(final int heapSize) {
    // 0 clears the request.
    if (strict && heapSize != 0) {
      checkHeapSize(heapSize);
    }
    this.heapSize = heapSize;
    return this;
  }

  /// Builds a SIMD-0385 v1 transaction from the configured fee payer and instructions.
  @Override
  public Transaction createTransaction() {
    if (instructions == null) {
      throw new IllegalStateException("No instructions provided");
    }
    final int numInstructions = instructions.size();
    if (strict) {
      if (numInstructions == 0) {
        throw new IllegalArgumentException("No instructions provided");
      } else if (numInstructions > MAX_V1_INSTRUCTIONS) {
        throw new IllegalStateException("A v1 transaction may not reference more than " + MAX_V1_INSTRUCTIONS + " instructions.");
      }
    } else if (numInstructions > MAX_ENCODABLE_V1_INSTRUCTIONS) {
      throw new IllegalStateException("A v1 NumInstructions field cannot encode more than " + MAX_ENCODABLE_V1_INSTRUCTIONS + " instructions.");
    }

    final var accounts = HashMap.<PublicKey, AccountMeta>newHashMap(MAX_V1_ACCOUNTS);
    final int instructionPayloadLength = mergeAccounts(feePayer, accounts, instructions);
    final var sortedAccounts = TransactionRecord.sortLegacyAccounts(accounts);

    final int numAccounts = sortedAccounts.length;
    if (strict) {
      if (numAccounts > MAX_V1_ACCOUNTS) {
        throw new IllegalStateException("A v1 transaction may not reference more than " + MAX_V1_ACCOUNTS + " accounts.");
      }
    } else if (numAccounts > MAX_ENCODABLE_V1_ACCOUNTS) {
      throw new IllegalStateException("A v1 NumAddresses field cannot encode more than " + MAX_ENCODABLE_V1_ACCOUNTS + " accounts.");
    }

    final var feePayer = sortedAccounts[0];
    if (!feePayer.feePayer()) {
      throw new IllegalStateException("Fee payer must be the first account in the transaction.");
    }

    int numRequiredSignatures = 1;
    int numReadonlySignedAccounts = 0;
    int a = 1;
    for (; a < numAccounts; ++a) {
      final var account = sortedAccounts[a];
      if (account.signer()) {
        ++numRequiredSignatures;
        if (!account.write()) {
          ++numReadonlySignedAccounts;
        }
      } else {
        break;
      }
    }
    if (strict && numRequiredSignatures > MAX_V1_SIGNATURES) {
      throw new IllegalStateException("A v1 transaction may not require more than " + MAX_V1_SIGNATURES + " signatures.");
    }
    for (; a < numAccounts; ++a) {
      final var account = sortedAccounts[a];
      if (!account.write()) {
        break;
      }
    }
    final int numReadonlyUnsignedAccounts = sortedAccounts.length - a;

    int configMask = 0;
    if (this.priorityFeeLamports != 0) {
      configMask |= 0b0000_0011;
    }
    if (this.computeUnitLimit != 0) {
      configMask |= 0b0000_0100;
    }
    if (this.accountDataSizeLimit != 0) {
      configMask |= 0b0000_1000;
    }
    if (this.heapSize != 0) {
      configMask |= 0b0001_0000;
    }

    final int messageLength = 1 // VersionByte
        + TransactionRecord.MSG_HEADER_LENGTH
        + V1_CONFIG_MASK_LENGTH
        + Transaction.BLOCK_HASH_LENGTH // LifetimeSpecifier
        + 1 // NumInstructions
        + 1 // NumAccounts
        + (numAccounts << 5) // Accounts
        + (Integer.bitCount(configMask) << 2) // ConfigValues, 4 bytes per set TransactionConfigMask bit.
        + (numInstructions * V1_INSTRUCTION_HEADER_LENGTH) // InstructionHeaders
        + instructionPayloadLength; // InstructionPayloads
    final int bufferSize = messageLength + (numRequiredSignatures << 6);
    // This bound is policy, not encodability — it is gated on `strict`, so it cannot be what keeps
    // instruction data lengths inside the u16 header field. That is checked where the field is
    // written.
    if (strict && bufferSize > MAX_SERIALIZED_LENGTH_V1) {
      throw new IllegalStateException("A v1 transaction may not exceed " + MAX_SERIALIZED_LENGTH_V1 + " bytes.");
    }

    final byte[] out = new byte[bufferSize];

    int i = 0;
    // VersionByte
    out[i++] = V1_VERSION_BYTE;

    // LegacyHeader
    out[i++] = (byte) numRequiredSignatures;
    out[i++] = (byte) numReadonlySignedAccounts;
    out[i++] = (byte) numReadonlyUnsignedAccounts;

    // TransactionConfigMask (u32)
    ByteUtil.putInt32LE(out, i, configMask);
    i += Integer.BYTES;

    // LifetimeSpecifier (recent block hash)
    i += Transaction.BLOCK_HASH_LENGTH;

    out[i++] = (byte) numInstructions;
    out[i++] = (byte) numAccounts;

    // Accounts
    final var accountIndexLookupTable = HashMap.<PublicKey, Integer>newHashMap(numAccounts);
    for (int index = 0; index < numAccounts; ++index) {
      final var publicKey = sortedAccounts[index].publicKey();
      accountIndexLookupTable.put(publicKey, index);
      i += publicKey.write(out, i);
    }

    // ConfigValues, ordered by ascending TransactionConfigMask bit position.
    if (this.priorityFeeLamports != 0) {
      ByteUtil.putInt64LE(out, i, priorityFeeLamports);
      i += Long.BYTES;
    }
    if (this.computeUnitLimit != 0) {
      ByteUtil.putInt32LE(out, i, computeUnitLimit);
      i += Integer.BYTES;
    }
    if (this.accountDataSizeLimit != 0) {
      ByteUtil.putInt32LE(out, i, accountDataSizeLimit);
      i += Integer.BYTES;
    }
    if (this.heapSize != 0) {
      ByteUtil.putInt32LE(out, i, heapSize);
      i += Integer.BYTES;
    }

    // InstructionHeaders
    for (final var instruction : instructions) {
      final byte programIdIndex = indexOfOrThrow(accountIndexLookupTable, instruction.programId().publicKey());
      if (programIdIndex == 0) {
        throw new IllegalStateException("A v1 instruction program may not be the fee payer.");
      }
      out[i++] = programIdIndex;
      final int numInstructionAccounts = instruction.accounts().size();
      if (numInstructionAccounts > MAX_V1_INSTRUCTION_ACCOUNTS) {
        throw new IllegalStateException("A v1 instruction may not reference more than " + MAX_V1_INSTRUCTION_ACCOUNTS + " accounts.");
      }
      out[i++] = (byte) numInstructionAccounts;
      final int dataLength = instruction.len();
      if (dataLength > MAX_V1_INSTRUCTION_DATA_LENGTH) {
        throw new IllegalStateException("A v1 instruction data length field cannot encode more than " + MAX_V1_INSTRUCTION_DATA_LENGTH + " bytes.");
      }
      out[i++] = (byte) dataLength;
      out[i++] = (byte) (dataLength >> 8);
    }

    // InstructionPayloads
    for (final var instruction : instructions) {
      for (final var account : instruction.accounts()) {
        out[i++] = indexOfOrThrow(accountIndexLookupTable, account.publicKey());
      }
      final int dataLength = instruction.len();
      System.arraycopy(instruction.data(), instruction.offset(), out, i, dataLength);
      i += dataLength;
    }

    // Signatures are appended after the message once the transaction is signed.

    return new V1Transaction(
        feePayer,
        List.copyOf(instructions),
        out,
        messageLength
    );
  }

  private static int mergeAccounts(final AccountMeta feePayer,
                                   final Map<PublicKey, AccountMeta> accounts,
                                   final List<Instruction> instructions) {
    if (instructions.isEmpty()) {
      throw new IllegalArgumentException("No instructions provided");
    }
    if (feePayer != null) {
      accounts.put(feePayer.publicKey(), feePayer);
    }
    int instructionPayloadLength = 0;
    for (final var instruction : instructions) {
      instructionPayloadLength += instruction.accounts().size() + instruction.len();
      for (final var meta : instruction.accounts()) {
        accounts.merge(meta.publicKey(), meta, TransactionRecord.MERGE_ACCOUNT_META);
      }
      final var programMeta = instruction.programId();
      accounts.merge(programMeta.publicKey(), programMeta, TransactionRecord.MERGE_ACCOUNT_META);
    }
    return instructionPayloadLength;
  }
}
