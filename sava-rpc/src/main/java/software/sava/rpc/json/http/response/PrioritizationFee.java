package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record PrioritizationFee(long slot, long prioritizationFee) {

  public static List<PrioritizationFee> parse(final JsonIterator ji) {
    final var samples = new ArrayList<PrioritizationFee>(150);
    while (ji.readArray()) {
      final var sample = ji.testObject(new Builder(), PARSER).create();
      samples.add(sample);
    }
    return samples;
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("slot", buf, offset, len)) {
      builder.slot(ji.readLong());
    } else if (fieldEquals("prioritizationFee", buf, offset, len)) {
      builder.prioritizationFee(ji.readLong());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private long slot;
    private long prioritizationFee;

    private Builder() {
    }

    private PrioritizationFee create() {
      return new PrioritizationFee(slot, prioritizationFee);
    }

    private void slot(final long slot) {
      this.slot = slot;
    }

    private void prioritizationFee(final long prioritizationFee) {
      this.prioritizationFee = prioritizationFee;
    }
  }
}
