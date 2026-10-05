package software.sava.vanity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.*;

/// A pseudo-terminal for the launcher and runtime tests. The defensive checks that need a real
/// terminal, such as the launcher's `-t 0`/`-t 1` test that decides whether `docker run` may
/// prompt and the JDK console password prompt the verifier falls back to, cannot run on
/// pipes. The master side stays in this JVM; a child attaches to the slave device through
/// ordinary `ProcessBuilder` file redirects. libc is reached through the FFM linker, so the
/// tests depend on no external utility such as script(1) or expect(1).
final class Pty implements AutoCloseable {

  private static final boolean MAC = System.getProperty("os.name").startsWith("Mac");
  private static final Linker LINKER = Linker.nativeLinker();
  private static final SymbolLookup LIBC = LINKER.defaultLookup();
  // poll and read distinguish a hangup from an interrupted call only through errno.
  private static final Linker.Option CAPTURE_ERRNO = Linker.Option.captureCallState("errno");
  private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
  private static final VarHandle ERRNO = CALL_STATE.varHandle(MemoryLayout.PathElement.groupElement("errno"));
  private static final MethodHandle POSIX_OPENPT = downcall("posix_openpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
  private static final MethodHandle GRANTPT = downcall("grantpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
  private static final MethodHandle UNLOCKPT = downcall("unlockpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
  private static final MethodHandle PTSNAME = downcall("ptsname", FunctionDescriptor.of(ADDRESS, JAVA_INT));
  // nfds_t is unsigned int on Darwin and unsigned long on glibc; the register width must match.
  private static final MethodHandle POLL = LINKER.downcallHandle(symbol("poll"),
      FunctionDescriptor.of(JAVA_INT, ADDRESS, MAC ? JAVA_INT : JAVA_LONG, JAVA_INT), CAPTURE_ERRNO);
  private static final MethodHandle READ = LINKER.downcallHandle(symbol("read"),
      FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), CAPTURE_ERRNO);
  private static final MethodHandle WRITE = downcall("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
  private static final MethodHandle CLOSE = downcall("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

  private static final int O_RDWR = 0x2;
  private static final int O_NOCTTY = MAC ? 0x20000 : 0x100;
  private static final int EINTR = 4;
  // struct pollfd { int fd; short events; short revents; } on both platforms.
  private static final long POLLFD_SIZE = 8;
  private static final long POLLFD_EVENTS = 4;
  private static final long POLLFD_REVENTS = 6;
  private static final short POLLIN = 0x1;
  private static final short POLLERR = 0x8;
  private static final short POLLHUP = 0x10;
  private static final short POLLNVAL = 0x20;
  private static final int POLL_MILLIS = 50;
  private static final long BUFFER_SIZE = 64 * 1024;
  private static final Duration READER_GRACE = Duration.ofSeconds(5);

  private final int master;
  private final Path slave;
  private final ByteArrayOutputStream output = new ByteArrayOutputStream();
  private Thread reader;
  private volatile boolean stop;
  private volatile boolean answered;
  private volatile boolean closed;

  private Pty(final int master, final Path slave) {
    this.master = master;
    this.slave = slave;
  }

  static Pty open() throws IOException {
    final int master = invokeInt(POSIX_OPENPT, O_RDWR | O_NOCTTY);
    if (master < 0) {
      throw new IOException("posix_openpt failed");
    }
    try {
      if (invokeInt(GRANTPT, master) != 0 || invokeInt(UNLOCKPT, master) != 0) {
        throw new IOException("grantpt/unlockpt failed");
      }
      final MemorySegment name;
      try {
        name = (MemorySegment) PTSNAME.invokeExact(master);
      } catch (final Throwable t) {
        throw new IllegalStateException(t);
      }
      if (name.equals(MemorySegment.NULL)) {
        throw new IOException("ptsname failed");
      }
      // ptsname returns static storage; copy it before anything else can call ptsname.
      return new Pty(master, Path.of(name.reinterpret(Long.MAX_VALUE).getString(0)));
    } catch (final IOException | RuntimeException e) {
      invokeInt(CLOSE, master);
      throw e;
    }
  }

  /// The slave device path for a child's standard streams; `-t` is true on it.
  Path slave() {
    return slave;
  }

  /// Starts draining the master. When `prompt` is non-null, `answer` is written once the
  /// prompt has appeared in the transcript, which is after the child has disabled echo.
  void start(final String prompt, final byte[] answer) {
    if (reader != null) {
      throw new IllegalStateException("already started");
    }
    final var promptBytes = prompt == null ? null : prompt.getBytes(StandardCharsets.UTF_8);
    reader = Thread.ofPlatform().daemon().name("pty-reader").start(() -> drain(promptBytes, answer));
  }

  private void drain(final byte[] prompt, final byte[] answer) {
    try (final var arena = Arena.ofConfined()) {
      final var state = arena.allocate(CALL_STATE);
      final var fds = arena.allocate(POLLFD_SIZE);
      fds.set(JAVA_INT, 0, master);
      fds.set(JAVA_SHORT, POLLFD_EVENTS, POLLIN);
      final var buffer = arena.allocate(BUFFER_SIZE);
      while (!stop) {
        fds.set(JAVA_SHORT, POLLFD_REVENTS, (short) 0);
        final int ready = poll(state, fds);
        if (ready < 0) {
          if (errno(state) == EINTR) {
            continue;
          }
          return;
        } else if (ready == 0) {
          continue;
        }
        final short revents = fds.get(JAVA_SHORT, POLLFD_REVENTS);
        if ((revents & POLLIN) != 0) {
          final long count = read(state, buffer);
          if (count < 0) {
            // Linux reports the hangup as EIO once every slave descriptor is closed; only an
            // interrupted call is worth retrying.
            if (errno(state) == EINTR) {
              continue;
            }
            return;
          } else if (count == 0) {
            // Darwin end of stream.
            return;
          }
          output.writeBytes(buffer.asSlice(0, count).toArray(JAVA_BYTE));
          if (prompt != null && !answered && indexOf(output.toByteArray(), prompt) >= 0) {
            write(arena, answer);
            answered = true;
          }
        } else if ((revents & (POLLHUP | POLLERR | POLLNVAL)) != 0) {
          return;
        }
      }
    }
  }

  private void write(final Arena arena, final byte[] bytes) {
    final var segment = arena.allocateFrom(JAVA_BYTE, bytes);
    final long written;
    try {
      written = (long) WRITE.invokeExact(master, segment, (long) bytes.length);
    } catch (final Throwable t) {
      throw new IllegalStateException(t);
    }
    if (written != bytes.length) {
      throw new IllegalStateException("short write to the terminal: " + written);
    }
  }

  private int poll(final MemorySegment state, final MemorySegment fds) {
    try {
      return MAC
          ? (int) POLL.invokeExact(state, fds, 1, POLL_MILLIS)
          : (int) POLL.invokeExact(state, fds, 1L, POLL_MILLIS);
    } catch (final Throwable t) {
      throw new IllegalStateException(t);
    }
  }

  private long read(final MemorySegment state, final MemorySegment buffer) {
    try {
      return (long) READ.invokeExact(state, master, buffer, BUFFER_SIZE);
    } catch (final Throwable t) {
      throw new IllegalStateException(t);
    }
  }

  private static int errno(final MemorySegment state) {
    return (int) ERRNO.get(state, 0L);
  }

  /// Waits for the transcript to end naturally once the child has exited and reports whether
  /// it did; a descendant that still holds the slave open is cut off by stopping the reader.
  boolean finish(final Duration grace) throws InterruptedException {
    if (reader == null || reader.join(grace)) {
      return true;
    }
    stop = true;
    reader.join(READER_GRACE);
    return false;
  }

  /// Everything the terminal displayed, as raw bytes with the line discipline's `\r\n`.
  byte[] output() {
    return output.toByteArray();
  }

  boolean answered() {
    return answered;
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    stop = true;
    boolean readerEnded = reader == null;
    if (!readerEnded) {
      try {
        readerEnded = reader.join(READER_GRACE);
      } catch (final InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }
    // Closing under a reader that is still polling would let the descriptor number be reused
    // beneath it; a leaked master is the lesser failure and is reported.
    if (!readerEnded) {
      throw new IOException("the terminal reader did not stop; the master descriptor stays open");
    }
    if (invokeInt(CLOSE, master) != 0) {
      throw new IOException("close failed for the terminal master");
    }
  }

  static int indexOf(final byte[] haystack, final byte[] needle) {
    Objects.requireNonNull(needle);
    outer:
    for (int i = 0; i <= haystack.length - needle.length; ++i) {
      for (int j = 0; j < needle.length; ++j) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static MemorySegment symbol(final String name) {
    return LIBC.find(name).orElseThrow(() -> new IllegalStateException(name));
  }

  private static MethodHandle downcall(final String name, final FunctionDescriptor descriptor) {
    return LINKER.downcallHandle(symbol(name), descriptor);
  }

  private static int invokeInt(final MethodHandle handle, final int argument) {
    try {
      return (int) handle.invokeExact(argument);
    } catch (final Throwable t) {
      throw new IllegalStateException(t);
    }
  }
}
