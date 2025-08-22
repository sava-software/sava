package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record PerfSample(long slot,
                         long numSlots,
                         long numTransactions,
                         long numNonVoteTransaction,
                         int samplePeriodSecs) {

  public static List<PerfSample> parse(final JsonIterator ji) {
    final var samples = new ArrayList<PerfSample>(720);
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
    private long numSlots;
    private long numTransactions;
    private long numNonVoteTransaction;
    private int samplePeriodSecs;

    private Parser() {
    }

    private PerfSample create() {
      return new PerfSample(slot, numSlots, numTransactions, numNonVoteTransaction, samplePeriodSecs);
    }

    private void reset() {
      slot = 0L;
      numSlots = 0L;
      numTransactions = 0L;
      numNonVoteTransaction = 0L;
      samplePeriodSecs = 0;
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("slot", buf, offset, len)) {
        slot = ji.readLong();
      } else if (fieldEquals("numSlots", buf, offset, len)) {
        numSlots = ji.readLong();
      } else if (fieldEquals("numTransactions", buf, offset, len)) {
        numTransactions = ji.readLong();
      } else if (fieldEquals("numNonVoteTransactions", buf, offset, len) || fieldEquals("numNonVoteTransaction", buf, offset, len)) {
        numNonVoteTransaction = ji.readLong();
      } else if (fieldEquals("samplePeriodSecs", buf, offset, len)) {
        samplePeriodSecs = ji.readInt();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
