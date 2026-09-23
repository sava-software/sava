package software.sava.rpc.soak;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/// An append-only tab-separated log written off the producing thread: producers hand a line to a
/// bounded queue, one writer thread drains it and flushes every 100 lines or every second.
///
/// The bound is the point. A soak that blocks a listener thread on a disk write measures the
/// disk, and one with an unbounded queue turns a slow disk into the retention defect it was
/// hunting; so the queue has a fixed capacity and every refused line is counted, both here and
/// in `harness.tsv.dropped`. A non-zero drop count invalidates the artifacts that depend on
/// this file, which is why the runner reads the counter rather than trusting the file's length.
public final class Tsv implements AutoCloseable {

  public static final int DEFAULT_CAPACITY = 1 << 16;
  private static final int FLUSH_LINES = 100;

  private final Path file;
  private final ArrayBlockingQueue<String> queue;
  private final LongAdder dropped;
  private final Counters counters;
  private final Thread writer;
  private final Object flushed;
  private volatile boolean closing;
  private volatile long enqueued;
  private volatile long written;
  private volatile IOException failure;

  private Tsv(final Path file, final int capacity, final Counters counters) {
    this.file = file;
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.dropped = new LongAdder();
    this.counters = counters;
    this.flushed = new Object();
    this.writer = new Thread(this::drain, "soak-tsv-" + file.getFileName());
    this.writer.setDaemon(true);
  }

  /// Opens (truncating) `file`, writes `header` as the first line when it is non-null, and
  /// starts the writer thread.
  public static Tsv open(final Path file, final String header, final int capacity, final Counters counters)
      throws IOException {
    final var parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(file, header == null ? "" : header + '\n', StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    final var tsv = new Tsv(file, capacity, counters);
    tsv.writer.start();
    return tsv;
  }

  public static Tsv open(final Path file, final String header, final Counters counters) throws IOException {
    return open(file, header, DEFAULT_CAPACITY, counters);
  }

  /// Appends one row. Cells are sanitised — a tab, newline or carriage return inside a value
  /// would silently re-shape the table for every later reader.
  public void append(final String... cells) {
    final var line = new StringBuilder(128);
    for (int i = 0; i < cells.length; ++i) {
      if (i > 0) {
        line.append('\t');
      }
      line.append(cell(cells[i]));
    }
    appendLine(line.toString());
  }

  public void appendLine(final String line) {
    if (closing || !queue.offer(line)) {
      dropped.increment();
      if (counters != null) {
        counters.increment(Counters.HARNESS_TSV_DROPPED);
      }
      return;
    }
    ++enqueued;
  }

  public static String cell(final String value) {
    if (value == null) {
      return "";
    }
    var cleaned = value;
    if (cleaned.indexOf('\t') >= 0 || cleaned.indexOf('\n') >= 0 || cleaned.indexOf('\r') >= 0) {
      cleaned = cleaned.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
    return cleaned;
  }

  public long dropped() {
    return dropped.sum();
  }

  public Path file() {
    return file;
  }

  /// Waits, up to `bound`, for everything appended so far to reach the file. Used by the phase
  /// boundaries and the shutdown hook, where an artifact that is one row short is worse than a
  /// second of waiting.
  public void flushNow(final Duration bound) {
    final long deadline = System.nanoTime() + bound.toNanos();
    final long target = enqueued;
    synchronized (flushed) {
      while (written < target && failure == null) {
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
          return;
        }
        try {
          TimeUnit.NANOSECONDS.timedWait(flushed, remaining);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void drain() {
    try (final var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
      final var batch = new ArrayList<String>(FLUSH_LINES);
      while (true) {
        final var first = queue.poll(1L, TimeUnit.SECONDS);
        if (first == null) {
          if (closing && queue.isEmpty()) {
            return;
          }
          continue;
        }
        batch.add(first);
        queue.drainTo(batch, FLUSH_LINES - 1);
        write(out, batch);
        batch.clear();
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (final IOException e) {
      failure = e;
      System.err.println("soak: TSV writer failed for " + file + ": " + e);
    } finally {
      synchronized (flushed) {
        flushed.notifyAll();
      }
    }
  }

  private void write(final BufferedWriter out, final ArrayList<String> batch) throws IOException {
    for (final var line : batch) {
      out.write(line);
      out.write('\n');
    }
    out.flush();
    synchronized (flushed) {
      written += batch.size();
      flushed.notifyAll();
    }
  }

  /// Stops accepting rows, gives the writer five seconds to drain, and reports rather than
  /// hides a writer that failed.
  @Override
  public void close() {
    closing = true;
    flushNow(Duration.ofSeconds(5));
    writer.interrupt();
    try {
      writer.join(Duration.ofSeconds(5).toMillis());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (failure != null) {
      throw new UncheckedIOException(failure);
    }
  }
}
