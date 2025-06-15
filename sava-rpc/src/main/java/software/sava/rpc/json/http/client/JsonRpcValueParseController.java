package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.response.Context;
import software.sava.rpc.json.http.response.JsonRpcException;
import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.BiFunction;
import java.util.function.Function;

import static software.sava.rpc.json.http.client.JsonResponseController.logBody;

record JsonRpcValueParseController<R>(
    BiFunction<JsonIterator, Context, R> parser) implements Function<HttpResponse<byte[]>, R> {

  @Override
  public R apply(final HttpResponse<byte[]> httpResponse) {
    try {
      // System.out.println(new String(httpResponse.body()));
      final var ji = JsonRpcHttpClient.createJsonIterator(httpResponse);
      ji.skipUntil("context");
      final var context = Context.parse(ji);
      ji.skipUntil("value");
      return parser.apply(ji, context);
    } catch (final JsonRpcException rpcException) {
      throw rpcException;
    } catch (final RuntimeException ex) {
      logBody(httpResponse, ex);
      throw ex;
    }
  }
}
