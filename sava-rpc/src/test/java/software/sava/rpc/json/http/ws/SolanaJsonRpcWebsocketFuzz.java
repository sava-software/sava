package software.sava.rpc.json.http.ws;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.JsonUtil;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/// Jazzer entry point for websocket message handling against hostile framing: the
/// fragment-reassembly arithmetic of `onText` (four distinct copy branches over
/// array-backed, array-less, positioned, and sliced [CharBuffer]s) and the
/// notification dispatch of `onWholeMessage` behind it, which parses whatever a
/// server sends — subscription confirmations, error envelopes, and every
/// notification channel's response parser — and, with batching on (header byte
/// 11), the element-by-element dispatch of a batch response array and the release
/// of a batch message the server could not read.
///
/// `onWholeMessage` swallows [RuntimeException] into a log plus the exception
/// subscribers, and the batch response reader does the same with an array's own
/// structural failure, so the malformed-input contract here is "no hang, no [Error],
/// nothing non-RuntimeException": Jazzer flags OOM/stack exhaustion and any
/// [Throwable] escaping `onText` on its own. On top of that, every input must
/// leave the engine consistent. After the carved frames (and a flush of any dangling
/// fragment) the ready set must still index exactly the pending requests not
/// admitted to a send; with batching off no batch may be ledgered and batching may
/// not be refused, and with batching on the in-flight batch leaves the ledger only
/// by the release that refuses batching. One more maintenance pass then sends what
/// the frames made due, none of it as a batch message while batching is off or
/// refused, and the ready set must still hold. And the canonical account
/// registration must have survived: a canonical account notification fed whole must
/// dispatch to it exactly once. The one connection replacement an input can
/// legitimately cause comes from a grant naming a subscription id that another
/// registration owns for a different request; the canonical notification then has
/// no connection to arrive on, so the harness requires that replacement's error to
/// be the one delivered and the registration to be still held.
///
/// A fresh websocket is built per input ([RecordingWebSocket]/[TestClock]/
/// [RecordingExecutor], no network, no background thread): the reassembly buffer,
/// its offset, and the subscription maps are instance state, and reusing them
/// would make crashes input-order-dependent and irreproducible. Subscriptions on
/// every channel (account, logs, program, signature, slot, root, and a generic
/// block subscription) are registered and confirmed in a fixed preamble so the
/// fuzzed message can reach each channel's parse-and-dispatch arm; the fixed
/// subscription ids (11/22/33/44/66/77/55) are what the seed corpus targets.
/// Account subscriptions for [#IN_FLIGHT_KEYS] are then registered and sent by
/// one maintenance pass, and left unanswered, so the fuzzed message finds
/// requests in flight to grant, refuse, reject or release; their fixed request ids
/// ([#IN_FLIGHT_IDS], 9 and 10) are what the seeds that answer them target.
///
/// Input layout — a 12-byte header, then the message text (one byte per char,
/// ISO-8859-1):
/// - byte 0 & 7: fragment count - 1
/// - bytes 1-2: two flavor bits per fragment — 0 array-backed, 1 array-less,
///   2 array-backed at position > 0, 3 sliced (arrayOffset > 0)
/// - byte 3 bit 0: whether the final fragment carries last=true; if not, the
///   input ends dangling and the harness flushes with an empty terminal frame
/// - bytes 4-10: split-point fractions for up to 7 splits
/// - byte 11 bit 0: batching on — the engine is built with a subscribe batch cap
///   of [#BATCH_BYTES], so the preamble's typed subscribes go out as one batch
///   message, the generic one alone, the in-flight requests as one more batch
///   message, and an inbound JSON array is read element by element as a batch
///   response; clear, batching is off, every request goes out alone and an array
///   is one unparseable message. The other bits are reserved.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
///
/// Run with `./gradlew :sava-rpc:fuzzWs [-PmaxFuzzTime=<seconds>]`.
public final class SolanaJsonRpcWebsocketFuzz {

  /// Feeding the parser garbage is the entire point here, and the engine and shared JSON
  /// utility diagnose malformed frames before the harness checks its state postcondition.
  /// Letting those records reach JUL's parent console handler floods a campaign without adding
  /// fuzz evidence. The fields hold the loggers strongly because LogManager otherwise retains
  /// them weakly and may recreate one with parent propagation restored during a long run.
  /// Direct diagnostic handlers remain usable; only default console propagation is disabled.
  private static final List<Logger> QUIET_PARSER_LOGS = List.of(
      Logger.getLogger(SolanaJsonRpcWebsocket.class.getName()),
      Logger.getLogger(JsonUtil.class.getName())
  );

  static {
    QUIET_PARSER_LOGS.forEach(logger -> logger.setUseParentHandlers(false));
  }

  private static final int HEADER_LENGTH = 12;
  /// The subscribe batch cap that header byte 11 switches on: Agave's inbound message limit,
  /// which counts the whole message and is inclusive.
  private static final int BATCH_BYTES = 4_096;

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  // Large delays keep any timing seam inert; there is no background thread regardless.
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);

  private static final PublicKey ACCOUNT_KEY =
      PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
  private static final PublicKey LOGS_KEY =
      PublicKey.fromBase58Encoded("83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri");
  private static final PublicKey PROGRAM_KEY =
      PublicKey.fromBase58Encoded("GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc");
  private static final String TX_SIG =
      "5h6xBEauJ3PK6SWCZ1PGjBvj8vDdWG3KpwATGy1ARAXFSDwt8GFXM7W5Ncn16wmqokgpiKRLuS83KUxyZyv2sUYv";

  // The subscription ids the preamble confirms, in registration order; seeds aim at these.
  private static final long[] SUB_IDS = {11, 22, 33, 44, 66, 77, 55};

  /// The accounts of the subscriptions left in flight for the fuzzed message, distinct from
  /// [#ACCOUNT_KEY] and from each other. More than one, so that with batching on they share a
  /// batch message: a message of one request goes out as its plain object, which is never
  /// ledgered and which no release can answer.
  private static final List<PublicKey> IN_FLIGHT_KEYS = List.of(
      PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112"),
      PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
  );
  /// The request ids the in-flight subscriptions are minted with, in [#IN_FLIGHT_KEYS] order.
  /// Seeds aim at these, so every input checks that they are what the engine minted.
  private static final List<Long> IN_FLIGHT_IDS = List.of(9L, 10L);

  /// A fragment of the diagnostic the engine delivers for the one connection replacement an
  /// input can cause here: a grant naming a subscription id that another registration owns for
  /// a different request.
  private static final String NON_EQUIVALENT_COLLISION = "was assigned to non-equivalent requests";

  private static final String LIVENESS_NOTIFICATION = """
      {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":5199307},"value":{"data":["dGVzdA==","base64"],"executable":false,"lamports":33594,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":4}},"subscription":11}}""";

  public static void fuzzerTestOneInput(final byte[] data) {
    if (data.length < HEADER_LENGTH) {
      return;
    }
    final boolean batching = (data[11] & 1) == 1;
    // Recorded, not null: the default error policy closes the instance and drops every
    // registration with it, and a hostile grant may legitimately replace the connection here
    // (see the replacement check below).
    final var errors = new ArrayList<Throwable>();
    try (final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(),
        new RecordingExecutor(),
        // A recording scheduler, not null: every input closes its websocket, and the default
        // path would queue an eight-second abort watchdog on the shared JDK delayer per input.
        new RecordingScheduler(),
        _ -> {
        },
        (_, _, _) -> {
        },
        (_, error) -> errors.add(error),
        null, null,
        batching ? BATCH_BYTES : 0
    )) {
      final var socket = new RecordingWebSocket();
      final var received = new ArrayList<Object>();
      final var subMsgIds = new ArrayList<Long>();

      ws.exceptionSubscribe(_ -> {
      });
      ws.accountSubscribe(ACCOUNT_KEY, sub -> subMsgIds.add(sub.msgId()), received::add);
      ws.logsSubscribe(LOGS_KEY, sub -> subMsgIds.add(sub.msgId()), received::add);
      ws.programSubscribe(PROGRAM_KEY, sub -> subMsgIds.add(sub.msgId()), received::add);
      ws.signatureSubscribe(TX_SIG, sub -> subMsgIds.add(sub.msgId()), received::add);
      ws.slotSubscribe(sub -> subMsgIds.add(sub.msgId()), received::add);
      ws.rootSubscribe(sub -> subMsgIds.add(sub.msgId()), received::add);
      ws.subscribe(
          "blockSubscribe", "blockUnsubscribe", "blockNotification", "all", "\"all\"",
          ji -> {
            ji.skip();
            return Boolean.TRUE;
          },
          sub -> subMsgIds.add(sub.msgId()),
          received::add
      );
      ws.onOpen(socket);
      if (subMsgIds.size() != SUB_IDS.length) {
        throw new AssertionError("preamble registered " + subMsgIds.size() + " subscriptions");
      }
      for (int i = 0; i < SUB_IDS.length; ++i) {
        feedWhole(ws, socket, "{\"jsonrpc\":\"2.0\",\"result\":" + SUB_IDS[i] + ",\"id\":" + subMsgIds.get(i) + "}");
      }
      final int receivedInPreamble = received.size();
      if (receivedInPreamble != 0) {
        throw new AssertionError("confirmations dispatched a notification");
      }

      // Requests for the fuzzed message to act on: registered once the preamble is confirmed,
      // sent by one pass and never answered here. Their consumers are not `received`, whose
      // count the liveness check reads.
      final var inFlightMsgIds = new ArrayList<Long>();
      for (final var key : IN_FLIGHT_KEYS) {
        ws.accountSubscribe(key, sub -> inFlightMsgIds.add(sub.msgId()), _ -> {
        });
      }
      final int sentBeforeInFlight = socket.sentText.size();
      pass(ws, socket);
      final var inFlightFrames = List.copyOf(socket.sentText.subList(sentBeforeInFlight, socket.sentText.size()));
      final boolean sentAsConfigured = batching
          ? inFlightFrames.size() == 1 && inFlightFrames.getFirst().startsWith("[")
          : inFlightFrames.size() == IN_FLIGHT_IDS.size() && noBatchMessage(inFlightFrames);
      // With batching on, pushing the in-flight batch pruned the preamble's answered one, so the
      // ledger holds exactly the in-flight batch.
      if (!inFlightMsgIds.equals(IN_FLIGHT_IDS) || !sentAsConfigured
          || ws.retainedOutstandingBatches() != (batching ? 1 : 0)) {
        throw new AssertionError("requests " + inFlightMsgIds + " went out as " + inFlightFrames + " with "
            + ws.retainedOutstandingBatches() + " batch messages ledgered");
      }

      // carve the message text into fragments and feed them
      final int fragments = 1 + (data[0] & 7);
      final int flavorBits = ((data[2] & 0xFF) << 8) | (data[1] & 0xFF);
      final boolean terminated = (data[3] & 1) == 1;
      final char[] text = new char[data.length - HEADER_LENGTH];
      for (int i = 0; i < text.length; ++i) {
        text[i] = (char) (data[HEADER_LENGTH + i] & 0xFF);
      }
      final int[] splits = new int[fragments + 1];
      for (int i = 1; i < fragments; ++i) {
        splits[i] = ((data[3 + i] & 0xFF) * text.length) / 255;
      }
      splits[fragments] = text.length;
      Arrays.sort(splits);
      for (int i = 0; i < fragments; ++i) {
        final int from = splits[i];
        final int len = splits[i + 1] - from;
        final boolean last = i == fragments - 1 && terminated;
        ws.onText(socket, wrap(text, from, len, (flavorBits >> (i << 1)) & 3), last);
      }
      if (!terminated) {
        // flush the dangling accumulation as a (garbage) whole message — tolerated
        ws.onText(socket, CharBuffer.wrap(""), true);
      }

      // Batching off never ledgers a message or latches the refusal: at a cap of 0 every request
      // travels alone, a lone request is never ledgered, and the refusal reads the ledger.
      if (!batching && (ws.retainedOutstandingBatches() != 0 || ws.batchingRefused())) {
        throw new AssertionError("batching off left " + ws.retainedOutstandingBatches()
            + " batch messages ledgered, refused: " + ws.batchingRefused());
      }

      if (!errors.isEmpty()) {
        // A grant naming a subscription id that another registration owns, for a different
        // request, replaces the connection by design: the id no longer names one stream. It is
        // the one replacement the frames can cause here. The engine's other connection-fatal
        // answers need a cancellation the caller made, and this harness makes none: the
        // cancellation the engine itself sends for an unknown id carries no request fingerprint,
        // so a rejection of it is never fatal. Its deadlines need the clock to move, and its
        // message cap is far above the target's longest input. The recording policy receives
        // the error once and keeps the instance open. The liveness notification has no
        // connection left to arrive on, so the registry answers for the canonical registration
        // instead: a duplicate of it is refused while it is still held.
        if (!isNonEquivalentCollision(errors)) {
          throw new AssertionError("the frames replaced the connection for " + errors);
        }
        if (!socket.aborted || ws.closed()) {
          throw new AssertionError("a replaced connection must be aborted and leave the instance open");
        }
        if (ws.accountSubscribe(ACCOUNT_KEY, _ -> {
        })) {
          throw new AssertionError("the connection replacement released the canonical registration");
        }
        return;
      }

      // With batching on the ledger holds the in-flight batch until its release, which also
      // stops batching. Nothing else inside one message prunes it: a prune runs only beside a
      // push, a release or a retirement, and the release needs a whole message, so no answer to
      // the batch can precede it. Nothing else stops batching: a connection lost with its batch
      // unanswered suspends it for the next connection only, and the one replacement the frames
      // can cause returned above.
      if (batching && ws.retainedOutstandingBatches() != (ws.batchingRefused() ? 0 : 1)) {
        throw new AssertionError(ws.retainedOutstandingBatches() + " batch messages ledgered, refused: "
            + ws.batchingRefused());
      }
      // The ready set is a subset index of the pending map: whatever grants, refusals,
      // rejections and releases the frames produced, every pending request must be ready or in
      // flight, never both and never neither, and every ready entry must be pending. Asked
      // before the pass below too, which would admit a ready entry left without its pending
      // registration and hide it.
      if (!ws.readySetMatchesPendingWork()) {
        throw new AssertionError("the frames left the ready set diverged from the pending and in-flight maps");
      }

      // One more pass sends what the frames made due: the requests of a released batch, at
      // once. A request a refusal re-armed keeps the stamp it went out with, which the unmoved
      // clock holds inside the resend window. While batching is off or refused the cap is 0, so
      // nothing the pass sends may be a batch message.
      final int sentBeforePass = socket.sentText.size();
      pass(ws, socket);
      final var sentByPass = List.copyOf(socket.sentText.subList(sentBeforePass, socket.sentText.size()));
      if ((!batching || ws.batchingRefused()) && !noBatchMessage(sentByPass)) {
        throw new AssertionError("a batch message went out while batching is off or refused: " + sentByPass);
      }

      // Whatever the frames did, the canonical registration must have survived: no inbound frame
      // sequence is a legitimate removal of subscription 11. A confirmation collision never
      // displaces the owner, dispatch is channel-correlated, cancellation acknowledgements
      // adjudicate by wire order, and no un-subscription for id 11 is ever minted here, so a
      // liveness notification that fails to dispatch is registry corruption.
      final int before = received.size();
      final int sentBefore = socket.sentText.size();
      ws.onText(socket, CharBuffer.wrap(LIVENESS_NOTIFICATION), true);
      final boolean dispatched = received.size() > before;
      if (!dispatched) {
        final boolean unsubscribed = socket.sentText.size() > sentBefore
            && socket.sentText.getLast().contains("accountUnsubscribe");
        throw new AssertionError(unsubscribed
            ? "the canonical registration was removed by fuzzed frames and auto-unsubscribed as unknown"
            : "liveness notification neither dispatched nor un-subscribed");
      }
      if (received.size() > before + 1) {
        throw new AssertionError("one notification dispatched more than once");
      }
      // And the pass's admissions kept the ready set exact.
      if (!ws.readySetMatchesPendingWork()) {
        throw new AssertionError("the ready set diverged from the pending and in-flight maps");
      }
    }
  }

  private static void feedWhole(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  /// One maintenance pass on the calling thread: a pong runs it, and it admits every due ready
  /// request.
  private static void pass(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket) {
    ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
  }

  private static boolean noBatchMessage(final List<String> frames) {
    return frames.stream().noneMatch(frame -> frame.startsWith("["));
  }

  /// Whether `errors` is the one diagnostic of a grant naming a subscription id that another
  /// registration owns for a different request.
  private static boolean isNonEquivalentCollision(final List<Throwable> errors) {
    if (errors.size() != 1 || !(errors.getFirst() instanceof IllegalStateException collision)) {
      return false;
    }
    final var message = collision.getMessage();
    return message != null && message.contains(NON_EQUIVALENT_COLLISION);
  }

  private static CharBuffer wrap(final char[] text, final int from, final int len, final int flavor) {
    switch (flavor) {
      case 0 -> {
        final char[] exact = new char[len];
        System.arraycopy(text, from, exact, 0, len);
        return CharBuffer.wrap(exact);
      }
      case 1 -> {
        return CharBuffer.wrap(new String(text, from, len));
      }
      default -> {
        // array-backed with a leading pad: position > 0, and sliced on top for
        // flavor 3 so the pad lands in arrayOffset instead
        final char[] padded = new char[7 + len];
        System.arraycopy(text, from, padded, 7, len);
        final var positioned = CharBuffer.wrap(padded, 7, len);
        return flavor == 2 ? positioned : positioned.slice();
      }
    }
  }

  private SolanaJsonRpcWebsocketFuzz() {
  }
}
