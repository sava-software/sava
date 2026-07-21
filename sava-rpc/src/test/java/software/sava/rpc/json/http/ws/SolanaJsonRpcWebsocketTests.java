package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.*;

import java.math.BigInteger;
import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

/// Drives the WebSocket.Listener message path of SolanaJsonRpcWebsocket directly, no network:
/// subscribe bookkeeping, outgoing frame construction, confirmation handling, notification
/// parsing/dispatch, and automatic un-subscription of unknown/stale subscription ids.
final class SolanaJsonRpcWebsocketTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  // Large delays keep the background subscription/ping thread from interleaving writes with the test thread.
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);

  private static SolanaJsonRpcWebsocket createWebsocket() {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        TIMINGS,
        new TestClock(),
        new RecordingExecutor(),
        _ -> {
        },
        (_, _, _) -> {
        },
        null, null, null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    // CharBuffer.wrap(String) has no backing array, exercising the buffer copy branch of onText.
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  @Test
  void accountSubscription() {
    try (final var ws = createWebsocket()) {
      final var key = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
      final var subRef = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      final var received = new ArrayList<AccountInfo<byte[]>>();

      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, key, subRef::set, received::add));
      assertFalse(ws.accountSubscribe(Commitment.CONFIRMED, key, subRef::set, received::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      assertEquals(List.of("""
              {"jsonrpc":"2.0","id":2,"method":"accountSubscribe","params":["7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r",{"encoding":"base64","commitment":"confirmed"}]}"""
          ), socket.sentText
      );

      final var sub = subRef.get();
      assertNotNull(sub);
      assertEquals(2, sub.msgId());
      assertEquals(key, sub.publicKey());
      assertNull(sub.subId());

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":23784,"id":2}"""
      );
      assertEquals(BigInteger.valueOf(23784), sub.subId());

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"apiVersion":"2.3.7","slot":5199307},"value":{"data":["dGVzdA==","base64"],"executable":false,"lamports":33594,"owner":"11111111111111111111111111111111","rentEpoch":18446744073709551615,"space":4}},"subscription":23784}}"""
      );

      assertEquals(1, received.size());
      final var accountInfo = received.getFirst();
      assertEquals(key, accountInfo.pubKey());
      assertEquals(33594L, accountInfo.lamports());
      assertEquals(PublicKey.fromBase58Encoded("11111111111111111111111111111111"), accountInfo.owner());
      assertEquals(5199307L, accountInfo.context().slot());
      assertArrayEquals("test".getBytes(US_ASCII), accountInfo.data());

      // A notification for an unknown subscription id triggers an un-subscription.
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":5199308},"value":{"data":["","base64"],"executable":false,"lamports":1,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":0}},"subscription":999}}"""
      );
      assertEquals(1, received.size());
      assertEquals("""
          {"jsonrpc":"2.0","id":3,"method":"accountUnsubscribe","params":[999]}""", socket.sentText.getLast()
      );
    }
  }

  @Test
  void fragmentedAccountNotification() {
    try (final var ws = createWebsocket()) {
      final var key = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(key, received::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":23784,"id":2}"""
      );

      final var notification = """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":5199307},"value":{"data":["dGVzdA==","base64"],"executable":false,"lamports":33594,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":4}},"subscription":23784}}""";
      final int third = notification.length() / 3;
      // CharBuffer.wrap(char[]) is array backed, exercising the System.arraycopy branches of onText.
      ws.onText(socket, CharBuffer.wrap(notification.substring(0, third).toCharArray()), false);
      ws.onText(socket, CharBuffer.wrap(notification.substring(third, third << 1).toCharArray()), false);
      assertTrue(received.isEmpty());
      ws.onText(socket, CharBuffer.wrap(notification.substring(third << 1).toCharArray()), true);

      assertEquals(1, received.size());
      final var accountInfo = received.getFirst();
      assertEquals(key, accountInfo.pubKey());
      assertEquals(33594L, accountInfo.lamports());
      assertArrayEquals("test".getBytes(US_ASCII), accountInfo.data());
    }
  }

  @Test
  void programSubscription() {
    try (final var ws = createWebsocket()) {
      final var program = PublicKey.fromBase58Encoded("GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc");
      final var received = new ArrayList<AccountInfo<byte[]>>();

      assertTrue(ws.programSubscribe(program, received::add));
      assertFalse(ws.programSubscribe(program, received::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      assertEquals(List.of("""
              {"jsonrpc":"2.0","id":2,"method":"programSubscribe","params":["GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc",{"commitment":"confirmed","encoding":"base64"}]}"""
          ), socket.sentText
      );

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":24040,"id":2}"""
      );

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"programNotification","params":{"result":{"context":{"slot":5208469},"value":{"pubkey":"H4vnBqifaSACnKa7acsxstsY1iV1bvJNxsCY7enrd1hq","account":{"data":["dGVzdA==","base64"],"executable":false,"lamports":33594,"owner":"GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc","rentEpoch":636,"space":80}}},"subscription":24040}}"""
      );

      assertEquals(1, received.size());
      final var accountInfo = received.getFirst();
      assertEquals(PublicKey.fromBase58Encoded("H4vnBqifaSACnKa7acsxstsY1iV1bvJNxsCY7enrd1hq"), accountInfo.pubKey());
      assertEquals(program, accountInfo.owner());
      assertEquals(33594L, accountInfo.lamports());
      assertEquals(5208469L, accountInfo.context().slot());
      assertArrayEquals("test".getBytes(US_ASCII), accountInfo.data());
    }
  }

  @Test
  void tokenAccountSubscriptionFilters() {
    try (final var ws = createWebsocket()) {
      final var owner = PublicKey.fromBase58Encoded("5q4WfFbcUggHhsvga263fvqwYhsBpAHkkfkdbY82S5J1");
      assertTrue(ws.subscribeToTokenAccounts(owner, _ -> {
          }
      ));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      assertEquals(1, socket.sentText.size());
      final var msg = socket.sentText.getFirst();
      assertTrue(msg.contains("""
          "method":"programSubscribe\""""), msg
      );
      assertTrue(msg.contains('"' + SolanaAccounts.MAIN_NET.tokenProgram().toBase58() + '"'), msg);
      assertTrue(msg.contains("\"filters\":["), msg);
      assertTrue(msg.contains("\"dataSize\":165"), msg);
      assertTrue(msg.contains("\"memcmp\""), msg);
      assertTrue(msg.contains(owner.toBase58()), msg);
    }
  }

  @Test
  void logsSubscription() {
    try (final var ws = createWebsocket()) {
      final var key = PublicKey.fromBase58Encoded("GovaE4iu227srtG2s3tZzB4RmWBzw8sTwrCLZz7kN7rY");
      final var received = new ArrayList<TxLogs>();

      assertTrue(ws.logsSubscribe(key, received::add));
      assertFalse(ws.logsSubscribe(key, received::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      assertEquals(List.of("""
              {"jsonrpc":"2.0","id":2,"method":"logsSubscribe","params":[{"mentions":["GovaE4iu227srtG2s3tZzB4RmWBzw8sTwrCLZz7kN7rY"]},{"commitment":"confirmed"}]}"""
          ), socket.sentText
      );

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":24040,"id":2}"""
      );

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"logsNotification","params":{"result":{"context":{"slot":5208469},"value":{"signature":"5h6xBEauJ3PK6SWCZ1PGjBvj8vDdWG3KpwATGy1ARAXFSDwt8GFXM7W5Ncn16wmqokgpiKRLuS83KUxyZyv2sUYv","err":null,"logs":["SBF program 83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri success"]}},"subscription":24040}}"""
      );

      assertEquals(1, received.size());
      final var txLogs = received.getFirst();
      assertEquals("5h6xBEauJ3PK6SWCZ1PGjBvj8vDdWG3KpwATGy1ARAXFSDwt8GFXM7W5Ncn16wmqokgpiKRLuS83KUxyZyv2sUYv", txLogs.signature());
      assertNull(txLogs.error());
      assertEquals(List.of("SBF program 83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri success"), txLogs.logs());
      assertEquals(5208469L, txLogs.context().slot());
    }
  }

  @Test
  void signatureSubscription() {
    try (final var ws = createWebsocket()) {
      final var sig = "2EBVM6cB8vAAD93Ktr6Vd8p67XPbQzCJX47MpReuiCXJAtcjaxpvWpcg9Ege1Nr5Tk3a2GFrByT7WPBjdsTycY9b";
      final var received = new ArrayList<TxResult>();

      assertTrue(ws.signatureSubscribe(sig, true, received::add));
      assertFalse(ws.signatureSubscribe(sig, true, received::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      assertEquals(List.of("""
              {"jsonrpc":"2.0","id":2,"method":"signatureSubscribe","params":["2EBVM6cB8vAAD93Ktr6Vd8p67XPbQzCJX47MpReuiCXJAtcjaxpvWpcg9Ege1Nr5Tk3a2GFrByT7WPBjdsTycY9b",{"commitment":"confirmed","enableReceivedNotification":true}]}"""
          ), socket.sentText
      );

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":24006,"id":2}"""
      );

      // The subscription survives a received notification.
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"signatureNotification","params":{"result":{"context":{"slot":5207623},"value":"receivedSignature"},"subscription":24006}}"""
      );
      assertEquals(1, received.size());
      assertEquals("receivedSignature", received.getFirst().value());
      assertNull(received.getFirst().error());

      // A processed notification cancels the server side subscription and is forgotten locally.
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"signatureNotification","params":{"result":{"context":{"slot":5207624},"value":{"err":null}},"subscription":24006}}"""
      );
      assertEquals(2, received.size());
      assertNull(received.getLast().value());
      assertNull(received.getLast().error());
      assertEquals(5207624L, received.getLast().context().slot());
    }
  }

  @Test
  void slotAndRootSubscriptions() {
    try (final var ws = createWebsocket()) {
      final var slots = new ArrayList<ProcessedSlot>();
      final var roots = new ArrayList<Long>();

      assertTrue(ws.slotSubscribe(slots::add));
      // A rejected duplicate still consumes a message id.
      assertFalse(ws.slotSubscribe(slots::add));
      assertTrue(ws.rootSubscribe(roots::add));
      assertFalse(ws.rootSubscribe(roots::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      assertEquals(List.of("""
              {"jsonrpc":"2.0","id":2,"method":"slotSubscribe"}""", """
              {"jsonrpc":"2.0","id":4,"method":"rootSubscribe"}"""
          ), socket.sentText
      );

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":0,"id":2}"""
      );
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":1,"id":4}"""
      );

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":75,"root":44,"slot":76},"subscription":0}}"""
      );
      assertEquals(1, slots.size());
      final var slot = slots.getFirst();
      assertEquals(76L, slot.slot());
      assertEquals(75L, slot.parent());
      assertEquals(44L, slot.root());

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"rootNotification","params":{"result":42,"subscription":1}}"""
      );
      assertEquals(List.of(42L), roots);
    }
  }

  @Test
  void genericSubscription() {
    try (final var ws = createWebsocket()) {
      final var received = new ArrayList<Long>();

      assertTrue(ws.subscribe(
          "voteSubscribe", "voteUnsubscribe", "voteNotification",
          "vote", "",
          ji -> ji.skipUntil("slots").openArray().readLong(),
          null,
          received::add
      ));
      assertFalse(ws.subscribe(
          "voteSubscribe", "voteUnsubscribe", "voteNotification",
          "vote", "",
          ji -> ji.skipUntil("slots").openArray().readLong(),
          null,
          received::add
      ));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      assertEquals(List.of("""
              {"jsonrpc":"2.0","id":2,"method":"voteSubscribe","params":[]}"""
          ), socket.sentText
      );

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":99,"id":2}"""
      );

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"voteNotification","params":{"result":{"hash":"8Rshv2oMkPu5E4opXTRyuyBeZBqQ4S477VG26wUTFxUM","slots":[1234,1235],"timestamp":null},"subscription":99}}"""
      );
      assertEquals(List.of(1234L), received);

      assertTrue(ws.unsubscribe("voteNotification", "vote"));
      assertFalse(ws.unsubscribe("voteNotification", "vote"));

      // The next notification for the forgotten subscription id flushes the queued un-subscription.
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"voteNotification","params":{"result":{"hash":"8Rshv2oMkPu5E4opXTRyuyBeZBqQ4S477VG26wUTFxUM","slots":[1236],"timestamp":null},"subscription":99}}"""
      );
      assertEquals(List.of(1234L), received);
      assertEquals("""
          {"jsonrpc":"2.0","id":3,"method":"voteUnsubscribe","params":[99]}""", socket.sentText.getLast()
      );
    }
  }

  @Test
  void exceptionNotifications() {
    try (final var ws = createWebsocket()) {
      final var exceptions = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(exceptions::add);

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params: unable to parse json"},"id":5}"""
      );
      assertEquals(1, exceptions.size());
      final var exception = assertInstanceOf(JsonRpcException.class, exceptions.getFirst());
      assertEquals(-32602, exception.code());
      assertEquals("Invalid params: unable to parse json", exception.getMessage());

      // Stale un-subscription errors are suppressed.
      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid subscription id."},"id":6}"""
      );
      assertEquals(1, exceptions.size());
    }
  }

  /// Slot and root notifications with no local subscriber are unsubscribed at the
  /// server rather than silently dropped forever.
  @Test
  void slotAndRootNotificationsWithoutASubscriberAreUnsubscribed() {
    try (final var ws = createWebsocket()) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"slotNotification","params":{"result":{"parent":75,"root":44,"slot":76},"subscription":7}}"""
      );
      assertEquals("""
          {"jsonrpc":"2.0","id":2,"method":"slotUnsubscribe","params":[7]}""", socket.sentText.getLast());

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"rootNotification","params":{"result":42,"subscription":8}}"""
      );
      assertEquals("""
          {"jsonrpc":"2.0","id":3,"method":"rootUnsubscribe","params":[8]}""", socket.sentText.getLast());
    }
  }

  /// A notification method nobody subscribed to generically is ignored without a
  /// frame or an exception.
  @Test
  void unknownNotificationMethodsAreIgnored() {
    try (final var ws = createWebsocket()) {
      final var exceptions = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(exceptions::add);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"mysteryNotification","params":{"result":1,"subscription":9}}"""
      );
      assertTrue(socket.sentText.isEmpty(), socket.sentText.toString());
      assertTrue(exceptions.isEmpty());
    }
  }

  /// Fragments arriving in array-less CharBuffers exercise the buffer-copy side
  /// of every onText branch the array-backed fragmented test does not.
  @Test
  void fragmentedNotificationWithoutABackingArray() {
    try (final var ws = createWebsocket()) {
      final var key = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(key, received::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":23784,"id":2}"""
      );

      final var notification = """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":5199307},"value":{"data":["dGVzdA==","base64"],"executable":false,"lamports":33594,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":4}},"subscription":23784}}""";
      final int half = notification.length() / 2;
      // CharBuffer.wrap(String) has no backing array on either fragment
      ws.onText(socket, CharBuffer.wrap(notification.substring(0, half)), false);
      assertTrue(received.isEmpty());
      ws.onText(socket, CharBuffer.wrap(notification.substring(half)), true);

      assertEquals(1, received.size());
      assertEquals(33594L, received.getFirst().lamports());
    }
  }

  @Test
  void accountUnsubscribe() {
    try (final var ws = createWebsocket()) {
      final var key = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(key, received::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":23784,"id":2}"""
      );

      assertTrue(ws.accountUnsubscribe(key));
      assertFalse(ws.accountUnsubscribe(key));

      // The queued un-subscription is written on the next message driven write cycle,
      // and the notification is no longer routed to the consumer.
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":5199307},"value":{"data":["","base64"],"executable":false,"lamports":1,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":0}},"subscription":23784}}"""
      );
      assertTrue(received.isEmpty());
      assertEquals("""
          {"jsonrpc":"2.0","id":3,"method":"accountUnsubscribe","params":[23784]}""", socket.sentText.getLast()
      );
    }
  }
}
