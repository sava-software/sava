package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
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
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        clock,
        new RecordingExecutor(),
        null,
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
  /// subsequent connection. The clock steps past the resend throttle before the
  /// reopen — inside the window, re-queued subscriptions would be skipped anyway
  /// and an uncleared map would go unnoticed. The account/slot channels are
  /// pinned in the reconnect tests; this covers the rest.
  @Test
  void closeClearsEveryChannel() {
    final var clock = new TestClock();
    final var ws = websocket(clock, (_, _, _) -> {
    }, null, null);
    final var key = software.sava.core.accounts.PublicKey
        .fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
    assertTrue(ws.logsSubscribe(key, _ -> {
    }));
    assertTrue(ws.signatureSubscribe(
        "5Uf53Zoxj9qrhRxrSSzFeRxcrALLupEP686yE68fXQUR6HsM92hbhp9vSoFLRGhxb4tLNDKvqRVXSVeGn5K6nYYi", _ -> {
        }));
    assertTrue(ws.programSubscribe(key, _ -> {
    }));
    assertTrue(ws.rootSubscribe(_ -> {
    }));
    assertTrue(ws.subscribe("voteSubscribe", "voteUnsubscribe", "voteNotification",
        "vote", "", JsonIterator::readString, null, _ -> {
        }));

    // The account channel is the sacrificial one: subscribing, confirming and unsubscribing it
    // populates the two collections no subscribe alone can reach — pendingUnSubscriptions and
    // subscriptionsBySubId — while leaving every other channel's registration in place for
    // close() to clear. The account and slot clears themselves are pinned by the reconnect
    // suite's close test, whose registrations survive to its close.
    assertTrue(ws.accountSubscribe(key, _ -> {
    }));
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    assertEquals(6, socket.sentText.size());

    ws.onText(socket, java.nio.CharBuffer.wrap("""
        {"jsonrpc":"2.0","result":555,"id":2}"""), true);
    ws.onText(socket, java.nio.CharBuffer.wrap("""
        {"jsonrpc":"2.0","result":557,"id":7}"""), true);
    assertTrue(ws.accountUnsubscribe(key));
    // 4 still pending + 1 queued un-subscription + the logs subId + logs, signature, program
    // and generic registrations + the root singleton
    assertEquals(11, ws.retainedRegistrations());

    ws.close();

    // Asserted directly rather than through a reopen: onOpen now refuses to run on a closed
    // instance, so an empty afterClose.sentText would hold whether or not close() cleared
    // anything — the assertion had gone vacuous, and the teardown mutants outlived it.
    assertEquals(0, ws.retainedRegistrations(), "close() must forget every registration");

    clock.advanceMillis(TIMINGS.reConnectDelay() + 1);
    final var afterClose = new RecordingWebSocket();
    ws.onOpen(afterClose);
    assertTrue(afterClose.aborted, "a handshake completing after close is aborted");
  }

  /// close() also drops in-flight state that no reopen would surface: a pending
  /// unconfirmed subscription must not be re-sent, and a queued un-subscription
  /// must not be flushed, by a later write cycle on the dead listener.
  /// The only evidence a connection is still carrying traffic. `closed()` reports that
  /// `close()` was called, so a half open socket, or one whose subscriptions were dropped
  /// server side, reports itself open forever; only an arriving message distinguishes them.
  @Test
  void anArrivingMessageStampsTheLivenessTimestamp() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock, null, null, null)) {
      assertEquals(0L, ws.lastMessageReceivedTimestamp(), "nothing has arrived yet");
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      // opening is not receiving: a connected socket which has never delivered is exactly the
      // state this has to distinguish from a healthy one
      assertEquals(0L, ws.lastMessageReceivedTimestamp());

      clock.advanceMillis(1_000L);
      final long firstMessage = clock.currentTimeMillis();
      ws.onText(socket, java.nio.CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":555,"id":2}"""), true);
      assertEquals(firstMessage, ws.lastMessageReceivedTimestamp());

      // a pong proves the transport is alive but says nothing about the subscriptions being
      // served, which is the failure this exists to expose, so it must not count
      clock.advanceMillis(1_000L);
      ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
      assertEquals(firstMessage, ws.lastMessageReceivedTimestamp(), "a pong is not a message");

      clock.advanceMillis(1_000L);
      final long secondMessage = clock.currentTimeMillis();
      ws.onText(socket, java.nio.CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":556,"id":3}"""), true);
      assertEquals(secondMessage, ws.lastMessageReceivedTimestamp());
    }
  }

  /// The stamp is scoped to a connection, and the field outlives the socket it describes: one
  /// instance is reused across reconnects, so a stamp left over from the previous connection
  /// would answer "is this connection carrying traffic?" with another connection's evidence.
  /// A reconnect is exactly when a caller asks — the half open socket this exists to expose is
  /// what provoked the reconnect — so the leftover would be wrong at the only moment it matters.
  @Test
  void reconnectingForgetsThePreviousConnectionsTraffic() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock, null, null, null)) {
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      clock.advanceMillis(1_000L);
      final long delivered = clock.currentTimeMillis();
      ws.onText(first, java.nio.CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":555,"id":2}"""), true);
      assertEquals(delivered, ws.lastMessageReceivedTimestamp());

      clock.advanceMillis(60_000L);
      final var second = new RecordingWebSocket();
      ws.onOpen(second);
      assertEquals(0L, ws.lastMessageReceivedTimestamp(),
          "the new connection has delivered nothing, so it has no evidence to offer");

      clock.advanceMillis(1_000L);
      final long redelivered = clock.currentTimeMillis();
      ws.onText(second, java.nio.CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":556,"id":3}"""), true);
      assertEquals(redelivered, ws.lastMessageReceivedTimestamp(),
          "and it resumes stamping once it does");
    }
  }

  /// One listener serves every connection, but the state it writes describes whichever
  /// connection is current, so a socket the instance has replaced must not act on it. Dropping
  /// the reference is not enough on a real socket: `this` stays its JDK listener and its demand
  /// outlives the field, so it keeps delivering unless it is aborted.
  ///
  /// The close half is the sharpest of these. Neither `onClose` branch looks at which socket
  /// died, and the no-handler branch closes the whole instance — so a connection that expired
  /// minutes ago could tear down the one that replaced it.
  @Test
  void aSupersededSocketNeitherStampsNorClosesTheLiveConnection() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock, null, null, null)) {
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      clock.advanceMillis(1_000L);
      ws.onText(first, java.nio.CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":555,"id":2}"""), true);

      clock.advanceMillis(1_000L);
      final var second = new RecordingWebSocket();
      ws.onOpen(second);
      assertTrue(first.aborted, "a displaced socket must be aborted, not merely dropped");
      assertEquals(0L, ws.lastMessageReceivedTimestamp());

      clock.advanceMillis(1_000L);
      ws.onText(first, java.nio.CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":556,"id":3}"""), true);
      assertEquals(0L, ws.lastMessageReceivedTimestamp(),
          "a superseded socket must not vouch for the connection that replaced it");

      ws.onClose(first, 1006, "the previous connection finally noticed");
      assertFalse(ws.closed(), "a superseded socket's close must not tear down the live one");

      clock.advanceMillis(1_000L);
      final long live = clock.currentTimeMillis();
      ws.onText(second, java.nio.CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":557,"id":4}"""), true);
      assertEquals(live, ws.lastMessageReceivedTimestamp(), "the live socket still stamps");
    }
  }

  /// A ping that never left is not a write, so the failure path restores the previous write
  /// stamp and the next cycle retries instead of waiting out the whole keep-alive delay.
  ///
  /// The setup exists to isolate that one stamp. The peer is heard from immediately before the
  /// cycle, which holds the liveness clause false, so the keep-alive clause measuring our own
  /// silence is the only one that can fire — and with no time passing between the two cycles,
  /// the retry can only come from `lastWrite` having been rolled back. Without that rollback
  /// the failed ping counts as a write and the second cycle stays silent.
  @Test
  void aFailedPingRollsBackTheWriteStampSoTheKeepAliveRetries() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock, null, null, (_, _) -> {
    })) {
      final var socket = new RecordingWebSocket();
      socket.failPing = new IOException("the ping never left");
      ws.onOpen(socket);

      clock.advanceMillis(TIMINGS.keepAliveDelay() + 1);
      ws.onText(socket, java.nio.CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":555,"id":2}"""), true);

      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings, "our own silence is past the keep-alive bound");

      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(2, socket.pings, "a ping that never left is not a write");
    }
  }

  /// Stamped before the message is examined: a frame the cap rejects, or one which does not
  /// parse, is still evidence the connection delivered something.
  @Test
  void anUnparseableMessageStillCountsAsTraffic() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock, null, null, null)) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      clock.advanceMillis(5_000L);
      final long arrived = clock.currentTimeMillis();
      // Not throwing IS part of the contract: a RuntimeException escaping a listener callback
      // makes the JDK abort the connection, so a malformed frame would kill the transport.
      assertDoesNotThrow(() -> ws.onText(socket, java.nio.CharBuffer.wrap("not json at all"), true));
      assertEquals(arrived, ws.lastMessageReceivedTimestamp(),
          "liveness asks whether the connection delivered, not whether the content was valid");
    }
  }

  @Test
  void closeDropsPendingAndQueuedWrites() {
    final var clock = new TestClock();
    final var ws = websocket(clock, (_, _, _) -> {
    }, null, null);
    final var key = software.sava.core.accounts.PublicKey
        .fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
    // a confirmed subscription with its un-subscription queued...
    assertTrue(ws.logsSubscribe(key, _ -> {
    }));
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    ws.onText(socket, java.nio.CharBuffer.wrap("""
        {"jsonrpc":"2.0","result":555,"id":2}"""), true);
    assertTrue(ws.logsUnsubscribe(key));
    // ...and a pending unconfirmed one
    assertTrue(ws.rootSubscribe(_ -> {
    }));
    final int sent = socket.sentText.size();

    ws.close();

    clock.advanceMillis(TIMINGS.reConnectDelay() + 1);
    ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
    assertEquals(sent, socket.sentText.size(),
        "nothing pending or queued should survive close(): " + socket.sentText.subList(sent, socket.sentText.size()));
  }

  /// After close() a notification quoting a previously confirmed subscription id
  /// must not reach the consumer — the id mapping does not outlive the client.
  @Test
  void closeForgetsActiveSubscriptionIds() {
    final var ws = websocket(new TestClock(), (_, _, _) -> {
    }, null, null);
    final var key = software.sava.core.accounts.PublicKey
        .fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
    final var received = new ArrayList<Object>();
    assertTrue(ws.accountSubscribe(key, received::add));
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    ws.onText(socket, java.nio.CharBuffer.wrap("""
        {"jsonrpc":"2.0","result":999,"id":2}"""), true);

    ws.close();

    ws.onText(socket, java.nio.CharBuffer.wrap("""
        {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":1},"value":{"data":["","base64"],"executable":false,"lamports":1,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":0}},"subscription":999}}"""), true);
    assertTrue(received.isEmpty(), "a subscription id must not dispatch after close()");
  }

  @Test
  void onCloseWithoutAHandlerClosesTheWebsocket() {
    final var ws = websocket(new TestClock(), null, null, null);
    // the same socket throughout: the JDK reports the close of the connection that died, and
    // a close reported for some other socket is one this instance has already replaced
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    ws.onClose(socket, 1006, "connection dropped");
    assertTrue(ws.closed());

    // and the blank-reason logging branch behaves the same
    final var blank = websocket(new TestClock(), null, null, null);
    final var blankSocket = new RecordingWebSocket();
    blank.onOpen(blankSocket);
    blank.onClose(blankSocket, 1006, "");
    assertTrue(blank.closed());
  }

  @Test
  void onCloseWithAHandlerDelegatesAndLeavesTheDecision() {
    final var seen = new AtomicReference<String>();
    try (final var ws = websocket(new TestClock(), (websocket, code, reason) -> seen.set(code + ":" + reason), null, null)) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onClose(socket, 4242, "bye");
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
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      // queued after the open, so the pong's write cycle — not the open — is the first sender
      assertTrue(ws.rootSubscribe(_ -> {
      }));
      assertEquals(0, socket.sentText.size());
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

  /// A failed ping rolls the ping window back so the next check retries instead of treating the
  /// failed ping as an ask that was made.
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
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings);
      assertSame(boom, seen.get());

      // Without advancing the clock again: a ping that never left is neither a write nor an ask,
      // so the rollback re-arms the window and the next check retries. That is what lets a run
      // of failures accumulate quickly enough for a caller to act on.
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(2, socket.pings, "a failed ping must not count as an ask");
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
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(), executor, null,
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
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(), executor, null,
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

  /// The loop interior, driven deterministically through the checkCycle seam: an
  /// unconfirmed subscription re-sends only once its retry window passes. This
  /// interior was previously reachable only by threads racing the test scheduler
  /// (the run-loop flip-insurance family in the ws triage README).
  @Test
  void checkCycleResendsAnUnconfirmedSubscription() throws InterruptedException {
    final var clock = new TestClock();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null,
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, new RecordingExecutor(), null,
        null, (_, _, _) -> {
        }, (_, _) -> {
        }, null, null
    );
    assertTrue(ws.rootSubscribe(_ -> {
    }));
    final var socket = new RecordingWebSocket();
    // The first send FAILS: a successfully sent request stays gated until the server answers —
    // JSON-RPC ids correlate responses, they do not deduplicate calls, so re-sending a merely
    // slow request would create a second, orphaned server subscription. Only a failed send is
    // safely retryable, and the retry is paced by the resend window.
    socket.failText = new java.io.IOException("the frame never left");
    ws.onOpen(socket);
    assertEquals(1, socket.sentText.size(), "opening attempts the pending subscription once");
    socket.failText = null;

    ws.checkCycle(0L);
    assertEquals(1, socket.sentText.size(), "inside the retry window the cycle must not re-send");

    clock.advanceMillis(TIMINGS.reConnectDelay() + 1);
    ws.checkCycle(0L);
    assertEquals(2, socket.sentText.size(), "a failed subscription send retries after the window");
    assertTrue(socket.sentText.get(1).contains("rootSubscribe"), socket.sentText.toString());
    ws.close();
  }

  /// Before any connection there is nothing to write to: a cycle with no websocket
  /// is a no-op that must not dereference the absent socket, and it leaves the
  /// subscription pending for the eventual onOpen flush.
  @Test
  void checkCycleWithoutASocketLeavesTheSubscriptionPending() throws InterruptedException {
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null,
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(), new RecordingExecutor(), null,
        null, (_, _, _) -> {
        }, (_, _) -> {
        }, null, null
    );
    assertTrue(ws.rootSubscribe(_ -> {
    }));
    ws.checkCycle(0L); // an NPE here means the absent socket was dereferenced

    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    assertEquals(1, socket.sentText.size(), "the subscription must still be pending after a socketless cycle");
    ws.close();
  }

  /// A RuntimeException escaping the loop body is the loop's failure funnel: it
  /// must close the websocket AND say so. The ERROR record is asserted through
  /// System.Logger's JUL backend, so a silent funnel cannot pass — failures are
  /// never silent.
  @Test
  void checkLoopClosesAndLogsAnUnhandledException() {
    final var executor = new RecordingExecutor();
    final var clock = new TestClock();
    final var timings = new Timings(60_000, 60_000, 0); // zero check delay: the await never parks
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null,
        timings, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock, executor, null,
        null, (_, _, _) -> {
        }, (_, _) -> {
        }, null, null
    );
    assertTrue(ws.rootSubscribe(_ -> {
    }));
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    clock.advanceMillis(timings.reConnectDelay() + 1);
    // The ping, not the text send: a throwing text send is contained by the outbound chain and
    // reported through onSendTextError, so it can no longer take the loop down. The advance has
    // already opened the ping gate.
    socket.throwPing = new IllegalStateException("send blew up");

    final var records = new ArrayList<java.util.logging.LogRecord>();
    final var handler = new java.util.logging.Handler() {
      @Override
      public void publish(final java.util.logging.LogRecord record) {
        records.add(record);
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    final var julLogger = java.util.logging.Logger.getLogger(SolanaJsonRpcWebsocket.class.getName());
    final boolean parentHandlers = julLogger.getUseParentHandlers();
    julLogger.setUseParentHandlers(false);
    julLogger.addHandler(handler);
    try {
      executor.tasks.getFirst().run();
    } finally {
      julLogger.removeHandler(handler);
      julLogger.setUseParentHandlers(parentHandlers);
    }

    // one frame, not two: the successful open-time send stays gated until a response, so the
    // advance past the resend window queues nothing further before the poisoned ping fires
    assertEquals(1, socket.sentText.size(), "the successful send stays gated until a response");
    assertTrue(ws.closed(), "an unhandled loop exception closes the websocket");
    assertTrue(records.stream().anyMatch(record -> record.getThrown() == socket.throwPing),
        "the failure funnel must log the exception, not swallow it");
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
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings);
    }
  }

  /// "Once closed, this WebSocket is no longer usable": a subscribe accepted after close would
  /// fill maps nothing will ever flush, and returning true for it is an affirmative lie.
  @Test
  void subscribingAfterCloseReturnsFalse() {
    final var ws = websocket(new TestClock(), null, null, null);
    ws.close();
    final var key = software.sava.core.accounts.PublicKey
        .fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
    assertFalse(ws.accountSubscribe(key, _ -> {
    }));
    assertFalse(ws.logsSubscribe(key, _ -> {
    }));
    assertFalse(ws.slotSubscribe(_ -> {
    }));
    assertFalse(ws.rootSubscribe(_ -> {
    }));
    assertFalse(ws.subscribe("voteSubscribe", "voteUnsubscribe", "voteNotification",
        "vote", "", JsonIterator::readString, null, _ -> {
        }));
  }

  /// close() can land between connect() and its handshake completing. The instance must not be
  /// rebuilt, and the socket that just opened belongs to nobody — leak it and it stays
  /// connected with a listener that ignores it.
  @Test
  void aHandshakeCompletingAfterCloseIsAborted() {
    final var opened = new java.util.concurrent.atomic.AtomicBoolean(false);
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(),
        new RecordingExecutor(),
        null,
        _ -> opened.set(true),
        null,
        (_, _) -> {
        },
        null,
        null
    );
    ws.close();
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    assertTrue(socket.aborted, "a handshake completing after close must be aborted");
    assertTrue(socket.sentText.isEmpty(), "nothing may be rebuilt on a closed instance");
    assertFalse(opened.get(), "the consumer must not be told a closed instance connected");
  }

  /// The check loop dying is the one terminal transition the instance makes on its own, and it
  /// must reach the consumer's error seam — their reconnect policy lives there, and a close()
  /// with no notification bypasses it invisibly. A zero check delay keeps the loop from
  /// parking, and a socket that throws from sendText is the poison.
  @Test
  @org.junit.jupiter.api.Timeout(30)
  void aCheckLoopFailureReachesOnErrorBeforeClosing() {
    final var seen = new AtomicReference<Throwable>();
    final var clock = new TestClock();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        new Timings(60_000, 1, 0),
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        clock,
        new RecordingExecutor(),
        null,
        null,
        null,
        (_, error) -> seen.set(error),
        null,
        null
    );
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    // The poison is the ping: a throwing text send is contained by the outbound chain and
    // reported through onSendTextError rather than killing the loop, so the ping — sent
    // directly, outside the chain — is what can still take the loop down.
    final var boom = new IllegalStateException("socket poisoned");
    socket.throwPing = boom;
    clock.advanceMillis(2L); // past the 1ms ping delay, so the first cycle pings

    ws.run();

    assertSame(boom, seen.get(), "the check loop's failure must reach the consumer's error seam");
    assertTrue(ws.closed(), "and the instance still closes after notifying");
  }

  /// The transaction signature is the one caller-supplied string that reaches the wire inside a
  /// frame — everything else is a key, an enum, or documented-raw JSON. A quote would splice
  /// into the frame, and anything outside the alphabet is at best a typo that would subscribe
  /// to nothing.
  ///
  /// Only frame-splicing is rejected client side, on live evidence (api.mainnet-beta.solana.com,
  /// 2026-08-09): a well-formed frame carrying a semantically invalid signature returns -32602
  /// WITH the request id — which the rejection path correlates, retires and reports — so
  /// client-side base58/length validation duplicated the server's authoritative check, and its
  /// old rationale ("the error cannot be correlated") was measured false. A splicing character
  /// is different in kind: it breaks the frame itself, the server answers -32700 with
  /// "id":null, and the request is left gated with nothing to correlate its failure.
  @Test
  void signatureSubscribeRejectsOnlyFrameSplicingCharacters() {
    try (final var ws = websocket(new TestClock(), null, null, null)) {
      for (final var bad : new String[]{null, "", "sig\"nature", "sig\\nature", "sig\nnature"}) {
        assertThrows(IllegalArgumentException.class, () -> ws.signatureSubscribe(bad, _ -> {
        }), String.valueOf(bad));
      }
      // semantically wrong but frame-safe is the server's jurisdiction: queued, sent, and
      // retired by the correlated -32602 the probe demonstrated
      assertTrue(ws.signatureSubscribe("sig0nature", _ -> {
      }), "an invalid-but-frame-safe signature is queued; the server's rejection is terminal");
    }
  }

  /// The failure funnel contains a throwing handler: the instance still closes, and the
  /// handler's own exception does not replace the loop's.
  @Test
  @org.junit.jupiter.api.Timeout(30)
  void aThrowingOnErrorHandlerDoesNotPreventTheClose() {
    final var clock = new TestClock();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        new Timings(60_000, 1, 0),
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        clock,
        new RecordingExecutor(),
        null,
        null,
        null,
        (_, _) -> {
          throw new IllegalStateException("handler is broken too");
        },
        null,
        null
    );
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    socket.throwPing = new IllegalStateException("socket poisoned");
    clock.advanceMillis(2L); // past the 1ms ping delay, so the first cycle pings

    assertDoesNotThrow(ws::run, "a broken handler must not escape the loop's containment");
    assertTrue(ws.closed());
  }

  /// Pacing rides the monotonic reading, the consumer-facing stamp rides the wall clock, and an
  /// NTP step backwards must only move the second. Before the split, a step of -10 minutes
  /// silently disabled ping detection, keep-alive and resend for ten minutes — on exactly the
  /// half-open connection the liveness feature exists to expose.
  @Test
  void aWallClockStepBackwardsDoesNotDisablePacing() {
    // Two independent readings, unlike TestClock, whose wall reading derives from its nanos.
    final var nanos = new java.util.concurrent.atomic.AtomicLong(9_876_543_210_000_000L);
    final var wallMillis = new java.util.concurrent.atomic.AtomicLong(1_754_000_000_000L);
    final var clock = new NanoClock() {
      @Override
      public long nanoTime() {
        return nanos.get();
      }

      @Override
      public long currentTimeMillis() {
        return wallMillis.get();
      }

      @Override
      public void sleep(final long millis) {
        nanos.addAndGet(millis * 1_000_000L);
      }
    };
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        clock,
        new RecordingExecutor(),
        null,
        null,
        null,
        (_, _) -> {
        },
        null,
        null
    );
    try (ws) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, java.nio.CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":555,"id":2}"""), true);
      assertEquals(wallMillis.get(), ws.lastMessageReceivedTimestamp(),
          "the consumer-facing stamp is epoch millis by contract");

      // the NTP step: wall lurches back ten minutes, real time marches on
      wallMillis.addAndGet(-600_000L);
      nanos.addAndGet((TIMINGS.pingDelay() + 1) * 1_000_000L);

      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings,
          "the peer has been silent past the ping delay of real time; the wall step must not hide it");
    }
  }

  /// Politeness is bounded: sendClose closes only the output, and a silent peer never answers,
  /// retaining the transport, this listener, and the reassembly buffer indefinitely. The abort
  /// scheduled behind the close frame is what makes release a property of close() rather than
  /// of the peer's cooperation.
  @Test
  void closeAbortsTheSocketAfterTheGracePeriod() {
    final var scheduler = new RecordingScheduler();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null,
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        }, (_, _) -> {
        }, null, null);
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    ws.close();
    assertFalse(socket.closeReasons.isEmpty(), "the polite close frame goes first");
    assertFalse(socket.aborted, "the grace period belongs to the peer");
    assertEquals(1, scheduler.deferred.size());
    assertEquals(SolanaJsonRpcWebsocket.CLOSE_GRACE_MILLIS, scheduler.deferred.getFirst().delay());

    scheduler.deferred.getFirst().task().run();
    assertTrue(socket.aborted, "a peer that never replies does not get to retain the transport");

    // Output and input close independently, and it is the INPUT that retains the transport: an
    // output-closed socket gets no second close frame, but it gets the watchdog regardless.
    final var halfClosed = new RecordingWebSocket();
    final var scheduler2 = new RecordingScheduler();
    final var ws2 = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null,
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), scheduler2, null, (_, _, _) -> {
        }, (_, _) -> {
        }, null, null);
    ws2.onOpen(halfClosed);
    halfClosed.outputClosed = true;
    ws2.close();
    assertTrue(halfClosed.closeReasons.isEmpty(), "no close frame on an already closed output");
    assertEquals(1, scheduler2.deferred.size(), "the watchdog is gated on release, not on output state");
    scheduler2.deferred.getFirst().task().run();
    assertTrue(halfClosed.aborted);
  }

  /// Fragments are peer contact, not messages: a peer trickling fragments of one document
  /// forever is provably alive while never having delivered anything, and the public message
  /// evidence must not report it healthy.
  @Test
  void aFragmentIsNotAMessage() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock, null, null, null)) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      clock.advanceMillis(1_000L);
      ws.onText(socket, java.nio.CharBuffer.wrap("{\"jsonrpc\":"), false);
      assertEquals(0L, ws.lastMessageReceivedTimestamp(),
          "a fragment proves the transport, not the subscriptions");

      clock.advanceMillis(1_000L);
      final long completed = clock.currentTimeMillis();
      ws.onText(socket, java.nio.CharBuffer.wrap("\"2.0\",\"result\":555,\"id\":2}"), true);
      assertEquals(completed, ws.lastMessageReceivedTimestamp(),
          "the terminal frame completes a message, and that is what the evidence counts");
    }
  }

  /// The terminal state must not depend on the consumer returning normally: the server has
  /// already cancelled its side, so a throwing consumer previously left the completed signature
  /// registered — replayed every reconnect — and its key blocked from resubscription.
  @Test
  void aThrowingSignatureConsumerDoesNotDefeatTerminalCleanup() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock, null, null, null)) {
      final var sig = "2EBVM6cB8vAAD93Ktr6Vd8p67XPbQzCJX47MpReuiCXJAtcjaxpvWpcg9Ege1Nr5Tk3a2GFrByT7WPBjdsTycY9b";
      assertTrue(ws.signatureSubscribe(sig, _ -> {
        throw new IllegalStateException("consumer blew up on the terminal notification");
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, java.nio.CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":24006,"id":2}"""), true);

      assertDoesNotThrow(() -> ws.onText(socket, java.nio.CharBuffer.wrap("""
          {"jsonrpc":"2.0","method":"signatureNotification","params":{"result":{"context":{"slot":2},"value":{"err":null}},"subscription":24006}}"""), true));

      assertEquals(0, ws.retainedRegistrations(), "the completed signature must be gone regardless");
      assertTrue(ws.signatureSubscribe(sig, _ -> {
      }), "and its key resubscribable — including from inside the terminal callback");
    }
  }

  /// The generic subscribe interpolates its method names into the frame, so they must not be
  /// able to splice into it.
  @Test
  void genericMethodNamesAreValidated() {
    try (final var ws = websocket(new TestClock(), null, null, null)) {
      for (final var bad : new String[]{null, "", "vote\"Subscribe", "vote\\Subscribe", "vote\nSubscribe"}) {
        assertThrows(IllegalArgumentException.class, () -> ws.subscribe(bad, "voteUnsubscribe",
            "voteNotification", "vote", "", JsonIterator::readString, null, _ -> {
            }), String.valueOf(bad));
      }
    }
  }

  /// F6: the attempt is reserved before the builder runs, so an onOpen handler delivered
  /// synchronously by a wrapping builder can re-enter connect() and JOIN the in-flight attempt
  /// — unreserved, the reentry found nothing in flight, aborted the socket it was being told
  /// about, and started a second handshake whose authority the outer return then overwrote.
  @Test
  void aSynchronousOnOpenReentrantConnectJoinsTheAttempt() {
    final var clock = new TestClock();
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>(), socket);
    webSocketBuilder.invokeOnOpen = true;
    final var reentrant = new AtomicReference<java.util.concurrent.CompletableFuture<?>>();
    try (final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(java.time.Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock,
        new RecordingExecutor(), new RecordingScheduler(),
        w -> reentrant.set(w.connect()), (_, _, _) -> {
        }, null, null, null)) {
      final var outer = ws.connect();
      assertNotNull(outer);
      assertEquals(1, webSocketBuilder.builds, "the re-entrant connect must join, not stack a second handshake");
      assertNotNull(reentrant.get(), "the re-entrant caller receives the in-flight attempt");
      assertFalse(socket.aborted, "the socket being adopted must not be aborted by its own onOpen");
      assertTrue(outer.toCompletableFuture().isDone());
      assertTrue(reentrant.get().isDone());

      // the adopted connection serves
      assertTrue(ws.slotSubscribe(_ -> {
      }));
      ws.onPong(socket, java.nio.ByteBuffer.wrap(new byte[0]));
      assertTrue(socket.sentText.stream().anyMatch(m -> m.contains("slotSubscribe")),
          "the adopted connection must carry traffic: " + socket.sentText);
    }
  }

  /// F7: close() commits the local teardown before attempting transport politeness, so a
  /// watchdog schedule rejected by an already-shut-down injected scheduler degrades to an
  /// immediate abort instead of skipping the registry clears and the loop signal.
  @Test
  void closeCommitsLocalTeardownWhenThePoliteWorkFails() {
    final var deadScheduler = new java.util.concurrent.ScheduledThreadPoolExecutor(1);
    deadScheduler.shutdown();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null,
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), deadScheduler, null, (_, _, _) -> {
        }, null, null, null);
    assertTrue(ws.slotSubscribe(_ -> {
    }));
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    assertDoesNotThrow(ws::close);
    assertTrue(ws.closed());
    assertEquals(0, ws.retainedRegistrations(), "teardown must be committed despite the rejected watchdog");
    assertTrue(socket.aborted, "politeness failing degrades to an immediate abort");
  }

  /// F7: the default (no-scheduler) deferred connect fires through its cancellable token — the
  /// JDK delayer is handed only the token, and the build chain hangs off it.
  @Test
  void aDefaultDeferredConnectFiresThroughItsToken() {
    final var clock = new TestClock();
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>(), socket);
    final var quick = new Timings(25, 60_000, 60_000);
    try (final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(java.time.Duration.ofMillis(1_000)),
        quick, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock,
        new RecordingExecutor(), null, null, (_, _, _) -> {
        }, null, null, null)) {
      assertNotNull(ws.connect());
      assertEquals(1, webSocketBuilder.builds);
      final var deferred = ws.connect(); // inside the throttle window: deferred on the real delayer
      assertNotNull(deferred);
      assertDoesNotThrow(() -> deferred.toCompletableFuture()
          .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).join());
      assertEquals(2, webSocketBuilder.builds, "the deferred attempt fires when the token completes");
    }
  }

  /// F12: a socket that throws synchronously from sendText — permitted of a wrapping builder's
  /// socket, though the JDK fails the future instead — is routed into the same failure seam, so
  /// onSendTextError fires rather than the failure being contained silently by the chain.
  @Test
  void aSynchronousSendTextThrowReachesOnSendTextError() {
    final var sendErrors = new ArrayList<Throwable>();
    try (final var ws = websocket(new TestClock(), null, (_, ex) -> sendErrors.add(ex), null)) {
      assertTrue(ws.slotSubscribe(_ -> {
      }));
      final var socket = new RecordingWebSocket();
      socket.throwText = new IllegalStateException("sync throw");
      ws.onOpen(socket);
      assertEquals(1, sendErrors.size(), "a thrown send is a failed send, and failed sends are reported");
      assertEquals("sync throw", sendErrors.getFirst().getMessage());
    }
  }
}
