package software.sava.rpc.soak.report;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/// Least-squares trend of a memory series in KiB per hour: the retention lens of the soak.
///
/// Four rules, all of them about not calling noise a leak:
///
/// 1. **Warm-up is dropped.** Heap commit, class loading and the JIT all ramp for the first
///    minutes of any run; a fit that includes them reports a slope that is really a start-up
///    transient. Every sample within the warm-up window of the FIRST sample is dropped, and the
///    window is `max(SOAK_WARMUP_SECONDS, 60 s, span / 10)` so a four-minute validate run and an
///    eight-hour campaign both drop a sensible prefix. That expression is the rule `soak.sh`'s
///    `slope_fit`/`nmt_slope` apply to the same two files: the gate and this report must fit the
///    same samples, or the runner fails a run on a number the report never printed.
/// 2. **A differently-taken sample is not part of the series.** `nmt.csv`'s `baseline` row is a
///    `jcmd VM.native_memory baseline` snapshot taken before the monitor loop, not one of its
///    diffs, so it is excluded — `soak.sh`'s `nmt_slope` excludes it by the same `label` column.
/// 3. **A slope under the noise floor is not a finding.** A short run can show a large rate from
///    a small absolute move, so the limit actually applied is `max(limit, floor / hours)` — the
///    floor is an absolute KiB budget over the measured window, and it dominates the comparison
///    until the window is long enough for the per-hour limit to be the tighter of the two.
/// 4. **Fewer than three samples AFTER the drop is "not measured", never zero and never a fit of
///    the ramp.** Reporting 0 KiB/h for an unmeasurable series would read as a clean result, and
///    falling back to the whole series — which this fit used to do — reports the JVM's start-up
///    ramp as retention: on a 90-second control run the ramp is the entire run.
final class Slopes {

  static final long MIN_WARMUP_SECONDS = 60L;
  static final int MIN_POINTS = 3;
  /// `nmt.csv`'s `label` value for the row [#nmt] excludes.
  static final String BASELINE_LABEL = "baseline";

  /// The outcome of one fit. [#measured] false means the series could not be fitted at all, and
  /// every renderer must print "not measured" rather than a number.
  record Fit(String name,
             boolean measured,
             int samples,
             int dropped,
             double slopeKibPerHour,
             double firstKib,
             double lastKib,
             double hours,
             double appliedLimitKibPerHour,
             boolean exceeded,
             String note) {

    static Fit notMeasured(final String name, final String note) {
      return new Fit(name, false, 0, 0, 0d, 0d, 0d, 0d, 0d, false, note);
    }

    /// Adds a caller's caveat to whatever the fit already had to say. Appends rather than
    /// replaces, because a fit can both be unmeasurable AND have had rows excluded, and a reader
    /// who is told only one of the two cannot tell what was fitted.
    Fit noting(final String extra) {
      if (extra == null || extra.isBlank()) {
        return this;
      }
      return new Fit(name, measured, samples, dropped, slopeKibPerHour, firstKib, lastKib, hours,
          appliedLimitKibPerHour, exceeded, note.isBlank() ? extra : note + "; " + extra);
    }

    String slope() {
      return measured ? String.format(Locale.ROOT, "%.1f", slopeKibPerHour) : "n/a";
    }

    String describe() {
      if (!measured) {
        return name + ": not measured (" + note + ')';
      }
      // a measured fit prints its caveats too: rows this fit excluded are part of what the number
      // means, and a describe() that dropped them would read as a fit of the whole file
      return String.format(Locale.ROOT,
          "%s: %.1f KiB/h over %.2f h (%d sample(s) after dropping %d warm-up), %.0f -> %.0f KiB, limit applied %.1f KiB/h -> %s",
          name, slopeKibPerHour, hours, samples, dropped, firstKib, lastKib, appliedLimitKibPerHour,
          exceeded ? "OVER" : "within") + (note.isBlank() ? "" : " [" + note + ']');
    }
  }

  /// `rss.csv` is headerless `epoch,rss_kb` (written by `soak.sh` with `ps -o rss=`), so the
  /// column names are supplied rather than read.
  static Fit rss(final Path rssCsv,
                 final double limitKibPerHour,
                 final double noiseFloorKib,
                 final double warmupSeconds) throws IOException {
    return rss(rssCsv, limitKibPerHour, noiseFloorKib, warmupSeconds, Long.MAX_VALUE);
  }

  /// `untilEpochSeconds` bounds the series the way the runner's gate bounds it - STEADY only, so
  /// quiesce, drain and the final dump are not fitted (the report and the verdict used to fit
  /// different windows; review).
  static Fit rss(final Path rssCsv,
                 final double limitKibPerHour,
                 final double noiseFloorKib,
                 final double warmupSeconds,
                 final long untilEpochSeconds) throws IOException {
    final var table = TsvReader.read(rssCsv, ',', List.of("epoch", "rss_kb"));
    final var bounded = new java.util.ArrayList<TsvReader.Row>(table.rows().size());
    for (final var row : table.rows()) {
      if (row.getLong("epoch", Long.MAX_VALUE) <= untilEpochSeconds) {
        bounded.add(row);
      }
    }
    final var series = new TsvReader.Table(table.path(), table.columns(), bounded, table.partialLines());
    return fit("RSS (rss.csv" + (untilEpochSeconds == Long.MAX_VALUE ? "" : ", STEADY only") + ')',
        series, "epoch", "rss_kb", limitKibPerHour, noiseFloorKib, warmupSeconds);
  }

  /// `nmt.csv` carries a header and many columns; the series that means anything is the LAST one
  /// — the committed total less Arena Chunk and Tracing, which both move by megabytes between
  /// consecutive samples with nothing leaked. Taken by name when the header spells it, by
  /// position otherwise, so a renamed column does not silently fit the wrong series.
  ///
  /// The `baseline` row is dropped before the fit. It is a `jcmd VM.native_memory baseline`
  /// snapshot taken once, before the monitor loop starts, and every later row is a diff against
  /// it; fitting the two together fits the step between two different measurements. `soak.sh`'s
  /// `nmt_slope` excludes the same row by the same `label` column, so the gate and this line are
  /// the same number over the same samples.
  static Fit nmt(final Path nmtCsv,
                 final double limitKibPerHour,
                 final double noiseFloorKib,
                 final double warmupSeconds) throws IOException {
    final var table = TsvReader.readCsv(nmtCsv);
    if (table.columns().isEmpty()) {
      return Fit.notMeasured("native memory, gated (nmt.csv)", "no nmt.csv");
    }
    final var epoch = table.has("epoch_s") ? "epoch_s" : table.columns().getFirst();
    final var gated = table.has("gated_committed_kb") ? "gated_committed_kb" : table.columns().getLast();
    // by name when the header spells it, by position otherwise — the label is soak.sh's second
    // column, and a file without one leaves every row in the series rather than guessing
    final var label = table.has("label") ? "label"
        : (table.columns().size() > 1 ? table.columns().get(1) : null);
    final var fittable = new java.util.ArrayList<TsvReader.Row>(table.rows().size());
    int excluded = 0;
    for (final var row : table.rows()) {
      if (label != null && BASELINE_LABEL.equalsIgnoreCase(row.get(label).strip())) {
        ++excluded;
        continue;
      }
      fittable.add(row);
    }
    final var series = new TsvReader.Table(table.path(), table.columns(), fittable, table.partialLines());
    final var fit = fit("native memory, gated (nmt.csv " + gated + ')', series, epoch, gated,
        limitKibPerHour, noiseFloorKib, warmupSeconds);
    return excluded == 0
        ? fit.noting(label == null ? "no label column: no row could be identified as the baseline snapshot" : "")
        : fit.noting(excluded + " `" + BASELINE_LABEL + "` row(s) excluded: a snapshot, not one of the diffs");
  }

  /// The after-GC heap floor, whole-run, from the gauge series the client writes every
  /// `SOAK_GAUGE_SECONDS` — `client.csv`'s `heap_after_gc` beside its `epoch_s`. The ring's own
  /// `jdk.GCHeapSummary` series only covers what the ring still holds, so it is a cross-check
  /// and this is the measurement.
  static Fit heapAfterGc(final Path clientCsv,
                         final double limitKibPerHour,
                         final double noiseFloorKib,
                         final double warmupSeconds) throws IOException {
    final var table = TsvReader.readCsv(clientCsv);
    if (table.columns().isEmpty()) {
      return Fit.notMeasured("heap after GC (client.csv)", "no client.csv");
    }
    if (!table.has("heap_after_gc")) {
      return Fit.notMeasured("heap after GC (client.csv)", "no heap_after_gc column");
    }
    final var epoch = table.has("epoch_s") ? "epoch_s" : table.columns().getFirst();
    // client.csv writes heap_after_gc in BYTES, straight from the GC notification's after-GC
    // usage, and -1 until the first collection has happened; the fit wants KiB and only real
    // samples, so the column is scaled here and the sentinel rows are dropped.
    return fit("heap after GC (client.csv)", table, epoch, "heap_after_gc", limitKibPerHour, noiseFloorKib,
        warmupSeconds, 1024d, true);
  }

  static Fit fit(final String name,
                 final TsvReader.Table table,
                 final String epochColumn,
                 final String valueColumn,
                 final double limitKibPerHour,
                 final double noiseFloorKib,
                 final double warmupSeconds) {
    return fit(name, table, epochColumn, valueColumn, limitKibPerHour, noiseFloorKib, warmupSeconds, 1d, false);
  }

  /// `warmupSeconds` is the run's own `SOAK_WARMUP_SECONDS`; the window actually dropped is
  /// `max(warmupSeconds, 60 s, span / 10)`. `divisor` converts the column's unit to KiB (1024 for
  /// a bytes column); `skipNegative` drops the -1 rows a gauge writes before it has a reading.
  static Fit fit(final String name,
                 final TsvReader.Table table,
                 final String epochColumn,
                 final String valueColumn,
                 final double limitKibPerHour,
                 final double noiseFloorKib,
                 final double warmupSeconds,
                 final double divisor,
                 final boolean skipNegative) {
    if (table.rows().isEmpty()) {
      return Fit.notMeasured(name, table.path() == null ? "artifact missing" : "no rows");
    }
    final var seconds = new java.util.ArrayList<Double>(table.rows().size());
    final var values = new java.util.ArrayList<Double>(table.rows().size());
    for (final var row : table.rows()) {
      final double at = row.getDouble(epochColumn, Double.NaN);
      final double value = row.getDouble(valueColumn, Double.NaN);
      if (Double.isNaN(at) || Double.isNaN(value) || (skipNegative && value < 0d)) {
        continue;
      }
      seconds.add(at);
      values.add(divisor == 1d ? kib(value) : value / divisor);
    }
    if (seconds.size() < MIN_POINTS) {
      return Fit.notMeasured(name, seconds.size() + " usable sample(s), need " + MIN_POINTS);
    }
    final double span = seconds.getLast() - seconds.getFirst();
    // the profile's own warm-up, floored at a minute and at a tenth of the run: soak.sh's
    // slope_fit and nmt_slope compute this expression from SOAK_WARMUP_SECONDS, and the gate and
    // the report have to fit the same samples or the runner fails a run on an unprintable number
    final double warmup = Math.max(Math.max(warmupSeconds, MIN_WARMUP_SECONDS), span / 10d);
    int from = 0;
    while (from < seconds.size() && seconds.get(from) < seconds.getFirst() + warmup) {
      ++from;
    }
    // A run too short to survive its own warm-up drop is NOT MEASURED. Falling back to the whole
    // series - which this fit used to do, so that a validate run printed something - fits the
    // JVM's start-up ramp: heap commit, class loading and the code cache are still climbing, and
    // on a 90-second control run that ramp is the entire file. A number that reports start-up as
    // retention is worse than no number, and it disagrees with soak.sh, which reports n/a here.
    if (seconds.size() - from < MIN_POINTS) {
      return Fit.notMeasured(name, (seconds.size() - from) + " sample(s) of " + seconds.size() + " survive the "
          + String.format(Locale.ROOT, "%.0f", warmup) + " s warm-up drop, need " + MIN_POINTS
          + " — the run is shorter than a fittable window, and fitting the start-up ramp instead would report it"
          + " as retention");
    }
    final int dropped = from;
    final int n = seconds.size() - from;
    double sumX = 0d, sumY = 0d, sumXY = 0d, sumXX = 0d;
    for (int i = from; i < seconds.size(); ++i) {
      final double x = seconds.get(i) - seconds.get(from);
      final double y = values.get(i);
      sumX += x;
      sumY += y;
      sumXY += x * y;
      sumXX += x * x;
    }
    final double denominator = n * sumXX - sumX * sumX;
    final double hours = (seconds.getLast() - seconds.get(from)) / 3600d;
    if (denominator == 0d || hours <= 0d) {
      return Fit.notMeasured(name, "zero-width window over " + n + " sample(s)");
    }
    final double slopePerSecond = (n * sumXY - sumX * sumY) / denominator;
    final double slope = slopePerSecond * 3600d;
    final double applied = Math.max(limitKibPerHour, noiseFloorKib / hours);
    return new Fit(name, true, n, dropped, slope, values.get(from), values.getLast(), hours, applied,
        slope > applied, "");
  }

  /// The gauge writers disagree on units by design — `ps` reports KiB, an MXBean reports bytes.
  /// A value above 2^32 cannot be KiB of a 1 GiB-heap JVM, so it is bytes and is scaled.
  private static double kib(final double value) {
    return value > 4_294_967_296d ? value / 1024d : value;
  }

  private Slopes() {
  }
}
