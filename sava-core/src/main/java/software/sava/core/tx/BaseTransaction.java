package software.sava.core.tx;

import software.sava.core.accounts.Signer;
import software.sava.core.accounts.meta.AccountMeta;
import software.sava.core.encoding.Base58;

import java.util.*;

import static software.sava.core.accounts.PublicKey.PUBLIC_KEY_LENGTH;

abstract class BaseTransaction implements Transaction {

  static final byte VERSIONED_BIT_MASK = (byte) (1 << 7);

  // Version neutral, so it lives here rather than beside the v1-only limits on TxBuilderImpl:
  // SIMD-0385 imposes it on the v1 format directly, and legacy/v0 transactions are bound at
  // execution by the same 64-instruction trace limit. Package-private because no caller needs the
  // number — Transaction#exceedsInstructionLimit is the published way to ask.
  static final int MAX_INSTRUCTIONS = 64;

  protected final AccountMeta feePayer;
  protected final List<Instruction> instructions;
  protected final byte[] data;

  protected BaseTransaction(final AccountMeta feePayer, final List<Instruction> instructions, final byte[] data) {
    this.feePayer = feePayer;
    this.instructions = instructions;
    this.data = data;
  }

  /// Returns the byte offset of the fee payer signature, or -1 if it has not been written yet.
  ///
  /// A v1 transaction appends its signatures, so their offset is implied by the serialized length
  /// alone. These bytes are untrusted — this is reachable from the public static
  /// [Transaction#getBase58Id(byte[])] — so the implied boundary is corroborated against the
  /// message the buffer actually contains. Without that, padding silently slides the window and the
  /// caller is handed 64 bytes that are not the transaction's id.
  ///
  /// @throws IllegalArgumentException if the buffer cannot hold the signatures its header declares,
  ///                                  or if the message does not end where they would begin
  static int feePayerSignatureOffset(final byte[] signedTransaction) {
    final int numSigners;
    final int signaturesOffset;
    if (V1Transaction.isV1(signedTransaction)) {
      numSigners = signedTransaction[1] & 0xFF;
      signaturesOffset = V1TransactionSkeleton.requireSignatureBlockOffset(signedTransaction);
    } else {
      numSigners = signedTransaction[0];
      signaturesOffset = 1;
      if (numSigners != 0 && signedTransaction.length < 1 + SIGNATURE_LENGTH) {
        throw new IllegalArgumentException(String.format(
            "A transaction of %d bytes cannot hold a signature.", signedTransaction.length
        ));
      }
    }
    if (numSigners != 0) {
      for (int i = signaturesOffset, to = signaturesOffset + SIGNATURE_LENGTH; i < to; ++i) {
        if (signedTransaction[i] != 0) {
          return signaturesOffset;
        }
      }
    }
    return -1;
  }

  static int signedIdOffset(final byte[] signedTransaction) {
    final int offset = feePayerSignatureOffset(signedTransaction);
    if (offset < 0) {
      throw new IllegalStateException("Transaction has not been signed by the fee payer yet.");
    }
    return offset;
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
  public final int numInstructions() {
    return instructions.size();
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

  private int signerIndex(final Signer signer) {
    final int numSigners = numSigners();
    final byte[] pubKey = signer.publicKey().toByteArray();
    for (int from = accountsOffset(), i = 0; i < numSigners; ++i, from += PUBLIC_KEY_LENGTH) {
      if (Arrays.equals(pubKey, 0, PUBLIC_KEY_LENGTH, data, from, from + PUBLIC_KEY_LENGTH)) {
        return i;
      }
    }
    throw new IllegalArgumentException("Failed to find index for signer " + signer.publicKey());
  }

  @Override
  public final void sign(final Signer signer) {
    final int signerIndex = signerIndex(signer);
    recordNumSignatures(numSigners());
    sign(signerIndex, signer);
  }

  @Override
  public final void sign(final int index, final Signer signer) {
    final int numSigners = numSigners();
    if (index < 0 || index >= numSigners) {
      throw new IllegalArgumentException(String.format(
          "Invalid signer index %d for transaction with %d required signers.", index, numSigners
      ));
    }
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
    final Signer[] signerArray = signers.toArray(Signer[]::new);
    final int passedSigners = signerArray.length;
    final int numSigners = numSigners();
    if (passedSigners != numSigners) {
      throw new IllegalArgumentException(String.format(
          "Expected %d signers, only passed %d.", numSigners, passedSigners
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

    recordNumSignatures(passedSigners);
    i = 0;
    for (final var signer : signerArray) {
      sign(signerIndexes[i++], signer);
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
        ", id=" + (feePayerSignatureOffset(data) < 0 ? "<unsigned>" : getBase58Id()) +
        ", feePayer=" + feePayer.publicKey().toBase58() +
        ", data=" + Base64.getEncoder().encodeToString(data) +
        '}';
  }
}
