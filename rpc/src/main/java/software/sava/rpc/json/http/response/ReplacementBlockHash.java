package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record ReplacementBlockHash(String blockhash, long lastValidBlockHeight) {

  static ReplacementBlockHash parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private String blockhash;
    private long lastValidBlockHeight;


    private ReplacementBlockHash create() {
      return new ReplacementBlockHash(blockhash, lastValidBlockHeight);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("blockhash", buf, offset, len)) {
        blockhash = ji.readString();
      } else if (fieldEquals("lastValidBlockHeight", buf, offset, len)) {
        lastValidBlockHeight = ji.readLong();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
