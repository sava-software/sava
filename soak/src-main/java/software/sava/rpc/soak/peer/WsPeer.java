package software.sava.rpc.soak.peer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/// One websocket port: its listener, its connections, its notification cadence and its share of the
/// fault schedule (`DESIGN.md` §7).
///
/// Threads, and nothing else:
/// - `peer-ws-accept-<port>` owns the `ServerSocket`;
/// - one `peer-ws-conn-<n>` reader and one `peer-ws-out-<n>` writer per connection, inside
///   [WsConnection];
/// - one `peer-ws-emit-<port>` scheduler drives the notification cadence for every connection on
///   this port.
///
/// The dialect is fixed for the whole run by the port's position in `SOAK_WS_PORTS`, so a reconnect
/// lands on the same dialect and a client-side anomaly after a reconnect can never be explained
/// away by the peer having changed its mind.
final class WsPeer {

  /// `HANDSHAKE_STALL` accepts the socket and sends nothing for this long — comfortably past the
  /// engine's 8 s default `connectTimeout`, so what ends the attempt is the client's own timeout
  /// and not the peer losing patience.
  static final int HANDSHAKE_STALL_SECONDS = 30;

  /// `STALL_BODY` holds the socket this long after half a body — past any exchange deadline the
  /// client can reasonably carry, so the deadline is what ends it.
  static final int STALL_HOLD_SECONDS = 300;

  private static final long EMIT_TICK_MILLIS = 50L;

  private final PeerMain.Config config;
  private final int portIndex;
  private final Dialect dialect;
  private final PeerLog log;
  private final Payloads payloads;
  private final AtomicLong subIds;
  private final ServerSocket serverSocket;
  private final ConcurrentHashMap<Long, WsConnection> connections;
  private final AtomicLong connCounter;
  private final AtomicLong subCounter;
  private final AtomicLong notifyCounter;
  private final AtomicBoolean running;
  private final AtomicBoolean quiesced;
  private final ScheduledExecutorService scheduler;
  private final int port;

  private volatile FaultSchedule schedule;
  private volatile ServerSocket listener;
  private long tick;
  /// The fault the next accepted connection will carry, taken from the schedule but **not yet
  /// spent**. `FaultSchedule.take` removes the entry, so taking it and then losing the `accept()`
  /// to an `IOException` — the rebind window `CONNECT_REFUSE` opens is exactly such a window —
  /// would consume a planned fault permanently while the report still counts its trigger as
  /// reached, i.e. `fault_kinds_unapplied` and a failed run with no client misbehaviour behind it.
  /// Held here instead until there is a connection to apply it to. Touched only by
  /// `peer-ws-accept-<port>`.
  ///
  /// One still held at shutdown is owed no outcome row: its `conn` ordinal never arrived, so the
  /// final counter is below its trigger and the report reads it as `fault_unreached` — which is
  /// what it is — rather than as a fault that fired with nothing to hit.
  private Fault pendingConnFault;
  /// Every port's peer, this one included, for the injections that need a connection other than
  /// their own ([#crosstalkSource]). Set once by `PeerMain` after all ports are bound.
  private volatile List<WsPeer> siblings = List.of();

  void siblings(final List<WsPeer> all) {
    this.siblings = List.copyOf(all);
  }

  WsPeer(final PeerMain.Config config,
         final int portIndex,
         final PeerLog log,
         final Payloads payloads,
         final AtomicLong subIds) throws IOException {
    this.config = config;
    this.portIndex = portIndex;
    this.dialect = Dialect.forPortIndex(portIndex);
    this.log = log;
    this.payloads = payloads;
    this.subIds = subIds;
    this.serverSocket = bind(0);
    this.listener = serverSocket;
    this.port = serverSocket.getLocalPort();
    this.connections = new ConcurrentHashMap<>();
    this.connCounter = new AtomicLong();
    this.subCounter = new AtomicLong();
    this.notifyCounter = new AtomicLong();
    this.running = new AtomicBoolean(true);
    this.quiesced = new AtomicBoolean();
    this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
      final var thread = new Thread(runnable, "peer-ws-emit-" + port);
      thread.setDaemon(true);
      return thread;
    });
  }

  private ServerSocket bind(final int requestedPort) throws IOException {
    final var socket = new ServerSocket();
    // Reuse matters for CONNECT_REFUSE: the listener closes and must come back on the SAME port,
    // and without it the rebind loses the race with TIME_WAIT.
    socket.setReuseAddress(true);
    socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 64);
    return socket;
  }

  int port() {
    return port;
  }

  int portIndex() {
    return portIndex;
  }

  Dialect dialect() {
    return dialect;
  }

  PeerLog log() {
    return log;
  }

  Payloads payloads() {
    return payloads;
  }

  PeerMain.Config config() {
    return config;
  }

  FaultSchedule schedule() {
    return schedule;
  }

  ScheduledExecutorService scheduler() {
    return scheduler;
  }

  long nextSubId() {
    return subIds.incrementAndGet();
  }

  /// The `sub` counter of DESIGN.md §6: the nth subscribe request on this port.
  long nextSubOrdinal() {
    return subCounter.incrementAndGet();
  }

  /// The `notify` counter of DESIGN.md §6: the nth notification written on this port.
  long nextNotifyOrdinal() {
    return notifyCounter.incrementAndGet();
  }

  long connections() {
    return connections.size();
  }

  long acceptedConnections() {
    return connCounter.get();
  }

  long subscribeRequests() {
    return subCounter.get();
  }

  long notificationsWritten() {
    return notifyCounter.get();
  }

  int liveSubscriptions() {
    int live = 0;
    for (final var connection : connections.values()) {
      live += connection.liveSubscriptions();
    }
    return live;
  }

  List<String> capture(final long connId) {
    final var connection = connections.get(connId);
    return connection == null ? List.of() : connection.capture();
  }

  void quiesce() {
    quiesced.set(true);
  }

  void start(final FaultSchedule faultSchedule) {
    this.schedule = faultSchedule;
    final var accept = new Thread(this::acceptLoop, "peer-ws-accept-" + port);
    accept.setDaemon(true);
    accept.start();
    scheduler.scheduleAtFixedRate(this::emitTick, EMIT_TICK_MILLIS, EMIT_TICK_MILLIS, TimeUnit.MILLISECONDS);
  }

  private void acceptLoop() {
    while (running.get()) {
      try {
        if (pendingConnFault == null) {
          pendingConnFault = connectionFault(connCounter.get() + 1);
        }
        final var pending = pendingConnFault;
        // CONNECT_REFUSE is looked at BEFORE accepting, because the property under test is the
        // client's behaviour against a peer that is not listening at all. Accepting and then
        // closing would be TCP_RESET wearing a different name. It is also the only kind that is
        // spent without a connection.
        if (pending != null && pending.kind() == FaultKind.CONNECT_REFUSE) {
          this.pendingConnFault = null;
          refuse(pending);
          continue;
        }
        final Socket socket = listener.accept();
        // Spent only now. Every other kind needs a connection to be applied to, and the accept
        // above can throw — on the rebind race, or on a listener closing under shutdown.
        this.pendingConnFault = null;
        final long connId = connCounter.incrementAndGet();
        final var connection = new WsConnection(this, port, connId, socket, dialect);
        connections.put(connId, connection);
        connection.start(pending);
      } catch (final IOException ex) {
        if (running.get() && !listener.isClosed()) {
          log.ws(port, "error", -1, -1, -1, -1, -1, 0, "accept: " + ex.getMessage());
        }
        if (listener.isClosed() && running.get()) {
          return;
        }
      }
    }
  }

  /// The fault the connection with this `conn` ordinal carries: the scheduled one, or the
  /// CONN-scope kind `SOAK_CONTROL` names.
  ///
  /// The control seam is consulted **here** because nothing else reads it at CONN scope:
  /// [WsConnection] asks for SUB and NOTIFY scope only, so a control naming `CROSSTALK`, a
  /// transport kind or a handshake kind was inert for the whole run — the control ran, the report
  /// scored it, and the peer had done nothing.
  private Fault connectionFault(final long connOrdinal) {
    final var scheduled = schedule.take("conn", port, connOrdinal);
    final var control = schedule.always(FaultKind.Scope.CONN, connOrdinal);
    if (control == null || (scheduled != null && scheduled.kind() == control)) {
      return scheduled;
    }
    if (scheduled != null) {
      // A control pre-empted the scheduled fault at this ordinal. Recording it `no_target` keeps
      // the fidelity denominator honest: the fault was reached, it just had nothing left to do.
      applied(scheduled, -1, -1, "pre-empted by control " + control, "no_target");
    }
    // Ordinal 0, counter `control`: a control injection is never in the plan (`DESIGN.md` §6), so
    // it must not be joined to a scheduled row nor counted in the schedule's fidelity. This is the
    // same row shape [#appliedControl] writes.
    return new Fault(0, control, port, "control", 0, "-");
  }

  /// The sequence a `CROSSTALK` injection on `self` may take: the lowest-numbered *other* live
  /// connection on any of this peer process's ports that holds a subscription on `channel` whose
  /// sequence has started. Any port, because each port serves one engine and a same-port sibling
  /// exists only in a reconnect's overlap: a control restricted to this port found no target on
  /// every one of its attempts (measured 2026-09-22).
  ///
  /// Lowest-numbered by (port, connId), not first-found, because the connection maps are
  /// concurrent and their iteration order is not reproducible — and a control that picks a
  /// different source on a rerun at the same seed is a control whose evidence cannot be replayed.
  WsConnection.CrosstalkSource crosstalkSource(final WsConnection self, final Payloads.Channel channel) {
    WsConnection.CrosstalkSource best = null;
    final var peers = siblings.isEmpty() ? List.of(this) : siblings;
    for (final var peer : peers) {
      for (final var other : peer.connections.values()) {
        if (other == self || other.closed()) {
          continue;
        }
        final var candidate = other.crosstalkSource(channel);
        if (candidate != null && (best == null || candidate.port() < best.port()
            || (candidate.port() == best.port() && candidate.connId() < best.connId()))) {
          best = candidate;
        }
      }
    }
    return best;
  }

  /// Close the listener, wait, reopen on the **same** port. `SO_REUSEADDR` is what makes the rebind
  /// succeed; without it the client would meet a different failure (a port that moved) than the one
  /// the fault names.
  private void refuse(final Fault fault) {
    final long seconds = config.refuseSeconds();
    applied(fault, -1, -1, "listener closed for " + seconds + "s on port " + port);
    log.ws(port, "abort", -1, -1, -1, -1, -1, 0, "CONNECT_REFUSE for " + seconds + "s");
    try {
      listener.close();
    } catch (final IOException ignored) {
      // Already gone; the reopen below is what matters.
    }
    WsFrames.pause(TimeUnit.SECONDS.toMillis(seconds));
    try {
      this.listener = bind(port);
      // Listener-level, not connection-level: `connId` is -1 because nothing was accepted, so this
      // row opens no connection in the client's view and owes no terminal row. Every kind that
      // *does* open one — every `HANDSHAKE_*` — is closed by [WsConnection#terminate()].
      log.ws(port, "accept", -1, -1, -1, -1, -1, 0, "listener reopened on port " + port);
    } catch (final IOException ex) {
      log.ws(port, "error", -1, -1, -1, -1, -1, 0, "failed to reopen port " + port + ": " + ex.getMessage());
      running.set(false);
    }
  }

  /// One cadence tick. The rate is per connection and enforced here rather than by the client, so a
  /// slow consumer's back-pressure shows up as a full outbound queue — a peer-side observable —
  /// instead of silently reshaping the load.
  private void emitTick() {
    if (quiesced.get() || connections.isEmpty()) {
      return;
    }
    ++tick;
    final int perTick = Math.max(1, (int) (config.wsNotifyRps() * EMIT_TICK_MILLIS / 1000L));
    final long ticksPerSecond = 1000L / EMIT_TICK_MILLIS;
    final boolean burst = config.burstPeriodSeconds() > 0
        && tick % (config.burstPeriodSeconds() * ticksPerSecond) == 0;
    final boolean large = config.largePeriodSeconds() > 0
        && tick % (config.largePeriodSeconds() * ticksPerSecond) == 0;
    for (final var connection : connections.values()) {
      if (connection.closed()) {
        connections.remove(connection.connId(), connection);
        continue;
      }
      for (int i = 0; i < perTick; ++i) {
        connection.emit(false);
      }
      if (burst) {
        for (int i = 0; i < config.burstFrames(); ++i) {
          connection.emit(false);
        }
      }
      if (large) {
        connection.emit(true);
      }
    }
  }

  void onConnectionClosed(final WsConnection connection) {
    connections.remove(connection.connId(), connection);
  }

  void applied(final Fault fault, final long connId, final long attemptOrdinal, final String detail) {
    applied(fault, connId, attemptOrdinal, detail, "applied");
  }

  void applied(final Fault fault,
               final long connId,
               final long attemptOrdinal,
               final String detail,
               final String outcome) {
    log.faultApplied(fault, connId, attemptOrdinal, outcome, detail);
  }

  /// A control-only kind has no scheduled row, so it is recorded at ordinal 0 with its own kind.
  /// The report can then count control injections without them polluting the schedule's fidelity.
  void appliedControl(final FaultKind kind,
                      final int atPort,
                      final long connId,
                      final long attemptOrdinal,
                      final String outcome,
                      final String detail) {
    log.faultApplied(new Fault(0, kind, atPort, "control", 0, "-"), connId, attemptOrdinal, outcome, detail);
  }

  void shutdown() {
    running.set(false);
    scheduler.shutdownNow();
    try {
      listener.close();
    } catch (final IOException ignored) {
      // Shutting down; a listener that will not close cannot make this worse.
    }
    for (final var connection : new ArrayList<>(connections.values())) {
      connection.closeWith(WsFrames.CLOSE_NORMAL, "shutdown");
    }
  }
}
