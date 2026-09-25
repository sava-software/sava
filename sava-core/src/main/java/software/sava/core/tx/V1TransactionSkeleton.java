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
  static final int V1_CONFIG_MASK_OFFSET = 1 /* VersionByte */ + TransactionRecord.MSG_HEADER_LENGTH;
  static final int V1_RECENT_BLOCK_HASH_INDEX = V1_CONFIG_MASK_OFFSET + V1_CONFIG_MASK_LENGTH;
  static final int V1_ACCOUNTS_OFFSET = V1_RECENT_BLOCK_HASH_INDEX + BLOCK_HASH_LENGTH + 2 /* NumInstructions + NumAddresses */;

  // TransactionConfigMask bit positions, ordered ascending as serialized in the ConfigValues block.
  static final int PRIORITY_FEE_MASK = 0b0000_0011; // Two bits per SIMD-0385.
  static final int COMPUTE_UNIT_LIMIT_MASK = 0b0000_0100;
  static final int ACCOUNT_DATA_SIZE_LIMIT_MASK = 0b0000_1000;
  static final int HEAP_SIZE_MASK = 0b0001_0000;
  /// Every TransactionConfigMask bit SIMD-0385 defines; Agave rejects any other bit
  /// (`has_unknown_bits`).
  static final int KNOWN_CONFIG_MASK_BITS =
      PRIORITY_FEE_MASK | COMPUTE_UNIT_LIMIT_MASK | ACCOUNT_DATA_SIZE_LIMIT_MASK | HEAP_SIZE_MASK;

  /// Returns the offset of the ConfigValue for the given TransactionConfigMask bits, or -1 if they
  /// are not set.
  ///
  /// ConfigValues are serialized in ascending mask-bit order, four bytes per set bit, so the offset
  /// counts the set bits below the target; the u64 priority fee owns two mask bits and so two
  /// slots. Requires a mask within [#KNOWN_CONFIG_MASK_BITS], as [#deserialize(byte\[\])]
  /// enforces: a field that is not four bytes per bit would break the count.
  static int configValueOffset(final byte[] data, final int maskBits) {
    final int configMask = ByteUtil.getInt32LE(data, V1_CONFIG_MASK_OFFSET);
    if ((configMask & maskBits) != maskBits) {
      return -1;
    }
    return V1_ACCOUNTS_OFFSET
        + ((data[V1_ACCOUNTS_OFFSET - 1] & 0xFF) << 5)
        + (Integer.bitCount(configMask & (Integer.lowestOneBit(maskBits) - 1)) << 2);
  }

  /// Returns the offset just past the last instruction payload of an unparsed v1 message, where its
  /// signature block must begin, or -1 if the buffer cannot hold the instruction headers its counts
  /// declare. Allocation free, so untrusted raw bytes can be checked without building a skeleton.
  static int messageEnd(final byte[] data) {
    final int configMask = ByteUtil.getInt32LE(data, V1_CONFIG_MASK_OFFSET);
    final int numInstructions = data[V1_ACCOUNTS_OFFSET - 2] & 0xFF;
    final int numAddresses = data[V1_ACCOUNTS_OFFSET - 1] & 0xFF;
    final int instructionsOffset = V1_ACCOUNTS_OFFSET
        + (numAddresses << 5)
        + (Integer.bitCount(configMask) << 2);
    int messageEnd = instructionsOffset + (numInstructions * V1_INSTRUCTION_HEADER_LENGTH);
    if (messageEnd > data.length) {
      return -1;
    }
    for (int i = 0, header = instructionsOffset; i < numInstructions; ++i, header += V1_INSTRUCTION_HEADER_LENGTH) {
      messageEnd += (data[header + 1] & 0xFF) + (ByteUtil.getInt16LE(data, header + 2) & 0xFFFF);
    }
    return messageEnd;
  }

  /// Returns the offset of an unparsed v1 message's signature block, checked against [#messageEnd]
  /// rather than trusted from the buffer length. A padded or truncated buffer misplaces the
  /// length-implied slots: the id would read the wrong bytes, and after truncation signing would
  /// overwrite the message tail.
  ///
  /// @throws IllegalArgumentException if the buffer cannot hold the signatures its header declares,
  ///                                  or if the message does not end where they would begin
  static int requireSignatureBlockOffset(final byte[] data) {
    final int numSigners = data[1] & 0xFF;
    final int signaturesOffset = data.length - (numSigners * SIGNATURE_LENGTH);
    if (signaturesOffset < V1_ACCOUNTS_OFFSET) {
      throw new IllegalArgumentException(String.format(
          "A v1 transaction of %d bytes cannot hold the %d signatures its header declares.",
          data.length, numSigners
      ));
    }
    final int messageEnd = messageEnd(data);
    if (messageEnd != signaturesOffset) {
      throw new IllegalArgumentException(String.format(
          "A v1 message ending at offset %d does not corroborate the %d signature slots a %d byte buffer places at offset %d.",
          messageEnd, numSigners, data.length, signaturesOffset
      ));
    }
    return signaturesOffset;
  }

  private V1TransactionSkeleton(final byte[] data,
                                final int numSignatures,
                                final int numReadonlySignedAccounts,
                                final int numReadonlyUnsignedAccounts,
                                final int numIncludedAccounts,
                                final int numInstructions,
                                final int instructionsOffset,
                                final int[] invokedIndexes) {
    super(
        data,
        1,
        numSignatures, numReadonlySignedAccounts, numReadonlyUnsignedAccounts,
        numInstructions, instructionsOffset, invokedIndexes,
        numIncludedAccounts
    );
  }

  static TransactionSkeleton deserialize(final byte[] data) {
    int o = 1;
    // LegacyHeader
    final int numRequiredSignatures = data[o++] & 0xFF;
    final int numReadonlySignedAccounts = data[o++] & 0xFF;
    final int numReadonlyUnsignedAccounts = data[o++] & 0xFF;

    // Only the two header rules that account parsing itself depends on are enforced here. Both
    // partition the address array, so violating either does not yield an invalid-but-faithful
    // view — it yields a plausible-looking wrong one, with accounts silently carrying the wrong
    // privileges. The SIMD's remaining constraints are population limits which leave parsing
    // meaningful, so they stay on the permissive-analysis side of the line: exceedsSignatureLimit,
    // exceedsAccountLimit and exceedsInstructionLimit report them, and TxBuilder rejects them when
    // built in strict mode.
    if (numReadonlySignedAccounts >= numRequiredSignatures) {
      throw new IllegalStateException(String.format(
          "A v1 transaction requiring %d signatures may not load %d of them as read-only; the fee payer must be writable.",
          numRequiredSignatures, numReadonlySignedAccounts
      ));
    }

    final int configMask = ByteUtil.getInt32LE(data, o);
    // A single priority fee bit is malformed per SIMD-0385, both must be set.
    final int priorityFeeBits = configMask & PRIORITY_FEE_MASK;
    if (priorityFeeBits != 0 && priorityFeeBits != PRIORITY_FEE_MASK) {
      throw new IllegalStateException("Both v1 priority fee TransactionConfigMask bits must be set: 0x" + Integer.toHexString(configMask));
    }
    // An unknown bit is not merely an unrecognised request to skip over: the ConfigValues block is
    // sized from the mask, so allocating a slot for a bit whose width this release cannot know
    // shifts the instruction headers and every offset after them. That yields a plausible-looking
    // wrong view rather than an invalid-but-faithful one, which is the same reason the header rules
    // below are enforced. Rust rejects these outright, so no such message can come from a cluster.
    if ((configMask & ~KNOWN_CONFIG_MASK_BITS) != 0) {
      throw new IllegalStateException(
          "Unknown v1 TransactionConfigMask bits: 0x" + Integer.toHexString(configMask)
      );
    }
    o += V1_CONFIG_MASK_LENGTH;

    o += BLOCK_HASH_LENGTH;

    final int numInstructions = data[o++] & 0xFF;
    final int numIncludedAccounts = data[o++] & 0xFF;

    if (numIncludedAccounts < numRequiredSignatures + numReadonlyUnsignedAccounts) {
      throw new IllegalStateException(String.format(
          "A v1 transaction with %d addresses cannot hold %d signers and %d read-only non-signers.",
          numIncludedAccounts, numRequiredSignatures, numReadonlyUnsignedAccounts
      ));
    }

    // Accounts begin at the fixed V1_ACCOUNTS_OFFSET, followed by the ConfigValues, 4 bytes per
    // set TransactionConfigMask bit, including unknown bits.
    o += numIncludedAccounts << 5;
    o += Integer.bitCount(configMask) << 2;

    final int instructionsOffset = o;
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
        numInstructions, instructionsOffset, invokedIndexes
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
    return ByteUtil.getInt32LE(data, V1_CONFIG_MASK_OFFSET);
  }

  @Override
  public long priorityFeeLamports() {
    final int offset = configValueOffset(data, PRIORITY_FEE_MASK);
    return offset < 0 ? 0L : ByteUtil.getInt64LE(data, offset);
  }

  @Override
  public int computeUnitLimit() {
    final int offset = configValueOffset(data, COMPUTE_UNIT_LIMIT_MASK);
    return offset < 0 ? 0 : ByteUtil.getInt32LE(data, offset);
  }

  @Override
  public int accountDataSizeLimit() {
    final int offset = configValueOffset(data, ACCOUNT_DATA_SIZE_LIMIT_MASK);
    return offset < 0 ? 0 : ByteUtil.getInt32LE(data, offset);
  }

  @Override
  public int heapSize() {
    final int offset = configValueOffset(data, HEAP_SIZE_MASK);
    return offset < 0 ? 0 : ByteUtil.getInt32LE(data, offset);
  }

  @Override
  public String id() {
    final int signaturesOffset = signaturesOffset();
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

  private void requireIncludedProgramAccount(final int accountIndex) {
    if (accountIndex >= numAccounts) {
      throw new IndexOutOfBoundsException(String.format(
          "Program account index %d is outside the %d included accounts.",
          accountIndex, numAccounts
      ));
    }
  }

  /// Resolves an instruction account index. An index at or past NumAddresses (`numAccounts`) fails
  /// SIMD-0385 sanitization and throws, bounded by the wire, never by the caller's array; a
  /// declared index past the end of a caller-truncated `accounts` reads as `null`. Keep the
  /// exception and message identical to [TransactionSkeletonImpl]'s.
  ///
  /// @throws IndexOutOfBoundsException if an instruction references an account index the
  ///                                   transaction does not declare
  private AccountMeta requireIncludedInstructionAccount(final AccountMeta[] accounts, final int accountIndex) {
    if (accountIndex >= numAccounts) {
      throw new IndexOutOfBoundsException(String.format(
          "Instruction account index %d is outside the %d accounts of this transaction.",
          accountIndex, numAccounts
      ));
    }
    return accountIndex < accounts.length ? accounts[accountIndex] : null;
  }

  private PublicKey getProgramAccount(final int accountIndex) {
    requireIncludedProgramAccount(accountIndex);
    return accountKey(accountIndex);
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
      programs[i] = getProgramAccount(programIdIndex(header));
    }
    return programs;
  }

  @Override
  public Instruction[] parseInstructions(final AccountMeta[] accounts) {
    final var instructions = new Instruction[numInstructions];
    int cursor = firstInstructionCursor();
    for (int i = 0, header = instructionsOffset; i < numInstructions; ++i, header += V1_INSTRUCTION_HEADER_LENGTH) {
      final int programAccountIndex = programIdIndex(header);
      requireIncludedProgramAccount(programAccountIndex);
      final var programAccount = invokedProgramAccount(accounts[programAccountIndex]);

      final int numIxAccounts = numIxAccounts(header);
      final var ixAccounts = new AccountMeta[numIxAccounts];
      for (int a = 0, accountIndex; a < numIxAccounts; ++a) {
        accountIndex = data[cursor++] & 0xFF;
        ixAccounts[a] = requireIncludedInstructionAccount(accounts, accountIndex);
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
      final var programAccount = getProgramAccount(programIdIndex(header));
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
      // Validated for every instruction, not just the matched ones, so a non-matching filter
      // cannot hide a malformed program index.
      final int programAccountIndex = programIdIndex(header);
      requireIncludedProgramAccount(programAccountIndex);
      final int numIxAccounts = numIxAccounts(header);
      final int numDataBytes = numDataBytes(header);
      final int dataOffset = cursor + numIxAccounts;
      if (discriminator.equals(data, dataOffset, numDataBytes)) {
        final var ixAccounts = new AccountMeta[numIxAccounts];
        for (int a = 0, accountIndex; a < numIxAccounts; ++a) {
          accountIndex = data[cursor + a] & 0xFF;
          ixAccounts[a] = requireIncludedInstructionAccount(accounts, accountIndex);
        }
        instructions[d++] = createInstruction(accountKey(programAccountIndex), Arrays.asList(ixAccounts), data, dataOffset, numDataBytes);
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
      // Validated for every instruction, not just the matched ones, so a non-matching filter
      // cannot hide a malformed program index.
      final int programAccountIndex = programIdIndex(header);
      requireIncludedProgramAccount(programAccountIndex);
      final int numDataBytes = numDataBytes(header);
      final int dataOffset = cursor + numIxAccounts(header);
      if (discriminator.equals(data, dataOffset, numDataBytes)) {
        instructions[d++] = createInstruction(accountKey(programAccountIndex), NO_ACCOUNTS, data, dataOffset, numDataBytes);
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

  /// Carries both limits through verbatim, 0 included: an absent v1 mask bit means the value is 0,
  /// and the interface default would raise a 0/0 transaction to the builder's runtime maximums.
  @Override
  public TxBuilder prototypeTransaction(final Instruction[] instructions) {
    return new TxBuilderImpl()
        .feePayer(feePayer())
        .addInstructions(TxBuilderImpl.withoutComputeBudgetInstructions(instructions))
        .priorityFeeLamports(priorityFeeLamports())
        .heapSize(heapSize())
        .computeUnitLimit(computeUnitLimit())
        .accountDataSizeLimit(accountDataSizeLimit());
  }

  /// Returns the offset of the appended signature block, verified to start exactly where the parsed
  /// message ends: a truncated or padded payload still parses, but its length-implied slots are
  /// misplaced as [#requireSignatureBlockOffset(byte\[\])] describes. This is the v1 counterpart of
  /// [TransactionSkeletonImpl]'s one-byte signature-prefix check.
  ///
  /// @throws IllegalStateException if the parsed message end does not coincide with the start of
  ///                               the required signature slots
  private int signaturesOffset() {
    final int signaturesOffset = data.length - (numSignatures * SIGNATURE_LENGTH);
    // Bound the fixed-width header block before walking it: serializedInstructionsLength() reads
    // three bytes per header that deserialize never touched, so an unchecked walk would raise
    // ArrayIndexOutOfBoundsException instead of the IllegalStateException documented above.
    final int headerBlockEnd = instructionsOffset + (numInstructions * V1_INSTRUCTION_HEADER_LENGTH);
    if (headerBlockEnd > data.length || signaturesOffset < headerBlockEnd) {
      throw new IllegalStateException(String.format(
          "A v1 message of %d bytes cannot hold %d instruction headers and %d signature slots.",
          data.length, numInstructions, numSignatures
      ));
    }
    final int messageEnd = instructionsOffset + serializedInstructionsLength();
    if (messageEnd != signaturesOffset) {
      throw new IllegalStateException(String.format(
          "v1 message ends at offset %d but its %d signature slots begin at offset %d.",
          messageEnd, numSignatures, signaturesOffset
      ));
    }
    return signaturesOffset;
  }

  @Override
  @SuppressWarnings("removal") // Required implementation of the retained instruction-list factory.
  public Transaction createTransaction(final List<Instruction> instructions) {
    return new V1Transaction(
        AccountMeta.createFeePayer(feePayer()),
        instructions,
        data,
        signaturesOffset()
    );
  }
}
