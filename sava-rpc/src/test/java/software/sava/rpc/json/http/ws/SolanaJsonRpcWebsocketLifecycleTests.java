package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/// Connection lifecycle beyond reconnects: accessors, the close frame and its
/// bookkeeping, the onClose/onError delegation split, pong-driven write cycles,
/// and the sendText/sendPing failure callbacks.
final class SolanaJsonRpcWebsocketLifecycleTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);

  private static SolanaJsonRpcWebsocket websocket(final TestClock clock,
                                                  final SolanaRpcWebsocket.OnClose onClose,
                                                  final BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError,
                                                  final BiConsumer<SolanaRpcWebsocket, Throwable> onPingError) {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        TIMINGS,
        clock,
        new RecordingExecutor(),
        null,
        onClose,
        (_, _) -> {
        },
        onSendTextError,
        onPingError
    );
  }

  @Test
  void accessorsExposeConstructorState() {
    try (final var ws = websocket(new TestClock(), null, null, null)) {
      assertEquals(ENDPOINT, ws.endpoint());
      assertSame(TIMINGS, ws.timings());
      assertSame(SolanaAccounts.MAIN_NET, ws.solanaAccounts());
      assertEquals(Commitment.CONFIRMED, ws.defaultCommitment());
      assertFalse(ws.closed());
    }
  }

  @Test
  void closeSendsTheNormalClosureFrame() {
    final var ws = websocket(new TestClock(), (_, _, _) -> {
    }, null, null);
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    ws.close();
    assertEquals(java.util.List.of("1000:close"), socket.closeReasons);
    assertTrue(ws.closed());
  }

  /// close() forgets every channel: nothing survives to be re-sent on a
  /// subsequent connection. The account/slot channels are pinned in the
  /// reconnect tests; this covers the rest.
  @Test
  void closeClearsEveryChannel() {
    final var ws = websocket(new TestClock(), (_, _, _) -> {
    }, null, null);
    final var key = software.sava.core.accounts.PublicKey
        .fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
    assertTrue(ws.logsSubscribe(key, _ -> {
    }));
    assertTrue(ws.signatureSubscribe("sig", _ -> {
    }));
    assertTrue(ws.programSubscribe(key, _ -> {
    }));
    assertTrue(ws.rootSubscribe(_ -> {
    }));
    assertTrue(ws.subscribe("voteSubscribe", "voteUnsubscribe", "voteNotification",
        "vote", "", JsonIterator::readString, null, _ -> {
        }));

    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    assertEquals(5, socket.sentText.size());

    ws.close();

    final var afterClose = new RecordingWebSocket();
    ws.onOpen(afterClose);
    assertTrue(afterClose.sentText.isEmpty(), "close() should forget every subscription: " + afterClose.sentText);
  }

  @Test
  void onCloseWithoutAHandlerClosesTheWebsocket() {
    final var ws = websocket(new TestClock(), null, null, null);
    ws.onOpen(new RecordingWebSocket());
    ws.onClose(new RecordingWebSocket(), 1006, "connection dropped");
    assertTrue(ws.closed());

    // and the blank-reason logging branch behaves the same
    final var blank = websocket(new TestClock(), null, null, null);
    blank.onOpen(new RecordingWebSocket());
    blank.onClose(new RecordingWebSocket(), 1006, "");
    assertTrue(blank.closed());
  }

  @Test
  void onCloseWithAHandlerDelegatesAndLeavesTheDecision() {
    final var seen = new AtomicReference<String>();
    try (final var ws = websocket(new TestClock(), (websocket, code, reason) -> seen.set(code + ":" + reason), null, null)) {
      ws.onOpen(new RecordingWebSocket());
      ws.onClose(new RecordingWebSocket(), 4242, "bye");
      assertEquals("4242:bye", seen.get());
      assertFalse(ws.closed(), "the handler owns the decision to close");
    }
  }

  @Test
  void onCloseAndThenComposesInOrder() {
    final var calls = new ArrayList<String>();
    final SolanaRpcWebsocket.OnClose first = (_, code, reason) -> calls.add("first:" + code + ':' + reason);
    final SolanaRpcWebsocket.OnClose second = (_, code, reason) -> calls.add("second:" + code + ':' + reason);
    first.andThen(second).accept(null, 7, "r");
    assertEquals(java.util.List.of("first:7:r", "second:7:r"), calls);
  }

  @Test
  void pongDrivesAWriteCycle() {
    try (final var ws = websocket(new TestClock(), (_, _, _) -> {
    }, null, null)) {
      assertTrue(ws.rootSubscribe(_ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
      assertEquals(1, socket.sentText.size(), "a pong should flush the pending subscription");
      assertTrue(socket.sentText.getFirst().contains("rootSubscribe"), socket.sentText.toString());
    }
  }

  @Test
  void sendTextFailureFeedsTheHandler() {
    final var seen = new AtomicReference<Throwable>();
    try (final var ws = websocket(new TestClock(), (_, _, _) -> {
    }, (_, error) -> seen.set(error), null)) {
      final var boom = new IllegalStateException("send failed");
      final var socket = new RecordingWebSocket();
      socket.failText = boom;
      assertTrue(ws.rootSubscribe(_ -> {
      }));
      ws.onOpen(socket);
      assertSame(boom, seen.get());
    }
  }

  @Test
  void sendTextFailureWithoutAHandlerIsLoggedNotThrown() {
    try (final var ws = websocket(new TestClock(), (_, _, _) -> {
    }, null, null)) {
      final var socket = new RecordingWebSocket();
      socket.failText = new IllegalStateException("send failed");
      assertTrue(ws.rootSubscribe(_ -> {
      }));
      assertDoesNotThrow(() -> ws.onOpen(socket));
      assertEquals(1, socket.sentText.size());
    }
  }

  /// A failed ping rolls `lastWrite` back so the next check retries instead of
  /// treating the failed ping as a successful write.
  @Test
  void pingFailureFeedsTheHandlerAndRetriesNextCycle() {
    final var seen = new AtomicReference<Throwable>();
    final var clock = new TestClock();
    try (final var ws = websocket(clock, (_, _, _) -> {
    }, null, (_, error) -> seen.set(error))) {
      final var boom = new IllegalStateException("ping failed");
      final var socket = new RecordingWebSocket();
      socket.failPing = boom;

      ws.onOpen(socket);
      assertEquals(0, socket.pings, "opening the connection counts as the first write");

      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
      assertEquals(1, socket.pings);
      assertSame(boom, seen.get());

      // without advancing the clock again: the rollback re-arms the ping window
      ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
      assertEquals(2, socket.pings, "a failed ping must not count as the last write");
    }
  }

  /// The check loop runs on the injected executor; interrupting it exits the
  /// loop and closes the websocket. Run inline with the interrupt flag pre-set,
  /// so the await throws immediately instead of parking.
  @Test
  void checkLoopExitsOnInterruptAndCloses() {
    final var executor = new RecordingExecutor();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null,
        TIMINGS, new TestClock(), executor,
        null, (_, _, _) -> {
        }, (_, _) -> {
        }, null, null
    );
    assertEquals(1, executor.tasks.size(), "the constructor submits the check loop");

    Thread.currentThread().interrupt();
    executor.tasks.getFirst().run();
    assertFalse(Thread.interrupted(), "the await consumed the interrupt");
    assertTrue(ws.closed(), "an interrupted loop closes the websocket on the way out");
  }

  /// Once closed, a (re)run of the loop task returns without waiting — this is
  /// how an injected executor, which close() never shuts down, gets its thread
  /// back.
  @Test
  void checkLoopReturnsImmediatelyOnceClosed() {
    final var executor = new RecordingExecutor();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null,
        TIMINGS, new TestClock(), executor,
        null, (_, _, _) -> {
        }, (_, _) -> {
        }, null, null
    );
    ws.close();
    assertFalse(executor.shutdown, "an injected executor is the caller's to shut down");
    executor.tasks.getFirst().run();
    assertFalse(Thread.currentThread().isInterrupted());
    assertTrue(ws.closed());
  }

  @Test
  void pingFailureWithoutAHandlerIsLoggedNotThrown() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock, (_, _, _) -> {
    }, null, null)) {
      final var socket = new RecordingWebSocket();
      socket.failPing = new IllegalStateException("ping failed");
      ws.onOpen(socket);
      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      assertDoesNotThrow(() -> ws.onPong(socket, ByteBuffer.wrap(new byte[0])));
      assertEquals(1, socket.pings);
    }
  }
}
