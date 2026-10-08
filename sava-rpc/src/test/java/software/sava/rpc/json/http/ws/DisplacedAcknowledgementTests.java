package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;

import java.math.BigInteger;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Defensive regression for the takeover fence on the cancellation-acknowledgement branch
/// of `SolanaJsonRpcWebsocket.onWholeMessage`. A peer's `true` answer to an un-subscription
/// can already be inside the displaced connection's parser when a successor is adopted. The
/// acknowledgement maps it would consult belong to the displaced connection, but its
/// casualty replay also writes the durable `Subscription` handle, which the successor
/// re-arms and re-confirms. The oracle is the public handle and the successor's wire: the
/// server id the successor granted stays on the handle, and a later unsubscribe cancels
/// exactly that id.
@ExtendWith(QuietWsLogging.class)
final class DisplacedAcknowledgementTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000L, 60_000L, 60_000L);
  private static final PublicKey ACCOUNT =
      PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final long CALLBACK_BOUND_MILLIS = 1_000L;

  private static SolanaJsonRpcWebsocket websocket(final TestClock clock) {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        clock,
        new RecordingExecutor(),
        null,
        _ -> {
        },
        (_, _, _) -> {
        },
        null,
        null,
        null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws,
                           final RecordingWebSocket socket,
                           final String json) {
    ws.onText(socket, json, true);
  }

  private static long framesContaining(final RecordingWebSocket socket, final String token) {
    return socket.sentText.stream().filter(frame -> frame.contains(token)).count();
  }

  /// Counts down when `onText` first asks for the message length, which it does after it
  /// has resolved the callback's connection and before it reaches any lifecycle lock.
  private record CheckpointedFrame(String json,
                                   CountDownLatch resolved,
                                   AtomicBoolean checkpointed) implements CharSequence {

    @Override
    public int length() {
      checkpointed.set(true);
      resolved.countDown();
      return json.length();
    }

    @Override
    public char charAt(final int index) {
      return json.charAt(index);
    }

    @Override
    public CharSequence subSequence(final int start, final int end) {
      return json.subSequence(start, end);
    }

    @Override
    public String toString() {
      return json;
    }
  }

  /// Delivers `json` on `displaced` from a second thread while this thread holds the
  /// lifecycle lock, and runs `takeover` once that callback has resolved its connection.
  /// The callback can then only proceed after the takeover is complete, with no sleeps or
  /// polling.
  private static void feedAcrossTakeover(final SolanaJsonRpcWebsocket ws,
                                         final RecordingWebSocket displaced,
                                         final String json,
                                         final Runnable takeover) throws InterruptedException {
    final var resolved = new CountDownLatch(1);
    final var checkpointed = new AtomicBoolean();
    final var retainedHolds = new AtomicInteger(-1);
    final var callbackFailure = new AtomicReference<Throwable>();
    final var callback = Thread.ofPlatform().unstarted(() -> {
      try {
        ws.onText(displaced, new CheckpointedFrame(json, resolved, checkpointed), true);
      } catch (final Throwable t) {
        callbackFailure.set(t);
      } finally {
        // A failed callback must not strand the lifecycle lock on this exiting thread.
        resolved.countDown();
        retainedHolds.set(ws.lock.getHoldCount());
        while (ws.lock.isHeldByCurrentThread()) {
          ws.lock.unlock();
        }
      }
    });

    ws.lock.lock();
    try {
      callback.start();
      assertTrue(resolved.await(CALLBACK_BOUND_MILLIS, TimeUnit.MILLISECONDS),
          "callback did not reach the post-connection-resolution checkpoint");
      assertTrue(checkpointed.get(), "callback exited before resolving the displaced connection");
      takeover.run();
    } finally {
      ws.lock.unlock();
      callback.join(CALLBACK_BOUND_MILLIS);
      if (callback.isAlive()) {
        callback.interrupt();
        callback.join(CALLBACK_BOUND_MILLIS);
      }
    }
    assertFalse(callback.isAlive(), "takeover callback did not complete within its bound");
    assertEquals(0, retainedHolds.get(), "callback retained the lifecycle lock");
    assertNull(callbackFailure.get(), () -> "callback escaped: " + callbackFailure.get());
  }

  /// The displaced connection holds a casualty shape: its compensation for id 700 (request
  /// 4) was transmitted after the successor registration's attempt (request 3), which an
  /// id-reusing peer granted 700 too. A `true` answer to request 4 on that connection would
  /// re-queue the registration as a casualty. Here the answer is already being parsed when
  /// a successor is adopted, replays request 3 once its resend window has passed, and
  /// grants it the id 800. The answer belongs to the displaced connection; the handle's id,
  /// and the cancellation an unsubscribe owes, belong to the successor.
  @Test
  void staleCasualtyAcknowledgementInsideTheParserCannotUnconfirmTheSuccessor()
      throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      final var displaced = new RecordingWebSocket();
      ws.onOpen(displaced); // predecessor request 2
      assertTrue(ws.accountUnsubscribe(ACCOUNT)); // cancelled in flight: tombstoned

      final var handle = new AtomicReference<Subscription<?>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, ACCOUNT, handle::set, _ -> {
      }));
      ws.checkCycle(0L); // successor request 3 precedes the compensation
      feed(ws, displaced, "{\"jsonrpc\":\"2.0\",\"result\":700,\"id\":2}"); // compensation 4
      feed(ws, displaced, "{\"jsonrpc\":\"2.0\",\"result\":700,\"id\":3}"); // id reuse
      assertEquals(BigInteger.valueOf(700), handle.get().subId());

      clock.advanceMillis(TIMINGS.subscriptionResendDelay() + 1); // the replay is due on adoption
      final var successor = new RecordingWebSocket();
      feedAcrossTakeover(ws, displaced, "{\"jsonrpc\":\"2.0\",\"result\":true,\"id\":4}", () -> {
        ws.onOpen(successor);
        assertEquals(1L, framesContaining(successor, "\"id\":3,\"method\":\"accountSubscribe\""),
            () -> "the successor replays request 3: " + successor.sentText);
        feed(ws, successor, "{\"jsonrpc\":\"2.0\",\"result\":800,\"id\":3}");
        assertEquals(BigInteger.valueOf(800), handle.get().subId(),
            "the successor confirmed the replayed handle before the stale answer resumed");
      });

      assertEquals(BigInteger.valueOf(800), handle.get().subId(),
          "a displaced connection's acknowledgement cannot erase the successor's grant");
      assertTrue(ws.accountUnsubscribe(ACCOUNT));
      ws.checkCycle(0L);
      assertEquals(1L, framesContaining(successor, "\"method\":\"accountUnsubscribe\",\"params\":[800]"),
          () -> "the successor's grant must be cancelled by the unsubscribe: " + successor.sentText);
    }
  }
}
