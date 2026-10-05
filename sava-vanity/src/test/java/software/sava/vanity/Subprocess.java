package software.sava.vanity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Runs a launcher or JVM under test with an exact environment and a bounded wait, capturing
/// its output without ever blocking on a full pipe. Terminal runs attach the selected streams
/// to a fresh [Pty] and can answer one password prompt.
final class Subprocess {

  private static final byte[] EMPTY = new byte[0];
  private static final Redirect DEV_NULL = Redirect.from(new File("/dev/null"));
  private static final Duration DRAIN_GRACE = Duration.ofSeconds(5);

  /// Child command lines and environments are encoded with the charset of this JVM's locale
  /// (`sun.jnu.encoding`), which no system property overrides; without UTF-8 a non-ASCII
  /// password would reach a launcher as `?`. The Gradle tasks pin the locale on Linux.
  static void assertUtf8ChildEncoding() {
    assertEquals("UTF-8", System.getProperty("sun.jnu.encoding"),
        "run the test JVM under a UTF-8 locale, for example LC_ALL=C.UTF-8, so child environments carry non-ASCII passwords intact");
  }

  record Result(int status, byte[] stdout, byte[] stderr, boolean promptAnswered) {

    String out() {
      return new String(stdout, StandardCharsets.UTF_8);
    }

    String err() {
      return new String(stderr, StandardCharsets.UTF_8);
    }

    boolean outputContains(final String text) {
      final var needle = text.getBytes(StandardCharsets.UTF_8);
      return Pty.indexOf(stdout, needle) >= 0 || Pty.indexOf(stderr, needle) >= 0;
    }
  }

  /// Which standard streams sit on the terminal. The others read /dev/null or write to
  /// captured pipes, so a single stream can be a tty while its partner is not. The terminal
  /// transcript is returned as stdout, so stderr may join it only together with stdout.
  record Terminal(boolean stdin, boolean stdout, boolean stderr) {

    static final Terminal ALL = new Terminal(true, true, true);
    static final Terminal STDIN_AND_STDOUT = new Terminal(true, true, false);

    Terminal {
      if (stderr && !stdout) {
        throw new IllegalArgumentException("stderr can join the terminal only together with stdout");
      }
    }

    /// Bash redirections that move the selected streams onto the slave device named by `$1`.
    private String redirections() {
      final var exec = new StringBuilder("exec");
      if (stdin) {
        exec.append(" 0<\"$1\"");
      }
      if (stdout) {
        exec.append(" 1>\"$1\"");
      }
      if (stderr) {
        exec.append(" 2>\"$1\"");
      }
      return exec.toString();
    }
  }

  /// Drains one pipe on its own thread so the child never blocks on a full buffer; whatever was
  /// read survives a torn-down pipe or a descendant that keeps it open past the grace period.
  private static final class Drain extends Thread {

    private final InputStream stream;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    private Drain(final InputStream stream) {
      super("subprocess-drain");
      this.stream = stream;
      setDaemon(true);
    }

    @Override
    public void run() {
      final var chunk = new byte[8192];
      try {
        for (int count; (count = stream.read(chunk)) >= 0; ) {
          bytes.write(chunk, 0, count);
        }
      } catch (final IOException _) {
        // A destroyed child tears the pipe down; what was read so far is kept.
      }
    }

    byte[] result() throws InterruptedException {
      join(DRAIN_GRACE);
      return bytes.toByteArray();
    }
  }

  /// stdin from /dev/null, stdout and stderr captured, merged into stdout when requested.
  static Result run(final List<String> command,
                    final Path directory,
                    final Map<String, String> environment,
                    final Duration timeout,
                    final boolean mergeStderr) throws IOException, InterruptedException {
    final var builder = builder(command, directory, environment).redirectInput(DEV_NULL);
    if (mergeStderr) {
      builder.redirectErrorStream(true);
    }
    return execute(builder, timeout, null, null, null);
  }

  /// The chosen streams on a pseudo-terminal; its transcript is returned as stdout whenever
  /// stdout is on it. A non-null prompt is answered once with `answer` after it appears.
  static Result runInTerminal(final List<String> command,
                              final Path directory,
                              final Map<String, String> environment,
                              final Duration timeout,
                              final Terminal terminal,
                              final String prompt,
                              final byte[] answer) throws IOException, InterruptedException {
    try (final var pty = Pty.open()) {
      // The child opens the slave itself. A parent that is a session leader without a
      // controlling terminal, such as PID 1 in a container, would otherwise acquire the tty
      // by opening it and be hung up when the master closes. The prelude execs the command,
      // so the launcher under test still runs as the process this JVM started; a redirection
      // that fails stops the chain, so the command never runs on fallback descriptors.
      final var wrapped = new ArrayList<>(List.of("/bin/bash", "-c", terminal.redirections() + " && shift && exec \"$@\"",
          "terminal", pty.slave().toString()));
      wrapped.addAll(command);
      final var builder = builder(wrapped, directory, environment).redirectInput(DEV_NULL);
      if (terminal.stdout()) {
        builder.redirectOutput(Redirect.DISCARD);
      }
      if (terminal.stderr()) {
        builder.redirectError(Redirect.DISCARD);
      }
      final var result = execute(builder, timeout, pty, prompt, answer);
      return terminal.stdout()
          ? new Result(result.status(), pty.output(), result.stderr(), pty.answered())
          : new Result(result.status(), result.stdout(), result.stderr(), pty.answered());
    }
  }

  private static ProcessBuilder builder(final List<String> command,
                                        final Path directory,
                                        final Map<String, String> environment) {
    final var builder = new ProcessBuilder(command);
    if (directory != null) {
      builder.directory(directory.toFile());
    }
    // The child sees exactly the supplied environment, never this JVM's inherited one.
    final var childEnvironment = builder.environment();
    childEnvironment.clear();
    childEnvironment.putAll(environment);
    return builder;
  }

  private static Result execute(final ProcessBuilder builder,
                                final Duration timeout,
                                final Pty pty,
                                final String prompt,
                                final byte[] answer) throws IOException, InterruptedException {
    final var process = builder.start();
    try {
      if (pty != null) {
        pty.start(prompt, answer);
      }
      final var stdout = builder.redirectOutput() == Redirect.PIPE ? new Drain(process.getInputStream()) : null;
      final var stderr = builder.redirectError() == Redirect.PIPE && !builder.redirectErrorStream()
          ? new Drain(process.getErrorStream())
          : null;
      if (stdout != null) {
        stdout.start();
      }
      if (stderr != null) {
        stderr.start();
      }
      final boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        destroy(process);
        process.waitFor(5, TimeUnit.SECONDS);
      }
      final var out = stdout == null ? EMPTY : stdout.result();
      final var err = stderr == null ? EMPTY : stderr.result();
      // Every captured stream must reach end of stream once the child has exited; a descendant
      // that keeps one open could otherwise write past what the assertions saw.
      boolean complete = (stdout == null || !stdout.isAlive()) && (stderr == null || !stderr.isAlive());
      if (pty != null) {
        complete &= pty.finish(DRAIN_GRACE);
      }
      if (!finished) {
        throw new AssertionError("Timed out after " + timeout + ": " + builder.command());
      }
      if (!complete) {
        throw new AssertionError("Output did not reach end of stream within " + DRAIN_GRACE
            + " of exit; a descendant still holds a stream: " + builder.command());
      }
      return new Result(process.exitValue(), out, err, pty != null && pty.answered());
    } finally {
      // An interrupted or failed wait must not leave the child running.
      if (process.isAlive()) {
        destroy(process);
      }
    }
  }

  private static void destroy(final Process process) {
    try {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
    } catch (final RuntimeException _) {
      // Descendant enumeration is best effort; the child itself is always destroyed below.
    }
    process.destroyForcibly();
  }

  private Subprocess() {
  }
}
