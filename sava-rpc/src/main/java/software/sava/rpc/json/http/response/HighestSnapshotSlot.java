package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record HighestSnapshotSlot(long full, long incremental) {

  public static HighestSnapshotSlot parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("full", buf, offset, len)) {
      builder.full = ji.readLong();
    } else if (fieldEquals("incremental", buf, offset, len)) {
      builder.incremental = ji.readLong();
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private long full;
    private long incremental;

    private Builder() {
    }

    private HighestSnapshotSlot create() {
      return new HighestSnapshotSlot(full, incremental);
    }
  }
}
