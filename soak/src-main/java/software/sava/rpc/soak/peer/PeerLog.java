package software.sava.rpc.soak.peer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/// Append-only peer logs: `peer-ws.tsv`, `peer-http.tsv` and `faults-applied.tsv` (`DESIGN.md` §6,
/// §7).
///
/// One writer thread drains a **bounded** queue. Bounded is the point: a peer whose logging can
/// block is a peer that stalls the connection it is describing, and the stall would then be
/// attributed to the client under test. So the queue overflows instead, [#dropped()] counts the
/// loss, and `/__soak/stats` reports it — a drop is visible evidence, never a silent gap in the
/// timeline the client's oracles are joined against.
///
/// The first line of each file is the `anchor` row carrying `System.nanoTime()` at start, so client
/// and peer nanos can be joined at *millisecond* granularity. Never finer: the two JVMs' nanoTime
/// origins are unrelated.
final class PeerLog implements AutoCloseable {

  private static final int QUEUE_CAPACITY = 1 << 16;
  private static final int FLUSH_EVERY = 100;
  private static final int WS = 0;
  private static final int HTTP = 1;
  private static final int FAULTS = 2;

  /// `faults-applied.tsv` carries a header row, unlike the two event logs whose first line is the
  /// anchor: it has no anchor to place there and the report joins it by `ordinal`, not by time.
  static final String FAULTS_HEADER =
      "ordinal\tkind\tscope\tport\tconnId\tattemptOrdinal\twallMillis\tnanosSinceAnchor\toutcome\tdetail";

  private record Row(int file, String line, long stampMillis) {
  }

  private final long anchorNanos;
  private final long anchorMillis;
  private final BufferedWriter[] writers;
  private final ArrayBlockingQueue<Row> queue;
  private final LongAdder dropped;
  /// The longest any row waited between its stamp and its flush to disk, over the run.
  private volatile long maxLagMillis;
  private final AtomicBoolean running;
  private final CountDownLatch drained;
  private final Thread writerThread;

  PeerLog(final Path runDir) throws IOException {
    this.anchorNanos = System.nanoTime();
    this.anchorMillis = System.currentTimeMillis();
    this.writers = new BufferedWriter[]{
        open(runDir.resolve("peer-ws.tsv")),
        open(runDir.resolve("peer-http.tsv")),
        open(runDir.resolve("faults-applied.tsv"))
    };
    this.queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    this.dropped = new LongAdder();
    this.running = new AtomicBoolean(true);
    this.drained = new CountDownLatch(1);

    // Anchor rows first, written directly so they cannot be the thing that gets dropped.
    final var anchorDetail = "nanoTime=" + anchorNanos + " wallMillis=" + anchorMillis;
    writers[WS].write(anchorMillis + "\t0\t-1\tanchor\t-1\t-1\t-1\t-1\t-1\t0\t" + anchorDetail + '\n');
    writers[HTTP].write(anchorMillis + "\t0\tanchor\t-1\t-1\t-1\t0\t-\t-1\t-\t" + anchorDetail + '\n');
    writers[FAULTS].write(FAULTS_HEADER + '\n');
    for (final var writer : writers) {
      writer.flush();
    }

    this.writerThread = new Thread(this::drain, "soak-peer-log");
    this.writerThread.setDaemon(true);
  }

  /// The peer's share of the harness tail's lag: the longest a row waited between being stamped
  /// and being flushed. Written to `peer-summary.json` as `logMaxLagMs`.
  long maxLagMillis() {
    return maxLagMillis;
  }

  private static BufferedWriter open(final Path path) throws IOException {
    return Files.newBufferedWriter(path, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
  }

  void start() {
    writerThread.start();
  }

  long anchorNanos() {
    return anchorNanos;
  }

  long anchorMillis() {
    return anchorMillis;
  }

  long dropped() {
    return dropped.sum();
  }

  /// `wallMillis nanosSinceAnchor port kind connId attemptOrdinal subId msgId seq bytes detail`.
  ///
  /// The detail column is free text owned by the writer, but parts of it are read by the client's
  /// oracles and are therefore interface: a `sub_req` row ends with `fp=<16 hex digits>`, the
  /// subscribe identity (fnv64 of `method|params`) W1-B keys on, and an `unsub_req` row carries
  /// `subMsgId=<n>`, the subscribe that granted the id. Two kinds carry the same weight: an
  /// `overflow` row is what a 1011 close and the retirement behind it are attributed to, so a
  /// retirement that follows one is the peer's doing; and an `abort` row naming a `HANDSHAKE_*`
  /// fault is how a connection the peer accepted and never upgraded closes in the client's view of
  /// the port. All are produced by [WsConnection]; a change to any of them is a change to
  /// `DESIGN.md` §7.
  ///
  /// Absence can be interface too. `DROP_REPLAYED_SUBSCRIBES` answers a replayed subscribe on the
  /// wire and deliberately writes neither a `sub_req` nor a `sub_ack` row: the missing rows are the
  /// staged defect W1-A reads. That is the only message the peer puts on the wire without logging
  /// it.
  void ws(final int port,
          final String kind,
          final long connId,
          final long attemptOrdinal,
          final long subId,
          final long msgId,
          final long seq,
          final long bytes,
          final String detail) {
    final var line = System.currentTimeMillis() + "\t" + (System.nanoTime() - anchorNanos)
        + '\t' + port + '\t' + kind + '\t' + connId + '\t' + attemptOrdinal + '\t' + subId
        + '\t' + msgId + '\t' + seq + '\t' + bytes + '\t' + clean(detail) + '\n';
    offer(WS, line);
  }

  /// `wallMillis nanosSinceAnchor method requestId seq status bytes encoding delayMillis faultKind detail`.
  void http(final String method,
            final long requestId,
            final long seq,
            final int status,
            final long bytes,
            final String encoding,
            final long delayMillis,
            final String faultKind,
            final String detail) {
    final var line = System.currentTimeMillis() + "\t" + (System.nanoTime() - anchorNanos)
        + '\t' + method + '\t' + requestId + '\t' + seq + '\t' + status + '\t' + bytes
        + '\t' + encoding + '\t' + delayMillis + '\t' + faultKind + '\t' + clean(detail) + '\n';
    offer(HTTP, line);
  }

  /// `ordinal kind scope port connId attemptOrdinal wallMillis nanosSinceAnchor outcome detail`
  /// with `outcome` in `{applied, no_target}`.
  void faultApplied(final Fault fault,
                    final long connId,
                    final long attemptOrdinal,
                    final String outcome,
                    final String detail) {
    final var line = fault.ordinal() + "\t" + fault.kind() + '\t' + fault.kind().scope()
        + '\t' + fault.port() + '\t' + connId + '\t' + attemptOrdinal
        + '\t' + System.currentTimeMillis() + '\t' + (System.nanoTime() - anchorNanos)
        + '\t' + outcome + '\t' + clean(detail) + '\n';
    offer(FAULTS, line);
  }

  private static String clean(final String detail) {
    if (detail == null || detail.isEmpty()) {
      return "-";
    }
    return detail.indexOf('\t') < 0 && detail.indexOf('\n') < 0
        ? detail
        : detail.replace('\t', ' ').replace('\n', ' ');
  }

  private void offer(final int file, final String line) {
    if (!queue.offer(new Row(file, line, System.currentTimeMillis()))) {
      dropped.increment();
    }
  }

  private void drain() {
    final var batch = new ArrayList<Row>(FLUSH_EVERY);
    while (running.get() || !queue.isEmpty()) {
      try {
        final var first = queue.poll(500, TimeUnit.MILLISECONDS);
        if (first == null) {
          flushAll();
          continue;
        }
        batch.add(first);
        queue.drainTo(batch, FLUSH_EVERY - 1);
        for (final var row : batch) {
          writers[row.file()].write(row.line());
        }
        flushAll();
        // How long the rows of this batch waited between their stamp and the flush: the peer's
        // half of the lag the harness's tail measures against the same stamps.
        final long landed = System.currentTimeMillis();
        for (final var row : batch) {
          final long waited = landed - row.stampMillis();
          if (waited > maxLagMillis) {
            maxLagMillis = waited;
          }
        }
        batch.clear();
      } catch (final InterruptedException ex) {
        Thread.currentThread().interrupt();
        break;
      } catch (final IOException ex) {
        // A log write that cannot land must not take the peer down with it: the run is still
        // producing evidence on the wire, and the drop counter is the honest record of the loss.
        dropped.increment();
      }
    }
    try {
      flushAll();
    } catch (final IOException ex) {
      dropped.increment();
    }
    drained.countDown();
  }

  private void flushAll() throws IOException {
    for (final var writer : writers) {
      writer.flush();
    }
  }

  /// Drain what is queued and fsync the files; `/__soak/flush` and the SIGTERM hook both use it.
  void flush() {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!queue.isEmpty() && System.nanoTime() < deadline) {
      java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
    }
  }

  @Override
  public void close() {
    if (running.compareAndSet(true, false)) {
      try {
        drained.await(10, TimeUnit.SECONDS);
      } catch (final InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
      for (final var writer : writers) {
        try {
          writer.close();
        } catch (final IOException ex) {
          throw new UncheckedIOException(ex);
        }
      }
    }
  }
}
