package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

/// Defensive coverage for this library's cancellation of an HTTP response whose body stalls:
/// the peer must observe the connection close, not just a failed future on the client.
final class JsonHttpClientCancellationTests {

  @Test
  void theResponseDeadlineClosesTheTransportOfAStalledBody() throws Exception {
    final var loopback = InetAddress.getLoopbackAddress();
    final var server = new ServerSocket(0, 1, loopback);
    final var executor = Executors.newVirtualThreadPerTaskExecutor();
    HttpClient client = null;
    CompletableFuture<HttpResponse<byte[]>> response = null;
    try {
      final var peerClosed = executor.submit(() -> {
        try (final var socket = server.accept()) {
          // A missing transport cancellation fails here before either the native request
          // timeout or the mutation watchdog can conceal it. SocketTimeoutException escapes.
          socket.setSoTimeout(1_000);
          final var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), US_ASCII));
          while (true) {
            final var line = input.readLine();
            assertNotNull(line, "the client must finish its request headers");
            if (line.isEmpty()) {
              break;
            }
          }
          final var output = socket.getOutputStream();
          output.write(("HTTP/1.1 200 OK\r\n"
              + "Transfer-Encoding: chunked\r\n"
              + "Connection: keep-alive\r\n\r\n"
              + "1\r\nx\r\n").getBytes(US_ASCII));
          output.flush();
          // There is deliberately no final chunk. The only expected input from this
          // single-request client is EOF or a reset when it aborts the response.
          try {
            return input.read() == -1;
          } catch (final SocketException closed) {
            return true;
          }
        }
      });
      client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
      final var endpoint = new URI("http", null, loopback.getHostAddress(), server.getLocalPort(), "/", null, null);
      final var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8)).build();
      final var headersReceived = new CountDownLatch(1);
      response = client.sendAsync(request, _ -> {
        headersReceived.countDown();
        return HttpResponse.BodySubscribers.ofByteArray();
      });
      assertTrue(headersReceived.await(2, TimeUnit.SECONDS), "the peer must send the response headers");
      assertFalse(response.isDone(), "the incomplete chunked body must keep the response pending");
      final var caller = response.thenApply(HttpResponse::statusCode);

      JsonHttpClient.withResponseDeadline(response, ForkJoinPool.commonPool(), 0L);

      final var failure = assertThrows(ExecutionException.class, () -> caller.get(2, TimeUnit.SECONDS));
      assertInstanceOf(CancellationException.class, failure.getCause());
      assertTrue(peerClosed.get(2, TimeUnit.SECONDS), "the deadline must close the underlying connection");
    } finally {
      if (response != null) {
        response.cancel(true);
      }
      if (client != null) {
        client.shutdownNow();
      }
      try {
        server.close();
      } finally {
        executor.close();
      }
    }
  }
}
