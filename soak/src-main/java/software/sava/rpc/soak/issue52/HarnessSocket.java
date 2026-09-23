package software.sava.rpc.soak.issue52;

import java.io.IOException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/// The wrapping `WebSocket` sava adopts. One instance per raw socket, by identity: sava stores
/// the object `onOpen` hands it, compares it by identity in `connectionFor`/`close()`/
/// `buildReservedAttempt`, and calls `abort()` on it from `retireConnection` — so this object
/// *is* the originating attempt's transport, which is what makes the abort-site the origin.
///
/// `abort()` classifies its direct caller with a `StackWalker` before delegating. The
/// retirement funnel is the only caller that is followed by a user handler on the same thread;
/// `connect()` replacement, `adopt` displacement, the `ownBuild` stale hook, the not-installed
/// arm of `buildReservedAttempt`, `close()` and its watchdog all abort without a callback, and
/// a frame created by one of those must never be consumed by a later handler. The classifier
/// names the engine methods, so a rename in sava fails loudly here instead of misattributing.
///
/// Every send returns a future mapped to `this` — sava returns the socket it was given — with
/// the failure object passed through unwrapped, so a ping failure reaches `onPingError` as the
/// same instance the transport produced (the self-test asserts that identity).
final class HarnessSocket implements WebSocket {

  private static final StackWalker WALKER = StackWalker.getInstance();
  private static final String HARNESS_PACKAGE = "software.sava.rpc.soak.issue52.";

  private final AttemptTracker tracker;
  private final AttemptTracker.Attempt attempt;
  final WebSocket raw;
  private final AtomicBoolean aborted = new AtomicBoolean();
  private final AtomicBoolean injected = new AtomicBoolean();

  HarnessSocket(final AttemptTracker tracker, final AttemptTracker.Attempt attempt, final WebSocket raw) {
    this.tracker = tracker;
    this.attempt = attempt;
    this.raw = raw;
  }

  long ordinal() {
    return attempt.ordinal;
  }

  AttemptTracker.Attempt attempt() {
    return attempt;
  }

  private CompletableFuture<WebSocket> mapToThis(final CompletableFuture<WebSocket> delegate) {
    final var mapped = new CompletableFuture<WebSocket>();
    delegate.whenComplete((_, ex) -> {
      if (ex == null) {
        mapped.complete(this);
      } else {
        mapped.completeExceptionally(ex);
      }
    });
    return mapped;
  }

  @Override
  public CompletableFuture<WebSocket> sendText(final CharSequence data, final boolean last) {
    return mapToThis(raw.sendText(data, last));
  }

  @Override
  public CompletableFuture<WebSocket> sendBinary(final ByteBuffer data, final boolean last) {
    return mapToThis(raw.sendBinary(data, last));
  }

  @Override
  public CompletableFuture<WebSocket> sendPing(final ByteBuffer message) {
    return mapToThis(raw.sendPing(message));
  }

  @Override
  public CompletableFuture<WebSocket> sendPong(final ByteBuffer message) {
    return mapToThis(raw.sendPong(message));
  }

  @Override
  public CompletableFuture<WebSocket> sendClose(final int statusCode, final String reason) {
    return mapToThis(raw.sendClose(statusCode, reason));
  }

  /// sava calls this off-lock after installing the `Connection` and before the user `onOpen`:
  /// exactly the #52 window. In forced mode, for every ordinal the tracker's arming selects, a
  /// retirement is injected here through the harness listener so it lands in a nested listener
  /// frame inside the adopting `onOpen` frame, the shape the paths table calls "retirement
  /// inside adopt".
  @Override
  public void request(final long n) {
    // Demand is the first collaborator call after the Connection is installed: this is the
    // adoption signal, and it precedes both the injected retirement and the user onOpen.
    attempt.installed = true;
    // The budget is claimed only for a socket that has not injected yet, so the once-only guard
    // can never swallow a claim that was already spent.
    if (!injected.get() && tracker.claimInjection(attempt.ordinal) && injected.compareAndSet(false, true)) {
      tracker.stats.injectedRetirements.increment();
      attempt.listener.onError(raw, new IOException("soak: injected in-adopt retirement"));
    }
    raw.request(n);
  }

  @Override
  public String getSubprotocol() {
    return raw.getSubprotocol();
  }

  @Override
  public boolean isOutputClosed() {
    return raw.isOutputClosed();
  }

  @Override
  public boolean isInputClosed() {
    return raw.isInputClosed();
  }

  @Override
  public void abort() {
    final long now = System.nanoTime();
    final String caller = directCaller();
    if (!aborted.compareAndSet(false, true)) {
      // The first abort is the candidate; a re-abort inside the same chain (close() aborting an
      // unadopted socket, adopt's stale arm, ownBuild's stale hook) is the same retirement.
      tracker.recordAbort(this, DeliveryFrames.nextAbortSeq(), now, caller, true);
      raw.abort();
      return;
    }
    tracker.recordAbort(this, DeliveryFrames.nextAbortSeq(), now, caller, false);
    raw.abort();
  }

  /// `Class.method` of the first frame outside this package. `retireConnection` is the one
  /// retirement site; a lambda body compiled from `ownBuild` shows as `lambda$ownBuild$N`, and a
  /// method reference run by an executor shows the executor's runner, never a sava frame.
  static String directCaller() {
    return WALKER.walk(frames -> frames
        .filter(f -> !f.getClassName().startsWith(HARNESS_PACKAGE))
        .findFirst()
        .map(f -> f.getClassName() + "." + f.getMethodName())
        .orElse("<none>"));
  }

  static boolean isRetirementCaller(final String caller) {
    return caller.equals(DeliveryFrames.ENGINE_CLASS + "." + DeliveryFrames.RETIREMENT_CLASSIFIER);
  }

  /// The engine's method name when the caller is the engine, else the whole `Class.method`.
  static String shortClassifier(final String caller) {
    final String enginePrefix = DeliveryFrames.ENGINE_CLASS + ".";
    return caller.startsWith(enginePrefix) ? caller.substring(enginePrefix.length()) : caller;
  }
}
