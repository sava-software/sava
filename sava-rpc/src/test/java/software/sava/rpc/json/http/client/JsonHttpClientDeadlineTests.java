package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
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
}
