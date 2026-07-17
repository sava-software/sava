package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.util.function.Supplier;

public record InflationGovernor(double foundation,
                                double foundationTerm,
                                double initial,
                                double taper,
                                double terminal) {

  public static InflationGovernor parse(final JsonIterator ji) {
    return ji.parseObject(Parser.FIELDS, new Parser());
  }

  private static final class Parser implements FieldIndexPredicate, Supplier<InflationGovernor> {

    private double foundation;
    private double foundationTerm;
    private double initial;
    private double taper;
    private double terminal;

    private Parser() {
    }

    @Override
    public InflationGovernor get() {
      return new InflationGovernor(foundation, foundationTerm, initial, taper, terminal);
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "foundation",
        "foundationTerm",
        "initial",
        "taper",
        "terminal"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> foundation = ji.readDouble();
        case 1 -> foundationTerm = ji.readDouble();
        case 2 -> initial = ji.readDouble();
        case 3 -> taper = ji.readDouble();
        case 4 -> terminal = ji.readDouble();
        default -> ji.skip();
      }
      return true;
    }
  }
}
