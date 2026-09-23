package software.sava.rpc.soak.issue52;

/// The run-level #52 totals with their denominators (attribution.md report_columns, run
/// summary). Denominators travel with every count because "zero double claims" means nothing
/// without "out of N retirements, M of which settled their build future" — exposure evidence,
/// never proof of impossibility.
///
/// `naturalWinRate` is the percentage of retirements whose race was NOT blocked that ended in a
/// misattribution — every unforced retirement plus the injections
/// [RetirementRecord#raceUnblocked(boolean, String)] admits — and is `NaN` when the run left none
/// of them unblocked. It measures this machine's timing, never the library's behaviour, and it is
/// not the revisit trigger: an injected race can never meet that, however unblocked.
///
/// `-1` for the percentiles and the margin means no record had a future-route claim, so no gap
/// was ever defined.
public record Issue52Summary(long retirements,
                             long futureSettledByRetirement,
                             long retirementsInsideAdopt,
                             long opportunities,
                             long doubleClaims,
                             long misattributed,
                             long doubleClaimsUnexplained,
                             long instanceDeaths,
                             long unobservedDeliveries,
                             long cancelledBeforeInstall,
                             long claims,
                             long claimsUnrouted,
                             long originUnknown,
                             long gapP50Us,
                             long gapP99Us,
                             long gapMaxUs,
                             long retryMarginMinUs,
                             double naturalWinRate,
                             double claimsUnroutedPct,
                             double originUnknownPct,
                             BackoffClass backoffClass,
                             boolean forced,
                             boolean triggerMet,
                             String verdict) {

  public static final String VERDICT_REVISIT = "REVISIT";
  public static final String VERDICT_KEEP_DEFERRED = "KEEP-DEFERRED";
  public static final String VERDICT_INCONCLUSIVE = "INCONCLUSIVE";

  public String verdictLine() {
    return "ISSUE52: " + verdict;
  }

  /// The `i52_*` keys of `jfr-metrics.properties` (DESIGN.md §11), so the report can copy them
  /// without re-deriving the arithmetic.
  public void writeProperties(final StringBuilder sb) {
    sb.append("i52_retirements=").append(retirements).append('\n')
        .append("i52_future_settled_by_retirement=").append(futureSettledByRetirement).append('\n')
        .append("i52_retirements_inside_adopt=").append(retirementsInsideAdopt).append('\n')
        .append("i52_claims=").append(claims).append('\n')
        .append("i52_double_claims=").append(doubleClaims).append('\n')
        .append("i52_double_claims_unexplained=").append(doubleClaimsUnexplained).append('\n')
        .append("i52_misattributed=").append(misattributed).append('\n')
        .append("i52_opportunities=").append(opportunities).append('\n')
        .append("i52_instance_deaths=").append(instanceDeaths).append('\n')
        .append("i52_unobserved_deliveries=").append(unobservedDeliveries).append('\n')
        .append("i52_cancelled_before_install=").append(cancelledBeforeInstall).append('\n')
        .append("i52_gap_p50_us=").append(gapP50Us).append('\n')
        .append("i52_gap_p99_us=").append(gapP99Us).append('\n')
        .append("i52_gap_max_us=").append(gapMaxUs).append('\n')
        .append("i52_retry_margin_min_us=").append(retryMarginMinUs).append('\n')
        .append("i52_natural_win_rate=").append(naturalWinRate).append('\n')
        .append("i52_claims_unrouted_pct=").append(claimsUnroutedPct).append('\n')
        .append("i52_origin_unknown_pct=").append(originUnknownPct).append('\n')
        .append("i52_backoff_class=").append(backoffClass).append('\n')
        .append("i52_forced=").append(forced).append('\n')
        .append("i52_trigger_met=").append(triggerMet).append('\n')
        .append("i52_verdict=").append(verdict).append('\n');
  }
}
