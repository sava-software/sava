package software.sava.rpc.json.http.ws;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/// [SolanaJsonRpcWebsocket#utf8Length(String)], the size the subscribe batch composer gives each
/// request, judged against the JDK's own UTF-8 encoding. For well-formed text the length is
/// `getBytes(UTF_8).length`, the bytes a websocket text frame carries. For text holding an
/// unpaired surrogate it is the [Integer#MAX_VALUE] sentinel that keeps the request out of every
/// shared message, exactly when an encoder that reports malformed input refuses the text, as the
/// JDK websocket's encoder does before it fails the send. The corpus holds both sides of every
/// width boundary, the surrogate range's own edges, the first and last supplementary code points,
/// and each place an unpaired half can sit. One dynamic test per case, named by its code points,
/// the per-case form the seed replay tests of this build use.
final class Utf8LengthTests {

  /// Well-formed text: no unpaired surrogate anywhere.
  private static final String[] WELL_FORMED = {
      "",
      "a",
      "\u007F",
      "\u0080",
      "߿",
      "ࠀ",
      "퟿",
      "",
      "￿",
      "𐀀",
      "😀",
      "􏿿",
      "😀😁",
      "😀abc",
      "aé€😀z",
      "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"signatureSubscribe\",\"params\":[\"€😀\"]}"
  };

  /// Text the JDK refuses to encode: a high surrogate not followed by a low one, or a low
  /// surrogate not preceded by a high one.
  private static final String[] UNPAIRED = {
      "\uD83D",
      "a\uD83Db",
      "ab\uD83D",
      "\uDE00",
      "a\uDE00b",
      "\uDE00a",
      "\uDE00\uD83D",
      "\uDE00\uDE00",
      "\uD83D😀",
      "😀\uDE00",
      "😀\uD83D"
  };

  /// A fresh encoder that reports what the JDK websocket's encoder reports; encoders are stateful.
  private static CharsetEncoder reportingEncoder() {
    return UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
  }

  /// The case as the corpus writes it: printable ASCII as itself and every other code unit as its
  /// four-digit hex escape, since most of the corpus does not print.
  private static String name(final String text) {
    if (text.isEmpty()) {
      return "empty";
    }
    return text.chars()
        .mapToObj(c -> c >= 0x20 && c < 0x7F ? String.valueOf((char) c) : String.format(Locale.ROOT, "\\u%04X", c))
        .collect(Collectors.joining());
  }

  @TestFactory
  Stream<DynamicTest> wellFormedTextCountsItsUtf8Bytes() {
    return Stream.of(WELL_FORMED).map(text -> DynamicTest.dynamicTest(name(text), () -> {
      assertDoesNotThrow(() -> reportingEncoder().encode(CharBuffer.wrap(text)),
          "the corpus entry must be well formed for this oracle to apply");
      assertEquals(text.getBytes(UTF_8).length, SolanaJsonRpcWebsocket.utf8Length(text));
    }));
  }

  @TestFactory
  Stream<DynamicTest> anUnpairedSurrogateIsTheUnshareableSentinel() {
    return Stream.of(UNPAIRED).map(text -> DynamicTest.dynamicTest(name(text), () -> {
      assertThrows(CharacterCodingException.class, () -> reportingEncoder().encode(CharBuffer.wrap(text)),
          "the JDK refuses to encode the corpus entry");
      assertEquals(Integer.MAX_VALUE, SolanaJsonRpcWebsocket.utf8Length(text));
    }));
  }
}
