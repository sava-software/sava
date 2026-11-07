package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/// A [SolanaRpcWebsocket#registerAll(java.util.function.Consumer)] scope. The calls inside it are
/// the calls a caller makes outside one, so every answer, frame and wire position here is judged
/// against what the same call does alone; what a scope moves is the wake of the check loop, to
/// one at the outermost exit. A wake has two halves, and each is observed on its own. The signal
/// reaches a cycle already parked, and is observed as the registry hardening tests observe it: a
/// test-owned stand-in parks on the production condition, and `hasWaiters`, asked under the
/// lifecycle lock, says whether a signal has reached it. A signal takes the waiter off the
/// condition queue before the signalling thread unlocks, so the answer is exact wherever a test
/// asks, inside a scope included. No wait with a deadline decides an assertion: the bounded
/// joins only clean up after a signal that never came. The memory reaches a cycle that has not
/// parked yet and lets it skip its await, so a cycle run with no time limit and an interrupt
/// already pending returns at once when a wake was remembered, and otherwise reaches the await,
/// which consumes the interrupt and throws at once.
@ExtendWith(QuietWsLogging.class)
final class RegistrationScopeTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  /// Large windows keep resends and pings away: every pass here is one the test drives.
  private static final Timings TIMINGS = new Timings(60_000L, 60_000L, 60_000L);
  private static final PublicKey FIRST = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
  private static final PublicKey SECOND = PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final Pattern REQUEST_ID = Pattern.compile("\"id\":(\\d+),");
  private static final Pattern METHOD = Pattern.compile("\"method\":\"(\\w+)\"");

  private static SolanaJsonRpcWebsocket websocket(final TestClock clock) {
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
        (_, _, _) -> {
        },
        null,
        null,
        null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  private static long framesContaining(final RecordingWebSocket socket, final String token) {
    return socket.sentText.stream().filter(frame -> frame.contains(token)).count();
  }

  /// The first match of `pattern` in each frame sent from index `from` on, in wire order.
  private static List<String> firstMatchFrom(final RecordingWebSocket socket, final int from, final Pattern pattern) {
    final var matches = new ArrayList<String>();
    for (final var frame : socket.sentText.subList(from, socket.sentText.size())) {
      final var matcher = pattern.matcher(frame);
      assertTrue(matcher.find(), () -> "every frame carries a " + pattern + ": " + frame);
      matches.add(matcher.group(1));
    }
    return matches;
  }

  /// A generic registration under `watchNotification`, answering as the generic subscribe does.
  private static boolean watch(final SolanaRpcWebsocket websocket, final String key) {
    return websocket.subscribe(
        "watchSubscribe", "watchUnsubscribe", "watchNotification",
        key, "", JsonIterator::readLong, null, _ -> {
        }
    );
  }

  @FunctionalInterface
  private interface InterruptibleTransition {

    void run() throws InterruptedException;
  }

  /// A removed unlock on a reentrant path does not block its own caller; it leaves that caller
  /// owning one extra hold, which only becomes a hang when another thread enters later. Capture
  /// that same-thread fact first, drain every leaked hold in `finally`, then fail synchronously.
  private static void assertCallingThreadReleasedLifecycleLock(final SolanaJsonRpcWebsocket ws,
                                                               final InterruptibleTransition transition)
      throws InterruptedException {
    assertEquals(0, ws.lock.getHoldCount(), "the transition must start without a borrowed hold");
    int retainedHoldCount;
    try {
      transition.run();
    } finally {
      retainedHoldCount = ws.lock.getHoldCount();
      while (ws.lock.isHeldByCurrentThread()) {
        ws.lock.unlock();
      }
    }
    assertEquals(0, retainedHoldCount,
        "the transition returned while its calling/completion thread still owned the lifecycle lock");
  }

  /// Whether a cycle is still parked on the production condition: false once any signal has
  /// reached it. Asked under the lifecycle lock, as `hasWaiters` requires, with one balanced
  /// hold, so asking from inside a scope leaves the scope's own lock accounting as it was.
  private static boolean cycleParked(final SolanaJsonRpcWebsocket ws) {
    ws.lock.lock();
    try {
      return ws.lock.hasWaiters(ws.newSubscription);
    } finally {
      ws.lock.unlock();
    }
  }

  /// Parks a test-owned stand-in for the maintenance loop on the production condition, then
  /// performs one transition which promises to wake that loop. Holding the lifecycle lock while
  /// checking `hasWaiters` makes both sides deterministic: acquiring it proves awaitNanos has
  /// enqueued and released, and a successful signal transfers the waiter off the condition queue
  /// before the triggering transition unlocks. A transferred waiter needs only the lifecycle
  /// lock, which the calling thread has released by then, so its thread is joined with no
  /// deadline; the bounded joins, and the interrupt between them, clean up after a signal that
  /// never came, so a broken transition fails instead of hanging. A copy of the registry
  /// hardening tests' helper; the transition may ask [#cycleParked] on the way.
  private static void assertSignalsAlreadyParkedCycle(final SolanaJsonRpcWebsocket ws,
                                                      final Runnable transition)
      throws InterruptedException {
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
      CompletableFuture.anyOf(beforeAwait, cycleCompleted).join();
      enteredAwait = beforeAwait.isDone();
      if (enteredAwait) {
        ws.lock.lock();
        try {
          assertTrue(ws.lock.hasWaiters(ws.newSubscription),
              "the checkpoint must own an already-parked maintenance cycle");
        } finally {
          while (ws.lock.isHeldByCurrentThread()) {
            ws.lock.unlock();
          }
        }

        // This assertion precedes the cross-thread queue inspection below. A leaked reentrant
        // hold is therefore a finite same-thread failure, and cleanup lets the waiter finish.
        assertCallingThreadReleasedLifecycleLock(ws, transition::run);

        ws.lock.lock();
        try {
          signalTransferred = !ws.lock.hasWaiters(ws.newSubscription);
        } finally {
          while (ws.lock.isHeldByCurrentThread()) {
            ws.lock.unlock();
          }
        }
      }
    } finally {
      if (signalTransferred) {
        // Off the condition queue, the waiter needs only the lock this thread has released, so
        // the join ends however late the cycle thread is scheduled.
        cycle.join();
      } else {
        // Independent cleanup for a missing transition signal, bounded so that a broken
        // transition fails the assertions below instead of hanging here.
        ws.close();
        cycle.join(1_000L);
        if (cycle.isAlive()) {
          cycle.interrupt();
          cycle.join(1_000L);
        }
      }
    }

    assertTrue(enteredAwait);
    assertTrue(signalTransferred,
        "the lifecycle transition must transfer an already-parked maintenance cycle");
    assertFalse(cycle.isAlive());
    assertNull(cycleFailure.get());
  }

  /// Runs one cycle with no time limit while an interrupt is pending, and answers whether it ran
  /// its pass without parking, which on an open websocket means a wake was remembered. Without
  /// one the cycle reaches the await, which consumes the interrupt and throws at once, so neither
  /// answer blocks. The `finally` clear is load-bearing: a cycle that skips the await leaves the
  /// interrupt pending, and it would otherwise reach whatever this thread runs next.
  private static boolean cycleRanWithoutParking(final SolanaJsonRpcWebsocket ws) {
    Thread.currentThread().interrupt();
    try {
      ws.checkCycle(Long.MAX_VALUE);
      return true;
    } catch (final InterruptedException _) {
      return false;
    } finally {
      Thread.interrupted();
    }
  }

  /// The lock-release contract after a path: no hold left on the calling thread, and the lock
  /// free. Leaked holds are drained before asserting, so a failure here cannot strand the
  /// try-with-resources close.
  private static void assertReleased(final SolanaJsonRpcWebsocket ws, final String path) {
    final int retainedHolds = ws.lock.getHoldCount();
    while (ws.lock.isHeldByCurrentThread()) {
      ws.lock.unlock();
    }
    assertAll(
        () -> assertEquals(0, retainedHolds,
            path + " must return with no lifecycle-lock hold on the calling thread"),
        () -> assertFalse(ws.lock.isLocked(),
            path + " must return with the lifecycle lock released")
    );
  }

  /// Every registration path that wakes the loop on its own, the confirmed cancellation included,
  /// leaves that wake to the scope: after each call the stand-in is still parked, and only the
  /// scope's return transfers it. Outside a scope each of these calls wakes it at once, which the
  /// registry hardening tests pin path by path.
  @Test
  void aScopeDefersItsWakeToItsExit() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.accountSubscribe(FIRST, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}""");

      final var answers = new ArrayList<Boolean>();
      final var parkedAfterEachCall = new ArrayList<Boolean>();
      assertSignalsAlreadyParkedCycle(ws, () -> ws.registerAll(scope -> {
        answers.add(scope.accountUnsubscribe(FIRST));
        parkedAfterEachCall.add(cycleParked(ws));
        answers.add(scope.signatureSubscribe("deferred-string-registration", _ -> {
        }));
        parkedAfterEachCall.add(cycleParked(ws));
        answers.add(scope.accountSubscribe(SECOND, _ -> {
        }));
        parkedAfterEachCall.add(cycleParked(ws));
        answers.add(scope.slotSubscribe(_ -> {
        }));
        parkedAfterEachCall.add(cycleParked(ws));
        answers.add(scope.rootSubscribe(_ -> {
        }));
        parkedAfterEachCall.add(cycleParked(ws));
        answers.add(watch(scope, "deferred"));
        parkedAfterEachCall.add(cycleParked(ws));
      }));

      assertEquals(List.of(true, true, true, true, true, true), answers);
      assertEquals(List.of(true, true, true, true, true, true), parkedAfterEachCall,
          "no registration path inside the scope may wake the loop before the scope returns");
    }
  }

  /// The exit owes the wake the calls inside deferred: a cycle already parked when the scope began
  /// is transferred by its return, with the lifecycle lock left as the scope found it.
  @Test
  void aScopeExitWakesAnAlreadyParkedCycle() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var answered = new AtomicBoolean();
      assertSignalsAlreadyParkedCycle(ws, () -> ws.registerAll(scope -> answered.set(scope.accountSubscribe(FIRST, _ -> {
      }))));
      assertTrue(answered.get());
    }
  }

  /// The exit remembers its wake as well as signalling it. A loop that is not parked when the
  /// scope returns, mid-pass or between passes, then runs its next pass at once for what the
  /// scope queued instead of parking a whole check delay. The cycle before the scope shows the
  /// oracle discriminating in this fixture: with nothing remembered it reaches the await, and the
  /// pending interrupt throws there.
  @Test
  void aScopeExitIsRememberedByACycleNotYetParked() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.checkCycle(0L);
      assertFalse(cycleRanWithoutParking(ws), "nothing is remembered before the scope");

      ws.registerAll(scope -> assertTrue(scope.accountSubscribe(FIRST, _ -> {
      })));
      assertEquals(0, framesContaining(socket, "\"method\":\"accountSubscribe\""),
          "a registration queues its request; only a pass sends it");
      assertTrue(cycleRanWithoutParking(ws),
          "the scope's exit must remember its wake for a cycle that has not parked yet");
      assertEquals(1, framesContaining(socket, "\"method\":\"accountSubscribe\""),
          "the remembered wake runs the pass that sends the scope's registration");
    }
  }

  /// The baseline a scope moves: outside one, a registration remembers its own wake as well as
  /// signalling it, so a loop that is not parked when the call returns runs its next pass at once
  /// for it. The registry hardening tests pin the signal half path by path; every registration
  /// path remembers through the one helper a scope defers, `wakeForQueuedWork`, so one path pins
  /// the memory half.
  @Test
  void aRegistrationOutsideAScopeIsRememberedByACycleNotYetParked() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.checkCycle(0L);
      assertFalse(cycleRanWithoutParking(ws), "nothing is remembered before the registration");

      assertTrue(ws.accountSubscribe(FIRST, _ -> {
      }));
      assertEquals(0, framesContaining(socket, "\"method\":\"accountSubscribe\""),
          "a registration queues its request; only a pass sends it");
      assertTrue(cycleRanWithoutParking(ws),
          "a registration must remember its wake for a cycle that has not parked yet");
      assertEquals(1, framesContaining(socket, "\"method\":\"accountSubscribe\""),
          "the remembered wake runs the pass that sends the registration");
    }
  }

  /// The mark a scope leaves on its thread ends with the scope. Inside, a registration's wake is
  /// deferred; once the scope has returned and its own wake has run its pass, the same thread's
  /// next registration wakes the loop at once, like any call outside a scope. A mark left behind
  /// would defer every later registration of that thread to an exit that never comes.
  @Test
  void aThreadIsNotLeftDeferringAfterItsScope() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.checkCycle(0L);
      final var rememberedInside = new AtomicBoolean(true);
      ws.registerAll(scope -> {
        assertTrue(scope.accountSubscribe(FIRST, _ -> {
        }));
        rememberedInside.set(cycleRanWithoutParking(ws));
      });
      assertFalse(rememberedInside.get(), "inside the scope the registration's wake is deferred");
      assertTrue(cycleRanWithoutParking(ws), "the scope's exit remembered its wake");
      assertFalse(cycleRanWithoutParking(ws), "and that pass consumed it");

      assertTrue(ws.accountSubscribe(SECOND, _ -> {
      }));
      assertTrue(cycleRanWithoutParking(ws),
          "after the scope the same thread's registration remembers its own wake again");
      assertEquals(2, framesContaining(socket, "\"method\":\"accountSubscribe\""),
          "both registrations were sent, each by the pass its own wake ran");
    }
  }

  /// A scope opened inside another runs inside it: its return wakes nothing, the outer scope
  /// keeps deferring after it, and the one wake comes at the outermost return.
  @Test
  void aNestedScopeWakesOnlyAtTheOutermostExit() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var innerAnswered = new AtomicBoolean();
      final var outerAnswered = new AtomicBoolean();
      final var parkedAfterInnerExit = new AtomicBoolean();
      assertSignalsAlreadyParkedCycle(ws, () -> ws.registerAll(outer -> {
        outer.registerAll(inner -> innerAnswered.set(inner.accountSubscribe(FIRST, _ -> {
        })));
        outerAnswered.set(outer.accountSubscribe(SECOND, _ -> {
        }));
        parkedAfterInnerExit.set(cycleParked(ws));
      }));

      assertTrue(innerAnswered.get(), "the nested scope runs its registrations");
      assertTrue(outerAnswered.get());
      assertTrue(parkedAfterInnerExit.get(),
          "the inner return and the outer scope's later call leave the wake to the outermost return");
    }
  }

  /// Deferral belongs to the thread running the scope. A registration another thread makes while
  /// the scope is open wakes the cycle at once, before the scope returns; an instance-wide
  /// deferral would hold it behind a scope that thread knows nothing about. The scope waits for
  /// that registration to return with no deadline: the only hold it could wait on until the scope
  /// returned is one the scope's thread keeps through the callback, and that thread's own hold
  /// count, exact on the thread that asks, rules one out before the other thread starts. The
  /// other thread is joined in `finally`, so it never outlives the test.
  @Test
  void anotherThreadsRegistrationIsNotDeferred() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var scopeAnswered = new AtomicBoolean();
      final var otherAnswered = new AtomicBoolean();
      final var holdsInsideScope = new AtomicInteger(-1);
      final var otherReturned = new AtomicBoolean();
      final var parkedWhileOpen = new AtomicBoolean(true);
      final var registrationReturned = new CompletableFuture<Void>();
      final var other = new Thread(() -> {
        try {
          otherAnswered.set(ws.accountSubscribe(SECOND, _ -> {
          }));
        } finally {
          registrationReturned.complete(null);
        }
      }, "registration-beside-a-scope");
      try {
        assertSignalsAlreadyParkedCycle(ws, () -> ws.registerAll(scope -> {
          scopeAnswered.set(scope.accountSubscribe(FIRST, _ -> {
          }));
          holdsInsideScope.set(ws.lock.getHoldCount());
          if (holdsInsideScope.get() != 0) {
            // The other registration would wait on this hold until the scope returned.
            return;
          }
          other.start();
          registrationReturned.join();
          otherReturned.set(registrationReturned.isDone());
          parkedWhileOpen.set(cycleParked(ws));
        }));
      } finally {
        other.join();
      }

      assertEquals(0, holdsInsideScope.get(),
          "registerAll takes no lock around the callback, so another thread's registration cannot wait on the scope");
      assertTrue(otherReturned.get(), "the other thread's registration returns while the scope is open");
      assertTrue(scopeAnswered.get());
      assertTrue(otherAnswered.get());
      assertFalse(parkedWhileOpen.get(),
          "another thread's registration must wake the cycle while this thread's scope is still open");
    }
  }

  /// A thread the scope itself creates is another thread like any other. Created and started
  /// inside the callback, it inherits nothing from the scope, and its registration wakes the cycle
  /// while the scope is still open. A mark that children inherit would defer it to the scope's
  /// return, and a scope that waits for its child's `onSub` would then wait for the next periodic
  /// pass instead of the pass that registration's own wake runs.
  @Test
  void aThreadCreatedInsideAScopeIsNotDeferred() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var childAnswered = new AtomicBoolean();
      final var holdsInsideScope = new AtomicInteger(-1);
      final var parkedWhileOpen = new AtomicBoolean(true);
      final var children = new ArrayList<Thread>();
      try {
        assertSignalsAlreadyParkedCycle(ws, () -> ws.registerAll(scope -> {
          holdsInsideScope.set(ws.lock.getHoldCount());
          if (holdsInsideScope.get() != 0) {
            // The child's registration would wait on this hold until the scope returned.
            return;
          }
          final var registrationReturned = new CompletableFuture<Void>();
          final var child = new Thread(() -> {
            try {
              childAnswered.set(scope.accountSubscribe(SECOND, _ -> {
              }));
            } finally {
              registrationReturned.complete(null);
            }
          }, "registration-created-inside-a-scope");
          children.add(child);
          child.start();
          registrationReturned.join();
          parkedWhileOpen.set(cycleParked(ws));
        }));
      } finally {
        for (final var child : children) {
          child.join();
        }
      }

      assertEquals(0, holdsInsideScope.get(), "registerAll takes no lock around the callback");
      assertTrue(childAnswered.get());
      assertFalse(parkedWhileOpen.get(),
          "a thread created inside the scope must wake the cycle while the scope is still open");
    }
  }

  /// The mark is this websocket's. A registration made on another websocket from inside the
  /// scope wakes that websocket's cycle at once; deferred, it would never be delivered, since this
  /// scope's return signals only its own websocket's condition.
  @Test
  void aScopeDefersOnlyItsOwnWebsocketsWakes() throws InterruptedException {
    try (final var scoped = websocket(new TestClock());
         final var other = websocket(new TestClock())) {
      final var otherAnswered = new AtomicBoolean();
      final var otherParkedInside = new AtomicBoolean(true);
      assertSignalsAlreadyParkedCycle(other, () -> scoped.registerAll(_ -> {
        otherAnswered.set(other.accountSubscribe(FIRST, _ -> {
        }));
        otherParkedInside.set(cycleParked(other));
      }));

      assertTrue(otherAnswered.get());
      assertFalse(otherParkedInside.get(),
          "a scope of one websocket must not defer another websocket's wake");
    }
  }

  /// The wake sits in `finally`, and so does the end of the scope's mark on its thread. A call
  /// that throws inside the scope propagates out of it as it would alone, the cycle is woken for
  /// what was queued before the throw, and that registration stays registered and goes out on the
  /// next pass. The thread is left unmarked: its next registration wakes the loop at once.
  @Test
  void aThrowingScopeStillWakesForWhatItQueued() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      final var answered = new AtomicBoolean();
      final var thrown = new AtomicReference<IllegalArgumentException>();
      assertSignalsAlreadyParkedCycle(ws, () -> thrown.set(assertThrows(IllegalArgumentException.class,
          () -> ws.registerAll(scope -> {
            answered.set(scope.accountSubscribe(FIRST, _ -> {
            }));
            scope.signatureSubscribe("bad\"sig", _ -> {
            });
          }))));

      assertTrue(answered.get());
      assertTrue(thrown.get().getMessage().contains("b58TxSig"), thrown.get()::getMessage);
      assertEquals(0, framesContaining(socket, "\"method\":\"accountSubscribe\""),
          "a registration queues its request; only a pass sends it");
      ws.checkCycle(0L);
      assertEquals(1, framesContaining(socket, "\"method\":\"accountSubscribe\""),
          "the registration made before the throw is sent by the next pass");

      assertFalse(cycleRanWithoutParking(ws), "nothing is remembered once that pass has run");
      assertTrue(ws.accountSubscribe(SECOND, _ -> {
      }));
      assertTrue(cycleRanWithoutParking(ws),
          "the throw left no mark behind: this thread's next registration wakes the loop at once");
    }
  }

  /// Each call answers for itself, exactly as alone: a new registration `true`, the same key or an
  /// occupied singleton `false` with no request id minted, an unsubscribe whether it removed
  /// something, an invalid generic method name a throw, and every subscribe `false` once closed.
  /// The ids on the wire are consecutive, which is what "no request id minted" means.
  @Test
  void eachCallInsideAScopeAnswersAsItsOwnCall() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      final var answers = new ArrayList<Boolean>();
      ws.registerAll(scope -> {
        answers.add(scope.accountSubscribe(FIRST, _ -> {
        }));
        answers.add(scope.accountSubscribe(FIRST, _ -> {
        }));
        answers.add(watch(scope, "a"));
        answers.add(watch(scope, "a"));
        answers.add(scope.slotSubscribe(_ -> {
        }));
        answers.add(scope.slotSubscribe(_ -> {
        }));
        answers.add(scope.accountUnsubscribe(SECOND));
        answers.add(scope.unsubscribe("watchNotification", "b"));
        assertThrows(IllegalArgumentException.class, () -> scope.subscribe(
            "watchSubscribe", "watchUnsubscribe", "accountNotification",
            "built-in", "", JsonIterator::readLong, null, _ -> {
            }));
      });
      assertEquals(List.of(true, false, true, false, true, false, false, false), answers);

      ws.checkCycle(0L);
      assertEquals(List.of("2", "3", "4"), firstMatchFrom(socket, 0, REQUEST_ID),
          "a refused call mints no request id and queues nothing");
      assertEquals(List.of("accountSubscribe", "watchSubscribe", "slotSubscribe"), firstMatchFrom(socket, 0, METHOD));

      final var removed = new AtomicBoolean();
      ws.registerAll(scope -> removed.set(scope.unsubscribe("watchNotification", "a")));
      assertTrue(removed.get(), "an unsubscribe inside a scope answers that it removed the registration");

      ws.close();
      final var afterClose = new ArrayList<Boolean>();
      ws.registerAll(scope -> {
        afterClose.add(scope.accountSubscribe(SECOND, _ -> {
        }));
        afterClose.add(watch(scope, "c"));
        afterClose.add(scope.rootSubscribe(_ -> {
        }));
      });
      assertEquals(List.of(false, false, false), afterClose, "closed: subscriptions return false");
      assertReleased(ws, "a scope on a closed websocket");
    }
  }

  /// With no connection a scope's registrations are durable intent, as single calls are: the
  /// adoption that follows sends every one of them.
  @Test
  void aScopeWithNoConnectionLeavesDurableRegistrations() {
    try (final var ws = websocket(new TestClock())) {
      final var answers = new ArrayList<Boolean>();
      ws.registerAll(scope -> {
        answers.add(scope.accountSubscribe(FIRST, _ -> {
        }));
        answers.add(scope.slotSubscribe(_ -> {
        }));
        answers.add(watch(scope, "a"));
      });
      assertEquals(List.of(true, true, true), answers, "intent is registrable while no connection exists");

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of("accountSubscribe", "slotSubscribe", "watchSubscribe"), firstMatchFrom(socket, 0, METHOD),
          "adoption sends every registration the scope made");
      assertEquals(List.of("2", "3", "4"), firstMatchFrom(socket, 0, REQUEST_ID));
    }
  }

  /// One pass after the scope sends what it queued in the order every pass uses, cancellations
  /// first, whatever order the calls came in: here a subscribe of another key precedes the
  /// cancellation inside the scope, and a resubscribe of the cancelled key follows it. The
  /// cancellation leading the resubscribe on the wire is what keeps an id-reusing server from
  /// killing the successor (the reconnect tests' wire-order pin).
  @Test
  void cancellationsInAScopePrecedeItsSubscribesOnTheWire() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.accountSubscribe(FIRST, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}""");
      final int before = socket.sentText.size();

      final var answers = new ArrayList<Boolean>();
      ws.registerAll(scope -> {
        answers.add(scope.accountSubscribe(SECOND, _ -> {
        }));
        answers.add(scope.accountUnsubscribe(FIRST));
        answers.add(scope.accountSubscribe(FIRST, _ -> {
        }));
      });
      assertEquals(List.of(true, true, true), answers);
      assertEquals(before, socket.sentText.size(), "the scope queues; the pass sends");

      ws.checkCycle(0L);
      assertEquals(List.of("accountUnsubscribe", "accountSubscribe", "accountSubscribe"),
          firstMatchFrom(socket, before, METHOD), socket.sentText::toString);
      assertTrue(socket.sentText.get(before).contains("\"params\":[700]"), socket.sentText::toString);
      assertEquals(List.of("5", "3", "4"), firstMatchFrom(socket, before, REQUEST_ID),
          "the cancellation, minted at the flush, leads both subscribes registered before it");
    }
  }

  /// `onSub` runs on the thread that sent the request, inside the pass and under its hold when the
  /// send completes at once, as the fake's do. A scope opened there re-enters the lock only for
  /// its exit signal and returns the hold count it found, so the pass, and the adoption around it,
  /// finish with the lock released; the scope's registration goes out on the next pass, not in
  /// the adoption pass whose `onSub` made it.
  @Test
  void aScopeOpenedFromOnSubCompletes() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var innerAnswered = new AtomicBoolean();
      final var holdsBeforeScope = new AtomicInteger(-1);
      final var holdsAfterScope = new AtomicInteger(-1);
      assertTrue(ws.accountSubscribe(FIRST, _ -> {
        holdsBeforeScope.set(ws.lock.getHoldCount());
        ws.registerAll(scope -> innerAnswered.set(scope.accountSubscribe(SECOND, _ -> {
        })));
        holdsAfterScope.set(ws.lock.getHoldCount());
      }, _ -> {
      }));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertTrue(holdsBeforeScope.get() > 0, "onSub ran under the adoption pass's hold");
      assertEquals(holdsBeforeScope.get(), holdsAfterScope.get(), "the scope returns the hold count it found");
      assertTrue(innerAnswered.get());
      assertReleased(ws, "an adoption whose onSub opened a scope");
      assertEquals(0, framesContaining(socket, SECOND.toBase58()),
          "the adoption pass does not send the registration its onSub's scope made");

      ws.checkCycle(0L);
      assertEquals(1, framesContaining(socket, SECOND.toBase58()), "the scope's registration is sent");
    }
  }

  /// The documented refusal of a null scope, with the lock left as it was found.
  @Test
  void aNullScopeThrowsNullPointerException() {
    try (final var ws = websocket(new TestClock())) {
      assertThrows(NullPointerException.class, () -> ws.registerAll(null));
      assertReleased(ws, "registerAll(null)");
    }
  }
}
