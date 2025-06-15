package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record BlockTx(TxMeta meta, byte[] data) {

  public static BlockTx parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private TxMeta meta;
    private byte[] data;

    private Parser() {
    }

    private BlockTx create() {
      return new BlockTx(meta, data);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("meta", buf, offset, len)) {
        this.meta = TxMeta.parse(ji);
      } else if (fieldEquals("transaction", buf, offset, len)) {
        if (ji.whatIsNext() == ValueType.ARRAY) {
          ji.openArray();
          this.data = ji.decodeBase64String();
          ji.skipRestOfArray();
        } else {
          ji.skip();
        }
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
