package software.sava.rpc.json.http.ws;

import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class SolanaRpcWebsocketBuilder implements SolanaRpcWebsocket.Builder {

  private URI wsUri;
  private WebSocket.Builder webSocketBuilder;
  private NanoClock clock = NanoClock.SYSTEM;
  private ExecutorService executorService;
  private ScheduledExecutorService scheduler;
  /// Default [#maxMessageLength(int)], in chars, with headroom above a base64-encoded account at
  /// the network's 10 MiB data cap.
  static final int DEFAULT_MAX_MESSAGE_LENGTH = 1 << 26;
  /// Default [#connectTimeout(long)], in millis. Deliberately above the reconnect delay: a cold
  /// TLS handshake to a rate-limited public endpoint can exceed that, turning a latency spike
  /// into a reconnect storm. A node slower than this is worth giving up on.
  static final long DEFAULT_CONNECT_TIMEOUT = 8_000;

  private int maxMessageLength = DEFAULT_MAX_MESSAGE_LENGTH;
  private long connectTimeout = DEFAULT_CONNECT_TIMEOUT;
  private long reConnectDelay = 3_000;
  private long pingDelay = 15_000;
  private long subscriptionAndPingCheckDelay = 2_000;
  /// 0 until set, so the default tracks [#pingDelay()].
  private long keepAliveDelay = 0;
  /// 0 until set, so the default tracks [#reConnectDelay()] and
  /// [#subscriptionAndPingCheckDelay()].
  private long subscriptionResendDelay = 0;
  private SolanaAccounts solanaAccounts = SolanaAccounts.MAIN_NET;
  private Commitment commitment = Commitment.CONFIRMED;
  private Consumer<SolanaRpcWebsocket> onOpen;
  private SolanaRpcWebsocket.OnClose onClose;
  private BiConsumer<SolanaRpcWebsocket, Throwable> onError;
  private BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError;
  private BiConsumer<SolanaRpcWebsocket, Throwable> onPingError;

  SolanaRpcWebsocketBuilder() {
  }

  @Override
  public SolanaRpcWebsocket create() {
    // Fail here, not later: the constructor starts the check loop on a non-daemon thread before
    // anything dereferences the endpoint, so an unset uri otherwise surfaces as an unlabelled
    // NPE from inside buildAsync with a stray thread left running.
    Objects.requireNonNull(wsUri, "uri is required to create a websocket");
    Objects.requireNonNull(webSocketBuilder, "webSocketBuilder is required to create a websocket");
    return new SolanaJsonRpcWebsocket(
        wsUri, solanaAccounts, commitment,
        webSocketBuilder.connectTimeout(Duration.ofMillis(connectTimeout)),
        // Through the getters, so the unset-means-derive rule lives in one place and what this
        // builds is exactly what those getters report.
        new Timings(reConnectDelay, pingDelay, subscriptionAndPingCheckDelay,
            keepAliveDelay(), subscriptionResendDelay()),
        maxMessageLength,
        clock == null ? NanoClock.SYSTEM : clock,
        executorService,
        scheduler,
        onOpen,
        onClose,
        onError,
        onSendTextError,
        onPingError
    );
  }

  @Override
  public URI wsUri() {
    return wsUri;
  }

  @Override
  public WebSocket.Builder webSocketBuilder() {
    return webSocketBuilder;
  }

  @Override
  public long connectTimeout() {
    return connectTimeout;
  }

  @Override
  public long reConnectDelay() {
    return reConnectDelay;
  }

  @Override
  public long pingDelay() {
    return pingDelay;
  }

  @Override
  public long keepAliveDelay() {
    return keepAliveDelay > 0 ? keepAliveDelay : Timings.keepAliveFor(pingDelay);
  }

  @Override
  public long subscriptionResendDelay() {
    return subscriptionResendDelay > 0
        ? subscriptionResendDelay
        : Timings.resendDelayFor(reConnectDelay, subscriptionAndPingCheckDelay);
  }

  @Override
  public long subscriptionAndPingCheckDelay() {
    return subscriptionAndPingCheckDelay;
  }

  @Override
  public SolanaAccounts solanaAccounts() {
    return solanaAccounts;
  }

  @Override
  public Commitment commitment() {
    return commitment;
  }

  @Override
  public SolanaRpcWebsocketBuilder uri(final URI uri) {
    this.wsUri = uri;
    return this;
  }

  @Override
  public SolanaRpcWebsocketBuilder webSocketBuilder(final WebSocket.Builder webSocketBuilder) {
    this.webSocketBuilder = webSocketBuilder;
    return this;
  }

  /// Test seam. Null means [NanoClock#SYSTEM].
  SolanaRpcWebsocketBuilder clock(final NanoClock clock) {
    this.clock = clock;
    return this;
  }

  NanoClock clock() {
    return clock;
  }

  /// Test seam. The executor hosts the check loop for the websocket's lifetime, so it must run
  /// tasks asynchronously: with a caller-thread executor [#create()] never returns. Null creates
  /// a single-thread executor that [SolanaRpcWebsocket#close()] shuts down; an injected one is
  /// the caller's to shut down.
  SolanaRpcWebsocketBuilder executorService(final ExecutorService executorService) {
    this.executorService = executorService;
    return this;
  }

  ExecutorService executorService() {
    return executorService;
  }

  /// Test seam that runs deferred connects and the close watchdog. Null uses
  /// [java.util.concurrent.CompletableFuture#delayedExecutor(long,java.util.concurrent.TimeUnit)];
  /// an injected scheduler is the caller's to shut down.
  SolanaRpcWebsocketBuilder scheduler(final ScheduledExecutorService scheduler) {
    this.scheduler = scheduler;
    return this;
  }

  ScheduledExecutorService scheduler() {
    return scheduler;
  }

  @Override
  public SolanaRpcWebsocketBuilder maxMessageLength(final int maxMessageLength) {
    if (maxMessageLength <= 0) {
      throw new IllegalArgumentException("maxMessageLength must be positive: " + maxMessageLength);
    }
    this.maxMessageLength = maxMessageLength;
    return this;
  }

  @Override
  public int maxMessageLength() {
    return maxMessageLength;
  }

  @Override
  public SolanaRpcWebsocketBuilder connectTimeout(final long connectTimeout) {
    if (connectTimeout <= 0) {
      throw new IllegalArgumentException("connectTimeout must be positive: " + connectTimeout);
    }
    this.connectTimeout = connectTimeout;
    return this;
  }

  @Override
  public SolanaRpcWebsocketBuilder reConnectDelay(final long reConnectDelay) {
    this.reConnectDelay = reConnectDelay;
    return this;
  }

  @Override
  public SolanaRpcWebsocketBuilder pingDelay(final long pingDelay) {
    this.pingDelay = pingDelay;
    return this;
  }

  @Override
  public SolanaRpcWebsocketBuilder keepAliveDelay(final long keepAliveDelay) {
    if (keepAliveDelay <= 0) {
      // Zero is how this records "not given", so accepting it would hand back the derived
      // default while looking like it took the caller's answer.
      throw new IllegalArgumentException("keepAliveDelay must be positive: " + keepAliveDelay);
    }
    this.keepAliveDelay = keepAliveDelay;
    return this;
  }

  @Override
  public SolanaRpcWebsocketBuilder subscriptionResendDelay(final long subscriptionResendDelay) {
    if (subscriptionResendDelay <= 0) {
      // Zero is how this records "not given", so accepting it would hand back the derived
      // default while looking like it took the caller's answer.
      throw new IllegalArgumentException("subscriptionResendDelay must be positive: " + subscriptionResendDelay);
    }
    this.subscriptionResendDelay = subscriptionResendDelay;
    return this;
  }

  @Override
  public SolanaRpcWebsocketBuilder subscriptionAndPingCheckDelay(final long subscriptionAndPingCheckDelay) {
    this.subscriptionAndPingCheckDelay = subscriptionAndPingCheckDelay;
    return this;
  }

  @Override
  public SolanaRpcWebsocketBuilder commitment(final Commitment commitment) {
    this.commitment = commitment;
    return this;
  }

  @Override
  public SolanaRpcWebsocketBuilder solanaAccounts(final SolanaAccounts solanaAccounts) {
    this.solanaAccounts = solanaAccounts;
    return this;
  }

  @Override
  public Consumer<SolanaRpcWebsocket> onOpen() {
    return onOpen;
  }

  @Override
  public SolanaRpcWebsocketBuilder onOpen(final Consumer<SolanaRpcWebsocket> onOpen) {
    this.onOpen = onOpen;
    return this;
  }

  @Override
  public SolanaRpcWebsocket.OnClose onClose() {
    return onClose;
  }

  @Override
  public SolanaRpcWebsocketBuilder onClose(final SolanaRpcWebsocket.OnClose onClose) {
    this.onClose = onClose;
    return this;
  }

  @Override
  public BiConsumer<SolanaRpcWebsocket, Throwable> onError() {
    return onError;
  }

  @Override
  public SolanaRpcWebsocketBuilder onError(final BiConsumer<SolanaRpcWebsocket, Throwable> onError) {
    this.onError = onError;
    return this;
  }

  @Override
  public BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError() {
    return onSendTextError;
  }

  @Override
  public SolanaRpcWebsocketBuilder onSendTextError(final BiConsumer<SolanaRpcWebsocket, Throwable> onSendTextError) {
    this.onSendTextError = onSendTextError;
    return this;
  }

  @Override
  public BiConsumer<SolanaRpcWebsocket, Throwable> onPingError() {
    return onPingError;
  }

  @Override
  public SolanaRpcWebsocketBuilder onPingError(final BiConsumer<SolanaRpcWebsocket, Throwable> onPingError) {
    this.onPingError = onPingError;
    return this;
  }
}
