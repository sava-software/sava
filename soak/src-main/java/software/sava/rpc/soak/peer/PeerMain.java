package software.sava.rpc.soak.peer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/// The peer JVM (`DESIGN.md` §2): `PeerMain <runDir>`.
///
/// It opens `SOAK_WS_PORT_COUNT` websocket ports and one HTTP port on 127.0.0.1 ephemeral ports,
/// proves its own determinism, writes the fault schedule, prints the ports, and then does nothing
/// but serve until SIGTERM.
///
/// Three deliberate choices:
///
/// **Its own `run.env` parser.** The peer reads the same file as the client but shares no
/// configuration class with it, so a change to the client's `SoakConfig` cannot silently change
/// what the peer does. It also does **not** reject unknown `SOAK_*` keys the way the client does —
/// most of them are the client's, and the peer is not the thing that validates them.
///
/// **Determinism before ports.** The self-check builds the first 64 responses of every RPC method
/// and the first 64 notifications of every channel twice, from two fresh instances, and compares
/// bytes. A peer whose output is not a pure function of `(seed, ordinal)` makes every client-side
/// assertion unfalsifiable, so a mismatch stops the run *before* the client starts rather than
/// producing evidence nobody can trust.
///
/// **The schedule is written before the ports are printed.** `soak.sh` therefore cannot start the
/// client against a peer whose plan is not yet on disk, and the report always has a plan to diff
/// the outcome against.
public final class PeerMain {

  private static final int SELF_CHECK_SAMPLES = 64;
  private static final int EXIT_NONDETERMINISTIC = 4;
  private static final int EXIT_USAGE = 2;

  private PeerMain() {
  }

  /// Everything the peer reads from `run.env`. Kept separate from the client's `SoakConfig` on
  /// purpose (see the class note).
  record Config(Path runDir,
                String profile,
                String control,
                long seed,
                int durationSeconds,
                int warmupSeconds,
                int stormSeconds,
                int wsPortCount,
                int peerHttpThreads,
                int blockTxs,
                int pgaAccounts,
                int burstFrames,
                int burstPeriodSeconds,
                int largePeriodSeconds,
                int largeBytes,
                int fragments,
                int refuseSeconds,
                int slowConsumerMicros,
                int throwEvery,
                int wsSubscriptions,
                int wsChurnPerMinute,
                int wsNotifyRps,
                int httpRps,
                int faultRate,
                int faultMinPerKind,
                int destructivePerHour,
                int checkDelayMs,
                int pingDelayMs) {

    /// `DELAY_CONFIRM` waits twice the engine's own resend delay, which is
    /// `max(reConnectDelay, subscriptionAndPingCheckDelay)` and defaults to 3000 ms. Twice it is
    /// slow enough to be interesting and short enough that the engine's escalation (4x resend) does
    /// not fire — the property is "a slow confirmation is not an escalation".
    long delayConfirmMillis() {
      return 2L * Math.max(3000, checkDelayMs);
    }
  }

  public static void main(final String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("usage: PeerMain <runDir>");
      System.exit(EXIT_USAGE);
      return;
    }
    final var runDir = Path.of(args[0]).toAbsolutePath().normalize();
    final var config = load(runDir);

    if (!selfCheck(config)) {
      System.out.println("PEER_NONDETERMINISTIC");
      System.err.println("peer responses are not a pure function of (seed, ordinal); refusing to start");
      System.exit(EXIT_NONDETERMINISTIC);
      return;
    }

    final var log = new PeerLog(runDir);
    log.start();
    final var payloads = new Payloads(config.seed(), config.blockTxs(), config.pgaAccounts(), config.largeBytes());
    final var subIds = new AtomicLong(1_000L);

    final var wsPeers = new ArrayList<WsPeer>(config.wsPortCount());
    for (int i = 0; i < config.wsPortCount(); ++i) {
      wsPeers.add(new WsPeer(config, i, log, payloads, subIds));
    }
    for (final var peer : wsPeers) {
      // CROSSTALK borrows a sequence from any live connection the peer process holds: every port
      // serves one engine, so a same-port sibling exists only during a reconnect's overlap.
      peer.siblings(wsPeers);
    }
    final var shutdown = new CountDownLatch(1);
    final var rpcPeer = new RpcPeer(config, log, payloads, List.copyOf(wsPeers), shutdown::countDown);

    final int[] wsPorts = new int[wsPeers.size()];
    for (int i = 0; i < wsPeers.size(); ++i) {
      wsPorts[i] = wsPeers.get(i).port();
    }
    final var schedule = FaultSchedule.of(config.seed(), config, wsPorts, rpcPeer.port());
    schedule.write(runDir.resolve("fault-schedule.tsv"));

    for (final var peer : wsPeers) {
      peer.start(schedule);
    }
    rpcPeer.start(schedule);

    final var summaryWritten = new java.util.concurrent.atomic.AtomicBoolean();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      if (summaryWritten.compareAndSet(false, true)) {
        finish(runDir, config, schedule, wsPeers, rpcPeer, log);
      }
    }, "soak-peer-shutdown"));

    final var ports = new StringBuilder(64);
    for (int i = 0; i < wsPorts.length; ++i) {
      if (i > 0) {
        ports.append(',');
      }
      ports.append(wsPorts[i]);
    }
    System.out.println("WS_PORTS=" + ports);
    System.out.println("HTTP_PORT=" + rpcPeer.port());
    System.out.println("SCHEDULE_SHA256=" + schedule.fileSha256());
    System.out.println("SCHEDULE_CANONICAL_SHA256=" + schedule.canonicalSha256());
    System.out.println("SCHEDULE_PLANNED=" + schedule.planned().size());
    if (!schedule.omitted().isEmpty()) {
      // A run too short for the per-kind minimums says so up front, rather than letting the report
      // discover the gap at the end and call it UNEXERCISED.
      System.out.println("SCHEDULE_OMITTED=" + String.join(",", schedule.omitted().stream().map(Enum::name).toList()));
    }
    System.out.println("PEER_READY");
    System.out.flush();

    shutdown.await();
    if (summaryWritten.compareAndSet(false, true)) {
      finish(runDir, config, schedule, wsPeers, rpcPeer, log);
    }
  }

  private static void finish(final Path runDir,
                             final Config config,
                             final FaultSchedule schedule,
                             final List<WsPeer> wsPeers,
                             final RpcPeer rpcPeer,
                             final PeerLog log) {
    try {
      for (final var peer : wsPeers) {
        peer.shutdown();
      }
      rpcPeer.shutdown();
      log.flush();
      Files.writeString(runDir.resolve("peer-summary.json"), summary(config, schedule, wsPeers, rpcPeer, log),
          StandardCharsets.UTF_8);
    } catch (final IOException ex) {
      System.err.println("failed to write peer-summary.json: " + ex);
    } finally {
      log.close();
    }
  }

  private static String summary(final Config config,
                                final FaultSchedule schedule,
                                final List<WsPeer> wsPeers,
                                final RpcPeer rpcPeer,
                                final PeerLog log) {
    final var sb = new StringBuilder(1024);
    sb.append("{\"profile\":\"").append(config.profile())
        .append("\",\"control\":\"").append(config.control())
        .append("\",\"seed\":").append(config.seed())
        .append(",\"scheduleSha256\":\"").append(schedule.fileSha256())
        .append("\",\"scheduleCanonicalSha256\":\"").append(schedule.canonicalSha256())
        .append("\",\"faultsPlanned\":").append(schedule.planned().size())
        .append(",\"faultsRemaining\":").append(schedule.remaining())
        .append(",\"omittedKinds\":[");
    final var omitted = new ArrayList<String>(schedule.omitted().size());
    for (final var kind : schedule.omitted()) {
      omitted.add('"' + kind.name() + '"');
    }
    final var stats = rpcPeer.stats();
    sb.append(String.join(",", omitted)).append("],\"logDropped\":").append(log.dropped())
        .append(",\"logMaxLagMs\":").append(log.maxLagMillis())
        .append(',').append(stats, 1, stats.length());
    return sb.toString();
  }

  // --- determinism self-check -------------------------------------------------------------------

  /// Builds the first [#SELF_CHECK_SAMPLES] responses of every RPC method and notifications of every
  /// channel twice from two fresh instances and compares the bytes.
  static boolean selfCheck(final Config config) {
    final var a = new Payloads(config.seed(), config.blockTxs(), config.pgaAccounts(), 4096);
    final var b = new Payloads(config.seed(), config.blockTxs(), config.pgaAccounts(), 4096);
    for (int i = 1; i <= SELF_CHECK_SAMPLES; ++i) {
      final var key = Stamp.derivedKey("self-check", i);
      final var sigs = List.of(Stamp.signature(i), Stamp.signature(i + 1));
      final var keys = List.of(key, Stamp.derivedKey("self-check", i + 1));
      if (!same(a.getAccountInfo(i, i, key), b.getAccountInfo(i, i, key))
          || !same(a.getMultipleAccounts(i, i, keys), b.getMultipleAccounts(i, i, keys))
          || !same(a.getProgramAccounts(i, i, key, 4), b.getProgramAccounts(i, i, key, 4))
          || !same(a.getBlock(i, Stamp.BASE_SLOT + i, 2), b.getBlock(i, Stamp.BASE_SLOT + i, 2))
          || !same(a.getSignatureStatuses(i, i, sigs), b.getSignatureStatuses(i, i, sigs))
          || !same(a.getLatestBlockHash(i, i), b.getLatestBlockHash(i, i))
          || !same(a.getSlot(i, i), b.getSlot(i, i))
          || !same(a.getBlockHeight(i, i), b.getBlockHeight(i, i))
          || !same(a.getEpochInfo(i, i), b.getEpochInfo(i, i))
          || !same(a.getVersion(i), b.getVersion(i))
          || !same(a.getHealth(i), b.getHealth(i))) {
        return false;
      }
      for (final var channel : Payloads.Channel.values()) {
        if (!same(a.notification(channel, 7, i, key, 64, false), b.notification(channel, 7, i, key, 64, false))
            || !same(a.notification(channel, 7, i, key, 4096, true), b.notification(channel, 7, i, key, 4096, true))) {
          return false;
        }
      }
      if (!same(a.overflowMessage(7, i), b.overflowMessage(7, i))
          || !same(a.unknownSubNotification(i), b.unknownSubNotification(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean same(final String left, final String right) {
    return java.util.Arrays.equals(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  // --- configuration ----------------------------------------------------------------------------

  /// `KEY=VALUE` lines, `#` comments, then the process environment wins — the same precedence the
  /// client's `SoakConfig` uses, so a `SOAK_*` override applies to both JVMs at once.
  static Map<String, String> readRunEnv(final Path runDir) throws IOException {
    final var values = new LinkedHashMap<String, String>();
    final var file = runDir.resolve("run.env");
    if (Files.isReadable(file)) {
      for (final var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        final var trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
          continue;
        }
        final int eq = trimmed.indexOf('=');
        if (eq > 0) {
          values.put(trimmed.substring(0, eq).strip(), unquote(trimmed.substring(eq + 1).strip()));
        }
      }
    }
    for (final var entry : System.getenv().entrySet()) {
      if (entry.getKey().startsWith("SOAK_")) {
        values.put(entry.getKey(), entry.getValue());
      }
    }
    return values;
  }

  private static String unquote(final String value) {
    if (value.length() >= 2
        && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
        || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  static Config load(final Path runDir) throws IOException {
    final var env = readRunEnv(runDir);
    final var profile = env.getOrDefault("SOAK_PROFILE", "validate").toLowerCase(Locale.ROOT);
    final int p = profileIndex(profile);
    return new Config(
        Path.of(env.getOrDefault("SOAK_RUN_DIR", runDir.toString())).toAbsolutePath().normalize(),
        profile,
        env.getOrDefault("SOAK_CONTROL", ""),
        longOf(env, "SOAK_SEED", 0x5A7A50A40001L),
        intOf(env, "SOAK_DURATION_SECONDS", pick(p, 240, 3600, 28800)),
        intOf(env, "SOAK_WARMUP_SECONDS", pick(p, 30, 360, 1800)),
        intOf(env, "SOAK_STORM_SECONDS", pick(p, 20, 60, 60)),
        intOf(env, "SOAK_WS_PORT_COUNT", 5),
        intOf(env, "SOAK_PEER_HTTP_THREADS", 16),
        intOf(env, "SOAK_BLOCK_TXS", 400),
        intOf(env, "SOAK_PGA_ACCOUNTS", 500),
        intOf(env, "SOAK_BURST_FRAMES", 256),
        intOf(env, "SOAK_BURST_PERIOD_SECONDS", 120),
        intOf(env, "SOAK_LARGE_PERIOD_SECONDS", 300),
        intOf(env, "SOAK_LARGE_BYTES", 8_388_608),
        intOf(env, "SOAK_FRAGMENTS", 37),
        intOf(env, "SOAK_REFUSE_SECONDS", 20),
        intOf(env, "SOAK_SLOW_CONSUMER_MICROS", 2000),
        intOf(env, "SOAK_THROW_EVERY", 7),
        intOf(env, "SOAK_WS_SUBSCRIPTIONS", pick(p, 24, 64, 128)),
        intOf(env, "SOAK_WS_CHURN_PER_MINUTE", pick(p, 120, 60, 60)),
        intOf(env, "SOAK_WS_NOTIFY_RPS", pick(p, 200, 200, 400)),
        intOf(env, "SOAK_HTTP_RPS", pick(p, 20, 40, 40)),
        intOf(env, "SOAK_FAULT_RATE", pick(p, 20, 120, 300)),
        intOf(env, "SOAK_FAULT_MIN_PER_KIND", pick(p, 1, 3, 8)),
        intOf(env, "SOAK_DESTRUCTIVE_PER_HOUR", pick(p, 240, 60, 30)),
        intOf(env, "SOAK_CHECK_DELAY_MS", pick(p, 1000, 2000, 2000)),
        intOf(env, "SOAK_PING_DELAY_MS", pick(p, 4000, 15000, 15000))
    );
  }

  /// `control` and `live` borrow the validate column: both are short runs whose point is a single
  /// named behaviour, not a trend.
  private static int profileIndex(final String profile) {
    return switch (profile) {
      case "pilot" -> 1;
      case "campaign" -> 2;
      default -> 0;
    };
  }

  private static int pick(final int index, final int validate, final int pilot, final int campaign) {
    return switch (index) {
      case 1 -> pilot;
      case 2 -> campaign;
      default -> validate;
    };
  }

  private static int intOf(final Map<String, String> env, final String key, final int fallback) {
    final var value = env.get(key);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.strip());
    } catch (final NumberFormatException ex) {
      return fallback;
    }
  }

  private static long longOf(final Map<String, String> env, final String key, final long fallback) {
    final var value = env.get(key);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    final var text = value.strip();
    try {
      return text.startsWith("0x") || text.startsWith("0X")
          ? Long.parseUnsignedLong(text.substring(2), 16)
          : Long.parseLong(text);
    } catch (final NumberFormatException ex) {
      return fallback;
    }
  }
}
