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
  private static final PublicKey ACCOUNT_B =
      PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");

  private static SolanaJsonRpcWebsocket websocket() {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null,
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), null, null, (_, _, _) -> {
        }, null, null, null
    );
  }

  /// The engine with a subscribe batch cap, which sends due subscribes in batch messages and reads
  /// an inbound array element by element.
  private static SolanaJsonRpcWebsocket batchingWebsocket() {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null,
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), null, null, (_, _, _) -> {
        }, null, null, null, 4_096
    );
  }

  private static void assertReleased(final SolanaJsonRpcWebsocket ws, final String path) {
    final int retainedHolds = ws.lock.getHoldCount();
    // A removed unlock leaves a reentrant hold on this test thread. Release it before asserting
    // so the intentionally failing mutant cannot strand close() or a later lock-taking path.
    while (ws.lock.isHeldByCurrentThread()) {
      ws.lock.unlock();
    }
    assertAll(
        () -> assertEquals(0, retainedHolds,
            path + " must return with no lifecycle-lock hold on the calling thread"),
        () -> assertFalse(ws.lock.isLocked(),
            path + " must return with the lifecycle lock released")
    );
  }

  @Test
  void registrationPathsReleaseTheLock() {
    try (final var ws = websocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      assertReleased(ws, "accountSubscribe");
      assertTrue(ws.signatureSubscribe("string-keyed-signature", _ -> {
      }));
      assertReleased(ws, "signatureSubscribe");
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
  void genericNotificationSnapshotReleasesTheLock() {
    try (final var ws = websocket()) {
      final var received = new AtomicReference<Long>();
      assertTrue(ws.subscribe("watch", "cancelWatch", "event", "key", "",
          JsonIterator::readLong, null, received::set));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":700,"id":2}"""), true);
      assertReleased(ws, "generic confirmation handling");

      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","method":"event","params":{"result":41,"subscription":700}}"""), true);

      assertAll(
          () -> assertEquals(41L, received.get(), "the snapshotted generic registration must receive the value"),
          () -> assertReleased(ws, "generic notification registry snapshot")
      );
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

  /// A failed subscription send takes the cleanup arm of its completion callback. That arm has
  /// the same lifecycle-lock obligation as the successful send-stamp arm above.
  @Test
  void failedSubscriptionSendReleasesTheLock() {
    try (final var ws = websocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      socket.failText = new java.io.IOException("subscription send failed");

      ws.onOpen(socket);

      assertReleased(ws, "failed sendSubscription cleanup");
    }
  }

  /// A batch response hands each element of an array to the whole-message handler, whose branches
  /// lock, and the array's own structural failure has a catch of its own: every path through an
  /// array returns with the lock released, a refused notification and a malformed element
  /// included.
  @Test
  void batchResponsePathsReleaseTheLock() throws InterruptedException {
    try (final var ws = batchingWebsocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      })); // request 2
      assertTrue(ws.accountSubscribe(ACCOUNT_B, _ -> {
      })); // request 3
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("""
          [{"jsonrpc":"2.0","result":700,"id":2},{"jsonrpc":"2.0","error":{"code":-32603,"message":"Subscription refused"},"id":3}]"""), true);
      assertReleased(ws, "a batch response confirming one request and refusing another");

      assertTrue(ws.accountUnsubscribe(ACCOUNT_A));
      ws.checkCycle(0L); // flush mints and transmits the cancellation, request 4
      ws.onText(socket, CharBuffer.wrap("""
          [{"jsonrpc":"2.0","result":true,"id":4},{"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":1,"root":0,"slot":2},"subscription":700}},5]"""), true);
      assertReleased(ws, "a batch response acknowledging a cancellation beside a notification and a malformed element");

      ws.onText(socket, CharBuffer.wrap("""
          [{"jsonrpc":"2.0","result":701,"id":3}"""), true);
      assertReleased(ws, "an unreadable batch response");
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
      // enters startBuild on this thread. Its ownership checks use the lifecycle lock, while the
      // caller-supplied buildAsync invocation itself remains off-lock.
      assertNotNull(ws.connect());
      assertFalse(scheduler.deferred.isEmpty(), "the second connect defers under the throttle");
      scheduler.deferred.getFirst().task().run();
      assertReleased(ws, "startBuild");
    }
  }

  /// The fallback from batch messages to single requests locks on two paths of its own: the release
  /// a null-id parse error drives from the error branch, off the lock, and the suspension decided
  /// from the retired ledger inside retirement's claim.
  @Test
  void batchRejectionFallbackPathsReleaseTheLock() throws InterruptedException {
    try (final var ws = batchingWebsocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      })); // request 2
      assertTrue(ws.accountSubscribe(ACCOUNT_B, _ -> {
      })); // request 3
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","error":{"code":-32700,"message":"Parse error"},"id":null}"""), true);
      assertReleased(ws, "a whole-batch rejection releasing the batch");
      assertTrue(ws.batchingRefused(), "the rejection was attributed");
      ws.checkCycle(0L); // the released requests go out one per message
      assertReleased(ws, "the pass re-sending the released requests");
    }
    try (final var ws = batchingWebsocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      assertTrue(ws.accountSubscribe(ACCOUNT_B, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onClose(socket, java.net.http.WebSocket.NORMAL_CLOSURE, "peer close");
      assertReleased(ws, "retirement suspending batching for the next connection");
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
