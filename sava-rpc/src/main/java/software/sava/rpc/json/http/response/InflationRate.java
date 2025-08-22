package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record InflationRate(long epoch, double foundation, double total, double validator) {

  public static InflationRate parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private long epoch;
    private double foundation;
    private double total;
    private double validator;

    private Parser() {
    }

    private InflationRate create() {
      return new InflationRate(epoch, foundation, total, validator);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("epoch", buf, offset, len)) {
        epoch = ji.readLong();
      } else if (fieldEquals("foundation", buf, offset, len)) {
        foundation = ji.readDouble();
      } else if (fieldEquals("total", buf, offset, len)) {
        total = ji.readDouble();
      } else if (fieldEquals("validator", buf, offset, len)) {
        validator = ji.readDouble();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
