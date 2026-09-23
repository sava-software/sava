package software.sava.rpc.soak;

import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.services.solana.websocket.WebSocketManager;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

/// What this run actually ran: the working tree's revision and dirty flag, the JDK, the garbage
/// collector, the resolved module path, and — the one that decides whether a result means
/// anything — where the subject's class files came from.
///
/// The subject check is a gate, not a note. A composite build that silently resolved
/// `software.sava:sava-rpc` from the Gradle cache instead of the local project produces a run
/// that passes or fails against a published jar while claiming to test the working tree, and
/// nothing later in the pipeline can tell the difference. So `SolanaRpcWebsocket`'s code source
/// must sit under `<savaRoot>/sava-rpc/build/`; anything else is `INVALID` (exit 4).
public record RunIdentity(String gitRevision,
                          boolean dirty,
                          String javaHome,
                          String javaVersion,
                          String javaVendor,
                          String garbageCollectors,
                          String subjectCodeSource,
                          String consumerCodeSource,
                          boolean subjectValid,
                          String subjectExpectedUnder,
                          String modulePath,
                          String savaRoot,
                          long pid,
                          long startEpochMillis,
                          long startNanoTime) {

  /// Lets the `control` workload D14 report a deliberately wrong subject, which is how the
  /// runner's own INVALID path is proved to work without breaking the build wiring. Nothing
  /// else sets it.
  public static final String SUBJECT_OVERRIDE_PROPERTY = "soak.subject.codeSource";

  /// Captures identity from the running JVM. `savaRoot` is derived from this module's own code
  /// source rather than configured, so there is no key an operator can point at the wrong tree.
  public static RunIdentity capture() {
    final var soakSource = codeSourceOf(RunIdentity.class);
    final var savaRoot = savaRoot(soakSource);
    final var subject = System.getProperty(SUBJECT_OVERRIDE_PROPERTY, codeSourceOf(SolanaRpcWebsocket.class));
    final var expectedUnder = savaRoot == null ? "" : savaRoot.resolve("sava-rpc").resolve("build").toString();
    final boolean valid = savaRoot != null && subject != null && underPath(subject, expectedUnder);
    final var gcs = new StringJoiner("+");
    for (final var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      gcs.add(bean.getName());
    }
    final var git = savaRoot == null ? new String[]{"unknown", "false"} : gitState(savaRoot);
    return new RunIdentity(
        git[0],
        Boolean.parseBoolean(git[1]),
        System.getProperty("java.home", ""),
        System.getProperty("java.version", ""),
        System.getProperty("java.vendor", ""),
        gcs.toString(),
        subject == null ? "" : subject,
        codeSourceOf(WebSocketManager.class),
        valid,
        expectedUnder,
        System.getProperty("jdk.module.path", ""),
        savaRoot == null ? "" : savaRoot.toString(),
        ProcessHandle.current().pid(),
        System.currentTimeMillis(),
        System.nanoTime());
  }

  public String toJson() {
    final var json = new StringBuilder(1024);
    json.append("{\n");
    field(json, "gitRevision", gitRevision, true);
    json.append("  \"dirty\": ").append(dirty).append(",\n");
    field(json, "javaHome", javaHome, true);
    field(json, "javaVersion", javaVersion, true);
    field(json, "javaVendor", javaVendor, true);
    field(json, "garbageCollectors", garbageCollectors, true);
    field(json, "subjectCodeSource", subjectCodeSource, true);
    field(json, "consumerCodeSource", consumerCodeSource, true);
    json.append("  \"subjectValid\": ").append(subjectValid).append(",\n");
    field(json, "subjectExpectedUnder", subjectExpectedUnder, true);
    field(json, "modulePath", modulePath, true);
    field(json, "savaRoot", savaRoot, true);
    json.append("  \"pid\": ").append(pid).append(",\n");
    json.append("  \"startEpochMillis\": ").append(startEpochMillis).append(",\n");
    json.append("  \"startNanoTime\": ").append(startNanoTime).append('\n');
    json.append("}\n");
    return json.toString();
  }

  /// The timings the run used, as a JSON object the report can quote beside the identity. Kept
  /// separate from [#toJson()] because identity answers "what ran" and this answers "under
  /// which bounds", and a reader conflating them is how a bound gets attributed to the wrong
  /// revision.
  public static String timingsJson(final SoakConfig config) {
    final var json = new StringBuilder(512);
    json.append("{\n");
    json.append("  \"profile\": \"").append(config.profile().lowerName()).append("\",\n");
    json.append("  \"seed\": \"0x").append(Long.toHexString(config.seed())).append("\",\n");
    json.append("  \"durationSeconds\": ").append(config.durationSeconds()).append(",\n");
    json.append("  \"warmupSeconds\": ").append(config.warmupSeconds()).append(",\n");
    json.append("  \"quiesceSeconds\": ").append(config.quiesceSeconds()).append(",\n");
    json.append("  \"drainSeconds\": ").append(config.drainSeconds()).append(",\n");
    json.append("  \"pingDelayMillis\": ").append(config.pingDelayMillis()).append(",\n");
    json.append("  \"checkDelayMillis\": ").append(config.checkDelayMillis()).append(",\n");
    json.append("  \"connectTimeoutMillis\": ").append(config.connectTimeoutMillis()).append(",\n");
    json.append("  \"reconnectDelayMillis\": ").append(config.reconnectDelayMillis()).append(",\n");
    json.append("  \"subscriptionResendMillis\": ").append(config.subscriptionResendMillis()).append('\n');
    json.append("}\n");
    return json.toString();
  }

  private static void field(final StringBuilder json, final String name, final String value,
                            final boolean comma) {
    json.append("  \"").append(name).append("\": \"").append(escapeJson(value)).append('"')
        .append(comma ? ",\n" : "\n");
  }

  public static String escapeJson(final String value) {
    if (value == null) {
      return "";
    }
    final var out = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); ++i) {
      final char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }

  public static String codeSourceOf(final Class<?> type) {
    final CodeSource codeSource = type.getProtectionDomain().getCodeSource();
    return codeSource == null || codeSource.getLocation() == null
        ? "<none: " + type.getModule().getName() + " has no code source>"
        : codeSource.getLocation().toString();
  }

  private static boolean underPath(final String codeSource, final String expectedUnder) {
    if (expectedUnder.isEmpty()) {
      return false;
    }
    final var normalised = codeSource.startsWith("file:") ? codeSource.substring("file:".length()) : codeSource;
    return normalised.startsWith(expectedUnder);
  }

  /// Walks up from this module's code source to the directory that holds both `sava-rpc` and
  /// `soak`, which is the sava working tree by construction.
  static Path savaRoot(final String soakCodeSource) {
    if (soakCodeSource == null || !soakCodeSource.startsWith("file:")) {
      return null;
    }
    Path path;
    try {
      path = Path.of(java.net.URI.create(soakCodeSource)).toAbsolutePath().normalize();
    } catch (final RuntimeException e) {
      return null;
    }
    while (path != null) {
      if (Files.isDirectory(path.resolve("sava-rpc")) && Files.isDirectory(path.resolve("soak"))) {
        return path;
      }
      path = path.getParent();
    }
    return null;
  }

  /// `git rev-parse HEAD` and `git status --porcelain` in the sava root. Bounded at ten seconds
  /// each and degrading to `unknown` rather than failing the run: a soak that cannot name its
  /// revision is worth reporting, not worth refusing to start.
  static String[] gitState(final Path savaRoot) {
    final var revision = runGit(savaRoot, List.of("git", "rev-parse", "HEAD"));
    final var status = runGit(savaRoot, List.of("git", "status", "--porcelain"));
    return new String[]{
        revision == null || revision.isBlank() ? "unknown" : revision.strip(),
        String.valueOf(status != null && !status.isBlank())
    };
  }

  private static String runGit(final Path directory, final List<String> command) {
    try {
      final var process = new ProcessBuilder(command)
          .directory(directory.toFile())
          .redirectErrorStream(false)
          .start();
      final var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(10L, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return null;
      }
      return process.exitValue() == 0 ? output : null;
    } catch (final IOException e) {
      return null;
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /// One line for `client.log`, so a run that turns out to be invalid says why in its own log
  /// rather than only in the runner's.
  public List<String> describe() {
    final var lines = new ArrayList<String>(8);
    lines.add("revision      = " + gitRevision + (dirty ? " (dirty working tree)" : ""));
    lines.add("jdk           = " + javaVersion + " " + javaVendor + " at " + javaHome);
    lines.add("gc            = " + garbageCollectors);
    lines.add("subject       = " + subjectCodeSource);
    lines.add("consumer      = " + consumerCodeSource);
    lines.add("subject valid = " + subjectValid + " (must be under " + subjectExpectedUnder + ')');
    return lines;
  }
}
