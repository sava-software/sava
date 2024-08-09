package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
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
    while (ji.readArray()) {
      final var sample = ji.testObject(new Builder(), PARSER).create();
      samples.add(sample);
    }
    return samples;
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("slot", buf, offset, len)) {
      builder.slot(ji.readLong());
    } else if (fieldEquals("numSlots", buf, offset, len)) {
      builder.numSlots(ji.readLong());
    } else if (fieldEquals("numTransactions", buf, offset, len)) {
      builder.numTransactions(ji.readLong());
    } else if (fieldEquals("numNonVoteTransaction", buf, offset, len)) {
      builder.numNonVoteTransaction(ji.readLong());
    } else if (fieldEquals("samplePeriodSecs", buf, offset, len)) {
      builder.samplePeriodSecs(ji.readInt());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private long slot;
    private long numSlots;
    private long numTransactions;
    private long numNonVoteTransaction;
    private int samplePeriodSecs;

    private Builder() {
    }

    private PerfSample create() {
      return new PerfSample(slot, numSlots, numTransactions, numNonVoteTransaction, samplePeriodSecs);
    }

    private void slot(final long slot) {
      this.slot = slot;
    }

    private void numSlots(final long numSlots) {
      this.numSlots = numSlots;
    }

    private void numTransactions(final long numTransactions) {
      this.numTransactions = numTransactions;
    }

    private void numNonVoteTransaction(final long numNonVoteTransaction) {
      this.numNonVoteTransaction = numNonVoteTransaction;
    }

    private void samplePeriodSecs(final int samplePeriodSecs) {
      this.samplePeriodSecs = samplePeriodSecs;
    }
  }
}
