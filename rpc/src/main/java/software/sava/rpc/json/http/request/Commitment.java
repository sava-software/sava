package software.sava.rpc.json.http.request;

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
}
