module software.sava.rpc {
  requires software.sava.core;

  requires systems.comodal.json_iterator;
  requires java.net.http;

  exports software.sava.rpc.json;
  exports software.sava.rpc.json.http;
  exports software.sava.rpc.json.http.client;
  exports software.sava.rpc.json.http.request;
  exports software.sava.rpc.json.http.response;
  exports software.sava.rpc.json.http.ws;
}
