package test.software.sava.rpc.json.http.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.nio.charset.StandardCharsets.UTF_8;

final class HttpClientTests {

  static {
    System.setProperty("com.sun.net.httpserver.HttpServerProvider", "sun.net.httpserver.DefaultHttpServerProvider");
  }

  private static final ExecutorService HTTP_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  static HttpServerRecord createServer() {
    try {
      final var httpServer = HttpServer.create(new InetSocketAddress(0), 0);
      httpServer.setExecutor(HTTP_EXECUTOR);
      httpServer.start();
      return HttpServerRecord.createRecord(httpServer);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static void writeResponse(final HttpExchange httpExchange, final String response) {
    writeResponse(200, httpExchange, response);
  }

  static void writeResponse(final int responseCode, final HttpExchange httpExchange, final String response) {
    final var responseBytes = response.getBytes(UTF_8);
    try {
      httpExchange.sendResponseHeaders(responseCode, responseBytes.length);
      try (final var os = httpExchange.getResponseBody()) {
        os.write(responseBytes);
      }
    } catch (final IOException ioEx) {
      throw new UncheckedIOException(ioEx);
    }
  }

  private HttpClientTests() {
  }
}
