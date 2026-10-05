package software.sava.vanity;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import software.sava.vanity.Subprocess.Result;
import software.sava.vanity.Subprocess.Terminal;
import software.sava.vanity.fixture.RuntimeKeyFixture;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Opt-in defensive runtime checks using only the public RFC 8032 test seed, run by the
/// `testVerifyKeyRuntime` task against the built jlink image. Nothing here builds an image,
/// reads a user wallet or sends a transaction; Docker runs use `--network none` and an
/// existing, explicitly named image.
@Tag("runtime")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class VerifyKeyRuntimeTests {

  private static final String ADDRESS = "FVen3X669xLzsi6N2V91DoiyzHzg1uAgqiT8jZ9nS96Z";
  private static final String OTHER_ADDRESS = "11111111111111111111111111111111";
  private static final String ASCII_PASSWORD = "synthetic-runtime-password";
  private static final String UNICODE_PASSWORD = "AéB€C🔑D";
  private static final String COLLIDING_OLD_PASSWORD = "AøB☃C😀D";
  private static final String LEGACY_PASSWORD = "A��B���C����D";
  private static final String PASSWORD_ENV = LauncherHarness.PASSWORD_ENV;
  private static final String MODULE = LauncherHarness.MODULE;
  private static final List<String> JVM_ENV = LauncherHarness.JVM_ENV;
  private static final String PROMPT = "Encryption password: ";
  private static final String PASS = "PASS: Decrypted the saved key and verified signing for " + ADDRESS;
  private static final byte[] GARBAGE = "garbage".getBytes(StandardCharsets.UTF_8);
  private static final String TIMEOUT_PROPERTY = "sava.vanity.runtimeTimeoutSeconds";
  private static final Path IMAGE = Path.of(System.getProperty("sava.vanity.image",
      LauncherHarness.PROJECT.resolve("build/images/sava-vanity").toString()));
  private static final Path JAVA = IMAGE.resolve("bin/java");
  private static final List<String> WRAPPER = List.of("/bin/bash", LauncherHarness.VERIFY_KEY.toString());

  private record Case(String variant, String password, boolean success, boolean legacy, String expected) {
  }

  private static final List<Case> CASES = List.of(
      new Case("ascii", ASCII_PASSWORD, true, false, ADDRESS),
      new Case("unicode", UNICODE_PASSWORD, true, false, ADDRESS),
      new Case("unicode", COLLIDING_OLD_PASSWORD, false, false, ADDRESS),
      new Case("unicode", "wrong-synthetic-password", false, false, ADDRESS),
      new Case("unicode", UNICODE_PASSWORD, false, false, OTHER_ADDRESS),
      new Case("legacy", UNICODE_PASSWORD, false, false, ADDRESS),
      new Case("legacy", UNICODE_PASSWORD, true, true, ADDRESS),
      new Case("legacy", LEGACY_PASSWORD, true, false, ADDRESS)
  );

  private Path directory;
  private Map<String, String> environment;
  private final Map<Path, byte[]> originals = new LinkedHashMap<>();
  private String dockerImage;
  private String docker;
  private int containers;
  private Duration timeout;

  @BeforeAll
  void generatePublicFixtures(@TempDir final Path temporary) throws Exception {
    Subprocess.assertUtf8ChildEncoding();
    timeout = configuredTimeout();
    assertTrue(Files.isExecutable(JAVA), () -> "First build :sava-vanity:image; there is no runtime at " + JAVA);
    directory = temporary.toRealPath();
    // A container user must be able to traverse the fixture directory.
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
    RuntimeKeyFixture.main(new String[]{directory.toString()});
    assertEquals(ADDRESS + '\n', Files.readString(directory.resolve("expected-address.txt"), StandardCharsets.US_ASCII),
        "Independent expected address did not match");
    try (final var files = Files.list(directory)) {
      for (final var file : files.sorted().toList()) {
        originals.put(file, Files.readAllBytes(file));
      }
    }
    assertEquals(7, originals.size(), "Expected six encrypted fixtures and one public address file");
    for (final var file : originals.keySet()) {
      readOnly(file);
    }
    environment = childEnvironment();
    final var requestedImage = System.getProperty("sava.vanity.dockerImage", "");
    dockerImage = requestedImage.isBlank() ? null : requestedImage;
    if (dockerImage != null) {
      docker = which("docker");
      assertNotNull(docker, "A Docker executable is required for the requested Docker image");
      final var inspect = Subprocess.run(List.of(docker, "image", "inspect", dockerImage), directory, environment, timeout, true);
      assertEquals(0, inspect.status(), "The requested Docker image must already exist locally");
    }
  }

  /// `-PverifyKeyRuntimeTimeout` arrives as decimal seconds; like the former script's --timeout,
  /// a malformed or non-positive value is rejected instead of silently replaced by the default.
  private static Duration configuredTimeout() {
    final var configured = System.getProperty(TIMEOUT_PROPERTY);
    if (configured == null) {
      return Duration.ofSeconds(45);
    }
    final double seconds;
    try {
      seconds = Double.parseDouble(configured.strip());
    } catch (final NumberFormatException _) {
      return fail(TIMEOUT_PROPERTY + " must be a positive number of seconds, not '" + configured + '\'');
    }
    assertTrue(seconds > 0 && Double.isFinite(seconds), () -> TIMEOUT_PROPERTY + " must be a positive number of seconds, not '" + configured + '\'');
    return Duration.ofMillis(Math.round(seconds * 1000));
  }

  /// Whitelists infrastructure settings and never inherits user passwords, Gradle repository
  /// credentials, JVM injection variables or shell startup files.
  private static Map<String, String> childEnvironment() {
    final var environment = new HashMap<String, String>();
    for (final var name : List.of("PATH", "HOME", "TMPDIR", "TERM", "XDG_RUNTIME_DIR",
        "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH")) {
      final var value = System.getenv(name);
      if (value != null) {
        environment.put(name, value);
      }
    }
    // Normal recovery checks require UTF-8; explicit C/POSIX cases override this.
    environment.put("LC_ALL", LauncherHarness.TEST_LOCALE);
    environment.put("LANG", LauncherHarness.TEST_LOCALE);
    environment.putIfAbsent("TERM", "xterm");
    return environment;
  }

  private String which(final String program) {
    for (final var entry : environment.getOrDefault("PATH", "").split(File.pathSeparator)) {
      if (!entry.isEmpty()) {
        final var candidate = Path.of(entry).resolve(program);
        if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
          return candidate.toString();
        }
      }
    }
    return null;
  }

  private static void readOnly(final Path file) throws IOException {
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
  }

  private static Map<String, String> with(final Map<String, String> base, final Map<String, String> overrides) {
    final var environment = new HashMap<>(base);
    environment.putAll(overrides);
    return environment;
  }

  private List<String> wrapperCommand(final Path keyFile, final String expected, final String... extra) {
    final var command = new ArrayList<>(WRAPPER);
    command.add("--keyFile=" + keyFile);
    command.add("--expectedPubKey=" + expected);
    command.addAll(List.of(extra));
    return command;
  }

  private Path unicodeFixture() {
    return directory.resolve("pbkdf2-unicode.properties");
  }

  private void assumeDocker() {
    assumeTrue(dockerImage != null, "pass -PverifyKeyDockerImage=<existing image> to include Docker verification");
  }

  /// Process output is deliberately left out of the failure messages: even public test
  /// passwords must not land in build logs if a console unexpectedly enables echo.
  private static void assertVerification(final String label, final Result result, final boolean expectedSuccess, final String password) {
    assertFalse(result.outputContains(password), label + ": password appeared in process output");
    for (final var variable : JVM_ENV) {
      assertFalse(result.outputContains("Picked up " + variable), label + ": JVM environment override produced a startup note");
    }
    final var text = result.out();
    if (expectedSuccess) {
      assertTrue(result.status() == 0 && text.contains(PASS), label + ": expected verified PASS, got exit " + result.status());
      assertTrue(text.contains("both rejected a changed message") && !text.contains("FAIL:"),
          label + ": signing-check evidence missing or contradicted");
    } else {
      assertTrue(result.status() == 1 && text.contains("FAIL:") && !text.contains("PASS:"),
          label + ": expected verification failure, got exit " + result.status());
    }
  }

  private void assertFixturesUnchanged(final String label) throws IOException {
    for (final var entry : originals.entrySet()) {
      assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()), label + ": fixture contents changed");
    }
  }

  /// Answers the real JDK console prompt through a pseudo-terminal.
  private Result console(final List<String> command, final Map<String, String> childEnvironment, final String password, final String label)
      throws IOException, InterruptedException {
    final var result = Subprocess.runInTerminal(command, directory, childEnvironment, timeout, Terminal.ALL, PROMPT,
        (password + '\n').getBytes(StandardCharsets.UTF_8));
    assertTrue(result.promptAnswered(), label + ": the real console password prompt was not observed");
    return result;
  }

  private void recover(final String runtime) throws Exception {
    for (final var kdf : new String[]{"pbkdf2", "argon2id"}) {
      // Every case exercises environment input end to end through the wrapper. Console cases
      // also exercise the implicit-empty environment fallback.
      for (final var mode : new String[]{"environment", "console"}) {
        final boolean console = mode.equals("console");
        for (int index = 0; index < CASES.size(); ++index) {
          final var testCase = CASES.get(index);
          final var path = directory.resolve(kdf + '-' + testCase.variant() + ".properties");
          final var label = runtime + '/' + kdf + '/' + mode + '/' + index + '-' + testCase.variant();
          final var childEnvironment = with(environment, Map.of(PASSWORD_ENV, console ? "" : testCase.password()));
          String container = null;
          final List<String> command;
          if (runtime.equals("docker") && console) {
            // Name the direct interactive container so timeout cleanup cannot leave one
            // running or affect unrelated containers.
            container = "sava-public-verifier-" + ProcessHandle.current().pid() + '-' + containers++;
            command = new ArrayList<>(List.of(docker, "run", "--rm", "--network", "none", "--name", container, "-it"));
            for (final var variable : JVM_ENV) {
              command.addAll(List.of("-e", variable));
            }
            command.addAll(List.of("-e", PASSWORD_ENV + '=', "--mount",
                "type=bind,source=" + path + ",target=/key.properties,readonly",
                dockerImage, "-Xms64m", "-Xmx512m", "-m", MODULE, "/key.properties", testCase.expected()));
            if (testCase.legacy()) {
              command.add("--legacy-docker-password");
            }
          } else {
            command = wrapperCommand(path, testCase.expected());
            if (runtime.equals("docker")) {
              command.add("--dockerImage=" + dockerImage);
            }
            if (testCase.legacy()) {
              command.add("--legacyDockerPassword");
            }
          }
          try {
            final var result = console
                ? console(command, childEnvironment, testCase.password(), label)
                : Subprocess.run(command, directory, childEnvironment, timeout, true);
            assertVerification(label, result, testCase.success(), testCase.password());
          } finally {
            if (container != null) {
              Subprocess.run(List.of(docker, "rm", "-f", container), directory, environment, timeout, true);
            }
            assertFixturesUnchanged(label);
          }
        }
      }
    }
  }

  @Test
  void localRecoveryThroughEnvironmentAndConsole() throws Exception {
    recover("local");
  }

  @Test
  void dockerRecoveryThroughEnvironmentAndConsole() throws Exception {
    assumeDocker();
    recover("docker");
  }

  @Test
  void launcherRejectsNonUtf8LocaleBeforePasswordUse() throws Exception {
    for (final var locale : new String[]{"C", "POSIX"}) {
      for (final var password : new String[]{"", UNICODE_PASSWORD}) {
        final var result = Subprocess.run(wrapperCommand(unicodeFixture(), ADDRESS), directory,
            with(environment, Map.of("LC_ALL", locale, PASSWORD_ENV, password)), timeout, true);
        assertTrue(result.status() == 2 && result.outputContains("UTF-8")
                && !result.outputContains("PASS:") && !result.outputContains(PROMPT.strip()),
            locale + ": non-UTF-8 local input was not rejected before password use, exit " + result.status());
        assertFalse(result.outputContains(UNICODE_PASSWORD), locale + ": locale rejection exposed the synthetic password");
      }
    }
  }

  @Test
  void launcherRejectsInvalidHeapSettingsWithTheDocumentedStatus() throws Exception {
    final var cases = new LinkedHashMap<List<String>, Integer>();
    cases.put(List.of("--maxHeap=32m"), 2);
    cases.put(List.of("--jvm=-Xms1g", "--maxHeap=512m"), 2);
    cases.put(List.of("--maxHeap=9223372036854775808"), 2);
    // Accepted by the launcher and rejected by the JVM itself.
    cases.put(List.of("--jvm=-Xmx1k"), 1);
    for (final var entry : cases.entrySet()) {
      final var result = Subprocess.run(wrapperCommand(unicodeFixture(), ADDRESS, entry.getKey().toArray(String[]::new)), directory,
          with(environment, Map.of(PASSWORD_ENV, UNICODE_PASSWORD)), timeout, true);
      assertTrue(result.status() == entry.getValue() && !result.outputContains("PASS:"),
          entry.getKey() + ": invalid heap settings did not fail with the documented status, exit " + result.status());
      assertFalse(result.outputContains(UNICODE_PASSWORD), entry.getKey() + ": heap rejection exposed the synthetic password");
    }
  }

  @Test
  void launcherSelectsThePhysicalKeyPath() throws Exception {
    final var physical = directory.resolve("physical");
    Files.createDirectories(physical.resolve("inner"));
    final var alias = directory.resolve("alias");
    Files.createSymbolicLink(alias, physical.resolve("inner"));
    final var fixture = Files.readAllBytes(unicodeFixture());
    final var images = dockerImage == null ? new String[]{null} : new String[]{null, dockerImage};
    for (final boolean expectedSuccess : new boolean[]{true, false}) {
      final var name = expectedSuccess ? "physical-valid.properties" : "physical-invalid.properties";
      // Lexically the same file as the one beside the fixtures; physically the one under physical/.
      final var selected = alias.resolve("..").resolve(name);
      final var physicalKey = physical.resolve(name);
      final var lexicalKey = directory.resolve(name);
      Files.write(physicalKey, expectedSuccess ? fixture : GARBAGE);
      Files.write(lexicalKey, expectedSuccess ? GARBAGE : fixture);
      readOnly(physicalKey);
      readOnly(lexicalKey);
      final var contents = Map.of(physicalKey, Files.readAllBytes(physicalKey), lexicalKey, Files.readAllBytes(lexicalKey));
      for (final var image : images) {
        final var label = "physical path selection" + (image == null ? "" : " via Docker");
        final var command = wrapperCommand(selected, ADDRESS);
        if (image != null) {
          command.add("--dockerImage=" + image);
        }
        final var result = Subprocess.run(command, directory, with(environment, Map.of(PASSWORD_ENV, UNICODE_PASSWORD)), timeout, true);
        assertVerification(label, result, expectedSuccess, UNICODE_PASSWORD);
        for (final var entry : contents.entrySet()) {
          assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()), label + ": verification changed a public fixture");
        }
      }
    }
  }

  @Test
  void dockerVerificationIsIndependentOfTheHostLocale() throws Exception {
    assumeDocker();
    // The image's UTF-8 locale is independent of the Docker client's host locale.
    for (final var locale : new String[]{"C", "POSIX"}) {
      final var result = Subprocess.run(wrapperCommand(unicodeFixture(), ADDRESS, "--dockerImage=" + dockerImage), directory,
          with(environment, Map.of("LC_ALL", locale, PASSWORD_ENV, UNICODE_PASSWORD)), timeout, true);
      assertVerification("Docker from non-UTF-8 host " + locale, result, true, UNICODE_PASSWORD);
    }
  }
}
