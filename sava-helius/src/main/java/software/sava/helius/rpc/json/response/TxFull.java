package software.sava.helius.rpc.json.response;

import software.sava.rpc.json.http.response.JsonUtil;
import software.sava.rpc.json.http.response.TxMeta;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxFull(long slot,
                     int transactionIndex,
                     byte[] data,
                     TxMeta meta,
                     OptionalLong blockTime) {


  public static PagedResponse<List<TxFull>> parseFullDetails(final JsonIterator ji) {
    final var parser = new FullDetailsParser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class FullDetailsParser extends PagedResponse.PageParser {

    private List<TxFull> data;

    private PagedResponse<List<TxFull>> create() {
      return new PagedResponse<>(data, paginationToken, slot, index);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("data", buf, offset, len)) {
        final var list = new ArrayList<TxFull>();
        while (ji.readArray()) {
          final var itemParser = new TxFullDetailParser();
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

  private static final class TxFullDetailParser implements FieldBufferPredicate {

    private long slot;
    private int transactionIndex;
    private byte[] data;
    private TxMeta meta;
    private long blockTime;

    private TxFull create() {
      final var optBlockTime = blockTime <= 0 ? OptionalLong.empty() : OptionalLong.of(blockTime);
      return new TxFull(slot, transactionIndex, data, meta, optBlockTime);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("slot", buf, offset, len)) {
        this.slot = ji.readLong();
      } else if (fieldEquals("transactionIndex", buf, offset, len)) {
        this.transactionIndex = ji.readInt();
      } else if (fieldEquals("transaction", buf, offset, len)) {
        this.data = JsonUtil.parseEncodedData(ji);
      } else if (fieldEquals("meta", buf, offset, len)) {
        this.meta = TxMeta.parse(ji);
      } else if (fieldEquals("blockTime", buf, offset, len)) {
        if (ji.whatIsNext() == ValueType.NUMBER) {
          this.blockTime = ji.readLong();
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
