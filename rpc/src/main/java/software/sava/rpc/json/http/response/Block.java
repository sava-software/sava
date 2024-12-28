package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record Block(long blockHeight,
                    long blockTime,
                    String blockHash,
                    String previousBlockHash,
                    long parentSlot,
                    List<TxReward> rewards,
                    List<String> signatures) {

  public static Block parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("blockHeight", buf, offset, len)) {
      builder.blockHeight = ji.readLong();
    } else if (fieldEquals("blockTime", buf, offset, len)) {
      builder.blockTime = ji.readLong();
    } else if (fieldEquals("blockhash", buf, offset, len)) {
      builder.blockHash = ji.readString();
    } else if (fieldEquals("previousBlockhash", buf, offset, len)) {
      builder.previousBlockHash = ji.readString();
    } else if (fieldEquals("parentSlot", buf, offset, len)) {
      builder.parentSlot = ji.readLong();
    } else if (fieldEquals("rewards", buf, offset, len)) {
      builder.rewards = TxReward.parseRewards(ji);
    } else if (fieldEquals("signatures", buf, offset, len)) {
      final var signatures = new ArrayList<String>(2_048);
      while (ji.readArray()) {
        signatures.add(ji.readString());
      }
      builder.signatures = signatures;
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private long blockHeight;
    private long blockTime;
    private String blockHash;
    private String previousBlockHash;
    private long parentSlot;
    private List<TxReward> rewards;
    private List<String> signatures;

    private Builder() {
    }

    private Block create() {
      return new Block(
          blockHeight,
          blockTime,
          blockHash,
          previousBlockHash,
          parentSlot,
          rewards,
          signatures
      );
    }
  }
}
