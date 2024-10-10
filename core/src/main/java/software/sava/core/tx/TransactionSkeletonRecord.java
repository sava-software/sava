package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
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
import static software.sava.core.tx.Transaction.VERSIONED_BIT_MASK;

record TransactionSkeletonRecord(byte[] data,
                                 int version,
                                 int numSigners,
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
  public int numSignatures() {
    return data[0] & 0xFF;
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
    return readPubKey(data, accountsOffset);
  }

  private int parseSignatureAccounts(final AccountMeta[] accounts) {
    accounts[0] = createFeePayer(feePayer());
    int o = accountsOffset + PUBLIC_KEY_LENGTH;
    int a = 1;
    for (final int numWriteSigners = numSigners - numReadonlySignedAccounts; a < numWriteSigners; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = createWritableSigner(readPubKey(data, o));
    }
    for (; a < numSigners; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = createReadOnlySigner(readPubKey(data, o));
    }
    return o;
  }

  @Override
  public AccountMeta[] parseAccounts() {
    final var accounts = new AccountMeta[numIncludedAccounts];
    int o = parseSignatureAccounts(accounts);
    int a = numSigners;
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
    final int numAccounts = numIncludedAccounts - numSigners;
    final var accounts = new AccountMeta[numAccounts];
    int o = accountsOffset + (numSigners * PUBLIC_KEY_LENGTH);
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
    final int numAccounts = numIncludedAccounts - numSigners;
    final var accounts = new PublicKey[numAccounts];
    for (int a = 0, o = accountsOffset + (numSigners * PUBLIC_KEY_LENGTH); a < numAccounts; ++a, o += PUBLIC_KEY_LENGTH) {
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
    int a = numSigners;
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

  private int accountOffset(final int accountIndex) {
    return accountsOffset + (accountIndex * PUBLIC_KEY_LENGTH);
  }

  private PublicKey getAccount(final int accountIndex) {
    return PublicKey.readPubKey(data, accountOffset(accountIndex));
  }

  @Override
  public PublicKey[] parseProgramAccounts() {
    final var programs = new PublicKey[numInstructions];
    for (int i = 0, o = instructionsOffset, programAccountIndex, numIxAccounts, len; i < numInstructions; ++i) {
      programAccountIndex = decode(data, o);
      o += getByteLen(data, o);
      programs[i] = getAccount(programAccountIndex);

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
      final int programAccountIndex = decode(data, o);
      o += getByteLen(data, o);
      final var programAccount = getAccount(programAccountIndex);

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
        instructions[d++] = createInstruction(getAccount(programAccountIndex), Arrays.asList(ixAccounts), data, o, len);
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
        instructions[d++] = createInstruction(getAccount(programAccountIndex), NO_ACCOUNTS, data, o, len);
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
}
