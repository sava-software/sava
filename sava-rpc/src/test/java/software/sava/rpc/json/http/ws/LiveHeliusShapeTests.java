package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.response.ProcessedSlot;
import software.sava.rpc.json.http.response.TxResult;

import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/// Every frame in this suite is a VERBATIM capture from a Helius standard websocket
/// (mainnet.helius-rpc.com, 2026-08-09), replayed through the real client — the same probe
/// sequence as [LiveMainnetShapeTests], against a second server implementation. Helius mirrors
/// Agave's member order everywhere: confirmations put id before result, error envelopes put id
/// before error, and notification params put subscription before result — so these frames drive
/// the reset-and-rescan fallbacks that mainnet-beta's ordering never touches. Behavior differs
/// twice: a stale unsubscribe draws a quiet {"result":false} where Agave answers -32602, and an
/// identical duplicate subscribe is granted a DISTINCT id with an independent lifetime where
/// Agave reuses the id. Request ids are remapped to the client's own msgId sequence where
/// correlation requires it; everything else is untouched.
@ExtendWith(QuietWsLogging.class)
final class LiveHeliusShapeTests {

  private static final URI ENDPOINT = URI.create("wss://mainnet.helius-rpc.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final PublicKey CLOCK_SYSVAR =
      PublicKey.fromBase58Encoded("SysvarC1ock11111111111111111111111111111111");
  private static final PublicKey SYSVAR_OWNER =
      PublicKey.fromBase58Encoded("Sysvar1111111111111111111111111111111111111");

  private static SolanaJsonRpcWebsocket websocket() {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null,
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), null, null, (_, _, _) -> {
        }, null, null, null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  /// The id-first confirmation exercises the confirmation field loop's other order, and the
  /// subscription-first notification lands the account publish path in its reset-and-rescan
  /// fallback: the result scan has already passed subscription when the id is finally wanted.
  @Test
  void theRealAccountNotificationParses() {
    try (final var ws = websocket()) {
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(CLOCK_SYSVAR, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","id":2,"result":62195691}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"subscription":62195691,"result":{"context":{"slot":438217755},"value":{"lamports":1169280,"data":["G6weGgAAAABBfHdqAAAAAPYDAAAAAAAA9wMAAAAAAAAPlHhqAAAAAA==","base64"],"owner":"Sysvar1111111111111111111111111111111111111","executable":false,"rentEpoch":18446744073709551615,"space":40}}}}""");

      assertEquals(1, received.size());
      final var account = received.getFirst();
      assertEquals(CLOCK_SYSVAR, account.pubKey());
      assertEquals(1_169_280L, account.lamports());
      assertEquals(SYSVAR_OWNER, account.owner());
      assertEquals(new BigInteger("18446744073709551615"), account.rentEpoch());
      assertEquals(40, account.data().length);
      assertEquals(438_217_755L, account.context().slot());
    }
  }

  /// The slot path reads the subscription id before the result, so Helius's order is its direct
  /// hit — and the result scan must then step back over the subscription member it started past.
  @Test
  void theRealSlotNotificationParses() {
    try (final var ws = websocket()) {
      final var slots = new ArrayList<ProcessedSlot>();
      assertTrue(ws.slotSubscribe(slots::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","id":2,"result":62195638}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"slotNotification","params":{"subscription":62195638,"result":{"slot":438217755,"parent":438217754,"root":438217722}}}""");

      assertEquals(1, slots.size());
      assertEquals(438_217_755L, slots.getFirst().slot());
      assertEquals(438_217_754L, slots.getFirst().parent());
      assertEquals(438_217_722L, slots.getFirst().root());
    }
  }

  /// The signature path scans for the subscription id inline after the result value, so the
  /// subscription-first order forces its reset fallback — and completion must still be
  /// terminal, freeing the signature for a fresh registration.
  @Test
  void theRealSignatureNotificationCompletesTheSubscription() {
    final var sig = "FJ1YfkALKShDyPG3JrDm8uWsghkoei2DTsyQmP2NarDQg8XkHJt8jtUzFU8B2YFsq8G66CVPi5XaKk1FXys6nKy";
    try (final var ws = websocket()) {
      final var received = new ArrayList<TxResult>();
      assertTrue(ws.signatureSubscribe(sig, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","id":2,"result":62197090}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"signatureNotification","params":{"subscription":62197090,"result":{"context":{"slot":438217778},"value":{"err":null}}}}""");

      assertEquals(1, received.size());
      assertNull(received.getFirst().error());
      assertEquals(438_217_778L, received.getFirst().context().slot());
      assertTrue(ws.signatureSubscribe(sig, received::add),
          "a completed signature subscription is terminal: the signature is free again");
    }
  }

  /// Helius emits slotsUpdates types mainnet-beta's probe never showed — "completed" — and its
  /// ordering drives the generic publish path through the same reset fallback.
  @Test
  void theRealSlotsUpdatesCompletedFrameDrivesAGenericSubscription() {
    record SlotsUpdate(String type, long slot, long timestamp) {
    }
    try (final var ws = websocket()) {
      final var updates = new ArrayList<SlotsUpdate>();
      assertTrue(ws.subscribe("slotsUpdatesSubscribe", "slotsUpdatesUnsubscribe", "slotsUpdatesNotification",
          "slotsUpdates", "",
          ji -> {
            final var update = new SlotsUpdate(
                ji.skipUntil("type").readString(),
                ji.skipUntil("slot").readLong(),
                ji.skipUntil("timestamp").readLong());
            ji.skipRestOfObject();
            return update;
          },
          null, updates::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","id":2,"result":62196919}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"slotsUpdatesNotification","params":{"subscription":62196919,"result":{"type":"completed","slot":438217777,"timestamp":1786287129297}}}""");

      assertEquals(1, updates.size());
      assertEquals(new SlotsUpdate("completed", 438_217_777L, 1_786_287_129_297L), updates.getFirst());
    }
  }

  /// Where Agave answers a stale unsubscribe with -32602 "Invalid subscription id.", Helius
  /// answers {"result":false} — a successful envelope whose payload says the id was already
  /// gone. Both settle the request: quiet, no re-send.
  @Test
  void theRealStaleUnsubscribeAnswerIsAQuietFalse() {
    try (final var ws = websocket()) {
      final var errors = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);
      assertTrue(ws.accountSubscribe(CLOCK_SYSVAR, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","id":2,"result":62195691}""");
      assertTrue(ws.accountUnsubscribe(CLOCK_SYSVAR));
      ws.onPong(socket, ByteBuffer.wrap(new byte[0])); // flush mints msgId 3

      feed(ws, socket, """
          {"jsonrpc":"2.0","id":3,"result":false}""");

      assertTrue(errors.isEmpty(), "an already-gone id is a settled cancellation, not consumer news");
      ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
      assertEquals(1, socket.sentText.stream().filter(m -> m.contains("accountUnsubscribe")).count(),
          "the false acknowledgement settles the request: nothing left to re-send");
    }
  }

  /// Refusing blockSubscribe, Helius puts id before error — the mirror of mainnet-beta's
  /// ordering, exercising the id scan from a second server implementation. -32601 is a request
  /// defect, so the refused generic registration is terminally released: re-sending "Method not
  /// found" forever was precisely the no-terminal-state bug.
  @Test
  void theRealIdFirstErrorEnvelopeRoutesCorrectly() {
    try (final var ws = websocket()) {
      final var errors = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);
      assertTrue(ws.subscribe("blockSubscribe", "blockUnsubscribe", "blockNotification",
          "blocks", "\"all\"", ji -> {
            ji.skip();
            return 0L;
          }, null, _ -> {
          }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      feed(ws, socket, """
          {"jsonrpc":"2.0","id":2,"error":{"code":-32601,"message":"Method not found"}}""");

      assertEquals(1, errors.size(), "the refusal reaches the exception subscribers");
      assertEquals("Method not found", errors.getFirst().getMessage());
      assertTrue(ws.subscribe("blockSubscribe", "blockUnsubscribe", "blockNotification",
          "blocks", "\"all\"", ji -> {
            ji.skip();
            return 0L;
          }, null, _ -> {
          }), "the refused registration is terminally released, not re-sent forever");
    }
  }
}
