package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record ProcessedSlot(long slot, long parent, long root) {

  public static ProcessedSlot parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private long slot;
    private long parent;
    private long root;

    private Parser() {
    }

    private ProcessedSlot create() {
      return new ProcessedSlot(slot, parent, root);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("slot", buf, offset, len)) {
        slot = ji.readLong();
      } else if (fieldEquals("parent", buf, offset, len)) {
        parent = ji.readLong();
      } else if (fieldEquals("root", buf, offset, len)) {
        root = ji.readLong();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
