package software.sava.rpc.json.http.ws;

public enum Channel {

  account,
  logs,
  program,
  signature,
  slot,
  transaction;

  private final String subscribe;
  private final String unSubscribe;

  Channel() {
    this.subscribe = name() + "Subscribe";
    this.unSubscribe = name() + "Unsubscribe";
  }

  public String subscribe() {
    return subscribe;
  }

  public String unSubscribe() {
    return unSubscribe;
  }
}
