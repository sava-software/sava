package software.sava.rpc.json.http.ws;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/// Captures scheduled tasks with their delays without running them, so a test
/// steps the clock and runs a deferred connect deliberately instead of waiting
/// it out. Each call returns a distinguishable cancellable handle so ownership
/// and teardown of the scheduled operation remain observable.
final class RecordingScheduler extends AbstractExecutorService implements ScheduledExecutorService {

  static final class Handle implements ScheduledFuture<Object> {

    private final long delayNanos;
    private boolean cancelled;
    private boolean done;

    private Handle(final long delayNanos) {
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
      if (!done) {
        throw new IllegalStateException("recorded task has not run");
      }
      return this;
    }

    @Override
    public Object get(final long timeout, final TimeUnit unit) {
      return get();
    }

    private void ran() {
      done = true;
    }
  }

  record Deferred(Runnable task, long delay, TimeUnit unit, Handle handle) {
  }

  final List<Deferred> deferred = new ArrayList<>();
  boolean shutdown;
  Runnable duringSchedule;

  @Override
  public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
    final var handle = new Handle(unit.toNanos(delay));
    deferred.add(new Deferred(() -> {
      handle.ran();
      command.run();
    }, delay, unit, handle));
    final var duringSchedule = this.duringSchedule;
    this.duringSchedule = null;
    if (duringSchedule != null) {
      duringSchedule.run();
    }
    return handle;
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
    final var handle = new Handle(0);
    deferred.add(new Deferred(() -> {
      handle.ran();
      command.run();
    }, 0, TimeUnit.MILLISECONDS, handle));
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
