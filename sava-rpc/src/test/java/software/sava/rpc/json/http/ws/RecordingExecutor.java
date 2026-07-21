package software.sava.rpc.json.http.ws;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/// Captures submitted tasks without running them: with this injected, the
/// websocket has no background check-loop thread, so clock-stepped assertions
/// cannot race it. A test that wants the loop runs a captured task itself.
final class RecordingExecutor extends AbstractExecutorService {

  final List<Runnable> tasks = new ArrayList<>();
  boolean shutdown;

  @Override
  public void execute(final Runnable command) {
    tasks.add(command);
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
