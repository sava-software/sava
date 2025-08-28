package software.sava.rpc.json.http.client;

import software.sava.rpc.json.http.response.Context;
import systems.comodal.jsoniter.JsonIterator;

import java.util.function.BiFunction;

final class JsonRpcBytesValueParseController<R> extends BaseJsonRpcValueParseController<byte[], R> {

  JsonRpcBytesValueParseController(final BiFunction<JsonIterator, Context, R> parser) {
    super(parser);
  }
}
