package software.sava.rpc.json.http.response;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record Version(long featureSet, String version) {

  public static Version parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private long featureSet;
    private String version;

    private Parser() {
    }

    private Version create() {
      return new Version(featureSet, version);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("feature-set", buf, offset, len)) {
        featureSet = ji.readLong();
      } else if (fieldEquals("solana-core", buf, offset, len)) {
        version = ji.readString();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
