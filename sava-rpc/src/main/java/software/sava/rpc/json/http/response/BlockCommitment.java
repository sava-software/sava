package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

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
        if (ji.whatIsNext() == ValueType.ARRAY) {
          commitment = new long[32];
          for (int i = 0; ji.readArray(); ++i) {
            if (i >= commitment.length) {
              final var commitment = new long[this.commitment.length << 1];
              System.arraycopy(this.commitment, 0, commitment, 0, this.commitment.length);
              this.commitment = commitment;
            }
            commitment[i] = ji.readLong();
          }
        } else {
          ji.skip();
          commitment = new long[0];
        }
      } else if (fieldEquals("totalStake", buf, offset, len)) {
        totalStake = ji.readLong();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
