import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Defensive controls for Gradle's dependency verification, using disposable keys and artifacts.
 * Run from the checkout root with a JDK 25+, GnuPG and an installed pinned Gradle distribution:
 * java --source 25 gradle/verification/VerificationControls.java --work-dir /tmp/new-controls
 * No package credentials, external Maven dependencies or shared Gradle/GPG writes are needed.
 * The disposable GnuPG home lives under /tmp, outside the work directory: its agent socket must
 * fit the Unix socket path limit (about 104 bytes on macOS) whatever the work directory is.
 */
public final class VerificationControls {

  private static final String REPORT = "dependency-verification-report.html";
  private static final Pattern BODIES = Pattern.compile("<tbody>(.*?)</tbody>", Pattern.DOTALL);
  private static final Pattern ROWS = Pattern.compile("<tr\\b[^>]*>(.*?)</tr>", Pattern.DOTALL);
  private static final String MISSING_CHECKSUM = "Checksums are missing";
  // Child processes see only these variables, so nothing in the caller's environment (JAVA_OPTS,
  // GRADLE_OPTS, init scripts, package credentials) can move or reconfigure the fixture builds.
  private static final Set<String> INHERITED = Set.of("PATH", "HOME", "USER", "LOGNAME", "TMPDIR", "LANG");
  private static final Path TEMP = Path.of("/tmp");
  private final Path checkout;
  private final Path work;
  private final Path gradleHome;
  private final Map<String, String> environment = new HashMap<>();
  private final Map<String, Path> artifacts = new HashMap<>();
  private Path gpgHome;
  private HttpServer server;
  private ExecutorService httpExecutor;
  private volatile Process activeProcess;
  private String repositoryUrl;
  private List<String> keys;
  private int gpgCommand;
  private int controlsPassed;
  private boolean closed;

  private VerificationControls(final Path checkout, final Path work) {
    this.checkout = checkout;
    this.work = work;
    this.gradleHome = work.resolve("gradle-home");
    System.getenv().forEach((name, value) -> {
      if (INHERITED.contains(name) || name.startsWith("LC_")) {
        environment.put(name, value);
      }
    });
    environment.put("GRADLE_USER_HOME", gradleHome.toString());
    environment.put("JAVA_HOME", System.getProperty("java.home"));
  }

  public static void main(final String[] args) throws Exception {
    var checkout = Path.of("").toAbsolutePath().normalize();
    Path work = null;
    for (int i = 0; i < args.length; ++i) {
      if (args[i].equals("--help")) {
        System.out.println("Usage: java --source 25 gradle/verification/VerificationControls.java "
            + "--work-dir <new-directory> [--checkout <checkout-root>]");
        return;
      }
      if ((!args[i].equals("--checkout") && !args[i].equals("--work-dir")) || i + 1 == args.length) {
        throw new IllegalArgumentException("Expected --work-dir <new-directory> [--checkout <checkout-root>]");
      }
      final var option = args[i];
      final var value = Path.of(args[++i]).toAbsolutePath().normalize();
      if (option.equals("--checkout")) {
        checkout = value;
      } else {
        work = value;
      }
    }
    if (work == null) {
      throw new IllegalArgumentException("--work-dir is required");
    }
    final var controls = new VerificationControls(checkout, work);
    // Resolve the installed distribution before creating anything; never invoke the shared Gradle home.
    final var distribution = controls.findDistribution();
    Files.createDirectory(work); // Also refuses dangling symlinks and a pre-existing evidence directory.
    final var cleanup = new Thread(controls::close, "verification-controls-cleanup");
    Runtime.getRuntime().addShutdownHook(cleanup);
    try {
      controls.prepare(distribution);
      controls.runControls();
      System.out.println("All " + controls.controlsPassed + " controls passed. Evidence: " + work);
    } finally {
      controls.close();
      Runtime.getRuntime().removeShutdownHook(cleanup);
    }
  }

  private Path findDistribution() throws Exception {
    final var properties = new Properties();
    try (var reader = Files.newBufferedReader(checkout.resolve("gradle/wrapper/gradle-wrapper.properties"))) {
      properties.load(reader);
    }
    final var distributionUrl = properties.getProperty("distributionUrl");
    if (distributionUrl == null) throw new IOException("Missing wrapper distributionUrl");
    final var zip = Path.of(URI.create(distributionUrl).getPath()).getFileName().toString();
    if (!zip.matches("gradle-.+-(bin|all)\\.zip")) {
      throw new IOException("Unsupported distributionUrl: expected a Gradle bin/all ZIP");
    }
    final var directory = zip.substring(0, zip.length() - 4);
    final var installation = directory.substring(0, directory.lastIndexOf('-'));
    final var homes = new ArrayList<Path>();
    var callerHome = System.getenv("GRADLE_USER_HOME");
    if (callerHome != null && !callerHome.isBlank()) {
      if (callerHome.equals("~")) {
        callerHome = System.getProperty("user.home");
      } else if (callerHome.startsWith("~/")) {
        callerHome = System.getProperty("user.home") + callerHome.substring(1);
      }
      homes.add(Path.of(callerHome));
    }
    homes.add(Path.of(System.getProperty("user.home"), ".gradle"));
    for (final var home : homes) {
      final var root = home.resolve("wrapper/dists").resolve(directory);
      if (!Files.isDirectory(root)) {
        continue;
      }
      final var found = new ArrayList<Path>();
      try (final var children = Files.newDirectoryStream(root)) {
        for (final var child : children) {
          final var candidate = child.resolve(installation);
          if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            found.add(candidate);
          }
        }
      }
      if (found.size() == 1) {
        return found.getFirst().toRealPath();
      }
      if (!found.isEmpty()) {
        throw new IOException("Expected one installed " + directory + "; found " + found.size());
      }
    }
    throw new IOException("No installed " + directory + " distribution; install the pinned wrapper before running controls");
  }

  private void prepare(final Path distribution) throws Exception {
    final var sourceParent = distribution.getParent();
    final var privateParent = gradleHome.resolve("wrapper/dists")
        .resolve(sourceParent.getParent().getFileName()).resolve(sourceParent.getFileName());
    copyTree(distribution, privateParent.resolve(distribution.getFileName()));
    final var zip = sourceParent.getParent().getFileName() + ".zip";
    Files.createFile(privateParent.resolve(zip + ".ok"));
    final var wrapper = work.resolve("wrapper/gradle/wrapper");
    Files.createDirectories(wrapper);
    Files.copy(checkout.resolve("gradlew"), work.resolve("wrapper/gradlew"), StandardCopyOption.COPY_ATTRIBUTES);
    for (final var name : List.of("gradle-wrapper.jar", "gradle-wrapper.properties")) {
      Files.copy(checkout.resolve("gradle/wrapper").resolve(name), wrapper.resolve(name));
    }
    gpgHome = Files.createTempDirectory(TEMP, "verification-gnupg-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    environment.put("GNUPGHOME", gpgHome.toString());
    Files.writeString(work.resolve("results.tsv"), "case\texit_code\taccepted\n");
    for (final var name : List.of("Expected", "Other")) {
      gpg("--quick-generate-key", name + " Fixture <" + name.toLowerCase(java.util.Locale.ROOT)
          + "@invalid.example>", "ed25519", "sign", "1d"
      );
    }
    keys = new String(gpg("--with-colons", "--list-keys"), StandardCharsets.UTF_8).lines()
        .filter(line -> line.startsWith("fpr:")).map(line -> line.split(":", -1)[9]).toList();
    if (keys.size() != 2) {
      throw new IOException("Expected exactly two disposable signing keys");
    }
    final var repository = Files.createDirectory(work.resolve("maven"));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    httpExecutor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(httpExecutor);
    server.createContext("/", exchange -> serve(exchange, repository));
    server.start();
    repositoryUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    for (final var name : List.of("unsigned", "signed", "wrong-signature", "untrusted", "missing-key")) {
      final var jar = fixtureJar(name, "1");
      artifacts.put(name, jar);
      if (!name.equals("unsigned")) {
        // missing-key is signed by the trusted key, which its keyring then leaves out.
        final var signer = keys.get(name.equals("untrusted") ? 1 : 0);
        gpg("--armor", "--detach-sign", "--local-user", signer, jar.toString());
        if (name.equals("wrong-signature")) {
          Files.writeString(jar, "tampered after signing", StandardOpenOption.APPEND);
        }
      }
    }
    for (final var jar : List.of(fixtureJar("signed", "2"), fixtureJar("other-module", "1"))) {
      gpg("--armor", "--detach-sign", "--local-user", keys.getFirst(), jar.toString());
    }
  }

  private Path fixtureJar(final String name, final String version) throws IOException {
    final var directory = Files.createDirectories(work.resolve("maven/fixture").resolve(name).resolve(version));
    final var jar = directory.resolve(name + "-" + version + ".jar");
    final var payload = "known fixture output\n".getBytes(StandardCharsets.UTF_8);
    final var crc = new CRC32();
    crc.update(payload);
    try (final var archive = new ZipOutputStream(Files.newOutputStream(jar))) {
      final var entry = new ZipEntry("payload.txt");
      entry.setTimeLocal(LocalDateTime.of(2020, 1, 1, 0, 0));
      entry.setMethod(ZipEntry.STORED);
      entry.setSize(payload.length);
      entry.setCrc(crc.getValue());
      archive.putNextEntry(entry);
      archive.write(payload);
      archive.closeEntry();
    }
    return jar;
  }

  private void runControls() throws Exception {
    final var root = project("unsigned-build", "unsigned");
    policy(root, checksum("unsigned"), "", false);
    run("unsigned-baseline", root, "unsigned", null, "resolveProbe", true);
    Files.writeString(artifacts.get("unsigned"), "tampered", StandardOpenOption.APPEND);
    run("unsigned-tamper", root, "unsigned", "Expected a sha256 checksum", "resolveProbe", true);
    policy(root, "", "", false);
    run("missing-entry", root, "unsigned", MISSING_CHECKSUM);

    for (final var name : List.of("signed", "wrong-signature", "untrusted", "missing-key")) {
      final var path = project(name + "-build", name);
      final var trust = "<trusted-keys><trusted-key id=\"" + keys.getFirst() + "\" group=\"fixture\"/></trusted-keys>";
      policy(path, "", trust, true);
      if (name.equals("missing-key")) {
        Files.write(path.resolve("gradle/verification-keyring.keys"), gpg("--armor", "--export", keys.get(1)));
      }
      final var cause = switch (name) {
        case "signed" -> null;
        case "wrong-signature" -> "signature didn't match";
        case "untrusted" -> "not in your trusted key list";
        case "missing-key" -> "couldn't be found in local key file";
        default -> throw new AssertionError(name);
      };
      run(name, path, name, cause);
    }

    // Module-scoped trust spans versions, but not another module signed by the same key.
    final var moduleTrust = "<trusted-keys><trusted-key id=\"" + keys.getFirst()
        + "\" group=\"fixture\" name=\"signed\"/></trusted-keys>";
    final var versionTwo = project("module-scope-version-two", "signed", "2");
    policy(versionTwo, "", moduleTrust, true);
    run("module-scoped-trust-accepts-new-version", versionTwo, "signed", null, "resolveProbe", false, "2");
    final var otherModule = project("module-scope-other-module", "other-module");
    policy(otherModule, "", moduleTrust, true);
    run("module-scoped-trust-refuses-other-module", otherModule, "other-module", "not in your trusted key list");

    final var signed = project("signed-wrong-checksum-build", "signed");
    final var pgp = "<pgp value=\"" + keys.getFirst() + "\"/>";
    final var digest = sha256(artifacts.get("signed"));
    policy(signed, entry("signed", pgp + sha(digest)), "", true);
    run("artifact-local-signed-baseline", signed, "signed", null);
    // A valid available signature must not bypass an explicitly recorded checksum.
    policy(signed, entry("signed", pgp + sha("0".repeat(64))), "", true);
    run("signed-wrong-checksum", signed, "signed", "Expected a sha256 checksum");
    Files.delete(artifacts.get("signed").resolveSibling("signed-1.jar.asc"));
    policy(signed, entry("signed", pgp + sha(digest)), "", true);
    run("artifact-local-pgp-matching-sha-missing-signature-accepted", signed, "signed", null);

    // A pinned checksum never stands in for a trusted key: an artifact signed by an untrusted key is
    // refused with its checksum recorded too, so a component entry cannot widen a signed artifact's
    // trust past the trusted-keys list.
    final var groupTrust = "<trusted-keys><trusted-key id=\"" + keys.getFirst() + "\" group=\"fixture\"/></trusted-keys>";
    final var pinnedUntrusted = project("untrusted-signature-pinned-checksum-build", "untrusted");
    policy(pinnedUntrusted, checksum("untrusted"), groupTrust, true);
    run("untrusted-signature-pinned-checksum-refused", pinnedUntrusted, "untrusted", "not in your trusted key list");

    final var outer = project("outer", "unsigned");
    final var inner = project("outer/included", "unsigned");
    Files.writeString(outer.resolve("settings.gradle"), "rootProject.name='outer'\nincludeBuild('included')\n");
    policy(outer, checksum("unsigned"), "", false);
    policy(inner, "", "", false);
    run("outer-policy-controls-included", outer, "unsigned", null, ":included:resolveProbe", false);
    run("standalone-inner-policy-refuses", inner, "unsigned", MISSING_CHECKSUM);
    policy(outer, "", "", false);
    policy(inner, checksum("unsigned"), "", false);
    run("inner-policy-cannot-rescue-outer", outer, "unsigned", MISSING_CHECKSUM, ":included:resolveProbe", false);
    run("standalone-inner-policy-passes", inner, "unsigned", null);

    final var settings = project("settings-control", "unsigned");
    policy(settings, "", "", false);
    Files.writeString(settings.resolve("settings.gradle"), """
        buildscript {
          repositories { maven { url=uri('%s'); allowInsecureProtocol=true; metadataSources { artifact() } } }
          dependencies { classpath 'fixture:unsigned:1' }
        }
        println('SETTINGS_BODY_EXECUTED')
        rootProject.name='settings-control'
        """.formatted(repositoryUrl)
    );
    run("settings-before-execution", settings, "unsigned", MISSING_CHECKSUM);
  }

  private Path project(final String relative, final String artifact) throws IOException {
    return project(relative, artifact, "1");
  }

  private Path project(final String relative, final String artifact, final String version) throws IOException {
    final var path = Files.createDirectories(work.resolve(relative));
    Files.writeString(path.resolve("settings.gradle"), "rootProject.name = '" + path.getFileName() + "'\n");
    Files.writeString(path.resolve("build.gradle"), """
        repositories { maven { url=uri('%s'); allowInsecureProtocol=true; metadataSources { artifact() } } }
        configurations { probe }
        dependencies { probe 'fixture:%s:%s' }
        tasks.register('resolveProbe') { doLast { configurations.probe.files.each { println('RESOLVED ' + it.name) } } }
        """.formatted(repositoryUrl, artifact, version)
    );
    return path;
  }

  private void policy(final Path path,
                      final String entries,
                      final String trust,
                      final boolean signatures) throws Exception {
    final var directory = Files.createDirectories(path.resolve("gradle"));
    Files.writeString(directory.resolve("verification-metadata.xml"), """
        <?xml version="1.0" encoding="UTF-8"?>
        <verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
          <configuration><verify-metadata>true</verify-metadata><verify-signatures>%s</verify-signatures><key-servers enabled="false"/>%s</configuration>
          <components>%s</components>
        </verification-metadata>
        """.formatted(signatures, trust, entries)
    );
    Files.write(directory.resolve("verification-keyring.keys"), gpg("--armor", "--export", keys.get(0), keys.get(1)));
  }

  private static String entry(final String name, final String value) {
    return "<component group=\"fixture\" name=\"" + name + "\" version=\"1\"><artifact name=\""
        + name + "-1.jar\">" + value + "</artifact></component>";
  }

  private static String sha(final String value) {
    return "<sha256 value=\"" + value + "\"/>";
  }

  private String checksum(final String name) throws Exception {
    return entry(name, sha(sha256(artifacts.get(name))));
  }

  private static String sha256(final Path path) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }

  private void run(final String name,
                   final Path project,
                   final String artifact,
                   final String cause) throws Exception {
    run(name, project, artifact, cause, "resolveProbe", false);
  }

  private void run(final String name,
                   final Path project,
                   final String artifact,
                   final String cause,
                   final String task,
                   final boolean defaultStrict) throws Exception {
    run(name, project, artifact, cause, task, defaultStrict, "1");
  }

  private void run(final String name,
                   final Path project,
                   final String artifact,
                   final String cause,
                   final String task,
                   final boolean defaultStrict,
                   final String version) throws Exception {
    // Evict only owned fixture caches; cached signatures must not conceal a changed artifact.
    deleteTree(gradleHome.resolve("caches/modules-2"), work);
    final var oldReports = reports(project);
    final var command = new ArrayList<>(List.of(work.resolve("wrapper/gradlew").toString(), "-p", project.toString(),
        "--no-daemon", "--console=plain", "--max-workers=1"
    ));
    if (!defaultStrict) {
      command.addAll(List.of("--dependency-verification", "strict"));
    }
    command.add(task);
    final var log = work.resolve(name + ".log");
    final int exit = execute(command, log, null, 100);
    final var output = Files.readString(log);
    final var filename = artifact + "-" + version + ".jar";
    final var coordinate = "fixture:" + artifact + ":" + version;
    boolean accepted;
    if (cause == null) {
      accepted = exit == 0 && output.contains("RESOLVED " + filename);
    } else {
      final var bodies = new StringBuilder();
      boolean matchingRow = false;
      for (final var report : reports(project)) {
        if (oldReports.contains(report)) {
          continue;
        }
        final var body = BODIES.matcher(Files.readString(report));
        while (body.find()) {
          bodies.append(body.group(1)).append('\n');
          final var rows = ROWS.matcher(body.group(1));
          while (rows.find()) {
            final var row = rows.group(1).replace("&#39;", "'").replace("&apos;", "'");
            // The module coordinate keeps a row about unsigned-1.jar from matching signed-1.jar.
            matchingRow |= row.contains(coordinate) && row.contains(filename) && row.contains(cause);
          }
        }
      }
      Files.writeString(work.resolve(name + ".report-rows.html"), bodies);
      accepted = exit != 0 && output.contains("Dependency verification failed") && matchingRow;
      if (name.equals("settings-before-execution")) {
        accepted &= !output.contains("SETTINGS_BODY_EXECUTED");
      }
    }
    Files.writeString(work.resolve("results.tsv"), name + "\t" + exit + "\t" + accepted + "\n", StandardOpenOption.APPEND);
    System.out.println(name + ": exit=" + exit + ", " + (accepted ? "PASS" : "FAIL"));
    if (!accepted) {
      throw new IOException("Control failed: " + name + "; inspect " + log + " and its report rows");
    }
    ++controlsPassed;
  }

  private static Set<Path> reports(final Path project) throws IOException {
    try (final var paths = Files.walk(project)) {
      return paths.filter(path -> path.getFileName().toString().equals(REPORT))
          .collect(java.util.stream.Collectors.toSet());
    }
  }

  private byte[] gpg(final String... arguments) throws Exception {
    final var command = new ArrayList<>(List.of("gpg", "--batch", "--pinentry-mode", "loopback", "--passphrase", ""));
    command.addAll(List.of(arguments));
    final var output = work.resolve("gpg-" + (++gpgCommand) + ".out");
    final int exit = execute(command, output, work.resolve("gpg.log"), 30);
    if (exit != 0) {
      throw new IOException("GPG failed (exit " + exit + "); inspect " + work.resolve("gpg.log"));
    }
    return Files.readAllBytes(output);
  }

  private int execute(final List<String> command,
                      final Path output,
                      final Path error,
                      final int timeoutSeconds) throws Exception {
    final var builder = new ProcessBuilder(command).directory(work.toFile()).redirectOutput(output.toFile());
    builder.environment().clear();
    builder.environment().putAll(environment);
    if (error == null) {
      builder.redirectErrorStream(true);
    } else {
      builder.redirectError(ProcessBuilder.Redirect.appendTo(error.toFile()));
    }
    final var process = builder.start();
    activeProcess = process;
    try {
      process.getOutputStream().close();
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        throw new IOException("Timed out after " + timeoutSeconds + " seconds; inspect " + output);
      }
      return process.exitValue();
    } finally {
      stop(process);
      activeProcess = null;
    }
  }

  private static void stop(final Process process) {
    if (process == null) return;
    // A timed-out wrapper can leave its single-use Gradle daemon behind.
    final var descendants = process.descendants().toList();
    descendants.forEach(ProcessHandle::destroy);
    if (process.isAlive()) {
      process.destroy();
    }
    try {
      if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    } catch (final InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
    descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
  }

  private synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    stop(activeProcess);
    if (server != null) {
      server.stop(0);
    }
    if (httpExecutor != null) {
      httpExecutor.shutdownNow();
    }
    if (gpgHome == null || !Files.isDirectory(gpgHome, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try {
      // Explicit homedir plus the isolated environment prevents touching any caller's agent.
      final var builder = new ProcessBuilder("gpgconf", "--homedir", gpgHome.toString(), "--kill", "all")
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.appendTo(work.resolve("gpg.log").toFile()));
      builder.environment().clear();
      builder.environment().putAll(environment);
      final var cleanup = builder.start();
      try {
        if (!cleanup.waitFor(10, TimeUnit.SECONDS) || cleanup.exitValue() != 0) {
          System.err.println("GPG cleanup failed; inspect " + work.resolve("gpg.log"));
        }
      } finally {
        stop(cleanup);
      }
    } catch (final IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      System.err.println("Could not stop disposable GPG services: " + e.getMessage());
    }
    try {
      deleteTree(gpgHome, TEMP);
    } catch (final IOException e) {
      System.err.println("Could not remove the disposable GnuPG home " + gpgHome + ": " + e.getMessage());
    }
  }

  private static void serve(final HttpExchange exchange, final Path repository) throws IOException {
    try (exchange) {
      final var method = exchange.getRequestMethod();
      final var file = repository.resolve("." + exchange.getRequestURI().getPath()).normalize();
      if ((!method.equals("GET") && !method.equals("HEAD")) || !file.startsWith(repository)
          || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        exchange.sendResponseHeaders(404, -1);
        return;
      }
      final long size = Files.size(file);
      exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
      exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
      exchange.sendResponseHeaders(200, method.equals("HEAD") ? -1 : size);
      if (method.equals("GET")) {
        Files.copy(file, exchange.getResponseBody());
      }
    }
  }

  private static void copyTree(final Path source, final Path target) throws IOException {
    // Do not follow links in a shared distribution into unrelated files or caller state.
    Files.walkFileTree(source, new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(final Path directory,
                                                   final BasicFileAttributes attributes) throws IOException {
            Files.createDirectories(target.resolve(source.relativize(directory)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
            if (!attributes.isRegularFile()) {
              throw new IOException("Not a regular distribution file: " + file);
            }
            Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
            return FileVisitResult.CONTINUE;
          }
        }
    );
  }

  private static void deleteTree(final Path root, final Path owner) throws IOException {
    if (!root.startsWith(owner) || root.equals(owner)) {
      throw new IOException("Refusing cleanup of " + root + " outside " + owner);
    }
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    // A stopping gpg-agent removes its own sockets, so an entry can vanish mid-walk.
    Files.walkFileTree(root, new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(final Path file, final IOException error) throws IOException {
            if (error instanceof NoSuchFileException) {
              return FileVisitResult.CONTINUE;
            }
            throw error;
          }

          @Override
          public FileVisitResult postVisitDirectory(final Path directory, final IOException error) throws IOException {
            if (error != null && !(error instanceof NoSuchFileException)) {
              throw error;
            }
            Files.deleteIfExists(directory);
            return FileVisitResult.CONTINUE;
          }
        }
    );
  }
}
