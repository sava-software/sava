package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.response.Context;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.*;

public abstract class JsonRpcHttpClient extends JsonHttpClient {

  public JsonRpcHttpClient(final URI endpoint,
                           final HttpClient httpClient,
                           final Duration requestTimeout,
                           final UnaryOperator<HttpRequest.Builder> extendRequest,
                           @Deprecated final Predicate<HttpResponse<byte[]>> applyResponse,
                           final BiPredicate<HttpResponse<?>, byte[]> testResponse) {
    super(endpoint, httpClient, requestTimeout, extendRequest, applyResponse, testResponse);
  }

  public JsonRpcHttpClient(final URI endpoint,
                           final HttpClient httpClient,
                           final Duration requestTimeout) {
    super(endpoint, httpClient, requestTimeout);
  }

  protected static <R> Function<HttpResponse<?>, R> applyGenericResponseValue(final BiFunction<JsonIterator, Context, R> adapter) {
    return new JsonRpcValueResponseParser<>(adapter);
  }

  protected static <R> Function<HttpResponse<?>, R> applyGenericResponseResult(final Function<JsonIterator, R> adapter) {
    return new JsonRpcResultResponseParser<>(adapter);
  }

  @Deprecated
  protected static <R> Function<HttpResponse<byte[]>, R> applyResponseValue(final BiFunction<JsonIterator, Context, R> adapter) {
    return new JsonRpcBytesValueParseController<>(adapter);
  }

  @Deprecated
  protected static <R> Function<HttpResponse<byte[]>, R> applyResponseResult(final Function<JsonIterator, R> adapter) {
    return new JsonRpcBytesResultParseController<>(adapter);
  }

  @Deprecated
  protected static <R> Function<HttpResponse<byte[]>, R> applyResponseResult(final BiFunction<HttpResponse<byte[]>, JsonIterator, R> adapter) {
    return new JsonRpcBytesResponseController<>(adapter);
  }
}
