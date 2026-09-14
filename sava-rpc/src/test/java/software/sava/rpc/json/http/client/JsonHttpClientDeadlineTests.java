package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/// The two halves of the exchange deadline, driven with plain futures instead of a server:
/// the sentinel's timeout cancels a pending response, and a response that completes -- either
/// way -- completes the sentinel, which is what releases the scheduled timer. The transport
/// tests cover the same contract end to end against a stalling server.
final class JsonHttpClientDeadlineTests {

  @Test
  void aTimedOutSentinelCancelsThePendingResponse() {
    final var response = new CompletableFuture<HttpResponse<byte[]>>();
    final var deadline = new CompletableFuture<Void>();
    assertSame(response, JsonHttpClient.withResponseDeadline(response, deadline));

    deadline.completeExceptionally(new TimeoutException("deadline"));

    assertTrue(response.isCancelled(), "the deadline must cancel, not merely fail, so the JDK relays it to the exchange");
  }

  @Test
  void aCompletedResponseReleasesTheTimer() {
    final var response = new CompletableFuture<HttpResponse<byte[]>>();
    final var deadline = new CompletableFuture<Void>();
    JsonHttpClient.withResponseDeadline(response, deadline);

    response.complete(null);

    assertTrue(deadline.isDone(), "a finished response must release its timer, or it is retained until the deadline");
    assertFalse(deadline.isCompletedExceptionally());
    assertFalse(deadline.completeExceptionally(new TimeoutException("late")), "a released timer can no longer fire");
    assertFalse(response.isCancelled());
  }

  @Test
  void aFailedResponseReleasesTheTimerToo() {
    final var response = new CompletableFuture<HttpResponse<byte[]>>();
    final var deadline = new CompletableFuture<Void>();
    JsonHttpClient.withResponseDeadline(response, deadline);

    response.completeExceptionally(new IllegalStateException("connection reset"));

    assertTrue(deadline.isDone());
    assertFalse(deadline.isCompletedExceptionally());
  }

  /// The deadline follows the timeout on the built request, which `extendRequest` may have
  /// replaced in either direction, and falls back to the client default only when the request
  /// carries none.
  @Test
  void theDeadlineFollowsTheBuiltRequestsTimeoutNotTheClientDefault() {
    final var uri = URI.create("http://127.0.0.1:1/");
    final var overridden = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).build();
    assertEquals(Duration.ofSeconds(60).toNanos(), JsonHttpClient.responseDeadlineNanos(overridden, Duration.ofSeconds(1)));

    final var shortened = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(200)).build();
    assertEquals(Duration.ofMillis(400).toNanos(), JsonHttpClient.responseDeadlineNanos(shortened, Duration.ofSeconds(5)));

    final var none = HttpRequest.newBuilder(uri).build();
    assertEquals(Duration.ofSeconds(2).toNanos(), JsonHttpClient.responseDeadlineNanos(none, Duration.ofSeconds(1)),
        "a request without a timeout falls back to the client default");
  }

  /// Doubling saturates instead of overflowing: the largest timeout that doubles exactly is
  /// Long.MAX_VALUE >> 1 ns; one nanosecond more, Long.MAX_VALUE ns, and a duration past
  /// `toNanos`' own range all pin to Long.MAX_VALUE rather than a negative deadline (which
  /// would cancel every request on the spot) or an ArithmeticException.
  @Test
  void theDeadlineSaturatesInsteadOfOverflowing() {
    assertEquals(Long.MAX_VALUE - 1, JsonHttpClient.responseDeadlineNanos(Duration.ofNanos(Long.MAX_VALUE >> 1)));
    assertEquals(Long.MAX_VALUE, JsonHttpClient.responseDeadlineNanos(Duration.ofNanos((Long.MAX_VALUE >> 1) + 1)));
    assertEquals(Long.MAX_VALUE, JsonHttpClient.responseDeadlineNanos(Duration.ofNanos(Long.MAX_VALUE)));
    assertEquals(Long.MAX_VALUE, JsonHttpClient.responseDeadlineNanos(Duration.ofDays(365L * 1_000)));
    assertEquals(Duration.ofSeconds(16).toNanos(), JsonHttpClient.responseDeadlineNanos(Duration.ofSeconds(8)));
  }
}
