package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.function.Supplier;

public record EpochInfo(long absoluteSlot,
                        long blockHeight,
                        long epoch,
                        int slotIndex,
                        int slotsInEpoch,
                        long transactionCount) {

  public static EpochInfo parse(final JsonIterator ji) {
    return ji.parseObject(Parser.FIELDS, new Parser());
  }

  private static final class Parser implements FieldIndexPredicate, Supplier<EpochInfo> {

    private long absoluteSlot;
    private long blockHeight;
    private long epoch;
    private int slotIndex;
    private int slotsInEpoch;
    private long transactionCount;

    private Parser() {
    }

    @Override
    public EpochInfo get() {
      return new EpochInfo(absoluteSlot, blockHeight, epoch, slotIndex, slotsInEpoch, transactionCount);
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "absoluteSlot",
        "blockHeight",
        "epoch",
        "slotIndex",
        "slotsInEpoch",
        "transactionCount"
    );

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
        if (ji.whatIsNext() == ValueType.NUMBER) {
          transactionCount = ji.readLong();
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
