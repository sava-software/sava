package software.sava.rpc.soak.issue52;

import software.sava.services.core.remote.call.Backoff;

import java.util.concurrent.TimeUnit;

/// The manager's `Backoff`, wrapped. `WebSocketManagerImpl#beginFailure` calls
/// `backoff.delay(claim.errorCount(), MILLISECONDS)` exactly once per *accepted* claim, on the
/// delivering thread, after the locked claim and after cancelling its private copy of the
/// attempt — so one call here is one accepted claim, with the exact post-increment
/// `errorCount`, and a fenced claim is precisely the absence of a call. The reset to zero is
/// not a call; it is implied by the next `delay(1)` and signalled by the manager's INFO
/// "connected" line, which the log capture uses to reset the tracker's last known count.
///
/// The claim is stamped at the delegate's return: that is `t(C_f return)`, where the
/// detection rule's gap begins.
final class RecordingBackoff implements Backoff {

  private final AttemptTracker tracker;
  private final Backoff delegate;
  final BackoffClass backoffClass;

  RecordingBackoff(final AttemptTracker tracker, final Backoff delegate) {
    this.tracker = tracker;
    this.delegate = delegate;
    this.backoffClass = BackoffClass.classify(delegate);
  }

  @Override
  public TimeUnit timeUnit() {
    return delegate.timeUnit();
  }

  @Override
  public long initialDelay(final TimeUnit timeUnit) {
    return delegate.initialDelay(timeUnit);
  }

  @Override
  public long maxDelay(final TimeUnit timeUnit) {
    return delegate.maxDelay(timeUnit);
  }

  @Override
  public long delay(final long errorCount, final TimeUnit timeUnit) {
    final long delay = delegate.delay(errorCount, timeUnit);
    final long delayMillis = timeUnit.toMillis(Math.max(0, delay));
    tracker.claim(errorCount, delayMillis, System.nanoTime());
    return delay;
  }
}
