package software.sava.helius.rpc.json.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record PagedResponse<T>(T data,
                               String paginationToken,
                               long slot,
                               int index) {


  static abstract class PageParser implements FieldBufferPredicate {

    protected String paginationToken;
    protected long slot;
    protected int index;

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("paginationToken", buf, offset, len)) {
        this.paginationToken = ji.readString();
        final int delimiter = paginationToken.lastIndexOf(':');
        if (delimiter > 0) {
          this.slot = Long.parseLong(paginationToken.substring(0, delimiter));
          this.index = Integer.parseInt(paginationToken.substring(delimiter + 1));
        }
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
