package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.SolanaNetwork;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// The builder is the whole public entry point to the websocket client, and its
/// defaults are the timings a caller inherits without saying anything.
final class SolanaRpcWebsocketBuilderTests {

  private static SolanaRpcWebsocket.Builder builder() {
    return SolanaRpcWebsocket.build();
  }

  @Test
  void defaultsAreTheDocumentedTimings() {
    final var builder = builder();
    assertEquals(3_000L, builder.reConnectDelay());
    assertEquals(15_000L, builder.pingDelay());
    assertEquals(2_000L, builder.subscriptionAndPingCheckDelay());
    assertEquals(Commitment.CONFIRMED, builder.commitment());
    assertSame(SolanaAccounts.MAIN_NET, builder.solanaAccounts());
  }

  @Test
  void unsetValuesStayNull() {
    final var builder = builder();
    assertNull(builder.wsUri());
    assertNull(builder.webSocketBuilder());
    assertNull(builder.onOpen());
    assertNull(builder.onClose());
    assertNull(builder.onError());
    assertNull(builder.onSendTextError());
    assertNull(builder.onPingError());
  }

  @Test
  void settersRoundTripAndChain() {
    final var uri = URI.create("wss://example.invalid");
    final var webSocketBuilder = HttpClient.newHttpClient().newWebSocketBuilder();
    final var builder = builder()
        .uri(uri)
        .webSocketBuilder(webSocketBuilder)
        .reConnectDelay(1_111L)
        .pingDelay(2_222L)
        .subscriptionAndPingCheckDelay(3_333L)
        .commitment(Commitment.FINALIZED)
        .solanaAccounts(SolanaAccounts.MAIN_NET);

    assertEquals(uri, builder.wsUri());
    assertSame(webSocketBuilder, builder.webSocketBuilder());
    assertEquals(1_111L, builder.reConnectDelay());
    assertEquals(2_222L, builder.pingDelay());
    assertEquals(3_333L, builder.subscriptionAndPingCheckDelay());
    assertEquals(Commitment.FINALIZED, builder.commitment());
  }

  @Test
  void callbacksRoundTrip() {
    final var opened = new AtomicReference<SolanaRpcWebsocket>();
    final SolanaRpcWebsocket.OnClose onClose = (_, _, _) -> {
    };
    final var builder = builder()
        .onOpen(opened::set)
        .onClose(onClose)
        .onError((_, _) -> {
        })
        .onSendTextError((_, _) -> {
        })
        .onPingError((_, _) -> {
        });

    assertNotNull(builder.onOpen());
    assertSame(onClose, builder.onClose());
    assertNotNull(builder.onError());
    assertNotNull(builder.onSendTextError());
    assertNotNull(builder.onPingError());
  }

  /// create() derives the connect timeout from the reconnect delay, so a caller
  /// tuning reconnects does not also have to remember to tune the timeout.
  @Test
  void createAppliesReconnectDelayAsTheConnectTimeout() {
    final var recorded = new AtomicReference<Duration>();
    final var webSocketBuilder = new RecordingWebSocketBuilder(recorded);

    final var websocket = builder()
        .uri(SolanaNetwork.MAIN_NET.getWebSocketEndpoint())
        .webSocketBuilder(webSocketBuilder)
        .reConnectDelay(7_500L)
        .create();

    assertNotNull(websocket);
    assertEquals(Duration.ofMillis(7_500L), recorded.get());
    assertEquals(SolanaNetwork.MAIN_NET.getWebSocketEndpoint(), websocket.endpoint());
  }

  @Test
  void createCarriesTheConfiguredCommitmentAndAccounts() {
    final var websocket = builder()
        .uri(SolanaNetwork.DEV_NET.getWebSocketEndpoint())
        .webSocketBuilder(new RecordingWebSocketBuilder(new AtomicReference<>()))
        .commitment(Commitment.FINALIZED)
        .create();

    assertEquals(Commitment.FINALIZED, websocket.defaultCommitment());
    assertEquals(SolanaNetwork.DEV_NET.getWebSocketEndpoint(), websocket.endpoint());
  }

  /// A websocket builder is required — create() dereferences it to set the
  /// connect timeout, so leaving it unset fails immediately rather than at
  /// connect time.
  @Test
  void createWithoutAWebSocketBuilderFails() {
    final var builder = builder().uri(URI.create("wss://example.invalid"));
    assertThrows(NullPointerException.class, builder::create);
  }

  @Test
  void uriStringOverloadParses() {
    final var builder = builder().uri("wss://example.invalid");
    assertEquals(URI.create("wss://example.invalid"), builder.wsUri());
  }

  @Test
  void uriNetworkOverloadUsesTheNetworkEndpoint() {
    final var builder = builder().uri(SolanaNetwork.DEV_NET);
    assertEquals(SolanaNetwork.DEV_NET.getWebSocketEndpoint(), builder.wsUri());
  }

  @Test
  void clockDefaultsToSystemAndRoundTrips() {
    final var builder = builder();
    assertSame(NanoClock.SYSTEM, builder.clock());
    final var clock = new TestClock();
    assertSame(clock, builder.clock(clock).clock());
  }

  /// Null (the default) means an internally created executor that close() shuts
  /// down; an injected one runs the check loop but stays the caller's to manage.
  /// The setter is package-private on the impl — not public API — so the test
  /// casts the builder.
  @Test
  void executorServiceDefaultsNullAndAnInjectedOneIsNotShutDownByClose() {
    final var builder = (SolanaRpcWebsocketBuilder) builder();
    assertNull(builder.executorService());

    final var executor = new RecordingExecutor();
    assertSame(executor, builder.executorService(executor).executorService());

    assertNull(builder.scheduler());
    final var scheduler = new RecordingScheduler();
    assertSame(scheduler, builder.scheduler(scheduler).scheduler());
    builder.scheduler(null);

    final var websocket = builder
        .uri(URI.create("wss://example.invalid"))
        .webSocketBuilder(new RecordingWebSocketBuilder(new AtomicReference<>()))
        .clock(new TestClock())
        .create();
    assertEquals(1, executor.tasks.size(), "create() submits the check loop to the injected executor");

    websocket.close();
    assertFalse(executor.shutdown, "close() must not shut down an executor it does not own");
  }

  /// connect() with no prior write attempts immediately; the builder's clock and
  /// websocket builder are what it runs against.
  @Test
  void connectBuildsImmediatelyWhenIdle() {
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>(), socket);
    final var endpoint = URI.create("wss://example.invalid");
    try (final var websocket = (SolanaJsonRpcWebsocket) builder()
        .uri(endpoint)
        .webSocketBuilder(webSocketBuilder)
        .clock(new TestClock())
        .create()) {
      final var future = websocket.connect();
      assertNotNull(future);
      assertSame(socket, future.toCompletableFuture().join());
      assertEquals(endpoint, webSocketBuilder.builtUri.get());

      // the attempt marked lastWrite, so the next check is inside the ping window
      websocket.onPing(socket, java.nio.ByteBuffer.wrap(new byte[0]));
      assertEquals(0, socket.pings, "connect() must count as the last write");
    }
  }

  /// With an injected scheduler, a deferred connect is a captured task with an
  /// exact delay — no waiting, no races: the test steps the clock, runs the task,
  /// and the future completes with the built socket.
  @Test
  void connectSchedulesOnTheInjectedSchedulerInsideTheWindow() {
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>(), socket);
    final var clock = new TestClock();
    final var scheduler = new RecordingScheduler();
    final var endpoint = URI.create("wss://example.invalid");
    try (final var websocket = (SolanaJsonRpcWebsocket) ((SolanaRpcWebsocketBuilder) builder())
        .scheduler(scheduler)
        .uri(endpoint)
        .webSocketBuilder(webSocketBuilder)
        .clock(clock)
        .reConnectDelay(60_000L)
        .create()) {
      assertTrue(websocket.rootSubscribe(_ -> {
      }));
      websocket.onOpen(socket);

      final var future = websocket.connect();
      assertNotNull(future);
      assertFalse(future.toCompletableFuture().isDone());
      assertEquals(1, scheduler.deferred.size());
      assertEquals(60_000L, scheduler.deferred.getFirst().delay(), "nothing has elapsed, so the full window defers");
      assertNull(webSocketBuilder.builtUri.get(), "no build before the delay elapses");

      clock.advanceMillis(60_000L);
      scheduler.deferred.getFirst().task().run();
      assertSame(socket, future.toCompletableFuture().join());
      assertEquals(endpoint, webSocketBuilder.builtUri.get());

      // the scheduled attempt marked lastWrite at its run time
      websocket.onPing(socket, java.nio.ByteBuffer.wrap(new byte[0]));
      assertEquals(0, socket.pings, "the deferred connect must count as the last write");
    }
  }

  /// The scheduled delay is the unelapsed remainder of the window, not the whole
  /// window.
  @Test
  void connectSchedulerDelayReflectsElapsedTime() {
    final var socket = new RecordingWebSocket();
    final var clock = new TestClock();
    final var scheduler = new RecordingScheduler();
    try (final var websocket = (SolanaJsonRpcWebsocket) ((SolanaRpcWebsocketBuilder) builder())
        .scheduler(scheduler)
        .uri(URI.create("wss://example.invalid"))
        .webSocketBuilder(new RecordingWebSocketBuilder(new AtomicReference<>(), socket))
        .clock(clock)
        .reConnectDelay(60_000L)
        .create()) {
      assertTrue(websocket.rootSubscribe(_ -> {
      }));
      websocket.onOpen(socket);

      clock.advanceMillis(13_000L);
      assertNotNull(websocket.connect());
      assertEquals(1, scheduler.deferred.size());
      assertEquals(47_000L, scheduler.deferred.getFirst().delay());
    }
  }

  /// At exactly the window edge the attempt is immediate: the build happens
  /// synchronously and nothing is handed to the scheduler.
  @Test
  void connectAtTheWindowEdgeIsImmediate() {
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>(), socket);
    final var clock = new TestClock();
    final var scheduler = new RecordingScheduler();
    try (final var websocket = (SolanaJsonRpcWebsocket) ((SolanaRpcWebsocketBuilder) builder())
        .scheduler(scheduler)
        .uri(URI.create("wss://example.invalid"))
        .webSocketBuilder(webSocketBuilder)
        .clock(clock)
        .reConnectDelay(60_000L)
        .create()) {
      assertTrue(websocket.rootSubscribe(_ -> {
      }));
      websocket.onOpen(socket);

      clock.advanceMillis(60_000L);
      final var future = websocket.connect();
      assertNotNull(future);
      assertTrue(scheduler.deferred.isEmpty(), "the window has fully elapsed; nothing to defer");
      assertNotNull(webSocketBuilder.builtUri.get(), "the build happens synchronously");
      assertSame(socket, future.toCompletableFuture().join());
    }
  }

  /// A failed build on the scheduled path surfaces through the returned future.
  @Test
  void connectFailureOnTheSchedulerPathSurfaces() {
    final var clock = new TestClock();
    final var scheduler = new RecordingScheduler();
    // no connect result: buildAsync fails
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>());
    try (final var websocket = (SolanaJsonRpcWebsocket) ((SolanaRpcWebsocketBuilder) builder())
        .scheduler(scheduler)
        .uri(URI.create("wss://example.invalid"))
        .webSocketBuilder(webSocketBuilder)
        .clock(clock)
        .reConnectDelay(60_000L)
        .create()) {
      assertTrue(websocket.rootSubscribe(_ -> {
      }));
      websocket.onOpen(new RecordingWebSocket());

      final var future = websocket.connect();
      scheduler.deferred.getFirst().task().run();
      assertTrue(future.toCompletableFuture().isCompletedExceptionally(),
          "a failed build must fail the deferred future");
    }
  }

  /// A write inside the reconnect window defers the attempt; the returned future
  /// completes only after the delay, which this test never waits out.
  @Test
  void connectIsDeferredInsideTheReconnectWindow() {
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>(), socket);
    try (final var websocket = (SolanaJsonRpcWebsocket) builder()
        .uri(URI.create("wss://example.invalid"))
        .webSocketBuilder(webSocketBuilder)
        .clock(new TestClock())
        .reConnectDelay(60_000L)
        .create()) {
      assertTrue(websocket.rootSubscribe(_ -> {
      }));
      websocket.onOpen(socket);
      assertEquals(1, socket.sentText.size(), "the write that arms the reconnect window");

      final var future = websocket.connect();
      assertNotNull(future);
      assertFalse(future.toCompletableFuture().isDone(), "the attempt is deferred by the reconnect window");
    }
  }

  /// The deferred branch does eventually build. The 25ms delay is the one real
  /// wait in this suite — connect()'s deferral runs on a delayed executor there
  /// is no seam for.
  @Test
  void connectRunsOnceTheReconnectDelayElapses() {
    final var socket = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>(), socket);
    try (final var websocket = (SolanaJsonRpcWebsocket) builder()
        .uri(URI.create("wss://example.invalid"))
        .webSocketBuilder(webSocketBuilder)
        .clock(new TestClock())
        .reConnectDelay(25L)
        .create()) {
      assertTrue(websocket.rootSubscribe(_ -> {
      }));
      websocket.onOpen(socket);

      final var future = websocket.connect();
      assertNotNull(future);
      assertSame(socket, future.toCompletableFuture().join());
    }
  }
}
