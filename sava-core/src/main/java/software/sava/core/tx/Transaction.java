package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.encoding.Base58;
import software.sava.core.encoding.CompactU16Encoding;

import java.util.*;

import static software.sava.core.encoding.CompactU16Encoding.getByteLen;
import static software.sava.core.encoding.CompactU16Encoding.signedByte;
import static software.sava.core.tx.TransactionRecord.NO_TABLES;
import static software.sava.core.tx.TransactionRecord.mergeAccounts;

public interface Transaction {

  int SIGNATURE_LENGTH = 64;
  int BLOCK_HASH_LENGTH = 32;
  int MAX_ACCOUNTS = 64;
  int BLOCK_QUEUE_SIZE = 151;
  int BLOCKS_UNTIL_FINALIZED = 32;

  /// Returns the fee payer's signature, the transaction id, Base58 encoded.
  ///
  /// @throws IllegalStateException    if no signer is declared or the fee payer signature is all
  ///                                  zero
  /// @throws IllegalArgumentException if `signedTransaction` is too short for its fee payer
  ///                                  signature, or a v1 buffer cannot hold every declared
  ///                                  signature or its message does not end where they begin
  /// @throws ArrayIndexOutOfBoundsException if `signedTransaction` is empty or holds only the v1
  ///                                        version byte
  static String getBase58Id(final byte[] signedTransaction) {
    final int offset = BaseTransaction.signedIdOffset(signedTransaction);
    return Base58.encode(signedTransaction, offset, offset + Transaction.SIGNATURE_LENGTH);
  }

  /// Like [#getBase58Id(byte\[\])], but returns a copy of the raw signature bytes.
  static byte[] getId(final byte[] signedTransaction) {
    final int offset = BaseTransaction.signedIdOffset(signedTransaction);
    return Arrays.copyOfRange(signedTransaction, offset, offset + Transaction.SIGNATURE_LENGTH);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
  static Transaction createTx(final PublicKey feePayer, final List<Instruction> instructions) {
    return createTx(feePayer == null ? null : AccountMeta.createFeePayer(feePayer), instructions);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
  static Transaction createTx(final AccountMeta feePayer, final List<Instruction> instructions) {
    final var accounts = HashMap.<PublicKey, AccountMeta>newHashMap(MAX_ACCOUNTS);
    final int serializedInstructionLength = mergeAccounts(feePayer, accounts, instructions);
    return createTx(instructions, serializedInstructionLength, TransactionRecord.sortLegacyAccounts(accounts));
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
  static Transaction createTx(final AccountMeta feePayer, final Instruction instruction) {
    return createTx(feePayer, List.of(instruction));
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
  static Transaction createTx(final List<Instruction> instructions) {
    return createTx((AccountMeta) null, instructions);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
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

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
  static Transaction createTx(final PublicKey feePayer,
                              final List<Instruction> instructions,
                              final AddressLookupTable lookupTable) {
    return createTx(feePayer == null ? null : AccountMeta.createFeePayer(feePayer), instructions, lookupTable);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
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

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
  static Transaction createTx(final List<Instruction> instructions, final AddressLookupTable lookupTable) {
    return createTx((AccountMeta) null, instructions, lookupTable);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
  static Transaction createTx(final PublicKey feePayer,
                              final List<Instruction> instructions,
                              final Instruction... push) {
    final var pushed = new ArrayList<Instruction>(instructions.size() + push.length);
    Collections.addAll(pushed, push);
    pushed.addAll(instructions);
    return createTx(feePayer, pushed);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
  static Transaction createTx(final List<Instruction> instructions, final Instruction... push) {
    return createTx(null, instructions, push);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
  static Transaction createTx(final PublicKey feePayer, final Instruction instruction) {
    return createTx(feePayer, List.of(instruction));
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
  static Transaction createTx(final Instruction instruction) {
    return createTx((AccountMeta) null, instruction);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
  static Transaction createTx(final List<Instruction> instructions, final AccountMeta[] sortedAccountKeys) {
    if (instructions.isEmpty()) {
      throw new IllegalArgumentException("No instructions provided");
    }
    final int serializedInstructionLength = instructions.stream().mapToInt(Instruction::serializedLength).sum();
    return createTx(instructions, serializedInstructionLength, sortedAccountKeys);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
  static Transaction createTx(final Instruction instruction, final AccountMeta[] sortedAccountKeys) {
    return createTx(List.of(instruction), instruction.serializedLength(), sortedAccountKeys);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
  static Transaction createTx(final Instruction[] instructions,
                              final int serializedInstructionLength,
                              final Map<PublicKey, AccountMeta> mergedAccounts) {
    return createTx(Arrays.asList(instructions), serializedInstructionLength, TransactionRecord.sortV0Accounts(mergedAccounts));
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
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

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
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

  // TODO: deprecate once v1 transactions are active on mainnet
  /// With a lookup table, reorders `sortedAccounts` in place: message accounts first, then
  /// table-loaded accounts, each group keeping its relative order. Pass a clone to keep the input
  /// order. A null table serializes a legacy transaction and leaves the array unchanged.
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
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

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
  static Transaction createTx(final AccountMeta feePayer,
                              final List<Instruction> instructions,
                              final LookupTableAccountMeta[] tableAccountMetas) {
    final var accounts = HashMap.<PublicKey, AccountMeta>newHashMap(MAX_ACCOUNTS);
    final int serializedInstructionLength = mergeAccounts(feePayer, accounts, instructions);
    return createTx(instructions, serializedInstructionLength, accounts, tableAccountMetas);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
  static Transaction createTx(final PublicKey feePayer,
                              final List<Instruction> instructions,
                              final LookupTableAccountMeta[] tableAccountMetas) {
    return createTx(feePayer == null ? null : AccountMeta.createFeePayer(feePayer), instructions, tableAccountMetas);
  }

  // TODO: deprecate once v1 transactions are active on mainnet
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
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

  // TODO: deprecate once v1 transactions are active on mainnet
  /// With one or more lookup tables, reorders `sortedAccounts` in place: message accounts first,
  /// then table-loaded accounts, each group keeping its relative order. Pass a clone to keep the
  /// input order. With several tables, each entry of `tableAccountMetas` is reset and refilled.
  /// An empty `tableAccountMetas` rebuilds a legacy transaction from `instructions` alone, neither
  /// using nor changing `sortedAccounts`, so an account only it holds, such as a fee payer, is
  /// dropped.
  // /// @deprecated use {@link TxBuilder} to create a v1 transaction instead.
  // @Deprecated
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
    final int recentBlockHashOffset;
    if (V1Transaction.isV1(data)) {
      recentBlockHashOffset = V1TransactionSkeleton.V1_RECENT_BLOCK_HASH_INDEX;
    } else {
      final int numSigners = Byte.toUnsignedInt(data[0]);
      final int versionOffset = 1 + (numSigners * Transaction.SIGNATURE_LENGTH);
      final int accountMetaOffset = versionOffset + (signedByte(data[versionOffset]) ? 4 : 3);
      final int accountMetaByteLen = CompactU16Encoding.getByteLen(data, accountMetaOffset);
      final int accountMetaLen = CompactU16Encoding.decode(data, accountMetaOffset) * PublicKey.PUBLIC_KEY_LENGTH;
      recentBlockHashOffset = accountMetaOffset + accountMetaByteLen + accountMetaLen;
    }
    System.arraycopy(recentBlockHash, 0, data, recentBlockHashOffset, Transaction.BLOCK_HASH_LENGTH);
  }

  static void sign(final Signer signer,
                   final byte[] out,
                   final int msgOffset,
                   final int msgLen,
                   final int sigOffset) {
    signer.sign(out, msgOffset, msgLen, sigOffset);
  }

  /// Signs the serialized transaction `out` in place into its first signature slot, whatever the
  /// signer's public key.
  ///
  /// A v1 `out` may require more signers; their slots are left untouched.
  ///
  /// @throws IllegalArgumentException if a legacy/v0 `out` does not declare exactly one required
  ///                                  signature or its signature layout is inconsistent, or if a v1
  ///                                  `out`'s signature block does not begin where its message ends
  static void sign(final Signer signer, final byte[] out) {
    BaseTransaction.signInOrder(Collections.singletonList(signer), out, true);
  }

  static String signAndBase64Encode(final Signer signer, final byte[] out) {
    sign(signer, out);
    return Base64.getEncoder().encodeToString(out);
  }

  /// @deprecated Use [#signInOrder(SequencedCollection, byte\[\], int, int, int)], which behaves
  ///             identically.
  @Deprecated(forRemoval = true)
  static void sign(final SequencedCollection<Signer> signers,
                   final byte[] out,
                   final int msgOffset,
                   final int msgLen,
                   final int sigOffset) {
    signInOrder(signers, out, msgOffset, msgLen, sigOffset);
  }

  /// @throws IllegalArgumentException if `signers` does not match the required signature count that
  ///                                  `out` declares
  /// @deprecated Use [#signInOrder(SequencedCollection, byte\[\])], which behaves identically:
  ///             signing is positional and public keys are not matched to slots.
  @Deprecated(forRemoval = true)
  static void sign(final SequencedCollection<Signer> signers, final byte[] out) {
    signInOrder(signers, out);
  }

  /// @deprecated Use [#signInOrderAndBase64Encode(SequencedCollection, byte\[\])], which behaves
  ///             identically.
  @Deprecated(forRemoval = true)
  static String signAndBase64Encode(final SequencedCollection<Signer> signers, final byte[] out) {
    return signInOrderAndBase64Encode(signers, out);
  }

  /// Signs `msgLen` bytes of `out` from `msgOffset` into consecutive signature slots starting at
  /// `sigOffset`, in iteration order. Neither signer public keys nor the layout are checked.
  static void signInOrder(final SequencedCollection<Signer> signers,
                          final byte[] out,
                          final int msgOffset,
                          final int msgLen,
                          int sigOffset) {
    for (final var signer : signers) {
      sigOffset = signer.sign(out, msgOffset, msgLen, sigOffset);
    }
  }

  /// Signs the serialized legacy, v0 or v1 transaction `out` in place, one required slot per signer
  /// in iteration order, whatever each signer's public key.
  ///
  /// @throws IllegalArgumentException if the signer count differs from the count `out` declares, or
  ///                                  its signature layout is inconsistent
  static void signInOrder(final SequencedCollection<Signer> signers, final byte[] out) {
    BaseTransaction.signInOrder(signers, out, false);
  }

  /// Signs as [#signInOrder(SequencedCollection, byte\[\])], then returns `out` Base64 encoded.
  static String signInOrderAndBase64Encode(final SequencedCollection<Signer> signers, final byte[] out) {
    signInOrder(signers, out);
    return Base64.getEncoder().encodeToString(out);
  }

  default String base64EncodeToString() {
    return Base64.getEncoder().encodeToString(serialized());
  }

  /// Signs the slot whose required signer address matches [Signer#publicKey()].
  ///
  /// @throws IllegalArgumentException if this transaction does not require that signer
  void sign(final Signer signer);

  /// Signs the required-signature slot at zero-based `index`, whatever the signer's public key.
  ///
  /// @throws IllegalArgumentException if `index` is negative or not less than [#numSigners()]
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

  /// Signs each required slot with the signer whose public key matches it; iteration order is
  /// irrelevant. Nothing is signed unless `signers` is exactly the required set.
  ///
  /// An argument typed as a [SequencedCollection], a [List] included, binds to the deprecated
  /// positional [#sign(SequencedCollection)] instead; use [#signByKey(Collection)] to sign it by
  /// key.
  ///
  /// @throws IllegalArgumentException if `signers` is not exactly the required signers: a wrong
  ///                                  count, a duplicate, or an unknown signer
  void sign(final Collection<Signer> signers);

  /// Signs each required slot positionally: the first signer writes the first slot, whatever its
  /// public key.
  ///
  /// @deprecated Use [#signInOrder(SequencedCollection)] to keep positional signing, or
  ///             [#signByKey(Collection)] to match public keys. Migrate before removal: a
  ///             recompiled `sign(list)` call would otherwise bind to [#sign(Collection)] and sign
  ///             by key.
  @Deprecated(forRemoval = true)
  void sign(final SequencedCollection<Signer> signers);

  /// @deprecated Use [#signInOrderAndBase64Encode(SequencedCollection)] to keep positional
  ///             signing, or [#signByKeyAndBase64Encode(Collection)] to match public keys.
  @Deprecated(forRemoval = true)
  default String signAndBase64Encode(final SequencedCollection<Signer> signers) {
    sign(signers);
    return base64EncodeToString();
  }

  /// @deprecated Use [#signInOrder(byte\[\], SequencedCollection)] to keep positional signing,
  ///             or [#signByKey(byte\[\], Collection)] to match public keys.
  @Deprecated(forRemoval = true)
  default void sign(final byte[] recentBlockHash, final SequencedCollection<Signer> signers) {
    setRecentBlockHash(recentBlockHash);
    sign(signers);
  }

  /// @deprecated Use [#signInOrder(String, SequencedCollection)] to keep positional signing,
  ///             or [#signByKey(String, Collection)] to match public keys.
  @Deprecated(forRemoval = true)
  default void sign(final String recentBlockHash, final SequencedCollection<Signer> signers) {
    setRecentBlockHash(recentBlockHash);
    sign(signers);
  }

  /// @deprecated Use [#signInOrderAndBase64Encode(byte\[\], SequencedCollection)] to keep
  ///             positional signing, or [#signByKeyAndBase64Encode(byte\[\], Collection)] to
  ///             match public keys.
  @Deprecated(forRemoval = true)
  default String signAndBase64Encode(final byte[] recentBlockHash, final SequencedCollection<Signer> signers) {
    sign(recentBlockHash, signers);
    return base64EncodeToString();
  }

  /// @deprecated Use [#signInOrderAndBase64Encode(String, SequencedCollection)] to keep
  ///             positional signing, or [#signByKeyAndBase64Encode(String, Collection)] to
  ///             match public keys.
  @Deprecated(forRemoval = true)
  default String signAndBase64Encode(final String recentBlockHash, final SequencedCollection<Signer> signers) {
    sign(recentBlockHash, signers);
    return base64EncodeToString();
  }

  /// Signs each required slot in iteration order: the first signer writes the first slot, whatever
  /// its public key, so the order must match the message's required signers.
  ///
  /// @throws IllegalArgumentException if the collection size differs from [#numSigners()]
  default void signInOrder(final SequencedCollection<Signer> signers) {
    // Preserve existing implementors' positional override. When removing that overload, migrate
    // this body too: leaving sign(signers) would silently bind to sign(Collection) instead.
    sign(signers);
  }

  /// Signs positionally and returns the signed transaction Base64 encoded.
  default String signInOrderAndBase64Encode(final SequencedCollection<Signer> signers) {
    return signAndBase64Encode(signers);
  }

  /// Sets the recent blockhash, then signs positionally.
  default void signInOrder(final byte[] recentBlockHash, final SequencedCollection<Signer> signers) {
    sign(recentBlockHash, signers);
  }

  /// Sets the Base58 recent blockhash, then signs positionally.
  default void signInOrder(final String recentBlockHash, final SequencedCollection<Signer> signers) {
    sign(recentBlockHash, signers);
  }

  /// Sets the recent blockhash, signs positionally, and returns the signed transaction as Base64.
  default String signInOrderAndBase64Encode(final byte[] recentBlockHash, final SequencedCollection<Signer> signers) {
    return signAndBase64Encode(recentBlockHash, signers);
  }

  /// Sets the Base58 blockhash, signs positionally, and returns the signed transaction as Base64.
  default String signInOrderAndBase64Encode(final String recentBlockHash, final SequencedCollection<Signer> signers) {
    return signAndBase64Encode(recentBlockHash, signers);
  }

  /// Signs by public key as [#sign(Collection)] does, for any collection type, [List] included.
  ///
  /// @throws IllegalArgumentException if `signers` is not exactly the required signers: a wrong
  ///                                  count, a duplicate, or an unknown signer
  default void signByKey(final Collection<Signer> signers) {
    sign(signers);
  }

  /// Signs by public key and returns the signed transaction Base64 encoded.
  default String signByKeyAndBase64Encode(final Collection<Signer> signers) {
    signByKey(signers);
    return base64EncodeToString();
  }

  /// Sets the recent blockhash, then signs by public key.
  default void signByKey(final byte[] recentBlockHash, final Collection<Signer> signers) {
    setRecentBlockHash(recentBlockHash);
    signByKey(signers);
  }

  /// Sets the Base58 recent blockhash, then signs by public key.
  default void signByKey(final String recentBlockHash, final Collection<Signer> signers) {
    setRecentBlockHash(recentBlockHash);
    signByKey(signers);
  }

  /// Sets the recent blockhash, signs by public key, and returns the signed transaction as Base64.
  default String signByKeyAndBase64Encode(final byte[] recentBlockHash, final Collection<Signer> signers) {
    signByKey(recentBlockHash, signers);
    return base64EncodeToString();
  }

  /// Sets the Base58 blockhash, signs by public key, and returns the signed transaction as Base64.
  default String signByKeyAndBase64Encode(final String recentBlockHash, final Collection<Signer> signers) {
    signByKey(recentBlockHash, signers);
    return base64EncodeToString();
  }

  /// Returns the fee payer's signature, the transaction id, Base58 encoded.
  ///
  /// @throws IllegalStateException    if the fee payer signature slot is missing or all zero
  /// @throws IllegalArgumentException if [#serialized()] is malformed, as for
  ///                                  [#getBase58Id(byte\[\])]
  String getBase58Id();

  /// Like [#getBase58Id()], with the same exceptions, but returns a copy of the raw signature
  /// bytes.
  byte[] getId();

  int size();

  /// Whether [#size()] exceeds this format's limit: 1232 bytes for legacy and v0, 4096 for v1. The
  /// default applies the legacy limit.
  default boolean exceedsSizeLimit() {
    return size() > TxBuilderImpl.MAX_SERIALIZED_LENGTH_LEGACY;
  }

  /// The number of accounts the message declares, including those loaded from address lookup
  /// tables.
  ///
  /// The default reparses [#serialized()] on every call; implementations should override it.
  default int numAccounts() {
    return TransactionSkeleton.deserializeSkeleton(serialized()).numAccounts();
  }

  /// Whether [#numAccounts()] exceeds [#MAX_ACCOUNTS].
  default boolean exceedsAccountLimit() {
    return numAccounts() > MAX_ACCOUNTS;
  }

  /// The number of top-level instructions.
  default int numInstructions() {
    return instructions().size();
  }

  /// Whether there are more than 64 top-level instructions: SIMD-0385's limit for v1, and the
  /// instruction trace limit legacy and v0 transactions hit at execution.
  default boolean exceedsInstructionLimit() {
    return numInstructions() > BaseTransaction.MAX_INSTRUCTIONS;
  }

  int numSigners();

  /// Whether more than 12 signatures are required, SIMD-0385's limit for v1. Always false for legacy
  /// and v0, which only the size limit bounds.
  default boolean exceedsSignatureLimit() {
    return version() == 1 && numSigners() > TxBuilderImpl.MAX_V1_SIGNATURES;
  }

  AccountMeta feePayer();

  List<Instruction> instructions();

  AddressLookupTable lookupTable();

  LookupTableAccountMeta[] tableAccountMetas();

  void setRecentBlockHash(final byte[] recentBlockHash);

  void setRecentBlockHash(final String recentBlockHash);

  byte[] recentBlockHash();

  int version();

  byte[] serialized();

  Transaction prependIx(final Instruction ix);

  Transaction prependInstructions(final Instruction ix1, final Instruction ix2);

  Transaction prependInstructions(final SequencedCollection<Instruction> instructions);

  Transaction appendIx(final Instruction ix);

  Transaction appendInstructions(final SequencedCollection<Instruction> instructions);

  Transaction replaceInstruction(final int index, final Instruction instruction);

  /// Sets the priority fee, in lamports.
  ///
  /// A v1 transaction overwrites its priority fee ConfigValue in place and returns `this`.
  ///
  /// Legacy and v0 transactions return a new transaction with a SetComputeUnitPrice instruction, in
  /// micro-lamports per compute unit, replaced or prepended. The price is rounded up so the runtime
  /// charges at least the given lamports against the limit it will apply: the SetComputeUnitLimit
  /// value if present, otherwise the runtime's default per-instruction allocation (counting a
  /// prepended instruction), capped at 1.4 million. An explicit limit of 0 writes a price of 0,
  /// and a negative fee or an overflow saturates the price at [Long#MAX_VALUE]. Set the compute
  /// unit limit first.
  ///
  /// @throws IllegalStateException if this v1 transaction's TransactionConfigMask lacks the priority
  ///                               fee bits: none was given to the [TxBuilder], or it was built
  ///                               elsewhere without one
  /// @throws UnsupportedOperationException from the default, which built-in implementations
  ///                                       override
  default Transaction setPriorityFeeLamports(final long priorityFeeLamports) {
    throw new UnsupportedOperationException("This Transaction implementation does not support setPriorityFeeLamports.");
  }

  /// Sets the compute unit limit.
  ///
  /// Legacy and v0 transactions return a new transaction with a SetComputeUnitLimit instruction
  /// replaced or prepended. A v1 transaction overwrites its ConfigValue in place and returns `this`;
  /// [TxBuilder] reserves that ConfigValue by default.
  ///
  /// @throws IllegalStateException if this v1 transaction's TransactionConfigMask lacks the compute
  ///                               unit limit bit: it was cleared, or it was built elsewhere
  ///                               without one
  /// @throws UnsupportedOperationException from the default, which built-in implementations
  ///                                       override
  default Transaction setComputeUnitLimit(final int computeUnitLimit) {
    throw new UnsupportedOperationException("This Transaction implementation does not support setComputeUnitLimit.");
  }

  /// Sets the priority fee from a SetComputeUnitPrice price, in micro-lamports per compute unit.
  ///
  /// Legacy and v0 transactions return a new transaction with that SetComputeUnitPrice instruction
  /// replaced or prepended, so their fee keeps following the compute unit limit in effect.
  ///
  /// A v1 transaction converts the price to lamports, capped and rounded up as
  /// [TxBuilder#computeUnitPriceToPriorityFeeLamports(long, int)] does, against its current compute
  /// unit limit, or 1.4 million if none is set; it overwrites its priority fee ConfigValue in place
  /// and returns `this`. The conversion is one-time: a limit changed afterwards does not move the
  /// fee, so when tightening after simulation set the limit first, or use
  /// [#setPriorityFeeLamportsFromComputeUnitPrice(long, int)].
  ///
  /// @throws IllegalStateException if this v1 transaction's TransactionConfigMask lacks the priority
  ///                               fee bits: none was given to the [TxBuilder], or it was built
  ///                               elsewhere without one
  /// @throws UnsupportedOperationException from the default, which built-in implementations
  ///                                       override
  default Transaction setPriorityFeeLamportsFromComputeUnitPrice(final long microLamportsPerComputeUnit) {
    throw new UnsupportedOperationException("This Transaction implementation does not support setPriorityFeeLamportsFromComputeUnitPrice.");
  }

  /// Sets the compute unit limit, and the priority fee from a SetComputeUnitPrice price, in
  /// micro-lamports per compute unit, sized against that limit.
  ///
  /// Legacy and v0 transactions return a new transaction with SetComputeUnitLimit and
  /// SetComputeUnitPrice instructions replaced or prepended. A v1 transaction overwrites both
  /// ConfigValues in place and returns `this`; prefer this overload when tightening a v1
  /// transaction after simulation. As with [#setPriorityFeeLamportsFromComputeUnitPrice(long)], the
  /// v1 fee does not follow a later limit change.
  ///
  /// The built-in implementations never half-update: on failure this transaction is unchanged. The
  /// default throws rather than composing [#setComputeUnitLimit(int)] with the one-argument overload,
  /// which could apply the limit and then throw.
  ///
  /// @throws IllegalStateException if this v1 transaction's TransactionConfigMask lacks the compute
  ///                               unit limit or priority fee bits: they were not given to the
  ///                               [TxBuilder], or it was built elsewhere without them
  /// @throws UnsupportedOperationException from the default, which built-in implementations
  ///                                       override
  /// @see TxBuilder#computeUnitPriceToPriorityFeeLamports(long, int)
  default Transaction setPriorityFeeLamportsFromComputeUnitPrice(final long microLamportsPerComputeUnit,
                                                                 final int computeUnitLimit) {
    throw new UnsupportedOperationException("This Transaction implementation does not support setPriorityFeeLamportsFromComputeUnitPrice.");
  }

  /// Sets the loaded accounts data size limit, in bytes. The runtime clamps values above its 64MiB
  /// maximum rather than rejecting them.
  ///
  /// Legacy and v0 transactions return a new transaction with a SetLoadedAccountsDataSizeLimit
  /// instruction replaced or prepended; the runtime rejects 0 there. A v1 transaction overwrites
  /// its ConfigValue in place and returns `this`; [TxBuilder] reserves that ConfigValue by default,
  /// and 0 is valid, the same as leaving the limit unset at 0 bytes (SIMD-0385).
  ///
  /// @throws IllegalArgumentException if the limit is not greater than 0 on a legacy or v0
  ///                                  transaction
  /// @throws IllegalStateException    if this v1 transaction's TransactionConfigMask lacks the
  ///                                  account data size limit bit: it was cleared, or it was built
  ///                                  elsewhere without one
  /// @throws UnsupportedOperationException from the default, which built-in implementations
  ///                                       override
  default Transaction setAccountDataSizeLimit(final int accountDataSizeLimit) {
    throw new UnsupportedOperationException("This Transaction implementation does not support setAccountDataSizeLimit.");
  }

  /// Sets the requested heap size, in bytes: a multiple of 1KiB from 32KiB to 256KiB inclusive.
  ///
  /// Legacy and v0 transactions return a new transaction with a RequestHeapFrame instruction
  /// replaced or prepended. A v1 transaction overwrites its ConfigValue in place and returns `this`.
  ///
  /// @throws IllegalArgumentException if the heap size is not a multiple of 1KiB in that range
  /// @throws IllegalStateException    if this v1 transaction's TransactionConfigMask lacks the heap
  ///                                  size bit: none was given to the [TxBuilder], or it was built
  ///                                  elsewhere without one
  /// @throws UnsupportedOperationException from the default, which built-in implementations
  ///                                       override
  default Transaction setHeapSize(final int heapSize) {
    throw new UnsupportedOperationException("This Transaction implementation does not support setHeapSize.");
  }
}
