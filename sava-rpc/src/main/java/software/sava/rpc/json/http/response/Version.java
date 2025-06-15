package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record Version(long featureSet, String version) {

  public static Version parse(final JsonIterator ji) {
    return ji.testObject(new Builder(), PARSER).create();
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("feature-set", buf, offset, len)) {
      builder.featureSet(ji.readLong());
    } else if (fieldEquals("solana-core", buf, offset, len)) {
      builder.version(ji.readString());
    } else {
      ji.skip();
    }
    return true;
  };

  private static final class Builder {

    private long featureSet;
    private String version;

    private Builder() {
    }

    private Version create() {
      return new Version(featureSet, version);
    }

    private void featureSet(final long featureSet) {
      this.featureSet = featureSet;
    }

    private void version(final String version) {
      this.version = version;
    }
  }
}
