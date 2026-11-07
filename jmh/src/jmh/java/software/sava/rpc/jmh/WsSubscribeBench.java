package software.sava.rpc.jmh;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.rpc.json.http.ws.Subscription;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/// Prices the websocket engine's subscribe pipeline through its public API: how long
/// `SolanaRpcWebsocket` takes to register, transmit and confirm a burst of account
/// subscriptions, and to re-establish them after the peer drops the connection. The peer is
/// a stub transport, so the scores are the client's own cost: every `sendText` future
/// settles on a dedicated transport thread, which then feeds the matching JSON-RPC
/// confirmation back into the engine's listener from that same thread. The JDK's transport
/// instead completes a send inside `sendText` when the connection is writable and no other
/// thread is sending a frame on it, and in production the sends a maintenance pass chains, and
/// each `onSub` callback, then run inside that pass under the engine's lock. Here each send
/// completes on the transport thread, which then runs that send's `onSub` callbacks and the
/// sends chained behind it outside the pass, unless it settled the future before the engine
/// attached its callback, when they run in the pass as on the JDK. The rows are therefore no
/// measurement of that hold: they include it only when that race goes the JDK's way. A batch
/// request (a JSON array) is answered with an array of confirmations in request order, as Agave
/// answers it.
///
/// Rows (`subscriptions` is the burst size; scores are milliseconds per burst):
///
/// - `burst` — one thread registers `subscriptions` accounts on a fresh engine, one
///   `accountSubscribe` call at a time; the sample ends when every registration has been
///   transmitted and its confirmation installed.
/// - `burstScoped` — `burst` made inside one
///   [SolanaRpcWebsocket#registerAll(java.util.function.Consumer)] scope, so its registrations
///   wake the maintenance loop once, at the scope's exit, instead of once per call.
/// - `replay` — an engine whose `subscriptions` registrations are confirmed is told the peer
///   closed; the sample runs from that close to the last re-confirmation on the replacement
///   transport. The engine paces a reconnect's replay by the re-send window (a re-queued
///   registration keeps its last-attempt stamp, see `Builder#subscriptionResendDelay`), so the
///   fixture lets that window elapse, untimed, before the close: the row prices the replay
///   work, not the documented wait.
/// - `backlog` — one registration at a time, each timed from its `accountSubscribe` call to
///   its `onSub` callback (its frame sent and the send completed), on an engine holding
///   `pending` earlier registrations the peer never answers; scores are microseconds per
///   registration. It prices what every maintenance pass pays for the requests awaiting an
///   answer, since each registration wakes the loop for one pass.
///
/// `burst`, `burstScoped` and `replay` also take `batchBytes`, the cap the fixture hands
/// `Builder.subscribeBatchBytes(int)`: 0, the default, sends one message per request, and 4096
/// ([Burst#batchBytes] says where that value comes from) lets each maintenance pass pack the
/// subscribes it finds due into JSON-RPC batch arrays of at most that many UTF-8 bytes; a pass
/// that finds a single subscribe due sends it as its plain object, never as an array of one. A
/// replay's first pass finds every registration due, and so does the pass a `burstScoped`
/// scope's exit wakes, so their arrays are as full as the cap allows. `burst` registers outside a
/// [SolanaRpcWebsocket#registerAll(java.util.function.Consumer)] scope, so each call wakes the
/// loop and an array holds only the registrations that queued since the previous pass. The
/// backlog row keeps batching off.
///
/// The burst and replay rows build a fresh engine per invocation, since registrations are
/// durable and a second burst on the same instance would be all duplicates; the backlog row
/// keeps one engine per trial and registers a new key each invocation. The re-send window is
/// [#RESEND_DELAY_MILLIS], and the engine's unanswered-request deadline,
/// `SolanaJsonRpcWebsocket.UNANSWERED_ESCALATION_FACTOR` of those windows, must exceed the time
/// a frame waits in the one-outstanding-send chain behind a whole burst, or the engine
/// replaces the connection mid-sample (the backlog engine, whose requests stay
/// unanswered on purpose, uses [#BACKLOG_RESEND_DELAY_MILLIS] instead). The README's results
/// say which engine each recorded row was measured on.
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 10)
public class WsSubscribeBench {

  /// Re-send window in milliseconds. The replay fixture waits it out before the close, and the
  /// engine's unanswered-request deadline, `SolanaJsonRpcWebsocket.UNANSWERED_ESCALATION_FACTOR`
  /// of these windows, bounds how long a burst may keep a frame queued unanswered.
  static final long RESEND_DELAY_MILLIS = 500;

  /// The backlog engine's re-send window: an hour, so its deliberately unanswered requests
  /// neither fall due again nor reach the unanswered deadline within a trial.
  static final long BACKLOG_RESEND_DELAY_MILLIS = 3_600_000;

  @State(Scope.Thread)
  public static class Burst {

    @Param({"1000", "10000"})
    int subscriptions;

    /// The cap handed to `Builder.subscribeBatchBytes(int)`, in UTF-8 bytes per message: 0
    /// keeps batching off, the default, and 4096 is
    /// `SolanaRpcWebsocketBuilder.MAX_SUBSCRIBE_BATCH_BYTES`, the largest cap the Builder accepts
    /// (Agave's inclusive inbound message limit).
    @Param({"0", "4096"})
    int batchBytes;

    Fixture fixture;

    @Setup(Level.Invocation)
    public void setup() throws InterruptedException {
      fixture = new Fixture(subscriptions, RESEND_DELAY_MILLIS, 0, batchBytes);
    }

    @TearDown(Level.Invocation)
    public void tearDown() {
      fixture.close();
    }
  }

  @State(Scope.Thread)
  public static class Replay {

    @Param({"1000", "10000"})
    int subscriptions;

    /// As [Burst#batchBytes]. The cap covers the untimed registrations made in the setup as
    /// well as the timed replay.
    @Param({"0", "4096"})
    int batchBytes;

    Fixture fixture;

    @Setup(Level.Invocation)
    public void setup() throws InterruptedException {
      fixture = new Fixture(subscriptions, RESEND_DELAY_MILLIS, 0, batchBytes);
      fixture.registerEach();
      fixture.awaitConfirmed();
      // Let the re-send window pass, so the replay after the close is due at once.
      Thread.sleep(RESEND_DELAY_MILLIS + 100);
    }

    @TearDown(Level.Invocation)
    public void tearDown() {
      fixture.close();
    }
  }

  @State(Scope.Thread)
  public static class Backlog {

    @Param({"0", "2000", "10000"})
    int pending;

    Fixture fixture;
    private long nextKey;

    @Setup(Level.Trial)
    public void setup() throws InterruptedException {
      // The peer withholds every answer to the `pending` registrations made here, so each
      // stays awaiting an answer for the whole trial; it answers everything registered later.
      // Batching stays off: a timed registration is the only request its pass finds ready, so
      // it would travel alone anyway, and the row keeps the default send path.
      fixture = new Fixture(pending, BACKLOG_RESEND_DELAY_MILLIS, pending, 0);
      fixture.registerEach();
      fixture.awaitSent();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      fixture.close();
    }

    /// A fresh key per invocation: the fixture's keys are random, this counter cannot
    /// collide with them in practice, and the engine refuses a duplicate anyway.
    PublicKey nextKey() {
      final var bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
      final long n = ++nextKey;
      for (int i = 0; i < Long.BYTES; ++i) {
        bytes[i] = (byte) (n >>> (i << 3));
      }
      return PublicKey.createPubKey(bytes);
    }
  }

  @Benchmark
  public void burst(final Burst state, final Blackhole blackhole) {
    state.fixture.registerEach();
    state.fixture.awaitConfirmed();
    blackhole.consume(state.fixture.handles);
  }

  /// The burst made inside one registration scope: the calls are the same, but the loop wakes
  /// once, when the scope returns, and the pass it runs finds the whole burst due.
  @Benchmark
  public void burstScoped(final Burst state, final Blackhole blackhole) {
    state.fixture.websocket.registerAll(_ -> state.fixture.registerEach());
    state.fixture.awaitConfirmed();
    blackhole.consume(state.fixture.handles);
  }

  @Benchmark
  public void replay(final Replay state, final Blackhole blackhole) throws InterruptedException {
    state.fixture.peerCloses();
    state.fixture.awaitConfirmed();
    blackhole.consume(state.fixture.handles);
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  @Warmup(iterations = 3, time = 1)
  @Measurement(iterations = 5, time = 1)
  public Subscription<AccountInfo<byte[]>> backlog(final Backlog state) {
    return state.fixture.registerOne(state.nextKey());
  }

  /// One engine with `subscriptions` keys, its stub transports, and the handles its onSub
  /// callbacks hand back.
  static final class Fixture {

    final int subscriptions;
    final List<PublicKey> keys;
    /// One slot per key, filled by that registration's onSub callback on the transport thread
    /// and read by the polling benchmark thread: lock-free, so polling cannot starve the thread
    /// that also drains the send chain and echoes confirmations.
    final AtomicReferenceArray<Subscription<AccountInfo<byte[]>>> handles;
    /// The backlog row's one slot: the handle of the registration being timed.
    final AtomicReference<Subscription<AccountInfo<byte[]>>> single = new AtomicReference<>();
    final StubBuilder builder;
    final AtomicReference<CountDownLatch> opened = new AtomicReference<>(new CountDownLatch(1));
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final SolanaRpcWebsocket websocket;

    /// An engine over a stub peer which withholds its answers to the first `withheldAnswers`
    /// subscriptions it is asked for and answers everything else. The engine sends its
    /// subscribes in batch messages of at most `batchBytes` UTF-8 bytes, or each in its own
    /// message at 0.
    Fixture(final int subscriptions,
            final long resendDelayMillis,
            final int withheldAnswers,
            final int batchBytes) throws InterruptedException {
      this.subscriptions = subscriptions;
      final var random = new Random(0x5ab5c21bL + subscriptions);
      this.keys = new ArrayList<>(subscriptions);
      final var bytes = new byte[PublicKey.PUBLIC_KEY_LENGTH];
      for (int i = 0; i < subscriptions; ++i) {
        random.nextBytes(bytes);
        keys.add(PublicKey.createPubKey(bytes.clone()));
      }
      this.handles = new AtomicReferenceArray<>(subscriptions);
      this.builder = new StubBuilder(withheldAnswers);
      this.websocket = SolanaRpcWebsocket.build()
          .uri(URI.create("ws://stub.invalid"))
          .webSocketBuilder(builder)
          .reConnectDelay(1)
          .subscriptionResendDelay(resendDelayMillis)
          .subscribeBatchBytes(batchBytes)
          .onOpen(_ -> opened.get().countDown())
          .onClose((ws, _, _) -> ws.connect())
          .onError((_, ex) -> failure.compareAndSet(null, ex))
          .create();
      websocket.connect().join();
      awaitOpen();
    }

    /// Registers `key` and waits for its onSub callback: the request's frame was sent and the
    /// send completed. The backlog row's timed body.
    Subscription<AccountInfo<byte[]>> registerOne(final PublicKey key) {
      single.set(null);
      if (!websocket.accountSubscribe(key, single::set, _ -> {
      })) {
        throw new IllegalStateException("registration refused for " + key, failure.get());
      }
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (true) {
        final var handle = single.get();
        if (handle != null) {
          return handle;
        }
        final var error = failure.get();
        if (error != null) {
          throw new IllegalStateException("engine error before the registration was sent", error);
        }
        if (System.nanoTime() > deadline) {
          throw new IllegalStateException("the registration was not sent before the deadline");
        }
        Thread.onSpinWait();
      }
    }

    /// Registers every key, each with its own `accountSubscribe` call on this thread. Each call
    /// wakes the engine's check loop, unless it runs inside a
    /// [SolanaRpcWebsocket#registerAll(java.util.function.Consumer)] scope, whose exit wakes the
    /// loop once for them all.
    void registerEach() {
      for (int i = 0; i < subscriptions; ++i) {
        final int slot = i;
        final var key = keys.get(i);
        final boolean registered = websocket.accountSubscribe(key, sub -> handles.set(slot, sub), _ -> {
        });
        if (!registered) {
          throw new IllegalStateException("registration refused for " + key, failure.get());
        }
      }
    }

    void peerCloses() throws InterruptedException {
      opened.set(new CountDownLatch(1));
      builder.current().close(1006, "peer went away");
      awaitOpen();
    }

    private void awaitOpen() throws InterruptedException {
      if (!opened.get().await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("the stub transport never opened", failure.get());
      }
    }

    /// Confirmed means the engine installed the server's subscription id on the handle, which
    /// happens on the listener thread after the confirmation frame is parsed; polling the
    /// handles is the only public evidence of it. Confirmations land in registration order
    /// (one send chain, one answering thread), so a cursor over the slots is linear in total.
    void awaitConfirmed() {
      await(true);
    }

    /// Sent means every registration's onSub callback ran: its frame was sent and the send
    /// completed, whether or not the peer will ever answer it.
    void awaitSent() {
      await(false);
    }

    private void await(final boolean confirmed) {
      final var state = confirmed ? "confirmed" : "sent";
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      int done = 0;
      while (done < subscriptions) {
        final var handle = handles.get(done);
        if (handle != null && (!confirmed || handle.subId() != null)) {
          ++done;
          continue;
        }
        final var error = failure.get();
        if (error != null) {
          throw new IllegalStateException("engine error with " + done + " of " + subscriptions + ' ' + state, error);
        }
        if (System.nanoTime() > deadline) {
          throw new IllegalStateException(done + " of " + subscriptions + ' ' + state + " before the deadline");
        }
        Thread.onSpinWait();
      }
    }

    void close() {
      websocket.close();
      builder.shutdown();
    }
  }

  /// One stub socket per connection attempt: records nothing, answers everything, on its own
  /// thread.
  static final class StubTransport implements WebSocket {

    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
      final var t = new Thread(r, "stub-transport");
      t.setDaemon(true);
      return t;
    });
    private final WebSocket.Listener listener;
    private final AtomicLong nextSubId;
    private final AtomicInteger withheldAnswers;
    private volatile boolean closed;

    StubTransport(final WebSocket.Listener listener,
                  final AtomicLong nextSubId,
                  final AtomicInteger withheldAnswers) {
      this.listener = listener;
      this.nextSubId = nextSubId;
      this.withheldAnswers = withheldAnswers;
    }

    void open(final CompletableFuture<WebSocket> connected) {
      thread.execute(() -> {
        listener.onOpen(this);
        connected.complete(this);
      });
    }

    void close(final int statusCode, final String reason) {
      closed = true;
      thread.execute(() -> listener.onClose(this, statusCode, reason));
    }

    @Override
    public CompletableFuture<WebSocket> sendText(final CharSequence data, final boolean last) {
      final var msg = data.toString();
      final var future = new CompletableFuture<WebSocket>();
      thread.execute(() -> {
        future.complete(this);
        if (!closed) {
          final var answer = answer(msg);
          if (answer != null) {
            listener.onText(this, answer, true);
          }
        }
      });
      return future;
    }

    /// The server's answer to a request frame: `true` for an un-subscription, a fresh
    /// subscription id for a subscription, and for a batch array one element per request in
    /// request order; null when every subscription in it is withheld, so nothing is answered.
    /// The engine renders every request as `{"jsonrpc":"2.0","id":N,"method":"...",...}`, so
    /// each element is located by its id member and classified by the method that follows it.
    private String answer(final String msg) {
      final boolean batch = msg.charAt(0) == '[';
      final var sb = new StringBuilder(batch ? msg.length() : 64);
      if (batch) {
        sb.append('[');
      }
      int from = 0;
      boolean first = true;
      while (true) {
        final int idIdx = msg.indexOf("\"id\":", from);
        if (idIdx < 0) {
          break;
        }
        final int idStart = idIdx + 5;
        int idEnd = idStart;
        while (Character.isDigit(msg.charAt(idEnd))) {
          ++idEnd;
        }
        final int methodIdx = msg.indexOf("\"method\":\"", idEnd) + 10;
        final int methodEnd = msg.indexOf('"', methodIdx);
        from = methodEnd;
        final boolean unsubscribe = msg.startsWith("Unsubscribe", methodEnd - 11);
        if (!unsubscribe && withheldAnswers.get() > 0 && withheldAnswers.getAndDecrement() > 0) {
          continue;
        }
        if (!first) {
          sb.append(',');
        }
        first = false;
        sb.append("{\"jsonrpc\":\"2.0\",\"result\":");
        if (unsubscribe) {
          sb.append("true");
        } else {
          sb.append(nextSubId.getAndIncrement());
        }
        sb.append(",\"id\":").append(msg, idStart, idEnd).append('}');
      }
      if (first) {
        return null;
      }
      if (batch) {
        sb.append(']');
      }
      return sb.toString();
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(final ByteBuffer data, final boolean last) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(final ByteBuffer message) {
      final var future = new CompletableFuture<WebSocket>();
      thread.execute(() -> {
        future.complete(this);
        if (!closed) {
          listener.onPong(this, ByteBuffer.allocate(0));
        }
      });
      return future;
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(final ByteBuffer message) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(final int statusCode, final String reason) {
      closed = true;
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public void request(final long n) {
    }

    @Override
    public String getSubprotocol() {
      return "";
    }

    @Override
    public boolean isOutputClosed() {
      return closed;
    }

    @Override
    public boolean isInputClosed() {
      return closed;
    }

    @Override
    public void abort() {
      closed = true;
    }

    void shutdown() {
      thread.shutdownNow();
    }
  }

  static final class StubBuilder implements WebSocket.Builder {

    private final List<StubTransport> transports = new ArrayList<>();
    private final AtomicLong nextSubId = new AtomicLong(1);
    private final AtomicInteger withheldAnswers;

    StubBuilder(final int withheldAnswers) {
      this.withheldAnswers = new AtomicInteger(withheldAnswers);
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
      final var transport = new StubTransport(listener, nextSubId, withheldAnswers);
      synchronized (transports) {
        transports.add(transport);
      }
      final var connected = new CompletableFuture<WebSocket>();
      transport.open(connected);
      return connected;
    }

    StubTransport current() {
      synchronized (transports) {
        return transports.getLast();
      }
    }

    void shutdown() {
      synchronized (transports) {
        for (final var transport : transports) {
          transport.shutdown();
        }
      }
    }
  }
}
