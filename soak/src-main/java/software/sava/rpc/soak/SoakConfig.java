package software.sava.rpc.soak;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/// Every knob of a run, resolved once at start-up from `<runDir>/run.env` and then from the
/// process environment, and rendered back out by [#toRunEnv()] so the artifact directory records
/// the values the run actually used rather than the ones the runner intended.
///
/// Two rules make a soak reproducible, and both are enforced here rather than by convention:
/// an unknown `SOAK_*` key is a start-up error (a typo that silently keeps a default is how a
/// run quietly stops testing what its operator thinks it tests), and every value — including
/// every peer-side key this JVM never reads — is carried and re-rendered, so `run.env` in the
/// artifacts is the whole configuration and not the subset one process cared about.
public final class SoakConfig {

  /// Which executor the shared [java.net.http.HttpClient] is built on. `platform` is the default
  /// because the response parser runs on whichever thread completes the JDK future, so a named
  /// platform pool makes those frames legible in the recording; `virtual` is the second
  /// validation run, where the virtual-thread balance and pinning counters become load-bearing.
  public enum ClientExecutor {
    PLATFORM,
    VIRTUAL;

    static ClientExecutor of(final String value) {
      return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "platform" -> PLATFORM;
        case "virtual" -> VIRTUAL;
        default -> throw new IllegalArgumentException(
            "SOAK_HTTP_CLIENT_EXECUTOR must be platform|virtual, not '" + value + '\'');
      };
    }

    public String lowerName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /// The cluster a live run points at. `mainnet` additionally requires `SOAK_LIVE_CONFIRM`, so
  /// that pointing a soak at production is never a one-character mistake.
  public enum Cluster {
    LOCAL,
    DEVNET,
    TESTNET,
    MAINNET;

    static Cluster of(final String value) {
      for (final var cluster : values()) {
        if (cluster.name().equalsIgnoreCase(value.trim())) {
          return cluster;
        }
      }
      throw new IllegalArgumentException(
          "SOAK_LIVE_CLUSTER must be local|devnet|testnet|mainnet, not '" + value + '\'');
    }

    public String lowerName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  // --- key names, in the order run.env renders them -------------------------------------------

  public static final String PROFILE = "SOAK_PROFILE";
  public static final String RUN_DIR = "SOAK_RUN_DIR";
  public static final String DURATION_SECONDS = "SOAK_DURATION_SECONDS";
  public static final String WARMUP_SECONDS = "SOAK_WARMUP_SECONDS";
  public static final String QUIESCE_SECONDS = "SOAK_QUIESCE_SECONDS";
  public static final String DRAIN_SECONDS = "SOAK_DRAIN_SECONDS";
  public static final String SEED = "SOAK_SEED";
  public static final String WS_ENGINES = "SOAK_WS_ENGINES";
  public static final String WS_SUBSCRIPTIONS = "SOAK_WS_SUBSCRIPTIONS";
  public static final String WS_CHURN_PER_MINUTE = "SOAK_WS_CHURN_PER_MINUTE";
  public static final String WS_NOTIFY_RPS = "SOAK_WS_NOTIFY_RPS";
  public static final String HTTP_CONCURRENCY = "SOAK_HTTP_CONCURRENCY";
  public static final String HTTP_INFLIGHT = "SOAK_HTTP_INFLIGHT";
  public static final String HTTP_RPS = "SOAK_HTTP_RPS";
  public static final String LARGE_FRACTION = "SOAK_LARGE_FRACTION";
  public static final String NOWRAP_FRACTION = "SOAK_NOWRAP_FRACTION";
  public static final String CANCEL_FRACTION = "SOAK_CANCEL_FRACTION";
  /// Mean ops between scheduled faults. `0` means **no scheduled faults at all**, which is what
  /// the no-faults negative control configures: a run whose fault plan is empty is the baseline
  /// every faulted run is read against, and it has to be expressible. It is therefore the one
  /// rate-shaped key that is not required to be positive — the schedule reads `0` as "plan
  /// nothing", never as "plan one fault per op".
  public static final String FAULT_RATE = "SOAK_FAULT_RATE";
  /// Minimum planned faults per kind. `0` means no kind is owed a minimum, so the plan is the
  /// weighted fill alone — and with [#FAULT_RATE] also `0`, nothing.
  public static final String FAULT_MIN_PER_KIND = "SOAK_FAULT_MIN_PER_KIND";
  /// Ceiling on retiring faults per hour of expected traffic, binding the weighted fill and the
  /// storms against one shared counter. It also sizes the `conn` trigger domain, because a
  /// connection ordinal is reached only through a retirement (`peer.FaultSchedule.connRange`), so a
  /// value set here decides which connection ordinals a fault may be keyed on at all.
  public static final String DESTRUCTIVE_PER_HOUR = "SOAK_DESTRUCTIVE_PER_HOUR";
  public static final String STORM_SECONDS = "SOAK_STORM_SECONDS";
  public static final String CHURN_PERIOD_SECONDS = "SOAK_CHURN_PERIOD_SECONDS";
  public static final String HEAP_MB = "SOAK_HEAP_MB";
  /// Extra arguments soak.sh appends to the client JVM's command line (a control row's
  /// measurement aid, empty otherwise). Registered here only so a run.env that carries the key
  /// passes the unknown-key check: nothing in the harness reads it.
  public static final String JVM_EXTRA = "SOAK_JVM_EXTRA";
  public static final String JFR_MAXSIZE = "SOAK_JFR_MAXSIZE";
  public static final String JFR_MAXAGE = "SOAK_JFR_MAXAGE";
  public static final String NMT_INTERVAL_SECONDS = "SOAK_NMT_INTERVAL_SECONDS";
  public static final String SAMPLE_SECONDS = "SOAK_SAMPLE_SECONDS";
  public static final String GAUGE_SECONDS = "SOAK_GAUGE_SECONDS";
  public static final String THREAD_MARGIN = "SOAK_THREAD_MARGIN";
  public static final String FD_MARGIN = "SOAK_FD_MARGIN";
  public static final String VTHREAD_MARGIN = "SOAK_VTHREAD_MARGIN";
  public static final String SKIP_LIMIT_PCT = "SOAK_SKIP_LIMIT_PCT";
  public static final String RSS_SLOPE_KIB_PER_HOUR = "SOAK_RSS_SLOPE_KIB_PER_HOUR";
  public static final String RSS_NOISE_FLOOR_KIB = "SOAK_RSS_NOISE_FLOOR_KIB";
  public static final String HEAP_FLOOR_SLOPE_KIB_PER_HOUR = "SOAK_HEAP_FLOOR_SLOPE_KIB_PER_HOUR";
  /// The after-GC heap floor's own noise floor (the report reads it; registered so run.env passes).
  public static final String HEAP_FLOOR_NOISE_KIB = "SOAK_HEAP_FLOOR_NOISE_KIB";
  public static final String PING_DELAY_MS = "SOAK_PING_DELAY_MS";
  public static final String CHECK_DELAY_MS = "SOAK_CHECK_DELAY_MS";
  public static final String HTTP_CLIENT_EXECUTOR = "SOAK_HTTP_CLIENT_EXECUTOR";
  public static final String SOCKET_THRESHOLD_MS = "SOAK_SOCKET_THRESHOLD_MS";
  public static final String WS_PORTS = "SOAK_WS_PORTS";
  public static final String HTTP_PORT = "SOAK_HTTP_PORT";
  public static final String CONTROL = "SOAK_CONTROL";
  public static final String MANAGER_REFLECT = "SOAK_MANAGER_REFLECT";
  public static final String LIVE = "SOAK_LIVE";
  public static final String LIVE_HTTP_URL = "SOAK_LIVE_HTTP_URL";
  public static final String LIVE_WS_URL = "SOAK_LIVE_WS_URL";
  public static final String LIVE_CLUSTER = "SOAK_LIVE_CLUSTER";
  public static final String LIVE_CONFIRM = "SOAK_LIVE_CONFIRM";
  public static final String LIVE_RPS = "SOAK_LIVE_RPS";
  public static final String LIVE_CONCURRENCY = "SOAK_LIVE_CONCURRENCY";
  public static final String LIVE_ACCOUNTS = "SOAK_LIVE_ACCOUNTS";
  public static final String PEER_HTTP_THREADS = "SOAK_PEER_HTTP_THREADS";
  public static final String BLOCK_TXS = "SOAK_BLOCK_TXS";
  public static final String PGA_ACCOUNTS = "SOAK_PGA_ACCOUNTS";
  public static final String BURST_FRAMES = "SOAK_BURST_FRAMES";
  public static final String BURST_PERIOD_SECONDS = "SOAK_BURST_PERIOD_SECONDS";
  public static final String LARGE_PERIOD_SECONDS = "SOAK_LARGE_PERIOD_SECONDS";
  public static final String LARGE_BYTES = "SOAK_LARGE_BYTES";
  public static final String FRAGMENTS = "SOAK_FRAGMENTS";
  public static final String REFUSE_SECONDS = "SOAK_REFUSE_SECONDS";
  public static final String SLOW_CONSUMER_MICROS = "SOAK_SLOW_CONSUMER_MICROS";
  public static final String THROW_EVERY = "SOAK_THROW_EVERY";
  public static final String WS_PORT_COUNT = "SOAK_WS_PORT_COUNT";

  /// The keys a live run must supply itself: there is no sensible default for somebody else's
  /// endpoint, and a defaulted one would mean a mistyped run silently hitting the wrong node.
  private static final List<String> LIVE_REQUIRED = List.of(
      LIVE_HTTP_URL, LIVE_WS_URL, LIVE_CLUSTER, LIVE_RPS, LIVE_CONCURRENCY, LIVE_ACCOUNTS);

  /// `soak.sh`'s own switches, which share the `SOAK_` prefix but never describe the run: the
  /// `--controls` parent exports two of them into every child's environment, and a dry run sets
  /// the third. They are never written to `run.env`, and the process environment is read here only
  /// as an overlay, so they are skipped rather than rejected; every other unknown `SOAK_*` key in
  /// the environment is still the start-up error the design asks for.
  private static final List<String> RUNNER_PRIVATE = List.of("SOAK_DRY_RUN", "SOAK_SKIP_BUILD", "SOAK_NO_LOCK");

  private final Map<String, String> resolved;
  private final Profile profile;
  private final Path runDir;
  private final int durationSeconds;
  private final int warmupSeconds;
  private final int quiesceSeconds;
  private final int drainSeconds;
  private final long seed;
  private final int wsEngines;
  private final int wsSubscriptions;
  private final int wsChurnPerMinute;
  private final int wsNotifyRps;
  private final int httpConcurrency;
  private final int httpInflight;
  private final int httpRps;
  private final double largeFraction;
  private final double noWrapFraction;
  private final double cancelFraction;
  private final int faultRate;
  private final int faultMinPerKind;
  private final int destructivePerHour;
  private final int stormSeconds;
  private final int churnPeriodSeconds;
  private final int heapMb;
  private final String jfrMaxSize;
  private final String jfrMaxAge;
  private final int nmtIntervalSeconds;
  private final int sampleSeconds;
  private final int gaugeSeconds;
  private final int threadMargin;
  private final int fdMargin;
  private final int vthreadMargin;
  private final int skipLimitPct;
  private final long rssSlopeKibPerHour;
  private final long rssNoiseFloorKib;
  private final long heapFloorSlopeKibPerHour;
  private final long pingDelayMillis;
  private final long checkDelayMillis;
  private final ClientExecutor httpClientExecutor;
  private final int socketThresholdMillis;
  private final List<Integer> wsPorts;
  private final int httpPort;
  private final String control;
  private final boolean managerReflect;
  private final boolean live;
  private final String liveHttpUrl;
  private final String liveWsUrl;
  private final Cluster liveCluster;
  private final int liveRps;
  private final int liveConcurrency;
  private final Path liveAccounts;
  private final int peerHttpThreads;
  private final int blockTxs;
  private final int pgaAccounts;
  private final int burstFrames;
  private final int burstPeriodSeconds;
  private final int largePeriodSeconds;
  private final int largeBytes;
  private final int fragments;
  private final int refuseSeconds;
  private final int slowConsumerMicros;
  private final int throwEvery;
  private final int wsPortCount;

  private SoakConfig(final Map<String, String> resolved) {
    this.resolved = resolved;
    this.profile = Profile.of(resolved.get(PROFILE));
    this.runDir = Path.of(require(resolved, RUN_DIR)).toAbsolutePath().normalize();
    this.durationSeconds = positiveInt(resolved, DURATION_SECONDS);
    this.warmupSeconds = nonNegativeInt(resolved, WARMUP_SECONDS);
    this.quiesceSeconds = nonNegativeInt(resolved, QUIESCE_SECONDS);
    this.drainSeconds = nonNegativeInt(resolved, DRAIN_SECONDS);
    this.seed = parseSeed(require(resolved, SEED));
    this.wsEngines = positiveInt(resolved, WS_ENGINES);
    this.wsSubscriptions = positiveInt(resolved, WS_SUBSCRIPTIONS);
    this.wsChurnPerMinute = positiveInt(resolved, WS_CHURN_PER_MINUTE);
    this.wsNotifyRps = positiveInt(resolved, WS_NOTIFY_RPS);
    this.httpConcurrency = positiveInt(resolved, HTTP_CONCURRENCY);
    this.httpInflight = positiveInt(resolved, HTTP_INFLIGHT);
    this.httpRps = positiveInt(resolved, HTTP_RPS);
    this.largeFraction = fraction(resolved, LARGE_FRACTION);
    this.noWrapFraction = fraction(resolved, NOWRAP_FRACTION);
    this.cancelFraction = fraction(resolved, CANCEL_FRACTION);
    // Both accept 0: "no scheduled faults" is a configuration, not a typo. Rejecting it here is
    // what kept the no-faults control from ever running — the run died at start-up on its own
    // override, so the baseline the faulted runs are compared against was never measured.
    this.faultRate = nonNegativeInt(resolved, FAULT_RATE);
    this.faultMinPerKind = nonNegativeInt(resolved, FAULT_MIN_PER_KIND);
    this.destructivePerHour = nonNegativeInt(resolved, DESTRUCTIVE_PER_HOUR);
    this.stormSeconds = nonNegativeInt(resolved, STORM_SECONDS);
    this.churnPeriodSeconds = positiveInt(resolved, CHURN_PERIOD_SECONDS);
    this.heapMb = positiveInt(resolved, HEAP_MB);
    this.jfrMaxSize = require(resolved, JFR_MAXSIZE);
    this.jfrMaxAge = require(resolved, JFR_MAXAGE);
    this.nmtIntervalSeconds = positiveInt(resolved, NMT_INTERVAL_SECONDS);
    this.sampleSeconds = positiveInt(resolved, SAMPLE_SECONDS);
    this.gaugeSeconds = positiveInt(resolved, GAUGE_SECONDS);
    this.threadMargin = nonNegativeInt(resolved, THREAD_MARGIN);
    this.fdMargin = nonNegativeInt(resolved, FD_MARGIN);
    this.vthreadMargin = nonNegativeInt(resolved, VTHREAD_MARGIN);
    this.skipLimitPct = nonNegativeInt(resolved, SKIP_LIMIT_PCT);
    this.rssSlopeKibPerHour = nonNegativeLong(resolved, RSS_SLOPE_KIB_PER_HOUR);
    this.rssNoiseFloorKib = nonNegativeLong(resolved, RSS_NOISE_FLOOR_KIB);
    this.heapFloorSlopeKibPerHour = nonNegativeLong(resolved, HEAP_FLOOR_SLOPE_KIB_PER_HOUR);
    this.pingDelayMillis = nonNegativeLong(resolved, PING_DELAY_MS);
    this.checkDelayMillis = nonNegativeLong(resolved, CHECK_DELAY_MS);
    this.httpClientExecutor = ClientExecutor.of(require(resolved, HTTP_CLIENT_EXECUTOR));
    this.socketThresholdMillis = nonNegativeInt(resolved, SOCKET_THRESHOLD_MS);
    this.wsPorts = parsePorts(resolved.getOrDefault(WS_PORTS, ""));
    this.httpPort = nonNegativeInt(resolved, HTTP_PORT);
    this.control = resolved.getOrDefault(CONTROL, "").trim();
    this.managerReflect = flag(resolved, MANAGER_REFLECT);
    this.live = flag(resolved, LIVE);
    this.liveHttpUrl = resolved.getOrDefault(LIVE_HTTP_URL, "").trim();
    this.liveWsUrl = resolved.getOrDefault(LIVE_WS_URL, "").trim();
    final var clusterValue = resolved.getOrDefault(LIVE_CLUSTER, "").trim();
    this.liveCluster = clusterValue.isEmpty() ? null : Cluster.of(clusterValue);
    this.liveRps = this.live ? positiveInt(resolved, LIVE_RPS) : 0;
    this.liveConcurrency = this.live ? positiveInt(resolved, LIVE_CONCURRENCY) : 0;
    final var accounts = resolved.getOrDefault(LIVE_ACCOUNTS, "").trim();
    this.liveAccounts = accounts.isEmpty() ? null : Path.of(accounts);
    this.peerHttpThreads = positiveInt(resolved, PEER_HTTP_THREADS);
    this.blockTxs = positiveInt(resolved, BLOCK_TXS);
    this.pgaAccounts = positiveInt(resolved, PGA_ACCOUNTS);
    this.burstFrames = positiveInt(resolved, BURST_FRAMES);
    this.burstPeriodSeconds = positiveInt(resolved, BURST_PERIOD_SECONDS);
    this.largePeriodSeconds = positiveInt(resolved, LARGE_PERIOD_SECONDS);
    this.largeBytes = positiveInt(resolved, LARGE_BYTES);
    this.fragments = positiveInt(resolved, FRAGMENTS);
    this.refuseSeconds = positiveInt(resolved, REFUSE_SECONDS);
    this.slowConsumerMicros = nonNegativeInt(resolved, SLOW_CONSUMER_MICROS);
    this.throwEvery = positiveInt(resolved, THROW_EVERY);
    this.wsPortCount = positiveInt(resolved, WS_PORT_COUNT);
    validate();
  }

  // --- loading --------------------------------------------------------------------------------

  /// Loads `<runDir>/run.env`, overlays the process environment, and fails on any unknown
  /// `SOAK_*` key from either source.
  public static SoakConfig load(final Path runDir) throws IOException {
    final var runEnv = runDir.resolve("run.env");
    final var fileValues = Files.isReadable(runEnv) ? parseRunEnv(Files.readString(runEnv, StandardCharsets.UTF_8))
        : new LinkedHashMap<String, String>();
    final var environment = new LinkedHashMap<String, String>();
    for (final var entry : System.getenv().entrySet()) {
      if (entry.getKey().startsWith("SOAK_") && !RUNNER_PRIVATE.contains(entry.getKey())) {
        environment.put(entry.getKey(), entry.getValue());
      }
    }
    fileValues.putIfAbsent(RUN_DIR, runDir.toAbsolutePath().normalize().toString());
    return of(fileValues, environment);
  }

  /// The pure half of [#load(Path)]: file values, then environment overrides, then per-profile
  /// defaults for everything still absent.
  public static SoakConfig of(final Map<String, String> fileValues, final Map<String, String> environment) {
    final var supplied = new LinkedHashMap<String, String>(fileValues);
    supplied.putAll(environment);
    final var profile = Profile.of(supplied.getOrDefault(PROFILE, "validate"));
    final var defaults = defaults(profile);
    final var unknown = new TreeSet<String>();
    for (final var key : supplied.keySet()) {
      if (key.startsWith("SOAK_") && !defaults.containsKey(key)) {
        unknown.add(key);
      }
    }
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException("unknown SOAK_* configuration key(s): " + String.join(", ", unknown));
    }
    final var resolved = new LinkedHashMap<String, String>(defaults);
    for (final var entry : supplied.entrySet()) {
      if (entry.getKey().startsWith("SOAK_")) {
        resolved.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue().trim());
      }
    }
    resolved.put(PROFILE, profile.lowerName());
    return new SoakConfig(resolved);
  }

  /// `KEY=VALUE` lines; `#` comments and blank lines ignored. The first line of a real `run.env`
  /// is the exact command line that produced the run, written as a comment by `soak.sh`.
  public static LinkedHashMap<String, String> parseRunEnv(final String text) {
    final var values = new LinkedHashMap<String, String>();
    for (final var rawLine : text.split("\n", -1)) {
      final var line = rawLine.strip();
      if (line.isEmpty() || line.charAt(0) == '#') {
        continue;
      }
      final int equals = line.indexOf('=');
      if (equals < 1) {
        throw new IllegalArgumentException("run.env line is not KEY=VALUE: " + line);
      }
      values.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
    }
    return values;
  }

  /// The complete key set, in render order, with this profile's defaults. Membership of this map
  /// is what makes a key "known"; a key absent here is a typo by definition.
  public static LinkedHashMap<String, String> defaults(final Profile profile) {
    final var defaults = new LinkedHashMap<String, String>();
    defaults.put(PROFILE, profile.lowerName());
    defaults.put(RUN_DIR, "");
    defaults.put(DURATION_SECONDS, profile.pick("240", "1800", "14400"));
    defaults.put(WARMUP_SECONDS, profile.pick("30", "360", "900"));
    defaults.put(QUIESCE_SECONDS, profile.pick("30", "120", "300"));
    defaults.put(DRAIN_SECONDS, profile.pick("30", "60", "60"));
    defaults.put(SEED, "0x5A7A50A40001");
    defaults.put(WS_ENGINES, "4");
    defaults.put(WS_SUBSCRIPTIONS, profile.pick("24", "64", "128"));
    defaults.put(WS_CHURN_PER_MINUTE, profile.pick("120", "60", "60"));
    defaults.put(WS_NOTIFY_RPS, profile.pick("200", "200", "400"));
    defaults.put(HTTP_CONCURRENCY, profile.pick("8", "16", "16"));
    defaults.put(HTTP_INFLIGHT, "4");
    defaults.put(HTTP_RPS, profile.pick("20", "40", "40"));
    defaults.put(LARGE_FRACTION, profile.pick("0.10", "0.05", "0.05"));
    defaults.put(NOWRAP_FRACTION, "0.05");
    defaults.put(CANCEL_FRACTION, "0.02");
    defaults.put(FAULT_RATE, profile.pick("20", "120", "300"));
    defaults.put(FAULT_MIN_PER_KIND, profile.pick("1", "3", "8"));
    // `soak.sh`'s `resolve_profile` carries a `by_profile SOAK_DESTRUCTIVE_PER_HOUR` row with its
    // own three-column table, and it exports the value before this JVM starts, so the runner's
    // column is what a `./soak.sh` run actually uses and this default only applies to a run started
    // without it. The two tables are a pair: changing one alone silently changes the retirement
    // budget — and with it the `conn` trigger domain — for only half the ways a soak is launched.
    defaults.put(DESTRUCTIVE_PER_HOUR, profile.pick("240", "60", "30"));
    defaults.put(STORM_SECONDS, profile.pick("20", "60", "60"));
    defaults.put(CHURN_PERIOD_SECONDS, profile.pick("15", "15", "30"));
    defaults.put(HEAP_MB, profile.pick("512", "1024", "1024"));
    defaults.put(JVM_EXTRA, "");
    defaults.put(JFR_MAXSIZE, profile.pick("256m", "256m", "1g"));
    defaults.put(JFR_MAXAGE, profile.pick("2h", "2h", "5h"));
    defaults.put(NMT_INTERVAL_SECONDS, profile.pick("60", "600", "1800"));
    defaults.put(SAMPLE_SECONDS, profile.pick("10", "30", "30"));
    defaults.put(GAUGE_SECONDS, "10");
    defaults.put(THREAD_MARGIN, profile.pick("24", "16", "16"));
    defaults.put(FD_MARGIN, profile.pick("96", "64", "64"));
    defaults.put(VTHREAD_MARGIN, profile.pick("64", "32", "32"));
    defaults.put(SKIP_LIMIT_PCT, profile.pick("10", "5", "5"));
    defaults.put(RSS_SLOPE_KIB_PER_HOUR, profile.pick("4096", "2048", "2048"));
    // the validate floor is start-up drift, not retention: see soak.sh's by_profile row (the twin)
    defaults.put(RSS_NOISE_FLOOR_KIB, profile.pick("65536", "8192", "8192"));
    defaults.put(HEAP_FLOOR_SLOPE_KIB_PER_HOUR, "1024");
    defaults.put(HEAP_FLOOR_NOISE_KIB, profile.pick("16384", "8192", "8192"));
    defaults.put(PING_DELAY_MS, profile.pick("4000", "15000", "15000"));
    defaults.put(CHECK_DELAY_MS, profile.pick("1000", "2000", "2000"));
    defaults.put(HTTP_CLIENT_EXECUTOR, "platform");
    defaults.put(SOCKET_THRESHOLD_MS, profile.pick("0", "20", "20"));
    defaults.put(WS_PORTS, "");
    defaults.put(HTTP_PORT, "0");
    defaults.put(CONTROL, "");
    defaults.put(MANAGER_REFLECT, "0");
    defaults.put(LIVE, "0");
    defaults.put(LIVE_HTTP_URL, "");
    defaults.put(LIVE_WS_URL, "");
    defaults.put(LIVE_CLUSTER, "");
    defaults.put(LIVE_CONFIRM, "");
    defaults.put(LIVE_RPS, "");
    defaults.put(LIVE_CONCURRENCY, "");
    defaults.put(LIVE_ACCOUNTS, "");
    defaults.put(PEER_HTTP_THREADS, "16");
    defaults.put(BLOCK_TXS, "400");
    defaults.put(PGA_ACCOUNTS, "500");
    defaults.put(BURST_FRAMES, "256");
    defaults.put(BURST_PERIOD_SECONDS, "120");
    defaults.put(LARGE_PERIOD_SECONDS, "300");
    defaults.put(LARGE_BYTES, "8388608");
    defaults.put(FRAGMENTS, "37");
    defaults.put(REFUSE_SECONDS, "20");
    defaults.put(SLOW_CONSUMER_MICROS, "2000");
    defaults.put(THROW_EVERY, "7");
    defaults.put(WS_PORT_COUNT, "5");
    return defaults;
  }

  private void validate() {
    if (warmupSeconds >= durationSeconds) {
      throw new IllegalArgumentException(WARMUP_SECONDS + " (" + warmupSeconds + ") must be less than "
          + DURATION_SECONDS + " (" + durationSeconds + "): the run's duration includes its warm-up");
    }
    if (live) {
      final var missing = new ArrayList<String>();
      for (final var key : LIVE_REQUIRED) {
        if (resolved.getOrDefault(key, "").isBlank()) {
          missing.add(key);
        }
      }
      if (!missing.isEmpty()) {
        throw new IllegalArgumentException("SOAK_LIVE=1 requires " + String.join(", ", missing)
            + ": a live run has no defaults, because a defaulted endpoint is a run against the wrong node");
      }
      if (liveCluster == Cluster.MAINNET && !"mainnet".equals(resolved.getOrDefault(LIVE_CONFIRM, "").trim())) {
        throw new IllegalArgumentException(
            "SOAK_LIVE_CLUSTER=mainnet requires " + LIVE_CONFIRM + "=mainnet");
      }
    }
  }

  // --- rendering ------------------------------------------------------------------------------

  /// Every resolved value as `KEY=VALUE` lines, in the canonical order, newline-terminated. This
  /// is what the run directory keeps, so a re-run needs no knowledge of which defaults applied.
  public String toRunEnv() {
    final var out = new StringBuilder(2048);
    for (final var entry : resolved.entrySet()) {
      // A live endpoint carries its credential in the URL; the record keeps scheme://host.
      final var key = entry.getKey();
      final var value = entry.getValue();
      final var recorded = (key.equals(LIVE_HTTP_URL) || key.equals(LIVE_WS_URL)) && !value.isBlank()
          ? Redaction.endpoint(value)
          : value;
      out.append(key).append('=').append(recorded).append('\n');
    }
    return out.toString();
  }

  /// The resolved map itself, for `run.json` and for the peer JVM's own parse.
  public Map<String, String> resolved() {
    return Map.copyOf(resolved);
  }

  public String raw(final String key) {
    return resolved.get(key);
  }

  // --- parsing helpers ------------------------------------------------------------------------

  private static String require(final Map<String, String> values, final String key) {
    final var value = values.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(key + " is required and has no default");
    }
    return value.trim();
  }

  private static int positiveInt(final Map<String, String> values, final String key) {
    final int value = parseInt(values, key);
    if (value <= 0) {
      throw new IllegalArgumentException(key + " must be positive, not " + value);
    }
    return value;
  }

  private static int nonNegativeInt(final Map<String, String> values, final String key) {
    final int value = parseInt(values, key);
    if (value < 0) {
      throw new IllegalArgumentException(key + " must not be negative, not " + value);
    }
    return value;
  }

  private static int parseInt(final Map<String, String> values, final String key) {
    try {
      return Integer.parseInt(require(values, key));
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException(key + " must be an integer, not '" + values.get(key) + '\'', e);
    }
  }

  private static long nonNegativeLong(final Map<String, String> values, final String key) {
    final long value;
    try {
      value = Long.parseLong(require(values, key));
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException(key + " must be a long, not '" + values.get(key) + '\'', e);
    }
    if (value < 0) {
      throw new IllegalArgumentException(key + " must not be negative, not " + value);
    }
    return value;
  }

  private static double fraction(final Map<String, String> values, final String key) {
    final double value;
    try {
      value = Double.parseDouble(require(values, key));
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException(key + " must be a fraction, not '" + values.get(key) + '\'', e);
    }
    if (value < 0d || value > 1d) {
      throw new IllegalArgumentException(key + " must be within [0, 1], not " + value);
    }
    return value;
  }

  private static boolean flag(final Map<String, String> values, final String key) {
    final var value = values.getOrDefault(key, "0").trim();
    return switch (value) {
      case "", "0", "false" -> false;
      case "1", "true" -> true;
      default -> throw new IllegalArgumentException(key + " must be 0 or 1, not '" + value + '\'');
    };
  }

  /// Hex (`0x…`) or decimal. Hex is parsed unsigned so the design's `0x5A7A50A40001` and a
  /// 64-bit seed with the top bit set both round-trip.
  public static long parseSeed(final String value) {
    final var trimmed = value.trim();
    try {
      return trimmed.startsWith("0x") || trimmed.startsWith("0X")
          ? Long.parseUnsignedLong(trimmed.substring(2), 16)
          : Long.parseLong(trimmed);
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException(SEED + " must be hex (0x…) or decimal, not '" + value + '\'', e);
    }
  }

  private static List<Integer> parsePorts(final String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    final var ports = new ArrayList<Integer>();
    for (final var part : value.split(",")) {
      final var trimmed = part.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      final int port;
      try {
        port = Integer.parseInt(trimmed);
      } catch (final NumberFormatException e) {
        throw new IllegalArgumentException(WS_PORTS + " must be a comma list of ports, not '" + value + '\'', e);
      }
      if (port < 1 || port > 65535) {
        throw new IllegalArgumentException(WS_PORTS + " holds an out-of-range port: " + port);
      }
      ports.add(port);
    }
    return List.copyOf(ports);
  }

  /// Convenience for the peer JVM, which reads the same file from a path rather than a run dir.
  public static SoakConfig loadUnchecked(final Path runDir) {
    try {
      return load(runDir);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // --- accessors ------------------------------------------------------------------------------

  public Profile profile() {
    return profile;
  }

  public Path runDir() {
    return runDir;
  }

  public int durationSeconds() {
    return durationSeconds;
  }

  public int warmupSeconds() {
    return warmupSeconds;
  }

  public int quiesceSeconds() {
    return quiesceSeconds;
  }

  public int drainSeconds() {
    return drainSeconds;
  }

  public long seed() {
    return seed;
  }

  public int wsEngines() {
    return wsEngines;
  }

  public int wsSubscriptions() {
    return wsSubscriptions;
  }

  public int wsChurnPerMinute() {
    return wsChurnPerMinute;
  }

  public int wsNotifyRps() {
    return wsNotifyRps;
  }

  public int httpConcurrency() {
    return httpConcurrency;
  }

  public int httpInflight() {
    return httpInflight;
  }

  public int httpRps() {
    return httpRps;
  }

  public double largeFraction() {
    return largeFraction;
  }

  public double noWrapFraction() {
    return noWrapFraction;
  }

  public double cancelFraction() {
    return cancelFraction;
  }

  /// Mean ops between scheduled faults, or `0` for a plan with no faults in it. A caller that
  /// clamps this up to 1 turns the no-faults control into the densest schedule the harness can
  /// build, which is the opposite of what the operator asked for.
  public int faultRate() {
    return faultRate;
  }

  /// Minimum planned faults per kind, or `0` for no per-kind minimum.
  public int faultMinPerKind() {
    return faultMinPerKind;
  }

  public int destructivePerHour() {
    return destructivePerHour;
  }

  public int stormSeconds() {
    return stormSeconds;
  }

  public int churnPeriodSeconds() {
    return churnPeriodSeconds;
  }

  public int heapMb() {
    return heapMb;
  }

  public String jfrMaxSize() {
    return jfrMaxSize;
  }

  public String jfrMaxAge() {
    return jfrMaxAge;
  }

  public int nmtIntervalSeconds() {
    return nmtIntervalSeconds;
  }

  public int sampleSeconds() {
    return sampleSeconds;
  }

  public int gaugeSeconds() {
    return gaugeSeconds;
  }

  public int threadMargin() {
    return threadMargin;
  }

  public int fdMargin() {
    return fdMargin;
  }

  public int vthreadMargin() {
    return vthreadMargin;
  }

  public int skipLimitPct() {
    return skipLimitPct;
  }

  public long rssSlopeKibPerHour() {
    return rssSlopeKibPerHour;
  }

  public long rssNoiseFloorKib() {
    return rssNoiseFloorKib;
  }

  public long heapFloorSlopeKibPerHour() {
    return heapFloorSlopeKibPerHour;
  }

  public long pingDelayMillis() {
    return pingDelayMillis;
  }

  public long checkDelayMillis() {
    return checkDelayMillis;
  }

  /// sava's own default: the re-send delay is `max(reConnectDelay, subscriptionAndPingCheckDelay)`
  /// and every replay and escalation bound in the ledger is derived from it, never guessed.
  public long subscriptionResendMillis() {
    return Math.max(3_000L, checkDelayMillis);
  }

  public long connectTimeoutMillis() {
    return 8_000L;
  }

  public long reconnectDelayMillis() {
    return 3_000L;
  }

  public ClientExecutor httpClientExecutor() {
    return httpClientExecutor;
  }

  public int socketThresholdMillis() {
    return socketThresholdMillis;
  }

  public List<Integer> wsPorts() {
    return wsPorts;
  }

  public int httpPort() {
    return httpPort;
  }

  public String control() {
    return control;
  }

  public boolean managerReflect() {
    return managerReflect;
  }

  public boolean live() {
    return live;
  }

  public String liveHttpUrl() {
    return liveHttpUrl;
  }

  public String liveWsUrl() {
    return liveWsUrl;
  }

  public Cluster liveCluster() {
    return liveCluster;
  }

  public int liveRps() {
    return liveRps;
  }

  public int liveConcurrency() {
    return liveConcurrency;
  }

  public Path liveAccounts() {
    return liveAccounts;
  }

  public int peerHttpThreads() {
    return peerHttpThreads;
  }

  public int blockTxs() {
    return blockTxs;
  }

  public int pgaAccounts() {
    return pgaAccounts;
  }

  public int burstFrames() {
    return burstFrames;
  }

  public int burstPeriodSeconds() {
    return burstPeriodSeconds;
  }

  public int largePeriodSeconds() {
    return largePeriodSeconds;
  }

  public int largeBytes() {
    return largeBytes;
  }

  public int fragments() {
    return fragments;
  }

  public int refuseSeconds() {
    return refuseSeconds;
  }

  public int slowConsumerMicros() {
    return slowConsumerMicros;
  }

  public int throwEvery() {
    return throwEvery;
  }

  public int wsPortCount() {
    return wsPortCount;
  }

  @Override
  public String toString() {
    return "SoakConfig[" + profile.lowerName() + ", " + durationSeconds + "s, seed=0x"
        + Long.toHexString(seed) + ", live=" + live + ']';
  }
}
