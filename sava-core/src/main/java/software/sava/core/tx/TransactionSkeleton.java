package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.programs.Discriminator;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.encoding.CompactU16Encoding.*;
import static software.sava.core.tx.BaseTransactionSkeleton.LEGACY_INVOKED_INDEXES;
import static software.sava.core.tx.BaseTransactionSkeleton.NO_TABLES;
import static software.sava.core.tx.Transaction.SIGNATURE_LENGTH;
import static software.sava.core.tx.TxBuilderImpl.V1_VERSION_BYTE;

public interface TransactionSkeleton {

  static TransactionSkeleton deserializeSkeleton(final byte[] data) {
    if ((data[0] & 0xFF) == (V1_VERSION_BYTE & 0xFF)) {
      return V1TransactionSkeleton.deserialize(data);
    }
    int o = 0;
    final int numSignatures = decode(data, o);
    o += getByteLen(data, o);
    o += (numSignatures * SIGNATURE_LENGTH);
    final int messageOffset = o;

    int version = data[o++] & 0xFF;
    final int numRequiredSignatures;
    if (signedByte(version)) {
      numRequiredSignatures = data[o++];
      version &= 0x7F;
    } else {
      numRequiredSignatures = version;
      version = BaseTransaction.VERSIONED_BIT_MASK;
    }
    final int numReadonlySignedAccounts = data[o++];
    final int numReadonlyUnsignedAccounts = data[o++];

    final int numIncludedAccounts = decode(data, o);
    o += getByteLen(data, o);
    final int accountsOffset = o;
    o += numIncludedAccounts << 5;

    final int recentBlockHashIndex = o;
    o += Transaction.BLOCK_HASH_LENGTH;

    final int numInstructions = decode(data, o);
    o += getByteLen(data, o);
    final int instructionsOffset = o;

    if (version >= 0) {
      final int[] invokedIndexes = new int[numInstructions];
      for (int i = 0, numAccounts, len; i < numInstructions; ++i) {
        invokedIndexes[i] = decode(data, o);
        o += getByteLen(data, o);

        numAccounts = decode(data, o);
        o += getByteLen(data, o);
        o += numAccounts;

        len = decode(data, o);
        o += getByteLen(data, o);
        o += len;
      }
      if (o < data.length) {
        final int numLookupTables = decode(data, o);
        ++o;
        final int lookupTablesOffset = o;
        if (numLookupTables > 0) {
          final PublicKey[] lookupTableAccounts = new PublicKey[numLookupTables];
          int numAccounts = numIncludedAccounts;
          for (int t = 0, numWriteIndexes, numReadIndexes; t < numLookupTables; ++t) {
            lookupTableAccounts[t] = PublicKey.readPubKey(data, o);
            o += PUBLIC_KEY_LENGTH;

            numWriteIndexes = decode(data, o);
            o += getByteLen(data, o);
            o += numWriteIndexes;
            numAccounts += numWriteIndexes;

            numReadIndexes = decode(data, o);
            o += getByteLen(data, o);
            o += numReadIndexes;
            numAccounts += numReadIndexes;
          }
          Arrays.sort(invokedIndexes);
          return new TransactionSkeletonImpl(
              data,
              version,
              messageOffset,
              numRequiredSignatures, numReadonlySignedAccounts, numReadonlyUnsignedAccounts,
              numIncludedAccounts, accountsOffset,
              recentBlockHashIndex,
              numInstructions, instructionsOffset, invokedIndexes,
              lookupTablesOffset, lookupTableAccounts,
              numAccounts
          );
        } else {
          return new TransactionSkeletonImpl(
              data,
              version,
              messageOffset,
              numRequiredSignatures, numReadonlySignedAccounts, numReadonlyUnsignedAccounts,
              numIncludedAccounts, accountsOffset,
              recentBlockHashIndex,
              numInstructions, instructionsOffset, invokedIndexes,
              lookupTablesOffset, NO_TABLES,
              numIncludedAccounts
          );
        }
      } else {
        return new TransactionSkeletonImpl(
            data,
            version,
            messageOffset,
            numRequiredSignatures, numReadonlySignedAccounts, numReadonlyUnsignedAccounts,
            numIncludedAccounts, accountsOffset,
            recentBlockHashIndex,
            numInstructions, instructionsOffset, invokedIndexes,
            data.length, NO_TABLES,
            numIncludedAccounts
        );
      }
    } else {
      for (int i = 0, numAccounts, len; i < numInstructions; ++i) {
        o += getByteLen(data, o); // program index

        numAccounts = decode(data, o);
        o += getByteLen(data, o);
        o += numAccounts;

        len = decode(data, o);
        o += getByteLen(data, o);
        o += len;
      }
      return new TransactionSkeletonImpl(
          data,
          version,
          messageOffset,
          numRequiredSignatures, numReadonlySignedAccounts, numReadonlyUnsignedAccounts,
          numIncludedAccounts, accountsOffset,
          recentBlockHashIndex,
          numInstructions, instructionsOffset, LEGACY_INVOKED_INDEXES,
          -1, NO_TABLES,
          numIncludedAccounts
      );
    }
  }

  byte[] data();

  int numSignatures();

  default int numSigners() {
    return numSignatures();
  }

  String id();

  int version();

  boolean isVersioned();

  boolean isLegacy();

  int numReadonlySignedAccounts();

  int numReadonlyUnsignedAccounts();

  int recentBlockHashIndex();

  byte[] blockHash();

  String base58BlockHash();

  int numInstructions();

  int instructionsOffset();

  int numIncludedAccounts();

  int numAccounts();

  default int numIndexedAccounts() {
    return numAccounts() - numIncludedAccounts();
  }

  PublicKey[] lookupTableAccounts();

  /// The priority fee, in lamports, for this transaction.
  ///
  /// v1 transactions return the priority fee ConfigValue directly.
  ///
  /// Legacy and v0 transactions derive the fee from the SetComputeUnitPrice compute budget
  /// instruction, which is priced in micro-lamports per compute unit. The price is multiplied
  /// by the requested compute unit limit, capped at the 1.4 million maximum, then converted to
  /// lamports, rounding up, mirroring the runtime's prioritization fee calculation.
  ///
  /// If no SetComputeUnitLimit instruction is present, a default limit of 200,000 units per
  /// non-compute-budget instruction is assumed. This is an estimate; per SIMD-0170 the runtime
  /// only allocates 3,000 units for each builtin program instruction, so the derived fee may
  /// differ for such transactions. Transactions which explicitly set a compute unit limit are
  /// exact.
  ///
  /// @return 0 if no priority fee ConfigValue or SetComputeUnitPrice instruction is present.
  long priorityFeeLamports();

  /// @return 0 if not explicitly set via Config Value or Compute Budget.
  int computeUnitLimit();

  /// @return 0 if not explicitly set via Config Value or Compute Budget.
  int accountDataSizeLimit();

  /// @return 0 if not explicitly set via Config Value or Compute Budget.
  int heapSize();

  AccountMeta[] parseAccounts();

  AccountMeta[] parseAccounts(final Map<PublicKey, AddressLookupTable> lookupTables);

  default AccountMeta[] parseAccounts(final Stream<AddressLookupTable> lookupTables) {
    final var lookupTableMap = lookupTables.collect(Collectors
        .toUnmodifiableMap(AddressLookupTable::address, Function.identity()));
    return parseAccounts(lookupTableMap);
  }

  AccountMeta[] parseAccounts(final List<PublicKey> writableLoaded, final List<PublicKey> readonlyLoaded);

  PublicKey feePayer();

  AccountMeta[] parseSignerAccounts();

  PublicKey[] parseSignerPublicKeys();

  AccountMeta[] parseNonSignerAccounts();

  PublicKey[] parseNonSignerPublicKeys();

  AccountMeta[] parseAccounts(final AddressLookupTable lookupTable);

  PublicKey[] parseProgramAccounts();

  int serializedInstructionsLength();

  Instruction[] parseInstructions(final AccountMeta[] accounts);

  default Instruction[] parseLegacyInstructions() {
    return parseInstructions(parseAccounts());
  }

  /**
   * Program accounts will be included for each instruction.
   * Instruction accounts will not.
   */
  Instruction[] parseInstructionsWithoutAccounts();

  /**
   * If this is a versioned transaction, accounts which are indexed into a lookup table will be null.
   * Signing accounts and program accounts will always be included.
   */
  Instruction[] parseInstructionsWithoutTableAccounts();

  Instruction[] filterInstructions(final AccountMeta[] accounts, final Discriminator discriminator);

  default Instruction[] filterInstructionsWithoutTableAccounts(final Discriminator discriminator) {
    return filterInstructions(parseAccounts(), discriminator);
  }

  Instruction[] filterInstructionsWithoutAccounts(final Discriminator discriminator);

  Transaction createTransaction(final List<Instruction> instructions);

  default Transaction createTransaction(final Instruction[] instructions) {
    return createTransaction(Arrays.asList(instructions));
  }

  default Transaction createTransaction(final AccountMeta[] accounts) {
    final var instructions = parseInstructions(accounts);
    return createTransaction(instructions);
  }

  default Transaction createTransaction() {
    final var accounts = parseAccounts();
    return createTransaction(accounts);
  }

  /// Creates a v1 {@link TxBuilder} from this transaction's fee payer, instructions, and compute
  /// budget values.
  ///
  /// v0 transactions which load accounts via address lookup tables must resolve those accounts
  /// first, e.g. {@code prototypeTransaction(parseInstructions(parseAccounts(lookupTables)))}.
  ///
  /// @throws IllegalStateException if this transaction loads accounts via address lookup tables.
  default TxBuilder prototypeTransaction() {
    if (numIndexedAccounts() > 0) {
      throw new IllegalStateException(
          "Accounts indexed into address lookup tables must be resolved, use prototypeTransaction(parseInstructions(parseAccounts(lookupTables))) instead."
      );
    }
    return prototypeTransaction(this.parseInstructionsWithoutTableAccounts());
  }

  /// Creates a v1 {@link TxBuilder} from this transaction's fee payer, the given instructions,
  /// and this transaction's compute budget values.
  ///
  /// ComputeBudgetProgram instructions are filtered out, their values are carried over as
  /// ConfigValues instead; per SIMD-0385 the v1 runtime ignores them for configuration and
  /// processes them as no-ops which still consume compute units.
  ///
  /// {@link #computeUnitLimit()} and {@link #accountDataSizeLimit()} return 0 when not
  /// explicitly set, which a {@link TxBuilder} treats as clearing the ConfigValue, a 0 unit and
  /// 0 byte budget per SIMD-0385. To mirror the runtime defaults such transactions actually
  /// executed with, unset values are not carried over so that the builder defaults of the
  /// runtime maximums are retained, which also reserves the ConfigValues for in-place updates.
  ///
  /// The legacy/v0 {@link #priorityFeeLamports()} carried over is derived from the
  /// SetComputeUnitPrice instruction and, when no SetComputeUnitLimit instruction is present, an
  /// estimated compute unit limit; prefer re-pricing the created transaction via
  /// {@link Transaction#setPriorityFeeLamports(long)} after simulating it.
  default TxBuilder prototypeTransaction(final Instruction[] instructions) {
    final var computeBudgetProgram = SolanaAccounts.MAIN_NET.computeBudgetProgram();
    int numRetained = 0;
    final var retained = new Instruction[instructions.length];
    for (final var instruction : instructions) {
      if (!computeBudgetProgram.equals(instruction.programId().publicKey())) {
        retained[numRetained++] = instruction;
      }
    }
    final var builder = new TxBuilderImpl()
        .feePayer(feePayer())
        .addInstructions(numRetained == instructions.length
            ? instructions
            : Arrays.copyOfRange(retained, 0, numRetained))
        .priorityFeeLamports(priorityFeeLamports())
        .heapSize(heapSize());
    final int computeUnitLimit = computeUnitLimit();
    if (computeUnitLimit != 0) {
      builder.computeUnitLimit(computeUnitLimit);
    }
    final int accountDataSizeLimit = accountDataSizeLimit();
    if (accountDataSizeLimit != 0) {
      builder.accountDataSizeLimit(accountDataSizeLimit);
    }
    return builder;
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  /// **Note:** for V1 transactions the provided lookup table will be ignored
  /// because V1 transactions do not support address lookup tables.
  ///
  // /// @deprecated use {@link TxBuilder} or {@link #prototypeTransaction} to create a v1 transaction instead.
  // @Deprecated
  Transaction createTransaction(final List<Instruction> instructions, final AddressLookupTable lookupTable);

  // TODO: deprecate once v1 transactions are active on mainnet
  /// **Note:** for V1 transactions the provided lookup table will be ignored
  /// because V1 transactions do not support address lookup tables.
  ///
  // /// @deprecated use {@link TxBuilder} or {@link #prototypeTransaction} to create a v1 transaction instead.
  // @Deprecated
  default Transaction createTransaction(final Instruction[] instructions, final AddressLookupTable lookupTable) {
    return createTransaction(Arrays.asList(instructions), lookupTable);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  /// **Note:** for V1 transactions the provided lookup table will be ignored
  /// because V1 transactions do not support address lookup tables.
  ///
  // /// @deprecated use {@link TxBuilder} or {@link #prototypeTransaction} to create a v1 transaction instead.
  // @Deprecated
  default Transaction createTransaction(final AccountMeta[] accounts, final AddressLookupTable lookupTable) {
    final var instructions = parseInstructions(accounts);
    return createTransaction(instructions, lookupTable);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  /// **Note:** for V1 transactions the provided lookup table will be ignored
  /// because V1 transactions do not support address lookup tables.
  ///
  // /// @deprecated use {@link TxBuilder} or {@link #prototypeTransaction} to create a v1 transaction instead.
  // @Deprecated
  default Transaction createTransaction(final AddressLookupTable lookupTable) {
    final var accounts = parseAccounts(lookupTable);
    return createTransaction(accounts, lookupTable);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  /// **Note:** for V1 transactions the provided lookup table will be ignored
  /// because V1 transactions do not support address lookup tables.
  ///
  // /// @deprecated use {@link TxBuilder} or {@link #prototypeTransaction} to create a v1 transaction instead.
  // @Deprecated
  default Transaction createTransaction(final AccountMeta[] accounts,
                                        final LookupTableAccountMeta[] tableAccountMetas) {
    final var instructions = parseInstructions(accounts);
    return createTransaction(Arrays.asList(instructions), tableAccountMetas);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  /// **Note:** for V1 transactions the provided lookup table will be ignored
  /// because V1 transactions do not support address lookup tables.
  ///
  // /// @deprecated use {@link TxBuilder} or {@link #prototypeTransaction} to create a v1 transaction instead.
  // @Deprecated
  Transaction createTransaction(final LookupTableAccountMeta[] tableAccountMetas);

  // TODO: deprecate once v1 transactions are active on mainnet
  /// **Note:** for V1 transactions the provided lookup table will be ignored
  /// because V1 transactions do not support address lookup tables.
  ///
  // /// @deprecated use {@link TxBuilder} or {@link #prototypeTransaction} to create a v1 transaction instead.
  // @Deprecated
  Transaction createTransaction(final List<Instruction> instructions, final LookupTableAccountMeta[] tableAccountMetas);
}
