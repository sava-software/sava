package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/// Connection lifecycle beyond reconnects: accessors, the close frame and its
/// bookkeeping, the onClose/onError delegation split, pong-driven write cycles,
/// and the sendText/sendPing failure callbacks.
@ExtendWith(QuietWsLogging.class)
final class SolanaJsonRpcWebsocketLifecycleTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);

  /// A valid scheduler is allowed to start an enabled task before schedule() returns its handle.
  /// Waiting for buildAsync to begin makes that ordering deterministic without sleeping.
  private static final class BuildBeforeScheduleReturns extends ScheduledThreadPoolExecutor {

    private final CountDownLatch buildEntered;
    private final AtomicBoolean firstSchedule = new AtomicBoolean(true);
    private volatile ScheduledFuture<?> connectHandle;

    private BuildBeforeScheduleReturns(final CountDownLatch buildEntered) {
      super(1);
      this.buildEntered = buildEntered;
    }

    @Override
    public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
      final var handle = super.schedule(command, delay, unit);
      if (firstSchedule.compareAndSet(true, false)) {
        connectHandle = handle;
        try {
          if (!buildEntered.await(500L, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("scheduled connect did not enter buildAsync");
          }
        } catch (final InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted while awaiting scheduled connect", ex);
        }
      }
      return handle;
    }
  }

  /// Holds the scheduled task after it has claimed its attempt, so cancelling the scheduler
  /// handle before schedule() returns would interrupt a live builder operation.
  private static final class BlockingBuildAsyncBuilder implements WebSocket.Builder {

    private final RecordingWebSocket socket;
    private final CountDownLatch buildEntered;
    private final CountDownLatch releaseBuild;
    private final AtomicBoolean interrupted = new AtomicBoolean();

    private BlockingBuildAsyncBuilder(final RecordingWebSocket socket,
                                      final CountDownLatch buildEntered,
                                      final CountDownLatch releaseBuild) {
      this.socket = socket;
      this.buildEntered = buildEntered;
      this.releaseBuild = releaseBuild;
    }

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
      buildEntered.countDown();
      try {
        if (!releaseBuild.await(500L, TimeUnit.MILLISECONDS)) {
          return CompletableFuture.failedFuture(
              new IllegalStateException("test did not release the scheduled builder"));
        }
      } catch (final InterruptedException ex) {
        interrupted.set(true);
        Thread.currentThread().interrupt();
        return CompletableFuture.failedFuture(ex);
      }
      listener.onOpen(socket);
      return CompletableFuture.completedFuture(socket);
    }
  }

  private static final class PoisonClock implements NanoClock {

    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    private void poison(final RuntimeException failure) {
      this.failure.set(failure);
    }

    @Override
    public long nanoTime() {
      final var failure = this.failure.get();
      if (failure != null) {
        throw failure;
      }
      return 1_234_567_890_123_456L;
    }

    @Override
    public void sleep(final long millis) {
    }
  }

  /// Delivers the listener's open synchronously, but leaves the builder future unsettled. This
  /// is the narrow window where a socket is current while close() still has an attempt to cancel,
  /// so cancellation ordering is observable without a racing thread or a sleep.
  private static final class SynchronousOpenPendingBuilder implements WebSocket.Builder {

    private final RecordingWebSocket socket;
    private final CompletableFuture<WebSocket> completion;
    private boolean invokeOnOpen = true;

    private SynchronousOpenPendingBuilder(final RecordingWebSocket socket) {
      this(socket, new CompletableFuture<>());
    }

    private SynchronousOpenPendingBuilder(final RecordingWebSocket socket,
                                          final CompletableFuture<WebSocket> completion) {
      this.socket = socket;
      this.completion = completion;
    }

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
      if (invokeOnOpen) {
        listener.onOpen(socket);
      }
      return completion;
    }
  }

  /// Each build opens its socket synchronously. The first future stays pending while the second
  /// settles, exposing whether retirement released the old single-flight authority before a
  /// lifecycle handler re-entered connect().
  private static final class SynchronousOpenSequenceBuilder implements WebSocket.Builder {

    private final List<RecordingWebSocket> sockets;
    private final List<CompletableFuture<WebSocket>> completions;
    private int builds;
    private Throwable firstBuildError;
    private RuntimeException firstBuildThrow;
    private RuntimeException secondBuildThrow;
    private Error firstBuildFatal;
    private boolean firstBuildReturnsNull;
    private boolean firstBuildOpens = true;
    private boolean insideBuild;

    private SynchronousOpenSequenceBuilder(final List<RecordingWebSocket> sockets,
                                           final List<CompletableFuture<WebSocket>> completions) {
      this.sockets = sockets;
      this.completions = completions;
    }

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
      assertFalse(insideBuild, "one mutable WebSocket.Builder must not be entered re-entrantly");
      insideBuild = true;
      try {
        final int attempt = builds++;
        if (attempt == 1 && secondBuildThrow != null) {
          throw secondBuildThrow;
        }
        if (attempt != 0 || firstBuildOpens) {
          listener.onOpen(sockets.get(attempt));
        }
        if (attempt == 0 && firstBuildError != null) {
          listener.onError(sockets.get(attempt), firstBuildError);
        }
        if (attempt == 0 && firstBuildThrow != null) {
          throw firstBuildThrow;
        }
        if (attempt == 0 && firstBuildFatal != null) {
          throw firstBuildFatal;
        }
        if (attempt == 0 && firstBuildReturnsNull) {
          return null;
        }
        return completions.get(attempt);
      } finally {
        insideBuild = false;
      }
    }
  }

  /// Returns caller-controlled futures in order and exposes each attempt listener. This keeps
  /// future completion, listener adoption and terminal retirement independently stepable: the
  /// JDK permits the build future and listener executor to settle on different threads.
  private static final class FutureSequenceBuilder implements WebSocket.Builder {

    private final List<CompletableFuture<WebSocket>> completions;
    private final List<WebSocket.Listener> listeners = new ArrayList<>();
    private int builds;

    private FutureSequenceBuilder(final List<CompletableFuture<WebSocket>> completions) {
      this.completions = completions;
    }

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
      listeners.add(listener);
      return completions.get(builds++);
    }
  }

  /// Models an upgrade which is already past its cancellable phase. close() still calls cancel,
  /// but ownership of a later successful socket remains the listener's obligation.
  private static final class UncancellableWebSocketFuture extends CompletableFuture<WebSocket> {

    @Override
    public boolean cancel(final boolean mayInterruptIfRunning) {
      return false;
    }
  }

  /// Runs one deterministic action after an already-completed future's ownership observer but
  /// before whenComplete returns to the caller which will install that future.
  private static final class AfterOwnershipFuture extends CompletableFuture<WebSocket> {

    private Runnable afterOwnership;
    private int cancelCalls;

    @Override
    public CompletableFuture<WebSocket> whenComplete(
        final BiConsumer<? super WebSocket, ? super Throwable> action) {
      return super.whenComplete((webSocket, failure) -> {
        action.accept(webSocket, failure);
        afterOwnership.run();
      });
    }

    @Override
    public boolean cancel(final boolean mayInterruptIfRunning) {
      ++cancelCalls;
      return super.cancel(mayInterruptIfRunning);
    }
  }

  /// Models an uncancellable build which completes exceptionally immediately after a losing
  /// installer observes it as incomplete. Both state methods carry the seam so the test remains
  /// faithful across the safe terminality-first implementation and the former exceptional-first
  /// implementation: only the latter proceeds to getNow after the outcome has changed.
  private static final class ExceptionCompletingLosingFuture extends CompletableFuture<WebSocket> {

    private final Throwable failure = new IOException("build failed while close won installation");
    private Runnable afterOwnershipRegistration;

    @Override
    public CompletableFuture<WebSocket> whenComplete(
        final BiConsumer<? super WebSocket, ? super Throwable> action) {
      final var dependent = super.whenComplete(action);
      afterOwnershipRegistration.run();
      return dependent;
    }

    @Override
    public boolean cancel(final boolean mayInterruptIfRunning) {
      return false;
    }

    @Override
    public boolean isDone() {
      final boolean done = super.isDone();
      if (!done) {
        completeExceptionally(failure);
      }
      return done;
    }

    @Override
    public boolean isCompletedExceptionally() {
      final boolean exceptional = super.isCompletedExceptionally();
      if (!exceptional) {
        completeExceptionally(failure);
      }
      return exceptional;
    }
  }

  private static SolanaJsonRpcWebsocket websocket(final TestClock clock,
                                                  final SolanaRpcWebsocket.OnClose onClose,
                                                  final BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError,
                                                  final BiConsumer<SolanaRpcWebsocket, Throwable> onPingError) {
    return websocket(clock, onClose, (_, _) -> {
    }, onSendTextError, onPingError);
  }

  private static SolanaJsonRpcWebsocket websocket(final TestClock clock,
                                                  final SolanaRpcWebsocket.OnClose onClose,
                                                  final BiConsumer<SolanaRpcWebsocket, Throwable> onError,
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
        onError,
        onSendTextError,
        onPingError
    );
  }

  private static boolean lifecycleLockHeldByCurrentThread(final SolanaJsonRpcWebsocket ws) {
    // Package-private access, per house rule: widen visibility rather than setAccessible.
    return ws.lock.isHeldByCurrentThread();
  }

  private static void assertLifecycleLockReleasedFromAnotherThread(
      final SolanaJsonRpcWebsocket ws) throws InterruptedException {
    final var checked = new CountDownLatch(1);
    final var acquired = new AtomicBoolean();
    final var checker = new Thread(() -> {
      final boolean locked = ws.lock.tryLock();
      acquired.set(locked);
      if (locked) {
        ws.lock.unlock();
      }
      checked.countDown();
    }, "websocket-lifecycle-lock-check");
    checker.setDaemon(true);
    checker.start();

    assertTrue(checked.await(500L, TimeUnit.MILLISECONDS),
        "the independent lock checker must finish within its deterministic bound");
    assertTrue(acquired.get(),
        "the lifecycle lock must be acquirable by another thread after the operation returns");
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

  /// The keep-alive path has the same transport semantics as the liveness path: if its Ping
  /// cannot be written, the connection is aborted and reported rather than retried forever.
  /// Fresh peer contact holds the liveness clause false so this isolates the keep-alive branch.
  @Test
  void aFailedKeepAlivePingAbortsTheTransport() {
    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, null, (_, error) -> errors.add(error), null, (_, _) -> {
    })) {
      final var socket = new RecordingWebSocket();
      final var failure = new IOException("the ping never left");
      socket.failPing = failure;
      ws.onOpen(socket);

      clock.advanceMillis(TIMINGS.keepAliveDelay() + 1);
      ws.onText(socket, java.nio.CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":555,"id":2}"""), true);

      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings, "our own silence is past the keep-alive bound");
      assertTrue(socket.aborted);
      assertEquals(java.util.List.of(failure), errors);
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
      assertTrue(socket.aborted, "the dead transport is retired before policy runs");
    }
  }

  private static void terminalCallbackRetiresAPendingAdoptedAttempt(final boolean closeNotice) {
    final var first = new RecordingWebSocket();
    final var successor = new RecordingWebSocket();
    final var pendingBuild = new CompletableFuture<WebSocket>();
    final var builder = new SynchronousOpenSequenceBuilder(
        List.of(first, successor),
        List.of(pendingBuild, CompletableFuture.completedFuture(successor))
    );
    final var retiredBeforePolicy = new AtomicReference<Boolean>();
    final var retry = new AtomicReference<CompletableFuture<?>>();
    final Consumer<SolanaRpcWebsocket> reconnect = websocket -> {
      retiredBeforePolicy.set(
          first.aborted
              && pendingBuild.isCancelled()
              && websocket.lastMessageReceivedTimestamp() == 0L
      );
      retry.set(websocket.connect());
    };
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder.connectTimeout(Duration.ofMillis(1_000)),
        new Timings(0, 60_000, 60_000),
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), null, null,
        (websocket, _, _) -> reconnect.accept(websocket),
        (websocket, _) -> reconnect.accept(websocket),
        null, null
    );
    try (ws) {
      final var initial = ws.connect();
      assertNotNull(initial);
      assertEquals(1, builder.builds);

      if (closeNotice) {
        ws.onClose(first, 4242, "retire");
      } else {
        ws.onError(first, new IOException("retire"));
      }

      assertEquals(Boolean.TRUE, retiredBeforePolicy.get(),
          "the socket and its pending build must be retired before user policy");
      assertTrue(initial.isCompletedExceptionally(),
          "retirement must release callers waiting on the old attempt");
      assertEquals(2, builder.builds,
          "the re-entrant reconnect must start a successor rather than join the old attempt");
      final var reconnectAttempt = retry.get();
      assertNotNull(reconnectAttempt);
      // Never join an attempt before proving it settled: a regression which accidentally
      // rejoins the retired bridge is a finite assertion failure, not a mutation-test timeout.
      assertTrue(reconnectAttempt.isDone(), "the synchronous successor attempt must be settled");
      assertSame(successor, reconnectAttempt.getNow(null));
      assertAll(
          () -> assertFalse(successor.aborted),
          () -> assertFalse(ws.closed(), "custom lifecycle policy keeps the reusable wrapper alive")
      );
    }
  }

  @Test
  void onCloseRetiresAPendingAdoptedAttemptBeforeReconnecting() {
    terminalCallbackRetiresAPendingAdoptedAttempt(true);
  }

  @Test
  void onErrorRetiresAPendingAdoptedAttemptBeforeReconnecting() {
    terminalCallbackRetiresAPendingAdoptedAttempt(false);
  }

  /// The default listener action is close(), but invoking it re-entrantly from the listener's
  /// locked decision must not undo close()'s callback boundary. Cancelling the attempt settles
  /// caller copies synchronously; a callback running under this lock can deadlock by waiting for
  /// another user thread which is entering any subscribe/unsubscribe operation.
  @Test
  void defaultOnCloseSettlesConnectCopiesAfterReleasingTheLifecycleLock() {
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new SynchronousOpenPendingBuilder(socket);
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), new RecordingScheduler(), null, null, null, null, null);
    final var connected = ws.connect();
    assertNotNull(connected);
    final var callbackHeldLock = new AtomicReference<Boolean>();
    connected.whenComplete((_, _) -> callbackHeldLock.set(lifecycleLockHeldByCurrentThread(ws)));

    ws.onClose(socket, WebSocket.NORMAL_CLOSURE, "peer close");

    assertEquals(Boolean.FALSE, callbackHeldLock.get(),
        "connect completion callbacks must run after the final listener lock hold is released");
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

  /// A failed Ping send remains observable through its specific callback, but it is also a
  /// terminal transport error. Both user callbacks run after the lifecycle lock is released.
  /// The ordinary error policy runs first, but even a re-entrant close cannot suppress the
  /// already-authorized Ping-send observation.
  @Test
  void pingFailureFeedsBothHandlersOffLockAndAborts() {
    final var events = new ArrayList<String>();
    final var clock = new TestClock();
    try (final var ws = websocket(clock, (_, _, _) -> {
    }, (webSocket, error) -> {
      assertFalse(((SolanaJsonRpcWebsocket) webSocket).lock.isHeldByCurrentThread());
      events.add("error:" + error.getMessage());
      webSocket.close();
    }, null, (webSocket, error) -> {
      assertFalse(((SolanaJsonRpcWebsocket) webSocket).lock.isHeldByCurrentThread());
      events.add("ping:" + error.getMessage());
    })) {
      final var boom = new IllegalStateException("ping failed");
      final var socket = new RecordingWebSocket();
      socket.failPing = boom;

      ws.onOpen(socket);
      assertEquals(0, socket.pings, "opening the connection counts as the first write");

      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings);
      assertTrue(socket.aborted);
      assertTrue(ws.closed());
      assertEquals(java.util.List.of("error:ping failed", "ping:ping failed"), events);

      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings, "an escalated transport receives no further control frames");
      assertEquals(2, events.size(), "the failure is reported exactly once through each seam");
    }
  }

  @Test
  void aSynchronousPingThrowFeedsTheSameFailureToBothHandlersExactlyOnce() {
    final var errors = new ArrayList<Throwable>();
    final var clock = new TestClock();
    try (final var ws = websocket(clock, (_, _, _) -> {
    }, (_, error) -> errors.add(error), null, (_, error) -> errors.add(error))) {
      final var failure = new IllegalStateException("sendPing threw");
      final var socket = new RecordingWebSocket();
      socket.throwPing = failure;
      ws.onOpen(socket);

      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));

      assertAll(
          () -> assertEquals(List.of(failure, failure), errors,
              "the ordinary error policy precedes the specific Ping observation"),
          () -> assertTrue(socket.aborted),
          () -> assertFalse(ws.closed(), "custom error policy keeps the reusable wrapper alive")
      );
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(2, errors.size(), "the synchronous throw is not reported again");
    }
  }

  /// A recorded Ping failure claims the transport before ordinary maintenance work. A queued
  /// cancellation must therefore stay off the wire: flushing it first writes application traffic
  /// onto a transport already known unusable and makes the early-return guard operationally real.
  @Test
  void aRecordedPingFailurePreventsQueuedUnsubscribeFromFlushing() {
    final var errors = new ArrayList<Throwable>();
    final var pingErrors = new ArrayList<Throwable>();
    final var clock = new TestClock();
    try (final var ws = websocket(
        clock,
        (_, _, _) -> {
        },
        (_, error) -> errors.add(error),
        null,
        (_, error) -> pingErrors.add(error)
    )) {
      final var socket = new RecordingWebSocket();
      socket.deferPings = true;
      assertTrue(ws.rootSubscribe(_ -> {
      }));
      ws.onOpen(socket);
      ws.onText(socket, java.nio.CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":700,"id":2}"""), true);

      clock.advanceMillis(TIMINGS.pingDelay() + 1L);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings);
      assertTrue(ws.rootUnsubscribe(), "the confirmed cancellation is queued for maintenance");
      assertTrue(socket.sentText.stream().noneMatch(msg -> msg.contains("rootUnsubscribe")));

      final var failure = new IOException("queued work lost its transport");
      socket.deferredPings.getFirst().completeExceptionally(failure);
      assertDoesNotThrow(() -> ws.checkCycle(0L));

      assertAll(
          () -> assertTrue(socket.aborted),
          () -> assertTrue(socket.sentText.stream().noneMatch(msg -> msg.contains("rootUnsubscribe")),
              "failed-Ping escalation must precede every queued application frame"),
          () -> assertEquals(List.of(failure), errors),
          () -> assertEquals(List.of(failure), pingErrors)
      );
    }
  }

  /// Once another maintenance finding has claimed the connection, a deferred Ping failure is
  /// teardown fallout, not a replacement terminal cause. The transition seam places completion
  /// after the connection enters its `ESCALATED` lifecycle state but before the transport is
  /// retired, distinguishing that guard from the already-covered superseded-socket guard.
  @Test
  void aPingFailureLosingToAnAlreadyClaimedDeadlineIsDropped() throws InterruptedException {
    final var errors = new ArrayList<Throwable>();
    final var pingErrors = new ArrayList<Throwable>();
    final var clock = new TestClock();
    try (final var ws = websocket(
        clock,
        (_, _, _) -> {
        },
        (_, error) -> errors.add(error),
        null,
        (_, error) -> pingErrors.add(error)
    )) {
      final var socket = new RecordingWebSocket();
      socket.deferPings = true;
      ws.onOpen(socket);

      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      ws.checkCycle(0L);
      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      final var deadlineDelivery = ws.prepareCheckCycleDelivery(0L);
      assertNotNull(deadlineDelivery, "the pending send deadline has claimed this connection");

      final var losingFailure = new IOException("send failed after its deadline lost");
      socket.deferredPings.getFirst().completeExceptionally(losingFailure);
      deadlineDelivery.run();

      assertAll(
          () -> assertTrue(socket.aborted),
          () -> assertEquals(1, errors.size()),
          () -> assertNotSame(losingFailure, errors.getFirst(),
              "the already-claimed deadline remains the one terminal cause"),
          () -> assertTrue(errors.getFirst().getMessage().startsWith("Ping send to ")),
          () -> assertTrue(pingErrors.isEmpty(),
              "an abort-induced losing completion is not a failed-Ping observation")
      );
    }
  }

  /// An empty maintenance pass must not claim a healthy connection. Besides being the ordinary
  /// no-failure path, this is what lets a later silence window send its first liveness probe.
  @Test
  void anEmptyMaintenancePassLeavesTheConnectionEligibleForItsFirstPing()
      throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock, (_, _, _) -> {
    }, (_, _) -> {
    }, null, (_, _) -> {
    })) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      ws.checkCycle(0L);
      clock.advanceMillis(TIMINGS.pingDelay() + 1L);
      ws.checkCycle(0L);

      assertEquals(1, socket.pings,
          "a null failed-Ping slot must not mark the healthy connection escalated");
      assertFalse(socket.aborted);
    }
  }

  /// A failed Ping is claimed under the lifecycle lock before its callbacks run off-lock. A
  /// second maintenance pass in that handoff window must therefore find no delivery of its own;
  /// otherwise two passes can race to report the same terminal transport failure.
  @Test
  void aRecordedPingFailureHasOneMaintenanceDeliveryClaim() throws InterruptedException {
    final var errors = new ArrayList<Throwable>();
    final var pingErrors = new ArrayList<Throwable>();
    final var clock = new TestClock();
    try (final var ws = websocket(
        clock,
        (_, _, _) -> {
        },
        (_, error) -> errors.add(error),
        null,
        (_, error) -> pingErrors.add(error)
    )) {
      final var socket = new RecordingWebSocket();
      socket.deferPings = true;
      ws.onOpen(socket);

      clock.advanceMillis(TIMINGS.pingDelay() + 1L);
      ws.checkCycle(0L);
      assertEquals(1, socket.deferredPings.size());
      final var failure = new IOException("failed Ping awaiting delivery");
      socket.deferredPings.getFirst().completeExceptionally(failure);

      final var delivery = ws.prepareCheckCycleDelivery(0L);
      assertNotNull(delivery, "the first maintenance pass claims the recorded failure");
      assertNull(ws.prepareCheckCycleDelivery(0L),
          "the lifecycle claim excludes a duplicate before off-lock delivery runs");

      delivery.run();
      assertAll(
          () -> assertTrue(socket.aborted),
          () -> assertEquals(List.of(failure), errors),
          () -> assertEquals(List.of(failure), pingErrors)
      );
    }
  }

  /// A cycle with no current transport has no maintenance finding to deliver. Drive this path
  /// directly so a broken null-connection branch fails synchronously rather than only from the
  /// background loop's terminal catch.
  @Test
  void aMaintenanceCycleWithoutAConnectionHasNoDelivery() throws InterruptedException {
    final var ws = websocket(new TestClock(), null, null, null);
    try {
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertFalse(ws.lock.isHeldByCurrentThread());
    } finally {
      ws.close();
    }
  }

  /// An open, unsignalled cycle must enter the Condition wait. A pre-existing interrupt is the
  /// synchronous oracle: the real await consumes it and throws immediately, while either
  /// forced-false operand of the wait guard skips the await and returns normally. Cleanup clears
  /// the flag independently so a failing mutation cannot contaminate the next test.
  @Test
  void anOpenUnsignalledCycleObservesInterruptAtTheConditionWait() {
    final var ws = websocket(new TestClock(), null, null, null);
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    try {
      Thread.currentThread().interrupt();
      assertThrows(InterruptedException.class,
          () -> ws.prepareCheckCycleDelivery(Long.MAX_VALUE));
      assertFalse(Thread.currentThread().isInterrupted(),
          "Condition.awaitNanos consumes the interrupt before throwing");
    } finally {
      // Mutation-safe cleanup: a skipped await leaves the test thread interrupted.
      Thread.interrupted();
      ws.close();
    }
  }

  /// The Condition signal has memory in `checkSignalled`, and terminal close is independently a
  /// no-wait state. Exercise each side only with a zero-duration wait: guard mutations remain
  /// finite and cannot turn this structural coverage into a parked-thread timeout.
  @Test
  void aRememberedSignalAndClosedStateBothCompleteZeroDelayCycles() throws InterruptedException {
    final var ws = websocket(new TestClock(), null, null, null);
    try {
      assertTrue(ws.slotSubscribe(_ -> {
      }), "registration records a signal before a waiter exists");
      assertNull(ws.prepareCheckCycleDelivery(0L));
      assertFalse(ws.lock.isHeldByCurrentThread());

      ws.close();
      // Consume close's remembered signal first, then isolate the closed guard with a false flag.
      assertNull(ws.prepareCheckCycleDelivery(0L));
      assertNull(ws.prepareCheckCycleDelivery(0L));
      assertFalse(ws.lock.isHeldByCurrentThread());
    } finally {
      ws.close();
    }
  }

  /// A Ping failure may complete after the maintenance loop has actually parked. The completion
  /// must both remember the finding and signal the Condition. The test itself owns the lock-to-
  /// await handoff, so the queue state is a synchronous oracle without adding checkpoint work to
  /// production; bounded thread joins are cleanup only.
  @Test
  void aFailedPingSignalsAnAlreadyParkedMaintenanceCycle() throws InterruptedException {
    final var errors = new ArrayList<Throwable>();
    final var pingErrors = new ArrayList<Throwable>();
    final var clock = new TestClock();
    final var ws = websocket(
        clock,
        (_, _, _) -> {
        },
        (_, error) -> errors.add(error),
        null,
        (_, error) -> pingErrors.add(error)
    );
    final var socket = new RecordingWebSocket();
    socket.deferPings = true;
    ws.onOpen(socket);
    clock.advanceMillis(TIMINGS.pingDelay() + 1);
    ws.checkCycle(0L);

    final var beforeAwait = new CompletableFuture<Void>();
    final var cycleCompleted = new CompletableFuture<Void>();
    final var cycleFailure = new AtomicReference<Throwable>();
    final var cycle = new Thread(() -> {
      ws.lock.lock();
      try {
        beforeAwait.complete(null);
        //noinspection ResultOfMethodCallIgnored
        ws.newSubscription.awaitNanos(Long.MAX_VALUE);
      } catch (final Throwable ex) {
        cycleFailure.set(ex);
      } finally {
        // Mutation-safe cleanup: any retained reentrant holds belong to this test thread and
        // must be released before it terminates so a failing assertion cannot poison teardown.
        while (ws.lock.isHeldByCurrentThread()) {
          ws.lock.unlock();
        }
        cycleCompleted.complete(null);
      }
    }, "parked-websocket-maintenance-test");
    cycle.start();

    boolean enteredAwait = false;
    boolean signalTransferred = false;
    try {
      // Completing beforeAwait while holding the lifecycle lock makes the following lock
      // acquisition wait until awaitNanos has atomically enqueued the cycle and released it.
      CompletableFuture.anyOf(beforeAwait, cycleCompleted).join();
      enteredAwait = beforeAwait.isDone();
      if (enteredAwait) {
        ws.lock.lock();
        try {
          assertTrue(ws.lock.hasWaiters(ws.newSubscription));
        } finally {
          while (ws.lock.isHeldByCurrentThread()) {
            ws.lock.unlock();
          }
        }
        final var failure = new IOException("parked Ping failed");
        socket.deferredPings.getFirst().completeExceptionally(failure);
        ws.lock.lock();
        try {
          signalTransferred = !ws.lock.hasWaiters(ws.newSubscription);
        } finally {
          while (ws.lock.isHeldByCurrentThread()) {
            ws.lock.unlock();
          }
        }
      }
      if (!signalTransferred) {
        // Cleanup for the signal-removal mutant: close owns a separate, independently tested
        // wake and releases the deliberately parked thread before assertions run.
        ws.close();
      }
      cycle.join(1_000L);
      if (!cycle.isAlive()) {
        // The test-owned waiter proves the wake but deliberately performs no maintenance work.
        // Drain the recorded failure through a separate zero-delay production cycle so callback
        // delivery remains synchronous and independent of the parked thread's scheduling.
        ws.checkCycle(0L);
      }
    } finally {
      ws.close();
      if (cycle.isAlive()) {
        cycle.interrupt();
        cycle.join(1_000L);
      }
    }

    final boolean didEnterAwait = enteredAwait;
    final boolean didTransferSignal = signalTransferred;
    assertAll(
        () -> assertTrue(didEnterAwait, "the deterministic checkpoint must enter the await path"),
        () -> assertTrue(didTransferSignal,
            "recordFailedPing.signal() must transfer the parked cycle off the Condition queue"),
        () -> assertFalse(cycle.isAlive()),
        () -> assertNull(cycleFailure.get()),
        () -> assertEquals(1, errors.size()),
        () -> assertEquals(1, pingErrors.size()),
        () -> assertSame(errors.getFirst(), pingErrors.getFirst(),
            "the woken cycle delivers one coherent failed-Ping callback set")
    );
  }

  private static void aTerminalNoticeCannotLoseARecordedPingFailure(final boolean closeNotice) {
    final var events = new ArrayList<String>();
    final var clock = new TestClock();
    try (final var ws = websocket(
        clock,
        (_, _, _) -> events.add("close"),
        (_, error) -> events.add("error:" + error.getMessage()),
        null,
        (_, error) -> events.add("ping:" + error.getMessage())
    )) {
      final var failure = new IOException("ping failed before terminal notice");
      final var socket = new RecordingWebSocket();
      socket.deferPings = true;
      ws.onOpen(socket);

      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.deferredPings.size());
      socket.deferredPings.getFirst().completeExceptionally(failure);

      if (closeNotice) {
        ws.onClose(socket, 4242, "peer closed while ping failed");
      } else {
        ws.onError(socket, new IOException("socket error after ping failure"));
      }

      assertAll(
          () -> assertEquals(List.of(
              "error:ping failed before terminal notice",
              "ping:ping failed before terminal notice"
          ), events, "the first recorded terminal finding owns one coherent callback set"),
          () -> assertTrue(socket.aborted),
          () -> assertFalse(ws.closed(), "custom error policy keeps the reusable wrapper alive")
      );
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(2, events.size(), "the signalled maintenance pass must not report it again");
    }
  }

  @Test
  void onCloseCannotLoseAPingFailureAlreadyRecordedForThatTransport() {
    aTerminalNoticeCannotLoseARecordedPingFailure(true);
  }

  @Test
  void onErrorCannotLoseAPingFailureAlreadyRecordedForThatTransport() {
    aTerminalNoticeCannotLoseARecordedPingFailure(false);
  }

  /// A ping failure caused by this engine aborting a superseded socket is expected transport
  /// teardown, not a failure of the replacement connection. The completion runs after takeover,
  /// so it must re-check the connection it belongs to before invoking the instance-wide handler.
  @Test
  void aSupersededConnectionsLatePingFailureIsNotReportedForItsSuccessor() throws InterruptedException {
    final var errors = new ArrayList<Throwable>();
    final var clock = new TestClock();
    try (final var ws = websocket(clock, (_, _, _) -> {
    }, null, (_, error) -> errors.add(error))) {
      final var first = new RecordingWebSocket();
      first.deferPings = true;
      ws.onOpen(first);
      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      ws.checkCycle(0L);
      assertEquals(1, first.deferredPings.size());

      final var second = new RecordingWebSocket();
      ws.onOpen(second);
      assertTrue(first.aborted, "takeover retires the first transport");

      first.deferredPings.getFirst().completeExceptionally(new IOException("aborted ping"));

      assertTrue(errors.isEmpty(),
          "an expected failure from the superseded transport must not be attributed to its successor");
    }
  }

  /// Unanswered-request escalation aborts the current socket while its text future is still
  /// pending. Its ensuing failure is caused by that deliberate abort, so it is not a second,
  /// transient send failure for the instance-wide handler to act on.
  @Test
  void anEscalatedConnectionsDeferredSendFailureIsNotReportedAgain() throws InterruptedException {
    final var sendErrors = new ArrayList<Throwable>();
    final var clock = new TestClock();
    try (final var ws = websocket(clock, (_, _, _) -> {
    }, (_, error) -> sendErrors.add(error), null)) {
      assertTrue(ws.rootSubscribe(_ -> {
      }));
      final var socket = new RecordingWebSocket();
      socket.deferTexts = true;
      ws.onOpen(socket);
      assertEquals(1, socket.deferredTexts.size());

      clock.advanceMillis(TIMINGS.subscriptionResendDelay()
          * SolanaJsonRpcWebsocket.UNANSWERED_ESCALATION_FACTOR + 1);
      ws.checkCycle(0L);
      assertTrue(socket.aborted, "the unanswered request retires the transport");

      socket.deferredTexts.getFirst().completeExceptionally(new IOException("aborted send"));

      assertTrue(sendErrors.isEmpty(),
          "the engine-triggered abort must not surface again as a transient send failure");
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
    assertTrue(ws.closed(), "close commits its terminal sentinel synchronously");
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
    final var clock = new PoisonClock();
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
    final var poison = new IllegalStateException("check loop poisoned");
    clock.poison(poison);

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

    // one frame, not two: the successful open-time send completed before the poisoned cycle
    assertEquals(1, socket.sentText.size(), "the successful send stays gated until a response");
    assertTrue(ws.closed(), "an unhandled loop exception closes the websocket");
    assertTrue(records.stream().anyMatch(record -> record.getThrown() == poison),
        "the failure funnel must log the exception, not swallow it");
  }

  @Test
  void pingFailureWithoutAHandlerIsLoggedNotThrown() {
    final var clock = new TestClock();
    try (final var ws = websocket(clock, (_, _, _) -> {
    }, null, null, null)) {
      final var socket = new RecordingWebSocket();
      socket.failPing = new IllegalStateException("ping failed");
      ws.onOpen(socket);
      clock.advanceMillis(TIMINGS.pingDelay() + 1);
      assertDoesNotThrow(() -> ws.checkCycle(0L));
      assertEquals(1, socket.pings);
      assertTrue(socket.aborted);
      assertTrue(ws.closed(), "the default onError policy closes an unresponsive instance");
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
  /// parking, and a clock which starts throwing after adoption is the deterministic poison.
  @Test
  @org.junit.jupiter.api.Timeout(30)
  void aCheckLoopFailureReachesOnErrorBeforeClosing() {
    final var seen = new AtomicReference<Throwable>();
    final var clock = new PoisonClock();
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
    final var boom = new IllegalStateException("socket poisoned");
    clock.poison(boom);

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
        // all three names are interpolated into frames, so all three are validated
        assertThrows(IllegalArgumentException.class, () -> ws.subscribe("voteSubscribe", bad,
            "voteNotification", "vote", "", JsonIterator::readString, null, _ -> {
            }), "unSubscribeMethod " + bad);
        assertThrows(IllegalArgumentException.class, () -> ws.subscribe("voteSubscribe",
            "voteUnsubscribe", bad, "vote", "", JsonIterator::readString, null, _ -> {
            }), "notificationMethod " + bad);
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
        w -> {
          assertFalse(((SolanaJsonRpcWebsocket) w).lock.isHeldByCurrentThread(),
              "a synchronous builder callback must not retain connect()'s lifecycle-lock hold");
          reentrant.set(w.connect());
        }, (_, _, _) -> {
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

  /// Every caller receives a disposable view of the single-flight attempt, including callers
  /// which arrive after that attempt was reserved. Cancelling a joined view must not cancel the
  /// internal bridge and admit a second mutable-builder invocation.
  @Test
  void cancellingAJoinedConnectViewDoesNotAbandonTheAttempt() {
    final var current = new RecordingWebSocket();
    final var built = new RecordingWebSocket();
    final var scheduler = new RecordingScheduler();
    final var builder = new RecordingWebSocketBuilder(new AtomicReference<>(), built);
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder.connectTimeout(Duration.ofMillis(1_000)), TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        }, null, null, null
    );
    try {
      ws.onOpen(current);
      final var owner = ws.connect();
      final var joined = ws.connect();
      assertNotNull(owner);
      assertNotNull(joined);
      assertEquals(1, scheduler.deferred.size());

      joined.cancel(true);
      final var stillJoined = ws.connect();

      assertNotNull(stillJoined);
      assertFalse(stillJoined.isDone(), "cancelling one joined view must not settle the owner");
      assertEquals(1, scheduler.deferred.size(), "the cancellation must not admit another attempt");

      scheduler.deferred.getFirst().task().run();
      assertSame(built, owner.join());
      assertSame(built, stillJoined.join());
      assertTrue(joined.isCancelled(), "only the caller's abandoned view remains cancelled");
      assertEquals(1, builder.builds, "the shared attempt invokes the mutable builder once");
    } finally {
      ws.close();
    }
  }

  /// Once close commits, the locked lifecycle guard is the authority for connect admission.
  /// A closed wrapper returns no attempt and never reaches the public builder again.
  @Test
  void connectAfterCloseCannotEnterTheBuilder() {
    final var builder = new RecordingWebSocketBuilder(
        new AtomicReference<>(), new RecordingWebSocket()
    );
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder.connectTimeout(Duration.ofMillis(1_000)), TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), new RecordingScheduler(), null, (_, _, _) -> {
        }, null, null, null
    );
    ws.close();

    assertNull(ws.connect());
    assertEquals(0, builder.builds, "a closed wrapper owns no further builder invocation");
  }

  /// A public builder may deliver the whole first lifecycle stack before buildAsync returns.
  /// Retirement and a policy-driven reconnect must not let the original continuation overwrite
  /// the successor's build ownership; closing the wrapper must still cancel that successor.
  @Test
  void aSynchronousTerminalCallbackCannotOverwriteItsReentrantSuccessor() {
    final var first = new RecordingWebSocket();
    final var successor = new RecordingWebSocket();
    final var firstBuild = new CompletableFuture<WebSocket>();
    final var successorBuild = new CompletableFuture<WebSocket>();
    final var builder = new SynchronousOpenSequenceBuilder(
        List.of(first, successor), List.of(firstBuild, successorBuild)
    );
    builder.firstBuildError = new IOException("failed during buildAsync");
    final var callbackHeldLock = new AtomicReference<Boolean>();
    final var joinedAttempt = new AtomicReference<CompletableFuture<?>>();
    final var attemptReleasedBeforePolicy = new AtomicReference<Boolean>();
    final var retry = new AtomicReference<CompletableFuture<?>>();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder.connectTimeout(Duration.ofMillis(1_000)),
        new Timings(0, 60_000, 60_000),
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), null,
        websocket -> joinedAttempt.set(websocket.connect()), (_, _, _) -> {
        }, (websocket, _) -> {
          callbackHeldLock.set(((SolanaJsonRpcWebsocket) websocket).lock.isHeldByCurrentThread());
          final var joined = joinedAttempt.get();
          attemptReleasedBeforePolicy.set(joined != null && joined.isDone());
          retry.set(websocket.connect());
        }, null, null
    );
    try {
      final var initial = ws.connect();

      assertEquals(Boolean.FALSE, callbackHeldLock.get(),
          "the terminal policy must run after buildAsync's reservation lock is released");
      assertEquals(Boolean.TRUE, attemptReleasedBeforePolicy.get(),
          "retirement must settle every joined attempt before invoking recovery policy");
      assertEquals(2, builder.builds);
      assertTrue(initial.isCompletedExceptionally(), "retirement settles the predecessor attempt");
      assertTrue(firstBuild.isCancelled(), "the predecessor builder operation is released");
      assertNotNull(retry.get());
      assertFalse(retry.get().isDone(), "the successor still owns its pending builder operation");
      assertFalse(successorBuild.isCancelled());

      ws.close();

      assertTrue(successorBuild.isCancelled(),
          "close must cancel the successor build rather than a stale predecessor handle");
    } finally {
      ws.close();
    }
  }

  /// A predecessor can lose installation after terminal policy has reserved a successor but
  /// before that successor may enter the mutable builder. If the successor builder then fails,
  /// close must not find the predecessor installed as the successor's cancellation owner.
  @Test
  void aLosingPredecessorBuildCannotBecomeTheSuccessorsCancellationOwner() {
    final var predecessor = new RecordingWebSocket();
    final var successor = new RecordingWebSocket();
    final var predecessorBuild = new AfterOwnershipFuture();
    predecessorBuild.complete(predecessor);
    final var builder = new SynchronousOpenSequenceBuilder(
        List.of(predecessor, successor),
        List.of(predecessorBuild, new CompletableFuture<>())
    );
    builder.secondBuildThrow = new IllegalStateException("successor builder failed");
    final var retry = new AtomicReference<CompletableFuture<?>>();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder.connectTimeout(Duration.ofMillis(1_000)),
        new Timings(0L, 60_000L, 60_000L),
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), new RecordingScheduler(), null, (_, _, _) -> {
        }, (websocket, _) -> retry.set(websocket.connect()), null, null
    );
    predecessorBuild.afterOwnership = () -> ws.onError(
        predecessor, new IOException("predecessor retired before installation")
    );
    try {
      final var initial = ws.connect();

      assertNotNull(initial);
      assertTrue(initial.isCompletedExceptionally(), "retirement settles the predecessor bridge");
      assertEquals(2, builder.builds, "the reserved successor receives the released builder");
      assertNotNull(retry.get());
      assertTrue(retry.get().isCompletedExceptionally(), "the successor contains its builder failure");
      assertEquals(1, predecessorBuild.cancelCalls,
          "losing installation cancels the predecessor build exactly once");

      ws.close();

      assertEquals(1, predecessorBuild.cancelCalls,
          "close must not rediscover the predecessor as the successor's build owner");
    } finally {
      ws.close();
    }
  }

  /// A builder operation may be past cancellation when its adopted connection fails. Recovery
  /// gives a newer generation authority immediately; if the predecessor future later produces a
  /// different, still-unadopted transport, that result belongs to the retired generation and must
  /// be aborted without disturbing the current generation's successful build.
  @Test
  void aRetiredUncancellableBuildAbortsItsLateResultWithoutDisturbingTheSuccessor() {
    final var predecessorBuild = new UncancellableWebSocketFuture();
    final var successor = new RecordingWebSocket();
    final var builder = new FutureSequenceBuilder(List.of(
        predecessorBuild,
        CompletableFuture.completedFuture(successor)
    ));
    final var retry = new AtomicReference<CompletableFuture<?>>();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder.connectTimeout(Duration.ofMillis(1_000)),
        new Timings(0L, 60_000L, 60_000L),
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), new RecordingScheduler(), null, (_, _, _) -> {
        }, (websocket, _) -> retry.set(websocket.connect()), null, null
    );
    try {
      final var predecessor = ws.connect();
      assertNotNull(predecessor);
      assertEquals(1, builder.builds);
      final var retired = new RecordingWebSocket();
      builder.listeners.getFirst().onOpen(retired);

      builder.listeners.getFirst().onError(retired, new IOException("retire predecessor"));

      assertTrue(predecessor.isCompletedExceptionally(),
          "retirement settles the predecessor even when its builder future cannot be cancelled");
      assertEquals(2, builder.builds, "recovery must start a fresh builder generation");
      assertNotNull(retry.get());
      assertSame(successor,
          retry.get().orTimeout(500L, TimeUnit.MILLISECONDS).join(),
          "the successor owns the recovery attempt");
      assertFalse(successor.aborted,
          "a current successful build awaiting onOpen remains owned by its generation");

      final var late = new RecordingWebSocket();
      assertTrue(predecessorBuild.complete(late));

      assertTrue(late.aborted,
          "a late result from the retired generation must be released without waiting on onOpen");
      assertFalse(successor.aborted,
          "cleaning the stale predecessor must not release the current unadopted successor");
      builder.listeners.get(1).onOpen(successor);
      assertEquals(Long.MAX_VALUE, successor.requested,
          "the preserved successor must remain adoptable after stale cleanup");
    } finally {
      ws.close();
    }
  }

  /// buildAsync is public collaborator code and may throw synchronously. The throw is contained
  /// into the returned attempt, and releasing the builder reservation must let a later retry use
  /// that same mutable builder rather than leave the retry permanently queued.
  @Test
  void aSynchronousBuilderThrowSettlesAndReleasesItsRetry() {
    final var first = new RecordingWebSocket();
    final var successor = new RecordingWebSocket();
    final var builder = new SynchronousOpenSequenceBuilder(
        List.of(first, successor),
        List.of(new CompletableFuture<>(), CompletableFuture.completedFuture(successor))
    );
    builder.firstBuildThrow = new IllegalStateException("builder threw synchronously");
    builder.firstBuildOpens = false;
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder.connectTimeout(Duration.ofMillis(1_000)),
        new Timings(0L, 60_000L, 60_000L),
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), new RecordingScheduler(), null, (_, _, _) -> {
        }, null, null, null
    );
    try {
      final var initial = assertDoesNotThrow(ws::connect,
          "a synchronous builder throw must settle the attempt instead of escaping connect");

      assertNotNull(initial);
      assertTrue(initial.isCompletedExceptionally());
      assertEquals(1, builder.builds, "the failed invocation settles before an explicit retry");

      final var retry = assertDoesNotThrow(ws::connect);

      assertNotNull(retry);
      assertEquals(2, builder.builds,
          "the retry must enter buildAsync after the throwing invocation releases it");
      assertSame(successor, retry.orTimeout(500L, TimeUnit.MILLISECONDS).join());
      assertFalse(successor.aborted, "the retry's live transport remains current");
    } finally {
      ws.close();
    }
  }

  /// An exceptionally completed builder future is a settled predecessor, not an exception for
  /// the next connect caller to join. Retry must discard that failed owner and enter the builder
  /// for a fresh generation without throwing from connect itself.
  @Test
  void retryAfterAnExceptionalBuilderFutureDoesNotJoinTheFailure() {
    final var successor = new RecordingWebSocket();
    final var builderFailure = new IOException("first builder future failed");
    final var builder = new FutureSequenceBuilder(List.of(
        CompletableFuture.failedFuture(builderFailure),
        CompletableFuture.completedFuture(successor)
    ));
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder.connectTimeout(Duration.ofMillis(1_000)),
        new Timings(0L, 60_000L, 60_000L),
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), new RecordingScheduler(), null, (_, _, _) -> {
        }, null, null, null
    );
    try {
      final var failed = ws.connect();
      assertNotNull(failed);
      assertTrue(failed.isCompletedExceptionally());

      final var retry = assertDoesNotThrow(ws::connect,
          "a failed prior builder future must not escape from the retry's caller stack");

      assertNotNull(retry);
      assertSame(successor, retry.join());
      assertEquals(2, builder.builds, "the retry owns a fresh builder invocation");
    } finally {
      ws.close();
    }
  }

  /// Returning null violates WebSocket.Builder's future contract, but the wrapper must contain
  /// that collaborator failure exactly like a synchronous throw and release the builder for a
  /// later retry.
  @Test
  void aNullBuilderFutureSettlesAndReleasesItsRetry() {
    final var first = new RecordingWebSocket();
    final var successor = new RecordingWebSocket();
    final var builder = new SynchronousOpenSequenceBuilder(
        List.of(first, successor),
        List.of(new CompletableFuture<>(), CompletableFuture.completedFuture(successor))
    );
    builder.firstBuildReturnsNull = true;
    builder.firstBuildOpens = false;
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder.connectTimeout(Duration.ofMillis(1_000)),
        new Timings(0L, 60_000L, 60_000L),
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), new RecordingScheduler(), null, (_, _, _) -> {
        }, null, null, null
    );
    try {
      final var initial = assertDoesNotThrow(ws::connect,
          "a null builder future must settle the attempt instead of escaping connect");

      assertNotNull(initial);
      assertTrue(initial.isCompletedExceptionally());
      assertEquals(1, builder.builds, "the failed invocation settles before an explicit retry");

      final var retry = assertDoesNotThrow(ws::connect);

      assertNotNull(retry);
      assertEquals(2, builder.builds,
          "the retry must enter buildAsync after the null-returning invocation releases it");
      assertSame(successor, retry.orTimeout(500L, TimeUnit.MILLISECONDS).join());
      assertFalse(successor.aborted, "the retry's live transport remains current");
    } finally {
      ws.close();
    }
  }

  /// A lifecycle callback may retire the attempt and reserve its successor before the mutable
  /// builder returns. If that same builder invocation then throws, ordinary failure containment
  /// must still hand the reservation to the queued successor after leaving buildAsync — starting
  /// it re-entrantly would violate the JDK builder's threading contract, while dropping it would
  /// strand the recovery future forever.
  @Test
  void aSynchronousBuilderThrowAfterRetirementStartsTheQueuedSuccessor() throws InterruptedException {
    final var first = new RecordingWebSocket();
    final var successor = new RecordingWebSocket();
    final var builder = new SynchronousOpenSequenceBuilder(
        List.of(first, successor),
        List.of(new CompletableFuture<>(), CompletableFuture.completedFuture(successor))
    );
    builder.firstBuildError = new IOException("retired during buildAsync");
    builder.firstBuildThrow = new IllegalStateException("builder threw after retirement");
    final var retry = new AtomicReference<CompletableFuture<?>>();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder.connectTimeout(Duration.ofMillis(1_000)),
        new Timings(0L, 60_000L, 60_000L),
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), new RecordingScheduler(), null, (_, _, _) -> {
        }, (websocket, _) -> retry.set(websocket.connect()), null, null
    );
    try {
      final var initial = assertDoesNotThrow(ws::connect);

      assertNotNull(initial);
      assertTrue(initial.isCompletedExceptionally(), "retirement settles the predecessor bridge");
      assertEquals(2, builder.builds,
          "the queued successor starts only after the throwing builder invocation exits");
      assertNotNull(retry.get());
      assertSame(successor, retry.get().join());
      assertLifecycleLockReleasedFromAnotherThread(ws);
    } finally {
      ws.close();
    }
  }

  /// Errors are not converted into an ordinary connection failure, but the builder reservation
  /// is still resource ownership and must be released on that exceptional edge. A successor
  /// already reserved by terminal policy therefore starts before the Error escapes to the caller.
  @Test
  void aFatalBuilderThrowStillStartsTheQueuedSuccessorBeforeEscaping() throws InterruptedException {
    final var first = new RecordingWebSocket();
    final var successor = new RecordingWebSocket();
    final var builder = new SynchronousOpenSequenceBuilder(
        List.of(first, successor),
        List.of(new CompletableFuture<>(), CompletableFuture.completedFuture(successor))
    );
    builder.firstBuildError = new IOException("retired during buildAsync");
    builder.firstBuildFatal = new AssertionError("fatal builder failure");
    final var retry = new AtomicReference<CompletableFuture<?>>();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder.connectTimeout(Duration.ofMillis(1_000)),
        new Timings(0L, 60_000L, 60_000L),
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), new RecordingScheduler(), null, (_, _, _) -> {
        }, (websocket, _) -> retry.set(websocket.connect()), null, null
    );
    try {
      final var fatal = assertThrows(AssertionError.class, ws::connect);

      assertEquals("fatal builder failure", fatal.getMessage());
      assertEquals(2, builder.builds,
          "exceptional builder release transfers ownership to the queued successor");
      assertNotNull(retry.get());
      assertSame(successor, retry.get().join());
      assertLifecycleLockReleasedFromAnotherThread(ws);
    } finally {
      ws.close();
    }
  }

  /// A completed builder future can publish its socket to ownBuild before the later install lock
  /// is reacquired. If close wins in that exact gap, the socket has no listener adoption and a
  /// completed future cannot be cancelled; the losing install must abort it explicitly.
  @Test
  void closeBetweenCompletedBuildObservationAndInstallationAbortsTheSocket() {
    final var socket = new RecordingWebSocket();
    final var built = new AfterOwnershipFuture();
    built.complete(socket);
    final var builder = new SynchronousOpenPendingBuilder(socket, built);
    builder.invokeOnOpen = false;
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder.connectTimeout(Duration.ofMillis(1_000)), TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), null, null, (_, _, _) -> {
        }, null, null, null
    );
    built.afterOwnership = ws::close;

    final var attempt = ws.connect();

    assertNotNull(attempt);
    assertTrue(attempt.isCompletedExceptionally());
    assertTrue(socket.aborted,
        "a successful build which loses installation must not escape without an owner");
    assertTrue(ws.closed());
  }

  /// A wrapping builder can violate its future contract by completing successfully with null.
  /// If close wins after ownership observation, losing-install cleanup must still settle the
  /// bridge without dereferencing an absent transport or letting that collaborator defect escape
  /// synchronously from connect().
  @Test
  void aNullSuccessfulBuildLosingInstallationIsContained() {
    final var built = new AfterOwnershipFuture();
    built.complete(null);
    final var builder = new SynchronousOpenPendingBuilder(new RecordingWebSocket(), built);
    builder.invokeOnOpen = false;
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder.connectTimeout(Duration.ofMillis(1_000)), TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), null, null, (_, _, _) -> {
        }, null, null, null
    );
    built.afterOwnership = ws::close;

    final var attempt = assertDoesNotThrow(ws::connect);

    assertNotNull(attempt);
    assertTrue(attempt.isCompletedExceptionally());
    assertTrue(ws.closed());
  }

  /// close() can also win installation while an uncancellable builder future is still pending.
  /// Its exceptional completion may then race the losing cleanup; connect() must contain that
  /// outcome in the reserved attempt rather than throwing it from the caller's stack.
  @Test
  void exceptionalBuildCompletionRacingLosingInstallationDoesNotEscapeConnect() {
    final var built = new ExceptionCompletingLosingFuture();
    final var builder = new SynchronousOpenPendingBuilder(new RecordingWebSocket(), built);
    builder.invokeOnOpen = false;
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder.connectTimeout(Duration.ofMillis(1_000)), TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), null, null, (_, _, _) -> {
        }, null, null, null
    );
    built.afterOwnershipRegistration = ws::close;

    final var attempt = assertDoesNotThrow(ws::connect,
        "a losing installer must not expose a raced exceptional build completion");

    assertNotNull(attempt);
    assertTrue(attempt.isCompletedExceptionally());
    assertTrue(built.isCompletedExceptionally());
    assertTrue(ws.closed());
  }

  /// A completion action attached by the synchronous onOpen re-entry is allowed to reconnect too.
  /// Completing the reserved bridge runs that action inline, before the outer connect() returns;
  /// the attempt it installs must remain the single-flight authority rather than being overwritten
  /// by the outer invocation's already-completed bridge.
  @Test
  void aSynchronousOnOpenCompletionReentryDoesNotLoseSingleFlightAuthority() {
    final var clock = new TestClock();
    final var socket = new RecordingWebSocket();
    final var scheduler = new RecordingScheduler();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>(), socket);
    webSocketBuilder.invokeOnOpen = true;
    final var completionReentry = new AtomicReference<CompletableFuture<?>>();
    try (final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock,
        new RecordingExecutor(), scheduler,
        w -> w.connect().whenComplete((_, _) -> completionReentry.set(w.connect())), (_, _, _) -> {
        }, null, null, null)) {
      final var outer = ws.connect();
      assertNotNull(outer);
      assertNotNull(completionReentry.get(), "the joined attempt's completion performs the reconnect");
      assertEquals(1, scheduler.deferred.size(), "that reconnect creates the one deferred attempt");

      final var observer = ws.connect();
      assertNotNull(observer);
      assertEquals(1, scheduler.deferred.size(),
          "the observer must join the deferred attempt, not stack another after outer connect overwrites its authority");
    }
  }

  /// buildAsync may complete before its listener executor delivers onOpen. Once the successful
  /// socket exists, close() owns it even though no Connection has adopted it yet; otherwise its
  /// bounded-abort guarantee depends on that delayed callback eventually running.
  @Test
  void closeAbortsASuccessfullyBuiltSocketAwaitingAdoption() {
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>(), socket);
    final var scheduler = new RecordingScheduler();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        }, null, null, null);
    final var connected = ws.connect();
    assertNotNull(connected);
    assertSame(socket, connected.join(), "the build succeeded even though onOpen is still queued");

    ws.close();

    // A correct implementation may abort immediately or retain the socket for the same bounded
    // polite-close watchdog used by an adopted connection. Drive any captured grace task rather
    // than prescribing which release strategy it chooses.
    scheduler.deferred.forEach(deferred -> deferred.task().run());
    assertTrue(socket.aborted, "close must abort a successful socket which has not been adopted");
  }

  /// Cancelling the internal attempt completes every caller's defensive copy synchronously.
  /// Local close state must already be committed when that caller code runs: otherwise a blocking
  /// completion action can indefinitely postpone connection detachment, registry clearing, and
  /// the check-loop signal while closed() already reports true.
  @Test
  void closeCommitsLocalTeardownBeforeConnectCancellationCallbacks() {
    final var clock = new TestClock();
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new SynchronousOpenPendingBuilder(socket);
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock,
        new RecordingExecutor(), new RecordingScheduler(), null, (_, _, _) -> {
        }, null, null, null);
    assertTrue(ws.slotSubscribe(_ -> {
    }));
    final var connected = ws.connect();
    assertNotNull(connected);
    ws.onText(socket, java.nio.CharBuffer.wrap("""
        {"jsonrpc":"2.0","result":999,"id":999}"""), true);
    assertNotEquals(0L, ws.lastMessageReceivedTimestamp());

    final var retainedDuringCancellation = new AtomicReference<Integer>();
    final var messageTimestampDuringCancellation = new AtomicReference<Long>();
    connected.whenComplete((_, _) -> {
      retainedDuringCancellation.set(ws.retainedRegistrations());
      messageTimestampDuringCancellation.set(ws.lastMessageReceivedTimestamp());
    });

    ws.close();
    webSocketBuilder.completion.cancel(false);

    assertAll(
        () -> assertEquals(0, retainedDuringCancellation.get(),
            "registries must be cleared before cancellation invokes caller code"),
        () -> assertEquals(0L, messageTimestampDuringCancellation.get(),
            "the connection must be detached before cancellation invokes caller code")
    );
  }

  /// Local state is committed before caller notification, but transport release must be
  /// committed too. Cancelling the bridge settles every defensive copy synchronously; if one
  /// caller blocks there, close() must already have cancelled the builder-owned HTTP upgrade
  /// and armed the adopted socket's abort watchdog rather than leaving either behind that code.
  @Test
  void closeArmsOwnedTransportReleaseBeforeNotifyingConnectWaiters() {
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new SynchronousOpenPendingBuilder(socket);
    final var scheduler = new RecordingScheduler();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        }, null, null, null);
    final var connected = ws.connect();
    assertNotNull(connected);
    final var buildCancelledWhenNotified = new AtomicReference<Boolean>();
    final var watchdogArmedWhenNotified = new AtomicReference<Boolean>();
    connected.whenComplete((_, _) -> {
      buildCancelledWhenNotified.set(webSocketBuilder.completion.isCancelled());
      watchdogArmedWhenNotified.set(scheduler.deferred.stream()
          .anyMatch(deferred -> deferred.delay() == SolanaJsonRpcWebsocket.CLOSE_GRACE_MILLIS));
    });

    ws.close();

    assertAll(
        () -> assertEquals(Boolean.TRUE, buildCancelledWhenNotified.get(),
            "the owned handshake must be cancelled before close invokes caller completion code"),
        () -> assertEquals(Boolean.TRUE, watchdogArmedWhenNotified.get(),
            "the adopted transport's bounded abort must be armed before caller code can block close")
    );
  }

  /// Before buildAsync starts, the scheduled wake is the operation retaining this websocket.
  /// Cancelling the caller bridge invokes completion actions on the close stack, so that wake must
  /// already be cancelled when caller code runs — a blocking action cannot be allowed to strand it.
  @Test
  void closeCancelsTheDeferredWakeBeforeNotifyingConnectWaiters() {
    final var socket = new RecordingWebSocket();
    final var scheduler = new RecordingScheduler();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        new RecordingWebSocketBuilder(new AtomicReference<>(), new RecordingWebSocket())
            .connectTimeout(Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        }, null, null, null
    );
    ws.onOpen(socket);
    final var connected = ws.connect();
    assertNotNull(connected);
    assertEquals(1, scheduler.deferred.size());
    final var handle = scheduler.deferred.getFirst().handle();
    final var cancelledWhenNotified = new AtomicReference<Boolean>();
    connected.whenComplete((_, _) -> cancelledWhenNotified.set(handle.isCancelled()));

    ws.close();

    assertEquals(Boolean.TRUE, cancelledWhenNotified.get(),
        "the deferred wake must be released before bridge cancellation invokes caller code");
  }

  /// A cancelled scheduler handle may still race into its Runnable. Once close has displaced its
  /// exact bridge, that stale wake must stop at the ownership check: it may not touch the pacing
  /// clock, reserve the mutable builder, or invoke buildAsync.
  @Test
  void aStaleDeferredWakeCannotCrossTheAttemptOwnershipGuard() {
    final var clock = new PoisonClock();
    final var scheduler = new RecordingScheduler();
    final var builder = new RecordingWebSocketBuilder(
        new AtomicReference<>(), new RecordingWebSocket()
    );
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder.connectTimeout(Duration.ofMillis(1_000)), TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        clock, new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        }, null, null, null
    );
    ws.onOpen(new RecordingWebSocket());
    final var attempt = ws.connect();
    assertNotNull(attempt);
    assertEquals(1, scheduler.deferred.size());
    final var staleWake = scheduler.deferred.getFirst();

    ws.close();
    assertTrue(staleWake.handle().isCancelled());
    clock.poison(new IllegalStateException("stale wake touched the pacing clock"));

    assertDoesNotThrow(staleWake.task()::run);
    assertEquals(0, builder.builds, "a displaced wake owns no builder invocation");
  }

  /// The injected scheduler is part of initiating a deferred attempt, so rejecting the schedule
  /// is the same shape as a builder throwing: the returned attempt fails and releases the
  /// single-flight slot. Installing an incomplete bridge before schedule() and then letting the
  /// throw escape leaves every later caller joined to a future nothing can ever settle.
  @Test
  void aRejectedDeferredScheduleDoesNotPoisonSingleFlightAuthority() throws InterruptedException {
    final var scheduler = new java.util.concurrent.ScheduledThreadPoolExecutor(1);
    scheduler.shutdown();
    final var socket = new RecordingWebSocket();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        new RecordingWebSocketBuilder(new AtomicReference<>(), new RecordingWebSocket())
            .connectTimeout(Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        }, null, null, null);
    try {
      ws.onOpen(socket); // stamps the reconnect throttle, so connect() must schedule

      final var first = assertDoesNotThrow(ws::connect,
          "schedule rejection is contained into the attempt like a synchronous builder throw");
      assertNotNull(first);
      assertTrue(first.isCompletedExceptionally(), "the rejected attempt must settle");
      assertLifecycleLockReleasedFromAnotherThread(ws);

      final var retry = assertDoesNotThrow(ws::connect,
          "a settled rejection releases the single-flight slot for a real retry");
      assertNotNull(retry);
      assertTrue(retry.isCompletedExceptionally(), "the still-rejected retry settles independently");
      assertNotSame(first, retry, "the retry must not join the poisoned first bridge");
    } finally {
      ws.close();
    }
  }

  /// Scheduling is collaborator code and runs after the placeholder is published. If close wins
  /// inside schedule(), the real handle returned afterward has lost ownership and must be
  /// cancelled rather than retained to its deadline.
  @Test
  void aScheduledHandleReturnedAfterCloseIsCancelled() {
    final var scheduler = new RecordingScheduler();
    final var socket = new RecordingWebSocket();
    final var builder = new RecordingWebSocketBuilder(new AtomicReference<>(), new RecordingWebSocket());
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder.connectTimeout(Duration.ofMillis(1_000)), TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        }, null, null, null
    );
    ws.onOpen(socket);
    scheduler.duringSchedule = ws::close;

    final var attempt = ws.connect();

    assertNotNull(attempt);
    assertTrue(attempt.isCompletedExceptionally());
    assertEquals(1, scheduler.deferred.size());
    assertTrue(scheduler.deferred.getFirst().handle().isCancelled(),
        "the handle returned after its placeholder lost ownership must be released");
    assertEquals(0, builder.builds, "close wins before the deferred builder may run");
  }

  /// A scheduled task may become enabled and enter buildAsync before schedule() returns. Once
  /// startBuild has consumed the placeholder, that missing placeholder means "started", not
  /// "stale": cancelling the late-returned handle with interruption would kill the live attempt.
  @Test
  void aScheduledBuildWhichStartsBeforeItsHandleReturnsIsNotCancelled() {
    final var buildEntered = new CountDownLatch(1);
    final var releaseBuild = new CountDownLatch(1);
    final var built = new RecordingWebSocket();
    final var builder = new BlockingBuildAsyncBuilder(built, buildEntered, releaseBuild);
    final var scheduler = new BuildBeforeScheduleReturns(buildEntered);
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder.connectTimeout(Duration.ofMillis(1_000)),
        new Timings(1L, 60_000L, 60_000L),
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        }, null, null, null
    );
    try {
      ws.onOpen(new RecordingWebSocket()); // stamps a one-millisecond reconnect delay

      final var attempt = ws.connect();

      assertNotNull(attempt);
      assertNotNull(scheduler.connectHandle);
      assertFalse(scheduler.connectHandle.isCancelled(),
          "a wake which already claimed the current attempt must not be cancelled as stale");
      assertFalse(builder.interrupted.get(), "the valid build must retain its worker");

      releaseBuild.countDown();
      assertSame(built, attempt.toCompletableFuture().orTimeout(500L, TimeUnit.MILLISECONDS).join());
      assertFalse(scheduler.connectHandle.isCancelled());
      assertFalse(builder.interrupted.get());
    } finally {
      releaseBuild.countDown();
      ws.close();
      scheduler.shutdownNow();
    }
  }

  /// The bridge exposed to callers is not the operation that owns the HTTP upgrade. close() must
  /// cancel the actual future returned by buildAsync as well, otherwise a handshake which never
  /// settles keeps its AttemptListener and this websocket retained indefinitely.
  @Test
  void closeCancelsTheActualImmediateBuilderFuture() {
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new SynchronousOpenPendingBuilder(socket);
    webSocketBuilder.invokeOnOpen = false;
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), new RecordingScheduler(), null, (_, _, _) -> {
        }, null, null, null);
    assertNotNull(ws.connect());

    ws.close();

    assertTrue(webSocketBuilder.completion.isCancelled(),
        "close must cancel the builder-owned handshake, not only its internal bridge");
  }

  /// Once a deferred task has entered buildAsync, its builder future is the same owned handshake
  /// as an immediate attempt. Cancelling only the outer future returned to connect() leaves that
  /// inner operation retaining the per-attempt listener.
  @Test
  void closeCancelsTheActualInjectedDeferredBuilderFuture() {
    final var first = new RecordingWebSocket();
    final var built = new RecordingWebSocket();
    final var webSocketBuilder = new SynchronousOpenPendingBuilder(built);
    webSocketBuilder.invokeOnOpen = false;
    final var scheduler = new RecordingScheduler();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        }, null, null, null);
    ws.onOpen(first);
    assertNotNull(ws.connect());
    scheduler.deferred.getFirst().task().run();

    ws.close();

    assertTrue(webSocketBuilder.completion.isCancelled(),
        "close must cancel a handshake after its deferred build task has started");
  }

  /// A normally completed builder future does not imply listener adoption has happened. Starting
  /// the next generation must release that successful but still-unadopted predecessor; relying on
  /// a late onOpen to abort it makes transport release depend on a callback that may never arrive.
  @Test
  void aSupersedingAttemptAbortsTheSuccessfulSocketStillAwaitingAdoption() {
    final var first = new RecordingWebSocket();
    final var scheduler = new RecordingScheduler();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>(), first);
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        }, null, null, null);
    try {
      final var completed = ws.connect();
      assertNotNull(completed);
      assertSame(first, completed.join(), "the first handshake has produced a socket");

      assertNotNull(ws.connect());

      assertTrue(first.aborted,
          "starting its successor must release a successful socket still awaiting onOpen");
    } finally {
      ws.close();
    }
  }

  /// close() may win before buildAsync produces its socket. Cancelling the bridge prevents that
  /// later result from becoming its value, but it must not make the socket ownerless: a successful
  /// result delivered after close is aborted even when onOpen remains queued forever.
  @Test
  void anImmediateBuildCompletingAfterCloseAbortsItsUnadoptedSocket() {
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new SynchronousOpenPendingBuilder(socket, new UncancellableWebSocketFuture());
    webSocketBuilder.invokeOnOpen = false;
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), new RecordingScheduler(), null, (_, _, _) -> {
        }, null, null, null);
    assertNotNull(ws.connect());

    ws.close();
    assertTrue(webSocketBuilder.completion.complete(socket));

    assertTrue(socket.aborted,
        "a socket produced after its cancelled bridge must be aborted without waiting for onOpen");
  }

  /// The same ownership rule applies after a deferred task has started buildAsync. Cancelling the
  /// outer connected future does not cancel the builder future it is bridging, so a later success
  /// must be observed and aborted rather than silently discarded.
  @Test
  void anInjectedDeferredBuildCompletingAfterCloseAbortsItsUnadoptedSocket() {
    final var first = new RecordingWebSocket();
    final var built = new RecordingWebSocket();
    final var webSocketBuilder = new SynchronousOpenPendingBuilder(built, new UncancellableWebSocketFuture());
    webSocketBuilder.invokeOnOpen = false;
    final var scheduler = new RecordingScheduler();
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), scheduler, null, (_, _, _) -> {
        }, null, null, null);
    ws.onOpen(first); // stamps the reconnect throttle
    assertNotNull(ws.connect());
    assertEquals(1, scheduler.deferred.size());
    scheduler.deferred.getFirst().task().run(); // buildAsync has started and remains pending

    ws.close();
    assertTrue(webSocketBuilder.completion.complete(built));

    assertTrue(built.aborted,
        "a deferred builder success after close must be aborted without waiting for onOpen");
  }

  /// Binary frames are rejected only while their socket is current. The attempt listener must
  /// apply the same stale/closed fence as every other entry before delegating to that rejection;
  /// otherwise an old socket can still throw into the JDK after takeover or explicit close.
  @Test
  void binaryRejectionIsFencedForStaleAndClosedAttemptListeners() {
    final var first = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>(), first);
    final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), new RecordingScheduler(), null, (_, _, _) -> {
        }, null, null, null);
    try {
      assertNotNull(ws.connect());
      final var listener = webSocketBuilder.listeners.getFirst();
      listener.onOpen(first);
      assertThrows(UnsupportedOperationException.class,
          () -> listener.onBinary(first, ByteBuffer.wrap(new byte[]{1}), true),
          "a binary frame on the live JSON transport remains a protocol violation");

      final var second = new RecordingWebSocket();
      ws.onOpen(second);
      assertNull(assertDoesNotThrow(
          () -> listener.onBinary(first, ByteBuffer.wrap(new byte[]{2}), true),
          "the displaced attempt's late binary callback is ignored"));

      ws.close();
      assertNull(assertDoesNotThrow(
          () -> ws.onBinary(second, ByteBuffer.wrap(new byte[]{3}), true),
          "a reciprocal callback after explicit close is ignored"));
    } finally {
      ws.close();
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
      // 500ms, not 5s: the real path completes in ~25ms, while a stranded token never
      // completes at all. PIT's watchdog fires at roughly timeoutFactor x normal +
      // timeoutConst (~1.6s here), so a budget above it hands the mutant a TIMED_OUT — a
      // detection that reads as load-dependent noise — instead of a failed assertion.
      assertDoesNotThrow(() -> deferred.toCompletableFuture()
          .orTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS).join());
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

  /// Every attempt's listener delegates to the engine, and onError is the one delegation whose
  /// removal is silent: the transport dies, nothing reaches the consumer's seam, and the
  /// instance sits believing it is connected. Driving the BUILDER's listener rather than the
  /// engine's own callback is what exercises the delegation instead of bypassing it.
  @Test
  void theAttemptListenerRoutesTransportErrorsThroughTheEngine() {
    final var errors = new ArrayList<Throwable>();
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>(), socket);
    try (final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), new RecordingScheduler(), null, (_, _, _) -> {
        }, (_, error) -> errors.add(error), null, null)) {
      assertNotNull(ws.connect());
      final var listener = webSocketBuilder.listeners.getFirst();
      listener.onOpen(socket);

      listener.onError(socket, new IllegalStateException("transport died"));

      assertEquals(1, errors.size(), "the attempt listener must route transport errors to the engine");
      assertEquals("transport died", errors.getFirst().getMessage());
    }
  }

  /// Escalation can fire from any pass, including one an inbound pong drives — and that pass
  /// owns the same obligation as the check cycle: deliver the escalation to the error seam
  /// after releasing the lock. Dropping the delivery leaves the connection aborted with the
  /// consumer never told, which is the silent-death case the deadline exists to prevent.
  @Test
  void aPongDrivenPassDeliversItsEscalation() {
    final var clock = new TestClock();
    final var errors = new ArrayList<Throwable>();
    try (final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null,
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock,
        new RecordingExecutor(), null, null, (_, _, _) -> {
        }, (_, error) -> errors.add(error), null, null)) {
      assertTrue(ws.accountSubscribe(
          software.sava.core.accounts.PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112"),
          _ -> {
          }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // the subscribe transmits; its answer deadline starts here

      clock.advanceMillis(TIMINGS.subscriptionResendDelay()
          * SolanaJsonRpcWebsocket.UNANSWERED_ESCALATION_FACTOR + 1);
      ws.onPong(socket, ByteBuffer.wrap(new byte[0]));

      assertTrue(socket.aborted, "the unanswered request retires the transport");
      assertEquals(1, errors.size(), "the pong-driven pass must deliver its escalation");
    }
  }

  /// The immediate branch's build settles synchronously under the recording builder, so the
  /// attempt it hands back must already be done when connect() returns. Asserting that is what
  /// makes a dropped completion bridge — or a completion handler that strands the future by
  /// feeding completeExceptionally a null — fail outright. Without it the caller simply parks,
  /// and PIT records a load-dependent timeout where a deterministic kill was available.
  @Test
  void anImmediateAttemptIsSettledWhenConnectReturns() {
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>(), socket);
    try (final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), new RecordingScheduler(), null, (_, _, _) -> {
        }, null, null, null)) {
      final var attempt = ws.connect();

      assertNotNull(attempt);
      assertTrue(attempt.toCompletableFuture().isDone(),
          "the immediate branch's bridge must settle the attempt before connect() returns");
      assertFalse(attempt.toCompletableFuture().isCompletedExceptionally(),
          "a successful build must settle the attempt successfully, not strand or fail it");
      assertSame(socket, attempt.toCompletableFuture().join());
    }
  }

  /// Every callback the JDK delivers arrives at the attempt's own listener, not at the engine
  /// directly — and each one is a delegation that can be deleted without any other test
  /// noticing, because every other test calls the engine's methods itself. Driving the
  /// BUILDER's listener is what exercises the wiring the JDK will actually use: a dropped
  /// delegation means frames, control frames, or the peer's close never reach the engine at
  /// all, and the instance sits healthy-looking and deaf.
  @Test
  void theAttemptListenerDelegatesEveryCallbackToTheEngine() {
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>(), socket);
    final var closes = new ArrayList<String>();
    final var clock = new TestClock();
    try (final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        webSocketBuilder.connectTimeout(Duration.ofMillis(1_000)),
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock,
        new RecordingExecutor(), new RecordingScheduler(), null,
        (_, statusCode, reason) -> closes.add(statusCode + ":" + reason), null, null, null)) {
      final var slots = new ArrayList<software.sava.rpc.json.http.response.ProcessedSlot>();
      assertTrue(ws.slotSubscribe(slots::add));
      assertNotNull(ws.connect());
      final var listener = webSocketBuilder.listeners.getFirst();
      listener.onOpen(socket); // adoption through the listener, not the engine

      // onText: the confirmation and a notification must reach the dispatch path
      listener.onText(socket, java.nio.CharBuffer.wrap("""
          {"jsonrpc":"2.0","result":31,"id":2}"""), true);
      listener.onText(socket, java.nio.CharBuffer.wrap("""
          {"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":1,"root":1,"slot":7},"subscription":31}}"""), true);
      assertEquals(1, slots.size(), "onText must reach the engine's dispatch");

      // onPing: the engine answers a peer ping by stamping liveness and driving a pass
      final long beforePing = ws.lastMessageReceivedTimestamp();
      listener.onPing(socket, ByteBuffer.wrap("p".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
      // onPong: same, and neither counts as a message — the stamp is the text path's alone
      listener.onPong(socket, ByteBuffer.wrap(new byte[0]));
      assertEquals(beforePing, ws.lastMessageReceivedTimestamp(),
          "control frames are peer contact, never messages");

      // onClose: the peer's close must reach the handler that decides policy
      listener.onClose(socket, java.net.http.WebSocket.NORMAL_CLOSURE, "peer close");
      assertEquals(1, closes.size(), "onClose must reach the engine's close handling");
      assertEquals("1000:peer close", closes.getFirst());
    }
  }
}
