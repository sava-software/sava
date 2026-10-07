package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/// The boolean every subscribe and unsubscribe answers, asserted at each exit that answers
/// it with a literal. `SolanaRpcWebsocket` documents two of those answers: once closed,
/// "subscriptions return false", and a subscribe's `false` means the registration already
/// exists (the `signatureSubscribe` documentation). The third, an unsubscribe answering
/// whether it removed a registration, is undocumented there but is what callers and this
/// package's tests rely on. Each exit returns its literal from inside the lifecycle lock's
/// `try`/`finally`, so the compiled exit stores the constant, runs the unlock, and reloads
/// it: replacing that value with the same constant changes nothing (the ws
/// `# literal return equivalent` family in `config/pitest/README.md`). This class is the
/// oracle that each such exit is reached and answers the literal its contract requires.
@ExtendWith(QuietWsLogging.class)
final class SubscriptionResultContractTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final PublicKey FIRST = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
  private static final PublicKey SECOND = PublicKey.fromBase58Encoded("Vote111111111111111111111111111111111111111");
  private static final PublicKey PROGRAM = PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");

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

  /// A key whose parameter rendering runs a hook once, on the calling thread. The typed
  /// subscribes render their parameters after the public map guard and before the locked
  /// helper, so a hook that registers the same key there brings the caller to the helper's
  /// lock-held duplicate decision without a second thread.
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

  /// The String-keyed registration helper, reached through `logsSubscribe`: queued, a
  /// duplicate decided under the lock, and closed.
  @Test
  void logsSubscribeAnswersTrueWhenQueuedAndFalseForALockedDuplicateOrAfterClose() {
    try (final var ws = createWebsocket()) {
      assertTrue(ws.logsSubscribe(Commitment.CONFIRMED, FIRST, _ -> {
      }), "a new registration is queued");

      final boolean[] firstThere = {false};
      final var racingKey = new RenderHookKey(SECOND, () -> firstThere[0] = ws.logsSubscribe(
          Commitment.CONFIRMED, SECOND, _ -> {
          }
      ));
      assertFalse(ws.logsSubscribe(Commitment.CONFIRMED, racingKey, _ -> {
      }), "registered between the public guard and the lock: already subscribed");
      assertTrue(firstThere[0], "the registration that got there first was queued");

      ws.close();
      assertFalse(ws.logsSubscribe(Commitment.CONFIRMED, FIRST, _ -> {
      }), "closed: subscriptions return false");
    }
  }

  /// The PublicKey-keyed registration helper, reached through `keyedProgramSubscribe`,
  /// which has no public guard of its own: queued, duplicate, and closed.
  @Test
  void keyedProgramSubscribeAnswersTrueWhenQueuedAndFalseForADuplicateOrAfterClose() {
    try (final var ws = createWebsocket()) {
      assertTrue(ws.keyedProgramSubscribe(Commitment.CONFIRMED, "pool-a", PROGRAM, null, _ -> {
      }), "a new registration is queued");
      assertFalse(ws.keyedProgramSubscribe(Commitment.CONFIRMED, "pool-a", PROGRAM, null, _ -> {
      }), "the same key and commitment: already subscribed");

      ws.close();
      assertFalse(ws.keyedProgramSubscribe(Commitment.CONFIRMED, "pool-b", PROGRAM, null, _ -> {
      }), "closed: subscriptions return false");
    }
  }

  /// The typed unsubscribe helper, reached through `accountUnsubscribe`: no registration
  /// under the key, none at the commitment under a registered key, and a removal.
  @Test
  void typedUnsubscribeAnswersWhetherItRemovedARegistration() {
    try (final var ws = createWebsocket()) {
      assertFalse(ws.accountUnsubscribe(Commitment.CONFIRMED, FIRST), "nothing under the key");
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, FIRST, _ -> {
      }));
      assertFalse(ws.accountUnsubscribe(Commitment.FINALIZED, FIRST),
          "the key is registered, but not at this commitment");
      assertTrue(ws.accountUnsubscribe(Commitment.CONFIRMED, FIRST), "removed");
    }
  }

  @Test
  void slotSubscriptionAnswersTrueOnceAndFalseWhileOccupiedOrClosed() {
    try (final var ws = createWebsocket()) {
      assertFalse(ws.slotUnsubscribe(), "nothing to remove");
      assertTrue(ws.slotSubscribe(_ -> {
      }), "a new registration is queued");
      assertFalse(ws.slotSubscribe(_ -> {
      }), "the singleton is occupied: already subscribed");
      assertTrue(ws.slotUnsubscribe(), "removed");

      ws.close();
      assertFalse(ws.slotSubscribe(_ -> {
      }), "closed: subscriptions return false");
    }
  }

  @Test
  void rootSubscriptionAnswersTrueOnceAndFalseWhileOccupiedOrClosed() {
    try (final var ws = createWebsocket()) {
      assertFalse(ws.rootUnsubscribe(), "nothing to remove");
      assertTrue(ws.rootSubscribe(_ -> {
      }), "a new registration is queued");
      assertFalse(ws.rootSubscribe(_ -> {
      }), "the singleton is occupied: already subscribed");
      assertTrue(ws.rootUnsubscribe(), "removed");

      ws.close();
      assertFalse(ws.rootSubscribe(_ -> {
      }), "closed: subscriptions return false");
    }
  }

  @Test
  void genericSubscriptionAnswersTrueOnceAndFalseForADuplicateKeyOrAfterClose() {
    try (final var ws = createWebsocket()) {
      assertTrue(subscribe(ws, "a"), "a new registration is queued");
      assertTrue(subscribe(ws, "b"), "a second key under the same notification method");
      assertFalse(subscribe(ws, "a"), "the same key: already subscribed");

      assertTrue(ws.unsubscribe("watchNotification", "a"), "removed");
      assertFalse(ws.unsubscribe("watchNotification", "a"),
          "the method is still registered, under another key");
      assertFalse(ws.unsubscribe("otherNotification", "b"), "nothing under that method");
      assertTrue(ws.unsubscribe("watchNotification", "b"), "removed");

      ws.close();
      assertFalse(subscribe(ws, "c"), "closed: subscriptions return false");
    }
  }

  private static boolean subscribe(final SolanaJsonRpcWebsocket ws, final String key) {
    return ws.subscribe(
        "watchSubscribe", "watchUnsubscribe", "watchNotification",
        key, "", JsonIterator::readLong, null, _ -> {
        }
    );
  }
}
