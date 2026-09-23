package software.sava.rpc.soak.selftest;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.rpc.soak.issue52.AttemptTracker;
import software.sava.rpc.soak.issue52.BackoffClass;
import software.sava.rpc.soak.issue52.ClaimRecord;
import software.sava.rpc.soak.issue52.ManagerLogCapture;
import software.sava.rpc.soak.issue52.RetirementDetector;
import software.sava.rpc.soak.issue52.RetirementRecord;
import software.sava.rpc.soak.issue52.RetirementRecord.CallbackRoute;
import software.sava.rpc.soak.issue52.RetirementRecord.CauseClass;
import software.sava.rpc.soak.issue52.RetirementRecord.FrameKind;
import software.sava.rpc.soak.issue52.RetirementRecord.FutureRoute;
import software.sava.rpc.soak.issue52.RetirementRecord.NextAttemptOutcome;
import software.sava.rpc.soak.issue52.RetirementRecord.OpenOrder;
import software.sava.rpc.soak.issue52.RetirementRecord.PingRoute;
import software.sava.rpc.soak.issue52.RetirementRecord.ThreadKind;
import software.sava.rpc.soak.issue52.RetirementRecord.Verdict;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.solana.websocket.WebSocketManager;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/// Attribution self-test (DESIGN.md §10, attribution.md self_test_spec): drives the REAL
/// `SolanaJsonRpcWebsocket` through its public API with a fake transport under the production
/// harness wrapper, so the code under test is the attribution machinery a soak runs. Cases
/// P1–P14 cover every retirement path in the paths table plus the no-callback aborts that must
/// never produce a record; NEG/POS/THREADED/JDKORDER are the #52 controls around ravina's
/// manager; DETECTOR judges synthetic frames; LOGCAPTURE is the startup assertion.
///
/// Prints one line per case, `SELFTEST <id> PASS|FAIL <detail>`, and exits 0 only if all pass.
/// Time-dependent paths use sava's package-private seams reflectively, so the modular launch
/// needs `--add-opens software.sava.rpc/software.sava.rpc.json.http.ws=software.sava.rpc.soak`;
/// the manager oracle needs `--add-opens software.sava.ravina_solana/software.sava.services.solana.websocket=software.sava.rpc.soak`
/// and is skipped with a stated SKIP when absent. No sleeps: bounded latches only where a real
/// thread is involved (P9, THREADED).
public final class SelfTest {

  private static final URI ENDPOINT = URI.create("ws://127.0.0.1:1");
  private static final long LATCH_NANOS = 2_000_000_000L;
  private static final List<String> skips = new ArrayList<>();

  private SelfTest() {
  }

  @FunctionalInterface
  interface Case {

    String run() throws Exception;
  }

  public static void main(final String[] args) {
    quietConsole();
    final var capture = ManagerLogCapture.install();
    boolean all = true;
    all &= run("LOGCAPTURE", () -> {
      check(capture.selfCheck(), "synthetic manager failure was not classified ATTEMPT_FAILED");
      return "throwaway manager's attempt-failed line classified";
    });
    all &= run("DETECTOR", () -> {
      check(RetirementDetector.selfTest(), "synthetic frames misjudged");
      return "synthetic frames judged";
    });
    all &= run("P1", SelfTest::p1JdkClose);
    all &= run("P2", SelfTest::p2JdkError);
    all &= run("P3", SelfTest::p3MaxMessageOverflow);
    all &= run("P4", SelfTest::p4IdCollision);
    all &= run("P5", SelfTest::p5UnansweredEscalation);
    all &= run("P6", SelfTest::p6PingSendTimeout);
    all &= run("P7", SelfTest::p7PingResponseTimeout);
    all &= run("P8", SelfTest::p8PingSendFailurePair);
    all &= run("P9", SelfTest::p9CheckLoopDeath);
    all &= run("P10", SelfTest::p10CloseNoRecords);
    all &= run("P11", SelfTest::p11ConnectReplacementAntiMisfire);
    all &= run("P12", SelfTest::p12StaleAdoptAndOwnBuild);
    all &= run("P13", SelfTest::p13CancelledBeforeInstall);
    all &= run("P14", SelfTest::p14NestedDelivery);
    all &= run("P15", SelfTest::p15InjectionArming);
    all &= run("NEG", SelfTest::negativeControl);
    all &= run("POS", SelfTest::positiveControl);
    all &= run("THREADED", SelfTest::threadedNegativeControl);
    all &= run("JDKORDER", SelfTest::jdkOrderControl);
    for (final var skip : skips) {
      System.out.println("SELFTEST " + skip);
    }
    System.out.println(capture.statsLine());
    System.out.println(all ? "SELFTEST ALL PASS" : "SELFTEST SOME FAIL");
    System.exit(all ? 0 : 1);
  }

  private static boolean run(final String id, final Case c) {
    try {
      final String detail = c.run();
      System.out.println("SELFTEST " + id + " PASS " + detail);
      return true;
    } catch (final Throwable t) {
      System.out.println("SELFTEST " + id + " FAIL " + t);
      t.printStackTrace(System.out);
      return false;
    }
  }

  static void check(final boolean condition, final String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  static void checkEquals(final Object expected, final Object actual, final String what) {
    if (expected == null ? actual != null : !expected.equals(actual)) {
      throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }
  }

  /// The engine and manager log every retirement at WARNING/ERROR; the console copy is noise
  /// next to the SELFTEST lines, and the capture handler is on the named loggers, not the root.
  private static void quietConsole() {
    for (final var handler : Logger.getLogger("").getHandlers()) {
      handler.setLevel(Level.OFF);
    }
  }

  // ----------------------------------------------------------------------------- fixtures

  /// Manually advanced clock implementing both sava's and ravina's `NanoClock` (they mirror
  /// each other). Non-zero origin. `poison` makes the next reading on that thread throw, which
  /// is how P9 kills the check loop deterministically.
  static final class SoakClock implements software.sava.rpc.json.http.ws.NanoClock, software.sava.services.core.NanoClock {

    private final AtomicLong nanos = new AtomicLong(1_234_567_890_123_456L);
    volatile Thread poison;

    void advanceMillis(final long millis) {
      nanos.addAndGet(millis * 1_000_000L);
    }

    @Override
    public long nanoTime() {
      final var p = poison;
      if (p != null && Thread.currentThread() == p) {
        throw new IllegalStateException("soak: clock poisoned for the check loop");
      }
      return nanos.get();
    }

    @Override
    public long currentTimeMillis() {
      return nanoTime() / 1_000_000L;
    }

    @Override
    public void sleep(final long millis) {
      advanceMillis(millis);
    }
  }

  /// Captures the loop task without running it: no background thread races the clock-stepped
  /// assertions, and `checkCycle(0)` is driven on the test thread instead.
  static final class NoRunExecutor extends AbstractExecutorService {

    final List<Runnable> tasks = new ArrayList<>();
    volatile boolean shutdown;

    @Override
    public void execute(final Runnable command) {
      tasks.add(command);
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) {
      return shutdown;
    }
  }

  /// Captures scheduled tasks with their delays; the test runs them deliberately.
  static final class FakeScheduler extends AbstractExecutorService implements ScheduledExecutorService {

    record Deferred(Runnable task, long delayMillis, Handle handle) {
    }

    static final class Handle implements ScheduledFuture<Object> {

      private final long delayNanos;
      volatile boolean cancelled;
      volatile boolean done;

      Handle(final long delayNanos) {
        this.delayNanos = delayNanos;
      }

      @Override
      public long getDelay(final TimeUnit unit) {
        return unit.convert(delayNanos, TimeUnit.NANOSECONDS);
      }

      @Override
      public int compareTo(final Delayed other) {
        return Long.compare(delayNanos, other.getDelay(TimeUnit.NANOSECONDS));
      }

      @Override
      public boolean cancel(final boolean mayInterruptIfRunning) {
        if (done) {
          return false;
        }
        cancelled = true;
        done = true;
        return true;
      }

      @Override
      public boolean isCancelled() {
        return cancelled;
      }

      @Override
      public boolean isDone() {
        return done;
      }

      @Override
      public Object get() {
        return this;
      }

      @Override
      public Object get(final long timeout, final TimeUnit unit) {
        return this;
      }
    }

    final List<Deferred> deferred = new CopyOnWriteArrayList<>();
    volatile boolean shutdown;

    @Override
    public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
      final var handle = new Handle(unit.toNanos(delay));
      deferred.add(new Deferred(command, unit.toMillis(delay), handle));
      return handle;
    }

    /// Runs every captured task not yet cancelled, in order.
    int runAll() {
      int ran = 0;
      for (final var d : deferred) {
        if (!d.handle().done) {
          d.handle().done = true;
          d.task().run();
          ++ran;
        }
      }
      return ran;
    }

    @Override
    public <V> ScheduledFuture<V> schedule(final Callable<V> callable, final long delay, final TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(final Runnable command, final long initialDelay, final long period, final TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(final Runnable command, final long initialDelay, final long delay, final TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void execute(final Runnable command) {
      command.run();
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) {
      return shutdown;
    }
  }

  /// sava's package-private builder seams and the engine's `checkCycle`, by reflection.
  static final class Seams {

    private static final String BUILDER_CLASS = "software.sava.rpc.json.http.ws.SolanaRpcWebsocketBuilder";

    private static Object invoke(final Object target, final String name, final Class<?> type, final Object arg) {
      try {
        final var method = target.getClass().getDeclaredMethod(name, type);
        method.setAccessible(true);
        return method.invoke(target, arg);
      } catch (final InvocationTargetException e) {
        final var cause = e.getCause();
        if (cause instanceof RuntimeException re) {
          throw re;
        }
        throw new IllegalStateException(cause);
      } catch (final ReflectiveOperationException | RuntimeException e) {
        throw new IllegalStateException("seam " + name + " unreachable on " + target.getClass().getName()
            + " (launch needs --add-opens software.sava.rpc/software.sava.rpc.json.http.ws=software.sava.rpc.soak): " + e, e);
      }
    }

    static void clock(final SolanaRpcWebsocket.Builder builder, final software.sava.rpc.json.http.ws.NanoClock clock) {
      check(builder.getClass().getName().equals(BUILDER_CLASS), "unexpected builder " + builder.getClass());
      invoke(builder, "clock", software.sava.rpc.json.http.ws.NanoClock.class, clock);
    }

    static void executorService(final SolanaRpcWebsocket.Builder builder, final ExecutorService executor) {
      invoke(builder, "executorService", ExecutorService.class, executor);
    }

    static void scheduler(final SolanaRpcWebsocket.Builder builder, final ScheduledExecutorService scheduler) {
      invoke(builder, "scheduler", ScheduledExecutorService.class, scheduler);
    }

    static void checkCycle(final SolanaRpcWebsocket ws) {
      invoke(ws, "checkCycle", long.class, 0L);
    }
  }

  /// Optional reflective view of `WebSocketManagerImpl`'s private state, taken under its own
  /// lock. Ties the self-test to ravina 25.6.1's layout, which is why it is an oracle for the
  /// seam-based inference and not a substitute for it.
  static final class ManagerOracle {

    record Snapshot(String state, int errorCount, long retrySequence) {
    }

    private final Object manager;
    private final Field state;
    private final Field errorCount;
    private final Field retrySequence;
    private final Field lock;

    private ManagerOracle(final Object manager, final Field state, final Field errorCount, final Field retrySequence, final Field lock) {
      this.manager = manager;
      this.state = state;
      this.errorCount = errorCount;
      this.retrySequence = retrySequence;
      this.lock = lock;
    }

    static ManagerOracle of(final WebSocketManager manager) {
      try {
        final var cls = manager.getClass();
        final var state = cls.getDeclaredField("state");
        final var errorCount = cls.getDeclaredField("errorCount");
        final var retrySequence = cls.getDeclaredField("retrySequence");
        final var lock = cls.getDeclaredField("lock");
        for (final var f : new Field[]{state, errorCount, retrySequence, lock}) {
          f.setAccessible(true);
        }
        return new ManagerOracle(manager, state, errorCount, retrySequence, lock);
      } catch (final ReflectiveOperationException | RuntimeException e) {
        return null;
      }
    }

    Snapshot snapshot() {
      try {
        final var l = (ReentrantLock) lock.get(manager);
        l.lock();
        try {
          return new Snapshot(String.valueOf(state.get(manager)), errorCount.getInt(manager), retrySequence.getLong(manager));
        } finally {
          l.unlock();
        }
      } catch (final IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /// One engine under one tracker with the fake transport.
  static final class Rig implements AutoCloseable {

    final List<RetirementRecord> records = new CopyOnWriteArrayList<>();
    final BlockingQueue<RetirementRecord> arrivals = new LinkedBlockingQueue<>();
    final List<ClaimRecord> claims = new CopyOnWriteArrayList<>();
    final List<Throwable> errors = new CopyOnWriteArrayList<>();
    final List<Throwable> pingErrors = new CopyOnWriteArrayList<>();
    final List<String> closes = new CopyOnWriteArrayList<>();
    final AttemptTracker tracker;
    final FakeWebSocketBuilder fake = new FakeWebSocketBuilder();
    final SoakClock clock = new SoakClock();
    final FakeScheduler scheduler = new FakeScheduler();
    ExecutorService loop = new NoRunExecutor();
    boolean seams = true;
    SolanaRpcWebsocket ws;
    WebSocketManager manager;

    Rig(final boolean forced) {
      this.tracker = AttemptTracker.forEngine("selftest", r -> {
        records.add(r);
        arrivals.add(r);
      }, claims::add, forced, false);
    }

    /// Sane-but-fast timings: no reconnect throttle (the manager zeroes it anyway), a ping and
    /// check cadence that never fires on its own, and a 100 ms resend deadline so the
    /// unanswered escalation is one clock step away.
    SolanaRpcWebsocket.Builder prototype() {
      final var builder = SolanaRpcWebsocket.build()
          .uri(ENDPOINT)
          .webSocketBuilder(tracker.wrap(() -> fake))
          .reConnectDelay(0)
          .pingDelay(60_000)
          .subscriptionAndPingCheckDelay(60_000)
          .subscriptionResendDelay(100)
          .commitment(Commitment.CONFIRMED)
          .onClose((_, code, reason) -> closes.add(code + ":" + reason))
          .onError((_, error) -> errors.add(error))
          .onPingError((_, error) -> pingErrors.add(error));
      if (seams) {
        Seams.clock(builder, clock);
        Seams.executorService(builder, loop);
        Seams.scheduler(builder, scheduler);
      }
      return builder;
    }

    SolanaRpcWebsocket create(final SolanaRpcWebsocket.Builder prototype) {
      ws = tracker.instrument(prototype).create();
      return ws;
    }

    SolanaRpcWebsocket create() {
      return create(prototype());
    }

    /// JDK 25 order: the build future settles, then `onOpen` arrives.
    void open(final int index) {
      fake.completeBuild(index);
      fake.listener(index).onOpen(fake.socket(index));
    }

    RetirementRecord only() {
      checkEquals(1, records.size(), "record count");
      return records.get(0);
    }

    @Override
    public void close() {
      if (manager != null) {
        manager.close();
      } else if (ws != null) {
        ws.close();
      }
    }
  }

  private static String threadName() {
    return Thread.currentThread().getName();
  }

  private static PublicKey key(final int seed) {
    final var bytes = new byte[32];
    bytes[0] = (byte) seed;
    bytes[31] = (byte) (seed * 7);
    return PublicKey.createPubKey(bytes);
  }

  private static long requestId(final String frame) {
    final var ji = JsonIterator.parse(frame);
    check(ji.skipUntil("id") != null, "no id in " + frame);
    return ji.readLong();
  }

  private static void checkRetirementCommon(final RetirementRecord r,
                                            final long origin,
                                            final CauseClass cause,
                                            final FrameKind frameKind,
                                            final ThreadKind threadKind) {
    checkEquals(origin, r.originOrdinal(), "originOrdinal");
    checkEquals(cause, r.causeClass(), "causeClass");
    checkEquals(threadName(), r.deliveryThread(), "deliveryThread");
    checkEquals(frameKind, r.frameKind(), "frameKind");
    checkEquals(threadKind, r.threadKind(), "threadKind");
    check(r.abortBeforeCallback(), "abortBeforeCallback");
    checkEquals("retireConnection", r.abortClassifier(), "abortClassifier");
    check(r.abortSeq() > 0, "abortSeq");
    check(r.abortToHandlerUs() >= 0, "abortToHandlerUs");
    check(r.retiredAtNanos() > 0, "retiredAtNanos");
  }

  // ---------------------------------------------------------------------- P1–P15

  static String p1JdkClose() throws Exception {
    try (final var rig = new Rig(false)) {
      final var ws = rig.create();
      final var attempt = ws.connect();
      checkEquals(1, rig.fake.builds.get(), "builds");
      checkEquals(List.of("1"), rig.fake.attemptHeaders, "X-Soak-Attempt");
      check(!rig.fake.connectTimeouts.isEmpty(), "connectTimeout replayed onto the delegate");
      rig.open(0);
      check(attempt.isDone() && !attempt.isCompletedExceptionally(), "connect() copy settled by the build");
      final var view = rig.tracker.attempt(1);
      check(view.installed() && view.openReported(), "attempt 1 installed and open reported");
      checkEquals(OpenOrder.FUTURE_FIRST, view.openOrder(), "openOrder");
      rig.fake.listener(0).onClose(rig.fake.socket(0), 1000, "bye");
      final var r = rig.only();
      checkRetirementCommon(r, 1, CauseClass.JDK_CLOSE, FrameKind.ON_CLOSE, ThreadKind.JDK_LISTENER);
      check(r.causeDetail().contains("status=1000 reason=bye"), "causeDetail " + r.causeDetail());
      check(r.buildCancelObserved() && r.buildWasDoneAtCancel(), "build cancel observed with wasDone");
      checkEquals(FutureRoute.NONE_ALREADY_SETTLED, r.futureRoute(), "futureRoute");
      checkEquals(CallbackRoute.ON_CLOSE_FENCED, r.callbackRoute(), "callbackRoute");
      checkEquals(PingRoute.ABSENT, r.pingRoute(), "pingRoute");
      checkEquals(0, r.claimCount(), "claimCount");
      checkEquals(Verdict.ATTRIBUTED_SINGLE_CLAIM, r.verdict(), "verdict");
      check(r.connectionAgeMs() >= 0, "connectionAgeMs");
      check(rig.fake.socket(0).aborted, "socket aborted");
      checkEquals(List.of("1000:bye"), rig.closes, "user onClose");
      check(!ws.closed(), "custom handler keeps the wrapper open");
      check(r.toTsv().split("\t", -1).length == RetirementRecord.tsvHeader().split("\t", -1).length, "tsv column count");
      return "origin 1, wasDone, no future route";
    }
  }

  static String p2JdkError() throws Exception {
    try (final var rig = new Rig(false)) {
      final var ws = rig.create();
      ws.connect();
      rig.open(0);
      final var failure = new IOException("x");
      rig.fake.listener(0).onError(rig.fake.socket(0), failure);
      final var r = rig.only();
      checkRetirementCommon(r, 1, CauseClass.JDK_ERROR, FrameKind.ON_ERROR, ThreadKind.JDK_LISTENER);
      check(r.causeDetail().startsWith("IOException: x"), "causeDetail " + r.causeDetail());
      checkEquals(CallbackRoute.ON_ERROR_FENCED, r.callbackRoute(), "callbackRoute");
      checkEquals(List.of(failure), rig.errors, "user onError");
      check(rig.pingErrors.isEmpty(), "no onPingError");
      return "origin 1, jdk-error";
    }
  }

  static String p3MaxMessageOverflow() throws Exception {
    try (final var rig = new Rig(false)) {
      final var ws = rig.create(rig.prototype().maxMessageLength(8));
      ws.connect();
      rig.open(0);
      rig.fake.listener(0).onText(rig.fake.socket(0), "0123456789", true);
      final var r = rig.only();
      checkRetirementCommon(r, 1, CauseClass.MAX_MESSAGE_OVERFLOW, FrameKind.ON_TEXT, ThreadKind.JDK_LISTENER);
      check(r.causeDetail().contains("exceeds maxMessageLength"), "causeDetail " + r.causeDetail());
      return "origin 1, overflow inside onText";
    }
  }

  static String p4IdCollision() throws Exception {
    try (final var rig = new Rig(false)) {
      final var ws = rig.create();
      check(ws.accountSubscribe(key(1), _ -> {
      }), "subscribe 1");
      check(ws.accountSubscribe(key(2), _ -> {
      }), "subscribe 2");
      ws.connect();
      rig.open(0);
      final var sent = rig.fake.socket(0).sentText;
      checkEquals(2, sent.size(), "subscribes sent on adoption");
      final long id1 = requestId(sent.get(0));
      final long id2 = requestId(sent.get(1));
      final var listener = rig.fake.listener(0);
      final var socket = rig.fake.socket(0);
      listener.onText(socket, "{\"jsonrpc\":\"2.0\",\"result\":7,\"id\":" + id1 + "}", true);
      check(rig.records.isEmpty(), "first confirmation is not a collision");
      listener.onText(socket, "{\"jsonrpc\":\"2.0\",\"result\":7,\"id\":" + id2 + "}", true);
      final var r = rig.only();
      checkRetirementCommon(r, 1, CauseClass.ID_COLLISION, FrameKind.ON_TEXT, ThreadKind.JDK_LISTENER);
      check(r.causeDetail().contains("non-equivalent"), "causeDetail " + r.causeDetail());
      check(r.causeDetail().contains("us:Subscription id 7"), "engine ERROR line captured in frame: " + r.causeDetail());
      return "origin 1, collision with engine line in frame";
    }
  }

  static String p5UnansweredEscalation() throws Exception {
    try (final var rig = new Rig(false)) {
      final var ws = rig.create();
      ws.accountSubscribe(key(3), _ -> {
      });
      ws.connect();
      rig.open(0);
      rig.clock.advanceMillis(401);
      Seams.checkCycle(ws);
      final var r = rig.only();
      checkRetirementCommon(r, 1, CauseClass.UNANSWERED_REQUEST, FrameKind.LAZY, ThreadKind.OTHER);
      check(r.causeDetail().contains("has gone unanswered"), "causeDetail " + r.causeDetail());
      check(r.causeDetail().contains("us:Request "), "engine WARNING captured before the abort: " + r.causeDetail());
      check(r.buildCancelObserved() && r.buildWasDoneAtCancel(), "cancel observed on the lazy frame");
      checkEquals(FutureRoute.NONE_ALREADY_SETTLED, r.futureRoute(), "futureRoute");
      return "origin 1 on the test thread via checkCycle(0), lazy frame";
    }
  }

  static String p6PingSendTimeout() throws Exception {
    try (final var rig = new Rig(false)) {
      final var ws = rig.create(rig.prototype().pingDelay(10));
      ws.connect();
      rig.fake.socket(0).deferPings = true;
      rig.open(0);
      rig.clock.advanceMillis(11);
      Seams.checkCycle(ws);
      checkEquals(1, rig.fake.socket(0).pings.get(), "one ping sent");
      check(rig.records.isEmpty(), "no escalation while the send is inside its window");
      rig.clock.advanceMillis(11);
      Seams.checkCycle(ws);
      final var r = rig.only();
      checkRetirementCommon(r, 1, CauseClass.PING_SEND_TIMEOUT, FrameKind.LAZY, ThreadKind.OTHER);
      check(r.causeDetail().contains("has not completed"), "causeDetail " + r.causeDetail());
      return "ping send window exceeded";
    }
  }

  static String p7PingResponseTimeout() throws Exception {
    try (final var rig = new Rig(false)) {
      final var ws = rig.create(rig.prototype().pingDelay(10));
      ws.connect();
      rig.fake.socket(0).deferPings = true;
      rig.open(0);
      rig.clock.advanceMillis(11);
      Seams.checkCycle(ws);
      final var socket = rig.fake.socket(0);
      checkEquals(1, socket.deferredPings.size(), "deferred ping");
      socket.deferredPings.get(0).complete(socket);
      rig.clock.advanceMillis(11);
      Seams.checkCycle(ws);
      final var r = rig.only();
      checkRetirementCommon(r, 1, CauseClass.PING_RESPONSE_TIMEOUT, FrameKind.LAZY, ThreadKind.OTHER);
      check(r.causeDetail().contains("has gone unanswered"), "causeDetail " + r.causeDetail());
      return "ping response window exceeded";
    }
  }

  static String p8PingSendFailurePair() throws Exception {
    try (final var rig = new Rig(false)) {
      final var ws = rig.create(rig.prototype().pingDelay(10));
      ws.connect();
      final var failure = new IOException("ping");
      rig.fake.socket(0).failPing = failure;
      rig.open(0);
      rig.clock.advanceMillis(11);
      Seams.checkCycle(ws);
      final var r = rig.only();
      checkRetirementCommon(r, 1, CauseClass.PING_SEND_FAILURE, FrameKind.LAZY, ThreadKind.OTHER);
      checkEquals(PingRoute.PRESENT, r.pingRoute(), "pingRoute");
      check(r.causeDetail().contains("onPingError=IOException"), "causeDetail " + r.causeDetail());
      checkEquals(List.of(failure), rig.errors, "onError received the ping failure");
      checkEquals(List.of(failure), rig.pingErrors, "onPingError received the same instance");
      checkEquals(0L, rig.tracker.stats().pingErrorWithoutPair.sum(), "pair consumed one sub-frame");
      checkEquals(0L, rig.tracker.stats().pingPairMissing.sum(), "no missing pair");
      return "onError+onPingError on one sub-frame, one record";
    }
  }

  static String p9CheckLoopDeath() throws Exception {
    final var loopThread = new Thread[1];
    final var executor = Executors.newSingleThreadExecutor(r -> {
      final var t = new Thread(r, "soak-selftest-loop");
      loopThread[0] = t;
      return t;
    });
    try (final var rig = new Rig(false)) {
      rig.loop = executor;
      rig.tracker.checkLoopThread(t -> t.getName().startsWith("soak-selftest-loop"));
      final var ws = rig.create(rig.prototype().subscriptionAndPingCheckDelay(5));
      ws.connect();
      rig.open(0);
      check(loopThread[0] != null, "loop thread started");
      rig.clock.poison = loopThread[0];
      final var r = rig.arrivals.poll(LATCH_NANOS, TimeUnit.NANOSECONDS);
      check(r != null, "no instance-death record within the bound");
      checkEquals(CauseClass.INSTANCE_DEATH, r.causeClass(), "causeClass");
      checkEquals(Verdict.INSTANCE_DEATH, r.verdict(), "verdict");
      checkEquals("soak-selftest-loop", r.deliveryThread(), "deliveryThread");
      checkEquals(ThreadKind.CHECK_LOOP, r.threadKind(), "threadKind");
      checkEquals(FrameKind.NONE, r.frameKind(), "frameKind");
      check(!r.abortBeforeCallback(), "no abort in frame");
      check(r.causeDetail().contains("no-fresh-abort"), "causeDetail " + r.causeDetail());
      check(r.causeDetail().contains("clock poisoned"), "the loop's exception is the cause: " + r.causeDetail());
      executor.shutdown();
      check(executor.awaitTermination(2, TimeUnit.SECONDS), "loop thread returned");
      check(ws.closed(), "instance closed after loop death");
      checkEquals(1, rig.records.size(), "one record");
      return "instance-death on the loop thread, closed afterwards";
    } finally {
      executor.shutdownNow();
    }
  }

  static String p10CloseNoRecords() throws Exception {
    try (final var rig = new Rig(false)) {
      final var ws = rig.create();
      ws.connect();
      rig.open(0);
      ws.close();
      check(rig.records.isEmpty(), "close() produces no retirement record");
      checkEquals(List.of("1000:close"), rig.fake.socket(0).closeReasons, "polite close");
      checkEquals(1, rig.scheduler.deferred.size(), "watchdog scheduled");
      checkEquals(8000L, rig.scheduler.deferred.get(0).delayMillis(), "watchdog delay");
      checkEquals(1, rig.scheduler.runAll(), "watchdog ran");
      check(rig.fake.socket(0).aborted, "watchdog aborted the socket");
      final var aborts = rig.tracker.recentAborts();
      checkEquals(1, aborts.size(), "one abort");
      check(!aborts.get(0).retirement(), "watchdog abort is not a retirement: " + aborts.get(0));
      check(!aborts.get(0).classifier().equals("retireConnection"), "classifier " + aborts.get(0).classifier());
      check(rig.records.isEmpty(), "still no record");
      return "zero records, watchdog abort classified " + aborts.get(0).classifier();
    }
  }

  static String p11ConnectReplacementAntiMisfire() throws Exception {
    try (final var rig = new Rig(false)) {
      final var ws = rig.create();
      ws.connect();
      rig.open(0);
      ws.connect();
      check(rig.records.isEmpty(), "replacement abort produced no record");
      final var aborts = rig.tracker.recentAborts();
      checkEquals(1, aborts.size(), "one abort so far");
      checkEquals(1L, aborts.get(0).ordinal(), "abort ordinal");
      checkEquals("connect", aborts.get(0).classifier(), "abort classifier");
      check(!aborts.get(0).retirement(), "not a retirement");
      check(rig.tracker.attempt(1).replaced(), "attempt 1 marked replaced");
      checkEquals(2, rig.fake.builds.get(), "second build");
      rig.open(1);
      rig.fake.listener(1).onClose(rig.fake.socket(1), 1000, "bye");
      final var r = rig.only();
      checkRetirementCommon(r, 2, CauseClass.JDK_CLOSE, FrameKind.ON_CLOSE, ThreadKind.JDK_LISTENER);
      rig.fake.listener(0).onClose(rig.fake.socket(0), 1001, "late");
      checkEquals(1, rig.records.size(), "late callback of the replaced socket dropped");
      return "origin 2 despite the stale abort of 1 on the same thread";
    }
  }

  static String p12StaleAdoptAndOwnBuild() throws Exception {
    // (a) close() while the build is pending, with a JDK-shaped uncancellable future: the socket
    //     arrives after the harness future was cancelled and is aborted by the orphan guard.
    try (final var rig = new Rig(false)) {
      rig.fake.uncancellableFutures = true;
      final var ws = rig.create();
      ws.connect();
      ws.close();
      check(rig.tracker.attempt(1).cancelledBy().equals("close"), "cancelled by close: " + rig.tracker.attempt(1));
      rig.fake.completeBuild(0);
      check(rig.fake.socket(0).aborted, "orphaned socket aborted");
      check(rig.records.isEmpty(), "no record");
      checkEquals(1L, rig.tracker.stats().orphanSocketsAborted.sum(), "orphan guard");
      checkEquals(NextAttemptOutcome.FAILED, rig.tracker.attemptOutcome(1), "attempt 1 outcome");
    }
    // (b) late onOpen after a reconnect: adopt's stale arm aborts, classified 'adopt', no record.
    try (final var rig = new Rig(false)) {
      final var ws = rig.create();
      ws.connect();
      rig.open(0);
      ws.connect();
      rig.open(1);
      rig.fake.listener(0).onOpen(rig.fake.socket(0));
      check(rig.records.isEmpty(), "stale adopt produced no record");
      final var aborts = rig.tracker.recentAborts();
      final var stale = aborts.stream().filter(a -> a.classifier().equals("adopt")).findFirst();
      check(stale.isPresent(), "adopt-stale abort recorded: " + aborts);
      checkEquals(1L, stale.get().ordinal(), "stale abort ordinal");
      check(!stale.get().retirement(), "not a retirement");
      check(rig.tracker.attempt(2).installed(), "attempt 2 still current");
    }
    // (c) retirement inside adopt with a synchronous fake: ownBuild's stale hook re-aborts the
    //     retired socket (deduplicated) and is classified lambda$ownBuild.
    try (final var rig = new Rig(false)) {
      rig.fake.deferFutures = false;
      rig.fake.invokeOnOpen = true;
      rig.fake.configureNext = s -> s.requestAction = () -> rig.fake.listener(0).onError(s, new IOException("soak: injected in-adopt retirement"));
      final var ws = rig.create();
      final var attempt = ws.connect();
      check(attempt.isCompletedExceptionally(), "attempt settled exceptionally by the in-adopt retirement");
      final var r = rig.only();
      checkRetirementCommon(r, 1, CauseClass.HARNESS_INJECTED, FrameKind.ON_ERROR, ThreadKind.ADOPTING);
      check(!r.buildCancelObserved(), "build not yet installed at the retirement");
      final var classifiers = rig.tracker.recentAborts().stream().map(AttemptTracker.AbortEntry::classifier).toList();
      check(classifiers.stream().anyMatch(c -> c.startsWith("lambda$ownBuild")), "ownBuild stale hook: " + classifiers);
      check(classifiers.contains("buildReservedAttempt"), "not-installed arm re-abort: " + classifiers);
      check(rig.tracker.recentAborts().stream().filter(a -> !a.duplicate()).count() == 1, "one non-duplicate abort");
      check(rig.fake.socket(0).aborted, "socket aborted");
    }
    return "orphan guard, adopt-stale and ownBuild-stale all classified, no records";
  }

  static String p13CancelledBeforeInstall() throws Exception {
    try (final var rig = new Rig(false)) {
      final var ws = rig.create();
      rig.fake.beforeReturn = ws::close;
      final var attempt = ws.connect();
      check(attempt.isCompletedExceptionally(), "connect() copy failed");
      check(ws.closed(), "closed inside buildAsync");
      final var r = rig.only();
      checkEquals(CauseClass.CANCELLED_BEFORE_INSTALL, r.causeClass(), "causeClass");
      checkEquals(1L, r.originOrdinal(), "originOrdinal");
      check(r.buildCancelObserved() && !r.buildWasDoneAtCancel(), "cancel observed, not done");
      check(!r.abortBeforeCallback(), "no abort");
      checkEquals(FrameKind.NONE, r.frameKind(), "frameKind");
      checkEquals(Verdict.ATTRIBUTED_SINGLE_CLAIM, r.verdict(), "verdict");
      check(rig.tracker.attempt(1).cancelledBeforeInstall(), "attempt flagged");
      checkEquals(NextAttemptOutcome.CANCELLED_BEFORE_INSTALL, rig.tracker.attemptOutcome(1), "attempt outcome");
      check(rig.tracker.recentAborts().isEmpty(), "no abort at all");
      return "attempt 1 cancelled-before-install, failed copy";
    }
  }

  static String p14NestedDelivery() throws Exception {
    try (final var rig = new Rig(false)) {
      final var reconnects = new AtomicInteger();
      // Replaces the rig's default onError (builder setters overwrite), so it keeps collecting.
      final var prototype = rig.prototype()
          .pingDelay(-1)
          .onError((ws, error) -> {
            rig.errors.add(error);
            if (reconnects.getAndIncrement() == 0) {
              ws.connect();
            }
          });
      final var ws = rig.create(prototype);
      ws.connect();
      rig.open(0);
      // The reconnect inside the handler builds attempt 2 synchronously, whose first-pass ping
      // fails at once: a nested retirement inside attempt 1's onError chain.
      rig.fake.deferFutures = false;
      rig.fake.invokeOnOpen = true;
      rig.fake.configureNext = s -> s.failPing = new IOException("ping-2");
      rig.fake.listener(0).onError(rig.fake.socket(0), new IOException("outer"));
      checkEquals(2, rig.records.size(), "two records");
      final var inner = rig.records.get(0);
      final var outer = rig.records.get(1);
      checkRetirementCommon(inner, 2, CauseClass.PING_SEND_FAILURE, FrameKind.ON_OPEN, ThreadKind.ADOPTING);
      checkEquals(PingRoute.PRESENT, inner.pingRoute(), "inner pingRoute");
      checkRetirementCommon(outer, 1, CauseClass.JDK_ERROR, FrameKind.ON_ERROR, ThreadKind.JDK_LISTENER);
      checkEquals(PingRoute.ABSENT, outer.pingRoute(), "outer pingRoute");
      check(outer.causeDetail().startsWith("IOException: outer"), "outer cause " + outer.causeDetail());
      checkEquals(2, reconnects.get(), "handler ran for both");
      checkEquals(2, rig.errors.size(), "two user onError");
      checkEquals(1, rig.pingErrors.size(), "one user onPingError");
      final var classifiers = rig.tracker.recentAborts().stream().map(AttemptTracker.AbortEntry::classifier).toList();
      check(classifiers.stream().anyMatch(c -> c.startsWith("lambda$ownBuild")), "ownBuild stale hook on the nested build: " + classifiers);
      checkEquals(0L, rig.tracker.stats().pingErrorWithoutPair.sum(), "pair consumed the inner sub-frame");
      return "inner origin 2 (onError+onPingError) then outer origin 1";
    }
  }

  /// The arming arithmetic on its own: the workload arms once and every later attempt is
  /// measured against that series, so a reconnect that outruns a re-arm cannot lose the
  /// injection. Asked through the tracker's claim seam, which is what the adoption site asks.
  static String p15InjectionArming() throws Exception {
    try (final var rig = new Rig(true)) {
      final var tracker = rig.tracker;
      tracker.injectRetirementEvery(2, 2);
      check(!tracker.claimInjection(0), "the ordinal current at arming time is not selected");
      check(tracker.claimInjection(1), "first target");
      check(!tracker.claimInjection(2), "off the series");
      check(tracker.claimInjection(3), "one period on");
      checkEquals(0L, tracker.stats().injectionCapReached.sum(), "budget not yet spent");
      check(!tracker.claimInjection(5), "budget spent");
      checkEquals(1L, tracker.stats().injectionCapReached.sum(), "ceiling counted once");
      check(!tracker.claimInjection(7), "still spent");
      checkEquals(1L, tracker.stats().injectionCapReached.sum(), "ceiling counted once per arming");
      tracker.injectRetirementAt(9);
      check(!tracker.claimInjection(10), "a one-shot arming selects nothing but its ordinal");
      check(tracker.claimInjection(9), "one-shot target");
      tracker.injectRetirementAt(AttemptTracker.NO_INJECTION);
      check(!tracker.claimInjection(11), "disarmed");
    }
    try (final var rig = new Rig(false)) {
      boolean refused = false;
      try {
        rig.tracker.injectRetirementEvery(1, 1);
      } catch (final IllegalStateException e) {
        refused = true;
      }
      check(refused, "an unforced engine refuses to be armed");
    }
    return "periodic arming selects its series, counts its ceiling, and is forced-only";
  }

  // --------------------------------------------------------------------- #52 controls

  private static void inject(final Rig rig, final int index) {
    final var socket = rig.fake.socket(index);
    final var listener = rig.fake.listener(index);
    socket.requestAction = () -> listener.onError(socket, new IOException("soak: injected in-adopt retirement"));
  }

  private static String oracleNote(final ManagerOracle oracle) {
    if (oracle == null) {
      final String skip = "ORACLE SKIP manager reflection unavailable (launch needs --add-opens software.sava.ravina_solana/software.sava.services.solana.websocket=software.sava.rpc.soak)";
      if (!skips.contains(skip)) {
        skips.add(skip);
      }
      return ", oracle skipped";
    }
    return ", oracle confirmed";
  }

  static String negativeControl() throws Exception {
    final var capture = ManagerLogCapture.installed();
    attemptFailedBaseline = capture.count(ManagerLogCapture.PrefixClass.ATTEMPT_FAILED);
    try (final var rig = new Rig(true)) {
      final var manager = WebSocketManager.createManager(
          rig.tracker.backoff(Backoff.single(MILLISECONDS, 0)),
          rig.tracker.instrument(rig.prototype()),
          null,
          rig.clock
      );
      rig.manager = manager;
      final var oracle = ManagerOracle.of(manager);
      capture.onAttemptFailedHook(manager::checkConnection);
      try {
        final var ws = manager.webSocket();
        check(ws != null, "manager created and started the first attempt");
        checkEquals(1, rig.fake.builds.get(), "first build");
        inject(rig, 0);
        rig.fake.listener(0).onOpen(rig.fake.socket(0));
        final var r = rig.only();
        checkRetirementCommon(r, 1, CauseClass.HARNESS_INJECTED, FrameKind.ON_ERROR, ThreadKind.ADOPTING);
        check(r.buildCancelObserved() && !r.buildWasDoneAtCancel(), "future settled by the retirement");
        checkEquals(FutureRoute.ACCEPTED, r.futureRoute(), "futureRoute");
        checkEquals(CallbackRoute.ON_ERROR_ACCEPTED, r.callbackRoute(), "callbackRoute");
        checkEquals(2, r.claimCount(), "claimCount");
        checkEquals(0L, r.errorCountBefore(), "errorCountBefore");
        checkEquals(2L, r.errorCountAfter(), "errorCountAfter");
        checkEquals(0L, r.retryDelayMs(), "retryDelayMs");
        checkEquals(0L, r.loggedRetryMs(), "loggedRetryMs");
        check(r.gapFutureToCallbackUs() >= 0, "gap defined");
        check(r.retryMarginUs() <= 0, "opportunity margin");
        checkEquals(2L, r.currentOrdinalAtCallbackClaim(), "currentOrdinalAtCallbackClaim");
        check(r.successorBuiltInsideGap(), "successor built inside the gap");
        checkEquals(Verdict.DOUBLE_CLAIM_MISATTRIBUTED, r.verdict(), "verdict");
        checkEquals(2L, r.nextAttemptOrdinal(), "nextAttemptOrdinal");
        checkEquals(2, rig.claims.size(), "claims");
        final var first = rig.claims.get(0);
        final var second = rig.claims.get(1);
        check(first.insideBuildCancel() && first.route() == ClaimRecord.Route.FUTURE && first.errorCount() == 1 && first.originOrdinal() == 1,
            "claim 1 " + first);
        check(!second.insideBuildCancel() && second.route() == ClaimRecord.Route.CALLBACK && second.errorCount() == 2
            && second.originOrdinal() == 1 && second.currentOrdinal() == 2, "claim 2 " + second);
        checkEquals(2, rig.fake.builds.get(), "successor built");
        checkEquals(1L, capture.count(ManagerLogCapture.PrefixClass.ATTEMPT_FAILED) - attemptFailedBaseline, "one attempt-failed line");
        if (oracle != null) {
          final var s = oracle.snapshot();
          checkEquals(2, s.errorCount(), "oracle errorCount after claim 2");
          checkEquals(2L, s.retrySequence(), "oracle retrySequence");
          checkEquals("BACKING_OFF", s.state(), "oracle state");
        }
        // attempt 2 opens while the manager is BACKING_OFF: unacknowledged
        rig.fake.listener(1).onOpen(rig.fake.socket(1));
        check(rig.tracker.attempt(2).installed() && !rig.tracker.attempt(2).acknowledged(), "attempt 2 opened unacknowledged");
        rig.fake.completeBuild(1);
        check(!rig.tracker.attempt(2).acknowledged(), "attempt 2 still unacknowledged after its future settled");
        manager.checkConnection();
        checkEquals(3, rig.fake.builds.get(), "retry replaced 2 with 3");
        check(rig.tracker.attempt(2).replaced(), "attempt 2 replaced without callback");
        checkEquals(NextAttemptOutcome.OPENED_UNACKNOWLEDGED_REPLACED, rig.tracker.attemptOutcome(2), "attempt 2 outcome");
        checkEquals(1, rig.records.size(), "replacement produced no record");
        rig.fake.completeBuild(2);
        rig.fake.listener(2).onOpen(rig.fake.socket(2));
        check(rig.tracker.attempt(3).acknowledged(), "attempt 3 acknowledged");
        checkEquals(NextAttemptOutcome.OPENED_ACKNOWLEDGED, rig.tracker.attemptOutcome(3), "attempt 3 outcome");
        checkEquals(0L, rig.tracker.lastErrorCount(), "errorCount reset on connected");
        if (oracle != null) {
          checkEquals(0, oracle.snapshot().errorCount(), "oracle errorCount after attempt 3 opened");
        }
        final var summary = RetirementDetector.evaluate(rig.records, rig.claims, rig.tracker.backoffClass(), true);
        checkEquals(1L, summary.misattributed(), "summary misattributed");
        checkEquals(0L, summary.doubleClaimsUnexplained(), "summary unexplained");
        check(!summary.triggerMet(), "forced constant-zero never meets the trigger");
        checkEquals(BackoffClass.CONSTANT_ZERO, summary.backoffClass(), "backoff class");
        return "double claim flagged, errorCount 2, attempt 2 unacknowledged -> replaced by 3" + oracleNote(oracle);
      } finally {
        capture.onAttemptFailedHook(null);
      }
    }
  }

  private static long attemptFailedBaseline;

  static String positiveControl() throws Exception {
    final var capture = ManagerLogCapture.installed();
    try (final var rig = new Rig(true)) {
      final var manager = WebSocketManager.createManager(
          rig.tracker.backoff(Backoff.single(MILLISECONDS, 10_000)),
          rig.tracker.instrument(rig.prototype()),
          null,
          rig.clock
      );
      rig.manager = manager;
      final var oracle = ManagerOracle.of(manager);
      capture.onAttemptFailedHook(manager::checkConnection);
      try {
        manager.webSocket();
        inject(rig, 0);
        rig.fake.listener(0).onOpen(rig.fake.socket(0));
        final var r = rig.only();
        checkRetirementCommon(r, 1, CauseClass.HARNESS_INJECTED, FrameKind.ON_ERROR, ThreadKind.ADOPTING);
        checkEquals(FutureRoute.ACCEPTED, r.futureRoute(), "futureRoute");
        checkEquals(CallbackRoute.ON_ERROR_FENCED, r.callbackRoute(), "callbackRoute");
        checkEquals(1, r.claimCount(), "claimCount");
        checkEquals(1L, r.errorCountAfter(), "errorCountAfter");
        checkEquals(10_000L, r.retryDelayMs(), "retryDelayMs");
        checkEquals(10_000L, r.loggedRetryMs(), "loggedRetryMs");
        check(r.retryMarginUs() > 0, "positive margin " + r.retryMarginUs());
        check(!r.successorBuiltInsideGap(), "no successor inside the gap");
        checkEquals(Verdict.FENCED_CORRECTLY, r.verdict(), "verdict");
        checkEquals(1, rig.fake.builds.get(), "retry not due: no successor yet");
        if (oracle != null) {
          checkEquals(1, oracle.snapshot().errorCount(), "oracle errorCount");
        }
        rig.clock.advanceMillis(10_000);
        manager.checkConnection();
        checkEquals(2, rig.fake.builds.get(), "retry built attempt 2");
        rig.fake.listener(1).onOpen(rig.fake.socket(1));
        check(rig.tracker.attempt(2).acknowledged() && !rig.tracker.attempt(2).acknowledgedViaFuture(), "attempt 2 acknowledged via onOpen");
        rig.fake.completeBuild(1);
        checkEquals(NextAttemptOutcome.OPENED_ACKNOWLEDGED, rig.tracker.attemptOutcome(2), "attempt 2 outcome");
        if (oracle != null) {
          final var s = oracle.snapshot();
          checkEquals(0, s.errorCount(), "oracle errorCount reset");
          checkEquals("OPEN", s.state(), "oracle state");
        }
        checkEquals(1, rig.records.size(), "one record overall");
        final var summary = RetirementDetector.evaluate(rig.records, rig.claims, rig.tracker.backoffClass(), true);
        checkEquals(0L, summary.misattributed(), "summary misattributed");
        checkEquals(0L, summary.opportunities(), "summary opportunities");
        return "single accepted future claim, callback fenced, attempt 2 acknowledged" + oracleNote(oracle);
      } finally {
        capture.onAttemptFailedHook(null);
      }
    }
  }

  static String threadedNegativeControl() throws Exception {
    final var capture = ManagerLogCapture.installed();
    final long blockedBefore = capture.blockedWaits();
    final long timeoutsBefore = capture.blockedTimeouts();
    try (final var rig = new Rig(true)) {
      rig.seams = false;
      rig.tracker.blockAttemptFailedUntilSuccessor(true);
      final var manager = WebSocketManager.createManager(
          rig.tracker.backoff(Backoff.single(MILLISECONDS, 0)),
          rig.tracker.instrument(rig.prototype()),
          null
      );
      rig.manager = manager;
      manager.webSocket();
      checkEquals(1, rig.fake.builds.get(), "first build");
      inject(rig, 0);
      rig.fake.listener(0).onOpen(rig.fake.socket(0));
      final var r = rig.only();
      checkRetirementCommon(r, 1, CauseClass.HARNESS_INJECTED, FrameKind.ON_ERROR, ThreadKind.ADOPTING);
      checkEquals(FutureRoute.ACCEPTED, r.futureRoute(), "futureRoute");
      checkEquals(CallbackRoute.ON_ERROR_ACCEPTED, r.callbackRoute(), "callbackRoute");
      checkEquals(Verdict.DOUBLE_CLAIM_MISATTRIBUTED, r.verdict(), "verdict");
      check(r.successorBuiltInsideGap(), "successor built inside the gap");
      checkEquals(2L, r.currentOrdinalAtCallbackClaim(), "currentOrdinalAtCallbackClaim");
      check(rig.fake.builds.get() >= 2, "successor built");
      final var successor = rig.tracker.attempt(2);
      check(successor != null && !successor.buildThread().equals(threadName()), "buildAsync(2) on a different thread: " + successor);
      checkEquals(1L, capture.blockedWaits() - blockedBefore, "handler blocked once");
      checkEquals(0L, capture.blockedTimeouts() - timeoutsBefore, "no blocking timeout");
      check(r.harnessTimeInGapUs() > 0, "blocking wait charged to the gap");
      return "double claim with buildAsync(2) on " + successor.buildThread() + " inside the gap";
    }
  }

  static String jdkOrderControl() throws Exception {
    final var capture = ManagerLogCapture.installed();
    try (final var rig = new Rig(false)) {
      final var manager = WebSocketManager.createManager(
          rig.tracker.backoff(Backoff.single(MILLISECONDS, 0)),
          rig.tracker.instrument(rig.prototype()),
          null,
          rig.clock
      );
      rig.manager = manager;
      final var oracle = ManagerOracle.of(manager);
      capture.onAttemptFailedHook(manager::checkConnection);
      try {
        manager.webSocket();
        // JDK 25 order: the future settles first, markOpen consumes the manager's copy.
        rig.fake.completeBuild(0);
        final var before = rig.tracker.attempt(1);
        check(before.acknowledged() && before.acknowledgedViaFuture(), "acknowledged through the future: " + before);
        inject(rig, 0);
        rig.fake.listener(0).onOpen(rig.fake.socket(0));
        final var r = rig.only();
        checkRetirementCommon(r, 1, CauseClass.HARNESS_INJECTED, FrameKind.ON_ERROR, ThreadKind.ADOPTING);
        checkEquals(OpenOrder.FUTURE_FIRST, r.openOrder(), "openOrder");
        check(r.buildCancelObserved() && r.buildWasDoneAtCancel(), "cancel found the build settled");
        checkEquals(FutureRoute.NONE_ALREADY_SETTLED, r.futureRoute(), "no future route");
        checkEquals(CallbackRoute.ON_ERROR_ACCEPTED, r.callbackRoute(), "callback claim accepted");
        checkEquals(1, r.claimCount(), "claimCount");
        checkEquals(Verdict.ATTRIBUTED_SINGLE_CLAIM, r.verdict(), "verdict");
        checkEquals(1, rig.fake.builds.get(), "no successor inside the handler");
        if (oracle != null) {
          checkEquals(1, oracle.snapshot().errorCount(), "oracle errorCount");
        }
        return "future-first open yields one correctly attributed callback claim" + oracleNote(oracle);
      } finally {
        capture.onAttemptFailedHook(null);
      }
    }
  }
}
