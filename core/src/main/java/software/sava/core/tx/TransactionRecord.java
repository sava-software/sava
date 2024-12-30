package software.sava.core.tx;

import software.sava.core.accounts.Signer;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.encoding.Base58;

import java.util.Arrays;
import java.util.List;

import static software.sava.core.encoding.CompactU16Encoding.signedByte;

record TransactionRecord(AccountMeta feePayer,
                         List<Instruction> instructions,
                         AddressLookupTable lookupTable,
                         LookupTableAccountMeta[] tableAccountMetas,
                         byte[] data,
                         int numSigners,
                         int messageOffset,
                         int recentBlockHashIndex) implements Transaction {

  static final LookupTableAccountMeta[] NO_TABLES = new LookupTableAccountMeta[0];

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
    return Arrays.copyOfRange(data, recentBlockHashIndex, Transaction.BLOCK_HASH_LENGTH);
  }

  @Override
  public int version() {
    int version = data[messageOffset] & 0xFF;
    return signedByte(version) ? version & 0x7F : VERSIONED_BIT_MASK;
  }

  @Override
  public byte[] serialized() {
    return this.data;
  }

  @Override
  public void sign(final Signer signer) {
    if (this.numSigners != 1) {
      throw new IllegalArgumentException(String.format("Expected %d signers.", this.numSigners));
    }
    this.data[0] = 1;
    Transaction.sign(signer, this.data, this.messageOffset, this.data.length - this.messageOffset, 1);
  }

  @Override
  public void sign(final int index, final Signer signer) {
    Transaction.sign(
        signer,
        this.data,
        this.messageOffset,
        this.data.length - this.messageOffset,
        1 + (index * SIGNATURE_LENGTH)
    );
  }

  @Override
  public void sign(final List<Signer> signers) {
    final int numSigners = signers.size();
    if (numSigners != this.numSigners) {
      throw new IllegalArgumentException(String.format("Expected %d signers, only passed %d.", this.numSigners, numSigners));
    }
    this.data[0] = (byte) numSigners;
    Transaction.sign(signers, this.data, this.messageOffset, this.data.length - this.messageOffset, 1);
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

  private void resetTableMetas() {
    for (final var tableMeta : tableAccountMetas) {
      tableMeta.reset();
    }
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
    resetTableMetas();
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
    resetTableMetas();
    return setBlockHash(Transaction.createTx(feePayer, Arrays.asList(ixArray), lookupTable, tableAccountMetas));
  }

  @Override
  public Transaction prependInstructions(final List<Instruction> instructions) {
    final var ixArray = new Instruction[instructions.size() + this.instructions.size()];
    int i = 0;
    for (final var ix : instructions) {
      ixArray[i++] = ix;
    }
    for (final var ix : this.instructions) {
      ixArray[i++] = ix;
    }
    resetTableMetas();
    return setBlockHash(Transaction.createTx(feePayer, Arrays.asList(ixArray), lookupTable, tableAccountMetas));
  }

  @Override
  public Transaction replaceInstruction(final int index, final Instruction instruction) {
    final var ixArray = instructions.toArray(Instruction[]::new);
    ixArray[index] = instruction;
    return setBlockHash(Transaction.createTx(feePayer, Arrays.asList(ixArray), lookupTable, tableAccountMetas));
  }
}
