package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record EpochSchedule(long firstNormalEpoch,
                            long firstNormalSlot,
                            long leaderScheduleSlotOffset,
                            int slotsPerEpoch,
                            boolean warmup) {

  public static EpochSchedule parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("firstNormalEpoch", buf, offset, len)) {
      builder.firstNormalEpoch(ji.readLong());
    } else if (fieldEquals("firstNormalSlot", buf, offset, len)) {
      builder.firstNormalSlot(ji.readLong());
    } else if (fieldEquals("leaderScheduleSlotOffset", buf, offset, len)) {
      builder.leaderScheduleSlotOffset(ji.readLong());
    } else if (fieldEquals("slotsPerEpoch", buf, offset, len)) {
      builder.slotsPerEpoch(ji.readInt());
    } else if (fieldEquals("warmup", buf, offset, len)) {
      builder.warmup(ji.readBoolean());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private long firstNormalEpoch;
    private long firstNormalSlot;
    private long leaderScheduleSlotOffset;
    private int slotsPerEpoch;
    private boolean warmup;

    private Builder() {
    }

    private EpochSchedule create() {
      return new EpochSchedule(firstNormalEpoch, firstNormalSlot, leaderScheduleSlotOffset, slotsPerEpoch, warmup);
    }

    private void firstNormalEpoch(final long firstNormalEpoch) {
      this.firstNormalEpoch = firstNormalEpoch;
    }

    private void firstNormalSlot(final long firstNormalSlot) {
      this.firstNormalSlot = firstNormalSlot;
    }

    private void leaderScheduleSlotOffset(final long leaderScheduleSlotOffset) {
      this.leaderScheduleSlotOffset = leaderScheduleSlotOffset;
    }

    private void slotsPerEpoch(final int slotsPerEpoch) {
      this.slotsPerEpoch = slotsPerEpoch;
    }

    private void warmup(final boolean warmup) {
      this.warmup = warmup;
    }
  }
}
