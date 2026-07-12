package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// @param commissionBps Vote account commission in basis points (SIMD-0291), empty when the responding node
///                      pre-dates this field.
public record InflationReward(long amount,
                              long effectiveSlot,
                              long epoch,
                              long postBalance,
                              int commission,
                              OptionalInt commissionBps) {

  private static final InflationReward ZERO = new InflationReward(0, 0, 0, 0, 0, OptionalInt.empty());

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
    private int commissionBps = -1;

    private Parser() {
    }

    private InflationReward create() {
      return new InflationReward(
          amount,
          effectiveSlot,
          epoch,
          postBalance,
          commission,
          commissionBps < 0 ? OptionalInt.empty() : OptionalInt.of(commissionBps)
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
          commissionBps = ji.readInt();
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
