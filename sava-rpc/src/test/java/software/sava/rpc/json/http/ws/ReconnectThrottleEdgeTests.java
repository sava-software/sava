package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// The reconnect throttle at its edge: the ws `# zero-delay convergence` family
/// (`config/pitest/README.md`). `connect()` defers a new attempt by `reConnectDelay -
/// elapsed` while `elapsed < reConnectDelay` and builds at once otherwise; the mutant reads
/// the comparison as `<=`, so the two differ only at `elapsed == reConnectDelay`, where the
/// deferral arm computes exactly zero.
///
/// The oracle is the contract on `SolanaRpcWebsocket.connect()`, "a new attempt waits out
/// whatever remains of `reConnectDelay` since the previous one", read at a millisecond on
/// each side of the edge and on it: one millisecond short defers by exactly that
/// millisecond, and nothing remains on or past the edge, so the attempt builds without a
/// scheduled wake.
@ExtendWith(QuietWsLogging.class)
final class ReconnectThrottleEdgeTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final long WINDOW = 5_000;

  private record Fixture(SolanaJsonRpcWebsocket ws,
                         TestClock clock,
                         RecordingScheduler scheduler,
                         RecordingWebSocketBuilder builder) implements AutoCloseable {

    /// A first attempt already made: its build settled at once with a socket nobody
    /// adopted, so the next `connect()` is admitted and only the throttle decides when it
    /// builds.
    static Fixture afterFirstAttempt() {
      final var clock = new TestClock();
      final var scheduler = new RecordingScheduler();
      final var builder = new RecordingWebSocketBuilder(new AtomicReference<>(), new RecordingWebSocket());
      final var ws = new SolanaJsonRpcWebsocket(
          ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
          builder.connectTimeout(Duration.ofMillis(1_000)),
          new Timings(WINDOW, 60_000, 60_000), SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
          clock, new RecordingExecutor(), scheduler, null, (_, _, _) -> {
          }, null, null, null
      );
      assertTrue(ws.connect().isDone());
      assertEquals(1, builder.builds, "the first attempt is never throttled");
      return new Fixture(ws, clock, scheduler, builder);
    }

    @Override
    public void close() {
      ws.close();
    }
  }

  @Test
  void oneMillisecondShortOfTheWindowDefersByThatMillisecond() {
    try (final var fixture = Fixture.afterFirstAttempt()) {
      fixture.clock().advanceMillis(WINDOW - 1);
      final var attempt = fixture.ws().connect();

      assertEquals(1, fixture.builder().builds, "inside the window nothing builds yet");
      assertEquals(1, fixture.scheduler().deferred.size());
      final var deferred = fixture.scheduler().deferred.getFirst();
      assertEquals(1, deferred.unit().toMillis(deferred.delay()), "what remains of the window");

      deferred.task().run();
      assertEquals(2, fixture.builder().builds);
      assertTrue(attempt.isDone());
    }
  }

  @Test
  void exactlyOneWindowAfterTheLastAttemptBuildsWithoutAWake() {
    try (final var fixture = Fixture.afterFirstAttempt()) {
      fixture.clock().advanceMillis(WINDOW);
      final var attempt = fixture.ws().connect();

      assertEquals(2, fixture.builder().builds, "nothing remains of the window at its edge");
      assertTrue(fixture.scheduler().deferred.isEmpty(), "no wake is scheduled for a zero wait");
      assertTrue(attempt.isDone());
    }
  }

  @Test
  void pastTheWindowBuildsWithoutAWake() {
    try (final var fixture = Fixture.afterFirstAttempt()) {
      fixture.clock().advanceMillis(WINDOW + 1);
      final var attempt = fixture.ws().connect();

      assertEquals(2, fixture.builder().builds);
      assertTrue(fixture.scheduler().deferred.isEmpty());
      assertTrue(attempt.isDone());
    }
  }
}
