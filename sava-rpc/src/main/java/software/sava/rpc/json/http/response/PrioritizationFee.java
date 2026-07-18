package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record PrioritizationFee(long slot, long prioritizationFee) {

  public static List<PrioritizationFee> parse(final JsonIterator ji) {
    return ji.readList(j -> j.parseObject(new Parser(), Parser::create));
  }

  private static final class Parser implements FieldBufferPredicate {

    private long slot;
    private long prioritizationFee;

    private Parser() {
    }

    private PrioritizationFee create() {
      return new PrioritizationFee(slot, prioritizationFee);
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
