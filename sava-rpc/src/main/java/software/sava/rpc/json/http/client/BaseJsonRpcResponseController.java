package software.sava.rpc.json.http.client;

import java.net.http.HttpResponse;
import java.util.function.Function;

abstract class BaseJsonRpcResponseController<B, R> extends BaseJsonRpcResponseParser<R> implements Function<HttpResponse<B>, R> {

  @Override
  public final R apply(final HttpResponse<B> httpResponse) {
    return super.applyResponse(httpResponse);
  }
}
