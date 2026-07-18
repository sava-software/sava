package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record BlockCommitment(long[] commitment, long totalStake) {

  public static BlockCommitment parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private long[] commitment;
    private long totalStake;

    private Parser() {
    }

    private BlockCommitment create() {
      return new BlockCommitment(commitment, totalStake);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("commitment", buf, offset, len)) {
        commitment = ji.readLongArray(31);
      } else if (fieldEquals("totalStake", buf, offset, len)) {
        totalStake = ji.readLong();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
