package software.sava.rpc.json.http.client;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.response.Context;
import software.sava.rpc.json.http.response.FeeForMessage;
import software.sava.rpc.json.http.response.Lamports;
import software.sava.rpc.json.http.response.LatestBlockHash;
import software.sava.rpc.json.http.response.TokenAmount;
import software.sava.rpc.json.http.response.TxStatus;
import systems.comodal.jsoniter.JsonIterator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/// The two routes by which [JsonRpcValueResponseParser] reaches a
/// `{"context":..,"value":..}` result. When `context` precedes `value`, the value is parsed
/// in place, in the middle of the result object's scan. When `value` comes first, its
/// position is marked, the value is skipped, and once the scan has found the context the
/// iterator is reset to the mark and the value parsed there. JSON object members are
/// unordered (RFC 8259 section 1), and Solana nodes serve the context first while other
/// providers need not, so both routes must hand the value parser the same value and the
/// same context, once, and return the same result. Nothing reads the iterator after the
/// parse returns: `applyResponse` returns the parser's result and drops the iterator, so
/// the routes may leave the cursor in different places.
final class ValueResponseRouteTests {

  private static final String CONTEXT = "\"context\":{\"apiVersion\":\"2.1.9\",\"slot\":432480512}";

  private record Call(long slot, String apiVersion, int mark, String value) {
  }

  /// A value parser that records each call: the context it was given, where the iterator
  /// stood, and the exact text it consumed (a whole value, by [JsonIterator#skip()]).
  private static BiFunction<JsonIterator, Context, String> recording(final byte[] body, final List<Call> calls) {
    return (ji, context) -> {
      final int mark = ji.mark();
      ji.skip();
      final var value = new String(body, mark, ji.mark() - mark, UTF_8).strip();
      calls.add(new Call(context.slot(), context.apiVersion(), mark, value));
      return value;
    };
  }

  private static <R> R parse(final BiFunction<JsonIterator, Context, R> parser, final byte[] body) {
    return new JsonRpcValueResponseParser<>(parser).apply(StubHttpResponse.of(200, body));
  }

  /// Where the value of the first `"value"` member starts: just past its name-separator, by
  /// the JSON grammar alone.
  private static int valuePosition(final String body) {
    final int name = body.indexOf("\"value\"");
    return body.indexOf(':', name + "\"value\"".length()) + 1;
  }

  /// The deferred route resets to the mark it took after the `value` member's colon. The
  /// mark is an index into the body, and a `value` member inside `result` sits behind at
  /// least `{"result":{"value":`, so the mark is never zero. The sweep runs the shortest
  /// envelopes, with whitespace and every value type, and finds the parser handed the
  /// iterator exactly where the grammar puts the value.
  @Test
  void aValueBeforeItsContextIsParsedFromWhereTheValueStarts() {
    for (final var value : List.of("7", "\"s\"", "{\"a\":1}", "[1,2]", "null", "true")) {
      for (final var body : List.of(
          "{\"result\":{\"value\":" + value + ",\"context\":{\"slot\":3}}}",
          "{ \"result\" : { \"value\" : " + value + " , \"context\" : { \"slot\" : 3 } } }",
          "{\"jsonrpc\":\"2.0\",\"result\":{\"value\":" + value + ",\"context\":{\"slot\":3}},\"id\":1}",
          "{\"id\":1,\"result\":{\"value\":" + value + ",\"context\":{\"slot\":3}}}")) {
        final var bytes = body.getBytes(UTF_8);
        final var calls = new ArrayList<Call>();

        assertEquals(value, parse(recording(bytes, calls), bytes), body);

        assertEquals(1, calls.size(), body);
        final var call = calls.getFirst();
        assertEquals(3L, call.slot(), "the deferred parse receives the context found after it");
        assertEquals(valuePosition(body), call.mark(), body);
        assertTrue(call.mark() >= "{\"result\":{\"value\":".length(), body);
      }
    }
  }

  /// One response per value shape, with the members in both orders. A trailing member and a
  /// value that nests `value` and `context` names keep the in-place route scanning past the
  /// value, and the deferred route skipping over it.
  @Test
  void bothMemberOrdersParseTheValueOnceWithTheSameInputs() {
    for (final var value : List.of(
        "123456",
        "\"payload\"",
        "{\"value\":{\"context\":1},\"x\":[1,{\"value\":2}]}",
        "[{\"context\":{\"slot\":9}},null]",
        "null",
        "false")) {
      final var trailer = ",\"unknown\":{\"value\":0,\"context\":{\"slot\":1}}";
      final var contextFirst = "{\"jsonrpc\":\"2.0\",\"result\":{" + CONTEXT + ",\"value\":" + value + trailer + "},\"id\":1}";
      final var valueFirst = "{\"jsonrpc\":\"2.0\",\"result\":{\"value\":" + value + ',' + CONTEXT + trailer + "},\"id\":1}";

      final var inPlace = new ArrayList<Call>();
      final var deferred = new ArrayList<Call>();
      final var inPlaceBytes = contextFirst.getBytes(UTF_8);
      final var deferredBytes = valueFirst.getBytes(UTF_8);
      assertEquals(value, parse(recording(inPlaceBytes, inPlace), inPlaceBytes), contextFirst);
      assertEquals(value, parse(recording(deferredBytes, deferred), deferredBytes), valueFirst);

      assertEquals(1, inPlace.size(), contextFirst);
      assertEquals(1, deferred.size(), valueFirst);
      final var a = inPlace.getFirst();
      final var b = deferred.getFirst();
      assertEquals(new Call(432480512L, "2.1.9", valuePosition(contextFirst), value), a, contextFirst);
      assertEquals(new Call(432480512L, "2.1.9", valuePosition(valueFirst), value), b, valueFirst);
    }
  }

  /// Parses `value` in both member orders, requires equal results, and returns the
  /// in-place one for the caller to check against the literal.
  private static <R> R assertBothOrdersAgree(final BiFunction<JsonIterator, Context, R> parser,
                                             final String value) {
    final var contextFirst = "{\"jsonrpc\":\"2.0\",\"result\":{" + CONTEXT + ",\"value\":" + value + "},\"id\":1}";
    final var valueFirst = "{\"jsonrpc\":\"2.0\",\"result\":{\"value\":" + value + ',' + CONTEXT + "},\"id\":1}";
    final var inPlace = parse(parser, contextFirst.getBytes(UTF_8));
    assertEquals(inPlace, parse(parser, valueFirst.getBytes(UTF_8)), valueFirst);
    return inPlace;
  }

  /// The value parsers the client hands this class, one per value shape: a number, a
  /// nullable number, an object, an object of strings, and an array of objects and nulls.
  @Test
  void theClientsValueParsersAgreeAcrossMemberOrders() {
    final var lamports = assertBothOrdersAgree(Lamports::parse, "123456");
    assertEquals(new Lamports(new Context(432480512L, "2.1.9"), 123456L), lamports);
    assertEquals(5000L, assertBothOrdersAgree(FeeForMessage::parse, "5000").fee());
    assertNull(assertBothOrdersAgree(FeeForMessage::parse, "null"));
    final var blockHash = assertBothOrdersAgree(LatestBlockHash::parse, """
        {"blockhash":"EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N","lastValidBlockHeight":3090}""");
    assertEquals("EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N", blockHash.blockHash());
    assertEquals(3090L, blockHash.lastValidBlockHeight());
    final var tokenAmount = assertBothOrdersAgree(TokenAmount::parse, """
        {"amount":"9864","decimals":2,"uiAmount":98.64,"uiAmountString":"98.64"}""");
    assertEquals(BigInteger.valueOf(9864), tokenAmount.amount());
    assertEquals(2, tokenAmount.decimals());
    final var statuses = assertBothOrdersAgree(TxStatus::parseList, """
        [{"slot":72,"confirmations":10,"err":null,"status":{"Ok":null},"confirmationStatus":"confirmed"},\
        null]""");
    assertEquals(2, statuses.size());
    assertEquals(72L, statuses.getFirst().slot());
    assertTrue(statuses.getLast().nil(), "a null entry parses to the nil status");
    assertEquals(432480512L, statuses.getLast().context().slot());
  }
}
