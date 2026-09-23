package software.sava.rpc.soak.issue52;

import software.sava.services.core.remote.call.Backoff;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/// The four reconnect-policy classes the #52 verdict is conditioned on (attribution.md
/// detection_rule). Classified by sampling `delay(1..8)` rather than by factory identity, so a
/// caller-supplied `Backoff` of any implementation lands in the class its numbers put it in.
///
/// `POSITIVE_ESCALATING` is the representative class and the only one in which a misattribution
/// meets the issue's revisit trigger; `CONSTANT_ZERO` is the forced-reproduction class the
/// manager's javadoc discourages.
public enum BackoffClass {
  CONSTANT_ZERO,
  ZERO_INITIAL_ESCALATING,
  POSITIVE_ESCALATING,
  POSITIVE_CONSTANT;

  /// How many error counts are sampled. Eight covers every factory ravina ships past the point
  /// where an escalating policy has demonstrably grown.
  static final int SAMPLES = 8;

  public static BackoffClass classify(final Backoff backoff) {
    final long[] delays = new long[SAMPLES];
    for (int k = 1; k <= SAMPLES; ++k) {
      delays[k - 1] = Math.max(0, backoff.delay(k, MILLISECONDS));
    }
    return classify(delays);
  }

  /// Package-private for the detector self-test, which feeds literal samples.
  static BackoffClass classify(final long[] delays) {
    final long first = delays[0];
    boolean anyPositive = false;
    boolean anyGrowth = false;
    for (int i = 0; i < delays.length; ++i) {
      if (delays[i] > 0) {
        anyPositive = true;
      }
      if (delays[i] > first) {
        anyGrowth = true;
      }
    }
    if (first == 0) {
      // A zero initial delay is the vulnerable state; whether it stays there is the question.
      return anyPositive ? ZERO_INITIAL_ESCALATING : CONSTANT_ZERO;
    }
    // A positive policy that ever exceeds its initial delay is reported as escalating even when
    // it is not monotone: the trigger asks whether the policy grows past the gap, not whether it
    // grows tidily.
    return anyGrowth ? POSITIVE_ESCALATING : POSITIVE_CONSTANT;
  }
}
