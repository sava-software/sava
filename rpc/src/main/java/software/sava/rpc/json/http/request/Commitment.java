package software.sava.rpc.json.http.request;

import systems.comodal.jsoniter.CharBufferFunction;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public enum Commitment {

  FINALIZED("finalized"),
  CONFIRMED("confirmed"),
  PROCESSED("processed");

  private final String value;

  Commitment(final String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  public static final CharBufferFunction<Commitment> PARSER = (buf, offset, len) -> {
    if (fieldEquals("processed", buf, offset, len)) {
      return PROCESSED;
    } else if (fieldEquals("confirmed", buf, offset, len)) {
      return CONFIRMED;
    } else if (fieldEquals("finalized", buf, offset, len)) {
      return FINALIZED;
    } else {
      return null;
    }
  };
}
