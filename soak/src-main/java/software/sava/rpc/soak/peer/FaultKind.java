package software.sava.rpc.soak.peer;

/// The fault catalogue of `DESIGN.md` §6.
///
/// Every kind names one concrete thing a hostile or broken node does to a client that parses what
/// it sends. The peers inject them on a schedule keyed to the peers' **own** counters, never to a
/// wall clock, so a rerun at the same seed hits the same operations on a busier machine.
///
/// Three attributes drive the schedule and the client's gates:
/// - [#scope()] picks which counter the trigger is keyed on, so a kind can only fire where it means
///   something (a body-truncation fault has nothing to do with a websocket connection ordinal).
/// - [#destructive()] marks the kinds that end a connection; they are capped per hour so they
///   cannot dominate a run.
/// - [#expectedRetirement()] lets the client subtract a retirement the harness *asked for* from the
///   unexplained-retirement gate. Without it every deliberate `TCP_RESET` would read as a defect.
///
/// The last two are different questions and deliberately not the same set. `destructive()` asks
/// whether the per-hour cap has to pay for a row; `expectedRetirement()` asks whether a retirement
/// on that attempt still needs explaining. `SWALLOW_UNSUB` retires a connection through the
/// client's own escalation deadline and costs the cap nothing, while `SLOW_WRITE` and `CROSSTALK`
/// disturb a connection without ending one.
enum FaultKind {

  // --- websocket transport (DESIGN.md §6, scope CONN) -------------------------------------------

  /// `setSoLinger(true, 0)` then close: an abrupt transport death with no close frame.
  TCP_RESET(Scope.CONN, true, true, false, 1),
  /// `shutdownOutput` and keep reading: a one-sided close.
  HALF_CLOSE(Scope.CONN, true, true, false, 1),
  /// A polite close frame, status 1011 reason `soak`.
  CLOSE_FRAME(Scope.CONN, true, true, false, 1),
  /// Answer the upgrade with 500: `buildAsync` fails, so the attempt future settles exceptionally
  /// and no listener callback ever runs.
  HANDSHAKE_500(Scope.CONN, true, true, false, 1),
  /// Accept the socket and send nothing, closing after 30 s: the client's `connectTimeout` is the
  /// only thing that can end this.
  HANDSHAKE_STALL(Scope.CONN, true, true, false, 1),
  /// A wrong `Sec-WebSocket-Accept`: rejected inside the JDK's own handshake.
  HANDSHAKE_BAD_ACCEPT(Scope.CONN, true, true, false, 1),
  /// Reset the connection in the middle of the upgrade.
  HANDSHAKE_RST(Scope.CONN, true, true, false, 1),
  /// 50 ms between the frames of one message: reassembly under a slow producer.
  SLOW_WRITE(Scope.CONN, false, false, false, 3),
  /// Stop accepting for `SOAK_REFUSE_SECONDS`, then reopen on the same port.
  CONNECT_REFUSE(Scope.CONN, true, true, false, 1),
  /// Go silent to draw a ping, never pong it, and stay silent through the engine's response
  /// window: only the client's two-phase ping probe can notice, and it notices only in silence
  /// (a peer frame answers the probe by contract). Phase one holds the stream and the pongs and
  /// still answers requests, re-basing its window behind each answer until the engine idles for a
  /// window and pings (or a budget of windows runs out: `no_target`); phase two holds everything.
  SWALLOW_PING(Scope.CONN, true, true, false, 1),

  // --- websocket protocol (DESIGN.md §6, scope SUB / NOTIFY) ------------------------------------

  /// Receive the subscribe and never answer it.
  SWALLOW_SUBSCRIBE(Scope.SUB, true, true, false, 2),
  /// Confirm, but only after twice the client's resend delay.
  DELAY_CONFIRM(Scope.SUB, false, false, false, 3),
  /// `-32603 "Subscription refused"`, the Agave node-limit shape.
  REJECT_SUBSCRIBE(Scope.SUB, false, false, false, 3),
  /// Send the confirmation twice.
  DUPLICATE_CONFIRMATION(Scope.SUB, false, false, false, 3),
  /// Never acknowledge an unsubscribe.
  ///
  /// Expected-retirement without being destructive: the peer does nothing to the transport, but an
  /// unanswered unsubscribe is an unanswered *request*, and the engine escalates one at four times
  /// its resend delay by aborting the connection — the same deadline and the same outcome as an
  /// unanswered subscribe. With the flag false that retirement was charged to the client as
  /// unexplained. [#destructive()] stays false on purpose: that flag is what the per-hour cap pays
  /// for, and raising it here would thin the kinds that really do break a socket out of the plan to
  /// pay for one the peer never touches.
  SWALLOW_UNSUB(Scope.SUB, false, true, false, 3),
  /// `-32602 "Invalid subscription id."` to a live unsubscribe.
  REFUSE_UNSUB(Scope.SUB, false, false, false, 3),
  /// `-32602` to a well-formed subscribe, which `exceptionSubscribe` should receive.
  ERROR_RESPONSE(Scope.SUB, false, false, false, 3),
  /// Grant an already-live subscription id to a *different* params set: the non-equivalent id
  /// collision the engine treats as connection-fatal.
  DUP_SUBID(Scope.SUB, true, true, false, 1),
  /// One more notification for a subscription id after acknowledging its unsubscribe.
  ///
  /// Bounded per retired id by `WsConnection.ZOMBIE_AMPLIFICATION_BOUND`, which binds an always-on
  /// control rather than this scheduled kind: a schedule reaches the bound for one id only if the
  /// client stops unsubscribing altogether.
  ZOMBIE_NOTIFICATION(Scope.NOTIFY, false, false, false, 2),
  /// A notification for a subscription id that was never granted.
  UNKNOWN_SUB_NOTIFICATION(Scope.NOTIFY, false, false, false, 2),
  /// A TEXT frame with FIN=0 and no continuation, then a normal message.
  ///
  /// Measured 2026-09-21 against `java.net.http.WebSocket`: the JDK's own reader rejects the second
  /// TEXT frame with `java.net.ProtocolException` and closes, so over a real transport this fault
  /// lands **below** sava — it exercises transport rejection and the recovery that follows, not the
  /// engine's reassembly buffer (which `fuzz/ws/dangling_fragment` drives directly). It is therefore
  /// destructive with an expected retirement: classifying it as harmless would have charged every
  /// one of its retirements to the client as unexplained.
  DANGLING_FRAGMENT(Scope.NOTIFY, true, true, false, 2),
  /// One message above the engine's `maxMessageLength`.
  MESSAGE_OVERFLOW(Scope.NOTIFY, true, true, false, 1),

  // --- HTTP (DESIGN.md §6, scope RPC) -----------------------------------------------------------

  /// Headers plus half a body, then hold the socket for 5 minutes.
  STALL_BODY(Scope.RPC, false, false, false, 2),
  /// A full, correct response after a seeded delay in `[50, 6000]` ms.
  DELAY_RESPONSE(Scope.RPC, false, false, false, 4),
  /// 429 with `Retry-After: 1` and a JSON body.
  HTTP_429(Scope.RPC, false, false, false, 3),
  /// 503 with a JSON body.
  HTTP_503(Scope.RPC, false, false, false, 3),
  /// A JSON-RPC `-32602` envelope with HTTP 200.
  RPC_ERROR(Scope.RPC, false, false, false, 3),
  /// `Content-Length: N`, send `N/2`, close.
  TRUNCATE_BODY(Scope.RPC, false, false, false, 2),
  /// `Content-Encoding: gzip` over bytes that are not gzip.
  BAD_GZIP(Scope.RPC, false, false, false, 2),
  /// A valid gzip member followed by trailing garbage.
  GZIP_TRAILING(Scope.RPC, false, false, false, 2),
  /// `Content-Length: 200000000` on a small body: Content-Length is untrusted input.
  BAD_CONTENT_LENGTH(Scope.RPC, false, false, false, 2),
  /// Close the connection before any headers reach the client.
  CONNECTION_DROP(Scope.RPC, false, false, false, 2),
  /// Chunked, one chunk per 200 ms: slow but progressing, and must not be killed early.
  CHUNKED_SLOW(Scope.RPC, false, false, false, 2),
  /// `id` = request id + 1. Observation only: the client records it, nothing asserts on it.
  WRONG_ID_ENVELOPE(Scope.RPC, false, false, false, 2),

  // --- control-only (DESIGN.md §6; never in the weighted fill) ----------------------------------

  /// Deliver a notification on the wrong connection (control `crosstalk`).
  ///
  /// `WsConnection` implements the diversion; the schedule only hands the kind out, through
  /// [FaultSchedule#always(FaultKind.Scope,long)] at CONN scope and only from the second accepted
  /// connection onward, because a connection with no sibling has nowhere to divert to.
  CROSSTALK(Scope.CONN, false, false, true, 0),
  /// Reuse one subscription id across two different params sets, deliberately, for a whole run.
  SUBID_REUSE_ACROSS_PARAMS(Scope.SUB, true, true, true, 0),
  /// Echo a confirmation under a request id that was never sent.
  WRONG_ID_ECHO(Scope.SUB, false, false, true, 0),
  /// Report a `programNotification` under a key the subscription was not granted for (control D4).
  WRONG_KEY_ECHO(Scope.NOTIFY, false, false, true, 0),
  /// Answer every subscribe replayed on a reconnected connection, and record none of it (control
  /// D1): the peer grants an id and sends the confirmation, writes neither a `sub_req` nor a
  /// `sub_ack` row, and never emits for that subscription.
  ///
  /// Neither destructive nor an expected retirement, because it no longer ends a connection. It
  /// used to swallow the replay outright, and an unanswered request is one the engine escalates at
  /// four resend delays by aborting — which superseded the W1-A episode the control exists to stage
  /// before W1-A's own bound, so the control could not fail the property it names. Answering on the
  /// wire while leaving no evidence is the shape of a client that lost the registration, which is
  /// what W1-A asks about. `WsWorkload.EXPECTED_RETIREMENT_KINDS` mirrors this set by name and must
  /// drop this kind with it: a name kept there after the peer stopped retiring connections excuses
  /// a retirement nobody asked for.
  DROP_REPLAYED_SUBSCRIBES(Scope.SUB, false, false, true, 0),
  /// Write every notification twice with the same sequence (control D2).
  DUPLICATE_NOTIFY(Scope.NOTIFY, false, false, true, 0),
  /// Hold one notification and emit it after the next (control D3).
  SWAP_ADJACENT_NOTIFY(Scope.NOTIFY, false, false, true, 0),
  /// Corrupt one byte of one continuation frame (control D5).
  FLIP_CONTINUATION_BYTE(Scope.NOTIFY, false, false, true, 0),
  /// Under the agave dialect, grant a *new* id to a byte-identical repeat subscribe, breaking
  /// dedupe so the client sees two confirmations for one registration (control D6).
  ///
  /// This is the peer-side stand-in for "the client re-sent a subscribe on a live connection",
  /// which is not a thing a server can do to itself. Breaking dedupe alone was not enough: W1-B's
  /// verdict is that one registration was *requested* twice on one connection, and its evidence is
  /// two `sub_req` rows under one id, which only a re-sending client leaves. So the peer also
  /// writes the second row itself — see the `DEDUPE_BREAK` branch of `WsConnection.onSubscribe`.
  DEDUPE_BREAK(Scope.SUB, false, false, true, 0),
  /// Serve a different body under gzip than under identity for the same request (control
  /// `gzip-wrong-twin`), so the client's plain/gzipped differential has something to catch.
  ///
  /// Control-only and RPC-scoped: `RpcPeer` reads it from [FaultSchedule#always(FaultKind.Scope)]
  /// on every request rather than at a seeded ordinal, because a differential oracle handed one
  /// pair per run would prove only that the comparison can fire once.
  GZIP_WRONG_TWIN(Scope.RPC, false, false, true, 0),
  /// Suppress one kind for the whole run, to prove the detector that watches for it can go quiet
  /// (control D12, applied to [#SWALLOW_PING]).
  NEVER_APPLY_KIND(Scope.CONN, false, false, true, 0);

  /// Which of the peers' counters a kind's trigger is keyed on.
  enum Scope {

    /// nth accepted websocket connection on a port.
    CONN("conn"),
    /// nth subscribe request on a port.
    SUB("sub"),
    /// nth notification written on a port.
    NOTIFY("notify"),
    /// nth JSON-RPC request (or nth of one method, via the `rpcMethod:<m>` trigger form).
    RPC("rpc");

    private final String counter;

    Scope(final String counter) {
      this.counter = counter;
    }

    /// The default trigger counter name written into `fault-schedule.tsv`.
    String counter() {
      return counter;
    }
  }

  private final Scope scope;
  private final boolean destructive;
  private final boolean expectedRetirement;
  private final boolean controlOnly;
  private final int weight;

  FaultKind(final Scope scope,
            final boolean destructive,
            final boolean expectedRetirement,
            final boolean controlOnly,
            final int weight) {
    this.scope = scope;
    this.destructive = destructive;
    this.expectedRetirement = expectedRetirement;
    this.controlOnly = controlOnly;
    this.weight = weight;
  }

  Scope scope() {
    return scope;
  }

  boolean destructive() {
    return destructive;
  }

  boolean expectedRetirement() {
    return expectedRetirement;
  }

  boolean controlOnly() {
    return controlOnly;
  }

  /// Relative draw weight in the weighted fill; `0` for control-only kinds, which the fill skips.
  int weight() {
    return weight;
  }
}
