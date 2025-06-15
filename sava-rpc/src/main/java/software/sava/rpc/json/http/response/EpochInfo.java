package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record EpochInfo(long absoluteSlot,
                        long blockHeight,
                        long epoch,
                        int slotIndex,
                        int slotsInEpoch,
                        long transactionCount) {

  public static EpochInfo parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("absoluteSlot", buf, offset, len)) {
      builder.absoluteSlot(ji.readLong());
    } else if (fieldEquals("blockHeight", buf, offset, len)) {
      builder.blockHeight(ji.readLong());
    } else if (fieldEquals("epoch", buf, offset, len)) {
      builder.epoch(ji.readLong());
    } else if (fieldEquals("slotIndex", buf, offset, len)) {
      builder.slotIndex(ji.readInt());
    } else if (fieldEquals("slotsInEpoch", buf, offset, len)) {
      builder.slotsInEpoch(ji.readInt());
    } else if (fieldEquals("transactionCount", buf, offset, len)) {
      builder.transactionCount(ji.readLong());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private long absoluteSlot;
    private long blockHeight;
    private long epoch;
    private int slotIndex;
    private int slotsInEpoch;
    private long transactionCount;

    private Builder() {

    }

    private EpochInfo create() {
      return new EpochInfo(absoluteSlot, blockHeight, epoch, slotIndex, slotsInEpoch, transactionCount);
    }

    private void absoluteSlot(final long absoluteSlot) {
      this.absoluteSlot = absoluteSlot;
    }

    private void blockHeight(final long blockHeight) {
      this.blockHeight = blockHeight;
    }

    private void epoch(final long epoch) {
      this.epoch = epoch;
    }

    private void slotIndex(final int slotIndex) {
      this.slotIndex = slotIndex;
    }

    private void slotsInEpoch(final int slotsInEpoch) {
      this.slotsInEpoch = slotsInEpoch;
    }

    private void transactionCount(final long transactionCount) {
      this.transactionCount = transactionCount;
    }
  }
}
