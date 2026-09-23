package software.sava.rpc.soak.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/// `jfr-metrics.properties`: the flat `key=value` file the runner's verdict reads. It is the
/// ONLY machine-readable output of this report — `summary.md` and `jfr-report.md` are for people,
/// and `DESIGN.md` §14 forbids the verdict from parsing markdown.
///
/// Every key of `DESIGN.md` §11 is written on every run, whether or not the artifact behind it
/// existed, because a verdict that reads a missing key cannot tell "the harness did not measure
/// this" from "the shell quoting ate it". The default is `0` for a count or a flag, `n/a` for a
/// name or a ratio whose denominator was zero.
///
/// Where a default could invent a FAIL it is chosen to be quiet instead: an unmeasured slope is
/// `0` (the run gets a NOTE in the markdown, not a failed gate) and an undefined fault fidelity
/// is `n/a` (the gate is `< 0.90`, and a zero there would fail every run that scheduled no
/// faults). Where a default could hide one it is chosen to be loud: counts start at 0 and are
/// only ever raised by an observation.
final class Metrics {

  /// The §11 key list, in §11's order, each with the value written when nothing observed it.
  /// Order is preserved into the file so a diff of two runs lines up. `fault_unreached`,
  /// `fault_kinds_unapplied`, `heap_after_gc_limit_kib_per_hour` and `i52_unobserved_delivery`
  /// are the keys the runner's verdict reads beyond the original list; they were added when the
  /// report was wired. The heap limit carries the noise-floor rule (`max(limit, floor / hours)`)
  /// to the runner so a three-minute run is not failed on a slope the window cannot resolve.
  ///
  /// Four later additions, each because a number that only existed in prose could not be read by
  /// anything: `decoding_failures` and `decoding_failures_unexplained` reconcile
  /// `rpc.completed.decode` against the body-corrupting faults the peer actually served, and
  /// `i52_claims_rows`/`i52_retirement_rows` carry the TSV row counts beside the `i52_claims` and
  /// `i52_retirements` totals, so row loss — the thing that makes `issue52.md` refuse to speak —
  /// is machine-readable rather than a sentence. They are additions to `DESIGN.md` §11's key list.
  private static final String[][] KEYS = {
      {"events_total", "0"},
      {"ring_coverage_pct", "n/a"},
      {"chunks", "0"},
      {"old_object_samples", "0"},
      {"old_object_samples_with_root", "0"},
      {"old_object_top_types", "n/a"},
      {"method_filters_requested", "0"},
      {"method_filters_resolved", "0"},
      {"socket_event_share_pct", "n/a"},
      {"exception_throttle_saturated", "0"},
      {"submit_failed", "0"},
      {"pinned", "0"},
      {"pinned_max_ms", "0.0"},
      {"errors_thrown", "0"},
      {"thread_starts", "0"},
      {"thread_ends", "0"},
      {"vt_started", "0"},
      {"vt_ended", "0"},
      {"thread_dump_stuck_threads", "0"},
      {"dominant_thread_flag", "0"},
      {"dominant_thread", "n/a"},
      {"dominant_thread_share_pct", "0"},
      {"execution_samples", "0"},
      {"heap_after_gc_slope_kib_per_hour", "0"},
      {"heap_after_gc_limit_kib_per_hour", "n/a"},
      {"heap_after_gc_first_kb", "0"},
      {"heap_after_gc_last_kb", "0"},
      {"gc_count", "0"},
      {"gc_pause_max_ms", "0.0"},
      {"fault_fidelity", "n/a"},
      {"fault_no_target", "0"},
      {"fault_unreached", "0"},
      {"fault_kinds_unapplied", ""},
      {"decoding_failures", "0"},
      {"decoding_failures_unexplained", "0"},
      {"recovery_over_budget", "0"},
      {"swallowed_ping_probe_retirements", "0"},
      {"swallowed_ping_other_retirements", "0"},
      {"tail_lag_max_ms", "0"},
      {"recovery_judged_under_tail_lag", "0"},
      {"replay_rows_late", "0"},
      {"i52_retirements", "0"},
      {"i52_retirement_rows", "0"},
      {"i52_claims", "0"},
      {"i52_claims_rows", "0"},
      {"i52_double_claims", "0"},
      {"i52_double_claims_unexplained", "0"},
      {"i52_misattributed", "0"},
      {"i52_opportunities", "0"},
      {"i52_gap_p99_us", "n/a"},
      {"i52_retry_margin_min_us", "n/a"},
      {"i52_natural_win_rate", "n/a"},
      {"i52_claims_unrouted_pct", "0"},
      {"i52_origin_unknown_pct", "0"},
      {"i52_trigger_met", "0"},
      {"i52_verdict", "INCONCLUSIVE"},
      {"i52_unobserved_delivery", "0"}
  };

  private final Map<String, String> values = new LinkedHashMap<>(KEYS.length * 2);

  Metrics() {
    for (final var key : KEYS) {
      values.put(key[0], key[1]);
    }
  }

  /// Rejects a key outside §11 loudly: a metric the verdict never reads is a silent no-op, and
  /// the only way that is ever noticed is when the gate it was meant to feed never fires.
  void put(final String key, final String value) {
    if (!values.containsKey(key)) {
      throw new IllegalArgumentException("not a DESIGN.md §11 metric key: " + key);
    }
    // a newline would forge a second key when the file is read back; an '=' inside the value is
    // harmless, because every reader splits on the FIRST one (old_object_top_types is 'type=count')
    values.put(key, value == null || value.isBlank() ? "n/a" : value.replace('\n', ' ').replace('\r', ' '));
  }

  /// A list-valued metric whose empty value MEANS empty: `fault_kinds_unapplied` is read by the
  /// runner as "no reason to fail" when blank, so the `n/a` substitution of [#put] would turn
  /// "nothing was unapplied" into a FAIL reason.
  void putList(final String key, final String value) {
    if (!values.containsKey(key)) {
      throw new IllegalArgumentException("not a DESIGN.md §11 metric key: " + key);
    }
    values.put(key, value == null ? "" : value.replace('\n', ' ').replace('\r', ' '));
  }

  void put(final String key, final long value) {
    put(key, Long.toString(value));
  }

  void put(final String key, final boolean flag) {
    put(key, flag ? "1" : "0");
  }

  /// A rate written with a fixed number of decimals, or `n/a` when the denominator was zero —
  /// never `NaN`, `Infinity` or `-0.0`, none of which a shell comparison handles.
  void putRate(final String key, final double value, final int decimals) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      put(key, "n/a");
    } else {
      put(key, String.format(Locale.ROOT, "%." + decimals + 'f', value + 0d));
    }
  }

  String get(final String key) {
    return values.get(key);
  }

  Map<String, String> asMap() {
    return java.util.Collections.unmodifiableMap(values);
  }

  void write(final Path path) throws IOException {
    final var sb = new StringBuilder(values.size() * 40);
    values.forEach((key, value) -> sb.append(key).append('=').append(value).append('\n'));
    Files.writeString(path, sb, StandardCharsets.UTF_8);
  }
}
