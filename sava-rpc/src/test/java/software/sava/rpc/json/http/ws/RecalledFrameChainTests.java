package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// A subscribe cancelled while it waits in the outbound chain is recalled at dispatch: its
/// link returns without sending, and the stage it leaves becomes the connection's chain
/// tail. Nothing outside the chain observes that stage. The only readers are the next
/// link's, in `sendSubscription` and in `queueText`, and both start with
/// `exceptionally(_ -> null)`, the same tolerance that keeps a failed send from blocking
/// the chain. A recalled link's stage must therefore never change what is sent after it,
/// or what is reported.
///
/// Each test recalls a frame, chains a successor behind it through one of the two readers,
/// and requires the successor on the wire in order with no error report from any seam.
@ExtendWith(QuietWsLogging.class)
final class RecalledFrameChainTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final PublicKey ACCOUNT_A =
      PublicKey.fromBase58Encoded("GovaE4iu227srtG2s3tZzB4RmWBzw8sTwrCLZz7kN7rY");
  private static final PublicKey ACCOUNT_B =
      PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
  private static final PublicKey ACCOUNT_C =
      PublicKey.fromBase58Encoded("H4vnBqifaSACnKa7acsxstsY1iV1bvJNxsCY7enrd1hq");

  private static SolanaJsonRpcWebsocket websocket(final List<Throwable> reported) {
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
        null,
        (_, _, _) -> {
        },
        (_, error) -> reported.add(error),
        (_, error) -> reported.add(error),
        (_, error) -> reported.add(error)
    );
  }

  private static void admitPending(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket) {
    // A pong runs the maintenance pass, which admits pending subscribes to the chain.
    ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
  }

  /// Opens a transport whose first subscribe (A) is held on the wire, queues B behind it,
  /// and cancels B while it waits, so settling A recalls B.
  private static RecordingWebSocket recallBehindAHeldSend(final SolanaJsonRpcWebsocket ws) {
    assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
    }));
    final var socket = new RecordingWebSocket();
    socket.deferTexts = true;
    ws.onOpen(socket);
    assertTrue(ws.accountSubscribe(ACCOUNT_B, _ -> {
    }));
    admitPending(ws, socket);
    assertTrue(ws.accountUnsubscribe(ACCOUNT_B));
    assertEquals(1, ws.retainedCancellationTombstones(), "B is recalled only if its tombstone is held");
    return socket;
  }

  @Test
  void aSubscribeChainedBehindARecalledFrameIsStillSent() {
    final var reported = new ArrayList<Throwable>();
    try (final var ws = websocket(reported)) {
      final var exceptions = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(exceptions::add);
      final var socket = recallBehindAHeldSend(ws);
      final var onSubC = new ArrayList<Subscription<AccountInfo<byte[]>>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, ACCOUNT_C, onSubC::add, _ -> {
      }));
      admitPending(ws, socket);
      assertEquals(1, socket.sentText.size(), "B and C wait behind A");

      socket.deferredTexts.getFirst().complete(socket);

      assertEquals(2, socket.sentText.size(), "B is recalled and C follows A: " + socket.sentText);
      assertTrue(socket.sentText.getFirst().contains(ACCOUNT_A.toBase58()));
      assertTrue(socket.sentText.getLast().contains(ACCOUNT_C.toBase58()));
      assertEquals(0, ws.retainedCancellationTombstones(), "the recall consumed B's tombstone");
      socket.deferredTexts.getLast().complete(socket);
      assertEquals(1, onSubC.size(), "C's send completes like any other");
      assertEquals(List.of(), reported, "no seam reports the recall");
      assertEquals(List.of(), exceptions);
    }
  }

  @Test
  void aCancellationQueuedAfterARecalledFrameIsStillSent() {
    final var reported = new ArrayList<Throwable>();
    try (final var ws = websocket(reported)) {
      final var exceptions = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(exceptions::add);
      final var socket = recallBehindAHeldSend(ws);
      socket.deferredTexts.getFirst().complete(socket);
      assertEquals(1, socket.sentText.size(), "B was recalled, so the chain ends at its link");
      assertEquals(0, ws.retainedCancellationTombstones(), "the recall consumed B's tombstone");

      // An unknown id draws a cancellation, which queueText chains onto B's recalled link.
      ws.onText(socket, CharBuffer.wrap("""
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":1},\
          "value":{"data":["","base64"],"executable":false,"lamports":1,\
          "owner":"11111111111111111111111111111111","rentEpoch":0,"space":0}},"subscription":999}}"""), true);

      assertEquals(2, socket.sentText.size(), "the cancellation follows the recalled link: " + socket.sentText);
      assertTrue(socket.sentText.getLast().contains("\"method\":\"accountUnsubscribe\",\"params\":[999]"),
          socket.sentText.getLast());
      socket.deferredTexts.getLast().complete(socket);
      assertEquals(List.of(), reported, "no seam reports the recall");
      assertEquals(List.of(), exceptions);
    }
  }
}
