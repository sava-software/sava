package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record ProcessedSlot(long slot, long parent, long root) {

  public static ProcessedSlot parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("slot", buf, offset, len)) {
      builder.slot = ji.readLong();
    } else if (fieldEquals("parent", buf, offset, len)) {
      builder.parent = ji.readLong();
    } else if (fieldEquals("root", buf, offset, len)) {
      builder.root = ji.readLong();
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private long slot;
    private long parent;
    private long root;

    private Builder() {
    }

    private ProcessedSlot create() {
      return new ProcessedSlot(slot, parent, root);
    }
  }
}
