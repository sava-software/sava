package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// The exchange deadline driven with a recording scheduler instead of a server: the
/// cancellation is scheduled for exactly the deadline, running it cancels a pending response,
/// and a response that completes -- either way -- cancels the scheduled task, which is what
/// keeps a finished response from being retained until the deadline. The transport tests cover
/// the same contract end to end against a stalling server.
final class JsonHttpClientDeadlineTests {

  /// Records the one `schedule` call the deadline makes and hands back a `ScheduledFuture`
  /// whose `cancel` is recorded too; nothing runs unless the test runs it.
  private static final class RecordingScheduler {

    Runnable scheduled;
    long delayNanos = -1;
    final AtomicInteger scheduleCalls = new AtomicInteger();
    final AtomicInteger timerCancels = new AtomicInteger();

    ScheduledExecutorService executor() {
      return (ScheduledExecutorService) Proxy.newProxyInstance(
          ScheduledExecutorService.class.getClassLoader(),
          new Class<?>[]{ScheduledExecutorService.class},
          (proxy, method, args) -> {
            if (!method.getName().equals("schedule") || !(args[0] instanceof Runnable command)) {
              throw new UnsupportedOperationException(method.getName());
            }
            scheduleCalls.incrementAndGet();
            scheduled = command;
            delayNanos = ((TimeUnit) args[2]).toNanos((long) args[1]);
            return Proxy.newProxyInstance(
                ScheduledFuture.class.getClassLoader(),
                new Class<?>[]{ScheduledFuture.class},
                (p, m, a) -> switch (m.getName()) {
                  case "cancel" -> {
                    timerCancels.incrementAndGet();
                    yield Boolean.TRUE;
                  }
                  default -> throw new UnsupportedOperationException(m.getName());
                }
            );
          }
      );
    }
  }

  @Test
  void theCancellationIsScheduledForExactlyTheDeadlineAndCancelsAPendingResponse() {
    final var response = new CompletableFuture<HttpResponse<byte[]>>();
    final var scheduler = new RecordingScheduler();

    assertSame(response, JsonHttpClient.withResponseDeadline(response, scheduler.executor(), 123_456_789L));

    assertEquals(1, scheduler.scheduleCalls.get());
    assertEquals(123_456_789L, scheduler.delayNanos, "the delay is the deadline, in the unit the scheduler was given");
    assertEquals(0, scheduler.timerCancels.get(), "a pending response leaves its timer armed");

    scheduler.scheduled.run();

    assertTrue(response.isCancelled(), "the deadline must cancel, not merely fail, so the JDK relays it to the exchange");
  }

  @Test
  void aCompletedResponseCancelsTheTimer() {
    final var response = new CompletableFuture<HttpResponse<byte[]>>();
    final var scheduler = new RecordingScheduler();
    JsonHttpClient.withResponseDeadline(response, scheduler.executor(), 1L);

    response.complete(null);

    assertEquals(1, scheduler.timerCancels.get(), "a finished response must release its timer, or it is retained until the deadline");
    scheduler.scheduled.run();
    assertFalse(response.isCancelled(), "a timer that fires late finds a completed response and changes nothing");
  }

  @Test
  void aFailedResponseCancelsTheTimerToo() {
    final var response = new CompletableFuture<HttpResponse<byte[]>>();
    final var scheduler = new RecordingScheduler();
    JsonHttpClient.withResponseDeadline(response, scheduler.executor(), 1L);

    response.completeExceptionally(new IllegalStateException("connection reset"));

    assertEquals(1, scheduler.timerCancels.get());
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
