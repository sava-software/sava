package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// @param commission    Vote account commission when the reward was credited, in basis points if
///                      [#commissionBps()], otherwise a percentage.
/// @param commissionBps True if the commission is in basis points (SIMD-0291). Nodes which serve it only
///                      serve the percentage as null.
public record InflationReward(long amount,
                              long effectiveSlot,
                              long epoch,
                              long postBalance,
                              int commission,
                              boolean commissionBps) {

  private static final InflationReward ZERO = new InflationReward(0, 0, 0, 0, 0, false);

  public static List<InflationReward> parse(final JsonIterator ji) {
    final var rewards = new ArrayList<InflationReward>();
    while (ji.readArray()) {
      if (ji.readNull()) {
        rewards.add(ZERO);
      } else {
        final var parser = new Parser();
        ji.testObject(parser);
        rewards.add(parser.create());
      }
    }
    return rewards;
  }

  private static final class Parser implements FieldBufferPredicate {

    private long amount;
    private long effectiveSlot;
    private long epoch;
    private long postBalance;
    private int commission;
    private boolean commissionBps;

    private Parser() {
    }

    private InflationReward create() {
      return new InflationReward(
          amount,
          effectiveSlot,
          epoch,
          postBalance,
          commission,
          commissionBps
      );
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
        if (ji.whatIsNext() == ValueType.NUMBER) {
          commission = ji.readInt();
        } else {
          ji.skip();
        }
      } else if (fieldEquals("commissionBps", buf, offset, len)) {
        if (ji.whatIsNext() == ValueType.NUMBER) {
          commission = ji.readInt();
          commissionBps = true;
        } else {
          ji.skip();
        }
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
