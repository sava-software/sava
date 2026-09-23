package software.sava.rpc.soak.issue52;

import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static software.sava.rpc.soak.issue52.DeliveryFrames.FrameKind;

/// The future the harness builder returns from `buildAsync` — returned DIRECTLY, because sava's
/// `ownBuild` hands the builder's own future back and installs it as `inFlightBuild`, so the
/// object `retireConnection`/`close()`/`connect()`/`buildReservedAttempt` cancel is this one.
/// Overriding `cancel(true)` is therefore the harness's only view of "the attempt future was
/// settled by the engine", and its direct caller says why.
///
/// While `super.cancel(true)` runs, every synchronous dependent — sava's bridge, every
/// `connect()` copy, the manager's `attempt.whenComplete` → `connectionAttemptFailed` →
/// `beginFailure` → `Backoff.delay` — executes on this thread, so the thread's
/// `insideBuildCancel` flag brackets exactly the future route. The underlying JDK future is
/// cancelled afterwards so a handshake still in flight is torn down as it would have been.
///
/// Completion is done from the JDK future's `whenComplete` inside a build-completion frame, so
/// sava's `ownBuild` stale hook, the bridge and the manager's `markOpen` all run inside a frame.
/// In forced mode the completion is held until `onOpen` has returned: the JDK settles the
/// future microseconds after `newInstance`, the manager's `markOpen(current, attempt)` then
/// consumes its copy from the future route, and the #52 window closes before any retirement
/// injected inside `adopt` can reach it. sava explicitly tolerates a wrapping builder that
/// orders the two signals either way.
final class HarnessBuildFuture extends CompletableFuture<WebSocket> {

  private final AttemptTracker tracker;
  private final AttemptTracker.Attempt attempt;
  private volatile CompletableFuture<WebSocket> jdkFuture;
  private volatile HarnessSocket deferredResult;
  private volatile boolean jdkSucceeded;
  private volatile boolean onOpenReturned;
  private final AtomicBoolean deferredCompleted = new AtomicBoolean();

  HarnessBuildFuture(final AttemptTracker tracker, final AttemptTracker.Attempt attempt) {
    this.tracker = tracker;
    this.attempt = attempt;
  }

  void attach(final CompletableFuture<WebSocket> jdkFuture) {
    this.jdkFuture = jdkFuture;
    jdkFuture.whenComplete(this::onJdkComplete);
  }

  private void onJdkComplete(final WebSocket raw, final Throwable failure) {
    final long now = System.nanoTime();
    final var frames = DeliveryFrames.of();
    final var frame = frames.push(tracker, FrameKind.BUILD_COMPLETION, attempt.ordinal, now);
    try {
      if (failure != null) {
        attempt.futureFailed(now, Thread.currentThread(), failure);
        completeExceptionally(failure);
        return;
      }
      final var wrapper = attempt.wrapperFor(raw);
      if (tracker.deferBuildCompletionUntilOnOpen()) {
        deferredResult = wrapper;
        jdkSucceeded = true;
        attempt.futureSucceeded(now, Thread.currentThread());
        maybeCompleteDeferred(frames);
        return;
      }
      attempt.futureSucceeded(now, Thread.currentThread());
      if (!complete(wrapper)) {
        orphanGuard(wrapper);
      }
    } finally {
      frames.pop(frame);
    }
  }

  /// Forced-mode ordering: complete only once both the JDK has produced the socket and the
  /// harness listener's `onOpen` has returned (either may come first with a wrapping fake).
  void onOpenReturned() {
    onOpenReturned = true;
    if (jdkSucceeded && !deferredCompleted.get()) {
      final var frames = DeliveryFrames.of();
      final var frame = frames.push(tracker, FrameKind.BUILD_COMPLETION, attempt.ordinal, System.nanoTime());
      try {
        maybeCompleteDeferred(frames);
      } finally {
        frames.pop(frame);
      }
    }
  }

  private void maybeCompleteDeferred(final DeliveryFrames frames) {
    if (jdkSucceeded && onOpenReturned && deferredCompleted.compareAndSet(false, true)) {
      final var wrapper = deferredResult;
      if (!complete(wrapper)) {
        orphanGuard(wrapper);
      }
    }
  }

  /// This future was already settled (cancelled by `close()`, `connect()` or a retirement)
  /// when the JDK delivered a socket. sava's `ownBuild` hook only ever sees THIS future, so a
  /// socket it never receives is nobody's: abort it here, as the engine would have, unless the
  /// attempt was adopted, in which case sava owns its release.
  private void orphanGuard(final HarnessSocket wrapper) {
    if (!attempt.adopted()) {
      tracker.stats.orphanSocketsAborted.increment();
      wrapper.abort();
    }
  }

  @Override
  public boolean cancel(final boolean mayInterruptIfRunning) {
    final long now = System.nanoTime();
    final boolean wasDone = isDone();
    final String caller = HarnessSocket.directCaller();
    final var frames = DeliveryFrames.of();
    final var sub = tracker.buildCancelObserved(this.attempt, frames, wasDone, now, caller);
    final boolean cancelled;
    ++frames.buildCancelDepth;
    try {
      cancelled = super.cancel(true);
    } finally {
      --frames.buildCancelDepth;
      if (sub != null) {
        sub.cancelReturnNanos = System.nanoTime();
        sub.ordinalAtCancelReturn = tracker.currentOrdinal();
      }
    }
    final var jdk = this.jdkFuture;
    if (jdk != null) {
      jdk.cancel(true);
    }
    return cancelled;
  }
}
