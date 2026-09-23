package software.sava.rpc.soak;

import software.sava.rpc.soak.events.SoakEvents;
import software.sava.rpc.soak.issue52.ManagerLogCapture;
import software.sava.rpc.soak.issue52.RetirementDetector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/// The client JVM: the process the subject lives in.
///
/// It runs a fixed sequence before it will admit to being ready, and every step of it is a gate
/// rather than a log line, because each one is a way a soak can produce a confident, meaningless
/// result: the configuration must parse with no unknown key; the subject's class files must come
/// from this working tree and not from a cached jar; redaction must demonstrably work on a
/// synthetic secret before any endpoint is touched; and the issue #52 detector must pass its own
/// synthetic test before it is allowed to classify anything. Only then does it print `READY`.
///
/// It prints `SOAK_EXIT <code>` as its last line and exits with that code: 0 complete, 3
/// incomplete (a bounded stage breached), 4 invalid (subject mismatch, a failed self-test, an
/// unknown configuration key). `INCOMPLETE` and `INVALID` are deliberately not `FAIL` — nothing
/// about the subject was disproved in either case, and a harness that reports its own troubles as
/// the library's defects is worse than one that reports nothing.
public final class Main {

  public static final int EXIT_COMPLETE = 0;
  public static final int EXIT_INCOMPLETE = 3;
  public static final int EXIT_INVALID = 4;

  private Main() {
  }

  public static void main(final String[] args) {
    final int exit = run(args);
    System.out.println("SOAK_EXIT " + exit);
    System.out.flush();
    System.exit(exit);
  }

  private static int run(final String[] args) {
    if (args.length < 1) {
      System.err.println("usage: software.sava.rpc.soak.Main <runDir|runDir/run.env>");
      return EXIT_INVALID;
    }
    final var argument = Path.of(args[0]).toAbsolutePath().normalize();
    final var runDir = "run.env".equals(String.valueOf(argument.getFileName())) ? argument.getParent() : argument;

    final SoakConfig config;
    try {
      config = SoakConfig.load(runDir);
    } catch (final IllegalArgumentException e) {
      System.err.println("INVALID: " + e.getMessage());
      return EXIT_INVALID;
    } catch (final IOException e) {
      System.err.println("INVALID: could not read " + runDir.resolve("run.env") + ": " + e);
      return EXIT_INVALID;
    }
    System.out.println("config = " + config);

    final var identity = RunIdentity.capture();
    for (final var line : identity.describe()) {
      System.out.println(line);
    }
    try {
      writeRunClientJson(config, identity);
    } catch (final IOException e) {
      System.err.println("soak: could not write run-client.json: " + e);
    }
    if (!identity.subjectValid()) {
      System.err.println("INVALID: the subject was not built from this working tree. "
          + "SolanaRpcWebsocket came from " + identity.subjectCodeSource()
          + ", which is not under " + identity.subjectExpectedUnder()
          + " - the composite substitution did not take, and the run would be testing a published jar.");
      return EXIT_INVALID;
    }
    if (!Redaction.selfTest()) {
      System.err.println("INVALID: the redaction self-test failed; artifacts could carry a credential.");
      return EXIT_INVALID;
    }
    if (!detectorSelfTest()) {
      System.err.println("INVALID: the issue #52 detector failed its own synthetic test.");
      return EXIT_INVALID;
    }
    // The manager's reconnect decisions are visible only in its log, and a handler attached after
    // the first engine exists would miss exactly the line the #52 evidence is built from. So the
    // process-wide JUL handler goes on before any workload - and therefore any engine - is built;
    // the websocket driver's own install() call is then the idempotent second caller.
    ManagerLogCapture.install();
    System.out.println("manager log capture installed on " + ManagerLogCapture.MANAGER_LOGGER
        + " and " + ManagerLogCapture.ENGINE_LOGGER);

    final var counters = new Counters();
    try (final var ctx = SoakContext.open(config, counters)) {
      return drive(ctx, counters);
    } catch (final IOException e) {
      System.err.println("INVALID: could not open the run context: " + e);
      return EXIT_INVALID;
    }
  }

  private static int drive(final SoakContext ctx, final Counters counters) {
    final var runDir = ctx.runDir();
    final var workloads = new ArrayList<Workload>();
    final Phases phases;
    try {
      phases = Phases.open(ctx, workloads);
    } catch (final IOException e) {
      System.err.println("INVALID: could not open phases.tsv: " + e);
      return EXIT_INVALID;
    }

    final var exited = new AtomicBoolean();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      if (exited.get()) {
        return;
      }
      // A killed run still owes its numbers: the artifacts written here are what the runner reads
      // when the phase machine never reached SHUTDOWN.
      try {
        counters.writeProperties(runDir.resolve("counters.properties"));
        ctx.properties().write(runDir.resolve("properties.tsv"));
      } catch (final IOException e) {
        System.err.println("soak: the shutdown hook could not write the artifacts: " + e);
      }
      phases.abort();
    }, "soak-shutdown"));

    JfrCounters jfrCounters = null;
    GaugeSampler gauges = null;
    Phases.Result result;
    try {
      phases.startup();
      workloads.addAll(Workloads.create(ctx));
      for (final var workload : workloads) {
        workload.start(ctx);
        System.out.println("started workload " + workload.name());
      }
      if (workloads.isEmpty()) {
        System.out.println("no workloads are wired; this run records, gauges and shuts down without load");
      }
      // Only now: the stream's consumer thread is not a daemon, so a JVM whose start-up failed
      // above must still be able to exit on its own.
      jfrCounters = JfrCounters.start(counters);
      gauges = GaugeSampler.start(ctx.config(), counters, jfrCounters, ctx.phase(), runDir);
      ctx.attachGauges(gauges);
      phases.gauges(gauges);
      gauges.sampleNow();
      System.out.println("READY");
      System.out.flush();
      result = phases.drive();
    } catch (final Exception e) {
      result = new Phases.Result(false, "start-up failed: " + Redaction.text(e.toString()));
      e.printStackTrace(System.err);
    } finally {
      if (jfrCounters != null) {
        jfrCounters.close(JfrCounters.CLOSE_BOUND);
      }
      if (gauges != null) {
        gauges.close();
      }
    }

    commitVerdict(ctx, result);
    try {
      counters.writeProperties(runDir.resolve("counters.properties"));
      ctx.properties().write(runDir.resolve("properties.tsv"));
    } catch (final IOException e) {
      System.err.println("soak: could not write the final artifacts: " + e);
    }
    exited.set(true);
    phases.close();
    report(ctx, result);
    return result.complete() ? EXIT_COMPLETE : EXIT_INCOMPLETE;
  }

  /// The verdict event carries what this process knows: whether every stage completed, which
  /// grade-A/B/C properties failed or were never exercised. The PASS/FAIL decision itself belongs
  /// to `soak.sh`, which reads `counters.properties`, `properties.tsv` and
  /// `jfr-metrics.properties` - never markdown, and never this event.
  private static void commitVerdict(final SoakContext ctx, final Phases.Result result) {
    final var event = new SoakEvents.Verdict();
    event.status = result.complete() ? "COMPLETE" : "INCOMPLETE";
    event.reasons = result.reason();
    final var failures = ctx.properties().fatalFailures();
    final var unexercised = ctx.properties().unexercised();
    event.notes = "failed=" + String.join(",", failures) + "; unexercised=" + String.join(",", unexercised);
    event.issue52 = "retirements=" + ctx.counters().get(Counters.I52_RETIREMENTS)
        + " claims=" + ctx.counters().get(Counters.I52_CLAIMS)
        + " misattributed=" + ctx.counters().get(Counters.I52_MISATTRIBUTED);
    event.commit();
  }

  private static void report(final SoakContext ctx, final Phases.Result result) {
    System.out.println("phases     = " + (result.complete() ? "complete" : "INCOMPLETE: " + result.reason()));
    System.out.println("failed     = " + ctx.properties().fatalFailures());
    System.out.println("unexercised= " + ctx.properties().unexercised());
    System.out.println("skipped ops= " + ctx.counters().get(Counters.HARNESS_OPS_SKIPPED_INFLIGHT)
        + " of " + ctx.counters().get(Counters.HARNESS_OPS_ATTEMPTED) + " attempted");
    System.out.println("tsv dropped= " + ctx.counters().get(Counters.HARNESS_TSV_DROPPED));
  }

  /// `run-client.json`: the client's half of the run's identity. `soak.sh` owns `run.json`,
  /// because it knows the command line, the `.jfc` digest and the fault-schedule digest; the
  /// client writes beside it rather than read-modify-writing a file another process appends to.
  private static void writeRunClientJson(final SoakConfig config, final RunIdentity identity)
      throws IOException {
    final var json = new StringBuilder(4096);
    json.append("{\n  \"identity\": ").append(identity.toJson().stripTrailing()).append(",\n");
    json.append("  \"timings\": ").append(RunIdentity.timingsJson(config).stripTrailing()).append(",\n");
    json.append("  \"config\": {\n");
    final var resolved = config.resolved();
    final var keys = new ArrayList<>(SoakConfig.defaults(config.profile()).keySet());
    for (int i = 0; i < keys.size(); ++i) {
      final var key = keys.get(i);
      json.append("    \"").append(key).append("\": \"")
          .append(RunIdentity.escapeJson(resolved.getOrDefault(key, ""))).append('"')
          .append(i + 1 < keys.size() ? ",\n" : "\n");
    }
    json.append("  }\n}\n");
    final var runDir = config.runDir();
    Files.createDirectories(runDir);
    Files.writeString(runDir.resolve("run-client.json"), json, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
  }

  /// The STARTUP gate on the issue #52 detector: its synthetic-frame self-test exercises nothing
  /// but arithmetic and enum plumbing, so a failure means the detector - not the run - is broken,
  /// and every verdict it would later stamp would be unfalsifiable. Hence `INVALID`, not a note.
  private static boolean detectorSelfTest() {
    final long startNanos = System.nanoTime();
    final boolean passed;
    try {
      passed = RetirementDetector.selfTest();
    } catch (final RuntimeException e) {
      System.err.println("detector self-test threw: " + e);
      return false;
    }
    System.out.println("detector self-test " + (passed ? "passed" : "FAILED") + " in "
        + Duration.ofNanos(System.nanoTime() - startNanos).toMillis() + " ms");
    return passed;
  }

  /// The workloads a run drives, for a caller that wants the list without starting one.
  static List<Workload> workloads(final SoakContext ctx) {
    return Workloads.create(ctx);
  }
}
