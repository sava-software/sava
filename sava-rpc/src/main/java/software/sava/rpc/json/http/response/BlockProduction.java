package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.HashMap;
import java.util.Map;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record BlockProduction(Context context,
                              Map<String, ValidatorLeaderInfo> leaderInfo,
                              long firstSlot,
                              long lastSlot) {

  public static BlockProduction parse(final JsonIterator ji, final Context context) {
    return ji.testObject(new Builder(context), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> RANGE_PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("firstSlot", buf, offset, len)) {
      builder.firstSlot(ji.readLong());
    } else if (fieldEquals("lastSlot", buf, offset, len)) {
      builder.lastSlot(ji.readLong());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("byIdentity", buf, offset, len)) {
      final var leaderInfo = new HashMap<String, ValidatorLeaderInfo>();
      for (String validator; (validator = ji.readObjField()) != null; ji.closeArray()) {
        final int numSlots = ji.openArray().readInt();
        final int blocksProduced = ji.continueArray().readInt();
        leaderInfo.put(validator, new ValidatorLeaderInfo(numSlots, blocksProduced));
      }
      builder.leaderInfo(leaderInfo);
    } else if (fieldEquals("range", buf, offset, len)) {
      ji.testObject(builder, RANGE_PARSER);
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder extends RootBuilder {

    private Map<String, ValidatorLeaderInfo> leaderInfo;
    private long firstSlot;
    private long lastSlot;

    private Builder(final Context context) {
      super(context);
    }

    private BlockProduction create() {
      return new BlockProduction(context, leaderInfo, firstSlot, lastSlot);
    }

    private void leaderInfo(final Map<String, ValidatorLeaderInfo> leaderInfo) {
      this.leaderInfo = leaderInfo;
    }

    private void firstSlot(final long firstSlot) {
      this.firstSlot = firstSlot;
    }

    private void lastSlot(final long lastSlot) {
      this.lastSlot = lastSlot;
    }
  }
}
