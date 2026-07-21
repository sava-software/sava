package software.sava.rpc.json.http.ws;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/// Captures scheduled tasks with their delays without running them, so a test
/// steps the clock and runs a deferred connect deliberately instead of waiting
/// it out. The returned handle is null: the websocket never keeps it.
final class RecordingScheduler extends AbstractExecutorService implements ScheduledExecutorService {

  record Deferred(Runnable task, long delay, TimeUnit unit) {
  }

  final List<Deferred> deferred = new ArrayList<>();
  boolean shutdown;

  @Override
  public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
    deferred.add(new Deferred(command, delay, unit));
    return null;
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
    deferred.add(new Deferred(command, 0, TimeUnit.MILLISECONDS));
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
