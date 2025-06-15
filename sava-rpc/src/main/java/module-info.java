module software.sava.rpc {
  requires java.net.http;

  requires transitive systems.comodal.json_iterator;

  requires transitive software.sava.core;

  exports software.sava.rpc.json;
  exports software.sava.rpc.json.http;
  exports software.sava.rpc.json.http.client;
  exports software.sava.rpc.json.http.request;
  exports software.sava.rpc.json.http.response;
  exports software.sava.rpc.json.http.ws;
}
