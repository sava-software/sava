package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record InflationReward(long amount,
                              long effectiveSlot,
                              long epoch,
                              long postBalance,
                              int commission) {

  public static List<InflationReward> parse(final JsonIterator ji) {
    final var rewards = new ArrayList<InflationReward>();
    while (ji.readArray()) {
      if (ji.whatIsNext() == ValueType.OBJECT) {
        final var voteAccount = ji.testObject(new Builder(), PARSER).create();
        rewards.add(voteAccount);
      } else {
        ji.skip();
      }
    }
    return rewards;
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("amount", buf, offset, len)) {
      builder.amount = ji.readLong();
    } else if (fieldEquals("effectiveSlot", buf, offset, len)) {
      builder.effectiveSlot = ji.readLong();
    } else if (fieldEquals("epoch", buf, offset, len)) {
      builder.epoch = ji.readLong();
    } else if (fieldEquals("postBalance", buf, offset, len)) {
      builder.postBalance = ji.readLong();
    } else if (fieldEquals("commission", buf, offset, len)) {
      builder.commission = ji.readInt();
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private long amount;
    private long effectiveSlot;
    private long epoch;
    private long postBalance;
    private int commission;

    private Builder() {

    }

    private InflationReward create() {
      return new InflationReward(amount, effectiveSlot, epoch, postBalance, commission);
    }
  }
}
