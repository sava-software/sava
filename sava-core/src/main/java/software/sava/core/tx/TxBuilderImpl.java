package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.ByteUtil;

import java.util.*;
import java.util.function.BiFunction;

import static software.sava.core.accounts.lookup.AccountIndexLookupTableEntry.indexOfOrThrow;
import static software.sava.core.accounts.meta.AccountMeta.ACCOUNT_META_ARRAY_GENERATOR;

final class TxBuilderImpl implements TxBuilder {

  static final BiFunction<AccountMeta, AccountMeta, AccountMeta> MERGE_ACCOUNT_META = (prev, add) -> prev == null ? add : prev.merge(add);

  private static final Comparator<AccountMeta> LEGACY_META_COMPARATOR = (am1, am2) -> {
    if (am1.feePayer()) {
      return -1;
    } else if (am2.feePayer()) {
      return 1;
    } else if (am1.signer() == am2.signer()) {
      if (am1.write() == am2.write()) {
        return 0;
      } else {
        return am1.write() ? -1 : 1;
      }
    } else {
      return am1.signer() ? -1 : 1;
    }
  };

  static final int MAX_SERIALIZED_LENGTH_V1 = 4_096;
  // static final int MAX_BASE64_V1_SIZE = 5_464;
  static final int MSG_HEADER_LENGTH = 3;
  // SIMD-0385 Transaction V1 format.
  // The version byte that distinguishes a v1 transaction from the legacy and v0 formats.
  static final byte V1_VERSION_BYTE = (byte) 129;
  // Length, in bytes, of the v1 TransactionConfigMask field.
  static final int V1_CONFIG_MASK_LENGTH = 4;
  // Length, in bytes, of a single v1 InstructionHeader: (u8 programIdIndex, u8 numAccounts, u16 numDataBytes).
  static final int V1_INSTRUCTION_HEADER_LENGTH = 4;
  // Maximum number of instructions and accounts permitted in a v1 transaction.
  static final int MAX_V1_INSTRUCTIONS = 64;
  static final int MAX_V1_ACCOUNTS = 64;
  // Maximum number of signatures permitted in a v1 transaction.
  static final int MAX_V1_SIGNATURES = 12;
  // Per-instruction limit imposed by the u8 account count header field.
  private static final int MAX_V1_INSTRUCTION_ACCOUNTS = 0xFF;
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
  public TxBuilder addInstructions(final List<Instruction> instructions) {
    if (this.instructions == null) {
      this.instructions = instructions;
    } else {
      this.instructions.addAll(instructions);
    }
    return this;
  }

  @Override
  public TxBuilder addInstructions(final SequencedCollection<Instruction> instructions) {
    if (this.instructions == null) {
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
    }

    final var accounts = HashMap.<PublicKey, AccountMeta>newHashMap(MAX_V1_ACCOUNTS);
    final int instructionPayloadLength = mergeAccounts(feePayer, accounts, instructions);
    final var sortedAccounts = sortLegacyAccounts(accounts);

    final int numAccounts = sortedAccounts.length;
    if (strict && numAccounts > MAX_V1_ACCOUNTS) {
      throw new IllegalStateException("A v1 transaction may not reference more than " + MAX_V1_ACCOUNTS + " accounts.");
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
        + MSG_HEADER_LENGTH
        + V1_CONFIG_MASK_LENGTH
        + Transaction.BLOCK_HASH_LENGTH // LifetimeSpecifier
        + 1 // NumInstructions
        + 1 // NumAccounts
        + (numAccounts << 5) // Accounts
        + (Integer.bitCount(configMask) << 2) // ConfigValues, 4 bytes per set TransactionConfigMask bit.
        + (numInstructions * V1_INSTRUCTION_HEADER_LENGTH) // InstructionHeaders
        + instructionPayloadLength; // InstructionPayloads
    final int bufferSize = messageLength + (numRequiredSignatures << 6);
    // Bounding the serialized size also guarantees instruction data lengths fit the u16 header field.
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

  static AccountMeta[] sortLegacyAccounts(final Map<PublicKey, AccountMeta> mergedAccounts) {
    final var accountMetas = mergedAccounts.values().toArray(ACCOUNT_META_ARRAY_GENERATOR);
    Arrays.sort(accountMetas, LEGACY_META_COMPARATOR);
    return accountMetas;
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
        accounts.merge(meta.publicKey(), meta, MERGE_ACCOUNT_META);
      }
      final var programMeta = instruction.programId();
      accounts.merge(programMeta.publicKey(), programMeta, MERGE_ACCOUNT_META);
    }
    return instructionPayloadLength;
  }
}
