package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.response.JsonRpcException;
import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.Function;

import static software.sava.rpc.json.http.client.JsonResponseController.logBody;

record JsonRpcResultParseController<R>(Function<JsonIterator, R> parser) implements Function<HttpResponse<byte[]>, R> {

  @Override
  public R apply(final HttpResponse<byte[]> httpResponse) {
    try {
      final var ji = JsonRpcHttpClient.createJsonIterator(httpResponse);
      return parser.apply(ji);
    } catch (final JsonRpcException rpcException) {
      throw rpcException;
    } catch (final RuntimeException ex) {
      logBody(httpResponse, ex);
      throw ex;
    }
  }
}
