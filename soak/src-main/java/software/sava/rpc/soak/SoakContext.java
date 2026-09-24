package software.sava.rpc.soak;

import software.sava.core.accounts.PublicKey;
import software.sava.rpc.soak.issue52.ClaimRecord;
import software.sava.rpc.soak.issue52.RetirementRecord;
import software.sava.rpc.soak.oracle.PropertyLedger;
import software.sava.rpc.soak.oracle.RecoveryLedger;
import software.sava.rpc.soak.oracle.SequenceOracle;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/// Everything a driver needs and nothing it should own: the resolved configuration, the counters,
/// the correctness ledger and its two oracles, the two issue #52 writers, the shared HTTP client,
/// the peer's admin client, the run directory, a shared timer and the current phase.
///
/// The ownership split is the point. The context creates the shared `HttpClient` and its executor
/// so W5-B1 can assert that they survive every driver being closed — a driver that built its own
/// would be asserting nothing. It creates the timer so that no driver spawns its own scheduler
/// and quietly changes the thread-count baseline the retention gate reads. And it registers gauge
/// sources through a queue, so a driver may publish its counts during `start()` even though the
/// sampler itself starts later, once the JFR counters exist.
public final class SoakContext implements AutoCloseable {

  /// The `issue52/retirements.tsv` header: the record that writes the rows also names the
  /// columns, so the writer created here and the rows the capture stage appends cannot disagree
  /// on order. The report reads columns by name, never by position.
  public static final String RETIREMENTS_HEADER = RetirementRecord.tsvHeader();

  /// The `issue52/claims.tsv` header, from the same source as its rows.
  public static final String CLAIMS_HEADER = ClaimRecord.tsvHeader();

  /// The shared client is HTTP/1.1 with a five-second connect timeout: the peer speaks 1.1, and a
  /// connect that has not completed in five seconds against loopback is a finding, not patience.
  public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  public static final int HTTP_CLIENT_THREADS = 8;
  public static final int TIMER_THREADS = 2;

  private final SoakConfig config;
  private final Counters counters;
  private final PropertyLedger properties;
  private final SequenceOracle sequences;
  private final RecoveryLedger recovery;
  private final Tsv retirements;
  private final Tsv claims;
  private final HttpClient httpClient;
  private final ExecutorService httpClientExecutor;
  private final PeerAdmin peerAdmin;
  private final ScheduledExecutorService timer;
  private final AtomicReference<Phase> phase;
  private final PublicKey[] keyTable;
  private final PublicKey[] liveAccounts;
  private final CopyOnWriteArrayList<GaugeSampler.GaugeSource> pendingGauges;
  private final AtomicReference<GaugeSampler> gauges;

  private SoakContext(final SoakConfig config,
                      final Counters counters,
                      final PropertyLedger properties,
                      final SequenceOracle sequences,
                      final RecoveryLedger recovery,
                      final Tsv retirements,
                      final Tsv claims,
                      final HttpClient httpClient,
                      final ExecutorService httpClientExecutor,
                      final PeerAdmin peerAdmin,
                      final ScheduledExecutorService timer) {
    this.config = config;
    this.counters = counters;
    this.properties = properties;
    this.sequences = sequences;
    this.recovery = recovery;
    this.retirements = retirements;
    this.claims = claims;
    this.httpClient = httpClient;
    this.httpClientExecutor = httpClientExecutor;
    this.peerAdmin = peerAdmin;
    this.timer = timer;
    this.phase = new AtomicReference<>(Phase.STARTUP);
    // A live run's population is the supplied accounts, whole and in file order: the HTTP driver
    // samples all of them, and the byte-indexed table the websocket and churn drivers share is
    // that population cycled (or its first 256 entries). The seeded table is keys nobody funded,
    // and subscribing to them on a real node measured nothing (review).
    this.liveAccounts = config.live() ? Seeds.liveAccounts(config.liveAccounts()) : null;
    this.keyTable = config.live() ? Seeds.cycled(liveAccounts) : Seeds.keyTable(config.seed());
    this.pendingGauges = new CopyOnWriteArrayList<>();
    this.gauges = new AtomicReference<>();
  }

  /// Builds the whole context for one run.
  public static SoakContext open(final SoakConfig config, final Counters counters) throws IOException {
    final var runDir = config.runDir();
    final var properties = new PropertyLedger(runDir);
    final var sequences = new SequenceOracle(oracleEngines(config), Seeds.KEY_TABLE_SIZE);
    final var recovery = new RecoveryLedger(RecoveryLedger.budgetMillis(
        config.connectTimeoutMillis(), config.reconnectDelayMillis(), config.subscriptionResendMillis()),
        properties);
    final var issue52 = runDir.resolve("issue52");
    final var retirements = Tsv.open(issue52.resolve("retirements.tsv"), RETIREMENTS_HEADER, counters);
    final var claims = Tsv.open(issue52.resolve("claims.tsv"), CLAIMS_HEADER, counters);
    final var executor = httpClientExecutor(config);
    final var httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(CONNECT_TIMEOUT)
        .executor(executor)
        .build();
    final var peerAdmin = config.live() || config.httpPort() <= 0
        ? PeerAdmin.none()
        : PeerAdmin.of(httpClient, "127.0.0.1", config.httpPort());
    final var timer = Executors.newScheduledThreadPool(TIMER_THREADS, named("soak-timer"));
    return new SoakContext(config, counters, properties, sequences, recovery, retirements, claims,
        httpClient, executor, peerAdmin, timer);
  }

  /// The sequence oracle is sized for the configured engines plus the churn and overflow
  /// engines, with head-room: an out-of-range engine index must be a loud
  /// `IllegalArgumentException` in a driver, not a silent resize of a hot-path array.
  private static int oracleEngines(final SoakConfig config) {
    return Math.max(8, config.wsEngines() * 2);
  }

  private static ExecutorService httpClientExecutor(final SoakConfig config) {
    return config.httpClientExecutor() == SoakConfig.ClientExecutor.VIRTUAL
        ? Executors.newVirtualThreadPerTaskExecutor()
        : Executors.newFixedThreadPool(HTTP_CLIENT_THREADS, named("soak-hc"));
  }

  /// Named, non-daemon-by-default threads with a stable prefix: every thread this harness creates
  /// is identifiable in a thread dump and in `jdk.ThreadStart`, because "which of these 300
  /// threads is ours" is the first question the resource section asks.
  static java.util.concurrent.ThreadFactory named(final String prefix) {
    final var counter = new AtomicInteger();
    return runnable -> {
      final var thread = new Thread(runnable, prefix + '-' + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  public SoakConfig config() {
    return config;
  }

  public Counters counters() {
    return counters;
  }

  public PropertyLedger properties() {
    return properties;
  }

  public SequenceOracle sequences() {
    return sequences;
  }

  public RecoveryLedger recovery() {
    return recovery;
  }

  public Tsv retirements() {
    return retirements;
  }

  public Tsv claims() {
    return claims;
  }

  /// The shared client every driver uses. Drivers never close it: W5-B1 asserts it still works
  /// after every driver is gone.
  public HttpClient httpClient() {
    return httpClient;
  }

  public PeerAdmin peerAdmin() {
    return peerAdmin;
  }

  public Path runDir() {
    return config.runDir();
  }

  /// The shared two-thread scheduler. Use it for pacing and deadlines instead of sleeping: a
  /// sleeping driver is a driver that cannot be told the run is over.
  public ScheduledExecutorService timer() {
    return timer;
  }

  public Supplier<Phase> phase() {
    return phase::get;
  }

  public Phase currentPhase() {
    return phase.get();
  }

  public long seed() {
    return config.seed();
  }

  public boolean live() {
    return config.live();
  }

  /// The run's 256 deterministic keys, shared by every driver and by the peer; on a live run,
  /// [#liveAccounts] cycled to that size (or its first 256 entries).
  public PublicKey[] keyTable() {
    return keyTable;
  }

  /// The whole live population, in file order without duplicates; null unless the run is live.
  /// The HTTP driver samples this rather than [#keyTable], so a list longer than the table loses
  /// nothing there.
  public PublicKey[] liveAccounts() {
    return liveAccounts;
  }

  /// Publishes a driver's live counts to the gauge. Safe before the sampler exists: sources
  /// registered early are queued and attached when it starts.
  public void registerGauge(final GaugeSampler.GaugeSource source) {
    final var sampler = gauges.get();
    if (sampler == null) {
      pendingGauges.add(source);
    } else {
      sampler.register(source);
    }
  }

  void attachGauges(final GaugeSampler sampler) {
    gauges.set(sampler);
    for (final var source : List.copyOf(pendingGauges)) {
      sampler.register(source);
    }
    pendingGauges.clear();
  }

  void setPhase(final Phase current) {
    phase.set(current);
  }

  /// How long the shared client's in-flight exchanges get to finish at shutdown. Bounded on
  /// purpose: `HttpClient.close()` blocks until every exchange terminates, and the peer's
  /// `STALL_BODY` fault holds a body open for five minutes, which the HTTP driver deliberately
  /// leaves un-cancelled as the evidence that the client's own deadline settled it (or did not).
  /// A run that parked here past the runner's SIGTERM window would lose its final artifacts to a
  /// SIGKILL - the one outcome worse than an abandoned exchange.
  public static final Duration HTTP_CLIENT_CLOSE_BOUND = Duration.ofSeconds(5);

  /// Closes what the context owns, in the order that keeps the artifacts complete: the timer
  /// first (no new work), then the client and its executor within a bound, then the writers.
  @Override
  public void close() {
    timer.shutdownNow();
    try {
      timer.awaitTermination(5L, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    httpClient.shutdownNow();
    try {
      if (!httpClient.awaitTermination(HTTP_CLIENT_CLOSE_BOUND)) {
        System.err.println("soak: the shared HttpClient still had exchanges in flight after "
            + HTTP_CLIENT_CLOSE_BOUND + "; abandoning them");
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    httpClientExecutor.shutdownNow();
    retirements.close();
    claims.close();
  }
}
