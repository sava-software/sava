package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.nio.CharBuffer;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/// The request-id counter doubles as the closed flag, and `closed()` reads its sign: the ws
/// `# positive sentinel gap` family (`config/pitest/README.md`) accepts the mutant that
/// reads `< 0` as `<= 0`, which differs only while the counter holds zero.
///
/// The oracle is the contract plus the wire. `SolanaRpcWebsocket` says `closed()` "says
/// only that close() was called", and the ids a client puts on the wire are the counter's
/// values: they start at 2 and climb by one per request of every kind, so an open counter
/// reaches zero only by wrapping past `Long.MAX_VALUE`. Close replaces the counter with
/// `Long.MIN_VALUE` before taking the lifecycle lock, and a closed instance mints no id;
/// the only increments after close are lock-held requests that passed their closed check
/// before it, a handful, each moving the counter one step up from `Long.MIN_VALUE`.
@ExtendWith(QuietWsLogging.class)
final class MessageIdSentinelTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final PublicKey ACCOUNT_A =
      PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final PublicKey ACCOUNT_B =
      PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final Pattern REQUEST_ID = Pattern.compile("\"id\":(-?\\d+)");

  private static SolanaJsonRpcWebsocket websocket() {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null, TIMINGS,
        SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(),
        new RecordingExecutor(), null, null, (_, _, _) -> {
        }, null, null, null
    );
  }

  private static List<Long> requestIds(final RecordingWebSocket socket) {
    return socket.sentText.stream()
        .map(frame -> {
          final var matcher = REQUEST_ID.matcher(frame);
          assertTrue(matcher.find(), () -> "no request id in " + frame);
          return Long.parseLong(matcher.group(1));
        })
        .toList();
  }

  @Test
  void requestIdsStartAtTwoAndClimbByOneWhileOpen() throws InterruptedException {
    try (final var ws = websocket()) {
      assertFalse(ws.closed(), "a fresh instance was never closed");
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      assertTrue(ws.accountSubscribe(ACCOUNT_B, _ -> {
      }));
      assertTrue(ws.slotSubscribe(_ -> {
      }));
      assertTrue(ws.rootSubscribe(_ -> {
      }));
      assertTrue(ws.subscribe(
          "eventSubscribe", "eventUnsubscribe", "eventNotification",
          "event", "", JsonIterator::readString, null, _ -> {
          }
      ));
      assertFalse(ws.closed());

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("{\"jsonrpc\":\"2.0\",\"result\":700,\"id\":2}"), true);
      assertTrue(ws.accountUnsubscribe(ACCOUNT_A));
      ws.checkCycle(0L);

      assertEquals(List.of(2L, 3L, 4L, 5L, 6L, 7L), requestIds(socket),
          () -> "five subscribes then the cancellation: " + socket.sentText);
      assertTrue(socket.sentText.getLast().contains("accountUnsubscribe"), socket.sentText::toString);
      assertFalse(ws.closed(), "minting ids never reads as closed");
    }
  }

  @Test
  void onlyCloseFlipsClosedAndAClosedInstanceMintsNoId() throws InterruptedException {
    final var ws = websocket();
    final var socket = new RecordingWebSocket();
    try {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      ws.onOpen(socket);
      assertEquals(List.of(2L), requestIds(socket));
      assertFalse(ws.closed());

      ws.close();
      assertTrue(ws.closed(), "close() is what closed() reports");

      assertFalse(ws.accountSubscribe(ACCOUNT_B, _ -> {
      }));
      assertFalse(ws.slotSubscribe(_ -> {
      }));
      assertFalse(ws.subscribe(
          "eventSubscribe", "eventUnsubscribe", "eventNotification",
          "event", "", JsonIterator::readString, null, _ -> {
          }
      ));
      assertFalse(ws.accountUnsubscribe(ACCOUNT_A));
      assertNull(ws.connect());
      ws.checkCycle(0L);
      ws.close();

      assertEquals(List.of(2L), requestIds(socket), "a closed instance put no request on the wire");
      assertTrue(ws.closed(), "nothing after close reads as open");
    } finally {
      ws.close();
    }
  }
}
