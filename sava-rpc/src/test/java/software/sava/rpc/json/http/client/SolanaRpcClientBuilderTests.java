package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.SolanaNetwork;
import software.sava.rpc.json.http.request.Commitment;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

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

  /// Every other test here hands the builder an `HttpClient` so it can assert the
  /// instance came through, which leaves the *omitted* case — the one that decides
  /// what an unconfigured client talks over — driven by no test at all. Construction
  /// wiring is only observable to the test that constructs, so the fallback needs its
  /// own build: without it, dropping `HttpClient.newHttpClient()` and passing the null
  /// field straight through is indistinguishable to the suite.
  @Test
  void anOmittedHttpClientDefaultsToANewOne() {
    final var client = SolanaRpcClient.build().endpoint(ENDPOINT).createClient();
    try (final var httpClient = client.httpClient()) {
      assertNotNull(httpClient, "an unconfigured builder must supply its own HttpClient");
    }
  }

  /// The exchange deadline is armed on the builder's scheduler and nowhere else. Observed the
  /// way the seam tests observe it: an HTTP client whose executor swallows the exchange leaves
  /// the response pending, and the one `schedule` call the deadline makes lands on the proxy.
  @Test
  void aConfiguredDeadlineSchedulerArmsTheExchangeDeadline() {
    final var scheduleCalls = new AtomicInteger();
    final var scheduler = (ScheduledExecutorService) Proxy.newProxyInstance(
        ScheduledExecutorService.class.getClassLoader(),
        new Class<?>[]{ScheduledExecutorService.class},
        (proxy, method, args) -> {
          if (!method.getName().equals("schedule")) {
            throw new UnsupportedOperationException(method.getName());
          }
          scheduleCalls.incrementAndGet();
          return Proxy.newProxyInstance(
              ScheduledFuture.class.getClassLoader(),
              new Class<?>[]{ScheduledFuture.class},
              (p, m, a) -> {
                if (m.getName().equals("cancel")) {
                  return Boolean.TRUE;
                }
                throw new UnsupportedOperationException(m.getName());
              }
          );
        }
    );
    final var swallowing = HttpClient.newBuilder().executor(_ -> {
    }).build();
    try {
      final var builder = SolanaRpcClient.build()
          .endpoint(ENDPOINT)
          .httpClient(swallowing)
          .deadlineScheduler(scheduler);
      assertSame(scheduler, builder.deadlineScheduler());
      final var client = builder.createClient();

      final var health = client.getHealth();

      assertFalse(health.isDone(), "the swallowed exchange must leave the response pending");
      assertEquals(1, scheduleCalls.get(), "one accepted submission arms exactly one deadline, on the supplied scheduler");
    } finally {
      // close() would wait for the swallowed exchange, which never runs
      swallowing.shutdownNow();
    }
  }

  @Test
  void anUnsetDeadlineSchedulerIsNullOnTheBuilder() {
    assertNull(SolanaRpcClient.build().deadlineScheduler(),
        "the default is applied in createClient, so an unset scheduler reads as null here");
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

  /// compressResponses composes with a previously set extendRequest — it used to
  /// silently replace it, which read as a bug because the chaining looks
  /// compositional. A later extendRequest still replaces everything, including
  /// the compression header: the setter is a setter.
  @Test
  void compressResponsesComposesWithAnEarlierExtendRequest() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var client = (SolanaJsonRpcClient) SolanaRpcClient.build()
          .endpoint(ENDPOINT)
          .httpClient(httpClient)
          .extendRequest(request -> request.header("X-First", "set"))
          .compressResponses()
          .createClient();

      final var request = client.newRequest().uri(ENDPOINT).build();
      assertEquals("gzip", request.headers().firstValue("Accept-Encoding").orElse(null));
      assertEquals("set", request.headers().firstValue("X-First").orElse(null),
          "compressResponses must compose with the earlier extendRequest: " + request.headers().map());
    }
  }

  /// The reverse order is a replacement, not a composition — extendRequest is a
  /// plain setter, so it discards the compression header set before it.
  @Test
  void aLaterExtendRequestStillReplacesCompressResponses() {
    try (final var httpClient = HttpClient.newHttpClient()) {
      final var client = (SolanaJsonRpcClient) SolanaRpcClient.build()
          .endpoint(ENDPOINT)
          .httpClient(httpClient)
          .compressResponses()
          .extendRequest(request -> request.header("X-First", "set"))
          .createClient();

      final var request = client.newRequest().uri(ENDPOINT).build();
      assertTrue(request.headers().firstValue("Accept-Encoding").isEmpty(),
          "extendRequest is a setter and replaces: " + request.headers().map());
      assertEquals("set", request.headers().firstValue("X-First").orElse(null));
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
