package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.rpc.DataSlice;
import software.sava.core.rpc.Filter;
import software.sava.rpc.json.http.request.Commitment;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Frames are JSON, so their numbers must be ASCII digits whatever the JVM's default format
/// locale; `String.format` without a locale writes that locale's digits. The default is
/// JVM-wide state, hence [Isolated].
@Isolated
@ExtendWith(QuietWsLogging.class)
final class LocaleIndependentFrameTests {

  /// Thai digits, through the Unicode numbering-system extension.
  private static final Locale NON_ASCII_DIGITS = Locale.forLanguageTag("th-TH-u-nu-thai");
  private static final PublicKey KEY =
      PublicKey.fromBase58Encoded("GovaE4iu227srtG2s3tZzB4RmWBzw8sTwrCLZz7kN7rY");
  private static final Timings TIMINGS = new Timings(60_000, 60_000, 60_000);

  private static SolanaJsonRpcWebsocket websocket() {
    return new SolanaJsonRpcWebsocket(
        URI.create("wss://localhost"), SolanaAccounts.MAIN_NET, Commitment.CONFIRMED,
        null, TIMINGS, SolanaRpcWebsocketBuilder.DEFAULT_MAX_MESSAGE_LENGTH,
        new TestClock(), new RecordingExecutor(), null, null, (_, _, _) -> {
        }, null, null, null
    );
  }

  @Test
  void subscribeAndUnsubscribeFramesAreWrittenInAsciiDigits() {
    final var formatLocale = Locale.getDefault(Locale.Category.FORMAT);
    Locale.setDefault(Locale.Category.FORMAT, NON_ASCII_DIGITS);
    try (final var ws = websocket()) {
      assertTrue(ws.accountSubscribe(new DataSlice(12, 40), KEY, _ -> {
      }));
      assertTrue(ws.programSubscribe(KEY, List.of(Filter.createDataSizeFilter(165), Filter.createMemCompFilter(32, KEY)), _ -> {
      }));
      assertTrue(ws.slotSubscribe(_ -> {
      }));
      assertTrue(ws.rootSubscribe(_ -> {
      }));
      assertTrue(ws.subscribe("transactionSubscribe", "transactionUnsubscribe", "transactionNotification",
          "tx", "{\"vote\":false}", _ -> null, _ -> {
          }));
      final var socket = new RecordingWebSocket();
      ws.onOpen(socket);
      ws.onText(socket, CharBuffer.wrap("{\"jsonrpc\":\"2.0\",\"result\":1234,\"id\":2}"), true);
      assertTrue(ws.accountUnsubscribe(KEY));
      // A pong is a write cycle, so it flushes the queued un-subscription.
      ws.onPong(socket, ByteBuffer.wrap(new byte[0]));

      assertEquals(6, socket.sentText.size());
      assertEquals(List.of(
          "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"accountSubscribe\",\"params\":[\"" + KEY
              + "\",{\"encoding\":\"base64\",\"commitment\":\"confirmed\",\"dataSlice\":{\"offset\":12,\"length\":40}}]}",
          "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"programSubscribe\",\"params\":[\"" + KEY
              + "\",{\"commitment\":\"confirmed\",\"encoding\":\"base64\",\"filters\":[{\"dataSize\":165},"
              + "{\"memcmp\":{\"offset\":32,\"bytes\":\"" + KEY + "\"}}]}]}",
          "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"slotSubscribe\"}",
          "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"rootSubscribe\"}",
          "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"transactionSubscribe\",\"params\":[{\"vote\":false}]}"
      ).stream().sorted().toList(), socket.sentText.subList(0, 5).stream().sorted().toList());
      assertEquals("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"accountUnsubscribe\",\"params\":[1234]}",
          socket.sentText.getLast());
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, formatLocale);
    }
  }
}
