package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

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
        rewards.add(ji.parseObject(Parser.FIELDS, new Parser()));
      }
    }
    return rewards;
  }

  private static final class Parser implements FieldIndexPredicate, Supplier<InflationReward> {

    private long amount;
    private long effectiveSlot;
    private long epoch;
    private long postBalance;
    private int commission;
    private boolean commissionBps;

    private Parser() {
    }

    @Override
    public InflationReward get() {
      return new InflationReward(
          amount,
          effectiveSlot,
          epoch,
          postBalance,
          commission,
          commissionBps
      );
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "amount",
        "effectiveSlot",
        "epoch",
        "postBalance",
        "commission",
        "commissionBps"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> amount = ji.readLong();
        case 1 -> effectiveSlot = ji.readLong();
        case 2 -> epoch = ji.readLong();
        case 3 -> postBalance = ji.readLong();
        case 4 -> {
          // Nodes serve either the percentage or the basis points, which take precedence regardless
          // of the order in which they are served.
          if (commissionBps || ji.whatIsNext() != ValueType.NUMBER) {
            ji.skip();
          } else {
            commission = ji.readInt();
          }
        }
        case 5 -> {
          if (ji.whatIsNext() == ValueType.NUMBER) {
            commission = ji.readInt();
            commissionBps = true;
          } else {
            ji.skip();
          }
        }
        default -> ji.skip();
      }
      return true;
    }
  }
}
