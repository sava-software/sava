package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

@Deprecated(forRemoval = true)
public record FeeCalculator(int lamportsPerSignature) {

  public static FeeCalculator parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private int lamportsPerSignature;

    private Parser() {
    }

    private FeeCalculator create() {
      return new FeeCalculator(lamportsPerSignature);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("lamportsPerSignature", buf, offset, len)) {
        lamportsPerSignature = ji.readInt();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
