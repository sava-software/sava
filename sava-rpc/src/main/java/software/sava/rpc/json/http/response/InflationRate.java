package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.util.function.Supplier;

public record InflationRate(long epoch, double foundation, double total, double validator) {

  public static InflationRate parse(final JsonIterator ji) {
    return ji.parseObject(Parser.FIELDS, new Parser());
  }

  private static final class Parser implements FieldIndexPredicate, Supplier<InflationRate> {

    private long epoch;
    private double foundation;
    private double total;
    private double validator;

    private Parser() {
    }

    @Override
    public InflationRate get() {
      return new InflationRate(epoch, foundation, total, validator);
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "epoch",
        "foundation",
        "total",
        "validator"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> epoch = ji.readLong();
        case 1 -> foundation = ji.readDouble();
        case 2 -> total = ji.readDouble();
        case 3 -> validator = ji.readDouble();
        default -> ji.skip();
      }
      return true;
    }
  }
}
