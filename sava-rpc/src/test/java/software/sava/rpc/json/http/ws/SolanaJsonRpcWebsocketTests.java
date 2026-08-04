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
import java.util.Arrays;
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

  /// An inbound notification is a write opportunity: after dispatch,
  /// `lockAndHandlePendingSubscriptions` must flush any subscribe frames queued
  /// since the last write — with no background thread, inbound traffic is what
  /// drives them out.
  @Test
  void notificationDispatchFlushesPendingSubscriptions() {
    try (final var ws = createWebsocket()) {
      final var confirmed = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(confirmed, received::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":23784,"id":2}"""
      );

      final var pending = PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
      assertTrue(ws.accountSubscribe(pending, received::add));
      final int sentBefore = socket.sentText.size();

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":5199307},"value":{"data":["dGVzdA==","base64"],"executable":false,"lamports":33594,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":4}},"subscription":23784}}"""
      );

      assertEquals(1, received.size());
      assertTrue(
          socket.sentText.stream().skip(sentBefore)
              .anyMatch(m -> m.contains("\"method\":\"accountSubscribe\"")
                  && m.contains("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")),
          "pending subscribe frame not flushed by the notification: " + socket.sentText
      );
    }
  }

  /// The dispatch catch is log *plus* notify, never log alone: a RuntimeException
  /// thrown by a notification's parser must reach every exception subscriber.
  @Test
  void dispatchFailureReachesExceptionSubscribers() {
    try (final var ws = createWebsocket()) {
      final var key = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
      final var received = new ArrayList<AccountInfo<byte[]>>();
      final var exceptions = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(exceptions::add);
      assertTrue(ws.accountSubscribe(key, received::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":23784,"id":2}"""
      );

      // "!!!" is not base64, so the account parser throws mid-dispatch
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":5199307},"value":{"data":["!!!","base64"],"executable":false,"lamports":33594,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":4}},"subscription":23784}}"""
      );

      assertTrue(received.isEmpty());
      assertEquals(1, exceptions.size());
    }
  }

  /// An un-subscription is confirmed with a boolean `result`, which parses to a
  /// `SubConfirmation` carrying no subscription id — the one shape that reaches
  /// the confirmation branch's `else`. Nothing should be recorded and nothing
  /// should be reported: forcing the `jsonRpcException() != null` test true
  /// dereferences a null exception, and the resulting NPE lands in the method's
  /// catch, which reports to the exception subscribers. Asserting they stay
  /// empty is what makes that mutant observable.
  @Test
  void unsubscribeConfirmationIsAcceptedSilently() {
    try (final var ws = createWebsocket()) {
      final var exceptions = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(exceptions::add);

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":7}"""
      );
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":false,"id":8}"""
      );

      assertTrue(exceptions.isEmpty(), () -> "un-subscription confirmation reported an error: " + exceptions);
    }
  }

  /// The unknown-subscription auto-unsubscribe has two implementations: the
  /// generic one (pinned by `unknownGenericSubscriptionIdUnsubscribes`) and the
  /// eagerly-parsed *item* overload the `logs` channel dispatches through. Only
  /// the generic one was covered, leaving the item overload's miss branch and its
  /// `sendUnSubscription` call unexercised — a stale server-side subscription
  /// would have gone on delivering with nothing to cancel it.
  @Test
  void anUnknownLogsSubscriptionIdUnsubscribes() {
    try (final var ws = createWebsocket()) {
      final var key = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
      final var logs = new ArrayList<TxLogs>();
      assertTrue(ws.logsSubscribe(Commitment.CONFIRMED, key, logs::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":24040,"id":2}"""
      );

      // a notification for a subscription this client does not know about
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"logsNotification","params":{"subscription":999,"result":{"value":{"signature":"sigR","err":null,"logs":["log line"]},"context":{"slot":5208470}}}}"""
      );

      assertTrue(logs.isEmpty(), "an unknown subscription must not deliver");
      assertTrue(socket.sentText.getLast().contains("logsUnsubscribe"),
          () -> "no un-subscription was sent for the unknown id: " + socket.sentText);
      assertTrue(socket.sentText.getLast().contains("999"),
          () -> "the un-subscription named the wrong id: " + socket.sentText);
    }
  }

  /// A duplicate subscribe must be rejected by the channel method's own
  /// `sub == null || !sub.containsKey(commitment)` guard, *before* reaching
  /// `queueSubscription` — which opens with `msgId.incrementAndGet()` and would
  /// burn a message id on the way to returning false.
  ///
  /// Both routes answer `false`, so asserting the return value alone cannot tell
  /// them apart, which is why the forced-true operand survived every existing
  /// duplicate-subscribe test. The id sequence can: a rejected duplicate that
  /// consumed an id leaves a gap in the ids actually written to the socket.
  @Test
  void aRejectedDuplicateSubscribeConsumesNoMessageId() {
    try (final var ws = createWebsocket()) {
      final var first = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
      final var second = PublicKey.fromBase58Encoded("11111111111111111111111111111111");
      final var logs = new ArrayList<TxLogs>();

      final var accounts = new ArrayList<AccountInfo<byte[]>>();
      final var sigs = new ArrayList<TxResult>();
      final var sig = "5j7s6NiJS3JAkvgkoc18WVAsiSaci2pxB2A6ueCJP4tprA2TFg9wSyTLeYouxPBJEMzJinENTkpA52YStRW5Dia7";

      // one accepted + one rejected duplicate per channel: each channel carries its
      // own copy of the guard, so each needs its own duplicate to pin it
      assertTrue(ws.logsSubscribe(Commitment.CONFIRMED, first, logs::add));
      assertFalse(ws.logsSubscribe(Commitment.CONFIRMED, first, logs::add), "duplicate logs subscribe");
      assertTrue(ws.programSubscribe(first, accounts::add));
      assertFalse(ws.programSubscribe(first, accounts::add), "duplicate program subscribe");
      assertTrue(ws.signatureSubscribe(sig, true, sigs::add));
      assertFalse(ws.signatureSubscribe(sig, true, sigs::add), "duplicate signature subscribe");
      assertTrue(ws.logsSubscribe(Commitment.CONFIRMED, second, logs::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);

      final var ids = new ArrayList<Long>();
      final var idPattern = java.util.regex.Pattern.compile("\"id\":(\\d+)");
      for (final var sent : socket.sentText) {
        final var matcher = idPattern.matcher(sent);
        if (matcher.find()) {
          ids.add(Long.parseLong(matcher.group(1)));
        }
      }

      assertEquals(4, ids.size(), () -> "expected one frame per accepted subscription: " + socket.sentText);
      for (int i = 1; i < ids.size(); i++) {
        final int index = i;
        assertEquals(ids.get(i - 1) + 1, ids.get(i),
            () -> "a rejected duplicate burned message id " + (ids.get(index - 1) + 1) + ", so it reached "
                + "queueSubscription instead of being stopped by its channel guard: " + ids);
      }
    }
  }

  /// The invariant the accepted `# defensive scan` family rests on, made
  /// executable. Those rows are the *match* inside `removeDanglingSub` and the
  /// generic `unsubscribe` scan — the recovery path for a subscription present
  /// in `subscriptionsBySubId` but gone from its channel map. It is accepted as
  /// unreachable because `queueUnsubscribe(Subscription)` removes from **both**
  /// maps, so they cannot diverge through any public sequence.
  ///
  /// That is a claim about code that can change, so it is pinned rather than
  /// asserted in prose: a second un-subscribe of an already-confirmed,
  /// already-removed subscription must report `false`. If a future edit ever
  /// leaves the by-sub-id entry behind, the scan finds it, this returns `true`,
  /// and the acceptance is revisited — instead of the family quietly becoming
  /// reachable while the README still says it is not.
  @Test
  void unsubscribingTwiceFindsNothingDangling() {
    try (final var ws = createWebsocket()) {
      final var key = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(Commitment.CONFIRMED, key, received::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":23784,"id":2}"""
      );

      assertTrue(ws.accountUnsubscribe(Commitment.CONFIRMED, key), "the map-first removal should report true");
      assertFalse(ws.accountUnsubscribe(Commitment.CONFIRMED, key),
          "a second un-subscribe found a dangling by-sub-id entry: the two maps diverged, "
              + "so the accepted defensive-scan family is reachable and needs re-triage");
    }
  }

  /// A whole array-backed frame takes the zero-copy path: the message is parsed in
  /// place at `position + arrayOffset`. Both terms must survive, so one frame puts
  /// the pad in position and the other slices it into arrayOffset.
  @Test
  void wholeArrayBackedNotificationDispatchesInPlace() {
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
      final char[] backing = new char[7 + notification.length() + 5];
      notification.getChars(0, notification.length(), backing, 7);

      ws.onText(socket, CharBuffer.wrap(backing, 7, notification.length()), true);
      assertEquals(1, received.size());

      ws.onText(socket, CharBuffer.wrap(backing, 7, notification.length()).slice(), true);
      assertEquals(2, received.size());
      assertEquals(33594L, received.getLast().lamports());
    }
  }

  /// A whole array-less frame longer than the 4096-char starting buffer must grow
  /// it before copying; the fragmented growth test never reaches this single-frame
  /// branch.
  @Test
  void largeArrayLessNotificationGrowsTheBuffer() {
    try (final var ws = createWebsocket()) {
      final var key = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(key, received::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":23784,"id":2}"""
      );

      final var data = "A".repeat(6000);
      final var notification = """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":5199307},"value":{"data":["%s","base64"],"executable":false,"lamports":33594,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":4500}},"subscription":23784}}"""
          .formatted(data);
      // CharBuffer.wrap(String) has no backing array: the copy must grow the buffer first
      ws.onText(socket, CharBuffer.wrap(notification), true);

      assertEquals(1, received.size());
      assertEquals(4500, received.getFirst().data().length);
    }
  }

  /// A sliced fragment carries its pad in arrayOffset with position zero — the
  /// accumulate copy's source index is the sum of both, and a sliced *non-last*
  /// fragment is the only shape that separates the terms on that path.
  @Test
  void slicedNonLastFragmentAccumulatesFromItsArrayOffset() {
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
      final char[] backing = new char[9 + half];
      notification.getChars(0, half, backing, 9);

      ws.onText(socket, CharBuffer.wrap(backing, 9, half).slice(), false);
      assertTrue(received.isEmpty());
      ws.onText(socket, CharBuffer.wrap(notification.substring(half)), true);

      assertEquals(1, received.size());
      assertEquals(33594L, received.getFirst().lamports());
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

  /// Notification field order is provider JSON, not a contract: when "subscription"
  /// precedes "result", or "value" precedes "context", the forward scan comes up empty
  /// and the parser must re-scan from its mark instead of dropping the notification.
  @Test
  void reorderedNotificationFields() {
    try (final var ws = createWebsocket()) {
      final var key = PublicKey.fromBase58Encoded("GovaE4iu227srtG2s3tZzB4RmWBzw8sTwrCLZz7kN7rY");
      final var received = new ArrayList<TxLogs>();
      assertTrue(ws.logsSubscribe(key, received::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":24040,"id":2}"""
      );

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"logsNotification","params":{"subscription":24040,"result":{"value":{"signature":"sigR","err":null,"logs":["log line"]},"context":{"slot":5208470}}}}"""
      );

      assertEquals(1, received.size());
      final var txLogs = received.getFirst();
      assertEquals("sigR", txLogs.signature());
      assertEquals(List.of("log line"), txLogs.logs());
      assertEquals(5208470L, txLogs.context().slot());
    }
  }

  /// A generic notification whose subscription id matches no generic subscription
  /// derives the unsubscribe method from the notification method name; the outgoing
  /// frame must carry the derived name, not the notification's.
  @Test
  void unknownGenericSubscriptionIdUnsubscribes() {
    try (final var ws = createWebsocket()) {
      final var received = new ArrayList<Long>();
      assertTrue(ws.subscribe(
          "voteSubscribe", "voteUnsubscribe", "voteNotification",
          "vote", "",
          ji -> ji.skipUntil("slots").openArray().readLong(),
          null,
          received::add
      ));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":99,"id":2}"""
      );

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"voteNotification","params":{"result":{"slots":[1,2]},"subscription":555}}"""
      );
      assertTrue(received.isEmpty());
      assertEquals("""
          {"jsonrpc":"2.0","id":3,"method":"voteUnsubscribe","params":[555]}""", socket.sentText.getLast()
      );
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

  /// Params field order is the node's choice: `subscription` may precede `result`.
  /// The dispatch paths carry mark/reset fallbacks for exactly that, and an
  /// exception subscriber pins that no parse goes through the catch-all instead.
  @Test
  void notificationParamsFieldOrderDoesNotMatter() {
    try (final var ws = createWebsocket()) {
      final var exceptions = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(exceptions::add);

      final var key = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
      final var accounts = new ArrayList<AccountInfo<byte[]>>();
      final var txResults = new ArrayList<TxResult>();
      final var votes = new ArrayList<Long>();
      assertTrue(ws.accountSubscribe(key, accounts::add));
      assertTrue(ws.signatureSubscribe("sigF", txResults::add));
      assertTrue(ws.subscribe("voteSubscribe", "voteUnsubscribe", "voteNotification",
          "vote", "", ji -> ji.skipUntil("slots").openArray().readLong(), null, votes::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":11,"id":2}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":12,"id":3}""");
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":13,"id":4}""");

      // subscription before result, on every fallback-carrying path
      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"subscription":11,"result":{"context":{"slot":1},"value":{"data":["dGVzdA==","base64"],"executable":false,"lamports":7,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":4}}}}""");
      assertEquals(1, accounts.size());
      assertEquals(7L, accounts.getFirst().lamports());

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"signatureNotification","params":{"subscription":12,"result":{"context":{"slot":2},"value":{"err":null}}}}""");
      assertEquals(1, txResults.size());

      feed(ws, socket, """
          {"jsonrpc":"2.0","method":"voteNotification","params":{"subscription":13,"result":{"hash":"8Rshv2oMkPu5E4opXTRyuyBeZBqQ4S477VG26wUTFxUM","slots":[77],"timestamp":null}}}""");
      assertEquals(List.of(77L), votes);

      assertTrue(exceptions.isEmpty(), "no reordered notification may fall into the exception path: " + exceptions);
    }
  }

  /// Array-backed fragments with non-zero position and arrayOffset drive the
  /// `buf.position() + buf.arrayOffset()` arithmetic that whole-array wraps
  /// (position 0, arrayOffset 0) leave invisible.
  @Test
  void fragmentedNotificationThroughOffsetBuffers() {
    try (final var ws = createWebsocket()) {
      final var key = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(key, received::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":23784,"id":2}""");

      final var notification = """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":5199307},"value":{"data":["dGVzdA==","base64"],"executable":false,"lamports":33594,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":4}},"subscription":23784}}""";
      final int half = notification.length() / 2;

      // fragment 1: non-zero position inside a larger backing array
      final char[] backing = new char[7 + half];
      Arrays.fill(backing, 0, 7, 'x');
      notification.getChars(0, half, backing, 7);
      ws.onText(socket, CharBuffer.wrap(backing, 7, half), false);
      assertTrue(received.isEmpty());

      // fragment 2: non-zero arrayOffset via slice()
      final char[] backing2 = new char[11 + (notification.length() - half)];
      Arrays.fill(backing2, 0, 11, 'y');
      notification.getChars(half, notification.length(), backing2, 11);
      final var sliced = CharBuffer.wrap(backing2, 11, notification.length() - half).slice();
      assertTrue(sliced.hasArray());
      ws.onText(socket, sliced, true);

      assertEquals(1, received.size());
      assertEquals(33594L, received.getFirst().lamports());
      assertArrayEquals("test".getBytes(US_ASCII), received.getFirst().data());
    }
  }

  /// A fragmented message larger than the 4096-char buffer forces ensureCapacity
  /// down both growth branches: doubling, and jumping straight to a minCapacity
  /// beyond the doubled size.
  @Test
  void oversizedFragmentedNotificationGrowsTheBuffer() {
    try (final var ws = createWebsocket()) {
      final var key = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(key, received::add));

      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":23784,"id":2}""");

      // 12k base64 chars decode to 9k bytes; the first oversized fragment jumps
      // past the doubled capacity, the trailing one grows incrementally
      final var data = "A".repeat(12_000);
      final var notification = """
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":1},"value":{"data":["%s","base64"],"executable":false,"lamports":5,"owner":"11111111111111111111111111111111","rentEpoch":0,"space":9000}},"subscription":23784}}"""
          .formatted(data);
      final int split = 9_000;
      ws.onText(socket, CharBuffer.wrap(notification.substring(0, split).toCharArray()), false);
      assertTrue(received.isEmpty());
      ws.onText(socket, CharBuffer.wrap(notification.substring(split)), true);

      assertEquals(1, received.size());
      assertEquals(9_000, received.getFirst().data().length);
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
