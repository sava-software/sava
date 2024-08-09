package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record VoteAccounts(List<VoteAccount> current, List<VoteAccount> delinquent) {

  public static VoteAccounts parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("current", buf, offset, len)) {
      builder.current = VoteAccount.parse(ji);
    } else if (fieldEquals("delinquent", buf, offset, len)) {
      builder.delinquent = VoteAccount.parse(ji);
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private List<VoteAccount> current;
    private List<VoteAccount> delinquent;

    private Builder() {

    }

    private VoteAccounts create() {
      return new VoteAccounts(current, delinquent);
    }
  }
}
