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

  /// An instruction's program is invoked by definition, but a `parseAccounts()` that has no
  /// invoked indexes to consult types every read-only account as [AccountMeta#createRead];
  /// mark it here so this agrees with [TransactionSkeleton#filterInstructions] and with
  /// [TransactionSkeleton#parseInstructionsWithoutTableAccounts]. Any writable use of the same
  /// account is recovered by [Instruction#mergeAccounts] when a transaction is rebuilt.
  protected static AccountMeta invokedProgramAccount(final AccountMeta account) {
    return account.invoked() ? account : createInvoked(account.publicKey());
  }

  // Returns the byte offset of the first account (the fee payer) within the serialized message.
  protected abstract int accountsOffset();

  protected final PublicKey accountKey(final int accountIndex) {
    return PublicKey.readPubKey(data, accountsOffset() + (accountIndex * PUBLIC_KEY_LENGTH));
  }

  /// The signature count and the address count are two independently read fields, so
  /// `numIncludedAccounts < numSignatures` is representable on the wire even though no valid
  /// transaction has it — every signer is an address. Every account-parsing entry point sizes its
  /// array from the address count and then fills signer slots from the signature count, so the
  /// mismatch used to surface as a bare `ArrayIndexOutOfBoundsException` (or a
  /// `NegativeArraySizeException` from [#parseNonSignerAccounts], which subtracts the two).
  ///
  /// This narrows *how* such a header fails, not *whether* it does: the same inputs threw before.
  /// Over-limit-but-coherent headers stay readable, as they must — narrowing a count to its wire
  /// byte is a documented analysis affordance. A header that contradicts itself is a different
  /// thing, because no reading of it is faithful. A v1 message cannot reach this: its
  /// deserialization rejects the same contradiction against SIMD-0385's header rules first.
  ///
  /// @throws IllegalStateException if the address array cannot hold the signers the header declares
  protected final void requireAddressesCoverSigners() {
    final int numIncludedAccounts = numIncludedAccounts();
    if (numIncludedAccounts < numSignatures) {
      throw new IllegalStateException(String.format(
          "Header declares %d required signatures but only %d addresses are included.",
          numSignatures, numIncludedAccounts
      ));
    }
  }

  @Override
  public final PublicKey feePayer() {
    requireAddressesCoverSigners();
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
    requireAddressesCoverSigners();
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
    requireAddressesCoverSigners();
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
    requireAddressesCoverSigners();
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
