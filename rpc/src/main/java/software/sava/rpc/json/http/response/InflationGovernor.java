package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record InflationGovernor(double foundation,
                                double foundationTerm,
                                double initial,
                                double taper,
                                double terminal) {

  public static InflationGovernor parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("foundation", buf, offset, len)) {
      builder.foundation(ji.readDouble());
    } else if (fieldEquals("foundationTerm", buf, offset, len)) {
      builder.foundationTerm(ji.readDouble());
    } else if (fieldEquals("initial", buf, offset, len)) {
      builder.initial(ji.readDouble());
    } else if (fieldEquals("taper", buf, offset, len)) {
      builder.taper(ji.readDouble());
    } else if (fieldEquals("terminal", buf, offset, len)) {
      builder.terminal(ji.readDouble());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private double foundation;
    private double foundationTerm;
    private double initial;
    private double taper;
    private double terminal;

    private Builder() {

    }

    private InflationGovernor create() {
      return new InflationGovernor(foundation, foundationTerm, initial, taper, terminal);
    }

    private void foundation(final double foundation) {
      this.foundation = foundation;
    }

    private void foundationTerm(final double foundationTerm) {
      this.foundationTerm = foundationTerm;
    }

    private void initial(final double initial) {
      this.initial = initial;
    }

    private void taper(final double taper) {
      this.taper = taper;
    }

    private void terminal(final double terminal) {
      this.terminal = terminal;
    }
  }
}
