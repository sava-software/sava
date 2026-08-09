package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.response.ProcessedSlot;
import software.sava.rpc.json.http.response.TxLogs;

import java.math.BigInteger;
import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Every frame in this suite is a VERBATIM capture from api.mainnet-beta.solana.com
/// (2026-08-09), replayed through the real client. Hand-written fixtures encode what we think
/// the server says; these encode what it said — field order, real error wording, and values a
/// plausible-looking fixture never picks, like the clock sysvar's rentEpoch of 2^64-1. Request
/// ids are remapped to the client's own msgId sequence where correlation requires it;
/// everything else is untouched.
final class LiveMainnetShapeTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
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

  /// The clock sysvar changes every slot, so its notification is the canonical account frame —
  /// and it carries rentEpoch 18446744073709551615, the unsigned 64-bit maximum, which no
  /// hand-written fixture here had ever used.
  @Test
  void theRealAccountNotificationParses() {
    try (final var ws = websocket()) {
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(CLOCK_SYSVAR, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":8091375,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":438214670},"value":{"lamports":1169280,"data":["DqAeGgAAAABBfHdqAAAAAPYDAAAAAAAA9wMAAAAAAAD4jnhqAAAAAA==","base64"],"owner":"Sysvar1111111111111111111111111111111111111","executable":false,"rentEpoch":18446744073709551615,"space":40}},"subscription":8091375}""");

      assertEquals(1, received.size());
      final var account = received.getFirst();
      assertEquals(CLOCK_SYSVAR, account.pubKey());
      assertEquals(1_169_280L, account.lamports());
      assertEquals(SYSVAR_OWNER, account.owner());
      assertFalse(account.executable());
      assertEquals(new BigInteger("18446744073709551615"), account.rentEpoch(),
          "the unsigned 64-bit maximum must survive parsing");
      assertEquals(40, account.data().length, "40 bytes of clock sysvar");
      assertEquals(438_214_670L, account.context().slot());
    }
  }

  /// The program frame nests pubkey and account one level deeper than the account frame.
  @Test
  void theRealProgramNotificationParses() {
    try (final var ws = websocket()) {
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.programSubscribe(SYSVAR_OWNER, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":8092706,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"programNotification","params":{"result":{"context":{"slot":438214705},"value":{"pubkey":"SysvarC1ock11111111111111111111111111111111","account":{"lamports":1169280,"data":["MaAeGgAAAABBfHdqAAAAAPYDAAAAAAAA9wMAAAAAAAAHj3hqAAAAAA==","base64"],"owner":"Sysvar1111111111111111111111111111111111111","executable":false,"rentEpoch":18446744073709551615,"space":40}}},"subscription":8092706}""");

      assertEquals(1, received.size());
      final var account = received.getFirst();
      assertEquals(CLOCK_SYSVAR, account.pubKey(), "the nested pubkey names the changed account");
      assertEquals(SYSVAR_OWNER, account.owner());
      assertEquals(new BigInteger("18446744073709551615"), account.rentEpoch());
    }
  }

  /// The live slot frame orders its members slot, parent, root — every hand-written fixture
  /// here had used parent, root, slot, so the real order had never been exercised.
  @Test
  void theRealSlotNotificationFieldOrderParses() {
    try (final var ws = websocket()) {
      final var slots = new ArrayList<ProcessedSlot>();
      assertTrue(ws.slotSubscribe(slots::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":129344,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"slot":438214668,"parent":438214667,"root":438214636},"subscription":129344}}""");

      assertEquals(1, slots.size());
      assertEquals(438_214_668L, slots.getFirst().slot());
      assertEquals(438_214_667L, slots.getFirst().parent());
      assertEquals(438_214_636L, slots.getFirst().root());
    }
  }

  @Test
  void theRealRootNotificationParses() {
    try (final var ws = websocket()) {
      final var roots = new ArrayList<Long>();
      assertTrue(ws.rootSubscribe(roots::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":8091280,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"rootNotification","params":{"result":438214637,"subscription":8091280}}""");
      assertEquals(List.of(438_214_637L), roots);
    }
  }

  /// A vote transaction's logs frame: signature first, null err, two log lines.
  @Test
  void theRealLogsNotificationParses() {
    try (final var ws = websocket()) {
      final var received = new ArrayList<TxLogs>();
      final var voteProgram = PublicKey.fromBase58Encoded("Vote111111111111111111111111111111111111111");
      assertTrue(ws.logsSubscribe(voteProgram, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":8092911,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"logsNotification","params":{"result":{"context":{"slot":438214712},"value":{"signature":"2nAFQ3XzyydwkdhtLoNti9C9SZw2VgZrNjJmJx1yARHSpkbSCJvDR7ghemmYZLvDbmuYGDdeikPsZ7PvJWvF6WG7","err":null,"logs":["Program Vote111111111111111111111111111111111111111 invoke [1]","Program Vote111111111111111111111111111111111111111 success"]}},"subscription":8092911}""");

      assertEquals(1, received.size());
      final var logs = received.getFirst();
      assertEquals("2nAFQ3XzyydwkdhtLoNti9C9SZw2VgZrNjJmJx1yARHSpkbSCJvDR7ghemmYZLvDbmuYGDdeikPsZ7PvJWvF6WG7",
          logs.signature());
      assertEquals(2, logs.logs().size());
      assertTrue(logs.logs().getFirst().endsWith("invoke [1]"));
    }
  }

  /// slotsUpdatesSubscribe is live on mainnet-beta and has no typed channel — the generic
  /// subscribe's real-world case, with the frame it actually produces.
  @Test
  void theRealSlotsUpdatesNotificationDrivesAGenericSubscription() {
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
          {"jsonrpc":"2.0","result":6591959,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"slotsUpdatesNotification","params":{"result":{"type":"optimisticConfirmation","slot":438214709,"timestamp":1786285834534},"subscription":6591959}}""");

      assertEquals(List.of(new SlotsUpdate("optimisticConfirmation", 438_214_709L, 1_786_285_834_534L)), updates);
    }
  }

  /// The two real -32602 wordings, each exercising its own path. A stale un-subscription draws
  /// "Invalid subscription id." — with a period, which the startsWith heuristic tolerates —
  /// and must stay out of the exception subscribers when uncorrelated. An invalid pubkey draws
  /// "Invalid Request: Invalid pubkey provided" with the request id, which is a terminal,
  /// reported rejection that frees the key.
  @Test
  void theRealErrorWordingsRouteCorrectly() {
    final var clock = new TestClock();
    try (final var ws = new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null,
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock,
        new RecordingExecutor(), null, null, (_, _, _) -> {
        }, null, null, null)) {
      final var errors = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);
      assertTrue(ws.accountSubscribe(CLOCK_SYSVAR, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      // uncorrelated stale-unsub error: an id this client never sent
      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid subscription id."},"id":9999}""");
      assertTrue(errors.isEmpty(), "an uncorrelated stale-unsub error is not consumer news");

      // correlated invalid-pubkey rejection: terminal, reported, key freed
      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid Request: Invalid pubkey provided"},"id":2}""");
      assertEquals(1, errors.size());
      assertEquals("Invalid Request: Invalid pubkey provided", errors.getFirst().getMessage());
      assertTrue(ws.accountSubscribe(CLOCK_SYSVAR, _ -> {
      }), "the rejected key is free for a corrected subscribe");
    }
  }

}
