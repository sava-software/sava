package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record EpochSchedule(long firstNormalEpoch,
                            long firstNormalSlot,
                            long leaderScheduleSlotOffset,
                            int slotsPerEpoch,
                            boolean warmup) {

  public static EpochSchedule parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private long firstNormalEpoch;
    private long firstNormalSlot;
    private long leaderScheduleSlotOffset;
    private int slotsPerEpoch;
    private boolean warmup;

    private Parser() {
    }

    private EpochSchedule create() {
      return new EpochSchedule(firstNormalEpoch, firstNormalSlot, leaderScheduleSlotOffset, slotsPerEpoch, warmup);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("firstNormalEpoch", buf, offset, len)) {
        firstNormalEpoch = ji.readLong();
      } else if (fieldEquals("firstNormalSlot", buf, offset, len)) {
        firstNormalSlot = ji.readLong();
      } else if (fieldEquals("leaderScheduleSlotOffset", buf, offset, len)) {
        leaderScheduleSlotOffset = ji.readLong();
      } else if (fieldEquals("slotsPerEpoch", buf, offset, len)) {
        slotsPerEpoch = ji.readInt();
      } else if (fieldEquals("warmup", buf, offset, len)) {
        warmup = ji.readBoolean();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
