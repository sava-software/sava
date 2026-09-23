package software.sava.rpc.soak.issue52;

import software.sava.rpc.soak.issue52.RetirementRecord.CallbackRoute;
import software.sava.rpc.soak.issue52.RetirementRecord.CauseClass;
import software.sava.rpc.soak.issue52.RetirementRecord.FutureRoute;
import software.sava.rpc.soak.issue52.RetirementRecord.ThreadKind;
import software.sava.rpc.soak.issue52.RetirementRecord.Verdict;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// The #52 detection rule as a pure function over the rows the tracker emitted
/// (attribution.md detection_rule). Kept free of engine and manager types on purpose: the
/// same code judges synthetic frames in `selfTest()`, the self-test's real deliveries, and a
/// soak's TSV rows read back by the report, so a disagreement between what was captured and
/// what was concluded cannot hide in two implementations.
///
/// Per record `R` with origin `A`: `C_f` is an accepted claim while `insideBuildCancel` (the
/// future route firing inside `retireConnection`'s `build.cancel(true)`), `C_c` an accepted
/// claim after it cleared and before the harness handler returned (the callback route).
/// `doubleClaim = C_f ∧ C_c`. The design's `misattributed = doubleClaim` is split here into
/// explained (a successor attempt existed when `C_c` landed, so the manager demonstrably charged
/// attempt B) and unexplained (none did: a harness bug or a changed manager). Only the explained
/// ones count toward the trigger; the unexplained ones are a reviewer-stop and make the verdict
/// INCONCLUSIVE, never a finding.
public final class RetirementDetector {

  private RetirementDetector() {
  }

  static boolean futureClaimed(final RetirementRecord r) {
    return r.futureRoute() == FutureRoute.ACCEPTED;
  }

  static boolean callbackClaimed(final RetirementRecord r) {
    return r.callbackAccepted();
  }

  static boolean doubleClaim(final RetirementRecord r) {
    return futureClaimed(r) && callbackClaimed(r);
  }

  /// Did a successor attempt exist when the second claim landed? Two independent observations,
  /// because the cheaper one has a blind spot the other does not:
  ///
  /// - `successorBuiltInsideGap` scans the tracker's attempt MAP for a `buildAsync` timestamp
  ///   inside the gap. `AttemptTracker.newAttempt` bumps the ordinal counter first and inserts
  ///   into that map only after the `Attempt` is constructed, so a successor whose insert has
  ///   not landed (or not yet become visible to the reading thread) is invisible to the scan
  ///   however the timestamps compare. `nextAttemptOrdinal` reads the same map and shares the
  ///   blind spot; it is a diagnostic, not part of this judgment.
  /// - `currentOrdinalAtCallbackClaim` is the monotonic counter that the second claim ITSELF
  ///   read, at the instant it was recorded. Above the origin it is direct proof that a later
  ///   attempt had already been allocated, which is the property "explained" means: there was
  ///   something else for the manager to charge.
  ///
  /// So the ordinal decides and the gap scan only adds to it. Observed 2026-09-22 on
  /// `I1-natural`: origin 124's callback claim read ordinal 125 while the map scan still found
  /// nothing above 124, and the row was called a harness defect for it.
  static boolean successorObserved(final boolean successorBuiltInsideGap,
                                   final long currentOrdinalAtCallbackClaim,
                                   final long originOrdinal) {
    return successorBuiltInsideGap || currentOrdinalAtCallbackClaim > originOrdinal;
  }

  static boolean successorObserved(final RetirementRecord r) {
    return successorObserved(r.successorBuiltInsideGap(), r.currentOrdinalAtCallbackClaim(), r.originOrdinal());
  }

  static boolean unexplained(final RetirementRecord r) {
    return doubleClaim(r) && !successorObserved(r);
  }

  static boolean misattributed(final RetirementRecord r) {
    return doubleClaim(r) && successorObserved(r);
  }

  /// `C_f ∧ retryDelay ≤ gap`: the retry was due inside the same-thread gap, so a successor could
  /// have been installed whether or not the retry thread won the race.
  static boolean opportunity(final RetirementRecord r) {
    return futureClaimed(r)
        && r.gapFutureToCallbackUs() >= 0
        && r.retryDelayMs() >= 0
        && r.retryDelayMs() * 1_000L <= r.gapFutureToCallbackUs();
  }

  /// The verdict the tracker stamps on a row at delivery, derived from the same predicates the
  /// summary uses, so a row's verdict and the run's totals cannot disagree. `successorObserved`
  /// must be [#successorObserved(boolean, long, long)] over the very fields being written into
  /// that row, or the stamped verdict and a later re-read of the TSV would part company.
  static Verdict verdictFor(final CauseClass cause,
                            final CallbackRoute callbackRoute,
                            final boolean futureClaimed,
                            final boolean callbackClaimed,
                            final boolean successorObserved,
                            final long retryDelayMs,
                            final long gapUs) {
    if (cause == CauseClass.INSTANCE_DEATH) {
      return Verdict.INSTANCE_DEATH;
    }
    if (callbackRoute == CallbackRoute.UNOBSERVED && cause != CauseClass.CANCELLED_BEFORE_INSTALL) {
      return Verdict.UNOBSERVED_DELIVERY;
    }
    if (futureClaimed && callbackClaimed) {
      return successorObserved ? Verdict.DOUBLE_CLAIM_MISATTRIBUTED : Verdict.DOUBLE_CLAIM_UNEXPLAINED;
    }
    if (futureClaimed) {
      final boolean opportunity = gapUs >= 0 && retryDelayMs >= 0 && retryDelayMs * 1_000L <= gapUs;
      return opportunity ? Verdict.OPPORTUNITY_NOT_REALIZED : Verdict.FENCED_CORRECTLY;
    }
    return Verdict.ATTRIBUTED_SINGLE_CLAIM;
  }

  public static Issue52Summary evaluate(final List<RetirementRecord> records,
                                        final List<ClaimRecord> claims,
                                        final BackoffClass backoffClass,
                                        final boolean forced) {
    long retirements = 0;
    long futureSettled = 0;
    long insideAdopt = 0;
    long opportunities = 0;
    long doubleClaims = 0;
    long misattributed = 0;
    long unexplained = 0;
    long instanceDeaths = 0;
    long unobserved = 0;
    long cancelledBeforeInstall = 0;
    long unblockedRetirements = 0;
    long unblockedMisattributed = 0;
    final var gaps = new ArrayList<Long>();
    long retryMarginMin = Long.MAX_VALUE;
    boolean anyMargin = false;
    for (final var r : records) {
      ++retirements;
      if (r.causeClass() == CauseClass.INSTANCE_DEATH) {
        ++instanceDeaths;
      }
      if (r.causeClass() == CauseClass.CANCELLED_BEFORE_INSTALL) {
        ++cancelledBeforeInstall;
      }
      if (r.verdict() == Verdict.UNOBSERVED_DELIVERY) {
        ++unobserved;
      }
      if (r.buildCancelObserved() && !r.buildWasDoneAtCancel()) {
        ++futureSettled;
      }
      if (r.threadKind() == ThreadKind.ADOPTING) {
        ++insideAdopt;
      }
      if (opportunity(r)) {
        ++opportunities;
      }
      final boolean unblocked = r.raceUnblocked();
      if (unblocked) {
        ++unblockedRetirements;
      }
      if (doubleClaim(r)) {
        ++doubleClaims;
        if (unexplained(r)) {
          ++unexplained;
        } else {
          ++misattributed;
          if (unblocked) {
            ++unblockedMisattributed;
          }
        }
      }
      if (futureClaimed(r) && r.gapFutureToCallbackUs() >= 0) {
        gaps.add(r.gapFutureToCallbackUs());
        if (r.retryDelayMs() >= 0) {
          final long margin = r.retryDelayMs() * 1_000L - r.gapFutureToCallbackUs();
          retryMarginMin = Math.min(retryMarginMin, margin);
          anyMargin = true;
        }
      }
    }
    long claimCount = 0;
    long unrouted = 0;
    long originUnknown = 0;
    for (final var c : claims) {
      ++claimCount;
      if (c.route() == ClaimRecord.Route.UNROUTED) {
        ++unrouted;
      }
      if (c.originQuality() == ClaimRecord.OriginQuality.UNKNOWN) {
        ++originUnknown;
      }
    }
    final long[] sorted = new long[gaps.size()];
    for (int i = 0; i < sorted.length; ++i) {
      sorted[i] = gaps.get(i);
    }
    Arrays.sort(sorted);
    final boolean triggerMet = misattributed > 0 && !forced && backoffClass == BackoffClass.POSITIVE_ESCALATING;
    final String verdict;
    if (triggerMet) {
      verdict = Issue52Summary.VERDICT_REVISIT;
    } else if (retirements == 0 || unexplained > 0 || unobserved > 0) {
      verdict = Issue52Summary.VERDICT_INCONCLUSIVE;
    } else {
      verdict = Issue52Summary.VERDICT_KEEP_DEFERRED;
    }
    return new Issue52Summary(
        retirements, futureSettled, insideAdopt, opportunities, doubleClaims, misattributed, unexplained,
        instanceDeaths, unobserved, cancelledBeforeInstall,
        claimCount, unrouted, originUnknown,
        percentile(sorted, 0.50), percentile(sorted, 0.99), sorted.length == 0 ? -1 : sorted[sorted.length - 1],
        anyMargin ? retryMarginMin : -1,
        // Percent over the retirements whose race was not blocked, which is what the
        // `i52_natural_win_rate` metric means; NaN, never 0, when nothing was left unblocked,
        // so "never measured" cannot read as "measured, and it never happened".
        unblockedRetirements == 0 ? Double.NaN : unblockedMisattributed * 100d / unblockedRetirements,
        claimCount == 0 ? 0.0 : 100.0 * unrouted / claimCount,
        claimCount == 0 ? 0.0 : 100.0 * originUnknown / claimCount,
        backoffClass, forced, triggerMet, verdict
    );
  }

  private static long percentile(final long[] sorted, final double p) {
    if (sorted.length == 0) {
      return -1;
    }
    final int index = (int) Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1);
    return sorted[Math.max(0, index)];
  }

  /// Synthetic-frame unit test of the rule (attribution.md self_test_spec, "Detector unit
  /// test"). Also the gate `Main` runs at STARTUP: it exercises nothing but arithmetic and
  /// enum plumbing, so it finishes in well under the 5 ms budget and a failure means the
  /// detector, not the run, is broken.
  public static boolean selfTest() {
    try {
      final var single = row(1, FutureRoute.NONE_ALREADY_SETTLED, CallbackRoute.ON_CLOSE_ACCEPTED, false, 250, -1);
      final var doubleExplained = row(2, FutureRoute.ACCEPTED, CallbackRoute.ON_ERROR_ACCEPTED, true, 0, 40);
      final var doubleUnexplained = row(3, FutureRoute.ACCEPTED, CallbackRoute.ON_ERROR_ACCEPTED, false, 0, 40);
      // The attempt map never showed the successor, but the second claim itself read an ordinal
      // above the origin: explained, and the case the gap scan alone gets wrong.
      final var doubleByOrdinal = row(7, FutureRoute.ACCEPTED, CallbackRoute.ON_ERROR_ACCEPTED, false, 0, 40, 8);
      final var fencedZero = row(4, FutureRoute.ACCEPTED, CallbackRoute.ON_ERROR_FENCED, false, 0, 50);
      final var fenced250 = row(5, FutureRoute.ACCEPTED, CallbackRoute.ON_ERROR_FENCED, false, 250, 50);
      final var death = row(6, FutureRoute.NONE_ALREADY_SETTLED, CallbackRoute.ON_ERROR_FENCED, false, -1, -1)
          .toBuilder();
      death.causeClass = CauseClass.INSTANCE_DEATH;
      death.verdict = Verdict.INSTANCE_DEATH;
      final var deathRow = death.build();

      boolean ok = !doubleClaim(single) && !opportunity(single) && !misattributed(single);
      ok &= doubleClaim(doubleExplained) && misattributed(doubleExplained) && !unexplained(doubleExplained);
      ok &= doubleClaim(doubleUnexplained) && unexplained(doubleUnexplained) && !misattributed(doubleUnexplained);
      ok &= doubleClaim(doubleByOrdinal) && misattributed(doubleByOrdinal) && !unexplained(doubleByOrdinal);
      ok &= doubleByOrdinal.verdict() == Verdict.DOUBLE_CLAIM_MISATTRIBUTED;
      // the ordinal half is strictly "above the origin": equal or unknown (-1) is not evidence
      ok &= !successorObserved(false, 8, 8) && !successorObserved(false, -1, 3) && successorObserved(false, 9, 8);
      ok &= opportunity(fencedZero) && !opportunity(fenced250);
      ok &= verdictFor(CauseClass.JDK_ERROR, CallbackRoute.ON_ERROR_ACCEPTED, true, true, true, 0, 40)
          == Verdict.DOUBLE_CLAIM_MISATTRIBUTED;
      ok &= verdictFor(CauseClass.JDK_ERROR, CallbackRoute.ON_ERROR_ACCEPTED, true, true, false, 0, 40)
          == Verdict.DOUBLE_CLAIM_UNEXPLAINED;
      ok &= verdictFor(CauseClass.JDK_ERROR, CallbackRoute.ON_ERROR_FENCED, true, false, false, 0, 50)
          == Verdict.OPPORTUNITY_NOT_REALIZED;
      ok &= verdictFor(CauseClass.JDK_ERROR, CallbackRoute.ON_ERROR_FENCED, true, false, false, 250, 50)
          == Verdict.FENCED_CORRECTLY;
      ok &= verdictFor(CauseClass.JDK_CLOSE, CallbackRoute.ON_CLOSE_ACCEPTED, false, true, false, 250, -1)
          == Verdict.ATTRIBUTED_SINGLE_CLAIM;
      ok &= verdictFor(CauseClass.INSTANCE_DEATH, CallbackRoute.ON_ERROR_FENCED, false, false, false, -1, -1)
          == Verdict.INSTANCE_DEATH;
      ok &= verdictFor(CauseClass.JDK_ERROR, CallbackRoute.UNOBSERVED, false, false, false, -1, -1)
          == Verdict.UNOBSERVED_DELIVERY;

      final var rows = List.of(single, doubleExplained, doubleUnexplained, doubleByOrdinal, fencedZero, fenced250, deathRow);
      final var claims = List.of(
          new ClaimRecord(1, "e", 1, 0, ClaimRecord.Route.FUTURE, 2, ClaimRecord.OriginQuality.FRAME, 2, true, "t", 1),
          new ClaimRecord(2, "e", 2, 0, ClaimRecord.Route.CALLBACK, 2, ClaimRecord.OriginQuality.FRAME, 3, false, "t", 2),
          new ClaimRecord(3, "e", 1, 0, ClaimRecord.Route.UNROUTED, -1, ClaimRecord.OriginQuality.UNKNOWN, 3, false, "t", 3)
      );
      final var representative = evaluate(rows, claims, BackoffClass.POSITIVE_ESCALATING, false);
      ok &= representative.retirements() == 7
          && representative.doubleClaims() == 3
          && representative.misattributed() == 2
          && representative.doubleClaimsUnexplained() == 1
          && representative.opportunities() == 4
          && representative.instanceDeaths() == 1
          && representative.claims() == 3
          && representative.claimsUnrouted() == 1
          && representative.originUnknown() == 1
          && representative.gapMaxUs() == 50
          && representative.retryMarginMinUs() == -50
          && representative.triggerMet()
          && Issue52Summary.VERDICT_REVISIT.equals(representative.verdict());
      final var forcedZero = evaluate(rows, claims, BackoffClass.CONSTANT_ZERO, true);
      ok &= !forcedZero.triggerMet() && Issue52Summary.VERDICT_INCONCLUSIVE.equals(forcedZero.verdict());
      final var quiet = evaluate(List.of(single, fenced250), List.of(), BackoffClass.POSITIVE_ESCALATING, false);
      ok &= !quiet.triggerMet()
          && Issue52Summary.VERDICT_KEEP_DEFERRED.equals(quiet.verdict())
          && quiet.retryMarginMinUs() == 249_950;
      final var empty = evaluate(List.of(), List.of(), BackoffClass.POSITIVE_ESCALATING, false);
      ok &= Issue52Summary.VERDICT_INCONCLUSIVE.equals(empty.verdict()) && empty.gapP99Us() == -1;

      ok &= BackoffClass.classify(new long[]{0, 0, 0, 0, 0, 0, 0, 0}) == BackoffClass.CONSTANT_ZERO;
      ok &= BackoffClass.classify(new long[]{0, 500, 1000, 2000, 4000, 8000, 16000, 30000}) == BackoffClass.ZERO_INITIAL_ESCALATING;
      ok &= BackoffClass.classify(new long[]{250, 500, 1000, 2000, 4000, 8000, 16000, 30000}) == BackoffClass.POSITIVE_ESCALATING;
      ok &= BackoffClass.classify(new long[]{250, 250, 250, 250, 250, 250, 250, 250}) == BackoffClass.POSITIVE_CONSTANT;
      ok &= BackoffClass.classify(HarnessBackoffs.constantZero()) == BackoffClass.CONSTANT_ZERO;
      ok &= BackoffClass.classify(HarnessBackoffs.zeroInitialEscalating()) == BackoffClass.ZERO_INITIAL_ESCALATING;
      ok &= BackoffClass.classify(HarnessBackoffs.exponential250to30s()) == BackoffClass.POSITIVE_ESCALATING;
      return ok;
    } catch (final RuntimeException e) {
      return false;
    }
  }

  /// A row whose callback claim, if it had one, read no ordinal above the origin — so the gap
  /// scan is its only successor evidence.
  private static RetirementRecord row(final long id,
                                      final FutureRoute futureRoute,
                                      final CallbackRoute callbackRoute,
                                      final boolean successorInsideGap,
                                      final long retryDelayMs,
                                      final long gapUs) {
    return row(id, futureRoute, callbackRoute, successorInsideGap, retryDelayMs, gapUs, -1);
  }

  private static RetirementRecord row(final long id,
                                      final FutureRoute futureRoute,
                                      final CallbackRoute callbackRoute,
                                      final boolean successorInsideGap,
                                      final long retryDelayMs,
                                      final long gapUs,
                                      final long currentOrdinalAtCallbackClaim) {
    final var b = new RetirementRecord.Builder();
    b.retirementId = id;
    b.engine = "synthetic";
    b.originOrdinal = id;
    b.causeClass = callbackRoute == CallbackRoute.ON_CLOSE_ACCEPTED ? CauseClass.JDK_CLOSE : CauseClass.JDK_ERROR;
    b.futureRoute = futureRoute;
    b.callbackRoute = callbackRoute;
    b.buildCancelObserved = futureRoute != FutureRoute.NONE_ALREADY_SETTLED;
    b.buildWasDoneAtCancel = false;
    b.successorBuiltInsideGap = successorInsideGap;
    b.currentOrdinalAtCallbackClaim = currentOrdinalAtCallbackClaim;
    b.retryDelayMs = retryDelayMs;
    b.gapFutureToCallbackUs = gapUs;
    b.retryMarginUs = gapUs < 0 || retryDelayMs < 0 ? -1 : retryDelayMs * 1_000L - gapUs;
    final boolean cF = futureRoute == FutureRoute.ACCEPTED;
    final boolean cC = callbackRoute == CallbackRoute.ON_CLOSE_ACCEPTED || callbackRoute == CallbackRoute.ON_ERROR_ACCEPTED;
    b.claimCount = (cF ? 1 : 0) + (cC ? 1 : 0);
    b.verdict = verdictFor(b.causeClass, callbackRoute, cF, cC,
        successorObserved(successorInsideGap, currentOrdinalAtCallbackClaim, b.originOrdinal),
        retryDelayMs, gapUs);
    return b.build();
  }
}
