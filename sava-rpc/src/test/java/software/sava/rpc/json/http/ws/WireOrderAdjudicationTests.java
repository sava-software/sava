package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;

import java.math.BigInteger;
import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/// A true cancellation acknowledgement is adjudicated against the subscribe attempts around
/// it by wire order: an attempt transmitted before the cancellation was cancelled by it,
/// one transmitted after it postdates it. The engine compares an attempt's wire ordinal
/// with the cancellation's using `<` at four places (the casualty replay, the kill record,
/// the dead-grant check, and the kill sweep); the ws `# strict wire ordinal` family claims
/// `<=` decides the same, because both ordinals come from one pre-incremented counter and
/// no attempt can share a cancellation's position on the wire.
///
/// The closest two transmissions can be is adjacent. Each test builds both adjacent shapes
/// around one cancellation, reads the positions off the recording socket rather than from
/// the ordinals, and asserts the outcome wire order demands on each side.
@ExtendWith(QuietWsLogging.class)
final class WireOrderAdjudicationTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final PublicKey ACCOUNT_A = PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final PublicKey ACCOUNT_B = PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");

  private static SolanaJsonRpcWebsocket createWebsocket() {
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
        null,
        null,
        null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  private static String accountNotification(final long subscription) {
    return """
        {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":1},"value":{"data":["","base64"],"executable":false,"lamports":1,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":0}},"subscription":%d}}"""
        .formatted(subscription);
  }

  /// Position on the wire of the only frame containing `token`.
  private static int wirePosition(final RecordingWebSocket socket, final String token) {
    final var positions = IntStream.range(0, socket.sentText.size())
        .filter(i -> socket.sentText.get(i).contains(token))
        .toArray();
    assertEquals(1, positions.length, () -> token + " is not on the wire exactly once: " + socket.sentText);
    return positions[0];
  }

  private static long framesContaining(final RecordingWebSocket socket, final String token) {
    return socket.sentText.stream().filter(frame -> frame.contains(token)).count();
  }

  /// The casualty replay: a live grant of the cancelled id whose attempt is adjacent before
  /// the cancellation is replayed; one adjacent after it is kept.
  @Test
  void aLiveGrantAdjacentBeforeACancellationIsReplayedAndOneAdjacentAfterIsKept() throws InterruptedException {
    try (final var ws = createWebsocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // request 2
      assertTrue(ws.accountUnsubscribe(ACCOUNT_A)); // unanswered: tombstoned
      final var successor = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, ACCOUNT_A, successor::set, _ -> {
      }));
      ws.checkCycle(0L); // successor request 3
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}"""); // tombstone: cancellation 4 of 700
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":3}"""); // the successor reuses id 700
      assertEquals(wirePosition(socket, "\"id\":3,") + 1, wirePosition(socket, "[700]"),
          "the successor's attempt is adjacent before the cancellation");

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":4}""");
      assertNull(successor.get().subId(), "a grant whose attempt preceded the cancellation died with it");
      ws.checkCycle(0L);
      assertEquals(2, framesContaining(socket, "\"id\":3,"), "the cancelled grant is re-sent");
    }

    try (final var ws = createWebsocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // request 2
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":2}""");
      assertTrue(ws.accountUnsubscribe(ACCOUNT_A)); // cancellation of 700 queued
      final var successor = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, ACCOUNT_A, successor::set, received::add));
      ws.checkCycle(0L); // flush first: cancellation 4, then request 3
      assertEquals(wirePosition(socket, "[700]") + 1, wirePosition(socket, "\"id\":3,"),
          "the successor's attempt is adjacent after the cancellation");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":700,"id":3}""");

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":4}""");
      assertEquals(BigInteger.valueOf(700), successor.get().subId(),
          "a grant whose attempt followed the cancellation postdates it");
      ws.checkCycle(0L);
      assertEquals(1, framesContaining(socket, "\"id\":3,"), "the live grant is not re-sent");
      feed(ws, socket, accountNotification(700));
      assertEquals(1, received.size(), "the kept grant still delivers");
    }
  }

  /// The kill record: a pending attempt adjacent before the cancellation makes the
  /// acknowledgement record a kill for its id; one adjacent after records nothing.
  @Test
  void aPendingAttemptAdjacentBeforeACancellationRecordsAKillAndOneAdjacentAfterDoesNot() throws InterruptedException {
    try (final var ws = createWebsocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // request 2
      feed(ws, socket, accountNotification(800)); // unknown id: cancellation 3
      assertEquals(wirePosition(socket, "\"id\":2,") + 1, wirePosition(socket, "[800]"));
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":3}""");
      assertEquals(2, ws.retainedOrdinalEntries(), "the pending attempt's ordinal and the recorded kill");
    }

    try (final var ws = createWebsocket()) {
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, accountNotification(800)); // unknown id: cancellation 2
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      ws.checkCycle(0L); // request 3
      assertEquals(wirePosition(socket, "[800]") + 1, wirePosition(socket, "\"id\":3,"));
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":2}""");
      assertEquals(1, ws.retainedOrdinalEntries(), "only the postdating attempt's ordinal; no kill");
    }
  }

  /// The dead-grant check: a grant of a killed id to an attempt adjacent below the kill is
  /// replayed; one to an attempt adjacent above it is installed and consumes the kill.
  @Test
  void aGrantAdjacentBelowAKillIsReplayedAndOneAdjacentAboveIsInstalled() throws InterruptedException {
    try (final var ws = createWebsocket()) {
      final var handle = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, ACCOUNT_A, handle::set, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // request 2
      feed(ws, socket, accountNotification(800)); // unknown id: cancellation 3
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":3}"""); // kill of 800 recorded
      assertEquals(wirePosition(socket, "\"id\":2,") + 1, wirePosition(socket, "[800]"));

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":800,"id":2}""");
      assertNull(handle.get().subId(), "a grant below the kill arrived already cancelled");
      ws.checkCycle(0L);
      assertEquals(2, framesContaining(socket, "\"id\":2,"), "the dead grant is re-sent");
      feed(ws, socket, accountNotification(800));
      assertTrue(received.isEmpty(), "the dead grant's id delivers nothing");
    }

    try (final var ws = createWebsocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // request 2
      feed(ws, socket, accountNotification(800)); // unknown id: cancellation 3
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":3}"""); // kill of 800 recorded
      final var handle = new AtomicReference<Subscription<AccountInfo<byte[]>>>();
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, ACCOUNT_B, handle::set, received::add));
      ws.checkCycle(0L); // request 4
      assertEquals(wirePosition(socket, "[800]") + 1, wirePosition(socket, "\"id\":4,"));

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":800,"id":4}""");
      assertEquals(BigInteger.valueOf(800), handle.get().subId(), "a grant above the kill is fresh");
      assertEquals(2, ws.retainedOrdinalEntries(), "two attempt ordinals; the kill is consumed");
      feed(ws, socket, accountNotification(800));
      assertEquals(1, received.size(), "the fresh grant delivers");
    }
  }

  /// The kill sweep: a kill stays while a pending attempt adjacent below it can still be
  /// granted the killed id, and is swept once only an attempt adjacent above it pends.
  @Test
  void theSweepKeepsAKillForAnAdjacentPredatingAttemptAndDropsItForAnAdjacentPostdatingOne()
      throws InterruptedException {
    try (final var ws = createWebsocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // request 2
      feed(ws, socket, accountNotification(800)); // unknown id: cancellation 3
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":3}""");
      assertEquals(wirePosition(socket, "\"id\":2,") + 1, wirePosition(socket, "[800]"));
      ws.checkCycle(0L);
      assertEquals(2, ws.retainedOrdinalEntries(), "the predating attempt keeps the kill");
    }

    try (final var ws = createWebsocket()) {
      assertTrue(ws.accountSubscribe(ACCOUNT_A, _ -> {
      }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket); // request 2
      feed(ws, socket, accountNotification(800)); // unknown id: cancellation 3
      assertTrue(ws.accountSubscribe(ACCOUNT_B, _ -> {
      }));
      ws.checkCycle(0L); // request 4
      assertEquals(wirePosition(socket, "[800]") + 1, wirePosition(socket, "\"id\":4,"));
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":3}"""); // request 2 predates it: a kill
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":801,"id":2}"""); // request 2 resolves to another id
      assertEquals(3, ws.retainedOrdinalEntries(), "two attempt ordinals and the kill");
      ws.checkCycle(0L);
      assertEquals(2, ws.retainedOrdinalEntries(), "only the postdating attempt pends: the kill is swept");
    }
  }
}
