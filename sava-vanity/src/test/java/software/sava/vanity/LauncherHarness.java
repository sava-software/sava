package software.sava.vanity;

import software.sava.vanity.Subprocess.Result;
import software.sava.vanity.Subprocess.Terminal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// An isolated copy of the saved-key launchers with stubbed `java`, `docker` and `gradlew`.
/// Every path segment Bash could mishandle is present on purpose: spaces, `;`, `$()`, globs
/// and trailing newlines. No Docker, Gradle, Java or user key file is ever invoked or read;
/// the stubs only record how they were called.
final class LauncherHarness {

  static final String ADDRESS = "synthetic-expected-address";
  static final String PASSWORD = "synthetic-wrapper-password-é-$()";
  static final String PASSWORD_ENV = "SAVA_VANITY_ENCRYPT_PASSWORD";
  static final List<String> JVM_ENV = List.of("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS");
  static final String MODULE = "software.sava.vanity/software.sava.vanity.VerifyKey";
  /// Normal cases run under UTF-8 independently of the caller; individual tests override
  /// LC_ALL to exercise real C/POSIX locale rejection.
  static final String TEST_LOCALE = System.getProperty("os.name").startsWith("Mac") ? "en_US.UTF-8" : "C.UTF-8";
  static final Duration TIMEOUT = Duration.ofSeconds(10);
  /// The sava-vanity project directory, where Gradle and IDE test runs start.
  static final Path PROJECT = Path.of(System.getProperty("sava.vanity.projectDir", "")).toAbsolutePath();
  static final Path VERIFY_KEY = PROJECT.resolve("verifyKey.sh");
  static final Path GEN_KEYS = PROJECT.resolve("genKeys.sh");

  private static final Path BASH = Path.of("/bin/bash");
  private static final Set<String> DROPPED_ENVIRONMENT = Set.of(PASSWORD_ENV, "BASH_ENV", "ENV", "SHELLOPTS", "BASHOPTS",
      "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS");
  /// One NUL-delimited record per call: the program, a set-marker and value for the password
  /// and each JVM variable (unset and empty differ), then the argument count and arguments.
  private static final String STUB = """
      #!/bin/bash
      # Launcher test stub: records how it was invoked, then exits as the test instructs.
      printf '%s\\0' "${0##*/}" "${SAVA_VANITY_ENCRYPT_PASSWORD+set}" "${SAVA_VANITY_ENCRYPT_PASSWORD-}" "${JDK_JAVA_OPTIONS+set}" "${JDK_JAVA_OPTIONS-}" "${JAVA_TOOL_OPTIONS+set}" "${JAVA_TOOL_OPTIONS-}" "${_JAVA_OPTIONS+set}" "${_JAVA_OPTIONS-}" "$#" "$@" >> "$WRAPPER_CALLS" || exit 99
      exit "${STUB_EXIT:-0}"
      """;

  /// A recorded stub invocation. `password` and the JVM variable values are null when the
  /// variable was unset, which the launcher must achieve for the runtime it starts.
  record Call(String program, List<String> args, String password, Map<String, String> jvmEnvironment) {

    int indexOf(final String argument) {
      final int index = args.indexOf(argument);
      assertTrue(index >= 0, () -> argument + " missing from " + args);
      return index;
    }

    List<String> argsFrom(final int start) {
      return args.subList(start, args.size());
    }

    List<String> lastArgs(final int count) {
      return args.subList(args.size() - count, args.size());
    }

    /// Assertion messages print calls; the recorded password stays out of build logs.
    @Override
    public String toString() {
      return "Call[program=" + program + ", args=" + args + ", password=" + (password == null ? "unset" : "set")
          + ", jvmEnvironment=" + jvmEnvironment + ']';
    }
  }

  private final Path root;
  private final Path repo;
  private final Path wrapper;
  private final Path java;
  private final Path bin;
  private final Path callsFile;
  private final Path key;
  private final Map<String, String> env;

  LauncherHarness(final Path temporary) throws IOException {
    assertTrue(Files.isExecutable(BASH), "the launcher tests need a POSIX host with /bin/bash");
    Subprocess.assertUtf8ChildEncoding();
    assertTrue(Files.isRegularFile(VERIFY_KEY), () -> "run from the sava-vanity project directory; no launcher at " + VERIFY_KEY);
    // The physical path: macOS temporary directories live behind a symlink, and the
    // launcher resolves directories physically.
    root = temporary.toRealPath();
    repo = root.resolve("repo space ; $()\n");
    final var module = repo.resolve("sava-vanity\n");
    Files.createDirectories(module);
    wrapper = module.resolve("verifyKey.sh");
    Files.copy(VERIFY_KEY, wrapper);
    java = module.resolve("build/images/sava-vanity/bin/java");
    writeStub(java);
    bin = root.resolve("bin");
    writeStub(bin.resolve("docker"));
    writeStub(repo.resolve("gradlew"));
    callsFile = root.resolve("calls");
    key = root.resolve("synthetic.properties");
    Files.writeString(key, "synthetic data, never decrypted\n");
    env = new HashMap<>(System.getenv());
    env.keySet().removeAll(DROPPED_ENVIRONMENT);
    env.put("PATH", bin + ":" + System.getenv().getOrDefault("PATH", ""));
    env.put("WRAPPER_CALLS", callsFile.toString());
    env.put("CDPATH", "/tmp:/");
    env.put("LC_ALL", TEST_LOCALE);
    env.put("LANG", TEST_LOCALE);
  }

  Path root() {
    return root;
  }

  Path wrapper() {
    return wrapper;
  }

  /// The stubbed local runtime; deleting it makes a missing runtime that would build first.
  Path java() {
    return java;
  }

  Path bin() {
    return bin;
  }

  Path key() {
    return key;
  }

  void writeStub(final Path path) throws IOException {
    writeExecutable(path, STUB);
  }

  void writeExecutable(final Path path, final String script) throws IOException {
    Files.createDirectories(path.getParent());
    Files.writeString(path, script);
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  Result invoke(final String... options) throws IOException, InterruptedException {
    return launch(wrapper, key, Map.of(), null, false, options);
  }

  Result invoke(final Map<String, String> environment, final String... options) throws IOException, InterruptedException {
    return launch(wrapper, key, environment, null, false, options);
  }

  Result invoke(final Path keyFile, final String... options) throws IOException, InterruptedException {
    return launch(wrapper, keyFile, Map.of(), null, false, options);
  }

  Result invoke(final Path keyFile, final Map<String, String> environment, final String... options) throws IOException, InterruptedException {
    return launch(wrapper, keyFile, environment, null, false, options);
  }

  /// Starts the launcher with `bash -x`, which it must switch off before touching a secret.
  Result invokeTracing(final Map<String, String> environment, final String... options) throws IOException, InterruptedException {
    return launch(wrapper, key, environment, null, true, options);
  }

  Result invokeLauncher(final Path launcher) throws IOException, InterruptedException {
    return launch(launcher, key, Map.of(), null, false);
  }

  /// Runs with the selected streams on a pseudo-terminal; stderr stays a captured pipe.
  Result invokeInTerminal(final Terminal terminal, final Map<String, String> environment, final String... options) throws IOException, InterruptedException {
    return launch(wrapper, key, environment, terminal, false, options);
  }

  private Result launch(final Path launcher,
                        final Path keyFile,
                        final Map<String, String> environment,
                        final Terminal terminal,
                        final boolean trace,
                        final String... options) throws IOException, InterruptedException {
    final var command = new ArrayList<String>();
    command.add(BASH.toString());
    if (trace) {
      command.add("-x");
    }
    command.add(launcher.toString());
    command.add("--keyFile=" + keyFile);
    command.add("--expectedPubKey=" + ADDRESS);
    command.addAll(List.of(options));
    final var childEnvironment = new HashMap<>(env);
    childEnvironment.putAll(environment);
    return terminal == null
        ? Subprocess.run(command, root, childEnvironment, TIMEOUT, false)
        : Subprocess.runInTerminal(command, root, childEnvironment, TIMEOUT, terminal, null, null);
  }

  /// `genKeys.sh` from its own stubbed repository, generating PBKDF2 keys into `keys`.
  Result invokeGenerator(final Map<String, String> environment, final String... options) throws IOException, InterruptedException {
    final var generatorRepo = root.resolve("generator-repo");
    final var module = generatorRepo.resolve("sava-vanity");
    Files.createDirectories(module);
    final var launcher = module.resolve("genKeys.sh");
    Files.copy(GEN_KEYS, launcher, StandardCopyOption.REPLACE_EXISTING);
    writeStub(module.resolve("build/images/sava-vanity/bin/java"));
    writeStub(generatorRepo.resolve("gradlew"));
    final var command = new ArrayList<>(List.of(BASH.toString(), launcher.toString(), "--outDir=keys", "--kdf=pbkdf2"));
    command.addAll(List.of(options));
    final var childEnvironment = new HashMap<>(env);
    childEnvironment.putAll(environment);
    return Subprocess.run(command, generatorRepo, childEnvironment, TIMEOUT, false);
  }

  List<Call> calls() throws IOException {
    if (!Files.exists(callsFile)) {
      return List.of();
    }
    final var bytes = Files.readAllBytes(callsFile);
    final var fields = new ArrayList<String>();
    int start = 0;
    for (int i = 0; i < bytes.length; ++i) {
      if (bytes[i] == 0) {
        fields.add(new String(bytes, start, i - start, StandardCharsets.UTF_8));
        start = i + 1;
      }
    }
    assertEquals(bytes.length, start, "every recorded field is NUL-terminated");
    final var calls = new ArrayList<Call>();
    int index = 0;
    while (index < fields.size()) {
      final var program = fields.get(index++);
      final var password = "set".equals(fields.get(index)) ? fields.get(index + 1) : null;
      index += 2;
      final var jvmEnvironment = new HashMap<String, String>();
      for (final var name : JVM_ENV) {
        jvmEnvironment.put(name, "set".equals(fields.get(index)) ? fields.get(index + 1) : null);
        index += 2;
      }
      final int count = Integer.parseInt(fields.get(index++));
      calls.add(new Call(program, List.copyOf(fields.subList(index, index + count)), password,
          Collections.unmodifiableMap(jvmEnvironment)));
      index += count;
    }
    return calls;
  }

  void resetCalls() throws IOException {
    Files.deleteIfExists(callsFile);
  }

  static Map<String, String> jvmEnvironment(final String value) {
    final var environment = new HashMap<String, String>();
    JVM_ENV.forEach(name -> environment.put(name, value));
    return environment;
  }

  /// What the launcher must hand the runtime: every JVM-option variable unset, not empty.
  static Map<String, String> unsetJvmEnvironment() {
    return jvmEnvironment(null);
  }

  void assertRejected(final Result result, final String context) throws IOException {
    assertRejected(result, 2, context);
  }

  void assertRejected(final Result result, final int status, final String context) throws IOException {
    assertEquals(status, result.status(), () -> context + ": " + result.err());
    assertEquals(List.of(), calls(), () -> context + ": must reject before building or launching");
  }

  void assertModule(final Call call) {
    assertEquals(MODULE, call.args().get(call.indexOf("-m") + 1));
  }
}
