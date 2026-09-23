package software.sava.rpc.soak.http;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.client.JsonHttpClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/// The caller-body-handler route, driven so that it is *exercised* rather than asserted.
///
/// `JsonHttpClient.sendPostRequestNoWrap(bodyHandler, ...)` deliberately carries only the JDK's
/// own request timeout: the caller's handler owns the body, which may legitimately stream for
/// longer than any budget the library would pick. That is a contract the library declines to
/// make, so W3-B does not apply here and the harness must not pretend it does — the report says
/// `exchange-deadline property NOT APPLICABLE by contract`. What this route *is* good for is
/// proving the path runs at all under load, and that a route with no deadline timer does not
/// quietly accumulate exchanges.
///
/// The request is `getAccountInfo`-shaped because that is the method the peer stamps most
/// precisely, so a body that came back on the wrong exchange is still visible by decoding it.
/// No `Accept-Encoding` is added: the raw bytes this route hands back are the peer's own, and an
/// inflate step here would be the harness decoding rather than the subject.
final class SoakRawHttpClient extends JsonHttpClient {

  SoakRawHttpClient(final URI endpoint, final HttpClient httpClient, final Duration requestTimeout) {
    super(endpoint, httpClient, requestTimeout);
  }

  /// The whole response of one `getAccountInfo`, status and headers included, with the body
  /// undecoded. The JSON is built by hand because this route exists to bypass the client's own
  /// request construction, not to reuse it. The response object rather than its body alone,
  /// because the peer's HTTP faults answer with a non-2xx status or a `Content-Encoding` this
  /// route by contract does not inflate, and the caller must be able to tell those apart from a
  /// body that answered the wrong request.
  CompletableFuture<HttpResponse<byte[]>> getAccountInfoRaw(final long requestId, final PublicKey account) {
    final var body = "{\"jsonrpc\":\"2.0\",\"id\":" + requestId
        + ",\"method\":\"getAccountInfo\",\"params\":[\"" + account.toBase58()
        + "\",{\"encoding\":\"base64\",\"commitment\":\"confirmed\"}]}";
    return sendPostRequestNoWrap(HttpResponse.BodyHandlers.ofByteArray(), response -> response, body);
  }
}
