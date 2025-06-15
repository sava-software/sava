package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.response.Context;
import software.sava.rpc.json.http.response.JsonRpcException;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static software.sava.rpc.json.http.client.JsonResponseController.throwUncheckedIOException;

public abstract class JsonRpcHttpClient extends JsonHttpClient {

  public JsonRpcHttpClient(final URI endpoint,
                           final HttpClient httpClient,
                           final Duration requestTimeout,
                           final UnaryOperator<HttpRequest.Builder> extendRequest,
                           final Predicate<HttpResponse<byte[]>> applyResponse) {
    super(endpoint, httpClient, requestTimeout, extendRequest, applyResponse);
  }

  public JsonRpcHttpClient(final URI endpoint,
                           final HttpClient httpClient,
                           final Duration requestTimeout) {
    super(endpoint, httpClient, requestTimeout, null, null);
  }

  static JsonIterator createJsonIterator(final HttpResponse<byte[]> httpResponse) {
    // System.out.println(new String(httpResponse.body()));
    final var ji = JsonIterator.parse(httpResponse.body());
    final int responseCode = httpResponse.statusCode();
    final boolean isJsonObject = ji.whatIsNext() == ValueType.OBJECT;
    if (responseCode < 200 || responseCode >= 300 || !isJsonObject || ji.skipUntil("result") == null) {
      // System.out.println(new String(httpResponse.body()));
      if (!isJsonObject) {
        throw throwUncheckedIOException(httpResponse, new String(httpResponse.body()));
      } else if (ji.reset(0).skipUntil("error") == null) {
        throw throwUncheckedIOException(httpResponse, new String(httpResponse.body()));
      } else {
        final var retryAfter = httpResponse.headers().firstValueAsLong("retry-after");
        throw JsonRpcException.parseException(ji, retryAfter);
      }
    } else {
      return ji;
    }
  }

  protected static <R> Function<HttpResponse<byte[]>, R> applyResponseValue(final BiFunction<JsonIterator, Context, R> adapter) {
    return new JsonRpcValueParseController<>(adapter);
  }

  protected static <R> Function<HttpResponse<byte[]>, R> applyResponseResult(final Function<JsonIterator, R> adapter) {
    return new JsonRpcResultParseController<>(adapter);
  }

  protected static <R> Function<HttpResponse<byte[]>, R> applyResponseResult(final BiFunction<HttpResponse<byte[]>, JsonIterator, R> adapter) {
    return new JsonRpcResponseResultParseController<>(adapter);
  }
}
