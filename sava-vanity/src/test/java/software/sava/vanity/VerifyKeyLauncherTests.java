package software.sava.vanity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.sava.vanity.Subprocess.Terminal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.vanity.LauncherHarness.*;

/// Defensive regressions for `verifyKey.sh` and `genKeys.sh` with synthetic paths, passwords
/// and process stubs. The launchers decide which runtime starts, with which heap and
/// environment, and whether a password may be prompted for, so each wrong branch here would
/// hand a real key or password to the wrong place. No Docker, Gradle, Java or user key file
/// is invoked or read.
final class VerifyKeyLauncherTests {

  @TempDir
  Path temporary;

  private LauncherHarness harness;

  @BeforeEach
  void createIsolatedLaunchers() throws Exception {
    harness = new LauncherHarness(temporary);
  }

  private static String[] withBuild(final String... options) {
    final var all = new ArrayList<String>();
    all.add("--build");
    all.addAll(List.of(options));
    return all.toArray(String[]::new);
  }

  @Test
  void exactKeyPathsAndCdpath() throws Exception {
    for (final var name : new String[]{"filename\n", "filename\n\n", "space ; $(not-executed) * [x]", "-leading"}) {
      final var directory = harness.root().resolve("parent\n\n").resolve("inner space");
      Files.createDirectories(directory);
      final var key = directory.resolve(name);
      Files.writeString(key, "synthetic");
      for (final var selected : new Path[]{key, harness.root().relativize(key)}) {
        harness.resetCalls();
        final var result = harness.invoke(selected);
        assertEquals(0, result.status(), result.err());
        final var call = harness.calls().getFirst();
        assertEquals(List.of(key.toString(), ADDRESS), call.lastArgs(2), name);
        harness.assertModule(call);
        assertEquals("", result.out());
      }
    }
  }

  @Test
  void localPhysicalKeyDirectoryKeepsTrailingNewlines() throws Exception {
    final var directory = harness.root().resolve("physical parent\n\n");
    Files.createDirectory(directory);
    final var alias = harness.root().resolve("key-parent-alias");
    Files.createSymbolicLink(alias, directory);
    final var target = directory.resolve("target.properties");
    Files.writeString(target, "synthetic");
    // Preserve the supplied basename even when the file itself is a symlink.
    final var key = directory.resolve("key-link\n\n");
    Files.createSymbolicLink(key, target.getFileName());
    final var result = harness.invoke(alias.resolve(key.getFileName()));
    assertEquals(0, result.status(), result.err());
    assertEquals(List.of(key.toString(), ADDRESS), harness.calls().getFirst().lastArgs(2));
  }

  @Test
  void symlinkLauncherPreservesNewlineTarget() throws Exception {
    final var launcher = harness.root().resolve("launcher");
    final var middle = harness.root().resolve("middle\n");
    Files.createSymbolicLink(middle, harness.root().relativize(harness.wrapper()));
    Files.createSymbolicLink(launcher, middle.getFileName());
    final var result = harness.invokeLauncher(launcher);
    assertEquals(0, result.status(), result.err());
    assertEquals("java", harness.calls().getFirst().program());
  }

  @Test
  void keyDirectoryResolvesSymlinksBeforeParentSegments() throws Exception {
    final var physical = harness.root().resolve("physical");
    Files.createDirectories(physical.resolve("inner"));
    final var alias = harness.root().resolve("alias");
    Files.createSymbolicLink(alias, physical.resolve("inner"));
    final var key = physical.resolve("key.properties");
    Files.writeString(key, "physical fixture");
    Files.writeString(harness.root().resolve(key.getFileName()), "different lexical fixture");
    final var selected = alias.resolve("..").resolve(key.getFileName());
    for (final var options : new String[][]{{}, {"--docker"}}) {
      harness.resetCalls();
      final var result = harness.invoke(selected, Map.of(PASSWORD_ENV, PASSWORD), options);
      assertEquals(0, result.status(), result.err());
      final var run = harness.calls().getLast();
      harness.assertModule(run);
      if (options.length > 0) {
        assertTrue(run.args().contains("type=bind,source=" + key + ",target=/key.properties,readonly"), run.args()::toString);
      } else {
        assertEquals(List.of(key.toString(), ADDRESS), run.lastArgs(2));
      }
    }
  }

  @Test
  void invalidJvmOptionsNeverBuildOrRun() throws Exception {
    Files.delete(harness.java());
    for (final var option : new String[]{"", " \t\r\n", "-version", "--dry-run", "--list-modules", "-X",
        "-cp elsewhere", "@args", "-Dfile.encoding=UTF-8", "-m other/main",
        "-jar other.jar", "-Xmx0", "-Xmx000m", "-Xmx-1g", "-Xmx1.5g",
        "-Xmx1gb", "-Xms", "-Xmx512m -version", "-server;touch bad"}) {
      harness.assertRejected(harness.invoke("--build", "--jvm=" + option), "--jvm=" + option);
    }
  }

  @Test
  void heapWhitelistAliasAndConvenience() throws Exception {
    final var result = harness.invoke("--jvmArgs=-server\t-Xms16M\n-Xmx2G", "--maxHeap=3g");
    assertEquals(0, result.status(), result.err());
    assertEquals(List.of("-server", "-Xms16M", "-Xmx2G", "-Xmx3g"), harness.calls().getFirst().args().subList(0, 4));
    for (final var size : new String[]{"", "0", "000G", "-1", "1.5g", "2T", "1g -version"}) {
      harness.resetCalls();
      harness.assertRejected(harness.invoke("--maxHeap=" + size), "--maxHeap=" + size);
    }
  }

  @Test
  void defaultsAndExitStatus() throws Exception {
    final var result = harness.invoke(Map.of("STUB_EXIT", "1"));
    assertEquals(1, result.status(), result.err());
    final var call = harness.calls().getFirst();
    assertEquals(List.of("-server", "-Xms64m", "-Xmx512m", "-m"), call.args().subList(0, 4));
    harness.assertModule(call);
  }

  @Test
  void heapOverflowAndFinalIncoherenceRejectedBeforeBuild() throws Exception {
    Files.delete(harness.java());
    for (final var options : new String[][]{
        {"--maxHeap=32m"},
        {"--jvm=-Xms1g", "--maxHeap=512m"},
        {"--jvm=-Xms512m -Xmx64m"},
        {"--maxHeap=9223372036854775808"},
        {"--maxHeap=8589934592g"},
        {"--jvm=-Xmx9007199254740992k"},
        {"--jvm=-Xms8796093022208m"},
        {"--maxHeap=" + "9".repeat(200)}}) {
      harness.assertRejected(harness.invoke(withBuild(options)), String.join(" ", options));
    }
  }

  @Test
  void finalHeapOptionsWinAndLeadingZeroSizesAreDecimal() throws Exception {
    for (final var options : new String[][]{
        {"--jvm=-Xms1g -Xms00016m -Xmx8m -Xmx00032m"},
        {"--jvm=-Xms1g -Xms16m -Xmx8m", "--maxHeap=32m"},
        {"--maxHeap=32m", "--jvm=-Xms16m -Xmx8m"},
        {"--jvm=-Xms16m -Xmx8m", "--maxHeap=8m", "--maxHeap=32m"},
        {"--jvm=-Xms00000000000000000016m -Xmx032m"},
        {"--jvm=-Xms16m -Xmx9223372036854775807"},
        {"--jvm=-Xms16m -Xmx9007199254740991k"},
        {"--jvm=-Xms16m -Xmx8796093022207m"},
        {"--jvm=-Xms16m -Xmx8589934591g"}}) {
      harness.resetCalls();
      final var result = harness.invoke(options);
      assertEquals(0, result.status(), () -> String.join(" ", options) + ": " + result.err());
      harness.assertModule(harness.calls().getFirst());
    }
  }

  @Test
  void localEncryptionRejectsNonUtf8LocaleBeforeBuild() throws Exception {
    for (final var locale : new String[]{"C", "POSIX"}) {
      for (final var generatorOptions : new String[][]{{"--encrypt"}, {"--passwordEnv=TEST_PASSWORD"}}) {
        harness.resetCalls();
        final var result = harness.invokeGenerator(Map.of("LC_ALL", locale, "TEST_PASSWORD", PASSWORD), withBuild(generatorOptions));
        harness.assertRejected(result, locale + ' ' + String.join(" ", generatorOptions));
        assertTrue(result.err().contains("UTF-8 locale"), result.err());
      }
      harness.resetCalls();
      final var result = harness.invoke(Map.of("LC_ALL", locale), "--build");
      harness.assertRejected(result, locale);
      assertTrue(result.err().contains("UTF-8 locale"), result.err());
    }
  }

  @Test
  void localEncryptionRejectsFailedLocaleProbe() throws Exception {
    harness.writeExecutable(harness.bin().resolve("locale"), "#!/bin/bash\nexit 1\n");
    harness.assertRejected(harness.invoke("--build"), "verifier");
    harness.assertRejected(harness.invokeGenerator(Map.of(), "--build", "--encrypt"), "generator");
  }

  @Test
  void dockerAndUnencryptedGeneratorAllowNonUtf8HostLocale() throws Exception {
    for (final var locale : new String[]{"C", "POSIX"}) {
      final var environment = Map.of("LC_ALL", locale, PASSWORD_ENV, PASSWORD);
      for (final var options : new String[][]{{"--docker"}, {"--docker=synthetic:test", "--encrypt"}, {"--encrypt=false"}}) {
        harness.resetCalls();
        final var result = options[0].equals("--docker")
            ? harness.invoke(environment, options)
            : harness.invokeGenerator(environment, options);
        assertEquals(0, result.status(), () -> locale + ' ' + String.join(" ", options) + ": " + result.err());
        assertFalse(harness.calls().isEmpty(), () -> locale + ' ' + String.join(" ", options) + ": nothing was launched");
      }
    }
  }

  @Test
  void missingAndDirectoryKeys() throws Exception {
    for (final var key : new Path[]{harness.root().resolve("missing"), harness.root()}) {
      harness.assertRejected(harness.invoke(key), 1, key.toString());
    }
  }

  @Test
  void explicitPasswordValidation() throws Exception {
    harness.assertRejected(harness.invoke("--passwordEnv=ABSENT_SYNTHETIC_PASSWORD"), "absent variable");
    harness.assertRejected(harness.invoke(Map.of("TEST_PASSWORD", ""), "--passwordEnv=TEST_PASSWORD"), "empty variable");
    harness.assertRejected(harness.invoke("--passwordEnv=BAD-NAME"), "invalid variable name");
  }

  @Test
  void implicitEmptyPasswordIsUnset() throws Exception {
    final var result = harness.invoke(Map.of(PASSWORD_ENV, ""));
    assertEquals(0, result.status(), result.err());
    assertNull(harness.calls().getFirst().password());
  }

  @Test
  void passwordAndLegacyFlagWithoutSecretOutput() throws Exception {
    final var result = harness.invokeTracing(Map.of("TEST_PASSWORD", PASSWORD), "--passwordEnv=TEST_PASSWORD", "--legacyDockerPassword");
    assertEquals(0, result.status(), result.err());
    final var call = harness.calls().getFirst();
    assertEquals(PASSWORD, call.password());
    assertEquals(List.of(harness.key().toString(), ADDRESS, "--legacy-docker-password"), call.lastArgs(3));
    assertFalse(result.outputContains(PASSWORD), "a traced launcher must not print the password");
    assertFalse(call.args().contains(PASSWORD), "the password must never be an argument");
  }

  @Test
  void dockerSafeArgumentsAndExactMount() throws Exception {
    final var key = harness.root().resolve("parent space").resolve("key space ; $()");
    Files.createDirectory(key.getParent());
    Files.writeString(key, "synthetic");
    final var result = harness.invoke(key, Map.of(PASSWORD_ENV, PASSWORD), "--docker", "--dockerUser=1001:2002", "--legacyDockerPassword");
    assertEquals(0, result.status(), result.err());
    final var calls = harness.calls();
    assertEquals(2, calls.size(), calls::toString);
    final var inspect = calls.get(0);
    final var run = calls.get(1);
    assertEquals(List.of("image", "inspect", "sava-vanity:local"), inspect.args());
    assertEquals(List.of("run", "--network", "none", "--rm",
        "-e", "JDK_JAVA_OPTIONS", "-e", "JAVA_TOOL_OPTIONS", "-e", "_JAVA_OPTIONS",
        "--user", "1001:2002",
        "-e", PASSWORD_ENV, "--mount",
        "type=bind,source=" + key + ",target=/key.properties,readonly",
        "sava-vanity:local"), run.args().subList(0, 17));
    assertEquals(List.of("-server", "-Xms64m", "-Xmx512m", "-m", MODULE), run.args().subList(17, 22));
    assertEquals(List.of("/key.properties", ADDRESS, "--legacy-docker-password"), run.lastArgs(3));
    assertEquals(PASSWORD, run.password());
    assertFalse(run.args().contains(PASSWORD), "the password must never be an argument");
    assertFalse(result.outputContains(PASSWORD), "the launcher must not print the password");
  }

  @Test
  void dockerCustomHeapOptionsAndModule() throws Exception {
    final var result = harness.invoke(Map.of(PASSWORD_ENV, PASSWORD), "--docker", "--jvmArgs=-server -Xms16M -Xmx2G", "--maxHeap=3g");
    assertEquals(0, result.status(), result.err());
    final var run = harness.calls().getLast();
    assertEquals(List.of("-server", "-Xms16M", "-Xmx2G", "-Xmx3g", "-m", MODULE, "/key.properties", ADDRESS),
        run.argsFrom(run.indexOf("sava-vanity:local") + 1));
  }

  @Test
  void dockerPromptRequiresTerminalBeforeBuild() throws Exception {
    for (final var environment : List.of(Map.<String, String>of(), Map.of(PASSWORD_ENV, ""))) {
      final var result = harness.invoke(environment, "--docker", "--build");
      harness.assertRejected(result, environment.toString());
      assertTrue(result.err().contains("--passwordEnv"), result.err());
    }
  }

  @Test
  void dockerPromptWithTerminal() throws Exception {
    final var result = harness.invokeInTerminal(Terminal.STDIN_AND_STDOUT, Map.of(PASSWORD_ENV, ""), "--dockerImage=synthetic:test");
    assertEquals(0, result.status(), result.err());
    final var run = harness.calls().getLast();
    assertTrue(run.args().contains("-it"), run.args()::toString);
    assertFalse(run.args().contains(PASSWORD_ENV), "an unset password must not be forwarded by name");
    assertNull(run.password());
  }

  @Test
  void dockerPromptRequiresBothTerminalStreams() throws Exception {
    for (final var terminal : new Terminal[]{new Terminal(true, false, false), new Terminal(false, true, false)}) {
      final var result = harness.invokeInTerminal(terminal, Map.of(), "--docker", "--build");
      harness.assertRejected(result, terminal.toString());
      assertTrue(result.err().contains("--passwordEnv"), result.err());
    }
  }

  @Test
  void requestedBuildResolvesRepositoryFromLauncher() throws Exception {
    final var environment = jvmEnvironment("-Xlog:help");
    final var result = harness.invoke(environment, "--build");
    assertEquals(0, result.status(), result.err());
    final var calls = harness.calls();
    assertEquals(2, calls.size(), calls::toString);
    final var build = calls.get(0);
    final var run = calls.get(1);
    assertEquals("gradlew", build.program());
    assertEquals(List.of(":sava-vanity:image"), build.args());
    assertEquals(environment, build.jvmEnvironment(), "build-time tuning keeps the caller's JVM options");
    assertEquals("java", run.program());
    assertEquals(unsetJvmEnvironment(), run.jvmEnvironment(), "the verifier runtime must not inherit JVM options");
  }

  @Test
  void dockerJvmEnvironmentClearedOnlyForRuntime() throws Exception {
    final var environment = jvmEnvironment("-Xlog:help");
    final var withPassword = new HashMap<>(environment);
    withPassword.put(PASSWORD_ENV, PASSWORD);
    final var result = harness.invoke(withPassword, "--docker", "--build");
    assertEquals(0, result.status(), result.err());
    final var calls = harness.calls();
    assertEquals(2, calls.size(), calls::toString);
    final var build = calls.get(0);
    final var run = calls.get(1);
    assertEquals("build", build.args().getFirst());
    assertEquals(environment, build.jvmEnvironment());
    assertEquals("run", run.args().getFirst());
    assertEquals(unsetJvmEnvironment(), run.jvmEnvironment());
    for (final var name : JVM_ENV) {
      assertEquals("-e", run.args().get(run.indexOf(name) - 1), name);
    }
  }

  @Test
  void invalidDockerUserAndMountPaths() throws Exception {
    for (final var option : new String[]{"--dockerUser=1", "--dockerUser=-1:2", "--dockerUser=user:group",
        "--dockerUser=1:2:3", "--dockerUser=1:2 --privileged"}) {
      harness.assertRejected(harness.invoke(Map.of(PASSWORD_ENV, PASSWORD), "--docker", option), option);
    }
    harness.assertRejected(harness.invoke("--dockerUser=1:2"), "--dockerUser without Docker");
    for (final var name : new String[]{"comma,key", "quote\"key", "newline\nkey", "trailing\n"}) {
      final var key = harness.root().resolve(name);
      Files.writeString(key, "synthetic");
      final var result = harness.invoke(key, Map.of(PASSWORD_ENV, PASSWORD), "--docker", "--build");
      harness.assertRejected(result, name);
      assertTrue(result.err().contains("use local verification"), result.err());
    }
  }
}
