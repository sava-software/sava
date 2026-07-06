package software.sava.core.tx;

import software.sava.core.accounts.Signer;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.Base58;

import java.util.*;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;

abstract class BaseTransaction implements Transaction {

  static final byte VERSIONED_BIT_MASK = (byte) (1 << 7);

  protected final AccountMeta feePayer;
  protected final List<Instruction> instructions;
  protected final byte[] data;

  protected BaseTransaction(final AccountMeta feePayer, final List<Instruction> instructions, final byte[] data) {
    this.feePayer = feePayer;
    this.instructions = instructions;
    this.data = data;
  }

  static int signedIdOffset(final byte[] signedTransaction) {
    final int numSigners;
    final int signaturesOffset;
    if (V1Transaction.isV1(signedTransaction)) {
      numSigners = signedTransaction[1] & 0xFF;
      signaturesOffset = signedTransaction.length - (numSigners * SIGNATURE_LENGTH);
    } else {
      numSigners = signedTransaction[0];
      signaturesOffset = 1;
    }
    if (numSigners != 0) {
      for (int i = signaturesOffset, to = signaturesOffset + SIGNATURE_LENGTH; i < to; ++i) {
        if (signedTransaction[i] != 0) {
          return signaturesOffset;
        }
      }
    }
    throw new IllegalStateException("Transaction has not been signed by the fee payer yet.");
  }

  // Returns the byte offset of the recent block hash within the serialized data.
  protected abstract int recentBlockHashIndex();

  // Returns the byte offset of the first account (the fee payer) within the serialized data.
  protected abstract int accountsOffset();

  // Returns the byte offset of the serialized message within the data.
  protected abstract int messageOffset();

  // Returns the byte length of the serialized message.
  protected abstract int messageLength();

  // Returns the byte offset of the signature for the given signer index.
  protected abstract int signatureOffset(final int signerIndex);

  // Writes the signature count for formats which serialize it before the signatures (legacy/v0).
  protected abstract void recordNumSignatures(final int numSignatures);

  // Creates a new transaction of the same format with the given instructions.
  protected abstract Transaction createTransaction(final List<Instruction> instructions);

  @Override
  public final AccountMeta feePayer() {
    return feePayer;
  }

  @Override
  public final List<Instruction> instructions() {
    return instructions;
  }

  @Override
  public final byte[] serialized() {
    return data;
  }

  @Override
  public final int size() {
    return data.length;
  }

  @Override
  public final void setRecentBlockHash(final byte[] recentBlockHash) {
    if (recentBlockHash == null || recentBlockHash.length != Transaction.BLOCK_HASH_LENGTH) {
      throw new IllegalArgumentException("32 byte recent blockHash is required");
    }
    System.arraycopy(recentBlockHash, 0, this.data, recentBlockHashIndex(), Transaction.BLOCK_HASH_LENGTH);
  }

  @Override
  public final void setRecentBlockHash(final String recentBlockHash) {
    setRecentBlockHash(Base58.decode(recentBlockHash));
  }

  @Override
  public final byte[] recentBlockHash() {
    final int recentBlockHashIndex = recentBlockHashIndex();
    return Arrays.copyOfRange(data, recentBlockHashIndex, recentBlockHashIndex + Transaction.BLOCK_HASH_LENGTH);
  }

  @Override
  public final String getBase58Id() {
    return Transaction.getBase58Id(this.data);
  }

  @Override
  public final byte[] getId() {
    return Transaction.getId(this.data);
  }

  @Override
  public final void sign(final Signer signer) {
    final int numSigners = numSigners();
    if (numSigners > 1) {
      final byte[] pubKey = signer.publicKey().toByteArray();
      for (int from = accountsOffset(), i = 0; i < numSigners; ++i, from += PUBLIC_KEY_LENGTH) {
        if (Arrays.equals(pubKey, 0, PUBLIC_KEY_LENGTH, data, from, from + PUBLIC_KEY_LENGTH)) {
          Transaction.sign(signer, this.data, messageOffset(), messageLength(), signatureOffset(i));
          return;
        }
      }
      throw new IllegalArgumentException("Failed to find index for signer " + signer.publicKey());
    } else {
      recordNumSignatures(1);
      Transaction.sign(signer, this.data, messageOffset(), messageLength(), signatureOffset(0));
    }
  }

  @Override
  public final void sign(final int index, final Signer signer) {
    Transaction.sign(signer, this.data, messageOffset(), messageLength(), signatureOffset(index));
  }

  @Override
  public final void sign(final SequencedCollection<Signer> signers) {
    final int numSigners = signers.size();
    if (numSigners != this.numSigners()) {
      throw new IllegalArgumentException(String.format("Expected %d signers, only passed %d.", this.numSigners(), numSigners));
    }
    recordNumSignatures(numSigners);
    Transaction.sign(signers, this.data, messageOffset(), messageLength(), signatureOffset(0));
  }

  @Override
  public final void sign(final Collection<Signer> signers) {
    final int numSigners = signers.size();
    if (numSigners != this.numSigners()) {
      throw new IllegalArgumentException(String.format("Expected %d signers, only passed %d.", this.numSigners(), numSigners));
    }
    recordNumSignatures(numSigners);
    for (final var signer : signers) {
      sign(signer);
    }
  }

  protected final Transaction setBlockHash(final Transaction transaction) {
    if (transaction instanceof BaseTransaction baseTransaction) {
      System.arraycopy(
          this.data, this.recentBlockHashIndex(),
          baseTransaction.data, baseTransaction.recentBlockHashIndex(),
          Transaction.BLOCK_HASH_LENGTH
      );
    } else {
      transaction.setRecentBlockHash(recentBlockHash());
    }
    return transaction;
  }

  @Override
  public final Transaction prependIx(final Instruction ix) {
    final var ixArray = new Instruction[1 + instructions.size()];
    ixArray[0] = ix;
    int i = 1;
    for (final var _ix : instructions) {
      ixArray[i++] = _ix;
    }
    return setBlockHash(createTransaction(Arrays.asList(ixArray)));
  }

  @Override
  public final Transaction prependInstructions(final Instruction ix1, final Instruction ix2) {
    final var ixArray = new Instruction[2 + instructions.size()];
    ixArray[0] = ix1;
    ixArray[1] = ix2;
    int i = 2;
    for (final var _ix : instructions) {
      ixArray[i++] = _ix;
    }
    return setBlockHash(createTransaction(Arrays.asList(ixArray)));
  }

  @Override
  public final Transaction prependInstructions(final SequencedCollection<Instruction> instructions) {
    final var ixArray = new Instruction[instructions.size() + this.instructions.size()];
    int i = 0;
    for (final var ix : instructions) {
      ixArray[i++] = ix;
    }
    for (final var ix : this.instructions) {
      ixArray[i++] = ix;
    }
    return setBlockHash(createTransaction(Arrays.asList(ixArray)));
  }

  @Override
  public final Transaction appendIx(final Instruction ix) {
    final var ixArray = new Instruction[1 + instructions.size()];
    int i = 0;
    for (final var _ix : instructions) {
      ixArray[i++] = _ix;
    }
    ixArray[instructions.size()] = ix;
    return setBlockHash(createTransaction(Arrays.asList(ixArray)));
  }

  @Override
  public final Transaction appendInstructions(final SequencedCollection<Instruction> instructions) {
    final var ixArray = new Instruction[instructions.size() + this.instructions.size()];
    int i = 0;
    for (final var ix : this.instructions) {
      ixArray[i++] = ix;
    }
    for (final var ix : instructions) {
      ixArray[i++] = ix;
    }
    return setBlockHash(createTransaction(Arrays.asList(ixArray)));
  }

  @Override
  public final Transaction replaceInstruction(final int index, final Instruction instruction) {
    final var ixArray = instructions.toArray(Instruction[]::new);
    ixArray[index] = instruction;
    return setBlockHash(createTransaction(Arrays.asList(ixArray)));
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(data);
  }

  @Override
  public String toString() {
    return "Transaction{" +
        " version=" + version() +
        ", id=" + getBase58Id() +
        ", feePayer=" + feePayer.publicKey().toBase58() +
        ", data=" + Base64.getEncoder().encodeToString(data) +
        '}';
  }
}
