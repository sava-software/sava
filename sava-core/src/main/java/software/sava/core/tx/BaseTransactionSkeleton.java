package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.Base58;

import java.util.Arrays;
import java.util.List;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.PublicKey.readPubKey;
import static software.sava.core.accounts.meta.AccountMeta.*;
import static software.sava.core.tx.Transaction.BLOCK_HASH_LENGTH;

abstract class BaseTransactionSkeleton implements TransactionSkeleton {

  static final int[] LEGACY_INVOKED_INDEXES = new int[0];
  static final PublicKey[] NO_TABLES = new PublicKey[0];

  protected static final List<AccountMeta> NO_ACCOUNTS = List.of();

  protected final byte[] data;
  protected final int version;
  protected final int numSignatures;
  protected final int numReadonlySignedAccounts;
  protected final int numReadonlyUnsignedAccounts;
  protected final int numInstructions;
  protected final int instructionsOffset;
  protected final int[] invokedIndexes;
  protected final int numAccounts;

  protected BaseTransactionSkeleton(final byte[] data,
                                    final int version,
                                    final int numSignatures,
                                    final int numReadonlySignedAccounts,
                                    final int numReadonlyUnsignedAccounts,
                                    final int numInstructions,
                                    final int instructionsOffset,
                                    final int[] invokedIndexes,
                                    final int numAccounts) {
    this.data = data;
    this.version = version;
    this.numSignatures = numSignatures;
    this.numReadonlySignedAccounts = numReadonlySignedAccounts;
    this.numReadonlyUnsignedAccounts = numReadonlyUnsignedAccounts;
    this.numInstructions = numInstructions;
    this.instructionsOffset = instructionsOffset;
    this.invokedIndexes = invokedIndexes;
    this.numAccounts = numAccounts;
  }

  @Override
  public final byte[] data() {
    return data;
  }

  @Override
  public final int version() {
    return version;
  }

  @Override
  public final int numSignatures() {
    return numSignatures;
  }

  @Override
  public final int numReadonlySignedAccounts() {
    return numReadonlySignedAccounts;
  }

  @Override
  public final int numReadonlyUnsignedAccounts() {
    return numReadonlyUnsignedAccounts;
  }

  @Override
  public final int numInstructions() {
    return numInstructions;
  }

  @Override
  public final int instructionsOffset() {
    return instructionsOffset;
  }

  @Override
  public final int numAccounts() {
    return numAccounts;
  }

  @Override
  public final byte[] blockHash() {
    final int recentBlockHashIndex = recentBlockHashIndex();
    return Arrays.copyOfRange(data, recentBlockHashIndex, recentBlockHashIndex + BLOCK_HASH_LENGTH);
  }

  @Override
  public final String base58BlockHash() {
    final int recentBlockHashIndex = recentBlockHashIndex();
    return Base58.encode(data, recentBlockHashIndex, recentBlockHashIndex + BLOCK_HASH_LENGTH);
  }

  // Returns the byte offset of the first account (the fee payer) within the serialized message.
  protected abstract int accountsOffset();

  protected final PublicKey accountKey(final int accountIndex) {
    return PublicKey.readPubKey(data, accountsOffset() + (accountIndex * PUBLIC_KEY_LENGTH));
  }

  @Override
  public final PublicKey feePayer() {
    return readPubKey(data, accountsOffset());
  }

  private int parseSignatureAccounts(final AccountMeta[] accounts) {
    accounts[0] = createFeePayer(feePayer());
    int o = accountsOffset() + PUBLIC_KEY_LENGTH;
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
  public final AccountMeta[] parseSignerAccounts() {
    final var accounts = new AccountMeta[numSignatures];
    parseSignatureAccounts(accounts);
    return accounts;
  }

  @Override
  public final PublicKey[] parseSignerPublicKeys() {
    final var accounts = new PublicKey[numSignatures];
    for (int o = accountsOffset(), a = 0; a < numSignatures; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = readPubKey(data, o);
    }
    return accounts;
  }

  @Override
  public final AccountMeta[] parseAccounts() {
    final int numIncludedAccounts = numIncludedAccounts();
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
  public final AccountMeta[] parseNonSignerAccounts() {
    final int numAccounts = numIncludedAccounts() - numSignatures;
    final var accounts = new AccountMeta[numAccounts];
    int o = accountsOffset() + (numSignatures * PUBLIC_KEY_LENGTH);
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
  public final PublicKey[] parseNonSignerPublicKeys() {
    final int numAccounts = numIncludedAccounts() - numSignatures;
    final var accounts = new PublicKey[numAccounts];
    for (int a = 0, o = accountsOffset() + (numSignatures * PUBLIC_KEY_LENGTH); a < numAccounts; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = readPubKey(data, o);
    }
    return accounts;
  }

  private AccountMeta parseVersionedReadAccount(final PublicKey pubKey, final int a) {
    return Arrays.binarySearch(invokedIndexes, a) < 0 ? createRead(pubKey) : createInvoked(pubKey);
  }

  protected final int parseVersionedIncludedAccounts(final AccountMeta[] accounts) {
    int o = parseSignatureAccounts(accounts);
    int a = numSignatures;
    final int numIncludedAccounts = numIncludedAccounts();
    for (final int to = numIncludedAccounts - numReadonlyUnsignedAccounts; a < to; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = createWrite(readPubKey(data, o));
    }
    for (; a < numIncludedAccounts; ++a, o += PUBLIC_KEY_LENGTH) {
      accounts[a] = parseVersionedReadAccount(readPubKey(data, o), a);
    }
    return a;
  }

  @Override
  public final AccountMeta[] parseAccounts(final List<PublicKey> writableLoaded, final List<PublicKey> readonlyLoaded) {
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
}
