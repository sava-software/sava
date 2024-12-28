package software.sava.rpc.json.http.response;

import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxSig(long slot,
                    OptionalLong blockTime,
                    Commitment confirmationStatus,
                    String signature,
                    String memo,
                    TransactionError transactionError) {

  public static List<TxSig> parseSignatures(final JsonIterator ji) {
    final var signatures = new ArrayList<TxSig>(2_048);
    while (ji.readArray()) {
      final var parser = new Parser();
      ji.testObject(parser);
      final var signature = parser.create();
      signatures.add(signature);
    }
    return signatures;
  }

  private static final class Parser implements FieldBufferPredicate {

    private long slot;
    private long blockTime;
    private Commitment confirmationStatus;
    private String signature;
    private String memo;
    private TransactionError error;

    private Parser() {
    }

    private TxSig create() {
      return new TxSig(slot,
          blockTime <= 0 ? OptionalLong.empty() : OptionalLong.of(blockTime),
          confirmationStatus,
          signature,
          memo,
          error
      );
    }


    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("slot", buf, offset, len)) {
        this.slot = ji.readLong();
      } else if (fieldEquals("blockTime", buf, offset, len)) {
        this.blockTime = ji.readLong();
      } else if (fieldEquals("confirmationStatus", buf, offset, len)) {
        this.confirmationStatus = ji.applyChars(Commitment.PARSER);
      } else if (fieldEquals("memo", buf, offset, len)) {
        this.memo = ji.readString();
      } else if (fieldEquals("signature", buf, offset, len)) {
        this.signature = ji.readString();
      } else if (fieldEquals("err", buf, offset, len)) {
        this.error = TransactionError.parseError(ji);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}