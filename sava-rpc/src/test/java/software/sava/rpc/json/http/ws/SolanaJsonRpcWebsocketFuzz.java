package software.sava.rpc.json.http.ws;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;

/// Jazzer entry point for websocket message handling against hostile framing: the
/// fragment-reassembly arithmetic of `onText` (four distinct copy branches over
/// array-backed, array-less, positioned, and sliced [CharBuffer]s) and the
/// notification dispatch of `onWholeMessage` behind it, which parses whatever a
/// server sends — subscription confirmations, error envelopes, and every
/// notification channel's response parser.
///
/// `onWholeMessage` swallows [RuntimeException] into a log plus the exception
/// subscribers, so the malformed-input contract here is "no hang, no [Error],
/// nothing non-RuntimeException": Jazzer flags OOM/stack exhaustion and any
/// [Throwable] escaping `onText` on its own. On top of that, every input must
/// leave the reassembly state machine recoverable — after the carved frames (and a
/// flush of any dangling fragment), a canonical account notification fed whole
/// must either dispatch to the still-registered subscription or trigger exactly
/// the unknown-subscription auto-unsubscribe, and never both or neither.
///
/// A fresh websocket is built per input ([RecordingWebSocket]/[TestClock]/
/// [RecordingExecutor], no network, no background thread): the reassembly buffer,
/// its offset, and the subscription maps are instance state, and reusing them
/// would make crashes input-order-dependent and irreproducible. Subscriptions on
/// every channel (account, logs, program, signature, slot, root, and a generic
/// block subscription) are registered and confirmed in a fixed preamble so the
/// fuzzed message can reach each channel's parse-and-dispatch arm; the fixed
/// subscription ids (11/22/33/44/66/77/55) are what the seed corpus targets.
///
/// Input layout — a 12-byte header, then the message text (one byte per char,
/// ISO-8859-1):
/// - byte 0 & 7: fragment count - 1
/// - bytes 1-2: two flavor bits per fragment — 0 array-backed, 1 array-less,
///   2 array-backed at position > 0, 3 sliced (arrayOffset > 0)
/// - byte 3 bit 0: whether the final fragment carries last=true; if not, the
///   input ends dangling and the harness flushes with an empty terminal frame
/// - bytes 4-10: split-point fractions for up to 7 splits
/// - byte 11: reserved
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
///
/// Run with `./gradlew :sava-rpc:fuzzWs [-PmaxFuzzTime=<seconds>]`.
public final class SolanaJsonRpcWebsocketFuzz {

  private static final int HEADER_LENGTH = 12;

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

  private static final String LIVENESS_NOTIFICATION = """
      {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":5199307},"value":{"data":["dGVzdA==","base64"],"executable":false,"lamports":33594,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":4}},"subscription":11}}""";

  public static void fuzzerTestOneInput(final byte[] data) {
    if (data.length < HEADER_LENGTH) {
      return;
    }
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
        null, null, null
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

      // whatever the frames did, the state machine must have recovered — and the canonical
      // registration must have SURVIVED: no inbound frame sequence is a legitimate removal of
      // subscription 11. Confirmation collisions no longer displace, dispatch is
      // channel-correlated, cancellation acknowledgements adjudicate by wire order, and no
      // un-subscription for id 11 is ever minted here — so a liveness notification that fails
      // to dispatch is registry corruption, which the old either-or postcondition (tolerating
      // an auto-unsubscribe answer as "legitimately unsubscribed") was masking.
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
    }
  }

  private static void feedWhole(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
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
