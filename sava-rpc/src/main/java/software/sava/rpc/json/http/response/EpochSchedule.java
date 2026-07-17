package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.util.function.Supplier;

public record EpochSchedule(long firstNormalEpoch,
                            long firstNormalSlot,
                            long leaderScheduleSlotOffset,
                            int slotsPerEpoch,
                            boolean warmup) {

  public static EpochSchedule parse(final JsonIterator ji) {
    return ji.parseObject(Parser.FIELDS, new Parser());
  }

  private static final class Parser implements FieldIndexPredicate, Supplier<EpochSchedule> {

    private long firstNormalEpoch;
    private long firstNormalSlot;
    private long leaderScheduleSlotOffset;
    private int slotsPerEpoch;
    private boolean warmup;

    private Parser() {
    }

    @Override
    public EpochSchedule get() {
      return new EpochSchedule(firstNormalEpoch, firstNormalSlot, leaderScheduleSlotOffset, slotsPerEpoch, warmup);
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "firstNormalEpoch",
        "firstNormalSlot",
        "leaderScheduleSlotOffset",
        "slotsPerEpoch",
        "warmup"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> firstNormalEpoch = ji.readLong();
        case 1 -> firstNormalSlot = ji.readLong();
        case 2 -> leaderScheduleSlotOffset = ji.readLong();
        case 3 -> slotsPerEpoch = ji.readInt();
        case 4 -> warmup = ji.readBoolean();
        default -> ji.skip();
      }
      return true;
    }
  }
}
