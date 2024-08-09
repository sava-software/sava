package test.software.sava.rpc.json.http.client;

import com.sun.net.httpserver.HttpServer;

import java.net.URI;

public record HttpServerRecord(HttpServer httpServer, URI endpoint) {

  static HttpServerRecord createRecord(final HttpServer httpServer) {
    final var serverAddress = httpServer.getAddress();
    final var endpoint = URI.create(String.format("http://[%s]:%d", serverAddress.getHostString(), serverAddress.getPort()));
    return new HttpServerRecord(httpServer, endpoint);
  }
}
