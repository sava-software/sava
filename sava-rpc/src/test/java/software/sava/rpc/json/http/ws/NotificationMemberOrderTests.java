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
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/// Member order inside a JSON object carries no meaning (RFC 8259 section 1: an object is
/// an unordered collection of name/value pairs), so a notification must deliver the same
/// item whatever order its members arrive in.
///
/// The parser finds the top-level `params`, the `value` beside `context`, and the
/// `subscription` beside `result` with a forward scan from where the cursor stands. When
/// that scan comes up empty, it rescans the same object from its start. A member that
/// follows the cursor is found by the forward scan; one that precedes it only by the
/// rescan. Each test sends one notification in every member order and requires the same
/// item from both routes. Decoy members that nest the same names are skipped whole by
/// both routes.
///
/// The rescan finds a different member than the forward scan only when an object holds
/// two members of one name, one on each side of the cursor. RFC 8259 section 4 says names
/// SHOULD be unique and leaves the behaviour of such an object unpredictable, so no
/// assertion here picks a winner. The account path's `subscription` scan is not a rescan
/// of one object (see [AccountNotificationAttributionTests]); its frames here keep
/// `result` free of a direct `subscription` member.
@ExtendWith(QuietWsLogging.class)
final class NotificationMemberOrderTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final PublicKey KEY =
      PublicKey.fromBase58Encoded("GovaE4iu227srtG2s3tZzB4RmWBzw8sTwrCLZz7kN7rY");
  private static final String SIGNATURE =
      "2EBVM6cB8vAAD93Ktr6Vd8p67XPbQzCJX47MpReuiCXJAtcjaxpvWpcg9Ege1Nr5Tk3a2GFrByT7WPBjdsTycY9b";
  private static final long SUB_ID = 24040;
  private static final String CONTEXT = "{\"slot\":5208470}";
  private static final String ACCOUNT = """
      {"data":["dGVzdA==","base64"],"executable":false,"lamports":33594,\
      "owner":"GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc","rentEpoch":636,"space":80}""";
  private static final String ACCOUNT_ITEM =
      "|33594|GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc|80|636|false|dGVzdA==|5208470";
  private static final String TOP_DECOY = member("decoy", """
      {"method":"slotNotification","params":{"subscription":1,"result":{"context":{"slot":2},"value":3}}}""");
  private static final String PARAMS_DECOY = member("decoy", """
      {"subscription":4,"result":{"context":{"slot":5},"value":6}}""");
  private static final String RESULT_DECOY = member("decoy", """
      {"context":{"slot":7},"value":{"signature":"decoy"},"subscription":8}""");
  private static final boolean[] BOTH = {false, true};

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

  private static String member(final String name, final String value) {
    return '"' + name + "\":" + value;
  }

  private static String object(final List<String> members) {
    return '{' + String.join(",", members) + '}';
  }

  private static List<String> withDecoy(final boolean decoys, final String decoy, final List<String> members) {
    if (!decoys) {
      return members;
    }
    final var withDecoy = new ArrayList<String>(members.size() + 1);
    withDecoy.add(decoy);
    withDecoy.addAll(members);
    return withDecoy;
  }

  /// One notification in every member order: `params` after or before `method`,
  /// `subscription` after or before `result`, the result members in their given order or
  /// reversed when `reorderResult`, each with and without decoys at the front of every
  /// object.
  private static List<String> frames(final String method,
                                     final List<String> resultMembers,
                                     final boolean reorderResult) {
    final var frames = new ArrayList<String>();
    for (final boolean decoys : BOTH) {
      for (final boolean reversed : reorderResult ? BOTH : new boolean[]{false}) {
        final var ordered = reversed ? resultMembers.reversed() : resultMembers;
        final var result = member("result", object(withDecoy(decoys, RESULT_DECOY, ordered)));
        final var subscription = member("subscription", Long.toString(SUB_ID));
        for (final boolean subscriptionFirst : BOTH) {
          final var params = member("params", object(withDecoy(decoys, PARAMS_DECOY,
              subscriptionFirst ? List.of(subscription, result) : List.of(result, subscription))));
          final var methodMember = member("method", '"' + method + '"');
          final var jsonrpc = member("jsonrpc", "\"2.0\"");
          for (final boolean paramsFirst : BOTH) {
            frames.add(object(withDecoy(decoys, TOP_DECOY, paramsFirst
                ? List.of(jsonrpc, params, methodMember)
                : List.of(jsonrpc, methodMember, params))));
          }
        }
      }
    }
    return frames;
  }

  private static List<String> contextValue(final String value) {
    return List.of(member("context", CONTEXT), member("value", value));
  }

  @FunctionalInterface
  private interface Registration<T> {

    boolean subscribe(SolanaJsonRpcWebsocket ws, Consumer<T> consumer);
  }

  /// Feeds `frame` to a fresh registration confirmed as [#SUB_ID] and returns what it
  /// delivered, rendered by `render`. A frame attributed to any other id would draw an
  /// unsubscribe, which is asserted absent.
  private static <T> List<String> deliver(final Registration<T> registration,
                                          final Function<T, String> render,
                                          final String frame) {
    try (final var ws = websocket()) {
      final var received = new ArrayList<T>();
      assertTrue(registration.subscribe(ws, received::add));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("{\"jsonrpc\":\"2.0\",\"result\":" + SUB_ID + ",\"id\":2}"), true);
      ws.onText(socket, CharBuffer.wrap(frame), true);
      assertEquals(List.of(), socket.sentText.stream().filter(m -> m.contains("nsubscribe")).toList(), frame);
      return received.stream().map(render).toList();
    }
  }

  private static <T> void assertEveryOrderDelivers(final String expected,
                                                   final Registration<T> registration,
                                                   final Function<T, String> render,
                                                   final List<String> frames) {
    for (final var frame : frames) {
      assertEquals(List.of(expected), deliver(registration, render, frame), frame);
    }
  }

  private static String render(final AccountInfo<byte[]> info) {
    return info.pubKey().toBase58() + '|' + info.lamports() + '|' + info.owner().toBase58() + '|' + info.space()
        + '|' + info.rentEpoch() + '|' + info.executable() + '|' + Base64.getEncoder().encodeToString(info.data())
        + '|' + info.context().slot();
  }

  @Test
  void aLogsNotificationDeliversTheSameItemInEveryMemberOrder() {
    assertEveryOrderDelivers(
        "sigR|[log line]|null|5208470",
        (ws, consumer) -> ws.logsSubscribe(KEY, consumer),
        (TxLogs logs) -> logs.signature() + '|' + logs.logs() + '|' + logs.error() + '|' + logs.context().slot(),
        frames("logsNotification", contextValue("""
            {"signature":"sigR","err":null,"logs":["log line"]}"""), true)
    );
  }

  @Test
  void aProgramNotificationDeliversTheSameItemInEveryMemberOrder() {
    assertEveryOrderDelivers(
        "H4vnBqifaSACnKa7acsxstsY1iV1bvJNxsCY7enrd1hq" + ACCOUNT_ITEM,
        (ws, consumer) -> ws.programSubscribe(KEY, consumer),
        NotificationMemberOrderTests::render,
        frames("programNotification", contextValue("""
            {"pubkey":"H4vnBqifaSACnKa7acsxstsY1iV1bvJNxsCY7enrd1hq","account":""" + ACCOUNT + '}'), true)
    );
  }

  @Test
  void anAccountNotificationDeliversTheSameItemInEveryMemberOrder() {
    assertEveryOrderDelivers(
        KEY.toBase58() + ACCOUNT_ITEM,
        (ws, consumer) -> ws.accountSubscribe(KEY, consumer),
        NotificationMemberOrderTests::render,
        frames("accountNotification", contextValue(ACCOUNT), true)
    );
  }

  @Test
  void aSignatureNotificationDeliversTheSameItemInEveryMemberOrder() {
    assertEveryOrderDelivers(
        "5208470|null|null",
        (ws, consumer) -> ws.signatureSubscribe(SIGNATURE, false, consumer),
        (TxResult result) -> result.context().slot() + "|" + result.value() + '|' + result.error(),
        frames("signatureNotification", contextValue("{\"err\":null}"), true)
    );
  }

  @Test
  void aGenericNotificationDeliversTheSameItemInEveryMemberOrder() {
    assertEveryOrderDelivers(
        "55",
        (ws, consumer) -> ws.subscribe("voteSubscribe", "voteUnsubscribe", "voteNotification",
            "vote", "", ji -> ji.skipUntil("slots").openArray().readLong(), consumer),
        (Long slot) -> Long.toString(slot),
        frames("voteNotification", List.of(member("slots", "[55]")), false)
    );
  }
}
