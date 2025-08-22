package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record PrioritizationFee(long slot, long prioritizationFee) {

  public static List<PrioritizationFee> parse(final JsonIterator ji) {
    final var samples = new ArrayList<PrioritizationFee>(150);
    final var parser = new Parser();
    while (ji.readArray()) {
      ji.testObject(parser);
      samples.add(parser.create());
      parser.reset();
    }
    return samples;
  }

  private static final class Parser implements FieldBufferPredicate {

    private long slot;
    private long prioritizationFee;

    private Parser() {
    }

    private PrioritizationFee create() {
      return new PrioritizationFee(slot, prioritizationFee);
    }

    private void reset() {
      slot = 0L;
      prioritizationFee = 0L;
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("slot", buf, offset, len)) {
        slot = ji.readLong();
      } else if (fieldEquals("prioritizationFee", buf, offset, len)) {
        prioritizationFee = ji.readLong();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
