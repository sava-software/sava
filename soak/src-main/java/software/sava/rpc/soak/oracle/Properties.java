package software.sava.rpc.soak.oracle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The fixed property table. Every id the harness can pass or fail is declared here once, with
/// the statement, the guarantee that licenses it and the bound the evaluation applies.
///
/// It is a table rather than a set of string literals scattered through the workloads for two
/// reasons: `properties.tsv` must list a row for a property that was never evaluated (an
/// unexercised grade-A property is a FAIL, and a missing row would read as a pass), and the
/// guarantee text has to travel with the id into the finding note, where the reader deciding
/// whether sava has a bug is not the person who wrote the assertion.
public final class Properties {

  public static final Property W1_A = new Property(
      "W1-A",
      Grade.A_PUBLIC_CONTRACT,
      "Every registration live at retirement, and not unsubscribed since, is re-sent as a subscribe"
          + " request on the next adopted connection.",
      "SolanaRpcWebsocket.subscribe javadoc, \"Subscriptions are replayed if the connection is"
          + " re-connected\"; SolanaRpcWebsocketBuilder.subscriptionResendDelay javadoc, \"This delay"
          + " also paces replay after a reconnect\". The harness owns the registration set and the peer"
          + " records, per connection, the exact set of (method, params) subscribes it received."
          + " A registration whose subscribe the server rejected with a code SolanaJsonRpcWebsocket's"
          + " isRequestDefect classes as terminal (INVALID_REQUEST, METHOD_NOT_FOUND, INVALID_PARAMS)"
          + " is excluded: that branch removes the pending subscription and frees its registry slot"
          + " because \"re-sending the same frame can only collect the same answer\", so the engine"
          + " promises it no replay. -32603 and its kin are the server's condition rather than the"
          + " request's, stay pending, and are owed one.",
      "connectTimeout + reConnectDelay + 4 x subscriptionResendDelay + 2 s; a live registration still"
          + " absent past the bound is a FAIL.");

  public static final Property W1_B = new Property(
      "W1-B",
      Grade.A_PUBLIC_CONTRACT,
      "A successfully sent request is never re-sent on its own connection.",
      "subscriptionResendDelay javadoc: \"A successfully sent request is never re-sent on its own"
          + " connection: JSON-RPC ids correlate responses, they do not deduplicate calls\". The peer"
          + " knows it received the first one, which is exactly the \"successfully sent\" condition.",
      "No two subscribe requests with byte-identical params on one connection without an intervening"
          + " unsubscribe.");

  public static final Property W1_C = new Property(
      "W1-C",
      Grade.A_PUBLIC_CONTRACT,
      "A sent-but-never-answered request escalates and replaces the connection.",
      "subscriptionResendDelay javadoc: \"and, times four, the deadline after which a"
          + " sent-but-never-answered request replaces the connection\". SWALLOW_SUBSCRIBE at a peer"
          + " ordinal makes the peer the oracle: it observes its own socket closed by the client.",
      "4 x subscriptionResendDelay + subscriptionAndPingCheckDelay + 2 s; FAIL if not retired by twice"
          + " that. Also asserts W1-B held for the swallowed request.");

  public static final Property W1_D = new Property(
      "W1-D",
      Grade.B_DOCUMENTED_INVARIANT,
      "A cancelled registration receives nothing.",
      "SolanaJsonRpcWebsocket's retiredSubIds / killedSubIds tombstones; peer-established in effect,"
          + " because ZOMBIE_NOTIFICATION sends a notification the harness knows the peer emitted for a"
          + " subId already acked as unsubscribed.",
      "The consumer must not be invoked at all; one invocation is a FAIL.");

  public static final Property W1_E = new Property(
      "W1-E",
      Grade.C_PEER_ESTABLISHED,
      "Per-key sequence is gapless and duplicate-free within one connection epoch, and monotone"
          + " across epochs.",
      "The peer assigns the sequence under the connection's write lock immediately before the bytes"
          + " are written, so a gap cannot be peer-caused.",
      "seq == last + 1 within an epoch; seq <= last is a duplicate or reorder FAIL; at an epoch"
          + " boundary the expectation resets to monotone with any gap allowed, because NP-1 declines to"
          + " assert delivery across a disconnect.");

  public static final Property W1_F = new Property(
      "W1-F",
      Grade.B_DOCUMENTED_INVARIANT,
      "An unknown-subscription notification is dropped without retiring the connection.",
      "The engine's documented handling of a notification whose subId it does not know;"
          + " UNKNOWN_SUB_NOTIFICATION is emitted by the controlled peer, so the input is established.",
      "FAIL if the connection retires within 2 s of it, or if any consumer is invoked.");

  public static final Property W1_G = new Property(
      "W1-G",
      Grade.A_PUBLIC_CONTRACT,
      "lastMessageReceivedTimestamp() is non-decreasing within an epoch and 0 only before the first"
          + " message.",
      "Its javadoc: the epoch-millis timestamp of the last complete message on the current"
          + " connection, 0 meaning no evidence.",
      "Sampled by the gauge sampler and after each delivery on one designated engine; a decrease"
          + " within an epoch, or a non-zero value before the epoch's first message, is a FAIL.");

  public static final Property W1_H = new Property(
      "W1-H",
      Grade.A_PUBLIC_CONTRACT,
      "Ping and keep-alive windows are honoured, and a swallowed ping retires the connection.",
      "pingDelay javadoc (a send window and a response window, each pingDelay) and keepAliveDelay;"
          + " the peer counts inbound Pings with timestamps.",
      "(i) with the peer silent for more than pingDelay, a Ping arrives within pingDelay +"
          + " subscriptionAndPingCheckDelay + 2 s; (ii) under SWALLOW_PING the connection is retired"
          + " within 2 x pingDelay + subscriptionAndPingCheckDelay + 2 s and onError fires; which"
          + " detector retired it (the probe's own deadline, or the unanswered-request deadline the"
          + " same silence starves) is counted, not judged.");

  public static final Property W1_I = new Property(
      "W1-I",
      Grade.A_PUBLIC_CONTRACT,
      "close() is terminal and bounded.",
      "close() javadoc: \"A close frame is sent politely, and the transport is aborted a few seconds"
          + " later\"; connect() returns null once closed and subscribe calls return false.",
      "After close(): connect() returns null, every subscribe returns false, and the peer observes the"
          + " socket closed within 10 s. FAIL on any of the three.");

  public static final Property W1_J = new Property(
      "W1-J",
      Grade.C_PEER_ESTABLISHED,
      "A program notification carries the pubkey the subscription was granted for.",
      "The peer writes into a programNotification's value.pubkey the program key the"
          + " subscription was granted for - the first string of the subscribe request's params,"
          + " echoed back by the rule DESIGN section 7 fixes for that channel (not"
          + " Stamp.derivedKey, which is the HTTP getProgramAccounts rule) - and logs the"
          + " notification it wrote, so the expected key is peer-established and not inferred from"
          + " sava. The engine forwards value.pubkey into AccountInfo.pubKey(), which is the only"
          + " place a consumer can read it.",
      "Every program-channel delivery carries a non-null AccountInfo.pubKey() holding the key the"
          + " peer derived for that subscription; any other value, null included, is a FAIL naming the"
          + " engine, the subscription and the sequence. WRONG_KEY_ECHO is this property's negative"
          + " control - it echoes a key no subscription was granted - so a run that applies it and"
          + " does not fail W1-J has an oracle that is reading nothing.");

  public static final Property W2_A = new Property(
      "W2-A",
      Grade.A_PUBLIC_CONTRACT,
      "Fragment reassembly is byte-exact.",
      "The peer knows the intended payload and the notification carries an FNV-1a checksum over its"
          + " own body, so the expected bytes are peer-established rather than inferred.",
      "Checksum equality on every LARGE message and on every HEAVY consumer delivery; a mismatch is a"
          + " FAIL with the frame capture attached.");

  public static final Property W2_B = new Property(
      "W2-B",
      Grade.A_PUBLIC_CONTRACT,
      "maxMessageLength aborts the connection, reports through onError, and does not OOM.",
      "maxMessageLength javadoc: \"A message the cap excludes aborts the connection and surfaces"
          + " through onError\".",
      "Exactly one onError and one retirement per MESSAGE_OVERFLOW; used heap after the next GC no"
          + " more than the cap in chars x 2 above the pre-fault floor. FAIL if no onError, if the"
          + " connection survives, or on any OutOfMemoryError.");

  public static final Property W2_C = new Property(
      "W2-C",
      Grade.A_PUBLIC_CONTRACT,
      "A throwing consumer is contained.",
      "exceptionSubscribe javadoc: \"Each subscriber is contained - one subscriber throwing does not"
          + " starve the rest.\"",
      "With THROWING consumers on 10 % of keys, every FAST key's sequence stays gapless within the"
          + " epoch and no retirement correlates with a throw.");

  public static final Property W2_D = new Property(
      "W2-D",
      Grade.D_MEASUREMENT,
      "Slow-consumer coupling: what a slow consumer costs the fast consumers sharing its connection.",
      "Measurement only. The engine dispatches on the listener thread, so head-of-line coupling is"
          + " expected; NP-3 declines to assert independence. Coupling the javadoc does not warn about"
          + " is a documentation finding to report, never a behaviour change.",
      "NotificationDelivered p50/p99 for FAST keys on connections that also carry SLOW keys, versus"
          + " connections that do not. Never a FAIL.");

  public static final Property W3_A = new Property(
      "W3-A",
      Grade.A_PUBLIC_CONTRACT,
      "Request/response association holds while responses complete out of order.",
      "JSON-RPC id correlation, which sava implements and callers rely on. Every response carries a"
          + " stamp derived from the request's own arguments, and DELAY_RESPONSE guarantees"
          + " out-of-order completion.",
      "Every parsed response's stamp matches the arguments of the request it answers; any mismatch is"
          + " a FAIL.");

  public static final Property W3_B = new Property(
      "W3-B",
      Grade.B_DOCUMENTED_INVARIANT,
      "A default GET/POST route completes within the exchange deadline.",
      "JsonHttpClient's withResponseDeadline bounds the whole exchange at 2 x requestTimeout, and"
          + " sava-rpc's own tests already pin it.",
      "Under STALL_BODY the future settles (exceptionally) by the exchange deadline"
          + " (2 x requestTimeout) plus 2 s of slack, and FAIL past that bound - the bound the ledger"
          + " publishes is the one it enforces. An operation whose own harness bound expires before"
          + " the client's deadline was due records neither pass nor fail: nothing was observed."
          + " Applies only to default routes - the caller-body-handler route is NOT APPLICABLE by"
          + " contract.");

  public static final Property W3_C = new Property(
      "W3-C",
      Grade.A_PUBLIC_CONTRACT,
      "Body decoding is correct and Content-Length is untrusted.",
      "The Accept-Encoding: gzip / Content-Encoding handling and the documented treatment of"
          + " Content-Length as untrusted; the uncompressed oracle is the peer's own pre-gzip bytes.",
      "Sub-cases plain, gzip, x-gzip, \"identity, gzip\", BAD_GZIP, GZIP_TRAILING, TRUNCATE_BODY. A"
          + " wrong value is a FAIL; a clean exception is a pass for the malformed cases. A truncated"
          + " but successfully returned value is the defect this hunts.");

  public static final Property W3_D = new Property(
      "W3-D",
      Grade.A_PUBLIC_CONTRACT,
      "Errors do not wedge the client.",
      "The client is documented as reusable after a failed call.",
      "After each HTTP_429 / HTTP_503 / RPC_ERROR / CONNECTION_DROP, the next 3 operations on the same"
          + " SolanaRpcClient settle within requestTimeout + 2 s. An operation answered promptly with"
          + " another error is settled, not wedged (the peer's next fault, or the JDK pool handing out a"
          + " socket the peer just dropped); only no settlement inside the bound fails.");

  public static final Property W3_E = new Property(
      "W3-E",
      Grade.A_PUBLIC_CONTRACT,
      "Cancellation is isolated to the cancelled call.",
      "A cancelled CompletableFuture affects only its own exchange; every other caller of the same"
          + " client is unaffected.",
      "With SOAK_CANCEL_FRACTION of futures cancelled at a seeded delay, every other in-flight future"
          + " on the same client still satisfies W3-A. FAIL if a cancellation correlates with a"
          + " mismatch or a wedge.");

  public static final Property W3_F = new Property(
      "W3-F",
      Grade.A_PUBLIC_CONTRACT,
      "The predicate's invocation count equals the client-side count of completed default-route"
          + " exchanges.",
      "The builder's documented predicate position: it wraps the parser and sees (response, body) for"
          + " every default-route completion.",
      "Per client at quiesce, invocations within [completed - stillInFlight, completed +"
          + " harnessCancelled + stillInFlight]; outside the band is a FAIL. This is an aggregate"
          + " identity and is stated as one: one response seen twice and another never seen net to"
          + " zero, so \"exactly once\" is what a per-response identity check would establish and is"
          + " deliberately not claimed here.");

  public static final Property W4_C = new Property(
      "W4-C",
      Grade.B_DOCUMENTED_INVARIANT,
      "The exchange deadline stays timely while the common pool is loaded.",
      "JsonHttpClient schedules its exchange deadline on ForkJoinPool.commonPool() (JDK 25's pool"
          + " scheduler), and the JDK re-dispatches every sendAsync completion onto that same pool,"
          + " so both the cancel and its observation by the caller wait on the common pool; a loaded"
          + " pool therefore delays the very timer that bounds the exchange.",
      "32 getSlot calls with requestTimeout 500 ms during each OVERLAP fan-out settle within"
          + " 2 x 500 ms + 2 s; the lateness distribution is recorded in http.deadlineLate. A bare"
          + " sentinel timer is scheduled beside each probe the same way: a late deadline next to a"
          + " sentinel that was itself late past the slack is the pool, counted http.w4c.poolStarved"
          + " and neither passed nor failed; next to a timely sentinel it fails, naming both.");

  public static final Property W5_A = new Property(
      "W5-A",
      Grade.A_PUBLIC_CONTRACT,
      "Engine-owned resources are released after close().",
      "close() is terminal and the engine owns the check-loop thread it created; an engine that was"
          + " never handed an executor must not leave one behind.",
      "Thread count returns to the pre-cycle baseline within SOAK_THREAD_MARGIN after each churn"
          + " cycle; a ratchet past the margin is a FAIL.");

  public static final Property W5_B1 = new Property(
      "W5-B1",
      Grade.A_PUBLIC_CONTRACT,
      "A caller-owned HttpClient survives the discard of the clients built on it.",
      "The builder takes an HttpClient the caller owns; sava never closes what it did not create.",
      "After every engine and churn client is closed, a request on the shared HttpClient still"
          + " succeeds.");

  public static final Property W5_B2 = new Property(
      "W5-B2",
      Grade.A_PUBLIC_CONTRACT,
      "A caller-injected ExecutorService is not shut down by the engine.",
      "Same ownership rule as W5-B1, observed through the package-private executor seam.",
      "The injected executor still accepts work after close(). NOT EVALUATED without the"
          + " --add-opens that makes the seam reachable - a stated skip, not a silent one.");

  public static final Property W5_C = new Property(
      "W5-C",
      Grade.D_MEASUREMENT,
      "The file-descriptor count returns to its baseline after quiescence.",
      "A measurement with a margin gate, following the reference harness's rule: file descriptors are"
          + " a step function, so a margin is the honest instrument and a slope is not.",
      "fds at the end of QUIESCE no more than baseline + SOAK_FD_MARGIN; breaching the margin FAILs"
          + " the run even though the grade is D.");

  public static final Property P7 = new Property(
      "P7",
      Grade.C_PEER_ESTABLISHED,
      "After a connection-level fault, every live registration is confirmed again within the recovery"
          + " budget.",
      "Every input is peer-established: the peer applied the fault, the peer saw the socket close, and"
          + " the peer recorded each re-subscribe it received.",
      "connectTimeout + reConnectDelay + 4 x subscriptionResendDelay + 2 s, printed in the ledger as"
          + " the derived value for this run's timings; a record over budget is a FAIL naming the fault"
          + " kind.");

  public static final Property NP_1 = new Property(
      "NP-1",
      Grade.NONE,
      "Delivery across a disconnect is NOT asserted.",
      "The client does not promise it. Recorded as a row so a later contributor sees it was considered"
          + " and declined rather than adding the \"obvious\" check.",
      "n/a - the sequence oracle resets to \"monotone, any gap allowed\" at every epoch boundary.");

  public static final Property NP_2 = new Property(
      "NP-2",
      Grade.NONE,
      "Exactly-once, or correlated, failure notification across the connect future and the lifecycle"
          + " callbacks is NOT asserted.",
      "connect()'s javadoc explicitly disclaims it. Issue #52's work counts the double observation; it"
          + " never fails on it.",
      "n/a - the #52 capture reports attribution, and #52 never contributes a FAIL.");

  public static final Property NP_3 = new Property(
      "NP-3",
      Grade.NONE,
      "Head-of-line independence between consumers on one connection is NOT asserted.",
      "The engine dispatches on the listener thread and says so; W2-D measures the coupling instead.",
      "n/a - see W2-D.");

  public static final Property NP_4 = new Property(
      "NP-4",
      Grade.NONE,
      "Notification ordering across channels is NOT asserted.",
      "Only per-key order is established by the peer's write-time sequence assignment; across channels"
          + " nothing promises an order.",
      "n/a - see W1-E, which is per (engine, key, epoch).");

  /// Every property, in report order. The non-properties come last so a reader reaches the
  /// assertions first and the declined checks as a closing note.
  public static final List<Property> ALL = List.of(
      W1_A, W1_B, W1_C, W1_D, W1_E, W1_F, W1_G, W1_H, W1_I, W1_J,
      W2_A, W2_B, W2_C, W2_D,
      W3_A, W3_B, W3_C, W3_D, W3_E, W3_F,
      W4_C,
      W5_A, W5_B1, W5_B2, W5_C,
      P7,
      NP_1, NP_2, NP_3, NP_4);

  private static final Map<String, Property> BY_ID = byId(ALL);

  private static Map<String, Property> byId(final List<Property> properties) {
    final var index = new LinkedHashMap<String, Property>(properties.size() * 2);
    for (final var property : properties) {
      index.put(property.id(), property);
    }
    return Map.copyOf(index);
  }

  /// Throws rather than returning null: an unknown id is a typo in an assertion, and a typo that
  /// silently records nothing is the failure mode the ledger exists to prevent.
  public static Property byId(final String id) {
    final var property = BY_ID.get(id);
    if (property == null) {
      throw new IllegalArgumentException("unknown property id '" + id + "'; the table is fixed in "
          + Properties.class.getName());
    }
    return property;
  }

  private Properties() {
  }
}
