package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record Identity(String identity) {

  public static Identity parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("identity", buf, offset, len)) {
      builder.identity = ji.readString();
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private String identity;

    private Builder() {
    }

    private Identity create() {
      return new Identity(identity);
    }
  }
}
