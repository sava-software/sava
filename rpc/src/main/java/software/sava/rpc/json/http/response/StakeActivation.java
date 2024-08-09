package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record StakeActivation(long active, long inactive, StakeState state) {

  public static StakeActivation parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("active", buf, offset, len)) {
      builder.active = ji.readLong();
    } else if (fieldEquals("inactive", buf, offset, len)) {
      builder.inactive = ji.readLong();
    } else if (fieldEquals("state", buf, offset, len)) {
      builder.state = StakeState.valueOf(ji.readString());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private long active;
    private long inactive;
    private StakeState state;

    private Builder() {
    }

    private StakeActivation create() {
      return new StakeActivation(active, inactive, state);
    }
  }
}
