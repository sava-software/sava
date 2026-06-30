package software.sava.core.tx;

import software.sava.core.accounts.Signer;
import software.sava.core.accounts.lookup.AddressLookupTable;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.accounts.meta.LookupTableAccountMeta;
import software.sava.core.encoding.Base58;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.SequencedCollection;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;
import static software.sava.core.tx.TransactionRecord.NO_TABLES;

// SIMD-0385 Transaction V1 format.
record V1Transaction(AccountMeta feePayer,
                     List<Instruction> instructions,
                     byte[] data,
                     int numSigners,
                     int signaturesOffset,
                     int accountsOffset,
                     int recentBlockHashIndex) implements Transaction {

  // Maximum serialized length of a v1 transaction as defined by SIMD-0385.
  public static final int MAX_SERIALIZED_LENGTH_V1 = 4096;

  private boolean notSigned() {
    for (int i = signaturesOffset, to = signaturesOffset + SIGNATURE_LENGTH; i < to; ++i) {
      if (data[i] != 0) {
        return false;
      }
    }
    return true;
  }

  @Override
  public List<Instruction> instructions() {
    return instructions;
  }

  @Override
  public AddressLookupTable lookupTable() {
    return null;
  }

  @Override
  public LookupTableAccountMeta[] tableAccountMetas() {
    return NO_TABLES;
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
    return data[0] & 0x7F;
  }

  @Override
  public byte[] serialized() {
    return this.data;
  }

  @Override
  public void sign(final Signer signer) {
    if (numSigners > 1) {
      final byte[] pubKey = signer.publicKey().toByteArray();
      for (int from = accountsOffset, i = 0; i < numSigners; ++i, from += PUBLIC_KEY_LENGTH) {
        if (Arrays.equals(pubKey, 0, PUBLIC_KEY_LENGTH, data, from, from + PUBLIC_KEY_LENGTH)) {
          Transaction.sign(
              signer,
              this.data,
              0,
              this.signaturesOffset,
              this.signaturesOffset + (i * SIGNATURE_LENGTH)
          );
          return;
        }
      }
      throw new IllegalArgumentException("Failed to find index for signer " + signer.publicKey());
    } else {
      Transaction.sign(signer, this.data, 0, this.signaturesOffset, this.signaturesOffset);
    }
  }

  @Override
  public void sign(final int index, final Signer signer) {
    Transaction.sign(
        signer,
        this.data,
        0,
        this.signaturesOffset,
        this.signaturesOffset + (index * SIGNATURE_LENGTH)
    );
  }

  @Override
  public void sign(final SequencedCollection<Signer> signers) {
    final int numSigners = signers.size();
    if (numSigners != this.numSigners) {
      throw new IllegalArgumentException(String.format("Expected %d signers, only passed %d.", this.numSigners, numSigners));
    }
    Transaction.sign(signers, this.data, 0, this.signaturesOffset, this.signaturesOffset);
  }

  @Override
  public void sign(final Collection<Signer> signers) {
    final int numSigners = signers.size();
    if (numSigners != this.numSigners) {
      throw new IllegalArgumentException(String.format("Expected %d signers, only passed %d.", this.numSigners, numSigners));
    }
    for (final var signer : signers) {
      sign(signer);
    }
  }

  @Override
  public String getBase58Id() {
    if (notSigned()) {
      throw new IllegalStateException("Transaction has not been signed yet.");
    }
    return Base58.encode(data, signaturesOffset, signaturesOffset + SIGNATURE_LENGTH);
  }

  @Override
  public byte[] getId() {
    if (notSigned()) {
      throw new IllegalStateException("Transaction has not been signed yet.");
    }
    return Arrays.copyOfRange(data, signaturesOffset, signaturesOffset + SIGNATURE_LENGTH);
  }

  @Override
  public int size() {
    return data.length;
  }

  @Override
  public boolean exceedsSizeLimit() {
    return size() > MAX_SERIALIZED_LENGTH_V1;
  }

  private Transaction setBlockHash(final Transaction transaction) {
    if (transaction instanceof V1Transaction transactionRecord) {
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
    return setBlockHash(TxBuilder.create().feePayer(feePayer).instructions(Arrays.asList(ixArray)).buildV1());
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
    return setBlockHash(TxBuilder.create().feePayer(feePayer).instructions(Arrays.asList(ixArray)).buildV1());
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
    return setBlockHash(TxBuilder.create().feePayer(feePayer).instructions(Arrays.asList(ixArray)).buildV1());
  }

  @Override
  public Transaction appendIx(final Instruction ix) {
    final var ixArray = new Instruction[1 + instructions.size()];
    int i = 0;
    for (final var _ix : instructions) {
      ixArray[i++] = _ix;
    }
    ixArray[instructions.size()] = ix;
    return setBlockHash(TxBuilder.create().feePayer(feePayer).instructions(Arrays.asList(ixArray)).buildV1());
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
    return setBlockHash(TxBuilder.create().feePayer(feePayer).instructions(Arrays.asList(ixArray)).buildV1());
  }

  @Override
  public Transaction replaceInstruction(final int index, final Instruction instruction) {
    final var ixArray = instructions.toArray(Instruction[]::new);
    ixArray[index] = instruction;
    return setBlockHash(TxBuilder.create().feePayer(feePayer).instructions(Arrays.asList(ixArray)).buildV1());
  }
}
