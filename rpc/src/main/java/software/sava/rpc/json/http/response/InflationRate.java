package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record InflationRate(long epoch, double foundation, double total, double validator) {

  public static InflationRate parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("epoch", buf, offset, len)) {
      builder.epoch(ji.readLong());
    } else if (fieldEquals("foundation", buf, offset, len)) {
      builder.foundation(ji.readDouble());
    } else if (fieldEquals("total", buf, offset, len)) {
      builder.total(ji.readDouble());
    } else if (fieldEquals("validator", buf, offset, len)) {
      builder.validator(ji.readDouble());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private long epoch;
    private double foundation;
    private double total;
    private double validator;

    private Builder() {

    }

    private InflationRate create() {
      return new InflationRate(epoch, foundation, total, validator);
    }

    private void epoch(final long epoch) {
      this.epoch = epoch;
    }

    private void foundation(final double foundation) {
      this.foundation = foundation;
    }

    private void total(final double total) {
      this.total = total;
    }

    private void validator(final double validator) {
      this.validator = validator;
    }
  }
}
