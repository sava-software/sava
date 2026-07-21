package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.response.TxLogs;
import software.sava.rpc.json.http.response.TxResult;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// The interface-default overloads: each must resolve commitment and
/// enableReceivedNotification the documented way, and the wire frame is the
/// observable proof. `signatureSubscribe`'s commitment-based default —
/// received notifications only make sense when watching PROCESSED — is the
/// branchy one.
final class SubscribeOverloadTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final PublicKey KEY =
      PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");

  private static SolanaJsonRpcWebsocket websocket() {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        TIMINGS,
        new TestClock(),
        new RecordingExecutor(),
        null,
        null,
        (_, _, _) -> {
        },
        null, null, null
    );
  }

  private static String onlyFrame(final SolanaJsonRpcWebsocket ws) {
    final var socket = new RecordingWebSocket();
    ws.onOpen(socket);
    assertEquals(1, socket.sentText.size(), socket.sentText.toString());
    return socket.sentText.getFirst();
  }

  @Test
  void signatureSubscribeDefaultsReceivedNotificationOffOutsideProcessed() {
    try (final var ws = websocket()) {
      assertTrue(ws.signatureSubscribe("sigA", (final TxResult _) -> {
      }));
      assertFalse(ws.signatureSubscribe("sigA", (final TxResult _) -> {
      }), "the duplicate must be rejected through the same overload");
      assertEquals("""
          {"jsonrpc":"2.0","id":2,"method":"signatureSubscribe","params":["sigA",{"commitment":"confirmed","enableReceivedNotification":false}]}""", onlyFrame(ws));
    }
  }

  @Test
  void signatureSubscribeProcessedCommitmentEnablesReceivedNotification() {
    try (final var ws = websocket()) {
      assertTrue(ws.signatureSubscribe(Commitment.PROCESSED, "sigB", (final TxResult _) -> {
      }));
      assertFalse(ws.signatureSubscribe(Commitment.PROCESSED, "sigB", (final TxResult _) -> {
      }));
      assertEquals("""
          {"jsonrpc":"2.0","id":2,"method":"signatureSubscribe","params":["sigB",{"commitment":"processed","enableReceivedNotification":true}]}""", onlyFrame(ws));
    }
  }

  @Test
  void signatureSubscribeOnSubOverloadsCarryTheirFlags() {
    try (final var ws = websocket()) {
      final var onSub = new AtomicReference<Subscription<TxResult>>();
      assertTrue(ws.signatureSubscribe("sigC", onSub::set, _ -> {
      }));
      assertFalse(ws.signatureSubscribe("sigC", onSub::set, _ -> {
      }));
      assertTrue(ws.signatureSubscribe("sigD", true, null, _ -> {
      }));
      assertFalse(ws.signatureSubscribe("sigD", true, null, _ -> {
      }));
      assertTrue(ws.signatureSubscribe(Commitment.PROCESSED, "sigE", null, _ -> {
      }));
      assertFalse(ws.signatureSubscribe(Commitment.PROCESSED, "sigE", null, _ -> {
      }));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(List.of("""
              {"jsonrpc":"2.0","id":2,"method":"signatureSubscribe","params":["sigC",{"commitment":"confirmed","enableReceivedNotification":false}]}""", """
              {"jsonrpc":"2.0","id":3,"method":"signatureSubscribe","params":["sigD",{"commitment":"confirmed","enableReceivedNotification":true}]}""", """
              {"jsonrpc":"2.0","id":4,"method":"signatureSubscribe","params":["sigE",{"commitment":"processed","enableReceivedNotification":true}]}"""
          ), socket.sentText
      );

      // the onSub callback fires once the frame has been written
      assertNotNull(onSub.get());
      assertEquals(2, onSub.get().msgId());
      assertEquals("sigC", onSub.get().key());
    }
  }

  @Test
  void accountSubscribeOverloads() {
    try (final var ws = websocket()) {
      final var onSub = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      assertTrue(ws.accountSubscribe(KEY, onSub::set, _ -> {
      }));
      assertFalse(ws.accountSubscribe(KEY, onSub::set, _ -> {
      }));
      assertTrue(ws.accountSubscribe(Commitment.FINALIZED, KEY, _ -> {
      }));
      assertFalse(ws.accountSubscribe(Commitment.FINALIZED, KEY, _ -> {
      }));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(2, socket.sentText.size());
      assertTrue(socket.sentText.getFirst().contains("\"commitment\":\"confirmed\""), socket.sentText.getFirst());
      assertTrue(socket.sentText.getLast().contains("\"commitment\":\"finalized\""), socket.sentText.getLast());
      assertNotNull(onSub.get());
      assertEquals(KEY, onSub.get().publicKey());
    }
  }

  @Test
  void logsSubscribeOverloads() {
    try (final var ws = websocket()) {
      final var onSub = new AtomicReference<Subscription<TxLogs>>();
      assertTrue(ws.logsSubscribe(KEY, onSub::set, _ -> {
      }));
      assertFalse(ws.logsSubscribe(KEY, onSub::set, _ -> {
      }));
      assertTrue(ws.logsSubscribe(Commitment.FINALIZED, KEY, _ -> {
      }));
      assertFalse(ws.logsSubscribe(Commitment.FINALIZED, KEY, _ -> {
      }));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(2, socket.sentText.size());
      assertTrue(socket.sentText.getFirst().contains("\"commitment\":\"confirmed\""), socket.sentText.getFirst());
      assertTrue(socket.sentText.getLast().contains("\"commitment\":\"finalized\""), socket.sentText.getLast());
      assertNotNull(onSub.get());
      assertEquals(KEY.toBase58(), onSub.get().key());
    }
  }

  @Test
  void programSubscribeWithFilters() {
    try (final var ws = websocket()) {
      assertTrue(ws.programSubscribe(KEY, List.of(Filter.createDataSizeFilter(165)), _ -> {
      }));
      assertFalse(ws.programSubscribe(KEY, List.of(Filter.createDataSizeFilter(165)), _ -> {
      }));
      final var frame = onlyFrame(ws);
      assertTrue(frame.contains("\"filters\":[{\"dataSize\":165}]"), frame);
      assertTrue(frame.contains("\"commitment\":\"confirmed\""), frame);
    }
  }

  @Test
  void programSubscribeDefaultOverloads() {
    try (final var ws = websocket()) {
      final var onSub = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      assertTrue(ws.programSubscribe(KEY, onSub::set, _ -> {
      }));
      assertFalse(ws.programSubscribe(KEY, onSub::set, _ -> {
      }));
      final var otherProgram = PublicKey.fromBase58Encoded("GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc");
      assertTrue(ws.programSubscribe(Commitment.FINALIZED, otherProgram, List.of(Filter.createDataSizeFilter(80)), _ -> {
      }));
      assertFalse(ws.programSubscribe(Commitment.FINALIZED, otherProgram, List.of(Filter.createDataSizeFilter(80)), _ -> {
      }));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      assertEquals(2, socket.sentText.size());
      assertTrue(socket.sentText.getFirst().contains("\"commitment\":\"confirmed\""), socket.sentText.getFirst());
      assertTrue(socket.sentText.getLast().contains("\"commitment\":\"finalized\""), socket.sentText.getLast());
      assertTrue(socket.sentText.getLast().contains("\"dataSize\":80"), socket.sentText.getLast());
      assertNotNull(onSub.get());
    }
  }

  /// The single-token-account subscription narrows by mint *and* owner; the
  /// all-token-accounts variant (pinned in SolanaJsonRpcWebsocketTests) only by
  /// owner.
  @Test
  void subscribeToTokenAccountFiltersByMintAndOwner() {
    try (final var ws = websocket()) {
      final var mint = PublicKey.fromBase58Encoded("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v");
      final var owner = PublicKey.fromBase58Encoded("5q4WfFbcUggHhsvga263fvqwYhsBpAHkkfkdbY82S5J1");
      assertTrue(ws.subscribeToTokenAccount(mint, owner, _ -> {
      }));
      // keyed by the token program, so any second token-account subscription is a duplicate
      assertFalse(ws.subscribeToTokenAccount(mint, owner, _ -> {
      }));
      assertFalse(ws.subscribeToTokenAccount(Commitment.CONFIRMED, mint, owner, _ -> {
      }));

      final var frame = onlyFrame(ws);
      assertTrue(frame.contains('"' + SolanaAccounts.MAIN_NET.tokenProgram().toBase58() + '"'), frame);
      assertTrue(frame.contains("\"dataSize\":165"), frame);
      assertTrue(frame.contains(mint.toBase58()), frame);
      assertTrue(frame.contains(owner.toBase58()), frame);
    }
  }
}
