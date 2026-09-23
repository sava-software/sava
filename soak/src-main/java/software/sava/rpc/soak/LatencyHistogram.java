package software.sava.rpc.soak;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/// A fixed-size log-scale histogram of latencies in microseconds (5 % bucket width, 1 µs to
/// about 1,000 s) so percentiles cost no per-sample allocation and the gauge sampler can read
/// while workers record.
///
/// The shape is lifted from the http-servers soak harness deliberately: a soak that allocates
/// to measure itself changes the retention numbers it exists to report.
public final class LatencyHistogram {

  private static final double LOG_BASE = Math.log(1.05);
  private static final int BUCKETS = 430;

  private final AtomicLongArray counts;
  private final AtomicLong maxMicros;
  private final AtomicLong recorded;

  LatencyHistogram() {
    this.counts = new AtomicLongArray(BUCKETS);
    this.maxMicros = new AtomicLong();
    this.recorded = new AtomicLong();
  }

  /// Records one sample. Sub-microsecond samples land in the first bucket rather than being
  /// dropped, so the count is always the true number of observations.
  public void record(final long nanos) {
    final long micros = Math.max(1L, nanos / 1_000L);
    final int bucket = Math.min(BUCKETS - 1, (int) (Math.log(micros) / LOG_BASE));
    counts.incrementAndGet(bucket);
    recorded.incrementAndGet();
    maxMicros.accumulateAndGet(micros, Math::max);
  }

  public void recordMicros(final long micros) {
    record(micros * 1_000L);
  }

  public long count() {
    return recorded.get();
  }

  public double maxMillis() {
    return maxMicros.get() / 1_000d;
  }

  /// The upper bound, in milliseconds, of the bucket holding the `percentile`-th sample, or 0
  /// when nothing was recorded. Reported as an upper bound, never interpolated: a 5 % bucket
  /// interpolated to three decimals would read as a precision the instrument does not have.
  public double percentileMillis(final double percentile) {
    long total = 0;
    for (int i = 0; i < BUCKETS; ++i) {
      total += counts.get(i);
    }
    if (total == 0) {
      return 0d;
    }
    final long rank = Math.max(1L, (long) Math.ceil(percentile * total));
    long cumulative = 0;
    for (int i = 0; i < BUCKETS; ++i) {
      cumulative += counts.get(i);
      if (cumulative >= rank) {
        return Math.pow(1.05, i + 1) / 1_000d;
      }
    }
    return Math.pow(1.05, BUCKETS) / 1_000d;
  }
}
