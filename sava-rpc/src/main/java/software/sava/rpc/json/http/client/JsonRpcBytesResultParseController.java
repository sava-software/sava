package software.sava.rpc.json.http.client;

import systems.comodal.jsoniter.JsonIterator;

import java.net.http.HttpResponse;
import java.util.function.Function;

final class JsonRpcBytesResultParseController<R> extends BaseJsonRpcResponseController<byte[], R> {

  private final Function<JsonIterator, R> parser;

  JsonRpcBytesResultParseController(final Function<JsonIterator, R> parser) {
    this.parser = parser;
  }

  @Override
  protected final R parseResponse(final HttpResponse<byte[]> httpResponse, final byte[] body, final JsonIterator ji) {
    return parser.apply(ji);
  }
}
