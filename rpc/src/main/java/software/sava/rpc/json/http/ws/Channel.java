package software.sava.rpc.json.http.ws;

enum Channel {

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

  String subscribe() {
    return subscribe;
  }

  String unSubscribe() {
    return unSubscribe;
  }
}
