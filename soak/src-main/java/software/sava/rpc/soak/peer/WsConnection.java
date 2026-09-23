package software.sava.rpc.soak.peer;

import systems.comodal.jsoniter.JsonIterator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/// One accepted websocket connection: the handshake, the JSON-RPC subscription semantics of
/// `DESIGN.md` §7, and every fault that is scoped to a connection, a subscription or a
/// notification.
///
/// Two threads own the socket and nothing else touches it:
/// - `peer-ws-conn-<n>` reads, parses and answers;
/// - `peer-ws-out-<n>` drains a **bounded** outbound queue and is the only writer.
///
/// The queue holds *intents*, not bytes, because `DESIGN.md` §7 requires the per-`(connId, subId)`
/// sequence to be assigned **under the write lock immediately before the bytes go out**. That is
/// what makes the client's gapless oracle exact: a peer-side drop, a full queue or a socket that
/// went away never consumes a sequence number, so a gap the client observes cannot have been the
/// peer's own load generator. Assigning at enqueue time would reintroduce exactly the "was that the
/// client or me?" ambiguity the oracle exists to remove.
///
/// Overflow is not silent. A full queue logs `overflow` and closes the connection with 1011 — a
/// peer that quietly dropped notifications would manufacture the gaps it is meant to detect. That
/// `overflow` row is interface rather than diagnostics: it is the row the client's retirement
/// attribution joins the 1011 close to, so a retirement that follows one is the peer's doing and
/// not an unexplained client defect.
///
/// Why the queue is as large as it is, measured in the 2026-09-21 controls campaign (rows `D1`,
/// `D7`, `crosstalk` and `I3` all show it, each on the BARE port): the first connection on that
/// port drew `SLOW_WRITE`, whose hold is `SLOW_WRITE_GAP_MILLIS` between **every** fragment of
/// `SLOW_WRITE_MESSAGES` messages — and at the `SOAK_FRAGMENTS` those runs set, that is seconds
/// per message rather than milliseconds. The writer therefore stood still for most of ten seconds
/// while `peer-ws-emit-<port>` went on enqueuing at `SOAK_WS_NOTIFY_RPS`; the queue filled in about
/// five of them, and a fault the catalogue classifies as touching nothing ended the connection with
/// a 1011 that the client then charged to itself as an unexplained retirement, taking W1-F's
/// containment check down with it. The sizing below is what closes that; `SLOW_WRITE` keeps its
/// classification, because the fault genuinely does not touch the transport.
final class WsConnection {

  /// Sized so back-pressure the peer inflicts on itself cannot end a connection. The floor is the
  /// longest hold the peer can impose — `SLOW_WRITE` pausing `SLOW_WRITE_GAP_MILLIS` between every
  /// fragment of `SLOW_WRITE_MESSAGES` messages, which at the campaign's `SOAK_FRAGMENTS` runs into
  /// seconds apiece — taken against the highest `SOAK_WS_NOTIFY_RPS` any profile drives, with a
  /// `SOAK_BURST_FRAMES` burst on top and better than twice that hold in margin above it.
  ///
  /// The memory it bounds is per connection and small, because the queue holds *intents*: a NOTIFY
  /// entry carries a payload size and no bytes, so the largest notification a run can send
  /// (`SOAK_LARGE_BYTES`) is never in here — it is built under the write lock. Only TEXT (a
  /// confirmation or an error envelope, a few hundred bytes) and PONG (an RFC 6455 control payload,
  /// at most 125) hold text at all, and both are bounded by subscriptions and pings rather than by
  /// the notification rate. A full queue therefore costs well under a mebibyte per connection.
  private static final int OUTBOUND_CAPACITY = 8192;
  private static final int CAPTURE_FRAMES = 64;
  private static final int CAPTURE_BYTES = 4096;
  private static final int SMALL_PAYLOAD_BYTES = 64;
  private static final long SLOW_WRITE_GAP_MILLIS = 50L;
  private static final int SLOW_WRITE_MESSAGES = 4;

  /// A transport fault armed at accept fires once the connection has written this many frames, so
  /// the client has actually used it. Firing at accept would test the connect path over and over
  /// and never the established one — and the frame count is a peer counter, so it stays
  /// reproducible.
  private static final int DESTRUCTIVE_AFTER_FRAMES = 16;

  /// How many further notifications one retired subscription may draw after the peer has
  /// acknowledged its unsubscribe.
  ///
  /// Unbounded, an always-on `ZOMBIE_NOTIFICATION` control makes that one retired id the whole
  /// stream: in the 2026-09-21 controls campaign D7's zombies were 97% of the notifications the run
  /// sent, thousands of them under a single retired id, which starved the live subscriptions of
  /// their own slots and turned `faults-applied.tsv` into one repeated sentence.
  ///
  /// The bound is small because one notification is already the entire defect — a client with no
  /// cancellation tombstone fails W1-D on the first. It is larger than one so the log shows the
  /// peer repeating rather than a reader having to take a single row on trust, and so a tombstone
  /// installed a beat late still meets a second copy. It sits above what a scheduled cadence
  /// reaches for one id (F7's ZOMBIE-only schedule topped out at five in that campaign), so it
  /// binds the always-on control and leaves the scheduled fault alone.
  private static final int ZOMBIE_AMPLIFICATION_BOUND = 8;

  /// How many ping windows phase one of `SWALLOW_PING` may spend waiting for the engine to idle.
  /// The window re-bases behind every answer the peer writes, so this is what bounds the hold of a
  /// connection that answers a request every few seconds and never pings.
  private static final int SWALLOW_PING_PROVOKE_WINDOWS = 4;

  private enum OutKind {TEXT, NOTIFY, CLOSE, PONG}

  /// `logKind` is null for the one message the peer must put on the wire and leave out of
  /// `peer-ws.tsv` — see the `DROP_REPLAYED_SUBSCRIBES` branch of [#onSubscribe].
  private record Out(OutKind kind,
                     String text,
                     SubState sub,
                     long subId,
                     long msgId,
                     int payloadBytes,
                     String logKind,
                     // the row's detail column: error answers carry `code=<n>` so the client's
                     // request-defect join reads the code the engine saw instead of inferring it
                     // from a fault row inside a time window
                     String detail) {
  }

  /// A notification the `SWAP_ADJACENT_NOTIFY` control is holding back, carrying the coordinates of
  /// the message itself. The `notify` row that finally puts it on the wire must name *its*
  /// subscription and sequence, not whichever notification happened to release it — a row whose
  /// `(connId, subId, seq)` belongs to different bytes is unjoinable evidence.
  private record Held(long subId, long msgId, long seq, String message) {
  }

  /// Where a `CROSSTALK` injection took its sequence from: another live connection, the
  /// subscription of its own that is on the same channel, and the value that connection's counter
  /// has reached.
  record CrosstalkSource(int port, long connId, long subId, long seq) {
  }

  /// One granted subscription. `seq` is written only by this connection's writer thread, under the
  /// write lock.
  static final class SubState {

    private final long subId;
    private final String method;
    private final String dedupeKey;
    private final Payloads.Channel channel;
    private final String key;
    private final long msgId;
    /// Volatile because the `CROSSTALK` control reads *another* connection's counter from a
    /// different writer thread, and a plain long read across threads is a data race that may also
    /// tear. Still one writer (this connection's writer, under its write lock), so `++seq` stays
    /// safe.
    private volatile long seq;
    private boolean signatureTerminated;

    private SubState(final long subId,
                     final String method,
                     final String dedupeKey,
                     final Payloads.Channel channel,
                     final String key,
                     final long msgId) {
      this.subId = subId;
      this.method = method;
      this.dedupeKey = dedupeKey;
      this.channel = channel;
      this.key = key;
      this.msgId = msgId;
    }

    long subId() {
      return subId;
    }

    String key() {
      return key;
    }
  }

  private final WsPeer peer;
  private final int port;
  private final long connId;
  private final Socket socket;
  private final Dialect dialect;
  private final ReentrantLock writeLock;
  private final ArrayBlockingQueue<Out> outbound;
  private final ConcurrentHashMap<Long, SubState> bySubId;
  private final ConcurrentHashMap<String, Long> byDedupeKey;
  private final CopyOnWriteArrayList<SubState> liveSubs;
  private final Deque<Long> recentlyUnsubscribed;
  private final Deque<String> inboundCapture;
  private final Deque<String> outboundCapture;
  private final AtomicBoolean closed;
  private final AtomicBoolean terminated;
  /// Connection-scoped faults that are armed and have not yet had an effect. Each is drained by
  /// exactly one `getAndSet(null)` — by the code that applies it, or by [#terminate()] writing it
  /// `no_target` — so the accounting is two-sided. It has to be: an armed fault that leaves no
  /// outcome row at all is a kind that was *reached and never applied*, which `DESIGN.md` §6 turns
  /// into `fault_kinds_unapplied` and a failed run with no client misbehaviour behind it. Recording
  /// one `applied` at arm time is the opposite error: a ping that never arrives and a message that
  /// is never written are faults that did not happen.
  private final AtomicReference<Fault> armedTransportFault;
  private final AtomicReference<Fault> armedPingFault;
  private final AtomicReference<Fault> armedSlowWriteFault;
  /// The `CROSSTALK` control's accounting handle. Its scope is CONN, so [WsPeer]'s accept loop
  /// takes it from the control seam and hands it down here, while the injection itself re-consults
  /// the same seam per notification. It is drained by the first notification that finds no other
  /// connection to take a sequence from, cleared by the first injection, and drained as `no_target`
  /// by [#terminate()] if neither happened — one row each, because a `faults-applied.tsv` row per
  /// notification would, at `SOAK_WS_NOTIFY_RPS`, spend the bounded peer-log queue on one repeated
  /// sentence and drop the `notify` rows the client actually joins on. Every injection is counted
  /// in [#crosstalkApplied], whose total [#terminate()] writes, and carries its own `notify` row.
  private final AtomicReference<Fault> armedCrosstalkFault;
  private final AtomicLong crosstalkApplied;
  /// Notifications the `SWAP_ADJACENT_NOTIFY` control is holding back, keyed by the subscription
  /// whose own next notification releases them.
  private final ConcurrentHashMap<Long, Held> heldNotifications;
  /// How many zombie notifications each recently retired subscription has already drawn, so
  /// [#ZOMBIE_AMPLIFICATION_BOUND] can be enforced per retired id rather than per connection. The
  /// entry dies with the id when it falls out of [#recentlyUnsubscribed], which is what keeps this
  /// map bounded over a campaign-length run.
  private final ConcurrentHashMap<Long, AtomicLong> zombieEmitted;

  private InputStream in;
  private OutputStream out;
  private volatile long attemptOrdinal = -1;
  private volatile int emitCursor;
  private volatile boolean swallowPing;
  /// `SWALLOW_PING`'s silence: while it holds, the write loop sets frames aside instead of writing
  /// them. The engine only pings into peer silence and only escalates a swallowed ping when
  /// nothing else arrives inside its response window (`SolanaRpcWebsocket.Builder.pingDelay`: a
  /// peer frame answers the probe), so a peer that merely eats the pong while it keeps notifying
  /// stages nothing — measured 2026-09-22, the engine re-pinged eight seconds later and was
  /// answered. Phase one holds the stream and the pongs to draw the ping and lets answers
  /// through, re-basing its window behind each one (an answer is peer contact, so the engine's
  /// own silence clock restarts there too); phase two, from the swallow, holds everything through
  /// the response window.
  private volatile long silentUntilNanos;
  /// The end of phase one: a client that has not pinged by then never will on this connection.
  /// Moves behind every answer written, never past [#provokeBudgetEndNanos].
  private volatile long provokeDeadlineNanos;
  /// Where phase one gives up: [#SWALLOW_PING_PROVOKE_WINDOWS] ping windows after the arm. Fixed
  /// at arm time so a connection that answers a request at least once per window cannot hold its
  /// stream indefinitely waiting for an idle gap that never comes. Measured 2026-09-22 on F2
  /// before the window floated: every workload engine wrote `no_target`, because the window ran
  /// from the handshake, which is exactly when the engine replays its subscriptions and is
  /// answered, and only the churn port's idle engines ever pinged.
  private volatile long provokeBudgetEndNanos;
  /// Answers written inside phase one, each of which re-based the window; on the `no_target` row.
  private volatile int provokeRebases;
  /// Phase two: set by the swallow, cleared when its silence ends. While set the write loop holds
  /// the answers too — an answer is a peer frame and would discharge the probe.
  private volatile boolean holdAnswers;
  private volatile FaultKind armedUnsubFault;
  private volatile Fault armedUnsubFaultRecord;
  private volatile int slowWriteMessagesLeft;
  private volatile long outboundFrames;
  /// Set the moment a close frame goes out. Nothing may be written after one — a TEXT frame that
  /// follows a CLOSE is a protocol error, and manufacturing one would fault the client for the
  /// peer's own misbehaviour.
  private volatile boolean closeFrameWritten;
  /// Set the moment the `accept` row goes out, which is the moment the client's view of this port
  /// gains this `connId`. From then on every path out of the connection owes that view a terminal
  /// row.
  private volatile boolean acceptLogged;
  /// True once 101 has gone out and the socket really is a websocket. Below it, the socket is still
  /// HTTP, so a close *frame* written onto it would be bytes no client can read as a close — and
  /// this peer's whole value is that any misbehaviour on the wire is the client's.
  private volatile boolean upgraded;
  /// Names the fault (or the malformed request) that ended the upgrade, and is cleared by whichever
  /// path writes the terminal row. Non-null means: accepted, never upgraded, nothing has closed the
  /// client's view of this `connId` yet.
  private volatile String handshakeAbort;
  private Thread readerThread;
  private Thread writerThread;

  WsConnection(final WsPeer peer, final int port, final long connId, final Socket socket, final Dialect dialect) {
    this.peer = peer;
    this.port = port;
    this.connId = connId;
    this.socket = socket;
    this.dialect = dialect;
    this.writeLock = new ReentrantLock();
    this.outbound = new ArrayBlockingQueue<>(OUTBOUND_CAPACITY);
    this.bySubId = new ConcurrentHashMap<>();
    this.byDedupeKey = new ConcurrentHashMap<>();
    this.liveSubs = new CopyOnWriteArrayList<>();
    this.recentlyUnsubscribed = new ArrayDeque<>();
    this.inboundCapture = new ArrayDeque<>();
    this.outboundCapture = new ArrayDeque<>();
    this.closed = new AtomicBoolean();
    this.terminated = new AtomicBoolean();
    this.armedTransportFault = new AtomicReference<>();
    this.armedPingFault = new AtomicReference<>();
    this.armedSlowWriteFault = new AtomicReference<>();
    this.armedCrosstalkFault = new AtomicReference<>();
    this.crosstalkApplied = new AtomicLong();
    this.heldNotifications = new ConcurrentHashMap<>();
    this.zombieEmitted = new ConcurrentHashMap<>();
  }

  long connId() {
    return connId;
  }

  long attemptOrdinal() {
    return attemptOrdinal;
  }

  boolean closed() {
    return closed.get();
  }

  int liveSubscriptions() {
    return liveSubs.size();
  }

  /// The last 64 inbound and 64 outbound frames, hex, for `/__soak/capture`.
  List<String> capture() {
    final var frames = new ArrayList<String>(CAPTURE_FRAMES * 2);
    synchronized (inboundCapture) {
      frames.addAll(inboundCapture);
    }
    synchronized (outboundCapture) {
      frames.addAll(outboundCapture);
    }
    return frames;
  }

  // --- lifecycle --------------------------------------------------------------------------------

  void start(final Fault connFault) {
    this.readerThread = new Thread(() -> run(connFault), "peer-ws-conn-" + connId);
    this.readerThread.setDaemon(true);
    this.readerThread.start();
  }

  private void run(final Fault connFault) {
    try {
      socket.setTcpNoDelay(true);
      this.in = new BufferedInputStream(socket.getInputStream(), 1 << 16);
      this.out = new BufferedOutputStream(socket.getOutputStream(), 1 << 16);
      if (!handshake(connFault)) {
        return;
      }
      armConnectionFault(connFault);
      this.writerThread = new Thread(this::writeLoop, "peer-ws-out-" + connId);
      this.writerThread.setDaemon(true);
      this.writerThread.start();
      readLoop();
    } catch (final WsFrames.ProtocolException ex) {
      peer.log().ws(port, "error", connId, attemptOrdinal, -1, -1, -1, 0, "protocol: " + ex.getMessage());
      closeWith(ex.closeCode(), "protocol");
    } catch (final IOException ex) {
      // This row is the connection's terminal row whichever side of the upgrade the socket died on,
      // so `terminate()` must not add a second one for an upgrade that never finished.
      this.handshakeAbort = null;
      peer.log().ws(port, "abort", connId, attemptOrdinal, -1, -1, -1, 0, ex.getClass().getSimpleName() + ": " + ex.getMessage());
    } finally {
      terminate();
    }
  }

  /// Reads the upgrade request, records `X-Soak-Attempt`, and answers 101 — unless a `HANDSHAKE_*`
  /// fault says otherwise. `X-Soak-Attempt` is the client-side attempt ordinal, which is what lets
  /// `faults-applied.tsv` say *which* connection attempt a fault hit.
  private boolean handshake(final Fault connFault) throws IOException {
    final var request = readRequestHead();
    if (request == null) {
      return false;
    }
    final var headers = request;
    final var key = headers.get("sec-websocket-key");
    final var attempt = headers.get("x-soak-attempt");
    if (attempt != null) {
      try {
        this.attemptOrdinal = Long.parseLong(attempt.trim());
      } catch (final NumberFormatException ignored) {
        this.attemptOrdinal = -1;
      }
    }
    peer.log().ws(port, "accept", connId, attemptOrdinal, -1, -1, -1, 0,
        "dialect=" + dialect.wireName() + " idPolicy=" + dialect.idPolicy());
    // The client now has this connId in its view of the port. Every `return false` below sets
    // `handshakeAbort`, and `terminate()` turns it into the terminal row that closes that view.
    this.acceptLogged = true;

    final var kind = connFault == null ? null : connFault.kind();
    if (kind == FaultKind.HANDSHAKE_500) {
      writeHttp("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
      peer.applied(connFault, connId, attemptOrdinal, "answered 500 to the upgrade");
      peer.log().ws(port, "handshake", connId, attemptOrdinal, -1, -1, -1, 0, "HANDSHAKE_500");
      this.handshakeAbort = "HANDSHAKE_500";
      return false;
    }
    if (kind == FaultKind.HANDSHAKE_RST) {
      peer.applied(connFault, connId, attemptOrdinal, "reset during the upgrade");
      peer.log().ws(port, "handshake", connId, attemptOrdinal, -1, -1, -1, 0, "HANDSHAKE_RST");
      this.handshakeAbort = "HANDSHAKE_RST";
      reset();
      return false;
    }
    if (kind == FaultKind.HANDSHAKE_STALL) {
      peer.applied(connFault, connId, attemptOrdinal, "accepted and sent nothing for "
          + WsPeer.HANDSHAKE_STALL_SECONDS + "s");
      peer.log().ws(port, "handshake", connId, attemptOrdinal, -1, -1, -1, 0, "HANDSHAKE_STALL");
      this.handshakeAbort = "HANDSHAKE_STALL";
      // The terminal row is owed at the end of the hold, not at its start: while the peer is
      // holding, the socket really is open, and a row claiming otherwise would hand the client's
      // connectTimeout an alibi it had not yet earned.
      WsFrames.pause(TimeUnit.SECONDS.toMillis(WsPeer.HANDSHAKE_STALL_SECONDS));
      return false;
    }
    if (key == null) {
      writeHttp("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
      peer.log().ws(port, "handshake", connId, attemptOrdinal, -1, -1, -1, 0, "missing Sec-WebSocket-Key");
      this.handshakeAbort = "missing Sec-WebSocket-Key";
      return false;
    }
    final var accept = kind == FaultKind.HANDSHAKE_BAD_ACCEPT
        ? WsFrames.acceptKey(key + "-wrong")
        : WsFrames.acceptKey(key.trim());
    writeHttp("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n");
    if (kind == FaultKind.HANDSHAKE_BAD_ACCEPT) {
      peer.applied(connFault, connId, attemptOrdinal, "wrong Sec-WebSocket-Accept");
      peer.log().ws(port, "handshake", connId, attemptOrdinal, -1, -1, -1, 0, "HANDSHAKE_BAD_ACCEPT");
      this.handshakeAbort = "HANDSHAKE_BAD_ACCEPT";
      return false;
    }
    peer.log().ws(port, "handshake", connId, attemptOrdinal, -1, -1, -1, 0, "101");
    this.upgraded = true;
    return true;
  }

  private void armConnectionFault(final Fault connFault) {
    if (connFault == null) {
      return;
    }
    switch (connFault.kind()) {
      // All three are armed here and recorded where they take effect — the swallowed ping, the first
      // slow message, the frame that trips DESTRUCTIVE_AFTER_FRAMES. Most connections in a validate
      // run never see a ping at all, so an `applied` row at arm time would claim a fidelity the run
      // did not earn and would open a P7 recovery window for a fault that never happened.
      case SWALLOW_PING -> {
        this.swallowPing = true;
        this.armedPingFault.set(connFault);
        final long window = pingWindowNanos();
        final long now = System.nanoTime();
        this.provokeBudgetEndNanos = now + SWALLOW_PING_PROVOKE_WINDOWS * window;
        this.provokeDeadlineNanos = now + window;
        this.silentUntilNanos = this.provokeDeadlineNanos;
        this.provokeRebases = 0;
        this.holdAnswers = false;
      }
      case SLOW_WRITE -> {
        this.slowWriteMessagesLeft = SLOW_WRITE_MESSAGES;
        this.armedSlowWriteFault.set(connFault);
      }
      case TCP_RESET, HALF_CLOSE, CLOSE_FRAME -> this.armedTransportFault.set(connFault);
      // CROSSTALK is armed for the connection's lifetime and applied per notification below; it is
      // the only CONN-scope kind that does nothing to the transport.
      // Nothing to arm on the transport: the injection is re-consulted per notification through the
      // CONN seam (`FaultSchedule.always`), and the reference held here is only what the outcome
      // rows below are written against.
      case CROSSTALK -> this.armedCrosstalkFault.set(connFault);
      default -> {
      }
    }
  }

  private Map<String, String> readRequestHead() throws IOException {
    final var headers = new java.util.LinkedHashMap<String, String>();
    final var line = new StringBuilder(128);
    int consecutiveNewlines = 0;
    boolean first = true;
    while (true) {
      final int b = in.read();
      if (b < 0) {
        return null;
      }
      if (b == '\r') {
        continue;
      }
      if (b == '\n') {
        if (line.isEmpty()) {
          if (++consecutiveNewlines >= 1 && !first) {
            return headers;
          }
          continue;
        }
        consecutiveNewlines = 0;
        final var text = line.toString();
        line.setLength(0);
        if (first) {
          first = false;
          headers.put(":request", text);
        } else {
          final int colon = text.indexOf(':');
          if (colon > 0) {
            headers.put(text.substring(0, colon).trim().toLowerCase(Locale.ROOT), text.substring(colon + 1).trim());
          }
        }
      } else {
        if (line.length() < 8192) {
          line.append((char) b);
        }
      }
    }
  }

  private void writeHttp(final String text) throws IOException {
    out.write(text.getBytes(StandardCharsets.US_ASCII));
    out.flush();
  }

  // --- reading ----------------------------------------------------------------------------------

  private void readLoop() throws IOException {
    final var message = new java.io.ByteArrayOutputStream(4096);
    int continuationOpcode = -1;
    while (!closed.get()) {
      final var frame = WsFrames.read(in);
      if (frame == null) {
        peer.log().ws(port, "close_in", connId, attemptOrdinal, -1, -1, -1, 0, "eof");
        return;
      }
      capture(inboundCapture, frame.opcode(), frame.payload());
      switch (frame.opcode()) {
        case WsFrames.OP_PING -> {
          peer.log().ws(port, "ping_in", connId, attemptOrdinal, -1, -1, -1, frame.payload().length, "");
          if (swallowPing) {
            swallowPing = false;
            provokeDeadlineNanos = 0L;
            // phase two: nothing leaves this connection until the engine's response window has
            // elapsed - answers included, since any peer frame would discharge the probe - so the
            // only answer it can get is the one the control withholds
            holdAnswers = true;
            silentUntilNanos = System.nanoTime() + pingWindowNanos();
            recordArmedConnectionFault(armedPingFault, "applied",
                "the ping drew no pong, and the peer stays silent through the response window");
            peer.log().ws(port, "ping_in", connId, attemptOrdinal, -1, -1, -1, frame.payload().length, "swallowed");
          } else {
            enqueue(new Out(OutKind.PONG, null, null, -1, -1, 0, "pong_out", ""), -1, frame.payload());
          }
        }
        case WsFrames.OP_PONG -> peer.log().ws(port, "pong_out", connId, attemptOrdinal, -1, -1, -1,
            frame.payload().length, "client pong");
        case WsFrames.OP_CLOSE -> {
          final int code = frame.payload().length >= 2
              ? ((frame.payload()[0] & 0xFF) << 8) | (frame.payload()[1] & 0xFF)
              : WsFrames.CLOSE_NORMAL;
          peer.log().ws(port, "close_in", connId, attemptOrdinal, -1, -1, -1, frame.payload().length, "code=" + code);
          closeWith(code, "echo");
          return;
        }
        case WsFrames.OP_TEXT, WsFrames.OP_CONTINUATION -> {
          if (frame.opcode() == WsFrames.OP_TEXT) {
            message.reset();
            continuationOpcode = WsFrames.OP_TEXT;
          } else if (continuationOpcode < 0) {
            throw new WsFrames.ProtocolException(WsFrames.CLOSE_PROTOCOL_ERROR, "continuation without a start frame");
          }
          message.write(frame.payload(), 0, frame.payload().length);
          if (frame.fin()) {
            continuationOpcode = -1;
            onMessage(message.toString(StandardCharsets.UTF_8));
            message.reset();
          }
        }
        case WsFrames.OP_BINARY -> throw new WsFrames.ProtocolException(WsFrames.CLOSE_PROTOCOL_ERROR,
            "binary frames are not part of the Solana websocket protocol");
        default -> throw new WsFrames.ProtocolException(WsFrames.CLOSE_PROTOCOL_ERROR,
            "unknown opcode " + frame.opcode());
      }
    }
  }

  private void onMessage(final String text) {
    final long msgId = readLong(text, "id", -1);
    final var method = readString(text, "method");
    if (method == null) {
      peer.log().ws(port, "error", connId, attemptOrdinal, -1, msgId, -1, text.length(), "no method member");
      enqueueText(Payloads.error(msgId, -32600, "Invalid Request"), "sub_err", -1, msgId, "code=-32600");
      return;
    }
    if (method.endsWith("Unsubscribe")) {
      onUnsubscribe(method, msgId, text);
    } else if (method.endsWith("Subscribe")) {
      onSubscribe(method, msgId, text);
    } else {
      enqueueText(Payloads.error(msgId, -32601, "Method not found"), "sub_err", -1, msgId,
          "code=-32601 unknown method " + method);
    }
  }

  // --- subscribe / unsubscribe ------------------------------------------------------------------

  private void onSubscribe(final String method, final long msgId, final String text) {
    final var params = rawParams(text);
    final var dedupeKey = method + '|' + params;
    final long subOrdinal = peer.nextSubOrdinal();
    final var scheduled = peer.schedule().take("sub", port, subOrdinal);
    final var control = peer.schedule().always(FaultKind.Scope.SUB, connId);
    final var kind = control != null ? control : scheduled == null ? null : scheduled.kind();
    if (kind == FaultKind.DROP_REPLAYED_SUBSCRIBES) {
      // Control-only (D1), and the one fault that must leave *no* `sub_req` and no `sub_ack` row.
      // W1-A asks whether every live registration was re-SENT and reads the answer out of those
      // rows, so a peer that logged the replay would leave W1-A's evidence complete and the control
      // could never fail the property it names.
      //
      // The replay is nevertheless ANSWERED on the wire, and that answer is what the 2026-09-21
      // campaign forced: an unanswered replay is an unanswered *request*, which the engine escalates
      // at four resend delays by aborting the connection, so the episode W1-A was meant to judge was
      // superseded before W1-A's own bound and D1 never reached its property at all. A subscription
      // that is granted, confirmed, and then never emitted for is the shape of a client that lost
      // the registration: the wire looks healthy and the evidence is missing. Nothing goes into
      // `liveSubs`, so no later notification can put the missing rows back.
      //
      // SWALLOW_SUBSCRIBE below keeps its `sub_req` row on purpose: W1-B counts rows per
      // (connection, id) and W1-C escalates on the `swallowed` detail, and both need it.
      final long droppedSubId = peer.nextSubId();
      record(scheduled, control, kind, "applied",
          "subscribe " + msgId + " answered, never logged, never emitted");
      enqueueUnlogged(Payloads.subConfirmation(msgId, droppedSubId), droppedSubId, msgId);
      return;
    }
    final boolean swallowed = kind == FaultKind.SWALLOW_SUBSCRIBE;
    // Exactly one sub_req row per request received: the client's W1-B oracle counts these rows
    // per (connection, id), so a second row for the same request would read as a re-send.
    //
    // The row also publishes the subscribe *identity* the peer has already computed for its own
    // dedupe: `fp=` is fnv64 of `method|params`, the same `dedupeKey` the grant below is keyed on.
    // W1-B is a promise about a registration, not about a JSON-RPC id, and an oracle that can only
    // see `msgId` passes a param-identical duplicate that arrives under a fresh id — which is
    // exactly what a dialect that does not dedupe (`helius`) and the `DEDUPE_BREAK` control
    // produce. Publishing the fingerprint is the peer half of keying that oracle on method+params.
    // The column shape is unchanged: this is one more token inside the detail column.
    peer.log().ws(port, "sub_req", connId, attemptOrdinal, -1, msgId, subOrdinal, text.length(),
        (swallowed ? "swallowed " + kind : method) + " fp=" + fingerprint(dedupeKey));

    if (swallowed) {
      record(scheduled, control, kind, "applied", "subscribe " + msgId + " received and never answered");
      return;
    }
    if (kind == FaultKind.REJECT_SUBSCRIBE) {
      record(scheduled, control, kind, "applied", "-32603 Subscription refused");
      enqueueText(Payloads.error(msgId, -32603, "Subscription refused"), "sub_err", -1, msgId, "code=-32603");
      return;
    }
    if (kind == FaultKind.ERROR_RESPONSE) {
      record(scheduled, control, kind, "applied", "-32602 to a well-formed subscribe");
      enqueueText(Payloads.error(msgId, -32602, "Invalid params"), "sub_err", -1, msgId, "code=-32602");
      return;
    }
    if (kind == FaultKind.SWALLOW_UNSUB || kind == FaultKind.REFUSE_UNSUB) {
      // Armed here, applied to the next unsubscribe on this connection: DESIGN.md §6 defines the
      // `sub` counter as the nth *subscribe* request, so an unsubscribe-scoped fault has no counter
      // of its own to be keyed on.
      this.armedUnsubFault = kind;
      this.armedUnsubFaultRecord = scheduled;
    }

    final long existing = dialect.idPolicy() == IdPolicy.DEDUPE && kind != FaultKind.DEDUPE_BREAK
        ? byDedupeKey.getOrDefault(dedupeKey, -1L)
        : -1L;
    final long subId;
    if (existing >= 0) {
      subId = existing;
    } else if (kind == FaultKind.DUP_SUBID || kind == FaultKind.SUBID_REUSE_ACROSS_PARAMS) {
      final var victim = firstSubWithOtherKey(dedupeKey);
      if (victim == null) {
        record(scheduled, control, kind, "no_target", "no live subscription with different params");
        subId = peer.nextSubId();
      } else {
        record(scheduled, control, kind, "applied", "granted live subId " + victim.subId + " to different params");
        subId = victim.subId;
      }
    } else {
      subId = peer.nextSubId();
    }

    if (kind == FaultKind.DEDUPE_BREAK) {
      record(scheduled, control, kind, "applied", "fresh id for byte-identical params under " + dialect.wireName());
      // A SECOND `sub_req` row for the same request: same JSON-RPC id, same `sub` ordinal, same
      // fingerprint. The fresh subscription id alone cannot fail W1-B, because that oracle's verdict
      // is "one registration was requested twice on one connection" and the evidence for it — two
      // request rows — is something only a re-sending CLIENT leaves. No server behaviour produces
      // it, which is why D6 ran green against a correct client for as long as the peer only broke
      // dedupe. The control is a stand-in for a client that re-sent, so the stand-in stages the
      // evidence the oracle reads rather than a peer-side symptom the client is right to ignore.
      peer.log().ws(port, "sub_req", connId, attemptOrdinal, -1, msgId, subOrdinal, text.length(),
          "DEDUPE_BREAK duplicate row fp=" + fingerprint(dedupeKey));
    }

    final var state = bySubId.computeIfAbsent(subId, id -> new SubState(
        id, method, dedupeKey, Payloads.Channel.forMethod(method), subscriptionKey(method, params), msgId));
    if (existing < 0) {
      byDedupeKey.putIfAbsent(dedupeKey, subId);
    }

    if (kind == FaultKind.DELAY_CONFIRM) {
      // A real node creates the subscription and confirms it BEFORE it emits for it, so the
      // stream goes live together with the deferred confirmation. Emitting first would hand the
      // client notifications for an id it has not been told about, which is a different fault
      // (UNKNOWN_SUB_NOTIFICATION) wearing this one's name.
      final long delay = peer.config().delayConfirmMillis();
      record(scheduled, control, kind, "applied", "confirmation deferred " + delay + "ms");
      peer.scheduler().schedule(() -> {
        enqueueText(Payloads.subConfirmation(msgId, subId), "sub_ack", subId, msgId);
        if (!closed.get() && bySubId.get(subId) == state && !liveSubs.contains(state)) {
          liveSubs.add(state);
        }
      }, delay, TimeUnit.MILLISECONDS);
      return;
    }
    if (existing < 0 && !liveSubs.contains(state)) {
      liveSubs.add(state);
    }
    enqueueText(Payloads.subConfirmation(msgId, subId), "sub_ack", subId, msgId);
    if (kind == FaultKind.DUPLICATE_CONFIRMATION) {
      record(scheduled, control, kind, "applied", "confirmation sent twice");
      enqueueText(Payloads.subConfirmation(msgId, subId), "sub_ack", subId, msgId);
    }
    if (kind == FaultKind.WRONG_ID_ECHO) {
      record(scheduled, control, kind, "applied", "extra confirmation under id " + (msgId + 1));
      enqueueText(Payloads.subConfirmation(msgId + 1, subId), "sub_ack", subId, msgId + 1);
    }
  }

  /// The subscription an oversized message is written for: the one that came up, when it can carry
  /// a payload, else the first live one that can. Only account and program notifications have a
  /// data field to inflate.
  private SubState overflowTarget(final SubState sub) {
    if (sub.channel == Payloads.Channel.ACCOUNT || sub.channel == Payloads.Channel.PROGRAM) {
      return sub;
    }
    for (final var candidate : liveSubs) {
      if (candidate.channel == Payloads.Channel.ACCOUNT || candidate.channel == Payloads.Channel.PROGRAM) {
        return candidate;
      }
    }
    return null;
  }

  private SubState firstSubWithOtherKey(final String dedupeKey) {
    for (final var sub : liveSubs) {
      if (!sub.dedupeKey.equals(dedupeKey)) {
        return sub;
      }
    }
    return null;
  }

  private void onUnsubscribe(final String method, final long msgId, final String text) {
    final long subId = firstParamLong(text);
    // The granting subscribe's own id travels in the detail, so the client's oracle can join a
    // cancellation to its registration even when the confirmation was delayed and has not been
    // seen yet (`DELAY_CONFIRM`), and can tell an unsubscribe of a granted id from a stale one.
    final var granted = bySubId.get(subId);
    peer.log().ws(port, "unsub_req", connId, attemptOrdinal, subId, msgId, -1, text.length(),
        granted == null ? method + " stale" : method + " subMsgId=" + granted.msgId);
    final var armed = armedUnsubFault;
    if (armed == FaultKind.SWALLOW_UNSUB) {
      this.armedUnsubFault = null;
      recordArmed("applied", "unsubscribe " + msgId + " never acknowledged");
      return;
    }
    if (armed == FaultKind.REFUSE_UNSUB) {
      this.armedUnsubFault = null;
      recordArmed("applied", "-32602 to a live unsubscribe");
      enqueueText(Payloads.error(msgId, -32602, "Invalid subscription id."), "unsub_err", subId, msgId, "code=-32602");
      return;
    }
    final var state = bySubId.remove(subId);
    if (state == null) {
      // The dialects differ here and sava has to tolerate both (measured 2026-08-09).
      if (dialect == Dialect.AGAVE) {
        enqueueText(Payloads.error(msgId, -32602, "Invalid subscription id."), "unsub_err", subId, msgId, "code=-32602");
      } else {
        enqueueText(Payloads.boolResult(msgId, false), "unsub_ack", subId, msgId);
      }
      return;
    }
    liveSubs.remove(state);
    byDedupeKey.remove(state.dedupeKey, subId);
    // A notification the swap control is still holding for this id must not survive the
    // cancellation: releasing it later would be a notification after an acknowledged unsubscribe,
    // which is the ZOMBIE_NOTIFICATION fault, and the client would rightly fail W1-D on a defect
    // the peer manufactured. The sequence is written off here, with its own coordinates.
    final var held = heldNotifications.remove(subId);
    if (held != null) {
      peer.log().ws(port, "notify", connId, attemptOrdinal, held.subId(), held.msgId(), held.seq(),
          held.message().length(), "swapped dropped: subscription unsubscribed while held");
    }
    synchronized (recentlyUnsubscribed) {
      recentlyUnsubscribed.addLast(subId);
      while (recentlyUnsubscribed.size() > CAPTURE_FRAMES) {
        // The amplification counter dies with the id it belongs to: an id that has fallen out of
        // this window can never be picked as a zombie target again, so keeping its count would leak
        // one entry per unsubscribe for the length of a campaign.
        zombieEmitted.remove(recentlyUnsubscribed.removeFirst());
      }
    }
    enqueueText(Payloads.boolResult(msgId, true), "unsub_ack", subId, msgId);
  }

  // --- notification emission --------------------------------------------------------------------

  /// Called by the port's `peer-ws-emit-<port>` scheduler. Picks the next live subscription
  /// round-robin and enqueues the *intent*; the sequence is assigned in [#dispatch] under the write
  /// lock.
  void emit(final boolean large) {
    final var subs = liveSubs;
    if (subs.isEmpty() || closed.get()) {
      return;
    }
    final int index = Math.floorMod(emitCursor++, subs.size());
    final var sub = subs.get(index);
    enqueue(new Out(OutKind.NOTIFY, null, sub, sub.subId, -1,
        large ? peer.config().largeBytes() : SMALL_PAYLOAD_BYTES, "notify", ""), sub.subId, null);
  }

  private void enqueueText(final String json, final String logKind, final long subId, final long msgId) {
    enqueueText(json, logKind, subId, msgId, "");
  }

  private void enqueueText(final String json, final String logKind, final long subId, final long msgId,
                           final String detail) {
    enqueue(new Out(OutKind.TEXT, json, null, subId, msgId, 0, logKind, detail), subId, null);
  }

  /// A message that goes on the wire and leaves no `peer-ws.tsv` row. The only caller is
  /// `DROP_REPLAYED_SUBSCRIBES`, whose whole staged defect is a reply the peer log cannot account
  /// for; any other unlogged write would be the peer hiding its own traffic from the oracles.
  private void enqueueUnlogged(final String json, final long subId, final long msgId) {
    enqueue(new Out(OutKind.TEXT, json, null, subId, msgId, 0, null, ""), subId, null);
  }

  private void enqueue(final Out out, final long subId, final byte[] controlPayload) {
    if (closed.get()) {
      return;
    }
    final var queued = controlPayload == null
        ? out
        : new Out(out.kind(), new String(controlPayload, StandardCharsets.ISO_8859_1), out.sub(), subId,
        out.msgId(), out.payloadBytes(), out.logKind(), out.detail());
    if (!outbound.offer(queued)) {
      peer.log().ws(port, "overflow", connId, attemptOrdinal, -1, -1, -1, OUTBOUND_CAPACITY,
          "outbound queue full; closing 1011");
      closeWith(WsFrames.CLOSE_INTERNAL, "overflow");
    }
  }

  /// One ping window as the engine measures it — the ping delay, plus the check-loop tick that
  /// observes it, plus slack — used for both halves of `SWALLOW_PING`'s silence.
  private long pingWindowNanos() {
    return TimeUnit.MILLISECONDS.toNanos(peer.config().pingDelayMs() + peer.config().checkDelayMs() + 2_000L);
  }

  /// Frames set aside while `SWALLOW_PING`'s silence holds, in order: notifications and pongs in
  /// phase one, so the single writer can keep answering requests until the engine idles and
  /// pings; everything but a close in phase two. Held rather than dropped, so the sequence oracle
  /// sees a pause and not a gap; a connection the engine retires during the hold never drains
  /// them, which is the retirement the control asked for. A hold that blocked the writer itself
  /// starved the answers queued behind the first held notification (measured on a pilot: a
  /// re-sent subscribe never acknowledged, escalated by the engine as unanswered before it had
  /// idled long enough to ping), which is why phase one lets answers through.
  private final java.util.ArrayDeque<Out> heldForSilence = new java.util.ArrayDeque<>();

  private boolean silent() {
    return System.nanoTime() < silentUntilNanos;
  }

  /// Once the silence has ended: the phase-one bookkeeping (a client that never pinged into it).
  private void silenceEnded() {
    holdAnswers = false;
    final long deadline = provokeDeadlineNanos;
    if (deadline != 0L && swallowPing && System.nanoTime() >= deadline) {
      // Phase one ended without a ping: the engine did not probe this silence, so the fault has
      // nothing to take on this connection. Said so and disarmed, rather than left armed for a
      // later keep-alive ping whose swallowing would then be recorded outside any silence.
      swallowPing = false;
      provokeDeadlineNanos = 0L;
      recordArmedConnectionFault(armedPingFault, "no_target", "the client did not ping: no "
          + TimeUnit.NANOSECONDS.toMillis(pingWindowNanos()) + " ms free of its own requests inside "
          + TimeUnit.NANOSECONDS.toMillis(SWALLOW_PING_PROVOKE_WINDOWS * pingWindowNanos())
          + " ms of peer silence (" + provokeRebases + " answer(s) re-based the window)");
    }
  }

  /// Phase one of `SWALLOW_PING`, measured the way the engine measures its silence: from the
  /// peer's last frame. An answer written inside the window is that frame, so the window starts
  /// again behind it, up to the budget fixed at arm time. Runs on the writer, after the frame is
  /// on the wire.
  private void rebaseProvokeWindow() {
    if (!swallowPing || provokeDeadlineNanos == 0L) {
      return;
    }
    final long deadline = Math.min(System.nanoTime() + pingWindowNanos(), provokeBudgetEndNanos);
    if (deadline > provokeDeadlineNanos) {
      provokeRebases++;
      provokeDeadlineNanos = deadline;
      silentUntilNanos = deadline;
    }
  }

  private void writeLoop() {
    while (!closed.get()) {
      try {
        final Out next;
        if (!silent()) {
          silenceEnded();
          // held notifications drain first, in order, before anything newer
          final var held = heldForSilence.poll();
          if (held != null) {
            next = held;
          } else {
            next = outbound.poll(200, TimeUnit.MILLISECONDS);
          }
        } else {
          next = outbound.poll(200, TimeUnit.MILLISECONDS);
        }
        if (next == null) {
          continue;
        }
        if (silent() && (holdAnswers
            ? next.kind() != OutKind.CLOSE
            : next.kind() == OutKind.NOTIFY || next.kind() == OutKind.PONG)) {
          // Held aside rather than in the writer's path. In phase one only the stream and the
          // pong are held and a request's answer still goes out: an ack held back is an unanswered
          // request to the engine, which escalates after its resend windows - on a pilot every
          // one of a replayed connection's 64 subscribes sat behind a 19 s hold and the engine
          // replaced the connection at 12.6 s, before it had any reason to ping. The answer is
          // peer contact, so the phase-one window re-bases behind it (dispatch) and the row reads
          // no_target only when the engine never idled for a whole window inside the budget. In
          // phase two everything but a close is held: the probe is out, any frame would answer it,
          // and a request sent into that silence is starved for at most one window - the engine
          // retires on the probe first unless the window is longer than its unanswered-request
          // deadline, which the client's W1-H(ii) counts rather than judges.
          heldForSilence.add(next);
          continue;
        }
        writeLock.lock();
        try {
          dispatch(next);
        } finally {
          writeLock.unlock();
        }
      } catch (final InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      } catch (final IOException ex) {
        peer.log().ws(port, "abort", connId, attemptOrdinal, -1, -1, -1, 0,
            "write: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        terminate();
        return;
      }
    }
  }

  private void dispatch(final Out next) throws IOException {
    switch (next.kind()) {
      case PONG -> {
        final byte[] payload = next.text().getBytes(StandardCharsets.ISO_8859_1);
        WsFrames.writePong(out, payload);
        capture(outboundCapture, WsFrames.OP_PONG, payload);
        peer.log().ws(port, "pong_out", connId, attemptOrdinal, -1, -1, -1, payload.length, "");
        countFrame();
      }
      case CLOSE -> {
        flushHeldNotifications();
        this.closeFrameWritten = true;
        WsFrames.writeClose(out, (int) next.msgId(), next.text());
        peer.log().ws(port, "close_out", connId, attemptOrdinal, -1, -1, -1, 0, "code=" + next.msgId());
        closed.set(true);
      }
      case TEXT -> {
        writeMessage(next.text());
        if (next.logKind() != null) {
          peer.log().ws(port, next.logKind(), connId, attemptOrdinal, next.subId(), next.msgId(), -1,
              next.text().length(), next.detail());
        }
        rebaseProvokeWindow();
      }
      case NOTIFY -> writeNotification(next);
    }
  }

  /// Write-time sequence assignment (`DESIGN.md` §7) plus every notification-scoped fault.
  private void writeNotification(final Out next) throws IOException {
    final var sub = next.sub();
    if (sub == null) {
      return;
    }
    final long notifyOrdinal = peer.nextNotifyOrdinal();
    final var scheduled = peer.schedule().take("notify", port, notifyOrdinal);
    final var control = peer.schedule().always(FaultKind.Scope.NOTIFY, connId);
    // Reassigned below when a fault found no target: the ordinary notification written in its
    // place must then be logged as ordinary, or the client's oracle reads a live subscription's
    // normal traffic as a zombie and fails W1-D on it.
    FaultKind kind = control != null ? control : scheduled == null ? null : scheduled.kind();

    if (kind == FaultKind.MESSAGE_OVERFLOW) {
      // The oversized message is a REAL notification for a live account or program subscription:
      // its payload carries the usual checksum and it consumes that subscription's next sequence.
      // On the engine whose cap it exceeds it is aborted unseen; on every other engine it is
      // delivered, and a delivered message with a bogus payload or a re-used sequence would fail
      // W2-A and W1-E for a fault that never fired.
      final var target = overflowTarget(sub);
      if (target == null) {
        record(scheduled, control, kind, "no_target", "no live account/program subscription to inflate");
      } else {
        final long overflowSeq = ++target.seq;
        final var message = peer.payloads().notification(target.channel, target.subId, overflowSeq,
            target.key, Payloads.OVERFLOW_PAYLOAD_BYTES, false);
        record(scheduled, control, kind, "applied", "message of " + message.length() + " chars");
        writeMessage(message);
        peer.log().ws(port, "notify", connId, attemptOrdinal, target.subId, target.msgId, overflowSeq,
            message.length(), "MESSAGE_OVERFLOW");
        return;
      }
    }
    if (kind == FaultKind.UNKNOWN_SUB_NOTIFICATION) {
      final var message = peer.payloads().unknownSubNotification(notifyOrdinal);
      record(scheduled, control, kind, "applied", "subId " + Payloads.UNKNOWN_SUB_ID + " was never granted");
      writeMessage(message);
      peer.log().ws(port, "notify", connId, attemptOrdinal, Payloads.UNKNOWN_SUB_ID, -1, -1, message.length(),
          "UNKNOWN_SUB_NOTIFICATION");
      return;
    }
    if (kind == FaultKind.ZOMBIE_NOTIFICATION) {
      final Long zombie;
      synchronized (recentlyUnsubscribed) {
        zombie = recentlyUnsubscribed.peekLast();
      }
      final long drawn = zombie == null
          ? 0L
          : zombieEmitted.computeIfAbsent(zombie, id -> new AtomicLong()).incrementAndGet();
      if (zombie == null) {
        record(scheduled, control, kind, "no_target", "no acknowledged unsubscribe on this connection yet");
        kind = null;
      } else if (drawn > ZOMBIE_AMPLIFICATION_BOUND) {
        // Past the bound the slot goes back to the live subscriptions. A scheduled firing is still
        // owed its own outcome row at its own ordinal, or the report reads the kind as reached and
        // never applied; the always-on control is owed one sentence rather than one per
        // notification, which at `SOAK_WS_NOTIFY_RPS` would be the whole of `faults-applied.tsv`.
        if (scheduled != null || drawn == ZOMBIE_AMPLIFICATION_BOUND + 1L) {
          record(scheduled, control, kind, "no_target", "retired subId " + zombie + " has drawn its "
              + ZOMBIE_AMPLIFICATION_BOUND + " notification(s) after the acknowledged unsubscribe");
        }
        kind = null;
      } else {
        final var message = peer.payloads().accountNotification(zombie, notifyOrdinal, SMALL_PAYLOAD_BYTES);
        record(scheduled, control, kind, "applied", "one more notification for retired subId " + zombie);
        writeMessage(message);
        peer.log().ws(port, "notify", connId, attemptOrdinal, zombie, -1, -1, message.length(),
            "ZOMBIE_NOTIFICATION");
        return;
      }
    }

    // CROSSTALK (control-only, CONN scope, so the seam is asked with this connection's own
    // ordinal): borrow an otherwise unfaulted notification and write it with a sequence that
    // belongs to a *different* connection's stream on the same channel. A scheduled or
    // NOTIFY-scope control fault at this ordinal keeps precedence — it is owed its outcome row —
    // so the borrow only happens when `kind` is null.
    if (kind == null
        && peer.schedule().always(FaultKind.Scope.CONN, connId) == FaultKind.CROSSTALK
        && applyCrosstalk(sub, next.payloadBytes())) {
      return;
    }

    final long seq = ++sub.seq;
    final boolean terminal = sub.channel == Payloads.Channel.SIGNATURE && !sub.signatureTerminated && (seq & 1) == 0;
    if (terminal) {
      sub.signatureTerminated = true;
    }
    // `Payloads.notification` hands `key` to `programNotification` and to nothing else: on ACCOUNT,
    // LOGS, SLOT, ROOT, SIGNATURE and TRANSACTION the argument is discarded and the bytes on the
    // wire are byte-identical to an unfaulted notification. Substituting there and recording
    // `applied` would claim an injection that changed nothing, so those channels get the
    // `no_target` sentence instead — the same shape ZOMBIE_NOTIFICATION uses above, `kind` cleared
    // so the row this writes reads as the ordinary notification it is.
    String key = sub.key;
    if (kind == FaultKind.WRONG_KEY_ECHO) {
      if (sub.channel == Payloads.Channel.PROGRAM) {
        key = Stamp.derivedKey("wrong-key-echo", (int) (seq & 0xFF));
        record(scheduled, control, kind, "applied", "pubkey echoed as " + key + " instead of " + sub.key);
      } else {
        record(scheduled, control, kind, "no_target",
            "channel " + sub.channel + " carries no pubkey to echo wrongly");
        kind = null;
      }
    }
    final var message = peer.payloads().notification(sub.channel, sub.subId, seq, key, next.payloadBytes(), terminal);

    if (kind == FaultKind.DANGLING_FRAGMENT) {
      final byte[] dangling = message.substring(0, Math.min(140, message.length())).getBytes(StandardCharsets.UTF_8);
      WsFrames.writeDanglingFragment(out, dangling);
      capture(outboundCapture, WsFrames.OP_TEXT, dangling);
      countFrame();
      record(scheduled, control, kind, "applied", "TEXT FIN=0 with no continuation, then a normal message");
    }
    if (kind == FaultKind.SWAP_ADJACENT_NOTIFY) {
      // Per subscription, in pairs: hold this subscription's seq N, and when its OWN seq N+1 comes
      // round write N+1 first and N straight after, so the client sees seq N+1 before seq N and the
      // per-(engine, key) oracle returns REORDER. Holding across subscriptions cannot produce that
      // defect at all: `emit` picks subscriptions round-robin, so two adjacent notifications on the
      // wire usually belong to different keys, and holding every message one position is a uniform
      // shift that leaves every key's own sequence strictly increasing.
      final var held = heldNotifications.remove(sub.subId);
      if (held == null) {
        heldNotifications.put(sub.subId, new Held(sub.subId, sub.msgId, seq, message));
        record(scheduled, control, kind, "no_target",
            "seq " + seq + " of subId " + sub.subId + " held back; the swap needs its successor");
        return;
      }
      record(scheduled, control, kind, "applied",
          "seq " + seq + " written ahead of held seq " + held.seq() + " of subId " + sub.subId);
      writeMessage(message);
      peer.log().ws(port, "notify", connId, attemptOrdinal, sub.subId, sub.msgId, seq, message.length(),
          "swapped ahead of " + held.seq());
      // Each row names the message it carries. Logging the held bytes under the releasing
      // notification's subId/msgId/seq would make every join on (connId, subId, seq) wrong.
      writeMessage(held.message());
      peer.log().ws(port, "notify", connId, attemptOrdinal, held.subId(), held.msgId(), held.seq(),
          held.message().length(), "swapped behind " + seq);
      return;
    }

    final int flip = kind == FaultKind.FLIP_CONTINUATION_BYTE ? 1 : -1;
    if (flip >= 0) {
      record(scheduled, control, kind, "applied", "one byte of continuation frame 1 flipped");
    }
    writeMessage(message, flip);
    peer.log().ws(port, "notify", connId, attemptOrdinal, sub.subId, sub.msgId, seq, message.length(),
        kind == null ? "" : kind.name());

    if (kind == FaultKind.DUPLICATE_NOTIFY) {
      record(scheduled, control, kind, "applied", "the same sequence written twice");
      writeMessage(message);
      peer.log().ws(port, "notify", connId, attemptOrdinal, sub.subId, sub.msgId, seq, message.length(), "duplicate");
    }
  }

  /// The `CROSSTALK` control (`DESIGN.md` §6): this connection's own subscription, its own granted
  /// `subId` and key, but the sequence another live connection has reached on the same channel.
  ///
  /// Only the *sequence* crosses. Writing the other connection's whole notification would put a
  /// `subId` this client was never granted on the wire, which is `UNKNOWN_SUB_NOTIFICATION` wearing
  /// another fault's name; the sequence is the field the client's per-`(engine, key, epoch)` oracle
  /// can refuse, so it is the field that has to come from somewhere else. Two connections' counters
  /// for one channel advance independently, so the borrowed value lands behind (`DUP`) or ahead
  /// (`GAP`) of what this key is owed.
  ///
  /// The subscription's own counter is **not** advanced: the injected message is an extra one, and
  /// the next ordinary notification still carries the sequence the client expects, so the anomaly
  /// is the injection itself rather than a truncated stream.
  ///
  /// The source is another live connection on **any port** of this peer process (each port serves
  /// one engine, so a same-port sibling exists only in a reconnect's overlap). With no such
  /// connection there is nothing to take a sequence from and the row says `no_target` rather than
  /// the peer inventing a number no counter holds.
  ///
  /// @return true when the injection was written, so the caller must not also write the ordinary
  ///         notification for this slot.
  private boolean applyCrosstalk(final SubState sub, final int payloadBytes) throws IOException {
    final var source = peer.crosstalkSource(this, sub.channel);
    if (source == null) {
      recordArmedConnectionFault(armedCrosstalkFault, "no_target", "no other live connection on any port"
          + " holds a " + sub.channel + " subscription whose sequence has started");
      return false;
    }
    final long foreignSeq = source.seq();
    if (foreignSeq == sub.seq + 1) {
      // The two counters are momentarily aligned, so this value IS the sequence the receiver is
      // owed next and injecting it would change nothing. Say so once and let the ordinary
      // notification take the slot; fabricating an offset would be the harness making up a
      // sequence rather than taking one from another connection.
      recordArmedConnectionFault(armedCrosstalkFault, "no_target", "connId " + source.connId()
          + " is at the same " + sub.channel + " sequence (" + foreignSeq + ") this subscription is owed");
      return false;
    }
    final var message = peer.payloads().notification(sub.channel, sub.subId, foreignSeq, sub.key, payloadBytes, false);
    writeMessage(message);
    peer.log().ws(port, "notify", connId, attemptOrdinal, sub.subId, sub.msgId, foreignSeq, message.length(),
        "CROSSTALK from port=" + source.port() + " connId=" + source.connId() + " subId=" + source.subId());
    if (crosstalkApplied.incrementAndGet() == 1) {
      // Cleared rather than drained through [#recordArmedConnectionFault]: an earlier notification
      // that found no source may already have written this connection's `no_target` row, and a
      // control that then did inject must still say so. `appliedControl` writes the same ordinal-0
      // row shape the armed reference would have.
      armedCrosstalkFault.set(null);
      peer.appliedControl(FaultKind.CROSSTALK, port, connId, attemptOrdinal, "applied",
          "seq " + foreignSeq + " of port " + source.port() + " connId " + source.connId() + " subId " + source.subId()
              + " written to connId " + connId + " subId " + sub.subId + " (" + sub.channel + "), "
              + (foreignSeq <= sub.seq ? "a duplicate" : "a gap") + " for this subscription");
    }
    return true;
  }

  /// This connection's offer to a `CROSSTALK` injection on another connection: a live subscription
  /// of `channel` whose sequence has already started. A counter still at 0 is not a sequence any
  /// stream has reached, and `DESIGN.md` §7 starts sequences at 1.
  CrosstalkSource crosstalkSource(final Payloads.Channel channel) {
    for (final var candidate : liveSubs) {
      final long seq = candidate.seq;
      if (candidate.channel == channel && seq > 0) {
        return new CrosstalkSource(port, connId, candidate.subId, seq);
      }
    }
    return null;
  }

  private void writeMessage(final String message) throws IOException {
    writeMessage(message, -1);
  }

  /// One logical message. Large payloads are split into `SOAK_FRAGMENTS` continuation frames at
  /// seeded split points; `SLOW_WRITE` puts 50 ms between them.
  private void writeMessage(final String message, final int flipFragment) throws IOException {
    final byte[] utf8 = message.getBytes(StandardCharsets.UTF_8);
    final boolean slow = slowWriteMessagesLeft > 0;
    final long gap = slow ? SLOW_WRITE_GAP_MILLIS : 0L;
    if (slow) {
      --slowWriteMessagesLeft;
      recordArmedConnectionFault(armedSlowWriteFault, "applied", SLOW_WRITE_GAP_MILLIS
          + "ms between the frames of " + SLOW_WRITE_MESSAGES + " messages");
    }
    if (utf8.length > peer.config().largeBytes() / 2 || flipFragment >= 0 || slow) {
      final int fragments = Math.max(2, peer.config().fragments());
      final int[] splits = WsFrames.splitPoints(peer.config().seed() ^ utf8.length, utf8.length, fragments);
      WsFrames.writeFragments(out, utf8, splits, gap, flipFragment);
      countFrames(splits.length + 1);
    } else {
      WsFrames.writeText(out, utf8);
      countFrame();
    }
    capture(outboundCapture, WsFrames.OP_TEXT, utf8);
  }

  private void countFrame() {
    countFrames(1);
  }

  private void countFrames(final int frames) {
    outboundFrames += frames;
    if (outboundFrames < DESTRUCTIVE_AFTER_FRAMES) {
      return;
    }
    // getAndSet is the arbiter between this thread and a concurrent `terminate()`: whichever drains
    // the reference writes the one outcome row the fault is owed, and the other sees null.
    final var armed = armedTransportFault.getAndSet(null);
    if (armed != null) {
      applyTransportFault(armed);
    }
  }

  private void applyTransportFault(final Fault fault) {
    // Whatever the swap control is still holding goes out before the transport is broken: the fault
    // is about the transport, not about swallowing a sequence the client is still owed.
    flushHeldNotifications();
    try {
      switch (fault.kind()) {
        case TCP_RESET -> {
          peer.applied(fault, connId, attemptOrdinal, "SO_LINGER(true,0) then close");
          peer.log().ws(port, "abort", connId, attemptOrdinal, -1, -1, -1, 0, "TCP_RESET");
          reset();
        }
        case HALF_CLOSE -> {
          peer.applied(fault, connId, attemptOrdinal, "shutdownOutput, still reading");
          peer.log().ws(port, "abort", connId, attemptOrdinal, -1, -1, -1, 0, "HALF_CLOSE");
          socket.shutdownOutput();
        }
        case CLOSE_FRAME -> {
          peer.applied(fault, connId, attemptOrdinal, "close 1011 soak");
          this.closeFrameWritten = true;
          WsFrames.writeClose(out, WsFrames.CLOSE_INTERNAL, "soak");
          peer.log().ws(port, "close_out", connId, attemptOrdinal, -1, -1, -1, 0, "code=1011 soak");
          closed.set(true);
        }
        default -> {
        }
      }
    } catch (final IOException ex) {
      peer.log().ws(port, "abort", connId, attemptOrdinal, -1, -1, -1, 0,
          fault.kind() + ": " + ex.getMessage());
    }
  }

  // --- shutdown ---------------------------------------------------------------------------------

  void closeWith(final int code, final String reason) {
    if (closed.get()) {
      return;
    }
    writeLock.lock();
    try {
      // Before the close frame, never after it: a text frame that follows a close is a protocol
      // error, and a sequence the swap control is still holding is still owed to the client.
      flushHeldNotifications();
      // Nothing to close politely before the upgrade: shutdown can reach a connection still inside
      // its handshake (a `HANDSHAKE_STALL` is holding one open by design), and a close frame there
      // would be websocket bytes on an HTTP socket plus a second terminal row beside the one
      // `terminate()` owes that connection.
      if (out != null && upgraded) {
        this.closeFrameWritten = true;
        WsFrames.writeClose(out, code, reason);
        peer.log().ws(port, "close_out", connId, attemptOrdinal, -1, -1, -1, 0, "code=" + code + " " + reason);
      }
    } catch (final IOException ignored) {
      // The socket is going away either way; the close frame is a courtesy, not a guarantee.
    } finally {
      closed.set(true);
      writeLock.unlock();
    }
    terminate();
  }

  private void reset() {
    try {
      socket.setSoLinger(true, 0);
    } catch (final Exception ignored) {
      // A socket already closed by the peer cannot be given a linger option; the close below is
      // still the right thing to do.
    }
    closed.set(true);
    closeQuietly();
  }

  /// Runs its body exactly once per connection. The old guard skipped it entirely when the socket
  /// was already closed *and* `closed` already set — the `reset()` path — which silently dropped
  /// every armed fault's outcome row on the one path that reaches it most often.
  void terminate() {
    if (!terminated.compareAndSet(false, true)) {
      return;
    }
    closed.set(true);
    flushHeldNotifications();
    closeQuietly();
    // A connection the peer accepted and never upgraded has, until here, no `close_out`, no
    // `close_in` and no `abort`: every `HANDSHAKE_*` path returns before the websocket exists, so
    // nothing downstream had a socket to close and the client's view of this connId stayed open for
    // the rest of the run. The oracles then went on asking about a connection the peer had already
    // dropped — W1-H reported "no ping" on a socket the peer had reset, W1-I asked about a stale
    // conn id. The socket is closed by the line above, so this row says so and names what ended the
    // upgrade; `abort` rather than `close_out` because none of these paths sends a close frame, and
    // a peer claiming a courtesy it never extended is the kind of lie this log exists not to tell.
    final var abortedUpgrade = handshakeAbort;
    if (acceptLogged && !upgraded && abortedUpgrade != null) {
      this.handshakeAbort = null;
      peer.log().ws(port, "abort", connId, attemptOrdinal, -1, -1, -1, 0,
          abortedUpgrade + ": upgrade never completed, socket closed");
    }
    // An unsubscribe-scoped fault armed on a connection that ended before any unsubscribe arrived
    // never had a target. Saying so keeps `applied / (planned - no_target)` an honest ratio.
    recordArmed("no_target", "connection ended before any unsubscribe arrived");
    // The connection-scoped faults are owed the same sentence, and for the transport ones the cost
    // of silence is higher than a dented ratio: `DESIGN.md` §6 calls a kind that was reached and
    // never applied `fault_kinds_unapplied` and fails the run on it, so a connection that died
    // below the frame trigger would fail a run no client misbehaved in.
    recordArmedConnectionFault(armedTransportFault, "no_target", "connection ended after "
        + outboundFrames + " outbound frame(s), below the " + DESTRUCTIVE_AFTER_FRAMES + "-frame trigger");
    recordArmedConnectionFault(armedPingFault, "no_target", "connection ended before the client pinged");
    recordArmedConnectionFault(armedSlowWriteFault, "no_target",
        "connection ended before another message was written");
    recordArmedConnectionFault(armedCrosstalkFault, "no_target",
        "connection ended before any notification could carry another connection's sequence");
    // The first injection wrote its own row; the rest were counted rather than written, so the
    // total is stated once here. Without it `faults-applied.tsv` would say a control fired and
    // never say how much of the run it shaped.
    final long crossed = crosstalkApplied.get();
    if (crossed > 1) {
      peer.appliedControl(FaultKind.CROSSTALK, port, connId, attemptOrdinal, "applied",
          "crosstalk injected " + crossed + " time(s) on this connection");
    }
    peer.onConnectionClosed(this);
  }

  /// Drains one armed connection-scoped fault and writes its single outcome row, or does nothing if
  /// another thread already drained it.
  private void recordArmedConnectionFault(final AtomicReference<Fault> armed,
                                          final String outcome,
                                          final String detail) {
    final var fault = armed.getAndSet(null);
    if (fault != null) {
      peer.applied(fault, connId, attemptOrdinal, detail, outcome);
    }
  }

  /// Writes back whatever `SWAP_ADJACENT_NOTIFY` is still holding. The control borrows a position
  /// in the wire order; it must never swallow a sequence, or a subscription's last consumed sequence
  /// would silently never reach the client and the gapless oracle would be scoring a stream the peer
  /// truncated. A socket that has already gone away cannot take them, and the row says so rather
  /// than leaving the sequence unaccounted for.
  private void flushHeldNotifications() {
    if (heldNotifications.isEmpty()) {
      return;
    }
    writeLock.lock();
    try {
      for (final var subId : List.copyOf(heldNotifications.keySet())) {
        final var held = heldNotifications.remove(subId);
        if (held == null) {
          continue;
        }
        if (closeFrameWritten || out == null || socket.isClosed()) {
          peer.log().ws(port, "notify", connId, attemptOrdinal, held.subId(), held.msgId(), held.seq(),
              held.message().length(), "swapped dropped at close: transport already gone");
          continue;
        }
        try {
          writeMessage(held.message());
          peer.log().ws(port, "notify", connId, attemptOrdinal, held.subId(), held.msgId(), held.seq(),
              held.message().length(), "swapped flushed at close");
        } catch (final IOException ex) {
          peer.log().ws(port, "notify", connId, attemptOrdinal, held.subId(), held.msgId(), held.seq(),
              held.message().length(), "swapped dropped at close: " + ex.getClass().getSimpleName());
        }
      }
    } finally {
      writeLock.unlock();
    }
  }

  private void closeQuietly() {
    try {
      socket.close();
    } catch (final IOException ignored) {
      // Nothing useful remains to be done with a socket that will not close.
    }
  }

  private void capture(final Deque<String> ring, final int opcode, final byte[] payload) {
    final int length = Math.min(payload.length, CAPTURE_BYTES);
    final byte[] slice = length == payload.length ? payload : java.util.Arrays.copyOf(payload, length);
    final var line = opcode + ":" + payload.length + ":" + Stamp.hex(slice);
    synchronized (ring) {
      ring.addLast(line);
      while (ring.size() > CAPTURE_FRAMES) {
        ring.removeFirst();
      }
    }
  }

  private void record(final Fault scheduled,
                      final FaultKind control,
                      final FaultKind kind,
                      final String outcome,
                      final String detail) {
    if (scheduled != null && scheduled.kind() == kind) {
      peer.applied(scheduled, connId, attemptOrdinal, detail, outcome);
      return;
    }
    if (control == kind) {
      peer.appliedControl(kind, port, connId, attemptOrdinal, outcome, detail);
    }
    if (scheduled != null) {
      // A control pre-empted the scheduled fault at this ordinal. Recording it `no_target` keeps
      // the fidelity denominator honest: the fault was reached, it just had nothing left to do.
      peer.applied(scheduled, connId, attemptOrdinal, "pre-empted by control " + control, "no_target");
    }
  }

  private void recordArmed(final String outcome, final String detail) {
    final var fault = armedUnsubFaultRecord;
    this.armedUnsubFaultRecord = null;
    if (fault != null) {
      peer.applied(fault, connId, attemptOrdinal, detail, outcome);
    }
  }

  /// The subscribe identity carried by a `sub_req` row's `fp=` token: fnv64 of `method|params`,
  /// zero-padded to 16 hex digits so the token has one fixed shape whatever the hash is. The input
  /// is the raw request text the client sent (see [#rawParams]), never a reparsed form, because
  /// byte-identical params is the contract the agave dialect dedupes on.
  static String fingerprint(final String dedupeKey) {
    return HexFormat.of().toHexDigits(Stamp.fnv64(dedupeKey));
  }

  // --- tiny JSON helpers ------------------------------------------------------------------------

  private static long readLong(final String text, final String field, final long fallback) {
    try {
      final var ji = JsonIterator.parse(text);
      return ji.skipUntil(field) == null ? fallback : ji.readLong();
    } catch (final RuntimeException ex) {
      return fallback;
    }
  }

  private static String readString(final String text, final String field) {
    try {
      final var ji = JsonIterator.parse(text);
      return ji.skipUntil(field) == null ? null : ji.readString();
    } catch (final RuntimeException ex) {
      return null;
    }
  }

  /// The raw `params` text, byte for byte as the client sent it.
  ///
  /// Byte-identical is the contract the agave dialect dedupes on, so this deliberately does **not**
  /// reparse and re-serialize: a normalising round trip would make two textually different requests
  /// look identical and silently change the dialect under test.
  static String rawParams(final String text) {
    final int at = text.indexOf("\"params\"");
    if (at < 0) {
      return "";
    }
    int i = text.indexOf(':', at + 8);
    if (i < 0) {
      return "";
    }
    ++i;
    while (i < text.length() && text.charAt(i) == ' ') {
      ++i;
    }
    if (i >= text.length()) {
      return "";
    }
    final int start = i;
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (; i < text.length(); ++i) {
      final char c = text.charAt(i);
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          inString = false;
        }
      } else if (c == '"') {
        inString = true;
      } else if (c == '[' || c == '{') {
        ++depth;
      } else if (c == ']' || c == '}') {
        if (--depth == 0) {
          return text.substring(start, i + 1);
        }
      }
    }
    return text.substring(start);
  }

  /// The first element of `params` when it is a number: `<x>Unsubscribe` takes `[<subId>]`.
  static long firstParamLong(final String text) {
    final var params = rawParams(text);
    int i = 0;
    while (i < params.length() && (params.charAt(i) == '[' || params.charAt(i) == ' ')) {
      ++i;
    }
    final int start = i;
    while (i < params.length() && Character.isDigit(params.charAt(i))) {
      ++i;
    }
    if (i == start) {
      return -1;
    }
    try {
      return Long.parseLong(params, start, i, 10);
    } catch (final NumberFormatException ex) {
      return -1;
    }
  }

  /// The base58 key (or `slot` / `root` for the singletons) a subscription was granted for, taken
  /// from the first string in `params` — which is where every one of sava's subscribe templates puts
  /// it except `logsSubscribe`, whose first string is inside `{"mentions":["<key>"]}` and is found
  /// by the same scan.
  static String subscriptionKey(final String method, final String params) {
    final int open = params.indexOf('"');
    if (open < 0) {
      return method.replace("Subscribe", "");
    }
    final int close = params.indexOf('"', open + 1);
    if (close < 0) {
      return method.replace("Subscribe", "");
    }
    final var candidate = params.substring(open + 1, close);
    return "mentions".equals(candidate) ? nextString(params, close + 1, method) : candidate;
  }

  private static String nextString(final String params, final int from, final String method) {
    final int open = params.indexOf('"', from);
    if (open < 0) {
      return method.replace("Subscribe", "");
    }
    final int close = params.indexOf('"', open + 1);
    return close < 0 ? method.replace("Subscribe", "") : params.substring(open + 1, close);
  }
}
