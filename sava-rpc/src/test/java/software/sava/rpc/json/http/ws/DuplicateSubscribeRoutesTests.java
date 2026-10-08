package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;

import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// A typed subscribe can refuse a duplicate `(key, commitment)` at two places: the public
/// registry guard before the request is rendered, and the lock-held check in
/// `queueSubscription`, which decides a duplicate registered after that guard ran. The ws
/// `# redundant outer duplicate guard` family in `config/pitest/README.md` claims a caller
/// cannot tell the two apart. Each test here drives a duplicate down both routes and
/// compares what a caller observes: the answer, the frames the connection sends, the id the
/// next registration carries, and which consumer a notification reaches.
///
/// The lock-held route needs a registration to land between the guard and the lock. For the
/// `PublicKey`-keyed channels, the key's rendering is that seam; a signature is a `String`,
/// so a second thread passes the guard and waits on the held lock while the registration
/// lands.
@ExtendWith(QuietWsLogging.class)
final class DuplicateSubscribeRoutesTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final PublicKey KEY = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
  private static final PublicKey OTHER = PublicKey.fromBase58Encoded("Vote111111111111111111111111111111111111111");
  private static final PublicKey PROGRAM = PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final String SIGNATURE = "5h6xBEauJ3PK6SWCZ1PGjBvj8vDdWG3KpwATGy1ARAXFSDwt8GFXM7W5Ncn16wmq";
  private static final String OTHER_SIGNATURE = "2nAFQ3XzyydwkdhtLoNti9C9SZw2VgZrNjJmJx1yARHSpkbSCJvDR7ghemmYZLvD";

  private static SolanaJsonRpcWebsocket createWebsocket() {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(),
        new RecordingExecutor(),
        null,
        _ -> {
        },
        (_, _, _) -> {
        },
        null, null, null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  /// A key that counts its two renderings apart: `toBase58`, which the registry lookups call,
  /// and `toString`, which only the request's `String.format` calls.
  private static final class RenderCountingKey implements PublicKey {

    private final PublicKey delegate;
    private int toBase58Calls;
    private int toStringCalls;

    private RenderCountingKey(final PublicKey delegate) {
      this.delegate = delegate;
    }

    @Override
    public int write(final byte[] out, final int off) {
      return delegate.write(out, off);
    }

    @Override
    public byte[] toByteArray() {
      return delegate.toByteArray();
    }

    @Override
    public byte[] copyByteArray() {
      return delegate.copyByteArray();
    }

    @Override
    public String toBase58() {
      ++toBase58Calls;
      return delegate.toBase58();
    }

    @Override
    public String toBase64() {
      return delegate.toBase64();
    }

    @Override
    public int compareTo(final PublicKey other) {
      return delegate.compareTo(other);
    }

    @Override
    public String toString() {
      ++toStringCalls;
      return delegate.toBase58();
    }
  }

  /// A key whose rendering runs a hook once, on the calling thread. The typed subscribes
  /// render their request after the public guard and before the locked helper, so a hook
  /// that registers the same key there sends the caller to the helper's lock-held decision.
  private static final class RenderHookKey implements PublicKey {

    private final PublicKey delegate;
    private Runnable onFirstRender;

    private RenderHookKey(final PublicKey delegate, final Runnable onFirstRender) {
      this.delegate = delegate;
      this.onFirstRender = onFirstRender;
    }

    @Override
    public int write(final byte[] out, final int off) {
      return delegate.write(out, off);
    }

    @Override
    public byte[] toByteArray() {
      return delegate.toByteArray();
    }

    @Override
    public byte[] copyByteArray() {
      return delegate.copyByteArray();
    }

    @Override
    public String toBase58() {
      return delegate.toBase58();
    }

    @Override
    public String toBase64() {
      return delegate.toBase64();
    }

    @Override
    public int compareTo(final PublicKey other) {
      return delegate.compareTo(other);
    }

    @Override
    public String toString() {
      final var hook = onFirstRender;
      onFirstRender = null;
      if (hook != null) {
        hook.run();
      }
      return delegate.toBase58();
    }
  }

  /// One route to a duplicate: registers the key for consumer `first`, answers a duplicate
  /// for consumer `second`, and returns that answer.
  @FunctionalInterface
  private interface DuplicateRoute {

    boolean answer(SolanaJsonRpcWebsocket ws, List<String> delivered) throws InterruptedException;
  }

  @FunctionalInterface
  private interface Registration {

    boolean register(SolanaJsonRpcWebsocket ws, List<String> delivered);
  }

  /// What a caller can observe after a duplicate: its answer, every frame the connection
  /// sends once a second key registers and the connection opens, and the consumers a
  /// notification for the first grant reaches.
  private record Outcome(boolean answer, List<String> frames, List<String> delivered) {
  }

  private static Outcome outcome(final DuplicateRoute route,
                                 final Registration registerOther,
                                 final String firstGrantNotification) throws InterruptedException {
    final var delivered = new ArrayList<String>();
    try (final var ws = createWebsocket()) {
      final boolean answer = route.answer(ws, delivered);
      assertTrue(registerOther.register(ws, delivered), "a fresh key registers after the duplicate");
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":701,"id":3}""");
      feed(ws, socket, firstGrantNotification);
      return new Outcome(answer, List.copyOf(socket.sentText), List.copyOf(delivered));
    }
  }

  private static void assertRoutesAgree(final String method,
                                        final Outcome outerGuard,
                                        final Outcome lockHeld) {
    assertAll(
        () -> assertFalse(outerGuard.answer(), "the public guard refuses the duplicate"),
        () -> assertEquals(2, outerGuard.frames().size(), outerGuard.frames()::toString),
        () -> assertTrue(outerGuard.frames().get(0).contains("\"id\":2,\"method\":\"" + method + '"'),
            outerGuard.frames()::toString),
        () -> assertTrue(outerGuard.frames().get(1).contains("\"id\":3,\"method\":\"" + method + '"'),
            () -> "the duplicate consumed a message id: " + outerGuard.frames()),
        () -> assertEquals(List.of("first"), outerGuard.delivered(),
            "the first registration keeps the key"),
        () -> assertEquals(outerGuard, lockHeld,
            "the lock-held route must answer exactly as the public guard does")
    );
  }

  private static final String ACCOUNT_NOTIFICATION = """
      {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":1},"value":{"data":["","base64"],"executable":false,"lamports":1,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":0}},"subscription":700}}""";

  private static final String LOGS_NOTIFICATION = """
      {"jsonrpc":"2.0","method":"logsNotification","params":{"result":{"context":{"slot":1},"value":{"signature":"sig","err":null,"logs":["l"]}},"subscription":700}}""";

  private static final String SIGNATURE_NOTIFICATION = """
      {"jsonrpc":"2.0","method":"signatureNotification","params":{"result":{"context":{"slot":1},"value":{"err":null}},"subscription":700}}""";

  @Test
  void accountDuplicateAnswersAlikeAtThePublicGuardAndUnderTheLock() throws InterruptedException {
    final var outerGuard = outcome(
        (ws, delivered) -> {
          assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, KEY, _ -> delivered.add("first")));
          return ws.accountSubscribe(Commitment.CONFIRMED, KEY, _ -> delivered.add("second"));
        },
        (ws, delivered) -> ws.accountSubscribe(Commitment.CONFIRMED, OTHER, _ -> delivered.add("other")),
        ACCOUNT_NOTIFICATION
    );
    final var lockHeld = outcome(
        (ws, delivered) -> {
          final boolean[] registered = {false};
          final var key = new RenderHookKey(KEY, () -> registered[0] = ws.accountSubscribe(
              Commitment.CONFIRMED, KEY, _ -> delivered.add("first")
          ));
          final boolean answer = ws.accountSubscribe(Commitment.CONFIRMED, key, _ -> delivered.add("second"));
          assertTrue(registered[0], "the key registered between the public guard and the lock");
          return answer;
        },
        (ws, delivered) -> ws.accountSubscribe(Commitment.CONFIRMED, OTHER, _ -> delivered.add("other")),
        ACCOUNT_NOTIFICATION
    );
    assertRoutesAgree("accountSubscribe", outerGuard, lockHeld);
  }

  @Test
  void logsDuplicateAnswersAlikeAtThePublicGuardAndUnderTheLock() throws InterruptedException {
    final var outerGuard = outcome(
        (ws, delivered) -> {
          assertTrue(ws.logsSubscribe(Commitment.CONFIRMED, KEY, _ -> delivered.add("first")));
          return ws.logsSubscribe(Commitment.CONFIRMED, KEY, _ -> delivered.add("second"));
        },
        (ws, delivered) -> ws.logsSubscribe(Commitment.CONFIRMED, OTHER, _ -> delivered.add("other")),
        LOGS_NOTIFICATION
    );
    final var lockHeld = outcome(
        (ws, delivered) -> {
          final boolean[] registered = {false};
          final var key = new RenderHookKey(KEY, () -> registered[0] = ws.logsSubscribe(
              Commitment.CONFIRMED, KEY, _ -> delivered.add("first")
          ));
          final boolean answer = ws.logsSubscribe(Commitment.CONFIRMED, key, _ -> delivered.add("second"));
          assertTrue(registered[0], "the key registered between the public guard and the lock");
          return answer;
        },
        (ws, delivered) -> ws.logsSubscribe(Commitment.CONFIRMED, OTHER, _ -> delivered.add("other")),
        LOGS_NOTIFICATION
    );
    assertRoutesAgree("logsSubscribe", outerGuard, lockHeld);
  }

  /// A signature has no rendering seam, so the contender passes the public guard on its own
  /// thread and parks on the lifecycle lock this thread holds. The spin ends when the
  /// contender is queued on the lock or has finished, so it needs no clock; the
  /// registration then lands under the held lock, and releasing it hands the contender the
  /// lock-held decision.
  @Test
  void signatureDuplicateAnswersAlikeAtThePublicGuardAndUnderTheLock() throws InterruptedException {
    final var outerGuard = outcome(
        (ws, delivered) -> {
          assertTrue(ws.signatureSubscribe(Commitment.CONFIRMED, SIGNATURE, _ -> delivered.add("first")));
          return ws.signatureSubscribe(Commitment.CONFIRMED, SIGNATURE, _ -> delivered.add("second"));
        },
        (ws, delivered) -> ws.signatureSubscribe(Commitment.CONFIRMED, OTHER_SIGNATURE, _ -> delivered.add("other")),
        SIGNATURE_NOTIFICATION
    );
    final var lockHeld = outcome(
        (ws, delivered) -> {
          final var answer = new AtomicReference<Boolean>();
          final var failure = new AtomicReference<Throwable>();
          final var contender = Thread.ofPlatform().unstarted(() -> {
            try {
              answer.set(ws.signatureSubscribe(Commitment.CONFIRMED, SIGNATURE, _ -> delivered.add("second")));
            } catch (final Throwable t) {
              failure.set(t);
            } finally {
              while (ws.lock.isHeldByCurrentThread()) {
                ws.lock.unlock();
              }
            }
          });
          final boolean queued;
          ws.lock.lock();
          try {
            contender.start();
            while (contender.isAlive() && !ws.lock.hasQueuedThread(contender)) {
              Thread.onSpinWait();
            }
            queued = ws.lock.hasQueuedThread(contender);
            if (queued) {
              assertTrue(ws.signatureSubscribe(Commitment.CONFIRMED, SIGNATURE, _ -> delivered.add("first")));
            }
          } finally {
            while (ws.lock.isHeldByCurrentThread()) {
              ws.lock.unlock();
            }
          }
          contender.join();
          assertTrue(queued, "the contender must pass the public guard and wait for the lock");
          assertNull(failure.get(), () -> "the contender threw: " + failure.get());
          return answer.get();
        },
        (ws, delivered) -> ws.signatureSubscribe(Commitment.CONFIRMED, OTHER_SIGNATURE, _ -> delivered.add("other")),
        SIGNATURE_NOTIFICATION
    );
    assertRoutesAgree("signatureSubscribe", outerGuard, lockHeld);
  }

  /// The legacy program registry is keyed by program and commitment like every typed
  /// channel: `keyedProgramSubscribe` is documented as the way to hold the same program and
  /// commitment twice, and `programUnsubscribe(Commitment, PublicKey)` removes one
  /// commitment of a program. A second commitment for a held program is therefore a new
  /// registration, not a duplicate; the account, logs and signature channels pin the same
  /// shape in their own tests.
  @Test
  void programSubscribeRegistersASecondCommitmentOfAHeldProgram() {
    try (final var ws = createWebsocket()) {
      final var confirmed = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      final var finalized = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      assertTrue(ws.programSubscribe(Commitment.CONFIRMED, PROGRAM, null, confirmed::set, _ -> {
      }));
      assertTrue(ws.programSubscribe(Commitment.FINALIZED, PROGRAM, null, finalized::set, _ -> {
      }), "a sibling commitment of a held program is a new registration");
      assertFalse(ws.programSubscribe(Commitment.FINALIZED, PROGRAM, null, _ -> {
      }, _ -> {
      }), "the program and commitment pair is held");

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      assertAll(
          () -> assertEquals(2, socket.sentText.size(), socket.sentText::toString),
          () -> assertTrue(socket.sentText.get(0).contains("\"id\":2,")
              && socket.sentText.get(0).contains("\"commitment\":\"confirmed\""), socket.sentText::toString),
          () -> assertTrue(socket.sentText.get(1).contains("\"id\":3,")
              && socket.sentText.get(1).contains("\"commitment\":\"finalized\""), socket.sentText::toString),
          () -> assertNotNull(confirmed.get(), "the confirmed registration was not sent"),
          () -> assertNotNull(finalized.get(), "the finalized registration was not sent"),
          () -> assertTrue(ws.programUnsubscribe(Commitment.FINALIZED, PROGRAM)),
          () -> assertTrue(ws.programUnsubscribe(Commitment.CONFIRMED, PROGRAM))
      );
    }
  }

  /// The public guard is a fast path with an effect a caller can observe: a duplicate it
  /// refuses never renders the caller's key into a request, since the request's
  /// `String.format` runs only past the guard (the guard's own lookup calls `toBase58`, which
  /// is counted apart). Sent past the guard, the mutant that forces its first operand, the
  /// lock-held check still refuses the duplicate, but only after the request rendered the
  /// key through `toString`, so that count is the oracle.
  @Test
  void aRefusedAccountDuplicateRendersNothingOfTheKey() {
    try (final var ws = createWebsocket()) {
      final var key = new RenderCountingKey(KEY);
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, key, _ -> {
      }));
      assertEquals(1, key.toStringCalls, "registering renders the key into its request once");

      assertFalse(ws.accountSubscribe(Commitment.CONFIRMED, key, _ -> {
      }));
      assertEquals(1, key.toStringCalls, "a refused duplicate must not render the key into a request");
    }
  }

  /// The same fast-path property for the logs channel.
  @Test
  void aRefusedLogsDuplicateRendersNothingOfTheKey() {
    try (final var ws = createWebsocket()) {
      final var key = new RenderCountingKey(KEY);
      assertTrue(ws.logsSubscribe(Commitment.CONFIRMED, key, _ -> {
      }));
      assertEquals(1, key.toStringCalls, "registering renders the key into its request once");

      assertFalse(ws.logsSubscribe(Commitment.CONFIRMED, key, _ -> {
      }));
      assertEquals(1, key.toStringCalls, "a refused duplicate must not render the key into a request");
    }
  }

  /// A signature renders as itself, so the fast path's observable is the lifecycle lock: a
  /// duplicate the public guard refuses never waits for it. The test holds the lock and starts
  /// the duplicate on another thread; it either finishes without the lock (the guard refused
  /// it) or queues behind the holder (the mutant sent it to the lock-held check). Both are
  /// deterministic outcomes of a spin that needs no clock, and the lock is released afterwards
  /// so a queued contender can still finish.
  @Test
  void aRefusedSignatureDuplicateNeverWaitsForTheLifecycleLock() throws InterruptedException {
    try (final var ws = createWebsocket()) {
      assertTrue(ws.signatureSubscribe(Commitment.CONFIRMED, SIGNATURE, _ -> {
      }));
      // A hold leaked by the listener path (every frame above ran on this thread) would park
      // the contender for good below; fail here instead of hanging.
      assertEquals(0, ws.lock.getHoldCount(), "the listener path must release the lifecycle lock");
      final boolean[] answer = {true};
      final var contender = new Thread(() -> answer[0] = ws.signatureSubscribe(Commitment.CONFIRMED, SIGNATURE, _ -> {
      }), "duplicate signature subscribe");
      ws.lock.lock();
      final boolean queued;
      try {
        contender.start();
        while (contender.isAlive() && !ws.lock.hasQueuedThread(contender)) {
          Thread.onSpinWait();
        }
        queued = ws.lock.hasQueuedThread(contender);
      } finally {
        while (ws.lock.isHeldByCurrentThread()) {
          ws.lock.unlock();
        }
      }
      contender.join();
      assertFalse(queued, "a refused duplicate must not wait for the lifecycle lock");
      assertFalse(answer[0], "the duplicate is refused");
    }
  }
}
