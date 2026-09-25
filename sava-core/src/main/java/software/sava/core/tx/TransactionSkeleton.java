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

  /// Parses a serialized legacy, v0 or SIMD-0385 v1 transaction into a read-only view that
  /// retains `data` without copying it.
  ///
  /// Parsing is not full validation. Legacy deserialization reads each instruction's account-count
  /// and data-length prefixes eagerly, so a missing prefix throws here, but the final payload is
  /// skipped without checking its end. A versioned message records which included accounts its
  /// instructions invoke even when its lookup-table section is empty or absent; see
  /// [#parseAccounts(List, List)].
  ///
  /// @throws RuntimeException if parsing runs past the end of `data`, or a v1 header or config mask
  ///                          is inconsistent; the exception type is not guaranteed
  static TransactionSkeleton deserializeSkeleton(final byte[] data) {
    // The v1 version byte 0x81 also opens a legacy message's compact-u16 signature count: 0x81 0x01
    // is 129 signatures, and 0x81 0x00 is the non-canonical encoding of 1. Only the latter is
    // reachable — 129 signatures cannot fit the 1232 byte packet limit — and it cannot be a v1
    // message, because byte one is a v1 message's num_required_signatures and SIMD-0385 sanitizes
    // that to at least one. Leaving it to the legacy walk keeps such messages readable, so the
    // signature layout check rejects them where a mutable transaction is actually built.
    if ((data[0] & 0xFF) == (V1_VERSION_BYTE & 0xFF) && data[1] != 0) {
      return V1TransactionSkeleton.deserialize(data);
    }
    int o = 0;
    final int serializedSignatureCount = decode(data, o);
    o += getByteLen(data, o);
    o += (serializedSignatureCount * SIGNATURE_LENGTH);
    final int messageOffset = o;

    int version = data[o++] & 0xFF;
    final int numRequiredSignatures;
    if (signedByte(version)) {
      // the three message header counts are u8 on the wire like the version byte above;
      // read as signed bytes, a value past 0x7F is a negative count that inflates the
      // signer loops in TransactionSkeletonImpl instead of being rejected as malformed
      numRequiredSignatures = data[o++] & 0xFF;
      version &= 0x7F;
    } else {
      numRequiredSignatures = version;
      version = BaseTransaction.VERSIONED_BIT_MASK;
    }
    final int numReadonlySignedAccounts = data[o++] & 0xFF;
    final int numReadonlyUnsignedAccounts = data[o++] & 0xFF;

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
        invokedIndexes[i] = data[o++] & 0xFF;

        numAccounts = decode(data, o);
        o += getByteLen(data, o);
        o += numAccounts;

        len = decode(data, o);
        o += getByteLen(data, o);
        o += len;
      }
      // Versioned account parsing uses binary search to identify invoked read-only accounts.
      Arrays.sort(invokedIndexes);
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
          return new TransactionSkeletonImpl(
              data,
              version,
              messageOffset,
              serializedSignatureCount,
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
              serializedSignatureCount,
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
            serializedSignatureCount,
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
        ++o; // raw u8 program index

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
          serializedSignatureCount,
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

  /// Returns the priority fee in lamports, or 0 if the transaction sets none.
  ///
  /// A v1 transaction returns its priority fee ConfigValue. A legacy or v0 transaction converts its
  /// SetComputeUnitPrice price with [TxBuilder#computeUnitPriceToPriorityFeeLamports(long, int)]
  /// against the requested compute unit limit. Without a SetComputeUnitLimit instruction, or with a
  /// limit of 0, the limit is estimated from the runtime's per-instruction defaults: 3,000 units
  /// per builtin program instruction (SIMD-0170) and 200,000 per other instruction.
  ///
  /// The default implementation reparses [#data()] on every call; override it.
  default long priorityFeeLamports() {
    return TransactionSkeleton.deserializeSkeleton(data()).priorityFeeLamports();
  }

  /// Returns the requested compute unit limit, or 0 if the transaction sets none.
  ///
  /// A v1 transaction that sets no limit is budgeted 0 units, not the runtime default, and cannot
  /// execute a single metered instruction.
  ///
  /// The default implementation reparses [#data()] on every call; override it.
  default int computeUnitLimit() {
    return TransactionSkeleton.deserializeSkeleton(data()).computeUnitLimit();
  }

  /// Returns the requested loaded accounts data size limit in bytes, or 0 if the transaction sets
  /// none.
  ///
  /// Per SIMD-0385 a v1 transaction that sets no limit is limited to 0 bytes, not the 64MiB legacy
  /// default.
  ///
  /// The default implementation reparses [#data()] on every call; override it.
  default int accountDataSizeLimit() {
    return TransactionSkeleton.deserializeSkeleton(data()).accountDataSizeLimit();
  }

  /// Returns the heap size in bytes this transaction *requests*, or 0 if it requests none, in which
  /// case the runtime applies its 32KiB minimum.
  ///
  /// Reporting 0 rather than the effective 32KiB lets [#prototypeTransaction()] rebuild exactly the
  /// ConfigValues its source carried.
  ///
  /// The default implementation reparses [#data()] on every call; override it.
  default int heapSize() {
    return TransactionSkeleton.deserializeSkeleton(data()).heapSize();
  }

  AccountMeta[] parseAccounts();

  AccountMeta[] parseAccounts(final Map<PublicKey, AddressLookupTable> lookupTables);

  default AccountMeta[] parseAccounts(final Stream<AddressLookupTable> lookupTables) {
    final var lookupTableMap = lookupTables.collect(Collectors
        .toUnmodifiableMap(AddressLookupTable::address, Function.identity()));
    return parseAccounts(lookupTableMap);
  }

  /// Parses the included accounts and appends the given table-loaded accounts, writable first;
  /// together the lists must number [#numIndexedAccounts()].
  ///
  /// For a versioned message, included read-only non-signers that an instruction invokes as its
  /// program are marked [AccountMeta#invoked()], which [#parseAccounts()] never does. A legacy
  /// message loads no accounts: pass empty lists to get the [#parseAccounts()] flags. Instruction
  /// views mark their programs invoked in every format.
  AccountMeta[] parseAccounts(final List<PublicKey> writableLoaded, final List<PublicKey> readonlyLoaded);

  PublicKey feePayer();

  AccountMeta[] parseSignerAccounts();

  PublicKey[] parseSignerPublicKeys();

  AccountMeta[] parseNonSignerAccounts();

  PublicKey[] parseNonSignerPublicKeys();

  AccountMeta[] parseAccounts(final AddressLookupTable lookupTable);

  PublicKey[] parseProgramAccounts();

  int serializedInstructionsLength();

  /// Parses each instruction, resolving its account indexes against `accounts`.
  ///
  /// An index the transaction declares (below [#numAccounts()], which counts included and
  /// table-loaded accounts) that `accounts` does not resolve, because the array is shorter or
  /// holds `null` there, yields a `null` element in that instruction's accounts. From this
  /// interface's own account parsers that happens only for a v0 message parsed without its lookup
  /// tables; a caller-truncated array can cause it in any format. A longer array does not make an
  /// undeclared index valid.
  ///
  /// @throws IndexOutOfBoundsException if an instruction references an account index the
  ///                                   transaction does not declare, or a program index outside
  ///                                   its included accounts
  Instruction[] parseInstructions(final AccountMeta[] accounts);

  default Instruction[] parseLegacyInstructions() {
    return parseInstructions(parseAccounts());
  }

  /// Parses each instruction with its program but an empty account list.
  Instruction[] parseInstructionsWithoutAccounts();

  /// Parses instructions against the included accounts only, so instruction accounts a v0
  /// transaction loads from a lookup table are `null`. Legacy and v1 transactions resolve every
  /// account.
  Instruction[] parseInstructionsWithoutTableAccounts();

  /// Parses the instructions whose data starts with `discriminator`, resolving accounts like
  /// [#parseInstructions(AccountMeta\[\])].
  ///
  /// @throws IndexOutOfBoundsException if a matched instruction references an account index the
  ///                                   transaction does not declare, or any instruction's program
  ///                                   index is outside the included accounts
  Instruction[] filterInstructions(final AccountMeta[] accounts, final Discriminator discriminator);

  default Instruction[] filterInstructionsWithoutTableAccounts(final Discriminator discriminator) {
    return filterInstructions(parseAccounts(), discriminator);
  }

  Instruction[] filterInstructionsWithoutAccounts(final Discriminator discriminator);

  /// Creates a mutable transaction from this parsed representation.
  ///
  /// @throws IllegalStateException if the serialized signature-slot count does not match the
  ///                               message header's required-signature count, or its prefix is
  ///                               not representable by a mutable transaction
  /// @deprecated The instructions must be this message's own, which the skeleton parses itself.
  ///             For legacy, v1, and v0 messages without lookup tables, [#createTransaction()]
  ///             builds the same transaction. For v0 with lookup tables, use
  ///             [#createTransaction(AddressLookupTable)] or
  ///             [#createTransaction(LookupTableAccountMeta\[\])], which also keep the tables for
  ///             later rebuilds, or [#createTransaction(AccountMeta\[\])] with accounts already
  ///             resolved.
  @Deprecated(forRemoval = true)
  Transaction createTransaction(final List<Instruction> instructions);

  /// Creates a mutable transaction from the supplied instructions.
  ///
  /// @throws IllegalStateException if this parsed signature layout cannot be represented by a
  ///                               mutable transaction
  /// @deprecated As for [#createTransaction(List)].
  @Deprecated(forRemoval = true)
  default Transaction createTransaction(final Instruction[] instructions) {
    return createTransaction(Arrays.asList(instructions));
  }

  /// Creates a mutable transaction after parsing instructions against `accounts`; it shares
  /// [#data()] like [#createTransaction()].
  ///
  /// @throws IllegalStateException as [#createTransaction()] does
  default Transaction createTransaction(final AccountMeta[] accounts) {
    final var instructions = parseInstructions(accounts);
    return createTransaction(instructions);
  }

  /// Creates a mutable transaction after parsing its included accounts and instructions. Its
  /// [Transaction#serialized()] bytes are this skeleton's [#data()] array, shared rather than
  /// copied, so in-place changes such as signing or setting the block hash write into it; every
  /// other `createTransaction` overload shares it the same way.
  ///
  /// @throws IllegalStateException if the serialized signature-slot count does not match the
  ///                               message header's required-signature count, or its prefix is
  ///                               not representable by a mutable transaction
  default Transaction createTransaction() {
    final var accounts = parseAccounts();
    return createTransaction(accounts);
  }

  /// Creates a v1 [TxBuilder] from this transaction's fee payer, instructions and compute budget
  /// values. A transaction that loads accounts from lookup tables must resolve them first, with
  /// `prototypeTransaction(parseInstructions(parseAccounts(lookupTables)))`.
  ///
  /// @throws IllegalStateException if this transaction loads accounts from address lookup tables
  default TxBuilder prototypeTransaction() {
    if (numIndexedAccounts() > 0) {
      throw new IllegalStateException(
          "Accounts indexed into address lookup tables must be resolved, use prototypeTransaction(parseInstructions(parseAccounts(lookupTables))) instead."
      );
    }
    return prototypeTransaction(this.parseInstructionsWithoutTableAccounts());
  }

  /// Creates a v1 [TxBuilder] from this transaction's fee payer and compute budget values and the
  /// given instructions.
  ///
  /// ComputeBudgetProgram instructions in `instructions` are dropped, since per SIMD-0385 the v1
  /// runtime treats them as no-ops that still cost compute units; the ConfigValues come from this
  /// transaction's values, never from the given instructions.
  ///
  /// From a legacy or v0 source, a compute unit or accounts data size limit that is not set (reads
  /// 0) is not carried over, so the builder keeps its runtime-maximum default rather than a 0
  /// budget, which also reserves that ConfigValue for in-place updates. A v1 source carries every
  /// value verbatim, 0 included. A legacy or v0 priority fee may rest on an estimated limit (see
  /// [#priorityFeeLamports()]); re-price the built transaction with
  /// [Transaction#setPriorityFeeLamports(long)] after simulating it.
  default TxBuilder prototypeTransaction(final Instruction[] instructions) {
    final var builder = new TxBuilderImpl()
        .feePayer(feePayer())
        .addInstructions(TxBuilderImpl.withoutComputeBudgetInstructions(instructions))
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
  /// Creates a mutable transaction from the given instructions and one lookup table; like
  /// [#createTransaction()], it shares [#data()] and does not serialize the instructions.
  ///
  /// A v1 transaction, which has no lookup tables, ignores the table.
  ///
  /// @throws IllegalStateException as [#createTransaction()] does
  // /// @deprecated use {@link TxBuilder} or {@link #prototypeTransaction} to create a v1 transaction instead.
  // @Deprecated
  Transaction createTransaction(final List<Instruction> instructions, final AddressLookupTable lookupTable);

  // TODO: deprecate once v1 transactions are active on mainnet
  /// Creates a mutable transaction from the given instructions and lookup table; like
  /// [#createTransaction()], it shares [#data()] and does not serialize the instructions.
  ///
  /// A v1 transaction, which has no lookup tables, ignores the table.
  ///
  /// @throws IllegalStateException as [#createTransaction()] does
  // /// @deprecated use {@link TxBuilder} or {@link #prototypeTransaction} to create a v1 transaction instead.
  // @Deprecated
  default Transaction createTransaction(final Instruction[] instructions, final AddressLookupTable lookupTable) {
    return createTransaction(Arrays.asList(instructions), lookupTable);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  /// Creates a mutable transaction after parsing instructions against `accounts`; it shares
  /// [#data()] like [#createTransaction()].
  ///
  /// A v1 transaction, which has no lookup tables, ignores the table.
  ///
  /// @throws IllegalStateException as [#createTransaction()] does
  // /// @deprecated use {@link TxBuilder} or {@link #prototypeTransaction} to create a v1 transaction instead.
  // @Deprecated
  default Transaction createTransaction(final AccountMeta[] accounts, final AddressLookupTable lookupTable) {
    final var instructions = parseInstructions(accounts);
    return createTransaction(instructions, lookupTable);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  /// Creates a mutable transaction after resolving accounts through one lookup table; it shares
  /// [#data()] like [#createTransaction()].
  ///
  /// A v1 transaction, which has no lookup tables, ignores the table.
  ///
  /// @throws IllegalStateException as [#createTransaction()] does
  // /// @deprecated use {@link TxBuilder} or {@link #prototypeTransaction} to create a v1 transaction instead.
  // @Deprecated
  default Transaction createTransaction(final AddressLookupTable lookupTable) {
    final var accounts = parseAccounts(lookupTable);
    return createTransaction(accounts, lookupTable);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  /// Creates a mutable transaction after parsing instructions against `accounts`; it shares
  /// [#data()] like [#createTransaction()].
  ///
  /// A v1 transaction, which has no lookup tables, ignores the table.
  ///
  /// @throws IllegalStateException as [#createTransaction()] does
  // /// @deprecated use {@link TxBuilder} or {@link #prototypeTransaction} to create a v1 transaction instead.
  // @Deprecated
  default Transaction createTransaction(final AccountMeta[] accounts,
                                        final LookupTableAccountMeta[] tableAccountMetas) {
    final var instructions = parseInstructions(accounts);
    return createTransaction(Arrays.asList(instructions), tableAccountMetas);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  /// Creates a mutable transaction after resolving accounts through the given lookup metadata; it
  /// shares [#data()] like [#createTransaction()].
  ///
  /// A v1 transaction, which has no lookup tables, ignores the table.
  ///
  /// @throws IllegalStateException as [#createTransaction()] does
  // /// @deprecated use {@link TxBuilder} or {@link #prototypeTransaction} to create a v1 transaction instead.
  // @Deprecated
  default Transaction createTransaction(final LookupTableAccountMeta[] tableAccountMetas) {
    final var accounts = parseAccounts(Arrays.stream(tableAccountMetas).map(LookupTableAccountMeta::lookupTable));
    return createTransaction(accounts, tableAccountMetas);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  /// Creates a mutable transaction from the given instructions and lookup-table metadata; like
  /// [#createTransaction()], it shares [#data()] and does not serialize the instructions.
  ///
  /// A v1 transaction, which has no lookup tables, ignores the table.
  ///
  /// @throws IllegalStateException as [#createTransaction()] does
  // /// @deprecated use {@link TxBuilder} or {@link #prototypeTransaction} to create a v1 transaction instead.
  // @Deprecated
  Transaction createTransaction(final List<Instruction> instructions, final LookupTableAccountMeta[] tableAccountMetas);
}
