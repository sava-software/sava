package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.encoding.Base58;
import software.sava.core.encoding.CompactU16Encoding;

import java.util.*;
import java.util.function.BiFunction;

import static software.sava.core.encoding.CompactU16Encoding.getByteLen;
import static software.sava.core.encoding.CompactU16Encoding.signedByte;
import static software.sava.core.tx.TransactionRecord.NO_TABLES;
import static software.sava.core.tx.TransactionRecord.mergeAccounts;

public interface Transaction {

  /// @deprecated no longer valid for all transaction versions
  @Deprecated
  int MAX_SERIALIZED_LENGTH = 1232;
  /// @deprecated - redundant value, check against the serialized bytes.
  @Deprecated
  int MAX_BASE_64_ENCODED_LENGTH = 1683;
  int SIGNATURE_LENGTH = 64;
  int BLOCK_HASH_LENGTH = 32;
  int MAX_ACCOUNTS = 64;
  int BLOCK_QUEUE_SIZE = 151;
  int BLOCKS_UNTIL_FINALIZED = 32;

  /// @deprecated internal serialization
  @Deprecated
  BiFunction<AccountMeta, AccountMeta, AccountMeta> MERGE_ACCOUNT_META = TransactionRecord.MERGE_ACCOUNT_META;

  // fee payer, sign, write, read
  /// @deprecated internal serialization
  @Deprecated
  Comparator<AccountMeta> LEGACY_META_COMPARATOR = TransactionRecord.LEGACY_META_COMPARATOR;

  /// @deprecated internal serialization
  @Deprecated
  Comparator<AccountMeta> VO_META_COMPARATOR = TransactionRecord.VO_META_COMPARATOR;

  /// @deprecated internal serialization
  @Deprecated
  int MSG_HEADER_LENGTH = TransactionRecord.MSG_HEADER_LENGTH;
  /// @deprecated internal serialization
  @Deprecated
  int VERSIONED_MSG_HEADER_LENGTH = TransactionRecord.VERSIONED_MSG_HEADER_LENGTH;
  /// @deprecated internal serialization
  @Deprecated
  byte VERSIONED_BIT_MASK = TransactionRecord.VERSIONED_BIT_MASK;
  /// @deprecated internal serialization
  @Deprecated
  int BASE_LOOKUP_TABLE_LEN = TransactionRecord.BASE_LOOKUP_TABLE_LEN;

  static String getBase58Id(final byte[] signedTransaction) {
    if (signedTransaction[0] == 0) {
      throw new IllegalStateException("Transaction has not been signed yet.");
    } else {
      return Base58.encode(signedTransaction, 1, 1 + Transaction.SIGNATURE_LENGTH);
    }
  }

  static byte[] getId(final byte[] signedTransaction) {
    if (signedTransaction[0] == 0) {
      throw new IllegalStateException("Transaction has not been signed yet.");
    } else {
      return Arrays.copyOfRange(signedTransaction, 1, 1 + Transaction.SIGNATURE_LENGTH);
    }
  }

  /// @deprecated internal serialization
  @Deprecated
  static AccountMeta[] sortLegacyAccounts(final Map<PublicKey, AccountMeta> mergedAccounts) {
    return TransactionRecord.sortLegacyAccounts(mergedAccounts);
  }

  static Transaction createTx(final PublicKey feePayer, final List<Instruction> instructions) {
    return createTx(feePayer == null ? null : AccountMeta.createFeePayer(feePayer), instructions);
  }

  static Transaction createTx(final AccountMeta feePayer, final List<Instruction> instructions) {
    final var accounts = HashMap.<PublicKey, AccountMeta>newHashMap(MAX_ACCOUNTS);
    final int serializedInstructionLength = mergeAccounts(feePayer, accounts, instructions);
    return createTx(instructions, serializedInstructionLength, TransactionRecord.sortLegacyAccounts(accounts));
  }

  static Transaction createTx(final AccountMeta feePayer, final Instruction instruction) {
    return createTx(feePayer, List.of(instruction));
  }

  static Transaction createTx(final List<Instruction> instructions) {
    return createTx((AccountMeta) null, instructions);
  }

  static Transaction createTx(final AccountMeta feePayer,
                              final List<Instruction> instructions,
                              final AddressLookupTable lookupTable) {
    if (lookupTable == null) {
      return createTx(feePayer, instructions);
    }
    final var accounts = HashMap.<PublicKey, AccountMeta>newHashMap(MAX_ACCOUNTS);
    final int serializedInstructionLength = mergeAccounts(feePayer, accounts, instructions);
    return createTx(instructions, serializedInstructionLength, TransactionRecord.sortV0Accounts(accounts), lookupTable);
  }

  static Transaction createTx(final PublicKey feePayer,
                              final List<Instruction> instructions,
                              final AddressLookupTable lookupTable) {
    return createTx(feePayer == null ? null : AccountMeta.createFeePayer(feePayer), instructions, lookupTable);
  }

  static Transaction createTx(final AccountMeta feePayer,
                              final List<Instruction> instructions,
                              final AddressLookupTable lookupTable,
                              final LookupTableAccountMeta[] tableAccountMetas) {
    if (tableAccountMetas == null || tableAccountMetas.length == 0) {
      return createTx(feePayer, instructions, lookupTable);
    } else if (lookupTable != null) {
      throw new IllegalStateException("Use either a single lookup table or multiple account metas, not both");
    } else {
      return createTx(feePayer, instructions, tableAccountMetas);
    }
  }

  static Transaction createTx(final List<Instruction> instructions, final AddressLookupTable lookupTable) {
    return createTx((AccountMeta) null, instructions, lookupTable);
  }

  static Transaction createTx(final PublicKey feePayer,
                              final List<Instruction> instructions,
                              final Instruction... push) {
    final var pushed = new ArrayList<Instruction>(instructions.size() + push.length);
    Collections.addAll(pushed, push);
    pushed.addAll(instructions);
    return createTx(feePayer, pushed);
  }

  static Transaction createTx(final List<Instruction> instructions, final Instruction... push) {
    return createTx(null, instructions, push);
  }

  static Transaction createTx(final PublicKey feePayer, final Instruction instruction) {
    return createTx(feePayer, List.of(instruction));
  }

  static Transaction createTx(final Instruction instruction) {
    return createTx((AccountMeta) null, instruction);
  }

  static Transaction createTx(final List<Instruction> instructions, final AccountMeta[] sortedAccountKeys) {
    if (instructions.isEmpty()) {
      throw new IllegalArgumentException("No instructions provided");
    }
    final int serializedInstructionLength = instructions.stream().mapToInt(Instruction::serializedLength).sum();
    return createTx(instructions, serializedInstructionLength, sortedAccountKeys);
  }

  static Transaction createTx(final Instruction instruction, final AccountMeta[] sortedAccountKeys) {
    return createTx(List.of(instruction), instruction.serializedLength(), sortedAccountKeys);
  }

  static Transaction createTx(final Instruction[] instructions,
                              final int serializedInstructionLength,
                              final Map<PublicKey, AccountMeta> mergedAccounts) {
    return createTx(Arrays.asList(instructions), serializedInstructionLength, TransactionRecord.sortV0Accounts(mergedAccounts));
  }

  static Transaction createTx(final List<Instruction> instructions,
                              final int serializedInstructionLength,
                              final AccountMeta[] sortedAccounts) {
    final int numAccounts = sortedAccounts.length;
    final var accountIndexLookupTable = HashMap.<PublicKey, Integer>newHashMap(numAccounts);

    int numRequiredSignatures = 0;
    int numReadonlySignedAccounts = 0;
    int numReadonlyUnsignedAccounts = 0;

    AccountMeta feePayer = null;
    for (int i = 0; i < numAccounts; ++i) {
      final var accountMeta = sortedAccounts[i];
      accountIndexLookupTable.put(accountMeta.publicKey(), i);

      if (accountMeta.signer()) {
        if (accountMeta.feePayer()) {
          feePayer = accountMeta;
        }
        ++numRequiredSignatures;
        if (!accountMeta.write()) {
          ++numReadonlySignedAccounts;
        }
      } else if (!accountMeta.write()) {
        ++numReadonlyUnsignedAccounts;
      }
    }

    final int sigLen = 1 + (numRequiredSignatures << 6);
    final int numInstructions = instructions.size();
    final int bufferSize = sigLen
        + TransactionRecord.MSG_HEADER_LENGTH
        + getByteLen(numAccounts) + (numAccounts << 5)
        + Transaction.BLOCK_HASH_LENGTH
        + getByteLen(numInstructions) + serializedInstructionLength;

    final byte[] out = new byte[bufferSize];
    out[0] = (byte) numRequiredSignatures;

    int i = sigLen;

    // Message Header
    out[i] = (byte) numRequiredSignatures;
    out[++i] = (byte) numReadonlySignedAccounts;
    out[++i] = (byte) numReadonlyUnsignedAccounts;
    ++i;

    i += CompactU16Encoding.encodeLength(out, i, numAccounts);
    final int accountsOffset = i;
    for (final var accountMeta : sortedAccounts) {
      i += accountMeta.publicKey().write(out, i);
    }

    final int recentBlockHashIndex = i;
    i += Transaction.BLOCK_HASH_LENGTH;

    i += CompactU16Encoding.encodeLength(out, i, numInstructions);
    for (final var instruction : instructions) {
      i = instruction.serialize(out, i, accountIndexLookupTable);
    }

    return new TransactionRecord(
        feePayer,
        instructions,
        null,
        NO_TABLES,
        out,
        numRequiredSignatures,
        sigLen,
        accountsOffset,
        recentBlockHashIndex
    );
  }

  /// @deprecated internal serialization
  @Deprecated
  static AccountMeta[] sortV0Accounts(final Map<PublicKey, AccountMeta> mergedAccounts) {
    return TransactionRecord.sortV0Accounts(mergedAccounts);
  }

  static Transaction createTx(final List<Instruction> instructions,
                              final int serializedInstructionLength,
                              final Map<PublicKey, AccountMeta> mergedAccounts,
                              final AddressLookupTable lookupTable) {
    if (lookupTable == null) {
      return createTx(instructions, serializedInstructionLength, TransactionRecord.sortLegacyAccounts(mergedAccounts));
    } else {
      return createTx(instructions, serializedInstructionLength, TransactionRecord.sortV0Accounts(mergedAccounts), lookupTable);
    }
  }

  static Transaction createTx(final List<Instruction> instructions,
                              final int serializedInstructionLength,
                              final AccountMeta[] sortedAccounts,
                              final AddressLookupTable lookupTable) {
    if (lookupTable == null) {
      return createTx(instructions, serializedInstructionLength, sortedAccounts);
    }
    final int numAccounts = sortedAccounts.length;
    final var accountIndexLookupTable = HashMap.<PublicKey, Integer>newHashMap(numAccounts);

    int numRequiredSignatures = 0;
    int numReadonlySignedAccounts = 0;
    int numReadonlyUnsignedAccounts = 0;

    AccountMeta feePayer = null;
    int numLookupReads = 0;
    int numLookupWrites = 0;
    int numIncludedAccounts = 0;
    for (int i = 0, len; i < numAccounts; ++i) {
      final var account = sortedAccounts[i];
      if (account.signer()) {
        if (account.feePayer()) {
          feePayer = account;
        }
        if (!account.write()) {
          ++numReadonlySignedAccounts;
        }
        ++numRequiredSignatures;
      } else if (account.invoked() || lookupTable.indexOf(account.publicKey()) < 0) {
        if (!account.write()) {
          ++numReadonlyUnsignedAccounts;
        }
        if (i > numIncludedAccounts) {
          len = i - numIncludedAccounts;
          if (len == 1) {
            sortedAccounts[i] = sortedAccounts[numIncludedAccounts];
          } else {
            System.arraycopy(sortedAccounts, numIncludedAccounts, sortedAccounts, numIncludedAccounts + 1, len);
          }
          sortedAccounts[numIncludedAccounts] = account;
        }
      } else {
        if (account.write()) {
          ++numLookupWrites;
        } else {
          ++numLookupReads;
        }
        continue; // skip lookup accounts.
      }
      accountIndexLookupTable.put(account.publicKey(), numIncludedAccounts);
      ++numIncludedAccounts;
    }
    for (int a = numIncludedAccounts; a < numAccounts; ++a) {
      accountIndexLookupTable.put(sortedAccounts[a].publicKey(), a);
    }

    final int numTableIndexedAccounts = numLookupReads + numLookupWrites;
    final int sigLen = 1 + (numRequiredSignatures << 6);
    final int bufferSize = sigLen
        + TransactionRecord.VERSIONED_MSG_HEADER_LENGTH
        + getByteLen(numIncludedAccounts) + (numIncludedAccounts << 5)
        + Transaction.BLOCK_HASH_LENGTH
        + getByteLen(instructions.size()) + serializedInstructionLength
        + (numTableIndexedAccounts > 0 ? (1 + TransactionRecord.BASE_LOOKUP_TABLE_LEN + numTableIndexedAccounts) : 1);

    final byte[] out = new byte[bufferSize];
    out[0] = (byte) numRequiredSignatures;

    int i = sigLen;

    // Version
    out[i] = TransactionRecord.VERSIONED_BIT_MASK;

    // Message Header
    out[++i] = (byte) numRequiredSignatures;
    out[++i] = (byte) numReadonlySignedAccounts;
    out[++i] = (byte) numReadonlyUnsignedAccounts;
    ++i;

    i += CompactU16Encoding.encodeLength(out, i, numIncludedAccounts);
    final int accountsOffset = i;
    for (int a = 0; a < numIncludedAccounts; ++a) {
      i += sortedAccounts[a].publicKey().write(out, i);
    }

    final int recentBlockHashIndex = i;
    i += Transaction.BLOCK_HASH_LENGTH;

    i += CompactU16Encoding.encodeLength(out, i, instructions.size());
    for (final var instruction : instructions) {
      i = instruction.serialize(out, i, accountIndexLookupTable);
    }

    // Address Lookup Table
    if (numTableIndexedAccounts > 0) {
      i += CompactU16Encoding.encodeLength(out, i, 1);
      i += lookupTable.address().write(out, i);
      i += CompactU16Encoding.encodeLength(out, i, numLookupWrites);
      int a = numIncludedAccounts;
      for (final int to = numIncludedAccounts + numLookupWrites; a < to; ++a, ++i) {
        out[i] = lookupTable.indexOfOrThrow(sortedAccounts[a].publicKey());
      }
      i += CompactU16Encoding.encodeLength(out, i, numLookupReads);
      for (; a < numAccounts; ++a, ++i) {
        out[i] = lookupTable.indexOfOrThrow(sortedAccounts[a].publicKey());
      }
      return new TransactionRecord(
          feePayer,
          instructions,
          lookupTable,
          NO_TABLES,
          out,
          numRequiredSignatures,
          sigLen,
          accountsOffset,
          recentBlockHashIndex
      );
    } else {
      CompactU16Encoding.encodeLength(out, i, 0);
      return new TransactionRecord(
          feePayer,
          instructions,
          null,
          NO_TABLES,
          out,
          numRequiredSignatures,
          sigLen,
          accountsOffset,
          recentBlockHashIndex
      );
    }
  }

  static Transaction createTx(final AccountMeta feePayer,
                              final List<Instruction> instructions,
                              final LookupTableAccountMeta[] tableAccountMetas) {
    final var accounts = HashMap.<PublicKey, AccountMeta>newHashMap(MAX_ACCOUNTS);
    final int serializedInstructionLength = mergeAccounts(feePayer, accounts, instructions);
    return createTx(instructions, serializedInstructionLength, accounts, tableAccountMetas);
  }

  static Transaction createTx(final PublicKey feePayer,
                              final List<Instruction> instructions,
                              final LookupTableAccountMeta[] tableAccountMetas) {
    return createTx(feePayer == null ? null : AccountMeta.createFeePayer(feePayer), instructions, tableAccountMetas);
  }

  static Transaction createTx(final List<Instruction> instructions,
                              final int serializedInstructionLength,
                              final Map<PublicKey, AccountMeta> mergedAccounts,
                              final LookupTableAccountMeta[] tableAccountMetas) {
    if (tableAccountMetas == null || tableAccountMetas.length == 0) {
      return createTx(instructions, serializedInstructionLength, TransactionRecord.sortLegacyAccounts(mergedAccounts));
    } else {
      return createTx(instructions, serializedInstructionLength, TransactionRecord.sortV0Accounts(mergedAccounts), tableAccountMetas);
    }
  }

  static Transaction createTx(final List<Instruction> instructions,
                              final int serializedInstructionLength,
                              final AccountMeta[] sortedAccounts,
                              final LookupTableAccountMeta[] tableAccountMetas) {
    final int numLookupTables = tableAccountMetas.length;
    if (numLookupTables == 0) {
      return createTx(instructions);
    } else if (numLookupTables == 1) {
      return createTx(instructions, serializedInstructionLength, sortedAccounts, tableAccountMetas[0].lookupTable());
    } else {
      // Defensively reset lookup tables.
      for (final var lookupTable : tableAccountMetas) {
        lookupTable.reset();
      }
    }

    final int numAccounts = sortedAccounts.length;
    final var accountIndexLookupTable = HashMap.<PublicKey, Integer>newHashMap(numAccounts);

    int numRequiredSignatures = 0;
    int numReadonlySignedAccounts = 0;
    int numReadonlyUnsignedAccounts = 0;

    int numIncludedAccounts = 0;

    AccountMeta feePayer = null;
    SKIP_LOOKUP_ACCOUNTS:
    for (int i = 0, len; i < numAccounts; ++i) {
      final var account = sortedAccounts[i];
      if (account.signer()) {
        if (account.feePayer()) {
          feePayer = account;
        }
        if (!account.write()) {
          ++numReadonlySignedAccounts;
        }
        ++numRequiredSignatures;
      } else {
        if (!account.invoked()) {
          for (final var lookupTable : tableAccountMetas) {
            if (lookupTable.addAccountIfExists(account)) {
              continue SKIP_LOOKUP_ACCOUNTS;
            }
          }
        }
        if (!account.write()) {
          ++numReadonlyUnsignedAccounts;
        }
        if (i > numIncludedAccounts) {
          len = i - numIncludedAccounts;
          if (len == 1) {
            sortedAccounts[i] = sortedAccounts[numIncludedAccounts];
          } else {
            System.arraycopy(sortedAccounts, numIncludedAccounts, sortedAccounts, numIncludedAccounts + 1, len);
          }
          sortedAccounts[numIncludedAccounts] = account;
        }
      }
      accountIndexLookupTable.put(account.publicKey(), numIncludedAccounts);
      ++numIncludedAccounts;
    }
    int tai = numIncludedAccounts;
    for (final var tableAccountMeta : tableAccountMetas) {
      tai = tableAccountMeta.indexWrites(accountIndexLookupTable, tai);
    }
    for (final var tableAccountMeta : tableAccountMetas) {
      tai = tableAccountMeta.indexReads(accountIndexLookupTable, tai);
    }
    int numTablesWithIndexedAccounts = 0;
    for (final var tableAccountMeta : tableAccountMetas) {
      if (tableAccountMeta.numIndexed() > 0) {
        ++numTablesWithIndexedAccounts;
      }
    }

    final int sigLen = 1 + (numRequiredSignatures << 6);
    final int bufferSize = sigLen
        + TransactionRecord.VERSIONED_MSG_HEADER_LENGTH
        + getByteLen(numIncludedAccounts) + (numIncludedAccounts << 5)
        + Transaction.BLOCK_HASH_LENGTH
        + getByteLen(instructions.size()) + serializedInstructionLength
        + (1 + (numTablesWithIndexedAccounts * TransactionRecord.BASE_LOOKUP_TABLE_LEN) + (numAccounts - numIncludedAccounts));

    final byte[] out = new byte[bufferSize];
    out[0] = (byte) numRequiredSignatures;

    int i = sigLen;

    // Version
    out[i] = TransactionRecord.VERSIONED_BIT_MASK;

    // Message Header
    out[++i] = (byte) numRequiredSignatures;
    out[++i] = (byte) numReadonlySignedAccounts;
    out[++i] = (byte) numReadonlyUnsignedAccounts;
    ++i;

    // Accounts
    i += CompactU16Encoding.encodeLength(out, i, numIncludedAccounts);
    final int accountsOffset = i;
    for (int a = 0; a < numIncludedAccounts; ++a) {
      i += sortedAccounts[a].publicKey().write(out, i);
    }

    final int recentBlockHashIndex = i;
    i += Transaction.BLOCK_HASH_LENGTH;

    // Instructions
    i += CompactU16Encoding.encodeLength(out, i, instructions.size());
    for (final var instruction : instructions) {
      i = instruction.serialize(out, i, accountIndexLookupTable);
    }

    // Address Lookup Tables
    i += CompactU16Encoding.encodeLength(out, i, numTablesWithIndexedAccounts);
    if (numLookupTables == numTablesWithIndexedAccounts) {
      for (final var tableAccountMeta : tableAccountMetas) {
        i = tableAccountMeta.serialize(out, i);
      }
      return new TransactionRecord(
          feePayer,
          instructions,
          null,
          tableAccountMetas,
          out,
          numRequiredSignatures,
          sigLen,
          accountsOffset,
          recentBlockHashIndex
      );
    } else {
      final var usedTables = new LookupTableAccountMeta[numTablesWithIndexedAccounts];
      int t = 0;
      for (final var tableAccountMeta : tableAccountMetas) {
        if (tableAccountMeta.numIndexed() > 0) {
          i = tableAccountMeta.serialize(out, i);
          usedTables[t++] = tableAccountMeta;
        }
      }
      return new TransactionRecord(
          feePayer,
          instructions,
          null,
          usedTables,
          out,
          numRequiredSignatures,
          sigLen,
          accountsOffset,
          recentBlockHashIndex
      );
    }
  }

  static void setBlockHash(final byte[] data, final byte[] recentBlockHash) {
    final int numSigners = Byte.toUnsignedInt(data[0]);
    final int versionOffset = 1 + (numSigners * Transaction.SIGNATURE_LENGTH);
    final int accountMetaOffset = versionOffset + (signedByte(data[versionOffset]) ? 4 : 3);
    final int accountMetaByteLen = CompactU16Encoding.getByteLen(data, accountMetaOffset);
    final int accountMetaLen = CompactU16Encoding.decode(data, accountMetaOffset) * PublicKey.PUBLIC_KEY_LENGTH;
    final int recentBlockHashOffset = accountMetaOffset + accountMetaByteLen + accountMetaLen;
    System.arraycopy(recentBlockHash, 0, data, recentBlockHashOffset, Transaction.BLOCK_HASH_LENGTH);
  }

  static void sign(final Signer signer,
                   final byte[] out,
                   final int msgOffset,
                   final int msgLen,
                   int offset) {
    signer.sign(out, msgOffset, msgLen, offset);
  }

  static void sign(final Signer signer, final byte[] out) {
    out[0] = 1;
    final int sigLen = 1 + Transaction.SIGNATURE_LENGTH;
    final int msgLen = out.length - sigLen;
    Transaction.sign(signer, out, sigLen, msgLen, 1);
  }

  static String signAndBase64Encode(final Signer signer, final byte[] out) {
    sign(signer, out);
    return Base64.getEncoder().encodeToString(out);
  }

  static void sign(final SequencedCollection<Signer> signers,
                   final byte[] out,
                   final int msgOffset,
                   final int msgLen,
                   int offset) {
    for (final var signer : signers) {
      offset = signer.sign(out, msgOffset, msgLen, offset);
    }
  }

  static void sign(final SequencedCollection<Signer> signers, final byte[] out) {
    final int numSigners = signers.size();
    out[0] = (byte) numSigners;
    final int sigLen = 1 + (numSigners * Transaction.SIGNATURE_LENGTH);
    final int msgLen = out.length - sigLen;
    Transaction.sign(signers, out, sigLen, msgLen, 1);
  }

  static String signAndBase64Encode(final SequencedCollection<Signer> signers, final byte[] out) {
    sign(signers, out);
    return Base64.getEncoder().encodeToString(out);
  }

  default String base64EncodeToString() {
    return Base64.getEncoder().encodeToString(serialized());
  }

  void sign(final Signer signer);

  void sign(final int index, final Signer signer);

  default String signAndBase64Encode(final Signer signer) {
    sign(signer);
    return base64EncodeToString();
  }

  default void sign(final byte[] recentBlockHash, final Signer signer) {
    setRecentBlockHash(recentBlockHash);
    sign(signer);
  }

  default void sign(final String recentBlockHash, final Signer signer) {
    sign(Base58.decode(recentBlockHash), signer);
  }

  default String signAndBase64Encode(final byte[] recentBlockHash, final Signer signer) {
    sign(recentBlockHash, signer);
    return base64EncodeToString();
  }

  default String signAndBase64Encode(final String recentBlockHash, final Signer signer) {
    return signAndBase64Encode(Base58.decode(recentBlockHash), signer);
  }

  void sign(final Collection<Signer> signers);

  void sign(final SequencedCollection<Signer> signers);

  default String signAndBase64Encode(final SequencedCollection<Signer> signers) {
    sign(signers);
    return base64EncodeToString();
  }

  default void sign(final byte[] recentBlockHash, final SequencedCollection<Signer> signers) {
    setRecentBlockHash(recentBlockHash);
    sign(signers);
  }

  default void sign(final String recentBlockHash, final SequencedCollection<Signer> signers) {
    setRecentBlockHash(recentBlockHash);
    sign(signers);
  }

  default String signAndBase64Encode(final byte[] recentBlockHash, final SequencedCollection<Signer> signers) {
    sign(recentBlockHash, signers);
    return base64EncodeToString();
  }

  default String signAndBase64Encode(final String recentBlockHash, final SequencedCollection<Signer> signers) {
    sign(recentBlockHash, signers);
    return base64EncodeToString();
  }

  String getBase58Id();

  byte[] getId();

  int size();

  default boolean exceedsSizeLimit() {
    return size() > Transaction.MAX_SERIALIZED_LENGTH;
  }

  List<Instruction> instructions();

  AddressLookupTable lookupTable();

  LookupTableAccountMeta[] tableAccountMetas();

  void setRecentBlockHash(final byte[] recentBlockHash);

  void setRecentBlockHash(final String recentBlockHash);

  byte[] recentBlockHash();

  int version();

  int numSigners();

  byte[] serialized();

  Transaction prependIx(final Instruction ix);

  Transaction prependInstructions(final Instruction ix1, final Instruction ix2);

  Transaction prependInstructions(final SequencedCollection<Instruction> instructions);

  Transaction appendIx(final Instruction ix);

  Transaction appendInstructions(final SequencedCollection<Instruction> instructions);

  Transaction replaceInstruction(final int index, final Instruction instruction);

  AccountMeta feePayer();
}
