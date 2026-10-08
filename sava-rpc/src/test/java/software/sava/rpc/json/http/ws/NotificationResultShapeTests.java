package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;

import java.net.URI;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// The members a keyed notification's `result` must carry. A `result` with no `context`, or
/// a `params` with no `result`, is a malformed frame: scanning on from the missing member
/// would read the rest of `params` as the context and the value, so a member outside
/// `result` (here a top-level `value` of another account) could be delivered under a
/// registration. Found while refuting the `# unique-member rescan` acceptance for the
/// `value` scan, whose equivalence holds only once `result` is known to carry `context`.
/// Both frames are refused the way other malformed frames are: the failure reaches the
/// exception subscribers once, nothing is delivered, and no cancellation goes out.
final class NotificationResultShapeTests {

  private static final URI ENDPOINT = URI.create("wss://api.mainnet-beta.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);
  private static final PublicKey KEY =
      PublicKey.fromBase58Encoded("GovaE4iu227srtG2s3tZzB4RmWBzw8sTwrCLZz7kN7rY");
  private static final String ACCOUNT_VALUE = """
      {"data":["","base64"],"executable":false,"lamports":%d,\
      "owner":"11111111111111111111111111111111","rentEpoch":0,"space":0}""";

  private record Fixture(SolanaJsonRpcWebsocket ws,
                         RecordingWebSocket socket,
                         List<AccountInfo<byte[]>> received,
                         List<RuntimeException> exceptions) implements AutoCloseable {

    static Fixture open() {
      final var ws = new SolanaJsonRpcWebsocket(
          ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null, TIMINGS,
          SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, new TestClock(), new RecordingExecutor(), null,
          _ -> {
          },
          (_, _, _) -> {
          },
          null, null, null
      );
      final var received = new ArrayList<AccountInfo<byte[]>>();
      assertTrue(ws.accountSubscribe(KEY, received::add));
      final var exceptions = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(exceptions::add);
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("{\"jsonrpc\":\"2.0\",\"result\":23784,\"id\":2}"), true);
      return new Fixture(ws, socket, received, exceptions);
    }

    void feed(final String json) {
      ws.onText(socket, CharBuffer.wrap(json), true);
    }

    void assertRefused(final String missingMember) {
      assertAll(
          () -> assertEquals(List.of(), received, "nothing is delivered from a malformed frame"),
          () -> assertEquals(1, exceptions.size(), () -> "one failure reaches the subscribers: " + exceptions),
          () -> assertInstanceOf(IllegalStateException.class, exceptions.getFirst()),
          () -> assertTrue(exceptions.getFirst().getMessage().contains("no " + missingMember),
              () -> exceptions.getFirst().getMessage()),
          () -> assertEquals(List.of(), socket.sentText.stream().filter(m -> m.contains("Unsubscribe")).toList(),
              "a malformed frame cancels nothing")
      );
    }

    @Override
    public void close() {
      ws.close();
    }
  }

  /// `result` without `context`, and a top-level `value` naming a different account: before
  /// the guard, the scan past the missing `context` consumed the rest of `params`, found the
  /// top-level `value`, and delivered that account under the registration.
  @Test
  void aResultWithoutContextIsRefusedRatherThanReadPastItsEnd() {
    try (final var fixture = Fixture.open()) {
      fixture.feed("""
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"value":%s},\
          "subscription":23784},"value":%s}""".formatted(ACCOUNT_VALUE.formatted(11), ACCOUNT_VALUE.formatted(22)));

      fixture.assertRefused("context");
    }
  }

  /// `params` without `result` at all.
  @Test
  void paramsWithoutAResultAreRefused() {
    try (final var fixture = Fixture.open()) {
      fixture.feed("""
          {"jsonrpc":"2.0","method":"accountNotification","params":{"subscription":23784}}""");

      fixture.assertRefused("result");
    }
  }

  /// The well-formed frame still delivers, whichever order `context` and `value` take.
  @Test
  void aWellFormedResultDeliversInEitherMemberOrder() {
    try (final var fixture = Fixture.open()) {
      fixture.feed("""
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"context":{"slot":1},\
          "value":%s},"subscription":23784}}""".formatted(ACCOUNT_VALUE.formatted(11)));
      fixture.feed("""
          {"jsonrpc":"2.0","method":"accountNotification","params":{"result":{"value":%s,\
          "context":{"slot":2}},"subscription":23784}}""".formatted(ACCOUNT_VALUE.formatted(12)));

      assertAll(
          () -> assertEquals(List.of(), fixture.exceptions()),
          () -> assertEquals(List.of(11L, 12L), fixture.received().stream().map(AccountInfo::lamports).toList())
      );
    }
  }
}
