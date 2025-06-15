package software.sava.rpc.json.http.ws;

public enum Channel {

  account,
  logs,
  program,
  signature,
  slot;

  private final String subscribe;
  private final String unSubscribe;

  Channel() {
    this.subscribe = name() + "Subscribe";
    this.unSubscribe = name() + "Unsubscribe";
  }

  String subscribe() {
    return subscribe;
  }

  String unSubscribe() {
    return unSubscribe;
  }
}
