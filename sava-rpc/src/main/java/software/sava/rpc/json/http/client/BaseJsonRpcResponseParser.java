package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;

abstract class BaseJsonRpcResponseParser<R> extends BaseJsonResponseParser<R> {

  @Override
  protected final JsonIterator checkResponse(final HttpResponse<?> httpResponse, final byte[] body) {
    return JsonRpcHttpClient.checkResponse(httpResponse, body);
  }
}
