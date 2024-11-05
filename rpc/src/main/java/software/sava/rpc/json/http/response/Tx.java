package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.OptionalLong;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record Tx(int slot,
                 OptionalLong blockTime,
                 TxMeta meta,
                 byte[] data,
                 int version) {

  public boolean isLegacy() {
    return version < 0;
  }

  public static Tx parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private int slot;
    private long blockTime;
    private TxMeta meta;
    private byte[] data;
    private int version = Integer.MIN_VALUE;

    private Parser() {
    }

    private Tx create() {
      return new Tx(
          slot,
          blockTime <= 0 ? OptionalLong.empty() : OptionalLong.of(blockTime),
          meta,
          data,
          version
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("slot", buf, offset, len)) {
        this.slot = ji.readInt();
      } else if (fieldEquals("blockTime", buf, offset, len)) {
        this.blockTime = ji.readLong();
      } else if (fieldEquals("meta", buf, offset, len)) {
        this.meta = TxMeta.parse(ji);
      } else if (fieldEquals("transaction", buf, offset, len)) {
        if (ji.whatIsNext() == ValueType.ARRAY) {
          ji.openArray();
          this.data = ji.decodeBase64String();
          ji.skipRestOfArray();
        } else {
          ji.skip();
        }
      } else if (fieldEquals("version", buf, offset, len)) {
        if (ji.whatIsNext() == ValueType.NUMBER) {
          this.version = ji.readInt();
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
