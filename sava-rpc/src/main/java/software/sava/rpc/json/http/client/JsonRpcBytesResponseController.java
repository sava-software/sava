package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.BiFunction;

final class JsonRpcBytesResponseController<R> extends BaseJsonRpcResponseControllerParser<byte[], R> {

  JsonRpcBytesResponseController(final BiFunction<HttpResponse<byte[]>, JsonIterator, R> parser) {
    super(parser);
  }
}
