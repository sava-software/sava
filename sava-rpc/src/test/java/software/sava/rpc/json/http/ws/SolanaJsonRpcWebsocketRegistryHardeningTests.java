package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Defensive tests for this library's durable-subscription and cancellation registries. The
/// peer frames are deliberately adversarial: repeated ids, reordered outcomes, and conflicting
/// channel obligations must not make one registration release or cancel another.
@ExtendWith(QuietWsLogging.class)
final class SolanaJsonRpcWebsocketRegistryHardeningTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000L, 60_000L, 60_000L);
  private static final PublicKey PROGRAM =
      PublicKey.fromBase58Encoded("GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc");
  private static final PublicKey ACCOUNT =
      PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");

  /// `onText` resolves its connection before asking the supplied CharSequence for its contents.
  /// A wrapping WebSocket is allowed to supply any CharSequence, so this checkpoint orders a
  /// takeover after connection capture without timing, sleeps, or production hooks.
  private static final class ConnectionCapturedMessage implements CharSequence {

    private final String value;
    private final AtomicBoolean checkpointed = new AtomicBoolean();
    final CompletableFuture<Void> connectionCaptured = new CompletableFuture<>();
    final CompletableFuture<Void> resume = new CompletableFuture<>();

    private ConnectionCapturedMessage(final String value) {
      this.value = value;
    }

    @Override
    public int length() {
      if (checkpointed.compareAndSet(false, true)) {
        connectionCaptured.complete(null);
        resume.join();
      }
      return value.length();
    }

    @Override
    public char charAt(final int index) {
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(final int start, final int end) {
      return value.subSequence(start, end);
    }
  }

  private static SolanaJsonRpcWebsocket websocket(final TestClock clock) {
    return websocket(clock, null);
  }

  private static SolanaJsonRpcWebsocket websocket(
      final TestClock clock,
      final BiConsumer<SolanaRpcWebsocket, Throwable> onError) {
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
        onError,
        null,
        null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws,
                           final RecordingWebSocket socket,
                           final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  private static long framesContaining(final RecordingWebSocket socket, final String token) {
    return socket.sentText.stream().filter(frame -> frame.contains(token)).count();
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

  /// Parks a test-owned stand-in for the maintenance loop on the production condition, then
  /// performs one transition which promises to wake that loop. Holding the lifecycle lock while
  /// checking `hasWaiters` makes both sides deterministic: acquiring it proves awaitNanos has
  /// enqueued and released, and a successful signal transfers the waiter off the condition queue
  /// before the triggering transition unlocks. Bounded joins are cleanup only.
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
      if (!signalTransferred) {
        // Independent cleanup for a missing transition signal.
        ws.close();
      }
      cycle.join(1_000L);
      if (cycle.isAlive()) {
        cycle.interrupt();
        cycle.join(1_000L);
      }
    }

    assertTrue(enteredAwait);
    assertTrue(signalTransferred,
        "the lifecycle transition must transfer an already-parked maintenance cycle");
    assertFalse(cycle.isAlive());
    assertNull(cycleFailure.get());
  }

  private static void prepareCancellationQueuedBehindGate(final SolanaJsonRpcWebsocket ws,
                                                           final RecordingWebSocket socket)
      throws InterruptedException {
    assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
    }));
    ws.onOpen(socket);
    feed(ws, socket, """
        {"jsonrpc":"2.0","result":700,"id":2}""");

    assertTrue(ws.accountUnsubscribe(ACCOUNT));
    assertCallingThreadReleasedLifecycleLock(ws,
        () -> ws.checkCycle(0L)); // cancellation 3 owns id 700's wire gate
    assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
    }));
    ws.checkCycle(0L); // successor request 4 follows cancellation 3
    feed(ws, socket, """
        {"jsonrpc":"2.0","result":700,"id":4}""");
    assertTrue(ws.accountUnsubscribe(ACCOUNT));
    ws.checkCycle(0L); // consume its remembered signal; cancellation 2 remains gate-blocked
  }

  private static void preparePredatingGrantAndCompensation(final SolanaJsonRpcWebsocket ws,
                                                            final RecordingWebSocket socket)
      throws InterruptedException {
    assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
    }));
    ws.onOpen(socket); // predecessor request 2
    assertTrue(ws.accountUnsubscribe(ACCOUNT));
    assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
    }));
    ws.checkCycle(0L); // successor request 3 precedes the compensation
    assertCallingThreadReleasedLifecycleLock(ws, () -> feed(ws, socket, """
        {"jsonrpc":"2.0","result":800,"id":2}""")); // compensation request 4
  }

  /// A typed subscription handle is durable across connections, but its server id is not. The
  /// public handle itself is the state oracle: immediately after successor adoption, before any
  /// successor confirmation, it must no longer claim the predecessor transport's id.
  @Test
  void adoptionClearsTypedServerIdBeforeTheSuccessorConfirms() {
    final var accountSub = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, ACCOUNT, accountSub::set, _ -> {
      }));
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      feed(ws, first, """
          {"jsonrpc":"2.0","result":700,"id":2}""");
      assertEquals(BigInteger.valueOf(700), accountSub.get().subId());

      ws.onOpen(new RecordingWebSocket());

      assertNull(accountSub.get().subId(),
          "a server subscription id belongs only to the transport which granted it");
    }
  }

  /// A terminal signature notification captured from the predecessor may finish parsing after a
  /// successor connection has re-armed the same durable handle. Its old transport mapping may
  /// retire, but it must not free the successor's registry slot. A duplicate public subscribe is
  /// the occupancy oracle and remains false.
  @Test
  void aCapturedPredecessorSignatureCannotReleaseTheSuccessorsRegistrySlot()
      throws Exception {
    final var callbackFailure = new AtomicReference<Throwable>();
    final var callbackHoldCount = new AtomicInteger(-1);
    final var callbackCompleted = new CompletableFuture<Void>();
    try (final var ws = websocket(new TestClock())) {
      final var signature = "predecessor-signature";
      assertTrue(ws.signatureSubscribe(signature, _ -> {
      }));
      final var first = new RecordingWebSocket();
      ws.onOpen(first);
      feed(ws, first, """
          {"jsonrpc":"2.0","result":700,"id":2}""");

      final var terminal = new ConnectionCapturedMessage("""
          {"jsonrpc":"2.0","method":"signatureNotification","params":{"result":{"context":{"slot":3},"value":{"err":null}},"subscription":700}}""");
      final var callback = new Thread(() -> {
        try {
          ws.onText(first, terminal, true);
        } catch (final Throwable ex) {
          callbackFailure.set(ex);
        } finally {
          callbackHoldCount.set(ws.lock.getHoldCount());
          while (ws.lock.isHeldByCurrentThread()) {
            ws.lock.unlock();
          }
          callbackCompleted.complete(null);
        }
      }, "captured-predecessor-signature-test");
      callback.start();

      try {
        CompletableFuture.anyOf(terminal.connectionCaptured, callbackCompleted)
            .get(1L, TimeUnit.SECONDS);
        assertTrue(terminal.connectionCaptured.isDone(),
            "the callback must pause after resolving the predecessor connection");
        ws.onOpen(new RecordingWebSocket());
      } finally {
        terminal.resume.complete(null);
        callback.join(1_000L);
        if (callback.isAlive()) {
          callback.interrupt();
          callback.join(1_000L);
        }
      }

      assertFalse(callback.isAlive());
      assertNull(callbackFailure.get());
      assertEquals(0, callbackHoldCount.get(),
          "the predecessor callback must release its lifecycle hold before manager re-entry");
      assertFalse(ws.signatureSubscribe(signature, _ -> {
      }), "the stale terminal frame must not free the successor's durable registration");
    }
  }

  /// A signature key is occupied per commitment, not globally. Adding a sibling commitment must
  /// succeed, while retrying that exact pair remains a duplicate and consumes no correlation id.
  /// The public results plus consecutive wire ids distinguish all three registry outcomes.
  @Test
  void signatureRegistryKeepsCommitmentOccupancyAndIdContinuity() {
    final var signature = "multi-commitment-signature";
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.signatureSubscribe(Commitment.CONFIRMED, signature, _ -> {
      }));
      assertTrue(ws.signatureSubscribe(Commitment.FINALIZED, signature, _ -> {
      }), "the same signature may occupy a sibling commitment");
      assertFalse(ws.signatureSubscribe(Commitment.FINALIZED, signature, _ -> {
      }), "the exact signature and commitment pair remains occupied");
      assertTrue(ws.signatureSubscribe("following-signature", _ -> {
      }));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      assertEquals(3, socket.sentText.size(), socket.sentText::toString);
      assertTrue(socket.sentText.get(0).contains("\"id\":2,"), socket.sentText.get(0));
      assertTrue(socket.sentText.get(1).contains("\"id\":3,"), socket.sentText.get(1));
      assertTrue(socket.sentText.get(2).contains("\"id\":4,"),
          () -> "the rejected duplicate consumed a correlation id: " + socket.sentText);
    }
  }

  /// Before any connection exists, no per-connection tombstone or wire-order entry can be
  /// retained. These explicit state seams must report that empty state rather than dereference a
  /// connection which has never existed.
  @Test
  void disconnectedRegistryStateHasNoPerConnectionEntries() {
    try (final var ws = websocket(new TestClock())) {
      assertEquals(0, ws.retainedCancellationTombstones());
      assertEquals(0, ws.retainedOrdinalEntries());
    }
  }

  /// Distinct keys under one generic notification method share one durable namespace. When they
  /// are registered before a connection exists, adoption must recover both from that registry;
  /// a detached per-call map would silently lose the later key.
  @Test
  void genericSiblingKeysBothReplayFromTheirDurableNamespaceOnFirstAdoption() {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.subscribe("fooSubscribe", "fooUnsubscribe", "fooNotification",
          "first", "1", JsonIterator::readInt, null, _ -> {
          }));
      assertTrue(ws.subscribe("fooSubscribe", "fooUnsubscribe", "fooNotification",
          "second", "2", JsonIterator::readInt, null, _ -> {
          }));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      assertEquals(2, framesContaining(socket, "\"method\":\"fooSubscribe\""));
      assertTrue(socket.sentText.stream().anyMatch(frame -> frame.contains("\"id\":2,")));
      assertTrue(socket.sentText.stream().anyMatch(frame -> frame.contains("\"id\":3,")));
    }
  }

  /// Removing the final generic key must prune its notification-method namespace. The public
  /// result alone cannot distinguish an empty retained map, so use the direct retention seam.
  @Test
  void genericUnsubscribePrunesItsLastDurableNamespace() {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.subscribe("fooSubscribe", "fooUnsubscribe", "fooNotification",
          "only", "1", JsonIterator::readInt, null, _ -> {
          }));
      assertEquals(1, ws.retainedRegistrations());

      assertTrue(ws.unsubscribe("fooNotification", "only"));

      assertEquals(0, ws.retainedRegistrations());
    }
  }

  /// A convenience overload is still a truthful admission result: duplicate registrations and
  /// registrations attempted after close must propagate the terminal `false` returned by the
  /// owning registry rather than report an operation which did not happen.
  @Test
  void convenienceSubscriptionResultsPreserveDuplicateAndClosedRejections() {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.subscribeToTokenAccounts(ACCOUNT, _ -> {
      }));
      assertFalse(ws.subscribeToTokenAccounts(ACCOUNT, _ -> {
      }), "both token-account convenience layers must preserve duplicate rejection");

      ws.close();
      assertFalse(ws.programSubscribe(PROGRAM, _ -> {
      }), "programSubscribe must preserve the closed registry's rejection");
      assertFalse(ws.signatureSubscribe("closed-signature-subscription", _ -> {
      }), "signatureSubscribe must preserve the closed registry's rejection");
    }
  }

  /// The legacy program-subscription protocol treats null and an empty filter list identically:
  /// both mean that the optional `filters` member is absent. Compare independently built wire
  /// frames so an empty list cannot silently become the distinct JSON value `"filters":[]`.
  @Test
  void legacyProgramSubscribeNullAndEmptyFiltersHaveTheSameWireEncoding() {
    final String nullFiltersFrame;
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.programSubscribe(Commitment.CONFIRMED, PROGRAM, null, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(1, socket.sentText.size(), socket.sentText::toString);
      nullFiltersFrame = socket.sentText.getFirst();
    }

    final String emptyFiltersFrame;
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.programSubscribe(Commitment.CONFIRMED, PROGRAM, List.of(), _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(1, socket.sentText.size(), socket.sentText::toString);
      emptyFiltersFrame = socket.sentText.getFirst();
    }

    assertEquals(nullFiltersFrame, emptyFiltersFrame);
    assertFalse(emptyFiltersFrame.contains("\"filters\""), emptyFiltersFrame);
  }

  /// New durable registrations are immediately actionable maintenance work. Each distinct
  /// registration implementation must wake a cycle which has already parked on the shared
  /// condition; setting the remembered-work flag alone cannot wake that waiter.
  @Test
  void everyRegistrationPathSignalsAnAlreadyParkedMaintenanceCycle()
      throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      assertSignalsAlreadyParkedCycle(ws, () ->
          assertTrue(ws.signatureSubscribe("parked-string-registration", _ -> {
          })));
    }
    try (final var ws = websocket(new TestClock())) {
      assertSignalsAlreadyParkedCycle(ws, () -> assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      })));
    }
    try (final var ws = websocket(new TestClock())) {
      assertSignalsAlreadyParkedCycle(ws, () -> assertTrue(ws.slotSubscribe(_ -> {
      })));
    }
    try (final var ws = websocket(new TestClock())) {
      assertSignalsAlreadyParkedCycle(ws, () -> assertTrue(ws.rootSubscribe(_ -> {
      })));
    }
    try (final var ws = websocket(new TestClock())) {
      assertSignalsAlreadyParkedCycle(ws, () -> assertTrue(ws.subscribe(
          "fooSubscribe", "fooUnsubscribe", "fooNotification", "parked", "1",
          JsonIterator::readInt, null, _ -> {
          })));
    }
  }

  /// Removing a confirmed registration queues its cancellation. A maintenance cycle which is
  /// already parked must be woken now; remembering work only helps the next cycle to arrive.
  @Test
  void confirmedCancellationSignalsAnAlreadyParkedMaintenanceCycle()
      throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}""");

      assertSignalsAlreadyParkedCycle(ws, () -> assertTrue(ws.accountUnsubscribe(ACCOUNT)));
    }
  }

  /// A notification for an unknown id enters the direct cancellation sender from an otherwise
  /// unlocked callback thread. Holding its text future open isolates that entry's own finally:
  /// it must return with no lifecycle hold before any completion callback exists.
  @Test
  void directCancellationSenderReleasesItsCallingThreadLifecycleHold()
      throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      socket.deferTexts = true;

      assertCallingThreadReleasedLifecycleLock(ws, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":1,"root":1,"slot":2},"subscription":77}}"""));

      assertEquals(1, framesContaining(socket, "\"method\":\"slotUnsubscribe\""));
    }
  }

  /// With the ordinary immediately-completed transport, the cancellation completion callback
  /// runs on this calling thread before `onText` returns. Its own lifecycle-lock hold must be
  /// released there; checking the same-thread hold directly makes a missing callback unlock a
  /// finite assertion failure rather than a later cross-thread deadlock.
  @Test
  void successfulDirectCancellationCompletionReleasesItsCallingThreadLifecycleHold()
      throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      assertCallingThreadReleasedLifecycleLock(ws, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":1,"root":1,"slot":2},"subscription":77}}"""));

      assertEquals(1, framesContaining(socket, "\"method\":\"slotUnsubscribe\""));
    }
  }

  /// A failed cancellation send re-queues the wire obligation. That transition occurs after the
  /// ordinary enqueue signal was consumed, so its own condition wake is what prevents the
  /// already-parked maintenance loop from waiting out the full check delay.
  @Test
  void failedCancellationSendSignalsAnAlreadyParkedMaintenanceCycle()
      throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}""");
      socket.deferTexts = true;
      assertTrue(ws.accountUnsubscribe(ACCOUNT));
      ws.checkCycle(0L);
      assertEquals(1, socket.deferredTexts.size());

      assertSignalsAlreadyParkedCycle(ws,
          () -> socket.deferredTexts.getFirst().completeExceptionally(
              new IOException("cancellation never left")));
    }
  }

  /// An acknowledgement frees the per-id gate while a later cancellation remains queued behind
  /// it. The released gate must wake an already-parked loop, independently of the remembered
  /// work flag used by a cycle which has not parked yet.
  @Test
  void cancellationAcknowledgementSignalsAnAlreadyParkedMaintenanceCycle()
      throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      prepareCancellationQueuedBehindGate(ws, socket);

      assertSignalsAlreadyParkedCycle(ws, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","result":false,"id":3}"""));
    }
  }

  /// A request-defect rejection settles an un-subscription and frees its per-id gate just like a
  /// boolean acknowledgement. If a later cancellation is already queued behind that gate, the
  /// rejection must wake a maintenance cycle which has already parked.
  @Test
  void rejectedCancellationSignalsAnAlreadyParkedMaintenanceCycle()
      throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      prepareCancellationQueuedBehindGate(ws, socket);

      assertSignalsAlreadyParkedCycle(ws, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid subscription id."},"id":3}"""));
    }
  }

  /// A numeric answer to an unsubscribe is malformed, but it still settles that request and
  /// releases its id gate. A queued successor cancellation therefore carries the same immediate
  /// wake obligation as a valid boolean acknowledgement.
  @Test
  void numericCancellationAnswerSignalsAnAlreadyParkedMaintenanceCycle()
      throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      prepareCancellationQueuedBehindGate(ws, socket);

      assertSignalsAlreadyParkedCycle(ws, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","result":999,"id":3}"""));
    }
  }

  /// A true cancellation can invalidate a live grant whose attempt preceded it. Re-queueing that
  /// durable registration is new maintenance work and must transfer a cycle already waiting on
  /// the condition.
  @Test
  void cancelledLiveGrantReplaySignalsAnAlreadyParkedMaintenanceCycle()
      throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      preparePredatingGrantAndCompensation(ws, socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":800,"id":3}""");

      assertSignalsAlreadyParkedCycle(ws, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":4}"""));
    }
  }

  /// If the cancellation acknowledgement beats the predating grant, its kill evidence makes
  /// that later grant dead on arrival. Re-queueing it is the same immediate maintenance
  /// obligation and must wake a cycle which is already parked.
  @Test
  void killedGrantReplaySignalsAnAlreadyParkedMaintenanceCycle()
      throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      preparePredatingGrantAndCompensation(ws, socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":4}""");

      assertSignalsAlreadyParkedCycle(ws, () -> feed(ws, socket, """
          {"jsonrpc":"2.0","result":800,"id":3}"""));
    }
  }

  /// A terminal rejection releases one `(key, commitment)` registration. The sibling
  /// commitment remains occupied, while the rejected commitment can be reclaimed without an id
  /// gap. The public return values and emitted JSON-RPC ids are independent observations of the
  /// durable registry and correlation sequence.
  @Test
  void terminalReleasePreservesTheSiblingCommitmentAndIdContinuity() throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.keyedProgramSubscribe(
          "scope", PROGRAM, List.of(Filter.createDataSizeFilter(165)), _ -> {
          }
      ));
      assertTrue(ws.keyedProgramSubscribe(
          Commitment.FINALIZED, "scope", PROGRAM, List.of(Filter.createDataSizeFilter(80)), _ -> {
          }
      ));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params"},"id":2}""");

      assertFalse(ws.keyedProgramSubscribe(
          Commitment.FINALIZED, "scope", PROGRAM, List.of(Filter.createDataSizeFilter(99)), _ -> {
          }
      ), "releasing confirmed must not release the finalized sibling");
      assertTrue(ws.keyedProgramSubscribe(
          "scope", PROGRAM, List.of(Filter.createDataSizeFilter(81)), _ -> {
          }
      ), "the rejected confirmed registration's exact slot is reusable");
      ws.checkCycle(0L);

      assertEquals(3, socket.sentText.size(), socket.sentText::toString);
      assertTrue(socket.sentText.get(0).contains("\"id\":2,"), socket.sentText.get(0));
      assertTrue(socket.sentText.get(1).contains("\"id\":3,"), socket.sentText.get(1));
      assertTrue(socket.sentText.get(2).contains("\"id\":4,"),
          () -> "a rejected occupancy check consumed a correlation id: " + socket.sentText);
    }
  }

  /// A caller key may deliberately equal the program's base58 address. A rejection in the keyed
  /// namespace must release only that exact object; the byte-identical legacy map key remains
  /// occupied. The two public subscribe results expose an accidental cross-map removal.
  @Test
  void keyedTerminalReleaseCannotDeleteTheCollidingLegacyRegistration() {
    final var sharedKey = PROGRAM.toBase58();
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.programSubscribe(PROGRAM, List.of(Filter.createDataSizeFilter(165)), _ -> {
      }));
      assertTrue(ws.keyedProgramSubscribe(
          sharedKey, PROGRAM, List.of(Filter.createDataSizeFilter(80)), _ -> {
          }
      ));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params"},"id":3}""");

      assertFalse(ws.programSubscribe(PROGRAM, List.of(Filter.createDataSizeFilter(99)), _ -> {
      }), "the legacy registration must survive a keyed rejection under the same map key");
      assertTrue(ws.keyedProgramSubscribe(
          sharedKey, PROGRAM, List.of(Filter.createDataSizeFilter(81)), _ -> {
          }
      ), "only the rejected keyed registration is released");
    }
  }

  /// Once the last generic key is terminally rejected, its notification-method namespace is
  /// empty and must be pruned. `retainedRegistrations` is the direct memory-retention oracle;
  /// merely proving that the key can be reused would not distinguish an empty outer map.
  @Test
  void terminalGenericReleasePrunesItsEmptyNotificationNamespace() {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.subscribe(
          "voteSubscribe", "voteUnsubscribe", "voteNotification", "vote", "",
          JsonIterator::readLong, null, _ -> {
          }
      ));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params"},"id":2}""");

      assertEquals(0, ws.retainedRegistrations(),
          "the rejected final key must not retain an empty notification-method map");
      assertTrue(ws.subscribe(
          "voteSubscribe", "voteUnsubscribe", "voteNotification", "vote", "1",
          JsonIterator::readLong, null, _ -> {
          }
      ));
    }
  }

  /// The first cancellation occupies id 77's wire gate. Root is the first obligation queued
  /// behind it; a later signature notification cannot replace that intent. Once the gate's false
  /// acknowledgement releases it, the next frame's method is the public wire-order oracle.
  @Test
  void aQueuedCancellationKeepsTheFirstPostdatingMethodWhileItsGateIsOccupied()
      throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":1,"root":1,"slot":2},"subscription":77}}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"rootNotification","params":{"result":2,"subscription":77}}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"signatureNotification","params":{"result":{"context":{"slot":3},"value":{"err":null}},"subscription":77}}""");
      assertEquals(1, socket.sentText.size(), "only the gate owner may be on the wire");

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":false,"id":2}""");
      ws.checkCycle(0L);

      assertEquals(2, socket.sentText.size(), socket.sentText::toString);
      assertTrue(socket.sentText.getLast().contains("\"method\":\"rootUnsubscribe\""),
          () -> "a later collision replaced the first queued obligation: " + socket.sentText);
    }
  }

  /// Once the gate releases, a fresh notification takes the direct cancellation path. An
  /// already-queued obligation still came first and therefore supplies the frame's method; the
  /// fresh notification is not authority to rewrite earlier intent.
  @Test
  void aGateReleasedQueuedCancellationOutranksAFreshNotification() {
    try (final var ws = websocket(new TestClock())) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":1,"root":1,"slot":2},"subscription":77}}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"rootNotification","params":{"result":2,"subscription":77}}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":false,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"signatureNotification","params":{"result":{"context":{"slot":3},"value":{"err":null}},"subscription":77}}""");

      assertEquals(2, socket.sentText.size(), socket.sentText::toString);
      assertTrue(socket.sentText.getLast().contains("\"method\":\"rootUnsubscribe\""),
          () -> "the fresh notification overtook queued cancellation intent: " + socket.sentText);
    }
  }

  /// The retry delay measures elapsed time from the last attempt. Moving the clock before the
  /// first send separates `now - lastAttempt` from `now + lastAttempt`; the exact boundary is
  /// still inside the exclusive resend window, and the following millisecond is due.
  @Test
  void subscriptionRetryUsesElapsedTimeAndAnExclusiveBoundary() throws InterruptedException {
    final var clock = new TestClock();
    clock.advanceMillis(40_000L);
    try (final var ws = websocket(clock)) {
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32603,"message":"Subscription refused"},"id":2}""");

      clock.advanceMillis(20_000L);
      ws.checkCycle(0L);
      assertEquals(1, framesContaining(socket, "\"method\":\"accountSubscribe\""),
          "retry pacing is elapsed time, not the sum of two clock readings");

      clock.advanceMillis(40_000L);
      ws.checkCycle(0L);
      assertEquals(1, framesContaining(socket, "\"method\":\"accountSubscribe\""),
          "an age equal to the resend delay has not exceeded it");

      clock.advanceMillis(1L);
      ws.checkCycle(0L);
      assertEquals(2, framesContaining(socket, "\"method\":\"accountSubscribe\""),
          "one millisecond past the window makes the retry due");
    }
  }

  /// `notBefore` names the first admissible instant, so equality is due. This drives the normal
  /// maintenance flush rather than a notification and pins the queued cancellation boundary.
  @Test
  void queuedUnsubscribeRetryIsDueAtItsNotBeforeBoundary() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}""");
      assertTrue(ws.accountUnsubscribe(ACCOUNT));
      ws.checkCycle(0L);
      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32603,"message":"Internal error"},"id":3}""");

      clock.advanceMillis(TIMINGS.subscriptionResendDelay());
      ws.checkCycle(0L);

      assertEquals(2, framesContaining(socket, "\"method\":\"accountUnsubscribe\""),
          () -> "the retry did not run at its first admissible instant: " + socket.sentText);
    }
  }

  /// The direct notification path observes the same `notBefore` contract as the maintenance
  /// flush. A late root frame at equality may trigger the owed retry, but not postpone it by an
  /// extra clock tick.
  @Test
  void directUnsubscribeRetryIsDueAtItsNotBeforeBoundary() throws InterruptedException {
    final var clock = new TestClock();
    try (final var ws = websocket(clock)) {
      assertTrue(ws.rootSubscribe(_ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}""");
      assertTrue(ws.rootUnsubscribe());
      ws.checkCycle(0L);
      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32603,"message":"Internal error"},"id":3}""");

      clock.advanceMillis(TIMINGS.subscriptionResendDelay());
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"rootNotification","params":{"result":2,"subscription":700}}""");

      assertEquals(2, framesContaining(socket, "\"method\":\"rootUnsubscribe\""),
          () -> "the direct retry did not run at its first admissible instant: " + socket.sentText);
    }
  }

  /// The queued predecessor fingerprint, not the notification which happens to flush it, says
  /// what the cancellation targeted. If a non-equivalent successor later owns the same id and
  /// the old cancellation fails, that ambiguity is connection-fatal; treating a lost fingerprint
  /// as a wildcard would silently trust two streams under one id.
  @Test
  void aNotificationFlushPreservesTheQueuedCancellationFingerprint() throws InterruptedException {
    final var errors = new ArrayList<Throwable>();
    try (final var ws = websocket(new TestClock(), (_, ex) -> errors.add(ex))) {
      assertTrue(ws.subscribe(
          "fooSubscribe", "fooUnsubscribe", "fooNotification", "key", "\"old\"",
          JsonIterator::readLong, null, _ -> {
          }
      ));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":55,"id":2}""");
      assertTrue(ws.unsubscribe("fooNotification", "key"));
      assertTrue(ws.subscribe(
          "fooSubscribe", "fooUnsubscribe", "fooNotification", "key", "\"new\"",
          JsonIterator::readLong, null, _ -> {
          }
      ));

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"fooNotification","params":{"result":7,"subscription":55}}""");
      ws.checkCycle(0L);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":55,"id":3}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32603,"message":"Internal error"},"id":4}""");

      assertTrue(socket.aborted,
          "a failed predecessor cancellation collides with a non-equivalent same-id successor");
      assertEquals(1, errors.size());
      assertInstanceOf(IllegalStateException.class, errors.getFirst());
    }
  }

  /// A true cancellation records a kill while an earlier subscribe attempt is unanswered. The
  /// kill and its retirement must survive that pending attempt, then both become obsolete once
  /// the attempt resolves to another id. A later unknown signature frame is the behavioral
  /// oracle that the swept retirement was released, rather than merely the bookkeeping count.
  @Test
  void aKillSurvivesItsPredatingAttemptAndSweepReleasesItsRetirement()
      throws InterruptedException {
    try (final var ws = websocket(new TestClock())) {
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // predecessor request 2
      assertTrue(ws.accountUnsubscribe(ACCOUNT));
      assertTrue(ws.accountSubscribe(ACCOUNT, _ -> {
      }));
      ws.checkCycle(0L); // successor request 3 precedes the compensation

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":800,"id":2}"""); // compensation request 4
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":4}""");
      ws.checkCycle(0L);
      assertEquals(2, ws.retainedOrdinalEntries(),
          "the predating request ordinal and its kill must both remain pending");

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":801,"id":3}""");
      ws.checkCycle(0L);
      assertEquals(1, ws.retainedOrdinalEntries(),
          "only the live successor ordinal remains after the kill is swept");

      final long before = framesContaining(socket, "\"method\":\"signatureUnsubscribe\"");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"signatureNotification","params":{"result":{"context":{"slot":3},"value":{"err":null}},"subscription":800}}""");
      assertEquals(before + 1, framesContaining(socket, "\"method\":\"signatureUnsubscribe\""),
          "the swept kill must release the id's retirement for ordinary unknown-id compensation");
    }
  }
}
