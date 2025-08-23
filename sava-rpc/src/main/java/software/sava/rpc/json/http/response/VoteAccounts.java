package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record VoteAccounts(List<VoteAccount> current, List<VoteAccount> delinquent) {

  public static VoteAccounts parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private List<VoteAccount> current;
    private List<VoteAccount> delinquent;

    private Parser() {
    }

    private VoteAccounts create() {
      return new VoteAccounts(current, delinquent);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("current", buf, offset, len)) {
        current = VoteAccount.parse(ji);
      } else if (fieldEquals("delinquent", buf, offset, len)) {
        delinquent = VoteAccount.parse(ji);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
