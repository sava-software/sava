package software.sava.rpc.soak.report;

import software.sava.rpc.soak.issue52.RetirementRecord;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// `issue52.md`: the decision evidence GitHub issue #52 asked for, and nothing else.
///
/// The issue is a DECISION RECORD, not a bug report: one websocket retirement is observed twice by
/// ravina's manager (the connect future settles exceptionally, then the close/error callback
/// runs), attribution is the only gap, and the consequences were judged bounded. The agreed
/// trigger to revisit is a misattribution under a POSITIVE, ESCALATING backoff in a run that did
/// not force the race. This file exists to answer that one question with its denominators
/// attached, and to refuse to answer when the capture itself is not trustworthy.
///
/// Three inputs, deliberately of different kinds:
///
/// - `issue52/retirements.tsv` — one row per retirement, the per-record evidence. `forced` and
///   `backoffClass` live ONLY here, so without this file no trigger can be evaluated at all.
/// - `issue52/claims.tsv` — one row per accepted `Backoff.delay(errorCount, unit)` call, which is
///   exactly one claim by the manager. The route and origin quality of each claim are what the
///   attribution gates are computed from.
/// - `counters.properties` — the harness's own whole-run totals. `DESIGN.md` §9 gives counters
///   the totals and the ring the shape, so when a count disagrees with the rows, the counter is
///   reported as authoritative and the disagreement is printed rather than smoothed away.
///
/// The two kinds are never mixed inside one statement. A rate takes both halves from the same
/// writer, because the bounded TSV queue can drop rows (`harness.tsv.dropped`) and a surviving
/// numerator over a whole-run denominator would quietly dilute a gate. A claim about `forced` or
/// `backoffClass` — the trigger, and the sentence that dismisses it — is made only over rows,
/// because no counter carries those columns; where the rows are short of the counters this file
/// reports INCONCLUSIVE rather than speak for the records that were never written.
final class Issue52Report {

  /// A run may not exceed these and still be read as evidence: past them the harness could not
  /// say where its own claims came from, so no verdict about the subject is defensible.
  static final double MAX_ORIGIN_UNKNOWN_PCT = 10d;
  static final double MAX_CLAIMS_UNROUTED_PCT = 5d;
  static final int MAX_TABLE_ROWS = 200;

  static final String REVISIT = "REVISIT";
  static final String KEEP_DEFERRED = "KEEP-DEFERRED";
  static final String INCONCLUSIVE = "INCONCLUSIVE";

  /// What section (e) of `jfr-report.md` repeats and what the runner's `summary.md` top line
  /// carries. [#line] is the `ISSUE52: ...` line verbatim, so the two files cannot drift.
  record Summary(String verdict, boolean triggerMet, String line, List<String> notes) {
  }

  private final Path runDir;
  private final Map<String, String> counters;
  private final Map<String, String> runEnv;

  Issue52Report(final Path runDir, final Map<String, String> counters, final Map<String, String> runEnv) {
    this.runDir = runDir;
    this.counters = counters;
    this.runEnv = runEnv;
  }

  Summary write(final Path issue52Md, final Metrics metrics) throws IOException {
    final var retirementsPath = runDir.resolve("issue52").resolve("retirements.tsv");
    final var claimsPath = runDir.resolve("issue52").resolve("claims.tsv");
    final var retirements = TsvReader.readTsv(retirementsPath);
    final var claims = TsvReader.readTsv(claimsPath);
    final var notes = new ArrayList<String>();
    final var body = new StringWriter(8192);

    try (final var out = new PrintWriter(body)) {
      out.println("# Issue #52 — websocket attempt attribution");
      out.println();
      out.println("Run: `" + runDir + "`, profile `" + runEnv.getOrDefault("SOAK_PROFILE", "(unknown)")
          + "`, control `" + runEnv.getOrDefault("SOAK_CONTROL", "(none)") + "`.");
      out.println();
      out.println("The question this file answers, and only this one: did a retirement get claimed TWICE by"
          + " ravina's `WebSocketManagerImpl` — once through the connect future settling exceptionally inside"
          + " sava's `retireConnection`, and once through the close/error callback that follows it on the same"
          + " thread — in a run that did NOT force the race, under a positive, escalating backoff? That is the"
          + " agreed trigger to revisit the deferral. Everything else here is a denominator for it.");

      // ---- denominators and counts ----
      final long rowRetirements = retirements.rows().size();
      final long rowClaims = claims.rows().size();
      final long counterRetirements = TsvReader.longValue(counters, "i52.retirements", -1L);
      final long counterClaims = TsvReader.longValue(counters, "i52.claims", -1L);

      final var verdicts = new LinkedHashMap<String, long[]>();
      final var byBackoff = new LinkedHashMap<String, long[]>();
      final var byForced = new LinkedHashMap<String, long[]>();
      final var gaps = new ArrayList<Long>();
      final var margins = new ArrayList<Long>();
      long wasDoneFalse = 0;
      long insideAdopt = 0;
      long rowMisattributed = 0;
      long rowUnexplained = 0;
      long rowOpportunities = 0;
      long triggerRows = 0;
      long unforcedRetirements = 0;
      // The win-rate denominator: retirements whose race was left to this machine's timing.
      // Wider than `unforced` on purpose — an injected retirement that nothing held open is a
      // measurement of the race, while the blocking-handler mode decides it. Narrower than
      // "all rows" for the same reason. It never touches `triggerRows`, which stays unforced.
      long unblockedRetirements = 0;
      long unblockedMisattributed = 0;
      long rowUnobserved = 0;
      for (final var row : retirements.rows()) {
        final var verdict = row.get("verdict", "?");
        bump(verdicts, verdict);
        if ("UNOBSERVED_DELIVERY".equals(verdict)) {
          ++rowUnobserved;
        }
        final var backoff = row.get("backoffClass", "?");
        bump(byBackoff, backoff);
        final boolean forced = row.getBoolean("forced");
        bump(byForced, forced ? "forced" : "natural");
        if (!row.getBoolean("buildWasDoneAtCancel")) {
          ++wasDoneFalse;
        }
        if ("ADOPTING".equals(row.get("threadKind"))) {
          ++insideAdopt;
        }
        final long gap = row.getLong("gapFutureToCallbackUs", -1L);
        if (gap >= 0) {
          gaps.add(gap);
        }
        // -1 is DESIGN.md §10's "unknown numeric"; a real margin can be negative, so a row that
        // never measured one is dropped rather than counted as a zero-margin win
        final var marginCell = row.get("retryMarginUs");
        if (!marginCell.isBlank() && !"-1".equals(marginCell.strip())) {
          margins.add(TsvReader.parseLong(marginCell, 0L));
        }
        final boolean misattributed = "DOUBLE_CLAIM_MISATTRIBUTED".equals(verdict);
        if (misattributed) {
          ++rowMisattributed;
        }
        if ("DOUBLE_CLAIM_UNEXPLAINED".equals(verdict)) {
          ++rowUnexplained;
        }
        // opportunity: the future route was claimed AND the retry delay fits inside the gap, so a
        // successor could be installed before the callback runs (DESIGN.md §10)
        if ("ACCEPTED".equals(row.get("futureRoute"))
            && !marginCell.isBlank() && TsvReader.parseLong(marginCell, 1L) <= 0L) {
          ++rowOpportunities;
        }
        if (!forced) {
          ++unforcedRetirements;
          if (misattributed && "POSITIVE_ESCALATING".equals(backoff)) {
            ++triggerRows;
          }
        }
        if (RetirementRecord.raceUnblocked(forced, row.get("forcedReason"))) {
          ++unblockedRetirements;
          if (misattributed) {
            ++unblockedMisattributed;
          }
        }
      }

      long rowUnrouted = 0;
      long rowOriginUnknown = 0;
      final var routes = new LinkedHashMap<String, long[]>();
      final var qualities = new LinkedHashMap<String, long[]>();
      for (final var row : claims.rows()) {
        final var route = row.get("route", "?");
        bump(routes, route);
        if (isUnrouted(route)) {
          ++rowUnrouted;
        }
        final var quality = row.get("originQuality", "?");
        bump(qualities, quality);
        if ("UNKNOWN".equalsIgnoreCase(quality) || "?".equals(quality)) {
          ++rowOriginUnknown;
        }
      }

      // counters own the totals; rows own the evidence. Where both exist and disagree, say so.
      final long retirementCount = counterRetirements >= 0 ? counterRetirements : rowRetirements;
      final long claimCount = counterClaims >= 0 ? counterClaims : rowClaims;
      // Neither attribution rate may cross its sources. `claims.tsv` is the output of a bounded
      // queue that counts its own overflow (`harness.tsv.dropped`), so a row-derived numerator
      // over the counter denominator under-reports by exactly the row-survival fraction — and
      // these two rates are the gates deciding whether this run may be read at all, so a loss
      // must never dilute them. The harness increments `i52.claims` and both of its numerators
      // together, before it appends the row, so the counter triple stays internally consistent
      // under any loss; the surviving rows are internally consistent too. Take a whole rate from
      // one side or the other, never a half from each.
      final long counterUnrouted = TsvReader.longValue(counters, "i52.claimsUnrouted", -1L);
      final long counterOriginUnknown = TsvReader.longValue(counters, "i52.originUnknown", -1L);
      final var unroutedRate = rate(claims.has("route"), rowUnrouted, rowClaims, counterUnrouted, counterClaims);
      final var originUnknownRate = rate(claims.has("originQuality"), rowOriginUnknown, rowClaims,
          counterOriginUnknown, counterClaims);
      final long unrouted = unroutedRate.numerator();
      final long originUnknown = originUnknownRate.numerator();
      final long tsvDropped = TsvReader.longValue(counters, "harness.tsv.dropped", -1L);
      final var droppedSuffix = tsvDropped > 0
          ? " (harness.tsv.dropped = " + tsvDropped + ": a bounded TSV writer discarded rows)" : "";
      final long misattributed = pick(counters, "i52.misattributed", rowMisattributed);
      final long unexplained = pick(counters, "i52.doubleClaimsUnexplained", rowUnexplained);
      final long doubleClaims = pick(counters, "i52.doubleClaims", rowMisattributed + rowUnexplained);
      final long opportunities = pick(counters, "i52.opportunities", rowOpportunities);
      final double originUnknownPct = originUnknownRate.pct();
      final double unroutedPct = unroutedRate.pct();

      out.println();
      out.println("## Totals and denominators");
      out.println();
      out.println("| quantity | counters.properties | retirements.tsv / claims.tsv |");
      out.println("|---|---:|---:|");
      out.println("| retirements | " + show(counterRetirements) + " | " + rowRetirements + " |");
      out.println("| accepted manager claims | " + show(counterClaims) + " | " + rowClaims + " |");
      out.println("| double claims | " + show(TsvReader.longValue(counters, "i52.doubleClaims", -1L)) + " | "
          + (rowMisattributed + rowUnexplained) + " |");
      out.println("| misattributed | " + show(TsvReader.longValue(counters, "i52.misattributed", -1L)) + " | "
          + rowMisattributed + " |");
      out.println("| double claims, unexplained | "
          + show(TsvReader.longValue(counters, "i52.doubleClaimsUnexplained", -1L)) + " | " + rowUnexplained + " |");
      out.println("| opportunities | " + show(TsvReader.longValue(counters, "i52.opportunities", -1L)) + " | "
          + rowOpportunities + " |");
      out.println("| retirements inside adopt | "
          + show(TsvReader.longValue(counters, "i52.retirementsInsideAdopt", -1L)) + " | " + insideAdopt + " |");
      out.println("| future settled by retirement | "
          + show(TsvReader.longValue(counters, "i52.futureSettledByRetirement", -1L)) + " | (not a row column) |");
      out.println("| retirements where the build future was NOT already done | (not a counter) | "
          + wasDoneFalse + " |");
      out.println("| unforced retirements | (not a counter) | " + unforcedRetirements + " |");
      out.println("| retirements whose race was not blocked (win-rate denominator) | (not a counter) | "
          + unblockedRetirements + " |");
      if (counterRetirements >= 0 && counterRetirements != rowRetirements) {
        notes.add("counters say " + counterRetirements + " retirement(s), retirements.tsv holds " + rowRetirements
            + " row(s) — one of the two writers stopped; the counter is used for the metrics");
      }
      if (counterClaims >= 0 && counterClaims != rowClaims) {
        notes.add("counters say " + counterClaims + " claim(s), claims.tsv holds " + rowClaims + " row(s)"
            + droppedSuffix + " — the unrouted rate is read from " + unroutedRate.source() + " and the"
            + " origin-unknown rate from " + originUnknownRate.source() + ", each over its own denominator");
      }

      out.println();
      out.println("## Attribution quality (the gates on whether this run may be read at all)");
      out.println();
      out.println("- claims whose origin frame was UNKNOWN: " + originUnknown + " of " + originUnknownRate.denominator()
          + " = " + String.format(Locale.ROOT, "%.1f", originUnknownPct) + " % (gate: " + MAX_ORIGIN_UNKNOWN_PCT
          + " %, from " + originUnknownRate.source() + ")");
      out.println("- claims that could not be routed to the future or to a callback: " + unrouted + " of "
          + unroutedRate.denominator() + " = " + String.format(Locale.ROOT, "%.1f", unroutedPct) + " % (gate: "
          + MAX_CLAIMS_UNROUTED_PCT + " %, from " + unroutedRate.source() + ")");
      out.println("- claim routes: " + counts(routes) + "; origin quality: " + counts(qualities));
      if (rowClaims != claimCount) {
        out.println("- `claims.tsv` holds " + rowClaims + " of " + claimCount + " claim(s)" + droppedSuffix
            + ", with " + rowUnrouted + " unrouted and " + rowOriginUnknown + " of unknown origin among them."
            + " Each rate above divides a numerator by the denominator of the SAME source, so a lost row cannot"
            + " dilute a gate; the route and quality breakdown beside them is the surviving rows only.");
      }
      out.println();
      out.println("A claim the harness cannot place is not evidence about sava or ravina: it is evidence that the"
          + " harness's own frame stack lost the thread. Past either gate this file reports INCONCLUSIVE and the"
          + " run says nothing about #52 either way.");

      out.println();
      out.println("## Backoff classes and forcing");
      out.println();
      out.println("- by backoff class: " + counts(byBackoff));
      out.println("- by forcing: " + counts(byForced));
      out.println("- verdicts: " + counts(verdicts));
      out.println();
      out.println("The four classes are never merged. `CONSTANT_ZERO` reproduces the residual condition by"
          + " construction and proves nothing about production; `POSITIVE_ESCALATING` is the one the issue's"
          + " trigger names, because it is the shape a real consumer runs. `forced` marks a run where the harness"
          + " deferred a build-future completion, blocked a log handler or injected a retirement to MAKE the race"
          + " happen — a forced misattribution is a mechanism demonstration, never the trigger.");
      out.println();
      out.println("`forced` is not the same question as whether the RACE was decided. Injecting a retirement"
          + " forces the race to be RUN; blocking the attempt-failed handler until the successor is built"
          + " forces it to be WON. The win rate below is taken over the first kind and not the second, which"
          + " is why its denominator is a row of its own above.");

      out.println();
      out.println("## Distributions");
      out.println();
      out.println("| series | n | p50 | p99 | max |");
      out.println("|---|---:|---:|---:|---:|");
      out.println("| gap, future settling -> callback (us) | " + gaps.size() + " | " + TsvReader.percentile(gaps, 0.5d)
          + " | " + TsvReader.percentile(gaps, 0.99d) + " | " + (gaps.isEmpty() ? 0 : java.util.Collections.max(gaps)) + " |");
      out.println("| retry margin, retryDelay - gap (us) | " + margins.size() + " | "
          + TsvReader.percentile(margins, 0.5d) + " | " + TsvReader.percentile(margins, 0.99d) + " | "
          + (margins.isEmpty() ? 0 : java.util.Collections.max(margins)) + " |");
      out.println();
      out.println("A NEGATIVE retry margin is the interesting one: the manager's retry delay is shorter than the"
          + " window between the future settling and the callback running, so a successor attempt can be installed"
          + " inside that window and the callback's claim then lands on it instead of on the attempt that died.");

      writeRetirementTable(out, retirements);
      writeClaimTable(out, claims);

      // ---- verdict ----
      final boolean control = !runEnv.getOrDefault("SOAK_CONTROL", "").isBlank()
          || "control".equalsIgnoreCase(runEnv.getOrDefault("SOAK_PROFILE", ""));
      // Every statement this file makes about the trigger is a statement about row columns:
      // `forced` and `backoffClass` exist only per record. When the counters saw more
      // retirements — or more misattributions — than `retirements.tsv` carries, the rows that
      // were never written are exactly the ones that could have carried the trigger, so neither
      // "the trigger did not fire" nor "all forced or outside POSITIVE_ESCALATING" is a claim
      // the evidence supports. A trigger already visible in the surviving rows still stands.
      final boolean rowsShort = (counterRetirements >= 0 && counterRetirements > rowRetirements)
          || misattributed > rowMisattributed;
      final String verdict;
      final String because;
      if (retirements.path() == null && claims.path() == null) {
        verdict = INCONCLUSIVE;
        because = "no issue52 artifacts in this run directory — the capture never wrote a row";
      } else if (originUnknownPct > MAX_ORIGIN_UNKNOWN_PCT || unroutedPct > MAX_CLAIMS_UNROUTED_PCT) {
        verdict = INCONCLUSIVE;
        because = "attribution gates exceeded (origin-unknown " + String.format(Locale.ROOT, "%.1f", originUnknownPct)
            + " % > " + MAX_ORIGIN_UNKNOWN_PCT + " %, unrouted " + String.format(Locale.ROOT, "%.1f", unroutedPct)
            + " % > " + MAX_CLAIMS_UNROUTED_PCT + " %)";
      } else if (control && retirementCount == 0 && claimCount == 0) {
        verdict = INCONCLUSIVE;
        because = "this is a control run and it produced no retirement and no claim — the control did not fire,"
            + " so it neither confirms nor refutes anything";
      } else if (triggerRows > 0) {
        verdict = REVISIT;
        because = triggerRows + " misattributed retirement(s) with forced=false under POSITIVE_ESCALATING —"
            + " the fingerprint the issue named as its trigger";
      } else if (retirements.path() == null) {
        verdict = INCONCLUSIVE;
        because = "no retirements.tsv: `forced` and `backoffClass` exist only per record, so the trigger cannot"
            + " be evaluated from counters alone";
      } else if (rowsShort) {
        verdict = INCONCLUSIVE;
        because = "the per-record evidence is short of the run — counters say " + retirementCount + " retirement(s)"
            + " and " + misattributed + " misattribution(s), retirements.tsv holds " + rowRetirements + " row(s) and "
            + rowMisattributed + "; `forced` and `backoffClass` exist only per record, so the trigger cannot be"
            + " evaluated over rows that were never written";
      } else {
        verdict = KEEP_DEFERRED;
        // deliberately the ROW count, not the counter: the sentence it introduces is an
        // assertion about `forced` and `backoffClass`, which only a row carries
        because = rowMisattributed == 0
            ? "no misattribution observed in " + retirementCount + " retirement(s)"
            : rowMisattributed + " misattribution(s), all forced or outside POSITIVE_ESCALATING — a mechanism"
            + " demonstration, which is what the existing assessment already assumed";
      }
      if (rowsShort) {
        notes.add("retirements.tsv is short of the counters: " + rowRetirements + " row(s) against " + retirementCount
            + " and " + rowMisattributed + " misattribution(s) against " + misattributed + droppedSuffix
            + " — the missing rows carry the `forced` and `backoffClass` columns the trigger is evaluated on");
      }
      if (retirementCount == 0 && !control) {
        notes.add("no retirement was observed at all: this run is not exposure evidence, whatever its verdict line says");
      }
      if (unexplained > 0) {
        notes.add(unexplained + " double claim(s) UNEXPLAINED — the second claim read no attempt ordinal above"
            + " the origin's, and no successor build fell inside the gap either, so the manager had nothing"
            + " else to charge and the harness paired two claims to one retirement. That is a harness defect,"
            + " not a subject finding, and the runner fails the run on it");
      }

      final double naturalWinRate = unblockedRetirements == 0
          ? Double.NaN : unblockedMisattributed * 100d / unblockedRetirements;
      final long gapP99 = TsvReader.percentile(gaps, 0.99d);
      final long marginMin = margins.isEmpty() ? Long.MIN_VALUE : java.util.Collections.min(margins);

      metrics.put("i52_retirements", retirementCount);
      metrics.put("i52_claims", claimCount);
      // The row counts beside the totals, so row loss is machine-readable. Everything this file
      // says about `forced`, `backoffClass` and claim routing is a statement about rows, and when
      // the rows are short of the counters it refuses to speak (INCONCLUSIVE) - but that refusal
      // reached the runner only as a verdict word and a sentence. A reader comparing
      // i52_retirements with i52_retirement_rows now sees the shortfall itself, and a run whose
      // TSV writer dropped rows can be told from one whose capture never fired.
      metrics.put("i52_retirement_rows", rowRetirements);
      metrics.put("i52_claims_rows", rowClaims);
      metrics.put("i52_double_claims", doubleClaims);
      metrics.put("i52_double_claims_unexplained", unexplained);
      metrics.put("i52_misattributed", misattributed);
      metrics.put("i52_opportunities", opportunities);
      metrics.put("i52_gap_p99_us", gaps.isEmpty() ? "n/a" : Long.toString(gapP99));
      metrics.put("i52_retry_margin_min_us", margins.isEmpty() ? "n/a" : Long.toString(marginMin));
      metrics.putRate("i52_natural_win_rate", naturalWinRate, 3);
      metrics.putRate("i52_claims_unrouted_pct", unroutedPct, 1);
      metrics.putRate("i52_origin_unknown_pct", originUnknownPct, 1);
      metrics.put("i52_trigger_met", REVISIT.equals(verdict));
      metrics.put("i52_verdict", verdict);
      // a retirement whose delivery no harness handler consumed: the capture lost the thread, so
      // the runner fails the run on it as a harness-health gate, never as a subject finding
      metrics.put("i52_unobserved_delivery", rowUnobserved);
      if (rowUnobserved > 0) {
        notes.add(rowUnobserved + " retirement(s) with an UNOBSERVED delivery - the harness saw the abort and"
            + " never the handler that followed it; a harness defect, not a subject finding");
      }

      final var line = "ISSUE52: " + verdict + " — " + because;
      out.println();
      out.println("## Verdict");
      out.println();
      out.println("```");
      out.println(line);
      out.println("```");
      out.println();
      out.println("- natural win rate — misattributions over the retirements whose race was NOT blocked,"
          + " which is every unforced retirement plus every injected one the harness then left alone"
          + " (`INJECTED_RETIREMENT_UNBLOCKED`, `INJECTED_RETIREMENT_JDK_ORDER`), and excludes the"
          + " blocking-handler mode (`INJECTED_RETIREMENT`), which holds the gap open until the successor"
          + " is built and so decides the race instead of measuring it: "
          + (Double.isNaN(naturalWinRate) ? "n/a (no retirement was left unblocked)"
          : String.format(Locale.ROOT, "%.3f", naturalWinRate) + " % of " + unblockedRetirements)
          + " — a property of THIS machine's timing, not of the library. An injected race says nothing"
          + " about how often production meets one; it says how often, once met, the successor is charged."
          + " It is not the revisit trigger either: an injected row can never meet that.");
      out.println("- #52 never contributes a FAIL. `REVISIT` with every other gate green is"
          + " `PASS-WITH-FINDING` (exit 2); the runner owns that mapping.");
      for (final var note : notes) {
        out.println("- NOTE: " + note);
      }
      out.println();
      out.println("Reading this file later: the agreed future shape is an additive `connectAttempt()` returning a"
          + " `ConnectionAttempt { long ordinal(); connected(); retired(); }`. Nothing here authorises implementing"
          + " it, and the harness deliberately does not.");
      out.flush();
      Files.writeString(issue52Md, body.toString(), StandardCharsets.UTF_8);
      return new Summary(verdict, REVISIT.equals(verdict), line, notes);
    }
  }

  private static void writeRetirementTable(final PrintWriter out, final TsvReader.Table retirements) {
    out.println();
    out.println("## Per-retirement records");
    out.println();
    if (retirements.path() == null) {
      out.println("- (missing) `issue52/retirements.tsv`");
      return;
    }
    if (retirements.rows().isEmpty()) {
      out.println("- no rows" + partial(retirements));
      return;
    }
    out.println("| id | engine | backoff | forced | cause | thread | future | callback | claims | gap us | retry ms |"
        + " margin us | verdict | next |");
    out.println("|---|---|---|---|---|---|---|---|---:|---:|---:|---:|---|---|");
    int written = 0;
    for (final var row : retirements.rows()) {
      if (written++ == MAX_TABLE_ROWS) {
        break;
      }
      out.println("| " + row.get("retirementId", "?")
          + " | " + row.get("engine", "?")
          + " | " + row.get("backoffClass", "?")
          + " | " + row.get("forced", "?")
          + " | " + row.get("causeClass", "?")
          + " | " + row.get("threadKind", "?")
          + " | " + row.get("futureRoute", "?")
          + " | " + row.get("callbackRoute", "?")
          + " | " + row.get("claimCount", "-")
          + " | " + row.get("gapFutureToCallbackUs", "-")
          + " | " + row.get("retryDelayMs", "-")
          + " | " + row.get("retryMarginUs", "-")
          + " | " + row.get("verdict", "?")
          + " | " + row.get("nextAttemptOutcome", "?") + " |");
    }
    if (retirements.rows().size() > MAX_TABLE_ROWS) {
      out.println();
      out.println("- " + (retirements.rows().size() - MAX_TABLE_ROWS) + " further row(s) in `issue52/retirements.tsv`"
          + partial(retirements));
    } else if (retirements.partialLines() > 0) {
      out.println();
      out.println("- " + partial(retirements).strip());
    }
    out.println();
    out.println("- `next` is the successor attempt's outcome AT THE TIME THE RECORD WAS WRITTEN, which is the"
        + " retirement itself: PENDING means the successor had not yet been built or acknowledged then, not"
        + " that it never was. Only a successor built inside the future-to-callback gap can be anything else"
        + " here; the successor's own fate is the next row for that engine.");
  }

  private static void writeClaimTable(final PrintWriter out, final TsvReader.Table claims) {
    out.println();
    out.println("## Accepted manager claims");
    out.println();
    out.println("One row per `Backoff.delay(errorCount, unit)` call: ravina's `beginFailure` claims the failure,"
        + " increments `errorCount`, cancels its own copy of the attempt future and THEN asks the backoff for a"
        + " delay, so a harness-supplied `Backoff` sees every claim exactly once.");
    out.println();
    if (claims.path() == null) {
      out.println("- (missing) `issue52/claims.tsv`");
      return;
    }
    if (claims.rows().isEmpty()) {
      out.println("- no rows" + partial(claims));
      return;
    }
    out.println("| seq | engine | errorCount | delay ms | route | origin | quality | current | insideBuildCancel |"
        + " thread |");
    out.println("|---:|---|---:|---:|---|---|---|---|---|---|");
    int written = 0;
    for (final var row : claims.rows()) {
      if (written++ == MAX_TABLE_ROWS) {
        break;
      }
      out.println("| " + row.get("claimSeq", "-")
          + " | " + row.get("engine", "?")
          + " | " + row.get("errorCount", "-")
          + " | " + row.get("delayMillis", "-")
          + " | " + row.get("route", "?")
          + " | " + row.get("originOrdinal", "-")
          + " | " + row.get("originQuality", "?")
          + " | " + row.get("currentOrdinal", "-")
          + " | " + row.get("insideBuildCancel", "?")
          + " | " + row.get("thread", "?") + " |");
    }
    if (claims.rows().size() > MAX_TABLE_ROWS) {
      out.println();
      out.println("- " + (claims.rows().size() - MAX_TABLE_ROWS) + " further row(s) in `issue52/claims.tsv`"
          + partial(claims));
    }
  }

  private static String partial(final TsvReader.Table table) {
    return table.partialLines() == 0 ? ""
        : " (" + table.partialLines() + " truncated line(s) skipped — the run was killed mid-write)";
  }

  /// `route` spellings that mean "the harness could not place this claim". Anything else is a
  /// route it DID place, whatever the enumeration is called.
  private static boolean isUnrouted(final String route) {
    if (route == null || route.isBlank()) {
      return true;
    }
    final var upper = route.strip().toUpperCase(Locale.ROOT);
    return upper.equals("?") || upper.equals("NONE") || upper.equals("UNROUTED") || upper.equals("UNKNOWN")
        || upper.equals("OTHER") || upper.equals("UNOBSERVED");
  }

  /// One attribution rate with both halves taken from the same writer, and the name of that
  /// writer so the report can say which one it read.
  private record Rate(long numerator, long denominator, boolean fromRows) {

    double pct() {
      return denominator == 0 ? 0d : numerator * 100d / denominator;
    }

    String source() {
      return fromRows ? "claims.tsv" : "counters.properties";
    }
  }

  /// Chooses the writer a rate is read from, whole.
  ///
  /// The rows win only when they can answer both halves; otherwise the counters do, because they
  /// count a claim and its two classifications in one step before the row is appended, so they
  /// stay self-consistent even where the bounded writer dropped rows. A column missing from the
  /// file leaves the rows no numerator at all, so the counter answers (absent, it reads 0 — the
  /// behaviour a file written without the column has always had) even where that means dividing
  /// by the row count, which is then the only denominator there is.
  private static Rate rate(final boolean columnPresent,
                           final long fromRows,
                           final long rowTotal,
                           final long fromCounter,
                           final long counterTotal) {
    if (columnPresent && (fromCounter < 0 || counterTotal < 0)) {
      return new Rate(fromRows, rowTotal, true);
    }
    return new Rate(Math.max(0L, fromCounter), counterTotal >= 0 ? counterTotal : rowTotal, false);
  }

  private static long pick(final Map<String, String> counters, final String key, final long fromRows) {
    final long counter = TsvReader.longValue(counters, key, -1L);
    return counter >= 0 ? counter : fromRows;
  }

  private static String show(final long value) {
    return value < 0 ? "(absent)" : Long.toString(value);
  }

  private static void bump(final Map<String, long[]> counts, final String key) {
    counts.computeIfAbsent(key == null || key.isBlank() ? "?" : key, ignored -> new long[1])[0]++;
  }

  private static String counts(final Map<String, long[]> counts) {
    if (counts.isEmpty()) {
      return "none";
    }
    final var sb = new StringBuilder();
    counts.forEach((key, value) -> sb.append(sb.isEmpty() ? "" : ", ").append(key).append(" (").append(value[0]).append(')'));
    return sb.toString();
  }
}
