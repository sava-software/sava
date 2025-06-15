package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.ArrayList;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record BlockCommitment(long[] commitment, long totalStake) {

  public static BlockCommitment parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("commitment", buf, offset, len)) {
      if (ji.whatIsNext() == ValueType.ARRAY) {
        final var lamports = new ArrayList<Long>();
        while (ji.readArray()) {
          lamports.add(ji.readLong());
        }
        builder.commitment = lamports.stream().mapToLong(Long::longValue).toArray();
      } else {
        ji.skip();
        builder.commitment = new long[0];
      }
    } else if (fieldEquals("totalStake", buf, offset, len)) {
      builder.totalStake = ji.readLong();
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private long[] commitment;
    private long totalStake;

    private Builder() {
    }

    private BlockCommitment create() {
      return new BlockCommitment(commitment, totalStake);
    }
  }
}
