package software.sava.rpc.soak.issue52;

/// One accepted manager claim: one `Backoff.delay(errorCount, unit)` call observed through the
/// harness `RecordingBackoff`. The manager calls its backoff exactly once per accepted claim,
/// after the locked claim and after cancelling its private copy of the attempt future, so the
/// call is the claim (attribution.md manager_state_exposure).
///
/// Columns are exactly DESIGN.md §10 `claims.tsv`.
public record ClaimRecord(long claimSeq,
                          String engine,
                          long errorCount,
                          long delayMillis,
                          Route route,
                          long originOrdinal,
                          OriginQuality originQuality,
                          long currentOrdinal,
                          boolean insideBuildCancel,
                          String thread,
                          long nanoTime) {

  /// Which manager path produced the claim. FUTURE is `connectionAttemptFailed` (inside a
  /// harness build cancel or a build-completion frame); CALLBACK is the transport callback
  /// route following a retirement abort on the same thread; UNROUTED means no harness frame
  /// held an origin (a `connect()` that returned null or threw, or an attempt that never
  /// reached `buildAsync`).
  public enum Route {
    FUTURE,
    CALLBACK,
    UNROUTED
  }

  /// FRAME: the origin came from an explicit harness frame; LAZY: from a lazily created frame
  /// on a thread with no harness entry point (the engine's check loop); UNKNOWN: no origin.
  public enum OriginQuality {
    FRAME,
    LAZY,
    UNKNOWN
  }

  public static String tsvHeader() {
    return "claimSeq\tengine\terrorCount\tdelayMillis\troute\toriginOrdinal\toriginQuality"
        + "\tcurrentOrdinal\tinsideBuildCancel\tthread\tnanoTime";
  }

  public String toTsv() {
    final var sb = new StringBuilder(160);
    toTsv(sb);
    return sb.toString();
  }

  public void toTsv(final StringBuilder sb) {
    sb.append(claimSeq).append('\t')
        .append(engine).append('\t')
        .append(errorCount).append('\t')
        .append(delayMillis).append('\t')
        .append(route).append('\t')
        .append(originOrdinal).append('\t')
        .append(originQuality).append('\t')
        .append(currentOrdinal).append('\t')
        .append(insideBuildCancel).append('\t')
        .append(RetirementRecord.tsvSafe(thread, 64)).append('\t')
        .append(nanoTime);
  }
}
