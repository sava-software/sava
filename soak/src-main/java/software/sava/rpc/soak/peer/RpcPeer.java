package software.sava.rpc.soak.peer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.GZIPOutputStream;

/// The controlled JSON-RPC peer (`DESIGN.md` §7): `com.sun.net.httpserver`, HTTP/1.1, a fixed pool,
/// and responses that are **pure functions of the request**.
///
/// Purity is the whole point. Because the account stamp carries `fnv64` of the key the request
/// named, a response that reached the wrong future is detectable from the response alone — which is
/// the request/response association property `W3-A`, and it cannot be asserted against a real node.
///
/// The pool is deliberately fixed and small so peer-side queueing is a *knob the report can name*
/// rather than an unbounded variable that silently reshapes the client's latency distribution.
/// Beyond it, a request is answered 503 and recorded `peer_overflow`: the client's no-wedge
/// property is then measured against a peer that said no, not against one that quietly took
/// forever.
final class RpcPeer {

  private static final int ADMISSION_QUEUE = 256;
  private static final int CHUNKED_SLOW_CHUNKS = 18;
  private static final long CHUNKED_SLOW_GAP_MILLIS = 200L;
  /// The fraction of the HTTP pool `STALL_BODY` may park at once; see [#stallHolds].
  static final int STALL_HOLD_SHARE = 4;
  private static final String JSON = "application/json";
  /// The header the harness's own liveness and survival probes mark themselves with; see
  /// [#isProbe].
  private static final String PROBE_HEADER = "X-Soak-Probe";
  /// The `gzip-wrong-twin` row of `controls.tsv`, and the fault kind its peer half applies; see
  /// [#wrongTwinKind].
  private static final String GZIP_WRONG_TWIN_CONTROL = "gzip-wrong-twin";
  private static final String GZIP_WRONG_TWIN_KIND = "GZIP_WRONG_TWIN";

  private final PeerMain.Config config;
  private final PeerLog log;
  private final Payloads payloads;
  private final List<WsPeer> wsPeers;
  private final Runnable shutdownHook;
  private final HttpServer server;
  private final ThreadPoolExecutor pool;
  /// Concurrent-plus-queued exchanges, counted from submission to the pool until the handler
  /// returns, against [#admissionBound].
  private final AtomicInteger outstanding;
  private final int admissionBound;
  /// How many `STALL_BODY` holds may be parked at once — a quarter of the pool, never all of it.
  ///
  /// A hold lasts [WsPeer#STALL_HOLD_SECONDS] and the peer cannot end it early: the client's
  /// exchange deadline cancels the *client's* side, and none of that reaches this thread. There is
  /// no in-band probe either — a zero-length write to the exchange's fixed-length body stream is
  /// buffered away without a syscall, and even a real write only fails once the local TCP stack has
  /// seen a reset. So holds accumulate: a storm window schedules more of them than the pool has
  /// threads, and a peer whose pool is parked queues every other request, which then lands in the
  /// client's latency distribution as if the *client* had been slow. Bounding the share keeps
  /// `STALL_BODY` one named fault instead of a second, unnamed one against the whole run.
  private final Semaphore stallHolds;
  private final int stallHoldBudget;
  private final AtomicLong rpcCounter;
  private final AtomicLong accountInfoCounter;
  private final LongAdder overflow;
  private final LongAdder faultsSeen;
  private final AtomicBoolean quiesced;
  private final int port;

  private volatile FaultSchedule schedule;

  RpcPeer(final PeerMain.Config config,
          final PeerLog log,
          final Payloads payloads,
          final List<WsPeer> wsPeers,
          final Runnable shutdownHook) throws IOException {
    this.config = config;
    this.log = log;
    this.payloads = payloads;
    this.wsPeers = wsPeers;
    this.shutdownHook = shutdownHook;
    this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), ADMISSION_QUEUE);
    this.port = server.getAddress().getPort();
    final int threads = Math.max(1, config.peerHttpThreads());
    final var counter = new AtomicLong();
    this.pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(ADMISSION_QUEUE * 4), runnable -> {
      final var thread = new Thread(runnable, "peer-http-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }, new ThreadPoolExecutor.CallerRunsPolicy());
    this.admissionBound = threads + ADMISSION_QUEUE;
    this.outstanding = new AtomicInteger();
    this.stallHoldBudget = Math.max(1, threads / STALL_HOLD_SHARE);
    this.stallHolds = new Semaphore(stallHoldBudget);
    this.rpcCounter = new AtomicLong();
    this.accountInfoCounter = new AtomicLong();
    this.overflow = new LongAdder();
    this.faultsSeen = new LongAdder();
    this.quiesced = new AtomicBoolean();
    // Counted at submission, not at handler start: an exchange queued behind the pool's workers
    // is outstanding too, and counting it only once a worker picked it up made the bound below
    // unreachable - the pool's own queue absorbed everything first (measured in review).
    server.setExecutor(task -> {
      outstanding.incrementAndGet();
      pool.execute(() -> {
        try {
          task.run();
        } finally {
          outstanding.decrementAndGet();
        }
      });
    });
    server.createContext("/", this::handleRpc);
    server.createContext("/__soak/", this::handleAdmin);
  }

  int port() {
    return port;
  }

  void start(final FaultSchedule faultSchedule) {
    this.schedule = faultSchedule;
    server.start();
  }

  void shutdown() {
    server.stop(1);
    pool.shutdownNow();
  }

  long rpcRequests() {
    return rpcCounter.get();
  }

  long overflowCount() {
    return overflow.sum();
  }

  long faultsApplied() {
    return faultsSeen.sum();
  }

  // --- JSON-RPC ---------------------------------------------------------------------------------

  private void handleRpc(final HttpExchange exchange) throws IOException {
    // Read before the admission gate so that every row a probe produces says so, including the one
    // it produces when the peer is over its bound: a probe answered 503 is evidence about the peer,
    // and the report has to be able to tell it from the subject's own traffic.
    final boolean probe = isProbe(exchange);
    // Admission control rather than an executor rejection handler: the queue bound has to produce a
    // *JSON* 503 attributable to a named request, and a rejected Runnable has no exchange to answer
    // with. The semaphore caps concurrent-plus-queued exchanges at threads + 256.
    if (outstanding.get() > admissionBound) {
      overflow.increment();
      final long id = -1;
      log.http("overflow", id, -1, 503, 0, "identity", 0, "peer_overflow",
          detail(probe ? "probe" : "", "admission bound reached"));
      sendFixed(exchange, 503, Payloads.rpcError(id, -32005, "Peer overflow").getBytes(StandardCharsets.UTF_8),
          null, -1);
      return;
    }
    try {
      final byte[] requestBody = exchange.getRequestBody().readAllBytes();
      final var body = new String(requestBody, StandardCharsets.UTF_8);
      final long requestId = longMember(body, "id");
      final var method = stringMember(body, "method");
      final long seq = rpcCounter.incrementAndGet();
      final long methodSeq = "getAccountInfo".equals(method) ? accountInfoCounter.incrementAndGet() : -1;
      final var scheduled = method == null ? null : schedule.takeRpc(port, method, seq, methodSeq);
      // A probe is the harness asking whether the peer is still answering — W3-D's no-wedge question
      // and W5-B1's caller-owned-client question are each one ordinary request. Faulting one measures
      // the fault instead of the property, so the trigger is withheld. It is still *consumed* and
      // written `no_target`: leaving it armed would strand it at an ordinal the counter has already
      // passed, which the report reads as reached-and-never-applied and fails the run over a fault
      // the peer deliberately did not inject. `no_target` is the existing spelling for "the ordinal
      // came up with nothing legitimate to break", and it leaves fault fidelity's denominator honest.
      final boolean withheld = scheduled != null && probe;
      if (withheld) {
        log.faultApplied(scheduled, -1, -1, "no_target",
            "probe: " + PROBE_HEADER + " request, not subject traffic");
      }
      final var fault = withheld ? null : scheduled;
      if (fault != null) {
        faultsSeen.increment();
      }
      answer(exchange, method, body, requestId, seq, fault, probe);
    } finally {
      exchange.close();
    }
  }

  private void answer(final HttpExchange exchange,
                      final String method,
                      final String body,
                      final long requestId,
                      final long seq,
                      final Fault fault,
                      final boolean probe) throws IOException {
    final var kind = fault == null ? null : fault.kind();
    final long envelopeId = kind == FaultKind.WRONG_ID_ENVELOPE ? requestId + 1 : requestId;
    if (kind == FaultKind.WRONG_ID_ENVELOPE) {
      applied(fault, "response id " + envelopeId + " for request " + requestId);
    }

    if (kind == FaultKind.CONNECTION_DROP) {
      applied(fault, "closed before any headers");
      log.http(method, requestId, seq, -1, 0, "identity", 0, kind.name(), "connection dropped mid-headers");
      exchange.close();
      return;
    }
    if (kind == FaultKind.HTTP_429 || kind == FaultKind.HTTP_503) {
      final int status = kind == FaultKind.HTTP_429 ? 429 : 503;
      final byte[] payload = Payloads.rpcError(envelopeId, -32005, status + " from the soak peer")
          .getBytes(StandardCharsets.UTF_8);
      if (kind == FaultKind.HTTP_429) {
        exchange.getResponseHeaders().add("Retry-After", "1");
      }
      applied(fault, "status " + status);
      sendFixed(exchange, status, payload, null, seq);
      log.http(method, requestId, seq, status, payload.length, "identity", 0, kind.name(), "");
      return;
    }
    if (kind == FaultKind.RPC_ERROR) {
      final byte[] payload = Payloads.rpcError(envelopeId, -32602, "Invalid params").getBytes(StandardCharsets.UTF_8);
      applied(fault, "-32602 with HTTP 200");
      sendFixed(exchange, 200, payload, null, seq);
      log.http(method, requestId, seq, 200, payload.length, "identity", 0, kind.name(), "");
      return;
    }

    long delayMillis = 0;
    if (kind == FaultKind.DELAY_RESPONSE) {
      delayMillis = parseParam(fault.params(), "delayMillis", 50);
      applied(fault, "response held " + delayMillis + "ms");
      WsFrames.pause(delayMillis);
    }

    final byte[] json = render(method, body, envelopeId, seq).getBytes(StandardCharsets.UTF_8);

    if (kind == FaultKind.TRUNCATE_BODY) {
      applied(fault, "Content-Length " + json.length + ", sent " + (json.length / 2));
      exchange.getResponseHeaders().set("Content-Type", JSON);
      exchange.getResponseHeaders().set("X-Soak-Seq", Long.toString(seq));
      exchange.sendResponseHeaders(200, json.length);
      try (final var out = exchange.getResponseBody()) {
        out.write(json, 0, json.length / 2);
        out.flush();
      } catch (final IOException ignored) {
        // Truncating a fixed-length body is expected to upset the server's own bookkeeping; the
        // client's view — a short body under a longer Content-Length — is what the fault is for.
      }
      log.http(method, requestId, seq, 200, json.length / 2L, "identity", delayMillis, kind.name(), "");
      return;
    }
    if (kind == FaultKind.BAD_CONTENT_LENGTH) {
      applied(fault, "Content-Length 200000000 on " + json.length + " bytes");
      exchange.getResponseHeaders().set("Content-Type", JSON);
      exchange.getResponseHeaders().set("X-Soak-Seq", Long.toString(seq));
      exchange.sendResponseHeaders(200, 200_000_000L);
      try (final var out = exchange.getResponseBody()) {
        out.write(json);
        out.flush();
      } catch (final IOException ignored) {
        // Same shape as above: Content-Length is untrusted input and this is what untrusted looks
        // like.
      }
      log.http(method, requestId, seq, 200, json.length, "identity", delayMillis, kind.name(),
          "declared 200000000");
      return;
    }
    // `STALL_BODY` is the one fault that costs the peer a thread for minutes, so it is the one the
    // peer rations: over budget it is not injected at all. A skipped trigger deliberately writes no
    // `faults-applied.tsv` row, which is this harness's spelling of *reached and never applied* — it
    // counts against fault fidelity instead of being subtracted from its denominator, and the reason
    // rides on the ordinary response that goes out instead, in `peer-http.tsv`'s detail column.
    String stallSkipped = "";
    if (kind == FaultKind.STALL_BODY) {
      if (stallHolds.tryAcquire()) {
        try {
          stall(exchange, fault, method, json, requestId, seq, delayMillis);
        } finally {
          stallHolds.release();
        }
        return;
      }
      stallSkipped = "hold skipped: " + stallHoldBudget + " stall hold(s) already parked";
    }
    if (kind == FaultKind.CHUNKED_SLOW) {
      applied(fault, CHUNKED_SLOW_CHUNKS + " chunks, one per " + CHUNKED_SLOW_GAP_MILLIS + "ms");
      sendChunkedSlow(exchange, json, seq);
      log.http(method, requestId, seq, 200, json.length, "chunked", delayMillis, kind.name(), "");
      return;
    }
    if (kind == FaultKind.BAD_GZIP) {
      final byte[] garbage = "{\"not\":\"gzip at all\"}".getBytes(StandardCharsets.UTF_8);
      applied(fault, "Content-Encoding: gzip over " + garbage.length + " bytes that are not gzip");
      sendFixed(exchange, 200, garbage, "gzip", seq);
      log.http(method, requestId, seq, 200, garbage.length, "gzip", delayMillis, kind.name(), "");
      return;
    }
    if (kind == FaultKind.GZIP_TRAILING) {
      final byte[] gzipped = gzip(json);
      final byte[] withTrailer = new byte[gzipped.length + 16];
      System.arraycopy(gzipped, 0, withTrailer, 0, gzipped.length);
      for (int i = gzipped.length; i < withTrailer.length; ++i) {
        withTrailer[i] = (byte) (0x5A + (i & 0x0F));
      }
      applied(fault, "valid gzip member plus 16 trailing bytes");
      sendFixed(exchange, 200, withTrailer, "gzip", seq);
      log.http(method, requestId, seq, 200, withTrailer.length, "gzip", delayMillis, kind.name(), "");
      return;
    }

    // Encoding rotation (DESIGN.md §7): gzip, x-gzip, "identity, gzip", plain by rpc seq mod 4. The
    // third case is the one that matters — gzip must be honoured anywhere in the list, not only as
    // the whole header value.
    final boolean acceptsGzip = acceptsGzip(exchange);
    final int rotation = (int) Math.floorMod(seq, 4L);
    final String encodingHeader;
    final byte[] payload;
    String wrongTwinDetail = "";
    if (acceptsGzip && rotation != 3) {
      encodingHeader = switch (rotation) {
        case 0 -> "gzip";
        case 1 -> "x-gzip";
        default -> "identity, gzip";
      };
      // The `gzip-wrong-twin` control: the *gzip* representation of a getAccountInfo answer
      // disagrees with the identity representation the client twins it against, so W3-C's
      // plain-vs-gzip projection has to fail rather than both halves merely parsing. Exactly one
      // member of that projection moves (`Payloads.WRONG_TWIN_SPACE_DELTA` says which and why), so
      // the control names the one property `controls.tsv` says it names. A probe is exempt for the
      // same reason it is exempt from the schedule: it is the harness measuring, not the workload.
      if (!probe && "getAccountInfo".equals(method) && wrongTwinSelected(seq)) {
        final var pubKey = firstString(WsConnection.rawParams(body), Stamp.OWNER);
        payload = gzip(payloads.getAccountInfo(envelopeId, seq, pubKey, Payloads.WRONG_TWIN_SPACE_DELTA)
            .getBytes(StandardCharsets.UTF_8));
        wrongTwinDetail = "gzip body reports space " + Payloads.WRONG_TWIN_SPACE_DELTA
            + " above the identity twin's for " + pubKey;
        final var controlKind = wrongTwinKind(seq);
        if (controlKind != null) {
          appliedControl(controlKind, wrongTwinDetail);
        }
      } else {
        payload = gzip(json);
      }
    } else {
      encodingHeader = null;
      payload = json;
    }
    final String faultColumn;
    if (kind != null) {
      faultColumn = kind.name();
    } else if (!wrongTwinDetail.isEmpty()) {
      faultColumn = GZIP_WRONG_TWIN_KIND;
    } else {
      faultColumn = "-";
    }
    sendFixed(exchange, 200, payload, encodingHeader, seq);
    log.http(method, requestId, seq, 200, payload.length,
        encodingHeader == null ? "identity" : encodingHeader, delayMillis, faultColumn,
        detail(probe ? "probe" : "", stallSkipped, wrongTwinDetail));
  }

  /// True when the request is one of the harness's own liveness or survival probes.
  ///
  /// The probes are the harness measuring the subject, not the subject's workload, so the peer owes
  /// them its ordinary behaviour: a scheduled fault landing on a W3-D wedge probe or a W5-B1
  /// survival probe is recorded as the subject wedging or as a caller-owned client that did not
  /// survive, and the run then fails on the harness's own instrumentation. Any non-blank value
  /// counts — a probe that spelled the header `true` is still a probe.
  private static boolean isProbe(final HttpExchange exchange) {
    final var value = exchange.getRequestHeaders().getFirst(PROBE_HEADER);
    return value != null && !value.isBlank();
  }

  /// The `gzip-wrong-twin` kind as the schedule's RPC-scope control seam names it, or null when the
  /// seam does not carry it.
  ///
  /// The seam is read by *name* rather than through a compile-time `FaultKind` constant because
  /// this control has halves in two files: the catalogue half (a `FaultKind` constant plus a
  /// `controlFor` case) and this peer half. Reading the name makes the seam authoritative the
  /// moment the constant exists, without the peer half having to wait for it.
  ///
  /// `seq` — the peer's own request ordinal — stands in for the connection ordinal the seam takes,
  /// which is `Control.minConnOrdinal`'s "apply from the nth event onward"; the HTTP peer has no
  /// connections to count. A literal `0` there would suppress every control, because the record's
  /// default minimum is 1.
  private FaultKind wrongTwinKind(final long seq) {
    final var always = schedule.always(FaultKind.Scope.RPC, seq);
    return always != null && GZIP_WRONG_TWIN_KIND.equals(always.name()) ? always : null;
  }

  /// Whether the run selected `gzip-wrong-twin`, by the seam or by the control id.
  ///
  /// The id is the second route so that the `controls.tsv` row asserts something today: a control
  /// that is listed and inert is worse than one that is absent, because the sheet reads green.
  private boolean wrongTwinSelected(final long seq) {
    return wrongTwinKind(seq) != null || GZIP_WRONG_TWIN_CONTROL.equals(schedule.control().id());
  }

  /// A control-only injection has no scheduled row, so `faults-applied.tsv` records it at ordinal 0
  /// (`DESIGN.md` §6), as `WsPeer.appliedControl` does for the websocket peer.
  ///
  /// It can only be written once the seam hands back a [FaultKind], because that file names a kind
  /// through a [Fault]. Until then the injection is still stated on the response's own
  /// `peer-http.tsv` row, whose `faultKind` column is text — the run says what it did either way.
  private void appliedControl(final FaultKind kind, final String faultDetail) {
    log.faultApplied(new Fault(0, kind, port, "control", 0, "-"), -1, -1, "applied", faultDetail);
  }

  /// Joins the reasons one response has to state into a single `detail` column, skipping the empty
  /// ones, so a probe that also met a rationed stall says both rather than the last one written.
  private static String detail(final String... parts) {
    final var sb = new StringBuilder(64);
    for (final var part : parts) {
      if (part != null && !part.isEmpty()) {
        if (!sb.isEmpty()) {
          sb.append("; ");
        }
        sb.append(part);
      }
    }
    return sb.toString();
  }

  private static boolean acceptsGzip(final HttpExchange exchange) {
    final var values = exchange.getRequestHeaders().get("Accept-Encoding");
    if (values == null) {
      return false;
    }
    for (final var value : values) {
      if (value.toLowerCase(java.util.Locale.ROOT).contains("gzip")) {
        return true;
      }
    }
    return false;
  }

  private void applied(final Fault fault, final String detail) {
    log.faultApplied(fault, -1, -1, "applied", detail);
  }

  private static long parseParam(final String params, final String name, final long fallback) {
    final int at = params.indexOf(name + '=');
    if (at < 0) {
      return fallback;
    }
    int end = at + name.length() + 1;
    while (end < params.length() && Character.isDigit(params.charAt(end))) {
      ++end;
    }
    try {
      return Long.parseLong(params, at + name.length() + 1, end, 10);
    } catch (final NumberFormatException ex) {
      return fallback;
    }
  }

  // --- the method table -------------------------------------------------------------------------

  private String render(final String method, final String body, final long requestId, final long seq) {
    if (method == null) {
      return Payloads.rpcError(requestId, -32600, "Invalid Request");
    }
    final var params = WsConnection.rawParams(body);
    return switch (method) {
      case "getAccountInfo" -> payloads.getAccountInfo(requestId, seq, firstString(params, Stamp.OWNER));
      case "getMultipleAccounts", "getAccounts" ->
          payloads.getMultipleAccounts(requestId, seq, stringArray(params, Stamp.OWNER));
      case "getProgramAccounts" ->
          payloads.getProgramAccounts(requestId, seq, firstString(params, Stamp.OWNER), payloads.pgaAccounts());
      case "getBlock" -> payloads.getBlock(requestId, firstLong(params, Stamp.BASE_SLOT + seq), payloads.blockTxs());
      case "getSignatureStatuses" ->
          payloads.getSignatureStatuses(requestId, seq, stringArray(params, Stamp.signature(seq)));
      case "getLatestBlockhash", "getLatestBlockHash" -> payloads.getLatestBlockHash(requestId, seq);
      case "getSlot" -> payloads.getSlot(requestId, seq);
      case "getBlockHeight" -> payloads.getBlockHeight(requestId, seq);
      case "getEpochInfo" -> payloads.getEpochInfo(requestId, seq);
      case "getVersion" -> payloads.getVersion(requestId);
      case "getHealth" -> payloads.getHealth(requestId);
      default -> Payloads.rpcError(requestId, -32601, "Method not found");
    };
  }

  private static String firstString(final String params, final String fallback) {
    try {
      final var ji = JsonIterator.parse(params);
      if (ji.readArray() && ji.whatIsNext() == ValueType.STRING) {
        final var value = ji.readString();
        return value == null || value.isEmpty() ? fallback : value;
      }
    } catch (final RuntimeException ignored) {
      // A request the peer cannot read is answered from the fallback rather than by throwing: the
      // client's own error handling is what this peer exists to exercise.
    }
    return fallback;
  }

  private static long firstLong(final String params, final long fallback) {
    try {
      final var ji = JsonIterator.parse(params);
      if (ji.readArray() && ji.whatIsNext() == ValueType.NUMBER) {
        return ji.readLong();
      }
    } catch (final RuntimeException ignored) {
      // See firstString.
    }
    return fallback;
  }

  private static List<String> stringArray(final String params, final String fallback) {
    final var values = new ArrayList<String>();
    try {
      final var ji = JsonIterator.parse(params);
      if (ji.readArray() && ji.whatIsNext() == ValueType.ARRAY) {
        while (ji.readArray()) {
          values.add(ji.readString());
        }
      }
    } catch (final RuntimeException ignored) {
      // See firstString.
    }
    if (values.isEmpty()) {
      values.add(fallback);
    }
    return values;
  }

  // --- response plumbing ------------------------------------------------------------------------

  private void sendFixed(final HttpExchange exchange,
                         final int status,
                         final byte[] payload,
                         final String contentEncoding,
                         final long seq) throws IOException {
    final var headers = exchange.getResponseHeaders();
    headers.set("Content-Type", JSON);
    if (seq >= 0) {
      headers.set("X-Soak-Seq", Long.toString(seq));
    }
    if (contentEncoding != null) {
      headers.set("Content-Encoding", contentEncoding);
    }
    exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
    if (payload.length > 0) {
      try (final var out = exchange.getResponseBody()) {
        out.write(payload);
        out.flush();
      }
    }
  }

  /// Headers, half a body, then a hold the *client's* deadline — not the peer's patience — is meant
  /// to end, so [WsPeer#STALL_HOLD_SECONDS] stays far longer than any deadline the client can carry.
  ///
  /// The caller holds a [#stallHolds] permit for the whole hold, and the `peer-http.tsv` row is
  /// written **before** it: a stall has no honest completion timestamp, and logging it afterwards
  /// dates every stall at the peer's shutdown — the one moment that says nothing about when the
  /// fault landed. Only interruption (`pool.shutdownNow()` at shutdown) ends the hold early.
  private void stall(final HttpExchange exchange,
                     final Fault fault,
                     final String method,
                     final byte[] json,
                     final long requestId,
                     final long seq,
                     final long delayMillis) throws IOException {
    applied(fault, "half a body then a " + WsPeer.STALL_HOLD_SECONDS + "s hold");
    exchange.getResponseHeaders().set("Content-Type", JSON);
    exchange.getResponseHeaders().set("X-Soak-Seq", Long.toString(seq));
    exchange.sendResponseHeaders(200, json.length);
    log.http(method, requestId, seq, 200, json.length / 2L, "identity", delayMillis,
        FaultKind.STALL_BODY.name(), "hold of " + WsPeer.STALL_HOLD_SECONDS + "s starts at this row");
    try (final var out = exchange.getResponseBody()) {
      out.write(json, 0, json.length / 2);
      out.flush();
      WsFrames.pause(TimeUnit.SECONDS.toMillis(WsPeer.STALL_HOLD_SECONDS));
    } catch (final IOException ignored) {
      // The client's exchange deadline is expected to end this from the other side. The peer only
      // learns of it if a later write reaches a reset socket, which is why the hold is rationed
      // rather than probed.
    }
  }

  /// Chunked, one chunk per 200 ms, under 4 s in total: a body that is slow but *progressing*, which
  /// must not be killed early.
  private void sendChunkedSlow(final HttpExchange exchange, final byte[] payload, final long seq) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", JSON);
    exchange.getResponseHeaders().set("X-Soak-Seq", Long.toString(seq));
    exchange.sendResponseHeaders(200, 0);
    final int chunk = Math.max(1, (payload.length + CHUNKED_SLOW_CHUNKS - 1) / CHUNKED_SLOW_CHUNKS);
    try (final OutputStream out = exchange.getResponseBody()) {
      for (int at = 0; at < payload.length; at += chunk) {
        if (at > 0) {
          WsFrames.pause(CHUNKED_SLOW_GAP_MILLIS);
        }
        out.write(payload, at, Math.min(chunk, payload.length - at));
        out.flush();
      }
    }
  }

  private static byte[] gzip(final byte[] payload) {
    final var buffer = new ByteArrayOutputStream(Math.max(64, payload.length / 4));
    try (final var gzip = new GZIPOutputStream(buffer)) {
      gzip.write(payload);
    } catch (final IOException ex) {
      throw new java.io.UncheckedIOException(ex);
    }
    return buffer.toByteArray();
  }

  // --- admin ------------------------------------------------------------------------------------

  /// `/__soak/*` is for `soak.sh` and the harness's `PeerAdmin` only, never the client: a route the
  /// subject called would put harness traffic into the subject's own timeline.
  private void handleAdmin(final HttpExchange exchange) throws IOException {
    try {
      final var path = exchange.getRequestURI().getPath();
      final var route = path.substring(path.lastIndexOf('/') + 1);
      switch (route) {
        case "stats" -> sendFixed(exchange, 200, stats().getBytes(StandardCharsets.UTF_8), null, -1);
        case "quiesce" -> {
          quiesced.set(true);
          for (final var peer : wsPeers) {
            peer.quiesce();
          }
          sendFixed(exchange, 200, "{\"quiesced\":true}".getBytes(StandardCharsets.UTF_8), null, -1);
        }
        case "flush" -> {
          log.flush();
          sendFixed(exchange, 200, "{\"flushed\":true}".getBytes(StandardCharsets.UTF_8), null, -1);
        }
        case "capture" -> sendFixed(exchange, 200, capture(exchange.getRequestURI().getQuery())
            .getBytes(StandardCharsets.UTF_8), null, -1);
        case "shutdown" -> {
          sendFixed(exchange, 200, "{\"shutdown\":true}".getBytes(StandardCharsets.UTF_8), null, -1);
          shutdownHook.run();
        }
        default -> sendFixed(exchange, 404, "{\"error\":\"unknown admin route\"}".getBytes(StandardCharsets.UTF_8),
            null, -1);
      }
    } finally {
      exchange.close();
    }
  }

  private String capture(final String query) {
    int capturePort = -1;
    long conn = -1;
    if (query != null) {
      for (final var pair : query.split("&")) {
        final int eq = pair.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        final var name = pair.substring(0, eq);
        final var value = pair.substring(eq + 1);
        try {
          if ("port".equals(name)) {
            capturePort = Integer.parseInt(value);
          } else if ("conn".equals(name)) {
            conn = Long.parseLong(value);
          }
        } catch (final NumberFormatException ignored) {
          // A malformed admin query answers an empty capture rather than failing the runner.
        }
      }
    }
    final var frames = new ArrayList<String>();
    for (final var peer : wsPeers) {
      if (capturePort < 0 || peer.port() == capturePort) {
        frames.addAll(peer.capture(conn));
      }
    }
    final var sb = new StringBuilder(256 + frames.size() * 64);
    sb.append("{\"port\":").append(capturePort).append(",\"conn\":").append(conn).append(",\"frames\":[");
    for (int i = 0; i < frames.size(); ++i) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(frames.get(i)).append('"');
    }
    return sb.append("]}").toString();
  }

  String stats() {
    final var sb = new StringBuilder(512);
    sb.append("{\"httpPort\":").append(port)
        .append(",\"rpcRequests\":").append(rpcCounter.get())
        // the `rpcMethod:getAccountInfo` trigger counter, so the report can tell a scheduled
        // fault the traffic never reached from one that was reached and not applied
        .append(",\"accountInfoRequests\":").append(accountInfoCounter.get())
        .append(",\"peerOverflow\":").append(overflow.sum())
        .append(",\"logDropped\":").append(log.dropped())
        .append(",\"logMaxLagMs\":").append(log.maxLagMillis())
        .append(",\"quiesced\":").append(quiesced.get())
        .append(",\"wsPorts\":[");
    for (int i = 0; i < wsPeers.size(); ++i) {
      final var peer = wsPeers.get(i);
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"port\":").append(peer.port())
          .append(",\"dialect\":\"").append(peer.dialect().wireName())
          .append("\",\"idPolicy\":\"").append(peer.dialect().idPolicy())
          .append("\",\"acceptedConnections\":").append(peer.acceptedConnections())
          .append(",\"openConnections\":").append(peer.connections())
          .append(",\"liveSubscriptions\":").append(peer.liveSubscriptions())
          .append(",\"subscribeRequests\":").append(peer.subscribeRequests())
          .append(",\"notifications\":").append(peer.notificationsWritten())
          .append('}');
    }
    return sb.append("]}").toString();
  }

  // --- tiny JSON helpers ------------------------------------------------------------------------

  private static long longMember(final String text, final String field) {
    try {
      final var ji = JsonIterator.parse(text);
      return ji.skipUntil(field) == null ? -1 : ji.readLong();
    } catch (final RuntimeException ex) {
      return -1;
    }
  }

  private static String stringMember(final String text, final String field) {
    try {
      final var ji = JsonIterator.parse(text);
      return ji.skipUntil(field) == null ? null : ji.readString();
    } catch (final RuntimeException ex) {
      return null;
    }
  }
}
