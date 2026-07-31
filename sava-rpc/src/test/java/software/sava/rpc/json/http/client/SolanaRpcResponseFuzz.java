package software.sava.rpc.json.http.client;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.rpc.json.http.response.*;

import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static software.sava.rpc.json.http.client.BaseSolanaJsonRpcClient.applyGenericResponseValue;
import static software.sava.rpc.json.http.client.JsonRpcHttpClient.applyGenericResponseResult;

/// Jazzer entry point for RPC response parsing, the http side of the untrusted-node
/// surface: everything a compromised or buggy provider returns flows through the
/// shared envelope gate (`BaseJsonRpcResponseParser.checkResponse`) and then one of
/// the hand-rolled json-iterator field predicates under `json.http.response`. The
/// harness drives the exact controller pipeline the client does — the same
/// `Function<HttpResponse<?>, R>` constants `SolanaJsonRpcClient` holds — over a
/// fuzz-chosen parser family, status code, and body.
///
/// The malformed-input contract is "garbage in -> RuntimeException out"
/// ([JsonRpcException], json-iterator failures, and kin are all tolerated); Jazzer
/// itself flags hangs, resource exhaustion, and non-RuntimeException throwables.
/// One invariant is asserted on top: these controllers are shared static state in
/// the real client, applied concurrently by every in-flight request, so parsing the
/// same body twice must classify it the same way — success both times or the same
/// exception class both times. A disagreement means a parser predicate is carrying
/// state between applications.
///
/// Input layout: byte 0 selects the parser family, byte 1 bit 0 the http status
/// (200 or 503 — the envelope gate weighs an error object against a non-2xx
/// status), and the rest is the response body verbatim.
///
/// Deliberately free of Jazzer imports so it compiles with the regular test sources.
///
/// Run with `./gradlew :sava-rpc:fuzzResponses [-PmaxFuzzTime=<seconds>]`.
public final class SolanaRpcResponseFuzz {

  private static final PublicKey ACCOUNT_KEY =
      PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");

  private static final List<Function<HttpResponse<?>, ?>> PARSERS = List.of(
      applyGenericResponseValue((ji, context) -> AccountInfo.parse(ACCOUNT_KEY, ji, context, AccountInfo.BYTES_IDENTITY)),
      applyGenericResponseValue((ji, context) -> AccountInfo.parseAccounts(ji, context, TokenAccount.FACTORY)),
      applyGenericResponseResult(Tx::parse),
      applyGenericResponseResult(Block::parse),
      applyGenericResponseValue((ji, context) -> TxSimulation.parse(List.of(), ji, context)),
      applyGenericResponseResult(VoteAccounts::parse),
      applyGenericResponseResult(TxSig::parseSignatures),
      applyGenericResponseValue(TxStatus::parseList),
      applyGenericResponseValue(LatestBlockHash::parse),
      applyGenericResponseResult(ClusterNode::parse),
      applyGenericResponseResult(SolanaJsonRpcClient.KEY_LONG_ARRAY_MAP_PARSER),
      applyGenericResponseValue(Supply::parse),
      applyGenericResponseResult(EpochInfo::parse),
      new JsonRestRpcResponseParser<>(NodeHealth::parse)
  );

  public static void fuzzerTestOneInput(final byte[] data) {
    if (data.length < 2) {
      return;
    }
    final var parser = PARSERS.get((data[0] & 0xFF) % PARSERS.size());
    final int statusCode = (data[1] & 1) == 0 ? 200 : 503;
    final byte[] body = Arrays.copyOfRange(data, 2, data.length);

    final var first = outcome(parser, statusCode, body);
    final var second = outcome(parser, statusCode, body);
    if (first != second) {
      throw new AssertionError("parsing is not deterministic: " + first + " then " + second
          + " for parser " + ((data[0] & 0xFF) % PARSERS.size()));
    }
  }

  /// The classification of one application: null for success, the exception class
  /// for a tolerated failure. Identity comparison is exactly what the invariant
  /// needs — same class object, not equal messages.
  private static Class<?> outcome(final Function<HttpResponse<?>, ?> parser,
                                  final int statusCode,
                                  final byte[] body) {
    try {
      parser.apply(StubHttpResponse.of(statusCode, body));
      return null;
    } catch (final RuntimeException tolerated) {
      return tolerated.getClass();
    }
  }

  private SolanaRpcResponseFuzz() {
  }
}
