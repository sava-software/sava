package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;

import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/// An attempt abandoned while its build is pending ends in a plain cancellation, for the
/// builder that handed out the future and for the caller holding `connect()`'s view: the ws
/// `# ignored null completion` family (`config/pitest/README.md`).
///
/// The engine watches each build with a `whenComplete` ownership hook whose dependent stage
/// it discards, and a cancelled build reaches that hook with no socket. The discarded stage
/// is not where a fault in the hook ends up: by the `CompletableFuture.whenComplete`
/// contract, an action that throws while its source completed exceptionally has its
/// exception added as suppressed to the source's own exception (JDK 25 `uniWhenComplete`),
/// and that exception is the build's cancellation, which the engine also forwards to the
/// caller. The oracle is that cancellation: no suppressed exception rides on it, whether
/// close, a retired transport, or a build that lost installation abandoned the attempt.
@ExtendWith(QuietWsLogging.class)
final class AbandonedAttemptCancellationTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);

  /// Hands out futures the test settles; with `openWith`, delivers that socket to the
  /// attempt listener synchronously before returning, as the JDK may before its future
  /// completes.
  private static final class PendingBuilder implements WebSocket.Builder {

    final List<CompletableFuture<WebSocket>> builds = new ArrayList<>();
    final List<WebSocket.Listener> listeners = new ArrayList<>();
    RecordingWebSocket openWith;

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
      listeners.add(listener);
      if (openWith != null) {
        listener.onOpen(openWith);
      }
      final var build = new CompletableFuture<WebSocket>();
      builds.add(build);
      return build;
    }
  }

  private static SolanaJsonRpcWebsocket websocket(final PendingBuilder builder,
                                                  final Consumer<SolanaRpcWebsocket> onOpen) {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        builder, TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), new RecordingScheduler(), onOpen, (_, _, _) -> {
        },
        // a handler keeps a retired transport from closing the wrapper
        (_, _) -> {
        },
        null, null
    );
  }

  /// The exception instance a settled future holds, as its dependents receive it. Read
  /// through `handle` because `join()` on JDK 25 throws a fresh `CancellationException`
  /// whose cause is the stored one.
  private static Throwable storedFailure(final CompletableFuture<?> future) {
    assertTrue(future.isDone());
    return future.handle((_, failure) -> failure).join();
  }

  /// The build's own cancellation, with nothing attached to it.
  private static void assertPlainCancellation(final CompletableFuture<WebSocket> build) {
    assertTrue(build.isCancelled(), "the abandoned attempt's build is cancelled");
    final var cancellation = assertInstanceOf(CancellationException.class, storedFailure(build));
    assertEquals(List.of(), Arrays.asList(cancellation.getSuppressed()),
        "the build's cancellation carries an engine fault");
  }

  /// What the caller holding `connect()`'s view sees: a cancellation, with nothing
  /// attached.
  private static void assertCallerSeesAPlainCancellation(final CompletableFuture<?> attempt) {
    final var failure = storedFailure(attempt);
    final var cancellation = assertInstanceOf(CancellationException.class,
        failure instanceof CompletionException ? failure.getCause() : failure);
    assertEquals(List.of(), Arrays.asList(cancellation.getSuppressed()),
        "the caller's cancellation carries an engine fault");
  }

  @Test
  void closeCancelsAPendingAttemptWithAPlainCancellation() {
    final var builder = new PendingBuilder();
    final var ws = websocket(builder, null);
    try {
      final var attempt = ws.connect();
      assertFalse(attempt.isDone());

      ws.close();

      assertPlainCancellation(builder.builds.getFirst());
      assertCallerSeesAPlainCancellation(attempt);
    } finally {
      ws.close();
    }
  }

  @Test
  void aRetiredTransportCancelsItsPendingAttemptWithAPlainCancellation() {
    final var builder = new PendingBuilder();
    final var socket = new RecordingWebSocket();
    builder.openWith = socket;
    try (final var ws = websocket(builder, null)) {
      final var attempt = ws.connect();
      assertFalse(attempt.isDone(), "adopted before its build settled");

      builder.listeners.getFirst().onError(socket, new IOException("transport lost"));

      assertTrue(socket.aborted);
      assertFalse(ws.closed(), "the handler kept the wrapper");
      assertPlainCancellation(builder.builds.getFirst());
      assertCallerSeesAPlainCancellation(attempt);
    }
  }

  /// Close from the open callback, while `buildAsync` is still running: the build it then
  /// returns is never installed and is cancelled by the losing installer. Only the builder
  /// holds that future, so its cancellation is the whole observable.
  @Test
  void aBuildThatLostInstallationIsCancelledPlainly() {
    final var builder = new PendingBuilder();
    final var socket = new RecordingWebSocket();
    builder.openWith = socket;
    final var ws = websocket(builder, SolanaRpcWebsocket::close);
    try {
      final var attempt = ws.connect();

      assertTrue(ws.closed());
      assertTrue(attempt.isCompletedExceptionally(), "close settles the caller's view");
      assertPlainCancellation(builder.builds.getFirst());
    } finally {
      ws.close();
    }
  }
}
