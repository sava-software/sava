package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;

import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// When `connect()` admits a new attempt, every build an earlier attempt started has
/// already settled: the premise of the ws `# settled prior build` family
/// (`config/pitest/README.md`). That mutant sends the predecessor's build to the `join()`
/// arm instead of the `cancel` arm, which differs only while that build is still pending.
///
/// The oracle is the builder's own futures, recorded as they are handed out and inspected
/// at each admission, an admission being a new `buildAsync` call or a newly deferred
/// attempt. Every way an attempt ends is driven: a pending handshake that callers join, a
/// deferred one, a build call that throws, a transport retired while its build is pending,
/// and a re-entrant `connect()` from the attempt's own completion.
@ExtendWith(QuietWsLogging.class)
final class ConnectSuccessorAdmissionTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  /// No reconnect throttle: a settled attempt is followed at once, so admission is decided
  /// by the single-flight gate alone.
  private static final Timings UNTHROTTLED = new Timings(0, 60_000, 60_000);

  /// Hands out futures the test settles. With `openWith`, delivers that socket to the
  /// attempt listener synchronously, as the JDK may before its future completes; with
  /// `throwNext`, the next call throws instead of returning.
  private static final class PendingBuilder implements WebSocket.Builder {

    final List<CompletableFuture<WebSocket>> builds = new ArrayList<>();
    final List<WebSocket.Listener> listeners = new ArrayList<>();
    RecordingWebSocket openWith;
    RuntimeException throwNext;
    /// Each call's view of the futures handed out before it: whether every one had settled.
    final List<Boolean> earlierBuildsSettledAtCall = new ArrayList<>();

    @Override
    public WebSocket.Builder header(final String name, final String value) {
      return this;
    }

    @Override
    public WebSocket.Builder connectTimeout(final Duration timeout) {
      return this;
    }

    @Override
    public WebSocket.Builder subprotocols(final String mostPreferred, final String... lesserPreferred) {
      return this;
    }

    @Override
    public CompletableFuture<WebSocket> buildAsync(final URI uri, final WebSocket.Listener listener) {
      earlierBuildsSettledAtCall.add(builds.stream().allMatch(CompletableFuture::isDone));
      listeners.add(listener);
      final var failure = throwNext;
      if (failure != null) {
        throwNext = null;
        builds.add(CompletableFuture.failedFuture(failure));
        throw failure;
      }
      if (openWith != null) {
        listener.onOpen(openWith);
      }
      final var build = new CompletableFuture<WebSocket>();
      builds.add(build);
      return build;
    }
  }

  private static SolanaJsonRpcWebsocket websocket(final PendingBuilder builder,
                                                  final Timings timings,
                                                  final TestClock clock,
                                                  final RecordingScheduler scheduler) {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder, timings, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock,
        new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        },
        // a handler keeps a retired transport from closing the wrapper
        (_, _) -> {
        },
        null, null
    );
  }

  @Test
  void aPendingBuildAdmitsNoSuccessorAndIsNotCancelledByItsJoiners() {
    final var builder = new PendingBuilder();
    try (final var ws = websocket(builder, UNTHROTTLED, new TestClock(), new RecordingScheduler())) {
      final var first = ws.connect();
      final var second = ws.connect();
      final var third = ws.connect();
      assertEquals(1, builder.builds.size(), "joiners must not start a second handshake");
      final var build = builder.builds.getFirst();
      assertFalse(build.isDone(), "a joiner must not cancel or settle the pending build");
      assertFalse(first.isDone());
      assertFalse(second.isDone());
      assertFalse(third.isDone());

      final var socket = new RecordingWebSocket();
      build.complete(socket);
      for (final var view : List.of(first, second, third)) {
        assertTrue(view.isDone(), "every view settles with the build");
        assertSame(socket, view.getNow(null));
      }

      assertNotNull(ws.connect());
      assertEquals(2, builder.builds.size(), "a settled attempt admits its successor");
      assertEquals(List.of(true, true), builder.earlierBuildsSettledAtCall);
      assertFalse(build.isCancelled(), "the predecessor settled on its own");
      assertTrue(socket.aborted, "the unadopted predecessor socket is released at admission");
    }
  }

  @Test
  void aDeferredAttemptAdmitsNoSuccessorUntilItsBuildSettles() {
    final var builder = new PendingBuilder();
    final var clock = new TestClock();
    final var scheduler = new RecordingScheduler();
    final long window = 5_000;
    try (final var ws = websocket(builder, new Timings(window, 60_000, 60_000), clock, scheduler)) {
      assertNotNull(ws.connect());
      builder.builds.getFirst().complete(new RecordingWebSocket());

      final var settledAtDeferral = new AtomicReference<Boolean>();
      scheduler.duringSchedule = () -> settledAtDeferral.set(
          builder.builds.stream().allMatch(CompletableFuture::isDone));
      final var deferred = ws.connect();
      assertEquals(1, scheduler.deferred.size(), "inside the window the attempt is deferred");
      assertEquals(Boolean.TRUE, settledAtDeferral.get(), "deferred only after the build settled");
      assertNotNull(ws.connect());
      assertEquals(1, scheduler.deferred.size(), "a joiner of a deferred attempt defers nothing");
      assertEquals(1, builder.builds.size());

      clock.advanceMillis(window);
      scheduler.deferred.getFirst().task().run();
      assertEquals(2, builder.builds.size());
      final var build = builder.builds.getLast();
      assertNotNull(ws.connect());
      assertEquals(2, builder.builds.size(), "the deferred attempt's pending build admits nothing");
      assertFalse(build.isDone());

      build.complete(new RecordingWebSocket());
      assertTrue(deferred.isDone());
      clock.advanceMillis(window);
      assertNotNull(ws.connect());
      assertEquals(3, builder.builds.size());
      assertEquals(List.of(true, true, true), builder.earlierBuildsSettledAtCall);
      assertFalse(build.isCancelled());
    }
  }

  @Test
  void aBuildCallThatThrowsLeavesNothingPendingForItsSuccessor() {
    final var builder = new PendingBuilder();
    builder.throwNext = new IllegalStateException("builder refused");
    try (final var ws = websocket(builder, UNTHROTTLED, new TestClock(), new RecordingScheduler())) {
      final var failed = ws.connect();
      assertTrue(failed.isCompletedExceptionally(), "a throwing build call settles its attempt");

      assertNotNull(ws.connect());
      assertEquals(2, builder.builds.size(), "the failed attempt admits its successor");
      assertEquals(List.of(true, true), builder.earlierBuildsSettledAtCall);
    }
  }

  @Test
  void aTransportRetiredWhileItsBuildIsPendingCancelsThatBuildBeforeAnySuccessor() {
    final var builder = new PendingBuilder();
    final var socket = new RecordingWebSocket();
    builder.openWith = socket;
    try (final var ws = websocket(builder, UNTHROTTLED, new TestClock(), new RecordingScheduler())) {
      final var attempt = ws.connect();
      final var build = builder.builds.getFirst();
      assertFalse(build.isDone(), "adopted before its build settled");

      builder.listeners.getFirst().onError(socket, new IOException("transport lost"));
      assertTrue(socket.aborted);
      assertTrue(build.isCancelled(), "retirement releases its own pending build");
      assertTrue(attempt.isCompletedExceptionally());

      builder.openWith = null;
      assertNotNull(ws.connect());
      assertEquals(2, builder.builds.size());
      assertEquals(List.of(true, true), builder.earlierBuildsSettledAtCall);
    }
  }

  @Test
  void aReentrantConnectFromTheAttemptsCompletionFindsItsBuildSettled() {
    final var builder = new PendingBuilder();
    try (final var ws = websocket(builder, UNTHROTTLED, new TestClock(), new RecordingScheduler())) {
      final var attempt = ws.connect();
      final var build = builder.builds.getFirst();
      final var buildSettledAtReentry = new AtomicReference<Boolean>();
      final var successor = new AtomicReference<CompletionStage<?>>();
      attempt.whenComplete((_, _) -> {
        buildSettledAtReentry.set(build.isDone());
        successor.set(ws.connect());
      });

      build.complete(new RecordingWebSocket());

      assertEquals(Boolean.TRUE, buildSettledAtReentry.get());
      assertNotNull(successor.get(), "the re-entrant connect was admitted");
      assertEquals(2, builder.builds.size());
      assertEquals(List.of(true, true), builder.earlierBuildsSettledAtCall);
      assertFalse(build.isCancelled());
    }
  }
}
