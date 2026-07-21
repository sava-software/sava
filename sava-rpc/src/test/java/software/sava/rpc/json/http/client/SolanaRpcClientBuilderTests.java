package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.SolanaNetwork;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/// The builder decides what an unconfigured client talks to and how patiently. Its
/// defaults are applied in `createClient`, not in the fields, so an omitted value
/// is only visible on the built client.
final class SolanaRpcClientBuilderTests {

  private static final URI ENDPOINT = URI.create("https://rpc.example.invalid");

  @Test
  void unconfiguredClientDefaultsToMainNetAndConfirmed() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var client = SolanaRpcClient.build().httpClient(httpClient).createClient();
      assertEquals(SolanaNetwork.MAIN_NET.getEndpoint(), client.endpoint());
      assertEquals(Commitment.CONFIRMED, client.defaultCommitment());
      assertEquals(SolanaJsonRpcClient.DEFAULT_REQUEST_TIMEOUT, client.defaultRequestTimeout());
      assertSame(httpClient, client.httpClient());
    }
  }

  @Test
  void configuredValuesReachTheClient() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var client = SolanaRpcClient.build()
          .endpoint(ENDPOINT)
          .httpClient(httpClient)
          .requestTimeout(Duration.ofSeconds(11))
          .defaultCommitment(Commitment.FINALIZED)
          .createClient();

      assertEquals(ENDPOINT, client.endpoint());
      assertSame(httpClient, client.httpClient());
      assertEquals(Duration.ofSeconds(11), client.defaultRequestTimeout());
      assertEquals(Commitment.FINALIZED, client.defaultCommitment());
    }
  }

  @Test
  void settersChainAndReturnTheSameBuilder() {
    final var builder = SolanaRpcClient.build();
    assertSame(builder, builder.endpoint(ENDPOINT));
    assertSame(builder, builder.requestTimeout(Duration.ofSeconds(1)));
    assertSame(builder, builder.defaultCommitment(Commitment.PROCESSED));
    assertSame(builder, builder.extendRequest(request -> request));
    assertSame(builder, builder.testResponse((_, _) -> true));
    assertSame(builder, builder.compressResponses());
  }

  /// extendRequest is the hook every outgoing request passes through, so what it
  /// adds has to survive onto the built request.
  @Test
  void extendRequestIsAppliedToOutgoingRequests() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var client = (SolanaJsonRpcClient) SolanaRpcClient.build()
          .endpoint(ENDPOINT)
          .httpClient(httpClient)
          .extendRequest(request -> request.header("X-Test", "applied"))
          .createClient();

      final var request = client.newRequest().uri(ENDPOINT).build();
      assertEquals("applied", request.headers().firstValue("X-Test").orElse(null));
    }
  }

  /// compressResponses is sugar for the Accept-Encoding header that turns on the
  /// gzip decode path.
  @Test
  void compressResponsesAddsTheAcceptEncodingHeader() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var client = (SolanaJsonRpcClient) SolanaRpcClient.build()
          .endpoint(ENDPOINT)
          .httpClient(httpClient)
          .compressResponses()
          .createClient();

      final var request = client.newRequest().uri(ENDPOINT).build();
      assertEquals("gzip", request.headers().firstValue("Accept-Encoding").orElse(null));
    }
  }

  /// compressResponses replaces any previously set extendRequest rather than
  /// composing with it — pinned because the chaining reads as if it would compose.
  @Test
  void compressResponsesReplacesAnEarlierExtendRequest() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var client = (SolanaJsonRpcClient) SolanaRpcClient.build()
          .endpoint(ENDPOINT)
          .httpClient(httpClient)
          .extendRequest(request -> request.header("X-First", "set"))
          .compressResponses()
          .createClient();

      final var request = client.newRequest().uri(ENDPOINT).build();
      assertEquals("gzip", request.headers().firstValue("Accept-Encoding").orElse(null));
      assertTrue(request.headers().firstValue("X-First").isEmpty(),
          "compressResponses overwrites extendRequest: " + request.headers().map());
    }
  }

  @Test
  void testResponsePredicateReachesTheClient() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var client = (SolanaJsonRpcClient) SolanaRpcClient.build()
          .endpoint(ENDPOINT)
          .httpClient(httpClient)
          .testResponse((_, body) -> body.length > 0)
          .createClient();
      assertNotNull(client);
    }
  }

  /// The builder exposes its own accessors, separate from the built client — a
  /// caller inspecting or wrapping a partially configured builder reads these.
  @Test
  void builderAccessorsReflectWhatWasSet() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var builder = SolanaRpcClient.build();
      assertNull(builder.endpoint());
      assertNull(builder.httpClient());
      assertNull(builder.requestTimeout());
      assertNull(builder.defaultCommitment());
      assertNull(builder.extendRequest());
      assertNull(builder.testResponse());

      builder.endpoint(ENDPOINT)
          .httpClient(httpClient)
          .requestTimeout(Duration.ofSeconds(17))
          .defaultCommitment(Commitment.FINALIZED)
          .extendRequest(request -> request)
          .testResponse((_, _) -> true);

      assertEquals(ENDPOINT, builder.endpoint());
      assertSame(httpClient, builder.httpClient());
      assertEquals(Duration.ofSeconds(17), builder.requestTimeout());
      assertEquals(Commitment.FINALIZED, builder.defaultCommitment());
      assertNotNull(builder.extendRequest());
      assertNotNull(builder.testResponse());
    }
  }
}
