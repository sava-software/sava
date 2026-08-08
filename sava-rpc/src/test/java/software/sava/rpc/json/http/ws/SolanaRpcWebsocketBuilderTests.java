package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.SolanaNetwork;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.nio.CharBuffer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// The builder is the whole public entry point to the websocket client, and its
/// defaults are the timings a caller inherits without saying anything.
final class SolanaRpcWebsocketBuilderTests {

  /// The concrete builder, not the public interface: `clock`, `executorService` and
  /// `scheduler` are package-private seams that deliberately do not appear on
  /// `SolanaRpcWebsocket.Builder`, and every setter here returns the concrete type so
  /// a chain keeps reaching them.
  private static SolanaRpcWebsocketBuilder builder() {
    return new SolanaRpcWebsocketBuilder();
  }

  @Test
  void defaultsAreTheDocumentedTimings() {
    final var builder = builder();
    assertEquals(3_000L, builder.reConnectDelay());
    assertEquals(15_000L, builder.pingDelay());
    assertEquals(2_000L, builder.subscriptionAndPingCheckDelay());
    assertEquals(8_000L, builder.connectTimeout());
    // 2x the 15s ping delay. Spelled as a literal rather than as pingDelay() times the factor:
    // asserting the derivation against its own constants restates the code instead of pinning
    // what it produces, and would keep passing if either changed.
    assertEquals(30_000L, builder.keepAliveDelay());
    // reConnectDelay 3s, floored at the 2s check delay, so the floor does not bind at defaults
    assertEquals(3_000L, builder.subscriptionResendDelay());
    assertEquals(Commitment.CONFIRMED, builder.commitment());
    assertSame(SolanaAccounts.MAIN_NET, builder.solanaAccounts());
  }

  /// The re-send deadline used to be `reConnectDelay` itself, and the two disagree about zero: as
  /// a reconnect throttle it coherently means "do not throttle", but as a re-send deadline it
  /// means "re-send whenever a millisecond has passed" — and since the re-send is driven from
  /// every inbound frame, that is a re-send per frame for as long as anything is unconfirmed.
  /// Giving the re-send its own setting is what lets `reConnectDelay(0)` mean only what it says.
  @Test
  void anUnthrottledReconnectDoesNotUnpaceTheSubscriptionResend() {
    final var builder = (SolanaRpcWebsocketBuilder) builder().reConnectDelay(0L);

    assertEquals(0L, builder.reConnectDelay(), "zero is a legal reconnect throttle: do not throttle");
    assertEquals(builder.subscriptionAndPingCheckDelay(), builder.subscriptionResendDelay(),
        "but the re-send floors at the check delay rather than following it to zero");

    final var timings = new Timings(0L, 15_000L, 2_000L);
    assertEquals(2_000L, timings.subscriptionResendDelay());
    assertEquals(0L, timings.reConnectDelay());
  }

  /// The floor applies to the derived default only. A caller who states a re-send delay means it,
  /// including one below the check cadence.
  @Test
  void anExplicitSubscriptionResendDelayIsNotFloored() {
    final var builder = (SolanaRpcWebsocketBuilder) builder()
        .reConnectDelay(0L)
        .subscriptionResendDelay(1L);
    assertEquals(1L, builder.subscriptionResendDelay());
    assertEquals(1L, new Timings(0L, 15_000L, 2_000L, 30_000L, 1L).subscriptionResendDelay());

    for (final long bad : new long[]{0L, -1L}) {
      assertThrows(IllegalArgumentException.class, () -> builder().subscriptionResendDelay(bad),
          "subscriptionResendDelay " + bad + " must be rejected");
    }
  }

  /// Timings validates its own components, so a value that arrives without passing a builder
  /// setter is checked too. Zero is legal for exactly the two delays where it says something:
  /// "do not throttle reconnects", and "the check loop never parks".
  @Test
  void timingsRejectsIncoherentDelays() {
    assertDoesNotThrow(() -> new Timings(0L, 15_000L, 2_000L), "zero reconnect throttle is legal");
    assertDoesNotThrow(() -> new Timings(3_000L, 15_000L, 0L), "a never-parking check loop is legal");
    // both documented-legal zeros together: the derived re-send deadline floors at 1 rather
    // than letting the record's own validation reject a combination of legal values
    assertEquals(1L, new Timings(0L, 15_000L, 0L).subscriptionResendDelay());

    assertThrows(IllegalArgumentException.class, () -> new Timings(-1L, 15_000L, 2_000L));
    assertThrows(IllegalArgumentException.class, () -> new Timings(3_000L, 15_000L, -1L));
    // zero here would ping every cycle and every inbound frame rather than at a cadence
    assertThrows(IllegalArgumentException.class, () -> new Timings(3_000L, 0L, 2_000L));
    assertThrows(IllegalArgumentException.class, () -> new Timings(3_000L, -1L, 2_000L));
    // through the canonical constructor too: the three-argument form derives keepAliveDelay
    // from pingDelay, so its guard fires first there and would mask a deleted pingDelay check
    assertThrows(IllegalArgumentException.class, () -> new Timings(3_000L, 0L, 2_000L, 30_000L, 3_000L));
    assertThrows(IllegalArgumentException.class, () -> new Timings(3_000L, 15_000L, 2_000L, 0L));
    assertThrows(IllegalArgumentException.class, () -> new Timings(3_000L, 15_000L, 2_000L, 30_000L, 0L));
  }

  /// Existing callers of the four-argument form get the historical meaning: the re-send follows
  /// the reconnect delay.
  @Test
  void theFourArgumentTimingsKeepsTheHistoricalResendDeadline() {
    assertEquals(9_000L, new Timings(9_000L, 15_000L, 2_000L, 30_000L).subscriptionResendDelay());
    assertEquals(9_000L, new Timings(9_000L, 15_000L, 2_000L).subscriptionResendDelay());
  }

  /// A very large ping delay is how a caller disables pinging, so the derived keep-alive must not
  /// wrap into a negative delay — that reads as long overdue and turns "never ping" into a ping
  /// on every check cycle. The boundary is the first value whose doubling overflows.
  @Test
  void disablingPingsDoesNotInvertIntoAKeepAlivePing() {
    for (final long pingDelay : new long[]{Long.MAX_VALUE, Long.MAX_VALUE / 2 + 1, Long.MAX_VALUE - 1}) {
      final var timings = new Timings(3_000L, pingDelay, 2_000L);
      assertTrue(timings.keepAliveDelay() > 0,
          "pingDelay " + pingDelay + " derived a non-positive keepAliveDelay: " + timings.keepAliveDelay());
      assertTrue(timings.keepAliveDelay() >= pingDelay,
          "a keep-alive poke must never come sooner than the ping it is derived from");

      assertTrue(((SolanaRpcWebsocketBuilder) builder().pingDelay(pingDelay)).keepAliveDelay() > 0,
          "the builder's getter must agree with the Timings it would build");
    }
    // Below the boundary the derivation is still the plain multiple.
    assertEquals(2L * (Long.MAX_VALUE / 2), new Timings(3_000L, Long.MAX_VALUE / 2, 2_000L).keepAliveDelay());
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

  /// The connect timeout is its own budget. It used to be derived from the reconnect delay,
  /// which meant a caller pacing retries also silently shortened the handshake budget — and the
  /// 3s default that pacing wants is tight for a cold TLS handshake to a shared public endpoint.
  @Test
  void theConnectTimeoutIsIndependentOfTheReconnectDelay() {
    final var recorded = new AtomicReference<Duration>();
    final var websocket = builder()
        .uri(SolanaNetwork.MAIN_NET.getWebSocketEndpoint())
        .webSocketBuilder(new RecordingWebSocketBuilder(recorded))
        .reConnectDelay(7_500L)
        .create();

    assertNotNull(websocket);
    assertEquals(Duration.ofMillis(SolanaRpcWebsocketBuilder.DEFAULT_CONNECT_TIMEOUT), recorded.get(),
        "pacing retries must not shorten the handshake budget");
    assertEquals(SolanaNetwork.MAIN_NET.getWebSocketEndpoint(), websocket.endpoint());
    websocket.close();

    final var tuned = new AtomicReference<Duration>();
    final var explicit = builder()
        .uri(SolanaNetwork.MAIN_NET.getWebSocketEndpoint())
        .webSocketBuilder(new RecordingWebSocketBuilder(tuned))
        .reConnectDelay(7_500L)
        .connectTimeout(1_250L)
        .create();
    assertEquals(Duration.ofMillis(1_250L), tuned.get(), "an explicit handshake budget is what is applied");
    assertEquals(1_250L, ((SolanaRpcWebsocketBuilder) builder().connectTimeout(1_250L)).connectTimeout());
    explicit.close();
  }

  @Test
  void aNonPositiveConnectTimeoutIsRejected() {
    for (final long bad : new long[]{0L, -1L}) {
      assertThrows(IllegalArgumentException.class, () -> builder().connectTimeout(bad),
          "connectTimeout " + bad + " must be rejected");
    }
  }

  @Test
  void createCarriesTheConfiguredCommitmentAndAccounts() {
    // try-with-resources: with no injected executor this create() starts a real, non-daemon
    // check-loop thread, and only close() returns it.
    try (final var websocket = builder()
        .uri(SolanaNetwork.DEV_NET.getWebSocketEndpoint())
        .webSocketBuilder(new RecordingWebSocketBuilder(new AtomicReference<>()))
        .commitment(Commitment.FINALIZED)
        .create()) {
      assertEquals(Commitment.FINALIZED, websocket.defaultCommitment());
      assertEquals(SolanaNetwork.DEV_NET.getWebSocketEndpoint(), websocket.endpoint());
    }
  }

  /// create() fails on an unset uri immediately. The constructor starts the check loop on a
  /// non-daemon thread before anything reads the endpoint, so deferring the failure left a
  /// stray thread running behind an unlabelled NPE from inside the first connect.
  @Test
  void createWithoutAUriFails() {
    final var builder = builder()
        .webSocketBuilder(new RecordingWebSocketBuilder(new AtomicReference<>()));
    final var ex = assertThrows(NullPointerException.class, builder::create);
    assertTrue(ex.getMessage().contains("uri"), ex.getMessage());
  }

  /// The throttle window is exclusive of its own edge: at exactly reConnectDelay since the
  /// last attempt, the next one launches immediately rather than deferring a full extra window.
  ///
  /// This pins the behaviour, not the comparison operator: at the edge both < and <= yield a
  /// zero deferral, and the delay-then-dispatch structure sends both down the identical
  /// immediate path — the boundary mutant is equivalent by construction, and no test can
  /// distinguish it.
  @Test
  void connectAtExactlyTheWindowEdgeIsImmediate() {
    final var replacement = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>(), replacement);
    final var clock = new TestClock();
    try (final var websocket = (SolanaJsonRpcWebsocket) ((SolanaRpcWebsocketBuilder) builder())
        .uri(URI.create("wss://example.invalid"))
        .webSocketBuilder(webSocketBuilder)
        .clock(clock)
        .create()) {
      websocket.onOpen(new RecordingWebSocket());
      clock.advanceMillis(3_000L); // exactly the default reConnectDelay
      assertNotNull(websocket.connect());
      assertNotNull(webSocketBuilder.builtUri.get(),
          "at the window edge the attempt is immediate, not deferred");
    }
  }

  /// The headline of the takeover fix, pinned where connect() is drivable: abandoning a socket
  /// without aborting it leaves `this` as its listener and its demand live, so it keeps writing
  /// into state that now describes the replacement connection.
  @Test
  void connectAbortsTheSocketItAbandons() {
    final var replacement = new RecordingWebSocket();
    final var webSocketBuilder = new RecordingWebSocketBuilder(new AtomicReference<>(), replacement);
    final var clock = new TestClock();
    try (final var websocket = (SolanaJsonRpcWebsocket) ((SolanaRpcWebsocketBuilder) builder())
        .uri(URI.create("wss://example.invalid"))
        .webSocketBuilder(webSocketBuilder)
        .clock(clock)
        .create()) {
      final var abandoned = new RecordingWebSocket();
      websocket.onOpen(abandoned);
      clock.advanceMillis(3_001L); // past the default reConnectDelay, so the attempt is immediate
      final var future = websocket.connect();
      assertNotNull(future);
      assertTrue(abandoned.aborted, "connect() must abort the socket it abandons, not merely drop it");
    }
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

  /// The keep-alive is a property of the network path, so it is settable; left alone it tracks
  /// the ping delay, so tuning only the detection deadline still moves it proportionately.
  @Test
  void keepAliveDelayDefaultsToAMultipleOfThePingDelayAndIsSettable() {
    assertEquals(15_000L * Timings.DEFAULT_KEEP_ALIVE_FACTOR, builder().keepAliveDelay(),
        "the default must follow the default ping delay");
    assertEquals(45_000L * Timings.DEFAULT_KEEP_ALIVE_FACTOR, builder().pingDelay(45_000L).keepAliveDelay(),
        "an unset keep-alive must follow whatever ping delay was chosen");
    assertEquals(7_500L, builder().pingDelay(45_000L).keepAliveDelay(7_500L).keepAliveDelay(),
        "an explicit keep-alive wins over the derived one");

    // and the choice reaches the built websocket either way
    final var endpoint = URI.create("wss://example.invalid");
    try (final var derived = (SolanaJsonRpcWebsocket) builder()
        .uri(endpoint)
        .webSocketBuilder(new RecordingWebSocketBuilder(new AtomicReference<>(), new RecordingWebSocket()))
        .pingDelay(45_000L)
        .create()) {
      assertEquals(45_000L * Timings.DEFAULT_KEEP_ALIVE_FACTOR, derived.timings().keepAliveDelay());
    }

    try (final var explicit = (SolanaJsonRpcWebsocket) builder()
        .uri(endpoint)
        .webSocketBuilder(new RecordingWebSocketBuilder(new AtomicReference<>(), new RecordingWebSocket()))
        .pingDelay(45_000L)
        .keepAliveDelay(7_500L)
        .create()) {
      assertEquals(7_500L, explicit.timings().keepAliveDelay());
    }
  }

  /// Zero is how the builder records "not given", so accepting it would hand back the derived
  /// default while looking like it took the caller's answer.
  @Test
  void aNonPositiveKeepAliveDelayIsRejected() {
    for (final long bad : new long[]{0L, -1L, Long.MIN_VALUE}) {
      final var failure = assertThrows(IllegalArgumentException.class,
          () -> builder().keepAliveDelay(bad), "keepAliveDelay " + bad + " must be rejected");
      assertTrue(String.valueOf(failure.getMessage()).contains("keepAliveDelay"), failure.getMessage());
    }
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

      // The attempt marked lastWrite, and the reconnect throttle is what reads it: a second
      // connect() with nothing elapsed must defer rather than build again. Asserted through the
      // throttle rather than through the ping window, which no longer depends on lastWrite —
      // the ping asks whether the peer is there, and our own writes are not an answer.
      final var throttled = websocket.connect();
      assertNotNull(throttled);
      assertFalse(throttled.toCompletableFuture().isDone(),
          "connect() must count as the last write, deferring an immediate retry");
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

      // The scheduled attempt marked lastConnectAttempt at its run time, so a further connect()
      // with nothing elapsed since defers the whole window again.
      final var throttled = websocket.connect();
      assertNotNull(throttled);
      assertFalse(throttled.toCompletableFuture().isDone());
      assertEquals(2, scheduler.deferred.size(), "the deferred connect must count as the last write");
      assertEquals(60_000L, scheduler.deferred.getLast().delay(),
          "nothing elapsed since the scheduled attempt ran, so the full window defers again");
    }
  }

  /// Traffic is not a connection attempt. The keep-alive puts a frame on the wire every
  /// keepAliveDelay, so while the throttle measured "any outbound frame" it was re-armed by the
  /// very thing that proves the connection was healthy — and a connection alive for ten minutes
  /// still had its reconnect deferred as though it had just been retried. The deferral was
  /// longest exactly when the connection had been most stable.
  ///
  /// reConnectDelay is set above keepAliveDelay here because that is the ordering which exposes
  /// it: the shipped defaults (3s against 30s) bound the damage to the window the caller already
  /// asked for, which is why this went unnoticed.
  @Test
  void aLongLivedConnectionReconnectsWithoutWaitingOutTheThrottle() {
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
        .pingDelay(15_000L)
        .create()) {
      websocket.onOpen(socket);

      // ten minutes of a peer that keeps talking: the liveness clause never fires, so every ping
      // here is the keep-alive, and each one is an outbound frame
      for (int i = 0; i < 60; ++i) {
        clock.advanceMillis(10_000L);
        websocket.onText(socket, CharBuffer.wrap("""
            {"jsonrpc":"2.0","result":555,"id":2}"""), true);
        assertDoesNotThrow(() -> websocket.checkCycle(0L));
      }
      assertTrue(socket.pings > 0, "the keep-alive must have been putting frames on the wire");

      clock.advanceMillis(5_000L);
      final var future = websocket.connect();

      assertNotNull(future);
      assertTrue(scheduler.deferred.isEmpty(),
          "the last attempt was ten minutes ago; a recent keep-alive ping is not an attempt");
      assertEquals(endpoint, webSocketBuilder.builtUri.get(), "the reconnect goes out immediately");
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
