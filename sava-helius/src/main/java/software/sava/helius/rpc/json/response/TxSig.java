package software.sava.helius.rpc.json.response;

import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TransactionError;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxSig(String signature,
                    long slot,
                    int transactionIndex,
                    TransactionError err,
                    String memo,
                    OptionalLong blockTime,
                    Commitment confirmationStatus) {


  public static PagedResponse<List<TxSig>> parseSignatures(final JsonIterator ji) {
    final var parser = new SignaturesParser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class SignaturesParser extends PagedResponse.PageParser {

    private List<TxSig> data;

    private PagedResponse<List<TxSig>> create() {
      return new PagedResponse<>(data, paginationToken, slot, index);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("data", buf, offset, len)) {
        final var list = new ArrayList<TxSig>();
        while (ji.readArray()) {
          final var itemParser = new TxSigSummaryParser();
          ji.testObject(itemParser);
          list.add(itemParser.create());
        }
        this.data = list;
      } else {
        return super.test(buf, offset, len, ji);
      }
      return true;
    }
  }

  private static final class TxSigSummaryParser implements FieldBufferPredicate {

    private String signature;
    private long slot;
    private int transactionIndex;
    private TransactionError err;
    private String memo;
    private long blockTime;
    private Commitment confirmationStatus;

    private TxSig create() {
      final var optBlockTime = blockTime <= 0 ? OptionalLong.empty() : OptionalLong.of(blockTime);
      return new TxSig(signature, slot, transactionIndex, err, memo, optBlockTime, confirmationStatus);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("signature", buf, offset, len)) {
        this.signature = ji.readString();
      } else if (fieldEquals("slot", buf, offset, len)) {
        this.slot = ji.readLong();
      } else if (fieldEquals("transactionIndex", buf, offset, len)) {
        this.transactionIndex = ji.readInt();
      } else if (fieldEquals("err", buf, offset, len)) {
        this.err = TransactionError.parseError(ji);
      } else if (fieldEquals("memo", buf, offset, len)) {
        this.memo = ji.readString();
      } else if (fieldEquals("blockTime", buf, offset, len)) {
        if (ji.whatIsNext() == ValueType.NUMBER) {
          this.blockTime = ji.readLong();
        } else {
          ji.skip();
        }
      } else if (fieldEquals("confirmationStatus", buf, offset, len)) {
        this.confirmationStatus = ji.applyChars(Commitment.PARSER);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
