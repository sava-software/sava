package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
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
    Boolean timerCancelMayInterrupt;

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
                    timerCancelMayInterrupt = (boolean) a[0];
                    yield Boolean.TRUE;
                  }
                  default -> throw new UnsupportedOperationException(m.getName());
                }
            );
          }
      );
    }
  }

  private static final class RecordingResponseFuture<T> extends CompletableFuture<HttpResponse<T>> {

    int cancellationCalls;
    Boolean mayInterruptIfRunning;

    @Override
    public boolean cancel(final boolean mayInterruptIfRunning) {
      cancellationCalls++;
      this.mayInterruptIfRunning = mayInterruptIfRunning;
      return super.cancel(mayInterruptIfRunning);
    }
  }

  @Test
  void theCancellationIsScheduledForExactlyTheDeadlineAndCancelsAPendingResponse() {
    final var response = new RecordingResponseFuture<byte[]>();
    final var scheduler = new RecordingScheduler();

    assertSame(response, JsonHttpClient.withResponseDeadline(response, scheduler.executor(), 123_456_789L));

    assertEquals(1, scheduler.scheduleCalls.get());
    assertEquals(123_456_789L, scheduler.delayNanos, "the delay is the deadline, in the unit the scheduler was given");
    assertEquals(0, scheduler.timerCancels.get(), "a pending response leaves its timer armed");

    scheduler.scheduled.run();

    assertEquals(1, response.cancellationCalls);
    assertEquals(Boolean.TRUE, response.mayInterruptIfRunning,
        "the JDK only relays cancellation to the underlying exchange when interruption is requested");
    assertTrue(response.isCancelled(), "the deadline must cancel, not merely fail, so the JDK relays it to the exchange");
    assertEquals(1, scheduler.timerCancels.get());
    assertEquals(Boolean.FALSE, scheduler.timerCancelMayInterrupt,
        "response completion unlinks the timer without asking to interrupt its task");
  }

  @Test
  void aCompletedResponseCancelsTheTimer() {
    final var response = new CompletableFuture<HttpResponse<byte[]>>();
    final var scheduler = new RecordingScheduler();
    JsonHttpClient.withResponseDeadline(response, scheduler.executor(), 1L);

    final var expected = StubHttpResponse.of(
        202, new byte[]{44, 55}, "X-Deadline-Test", "completed-after-setup");
    assertTrue(response.complete(expected));

    assertSame(expected, response.join());
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

  @Test
  void anAlreadyCompletedResponseImmediatelyCancelsTheTimerAndKeepsItsResult() {
    final var expected = StubHttpResponse.of(
        207, new byte[]{11, 22, 33}, "X-Deadline-Test", "already-complete");
    final var response = CompletableFuture.<HttpResponse<byte[]>>completedFuture(expected);
    final var scheduler = new RecordingScheduler();

    final var returned = JsonHttpClient.withResponseDeadline(response, scheduler.executor(), 99L);

    assertSame(response, returned);
    assertSame(expected, returned.join());
    assertEquals(scheduler.scheduleCalls.get(), scheduler.timerCancels.get(),
        "no timer may remain armed when completion won the setup race");
  }

  @Test
  void anAlreadyFailedResponseImmediatelyCancelsTheTimerAndKeepsItsCause() {
    final var cause = new IllegalStateException("response failed before deadline setup");
    final var response = CompletableFuture.<HttpResponse<byte[]>>failedFuture(cause);
    final var scheduler = new RecordingScheduler();

    final var returned = JsonHttpClient.withResponseDeadline(response, scheduler.executor(), 101L);

    assertSame(response, returned);
    assertSame(cause, assertThrows(CompletionException.class, returned::join).getCause());
    assertEquals(scheduler.scheduleCalls.get(), scheduler.timerCancels.get(),
        "no timer may remain armed when failure won the setup race");
  }

  @Test
  void aRejectedSubmissionDoesNotScheduleADeadline() {
    final var rejection = new RejectedExecutionException("no request threads");
    try (final var httpClient = HttpClient.newBuilder()
        .executor(_ -> {
          throw rejection;
        })
        .build()) {
      final var uri = URI.create("http://127.0.0.1:1/");
      final var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).build();
      final var scheduler = new RecordingScheduler();
      final var client = new JsonHttpClient(uri, httpClient, Duration.ofSeconds(5)) {
      };

      assertSame(rejection, assertThrows(RejectedExecutionException.class,
          () -> client.sendWithDeadline(request, HttpResponse.BodyHandlers.ofByteArray(), scheduler.executor())));
      assertEquals(0, scheduler.scheduleCalls.get(),
          "a request rejected before sendAsync returns has no response future for a timer to release");
    }
  }

  /// The seam's positive half: a submission the executor accepts arms exactly one timer on the
  /// injected scheduler, for twice the built request's timeout rather than the client default,
  /// and settling the response releases it. The executor swallows the exchange, so the response
  /// stays pending until the test cancels it and nothing here touches the network.
  @Test
  void anAcceptedSubmissionArmsOneDeadlineOnTheInjectedScheduler() {
    final var httpClient = HttpClient.newBuilder().executor(_ -> {
    }).build();
    try {
      final var uri = URI.create("http://127.0.0.1:1/");
      final var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).build();
      final var scheduler = new RecordingScheduler();
      final var client = new JsonHttpClient(uri, httpClient, Duration.ofSeconds(5)) {
      };

      final var response = client.sendWithDeadline(request, HttpResponse.BodyHandlers.ofByteArray(), scheduler.executor());

      assertFalse(response.isDone(), "the swallowed exchange must leave the response pending");
      assertEquals(1, scheduler.scheduleCalls.get(), "one accepted submission arms exactly one timer");
      assertEquals(Duration.ofSeconds(6).toNanos(), scheduler.delayNanos,
          "the timer follows the built request's timeout, not the client default");
      assertEquals(0, scheduler.timerCancels.get(), "a pending response keeps its timer armed");

      assertTrue(response.cancel(true));
      assertEquals(1, scheduler.timerCancels.get(), "settling the response releases its timer");
    } finally {
      // close() would wait for the swallowed exchange, which never runs
      httpClient.shutdownNow();
    }
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
