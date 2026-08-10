package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// The lock-release contract, asserted directly: every entry point that acquires the
/// engine's lifecycle lock must return with it released. Removing an `unlock()` from a
/// `finally` is invisible to the mutated thread — reentrancy hides the leak from control flow
/// — and was long accepted as observable only by a second thread blocking, which is a timing
/// harness. The lock has been package-private since the round-nine review (widened for a
/// lock-boundary assertion, per the house rule of visibility over reflection), which turns
/// every one of those mutants into a zero-race kill: drive the path on the test thread, then
/// ask the hold count. This suite retires the `# unlock in finally` baseline family, the
/// audited checkCycle timeout row, and the unlock flavor of the timeout-audit newcomers — the
/// leaked lock parks a LATER locked entry, which under load read as a racy TIMED_OUT.
@ExtendWith(QuietWsLogging.class)
final class WsLockReleaseContractTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final PublicKey ACCOUNT_A =
      PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");

  private static SolanaJsonRpcWebsocket websocket() {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null,
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), null, null, (_, _, _) -> {
        }, null, null, null
    );
  }

  private static void assertReleased(final SolanaJsonRpcWebsocket ws, final String path) {
    assertFalse(ws.lock.isLocked(), path + " must return with the lifecycle lock released");
  }

  @Test
  void registrationPathsReleaseTheLock() {
    try (final var ws = websocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      assertReleased(ws, "accountSubscribe");
      assertTrue(ws.slotSubscribe(_ -> {
      }));
      assertReleased(ws, "slotSubscribe");
      assertTrue(ws.rootSubscribe(_ -> {
      }));
      assertReleased(ws, "rootSubscribe");
      assertTrue(ws.subscribe("fooSubscribe", "fooUnsubscribe", "fooNotification",
          "k", "\"p\"", JsonIterator::readLong, null, _ -> {
          }));
      assertReleased(ws, "subscribe");
      ws.exceptionSubscribe(_ -> {
      });
      assertReleased(ws, "exceptionSubscribe");

      assertTrue(ws.accountUnsubscribe(ACCOUNT_A));
      assertReleased(ws, "accountUnsubscribe");
      assertTrue(ws.slotUnsubscribe());
      assertReleased(ws, "slotUnsubscribe");
      assertTrue(ws.rootUnsubscribe());
      assertReleased(ws, "rootUnsubscribe");
      assertTrue(ws.unsubscribe("fooNotification", "k"));
      assertReleased(ws, "unsubscribe");
    }
  }

  @Test
  void adoptionSendAndCheckPathsReleaseTheLock() throws InterruptedException {
    try (final var ws = websocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      // onOpen -> adopt -> handlePendingSubscriptions -> sendSubscription's chained stages run
      // synchronously on this thread under the recording socket, covering the compose-stage
      // and send-success-stamp unlocks alongside adopt's own.
      ws.onOpen(socket);
      assertReleased(ws, "onOpen/adopt/sendSubscription");
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":700,"id":2}"""), true);
      assertReleased(ws, "confirmation handling");
      ws.checkCycle(0L);
      assertReleased(ws, "checkCycle");
      ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
      assertReleased(ws, "onPong-driven pass");
      assertTrue(ws.accountUnsubscribe(ACCOUNT_A));
      ws.checkCycle(0L); // flush mints and transmits the cancellation
      assertReleased(ws, "flush/sendUnSubscription");
    }
  }

  @Test
  void connectPathsReleaseTheLock() {
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>(), socket);
    final var scheduler = new RecordingScheduler();
    try (final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        }, null, null, null)) {
      // Immediate branch: the recording builder completes synchronously, so ownBuild's
      // ownership hook runs its stale-check (and its unlock) on this thread too.
      assertNotNull(ws.connect());
      assertReleased(ws, "connect (immediate) and ownBuild's completion hook");

      // Deferred branch: the throttle routes through the scheduler; running the captured task
      // executes deferredBuild — whose closed-check and buildAsync share one locked step —
      // on this thread.
      assertNotNull(ws.connect());
      assertFalse(scheduler.deferred.isEmpty(), "the second connect defers under the throttle");
      scheduler.deferred.getFirst().task().run();
      assertReleased(ws, "deferredBuild");
    }
  }

  @Test
  void teardownPathsReleaseTheLock() {
    final var ws = websocket();
    assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
    }));
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    ws.onClose(socket, java.net.http.WebSocket.NORMAL_CLOSURE, "peer close");
    assertReleased(ws, "onClose default action");
    ws.close();
    assertReleased(ws, "close");
    assertTrue(ws.closed());
  }
}
