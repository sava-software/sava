package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.programs.Discriminator;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.encoding.CompactU16Encoding.*;
import static software.sava.core.tx.Transaction.SIGNATURE_LENGTH;
import static software.sava.core.tx.Transaction.VERSIONED_BIT_MASK;
import static software.sava.core.tx.TransactionSkeletonRecord.LEGACY_INVOKED_INDEXES;
import static software.sava.core.tx.TransactionSkeletonRecord.NO_TABLES;

public interface TransactionSkeleton {

  static TransactionSkeleton deserializeSkeleton(final byte[] data) {
    int o = 0;
    final int numSignatures = decode(data, o);
    o += getByteLen(data, o);
    o += (numSignatures * SIGNATURE_LENGTH);

    int version = data[o++] & 0xFF;
    final int numRequiredSignatures;
    if (signedByte(version)) {
      numRequiredSignatures = data[o++];
      version &= 0x7F;
    } else {
      numRequiredSignatures = version;
      version = VERSIONED_BIT_MASK;
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
        if (numLookupTables > 0) {
          ++o;
          final int lookupTablesOffset = o;
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
          return new TransactionSkeletonRecord(
              data,
              version, numRequiredSignatures, numReadonlySignedAccounts, numReadonlyUnsignedAccounts,
              numIncludedAccounts, accountsOffset,
              recentBlockHashIndex,
              numInstructions, instructionsOffset, invokedIndexes,
              lookupTablesOffset, lookupTableAccounts,
              numAccounts
          );
        }
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
    }
    return new TransactionSkeletonRecord(
        data,
        version, numRequiredSignatures, numReadonlySignedAccounts, numReadonlyUnsignedAccounts,
        numIncludedAccounts, accountsOffset,
        recentBlockHashIndex,
        numInstructions, instructionsOffset, LEGACY_INVOKED_INDEXES,
        -1, NO_TABLES,
        numIncludedAccounts
    );
  }

  byte[] data();

  int version();

  boolean isVersioned();

  boolean isLegacy();

  int numSignatures();

  int numSigners();

  int recentBlockHashIndex();

  byte[] blockHash();

  String base58BlockHash();

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

  PublicKey feePayer();

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
   * If this is a versioned transaction accounts which are indexed into a lookup table will be null.
   * Signing accounts and program accounts will always be included.
   */
  Instruction[] parseInstructionsWithoutTableAccounts();

  Instruction[] filterInstructions(final AccountMeta[] accounts, final Discriminator discriminator);

  default Instruction[] filterInstructionsWithoutTableAccounts(final Discriminator discriminator) {
    return filterInstructions(parseAccounts(), discriminator);
  }

  Instruction[] filterInstructionsWithoutAccounts(final Discriminator discriminator);
}
