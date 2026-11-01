package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.TxLogs;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Every frame in this suite is a VERBATIM capture from api.devnet.solana.com on 2026-11-01
/// (`getVersion` reporting solana-core 4.4.0-beta.1 and feature-set 3993424257), replayed
/// through the real client. The first capture is a `logsSubscribe` with the `all` filter at
/// confirmed commitment, its first two notifications, and the `logsUnsubscribe` that ended it.
/// A follow-up probe the same day joined the same server subscription, id 28179973 (agave
/// shares one subscription among the connections asking with identical params), so its
/// subscription result, cancellation and `true` are byte for byte the first capture's. The
/// probe kept the notifications that arrived between its cancellation and the `true`, and
/// then repeated the cancellation to record agave's refusal for an id already gone. Request
/// ids are remapped to the client's own msgId sequence: the captures' requests 1 and 2 are 2
/// and 3 here, and the probe's repeated cancellation, 3, is 4. Everything else is untouched.
@ExtendWith(QuietWsLogging.class)
final class LiveDevnetLogsAllShapeTests {

  private static final URI ENDPOINT = URI.create("wss://api.devnet.solana.com");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);

  private static final String SUBSCRIBE = """
      {"jsonrpc":"2.0","id":2,"method":"logsSubscribe","params":["all",{"commitment":"confirmed"}]}""";
  private static final String CANCEL = """
      {"jsonrpc":"2.0","id":3,"method":"logsUnsubscribe","params":[28179973]}""";
  private static final String CANCEL_AGAIN = """
      {"jsonrpc":"2.0","id":4,"method":"logsUnsubscribe","params":[28179973]}""";
  /// The first two of the notifications the probe received after its cancellation left.
  private static final String LATE_NOTIFICATION_1 = """
      {"jsonrpc":"2.0","method":"logsNotification","params":{"result":{"context":{"slot":517595033},"value":{"signature":"3NNibeFArFHz79Li7STp1aRVEYjpEEQzK5WtQoZGRAFT7pjz3njqSz81hSD4z2TUadNhAiX3ybiQLDZXBZkoTTM","err":null,"logs":["Program ComputeBudget111111111111111111111111111111 invoke [1]","Program ComputeBudget111111111111111111111111111111 success","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB invoke [1]","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB consumed 5330 of 1199850 compute units","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB success","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB invoke [1]","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB consumed 5268 of 1194520 compute units","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB success","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB invoke [1]","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB consumed 4810 of 1189252 compute units","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB success","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB invoke [1]","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB consumed 5188 of 1184442 compute units","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB success","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB invoke [1]","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB consumed 5188 of 1179254 compute units","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB success","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB invoke [1]","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB consumed 5230 of 1174066 compute units","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB success","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB invoke [1]","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB consumed 5188 of 1168836 compute units","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB success","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB invoke [1]","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB consumed 5230 of 1163648 compute units","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB success","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB invoke [1]","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB consumed 4810 of 1158418 compute units","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB success","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB invoke [1]","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB consumed 5188 of 1153608 compute units","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB success","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB invoke [1]","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB consumed 5187 of 1148420 compute units","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB success","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB invoke [1]","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB consumed 5310 of 1143233 compute units","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB success","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB invoke [1]","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB consumed 5188 of 1137923 compute units","Program ETDLAdiAyWnEUngspYczTXUceT6X8f92eZQvr8nmSkWB success"]}},"subscription":28179973}}""";
  private static final String LATE_NOTIFICATION_2 = """
      {"jsonrpc":"2.0","method":"logsNotification","params":{"result":{"context":{"slot":517595033},"value":{"signature":"3finZtzzpUqLbAMiiYZpGeqhcKPx91s79Fwd4i6UM9Ae1GLWxpcYsypHt7AduAxFHE7jt1Mfq953dbrK36YV9pbM","err":null,"logs":["Program ComputeBudget111111111111111111111111111111 invoke [1]","Program ComputeBudget111111111111111111111111111111 success","Program ComputeBudget111111111111111111111111111111 invoke [1]","Program ComputeBudget111111111111111111111111111111 success","Program DFZ2oNmLheuLvXMjVACDHsDhrNmWFyEAxgaZwaChNJL4 invoke [1]","Program log: Instruction: Payout","Program 6KGECoL2nosgN24aKZ4VUenyADTtFFve9jMbEQ4uDFKj invoke [2]","Program log: Instruction: Distribute","Program data: CtQYTiPKtoY//WIQUVpKwN/yU8WlZmwYg5KhY7o4aQAiBQfysxzTtAA2bgEAAAAACvuAQFVZCzBPt1cjhRHZOqn8Z6AiRCLhV7cUsNc8XyWFBw7RHVUM6K63mLVYWDXgoBmAEtWN/+s0+nprzJFuCw==","Program 6KGECoL2nosgN24aKZ4VUenyADTtFFve9jMbEQ4uDFKj consumed 38942 of 351897 compute units","Program 6KGECoL2nosgN24aKZ4VUenyADTtFFve9jMbEQ4uDFKj success","Program 6KGECoL2nosgN24aKZ4VUenyADTtFFve9jMbEQ4uDFKj invoke [2]","Program log: Instruction: CloseGameAccount","Program data: ng3zAlM1W/SFBw7RHVUM6K63mLVYWDXgoBmAEtWN/+s0+nprzJFuCwr7gEBVWQswT7dXI4UR2Tqp/GegIkQi4Ve3FLDXPF8l2qvbKkL9+FXBi14LkAZV6ezQGT3UjJJRFxayQKXSt5A=","Program 6KGECoL2nosgN24aKZ4VUenyADTtFFve9jMbEQ4uDFKj consumed 19235 of 308588 compute units","Program 6KGECoL2nosgN24aKZ4VUenyADTtFFve9jMbEQ4uDFKj success","Program DFZ2oNmLheuLvXMjVACDHsDhrNmWFyEAxgaZwaChNJL4 consumed 112003 of 399700 compute units","Program DFZ2oNmLheuLvXMjVACDHsDhrNmWFyEAxgaZwaChNJL4 success"]}},"subscription":28179973}}""";

  private static SolanaJsonRpcWebsocket websocket(final TestClock clock, final List<Throwable> transportErrors) {
    return new SolanaJsonRpcWebsocket(
        ENDPOINT, SolanaAccounts.MAIN_NET, Commitment.CONFIRMED, null,
        TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH, clock,
        new RecordingExecutor(), null, null, (_, _, _) -> {
        }, (_, error) -> transportErrors.add(error), null, null
    );
  }

  private static void feed(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket, final String json) {
    ws.onText(socket, CharBuffer.wrap(json), true);
  }

  /// A pong runs the maintenance pass, which flushes queued cancellations and enforces the
  /// unanswered-request deadline.
  private static void pong(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket) {
    ws.onPong(socket, ByteBuffer.wrap(new byte[0]));
  }

  /// Steps just past the age at which an unanswered request replaces the connection.
  private static void passTheUnansweredDeadline(final TestClock clock) {
    clock.advanceMillis(SolanaJsonRpcWebsocket.UNANSWERED_ESCALATION_FACTOR * TIMINGS.subscriptionResendDelay() + 1);
  }

  /// Subscribes, answers the subscription with the captured result and delivers the two
  /// captured notifications, which arrived in that order from one slot.
  private static List<TxLogs> subscribeAndDeliver(final SolanaJsonRpcWebsocket ws, final RecordingWebSocket socket) {
    final var received = new ArrayList<TxLogs>();
    assertTrue(ws.logsSubscribeAll(received::add));
    ws.onOpen(socket);
    assertEquals(List.of(SUBSCRIBE), socket.sentText, "the request the capture sent, under the client's own id");

    feed(ws, socket, """
        {"jsonrpc":"2.0","result":28179973,"id":2}""");
    feed(ws, socket, """
        {"jsonrpc":"2.0","method":"logsNotification","params":{"result":{"context":{"slot":517568769},"value":{"signature":"52wE5iaMyc4qBw3dbeaYHvoVjh3yHJBzASE3YfBhs7py7RVAovpj8Hy3p4kqo4Nzmtb9T37Q2eXNJV1LxB45D6ZH","err":null,"logs":["Program HADRoNbLovyqhCsocfYQYB7QdfCAAinN9HTePvBCVDQ8 invoke [1]","Program HADRoNbLovyqhCsocfYQYB7QdfCAAinN9HTePvBCVDQ8 consumed 36 of 60 compute units","Program HADRoNbLovyqhCsocfYQYB7QdfCAAinN9HTePvBCVDQ8 success"]}},"subscription":28179973}}""");
    feed(ws, socket, """
        {"jsonrpc":"2.0","method":"logsNotification","params":{"result":{"context":{"slot":517568769},"value":{"signature":"SCJygGTtcBFk42D92JqLECHURfyGDbFXQoZQedEBHPpopY9WyQShnZD1ftf4PsqnoQNMs9rz8czLHWk51LCdrkr","err":null,"logs":["Program BiSoNHVpsVZW2F7rx2eQ59yQwKxzU5NvBcmKshCSUypi invoke [1]","Program BiSoNHVpsVZW2F7rx2eQ59yQwKxzU5NvBcmKshCSUypi consumed 39 of 48 compute units","Program BiSoNHVpsVZW2F7rx2eQ59yQwKxzU5NvBcmKshCSUypi success"]}},"subscription":28179973}}""");
    return received;
  }

  /// Cancels, then replays the probe's late notifications into the window before the
  /// cancellation's answer: neither reaches the consumer, and while the cancellation is
  /// unanswered neither puts a frame on the wire.
  private static void cancelIntoTheLateNotifications(final SolanaJsonRpcWebsocket ws,
                                                     final RecordingWebSocket socket,
                                                     final List<TxLogs> received) {
    assertTrue(ws.logsUnsubscribeAll());
    pong(ws, socket);
    assertEquals(List.of(SUBSCRIBE, CANCEL), socket.sentText);

    feed(ws, socket, LATE_NOTIFICATION_1);
    feed(ws, socket, LATE_NOTIFICATION_2);
    assertEquals(2, received.size(), "a notification after the cancellation reached the consumer");
    assertEquals(List.of(SUBSCRIBE, CANCEL), socket.sentText, "one cancellation per id while it is unanswered");
  }

  private static void assertReplacedFor(final String request,
                                        final RecordingWebSocket socket,
                                        final List<Throwable> transportErrors) {
    assertTrue(socket.aborted, "the connection must be replaced");
    assertEquals(1, transportErrors.size(), transportErrors::toString);
    final var message = transportErrors.getFirst().getMessage();
    assertTrue(message.startsWith("Request " + request + " to api.devnet.solana.com has gone unanswered"), message);
  }

  /// The `all` stream's notification is the same `logsNotification` a mentions subscription
  /// gets: signature, a null err for a successful transaction, and the transaction's log lines.
  @Test
  void theRealAllFilterNotificationsReachTheAllConsumer() {
    final var transportErrors = new ArrayList<Throwable>();
    try (final var ws = websocket(new TestClock(), transportErrors)) {
      final var errors = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);
      final var received = subscribeAndDeliver(ws, new RecordingWebSocket());

      assertEquals(2, received.size());
      final var first = received.getFirst();
      assertEquals("52wE5iaMyc4qBw3dbeaYHvoVjh3yHJBzASE3YfBhs7py7RVAovpj8Hy3p4kqo4Nzmtb9T37Q2eXNJV1LxB45D6ZH",
          first.signature());
      assertNull(first.error(), "err null is a successful transaction");
      assertEquals(List.of(
          "Program HADRoNbLovyqhCsocfYQYB7QdfCAAinN9HTePvBCVDQ8 invoke [1]",
          "Program HADRoNbLovyqhCsocfYQYB7QdfCAAinN9HTePvBCVDQ8 consumed 36 of 60 compute units",
          "Program HADRoNbLovyqhCsocfYQYB7QdfCAAinN9HTePvBCVDQ8 success"
      ), first.logs());
      assertEquals(517_568_769L, first.context().slot());

      final var second = received.getLast();
      assertEquals("SCJygGTtcBFk42D92JqLECHURfyGDbFXQoZQedEBHPpopY9WyQShnZD1ftf4PsqnoQNMs9rz8czLHWk51LCdrkr",
          second.signature());
      assertNull(second.error());
      assertEquals(3, second.logs().size());
      assertEquals("Program BiSoNHVpsVZW2F7rx2eQ59yQwKxzU5NvBcmKshCSUypi success", second.logs().getLast());
      assertEquals(517_568_769L, second.context().slot());

      assertTrue(errors.isEmpty(), errors::toString);
      assertTrue(transportErrors.isEmpty(), transportErrors::toString);
    }
  }

  /// The cancellation names the id the server granted. The late notifications leave the client
  /// owing a second cancellation of that id, which it sends once the first is answered; agave
  /// refuses it, the id being gone, and the client settles the refusal quietly. Those two
  /// answers settle the exchange: past the unanswered-request deadline the next maintenance
  /// pass neither re-sends nor replaces the connection. The tests below withhold each answer
  /// in turn, and the same pass then replaces it.
  @Test
  void theRealUnsubscribeExchangeSettlesTheRegistration() {
    final var clock = new TestClock();
    final var transportErrors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, transportErrors)) {
      final var errors = new ArrayList<RuntimeException>();
      ws.exceptionSubscribe(errors::add);
      final var socket = new RecordingWebSocket();
      cancelIntoTheLateNotifications(ws, socket, subscribeAndDeliver(ws, socket));

      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":3}""");
      pong(ws, socket);
      assertEquals(List.of(SUBSCRIBE, CANCEL, CANCEL_AGAIN), socket.sentText,
          "the cancellation the late notifications left owing goes out once the first is answered");

      feed(ws, socket, """
          {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid subscription id."},"id":4}""");
      pong(ws, socket);
      assertEquals(List.of(SUBSCRIBE, CANCEL, CANCEL_AGAIN), socket.sentText, "the refusal settles it");
      assertEquals(0, ws.retainedRegistrations());
      assertTrue(errors.isEmpty(), errors::toString);

      passTheUnansweredDeadline(clock);
      pong(ws, socket);
      assertEquals(List.of(SUBSCRIBE, CANCEL, CANCEL_AGAIN), socket.sentText, "nothing is left to re-send");
      assertFalse(socket.aborted, "nothing is left unanswered");
      assertTrue(transportErrors.isEmpty(), transportErrors::toString);
    }
  }

  /// Without the cancellation's `true`, the deadline replaces the connection rather than
  /// re-sending the cancellation.
  @Test
  void anUnansweredCancellationReplacesTheConnectionAtTheDeadline() {
    final var clock = new TestClock();
    final var transportErrors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, transportErrors)) {
      final var socket = new RecordingWebSocket();
      cancelIntoTheLateNotifications(ws, socket, subscribeAndDeliver(ws, socket));

      passTheUnansweredDeadline(clock);
      pong(ws, socket);
      assertEquals(List.of(SUBSCRIBE, CANCEL), socket.sentText, "an unanswered request is never re-sent");
      assertReplacedFor("3", socket, transportErrors);
    }
  }

  /// With the `true` but without agave's refusal, the second cancellation is the request the
  /// deadline finds unanswered.
  @Test
  void anUnansweredSecondCancellationReplacesTheConnectionAtTheDeadline() {
    final var clock = new TestClock();
    final var transportErrors = new ArrayList<Throwable>();
    try (final var ws = websocket(clock, transportErrors)) {
      final var socket = new RecordingWebSocket();
      cancelIntoTheLateNotifications(ws, socket, subscribeAndDeliver(ws, socket));
      feed(ws, socket, """
          {"jsonrpc":"2.0","result":true,"id":3}""");
      pong(ws, socket);
      assertEquals(List.of(SUBSCRIBE, CANCEL, CANCEL_AGAIN), socket.sentText);

      passTheUnansweredDeadline(clock);
      pong(ws, socket);
      assertEquals(List.of(SUBSCRIBE, CANCEL, CANCEL_AGAIN), socket.sentText, "an unanswered request is never re-sent");
      assertReplacedFor("4", socket, transportErrors);
    }
  }
}
