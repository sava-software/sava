package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;

import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Regression tests for a defect in the account notification path, failing until it is
/// fixed. The defect: an account notification whose `params.result` carries an unknown
/// member named `subscription` after `value` is attributed to that member's id instead
/// of to `params.subscription`.
///
/// Oracle: the notification format puts the subscription id at `params.subscription`, and
/// RFC 8259 makes member names unique only within their own object, so a member of
/// `result` is payload. The logs, program and signature paths already attribute these
/// frames correctly ([NestedSubscriptionMemberTests]).
///
/// Cause: `publish`'s factory overload marks the value, then calls `skipRestOfObject()`
/// with the cursor in front of the value, which consumes only the value object. Its
/// forward `skipUntil("subscription")` therefore scans the rest of `result`, not of
/// `params`, and finds the nested member. The item overload the logs and program paths
/// use calls the same `skipRestOfObject()` after the value parse, where it consumes the
/// rest of `result`. On frames without such a member the forward scan misses and the
/// `params` rescan finds the right id, which is why no other test sees the defect.
@ExtendWith(QuietWsLogging.class)
final class AccountNotificationAttributionTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final PublicKey KEY_A =
      PublicKey.fromBase58Encoded("GovaE4iu227srtG2s3tZzB4RmWBzw8sTwrCLZz7kN7rY");
  private static final PublicKey KEY_B =
      PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
  private static final String NOTIFICATION = """
      {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":1},\
      "value":{"data":["","base64"],"executable":false,"lamports":11,\
      "owner":"11111111111111111111111111111111","rentEpoch":0,"space":0},\
      "subscription":777},"subscription":23784}}""";

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

  /// Fails on the current code: the notification is dropped and an `accountUnsubscribe`
  /// for 777, an id this client never held, goes out.
  @Test
  void aSubscriptionMemberAfterTheAccountValueDoesNotDropTheNotification() {
    try (final var ws = websocket()) {
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(KEY_A, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":23784,"id":2}""");

      feed(ws, socket, NOTIFICATION);

      assertAll(
          () -> assertEquals(1, received.size(), "params.subscription names the registration"),
          () -> assertEquals(List.of(), socket.sentText.stream().filter(m -> m.contains("accountUnsubscribe")).toList(),
              "the nested id is payload, not a stream to cancel")
      );
    }
  }

  /// Fails on the current code: when 777 is another account registration, A's account data
  /// is delivered to B, parsed under B's public key.
  @Test
  void aSubscriptionMemberAfterTheAccountValueDoesNotDeliverItToAnotherAccount() {
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

      feed(ws, socket, NOTIFICATION);

      assertAll(
          () -> assertEquals(1, receivedA.size(), "params.subscription names A"),
          () -> assertEquals(List.of(), receivedB, "B's key must not be bound to A's account data")
      );
    }
  }
}
