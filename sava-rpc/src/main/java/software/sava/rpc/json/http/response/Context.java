package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record Context(long slot, String apiVersion) {

  public static Context parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("slot", buf, offset, len)) {
      builder.slot(ji.readLong());
    } else if (fieldEquals("apiVersion", buf, offset, len)) {
      builder.apiVersion(ji.readString());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private long slot;
    private String apiVersion;

    private Builder() {
    }

    private Context create() {
      return new Context(slot, apiVersion);
    }

    private void slot(final long slot) {
      this.slot = slot;
    }

    private void apiVersion(final String apiVersion) {
      this.apiVersion = apiVersion;
    }
  }
}
