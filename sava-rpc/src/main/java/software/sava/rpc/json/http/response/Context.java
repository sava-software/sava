package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record Context(long slot, String apiVersion) {

  public static Context parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private long slot;
    private String apiVersion;

    private Parser() {
    }

    private Context create() {
      return new Context(slot, apiVersion);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("slot", buf, offset, len)) {
        slot = ji.readLong();
      } else if (fieldEquals("apiVersion", buf, offset, len)) {
        apiVersion = ji.readString();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
