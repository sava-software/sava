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
  /// Default cap on a single (possibly fragmented) text message, in chars:
  /// 67,108,864 (a 128 MiB `char[]`). A 10 MiB account — the network's account
  /// data cap — base64-encodes to under 14M chars, so legitimate notifications
  /// sit well inside it; a server that exceeds it is violating the protocol, not
  /// sending a bigger account. Package-private on purpose: the public surface is
  /// the builder knob, not the constant.
  static final int DEFAULT_MAX_MESSAGE_LENGTH = 1 << 26;
  /// Default budget for the whole handshake — DNS, TCP, TLS, and the HTTP upgrade — in millis.
  ///
  /// This was previously the reconnect delay, 3s, which is a plausible number for pacing retries
  /// and a tight one for a cold TLS handshake to a shared public endpoint such as
  /// api.mainnet-beta.solana.com, where a request may also queue behind rate limiting. Exceeding
  /// it does not yield a slow connection, it yields a failed one, then a throttled retry, then
  /// backoff — so a latency spike became a reconnect storm against the endpoint least able to
  /// absorb it.
  ///
  /// A warm handshake to a nearby endpoint completes well inside a second, so this is roughly an
  /// order of magnitude of headroom over the normal case while still failing fast enough to be
  /// worth retrying: a handshake that has not completed in this long is a node worth giving up
  /// on rather than waiting out.
  static final long DEFAULT_CONNECT_TIMEOUT = 8_000;

  private int maxMessageLength = DEFAULT_MAX_MESSAGE_LENGTH;
  private long connectTimeout = DEFAULT_CONNECT_TIMEOUT;
  private long reConnectDelay = 3_000;
  private long pingDelay = 15_000;
  private long subscriptionAndPingCheckDelay = 2_000;
  /// Unset until given, so it can track a caller-supplied pingDelay instead of a stale default.
  private long keepAliveDelay = 0;
  /// Unset until given, for the same reason: it follows reConnectDelay unless stated.
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

  /// Deliberately not on the public [SolanaRpcWebsocket.Builder] interface, like
  /// [#executorService(ExecutorService)]: the clock exists so tests can advance
  /// time instead of waiting on the reconnect throttle and ping pacing, and no
  /// caller outside this package has a reason to replace it. Null leaves
  /// [NanoClock#SYSTEM] in place — [#create()] substitutes it — so a builder that
  /// never touches this method behaves exactly as it always has.
  SolanaRpcWebsocketBuilder clock(final NanoClock clock) {
    this.clock = clock;
    return this;
  }

  NanoClock clock() {
    return clock;
  }

  /// Deliberately not on the public [SolanaRpcWebsocket.Builder] interface: the
  /// executor runs the background check loop for the websocket's lifetime, so it
  /// must run tasks asynchronously — a caller-thread executor would never return
  /// from [#create()]. Null (the default) creates a dedicated single-thread
  /// executor owned and shut down by the websocket's close(); an injected
  /// executor is the caller's to shut down — close() only asks the loop to
  /// return its thread. Tests reach it by casting the builder.
  SolanaRpcWebsocketBuilder executorService(final ExecutorService executorService) {
    this.executorService = executorService;
    return this;
  }

  ExecutorService executorService() {
    return executorService;
  }

  /// Deliberately not on the public [SolanaRpcWebsocket.Builder] interface, like
  /// [#executorService(ExecutorService)]. Null (the default) defers reconnects on
  /// `CompletableFuture.delayedExecutor` — the shared JDK delayer, no extra
  /// thread; injected, deferred connects are scheduled on it and its lifecycle is
  /// the caller's. Tests reach it by casting the builder.
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

  /// How long the whole handshake may take. Separate from [#reConnectDelay(long)], which paces
  /// retries: a handshake budget and a retry cadence have no reason to be the same number, and
  /// tuning one used to move the other.
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

  /// How long this end may be silent before it pokes the peer. Defaults to a multiple of the ping
  /// delay, so tuning only the ping delay still moves this proportionately.
  ///
  /// Set it explicitly when something in the path enforces client liveness — that is, ages the
  /// connection on what it receives *from us*. An ordinary proxy or load balancer is not that:
  /// those reset on traffic in either direction, so an inbound-busy connection never looks idle
  /// to them, and when both ends go quiet [Timings#pingDelay()] fires first. See
  /// [Timings#keepAliveDelay()] for why the derived default is proportional rather than capped.
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

  /// How long a FAILED subscription send waits before it is retried, in milliseconds — and,
  /// times four, the unanswered-request deadline that replaces the connection. Defaults to the
  /// reconnect delay floored at the check delay. Successfully sent requests are never re-sent
  /// on their own connection; see the interface note. This delay also paces replay after a
  /// reconnect — a re-queued subscription keeps its last attempt stamp, so a large value
  /// chosen to space out retries delays the fresh connection's replay by the same window.
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
