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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/// Pins the `maxMessageLength` cap on websocket text-message reassembly — the
/// close of the 2026-07-31 review finding (`HARDENING_NOTES.md`): `ensureCapacity`
/// doubled the reassembly buffer without bound, so a server that never sent a
/// final frame grew it until OOM. An excluded message is connection-fatal, not a
/// parse failure: the partial message is dropped, the connection aborted, and the
/// `onError` seam decides what happens next — so these tests also pin that the
/// reassembly state machine is clean afterwards.
@ExtendWith(QuietWsLogging.class)
final class MessageSizeCapTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);

  private static final String NOTIFICATION = """
      {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":5199307},"value":{"data":["dGVzdA==","base64"],"executable":false,"lamports":33594,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":4}},"subscription":11}}""";

  private static SolanaJsonRpcWebsocket createWebsocket(final int maxMessageLength, final List<Throwable> errors) {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT,
        SolanaAccounts.MAIN_NET,
        Commitment.CONFIRMED,
        null,
        TIMINGS,
        maxMessageLength,
        new TestClock(),
        new RecordingExecutor(),
        null,
        _ -> {
        },
        (_, _, _) -> {
        },
        (_, error) -> errors.add(error),
        null, null
    );
  }

  /// Subscribes, opens, and confirms subscription id 11 so `NOTIFICATION` has a
  /// live target; returns the consumer's sink.
  private static List<AccountInfo<byte[]>> subscribe(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket) {
    final var received = new ArrayList<AccountInfo<byte[]>>();
    final var msgId = new AtomicLong();
    final var key = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
    assertTrue(ws.accountSubscribe(key, sub -> msgId.set(sub.msgId()), received::add));
    ws.onOpen(socket);
    ws.onText(socket, CharBuffer.wrap("{\"jsonrpc\":\"2.0\",\"result\":11,\"id\":" + msgId.get() + "}"), true);
    return received;
  }

  @Test
  void oversizedFragmentedMessageAbortsAndRecovers() {
    final var errors = new ArrayList<Throwable>();
    try (final var ws = createWebsocket(512, errors)) {
      final var socket = new RecordingWebSocket();
      final var received = subscribe(ws, socket);

      final var fragment = "x".repeat(300);
      ws.onText(socket, CharBuffer.wrap(fragment), false);
      assertFalse(socket.aborted);
      assertTrue(errors.isEmpty());

      // 600 accumulated chars cross the 512 cap: connection-fatal
      ws.onText(socket, CharBuffer.wrap(fragment), false);
      assertTrue(socket.aborted);
      assertEquals(1, errors.size());
      final var error = errors.getFirst();
      assertInstanceOf(IllegalStateException.class, error);
      assertEquals(
          "600 char message from api.mainnet-beta.solana.com exceeds maxMessageLength 512",
          error.getMessage()
      );
      assertTrue(received.isEmpty());

      // the partial message was dropped with it: a canonical whole notification
      // dispatches without the 300 stale chars prepending themselves
      ws.onText(socket, CharBuffer.wrap(NOTIFICATION), true);
      assertEquals(1, received.size());
      assertEquals(33594L, received.getFirst().lamports());
      assertEquals(1, errors.size());
    }
  }

  @Test
  void messageExactlyAtCapDispatches() {
    final var errors = new ArrayList<Throwable>();
    try (final var ws = createWebsocket(NOTIFICATION.length(), errors)) {
      final var socket = new RecordingWebSocket();
      final var received = subscribe(ws, socket);

      // whole, then fragmented: both sum to exactly the cap
      ws.onText(socket, CharBuffer.wrap(NOTIFICATION), true);
      final int half = NOTIFICATION.length() / 2;
      ws.onText(socket, CharBuffer.wrap(NOTIFICATION.substring(0, half)), false);
      ws.onText(socket, CharBuffer.wrap(NOTIFICATION.substring(half)), true);

      assertEquals(2, received.size());
      assertFalse(socket.aborted);
      assertTrue(errors.isEmpty());
    }
  }

  @Test
  void oneCharOverCapAborts() {
    final var errors = new ArrayList<Throwable>();
    try (final var ws = createWebsocket(NOTIFICATION.length() - 1, errors)) {
      final var socket = new RecordingWebSocket();
      final var received = subscribe(ws, socket);

      ws.onText(socket, CharBuffer.wrap(NOTIFICATION), true);

      assertTrue(received.isEmpty());
      assertTrue(socket.aborted);
      assertEquals(1, errors.size());
    }
  }

  @Test
  void builderValidatesAndDefaultsTheCap() {
    final var builder = new SolanaRpcWebsocketBuilder();
    assertEquals(SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, builder.maxMessageLength());

    assertSame(builder, builder.maxMessageLength(1));
    assertEquals(1, builder.maxMessageLength());
    assertSame(builder, builder.maxMessageLength(1024));
    assertEquals(1024, builder.maxMessageLength());

    assertThrows(IllegalArgumentException.class, () -> builder.maxMessageLength(0));
    assertThrows(IllegalArgumentException.class, () -> builder.maxMessageLength(-1));
    assertEquals(1024, builder.maxMessageLength());
  }
}
