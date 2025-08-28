package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;

@FunctionalInterface
public interface JsonRpcResponseParser<R> {

  R parseResponse(final HttpResponse<?> httpResponse, final byte[] body, final JsonIterator ji);
}
