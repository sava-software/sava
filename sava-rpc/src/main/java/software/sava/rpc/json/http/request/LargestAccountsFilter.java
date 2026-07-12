package software.sava.rpc.json.http.request;

public enum LargestAccountsFilter {

  CIRCULATING("circulating"),
  NON_CIRCULATING("nonCirculating");

  private final String value;

  LargestAccountsFilter(final String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }
}
