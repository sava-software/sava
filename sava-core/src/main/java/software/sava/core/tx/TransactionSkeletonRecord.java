package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.encoding.Base58;
import software.sava.core.programs.Discriminator;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;
import static software.sava.core.accounts.meta.AccountMeta.*;
import static software.sava.core.encoding.CompactU16Encoding.decode;
import static software.sava.core.encoding.CompactU16Encoding.getByteLen;
import static software.sava.core.tx.Instruction.createInstruction;
import static software.sava.core.tx.Transaction.BLOCK_HASH_LENGTH;
import static software.sava.core.tx.TransactionRecord.VERSIONED_BIT_MASK;

record TransactionSkeletonRecord(byte[] data,
                                 int version,
                                 int messageOffset,
                                 int serializedSignatureCount,
                                 int numSignatures,
                                 int numReadonlySignedAccounts,
                                 int numReadonlyUnsignedAccounts,
                                 int numIncludedAccounts, int accountsOffset,
                                 int recentBlockHashIndex,
                                 int numInstructions, int instructionsOffset, int[] invokedIndexes,
                                 int lookupTablesOffset, PublicKey[] lookupTableAccounts,
                                 int numAccounts) implements TransactionSkeleton {

  static final int[] LEGACY_INVOKED_INDEXES = new int[0];
  static final PublicKey[] NO_TABLES = new PublicKey[0];

  @Override
  public boolean isVersioned() {
    return version != VERSIONED_BIT_MASK;
  }

  @Override
  public boolean isLegacy() {
    return version == VERSIONED_BIT_MASK;
  }

  @Override
  public String id() {
    return Base58.encode(data, 1, 1 + Transaction.SIGNATURE_LENGTH);
  }

  @Override
  public byte[] blockHash() {
    return Arrays.copyOfRange(data, recentBlockHashIndex, recentBlockHashIndex + BLOCK_HASH_LENGTH);
  }

  @Override
  public String base58BlockHash() {
    return Base58.encode(data, recentBlockHashIndex, recentBlockHashIndex + BLOCK_HASH_LENGTH);
  }

  @Override
  public PublicKey feePayer() {
    requireAddressesCoverSigners();
    return readPubKey(data, accountsOffset);
  }

  /// The signature count and the address count are two independently read compact-u16 fields, so
  /// `numIncludedAccounts < numSignatures` is representable on the wire even though no valid
  /// transaction has it — every signer is an address. Every account-parsing entry point sizes its
  /// array from the address count and then fills signer slots from the signature count, so the
  /// mismatch used to surface as a bare `ArrayIndexOutOfBoundsException` (or a
  /// `NegativeArraySizeException` from [#parseNonSignerAccounts], which subtracts the two).
  ///
  /// This narrows *how* such a header fails, not *whether* it does: the same inputs threw before.
  /// Over-limit-but-coherent headers stay readable, as they must — narrowing a count to its wire
  /// byte is a documented analysis affordance. A header that contradicts itself is a different
  /// thing, because no reading of it is faithful.
  ///
  /// @throws IllegalStateException if the address array cannot hold the signers the header declares
  private void requireAddressesCoverSigners() {
    if (numIncludedAccounts < numSignatures) {
      throw new IllegalStateException(String.format(
          "Header declares %d required signatures but only %d addresses are included.",
          numSignatures, numIncludedAccounts
      ));
    }
  }

  private int parseSignatureAccounts(final AccountMeta[] accounts) {
    accounts[0] = createFeePayer(feePayer());
    int o = accountsOffset + PUBLIC_KEY_LENGTH;
    int a = 1;
    for (final int numWriteSigners = numSignatures - numReadonlySignedAccounts; a < numWriteSigners; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = createWritableSigner(readPubKey(data, o));
    }
    for (; a < numSignatures; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = createReadOnlySigner(readPubKey(data, o));
    }
    return o;
  }

  @Override
  public AccountMeta[] parseSignerAccounts() {
    final var accounts = new AccountMeta[numSignatures];
    parseSignatureAccounts(accounts);
    return accounts;
  }

  @Override
  public PublicKey[] parseSignerPublicKeys() {
    requireAddressesCoverSigners();
    final var accounts = new PublicKey[numSignatures];
    for (int o = accountsOffset, a = 0; a < numSignatures; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = readPubKey(data, o);
    }
    return accounts;
  }

  @Override
  public AccountMeta[] parseAccounts() {
    final var accounts = new AccountMeta[numIncludedAccounts];
    int o = parseSignatureAccounts(accounts);
    int a = numSignatures;
    for (final int to = numIncludedAccounts - numReadonlyUnsignedAccounts; a < to; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = createWrite(readPubKey(data, o));
    }
    for (; a < numIncludedAccounts; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = createRead(readPubKey(data, o));
    }
    return accounts;
  }

  @Override
  public AccountMeta[] parseNonSignerAccounts() {
    requireAddressesCoverSigners();
    final int numAccounts = numIncludedAccounts - numSignatures;
    final var accounts = new AccountMeta[numAccounts];
    int o = accountsOffset + (numSignatures * PUBLIC_KEY_LENGTH);
    int a = 0;
    for (final int to = numAccounts - numReadonlyUnsignedAccounts; a < to; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = createWrite(readPubKey(data, o));
    }
    for (; a < numAccounts; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = createRead(readPubKey(data, o));
    }
    return accounts;
  }

  @Override
  public PublicKey[] parseNonSignerPublicKeys() {
    requireAddressesCoverSigners();
    final int numAccounts = numIncludedAccounts - numSignatures;
    final var accounts = new PublicKey[numAccounts];
    for (int a = 0, o = accountsOffset + (numSignatures * PUBLIC_KEY_LENGTH); a < numAccounts; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = readPubKey(data, o);
    }
    return accounts;
  }

  @Override
  public AccountMeta[] parseAccounts(final AddressLookupTable lookupTable) {
    return lookupTable == null
        ? parseAccounts()
        : parseAccounts(Map.of(lookupTable.address(), lookupTable));
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

  private AccountMeta parseVersionedReadAccount(final PublicKey pubKey, final int a) {
    return Arrays.binarySearch(invokedIndexes, a) < 0 ? createRead(pubKey) : createInvoked(pubKey);
  }

  private int parseVersionedIncludedAccounts(final AccountMeta[] accounts) {
    int o = parseSignatureAccounts(accounts);
    int a = numSignatures;
    for (final int to = numIncludedAccounts - numReadonlyUnsignedAccounts; a < to; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = createWrite(readPubKey(data, o));
    }
    for (; a < numIncludedAccounts; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = parseVersionedReadAccount(readPubKey(data, o), a);
    }
    return a;
  }

  @Override
  public AccountMeta[] parseAccounts(final Map<PublicKey, AddressLookupTable> lookupTables) {
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
  public AccountMeta[] parseAccounts(final List<PublicKey> writableLoaded, final List<PublicKey> readonlyLoaded) {
    final var accounts = new AccountMeta[numAccounts];
    int a = parseVersionedIncludedAccounts(accounts);
    for (final var writeable : writableLoaded) {
      accounts[a++] = createWrite(writeable);
    }
    for (final var readable : readonlyLoaded) {
      accounts[a++] = createRead(readable);
    }
    return accounts;
  }

  /// An instruction's program is invoked by definition, but a legacy `parseAccounts()` has
  /// no invoked indexes to consult and types every read-only account as [AccountMeta#createRead];
  /// mark it here so this agrees with [#filterInstructions]. Any writable use of the same
  /// account is recovered by [Instruction#mergeAccounts] when a transaction is rebuilt.
  private static AccountMeta invokedProgramAccount(final AccountMeta account) {
    return account.invoked() ? account : createInvoked(account.publicKey());
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
        ixAccounts[a] = instructionAccount(accounts, accountIndex);
      }

      final int len = decode(data, o);
      o += getByteLen(data, o);
      instructions[i] = createInstruction(programAccount, Arrays.asList(ixAccounts), data, o, len);
      o += len;
    }
    return instructions;
  }

  private int accountOffset(final int accountIndex) {
    return accountsOffset + (accountIndex * PUBLIC_KEY_LENGTH);
  }

  private PublicKey getProgramAccount(final int accountIndex) {
    requireIncludedProgramAccount(accountIndex);
    return PublicKey.readPubKey(data, accountOffset(accountIndex));
  }

  private void requireIncludedProgramAccount(final int accountIndex) {
    if (accountIndex >= numIncludedAccounts) {
      throw new IndexOutOfBoundsException(String.format(
          "Program account index %d is outside the %d included accounts.",
          accountIndex, numIncludedAccounts
      ));
    }
  }

  /// The instruction-account counterpart to [#requireIncludedProgramAccount], bounded twice: once
  /// by the wire and once by the caller.
  ///
  /// The transaction itself declares how many accounts it references — `numAccounts`, the included
  /// accounts plus every index its lookup tables load. An instruction index at or past that total
  /// names an account no reading of the transaction can supply, in any format, and the runtime
  /// rejects such a message outright; it used to read as the same `null` a legitimately
  /// unresolvable index produces, an instruction that looks well formed and throws a
  /// `NullPointerException` far from the malformed input, while the program-index field of the
  /// same instruction has always been loud about the same defect. It now throws for every format
  /// this class parses — legacy and v0 — and transaction v1's own skeleton, which its
  /// deserialization entry point will select before this class is reached, rejects with the
  /// identical exception and message.
  ///
  /// An index the transaction *does* declare but the supplied array cannot resolve reads as the
  /// documented `null`: through sava's own parsers that is exactly a v0 message parsed without its
  /// lookup tables, whose first loaded account sits at `numIncludedAccounts`. Legacy declares no
  /// loaded accounts, so its every declared index resolves and only a caller-truncated array — one
  /// no sava parser produces — can observe a legacy `null`.
  ///
  /// @throws IndexOutOfBoundsException if an instruction references an account index the
  ///                                   transaction does not declare
  private AccountMeta instructionAccount(final AccountMeta[] accounts, final int accountIndex) {
    if (accountIndex >= numAccounts) {
      throw new IndexOutOfBoundsException(String.format(
          "Instruction account index %d is outside the %d accounts of this transaction.",
          accountIndex, numAccounts
      ));
    }
    return accountIndex < accounts.length ? accounts[accountIndex] : null;
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

  private static final List<AccountMeta> NO_ACCOUNTS = List.of();

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

      if (discriminator.equals(data, o, len)) {
        final var ixAccounts = new AccountMeta[numIxAccounts];
        for (int a = 0; a < numIxAccounts; ++a) {
          final int accountIndex = data[accountsOffset++] & 0xFF;
          ixAccounts[a] = instructionAccount(accounts, accountIndex);
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

      if (discriminator.equals(data, o, len)) {
        instructions[d++] = createInstruction(getProgramAccount(programAccountIndex), NO_ACCOUNTS, data, o, len);
      }
      o += len;
    }
    return d == numInstructions
        ? instructions
        : Arrays.copyOfRange(instructions, 0, d);
  }

  @Override
  public Instruction[] parseInstructionsWithoutTableAccounts() {
    final var accounts = new AccountMeta[numAccounts];
    parseVersionedIncludedAccounts(accounts);
    return parseInstructions(accounts);
  }

  private void requireSignableSignatureLayout() {
    if (serializedSignatureCount != numSignatures) {
      throw new IllegalStateException(String.format(
          "Serialized signature count %d does not match the message header's required signature count %d.",
          serializedSignatureCount, numSignatures
      ));
    }
    final int signaturePrefixLength = messageOffset - (serializedSignatureCount * Transaction.SIGNATURE_LENGTH);
    if (signaturePrefixLength != 1) {
      throw new IllegalStateException(String.format(
          "Serialized signature count %d uses a %d-byte prefix; mutable transactions require a one-byte prefix.",
          serializedSignatureCount, signaturePrefixLength
      ));
    }
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
