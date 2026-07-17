package software.sava.rpc.json.http.response;

import software.sava.core.tx.TransactionSkeleton;
import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Supplier;

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
    return ji.parseObject(Parser.FIELDS, new Parser());
  }

  private static final class Parser implements FieldIndexPredicate, Supplier<Tx> {

    private long slot;
    private long blockTime;
    private TxMeta meta;
    private byte[] data;
    private int version = Integer.MIN_VALUE;
    private int transactionIndex = -1;

    private Parser() {
    }

    @Override
    public Tx get() {
      return new Tx(
          slot,
          blockTime <= 0 ? OptionalLong.empty() : OptionalLong.of(blockTime),
          meta,
          data,
          version,
          transactionIndex < 0 ? OptionalInt.empty() : OptionalInt.of(transactionIndex)
      );
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "slot",
        "blockTime",
        "meta",
        "transaction",
        "version",
        "transactionIndex"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> this.slot = ji.readLong();
        case 1 -> {
          if (ji.whatIsNext() == ValueType.NUMBER) {
            this.blockTime = ji.readLong();
          } else {
            ji.skip();
          }
        }
        case 2 -> {
          if (!ji.readNull()) {
            this.meta = TxMeta.parse(ji);
          }
        }
        case 3 -> {
          if (ji.whatIsNext() == ValueType.ARRAY) {
            ji.openArray();
            this.data = ji.decodeBase64String();
            ji.skipRestOfArray();
          } else {
            ji.skip();
          }
        }
        case 4 -> {
          if (ji.whatIsNext() == ValueType.NUMBER) {
            this.version = ji.readInt();
          } else {
            ji.skip();
          }
        }
        case 5 -> {
          if (ji.whatIsNext() == ValueType.NUMBER) {
            this.transactionIndex = ji.readInt();
          } else {
            ji.skip();
          }
        }
        default -> ji.skip();
      }
      return true;
    }
  }
}
