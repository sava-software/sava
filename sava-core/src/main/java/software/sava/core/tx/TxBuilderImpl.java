package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.ByteUtil;

import java.util.*;

import static software.sava.core.accounts.lookup.AccountIndexLookupTableEntry.indexOfOrThrow;
import static software.sava.core.tx.Transaction.MAX_ACCOUNTS;
import static software.sava.core.tx.Transaction.MSG_HEADER_LENGTH;


final class TxBuilderImpl implements TxBuilder {

  // SIMD-0385 Transaction V1 format.
  // The version byte that distinguishes a v1 transaction from the legacy and v0 formats.
  static final byte V1_VERSION_BYTE = (byte) 129;
  // Length, in bytes, of the v1 TransactionConfigMask field.
  static final int V1_CONFIG_MASK_LENGTH = 4;
  // Length, in bytes, of a single v1 InstructionHeader: (u8 programIdIndex, u8 numAccounts, u16 numDataBytes).
  static final int V1_INSTRUCTION_HEADER_LENGTH = 4;
  // Maximum number of instructions and accounts permitted in a v1 transaction.
  private static final int MAX_V1_INSTRUCTIONS = 64;
  private static final int MAX_V1_ACCOUNTS = 64;

  private AccountMeta feePayer;
  private List<Instruction> instructions;
  private long priorityFeeLamports;
  private int computeUnitLimit;
  private int accountDataSizeLimit;
  private int heapSize;

  TxBuilderImpl() {
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
  public TxBuilder priorityFeeLamports(final long priorityFeeLamports) {
    this.priorityFeeLamports = priorityFeeLamports;
    return this;
  }

  @Override
  public TxBuilder computeUnitLimit(final int computeUnitLimit) {
    this.computeUnitLimit = computeUnitLimit;
    return this;
  }

  @Override
  public TxBuilder accountDataSizeLimit(final int accountDataSizeLimit) {
    this.accountDataSizeLimit = accountDataSizeLimit;
    return this;
  }

  @Override
  public TxBuilder heapSize(final int heapSize) {
    // Per SIMD-0385, a requested heap size must be a multiple of 1KiB within [32KiB, 256KiB].
    if (heapSize % 1_024 != 0 || heapSize < 32 * 1_024 || heapSize > 256 * 1_024) {
      throw new IllegalStateException(
          "A v1 requested heap size must be a multiple of 1KiB in the inclusive range [32KiB, 256KiB]."
      );
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
    if (numInstructions == 0) {
      throw new IllegalArgumentException("No instructions provided");
    } else if (numInstructions > MAX_V1_INSTRUCTIONS) {
      throw new IllegalStateException("A v1 transaction may not reference more than " + MAX_V1_INSTRUCTIONS + " instructions.");
    }

    final var accounts = HashMap.<PublicKey, AccountMeta>newHashMap(MAX_ACCOUNTS);
    final int instructionPayloadLength = mergeAccounts(feePayer, accounts, instructions);
    final var sortedAccounts = Transaction.sortLegacyAccounts(accounts);

    final int numAccounts = sortedAccounts.length;
    if (numAccounts > MAX_V1_ACCOUNTS) {
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
      out[i++] = indexOfOrThrow(accountIndexLookupTable, instruction.programId().publicKey());
      out[i++] = (byte) instruction.accounts().size();
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
        instructions,
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
        accounts.merge(meta.publicKey(), meta, Transaction.MERGE_ACCOUNT_META);
      }
      final var programMeta = instruction.programId();
      accounts.merge(programMeta.publicKey(), programMeta, Transaction.MERGE_ACCOUNT_META);
    }
    return instructionPayloadLength;
  }
}
