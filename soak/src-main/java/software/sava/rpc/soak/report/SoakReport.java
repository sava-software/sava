package software.sava.rpc.soak.report;

import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Post-processing for one soak run: `jfr-report.md` for people, `jfr-metrics.properties` for the
/// runner's verdict, `issue52.md` for the decision record GitHub issue #52 is waiting on.
///
/// ```
/// java -p <module path> -m software.sava.rpc.soak/software.sava.rpc.soak.report.SoakReport <runDir> <recording.jfr|->
/// ```
///
/// `-` in place of a recording skips every JFR section: a run whose dump timed out still has its
/// counters, its ledgers and its gauge series, and a report that refused to run over them would
/// throw away the evidence that survived.
///
/// This package reads the artifacts of `DESIGN.md` §3 by their documented formats and imports
/// NOTHING from the rest of the harness. That is deliberate: the report must be runnable against
/// a run directory produced by a different revision of the harness — a campaign from last week,
/// a control set from before a refactor — and a compile-time dependency on the writer's classes
/// would quietly tie the two together.
///
/// Failure policy, which the runner depends on:
///
/// - A required artifact that is missing or unreadable is exit non-zero WITH A MESSAGE on stderr:
///   the run directory itself, and the recording when one was named.
/// - Every other artifact is optional. A missing one produces a labelled `(missing)` line in the
///   section that wanted it — never an exception, and never a silently absent section, because
///   "the report did not mention it" and "it was clean" must not look the same.
public final class SoakReport {

  static final int MAX_JSON_DEPTH = 8;
  static final int MAX_JSON_FIELDS = 512;
  /// Defaults matching `DESIGN.md` §5's campaign column, used when `run.env` does not say.
  static final double DEFAULT_RSS_SLOPE_KIB_PER_HOUR = 2048d;
  static final double DEFAULT_RSS_NOISE_FLOOR_KIB = 8192d;
  static final double DEFAULT_HEAP_FLOOR_SLOPE_KIB_PER_HOUR = 1024d;

  /// The fault catalogue of `DESIGN.md` §6, copied rather than imported.
  ///
  /// This package reads artifacts and imports nothing from the rest of the harness (see the class
  /// note above), so it cannot ask `peer.FaultKind` what exists. Without a copy, a kind that never
  /// reached the plan at all is INVISIBLE here — it has no schedule row, no outcome row and no
  /// column of its own, so the per-kind table below simply has one fewer line and nothing says so.
  ///
  /// The copy is kept honest from both ends: a listed kind with no planned row is reported, and a
  /// kind seen in this run's artifacts that is NOT listed here is reported as a stale copy. Anyone
  /// adding a `FaultKind` adds it here too, and the second check is what says so if they do not.
  static final List<String> SCHEDULED_FAULT_KINDS = List.of(
      "TCP_RESET", "HALF_CLOSE", "CLOSE_FRAME", "HANDSHAKE_500", "HANDSHAKE_STALL", "HANDSHAKE_BAD_ACCEPT",
      "HANDSHAKE_RST", "SLOW_WRITE", "CONNECT_REFUSE", "SWALLOW_PING",
      "SWALLOW_SUBSCRIBE", "DELAY_CONFIRM", "REJECT_SUBSCRIBE", "DUPLICATE_CONFIRMATION", "SWALLOW_UNSUB",
      "REFUSE_UNSUB", "ERROR_RESPONSE", "DUP_SUBID", "ZOMBIE_NOTIFICATION", "UNKNOWN_SUB_NOTIFICATION",
      "DANGLING_FRAGMENT", "MESSAGE_OVERFLOW",
      "STALL_BODY", "DELAY_RESPONSE", "HTTP_429", "HTTP_503", "RPC_ERROR", "TRUNCATE_BODY", "BAD_GZIP",
      "GZIP_TRAILING", "BAD_CONTENT_LENGTH", "CONNECTION_DROP", "CHUNKED_SLOW", "WRONG_ID_ENVELOPE");

  /// Control-only kinds: never in the weighted fill, selected by `SOAK_CONTROL`, and injected with
  /// ordinal 0. Their absence from a plan is the design, not a gap, so they are excluded from the
  /// missing-kind report rather than left to produce a note on every ordinary run.
  static final List<String> CONTROL_ONLY_FAULT_KINDS = List.of(
      "CROSSTALK", "SUBID_REUSE_ACROSS_PARAMS", "WRONG_ID_ECHO", "WRONG_KEY_ECHO", "DROP_REPLAYED_SUBSCRIBES",
      "DUPLICATE_NOTIFY", "SWAP_ADJACENT_NOTIFY", "FLIP_CONTINUATION_BYTE", "DEDUPE_BREAK", "NEVER_APPLY_KIND");

  /// The HTTP fault kinds that corrupt a response body on the wire, and so are the injected
  /// explanations for a client-side decoding failure (`rpc.completed.decode`).
  static final List<String> BODY_CORRUPTING_FAULT_KINDS =
      List.of("BAD_GZIP", "GZIP_TRAILING", "TRUNCATE_BODY", "BAD_CONTENT_LENGTH");

  /// `peer-http.tsv` has no header; its first line is the `anchor` row (`DESIGN.md` §7).
  static final List<String> PEER_HTTP_COLUMNS = List.of("wallMillis", "nanosSinceAnchor", "method", "requestId",
      "seq", "status", "bytes", "encoding", "delayMillis", "faultKind", "detail");

  public static void main(final String[] args) {
    if (args.length < 2) {
      System.err.println("usage: SoakReport <runDir> <recording.jfr|->");
      System.exit(2);
      return;
    }
    final var runDir = Path.of(args[0]).toAbsolutePath().normalize();
    if (!Files.isDirectory(runDir)) {
      System.err.println("SoakReport: not a run directory: " + runDir);
      System.exit(1);
      return;
    }
    final Path recording;
    if ("-".equals(args[1].strip())) {
      recording = null;
    } else {
      recording = Path.of(args[1]).toAbsolutePath().normalize();
      if (!Files.isRegularFile(recording) || !Files.isReadable(recording)) {
        System.err.println("SoakReport: recording not readable: " + recording);
        System.exit(1);
        return;
      }
    }
    try {
      final var report = new SoakReport(runDir, recording);
      report.run();
    } catch (final IOException | RuntimeException e) {
      System.err.println("SoakReport: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      System.exit(1);
    }
  }

  private final Path runDir;
  private final Path recording;
  private final Metrics metrics = new Metrics();
  private final List<String> notes = new ArrayList<>();

  SoakReport(final Path runDir, final Path recording) {
    this.runDir = runDir;
    this.recording = recording;
  }

  void run() throws IOException {
    final var runEnv = TsvReader.readKeyValues(runDir.resolve("run.env"));
    final var counters = TsvReader.readKeyValues(runDir.resolve("counters.properties"));
    final var phases = TsvReader.read(runDir.resolve("phases.tsv"), '\t',
        List.of("epochMillis", "name", "index"));
    final var clientCsv = TsvReader.readCsv(runDir.resolve("client.csv"));

    final var jfr = new JfrPass(recording, runDir.resolve("logs").resolve("jfr-methodtrace.log"));
    applyRunWindow(jfr, phases);
    jfr.read();

    final var issue52 = new Issue52Report(runDir, counters, runEnv)
        .write(runDir.resolve("issue52.md"), metrics);

    final var body = new StringWriter(1 << 16);
    try (final var out = new PrintWriter(body)) {
      out.println("# sava RPC soak report");
      out.println();
      out.println("Run directory: `" + runDir + "`");
      out.println();
      out.println("Generated by `SoakReport` over " + (recording == null
          ? "**no recording** (`-`): every JFR section reads `(missing)`, and the harness counters,"
          + " ledgers and gauge series below are unaffected"
          : "`" + recording + "`") + '.');
      out.println();
      out.println("What this report is for, in one line: sava-rpc parses what untrusted nodes send, so the"
          + " harness drives its own HTTP and websocket clients against controlled peers that inject faults on a"
          + " recorded schedule, and this file says what happened. A finding here becomes a deterministic"
          + " regression test in sava-rpc's own test source set — never a change to the harness to make a run"
          + " pass, and never a change to published behaviour.");

      jfr.writeCensus(out, metrics);
      writeIdentity(out, runEnv);
      writeProgress(out, counters, phases);
      writeLedger(out);
      writeFaults(out, jfr, counters);
      writeIssue52(out, issue52, jfr);
      writeHarnessLatency(out, counters);
      jfr.writeLatency(out, metrics);
      writeResourceSeries(out, runEnv, clientCsv, counters, phases);
      jfr.writeResources(out, metrics);
      writeResiduals(out, clientCsv, counters);
      jfr.writeStalls(out, metrics);
      writeVerdictInputs(out, issue52);
      out.flush();
    }
    Files.writeString(runDir.resolve("jfr-report.md"), body.toString(), StandardCharsets.UTF_8);
    metrics.write(runDir.resolve("jfr-metrics.properties"));
    System.out.println("SoakReport: " + runDir.resolve("jfr-report.md") + ", "
        + runDir.resolve("jfr-metrics.properties") + ", " + runDir.resolve("issue52.md")
        + " — " + issue52.line());
  }

  /// Ring coverage is a percentage OF THE RUN, so the run's own window has to come from an
  /// artifact the ring cannot lose: the first `STARTUP` and last `SHUTDOWN`/`ABORTED` rows of
  /// `phases.tsv`, which the client appends at every boundary.
  private void applyRunWindow(final JfrPass jfr, final TsvReader.Table phases) {
    if (phases.rows().isEmpty()) {
      jfr.runWindow(null, null, "phases.tsv missing or empty");
      return;
    }
    Instant from = null;
    Instant to = null;
    var note = "";
    for (final var row : phases.rows()) {
      final long at = row.getLong("epochMillis", -1L);
      if (at < 0) {
        continue;
      }
      final var name = row.get("name");
      if (from == null && "STARTUP".equals(name)) {
        from = Instant.ofEpochMilli(at);
      }
      if ("SHUTDOWN".equals(name) || "ABORTED".equals(name)) {
        to = Instant.ofEpochMilli(at);
      }
    }
    if (from == null) {
      from = Instant.ofEpochMilli(phases.rows().getFirst().getLong("epochMillis", 0L));
      note = "no STARTUP row: the window starts at the first phase row";
    }
    if (to == null) {
      to = Instant.ofEpochMilli(phases.rows().getLast().getLong("epochMillis", 0L));
      note = note.isEmpty()
          ? "no SHUTDOWN/ABORTED row: the run did not reach a terminal phase, so the window ends at the last one"
          : note + "; and no SHUTDOWN/ABORTED row";
      notes.add("phases.tsv has no terminal SHUTDOWN/ABORTED row — the run ended without completing its phases");
    }
    jfr.runWindow(from, to, note);
  }

  // ---------------------------------------------------------------- (a) identity

  private void writeIdentity(final PrintWriter out, final Map<String, String> runEnv) throws IOException {
    out.println();
    out.println("## (a) Run identity");
    out.println();
    final var runJson = runDir.resolve("run.json");
    if (!Files.isRegularFile(runJson)) {
      out.println("- (missing) `run.json` — the subject under test, the JDK, the GC and the schedule digest are"
          + " unknown, so nothing below can be attributed to a revision");
    } else {
      final var fields = new LinkedHashMap<String, String>();
      try {
        flatten(JsonIterator.parse(Files.readAllBytes(runJson)), "", fields, 0);
      } catch (final RuntimeException e) {
        fields.clear();
        out.println("- `run.json` is present but did not parse (" + e.getClass().getSimpleName()
            + ") — treat this run as unidentified");
      }
      if (!fields.isEmpty()) {
        out.println("| field | value |");
        out.println("|---|---|");
        fields.forEach((key, value) -> out.println("| " + key + " | `" + redact(key, value) + "` |"));
      }
    }
    out.println();
    if (runEnv.isEmpty()) {
      out.println("- (missing) `run.env` — no resolved configuration for this run");
      return;
    }
    out.println("Resolved configuration (`run.env`; values that can carry a credential are masked here, because"
        + " this report is the file people attach to an issue):");
    out.println();
    out.println("| key | value |");
    out.println("|---|---|");
    runEnv.forEach((key, value) -> out.println("| " + key + " | `" + redact(key, value) + "` |"));
  }

  /// A live run's endpoint can carry an API key in a query string or in userinfo, and a JFR
  /// recording already records environment variables verbatim unless the .jfc disables it. The
  /// report is the artifact people attach to an issue, so it masks rather than trusting the
  /// writer to have done it.
  static String redact(final String key, final String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    final var upper = key == null ? "" : key.toUpperCase(Locale.ROOT);
    if (upper.contains("KEY") || upper.contains("TOKEN") || upper.contains("SECRET")
        || upper.contains("PASSWORD") || upper.contains("AUTH")) {
      return "***";
    }
    var masked = value;
    final int at = masked.indexOf('@');
    final int scheme = masked.indexOf("//");
    if (scheme >= 0 && at > scheme) {
      masked = masked.substring(0, scheme + 2) + "***@" + masked.substring(at + 1);
    }
    final int query = masked.indexOf('?');
    if (query >= 0) {
      masked = masked.substring(0, query) + "?***";
    }
    return masked;
  }

  /// Flattens `run.json` to `a.b[0].c = value` pairs. Generic on purpose: the identity block is
  /// whatever the runner chose to record, and a reader that only understood today's field names
  /// would drop tomorrow's.
  static void flatten(final JsonIterator ji, final String prefix, final Map<String, String> into, final int depth) {
    if (into.size() >= MAX_JSON_FIELDS || depth > MAX_JSON_DEPTH) {
      ji.skip();
      return;
    }
    final var next = ji.whatIsNext();
    if (next == ValueType.OBJECT) {
      ji.testObject((buf, offset, length, inner) -> {
        final var name = new String(buf, offset, length);
        flatten(inner, prefix.isEmpty() ? name : prefix + '.' + name, into, depth + 1);
        return true;
      });
    } else if (next == ValueType.ARRAY) {
      final var index = new int[1];
      while (ji.readArray()) {
        flatten(ji, prefix + '[' + index[0]++ + ']', into, depth + 1);
      }
    } else if (next == ValueType.STRING) {
      into.put(prefix, ji.readString());
    } else if (next == ValueType.NUMBER) {
      into.put(prefix, ji.readNumberAsString());
    } else if (next == ValueType.BOOLEAN) {
      into.put(prefix, Boolean.toString(ji.readBoolean()));
    } else if (next == ValueType.NULL) {
      ji.readNull();
      into.put(prefix, "null");
    } else {
      ji.skip();
    }
  }

  // ---------------------------------------------------------------- (b) progress

  private void writeProgress(final PrintWriter out, final Map<String, String> counters, final TsvReader.Table phases) {
    out.println();
    out.println("## (b) Progress and starvation");
    out.println();
    if (phases.rows().isEmpty()) {
      out.println("- (missing) `phases.tsv`");
    } else {
      out.println("Phases reached (`phases.tsv`):");
      out.println();
      out.println("| at | phase | index |");
      out.println("|---|---|---:|");
      for (final var row : phases.rows()) {
        final long at = row.getLong("epochMillis", -1L);
        out.println("| " + (at < 0 ? "?" : Instant.ofEpochMilli(at).toString()) + " | " + row.get("name", "?")
            + " | " + row.get("index", "-") + " |");
      }
    }
    out.println();
    if (counters.isEmpty()) {
      out.println("- (missing) `counters.properties` — there is no whole-run total for anything, and every"
          + " count in this report is then only what survived in the ring");
      return;
    }
    // The skips are the HTTP driver's, so the denominator is the HTTP attempts - the same ratio
    // the runner's starvation gate reads (review: this line divided by every workload's attempts).
    final long attempted = TsvReader.longValue(counters, "http.ops.attempted",
        TsvReader.longValue(counters, "harness.opsAttempted", 0L));
    final long skippedCap = TsvReader.longValue(counters, "harness.opsSkipped.inflightCap", 0L);
    final long skippedDeadline = TsvReader.longValue(counters, "harness.opsSkipped.deadline", 0L);
    final long skipped = skippedCap + skippedDeadline;
    final double skipPct = attempted <= 0 ? 0d : skipped * 100d / attempted;
    out.println("Harness starvation — the check that the HARNESS, not the subject, kept up. A run that could not"
        + " issue its own work is INVALID, not failing: it measured nothing.");
    out.println();
    out.println("- operations attempted: " + attempted + "; skipped " + skipped + " ("
        + String.format(Locale.ROOT, "%.2f", skipPct) + " %) = " + skippedCap + " at the in-flight cap + "
        + skippedDeadline + " past their deadline");
    final long peerLogDropped = TsvReader.longValue(counters, "harness.peerLog.dropped", 0L);
    out.println("- peer log lines dropped: " + peerLogDropped
        + (peerLogDropped > 0 ? " — **the peer's own record is incomplete**, so peer-established (grade C)"
        + " properties cannot be trusted for this run" : ""));
    out.println();
    out.println("Workload progress (`counters.properties`):");
    out.println();
    out.println("| counter | value |");
    out.println("|---|---:|");
    for (final var entry : counters.entrySet()) {
      final var key = entry.getKey();
      if (key.startsWith("hist.") || key.startsWith("i52.")) {
        continue;
      }
      out.println("| " + key + " | " + entry.getValue() + " |");
    }
  }

  // ---------------------------------------------------------------- (c) ledger

  private void writeLedger(final PrintWriter out) throws IOException {
    out.println();
    out.println("## (c) Correctness ledger");
    out.println();
    final var properties = TsvReader.readTsv(runDir.resolve("properties.tsv"));
    if (properties.path() == null) {
      out.println("- (missing) `properties.tsv` — no property was recorded as evaluated, which is not the same as"
          + " no property failing");
      return;
    }
    out.println("Grades: **A** a public contract, **B** a documented invariant, **C** established by the"
        + " controlled peer, **D** a measurement. Only A/B/C can fail a run; D never does. A grade-A/B/C row with"
        + " `evaluated == 0` and no stated skip is a FAIL — an unexercised guarantee is not a passing one.");
    out.println();
    out.println("| id | grade | evaluated | passed | failed | skip | statement |");
    out.println("|---|---|---:|---:|---:|---|---|");
    final var failing = new ArrayList<String>();
    for (final var row : properties.rows()) {
      final var id = row.get("id", "?");
      final var grade = row.get("grade", "?");
      final long evaluated = row.getLong("evaluated", 0L);
      final long failed = row.getLong("failed", 0L);
      final var skip = row.get("skipReason");
      final boolean gating = grade.startsWith("A") || grade.startsWith("B") || grade.startsWith("C");
      if (gating && failed > 0) {
        failing.add(id + " failed " + failed + "x");
      } else if (gating && evaluated == 0 && skip.isBlank()) {
        failing.add(id + " UNEXERCISED");
      }
      out.println("| " + id + " | " + grade + " | " + evaluated + " | " + row.get("passed", "-") + " | " + failed
          + " | " + (skip.isBlank() ? "" : skip) + " | " + row.get("statement", "") + " |");
    }
    out.println();
    if (failing.isEmpty()) {
      out.println("- no grade-A/B/C property failed or went unexercised in this ledger");
    } else {
      out.println("- **gating rows needing attention**: " + String.join("; ", failing));
    }
    final var examples = new ArrayList<String>();
    for (final var row : properties.rows()) {
      final var example = row.get("examples");
      if (!example.isBlank() && row.getLong("failed", 0L) > 0) {
        examples.add(row.get("id", "?") + ": " + example);
      }
    }
    for (final var example : examples) {
      out.println("  - " + example);
    }
    if (properties.partialLines() > 0) {
      out.println("- " + properties.partialLines() + " truncated line(s) in `properties.tsv` were skipped");
    }
  }

  // ---------------------------------------------------------------- (d) faults

  /// Fidelity is measured over the faults the run's traffic actually REACHED, not over the
  /// whole plan. The schedule is sized from the configured duration and the expected rates
  /// before the run starts, so a run that is cut short - or one whose real traffic ran below
  /// the estimate - leaves the tail of the plan untouched, and a fault the peer's counter never
  /// got to says nothing about whether the peer injects what it is told to. A fault is reached
  /// when the peer's own final counter for its `(counter, port)` domain passed its trigger
  /// (`peer-summary.json`), or when the peer wrote an outcome row for it. Unreached faults are
  /// counted and printed on their own; a kind that was reached and never applied is the gate.
  private void writeFaults(final PrintWriter out, final JfrPass jfr, final Map<String, String> counters) throws IOException {
    out.println();
    out.println("## (d) Faults and recovery");
    out.println();
    final var schedule = TsvReader.readTsv(runDir.resolve("fault-schedule.tsv"));
    final var applied = TsvReader.readTsv(runDir.resolve("faults-applied.tsv"));
    if (schedule.path() == null) {
      out.println("- (missing) `fault-schedule.tsv` - nothing was planned, so fidelity is undefined rather than"
          + " zero (the metric reads `n/a`)");
    }
    if (applied.path() == null) {
      out.println("- (missing) `faults-applied.tsv`");
    }
    final var peer = peerSummary();
    final var peerCounters = peer == null ? null : peer.counters();
    if (peerCounters == null) {
      out.println("- (missing) `peer-summary.json` - the peer's final counters are unknown, so every planned"
          + " fault is treated as reached and the fidelity below is a LOWER bound");
    }

    // outcome rows by ordinal; control injections (ordinal 0) are counted apart from the plan
    final var outcomes = new LinkedHashMap<Long, String>();
    long controlInjections = 0;
    for (final var row : applied.rows()) {
      final long ordinal = row.getLong("ordinal", -1L);
      if (ordinal <= 0) {
        ++controlInjections;
        continue;
      }
      outcomes.put(ordinal, row.get("outcome", "?"));
    }

    // per kind: {planned, reached, applied, no_target}
    final var byKind = new LinkedHashMap<String, long[]>();
    long plannedTotal = 0;
    long reachedTotal = 0;
    long appliedTotal = 0;
    long noTarget = 0;
    for (final var row : schedule.rows()) {
      final var kind = row.get("kind", "?");
      final var counts = byKind.computeIfAbsent(kind, ignored -> new long[4]);
      ++counts[0];
      ++plannedTotal;
      final long ordinal = row.getLong("ordinal", -1L);
      final var outcome = outcomes.get(ordinal);
      final boolean reached = outcome != null || reached(row, peerCounters);
      if (!reached) {
        continue;
      }
      ++counts[1];
      ++reachedTotal;
      if ("applied".equals(outcome)) {
        ++counts[2];
        ++appliedTotal;
      } else if (outcome != null) {
        ++counts[3];
        ++noTarget;
      }
    }
    for (final var row : applied.rows()) {
      // an outcome row for an ordinal the plan does not carry (a plan file from another run, or a
      // control injection) is still shown, under its own kind, so it is never silently absent
      if (row.getLong("ordinal", -1L) > 0 && !byKind.containsKey(row.get("kind", "?"))) {
        byKind.computeIfAbsent(row.get("kind", "?"), ignored -> new long[4]);
      }
    }
    final long unreached = plannedTotal - reachedTotal;
    final long attemptable = reachedTotal - noTarget;
    if (schedule.path() == null) {
      metrics.put("fault_fidelity", "n/a");
    } else if (attemptable <= 0) {
      // nothing reached found a target: fidelity of nothing is undefined, and a 0 here would fail
      // a run that scheduled faults its traffic never got to
      metrics.put("fault_fidelity", "n/a");
      notes.add("no reached fault had a target - fault fidelity is undefined for this run");
    } else {
      metrics.putRate("fault_fidelity", appliedTotal / (double) attemptable, 3);
    }
    metrics.put("fault_no_target", noTarget);
    metrics.put("fault_unreached", unreached);
    out.println("Fidelity = applied / (reached - no_target) = " + appliedTotal + " / " + attemptable + " = "
        + metrics.get("fault_fidelity") + " (the gate is >= 0.90). Of " + plannedTotal + " planned fault(s), "
        + reachedTotal + " were reached by the run's traffic and " + unreached + " were not"
        + (controlInjections > 0 ? "; " + controlInjections + " control injection(s) at ordinal 0 are outside the plan" : "")
        + ". `no_target` means the schedule's ordinal came up when nothing was there to break - a scheduling"
        + " miss, not an injection failure, so it is subtracted from the denominator rather than counted"
        + " against it. An unreached fault is one the peer's counter never got to: it says the plan was longer"
        + " than the run, not that the peer failed to inject.");
    final var never = new ArrayList<String>();
    if (!byKind.isEmpty()) {
      out.println();
      out.println("| fault kind | planned | reached | applied | no_target | observed by the client |");
      out.println("|---|---:|---:|---:|---:|---:|");
      for (final var entry : byKind.entrySet()) {
        final var counts = entry.getValue();
        final var observed = jfr.faultsObserved.get(entry.getKey());
        out.println("| " + entry.getKey() + " | " + counts[0] + " | " + counts[1] + " | " + counts[2] + " | "
            + counts[3] + " | " + (observed == null ? (jfr.present() ? "0" : "-") : Long.toString(observed[0])) + " |");
        if (counts[1] - counts[3] > 0 && counts[2] == 0) {
          never.add(entry.getKey());
        }
      }
      final var unreachedKinds = new ArrayList<String>();
      for (final var entry : byKind.entrySet()) {
        if (entry.getValue()[0] > 0 && entry.getValue()[1] == 0) {
          unreachedKinds.add(entry.getKey());
        }
      }
      if (!never.isEmpty()) {
        out.println();
        // Each kind is printed as its own bracketed token, here and in the metric, so a control's
        // expectation can be grepped exactly: '[SWALLOW_PING]' matches one kind and nothing else,
        // while a bare SWALLOW_PING also matches SWALLOW_PING_AND_ANYTHING and any prose above.
        out.println("- **reached and never applied**: " + String.join(", ", bracketed(never))
            + " - the peer's counter passed every trigger of the kind and no outcome row followed; the"
            + " injection path did not run, which leaves that recovery path unexercised");
      }
      if (!unreachedKinds.isEmpty()) {
        out.println();
        out.println("- planned but never reached: " + String.join(", ", bracketed(unreachedKinds))
            + " - the run's traffic ended before their first trigger; a longer run, a higher rate or a"
            + " denser schedule is what exercises them");
        notes.add(unreachedKinds.size() + " fault kind(s) planned and never reached: "
            + String.join(", ", bracketed(unreachedKinds)));
      }
    }
    writeKindCoverage(out, byKind, peer);
    metrics.putList("fault_kinds_unapplied", String.join(",", bracketed(never)));
    writeDecodingReconciliation(out, counters);
    out.println();
    final long overBudget = jfr.present() && jfr.recoveryEvents > 0
        ? jfr.recoveryIncomplete
        : TsvReader.longValue(counters, "recovery.overBudget", 0L);
    metrics.put("recovery_over_budget", overBudget);
    // W1-H(ii)'s two outcomes, so a control row can ask for the probe's own retirement by name.
    metrics.put("swallowed_ping_probe_retirements",
        TsvReader.longValue(counters, "ws.harness.w1hProbeRetirements", 0L));
    metrics.put("swallowed_ping_other_retirements",
        TsvReader.longValue(counters, "ws.harness.w1hOtherRetirements", 0L));
    // W1-A and P7 judge the peer's rows up to a bound and wait for the tail to have read that
    // far; a judgment made after the grace with the tail still behind is counted here.
    metrics.put("recovery_judged_under_tail_lag",
        TsvReader.longValue(counters, "ws.harness.judgedUnderTailLag", 0L));
    metrics.put("replay_rows_late", TsvReader.longValue(counters, "ws.harness.replayRowsLate", 0L));
    final long superseded = TsvReader.longValue(counters, "recovery.superseded", 0L);
    final long vacuous = TsvReader.longValue(counters, "recovery.vacuous", 0L);
    if (jfr.present() && jfr.recoveryEvents > 0) {
      out.println("Recovery (`sava.soak.Recovery`): " + jfr.recoveryEvents + " episode(s), " + jfr.recoveryIncomplete
          + " incomplete, p50 " + JfrPass.millis(jfr.recoveryDuration.p(0.5d)) + " ms, p99 "
          + JfrPass.millis(jfr.recoveryDuration.p(0.99d)) + " ms, max " + JfrPass.millis(jfr.recoveryDuration.max)
          + " ms. An episode is over budget when the engine did not get back to the registrations it held before"
          + " the fault within the derived bound - that is property P7.");
      if (superseded > 0 || vacuous > 0) {
        out.println();
        out.println("- " + superseded + " window(s) superseded: the next connection-level fault on the same engine"
            + " landed while the window was still inside its budget and incomplete, so they carry no verdict"
            + " (the fault schedule is denser than the recovery budget there); " + vacuous
            + " opened with no live registration and owed nothing.");
        if (superseded > jfr.recoveryEvents) {
          notes.add(superseded + " recovery window(s) superseded against " + jfr.recoveryEvents
              + " evaluated: the fault schedule discards most of P7's evidence at this density");
        }
      }
    } else {
      out.println("Recovery: (missing) no `sava.soak.Recovery` events" + (jfr.present() ? " in the ring" : "")
          + "; `recovery_over_budget` falls back to the `recovery.overBudget` counter (" + overBudget + ").");
    }
    out.println();
    final long epochRetired = TsvReader.longValue(counters, "ws.epoch.retired", 0L);
    final long trackedRetired = TsvReader.longValue(counters, "i52.retirements", 0L);
    out.println("- retirements: " + TsvReader.longValue(counters, "ws.retirement.expected", 0L) + " expected, "
        + TsvReader.longValue(counters, "ws.retirement.unexplained", 0L) + " unexplained. A destructive fault is"
        + " SUPPOSED to retire the connection, so those are subtracted before the unexplained gate."
        + (trackedRetired > epochRetired
            ? " The #52 tracker counts " + trackedRetired + ": " + (trackedRetired - epochRetired)
              + " of its retirements happened before the attempt opened (a failed handshake), which has"
              + " no epoch to explain and is the tracker's, not this gate's."
            : ""));
  }

  /// Each name as its own bracketed token. A control row's expectation is a needle grepped out of
  /// a report, and an unbracketed list of kinds gives a needle no boundary: `[SWALLOW_PING]` is
  /// exact where `SWALLOW_PING` also hits the schedule table, a prose mention and any longer kind
  /// name that happens to start the same way.
  static List<String> bracketed(final List<String> names) {
    final var tokens = new ArrayList<String>(names.size());
    for (final var name : names) {
      tokens.add('[' + name + ']');
    }
    return tokens;
  }

  /// Kinds the plan never carried a row for.
  ///
  /// The per-kind table above is built from the schedule, so a kind that was never planned has no
  /// line there and no other evidence anywhere in the run: "nothing went wrong with it" and "it
  /// was never in the run" print identically, which is the one reading the table cannot support.
  /// This says which of the catalogue's scheduled kinds are absent, and whether the peer itself
  /// declared the omission (`SCHEDULE_OMITTED` in `peer-summary.json`) or it is unaccounted for.
  private void writeKindCoverage(final PrintWriter out,
                                 final Map<String, long[]> byKind,
                                 final PeerSummary peer) {
    final var declared = peer == null ? List.<String>of() : peer.omittedKinds();
    final var omittedByPeer = new ArrayList<String>();
    final var unaccounted = new ArrayList<String>();
    for (final var kind : SCHEDULED_FAULT_KINDS) {
      final var counts = byKind.get(kind);
      if (counts != null && counts[0] > 0) {
        continue;
      }
      (declared.contains(kind) ? omittedByPeer : unaccounted).add(kind);
    }
    // the other direction: a kind this run knows about that the catalogue above does not, which
    // means the copy has fallen behind `peer.FaultKind` and its missing-kind half is incomplete
    final var unknown = new ArrayList<String>();
    for (final var kind : byKind.keySet()) {
      if (!SCHEDULED_FAULT_KINDS.contains(kind) && !CONTROL_ONLY_FAULT_KINDS.contains(kind)) {
        unknown.add(kind);
      }
    }
    for (final var kind : declared) {
      if (!SCHEDULED_FAULT_KINDS.contains(kind) && !CONTROL_ONLY_FAULT_KINDS.contains(kind) && !unknown.contains(kind)) {
        unknown.add(kind);
      }
    }
    if (omittedByPeer.isEmpty() && unaccounted.isEmpty() && unknown.isEmpty()) {
      out.println();
      out.println("- every scheduled fault kind of `DESIGN.md` §6 has at least one planned row; control-only kinds"
          + " are not planned by design and are injected at ordinal 0 when `SOAK_CONTROL` names one");
      return;
    }
    out.println();
    if (!omittedByPeer.isEmpty()) {
      out.println("- planned zero times, declared omitted by the peer: " + String.join(", ", bracketed(omittedByPeer))
          + " - `SCHEDULE_OMITTED` in `peer-summary.json`: the kind could not be given a guaranteed instance"
          + " (too small a counter domain, or one already taken), so the peer said so rather than dropping it"
          + " silently. Nothing in this run exercises them.");
      notes.add(omittedByPeer.size() + " fault kind(s) omitted from the plan by the peer: "
          + String.join(", ", bracketed(omittedByPeer)));
    }
    if (!unaccounted.isEmpty()) {
      out.println("- **planned zero times and not declared omitted**: " + String.join(", ", bracketed(unaccounted))
          + " - these are catalogue kinds with no schedule row and no explanation from the peer. A kind absent"
          + " from the plan is otherwise invisible in this report: it exercises nothing, and the tables above"
          + " look exactly as they would if it had never existed.");
      notes.add(unaccounted.size() + " fault kind(s) never planned and not declared omitted: "
          + String.join(", ", bracketed(unaccounted)));
    }
    if (!unknown.isEmpty()) {
      out.println("- this report's copy of the §6 catalogue is STALE: " + String.join(", ", bracketed(unknown))
          + " appear in this run's artifacts and are not in `SCHEDULED_FAULT_KINDS`/`CONTROL_ONLY_FAULT_KINDS`."
          + " The missing-kind list above is therefore incomplete - add them to `SoakReport` beside"
          + " `peer.FaultKind`.");
      notes.add("SoakReport's copy of the fault catalogue is stale: " + String.join(", ", bracketed(unknown)));
    }
  }

  /// Decoding failures against the corruption that was actually served.
  ///
  /// `rpc.completed.decode` counts bodies that reached the parser and could not be read. The peer
  /// corrupts bodies on purpose, so most of those are the harness working; the number worth
  /// reading is what is left after the injected corruption is subtracted, because a run whose
  /// well-formed bodies fail to inflate or parse is a regression in exactly the code W3-C watches.
  ///
  /// The subtraction is a reconciliation, not a join: `peer-http.tsv` rows are not matched to
  /// client outcomes by request id, and it is deliberately generous to the library - it subtracts
  /// every corrupting row served, including `GZIP_TRAILING`, which a healthy client decodes
  /// cleanly by reading the first member. `decoding_failures_unexplained` is therefore a FLOOR on
  /// the decoding failures no injected fault accounts for, never an exact count, and it is
  /// reported rather than gated.
  private void writeDecodingReconciliation(final PrintWriter out, final Map<String, String> counters) throws IOException {
    final long decodeFailures = TsvReader.longValue(counters, "rpc.completed.decode", 0L);
    final var peerHttp = TsvReader.read(runDir.resolve("peer-http.tsv"), '\t', PEER_HTTP_COLUMNS);
    long corrupting = 0;
    final var byKind = new LinkedHashMap<String, long[]>();
    for (final var row : peerHttp.rows()) {
      final var kind = row.get("faultKind").strip();
      if (!BODY_CORRUPTING_FAULT_KINDS.contains(kind)) {
        continue;
      }
      ++corrupting;
      byKind.computeIfAbsent(kind, ignored -> new long[1])[0]++;
    }
    final long unexplained = Math.max(0L, decodeFailures - corrupting);
    metrics.put("decoding_failures", decodeFailures);
    metrics.put("decoding_failures_unexplained", unexplained);
    out.println();
    if (peerHttp.path() == null) {
      out.println("- decoding: " + decodeFailures + " body/bodies reached the parser and could not be read"
          + " (`rpc.completed.decode`); `peer-http.tsv` is (missing), so none of them can be attributed to an"
          + " injected corruption and `decoding_failures_unexplained` reads the full " + unexplained + ".");
      notes.add("peer-http.tsv is missing: all " + decodeFailures + " decoding failure(s) are unattributed");
      return;
    }
    final var served = byKind.isEmpty() ? "none" : counts(byKind);
    out.println("- decoding: " + decodeFailures + " body/bodies reached the parser and could not be read"
        + " (`rpc.completed.decode`), against " + corrupting + " body-corrupting response(s) the peer served"
        + " (" + served + " in `peer-http.tsv`) - **" + unexplained + " unexplained**. The subtraction is a"
        + " reconciliation, not a per-request join, and it is generous to the library: `GZIP_TRAILING` is served"
        + " as corruption here but a healthy client decodes it by reading the first member, so a positive number"
        + " is a FLOOR on the decoding failures no injected fault accounts for - the kind of failure W3-C exists"
        + " to catch. It is reported, never gated.");
    if (unexplained > 0) {
      notes.add(unexplained + " decoding failure(s) not accounted for by the body-corrupting faults the peer"
          + " served - a floor, since peer log lines can be dropped (`harness.peerLog.dropped`)");
    }
  }

  private static String counts(final Map<String, long[]> byKind) {
    final var sb = new StringBuilder();
    byKind.forEach((kind, count) ->
        sb.append(sb.isEmpty() ? "" : ", ").append(kind).append(' ').append(count[0]));
    return sb.toString();
  }

  /// What `peer-summary.json` says when the run ended: the peer's final trigger counters, and the
  /// kinds the peer itself declared it could not place in the plan (`SCHEDULE_OMITTED`). The two
  /// travel together because both answer "was this kind's absence the peer's decision?".
  private record PeerSummary(Map<String, Long> counters, List<String> omittedKinds) {
  }

  /// The peer's final counters from `peer-summary.json`, keyed `<counter>@<port>`: `conn`, `sub`
  /// and `notify` per websocket port from its `wsPorts[]` entries, `rpc` and
  /// `rpcMethod:getAccountInfo` on the HTTP port, plus its `omittedKinds[]`. Null when the file is
  /// absent or unparseable.
  private PeerSummary peerSummary() {
    final var file = runDir.resolve("peer-summary.json");
    if (!Files.isRegularFile(file)) {
      return null;
    }
    final var fields = new LinkedHashMap<String, String>();
    try {
      flatten(JsonIterator.parse(Files.readAllBytes(file)), "", fields, 0);
    } catch (final IOException | RuntimeException e) {
      notes.add("peer-summary.json is present but did not parse (" + e.getClass().getSimpleName()
          + ") - every planned fault is treated as reached");
      return null;
    }
    final var counters = new LinkedHashMap<String, Long>();
    final var httpPort = fields.get("httpPort");
    if (httpPort != null) {
      counters.put("rpc@" + httpPort, TsvReader.parseLong(fields.get("rpcRequests"), 0L));
      counters.put("rpcMethod:getAccountInfo@" + httpPort, TsvReader.parseLong(fields.get("accountInfoRequests"), 0L));
    }
    for (int i = 0; i < 64; ++i) {
      final var port = fields.get("wsPorts[" + i + "].port");
      if (port == null) {
        break;
      }
      counters.put("conn@" + port, TsvReader.parseLong(fields.get("wsPorts[" + i + "].acceptedConnections"), 0L));
      counters.put("sub@" + port, TsvReader.parseLong(fields.get("wsPorts[" + i + "].subscribeRequests"), 0L));
      counters.put("notify@" + port, TsvReader.parseLong(fields.get("wsPorts[" + i + "].notifications"), 0L));
    }
    final var omitted = new ArrayList<String>();
    for (int i = 0; i < 128; ++i) {
      final var kind = fields.get("omittedKinds[" + i + ']');
      if (kind == null) {
        break;
      }
      omitted.add(kind);
    }
    return new PeerSummary(counters, List.copyOf(omitted));
  }

  /// `trigger` is `<counter>=<n>`; the fault was reached when the peer's final value of that
  /// counter on that port is at least `n`. With no counters at all every fault counts as reached,
  /// which makes the fidelity a lower bound rather than an invented pass.
  private static boolean reached(final TsvReader.Row row, final Map<String, Long> peerCounters) {
    if (peerCounters == null) {
      return true;
    }
    final var trigger = row.get("trigger");
    final int equals = trigger.lastIndexOf('=');
    if (equals <= 0) {
      return true;
    }
    final var counter = trigger.substring(0, equals);
    final long n = TsvReader.parseLong(trigger.substring(equals + 1), Long.MAX_VALUE);
    final var value = peerCounters.get(counter + '@' + row.get("port"));
    return value != null && value >= n;
  }

  // ---------------------------------------------------------------- (e) issue 52

  private void writeIssue52(final PrintWriter out, final Issue52Report.Summary summary, final JfrPass jfr) {
    out.println();
    out.println("## (e) Issue #52 — websocket attempt attribution");
    out.println();
    out.println("```");
    out.println(summary.line());
    out.println("```");
    out.println();
    out.println("Full evidence, denominators and per-record tables: `issue52.md`.");
    jfr.writeIssue52Events(out);
    for (final var note : summary.notes()) {
      out.println("- NOTE: " + note);
    }
  }

  // ---------------------------------------------------------------- (f) harness latency

  private void writeHarnessLatency(final PrintWriter out, final Map<String, String> counters) {
    out.println();
    out.println("## (f1) Latency (harness histograms, full denominators)");
    out.println();
    final var histograms = new LinkedHashMap<String, Map<String, String>>();
    for (final var entry : counters.entrySet()) {
      if (!entry.getKey().startsWith("hist.")) {
        continue;
      }
      final int dot = entry.getKey().lastIndexOf('.');
      if (dot <= 5) {
        continue;
      }
      histograms.computeIfAbsent(entry.getKey().substring(5, dot), ignored -> new LinkedHashMap<>())
          .put(entry.getKey().substring(dot + 1), entry.getValue());
    }
    if (histograms.isEmpty()) {
      out.println("- (missing) no `hist.*` entries in `counters.properties`");
      return;
    }
    out.println("These are the measurement: every operation is counted, unlike the ring's tails, which only hold"
        + " what crossed an event threshold.");
    out.println();
    // The unit belongs in every column header, not in a sentence above the table: `Counters` writes
    // hist.<name>.{p50,p99,max} from LatencyHistogram's percentileMillis/maxMillis, so these cells are
    // milliseconds, and (f2) below prints the same figures under the same `p50 ms`/`p99 ms`/`max ms`
    // headings. One report that labelled the two halves differently read as a 1000x discrepancy.
    out.println("| histogram | count | p50 ms | p99 ms | max ms |");
    out.println("|---|---:|---:|---:|---:|");
    histograms.forEach((name, values) -> out.println("| " + name + " | " + values.getOrDefault("count", "-")
        + " | " + values.getOrDefault("p50", "-") + " | " + values.getOrDefault("p99", "-")
        + " | " + values.getOrDefault("max", "-") + " |"));
  }

  // ---------------------------------------------------------------- (g) resources

  private void writeResourceSeries(final PrintWriter out,
                                   final Map<String, String> runEnv,
                                   final TsvReader.Table clientCsv,
                                   final Map<String, String> counters,
                                   final TsvReader.Table phases) throws IOException {
    // The same window the verdict fits: up to the QUIESCE boundary, or the whole file without one.
    long steadyEnd = Long.MAX_VALUE;
    for (final var row : phases.rows()) {
      if ("QUIESCE".equals(row.get("name", ""))) {
        steadyEnd = row.getLong("epochMillis", Long.MAX_VALUE) / 1_000L;
        break;
      }
    }
    out.println();
    out.println("## (g1) Resources (whole-run series)");
    out.println();
    final double rssLimit = TsvReader.doubleValue(runEnv, "SOAK_RSS_SLOPE_KIB_PER_HOUR", DEFAULT_RSS_SLOPE_KIB_PER_HOUR);
    final double rssFloor = TsvReader.doubleValue(runEnv, "SOAK_RSS_NOISE_FLOOR_KIB", DEFAULT_RSS_NOISE_FLOOR_KIB);
    final double heapLimit = TsvReader.doubleValue(runEnv, "SOAK_HEAP_FLOOR_SLOPE_KIB_PER_HOUR",
        DEFAULT_HEAP_FLOOR_SLOPE_KIB_PER_HOUR);
    // Its own floor: RSS's carries the JIT and class-data drift of a JVM's first minutes, which the
    // after-GC heap does not, and borrowing it let the validate RSS floor swallow D10's retention.
    final double heapFloor = TsvReader.doubleValue(runEnv, "SOAK_HEAP_FLOOR_NOISE_KIB", DEFAULT_RSS_NOISE_FLOOR_KIB);
    // The run's own warm-up, which the runner passes to its awk fits as `skip`. A run.env without
    // it reads 0, and the 60 s floor inside Slopes still applies — the same order soak.sh takes.
    final double warmupSeconds = TsvReader.doubleValue(runEnv, "SOAK_WARMUP_SECONDS", 0d);

    final var rss = Slopes.rss(runDir.resolve("rss.csv"), rssLimit, rssFloor, warmupSeconds, steadyEnd);
    final var nmt = Slopes.nmt(runDir.resolve("nmt.csv"), rssLimit, rssFloor, warmupSeconds);
    final var heap = Slopes.heapAfterGc(runDir.resolve("client.csv"), heapLimit, heapFloor, warmupSeconds);

    out.println("Three series, each fitted over the samples that survive a warm-up drop of"
        + " `max(SOAK_WARMUP_SECONDS, 60 s, span / 10)` = "
        + String.format(Locale.ROOT, "%.0f", Math.max(Math.max(warmupSeconds, Slopes.MIN_WARMUP_SECONDS), 0d))
        + " s or more (the span term is per series), with the noise-floor rule"
        + " `max(limit, floor / hours)` applied to each. That drop is the rule `soak.sh`'s own gate applies to"
        + " the same files, so a slope below is the slope the verdict reads; a series with fewer than three"
        + " samples left is `not measured` on both sides rather than a fit of the JVM's start-up ramp:");
    out.println();
    out.println("- " + rss.describe());
    out.println("- " + nmt.describe() + (nmt.measured() ? "" : " (the gated column is the total less Arena Chunk"
        + " and Tracing, both of which move by megabytes with nothing leaked)"));
    out.println("- " + heap.describe());
    out.println();
    out.println("The after-GC heap floor is the retention metric: RSS and NMT move with the recorder's own buffers"
        + " and with arena churn, and a heap that never comes back down after a collection is the one signal that"
        + " means the application is holding objects.");
    if (heap.measured()) {
      metrics.putRate("heap_after_gc_slope_kib_per_hour", heap.slopeKibPerHour(), 1);
      metrics.putRate("heap_after_gc_limit_kib_per_hour", heap.appliedLimitKibPerHour(), 1);
      metrics.put("heap_after_gc_first_kb", Math.round(heap.firstKib()));
      metrics.put("heap_after_gc_last_kb", Math.round(heap.lastKib()));
    } else {
      // A slope that could not be fitted is `n/a` with a NOTE, never a number the verdict can fail
      // on: "not measured" is a gap in the evidence, not evidence of growth. It is `n/a` rather
      // than the 0 this key used to default to because the two readings differ — the runner has a
      // branch for `n/a` that raises a NOTE, while 0 is a measured slope of zero, which is exactly
      // what a run too short to fit does NOT know. Slopes now refuses to fit a start-up ramp, so
      // this arm is reached by every run shorter than its own warm-up window.
      metrics.put("heap_after_gc_slope_kib_per_hour", "n/a");
      notes.add("after-GC heap slope not measured (" + heap.note() + ") — the retention gate had nothing to read");
    }
    if (rss.exceeded()) {
      notes.add("RSS slope " + rss.slope() + " KiB/h exceeds the applied limit "
          + String.format(Locale.ROOT, "%.1f", rss.appliedLimitKibPerHour()) + " KiB/h");
    }
    if (nmt.exceeded()) {
      notes.add("gated native-memory slope " + nmt.slope() + " KiB/h exceeds its applied limit");
    }
    if (heap.exceeded()) {
      notes.add("after-GC heap floor slope " + heap.slope() + " KiB/h exceeds its applied limit");
    }
    out.println();
    if (clientCsv.rows().isEmpty()) {
      out.println("- (missing) `client.csv` — no gauge series, so thread, fd and virtual-thread balances have no"
          + " whole-run witness");
      return;
    }
    final var first = clientCsv.rows().getFirst();
    final var last = clientCsv.rows().getLast();
    out.println("Gauge series (`client.csv`, " + clientCsv.rows().size() + " sample(s)"
        + (clientCsv.partialLines() > 0 ? ", " + clientCsv.partialLines() + " truncated line(s) skipped" : "") + "):");
    out.println();
    out.println("| gauge | first | last | delta |");
    out.println("|---|---:|---:|---:|");
    for (final var column : List.of("threads", "fds", "vt_started", "vt_ended", "open_conns", "live_subs",
        "inflight_rpc", "overdue_rpc", "commonpool_queued", "commonpool_active")) {
      if (!clientCsv.has(column)) {
        continue;
      }
      final long from = first.getLong(column, 0L);
      final long to = last.getLong(column, 0L);
      final long delta = to - from;
      out.println("| " + column + " | " + from + " | " + to + " | " + (delta > 0 ? "+" + delta : Long.toString(delta)) + " |");
    }
    if (clientCsv.has("tail_lag_ms")) {
      // The age of the newest peer row the harness had read at each sample: how far the oracles
      // that read the peer's log trail the wire. Its maximum is what the two judgments that wait
      // for the tail (W1-A, P7) are protected against.
      long tailLagMax = 0L;
      for (final var row : clientCsv.rows()) {
        tailLagMax = Math.max(tailLagMax, row.getLong("tail_lag_ms", 0L));
      }
      metrics.put("tail_lag_max_ms", tailLagMax);
      out.println("| tail_lag_ms (max over samples) | - | - | " + tailLagMax + " |");
    }
    final long vtStarted = last.getLong("vt_started", TsvReader.longValue(counters, "jfr.vtStarted", 0L));
    final long vtEnded = last.getLong("vt_ended", TsvReader.longValue(counters, "jfr.vtEnded", 0L));
    out.println();
    out.println("- virtual threads started - ended at the last sample: " + (vtStarted - vtEnded)
        + " (the gate is on this after DRAIN; virtual threads are invisible to `jdk.ThreadDump`, so this balance"
        + " is the only way a leaked one shows up)");
  }

  // ---------------------------------------------------------------- (h) residuals

  private void writeResiduals(final PrintWriter out, final TsvReader.Table clientCsv, final Map<String, String> counters) {
    out.println();
    out.println("## (h) Quiescence residuals");
    out.println();
    if (clientCsv.rows().isEmpty()) {
      out.println("- (missing) `client.csv`");
      return;
    }
    out.println("What the engine still held when the work stopped. The four `retained_*` columns are the"
        + " reflective probes the engine exposes to its own tests: `retained_regs` durable registrations,"
        + " `retained_tombstones` cancellation tombstones, `retained_ordinals` killed subscription ids plus"
        + " attempt sequences, and `retained_exc_subs` exception subscribers. A `-1` means the package was not"
        + " opened and the probe read nothing — never that it read zero.");
    out.println();
    out.println("Not measured here: the documented `retiredSubIds` residue — one retired subscription id per"
        + " replayed casualty cancelled before its re-send, kept until the next reconnect. The engine exposes no"
        + " seam for that set, so this harness cannot count it, and no figure in this section is net of it. Until"
        + " an accessor exists beside the four above, that residue is invisible from here: growth in it would"
        + " show up in no column below, so the quiescence account this section gives is the four probes and the"
        + " gauges, not the whole of what the engine holds.");
    out.println();
    out.println("| phase | live_subs | pending_confirm | inflight_rpc | overdue_rpc | open_conns |"
        + " retained_regs | retained_tombstones | retained_ordinals | retained_exc_subs |");
    out.println("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
    final var lastByPhase = new LinkedHashMap<String, TsvReader.Row>();
    for (final var row : clientCsv.rows()) {
      lastByPhase.put(row.get("phase", "?"), row);
    }
    lastByPhase.forEach((phase, row) -> out.println("| " + phase
        + " | " + row.get("live_subs", "-")
        + " | " + row.get("pending_confirm", "-")
        + " | " + row.get("inflight_rpc", "-")
        + " | " + row.get("overdue_rpc", "-")
        + " | " + row.get("open_conns", "-")
        + " | " + row.get("retained_regs", "-")
        + " | " + row.get("retained_tombstones", "-")
        + " | " + row.get("retained_ordinals", "-")
        + " | " + row.get("retained_exc_subs", "-") + " |"));
    out.println();
    out.println("- zombie notifications (consumer invoked after an unsubscribe ack): "
        + TsvReader.longValue(counters, "ws.notifications.zombie", 0L)
        + "; unknown-sub notifications tolerated: " + TsvReader.longValue(counters, "ws.notifications.unknownSub", 0L));
    out.println("- sequence observations: ok " + TsvReader.longValue(counters, "ws.notifications.sequence.ok", 0L)
        + ", gap " + TsvReader.longValue(counters, "ws.notifications.sequence.gap", 0L)
        + ", dup " + TsvReader.longValue(counters, "ws.notifications.sequence.dup", 0L)
        + ", reorder " + TsvReader.longValue(counters, "ws.notifications.sequence.reorder", 0L)
        + " — a gap across a connection epoch is NOT a defect (a new connection resets to monotone-any-gap);"
        + " within one epoch it is.");
  }

  // ---------------------------------------------------------------- (j) verdict inputs

  private void writeVerdictInputs(final PrintWriter out, final Issue52Report.Summary summary) {
    out.println();
    out.println("## (j) Verdict inputs");
    out.println();
    out.println("Everything the runner's verdict reads is below and in `counters.properties`, `properties.tsv`"
        + " and the csv series. It never parses this markdown — that rule is what lets this file be rewritten"
        + " freely.");
    out.println();
    out.println("| metric | value |");
    out.println("|---|---|");
    metrics.asMap().forEach((key, value) -> out.println("| " + key + " | " + value + " |"));
    out.println();
    out.println("`i52_trigger_met=1` is not a failure: with every other gate green it is PASS-WITH-FINDING"
        + " (exit 2). Issue #52 never contributes a FAIL.");
    if (notes.isEmpty()) {
      out.println();
      out.println("- no report-level notes");
      return;
    }
    out.println();
    out.println("Notes raised while building this report:");
    out.println();
    for (final var note : notes) {
      out.println("- " + note);
    }
    if (!summary.notes().isEmpty()) {
      for (final var note : summary.notes()) {
        out.println("- (issue52) " + note);
      }
    }
  }
}
