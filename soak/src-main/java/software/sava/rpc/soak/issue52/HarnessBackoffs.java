package software.sava.rpc.soak.issue52;

import software.sava.services.core.remote.call.Backoff;

import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/// The three reconnect policies the workloads drive managers with (DESIGN.md §10). Factories
/// rather than constants because ravina's implementations are stateless value objects and a
/// fresh instance per engine keeps the classifier's sampling from ever sharing state.
public final class HarnessBackoffs {

  private HarnessBackoffs() {
  }

  /// The representative policy: positive, escalating, seconds-scale at the top.
  public static Backoff exponential250to30s() {
    return Backoff.exponential(MILLISECONDS, 250, 30_000);
  }

  /// Zero for the first failure, then the exponential value: passes through the vulnerable
  /// state exactly once per failure streak. Harness-defined because no ravina factory yields a
  /// zero first step followed by growth (`linear(0, …)` would divide by zero in its guard).
  public static Backoff zeroInitialEscalating() {
    return new ZeroInitialEscalating(exponential250to30s());
  }

  /// The forced-reproduction policy the manager's javadoc discourages.
  public static Backoff constantZero() {
    return Backoff.single(MILLISECONDS, 0);
  }

  private static final class ZeroInitialEscalating implements Backoff {

    private final Backoff escalating;

    private ZeroInitialEscalating(final Backoff escalating) {
      this.escalating = escalating;
    }

    @Override
    public TimeUnit timeUnit() {
      return MILLISECONDS;
    }

    @Override
    public long initialDelay(final TimeUnit timeUnit) {
      return 0;
    }

    @Override
    public long maxDelay(final TimeUnit timeUnit) {
      return escalating.maxDelay(timeUnit);
    }

    @Override
    public long delay(final long errorCount, final TimeUnit timeUnit) {
      return errorCount <= 1 ? 0 : escalating.delay(errorCount, timeUnit);
    }
  }
}
