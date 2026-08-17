package software.sava.core.tx;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.encoding.Base58;

import java.util.*;
import java.util.function.BiFunction;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.accounts.meta.AccountMeta.ACCOUNT_META_ARRAY_GENERATOR;
import static software.sava.core.encoding.CompactU16Encoding.signedByte;

record TransactionRecord(AccountMeta feePayer,
                         List<Instruction> instructions,
                         AddressLookupTable lookupTable,
                         LookupTableAccountMeta[] tableAccountMetas,
                         byte[] data,
                         int numSigners,
                         int messageOffset,
                         int accountsOffset,
                         int recentBlockHashIndex) implements Transaction {

  static final LookupTableAccountMeta[] NO_TABLES = new LookupTableAccountMeta[0];

  static final BiFunction<AccountMeta, AccountMeta, AccountMeta> MERGE_ACCOUNT_META = (prev, add) -> prev == null ? add : prev.merge(add);

  // fee payer, sign, write, read
  static final Comparator<AccountMeta> LEGACY_META_COMPARATOR = (am1, am2) -> {
    if (am1.feePayer()) {
      return -1;
    } else if (am2.feePayer()) {
      return 1;
    } else if (am1.signer() == am2.signer()) {
      if (am1.write() == am2.write()) {
        return 0;
      } else {
        return am1.write() ? -1 : 1;
      }
    } else {
      return am1.signer() ? -1 : 1;
    }
  };

  static final Comparator<AccountMeta> VO_META_COMPARATOR = (am1, am2) -> {
    if (am1.feePayer()) {
      return -1;
    } else if (am2.feePayer()) {
      return 1;
    } else if (am1.signer() == am2.signer()) {
      if (am1.write() == am2.write()) {
        return am1.invoked() == am2.invoked() ? 0 : am1.invoked() ? -1 : 1;
      } else {
        return am1.write() ? -1 : 1;
      }
    } else {
      return am1.signer() ? -1 : 1;
    }
  };

  static final int MSG_HEADER_LENGTH = 3;
  static final int VERSIONED_MSG_HEADER_LENGTH = 1 + MSG_HEADER_LENGTH;
  static final byte VERSIONED_BIT_MASK = (byte) (1 << 7);
  static final int BASE_LOOKUP_TABLE_LEN = PUBLIC_KEY_LENGTH + 2;

  static AccountMeta[] sortLegacyAccounts(final Map<PublicKey, AccountMeta> mergedAccounts) {
    final var accountMetas = mergedAccounts.values().toArray(ACCOUNT_META_ARRAY_GENERATOR);
    Arrays.sort(accountMetas, LEGACY_META_COMPARATOR);
    return accountMetas;
  }

  static AccountMeta[] sortV0Accounts(final Map<PublicKey, AccountMeta> mergedAccounts) {
    final var accountMetas = mergedAccounts.values().toArray(ACCOUNT_META_ARRAY_GENERATOR);
    Arrays.sort(accountMetas, VO_META_COMPARATOR);
    return accountMetas;
  }

  static int mergeAccounts(final AccountMeta feePayer,
                           final Map<PublicKey, AccountMeta> accounts,
                           final List<Instruction> instructions) {
    final int numInstructions = instructions.size();
    if (numInstructions == 0) {
      throw new IllegalArgumentException("No instructions provided");
    }
    if (feePayer != null) {
      accounts.put(feePayer.publicKey(), feePayer);
    }
    int serializedInstructionLength = 0;
    for (final var instruction : instructions) {
      serializedInstructionLength += instruction.serializedLength();
      for (final var meta : instruction.accounts()) {
        accounts.merge(meta.publicKey(), meta, MERGE_ACCOUNT_META);
      }
      final var programMeta = instruction.programId();
      accounts.merge(programMeta.publicKey(), programMeta, MERGE_ACCOUNT_META);
    }
    return serializedInstructionLength;
  }

  @Override
  public List<Instruction> instructions() {
    return instructions;
  }

  @Override
  public AddressLookupTable lookupTable() {
    return lookupTable;
  }

  @Override
  public LookupTableAccountMeta[] tableAccountMetas() {
    return tableAccountMetas;
  }

  @Override
  public void setRecentBlockHash(final byte[] recentBlockHash) {
    if (recentBlockHash == null || recentBlockHash.length != Transaction.BLOCK_HASH_LENGTH) {
      throw new IllegalArgumentException("32 byte recent blockHash is required");
    }
    System.arraycopy(recentBlockHash, 0, this.data, this.recentBlockHashIndex, Transaction.BLOCK_HASH_LENGTH);
  }

  @Override
  public void setRecentBlockHash(final String recentBlockHash) {
    setRecentBlockHash(Base58.decode(recentBlockHash));
  }

  @Override
  public byte[] recentBlockHash() {
    return Arrays.copyOfRange(data, recentBlockHashIndex, recentBlockHashIndex + Transaction.BLOCK_HASH_LENGTH);
  }

  @Override
  public int version() {
    int version = data[messageOffset] & 0xFF;
    return signedByte(version) ? version & 0x7F : VERSIONED_BIT_MASK;
  }

  @Override
  public boolean exceedsSizeLimit() {
    return size() > 1232;
  }

  @Override
  public byte[] serialized() {
    return this.data;
  }

  @Override
  public void sign(final Signer signer) {
    final int signerIndex = signerIndex(signer);
    this.data[0] = (byte) numSigners;
    sign(signerIndex, signer);
  }

  private int signerIndex(final Signer signer) {
    final byte[] pubKey = signer.publicKey().toByteArray();
    for (int from = accountsOffset, i = 0; i < numSigners; ++i, from += PUBLIC_KEY_LENGTH) {
      if (Arrays.equals(pubKey, 0, PUBLIC_KEY_LENGTH, data, from, from + PUBLIC_KEY_LENGTH)) {
        return i;
      }
    }
    throw new IllegalArgumentException("Failed to find index for signer " + signer.publicKey());
  }

  @Override
  public void sign(final int index, final Signer signer) {
    if (index < 0 || index >= numSigners) {
      throw new IllegalArgumentException(String.format(
          "Invalid signer index %d for transaction with %d required signers.", index, numSigners
      ));
    }
    Transaction.sign(
        signer,
        this.data,
        this.messageOffset,
        this.data.length - this.messageOffset,
        1 + (index * SIGNATURE_LENGTH)
    );
  }

  @Override
  public void sign(final SequencedCollection<Signer> signers) {
    final int numSigners = signers.size();
    if (numSigners != this.numSigners) {
      throw new IllegalArgumentException(String.format("Expected %d signers, only passed %d.", this.numSigners, numSigners));
    }
    this.data[0] = (byte) numSigners;
    Transaction.sign(signers, this.data, this.messageOffset, this.data.length - this.messageOffset, 1);
  }

  @Override
  public void sign(final Collection<Signer> signers) {
    final Signer[] signerArray = signers.toArray(Signer[]::new);
    final int passedSigners = signerArray.length;
    if (passedSigners != this.numSigners) {
      throw new IllegalArgumentException(String.format(
          "Expected %d signers, only passed %d.", this.numSigners, passedSigners
      ));
    }
    final int[] signerIndexes = new int[passedSigners];
    final boolean[] seenSignerIndexes = new boolean[passedSigners];
    int i = 0;
    for (final var signer : signerArray) {
      final int signerIndex = signerIndex(signer);
      if (seenSignerIndexes[signerIndex]) {
        throw new IllegalArgumentException("Duplicate signer " + signer.publicKey());
      }
      seenSignerIndexes[signerIndex] = true;
      signerIndexes[i++] = signerIndex;
    }

    this.data[0] = (byte) passedSigners;
    i = 0;
    for (final var signer : signerArray) {
      sign(signerIndexes[i++], signer);
    }
  }

  /// The serialized payload, not the caller, decides where a transaction's message begins.
  ///
  /// Backs [Transaction]'s `static` signing helpers, where the signer count arrives as raw bytes and
  /// is therefore untrusted. Those helpers used to size the signature block from the caller's
  /// argument and overwrite the count byte to match, which silently relocates the message: signing a
  /// two-signer payload with one signer moved the message start back 64 bytes and wrote a signature
  /// over the header. The overwrite dated from a time when construction did not set that byte;
  /// every `createTx` path writes it at allocation now, so all it still did was let a mismatch pass.
  ///
  /// A payload states its signature count twice — the prefix that positions the message, and the
  /// header's own `num_required_signatures` — and only the prefix locates anything, so both are
  /// checked. Trusting the prefix alone would have moved the defect rather than closed it: a payload
  /// whose two copies disagree would still be signed, over a span its own header contradicts. The
  /// header sits at the prefix's implied message offset, after the version byte where there is one.
  /// [TransactionSkeleton] corroborates the same pair before it will sign.
  ///
  /// Nothing is written, and a caller assembling a buffer by hand declares its count exactly as
  /// `createTx` does. Like every signature-count site in [Transaction] this reads one byte, `sigLen`
  /// being `1 + (n << 6)` throughout, so counts above 127 narrow rather than growing a second
  /// compact-u16 byte.
  ///
  /// @throws IllegalArgumentException if `numSigners` disagrees with the payload, if the payload's
  ///                                  two copies of the count disagree, or if it is too short to
  ///                                  hold the signatures and header they imply
  static void requireSignerCount(final byte[] out, final int numSigners) {
    final int numRequiredSignatures = out[0] & 0xFF;
    if (numSigners != numRequiredSignatures) {
      throw new IllegalArgumentException(String.format(
          "Expected %d signers, only passed %d.", numRequiredSignatures, numSigners
      ));
    }
    final int msgOffset = 1 + (numRequiredSignatures << 6);
    if (msgOffset >= out.length) {
      throw new IllegalArgumentException(String.format(
          "A %d byte payload cannot hold %d signatures and a message.", out.length, numRequiredSignatures
      ));
    }
    final int versionByte = out[msgOffset] & 0xFF;
    final int headerOffset = signedByte(versionByte) ? msgOffset + 1 : msgOffset;
    if (headerOffset >= out.length) {
      throw new IllegalArgumentException(String.format(
          "A %d byte payload cannot hold %d signatures and a message header.", out.length, numRequiredSignatures
      ));
    }
    // One read serves both formats: a legacy header offset is the message offset, so this re-reads
    // the very byte the version test just looked at, which is that format's count.
    final int headerSignatures = out[headerOffset] & 0xFF;
    if (headerSignatures != numRequiredSignatures) {
      throw new IllegalArgumentException(String.format(
          "Serialized signature count %d does not match the message header's required signature count %d.",
          numRequiredSignatures, headerSignatures
      ));
    }
  }

  @Override
  public String getBase58Id() {
    return Transaction.getBase58Id(this.data);
  }

  @Override
  public byte[] getId() {
    return Transaction.getId(this.data);
  }

  @Override
  public int size() {
    return data.length;
  }

  private Transaction setBlockHash(final Transaction transaction) {
    if (transaction instanceof TransactionRecord transactionRecord) {
      System.arraycopy(
          this.data, this.recentBlockHashIndex,
          transactionRecord.data, transactionRecord.recentBlockHashIndex,
          Transaction.BLOCK_HASH_LENGTH
      );
    } else {
      transaction.setRecentBlockHash(recentBlockHash());
    }
    return transaction;
  }

  @Override
  public Transaction prependIx(final Instruction ix) {
    final var ixArray = new Instruction[1 + instructions.size()];
    ixArray[0] = ix;
    int i = 1;
    for (final var _ix : instructions) {
      ixArray[i++] = _ix;
    }
    return setBlockHash(Transaction.createTx(feePayer, Arrays.asList(ixArray), lookupTable, tableAccountMetas));
  }

  @Override
  public Transaction prependInstructions(final Instruction ix1, final Instruction ix2) {
    final var ixArray = new Instruction[2 + instructions.size()];
    ixArray[0] = ix1;
    ixArray[1] = ix2;
    int i = 2;
    for (final var _ix : instructions) {
      ixArray[i++] = _ix;
    }
    return setBlockHash(Transaction.createTx(feePayer, Arrays.asList(ixArray), lookupTable, tableAccountMetas));
  }

  @Override
  public Transaction prependInstructions(final SequencedCollection<Instruction> instructions) {
    final var ixArray = new Instruction[instructions.size() + this.instructions.size()];
    int i = 0;
    for (final var ix : instructions) {
      ixArray[i++] = ix;
    }
    for (final var ix : this.instructions) {
      ixArray[i++] = ix;
    }
    return setBlockHash(Transaction.createTx(feePayer, Arrays.asList(ixArray), lookupTable, tableAccountMetas));
  }

  @Override
  public Transaction appendIx(final Instruction ix) {
    final var ixArray = new Instruction[1 + instructions.size()];
    int i = 0;
    for (final var _ix : instructions) {
      ixArray[i++] = _ix;
    }
    ixArray[instructions.size()] = ix;
    return setBlockHash(Transaction.createTx(feePayer, Arrays.asList(ixArray), lookupTable, tableAccountMetas));
  }

  @Override
  public Transaction appendInstructions(final SequencedCollection<Instruction> instructions) {
    final var ixArray = new Instruction[instructions.size() + this.instructions.size()];
    int i = 0;
    for (final var ix : this.instructions) {
      ixArray[i++] = ix;
    }
    for (final var ix : instructions) {
      ixArray[i++] = ix;
    }
    return setBlockHash(Transaction.createTx(feePayer, Arrays.asList(ixArray), lookupTable, tableAccountMetas));
  }

  @Override
  public Transaction replaceInstruction(final int index, final Instruction instruction) {
    final var ixArray = instructions.toArray(Instruction[]::new);
    ixArray[index] = instruction;
    return setBlockHash(Transaction.createTx(feePayer, Arrays.asList(ixArray), lookupTable, tableAccountMetas));
  }
}
