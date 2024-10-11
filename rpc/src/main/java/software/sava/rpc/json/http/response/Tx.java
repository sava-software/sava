package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.List;
import java.util.OptionalLong;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record Tx(int slot,
                 OptionalLong blockTime,
                 TxMeta meta,
                 List<String> signatures,
                 List<AccountKey> accountKeys,
                 List<TxParsedInstruction> instructions,
                 String recentBlockHash,
                 byte[] data) {

  public static Tx parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("slot", buf, offset, len)) {
      builder.slot(ji.readInt());
    } else if (fieldEquals("blockTime", buf, offset, len)) {
      builder.blockTime(ji.readLong());
    } else if (fieldEquals("meta", buf, offset, len)) {
      builder.meta(TxMeta.parse(ji));
    } else if (fieldEquals("transaction", buf, offset, len)) {
      if (ji.whatIsNext() == ValueType.ARRAY) {
        ji.openArray();
        builder.data = ji.decodeBase64String();
        ji.skipRestOfArray();
      } else {
        ji.skip();
      }
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private int slot;
    private long blockTime;
    private TxMeta meta;
    private List<String> signatures;
    private List<AccountKey> accountKeys;
    private List<TxParsedInstruction> instructions;
    private String recentBlockHash;
    private byte[] data;

    private Builder() {
      super();
    }

    private Tx create() {
      return new Tx(slot, blockTime <= 0 ? OptionalLong.empty() : OptionalLong.of(blockTime), meta, signatures, accountKeys, instructions, recentBlockHash, data);
    }

    private void slot(final int slot) {
      this.slot = slot;
    }

    private void blockTime(final long blockTime) {
      this.blockTime = blockTime;
    }

    private void meta(final TxMeta meta) {
      this.meta = meta;
    }

    private void signatures(final List<String> signatures) {
      this.signatures = signatures;
    }

    private void accountKeys(final List<AccountKey> accountKeys) {
      this.accountKeys = accountKeys;
    }

    private void instructions(final List<TxParsedInstruction> instructions) {
      this.instructions = instructions;
    }

    private void recentBlockHash(final String recentBlockHash) {
      this.recentBlockHash = recentBlockHash;
    }
  }
}
