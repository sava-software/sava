package software.sava.rpc.json.http.response;

import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.FieldIndexPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Supplier;

/// @param transactionIndex Index of the transaction within its block, empty when the responding node pre-dates
///                         this field.
public record TxSig(long slot,
                    OptionalInt transactionIndex,
                    OptionalLong blockTime,
                    Commitment confirmationStatus,
                    String signature,
                    String memo,
                    TransactionError transactionError) {

  public static List<TxSig> parseSignatures(final JsonIterator ji) {
    final var signatures = new ArrayList<TxSig>(2_048);
    while (ji.readArray()) {
      signatures.add(ji.parseObject(Parser.FIELDS, new Parser()));
    }
    return signatures;
  }

  private static final class Parser implements FieldIndexPredicate, Supplier<TxSig> {

    private long slot;
    private int transactionIndex = -1;
    private long blockTime;
    private Commitment confirmationStatus;
    private String signature;
    private String memo;
    private TransactionError error;

    private Parser() {
    }

    @Override
    public TxSig get() {
      return new TxSig(slot,
          transactionIndex < 0 ? OptionalInt.empty() : OptionalInt.of(transactionIndex),
          blockTime <= 0 ? OptionalLong.empty() : OptionalLong.of(blockTime),
          confirmationStatus,
          signature,
          memo,
          error
      );
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "slot",
        "transactionIndex",
        "blockTime",
        "confirmationStatus",
        "memo",
        "signature",
        "err",
        "error"
    );

    @Override
    public boolean test(final int fieldIndex, final JsonIterator ji) {
      switch (fieldIndex) {
        case 0 -> this.slot = ji.readLong();
        case 1 -> {
          if (ji.whatIsNext() == ValueType.NUMBER) {
            this.transactionIndex = ji.readInt();
          } else {
            ji.skip();
          }
        }
        case 2 -> {
          if (ji.whatIsNext() == ValueType.NUMBER) {
            this.blockTime = ji.readLong();
          } else {
            ji.skip();
          }
        }
        case 3 -> this.confirmationStatus = ji.applyChars(Commitment.PARSER);
        case 4 -> this.memo = ji.readString();
        case 5 -> this.signature = ji.readString();
        case 6, 7 -> this.error = TransactionError.parseError(ji);
        default -> ji.skip();
      }
      return true;
    }
  }
}
