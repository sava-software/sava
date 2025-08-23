package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record InflationGovernor(double foundation,
                                double foundationTerm,
                                double initial,
                                double taper,
                                double terminal) {

  public static InflationGovernor parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private double foundation;
    private double foundationTerm;
    private double initial;
    private double taper;
    private double terminal;

    private Parser() {
    }

    private InflationGovernor create() {
      return new InflationGovernor(foundation, foundationTerm, initial, taper, terminal);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("foundation", buf, offset, len)) {
        foundation = ji.readDouble();
      } else if (fieldEquals("foundationTerm", buf, offset, len)) {
        foundationTerm = ji.readDouble();
      } else if (fieldEquals("initial", buf, offset, len)) {
        initial = ji.readDouble();
      } else if (fieldEquals("taper", buf, offset, len)) {
        taper = ji.readDouble();
      } else if (fieldEquals("terminal", buf, offset, len)) {
        terminal = ji.readDouble();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
