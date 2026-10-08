package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;
import software.sava.rpc.json.http.response.TxLogs;
import software.sava.rpc.json.http.response.TxResult;

import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// A notification belongs to the registration its `params.subscription` names. The payload
/// is nested under `params.result`, and RFC 8259 makes a member name unique only within its
/// own object, so a payload can carry a member that is also called `subscription`: an
/// unknown field a provider adds to `result` or to an account object. Attribution must
/// read the `params` member and skip the nested one.
///
/// Every frame here names the registered id in `params` and an unregistered id (777) in a
/// nested member. Reading the nested id drops the notification and answers it with an
/// unsubscribe for 777, or, when 777 is another live registration, delivers the payload to
/// that registration instead.
@ExtendWith(QuietWsLogging.class)
final class NestedSubscriptionMemberTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final PublicKey KEY_A =
      PublicKey.fromBase58Encoded("GovaE4iu227srtG2s3tZzB4RmWBzw8sTwrCLZz7kN7rY");
  private static final PublicKey KEY_B =
      PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
  private static final String SIGNATURE =
      "2EBVM6cB8vAAD93Ktr6Vd8p67XPbQzCJX47MpReuiCXJAtcjaxpvWpcg9Ege1Nr5Tk3a2GFrByT7WPBjdsTycY9b";
  private static final String ACCOUNT_VALUE = """
      {"data":["dGVzdA==","base64"],"executable":false,"lamports":33594,\
      "owner":"GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc","rentEpoch":636,"space":80}""";

  private static SolanaJsonRpcWebsocket websocket() {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(),
        new RecordingExecutor(),
        null,
        _ -> {
        },
        (_, _, _) -> {
        },
        null, null, null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  private static List<String> unsubscribes(final RecordingWebSocket socket) {
    return socket.sentText.stream().filter(m -> m.contains("nsubscribe")).toList();
  }

  @Test
  void aSubscriptionMemberAfterTheLogsValueDoesNotRedirectTheNotification() {
    try (final var ws = websocket()) {
      final var received = new ArrayList<TxLogs>();
      assertTrue(ws.logsSubscribe(KEY_A, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":24040,"id":2}""");

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"logsNotification","params":{"result":{"context":{"slot":5208469},\
          "value":{"signature":"sigN","err":null,"logs":["l"]},"subscription":777},"subscription":24040}}""");

      assertEquals(1, received.size(), "params.subscription names the registration");
      assertEquals("sigN", received.getFirst().signature());
      assertEquals(List.of(), unsubscribes(socket), "the nested id is payload, not a stream to cancel");
    }
  }

  @Test
  void aSubscriptionMemberAfterTheProgramValueDoesNotRedirectTheNotification() {
    try (final var ws = websocket()) {
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.programSubscribe(KEY_A, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":24040,"id":2}""");

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"programNotification","params":{"result":{"context":{"slot":5208469},\
          "value":{"pubkey":"H4vnBqifaSACnKa7acsxstsY1iV1bvJNxsCY7enrd1hq","account":""" + ACCOUNT_VALUE + """
          },"subscription":777},"subscription":24040}}""");

      assertEquals(1, received.size(), "params.subscription names the registration");
      assertEquals(33594L, received.getFirst().lamports());
      assertEquals(List.of(), unsubscribes(socket), "the nested id is payload, not a stream to cancel");
    }
  }

  @Test
  void aSubscriptionMemberAfterTheSignatureValueDoesNotRedirectTheNotification() {
    try (final var ws = websocket()) {
      final var received = new ArrayList<TxResult>();
      assertTrue(ws.signatureSubscribe(SIGNATURE, false, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":24006,"id":2}""");

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"signatureNotification","params":{"result":{"context":{"slot":5207624},\
          "value":{"err":null},"subscription":777},"subscription":24006}}""");

      assertEquals(1, received.size(), "params.subscription names the registration");
      assertEquals(5207624L, received.getFirst().context().slot());
      assertEquals(List.of(), unsubscribes(socket), "the nested id is payload, not a stream to cancel");
    }
  }

  @Test
  void aSubscriptionMemberInsideTheAccountObjectDoesNotRedirectTheNotification() {
    try (final var ws = websocket()) {
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(KEY_A, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":23784,"id":2}""");

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":1},\
          "value":{"data":["","base64"],"executable":false,"lamports":5,"subscription":777,\
          "owner":"11111111111111111111111111111111","rentEpoch":0,"space":0}},"subscription":23784}}""");

      assertEquals(1, received.size(), "params.subscription names the registration");
      final var info = received.getFirst();
      assertEquals(KEY_A, info.pubKey());
      assertEquals(5L, info.lamports(), "the unknown account member is skipped by the parse");
      assertEquals(List.of(), unsubscribes(socket), "the nested id is payload, not a stream to cancel");
    }
  }

  /// The nested id is another live registration of the same channel: reading it would hand
  /// one registration's payload to the other.
  @Test
  void aNestedIdOfAnotherLogsRegistrationDoesNotReceiveThePayload() {
    try (final var ws = websocket()) {
      final var receivedA = new ArrayList<TxLogs>();
      final var receivedB = new ArrayList<TxLogs>();
      assertTrue(ws.logsSubscribe(KEY_A, receivedA::add));
      assertTrue(ws.logsSubscribe(KEY_B, receivedB::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":24040,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":777,"id":3}""");

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"logsNotification","params":{"result":{"context":{"slot":9},\
          "value":{"signature":"forA","err":null,"logs":[]},"subscription":777},"subscription":24040}}""");

      assertEquals(List.of("forA"), receivedA.stream().map(TxLogs::signature).toList());
      assertEquals(List.of(), receivedB, "the nested id's registration must not receive A's payload");
    }
  }

  /// The account parse binds the registration's own key to the payload, so reading a nested
  /// id of another account registration would label A's data with B's key.
  @Test
  void aNestedIdOfAnotherAccountRegistrationDoesNotReceiveThePayload() {
    try (final var ws = websocket()) {
      final var receivedA = new ArrayList<AccountInfo<byte[]>>();
      final var receivedB = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(KEY_A, receivedA::add));
      assertTrue(ws.accountSubscribe(KEY_B, receivedB::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":23784,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":777,"id":3}""");

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":1},\
          "value":{"data":["","base64"],"executable":false,"lamports":11,"subscription":777,\
          "owner":"11111111111111111111111111111111","rentEpoch":0,"space":0}},"subscription":23784}}""");

      assertEquals(1, receivedA.size());
      assertEquals(KEY_A, receivedA.getFirst().pubKey());
      assertEquals(11L, receivedA.getFirst().lamports());
      assertEquals(List.of(), receivedB, "B's key must not be bound to A's account data");
    }
  }
}
