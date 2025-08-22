package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record EpochInfo(long absoluteSlot,
                        long blockHeight,
                        long epoch,
                        int slotIndex,
                        int slotsInEpoch,
                        long transactionCount) {

  public static EpochInfo parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private long absoluteSlot;
    private long blockHeight;
    private long epoch;
    private int slotIndex;
    private int slotsInEpoch;
    private long transactionCount;

    private Parser() {
    }

    private EpochInfo create() {
      return new EpochInfo(absoluteSlot, blockHeight, epoch, slotIndex, slotsInEpoch, transactionCount);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("absoluteSlot", buf, offset, len)) {
        absoluteSlot = ji.readLong();
      } else if (fieldEquals("blockHeight", buf, offset, len)) {
        blockHeight = ji.readLong();
      } else if (fieldEquals("epoch", buf, offset, len)) {
        epoch = ji.readLong();
      } else if (fieldEquals("slotIndex", buf, offset, len)) {
        slotIndex = ji.readInt();
      } else if (fieldEquals("slotsInEpoch", buf, offset, len)) {
        slotsInEpoch = ji.readInt();
      } else if (fieldEquals("transactionCount", buf, offset, len)) {
        transactionCount = ji.readLong();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
