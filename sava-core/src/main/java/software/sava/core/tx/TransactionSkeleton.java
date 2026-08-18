package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
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
import static software.sava.core.tx.Transaction.SIGNATURE_LENGTH;
import static software.sava.core.tx.TransactionRecord.VERSIONED_BIT_MASK;
import static software.sava.core.tx.TransactionSkeletonRecord.LEGACY_INVOKED_INDEXES;
import static software.sava.core.tx.TransactionSkeletonRecord.NO_TABLES;

public interface TransactionSkeleton {

  /**
   * Parses the serialized transaction into its structural views.
   *
   * <p>For versioned messages, account parsing marks every included read-only account referenced
   * by an instruction's {@code program_id_index} as invoked. This also holds when the address-table
   * lookup count is zero or the data ends immediately after the instruction section.</p>
   */
  static TransactionSkeleton deserializeSkeleton(final byte[] data) {
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
      // signer loops in TransactionSkeletonRecord instead of being rejected as malformed
      numRequiredSignatures = data[o++] & 0xFF;
      version &= 0x7F;
    } else {
      numRequiredSignatures = version;
      version = VERSIONED_BIT_MASK;
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
          return new TransactionSkeletonRecord(
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
          return new TransactionSkeletonRecord(
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
        return new TransactionSkeletonRecord(
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
      return new TransactionSkeletonRecord(
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

  /**
   * Parses each instruction's account references against the supplied array by index.
   *
   * <p>An out-of-range account index resolves against two different bounds. An index at or beyond
   * {@link #numAccounts()} — the included accounts plus every index the transaction's lookup
   * tables load — names an account the transaction does not declare; that is corruption in every
   * format and throws, exactly as an out-of-range program index always has. An index the
   * transaction declares but the supplied array cannot resolve yields a {@code null} element
   * inside that instruction's account list: through this interface's own parsers that is precisely
   * a <b>v0</b> message parsed without its lookup tables, whose first table-loaded account sits at
   * {@link #numIncludedAccounts()} — the same contract
   * {@link #parseInstructionsWithoutTableAccounts()} documents. Resolvability is judged against
   * the supplied array alone, so a caller-truncated array produces the same {@code null} for a
   * declared index in any format; a legacy message has no lookup tables, so through arrays this
   * interface produces its every declared index resolves and its instruction accounts are never
   * {@code null}. Transaction v1 also declares no loaded accounts, and its reader — which arrives
   * with v1 support — enforces these same two bounds, rejecting an undeclared index with the
   * identical exception and message.</p>
   *
   * @throws IndexOutOfBoundsException if an instruction references an account index the
   *                                   transaction does not declare
   */
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
   * If this is a v0 transaction, accounts which are indexed into a lookup table will be null.
   * Signing accounts and program accounts will always be included. Legacy and v1 transactions have
   * no lookup tables, so every account is resolved.
   */
  Instruction[] parseInstructionsWithoutTableAccounts();

  /**
   * Filters by discriminator, resolving accounts the way {@link #parseInstructions(AccountMeta[])}
   * does — {@code null} elements for declared indices the supplied array cannot resolve, which
   * through sava-produced arrays only a v0 message parsed without its lookup tables exhibits, and
   * the same rejection of undeclared indices in every format.
   *
   * @throws IndexOutOfBoundsException if a matched instruction references an account index the
   *                                   transaction does not declare
   */
  Instruction[] filterInstructions(final AccountMeta[] accounts, final Discriminator discriminator);

  default Instruction[] filterInstructionsWithoutTableAccounts(final Discriminator discriminator) {
    return filterInstructions(parseAccounts(), discriminator);
  }

  Instruction[] filterInstructionsWithoutAccounts(final Discriminator discriminator);

  /**
   * Creates a mutable transaction from this parsed representation.
   *
   * @throws IllegalStateException if the serialized signature-slot count does not match the
   *                               message header's required-signature count, or its prefix is
   *                               not representable by a mutable transaction
   */
  Transaction createTransaction(final List<Instruction> instructions);

  /**
   * Creates a mutable transaction from the supplied instructions.
   *
   * @throws IllegalStateException if this parsed signature layout cannot be represented by a
   *                               mutable transaction
   */
  default Transaction createTransaction(final Instruction[] instructions) {
    return createTransaction(Arrays.asList(instructions));
  }

  /**
   * Creates a mutable transaction after parsing instructions against the supplied accounts.
   *
   * @throws IllegalStateException if this parsed signature layout cannot be represented by a
   *                               mutable transaction
   */
  default Transaction createTransaction(final AccountMeta[] accounts) {
    final var instructions = parseInstructions(accounts);
    return createTransaction(instructions);
  }

  /**
   * Creates a mutable transaction after parsing its accounts and instructions.
   *
   * @throws IllegalStateException if this parsed signature layout cannot be represented by a
   *                               mutable transaction
   */
  default Transaction createTransaction() {
    final var accounts = parseAccounts();
    return createTransaction(accounts);
  }

  /**
   * Creates a mutable transaction using one lookup table.
   *
   * @throws IllegalStateException if the serialized signature-slot count does not match the
   *                               message header's required-signature count, or its prefix is
   *                               not representable by a mutable transaction
   */
  Transaction createTransaction(final List<Instruction> instructions, final AddressLookupTable lookupTable);

  /**
   * Creates a mutable transaction from the supplied instructions and lookup table.
   *
   * @throws IllegalStateException if this parsed signature layout cannot be represented by a
   *                               mutable transaction
   */
  default Transaction createTransaction(final Instruction[] instructions, final AddressLookupTable lookupTable) {
    return createTransaction(Arrays.asList(instructions), lookupTable);
  }

  /**
   * Creates a mutable transaction after parsing instructions against the supplied accounts.
   *
   * @throws IllegalStateException if this parsed signature layout cannot be represented by a
   *                               mutable transaction
   */
  default Transaction createTransaction(final AccountMeta[] accounts, final AddressLookupTable lookupTable) {
    final var instructions = parseInstructions(accounts);
    return createTransaction(instructions, lookupTable);
  }

  /**
   * Creates a mutable transaction after resolving accounts through one lookup table.
   *
   * @throws IllegalStateException if this parsed signature layout cannot be represented by a
   *                               mutable transaction
   */
  default Transaction createTransaction(final AddressLookupTable lookupTable) {
    final var accounts = parseAccounts(lookupTable);
    return createTransaction(accounts, lookupTable);
  }

  /**
   * Creates a mutable transaction after parsing instructions against the supplied accounts.
   *
   * @throws IllegalStateException if this parsed signature layout cannot be represented by a
   *                               mutable transaction
   */
  default Transaction createTransaction(final AccountMeta[] accounts,
                                        final LookupTableAccountMeta[] tableAccountMetas) {
    final var instructions = parseInstructions(accounts);
    return createTransaction(Arrays.asList(instructions), tableAccountMetas);
  }

  /**
   * Creates a mutable transaction after resolving accounts through the supplied lookup metadata.
   *
   * @throws IllegalStateException if this parsed signature layout cannot be represented by a
   *                               mutable transaction
   */
  default Transaction createTransaction(final LookupTableAccountMeta[] tableAccountMetas) {
    final var accounts = parseAccounts(Arrays.stream(tableAccountMetas).map(LookupTableAccountMeta::lookupTable));
    return createTransaction(accounts, tableAccountMetas);
  }

  /**
   * Creates a mutable transaction using the supplied lookup-table metadata.
   *
   * @throws IllegalStateException if the serialized signature-slot count does not match the
   *                               message header's required-signature count, or its prefix is
   *                               not representable by a mutable transaction
   */
  Transaction createTransaction(final List<Instruction> instructions, final LookupTableAccountMeta[] tableAccountMetas);
}
