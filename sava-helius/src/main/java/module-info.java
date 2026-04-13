module software.sava.helius {
  requires java.net.http;

  requires transitive systems.comodal.json_iterator;

  requires transitive software.sava.core;
  requires transitive software.sava.rpc;

  exports software.sava.helius.rpc.json;
  exports software.sava.helius.rpc.json.request;
  exports software.sava.helius.rpc.json.response;
  exports software.sava.helius.demo;
}
