package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record LatestBlockHash(Context context, String blockHash, long lastValidBlockHeight) {

  public static LatestBlockHash parse(final JsonIterator ji, final Context context) {
    final var parser = new Parser(context);
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser extends RootBuilder implements FieldBufferPredicate {

    private String blockHash;
    private long lastValidBlockHeight;

    private Parser(final Context context) {
      super(context);
    }

    private LatestBlockHash create() {
      return new LatestBlockHash(context, blockHash, lastValidBlockHeight);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("blockhash", buf, offset, len)) {
        blockHash = ji.readString();
      } else if (fieldEquals("lastValidBlockHeight", buf, offset, len)) {
        lastValidBlockHeight = ji.readLong();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
