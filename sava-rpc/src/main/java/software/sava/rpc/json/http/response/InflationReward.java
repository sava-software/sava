package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

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
    final var parser = new Parser();
    while (ji.readArray()) {
      ji.testObject(parser);
      rewards.add(parser.create());
      parser.reset();
    }
    return rewards;
  }

  private static final class Parser implements FieldBufferPredicate {

    private long amount;
    private long effectiveSlot;
    private long epoch;
    private long postBalance;
    private int commission;

    private Parser() {
    }

    private InflationReward create() {
      return new InflationReward(amount, effectiveSlot, epoch, postBalance, commission);
    }

    private void reset() {
      amount = 0L;
      effectiveSlot = 0L;
      epoch = 0L;
      postBalance = 0L;
      commission = 0;
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("amount", buf, offset, len)) {
        amount = ji.readLong();
      } else if (fieldEquals("effectiveSlot", buf, offset, len)) {
        effectiveSlot = ji.readLong();
      } else if (fieldEquals("epoch", buf, offset, len)) {
        epoch = ji.readLong();
      } else if (fieldEquals("postBalance", buf, offset, len)) {
        postBalance = ji.readLong();
      } else if (fieldEquals("commission", buf, offset, len)) {
        commission = ji.readInt();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
