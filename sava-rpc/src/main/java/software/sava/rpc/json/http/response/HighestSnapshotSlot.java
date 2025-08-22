package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record HighestSnapshotSlot(long full, long incremental) {

  public static HighestSnapshotSlot parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private long full;
    private long incremental;

    private Parser() {
    }

    private HighestSnapshotSlot create() {
      return new HighestSnapshotSlot(full, incremental);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("full", buf, offset, len)) {
        full = ji.readLong();
      } else if (fieldEquals("incremental", buf, offset, len)) {
        incremental = ji.readLong();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
