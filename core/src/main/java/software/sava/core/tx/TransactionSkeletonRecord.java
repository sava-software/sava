package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.Base58;
import software.sava.core.encoding.CompactU16Encoding;

import java.util.Arrays;
import java.util.Map;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;
import static software.sava.core.accounts.meta.AccountMeta.*;
import static software.sava.core.tx.Instruction.createInstruction;
import static software.sava.core.tx.Transaction.BLOCK_HASH_LENGTH;

record TransactionSkeletonRecord(byte[] data,
                                 int version,
                                 int numRequiredSignatures,
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

  private int parseSignatureAccounts(final AccountMeta[] accounts) {
    accounts[0] = createFeePayer(readPubKey(data, accountsOffset));
    int o = accountsOffset + PUBLIC_KEY_LENGTH;
    int a = 1;
    for (final int numWriteSigners = numRequiredSignatures - numReadonlySignedAccounts; a < numWriteSigners; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = createWritableSigner(readPubKey(data, o));
    }
    for (; a < numRequiredSignatures; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = createReadOnlySigner(readPubKey(data, o));
    }
    return o;
  }

  @Override
  public AccountMeta[] parseAccounts() {
    final var accounts = new AccountMeta[numIncludedAccounts];
    int o = parseSignatureAccounts(accounts);
    int a = numRequiredSignatures;
    for (final int to = numIncludedAccounts - numReadonlyUnsignedAccounts; a < to; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = createWrite(readPubKey(data, o));
    }
    for (; a < numIncludedAccounts; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = createRead(readPubKey(data, o));
    }
    return accounts;
  }

  private AccountMeta parseVersionedReadAccount(final PublicKey pubKey, final int a) {
    return Arrays.binarySearch(invokedIndexes, a) < 0 ? createRead(pubKey) : createInvoked(pubKey);
  }

  private int parseVersionedIncludedAccounts(final AccountMeta[] accounts) {
    int o = parseSignatureAccounts(accounts);
    int a = numRequiredSignatures;
    for (final int to = numIncludedAccounts - numReadonlyUnsignedAccounts; a < to; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = createWrite(readPubKey(data, o));
    }
    for (; a < numIncludedAccounts; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = parseVersionedReadAccount(readPubKey(data, o), a);
    }
    return a;
  }

  @Override
  public AccountMeta[] parseAccounts(final AddressLookupTable lookupTable) {
    return lookupTable == null
        ? parseAccounts()
        : parseAccounts(Map.of(lookupTable.address(), lookupTable));
  }

  @Override
  public AccountMeta[] parseAccounts(final Map<PublicKey, AddressLookupTable> lookupTables) {
    final var accounts = new AccountMeta[numAccounts];
    int a = parseVersionedIncludedAccounts(accounts);

    int o = lookupTablesOffset;
    for (final var lookupTableKey : lookupTableAccounts) {
      final var lookupTable = lookupTables.get(lookupTableKey);
      o += PUBLIC_KEY_LENGTH;
      final int numWriteIndexes = data[o] & 0xFF;
      ++o;
      for (int w = 0; w < numWriteIndexes; ++w, ++a, ++o) {
        accounts[a] = createWrite(lookupTable.account(data[o] & 0xFF));
      }
      final int numReadIndexes = data[o] & 0xFF;
      ++o;
      o += numReadIndexes;
    }

    o = lookupTablesOffset;
    for (final var lookupTableKey : lookupTableAccounts) {
      final var lookupTable = lookupTables.get(lookupTableKey);
      o += PUBLIC_KEY_LENGTH;
      final int numWriteIndexes = data[o] & 0xFF;
      ++o;
      o += numWriteIndexes;
      final int numReadIndexes = data[o] & 0xFF;
      ++o;
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
      final var programAccount = accounts[data[o++] & 0xFF];
      numIxAccounts = CompactU16Encoding.decode(data, o);
      final var ixAccounts = new AccountMeta[numIxAccounts];
      ++o;
      for (int a = 0; a < numIxAccounts; ++a) {
        accountIndex = data[o++] & 0xFF;
        ixAccounts[a] = accounts[accountIndex];
      }
      final int len = CompactU16Encoding.decode(data, o);
      ++o;
      instructions[i] = createInstruction(programAccount, Arrays.asList(ixAccounts), data, o, len);
      o += len;
    }
    return instructions;
  }
}
