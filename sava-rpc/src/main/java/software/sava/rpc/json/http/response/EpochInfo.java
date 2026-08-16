package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

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
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> absoluteSlot = ji.readLong();
        case 1 -> blockHeight = ji.readLong();
        case 2 -> epoch = ji.readLong();
        case 3 -> slotIndex = ji.readInt();
        case 4 -> slotsInEpoch = ji.readInt();
        case 5 -> transactionCount = ji.readLongOr(transactionCount);
        default -> ji.skip();
      }
      return true;
    }
  }
}
