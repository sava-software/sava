package software.sava.rpc.soak.issue52;

import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.services.core.NanoClock;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.solana.websocket.WebSocketManager;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/// One process-wide JUL handler on ravina's `WebSocketManagerImpl` logger (INFO+) and sava's
/// `SolanaJsonRpcWebsocket` logger (WARNING+). `System.Logger` backs onto JUL in this JVM and
/// `Handler.publish` runs synchronously on the logging thread, so every record can be tagged
/// with that thread's current delivery frame — which is how "Websocket connection attempt
/// failed…" (future route) and "Websocket closed…"/"Websocket failure…" (callback route) for
/// one retirement are recognised as the #52 fingerprint, and how an engine cause line logged
/// under the lock is attached to the abort that follows it.
///
/// The two `Logger` objects are held strongly: JUL keeps loggers weakly and a collected logger
/// silently drops its handlers (sava's `QuietWsLogging` shows the same pattern).
///
/// Cost discipline: in representative runs the handler does prefix checks and array appends
/// only; its own duration is measured and, when it lands inside a retirement's future-to-
/// callback gap, charged to that row's `harnessTimeInGapUs`. The blocking wait is forced-mode
/// only: it holds the retiring thread inside the gap until the tracker has seen the successor's
/// `buildAsync`, bounded at [#BLOCK_TIMEOUT_NANOS], and that wait is what makes the constant-
/// zero reproduction deterministic rather than probabilistic.
public final class ManagerLogCapture extends Handler {

  public enum PrefixClass {
    ATTEMPT_FAILED("Websocket connection attempt failed. Re-connecting in "),
    CLOSED("Websocket closed [statusCode="),
    FAILURE("Websocket failure. Re-connecting in "),
    CONNECTED("WebSocket connected to "),
    TERMINAL("Websocket became terminal while connecting."),
    ENGINE_UNANSWERED("Request "),
    ENGINE_PING_SEND("Ping send to "),
    ENGINE_PING_RESPONSE("Ping to "),
    ENGINE_COLLISION("Subscription id "),
    ENGINE_UNSUB("Un-subscription "),
    ENGINE_LOOP_DEATH("Unhandled Solana Websocket exception."),
    OTHER("");

    final String prefix;

    PrefixClass(final String prefix) {
      this.prefix = prefix;
    }

    boolean engine() {
      return ordinal() >= ENGINE_UNANSWERED.ordinal() && this != OTHER;
    }

    boolean carriesRetry() {
      return this == ATTEMPT_FAILED || this == CLOSED || this == FAILURE || this == TERMINAL;
    }
  }

  public static final String MANAGER_LOGGER = "software.sava.services.solana.websocket.WebSocketManagerImpl";
  public static final String ENGINE_LOGGER = "software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket";
  public static final long BLOCK_TIMEOUT_NANOS = 2_000_000_000L;
  private static final int MAX_MESSAGE = 240;
  private static final PrefixClass[] CLASSES = PrefixClass.values();

  private static final Object INSTALL_LOCK = new Object();
  private static volatile ManagerLogCapture instance;

  /// Idempotent: the second caller gets the first handler.
  public static ManagerLogCapture install() {
    var installed = instance;
    if (installed != null) {
      return installed;
    }
    synchronized (INSTALL_LOCK) {
      installed = instance;
      if (installed == null) {
        installed = new ManagerLogCapture();
        instance = installed;
      }
      return installed;
    }
  }

  public static ManagerLogCapture installed() {
    return instance;
  }

  private final Logger managerLogger;
  private final Logger engineLogger;
  /// Self-test seam: runs on the logging thread after an ATTEMPT_FAILED record was tagged, which
  /// is where the polling negative control re-enters `checkConnection()`.
  volatile Runnable onAttemptFailedHook;

  final LongAdder published = new LongAdder();
  final LongAdder classified = new LongAdder();
  final LongAdder tagged = new LongAdder();
  final LongAdder untagged = new LongAdder();
  final AtomicLongArray byClass = new AtomicLongArray(CLASSES.length);
  final LongAdder publishNanosTotal = new LongAdder();
  final AtomicLong publishNanosMax = new AtomicLong();
  final LongAdder blockedWaits = new LongAdder();
  final LongAdder blockedTimeouts = new LongAdder();

  private ManagerLogCapture() {
    setLevel(Level.ALL);
    this.managerLogger = Logger.getLogger(MANAGER_LOGGER);
    this.engineLogger = Logger.getLogger(ENGINE_LOGGER);
    raiseTo(managerLogger, Level.INFO);
    raiseTo(engineLogger, Level.WARNING);
    managerLogger.addHandler(this);
    engineLogger.addHandler(this);
  }

  /// Only lowers a logger's threshold, never raises it: a logging config that already asks
  /// for FINE output keeps it, and the handler filters what it does not need.
  private static void raiseTo(final Logger logger, final Level atLeast) {
    final var effective = effectiveLevel(logger);
    if (effective == null || effective.intValue() > atLeast.intValue()) {
      logger.setLevel(atLeast);
    }
  }

  private static Level effectiveLevel(final Logger logger) {
    for (var l = logger; l != null; l = l.getParent()) {
      final var level = l.getLevel();
      if (level != null) {
        return level;
      }
    }
    return null;
  }

  public static PrefixClass classify(final String message) {
    for (int i = 0; i < CLASSES.length - 1; ++i) {
      if (message.startsWith(CLASSES[i].prefix)) {
        return CLASSES[i];
      }
    }
    return PrefixClass.OTHER;
  }

  /// The N of "… Re-connecting in N milliseconds.", or -1.
  public static long parseRetryMillis(final String message) {
    final int at = message.indexOf("Re-connecting in ");
    if (at < 0) {
      return -1;
    }
    long value = 0;
    boolean any = false;
    for (int i = at + "Re-connecting in ".length(); i < message.length(); ++i) {
      final char c = message.charAt(i);
      if (c < '0' || c > '9') {
        break;
      }
      value = value * 10 + (c - '0');
      any = true;
    }
    return any ? value : -1;
  }

  @Override
  public void publish(final LogRecord record) {
    if (record == null) {
      return;
    }
    final long t0 = System.nanoTime();
    published.increment();
    if (record.getLevel().intValue() < Level.INFO.intValue()) {
      return;
    }
    final String message = record.getMessage();
    if (message == null) {
      return;
    }
    final var prefixClass = classify(message);
    if (prefixClass == PrefixClass.OTHER) {
      return;
    }
    classified.increment();
    byClass.incrementAndGet(prefixClass.ordinal());
    final var frames = DeliveryFrames.of();
    final var frame = frames.current();
    final boolean insideCancel = frames.insideBuildCancel();
    AttemptTracker tracker = null;
    DeliveryFrames.SubFrame sub = null;
    long originOrdinal = -1;
    if (frame != null) {
      tracker = frame.tracker;
      sub = frame.innermostRetirement();
      originOrdinal = sub != null ? sub.ordinal : frame.ordinal;
    }
    if (prefixClass.engine()) {
      // Logged under the engine's lock, before the abort that follows: held on the thread until
      // the next retirement abort adopts it (or an instance-death row drains it).
      frames.addPendingEngineLine(message, t0);
    } else {
      if (sub != null) {
        sub.addManagerLine(message, t0);
        if (prefixClass.carriesRetry() && sub.loggedRetryMs < 0) {
          sub.loggedRetryMs = parseRetryMillis(message);
        }
      }
      if (tracker != null) {
        tagged.increment();
        if (prefixClass == PrefixClass.CONNECTED) {
          tracker.connectedObserved(frame, t0);
        }
      } else {
        untagged.increment();
      }
    }
    final var event = new Issue52Events.ManagerLog();
    if (event.isEnabled()) {
      event.level = record.getLevel().getName();
      event.prefixClass = prefixClass.name();
      event.message = message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE);
      final var thrown = record.getThrown();
      event.thrownClass = thrown == null ? "" : thrown.getClass().getName();
      event.logThread = Thread.currentThread();
      event.originOrdinal = originOrdinal;
      event.insideBuildCancel = insideCancel;
      event.harnessNanos = t0;
      event.commit();
    }
    if (prefixClass == PrefixClass.ATTEMPT_FAILED) {
      final var hook = onAttemptFailedHook;
      if (hook != null) {
        hook.run();
      }
      if (tracker != null && insideCancel && tracker.blockAttemptFailedUntilSuccessor()) {
        blockedWaits.increment();
        if (!tracker.awaitBuildAfter(originOrdinal, BLOCK_TIMEOUT_NANOS)) {
          blockedTimeouts.increment();
        }
      }
    }
    final long spent = System.nanoTime() - t0;
    publishNanosTotal.add(spent);
    publishNanosMax.accumulateAndGet(spent, Math::max);
    if (sub != null && sub.consumedBy == 0 && sub.futureClaim() != null) {
      sub.harnessNanosInGap += spent;
    }
  }

  @Override
  public void flush() {
  }

  @Override
  public void close() {
  }

  public long count(final PrefixClass prefixClass) {
    return byClass.get(prefixClass.ordinal());
  }

  /// Self-test seam only: the polling negative control re-enters the manager from inside the
  /// ATTEMPT_FAILED record. Null clears it.
  public void onAttemptFailedHook(final Runnable hook) {
    this.onAttemptFailedHook = hook;
  }

  public long blockedWaits() {
    return blockedWaits.sum();
  }

  public long blockedTimeouts() {
    return blockedTimeouts.sum();
  }

  /// The startup assertion (DESIGN.md §10): drives one synthetic connection failure through a
  /// throwaway polling manager whose builder throws from `buildAsync`, and requires that the
  /// handler classified the resulting "Websocket connection attempt failed…" line. A false here
  /// means the log path is not observable (a different backend, a level override, a changed
  /// prefix) and the run's fingerprint evidence would be silently empty.
  public boolean selfCheck() {
    final long before = count(PrefixClass.ATTEMPT_FAILED);
    final WebSocket.Builder throwing = new WebSocket.Builder() {
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
        throw new IllegalStateException("soak: log-capture self-check");
      }
    };
    final var prototype = SolanaRpcWebsocket.build()
        .uri(URI.create("ws://127.0.0.1:1"))
        .webSocketBuilder(throwing);
    final var manager = WebSocketManager.createManager(
        Backoff.single(MILLISECONDS, 0), prototype, null, NanoClock.SYSTEM
    );
    try {
      manager.webSocket();
    } finally {
      manager.close();
    }
    return count(PrefixClass.ATTEMPT_FAILED) > before;
  }

  public String statsLine() {
    final var sb = new StringBuilder(256);
    sb.append("logcapture published=").append(published.sum())
        .append(" classified=").append(classified.sum())
        .append(" tagged=").append(tagged.sum())
        .append(" untagged=").append(untagged.sum());
    for (final var c : CLASSES) {
      if (c != PrefixClass.OTHER) {
        sb.append(' ').append(c.name().toLowerCase()).append('=').append(byClass.get(c.ordinal()));
      }
    }
    sb.append(" publishNanosTotal=").append(publishNanosTotal.sum())
        .append(" publishNanosMax=").append(publishNanosMax.get())
        .append(" blockedWaits=").append(blockedWaits.sum())
        .append(" blockedTimeouts=").append(blockedTimeouts.sum());
    return sb.toString();
  }
}
