package software.sava.rpc.json.http.response;

import software.sava.core.tx.TransactionSkeleton;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.OptionalInt;
import java.util.OptionalLong;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// @param transactionIndex Index of the transaction within its block, empty when the responding node does not
///                         serve this field.
public record Tx(long slot,
                 OptionalLong blockTime,
                 TxMeta meta,
                 byte[] data,
                 int version,
                 OptionalInt transactionIndex) {

  public boolean isLegacy() {
    return version < 0;
  }

  /// Deserializes the transaction data on each call, null if no data is present.
  public TransactionSkeleton skeleton() {
    return data == null || data.length == 0 ? null : TransactionSkeleton.deserializeSkeleton(data);
  }

  public static Tx parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private long slot;
    private long blockTime;
    private TxMeta meta;
    private byte[] data;
    private int version = Integer.MIN_VALUE;
    private int transactionIndex = -1;

    private Parser() {
    }

    private Tx create() {
      return new Tx(
          slot,
          blockTime <= 0 ? OptionalLong.empty() : OptionalLong.of(blockTime),
          meta,
          data,
          version,
          transactionIndex < 0 ? OptionalInt.empty() : OptionalInt.of(transactionIndex)
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("slot", buf, offset, len)) {
        this.slot = ji.readLong();
      } else if (fieldEquals("blockTime", buf, offset, len)) {
        if (ji.whatIsNext() == ValueType.NUMBER) {
          this.blockTime = ji.readLong();
        } else {
          ji.skip();
        }
      } else if (fieldEquals("meta", buf, offset, len)) {
        if (!ji.readNull()) {
          this.meta = TxMeta.parse(ji);
        }
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
      } else if (fieldEquals("transactionIndex", buf, offset, len)) {
        if (ji.whatIsNext() == ValueType.NUMBER) {
          this.transactionIndex = ji.readInt();
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
